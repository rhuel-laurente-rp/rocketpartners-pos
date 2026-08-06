package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionServiceTest {

    private static final BigDecimal SEVEN_PERCENT = new BigDecimal("0.07");
    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");
    private static final BigDecimal NO_TAX = BigDecimal.ZERO;

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));
    private static final Item GADGET = new Item("UPC-G", "Gadget", new BigDecimal("5.00"));

    private static InMemoryItemRepository repo() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        items.put(GADGET.getUpc(), GADGET);
        return new InMemoryItemRepository(items);
    }

    private static TransactionService service(BigDecimal rate, RecordingDispatcher dispatcher) {
        return new TransactionService(repo(), new TaxService(rate), dispatcher);
    }

    private static TransactionService service(BigDecimal rate) {
        return service(rate, new RecordingDispatcher());
    }

    // -----------------------------------------------------------------------
    // Start / lifecycle

    @Test
    void startTransaction_returnsNewInProgressTransaction() {
        Transaction tx = service(NO_TAX).startTransaction();
        assertThat(tx.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        assertThat(tx.getTaxRate()).isEqualByComparingTo("0");
    }

    @Test
    void startTransaction_passesTaxRateIntoAggregate() {
        Transaction tx = service(SEVEN_PERCENT).startTransaction();
        assertThat(tx.getTaxRate()).isEqualByComparingTo("0.07");
    }

    @Test
    void startTransaction_whileAnotherIsOpen_throws() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        assertThatThrownBy(svc::startTransaction).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startTransaction_afterVoidedBasket_isAllowed() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.voidBasket();
        Transaction next = svc.startTransaction();
        assertThat(next.getState()).isEqualTo(TransactionState.IN_PROGRESS);
    }

    @Test
    void startTransaction_afterPaid_isAllowed() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        svc.tenderCash(new BigDecimal("10.00"));
        Transaction next = svc.startTransaction();
        assertThat(next.getState()).isEqualTo(TransactionState.IN_PROGRESS);
    }

    // -----------------------------------------------------------------------
    // Add by UPC

    @Test
    void addItemByUpc_hit_appendsLineItem() {
        TransactionService svc = service(NO_TAX);
        Transaction tx = svc.startTransaction();
        LineItem li = svc.addItemByUpc(WIDGET.getUpc(), 1);
        assertThat(tx.getLineItems()).hasSize(1);
        assertThat(li.getItem()).isEqualTo(WIDGET);
        assertThat(li.getQuantity()).isEqualTo(1);
    }

    @Test
    void addItemByUpc_sameUpcTwice_accumulatesQuantity() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 2);
        LineItem li = svc.addItemByUpc(WIDGET.getUpc(), 3);
        assertThat(svc.getCurrentTransaction().getLineItems()).hasSize(1);
        assertThat(li.getQuantity()).isEqualTo(5);
    }

    @Test
    void addItemByUpc_unknownUpc_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        assertThatThrownBy(() -> svc.addItemByUpc("no-such-upc", 1))
                .isInstanceOf(IllegalArgumentException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("UPC_NOT_FOUND");
        assertThat(error.getProperty("upc", String.class)).isEqualTo("no-such-upc");
    }

    @Test
    void addItemByUpc_beforeStart_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        assertThatThrownBy(() -> svc.addItemByUpc(WIDGET.getUpc(), 1))
                .isInstanceOf(IllegalStateException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("NO_TRANSACTION");
        assertThat(error.getProperty("operation", String.class)).isEqualTo("addItemByUpc");
    }

    @Test
    void addItemByUpc_afterTotal_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        dispatcher.clear();
        assertThatThrownBy(() -> svc.addItemByUpc(GADGET.getUpc(), 1))
                .isInstanceOf(IllegalStateException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("TOTALED_INVARIANT");
        assertThat(error.getProperty("operation", String.class)).isEqualTo("addItemByUpc");
        // transaction stays TOTALED, no side effect
        assertThat(svc.getCurrentTransaction().getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(svc.getCurrentTransaction().getLineItems()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Void

    @Test
    void voidLine_beforeTotal_softDeletesLine() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        LineItem li = svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.voidLine(li);
        assertThat(li.isVoided()).isTrue();
    }

    @Test
    void voidLine_afterTotal_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        LineItem li = svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        dispatcher.clear();
        assertThatThrownBy(() -> svc.voidLine(li)).isInstanceOf(IllegalStateException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("TOTALED_INVARIANT");
        assertThat(error.getProperty("operation", String.class)).isEqualTo("voidLine");
        assertThat(li.isVoided()).isFalse();
    }

    @Test
    void voidLine_lineNotOnTransaction_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        LineItem foreign = new LineItem(WIDGET, 1);
        assertThatThrownBy(() -> svc.voidLine(foreign)).isInstanceOf(IllegalArgumentException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("INVALID_ARGUMENT");
        assertThat(error.getProperty("operation", String.class)).isEqualTo("voidLine");
    }

    @Test
    void updateLineItemQuantity_beforeTotal_setsNewQuantity() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        LineItem line = svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.updateLineItemQuantity(line, 5);
        assertThat(line.getQuantity()).isEqualTo(5);
        assertThat(svc.getCurrentTransaction().subtotal()).isEqualByComparingTo("50.00");
    }

    @Test
    void updateLineItemQuantity_afterTotal_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        LineItem line = svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        dispatcher.clear();
        assertThatThrownBy(() -> svc.updateLineItemQuantity(line, 3))
                .isInstanceOf(IllegalStateException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("TOTALED_INVARIANT");
        assertThat(error.getProperty("operation", String.class)).isEqualTo("updateLineItemQuantity");
        assertThat(line.getQuantity()).isEqualTo(1);
    }

    @Test
    void updateLineItemQuantity_rejectsZero() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        LineItem line = svc.addItemByUpc(WIDGET.getUpc(), 2);
        assertThatThrownBy(() -> svc.updateLineItemQuantity(line, 0))
                .isInstanceOf(IllegalArgumentException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("INVALID_ARGUMENT");
        assertThat(error.getProperty("operation", String.class)).isEqualTo("updateLineItemQuantity");
        assertThat(line.getQuantity()).isEqualTo(2);
    }

    @Test
    void updateLineItemQuantity_beforeStart_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        LineItem foreign = new LineItem(WIDGET, 1);
        assertThatThrownBy(() -> svc.updateLineItemQuantity(foreign, 3))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dispatcher.onlyError().getProperty("code", String.class)).isEqualTo("NO_TRANSACTION");
    }

    @Test
    void voidBasket_beforeTotal_clearsCurrentAndAllowsRestart() {
        TransactionService svc = service(NO_TAX);
        Transaction first = svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        Transaction voided = svc.voidBasket();
        assertThat(voided).isSameAs(first);
        assertThat(voided.getState()).isEqualTo(TransactionState.VOIDED);
        assertThat(svc.getCurrentTransaction()).isNull();
        // starting again succeeds
        assertThat(svc.startTransaction().getState()).isEqualTo(TransactionState.IN_PROGRESS);
    }

    @Test
    void voidBasket_afterTotal_clearsCurrentAndTransitionsVoided() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction voided = svc.voidBasket();
        assertThat(voided.getState()).isEqualTo(TransactionState.VOIDED);
        assertThat(svc.getCurrentTransaction()).isNull();
    }

    // -----------------------------------------------------------------------
    // Total / tender / math

    @Test
    void total_transitionsToTotaled() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        Transaction tx = svc.total();
        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
    }

    @Test
    void total_beforeStart_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        assertThatThrownBy(svc::total).isInstanceOf(IllegalStateException.class);
        assertThat(dispatcher.onlyError().getProperty("code", String.class)).isEqualTo("NO_TRANSACTION");
    }

    @Test
    void tenderCash_exactAmount_paysWithZeroChange() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("10.00"));
        assertThat(paid.getState()).isEqualTo(TransactionState.PAID);
        assertThat(paid.changeDue()).isEqualByComparingTo("0.00");
        assertThat(svc.getCurrentTransaction()).isNull();
    }

    @Test
    void tenderCash_overpayment_returnsChangeDue() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("20.00"));
        assertThat(paid.changeDue()).isEqualByComparingTo("10.00");
    }

    @Test
    void tenderCash_beforeTotal_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        dispatcher.clear();
        assertThatThrownBy(() -> svc.tenderCash(new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("TOTALED_INVARIANT");
        assertThat(error.getProperty("operation", String.class)).isEqualTo("tenderCash");
    }

    @Test
    void tenderPayNextDollar_roundsUpAndClearsCurrent() {
        Map<String, Item> items = new LinkedHashMap<>();
        Item penny = new Item("UPC-P", "PennyGoods", new BigDecimal("7.01"));
        items.put(penny.getUpc(), penny);
        TransactionService svc = new TransactionService(
                new InMemoryItemRepository(items),
                new TaxService(NO_TAX),
                new RecordingDispatcher());
        svc.startTransaction();
        svc.addItemByUpc(penny.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderPayNextDollar();
        assertThat(paid.getState()).isEqualTo(TransactionState.PAID);
        assertThat(paid.getTenderType()).isEqualTo(TenderType.CASH);
        assertThat(paid.getCashTendered()).isEqualByComparingTo("8.00");
        // Next Dollar settles at the rounded amount — no change to the customer.
        assertThat(paid.amountDue()).isEqualByComparingTo("8.00");
        assertThat(paid.changeDue()).isEqualByComparingTo("0.00");
        assertThat(svc.getCurrentTransaction()).isNull();
    }

    @Test
    void tenderPayNextDollar_beforeTotal_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        dispatcher.clear();
        assertThatThrownBy(svc::tenderPayNextDollar).isInstanceOf(IllegalStateException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getProperty("code", String.class)).isEqualTo("TOTALED_INVARIANT");
        assertThat(error.getProperty("operation", String.class)).isEqualTo("tenderPayNextDollar");
    }

    @Test
    void tenderPayNextDollar_beforeStart_dispatchesErrorAndThrows() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        assertThatThrownBy(svc::tenderPayNextDollar).isInstanceOf(IllegalStateException.class);
        assertThat(dispatcher.onlyError().getProperty("code", String.class)).isEqualTo("NO_TRANSACTION");
    }

    @Test
    void tenderCard_debit_setsPaidAndClearsCurrent() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCard(TenderType.DEBIT, new BigDecimal("10.00"));
        assertThat(paid.getTenderType()).isEqualTo(TenderType.DEBIT);
        assertThat(paid.getState()).isEqualTo(TransactionState.PAID);
        assertThat(svc.getCurrentTransaction()).isNull();
    }

    @Test
    void tenderCard_credit_setsPaidAndClearsCurrent() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(GADGET.getUpc(), 2);
        svc.total();
        Transaction paid = svc.tenderCard(TenderType.CREDIT, new BigDecimal("10.00"));
        assertThat(paid.getTenderType()).isEqualTo(TenderType.CREDIT);
    }

    @Test
    void tenderCard_withCashType_throws() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        assertThatThrownBy(() -> svc.tenderCard(TenderType.CASH, new BigDecimal("10.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // Tax on the correct base

    @Test
    void total_taxAppliedToPostDiscountSubtotal() {
        TransactionService svc = service(TEN_PERCENT);
        Transaction tx = svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1); // 10.00
        svc.total();
        tx.applyDiscount(new Discount("D-1", "loyalty", DiscountType.FIXED_AMOUNT_OFF,
                new BigDecimal("2.00"), new BigDecimal("2.00")));
        // tax base = 10.00 - 2.00 = 8.00; tax = 0.80; grand = 8.80
        assertThat(tx.taxTotal()).isEqualByComparingTo("0.80");
        assertThat(tx.grandTotal()).isEqualByComparingTo("8.80");
    }

    // -----------------------------------------------------------------------
    // Receipt

    @Test
    void generateReceipt_containsTransactionIdAndTotal() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("10.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains(paid.getTransactionId());
        assertThat(receipt).contains("TOTAL:");
        assertThat(receipt).contains("10.00");
    }

    @Test
    void generateReceipt_listsEachNonVoidedLineItem() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 2);
        svc.addItemByUpc(GADGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("25.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains("2 x Widget");
        assertThat(receipt).contains("1 x Gadget");
    }

    @Test
    void generateReceipt_omitsVoidedLineItems() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        LineItem widget = svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.addItemByUpc(GADGET.getUpc(), 1);
        svc.voidLine(widget);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("5.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).doesNotContain("1 x Widget");
        assertThat(receipt).contains("1 x Gadget");
    }

    @Test
    void generateReceipt_includesDiscountLineWhenPresent() {
        TransactionService svc = service(NO_TAX);
        Transaction tx = svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        tx.applyDiscount(new Discount("D-1", "loyalty", DiscountType.FIXED_AMOUNT_OFF,
                new BigDecimal("2.00"), new BigDecimal("2.00")));
        Transaction paid = svc.tenderCash(new BigDecimal("8.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains("Discount: loyalty");
        assertThat(receipt).contains("-2.00");
    }

    @Test
    void generateReceipt_showsCashChange() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("20.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains("Tender: CASH");
        assertThat(receipt).contains("Change:");
        assertThat(receipt).contains("10.00");
    }

    @Test
    void generateReceipt_omitsChangeLineForCardTenders() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCard(TenderType.DEBIT, new BigDecimal("10.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains("Tender: DEBIT");
        assertThat(receipt).doesNotContain("Change:");
    }

    @Test
    void generateReceipt_formatsMoneyAtScale2() {
        // subtotal 0.05, tax 10% → 0.005 → grandTotal 0.055 → HALF_UP 0.06
        Item penny = new Item("UPC-P", "PennyGoods", new BigDecimal("0.05"));
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(penny.getUpc(), penny);
        TransactionService svc = new TransactionService(
                new InMemoryItemRepository(items),
                new TaxService(TEN_PERCENT),
                new RecordingDispatcher());
        svc.startTransaction();
        svc.addItemByUpc(penny.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("1.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains("0.06");
        assertThat(receipt).doesNotContain("0.055");
    }

    // -----------------------------------------------------------------------
    // Amount-due on the receipt

    @Test
    void generateReceipt_exactTender_showsAmountDueEqualToGrandTotal() {
        TransactionService svc = service(NO_TAX);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("10.00"), new BigDecimal("10.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains("Amount Due (Exact):");
        assertThat(receipt).doesNotContain("Next Dollar");
    }

    @Test
    void generateReceipt_nextDollarTender_showsAmountDueAndModeLabel() {
        Item penny = new Item("UPC-P", "PennyGoods", new BigDecimal("7.30"));
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(penny.getUpc(), penny);
        TransactionService svc = new TransactionService(
                new InMemoryItemRepository(items),
                new TaxService(NO_TAX),
                new RecordingDispatcher());
        svc.startTransaction();
        svc.addItemByUpc(penny.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderCash(new BigDecimal("8.00"), new BigDecimal("8.00"));
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains("TOTAL:");
        assertThat(receipt).contains("7.30");                 // raw grand total
        assertThat(receipt).contains("Amount Due (Next Dollar):");
        assertThat(receipt).contains("8.00");                 // settled amount
        // No change: the customer paid the settled amount.
        assertThat(paid.changeDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void generateReceipt_payNextDollarHelper_showsNextDollarLine() {
        Item penny = new Item("UPC-P", "PennyGoods", new BigDecimal("7.30"));
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(penny.getUpc(), penny);
        TransactionService svc = new TransactionService(
                new InMemoryItemRepository(items),
                new TaxService(NO_TAX),
                new RecordingDispatcher());
        svc.startTransaction();
        svc.addItemByUpc(penny.getUpc(), 1);
        svc.total();
        Transaction paid = svc.tenderPayNextDollar();
        String receipt = svc.generateReceipt(paid);
        assertThat(receipt).contains("Amount Due (Next Dollar):");
        assertThat(receipt).contains("8.00");
        assertThat(paid.changeDue()).isEqualByComparingTo("0.00");
    }

    // -----------------------------------------------------------------------
    // Error dispatch shape

    @Test
    void errorEvent_carriesCodeMessageAndCause() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        TransactionService svc = service(NO_TAX, dispatcher);
        svc.startTransaction();
        svc.addItemByUpc(WIDGET.getUpc(), 1);
        svc.total();
        dispatcher.clear();
        assertThatThrownBy(() -> svc.addItemByUpc(GADGET.getUpc(), 1))
                .isInstanceOf(IllegalStateException.class);
        PosEvent error = dispatcher.onlyError();
        assertThat(error.getType()).isEqualTo(PosEventType.ERROR);
        assertThat(error.hasProperty("code")).isTrue();
        assertThat(error.hasProperty("message")).isTrue();
        assertThat(error.hasProperty("cause")).isTrue();
        assertThat(error.getProperty("cause", Throwable.class)).isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------------
    // Recording dispatcher

    static final class RecordingDispatcher implements IPosEventDispatcher {
        final List<PosEvent> events = new ArrayList<>();

        @Override
        public void dispatchPosEvent(PosEvent event) {
            events.add(event);
        }

        void clear() {
            events.clear();
        }

        PosEvent onlyError() {
            List<PosEvent> errors = new ArrayList<>();
            for (PosEvent e : events) {
                if (e.getType() == PosEventType.ERROR) errors.add(e);
            }
            assertThat(errors).hasSize(1);
            return errors.get(0);
        }
    }
}
