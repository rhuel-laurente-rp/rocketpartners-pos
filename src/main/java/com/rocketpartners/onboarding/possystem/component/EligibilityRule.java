package com.rocketpartners.onboarding.possystem.component;

import com.rocketpartners.onboarding.commons.model.DiscountType;

import java.math.BigDecimal;

/**
 * A POS-side, read-only view of one {@code ELIGIBILITY} discount rule as returned by the discount
 * engine's {@code GET /discounts/rules} endpoint.
 *
 * <p><strong>Why this exists rather than reusing the engine's entity.</strong> The engine models a
 * rule as a JPA entity ({@code posdiscountengine.entity.DiscountRule}). The POS must not import
 * that type — the whole point of Phase 3 is that the two talk over HTTP, not by sharing classes
 * (see {@code CLAUDE.md} package discipline). The engine serialises its entity to JSON; the POS
 * deserialises the handful of fields it actually needs into this immutable record. Unknown JSON
 * fields (id, category, targetType, priority, active, …) are simply ignored by
 * {@link CloudApiComponent}'s hand-rolled {@code JsonNode} parse.</p>
 *
 * <p>Only the fields the cashier dialog and the local preview need are carried:</p>
 * <ul>
 *   <li>{@code code} / {@code description} — the dialog's data-driven label and the code sent back
 *       to the engine at Total.</li>
 *   <li>{@code discountType} / {@code amount} — the inputs to the one piece of arithmetic the POS
 *       performs locally (see {@link com.rocketpartners.onboarding.possystem.service.DiscountPreview}).</li>
 *   <li>{@code exclusivityGroup} — so the dialog and {@link DiscountSession} can enforce that only
 *       one rule per group is applied.</li>
 * </ul>
 */
public record EligibilityRule(
        String code,
        String description,
        DiscountType discountType,
        BigDecimal amount,
        String exclusivityGroup) {
}
