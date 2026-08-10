package com.rocketpartners.onboarding.possystem.component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Detects scanner bursts amidst all keyboard input the POS sees.
 *
 * <p>A scanner is just a fast keyboard; it types a barcode's characters and a terminator
 * (typically Enter) into whatever component has focus. This class is what lets the POS tell a
 * scanner burst apart from a human typing into the scan field: it accepts every keystroke —
 * with a timestamp — and returns the completed barcode only when it sees a terminator after a
 * fast-enough burst of characters. Nothing else.</p>
 *
 * <p>The whole point of putting this in {@code component} rather than {@code display} is that
 * it has no Swing dependency. Tests feed it {@code (char, timestamp)} pairs directly with
 * synthetic timings, without a live event queue.</p>
 *
 * <p>Three configurable knobs:</p>
 * <ul>
 *   <li>{@code burstGapMs} — inter-character gap classifying input as a scanner burst. Chars
 *       arriving beyond this gap are discarded rather than accumulated; humans typing at
 *       normal speed never produce a completed burst. Default 50 ms.</li>
 *   <li>{@code staleTimeoutMs} — silence after which any pending buffer is discarded. Protects
 *       against the classic wedge bug: a bad-read half-scan prepending itself to the next
 *       barcode. Default 200 ms.</li>
 *   <li>{@code prefix} — optional single character stripped when it arrives as the first char
 *       of a burst. Some scanners are configured to prepend a symbology marker (e.g. {@code
 *       '~'}). Use {@code 0} for "no prefix".</li>
 * </ul>
 *
 * <p>The buffer only decides whether a burst was a scanner burst; it does NOT validate the
 * result. Digit-only / length checks live in the caller — see
 * {@link Barcodes#isValidUpc(String)}. That split keeps this class about timing and the
 * caller about domain rules.</p>
 */
public final class BarcodeInputBuffer {

    /** Default inter-character gap tolerated inside a scanner burst. */
    public static final long DEFAULT_BURST_GAP_MS = 50L;

    /** Default silence timeout after which a stale buffer is discarded. */
    public static final long DEFAULT_STALE_TIMEOUT_MS = 200L;

    /** Standard Enter terminator. Scanners are typically shipped configured this way. */
    public static final char TERMINATOR_ENTER = '\n';

    /** Carriage return terminator. Some scanners send {@code \r} either alone or as CR+LF. */
    public static final char TERMINATOR_CR = '\r';

    /** Standard Tab terminator. Some scanners are configured this way. */
    public static final char TERMINATOR_TAB = '\t';

    /** Sentinel value for {@link #prefix} meaning "no prefix configured". */
    public static final char NO_PREFIX = '\0';

    private final long burstGapMs;
    private final long staleTimeoutMs;
    private final char prefix;
    private final Set<Character> terminators;

    private final StringBuilder buffer = new StringBuilder();
    private long lastCharTs = Long.MIN_VALUE;

    /**
     * Timestamp of the terminator that most recently completed a burst; {@link Long#MIN_VALUE}
     * when no burst has been emitted yet or the buffer has since been reset. Used to swallow a
     * CR+LF pair as a single terminator — after CR completes a burst, an LF arriving within the
     * burst-gap of that CR is dropped rather than counted as an empty submit.
     */
    private long lastEmittedTerminatorTs = Long.MIN_VALUE;

    /**
     * Inter-character gap accumulator for the burst that is currently being built. Reset every
     * time the buffer is cleared (stale timeout, gap overrun, reset, emit). Read by the
     * controller when a burst completes so it can log a calibration summary in debug mode.
     */
    private int burstCharCount = 0;
    private long burstMinGap = Long.MAX_VALUE;
    private long burstMaxGap = Long.MIN_VALUE;
    private long burstTotalGap = 0L;
    private int burstGapCount = 0;

    private BurstStats lastCompletedBurstStats;

    /**
     * Builds a buffer with default timings ({@value #DEFAULT_BURST_GAP_MS} ms burst gap,
     * {@value #DEFAULT_STALE_TIMEOUT_MS} ms stale timeout), no prefix, Enter and Tab as
     * terminators.
     */
    public BarcodeInputBuffer() {
        this(DEFAULT_BURST_GAP_MS, DEFAULT_STALE_TIMEOUT_MS, NO_PREFIX,
                defaultTerminators());
    }

    /**
     * @param burstGapMs     max inter-char gap (ms) inside a burst; chars arriving beyond this
     *                       reset the buffer rather than accumulating
     * @param staleTimeoutMs silence (ms) after which the pending buffer is discarded
     * @param prefix         optional first-char prefix to strip, or {@link #NO_PREFIX}
     * @param terminators    characters that complete a barcode; must contain at least one
     */
    public BarcodeInputBuffer(long burstGapMs, long staleTimeoutMs, char prefix,
                              Set<Character> terminators) {
        if (burstGapMs < 0) throw new IllegalArgumentException("burstGapMs must be >= 0");
        if (staleTimeoutMs < 0) throw new IllegalArgumentException("staleTimeoutMs must be >= 0");
        if (terminators == null || terminators.isEmpty()) {
            throw new IllegalArgumentException("terminators must not be empty");
        }
        this.burstGapMs = burstGapMs;
        this.staleTimeoutMs = staleTimeoutMs;
        this.prefix = prefix;
        this.terminators = Collections.unmodifiableSet(new HashSet<>(terminators));
    }

    private static Set<Character> defaultTerminators() {
        Set<Character> t = new HashSet<>();
        t.add(TERMINATOR_ENTER);
        t.add(TERMINATOR_CR);
        t.add(TERMINATOR_TAB);
        return t;
    }

    /**
     * Accepts a single keystroke arriving at {@code tsMillis} and returns the completed
     * barcode when the terminator arrives at scanner speed; otherwise returns an empty
     * {@link Optional}.
     *
     * <p>Precondition: {@code tsMillis} is monotonically non-decreasing across calls (i.e.,
     * event time). No cross-call absolute clock is required — synthetic timestamps work.</p>
     *
     * @param c        the character (may be a terminator; may be the configured prefix)
     * @param tsMillis wall-clock or event-time in milliseconds
     * @return the completed barcode (never {@code null}) on a terminator that closes a valid
     *         scanner burst; otherwise empty
     */
    public Optional<String> accept(char c, long tsMillis) {
        // Stale timeout: a burst that stalled silently is dropped BEFORE we consider `c`.
        if (buffer.length() > 0 && lastCharTs != Long.MIN_VALUE
                && (tsMillis - lastCharTs) > staleTimeoutMs) {
            buffer.setLength(0);
            resetBurstStats();
        }

        if (terminators.contains(c)) {
            // CR+LF suppression: some scanners send both terminators back-to-back. If we just
            // emitted a burst on a CR and an LF (or another terminator) follows within the
            // burst gap, treat the pair as one terminator rather than one scan plus an empty
            // submit. Anything arriving later than the gap is a genuine standalone terminator.
            if (lastEmittedTerminatorTs != Long.MIN_VALUE
                    && (tsMillis - lastEmittedTerminatorTs) <= burstGapMs) {
                lastCharTs = tsMillis;
                lastEmittedTerminatorTs = tsMillis;
                return Optional.empty();
            }
            if (buffer.length() == 0) {
                lastCharTs = tsMillis;
                lastEmittedTerminatorTs = Long.MIN_VALUE;
                return Optional.empty();
            }
            String result = buffer.toString();
            buffer.setLength(0);
            lastCharTs = tsMillis;
            lastEmittedTerminatorTs = tsMillis;
            lastCompletedBurstStats = snapshotBurstStats();
            resetBurstStats();
            return Optional.of(result);
        }

        // Non-terminator: check the gap. If we're beyond the scanner-burst threshold, drop
        // whatever's in the buffer — this is either the start of a new burst (accept), or
        // human typing (which will never terminate a valid burst).
        boolean burstGapExceeded = buffer.length() > 0 && lastCharTs != Long.MIN_VALUE
                && (tsMillis - lastCharTs) > burstGapMs;
        if (burstGapExceeded) {
            buffer.setLength(0);
            resetBurstStats();
        }

        // Record the inter-character gap for calibration BEFORE we append. Only meaningful
        // when this char is being accumulated (a prefix will be stripped below but still
        // counts as an inbound keystroke; skip it for stats to keep the numbers about the
        // barcode payload only).
        if (buffer.length() > 0 && lastCharTs != Long.MIN_VALUE) {
            long gap = tsMillis - lastCharTs;
            if (gap < burstMinGap) burstMinGap = gap;
            if (gap > burstMaxGap) burstMaxGap = gap;
            burstTotalGap += gap;
            burstGapCount++;
        }

        // A configured prefix that arrives as the first character of a burst is silently
        // stripped. Any later occurrence is treated as a normal char (it would fail digit
        // validation anyway, but that's the caller's job).
        boolean isPrefix = prefix != NO_PREFIX && c == prefix && buffer.length() == 0;
        if (!isPrefix) {
            buffer.append(c);
            burstCharCount++;
        }
        lastCharTs = tsMillis;
        // A new burst is starting; a subsequent terminator opens a fresh CR+LF window from
        // the terminator itself, not from any prior emission.
        lastEmittedTerminatorTs = Long.MIN_VALUE;
        return Optional.empty();
    }

    /**
     * Clears the buffer without emitting anything. Called by the controller when the
     * transaction transitions to a state where scans are not accepted, or when a modal dialog
     * suspends scan capture.
     */
    public void reset() {
        buffer.setLength(0);
        lastCharTs = Long.MIN_VALUE;
        lastEmittedTerminatorTs = Long.MIN_VALUE;
        resetBurstStats();
    }

    /**
     * @return the calibration snapshot for the most recently completed burst, or empty if no
     *         burst has completed since construction / {@link #reset()}. Used by the controller
     *         when {@code --debug} is on to log per-scan gap statistics for tuning
     *         {@code --scan-burst-gap-ms} to actual hardware.
     */
    public Optional<BurstStats> pollLastBurstStats() {
        BurstStats out = lastCompletedBurstStats;
        lastCompletedBurstStats = null;
        return Optional.ofNullable(out);
    }

    private BurstStats snapshotBurstStats() {
        long minGap = burstGapCount == 0 ? 0L : burstMinGap;
        long maxGap = burstGapCount == 0 ? 0L : burstMaxGap;
        double mean = burstGapCount == 0 ? 0d : ((double) burstTotalGap) / burstGapCount;
        return new BurstStats(burstCharCount, burstGapCount, minGap, maxGap, mean);
    }

    private void resetBurstStats() {
        burstCharCount = 0;
        burstMinGap = Long.MAX_VALUE;
        burstMaxGap = Long.MIN_VALUE;
        burstTotalGap = 0L;
        burstGapCount = 0;
    }

    /** Inter-character gap summary for a completed burst; times in milliseconds. */
    public static final class BurstStats {
        private final int charCount;
        private final int gapCount;
        private final long minGapMs;
        private final long maxGapMs;
        private final double meanGapMs;

        BurstStats(int charCount, int gapCount, long minGapMs, long maxGapMs, double meanGapMs) {
            this.charCount = charCount;
            this.gapCount = gapCount;
            this.minGapMs = minGapMs;
            this.maxGapMs = maxGapMs;
            this.meanGapMs = meanGapMs;
        }

        public int getCharCount() { return charCount; }
        public int getGapCount() { return gapCount; }
        public long getMinGapMs() { return minGapMs; }
        public long getMaxGapMs() { return maxGapMs; }
        public double getMeanGapMs() { return meanGapMs; }
    }

    /**
     * @return the number of characters currently accumulated in the buffer (0 when idle or
     *         after emitting a completed barcode)
     */
    public int pendingLength() {
        return buffer.length();
    }

    /** @return the burst gap threshold in milliseconds */
    public long getBurstGapMs() {
        return burstGapMs;
    }

    /** @return the stale-timeout threshold in milliseconds */
    public long getStaleTimeoutMs() {
        return staleTimeoutMs;
    }

    /** @return the configured prefix character, or {@link #NO_PREFIX} */
    public char getPrefix() {
        return prefix;
    }

    /** @return the set of terminator characters (unmodifiable) */
    public Set<Character> getTerminators() {
        return terminators;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BarcodeInputBuffer b)) return false;
        return burstGapMs == b.burstGapMs
                && staleTimeoutMs == b.staleTimeoutMs
                && prefix == b.prefix
                && terminators.equals(b.terminators);
    }

    @Override
    public int hashCode() {
        return Objects.hash(burstGapMs, staleTimeoutMs, prefix, terminators);
    }
}
