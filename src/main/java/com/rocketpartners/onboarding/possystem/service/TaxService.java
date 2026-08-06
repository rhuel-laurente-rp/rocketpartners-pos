package com.rocketpartners.onboarding.possystem.service;

import java.math.BigDecimal;

/**
 * Supplies the flat sales-tax rate the POS applies to new transactions.
 *
 * <p>This is a rate <em>source</em>, not a compute layer — the actual tax formula lives on
 * {@link com.rocketpartners.onboarding.commons.model.Transaction}. A more sophisticated
 * jurisdiction-aware implementation can replace this without touching the aggregate.</p>
 */
public class TaxService {

    private final BigDecimal rate;

    /**
     * @param rate the flat rate; must not be {@code null} and must be non-negative
     */
    public TaxService(BigDecimal rate) {
        if (rate == null) throw new IllegalArgumentException("rate must not be null");
        if (rate.signum() < 0) throw new IllegalArgumentException("rate must be non-negative, got " + rate);
        this.rate = rate;
    }

    /**
     * @return the configured flat sales-tax rate (e.g. {@code 0.07} for 7%)
     */
    public BigDecimal getRate() {
        return rate;
    }
}
