package com.rocketpartners.onboarding.posdiscountengine.entity;

/**
 * What a {@link DiscountRule} applies to.
 */
public enum TargetType {
    /** The rule applies to the transaction as a whole; {@code targetValue} is unused. */
    TRANSACTION,
    /** The rule applies to a specific product; {@code targetValue} carries that product's UPC. */
    UPC
}
