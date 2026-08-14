package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.possystem.component.EligibilityRule;

import java.math.BigDecimal;
import java.util.List;

/**
 * The <strong>one and only</strong> piece of discount arithmetic the POS performs itself.
 *
 * <p>Every real discount figure is computed by the discount engine and returned over HTTP — the
 * engine evaluates rules in priority order against a running net so promotions apply before
 * eligibility percentages (see the engine's {@code DiscountService}). Calling the engine on every
 * scan, however, would put a two-second network round-trip on the hot path. So while a transaction
 * is {@code IN_PROGRESS} the POS shows a <em>preview</em> of the selected eligibility discount that
 * updates instantly as the basket changes, and at Total the engine's response replaces it
 * wholesale.</p>
 *
 * <p>The preview is deliberately trivial and covers only transaction-level eligibility discounts:</p>
 * <ul>
 *   <li>{@link DiscountType#PERCENT_OFF}: {@code subtotal × amount / 100}, computed with
 *       {@link BigDecimal#movePointLeft(int)} rather than {@code divide(100)} — an unscaled
 *       {@code divide} throws {@link ArithmeticException} on a non-terminating quotient, and
 *       {@code movePointLeft(2)} is exact.</li>
 *   <li>{@link DiscountType#FIXED_AMOUNT_OFF}: {@code min(amount, subtotal)} — a flat discount can
 *       never reduce the basket below zero.</li>
 *   <li>{@link DiscountType#PROMO}: not previewed. Promotions are basket-qualifying and computed
 *       only by the engine; the POS never guesses at them.</li>
 * </ul>
 *
 * <p>For a single eligibility discount and no promotions, this preview equals the engine's figure
 * exactly (the running net is just the subtotal). The two can differ only when sequencing matters —
 * a promotion reducing the net before an eligibility percentage — and in every such case the engine
 * result at Total is authoritative and wins. Do not grow this class into a second discount engine.</p>
 */
public final class DiscountPreview {

    private DiscountPreview() {}

    /**
     * Sum of the local preview amounts for the given eligibility rules against {@code subtotal},
     * clamped so the total preview never exceeds the subtotal.
     *
     * @param rules    the currently-selected eligibility rules; must not be {@code null}
     * @param subtotal the current pre-discount subtotal; must not be {@code null}
     * @return the previewed discount total; never {@code null}, never greater than {@code subtotal}
     */
    public static BigDecimal previewTotal(List<EligibilityRule> rules, BigDecimal subtotal) {
        BigDecimal sum = BigDecimal.ZERO;
        for (EligibilityRule rule : rules) {
            sum = sum.add(previewAmount(rule, subtotal));
        }
        return sum.compareTo(subtotal) > 0 ? subtotal : sum;
    }

    /**
     * The preview reduction one eligibility rule produces against {@code subtotal}. Returns
     * {@link BigDecimal#ZERO} for a {@link DiscountType#PROMO} rule or a rule with a null amount —
     * neither is previewable locally.
     */
    public static BigDecimal previewAmount(EligibilityRule rule, BigDecimal subtotal) {
        if (rule == null || rule.discountType() == null || rule.amount() == null) {
            return BigDecimal.ZERO;
        }
        return switch (rule.discountType()) {
            // Exact percent: multiply then shift two places. divide(100) with no explicit scale
            // throws on a non-terminating quotient; movePointLeft(2) never does.
            case PERCENT_OFF -> subtotal.multiply(rule.amount()).movePointLeft(2);
            case FIXED_AMOUNT_OFF -> rule.amount().min(subtotal);
            case PROMO -> BigDecimal.ZERO;
        };
    }
}
