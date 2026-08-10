package com.rocketpartners.onboarding.possystem.component;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarcodesTest {

    // ---- isValidUpc --------------------------------------------------------

    @Test
    void isValidUpc_acceptsShortAndLongDigitStrings() {
        assertThat(Barcodes.isValidUpc("1234")).isTrue();        // 4-digit pricebook code
        assertThat(Barcodes.isValidUpc("12345678")).isTrue();    // 8-digit
        assertThat(Barcodes.isValidUpc("012345678905")).isTrue();// 12-digit UPC-A
        assertThat(Barcodes.isValidUpc("1234567890123")).isTrue();// 13-digit EAN-13
    }

    @Test
    void isValidUpc_rejectsEmptyAndNull() {
        assertThat(Barcodes.isValidUpc(null)).isFalse();
        assertThat(Barcodes.isValidUpc("")).isFalse();
    }

    @Test
    void isValidUpc_rejectsNonDigitInput() {
        assertThat(Barcodes.isValidUpc("banana")).isFalse();
        assertThat(Barcodes.isValidUpc("049000abc418")).isFalse();
        assertThat(Barcodes.isValidUpc(" 12345")).isFalse();
    }

    @Test
    void isValidUpc_rejectsOverLongInput() {
        // MAX_UPC_LENGTH is 20; 21 digits is rejected as runaway input.
        String twentyOne = "1".repeat(21);
        assertThat(Barcodes.isValidUpc(twentyOne)).isFalse();
        String twenty = "1".repeat(20);
        assertThat(Barcodes.isValidUpc(twenty)).isTrue();
    }

    // ---- upcACheckDigit ----------------------------------------------------

    @Test
    void upcACheckDigit_computesKnownVectors() {
        // 012345678905 — d12 = 5 (canonical test vector)
        assertThat(Barcodes.upcACheckDigit("012345678905")).isEqualTo(5);
        // 036000291452 — d12 = 2 (another canonical vector)
        assertThat(Barcodes.upcACheckDigit("036000291452")).isEqualTo(2);
        // COCA COLA CAN 049000053418 — real pricebook UPC, valid
        assertThat(Barcodes.upcACheckDigit("049000053418")).isEqualTo(8);
    }

    @Test
    void upcACheckDigit_requiresExactlyTwelveDigits() {
        assertThatThrownBy(() -> Barcodes.upcACheckDigit("12345"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Barcodes.upcACheckDigit(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Barcodes.upcACheckDigit("01234567890a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- hasValidUpcACheckDigit --------------------------------------------

    @Test
    void hasValidUpcACheckDigit_acceptsGoodChecksum() {
        assertThat(Barcodes.hasValidUpcACheckDigit("012345678905")).isTrue();
        assertThat(Barcodes.hasValidUpcACheckDigit("036000291452")).isTrue();
        assertThat(Barcodes.hasValidUpcACheckDigit("049000053418")).isTrue();
    }

    @Test
    void hasValidUpcACheckDigit_rejectsBadChecksum() {
        // 012345678906 differs in the last digit from the canonical 012345678905.
        assertThat(Barcodes.hasValidUpcACheckDigit("012345678906")).isFalse();
    }

    @Test
    void hasValidUpcACheckDigit_rejectsWrongLength() {
        assertThat(Barcodes.hasValidUpcACheckDigit("1234")).isFalse();
        assertThat(Barcodes.hasValidUpcACheckDigit("0000000012345")).isFalse();
        assertThat(Barcodes.hasValidUpcACheckDigit(null)).isFalse();
    }

    @Test
    void hasValidUpcACheckDigit_rejectsNonDigit() {
        assertThat(Barcodes.hasValidUpcACheckDigit("01234567890a")).isFalse();
    }
}
