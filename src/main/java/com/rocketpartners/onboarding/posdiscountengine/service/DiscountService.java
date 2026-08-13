package com.rocketpartners.onboarding.posdiscountengine.service;

import com.rocketpartners.onboarding.commons.dto.DiscountResponseDto;
import com.rocketpartners.onboarding.commons.dto.LineItemDto;
import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountCategory;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import com.rocketpartners.onboarding.posdiscountengine.repository.DiscountRuleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the discount rules against a transaction and returns the discounts to apply.
 *
 * <p><strong>Evaluation order is the load-bearing invariant of this class.</strong> Rules are
 * applied in ascending {@code priority}, and each rule's {@code appliedAmount} is computed against
 * the <em>running net</em> — the subtotal minus everything already applied — not the original
 * subtotal. This is standard retail practice: product promotions (priority 1) reduce the basket
 * first, then customer-eligibility percentages (priority 2) apply to what remains.</p>
 *
 * <p>Worked example — 7 × item @ 3.79, subtotal 26.53:</p>
 * <pre>
 *   priority 1  BOGO buy2get1   floor(7/3)*1 = 2 free  -7.58  net 18.95
 *   priority 2  Senior 20%      20% of 18.95           -3.79  net 15.16
 *                                             discountTotal = 11.37
 * </pre>
 * <p>Senior takes 20% of 18.95, <em>not</em> of 26.53. Reversing the two priorities yields a
 * different total (12.89) — the ordering is real, not incidental. Because all sequencing lives in
 * the amounts computed here, {@code Transaction.discountTotal()}'s naive sum stays correct and the
 * domain model needs no changes. This ordering is invisible from the model and is the single most
 * likely source of a wrong total, which is why it is spelled out here.</p>
 *
 * <p>Each {@code appliedAmount} is scaled to 2 decimal places <em>before</em> it is subtracted from
 * the running net and accumulated, so the returned rows always sum exactly to {@code discountTotal}.
 * Every applied amount is clamped to the running net, so the net never goes negative and
 * {@code discountTotal} never exceeds the subtotal.</p>
 *
 * <p><strong>Exclusivity is enforced only across the supplied eligibility codes.</strong>
 * Promotional rules stack freely and are not checked against exclusivity groups. This is a
 * deliberate decision for this phase; if a promotional rule ever carries an {@code exclusivityGroup}
 * this method must be revisited.</p>
 */
@Service
public class DiscountService {

    private static final int MONEY_SCALE = 2;

    private final DiscountRuleRepository repository;

    public DiscountService(DiscountRuleRepository repository) {
        this.repository = repository;
    }

