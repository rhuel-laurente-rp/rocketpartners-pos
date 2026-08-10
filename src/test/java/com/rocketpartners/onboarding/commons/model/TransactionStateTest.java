package com.rocketpartners.onboarding.commons.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionStateTest {

    private static final BigDecimal NO_TAX = BigDecimal.ZERO;

    private static Item widget() {
        return new Item("UPC-1", "Widget", new BigDecimal("1.00"));
    }

    private static Transaction inProgress() {
        return new Transaction(NO_TAX);
    }

    private static Transaction totaled() {
        Transaction tx = inProgress();
        tx.addLineItem(widget(), 1);
        tx.total();
        return tx;
    }

    private static Transaction paid() {
        Transaction tx = totaled();
        tx.tender(TenderType.CASH, new BigDecimal("1.00"), null);
        return tx;
    }

    private static Transaction voided() {
        Transaction tx = inProgress();
        tx.voidBasket();
        return tx;
    }

    @Test
    void freshTransaction_startsInProgress() {
        assertThat(inProgress().getState()).isEqualTo(TransactionState.IN_PROGRESS);
    }

    @Test
    void total_transitionsInProgressToTotaled() {
        Transaction tx = inProgress();
        tx.addLineItem(widget(), 1);
        tx.total();
        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
    }

    @Test
    void tender_transitionsTotaledToPaid() {
        Transaction tx = totaled();
        tx.tender(TenderType.CASH, new BigDecimal("1.00"), null);
        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        assertThat(tx.getTenderType()).isEqualTo(TenderType.CASH);
    }

    @Test
    void tender_cardOverloadRejectsCash() {
        Transaction tx = totaled();
        assertThatThrownBy(() -> tx.tender(TenderType.CASH, new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tender_cashOverloadRejectsCardTypes() {
        Transaction tx = totaled();
        assertThatThrownBy(() -> tx.tender(TenderType.DEBIT, new BigDecimal("1.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
        Transaction tx2 = totaled();
        assertThatThrownBy(() -> tx2.tender(TenderType.CREDIT, new BigDecimal("1.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tender_cashOverloadRecordsSettledAmountDueForNextDollar() {
        Transaction tx = new Transaction(NO_TAX);
        tx.addLineItem(new Item("UPC-1", "Widget", new BigDecimal("7.30")), 1);
        tx.total();
        tx.tender(TenderType.CASH, new BigDecimal("8.00"), new BigDecimal("8.00"));
        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        assertThat(tx.amountDue()).isEqualByComparingTo("8.00");
        assertThat(tx.changeDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void tender_cashOverloadWithNullAmountDueFallsBackToGrandTotal() {
        Transaction tx = new Transaction(NO_TAX);
        tx.addLineItem(new Item("UPC-1", "Widget", new BigDecimal("7.30")), 1);
        tx.total();
        tx.tender(TenderType.CASH, new BigDecimal("10.00"), null);
        assertThat(tx.amountDue()).isEqualByComparingTo("7.30");
        assertThat(tx.changeDue()).isEqualByComparingTo("2.70");
    }

    @Test
    void voidBasket_fromInProgress_transitionsToVoided() {
        Transaction tx = inProgress();
        tx.voidBasket();
        assertThat(tx.getState()).isEqualTo(TransactionState.VOIDED);
    }

    @Test
    void voidBasket_fromTotaled_transitionsToVoided() {
        Transaction tx = totaled();
        tx.voidBasket();
        assertThat(tx.getState()).isEqualTo(TransactionState.VOIDED);
    }

    @Test
    void addLineItem_illegalFromTotaled() {
        Transaction tx = totaled();
        assertThatThrownBy(() -> tx.addLineItem(widget(), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addLineItem_illegalFromPaid() {
        Transaction tx = paid();
        assertThatThrownBy(() -> tx.addLineItem(widget(), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addLineItem_illegalFromVoided() {
        Transaction tx = voided();
        assertThatThrownBy(() -> tx.addLineItem(widget(), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voidLine_illegalFromTotaled() {
        Transaction tx = inProgress();
        tx.addLineItem(widget(), 1);
        LineItem line = tx.getLineItems().get(0);
        tx.total();
        assertThatThrownBy(() -> tx.voidLine(line)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voidLine_rejectsLineNotOnTransaction() {
        Transaction tx = inProgress();
        LineItem foreign = new LineItem(widget(), 1);
        assertThatThrownBy(() -> tx.voidLine(foreign)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void total_illegalFromTotaled() {
        Transaction tx = totaled();
        assertThatThrownBy(tx::total).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void total_illegalFromPaid() {
        Transaction tx = paid();
        assertThatThrownBy(tx::total).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void total_illegalFromVoided() {
        Transaction tx = voided();
        assertThatThrownBy(tx::total).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tender_illegalFromInProgress() {
        Transaction tx = inProgress();
        assertThatThrownBy(() -> tx.tender(TenderType.CASH, new BigDecimal("1.00"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tender_illegalFromPaid() {
        Transaction tx = paid();
        assertThatThrownBy(() -> tx.tender(TenderType.CASH, new BigDecimal("1.00"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tender_illegalFromVoided() {
        Transaction tx = voided();
        assertThatThrownBy(() -> tx.tender(TenderType.CASH, new BigDecimal("1.00"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyDiscount_illegalFromInProgress() {
        Transaction tx = inProgress();
        Discount d = new Discount("D", "test", DiscountType.PROMO, BigDecimal.ZERO, new BigDecimal("1.00"));
        assertThatThrownBy(() -> tx.applyDiscount(d)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyDiscount_illegalFromPaid() {
        Transaction tx = paid();
        Discount d = new Discount("D", "test", DiscountType.PROMO, BigDecimal.ZERO, new BigDecimal("1.00"));
        assertThatThrownBy(() -> tx.applyDiscount(d)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyDiscount_illegalFromVoided() {
        Transaction tx = voided();
        Discount d = new Discount("D", "test", DiscountType.PROMO, BigDecimal.ZERO, new BigDecimal("1.00"));
        assertThatThrownBy(() -> tx.applyDiscount(d)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voidBasket_illegalFromPaid() {
        Transaction tx = paid();
        assertThatThrownBy(tx::voidBasket).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voidBasket_illegalFromVoided() {
        Transaction tx = voided();
        assertThatThrownBy(tx::voidBasket).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateLineItemQuantity_inProgress_setsQuantity() {
        Transaction tx = inProgress();
        tx.addLineItem(widget(), 1);
        LineItem line = tx.getLineItems().get(0);
        tx.updateLineItemQuantity(line, 5);
        assertThat(line.getQuantity()).isEqualTo(5);
    }

    @Test
    void updateLineItemQuantity_illegalFromTotaled() {
        Transaction tx = inProgress();
        tx.addLineItem(widget(), 1);
        LineItem line = tx.getLineItems().get(0);
        tx.total();
        assertThatThrownBy(() -> tx.updateLineItemQuantity(line, 3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateLineItemQuantity_rejectsZero() {
        Transaction tx = inProgress();
        tx.addLineItem(widget(), 1);
        LineItem line = tx.getLineItems().get(0);
        assertThatThrownBy(() -> tx.updateLineItemQuantity(line, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateLineItemQuantity_rejectsNegative() {
        Transaction tx = inProgress();
        tx.addLineItem(widget(), 1);
        LineItem line = tx.getLineItems().get(0);
        assertThatThrownBy(() -> tx.updateLineItemQuantity(line, -2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateLineItemQuantity_rejectsLineNotOnTransaction() {
        Transaction tx = inProgress();
        LineItem foreign = new LineItem(widget(), 1);
        assertThatThrownBy(() -> tx.updateLineItemQuantity(foreign, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateLineItemQuantity_rejectsVoidedLine() {
        Transaction tx = inProgress();
        tx.addLineItem(widget(), 1);
        LineItem line = tx.getLineItems().get(0);
        tx.voidLine(line);
        assertThatThrownBy(() -> tx.updateLineItemQuantity(line, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
