package com.rocketpartners.onboarding.posdiscountengine.repository;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountCategory;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import com.rocketpartners.onboarding.posdiscountengine.entity.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class DiscountRuleRepositoryTest {

    @Autowired
    private DiscountRuleRepository repository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        // Two active eligibility rules with distinct priorities (2 then 1 -> should sort 1,2).
        repository.save(eligibility("SENIOR_20", "Senior Citizen Discount 20%", new BigDecimal("20"), 2, true));
        repository.save(eligibility("VETERAN_15", "Veteran Discount 15%", new BigDecimal("15"), 1, true));
        // An inactive eligibility rule that finders must exclude.
        repository.save(eligibility("EXPIRED_10", "Expired Discount 10%", new BigDecimal("10"), 2, false));
        // An active promotional UPC rule.
        repository.save(DiscountRule.builder()
                .code("BOGO_MONSTER").description("Buy 2 Get 1 Free Monster Energy")
                .category(DiscountCategory.PROMOTIONAL).targetType(TargetType.UPC)
                .targetValue("070847811169").discountType(DiscountType.PROMO)
                .buyQuantity(2).getQuantity(1).priority(1).active(true).build());
    }

    @Test
    void findByCategoryAndActive_excludesInactiveAndOtherCategories_andSortsByPriority() {
        List<DiscountRule> eligibility =
                repository.findByCategoryAndActiveTrueOrderByPriorityAsc(DiscountCategory.ELIGIBILITY);

        assertEquals(List.of("VETERAN_15", "SENIOR_20"),
                eligibility.stream().map(DiscountRule::getCode).toList(),
                "inactive rule excluded, promotional excluded, sorted by ascending priority");
    }

    @Test
    void findByCategoryAndActive_returnsPromotionalSeparately() {
        List<DiscountRule> promo =
                repository.findByCategoryAndActiveTrueOrderByPriorityAsc(DiscountCategory.PROMOTIONAL);

        assertEquals(1, promo.size());
        assertEquals("BOGO_MONSTER", promo.get(0).getCode());
    }

    @Test
    void findByTargetValueAndActive_findsTheUpcRule() {
        List<DiscountRule> rules = repository.findByTargetValueAndActiveTrue("070847811169");

        assertEquals(1, rules.size());
        assertEquals("BOGO_MONSTER", rules.get(0).getCode());
    }

    @Test
    void findByCode_findsSeededRule() {
        assertTrue(repository.findByCode("SENIOR_20").isPresent());
        assertTrue(repository.findByCode("NOPE").isEmpty());
    }

    private static DiscountRule eligibility(String code, String description, BigDecimal amount,
                                            int priority, boolean active) {
        return DiscountRule.builder()
                .code(code).description(description)
                .category(DiscountCategory.ELIGIBILITY).targetType(TargetType.TRANSACTION)
                .discountType(DiscountType.PERCENT_OFF).amount(amount)
                .priority(priority).exclusivityGroup("CUSTOMER_ELIGIBILITY").active(active)
                .build();
    }
}
