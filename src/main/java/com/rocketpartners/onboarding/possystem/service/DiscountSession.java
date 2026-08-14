package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.possystem.component.EligibilityRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The eligibility discounts the cashier has chosen for the <em>current</em> transaction — the
 * transaction-scoped selection that drives both the running preview and the codes sent to the
 * engine at Total.
 *
 * <p>This is not domain state: the aggregate ({@code Transaction}) only ever holds engine-computed
 * {@code Discount} values, and only after Total. Between scans the POS needs somewhere to remember
 * "the cashier picked Senior" so the preview can update and so the right codes travel on the
 * calculate request. That is this object. It is cleared whenever a new transaction begins.</p>
 *
 * <p><strong>Exclusivity is enforced here, not just in the dialog.</strong> {@code Transaction}'s
 * {@code discountTotal()} sums every discount with no deduplication, so without a guard a cashier
 * could stack Senior and Veteran for 35% off. {@link #select(EligibilityRule)} replaces any existing
 * rule sharing the new rule's {@code exclusivityGroup} rather than adding a second — one discount
 * per group. Rules with a null/blank group stack freely.</p>
 *
 * <p>Not thread-safe: only ever touched on the Swing event dispatch thread.</p>
 */
public class DiscountSession {

    private final List<EligibilityRule> selected = new ArrayList<>();

    /** @return an unmodifiable snapshot of the currently-selected eligibility rules */
    public List<EligibilityRule> getSelectedRules() {
        return Collections.unmodifiableList(new ArrayList<>(selected));
    }

    /** @return the codes of the currently-selected rules, in selection order */
    public List<String> getSelectedCodes() {
        List<String> codes = new ArrayList<>(selected.size());
        for (EligibilityRule r : selected) {
            codes.add(r.code());
        }
        return codes;
    }

    /** @return {@code true} if no eligibility discount is currently selected */
    public boolean isEmpty() {
        return selected.isEmpty();
    }

    /**
     * Selects an eligibility rule, enforcing exclusivity. Any already-selected rule sharing the new
     * rule's non-blank {@code exclusivityGroup} is removed (replaced); a re-selection of the same
     * code is a no-op replacement.
     *
     * @param rule the rule to select; must not be {@code null}
     * @return the code of a <em>different</em> rule that was replaced within the same exclusivity
     *         group, or {@code null} if nothing was displaced
     */
    public String select(EligibilityRule rule) {
        if (rule == null) throw new IllegalArgumentException("rule must not be null");
        String replaced = null;
        String group = rule.exclusivityGroup();
        boolean hasGroup = group != null && !group.isBlank();
        // Walk the current selection: drop the same code (dedup) and, within the same exclusivity
        // group, drop the incumbent and remember it as the replaced code.
        for (var it = selected.iterator(); it.hasNext(); ) {
            EligibilityRule existing = it.next();
            if (existing.code().equals(rule.code())) {
                it.remove();
            } else if (hasGroup && group.equals(existing.exclusivityGroup())) {
                replaced = existing.code();
                it.remove();
            }
        }
        selected.add(rule);
        return replaced;
    }

    /** Clears the selection — called when a new transaction begins. */
    public void clear() {
        selected.clear();
    }
}
