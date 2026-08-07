package com.rocketpartners.onboarding.possystem.component;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ships journal entries to a {@link com.rocketpartners.onboarding.posvirtualjournal.POSVirtualJournal}
 * server over a TCP socket, off the Swing EDT.
 *
 * <h2>Runtime shape</h2>
 * <ul>
 *   <li>A bounded {@link BlockingQueue} ({@link #QUEUE_CAPACITY} entries).</li>
 *   <li>A single daemon sender thread — one, not a pool, so entries ship in the exact order
 *       they were enqueued. A pool would interleave and produce a journal that lies about
 *       sequence.</li>
 *   <li>{@link #journal(String)} does a non-blocking {@link BlockingQueue#offer offer}: if the
 *       queue is full the entry is dropped and a counter is incremented. When the queue drains,
 *       the sender emits a single {@code JOURNAL_DROPPED n=…} line through the {@link LocalJournal}
 *       so the gap is visible. This never blocks the caller.</li>
 * </ul>
 *
 * <h2>Connection lifecycle</h2>
 * <ul>
 *   <li>Lazy connect on the first entry, with an explicit {@link #CONNECT_TIMEOUT_MS 2s}
 *       connect timeout.</li>
 *   <li>On write failure the socket is closed, the connection is marked lost, and the sender
 *       backs off before the next attempt: 1s, 2s, 4s, 8s, 16s, 30s cap. Reconnection is not
 *       attempted per entry — a dead journal would otherwise turn every keystroke into a
 *       connect() call.</li>
 *   <li>The pending entry is held aside across reconnects, so entries never get shipped out
 *       of order because an intermediate one lost its slot.</li>
 *   <li>Transitions are logged once through {@link LocalJournal}
 *       ({@code JOURNAL_CONNECTED} / {@code JOURNAL_UNREACHABLE} / {@code JOURNAL_DISCONNECTED}),
 *       never per attempt.</li>
 *   <li>No replay buffer, no disk spool: entries dropped while disconnected stay dropped.</li>
 * </ul>
 *
 * <h2>Sanitization</h2>
 * {@link #journal(String)} folds embedded {@code \n} and {@code \r} into spaces so a
 * description containing a newline cannot split into two entries and desynchronize the stream.
 * Entries longer than {@link #MAX_ENTRY_CHARS} are truncated with a {@link #TRUNCATION_MARKER}.
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
    private static final String POISON = "__POISON_PILL__";

    /**
     * Opens a TCP {@link Socket} to the given host/port with an explicit connect timeout.
     * Injected so tests can supply a mock without touching real sockets.
     */
    @FunctionalInterface
    public interface Connector {
        Socket connect(String host, int port, int timeoutMs) throws IOException;
    }

    /** Sleeps the current thread for {@code ms} milliseconds. Tests replace with a no-op. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final String host;
    private final int port;
    private final LocalJournal local;
    private final Connector connector;
    private final Sleeper sleeper;
    private final BlockingQueue<String> queue;
    private final Thread sender;

    private final AtomicLong droppedSinceLastReport = new AtomicLong();
    private volatile boolean running = true;

    /**
     * Production ctor: real {@link Socket} connect + {@link Thread#sleep} backoff.
     *
     * @param host  journal server hostname; must not be {@code null}
     * @param port  TCP port
     * @param local a {@link LocalJournal} used for transition and drop-count reports;
     *              must not be {@code null}
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
    public void journal(String entry) {
        if (entry == null || entry.isEmpty()) return;
        String sanitized = sanitize(entry);
        if (!queue.offer(sanitized)) {
            droppedSinceLastReport.incrementAndGet();
        }
    }

    /** Sanitizes a raw entry to a single-line, at-most-{@link #MAX_ENTRY_CHARS} string. */
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
        String pending = null; // held across reconnect attempts so order is preserved

        while (running) {
            if (pending == null) {
                try {
                    pending = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    break;
                }
                if (pending == null) continue;
                if (POISON.equals(pending)) break;
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
                        local.journal("JOURNAL_CONNECTED host=" + host + " port=" + port);
                    }
                } catch (IOException e) {
                    if (backoffIndex == 0) {
                        local.journal("JOURNAL_UNREACHABLE host=" + host + " port=" + port
                                + " reason=" + e.getClass().getSimpleName() + ":" + e.getMessage());
                    }
                    backoffIndex++;
                    socket = null;
                    writer = null;
                    // pending stays put; try again next iteration
                    continue;
                }
            }

            long dropped = droppedSinceLastReport.getAndSet(0);
            if (dropped > 0) {
                local.journal("JOURNAL_DROPPED n=" + dropped);
            }

            try {
                writer.write(pending);
                writer.write('\n');
                writer.flush();
                pending = null;
            } catch (IOException e) {
                local.journal("JOURNAL_DISCONNECTED reason=" + e.getClass().getSimpleName()
                        + ":" + e.getMessage());
                closeQuietly(socket);
                socket = null;
                writer = null;
                backoffIndex = 1;
                // pending stays put — reconnect and retry
                // Put drop count back since we didn't successfully report it.
                if (dropped > 0) droppedSinceLastReport.addAndGet(dropped);
            }
        }

        closeQuietly(socket);
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
