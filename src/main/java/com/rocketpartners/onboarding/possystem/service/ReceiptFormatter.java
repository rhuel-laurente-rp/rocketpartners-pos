package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a {@link Transaction} as a plain-text receipt.
 *
 * <p>The receipt is a projection of the transaction, not a source of truth — no state is
 * stored here. Money values are rendered at scale 2 with {@link RoundingMode#HALF_UP} for
 * display only; the underlying aggregate is untouched.</p>
 */
public final class ReceiptFormatter {

    private static final int LINE_WIDTH = 33;
    private static final String DOUBLE_RULE = "=".repeat(LINE_WIDTH);
    private static final String SINGLE_RULE = "-".repeat(LINE_WIDTH);

    private ReceiptFormatter() {}

    /**
     * Formats the transaction as a multi-line plain-text receipt.
     *
     * @param tx the transaction to render; must not be {@code null}
     * @return the receipt text
     */
    public static String format(Transaction tx) {
        if (tx == null) throw new IllegalArgumentException("tx must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append(DOUBLE_RULE).append('\n');
        sb.append("Transaction: ").append(tx.getTransactionId()).append('\n');
        sb.append("Date:        ").append(DateTimeFormatter.ISO_INSTANT.format(tx.getCreatedAt())).append('\n');
        sb.append(DOUBLE_RULE).append('\n');

        for (LineItem li : tx.getLineItems()) {
            if (li.isVoided()) continue;
            String left = li.getQuantity() + " x " + li.getItem().getDescription();
            sb.append(pad(left, money(li.extendedTotal()))).append('\n');
        }

        sb.append(SINGLE_RULE).append('\n');
        sb.append(pad("Subtotal:", money(tx.subtotal()))).append('\n');

        for (Discount d : tx.getDiscounts()) {
            sb.append(pad("Discount: " + d.getDescription(), "-" + money(d.getAppliedAmount()))).append('\n');
        }

        sb.append(pad("Tax:", money(tx.taxTotal()))).append('\n');
        sb.append(SINGLE_RULE).append('\n');
        sb.append(pad("TOTAL:", money(tx.grandTotal()))).append('\n');
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

    private static String money(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        return String.format(Locale.US, "%.2f", rounded);
    }
}
