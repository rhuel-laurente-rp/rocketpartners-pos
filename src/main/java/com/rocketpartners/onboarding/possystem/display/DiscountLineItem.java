package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;

import java.math.BigDecimal;

/**
 * A <strong>display-only</strong> basket row for a per-item discount — the indented
 * "↳ &lt;deal&gt;  −$X.XX" line that appears beneath a product whose UPC carries a percent-off or
 * amount-off rule, the sibling of {@link FreeLineItem}'s buy-N-get-M "free" row.
 *
 * <p>Like {@link FreeLineItem} this is <em>not</em> a domain line item: the aggregate
 * {@link com.rocketpartners.onboarding.commons.model.Transaction} never sees it, the real product
 * line keeps its full quantity at full price so the subtotal stays honest, and the reduction is a
 * transaction-level discount. It exists so the cashier sees the deal in the basket the moment the
 * item is rung up, mirrored by the summary's Discount total. {@link #extendedTotal()} is the
 * negative reduction so the Total column reads {@code -$X.XX}.</p>
 *
 * <p>It carries its {@link DiscountType} so the renderer can tint the row (and the matching Quick
 * Add tile edge and legend) by the kind of deal, and a short {@code label} — the rule's description
 * — so the row names the deal rather than repeating the product name.</p>
 */
final class DiscountLineItem extends LineItem implements PreviewRow {

    private final DiscountType discountType;
    private final String label;
    private final BigDecimal amount; // positive magnitude of the reduction

    /**
     * @param sourceItem   the product the discount applies to — supplies nothing rendered directly,
     *                     but keeps the row anchored to a real item
     * @param discountType the kind of deal, for the row's accent colour
     * @param label        the deal's description (e.g. "25% Off Reign 16oz"); rendered on the row
     * @param amount       the positive dollar reduction; rendered as {@code -amount}
     */
    DiscountLineItem(Item sourceItem, DiscountType discountType, String label, BigDecimal amount) {
        super(sourceItem, 1);
        if (discountType == null) throw new IllegalArgumentException("discountType must not be null");
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        this.discountType = discountType;
        this.label = label == null ? sourceItem.getDisplayLabel() : label;
        this.amount = amount;
    }

    DiscountType getDiscountType() {
        return discountType;
    }

    String getLabel() {
        return label;
    }

    /** @return the positive magnitude of the reduction (the Total column shows its negation) */
    BigDecimal getDiscountAmount() {
        return amount;
    }

    /** Negative so the basket's Total column reads {@code -$X.XX} for the discount. */
    @Override
    public BigDecimal extendedTotal() {
        return amount.negate();
    }
}
