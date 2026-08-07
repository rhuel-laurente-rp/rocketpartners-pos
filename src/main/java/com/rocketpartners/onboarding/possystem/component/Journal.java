package com.rocketpartners.onboarding.possystem.component;

/**
 * A destination for journal entries. Concrete implementations:
 * <ul>
 *   <li>{@link LocalJournal} — always writes locally so there is a record even when the remote
 *       journal has never been reachable.</li>
 *   <li>{@link RemoteJournal} — enqueues entries and sends them over a socket on a background
 *       thread; drops rather than blocks when overwhelmed.</li>
 *   <li>{@link Journals} — a composite that fans an entry out to every wrapped journal.</li>
 * </ul>
 *
 * <p><strong>Contract for all implementations:</strong> {@link #journal(String)} must never
 * block the caller, must never throw, and must be safe to call from the Swing event dispatch
 * thread. A journal that could break checkout is worse than no journal at all.</p>
 */
public interface Journal {

    /**
     * Records one journal entry. Best-effort: implementations may drop entries under back-pressure
     * or when their transport is unavailable, but must not block or propagate exceptions.
     *
     * @param entry the pipe-delimited entry to record; may be {@code null} or blank
     *              (implementations should treat those as a no-op)
     */
    void journal(String entry);

    /**
     * Releases any resources held by the journal (background threads, sockets, file handles).
     * Idempotent. Any queued entries not yet flushed may be lost.
     */
    default void close() {}
}
