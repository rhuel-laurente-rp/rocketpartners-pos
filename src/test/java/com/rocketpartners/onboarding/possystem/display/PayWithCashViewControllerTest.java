package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-level tests for the restructured cash flow.
 *
 * <p><strong>Two one-tap terminal modes.</strong> {@link PosEventType#CASH_EXACT_PRESSED} tenders
 * the grand total immediately; {@link PosEventType#CASH_NEXT_DOLLAR_PRESSED} tenders the ceiled
 * amount immediately (change $0.00). Neither opens the entry dialog — the mode choice is the
 * whole interaction and the receipt follows.</p>
 *
 * <p><strong>One navigation mode.</strong> {@link PosEventType#OTHER_CASH_AMOUNT_PRESSED} opens
 * {@link PayWithCashView} and defers tender to {@link PosEventType#CASH_CONFIRM_PRESSED}; change
 * is {@code cashReceived − grandTotal()}, coins included.</p>
 */
class PayWithCashViewControllerTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("7.30"));
    private static final Item DOLLAR = new Item("UPC-D", "Dollar Item", new BigDecimal("7.00"));

    private PosComponent pos;
    private CashModeChoiceView choiceView;
    private PayWithCashView entryView;
    private PayWithCashViewController controller;
    private RecordingListener notifications;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        items.put(DOLLAR.getUpc(), DOLLAR);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "Test Store",
                1,
                false);
        choiceView = mock(CashModeChoiceView.class);
        entryView = mock(PayWithCashView.class);
        when(entryView.getCashReceivedText()).thenReturn("");
        controller = new PayWithCashViewController(choiceView, entryView);
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
        pos.addController(controller);
        pos.start();
    }

    // Bring the transaction to TOTALED at the given grand total (with the given item on it).
    private Transaction totaledWith(Item item) {
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(item.getUpc(), 1);
        return pos.getTransactionService().total();
    }

    // Totaled, then open the mode-choice dialog (step one). Leaves the transaction TOTALED.
    private Transaction totaledAndOpened(Item item) {
        Transaction tx = totaledWith(item);
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        return tx;
    }

    // Totaled, opened, then dropped into the Other Amount entry dialog so `amountDue` is primed
    // and CASH_CONFIRM_PRESSED runs the confirm handler.
    private Transaction totaledAndOtherReady(Item item) {
        Transaction tx = totaledAndOpened(item);
        pos.dispatchPosEvent(new PosEvent(PosEventType.OTHER_CASH_AMOUNT_PRESSED));
        return tx;
    }

    // ---- Step one: mode choice ------------------------------------------

    @Test
    void tenderCashPressed_opensChoiceDialog_seededWithExactAndNextDollar() {
        totaledWith(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));

        verify(choiceView).openFor(new BigDecimal("7.30"), new BigDecimal("8.00"));
        verify(entryView, never()).openFor(any(), any());
    }

    @Test
    void tenderCashPressed_wholeDollarTotal_seedsBothAmountsEqual() {
        totaledWith(DOLLAR); // 7.00

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));

        verify(choiceView).openFor(new BigDecimal("7.00"), new BigDecimal("7.00"));
    }

    // ---- Exact Amount: one tap, tenders grand total ---------------------

    @Test
    void exactModeSelected_tendersGrandTotalImmediately_zeroChange_noEntryDialog() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_EXACT_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        assertThat(tx.getTenderType()).isEqualTo(TenderType.CASH);
        verify(entryView, never()).openFor(any(), any());
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isEqualTo(1);
        assertThat(notifications.countOf(PosEventType.TRANSACTION_COMPLETED)).isEqualTo(1);
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("tenderType", TenderType.class)).isEqualTo(TenderType.CASH);
        assertThat(tendered.getProperty("amountTendered", BigDecimal.class))
                .isEqualByComparingTo("7.30");
        assertThat(tendered.getProperty("amountDue", BigDecimal.class))
                .isEqualByComparingTo("7.30");
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    // ---- Next Dollar: one tap, tenders the ceiled amount ----------------

    @Test
    void nextDollarModeSelected_tendersCeiledAmountImmediately_zeroChange_noEntryDialog() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30 → ceil 8.00

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        verify(entryView, never()).openFor(any(), any());
        // Next Dollar settles the ceiled figure as amountDue, so change is exactly zero.
        assertThat(tx.amountDue()).isEqualByComparingTo("8.00");
        assertThat(tx.changeDue()).isEqualByComparingTo("0.00");
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("amountTendered", BigDecimal.class))
                .isEqualByComparingTo("8.00");
        assertThat(tendered.getProperty("amountDue", BigDecimal.class))
                .isEqualByComparingTo("8.00");
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    // ---- Other Amount: opens the entry dialog, does not tender by itself -

    @Test
    void otherAmountSelected_opensEntryDialog_andDoesNotTender() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.OTHER_CASH_AMOUNT_PRESSED));

        // Entry dialog opens with the grand total as the amount owed; no tender yet.
        verify(entryView).openFor(new BigDecimal("7.30"), PayWithCashView.Mode.EXACT);
        verify(choiceView).closeDialog();
        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
    }

    // ---- Other Amount confirm — change against the grand total ----------

    @Test
    void otherAmount_overpayment_producesChangeAgainstGrandTotal_notCeiled() {
        Transaction tx = totaledAndOtherReady(WIDGET); // 7.30

        pos.dispatchPosEvent(cashConfirm("10.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("amountTendered", BigDecimal.class))
                .isEqualByComparingTo("10.00");
        // Change measured against the true grand total (7.30), NOT a ceiled figure.
        assertThat(tendered.getProperty("amountDue", BigDecimal.class))
                .isEqualByComparingTo("7.30");
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("2.70");
        verify(entryView).closeDialog();
    }

    @Test
    void otherAmount_exactCash_paysWithZeroChange() {
        Transaction tx = totaledAndOtherReady(WIDGET); // 7.30

        pos.dispatchPosEvent(cashConfirm("7.30"));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        assertThat(notifications.lastOf(PosEventType.CASH_TENDERED)
                .getProperty("changeDue", BigDecimal.class)).isEqualByComparingTo("0.00");
    }

    @Test
    void otherAmount_underpayment_isRejectedInline_transactionStaysTotaled() {
        Transaction tx = totaledAndOtherReady(WIDGET); // 7.30

        pos.dispatchPosEvent(cashConfirm("5.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("UNDERPAYMENT");
        verify(entryView, never()).closeDialog();
    }

    @Test
    void otherAmount_nonNumericInput_isRejected() {
        Transaction tx = totaledAndOtherReady(WIDGET);

        pos.dispatchPosEvent(cashConfirm("banana"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_CASH_AMOUNT");
        verify(entryView).showError(any());
    }

    @Test
    void otherAmount_negativeInput_isRejected() {
        Transaction tx = totaledAndOtherReady(WIDGET);

        pos.dispatchPosEvent(cashConfirm("-5.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_CASH_AMOUNT");
    }

    @Test
    void confirmPressed_whenTransactionIsInProgress_isRejectedByService() {
        Transaction tx = totaledAndOtherReady(WIDGET); // primes amountDue at 7.30
        // Void the totaled transaction and open a fresh IN_PROGRESS one; the confirm below must
        // reach the service and be rejected there, not short-circuit in the controller.
        pos.getTransactionService().voidBasket();
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        Transaction inProgress = pos.getTransactionService().getCurrentTransaction();

        pos.dispatchPosEvent(cashConfirm("10.00"));

        assertThat(inProgress.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        assertThat(tx.getState()).isEqualTo(TransactionState.VOIDED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("TOTALED_INVARIANT");
        verify(entryView).showError(any());
    }

    // ---- Zero grand total is rejected on all three paths ----------------

    @Test
    void zeroGrandTotal_isRejected_onAllThreePaths() {
        pos.getTransactionService().startTransaction();
        Transaction empty = pos.getTransactionService().total(); // empty basket → grand total 0.00

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_EXACT_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.OTHER_CASH_AMOUNT_PRESSED));

        assertThat(empty.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        // Neither dialog opens for a zero-total basket.
        verify(choiceView, never()).openFor(any(), any());
        verify(entryView, never()).openFor(any(), any());
        // Each path dispatched an INVALID_ARGUMENT error rather than tendering nothing.
        assertThat(notifications.countOf(PosEventType.ERROR)).isEqualTo(4);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_ARGUMENT");
    }

    // ---- Cancel / Back — no tender, transaction stays TOTALED -----------

    @Test
    void cancelFromChoiceStep_leavesTransactionTotaled_andClosesBothDialogs() {
        Transaction tx = totaledAndOpened(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        verify(choiceView).closeDialog();
        verify(entryView).closeDialog();
    }

    @Test
    void cancelFromEntryStep_leavesTransactionTotaled_andDispatchesNoTender() {
        Transaction tx = totaledAndOtherReady(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        verify(entryView).closeDialog();
    }

    @Test
    void backFromEntryStep_reopensChoice_withoutTendering() {
        Transaction tx = totaledAndOtherReady(WIDGET); // choiceView.openFor called once so far

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_ENTRY_BACK_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        // Back closes the entry dialog and reopens the mode choice with the same figures.
        // (Opening Other Amount closes the *choice* dialog, not the entry one, so the entry
        // dialog is closed exactly once — on Back.)
        verify(entryView, times(1)).closeDialog();
        verify(choiceView, times(2)).openFor(new BigDecimal("7.30"), new BigDecimal("8.00"));
    }

    @Test
    void backThenExact_tendersNormally() {
        totaledAndOtherReady(WIDGET); // 7.30
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_ENTRY_BACK_PRESSED));

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_EXACT_PRESSED));

        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.CASH_TENDERED)
                .getProperty("changeDue", BigDecimal.class)).isEqualByComparingTo("0.00");
    }

    @Test
    void cancelThenReconfirm_paysNormally() {
        Transaction tx = totaledWith(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        assertThat(notifications.lastOf(PosEventType.CASH_TENDERED)
                .getProperty("changeDue", BigDecimal.class)).isEqualByComparingTo("0.00");
    }

    // ---- Journal: one-tap paths show both mode and tender ---------------

    @Test
    void oneTap_exact_journalShowsBothModeSelectionAndTender() {
        totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(exactMode("7.30"));

        // The mode-selection event is on the wire (JournalListener records it), and so is the
        // tender, carrying tender type, amount, amountDue, and change.
        assertThat(notifications.countOf(PosEventType.CASH_EXACT_PRESSED)).isEqualTo(1);
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("tenderType", TenderType.class)).isEqualTo(TenderType.CASH);
        assertThat(tendered.getProperty("amountTendered", BigDecimal.class)).isNotNull();
        assertThat(tendered.getProperty("amountDue", BigDecimal.class)).isNotNull();
        assertThat(tendered.getProperty("changeDue", BigDecimal.class)).isNotNull();
    }

    // ---- helpers --------------------------------------------------------

    private static PosEvent cashConfirm(String cashReceived) {
        Map<String, Object> props = new HashMap<>();
        props.put("cashReceived", cashReceived);
        return new PosEvent(PosEventType.CASH_CONFIRM_PRESSED, props);
    }

    private static PosEvent exactMode(String prefillAmount) {
        Map<String, Object> props = new HashMap<>();
        props.put("prefillAmount", new BigDecimal(prefillAmount));
        return new PosEvent(PosEventType.CASH_EXACT_PRESSED, props);
    }

    static final class RecordingListener implements IPosEventListener {
        final Set<PosEventType> types;
        final List<PosEvent> received = new ArrayList<>();

        RecordingListener(Set<PosEventType> types) {
            this.types = types;
        }

        @Override
        public Set<PosEventType> getListeningEventTypes() {
            return types;
        }

        @Override
        public void onPosEvent(PosEvent event) {
            received.add(event);
        }

        int countOf(PosEventType type) {
            return (int) received.stream().filter(e -> e.getType() == type).count();
        }

        PosEvent lastOf(PosEventType type) {
            PosEvent last = null;
            for (PosEvent e : received) if (e.getType() == type) last = e;
            return last;
        }
    }
}
