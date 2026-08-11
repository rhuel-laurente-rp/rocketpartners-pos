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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PayWithCardViewControllerTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    private PosComponent pos;
    private PayWithCardView view;
    private TenderConfirmView confirmView;
    private QueuedScheduler scheduler;
    private PayWithCardViewController controller;
    private RecordingListener notifications;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "Test Store",
                1,
                false);
        view = mock(PayWithCardView.class);
        confirmView = mock(TenderConfirmView.class);
        scheduler = new QueuedScheduler();
        controller = new PayWithCardViewController(view, confirmView, scheduler);
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
        pos.addController(controller);
        pos.start();
    }

    private Transaction totaledWith(Item item) {
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(item.getUpc(), 1);
        return pos.getTransactionService().total();
    }

    @Test
    void tenderDebitPressed_opensConfirmation_doesNotProcessUntilConfirmed() {
        Transaction tx = totaledWith(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_DEBIT_PRESSED));

        // The button press opens the confirmation only — no card processing starts.
        verify(confirmView).openFor(any(), any(), any(), eq(new BigDecimal("10.00")));
        verify(view, never()).showProcessing();
        verify(view, never()).openDialog();
        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CARD_TENDERED)).isZero();
    }

    @Test
    void tenderDebitConfirmed_opensDialogInProcessingState_thenApprovesAndCompletes() {
        Transaction tx = totaledWith(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_DEBIT_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDER_CONFIRM_PRESSED));

        verify(view).configure(eq(TenderType.DEBIT), eq(new BigDecimal("10.00")));
        verify(view).showProcessing();
        verify(view).openDialog();
        // Approval hasn't fired yet — still in-flight.
        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CARD_TENDERED)).isZero();

        scheduler.runNext();

        assertThat(tx.getState()).isEqualTo(TransactionState.PAID);
        assertThat(tx.getTenderType()).isEqualTo(TenderType.DEBIT);
        assertThat(tx.getCashTendered()).isEqualByComparingTo("10.00");
        assertThat(tx.changeDue()).isEqualByComparingTo("0.00");
        verify(view).showApproved();
        verify(view).closeDialog();
        assertThat(notifications.countOf(PosEventType.CARD_TENDERED)).isEqualTo(1);
        assertThat(notifications.countOf(PosEventType.TRANSACTION_COMPLETED)).isEqualTo(1);
        PosEvent tendered = notifications.lastOf(PosEventType.CARD_TENDERED);
        assertThat(tendered.getProperty("tenderType", TenderType.class)).isEqualTo(TenderType.DEBIT);
        assertThat(tendered.getProperty("amountTendered", BigDecimal.class))
                .isEqualByComparingTo("10.00");
        assertThat(tendered.getProperty("changeDue", BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void tenderCreditPressed_completesWithCreditTender() {
        Transaction tx = totaledWith(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CREDIT_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDER_CONFIRM_PRESSED));
        scheduler.runNext();

        assertThat(tx.getTenderType()).isEqualTo(TenderType.CREDIT);
        verify(view).configure(eq(TenderType.CREDIT), eq(new BigDecimal("10.00")));
        PosEvent tendered = notifications.lastOf(PosEventType.CARD_TENDERED);
        assertThat(tendered.getProperty("tenderType", TenderType.class)).isEqualTo(TenderType.CREDIT);
    }

    @Test
    void cardApproval_completesWithZeroChange() {
        Transaction tx = totaledWith(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_DEBIT_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDER_CONFIRM_PRESSED));
        scheduler.runNext();

        assertThat(tx.changeDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void tenderPressedInWrongState_isRejectedByService_noCompletion() {
        // Bring transaction to IN_PROGRESS (not TOTALED).
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        Transaction tx = pos.getTransactionService().getCurrentTransaction();

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_DEBIT_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDER_CONFIRM_PRESSED));
        scheduler.runNext();

        assertThat(tx.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        assertThat(notifications.countOf(PosEventType.CARD_TENDERED)).isZero();
        PosEvent error = notifications.lastOf(PosEventType.ERROR);
        assertThat(error).isNotNull();
        assertThat(error.getProperty("code", String.class)).isEqualTo("TOTALED_INVARIANT");
    }

    @Test
    void cancelFromConfirmation_leavesTransactionTotaled_noProcessing() {
        Transaction tx = totaledWith(WIDGET);

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_DEBIT_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDER_CANCELLED));

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CARD_TENDERED)).isZero();
        verify(view, never()).showProcessing();
        verify(confirmView).closeDialog();
    }

    /**
     * Test double: captures the callback rather than running it on a real Swing timer, so tests
     * can decide when "approval" fires.
     */
    static final class QueuedScheduler implements PayWithCardViewController.ApprovalScheduler {
        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void schedule(Runnable callback) {
            queue.add(callback);
        }

        void runNext() {
            Runnable r = queue.pollFirst();
            if (r != null) r.run();
        }
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
