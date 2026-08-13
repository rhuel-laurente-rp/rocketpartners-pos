package com.rocketpartners.onboarding.commons.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the arithmetic the discount feature depends on at the aggregate: engine-computed discounts,
 * once applied, reduce {@code discountTotal} and {@code grandTotal}; tax is charged on
 * {@code subtotal − discountTotal}; and Next Dollar ceils the <em>discounted</em> grand total.
 */
class TransactionDiscountTotalsTest {

    private static final BigDecimal TAX_7 = new BigDecimal("0.07");
    private static final Item ITEM = new Item("UPC-X", "Thing", new BigDecimal("3.79"));

    private Transaction totaledBasketOf7() {
        Transaction tx = new Transaction("t1", java.time.Instant.EPOCH, TAX_7);
        tx.addLineItem(ITEM, 7); // subtotal 26.53
        tx.total();
        return tx;
    }

    @Test
    void percentOff_reducesDiscountTotalAndGrandTotal_andTaxesThePostDiscountSubtotal() {
        Transaction tx = totaledBasketOf7();
        // Engine-shaped values: 20% of 26.53 = 5.31 applied.
        tx.applyDiscount(new Discount("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF,
                new BigDecimal("20"), new BigDecimal("5.31")));

        assertThat(tx.discountTotal()).isEqualByComparingTo("5.31");
        // Tax on (26.53 - 5.31) = 21.22 * 0.07 = 1.4854.
        assertThat(tx.taxTotal()).isEqualByComparingTo("1.4854");
        // Grand total = 26.53 - 5.31 + 1.4854 = 22.7054 -> 22.71.
        assertThat(tx.grandTotal()).isEqualByComparingTo("22.71");
    }

    @Test
    void fixedAmountOff_reducesTotals() {
        Transaction tx = totaledBasketOf7();
        tx.applyDiscount(new Discount("EMPLOYEE_5", "Employee $5", DiscountType.FIXED_AMOUNT_OFF,
                new BigDecimal("5.00"), new BigDecimal("5.00")));
        assertThat(tx.discountTotal()).isEqualByComparingTo("5.00");
        // (26.53 - 5.00) * 1.07 = 21.53 * 1.07 = 23.0371 -> 23.04.
        assertThat(tx.grandTotal()).isEqualByComparingTo("23.04");
    }

    @Test
    void promoDiscount_reducesTotals_andStacksWithEligibilityViaSummedAmounts() {
        Transaction tx = totaledBasketOf7();
        // Promotion applied first (6.58), then eligibility computed by the engine against the net
        // (3.79) — the POS just sums the two applied amounts.
        tx.applyDiscount(new Discount("BOGO", "Buy 2 Get 1", DiscountType.PROMO,
                BigDecimal.ZERO, new BigDecimal("6.58")));
        tx.applyDiscount(new Discount("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF,
                new BigDecimal("20"), new BigDecimal("3.79")));
        assertThat(tx.discountTotal()).isEqualByComparingTo("10.37");
        // (26.53 - 10.37) * 1.07 = 16.16 * 1.07 = 17.2912 -> 17.29.
        assertThat(tx.grandTotal()).isEqualByComparingTo("17.29");
    }

    @Test
    void payNextDollar_ceilsTheDiscountedGrandTotal_notTheUndiscounted() {
        Transaction tx = totaledBasketOf7();
        tx.applyDiscount(new Discount("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF,
                new BigDecimal("20"), new BigDecimal("5.31")));
        // Discounted grand total is 22.71 -> ceils to 23.00 (NOT ceil of the undiscounted 28.39).
        tx.payNextDollar();
        assertThat(tx.amountDue()).isEqualByComparingTo("23.00");
        assertThat(tx.changeDue()).isEqualByComparingTo("0.00");
    }
}
