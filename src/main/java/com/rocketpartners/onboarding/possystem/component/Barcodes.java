package com.rocketpartners.onboarding.possystem.component;

/**
 * Barcode-shape checks the POS applies before sending a scanned string to the pricebook.
 *
 * <p>The pricebook is keyed on UPCs; scans that are obviously not a UPC (letters, wrong
 * length, empty burst from a stray Enter) should be rejected with a cashier-readable error
 * rather than dispatched as an item lookup. Kept here as a plain function so both the buffer's
 * caller and any tests can share the single rule.</p>
 */
public final class Barcodes {

    private Barcodes() {}

    /**
     * @return {@code true} if {@code raw} is all digits and 12 (UPC-A) or 13 (EAN-13)
     *         characters long
     */
    public static boolean isValidUpc(String raw) {
        if (raw == null) return false;
        int len = raw.length();
        if (len != 12 && len != 13) return false;
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
