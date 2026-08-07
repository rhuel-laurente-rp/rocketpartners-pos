package com.rocketpartners.onboarding.posvirtualjournal;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The virtual journal server: owns a {@link ServerSocket}, an accept loop, and a bounded
 * executor that hands each accepted connection to a {@link ClientHandler}.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li><strong>Bounded pool.</strong> A {@link ThreadPoolExecutor} with a hard maximum of
 *       {@link #MAX_CLIENTS} threads and a {@link SynchronousQueue} handoff. Reaching the cap
 *       throws {@link RejectedExecutionException} at submit time; we log the refusal, close
 *       the accepted socket, and go back to accepting — one lane's misbehavior can't monopolize
 *       the server. Unbounded thread-per-client is exactly the resource-exhaustion bug the
 *       spec calls out.</li>
 *   <li><strong>Idle timeout.</strong> Each accepted socket has its {@code soTimeout} set so a
 *       client that opens the connection and sends nothing does not pin a handler thread
 *       forever.</li>
 *   <li><strong>Serialized printing.</strong> All handlers share one {@link JournalPrinter};
 *       {@link JournalPrinter#print(String, String)} is {@code synchronized}, so concurrent
 *       clients cannot interleave fragments of the same printed line.</li>
 *   <li><strong>Shutdown.</strong> {@link #stop()} is idempotent and safe to call from a JVM
 *       shutdown hook; it closes the server socket (breaking the accept loop) and drains the
 *       executor within a bounded window.</li>
 * </ul>
 */
public class POSVirtualJournal {

    /** Hard cap on concurrent connected clients. 16 is generous for a two-lane demo. */
    public static final int MAX_CLIENTS = 16;

    /** SO_TIMEOUT applied to each accepted socket, in milliseconds. */
    public static final int READ_TIMEOUT_MS = 30_000;

    /** Bounded window {@link #stop()} waits for the accept loop and handlers to drain. */
    public static final long SHUTDOWN_TIMEOUT_MS = 5_000L;

    private final int requestedPort;
    private final JournalPrinter printer;
    private final int soTimeoutMs;

    private ServerSocket serverSocket;
    private ThreadPoolExecutor executor;
    private Thread acceptThread;
    private volatile boolean running;

    /**
     * @param port    TCP port to bind; {@code 0} to pick an ephemeral port (useful in tests)
     * @param printer the shared printer; must not be {@code null}
     */
    public POSVirtualJournal(int port, JournalPrinter printer) {
        this(port, printer, READ_TIMEOUT_MS);
    }

    /**
     * As {@link #POSVirtualJournal(int, JournalPrinter)}, but with a custom per-connection
     * read timeout. Kept public because tests need short timeouts to exercise the idle path
     * without a 30-second wait.
     */
    public POSVirtualJournal(int port, JournalPrinter printer, int soTimeoutMs) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        if (printer == null) throw new IllegalArgumentException("printer must not be null");
        if (soTimeoutMs < 0) throw new IllegalArgumentException("soTimeoutMs must be >= 0");
        this.requestedPort = port;
        this.printer = printer;
        this.soTimeoutMs = soTimeoutMs;
    }

    /**
     * Binds the server socket and starts the accept loop on a daemon thread. Non-blocking.
     *
     * @throws IOException if the port cannot be bound (rethrown for {@link Driver} to handle)
     */
    public synchronized void start() throws IOException {
        if (running) return;
        this.serverSocket = new ServerSocket(requestedPort);
        this.executor = new ThreadPoolExecutor(
                0, MAX_CLIENTS,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new NumberedThreadFactory("journal-handler"));
        this.running = true;
        this.acceptThread = new Thread(this::acceptLoop, "journal-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    /**
     * Shuts down the server: closes the socket, interrupts handlers, and waits up to
     * {@link #SHUTDOWN_TIMEOUT_MS} for the executor to drain. Idempotent.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // Nothing to do — the accept loop treats close() as the exit signal.
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(SHUTDOWN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return the port the server is actually listening on (post-bind), or -1 if not started */
    public int getBoundPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    /** @return {@code true} while the accept loop is running */
    public boolean isRunning() {
        return running;
    }

    // ---- internals ---------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (SocketException se) {
                // ServerSocket closed → exit.
                return;
            } catch (IOException ioe) {
                if (!running) return;
                System.err.println("[journal] accept failed: " + ioe.getMessage());
                continue;
            }

            try {
                client.setSoTimeout(soTimeoutMs);
            } catch (SocketException se) {
                System.err.println("[journal] could not set soTimeout on "
                        + client.getRemoteSocketAddress() + ": " + se.getMessage());
                closeQuietly(client);
                continue;
            }

            ClientHandler handler = new ClientHandler(client, printer);
            try {
                executor.execute(handler);
            } catch (RejectedExecutionException ree) {
                System.err.println("[journal] refusing connection from "
                        + client.getRemoteSocketAddress()
                        + " — at capacity (" + MAX_CLIENTS + ")");
                closeQuietly(client);
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    /** Daemon thread factory that gives each handler a numbered, identifiable name. */
    private static final class NumberedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger n = new AtomicInteger(1);

        NumberedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
