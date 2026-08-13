package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;

import java.math.BigDecimal;

/**
 * A <strong>display-only</strong> basket row for units a promotion made free — the indented
 * "↳ … free  −$X.XX" line that appears beneath the product that triggered a buy-N-get-M deal.
 *
 * <p>This is <em>not</em> a domain line item. The aggregate {@link com.rocketpartners.onboarding.commons.model.Transaction}
 * never sees it: the real product line keeps its full quantity at full price (so the subtotal is
 * honest), and the promotion is a transaction-level discount. This row exists purely so the cashier
 * and customer can see, in the basket, that a unit was given free and by how much the price dropped.
 * It is built by {@code CustomerViewController} and handed to the view alongside the real lines.</p>
 *
 * <p>Because it subclasses {@link LineItem}, the {@code JList<LineItem>} model can hold it with no
 * type change; every place that must treat it differently keys off {@code instanceof FreeLineItem}:
 * the renderer paints it indented and inert, and {@code CustomerView} excludes it from the item
 * count, density, flash, and — crucially — from selection, so it can never be voided or
 * quantity-changed. {@link #extendedTotal()} is the negative reduction so the Total column reads
 * {@code -$X.XX}.</p>
 */
final class FreeLineItem extends LineItem {

    private final int freeUnits;
    private final BigDecimal freeAmount; // positive magnitude of the reduction

    /**
     * @param sourceItem the product that was (partly) made free — supplies the description
     * @param freeUnits  how many units are free (≥ 1)
     * @param freeAmount the positive dollar amount those free units come to; rendered as {@code -amount}
     */
    FreeLineItem(Item sourceItem, int freeUnits, BigDecimal freeAmount) {
        super(sourceItem, 1);
        if (freeAmount == null) throw new IllegalArgumentException("freeAmount must not be null");
        this.freeUnits = freeUnits;
        this.freeAmount = freeAmount;
    }

    int getFreeUnits() {
        return freeUnits;
    }

    /** @return the positive magnitude of the reduction (the Total column shows its negation) */
    BigDecimal getFreeAmount() {
        return freeAmount;
    }

    /** Negative so the basket's Total column reads {@code -$X.XX} for the freed units. */
    @Override
    public BigDecimal extendedTotal() {
        return freeAmount.negate();
    }
}
