package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a {@link Transaction} as a plain-text receipt.
 *
 * <p>The receipt is a projection of the transaction, not a source of truth — no state is
 * stored here. Money values are rendered at scale 2 with {@link RoundingMode#HALF_UP} for
 * display only; the underlying aggregate is untouched.</p>
 *
 * <p>Two entry points: a header-less {@link #format(Transaction)} used by tests that don't care
 * about store metadata, and which prepends the store
 * name and lane number the way a real receipt reads. Both share the same body.</p>
 */
public final class ReceiptFormatter {

    private static final int LINE_WIDTH = 40;
    private static final String DOUBLE_RULE = "=".repeat(LINE_WIDTH);
    private static final String SINGLE_RULE = "-".repeat(LINE_WIDTH);

    /** Fixed left-hand label a per-discount line carries before its description. */
    private static final String DISCOUNT_PREFIX = "Discount: ";
    /** Minimum gap {@link #pad(String, String)} guarantees between the label and the amount. */
    private static final int MIN_COLUMN_GAP = 1;
    /** Ellipsis appended to a description clipped to fit the line. One column wide. */
    private static final String ELLIPSIS = "…";

    /** Cashier-readable timestamp (local zone): {@code MM/dd/yyyy HH:mm:ss}. */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss", Locale.US)
                    .withZone(ZoneId.systemDefault());

    private ReceiptFormatter() {}

    /**
     * Formats the transaction as a multi-line plain-text receipt, without a store header.
     *
     * @param tx the transaction to render; must not be {@code null}
     * @return the receipt text
     */
    public static String format(Transaction tx) {
        return format(tx, null, null, null);
    }

    /**
     * Formats the transaction with the given store name and lane number in the header, with no
     * cashier line. Retained for callers/tests that don't carry an operator id.
     *
     * @param tx         the transaction to render; must not be {@code null}
     * @param storeName  the store label; may be {@code null} to omit
     * @param laneNumber the lane number; may be {@code null} to omit
     * @return the receipt text
     */
    public static String format(Transaction tx, String storeName, Integer laneNumber) {
        return format(tx, storeName, laneNumber, null);
    }

    /**
     * Formats the transaction with the given store name, lane number, and cashier code in the header.
     *
     * @param tx         the transaction to render; must not be {@code null}
     * @param storeName  the store label; may be {@code null} to omit
     * @param laneNumber the lane number; may be {@code null} to omit
     * @param cashier    the signed-in operator id from the login screen; may be {@code null} or blank
     *                   to omit the cashier line
     * @return the receipt text
     */
    public static String format(Transaction tx, String storeName, Integer laneNumber, String cashier) {
        if (tx == null) throw new IllegalArgumentException("tx must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append(DOUBLE_RULE).append('\n');
        if (storeName != null) {
            sb.append(center(storeName)).append('\n');
        }
        if (laneNumber != null) {
            sb.append(center("Lane " + laneNumber)).append('\n');
        }
        if (storeName != null || laneNumber != null) {
            sb.append(DOUBLE_RULE).append('\n');
        }
        sb.append("Transaction: ").append(tx.getTransactionId()).append('\n');
        sb.append("Date:        ").append(DATE_FORMAT.format(tx.getCreatedAt())).append('\n');
        // The cashier on duty, carried from the login screen through PosComponent. Omitted when
        // unknown (e.g. tests that don't sign in) so the header degrades cleanly.
        if (cashier != null && !cashier.isBlank()) {
            sb.append("Cashier:     ").append(cashier).append('\n');
        }
        sb.append(DOUBLE_RULE).append('\n');

        for (LineItem li : tx.getLineItems()) {
            if (li.isVoided()) continue;
            String left = li.getQuantity() + " x " + li.getItem().getDescription();
            sb.append(pad(left, money(li.extendedTotal()))).append('\n');
            String unitPrice = "  @ " + money(li.getItem().getUnitPrice()) + " ea";
            sb.append(unitPrice).append('\n');
        }

        sb.append(SINGLE_RULE).append('\n');
        sb.append(pad("Subtotal:", money(tx.subtotal()))).append('\n');

        // One line per applied discount, then the combined discount total. A transaction with no
        // discounts renders exactly as before — no lines, no "Discount Total" row, no empty section.
        // Rule descriptions come from the discount engine's database, which someone will edit by
        // hand: the formatter cannot assume a long one won't blow past the line. Each description is
        // clipped to the space actually left after the fixed "Discount: " label, the amount column,
        // and the minimum gap between them — a value derived from LINE_WIDTH, never a magic number —
        // then ellipsised. Shortening the CSV fixes today's data; this fixes tomorrow's.
        for (Discount d : tx.getDiscounts()) {
            String amount = "-" + money(d.getAppliedAmount());
            int budget = LINE_WIDTH - DISCOUNT_PREFIX.length() - amount.length() - MIN_COLUMN_GAP;
            sb.append(pad(DISCOUNT_PREFIX + ellipsize(d.getDescription(), budget), amount)).append('\n');
        }
        if (!tx.getDiscounts().isEmpty()) {
            sb.append(pad("Discount Total:", "-" + money(tx.discountTotal()))).append('\n');
        }

        // The rate is derived from the transaction, not hard-coded: TaxService's rate is
        // configurable, so a literal "7%" would become a lie the first time it changes.
        sb.append(pad("Tax (" + taxRatePercent(tx.getTaxRate()) + "%):", money(tx.taxTotal())))
                .append('\n');
        sb.append(SINGLE_RULE).append('\n');
        sb.append(pad("TOTAL:", money(tx.grandTotal()))).append('\n');

        // Show the settled amount only once the transaction is paid — beforehand there is no
        // "amount due" separate from the grand total. When they differ (Next Dollar shortcut),
        // annotate with the mode so the audit trail records which cashier action produced it.
        if (tx.getState() == TransactionState.PAID) {
            BigDecimal grand = tx.grandTotal();
            BigDecimal settled = tx.amountDue();
            String modeLabel = settled.compareTo(grand) == 0 ? "Exact" : "Next Dollar";
            sb.append(pad("Amount Due (" + modeLabel + "):", money(settled))).append('\n');
        }

        sb.append(DOUBLE_RULE).append('\n');

        TenderType tenderType = tx.getTenderType();
        if (tenderType != null) {
            sb.append(pad("Tender: " + tenderType, money(tx.getCashTendered()))).append('\n');
            if (tenderType == TenderType.CASH) {
                sb.append(pad("Change:", money(tx.changeDue()))).append('\n');
            }
            sb.append(DOUBLE_RULE).append('\n');
        }

        return sb.toString();
    }

    private static String pad(String left, String right) {
        int spaces = Math.max(1, LINE_WIDTH - left.length() - right.length());
        return left + " ".repeat(spaces) + right;
    }

    private static String center(String text) {
        int pad = Math.max(0, (LINE_WIDTH - text.length()) / 2);
        return " ".repeat(pad) + text;
    }

    private static String money(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        return String.format(Locale.US, "%.2f", rounded);
    }

    /**
     * Clips {@code text} to at most {@code max} columns, appending {@link #ELLIPSIS} when it
     * overflows so the reader can see the label was truncated. A non-positive budget yields the
     * empty string — the amount column has consumed the whole line.
     */
    private static String ellipsize(String text, int max) {
        String s = text == null ? "" : text;
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        if (max <= ELLIPSIS.length()) return ELLIPSIS.substring(0, max);
        return s.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }

    /**
     * Renders a tax rate as its whole-number (or shortest-decimal) percentage: {@code 0.07 → "7"},
     * {@code 0.075 → "7.5"}. Trailing zeros are stripped so a flat rate never prints as "7.00".
     */
    private static String taxRatePercent(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }
}
