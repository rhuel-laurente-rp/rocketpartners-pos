package com.rocketpartners.onboarding.posvirtualjournal;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Serialized printer for journal lines received by the server.
 *
 * <p>{@link #print(String, String)} prepends an ISO-8601 UTC receive-timestamp and the sending
 * peer's identity to the raw entry, and writes the result on a single {@link PrintStream#println}
 * call. Access is synchronized so that concurrent {@link ClientHandler}s never interleave
 * fragments of two entries on the same line.</p>
 */
public class JournalPrinter {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final PrintStream out;
    private final Clock clock;

    /** Production ctor: prints to {@link System#out} using the system UTC clock. */
    public JournalPrinter() {
        this(System.out, Clock.systemUTC());
    }

    /**
     * Test-facing ctor: inject the sink and clock so tests can capture output and pin timestamps.
     *
     * @param out   destination stream; must not be {@code null}
     * @param clock timestamp source; must not be {@code null}
     */
    public JournalPrinter(PrintStream out, Clock clock) {
        if (out == null) throw new IllegalArgumentException("out must not be null");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.out = out;
        this.clock = clock;
    }

    /**
     * Writes one journal entry, prefixed with a receive-timestamp and the peer identifier.
     * Serialized: concurrent callers block on the same monitor so a printed line is never
     * interleaved with another.
     *
     * @param peer human-readable peer identifier (typically {@code remoteAddress}); may be
     *             {@code null} → rendered as {@code ?}
     * @param line the raw entry as received from the wire; must not be {@code null}
     */
    public synchronized void print(String peer, String line) {
        if (line == null) throw new IllegalArgumentException("line must not be null");
        String ts = TS_FORMAT.format(Instant.now(clock));
        String tag = peer == null ? "?" : peer;
        out.println("[" + ts + "] " + tag + " " + line);
    }
}
