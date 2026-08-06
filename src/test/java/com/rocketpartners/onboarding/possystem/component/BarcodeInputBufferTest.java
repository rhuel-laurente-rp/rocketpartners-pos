package com.rocketpartners.onboarding.possystem.component;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BarcodeInputBufferTest {

    /**
     * Feeds a sequence of characters at the given inter-character gap and returns whatever
     * the buffer emitted along the way (typically empty until the final terminator).
     */
    private static Optional<String> feedBurst(BarcodeInputBuffer buf, String chars, long gapMs, long startTs) {
        Optional<String> last = Optional.empty();
        long ts = startTs;
        for (int i = 0; i < chars.length(); i++) {
            last = buf.accept(chars.charAt(i), ts);
            ts += gapMs;
        }
        return last;
    }

    @Test
    void twelveDigitBurst_at5msIntervals_plusEnter_yieldsBarcode() {
        BarcodeInputBuffer buf = new BarcodeInputBuffer();

        feedBurst(buf, "049000053418", 5, 1000L);
        Optional<String> completed = buf.accept('\n', 1060L);

        assertThat(completed).contains("049000053418");
        assertThat(buf.pendingLength()).isZero();
    }

    @Test
    void thirteenDigitBurst_at5msIntervals_plusEnter_yieldsBarcode() {
        BarcodeInputBuffer buf = new BarcodeInputBuffer();

        feedBurst(buf, "1234567890123", 5, 0L);
        Optional<String> completed = buf.accept('\n', 100L);

        assertThat(completed).contains("1234567890123");
    }

    @Test
    void sameDigitsAt150msIntervals_doNotAutoSubmitFullBarcode() {
        BarcodeInputBuffer buf = new BarcodeInputBuffer();

        // 150 ms > default 50 ms burst gap → each char resets the buffer.
        feedBurst(buf, "049000053418", 150, 0L);
        Optional<String> completed = buf.accept('\n', 150L * 12);

        // Whatever comes out is at most the LAST character — never the full barcode.
        assertThat(completed).isNotEqualTo(Optional.of("049000053418"));
        completed.ifPresent(s -> assertThat(s.length()).isLessThan(12));
    }

    @Test
    void abandonedBurst_isDiscarded_beforeNextBurstStarts() {
        BarcodeInputBuffer buf = new BarcodeInputBuffer(); // 200 ms stale timeout

        // Half a scan, then a long silence.
        feedBurst(buf, "049000", 5, 0L);
        assertThat(buf.pendingLength()).isEqualTo(6);

        // Next burst arrives 400 ms after the last char — well past the stale timeout.
        feedBurst(buf, "111222333444", 5, 400L);
        Optional<String> completed = buf.accept('\n', 460L);

        assertThat(completed).contains("111222333444");
        assertThat(completed.get()).doesNotContain("049000");
    }

    @Test
    void tabTerminator_works() {
        BarcodeInputBuffer buf = new BarcodeInputBuffer();

        feedBurst(buf, "111222333444", 5, 0L);
        Optional<String> completed = buf.accept('\t', 60L);

        assertThat(completed).contains("111222333444");
    }

    @Test
    void configuredPrefix_isStrippedFromFirstChar() {
        Set<Character> terms = new HashSet<>();
        terms.add('\n');
        BarcodeInputBuffer buf = new BarcodeInputBuffer(50L, 200L, '~', terms);

        // Prefix (~) then 12 digits then Enter.
        Optional<String> zero = buf.accept('~', 0L);
        assertThat(zero).isEmpty();
        assertThat(buf.pendingLength()).isZero(); // prefix stripped, buffer empty

        feedBurst(buf, "111222333444", 5, 5L);
        Optional<String> completed = buf.accept('\n', 65L);

        assertThat(completed).contains("111222333444");
    }

    @Test
    void terminatorOnEmptyBuffer_producesNothing() {
        BarcodeInputBuffer buf = new BarcodeInputBuffer();

        Optional<String> completed = buf.accept('\n', 0L);

        assertThat(completed).isEmpty();
    }

    @Test
    void multipleBursts_areIndependent() {
        BarcodeInputBuffer buf = new BarcodeInputBuffer();

        feedBurst(buf, "111222333444", 5, 0L);
        Optional<String> first = buf.accept('\n', 60L);
        feedBurst(buf, "999888777666", 5, 500L);
        Optional<String> second = buf.accept('\n', 560L);

        assertThat(first).contains("111222333444");
        assertThat(second).contains("999888777666");
    }

    @Test
    void reset_dropsBufferedChars() {
        BarcodeInputBuffer buf = new BarcodeInputBuffer();
        feedBurst(buf, "0490", 5, 0L);

        buf.reset();
        assertThat(buf.pendingLength()).isZero();

        feedBurst(buf, "111222333444", 5, 100L);
        Optional<String> completed = buf.accept('\n', 160L);

        assertThat(completed).contains("111222333444");
        assertThat(completed.get()).doesNotContain("0490");
    }

    @Test
    void barcodes_isValidUpc_acceptsAnyNonEmptyDigitString() {
        assertThat(Barcodes.isValidUpc("049000053418")).isTrue();   // 12 digits (UPC-A)
        assertThat(Barcodes.isValidUpc("1234567890123")).isTrue();  // 13 digits (EAN-13)
        assertThat(Barcodes.isValidUpc("12345")).isTrue();          // short PLU-style codes
        assertThat(Barcodes.isValidUpc("12345678901234")).isTrue(); // 14-digit codes
    }

    @Test
    void barcodes_isValidUpc_rejectsEmptyAndNonDigitInput() {
        assertThat(Barcodes.isValidUpc(null)).isFalse();
        assertThat(Barcodes.isValidUpc("")).isFalse();
        assertThat(Barcodes.isValidUpc("banana")).isFalse();
        assertThat(Barcodes.isValidUpc("0490abc53418")).isFalse();
    }
}
