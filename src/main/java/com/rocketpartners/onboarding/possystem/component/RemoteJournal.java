package com.rocketpartners.onboarding.possystem.component;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ships journal records to a {@link com.rocketpartners.onboarding.posvirtualjournal.POSVirtualJournal}
 * server over a TCP socket, off the Swing EDT.
 *
 * <h2>Runtime shape</h2>
 * <ul>
 *   <li>A bounded {@link BlockingQueue} ({@link #QUEUE_CAPACITY} records).</li>
 *   <li>A single daemon sender thread — one, not a pool, so entries ship in the exact order
 *       they were enqueued.</li>
 *   <li>{@link #journal(JournalRecord)} does a non-blocking {@link BlockingQueue#offer offer};
 *       when the queue is full the record is dropped and a counter is incremented. On recovery
 *       the sender emits one {@code JOURNAL_DROPPED n=…} record through the {@link LocalJournal}
 *       so the gap is visible. This never blocks the caller.</li>
 * </ul>
 *
 * <h2>Wire format</h2>
 * Each record is serialized via {@link JournalRecord#toPipeDelimited()} and sent as one
 * UTF-8 line. Records longer than {@link #MAX_ENTRY_CHARS} are truncated with
 * {@link #TRUNCATION_MARKER}.
 *
 * <h2>Connection lifecycle</h2>
 * <ul>
 *   <li>Lazy connect on the first entry, with an explicit {@link #CONNECT_TIMEOUT_MS 2s}
 *       connect timeout.</li>
 *   <li>On write failure the socket is closed, the connection is marked lost, and the sender
 *       backs off before the next attempt: 1s, 2s, 4s, 8s, 16s, 30s cap.</li>
 *   <li>The pending record is held across reconnect attempts so ordering is preserved even
 *       through outages.</li>
 *   <li>Transitions are logged once through {@link LocalJournal}
 *       ({@code JOURNAL_CONNECTED} / {@code JOURNAL_UNREACHABLE} / {@code JOURNAL_DISCONNECTED}).</li>
 *   <li>No replay buffer, no disk spool: records dropped while disconnected stay dropped.</li>
 * </ul>
 */
public class RemoteJournal implements Journal {

    /** Bounded queue capacity — sized so it absorbs a burst without pinning the JVM. */
    public static final int QUEUE_CAPACITY = 1000;

    /** Connect timeout in ms. */
    public static final int CONNECT_TIMEOUT_MS = 2_000;

    /** Backoff schedule between reconnect attempts, in ms; the last entry is the cap. */
    public static final long[] BACKOFF_SCHEDULE_MS = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L};

    /** Maximum on-the-wire entry length. Longer entries are truncated. */
    public static final int MAX_ENTRY_CHARS = 4096;

    /** Suffix appended when {@link #MAX_ENTRY_CHARS} truncates. */
    static final String TRUNCATION_MARKER = "…TRUNCATED";

    /** Poison-pill sentinel enqueued by {@link #close()} to signal the sender to exit. */
    private static final JournalRecord POISON =
            new JournalRecord(Instant.EPOCH, "?", 0, "-", "__POISON_PILL__", new LinkedHashMap<>());

    /**
     * Opens a TCP {@link Socket} to the given host/port with an explicit connect timeout.
     */
    @FunctionalInterface
    public interface Connector {
        Socket connect(String host, int port, int timeoutMs) throws IOException;
    }

    /** Sleeps the current thread for {@code ms} milliseconds. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /**
     * Coarse-grained connection state exposed to observers (typically a status indicator in
     * the UI). The {@link #DISCONNECTED} state covers both "never connected" and "lost the
     * connection" — callers rendering it should say things like "journal offline" and rely on
     * the connect/disconnect transitions rather than trying to distinguish sub-states.
     */
    public enum ConnectionState {
        /** Not currently connected — either never was, or lost the socket. */
        DISCONNECTED,
        /** Socket is open and the last write succeeded. */
        CONNECTED
    }

    /** Notified whenever {@link RemoteJournal} transitions between {@link ConnectionState}s. */
    @FunctionalInterface
    public interface ConnectionListener {
        void onStateChanged(ConnectionState state);
    }

    private final String host;
    private final int port;
    private final LocalJournal local;
    private final Connector connector;
    private final Sleeper sleeper;
    private final BlockingQueue<JournalRecord> queue;
    private final Thread sender;

    private final AtomicLong droppedSinceLastReport = new AtomicLong();
    private volatile boolean running = true;
    private volatile ConnectionState currentState = ConnectionState.DISCONNECTED;
    private volatile ConnectionListener connectionListener;

    /**
     * Production ctor: real {@link Socket} connect + {@link Thread#sleep} backoff.
     */
    public RemoteJournal(String host, int port, LocalJournal local) {
        this(host, port, local, RemoteJournal::defaultConnect, Thread::sleep, QUEUE_CAPACITY);
    }

    /** Test-facing ctor: inject the connector, sleeper, and queue size. */
    RemoteJournal(String host, int port, LocalJournal local,
                  Connector connector, Sleeper sleeper, int queueCapacity) {
        if (host == null) throw new IllegalArgumentException("host must not be null");
        if (local == null) throw new IllegalArgumentException("local must not be null");
        if (connector == null) throw new IllegalArgumentException("connector must not be null");
        if (sleeper == null) throw new IllegalArgumentException("sleeper must not be null");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        this.host = host;
        this.port = port;
        this.local = local;
        this.connector = connector;
        this.sleeper = sleeper;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.sender = new Thread(this::runSender, "remote-journal-sender");
        this.sender.setDaemon(true);
        this.sender.start();
    }

    private static Socket defaultConnect(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        return s;
    }

    @Override
    public void journal(JournalRecord record) {
        if (record == null) return;
        if (!queue.offer(record)) {
            droppedSinceLastReport.incrementAndGet();
        }
    }

    /**
     * Registers a listener that will be notified whenever the underlying socket transitions
     * between {@link ConnectionState#CONNECTED} and {@link ConnectionState#DISCONNECTED}.
     * The listener is invoked on the sender thread — implementations that touch Swing must
     * marshal onto the EDT themselves. Setting to {@code null} unsubscribes.
     *
     * <p>On registration the listener is immediately invoked with the current state, so the
     * UI can paint the correct pill even if it subscribes after a transition has already
     * happened.</p>
     */
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
        if (listener != null) {
            try {
                listener.onStateChanged(currentState);
            } catch (RuntimeException e) {
                System.err.println("[journal] connection listener threw on register: "
                        + e.getMessage());
            }
        }
    }

    /** @return the last state the sender observed */
    public ConnectionState getConnectionState() {
        return currentState;
    }

    private void setState(ConnectionState newState) {
        if (currentState == newState) return;
        currentState = newState;
        ConnectionListener l = connectionListener;
        if (l == null) return;
        try {
            l.onStateChanged(newState);
        } catch (RuntimeException e) {
            System.err.println("[journal] connection listener threw on notify: " + e.getMessage());
        }
    }

    /** Sanitizes a rendered line: folds newlines and truncates past {@link #MAX_ENTRY_CHARS}. */
    static String sanitize(String raw) {
        String flat = raw.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= MAX_ENTRY_CHARS) return flat;
        int keep = MAX_ENTRY_CHARS - TRUNCATION_MARKER.length();
        if (keep < 0) keep = 0;
        return flat.substring(0, keep) + TRUNCATION_MARKER;
    }

    @Override
    public void close() {
        if (!running) return;
        running = false;
        queue.offer(POISON);
        sender.interrupt();
        try {
            sender.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Sender thread -----------------------------------------------------

    private void runSender() {
        Socket socket = null;
        Writer writer = null;
        int backoffIndex = 0;
        boolean everConnected = false;
        JournalRecord pending = null;

        while (running) {
            if (pending == null) {
                try {
                    pending = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    break;
                }
                if (pending == null) continue;
                if (pending == POISON) break;
            }

            if (socket == null || writer == null) {
                if (backoffIndex > 0) {
                    long delay = BACKOFF_SCHEDULE_MS[Math.min(backoffIndex - 1,
                            BACKOFF_SCHEDULE_MS.length - 1)];
                    try {
                        sleeper.sleep(delay);
                    } catch (InterruptedException ie) {
                        break;
                    }
                    if (!running) break;
                }
                try {
                    socket = connector.connect(host, port, CONNECT_TIMEOUT_MS);
                    writer = new OutputStreamWriter(socket.getOutputStream(),
                            StandardCharsets.UTF_8);
                    boolean firstEver = !everConnected;
                    boolean afterOutage = backoffIndex > 0;
                    everConnected = true;
                    backoffIndex = 0;
                    if (firstEver || afterOutage) {
                        local.journal(system("JOURNAL_CONNECTED", "host", host, "port", port));
                    }
                    setState(ConnectionState.CONNECTED);
                } catch (IOException e) {
                    if (backoffIndex == 0) {
                        local.journal(system("JOURNAL_UNREACHABLE",
                                "host", host, "port", port,
                                "reason", e.getClass().getSimpleName() + ":" + e.getMessage()));
                    }
                    backoffIndex++;
                    socket = null;
                    writer = null;
                    setState(ConnectionState.DISCONNECTED);
                    continue;
                }
            }

            long dropped = droppedSinceLastReport.getAndSet(0);
            if (dropped > 0) {
                local.journal(system("JOURNAL_DROPPED", "n", dropped));
            }

            try {
                writer.write(sanitize(pending.toPipeDelimited()));
                writer.write('\n');
                writer.flush();
                pending = null;
            } catch (IOException e) {
                local.journal(system("JOURNAL_DISCONNECTED",
                        "reason", e.getClass().getSimpleName() + ":" + e.getMessage()));
                closeQuietly(socket);
                socket = null;
                writer = null;
                backoffIndex = 1;
                setState(ConnectionState.DISCONNECTED);
                if (dropped > 0) droppedSinceLastReport.addAndGet(dropped);
            }
        }

        closeQuietly(socket);
    }

    /** Builds a system-labeled JournalRecord for connection-state transitions. */
    private static JournalRecord system(String event, Object... kv) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            fields.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new JournalRecord(Instant.now(), "?", 0, "-", event, fields);
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException | RuntimeException ignored) {
            // Best-effort.
        }
    }

    // ---- Package-private test helpers --------------------------------------

    int queueSize() {
        return queue.size();
    }

    long droppedCount() {
        return droppedSinceLastReport.get();
    }

    boolean isSenderAlive() {
        return sender.isAlive();
    }
}
