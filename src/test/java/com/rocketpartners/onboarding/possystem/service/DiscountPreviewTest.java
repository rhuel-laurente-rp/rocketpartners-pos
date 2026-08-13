package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.dto.DiscountResponseDto;
import com.rocketpartners.onboarding.commons.dto.LineItemDto;
import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountCategory;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import com.rocketpartners.onboarding.posdiscountengine.entity.TargetType;
import com.rocketpartners.onboarding.posdiscountengine.repository.DiscountRuleRepository;
import com.rocketpartners.onboarding.posdiscountengine.service.DiscountService;
import com.rocketpartners.onboarding.possystem.component.EligibilityRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the POS's one piece of local discount arithmetic, and — crucially — pins that for a single
 * eligibility discount it agrees with the engine to the penny. The two can only ever diverge where
 * a promotion reduces the running net before an eligibility percentage; with a single eligibility
 * discount and no promotions, the net is the subtotal and the figures are identical.
 */
class DiscountPreviewTest {

    private static final EligibilityRule SENIOR =
            new EligibilityRule("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF, new BigDecimal("20"), "G");

    @Test
    void percentOff_isSubtotalTimesAmountOverHundred() {
        // 20% of 26.53 = 5.306 (exact; the display layer rounds to 5.31).
        BigDecimal preview = DiscountPreview.previewAmount(SENIOR, new BigDecimal("26.53"));
        assertThat(preview).isEqualByComparingTo(new BigDecimal("5.306"));
    }

    @Test
    void fixedAmountOff_isClampedToSubtotal() {
        EligibilityRule five =
                new EligibilityRule("E5", "Employee $5", DiscountType.FIXED_AMOUNT_OFF, new BigDecimal("5.00"), "G");
        assertThat(DiscountPreview.previewAmount(five, new BigDecimal("10.00")))
                .isEqualByComparingTo(new BigDecimal("5.00"));
        // A flat discount can never take the basket below zero.
        assertThat(DiscountPreview.previewAmount(five, new BigDecimal("3.00")))
                .isEqualByComparingTo(new BigDecimal("3.00"));
    }

    @Test
    void promoAndNullAmount_previewAsZero() {
        EligibilityRule promo =
                new EligibilityRule("P", "Promo", DiscountType.PROMO, null, null);
        EligibilityRule nullAmount =
                new EligibilityRule("N", "Null", DiscountType.PERCENT_OFF, null, "G");
        assertThat(DiscountPreview.previewAmount(promo, new BigDecimal("10.00"))).isEqualByComparingTo("0");
        assertThat(DiscountPreview.previewAmount(nullAmount, new BigDecimal("10.00"))).isEqualByComparingTo("0");
    }

    @Test
    void previewTotal_isClampedToSubtotal() {
        EligibilityRule big =
                new EligibilityRule("BIG", "Big", DiscountType.FIXED_AMOUNT_OFF, new BigDecimal("100"), "G");
        assertThat(DiscountPreview.previewTotal(List.of(big), new BigDecimal("12.00")))
                .isEqualByComparingTo(new BigDecimal("12.00"));
    }

    @Test
    void previewEqualsEngineFigure_forSingleEligibilityDiscount() {
        // Build the SAME rule the engine holds, wire a mocked repository, and run the real engine
        // DiscountService against a single-eligibility, no-promotion basket. The engine's applied
        // amount (scaled to 2dp) must equal the POS preview scaled to 2dp.
        BigDecimal subtotal = new BigDecimal("26.53");

        DiscountRule seniorRule = DiscountRule.builder()
                .code("SENIOR_20").description("Senior 20%")
                .category(DiscountCategory.ELIGIBILITY).targetType(TargetType.TRANSACTION)
                .discountType(DiscountType.PERCENT_OFF).amount(new BigDecimal("20"))
                .priority(2).exclusivityGroup("CUSTOMER_ELIGIBILITY").active(true)
                .build();

        DiscountRuleRepository repo = mock(DiscountRuleRepository.class);
        when(repo.findByCode("SENIOR_20")).thenReturn(Optional.of(seniorRule));
        when(repo.findByCategoryAndActiveTrueOrderByPriorityAsc(any())).thenReturn(List.of());

        DiscountService engine = new DiscountService(repo);
        TransactionDto request = new TransactionDto("t1", null,
                List.of(new LineItemDto("UPC-X", "Thing", 1, subtotal)),
                subtotal, List.of("SENIOR_20"));
        DiscountResponseDto response = engine.calculate(request);

        BigDecimal engineFigure = response.getDiscountTotal();
        BigDecimal previewFigure = DiscountPreview.previewAmount(SENIOR, subtotal)
                .setScale(2, RoundingMode.HALF_UP);

        assertThat(previewFigure).isEqualByComparingTo(engineFigure);
    }
}
