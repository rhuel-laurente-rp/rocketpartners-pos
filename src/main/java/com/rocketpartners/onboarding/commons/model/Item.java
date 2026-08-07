package com.rocketpartners.onboarding.commons.model;

import lombok.AllArgsConstructor;
import lombok.Value;

import java.math.BigDecimal;

/**
 * A pricebook record: one per UPC. Immutable.
 *
 * <p>An {@code Item} describes a product that can be sold — its identifying barcode, human-readable
 * description, and unit price. It does not know about any particular sale; that association is
 * made by a {@link LineItem} on a {@link Transaction}. Tax is not a per-item concern — a flat
 * transaction-level tax rate is applied at total time.</p>
 *
 * <p>{@link #displayName} is an optional customer-friendly label. Pricebook descriptions are the
 * raw SKU strings the back office ships us — {@code RED BULL ENERGY DRIN}, {@code M&M PNUT REG
 * 1.74Z} — which read fine on a receipt but are hostile on a customer-facing display. When
 * populated, {@link #getDisplayLabel()} returns it; otherwise the description is used. Storing
 * the friendly label as data (a fourth column in the pricebook) rather than a view-side rewrite
 * keeps the mapping owned by the domain, not by any one screen.</p>
 */
@Value
@AllArgsConstructor
public class Item {

    /** Barcode; the pricebook lookup key. */
    String upc;

    /** Human-readable product description, shown on receipts and displays. */
    String description;

    /** Price per unit, stored at whatever scale the pricebook provides (typically 2 for USD). */
    BigDecimal unitPrice;

    /**
     * Optional customer-friendly display name. May be {@code null} or blank, in which case
     * {@link #getDisplayLabel()} falls back to {@link #description}.
     */
    String displayName;

    /**
     * Convenience constructor for callers that do not carry a display name — kept because most
     * unit tests and older pricebook rows have no fourth column.
     */
    public Item(String upc, String description, BigDecimal unitPrice) {
        this(upc, description, unitPrice, null);
    }

    /**
     * The label to render on customer-facing surfaces. Returns {@link #displayName} when it is
     * non-null and non-blank, otherwise the raw {@link #description}. Receipts and the journal
     * continue to use {@link #getDescription()} directly so audit output matches the pricebook.
     */
    public String getDisplayLabel() {
        return (displayName == null || displayName.isBlank()) ? description : displayName;
    }
}
