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
        return format(tx, null, null);
    }

    /**
     * Formats the transaction with the given store name and lane number in the header.
     *
     * @param tx         the transaction to render; must not be {@code null}
     * @param storeName  the store label; may be {@code null} to omit
     * @param laneNumber the lane number; may be {@code null} to omit
     * @return the receipt text
     */
    public static String format(Transaction tx, String storeName, Integer laneNumber) {
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
        for (Discount d : tx.getDiscounts()) {
            sb.append(pad("Discount: " + d.getDescription(), "-" + money(d.getAppliedAmount()))).append('\n');
        }
        if (!tx.getDiscounts().isEmpty()) {
            sb.append(pad("Discount Total:", "-" + money(tx.discountTotal()))).append('\n');
        }

        sb.append(pad("Tax (7%):", money(tx.taxTotal()))).append('\n');
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
}
