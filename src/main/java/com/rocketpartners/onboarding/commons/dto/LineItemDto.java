package com.rocketpartners.onboarding.commons.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Wire form of a single line item on a discount-calculation request.
 *
 * <p>Flattens the domain {@code LineItem}'s {@code Item} reference into scalars — the discount
 * engine reads these fields but does not need the domain object graph. Kept in {@code commons.dto}
 * so the wire contract can evolve independently of the domain model.</p>
 *
 * <p>Any change here is an API contract change with the discount engine; version accordingly.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineItemDto {

    /** Barcode of the product. */
    private String upc;

    /** Human-readable description (helpful for engine-side rule diagnostics). */
    private String description;

    /** Units on this line. */
    private int quantity;

    /** Price per unit, at whatever scale the POS holds it. */
    private BigDecimal unitPrice;
}
