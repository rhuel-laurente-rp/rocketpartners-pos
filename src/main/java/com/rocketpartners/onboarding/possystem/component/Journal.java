package com.rocketpartners.onboarding.possystem.component;

/**
 * A destination for journal records. Concrete implementations:
 * <ul>
 *   <li>{@link LocalJournal} — pipe-delimited to stdout; unconditional local record.</li>
 *   <li>{@link FileJournal} — one JSON object per line, appended to a daily file under {@code logs/}.</li>
 *   <li>{@link RemoteJournal} — pipe-delimited over a TCP socket to the virtual journal server.</li>
 *   <li>{@link Journals} — a composite that fans one record out to every wrapped journal.</li>
 * </ul>
 *
 * <p><strong>Contract for all implementations:</strong> {@link #journal(JournalRecord)} must
 * never block the caller, must never throw, and must be safe to call from the Swing event
 * dispatch thread. A journal that could break checkout is worse than no journal at all.</p>
 */
public interface Journal {

    /**
     * Records one journal entry. Best-effort: implementations may drop entries under
     * back-pressure or when their transport is unavailable, but must not block or propagate
     * exceptions.
     *
     * @param record the structured entry; may be {@code null} (implementations should treat
     *               that as a no-op)
     */
    void journal(JournalRecord record);

    /**
     * Releases any resources held by the journal (background threads, sockets, file handles).
     * Idempotent. Any queued entries not yet flushed may be lost.
     */
    default void close() {}
}
