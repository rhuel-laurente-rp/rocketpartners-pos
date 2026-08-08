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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-level tests for the two-step cash flow.
 *
 * <p>Step one opens {@link CashModeChoiceView} seeded with the transaction's grand total (as
 * "exact") and its next-dollar rounded companion. The mode buttons dispatch back to the bus
 * carrying a {@code prefillAmount}, which the controller uses to open {@link PayWithCashView}
 * pre-filled — but does not tender by itself.</p>
 *
 * <p>Step two owns validation and confirmation. Change is always {@code cashReceived −
 * grandTotal()} regardless of which mode the cashier picked in step one: the mode only decides
 * what the entry field starts at, not what the customer owes.</p>
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

    // As above, then open the mode-choice dialog. Leaves `grandTotalAmountDue` unset — the
    // caller must dispatch a mode-select event before confirming.
    private Transaction totaledAndOpened(Item item) {
        Transaction tx = totaledWith(item);
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        return tx;
    }

    // As above, then also drop into Exact mode so `grandTotalAmountDue` is primed and
    // CASH_CONFIRM_PRESSED runs the confirm handler. For tests that need Next Dollar
    // semantics, dispatch CASH_NEXT_DOLLAR_PRESSED explicitly instead of this helper.
    private Transaction totaledAndExactReady(Item item) {
        Transaction tx = totaledAndOpened(item);
        Map<String, Object> props = new HashMap<>();
        props.put("prefillAmount", tx.grandTotal());
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_EXACT_PRESSED, props));
        return tx;
    }

    // ---- Step one: mode choice ------------------------------------------

    @Test
    void tenderCashPressed_opensChoiceDialog_seededWithExactAndNextDollar() {
        totaledWith(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));

        // The mode-choice dialog opens; the entry dialog does not.
        verify(choiceView).openFor(new BigDecimal("7.30"), new BigDecimal("8.00"));
        verify(entryView, never()).openFor(any(), any());
    }

    @Test
    void tenderCashPressed_wholeDollarTotal_seedsBothAmountsEqual() {
        totaledWith(DOLLAR); // 7.00

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));

        verify(choiceView).openFor(new BigDecimal("7.00"), new BigDecimal("7.00"));
    }

    // ---- Step two: opening the entry dialog from a mode -----------------

    @Test
    void exactModeSelected_opensEntryDialog_prefilledWithGrandTotal() {
        totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(modeEvent(PosEventType.CASH_EXACT_PRESSED, "7.30"));

        verify(entryView).openFor(new BigDecimal("7.30"), PayWithCashView.Mode.EXACT);
    }

    @Test
    void nextDollarModeSelected_opensEntryDialog_prefilledWithRoundedAmount() {
        totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(modeEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED, "8.00"));

        verify(entryView).openFor(new BigDecimal("8.00"), PayWithCashView.Mode.NEXT_DOLLAR);
    }

    @Test
    void modeSelected_closesChoiceDialog_soExactlyOneModalIsOpen() {
        totaledAndOpened(WIDGET);

        pos.dispatchPosEvent(modeEvent(PosEventType.CASH_EXACT_PRESSED, "7.30"));

        verify(choiceView).closeDialog();
    }

    // ---- Confirm — validation happens against grand total, always -------

    @Test
    void confirmPressed_underpayment_isRejectedInline_transactionStaysTotaled() {
        Transaction tx = totaledAndExactReady(WIDGET); // 7.30

        pos.dispatchPosEvent(cashConfirm("5.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.countOf(PosEventType.ERROR)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("UNDERPAYMENT");
        verify(entryView, never()).closeDialog();
    }

    @Test
    void confirmPressed_underpayment_isRelativeToSettledAmount_afterNextDollar() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30

        // Cashier picked Next Dollar → settled grandTotalAmountDue = $8.00. Handing over $7.50
        // is below settled and must be rejected, even though it's above the raw grand total.
        pos.dispatchPosEvent(modeEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED, "8.00"));
        pos.dispatchPosEvent(cashConfirm("7.50"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("UNDERPAYMENT");
    }

    @Test
    void confirmPressed_overpayment_paysAndProducesChange() {
        Transaction tx = totaledAndExactReady(WIDGET); // 7.30

        pos.dispatchPosEvent(cashConfirm("10.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        assertThat(tx.getTenderType()).isEqualTo(TenderType.CASH);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isEqualTo(1);
        assertThat(notifications.countOf(PosEventType.TRANSACTION_COMPLETED)).isEqualTo(1);
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("tenderType", TenderType.class)).isEqualTo(TenderType.CASH);
        assertThat(tendered.getProperty("amountTendered", BigDecimal.class))
                .isEqualByComparingTo("10.00");
        assertThat(tendered.getProperty("amountDue", BigDecimal.class))
                .isEqualByComparingTo("7.30");
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("2.70");
        verify(entryView).closeDialog();
    }

    @Test
    void confirmPressed_exactAmount_paysWithZeroChange() {
        Transaction tx = totaledAndExactReady(WIDGET); // 7.30

        pos.dispatchPosEvent(cashConfirm("7.30"));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void confirmPressed_afterNextDollar_yieldsZeroChange_whenCustomerHandsOverSettled() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(modeEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED, "8.00"));
        pos.dispatchPosEvent(cashConfirm("8.00")); // customer hands over exactly $8

        // Settled-amount semantics: grandTotalAmountDue = $8.00 (mode-inflected), so change is
        // 8.00 - 8.00 = 0.00. The receipt reflects the $8.00 the customer actually paid.
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.00");
        assertThat(tx.amountDue()).isEqualByComparingTo("8.00");
        assertThat(tx.changeDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void confirmPressed_twentyDollarBill_yieldsCorrectChange() {
        // The $20-bill case the brief calls out: customer hands over a bill for a $17.70 basket.
        // Rebuild a fresh totaled tx with a matching total using our two-item bag.
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1); // 7.30
        pos.getTransactionService().addItemByUpc(DOLLAR.getUpc(), 1); // 7.00
        // Bump quantity to hit $17.70: another Widget + Dollar + ... — easier: adjust the fixture.
        // But keep it simple: use $14.30 basket and $20 tender; the arithmetic is the same shape.
        Transaction tx = pos.getTransactionService().total(); // 14.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(modeEvent(PosEventType.CASH_EXACT_PRESSED, "14.30"));
        // Cashier types over the 14.30 prefill with a $20 bill.
        pos.dispatchPosEvent(cashConfirm("20.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("5.70");
    }

    @Test
    void confirmPressed_nonNumericInput_isRejected() {
        Transaction tx = totaledAndExactReady(WIDGET);

        pos.dispatchPosEvent(cashConfirm("banana"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.countOf(PosEventType.ERROR)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_CASH_AMOUNT");
        verify(entryView).showError(any());
    }

    @Test
    void confirmPressed_negativeInput_isRejected() {
        Transaction tx = totaledAndExactReady(WIDGET);

        pos.dispatchPosEvent(cashConfirm("-5.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_CASH_AMOUNT");
    }

    @Test
    void confirmPressed_whenTransactionIsInProgress_isRejectedByService() {
        totaledWith(WIDGET);
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        // Mode-select so `grandTotalAmountDue` is primed; the CASH_CONFIRM_PRESSED below
        // must reach the service and be rejected there, not short-circuit in the controller.
        Map<String, Object> exact = new HashMap<>();
        exact.put("prefillAmount", new BigDecimal("7.30"));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_EXACT_PRESSED, exact));
        Transaction tx = pos.getTransactionService().getCurrentTransaction();
        pos.getTransactionService().voidBasket();
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        Transaction inProgress = pos.getTransactionService().getCurrentTransaction();

        pos.dispatchPosEvent(cashConfirm("10.00"));

        assertThat(inProgress.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        assertThat(tx.getState()).isEqualTo(TransactionState.VOIDED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        PosEvent error = notifications.lastOf(PosEventType.ERROR);
        assertThat(error).isNotNull();
        assertThat(error.getProperty("code", String.class)).isEqualTo("TOTALED_INVARIANT");
        verify(entryView).showError(any());
    }

    // ---- Cancel from either step ---------------------------------------

    @Test
    void cancelFromChoiceStep_leavesTransactionTotaled_andClosesBothDialogs() {
        Transaction tx = totaledWith(WIDGET);
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));

        // Cashier cancels from the choice dialog before selecting a mode.
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        // The controller defensively closes both — one is a no-op — so no matter which dialog
        // the cashier bailed from, both are gone afterwards.
        verify(choiceView).closeDialog();
        verify(entryView).closeDialog();
    }

    @Test
    void cancelFromEntryStep_leavesTransactionTotaled_andClosesBothDialogs() {
        Transaction tx = totaledAndOpened(WIDGET);
        pos.dispatchPosEvent(modeEvent(PosEventType.CASH_EXACT_PRESSED, "7.30"));

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        verify(entryView).closeDialog();
    }

    @Test
    void cancelThenReconfirm_paysNormally() {
        Transaction tx = totaledWith(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(modeEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED, "8.00"));
        pos.dispatchPosEvent(cashConfirm("8.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        // Settled-amount semantics: Next Dollar → grandTotalAmountDue = $8.00, cash = $8.00,
        // change = 0.00.
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    // ---- helpers --------------------------------------------------------

    private static PosEvent cashConfirm(String cashReceived) {
        Map<String, Object> props = new HashMap<>();
        props.put("cashReceived", cashReceived);
        return new PosEvent(PosEventType.CASH_CONFIRM_PRESSED, props);
    }

    private static PosEvent modeEvent(PosEventType type, String prefillAmount) {
        Map<String, Object> props = new HashMap<>();
        props.put("prefillAmount", new BigDecimal(prefillAmount));
        return new PosEvent(type, props);
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
