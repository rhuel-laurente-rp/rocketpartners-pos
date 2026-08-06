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
        }

        if (terminators.contains(c)) {
            if (buffer.length() == 0) {
                lastCharTs = tsMillis;
                return Optional.empty();
            }
            String result = buffer.toString();
            buffer.setLength(0);
            lastCharTs = tsMillis;
            return Optional.of(result);
        }

        // Non-terminator: check the gap. If we're beyond the scanner-burst threshold, drop
        // whatever's in the buffer — this is either the start of a new burst (accept), or
        // human typing (which will never terminate a valid burst).
        if (buffer.length() > 0 && lastCharTs != Long.MIN_VALUE
                && (tsMillis - lastCharTs) > burstGapMs) {
            buffer.setLength(0);
        }

        // A configured prefix that arrives as the first character of a burst is silently
        // stripped. Any later occurrence is treated as a normal char (it would fail digit
        // validation anyway, but that's the caller's job).
        boolean isPrefix = prefix != NO_PREFIX && c == prefix && buffer.length() == 0;
        if (!isPrefix) {
            buffer.append(c);
        }
        lastCharTs = tsMillis;
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
