package com.rocketpartners.onboarding.posdiscountengine.service;

/**
 * Thrown when a {@code POST /discounts/calculate} request is well-formed JSON but semantically
 * invalid: an unknown eligibility code, two codes colliding within one exclusivity group, or an
 * invalid line item. Mapped to HTTP 400 by the controller advice.
 *
 * <p>The engine is the authority on these rules even though the POS should also prevent them — it
 * must not be talked into double-discounting.</p>
 */
public class DiscountValidationException extends RuntimeException {

    public DiscountValidationException(String message) {
        super(message);
    }
}
