package com.rocketpartners.onboarding.posdiscountengine.entity;

/**
 * What kind of thing a {@link DiscountRule} represents — the coarse split the POS dialog uses to
 * decide how a rule is offered.
 */
public enum DiscountCategory {
    /** Applies because of <em>who</em> the customer is (senior, veteran, employee). The cashier
     *  picks one of these from a dialog; they share an exclusivity group so only one can apply. */
    ELIGIBILITY,
    /** Applies because of <em>what</em> is in the basket (BOGO and other promotions). Evaluated
     *  automatically against the transaction, not chosen by the cashier. */
    PROMOTIONAL
}
