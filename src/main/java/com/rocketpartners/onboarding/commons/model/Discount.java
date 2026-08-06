package com.rocketpartners.onboarding.commons.model;

import lombok.Value;

import java.math.BigDecimal;

/**
 * A price reduction applied to a {@link Transaction}. Immutable.
 *
 * <p><strong>This is a value, not a rule.</strong> The rule that produced this discount
 * (e.g. "10% off produce", BOGO on soda, a store-credit code) lives in the discount engine's
 * database. What the POS holds is the <em>result</em> of applying that rule to a specific
 * transaction: a description for the receipt and a concrete dollar amount reduction.</p>
 *
 * <p>The meaning of {@link #getAmount()} depends on {@link #getType()}:</p>
 * <ul>
 *   <li>{@link DiscountType#PERCENT_OFF}: {@code amount} is the percent (e.g. {@code 10} for 10%).</li>
 *   <li>{@link DiscountType#FIXED_AMOUNT_OFF}: {@code amount} is the flat dollar amount.</li>
 *   <li>{@link DiscountType#PROMO}: {@code amount} may be zero; only {@link #getAppliedAmount()}
 *       is meaningful.</li>
 * </ul>
 *
 * <p>{@link #getAppliedAmount()} is the dollar reduction actually applied to this transaction,
 * regardless of type. This is what {@code Transaction.discountTotal()} sums.</p>
 */
@Value
public class Discount {

    /** Stable identifier for this discount instance (typically assigned by the engine). */
    String discountId;

    /** Human-readable description for the receipt (e.g. "10% off produce"). */
    String description;

    /** The kind of reduction — determines how {@link #amount} is interpreted. */
    DiscountType type;

    /** The rule's parameter (percent, flat dollars, or unused for {@link DiscountType#PROMO}). */
    BigDecimal amount;

    /** The dollar reduction actually applied to this transaction. Always meaningful. */
    BigDecimal appliedAmount;
}
