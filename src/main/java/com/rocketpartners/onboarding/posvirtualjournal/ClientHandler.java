package com.rocketpartners.onboarding.posvirtualjournal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Reads newline-delimited UTF-8 journal entries from one {@link Socket} and forwards them to
 * a shared {@link JournalPrinter}.
 *
 * <p>The handler's job is to survive any misbehavior of a single client without disturbing the
 * server or its peer connections. Concretely:</p>
 * <ul>
 *   <li>An abrupt disconnect ({@link SocketException} or {@code null} from
 *       {@link BufferedReader#readLine}) closes the socket and returns cleanly.</li>
 *   <li>An idle client is bounded by the socket's {@code SO_TIMEOUT}: on
 *       {@link SocketTimeoutException} the handler logs a warning and closes.</li>
 *   <li>An oversized line (longer than {@link #MAX_ENTRY_CHARS}) is discarded with a
 *       warning; reading continues from the next line, so a garbage burst does not kill the
 *       stream.</li>
 *   <li>Any other {@link IOException} is logged and the handler returns.</li>
 * </ul>
 *
 * <p>The reader is built with an explicit {@link StandardCharsets#UTF_8} to avoid platform-default
 * charset surprises when the server runs in a Linux container against a macOS POS.</p>
 */
public class ClientHandler implements Runnable {

    /** Maximum entry length the server will accept. Longer lines are dropped with a warning. */
    public static final int MAX_ENTRY_CHARS = 4096;

    private final Socket socket;
    private final JournalPrinter printer;
    private final String peer;

    /**
     * @param socket  an accepted connection; must not be {@code null} and should already have
     *                had a soTimeout applied by the caller
     * @param printer the shared printer; must not be {@code null}
     */
    public ClientHandler(Socket socket, JournalPrinter printer) {
        if (socket == null) throw new IllegalArgumentException("socket must not be null");
        if (printer == null) throw new IllegalArgumentException("printer must not be null");
        this.socket = socket;
        this.printer = printer;
        this.peer = describe(socket);
    }

    @Override
    public void run() {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            readLoop(in);
        } catch (IOException e) {
            System.err.println("[journal] handler " + peer + " I/O error: " + e.getMessage());
        }
    }

    private void readLoop(BufferedReader in) {
        while (true) {
            String line;
            try {
                line = in.readLine();
            } catch (SocketTimeoutException ste) {
                System.err.println("[journal] handler " + peer + " idle timeout — closing");
                return;
            } catch (SocketException se) {
                // Common: client killed mid-write or gone away. Log briefly and exit.
                System.err.println("[journal] handler " + peer + " disconnected: " + se.getMessage());
                return;
            } catch (IOException ioe) {
                System.err.println("[journal] handler " + peer + " read error: " + ioe.getMessage());
                return;
            }
            if (line == null) {
                // Peer closed the stream cleanly.
                return;
            }
            if (line.length() > MAX_ENTRY_CHARS) {
                System.err.println("[journal] handler " + peer + " oversized line ("
                        + line.length() + " chars) discarded");
                continue;
            }
            try {
                printer.print(peer, line);
            } catch (RuntimeException e) {
                System.err.println("[journal] handler " + peer + " print failed: " + e.getMessage());
            }
        }
    }

    private static String describe(Socket s) {
        try {
            return s.getRemoteSocketAddress() == null ? "?" : s.getRemoteSocketAddress().toString();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
