package com.rocketpartners.onboarding.commons.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionTotalsTest {

    private static final BigDecimal NO_TAX = BigDecimal.ZERO;
    private static final BigDecimal SEVEN_PERCENT = new BigDecimal("0.07");
    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");

    private static Item item(String upc, String price) {
        return new Item(upc, "desc-" + upc, new BigDecimal(price));
    }

    private static Discount fixedOff(String appliedAmount) {
        return new Discount("D-1", "test", DiscountType.FIXED_AMOUNT_OFF,
                new BigDecimal(appliedAmount), new BigDecimal(appliedAmount));
    }

    @Test
    void emptyTransaction_allTotalsZero() {
        Transaction tx = new Transaction(SEVEN_PERCENT);
        assertThat(tx.subtotal()).isEqualByComparingTo("0");
        assertThat(tx.discountTotal()).isEqualByComparingTo("0");
        assertThat(tx.taxTotal()).isEqualByComparingTo("0");
        assertThat(tx.grandTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    void subtotal_sumsAcrossMultipleLines() {
        Transaction tx = new Transaction(NO_TAX);
        tx.addLineItem(item("A", "1.99"), 2); // 3.98
        tx.addLineItem(item("B", "0.50"), 4); // 2.00
        assertThat(tx.subtotal()).isEqualByComparingTo("5.98");
    }

    @Test
    void addLineItem_accumulatesQuantityForSameUpc() {
        Transaction tx = new Transaction(NO_TAX);
        Item apple = item("A", "1.00");
        tx.addLineItem(apple, 2);
        tx.addLineItem(apple, 3);
        assertThat(tx.getLineItems()).hasSize(1);
        assertThat(tx.getLineItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(tx.subtotal()).isEqualByComparingTo("5.00");
    }

    @Test
    void taxTotal_isSubtotalMinusDiscountTimesRate() {
        Transaction tx = new Transaction(TEN_PERCENT);
        tx.addLineItem(item("A", "10.00"), 1);
        tx.total();
        tx.applyDiscount(fixedOff("2.00"));
        // tax base = (10.00 - 2.00) = 8.00; tax = 8.00 * 0.10 = 0.80
        assertThat(tx.taxTotal()).isEqualByComparingTo("0.80");
    }

    @Test
    void taxTotal_withNoDiscount_isSubtotalTimesRate() {
        Transaction tx = new Transaction(TEN_PERCENT);
        tx.addLineItem(item("A", "10.00"), 1);
        assertThat(tx.taxTotal()).isEqualByComparingTo("1.00");
    }

    @Test
    void voidedLine_excludedFromAllTotals() {
        Transaction tx = new Transaction(TEN_PERCENT);
        tx.addLineItem(item("A", "5.00"), 1);
        LineItem line = tx.getLineItems().get(0);
        tx.voidLine(line);
        assertThat(tx.subtotal()).isEqualByComparingTo("0");
        assertThat(tx.taxTotal()).isEqualByComparingTo("0");
    }

    @Test
    void discountTotal_sumsAppliedAmountsOnly() {
        Transaction tx = new Transaction(NO_TAX);
        tx.addLineItem(item("A", "10.00"), 1);
        tx.total();
        // amount and appliedAmount deliberately differ to prove only appliedAmount is summed.
        tx.applyDiscount(new Discount("D-1", "10% off", DiscountType.PERCENT_OFF,
                new BigDecimal("10"), new BigDecimal("1.00")));
        tx.applyDiscount(new Discount("D-2", "loyalty", DiscountType.FIXED_AMOUNT_OFF,
                new BigDecimal("2.00"), new BigDecimal("2.00")));
        assertThat(tx.discountTotal()).isEqualByComparingTo("3.00");
    }

    @Test
    void grandTotal_rollsUpAllComponentsAndRoundsHalfUp() {
        // subtotal 0.05, no discount, tax 10% → tax = 0.005 → raw grand = 0.055 → HALF_UP → 0.06
        Transaction tx = new Transaction(TEN_PERCENT);
        tx.addLineItem(item("A", "0.05"), 1);
        assertThat(tx.grandTotal()).isEqualByComparingTo("0.06");
    }

    @Test
    void grandTotal_isSubtotalMinusDiscountPlusTax() {
        Transaction tx = new Transaction(TEN_PERCENT);
        tx.addLineItem(item("A", "10.00"), 1);
        tx.total();
        tx.applyDiscount(fixedOff("2.00"));
        // subtotal 10.00 - discount 2.00 + tax on 8.00 at 10% (=0.80) = 8.80
        assertThat(tx.grandTotal()).isEqualByComparingTo("8.80");
    }

    @Test
    void changeDue_onCashOverpayment_returnsDifference() {
        Transaction tx = new Transaction(NO_TAX);
        tx.addLineItem(item("A", "7.50"), 1);
        tx.total();
        tx.tender(TenderType.CASH, new BigDecimal("10.00"), null);
        assertThat(tx.changeDue()).isEqualByComparingTo("2.50");
    }

    @Test
    void changeDue_onCardTender_isZero() {
        Transaction tx = new Transaction(NO_TAX);
        tx.addLineItem(item("A", "7.50"), 1);
        tx.total();
        tx.tender(TenderType.DEBIT, new BigDecimal("7.50"));
        assertThat(tx.changeDue()).isEqualByComparingTo("0");
    }

}
