package com.rocketpartners.onboarding.possystem.component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A composite {@link Journal} that fans one record out to every wrapped journal. Used to send
 * every entry to {@link LocalJournal} (unconditional stdout), {@link FileJournal} (JSONL on
 * disk), and {@link RemoteJournal} (best-effort socket) at once.
 *
 * <p>Each delegate's {@link Journal#journal(JournalRecord)} is invoked in order; a
 * RuntimeException from one delegate is caught and logged so it does not stop the others. This
 * should never happen — the {@link Journal} contract forbids throwing — but the wrapper is the
 * last line of defense between the journal system and the Swing EDT.</p>
 */
public class Journals implements Journal {

    private final List<Journal> delegates;

    public Journals(Journal... delegates) {
        if (delegates == null) throw new IllegalArgumentException("delegates must not be null");
        this.delegates = new ArrayList<>(Arrays.asList(delegates));
        for (Journal j : this.delegates) {
            if (j == null) throw new IllegalArgumentException("delegate must not be null");
        }
    }

    @Override
    public void journal(JournalRecord record) {
        for (Journal j : delegates) {
            try {
                j.journal(record);
            } catch (RuntimeException e) {
                System.err.println("[journal] delegate " + j.getClass().getSimpleName()
                        + " threw: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        for (Journal j : delegates) {
            try {
                j.close();
            } catch (RuntimeException ignored) {
                // Best-effort; a shutdown may already have torn things down.
            }
        }
    }
}
