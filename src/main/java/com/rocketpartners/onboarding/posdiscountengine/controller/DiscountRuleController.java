package com.rocketpartners.onboarding.posdiscountengine.controller;

import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountCategory;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import com.rocketpartners.onboarding.posdiscountengine.repository.DiscountRuleRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only endpoint exposing the discount rules the POS dialog offers.
 *
 * <p>This is what keeps the cashier dialog data-driven: it asks the engine which active rules of a
 * category exist rather than hard-coding "Senior" and "Veteran". No calculation happens here —
 * evaluating a rule against a transaction is a later branch.</p>
 */
@RestController
public class DiscountRuleController {

    private final DiscountRuleRepository repository;

    public DiscountRuleController(DiscountRuleRepository repository) {
        this.repository = repository;
    }

    /** Active rules of the requested category, in application order. */
    @GetMapping("/discounts/rules")
    public List<DiscountRule> rulesByCategory(@RequestParam DiscountCategory category) {
        return repository.findByCategoryAndActiveTrueOrderByPriorityAsc(category);
    }
}
