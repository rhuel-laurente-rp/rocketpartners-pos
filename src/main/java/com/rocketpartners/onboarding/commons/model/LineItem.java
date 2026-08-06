package com.rocketpartners.onboarding.commons.model;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One product's appearance on a specific {@link Transaction}, with a quantity.
 *
 * <p>An {@link Item} is the pricebook record; a {@code LineItem} is that item on a sale.
 * A single {@code LineItem} can accumulate quantity as the cashier scans the same UPC repeatedly;
 * this accumulation is a {@link Transaction} concern (see {@code Transaction.addLineItem}).</p>
 *
 * <p>Voiding is a soft-delete: {@link #setVoided(boolean)} flips the flag but the line stays
 * on the transaction, preserving the audit trail for the journal. A voided line contributes
 * zero to totals — see {@link #extendedTotal()}.</p>
 */
@Getter
public class LineItem {

    /** The pricebook item; final. */
    private final Item item;

    /** Number of units on this line. Must be at least 1. */
    @Setter
    private int quantity;

    /** {@code true} if the cashier has voided this line; contributes zero to totals when set. */
    @Setter
    private boolean voided;

    /**
     * Constructs a new, non-voided line item for the given product and quantity.
     *
     * @param item     the pricebook item; must not be {@code null}
     * @param quantity units on this line; must be at least 1
     * @throws IllegalArgumentException if {@code item} is null or {@code quantity < 1}
     */
    public LineItem(Item item, int quantity) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1, got " + quantity);
        }
        this.item = item;
        this.quantity = quantity;
        this.voided = false;
    }

    /**
     * The extended (line) total: {@code unitPrice × quantity}, or {@link BigDecimal#ZERO}
     * when this line is voided. Never rounded here — rounding happens once at
     * {@code Transaction.grandTotal()}.
     *
     * @return zero if voided; otherwise unit price times quantity
     */
    public BigDecimal extendedTotal() {
        if (voided) {
            return BigDecimal.ZERO;
        }
        return item.getUnitPrice().multiply(BigDecimal.valueOf(quantity));
    }
}
