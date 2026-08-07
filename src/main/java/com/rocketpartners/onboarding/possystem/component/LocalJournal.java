package com.rocketpartners.onboarding.possystem.component;

import java.io.PrintStream;

/**
 * A {@link Journal} that writes each record as pipe-delimited text to a {@link PrintStream}
 * (stdout by default). Unconditionally-available: no network, no queue, no failure paths. Kept
 * as its own class so the POS always has a record even when the remote journal has never been
 * reachable.
 */
public class LocalJournal implements Journal {

    private final PrintStream out;

    /** Prints to {@link System#out}. */
    public LocalJournal() {
        this(System.out);
    }

    /**
     * @param out the destination; must not be {@code null}
     */
    public LocalJournal(PrintStream out) {
        if (out == null) throw new IllegalArgumentException("out must not be null");
        this.out = out;
    }

    @Override
    public void journal(JournalRecord record) {
        if (record == null) return;
        // println is synchronized on PrintStream, so concurrent callers cannot interleave.
        out.println(record.toPipeDelimited());
    }
}
