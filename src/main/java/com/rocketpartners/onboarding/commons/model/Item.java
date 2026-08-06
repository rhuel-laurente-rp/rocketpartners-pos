package com.rocketpartners.onboarding.commons.model;

import lombok.Value;

import java.math.BigDecimal;

/**
 * A pricebook record: one per UPC. Immutable.
 *
 * <p>An {@code Item} describes a product that can be sold — its identifying barcode, human-readable
 * description, and unit price. It does not know about any particular sale; that association is
 * made by a {@link LineItem} on a {@link Transaction}. Tax is not a per-item concern — a flat
 * transaction-level tax rate is applied at total time.</p>
 */
@Value
public class Item {

    /** Barcode; the pricebook lookup key. */
    String upc;

    /** Human-readable product description, shown on receipts and displays. */
    String description;

    /** Price per unit, stored at whatever scale the pricebook provides (typically 2 for USD). */
    BigDecimal unitPrice;
}
