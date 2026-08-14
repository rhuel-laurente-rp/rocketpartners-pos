package com.rocketpartners.onboarding.commons.dto;

import com.rocketpartners.onboarding.commons.model.Discount;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire form of the discount engine's response to {@code POST /discounts/calculate}: the discounts
 * that apply, in application order, plus their combined total.
 *
 * <p>Carries {@link Discount} <em>values</em> — the immutable, scalar result type shared between the
 * POS and the engine (see {@link Discount}'s own Javadoc). The rich aggregates
 * ({@code Transaction}, {@code LineItem}) and the JPA rule entity never travel on the wire; only
 * DTOs and value objects do.</p>
 *
 * <p>{@link #discountTotal} equals the sum of the discounts' {@code appliedAmount}s and never
 * exceeds the transaction subtotal.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiscountResponseDto {

    /** The discounts to apply, in ascending-priority application order. Empty when none apply. */
    private List<Discount> discounts;

    /** Sum of every discount's {@code appliedAmount}, scaled to 2 decimal places. */
    private BigDecimal discountTotal;
}