    public DiscountResponseDto calculate(TransactionDto request) {
        List<LineItemDto> lineItems = request.getLineItems() == null ? List.of() : request.getLineItems();
        validateLineItems(lineItems);

        List<String> codes = request.getAppliedEligibilityCodes() == null
                ? List.of() : request.getAppliedEligibilityCodes();
        List<DiscountRule> eligibilityRules = resolveAndValidateEligibility(codes);

        BigDecimal subtotal = lineItems.stream()
                .map(li -> li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Candidates: the selected eligibility rules plus every active promotional rule (promos
        // apply automatically when the basket qualifies). Ascending priority, then code for a stable
        // order among ties.
        List<DiscountRule> candidates = new ArrayList<>(eligibilityRules);
        candidates.addAll(repository.findByCategoryAndActiveTrueOrderByPriorityAsc(DiscountCategory.PROMOTIONAL));
        candidates.sort(Comparator.comparingInt(DiscountRule::getPriority).thenComparing(DiscountRule::getCode));

        BigDecimal net = subtotal;
        BigDecimal discountTotal = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<Discount> discounts = new ArrayList<>();

        for (DiscountRule rule : candidates) {
            BigDecimal raw = rawAmountFor(rule, lineItems, net);
            if (raw == null) {
                continue; // rule does not apply to this basket
            }
            BigDecimal applied = raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (applied.compareTo(net) > 0) {
                // Clamp to the running net (floor to 2dp so we never over-discount past it).
                applied = net.setScale(MONEY_SCALE, RoundingMode.DOWN);
            }
            if (applied.signum() <= 0) {
                continue; // nothing left to discount, or a zero-value rule — omit the row
            }
            net = net.subtract(applied);
            discountTotal = discountTotal.add(applied);

            BigDecimal amount = rule.getAmount() == null ? BigDecimal.ZERO : rule.getAmount();
            discounts.add(new Discount(rule.getCode(), rule.getDescription(),
                    rule.getDiscountType(), amount, applied));
        }

        return new DiscountResponseDto(discounts, discountTotal);
    }

    /**
     * Computes the unrounded reduction a rule produces against the current {@code net}, or
     * {@code null} if the rule does not apply. The loader guarantees only supported type/target
     * combinations are persisted, so the three arms below are exhaustive in practice.
     */
    private BigDecimal rawAmountFor(DiscountRule rule, List<LineItemDto> lineItems, BigDecimal net) {
        return switch (rule.getDiscountType()) {
            case PROMO -> promoAmount(rule, lineItems);
            // Exact percent: multiply then shift two places rather than divide(100), which would
            // throw on a non-terminating quotient if the divisor were ever parameterised.
            case PERCENT_OFF -> rule.getAmount() == null ? null : net.multiply(rule.getAmount()).movePointLeft(2);
            case FIXED_AMOUNT_OFF -> rule.getAmount();
        };
    }

    /**
     * Buy-N-get-M on a single UPC. {@code freeUnits = floor(qty / (buy + get)) * get} — one completed
     * group of {@code buy + get} units yields {@code get} free ones (buy-2-get-2 at qty 7 gives
     * {@code floor(7/4) * 2 = 2}, not 1). Same-UPC only, so every unit is the same price and which
     * unit is free is trivial; category-wide mix-and-match with cheapest-first selection is a future
     * extension, not built here.
     */
    private BigDecimal promoAmount(DiscountRule rule, List<LineItemDto> lineItems) {
        int buy = rule.getBuyQuantity() == null ? 0 : rule.getBuyQuantity();
        int get = rule.getGetQuantity() == null ? 0 : rule.getGetQuantity();
        int group = buy + get;
        if (group <= 0 || get <= 0) {
            return null;
        }
        int qty = 0;
        BigDecimal unitPrice = null;
        for (LineItemDto li : lineItems) {
            if (rule.getTargetValue() != null && rule.getTargetValue().equals(li.getUpc())) {
                qty += li.getQuantity();
                if (unitPrice == null) {
                    unitPrice = li.getUnitPrice();
                }
            }
        }
        if (unitPrice == null) {
            return null; // basket does not contain the targeted UPC
        }
        int freeUnits = (qty / group) * get;
        if (freeUnits == 0) {
            return null;
        }
        return unitPrice.multiply(BigDecimal.valueOf(freeUnits));
    }

    private void validateLineItems(List<LineItemDto> lineItems) {
        for (LineItemDto li : lineItems) {
            if (li == null) {
                throw new DiscountValidationException("line item must not be null");
            }
            if (li.getUpc() == null || li.getUpc().isBlank()) {
                throw new DiscountValidationException("line item upc must not be blank");
            }
            if (li.getQuantity() < 0) {
                throw new DiscountValidationException("line item quantity must not be negative: " + li.getQuantity());
            }
            if (li.getUnitPrice() == null) {
                throw new DiscountValidationException("line item unitPrice must not be null");
            }
            if (li.getUnitPrice().signum() < 0) {
                throw new DiscountValidationException("line item unitPrice must not be negative: " + li.getUnitPrice());
            }
        }
    }

    /**
     * Resolves each supplied code to an active {@link DiscountCategory#ELIGIBILITY} rule and enforces
     * exclusivity across the supplied set. Rejects unknown/ineligible codes and any pair of distinct
     * codes sharing an exclusivity group. Duplicate codes are collapsed so the same rule is never
     * applied twice.
     */
    private List<DiscountRule> resolveAndValidateEligibility(List<String> codes) {
        List<DiscountRule> resolved = new ArrayList<>();
        Map<String, String> groupToCode = new HashMap<>();
        for (String code : new LinkedHashSet<>(codes)) {
            DiscountRule rule = repository.findByCode(code)
                    .filter(DiscountRule::isActive)
                    .filter(r -> r.getCategory() == DiscountCategory.ELIGIBILITY)
                    .orElseThrow(() -> new DiscountValidationException("unknown eligibility code: " + code));

            String group = rule.getExclusivityGroup();
            if (group != null && !group.isBlank()) {
                String existing = groupToCode.putIfAbsent(group, code);
                if (existing != null) {
                    throw new DiscountValidationException(
                            "conflicting eligibility codes in exclusivity group '" + group + "': "
                                    + existing + " and " + code);
                }
            }
            resolved.add(rule);
        }
        return resolved;
    }
}
