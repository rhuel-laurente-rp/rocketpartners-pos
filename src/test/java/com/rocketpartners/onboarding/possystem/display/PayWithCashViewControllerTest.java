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

class PayWithCashViewControllerTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("7.30"));
    private static final Item DOLLAR = new Item("UPC-D", "Dollar Item", new BigDecimal("7.00"));

    private PosComponent pos;
    private PayWithCashView view;
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
        view = mock(PayWithCashView.class);
        when(view.getCashReceivedText()).thenReturn("");
        controller = new PayWithCashViewController(view);
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

    // As above, then open the cash dialog so `amountDue` state is primed for confirm.
    private Transaction totaledAndOpened(Item item) {
        Transaction tx = totaledWith(item);
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        return tx;
    }

    @Test
    void tenderCashPressed_opensDialogWithAmountDue_andEmptyField() {
        totaledWith(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));

        verify(view).setAmountDue(new BigDecimal("7.30"));
        verify(view).setCashReceivedText("");
        verify(view).clearStatus();
        verify(view).openDialog();
    }

    @Test
    void nextDollarPressed_updatesAmountDueToCeiling_forFractionalAmount() {
        totaledWith(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED));

        // The button changes the total payable, not the cash received field.
        verify(view).setAmountDue(new BigDecimal("8.00"));
        verify(view, never()).setCashReceivedText("8.00");
    }

    @Test
    void nextDollarPressed_leavesAmountDueUnchanged_forWholeDollarAmount() {
        totaledWith(DOLLAR); // 7.00

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED));

        verify(view).setAmountDue(new BigDecimal("7.00"));
    }

    @Test
    void exactAmountPressed_resetsAmountDueToGrandTotal() {
        totaledWith(WIDGET); // 7.30

        // Bump to next dollar first, then back to exact.
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_EXACT_PRESSED));

        verify(view).setAmountDue(new BigDecimal("8.00"));
        verify(view).setAmountDue(new BigDecimal("7.30"));
    }

    @Test
    void confirmPressed_underpayment_isRejectedInline_transactionStaysTotaled() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(cashConfirm("5.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.countOf(PosEventType.ERROR)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("UNDERPAYMENT");
        verify(view).showError(any());
        verify(view, never()).closeDialog();
    }

    @Test
    void confirmPressed_underpayment_isRelativeToAdjustedAmountDue() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30

        // Cashier bumps amount due to $8.00. Handing over $7.50 is now underpayment.
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED));
        pos.dispatchPosEvent(cashConfirm("7.50"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("UNDERPAYMENT");
    }

    @Test
    void confirmPressed_overpayment_paysAndProducesChange() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30

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
        verify(view).showChangeDue(any());
        verify(view).closeDialog();
    }

    @Test
    void confirmPressed_exactAmount_paysWithZeroChange() {
        Transaction tx = totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(cashConfirm("7.30"));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void confirmPressed_afterNextDollar_computesChangeAgainstAdjustedAmountDue() {
        totaledAndOpened(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_NEXT_DOLLAR_PRESSED)); // amountDue = 8.00
        pos.dispatchPosEvent(cashConfirm("8.00")); // customer hands over exactly $8

        // Under user-visible semantics (change = cashReceived − adjusted amount due),
        // the cashier owes zero change: the customer paid the (rounded) total payable.
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("amountDue", BigDecimal.class))
                .isEqualByComparingTo("8.00");
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void confirmPressed_nonNumericInput_isRejected() {
        Transaction tx = totaledAndOpened(WIDGET);

        pos.dispatchPosEvent(cashConfirm("banana"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.countOf(PosEventType.ERROR)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_CASH_AMOUNT");
        verify(view).showError(any());
    }

    @Test
    void confirmPressed_negativeInput_isRejected() {
        Transaction tx = totaledAndOpened(WIDGET);

        pos.dispatchPosEvent(cashConfirm("-5.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_CASH_AMOUNT");
    }

    @Test
    void confirmPressed_whenTransactionIsInProgress_isRejectedByService() {
        // The dialog was opened while TOTALED, but the transaction went back to IN_PROGRESS
        // out from under us — a stale confirm click must be refused by the service.
        totaledWith(WIDGET);
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
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
        verify(view).showError(any());
    }

    @Test
    void cancelPressed_leavesTransactionTotaled_andCloses() {
        Transaction tx = totaledWith(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
        assertThat(notifications.countOf(PosEventType.TRANSACTION_COMPLETED)).isZero();
        verify(view).closeDialog();
    }

    @Test
    void cancelThenReconfirm_paysNormally() {
        Transaction tx = totaledWith(WIDGET); // 7.30

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(cashConfirm("8.00"));

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        PosEvent tendered = notifications.lastOf(PosEventType.CASH_TENDERED);
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.70");
    }

    private static PosEvent cashConfirm(String cashReceived) {
        Map<String, Object> props = new HashMap<>();
        props.put("cashReceived", cashReceived);
        return new PosEvent(PosEventType.CASH_CONFIRM_PRESSED, props);
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
