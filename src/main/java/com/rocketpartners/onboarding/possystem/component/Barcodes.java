package com.rocketpartners.onboarding.possystem.component;

/**
 * Barcode-shape checks the POS applies before sending a scanned string to the pricebook.
 *
 * <p>The pricebook is keyed on UPCs; scans that are obviously not a UPC (letters, empty burst
 * from a stray Enter, absurd length) should be rejected with a cashier-readable error rather
 * than dispatched as an item lookup. Kept here as plain functions so both the buffer's caller
 * and any tests can share the single rule.</p>
 *
 * <p>Two families of function:</p>
 * <ul>
 *   <li>{@link #isValidUpc(String)} — the shape gate: non-empty, all digits, not absurdly long.
 *       Length is NOT constrained to UPC-A (12) or EAN-13 (13); the pricebook carries UPCs of
 *       assorted lengths, so the effective validity gate is the pricebook lookup itself.</li>
 *   <li>{@link #upcACheckDigit(String)} / {@link #hasValidUpcACheckDigit(String)} —
 *       diagnostics used to differentiate a genuine unknown code from a probable scanner
 *       misread (12-digit input, bad check digit). Never a rejection reason on their own.</li>
 * </ul>
 */
public final class Barcodes {

    /**
     * Runaway-input guard. A hardware scanner emits at most a few tens of characters per burst;
     * anything longer is either paste-bombed input or an infinite-loop diagnostic. 20 is well
     * above every real barcode symbology and any pricebook code observed today.
     */
    public static final int MAX_UPC_LENGTH = 20;

    /** Fixed length of a UPC-A barcode, in digits. */
    public static final int UPC_A_LENGTH = 12;

    private Barcodes() {}

    /**
     * @return {@code true} if {@code raw} is a non-empty string of digits and not longer than
     *         {@link #MAX_UPC_LENGTH}. No exact-length rule — a 4-digit manual entry and a
     *         12-digit scan are both legitimate lookup keys.
     */
    public static boolean isValidUpc(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        int len = raw.length();
        if (len > MAX_UPC_LENGTH) return false;
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Computes the UPC-A check digit for the given 12-digit barcode's payload. Callers pass in
     * the full 12-digit code; only the first 11 digits participate in the sum, and the 12th
     * position is what a valid barcode's last digit would be.
     *
     * <p>Formula (standard UPC-A):
     * {@code odd = d1+d3+d5+d7+d9+d11}, {@code even = d2+d4+d6+d8+d10},
     * {@code expected = (10 - ((odd*3 + even) % 10)) % 10}.</p>
     *
     * @param upcA a 12-digit, all-digit string
     * @return the check digit (0..9) computed from positions 1..11
     * @throws IllegalArgumentException if {@code upcA} is not exactly 12 digits
     */
    public static int upcACheckDigit(String upcA) {
        if (upcA == null || upcA.length() != UPC_A_LENGTH) {
            throw new IllegalArgumentException(
                    "UPC-A check digit requires 12 digits, got '" + upcA + "'");
        }
        for (int i = 0; i < UPC_A_LENGTH; i++) {
            char c = upcA.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                        "UPC-A check digit requires 12 digits, got '" + upcA + "'");
            }
        }
        int odd = 0, even = 0;
        for (int i = 0; i < UPC_A_LENGTH - 1; i++) {
            int d = upcA.charAt(i) - '0';
            if ((i & 1) == 0) odd += d;
            else even += d;
        }
        int total = odd * 3 + even;
        return (10 - (total % 10)) % 10;
    }

    /**
     * @return {@code true} if {@code raw} is a 12-digit all-digit string whose final digit
     *         equals {@link #upcACheckDigit(String)} computed over its first 11 digits. Returns
     *         {@code false} for anything else — non-12-length, non-digit, {@code null}.
     */
    public static boolean hasValidUpcACheckDigit(String raw) {
        if (raw == null || raw.length() != UPC_A_LENGTH) return false;
        for (int i = 0; i < UPC_A_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return (raw.charAt(UPC_A_LENGTH - 1) - '0') == upcACheckDigit(raw);
    }
}
