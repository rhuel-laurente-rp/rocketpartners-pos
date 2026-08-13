package com.rocketpartners.onboarding.posdiscountengine.service;

import com.rocketpartners.onboarding.commons.dto.DiscountResponseDto;
import com.rocketpartners.onboarding.commons.dto.LineItemDto;
import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountCategory;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import com.rocketpartners.onboarding.posdiscountengine.entity.TargetType;
import com.rocketpartners.onboarding.posdiscountengine.repository.DiscountRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscountServiceTest {

    private DiscountRuleRepository repository;
    private DiscountService service;

    @BeforeEach
    void setUp() {
        repository = mock(DiscountRuleRepository.class);
        service = new DiscountService(repository);
    }

    // ---- the worked example -------------------------------------------------------------------

    @Test
    void workedExample_bogoThenSenior_producesExactAmounts() {
        stubPromos(bogo("BOGO_RB", "RB", 2, 1, 1));
        stubCode(senior("SENIOR_20", 20, 2));

        DiscountResponseDto res = service.calculate(txn(List.of("SENIOR_20"), line("RB", 7, "3.79")));

        assertEquals(List.of("BOGO_RB", "SENIOR_20"), codes(res));
        assertEquals(0, new BigDecimal("7.58").compareTo(res.getDiscounts().get(0).getAppliedAmount()));
        assertEquals(0, new BigDecimal("3.79").compareTo(res.getDiscounts().get(1).getAppliedAmount()));
        assertEquals(0, new BigDecimal("11.37").compareTo(res.getDiscountTotal()));
    }

    @Test
    void reversingPriorityProducesADifferentTotal_provingOrderingIsReal() {
        // Senior now priority 1 (applies to the full subtotal), BOGO priority 2.
        stubPromos(bogo("BOGO_RB", "RB", 2, 1, 2));
        stubCode(senior("SENIOR_20", 20, 1));

        DiscountResponseDto res = service.calculate(txn(List.of("SENIOR_20"), line("RB", 7, "3.79")));

        assertEquals(List.of("SENIOR_20", "BOGO_RB"), codes(res));
        assertEquals(0, new BigDecimal("5.31").compareTo(res.getDiscounts().get(0).getAppliedAmount()));
        assertEquals(0, new BigDecimal("7.58").compareTo(res.getDiscounts().get(1).getAppliedAmount()));
        assertEquals(0, new BigDecimal("12.89").compareTo(res.getDiscountTotal()));
    }

    // ---- each type in isolation ---------------------------------------------------------------

    @Test
    void percentOffTransaction_inIsolation() {
        stubPromos();
        stubCode(senior("SENIOR_20", 20, 2));

        DiscountResponseDto res = service.calculate(txn(List.of("SENIOR_20"), line("RB", 2, "10.00")));

        assertEquals(1, res.getDiscounts().size());
        Discount d = res.getDiscounts().get(0);
        assertEquals(DiscountType.PERCENT_OFF, d.getType());
        assertEquals(0, new BigDecimal("4.00").compareTo(d.getAppliedAmount())); // 20% of 20.00
        assertEquals(0, new BigDecimal("4.00").compareTo(res.getDiscountTotal()));
    }

    @Test
    void fixedAmountOffTransaction_inIsolation() {
        stubPromos();
        stubCode(fixed("EMPLOYEE_5", "5.00", 2));

        DiscountResponseDto res = service.calculate(txn(List.of("EMPLOYEE_5"), line("RB", 1, "12.00")));

        assertEquals(1, res.getDiscounts().size());
        Discount d = res.getDiscounts().get(0);
        assertEquals(DiscountType.FIXED_AMOUNT_OFF, d.getType());
        assertEquals(0, new BigDecimal("5.00").compareTo(d.getAppliedAmount()));
    }

    @Test
    void promoOnUpc_inIsolation_amountIsZeroNotNull() {
        stubPromos(bogo("BOGO_RB", "RB", 2, 1, 1));

        DiscountResponseDto res = service.calculate(txn(List.of(), line("RB", 3, "3.79")));

        assertEquals(1, res.getDiscounts().size());
        Discount d = res.getDiscounts().get(0);
        assertEquals(DiscountType.PROMO, d.getType());
        assertEquals(0, new BigDecimal("3.79").compareTo(d.getAppliedAmount())); // 1 free unit
        // PROMO rule carries a null amount; the Discount value must expose ZERO, not null.
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getAmount()));
    }

    // ---- BOGO boundary arithmetic -------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "1, 0",  // no complete group
            "2, 0",
            "3, 1",  // one group -> 1 free
            "6, 2",
            "7, 2",
    })
    void bogoBuy2Get1_freeUnitsByQuantity(int qty, int expectedFree) {
        stubPromos(bogo("BOGO_RB", "RB", 2, 1, 1));

        DiscountResponseDto res = service.calculate(txn(List.of(), line("RB", qty, "3.79")));

        BigDecimal expected = new BigDecimal("3.79").multiply(BigDecimal.valueOf(expectedFree));
        if (expectedFree == 0) {
            assertTrue(res.getDiscounts().isEmpty(), "no discount row when no units are free");
            assertEquals(0, BigDecimal.ZERO.compareTo(res.getDiscountTotal()));
        } else {
            assertEquals(0, expected.compareTo(res.getDiscounts().get(0).getAppliedAmount()));
        }
    }

    @Test
    void bogoBuy2Get2_atQty7_frees2Units_not1() {
        // Guards the "* getQuantity" fix: floor(7/4) = 1 completed group, * get(2) = 2 free units.
        // The buggy formula floor(7/(2+2)) = 1 would free only 1.
        stubPromos(bogo("BOGO_RB", "RB", 2, 2, 1));

        DiscountResponseDto res = service.calculate(txn(List.of(), line("RB", 7, "3.79")));

        assertEquals(0, new BigDecimal("7.58").compareTo(res.getDiscounts().get(0).getAppliedAmount()));
    }

    // ---- clamping -----------------------------------------------------------------------------

    @Test
    void fixedAmountLargerThanNet_isClampedAgainstReducedNet_afterPromoRunsFirst() {
        // Promo (priority 1) reduces the net first; the fixed amount (priority 2) is far larger than
        // what remains and must clamp to the running net, not the subtotal.
        stubPromos(bogo("BOGO_X", "X", 2, 1, 1));       // qty 3 @ 2.00 -> 1 free -> -2.00, net 4.00
        stubCode(fixed("EMPLOYEE_100", "100.00", 2));   // clamps to remaining 4.00

        DiscountResponseDto res = service.calculate(txn(List.of("EMPLOYEE_100"), line("X", 3, "2.00")));

        assertEquals(List.of("BOGO_X", "EMPLOYEE_100"), codes(res));
        assertEquals(0, new BigDecimal("2.00").compareTo(res.getDiscounts().get(0).getAppliedAmount()));
        assertEquals(0, new BigDecimal("4.00").compareTo(res.getDiscounts().get(1).getAppliedAmount()));
        // Total equals the subtotal (6.00) and never exceeds it.
        assertEquals(0, new BigDecimal("6.00").compareTo(res.getDiscountTotal()));
    }

    // ---- validation ---------------------------------------------------------------------------

    @Test
    void conflictingExclusivityCodes_areRejected() {
        stubCode(senior("SENIOR_20", 20, 2));      // group CUSTOMER_ELIGIBILITY
        stubCode(fixed("EMPLOYEE_5", "5.00", 2));  // same group CUSTOMER_ELIGIBILITY

        assertThrows(DiscountValidationException.class, () ->
                service.calculate(txn(List.of("SENIOR_20", "EMPLOYEE_5"), line("RB", 1, "10.00"))));
    }

    @Test
    void unknownEligibilityCode_isRejected() {
        when(repository.findByCode("BOGUS")).thenReturn(Optional.empty());

        assertThrows(DiscountValidationException.class, () ->
                service.calculate(txn(List.of("BOGUS"), line("RB", 1, "10.00"))));
    }

    @Test
    void emptyBasket_returnsEmptyDiscountList() {
        stubPromos();

        DiscountResponseDto res = service.calculate(txn(List.of()));

        assertTrue(res.getDiscounts().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(res.getDiscountTotal()));
    }

    // ---- rounding contract --------------------------------------------------------------------

    @Test
    void everyAppliedAmount_comesBackAtTwoDecimalPlaces() {
        stubPromos(bogo("BOGO_RB", "RB", 2, 1, 1));
        stubCode(senior("SENIOR_20", 20, 2));

        DiscountResponseDto res = service.calculate(txn(List.of("SENIOR_20"), line("RB", 7, "3.79")));

        for (Discount d : res.getDiscounts()) {
            assertEquals(2, d.getAppliedAmount().scale(), "appliedAmount must be scaled to 2 places");
        }
        assertEquals(2, res.getDiscountTotal().scale());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void stubPromos(DiscountRule... rules) {
        when(repository.findByCategoryAndActiveTrueOrderByPriorityAsc(DiscountCategory.PROMOTIONAL))
                .thenReturn(List.of(rules));
    }

    private void stubCode(DiscountRule rule) {
        when(repository.findByCode(rule.getCode())).thenReturn(Optional.of(rule));
    }

    private static List<String> codes(DiscountResponseDto res) {
        return res.getDiscounts().stream().map(Discount::getDiscountId).toList();
    }

    private static DiscountRule bogo(String code, String upc, int buy, int get, int priority) {
        return DiscountRule.builder()
                .code(code).description(code)
                .category(DiscountCategory.PROMOTIONAL).targetType(TargetType.UPC).targetValue(upc)
                .discountType(DiscountType.PROMO).buyQuantity(buy).getQuantity(get)
                .priority(priority).active(true).build();
    }

    private static DiscountRule senior(String code, int percent, int priority) {
        return DiscountRule.builder()
                .code(code).description(code)
                .category(DiscountCategory.ELIGIBILITY).targetType(TargetType.TRANSACTION)
                .discountType(DiscountType.PERCENT_OFF).amount(new BigDecimal(percent))
                .priority(priority).exclusivityGroup("CUSTOMER_ELIGIBILITY").active(true).build();
    }

    private static DiscountRule fixed(String code, String amount, int priority) {
        return DiscountRule.builder()
                .code(code).description(code)
                .category(DiscountCategory.ELIGIBILITY).targetType(TargetType.TRANSACTION)
                .discountType(DiscountType.FIXED_AMOUNT_OFF).amount(new BigDecimal(amount))
                .priority(priority).exclusivityGroup("CUSTOMER_ELIGIBILITY").active(true).build();
    }

    private static LineItemDto line(String upc, int qty, String unitPrice) {
        return new LineItemDto(upc, upc, qty, new BigDecimal(unitPrice));
    }

    private static TransactionDto txn(List<String> eligibilityCodes, LineItemDto... lines) {
        return new TransactionDto("t1", null, List.of(lines), null, eligibilityCodes);
    }
}
