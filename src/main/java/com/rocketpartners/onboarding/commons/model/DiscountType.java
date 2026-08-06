package com.rocketpartners.onboarding.commons.model;

/**
 * The kind of reduction a {@link Discount} represents.
 *
 * <p>A {@code Discount} is the <em>result</em> of a rule evaluation, not the rule itself.
 * The rule that produced it lives in the discount engine's database.</p>
 */
public enum DiscountType {
    /** A percentage off the transaction subtotal. {@code Discount.amount} is the percent (e.g. {@code 10} = 10%). */
    PERCENT_OFF,
    /** A flat dollar amount off. {@code Discount.amount} is the dollar amount. */
    FIXED_AMOUNT_OFF,
    /** Engine-computed promotion (BOGO, tiered, etc.). {@code Discount.amount} may be zero;
     *  only {@code appliedAmount} is meaningful. */
    PROMO
}
