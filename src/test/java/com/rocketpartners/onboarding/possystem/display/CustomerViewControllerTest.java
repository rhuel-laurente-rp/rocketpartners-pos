package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class CustomerViewControllerTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));
    private static final Item GIZMO = new Item("UPC-G", "Gizmo", new BigDecimal("2.50"));

    private PosComponent pos;
    private CustomerView view;
    private CustomerViewController controller;
    private RecordingListener notifications;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        items.put(GIZMO.getUpc(), GIZMO);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "Test Store",
                1,
                false);
        view = mock(CustomerView.class);
        controller = new CustomerViewController(view);
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
    }

    @Test
    void onStart_startsTransaction_enablesBasketInput_disablesTenderInput_showsWindow() {
        pos.addController(controller);
        pos.start();

        Transaction tx = pos.getTransactionService().getCurrentTransaction();
        assertThat(tx).isNotNull();
        assertThat(tx.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        // The controller feeds the full breakdown (subtotal, discount, tax, total) so the
        // inline summary strip renders live tax; on an empty basket every figure compares
        // equal to zero. Compare by value — the controller's derived figures come from
        // BigDecimal arithmetic (unrounded intermediates, rounded grand total) so scales
        // differ across arguments.
        verify(view).updateBasket(
                eq(List.of()),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(zeroByValue()));
        verify(view).setBasketInputEnabled(true);
        verify(view).setLifecycleInputEnabled(true);
        verify(view).setTenderInputEnabled(false);
        verify(view).setVisible(true);
    }

    @Test
    void quickAddPressed_knownUpc_addsLineItem_dispatchesItemAdded_andRerenders() {
        pos.addController(controller);
        pos.start();
        reset(view);

        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));

        List<LineItem> lines = pos.getTransactionService().getCurrentTransaction().getLineItems();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getItem()).isEqualTo(WIDGET);
        assertThat(notifications.countOf(PosEventType.ITEM_ADDED)).isEqualTo(1);
        // subtotal $10.00, no discount, tax rate 0 → $0.00 tax, grand total $10.00.
        // Compare BigDecimal args by value — see onStart test for the rationale.
        verify(view).updateBasket(
                eq(new ArrayList<>(lines)),
                argThat(v -> v != null && v.compareTo(new BigDecimal("10.00")) == 0),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(v -> v != null && v.compareTo(new BigDecimal("10.00")) == 0));
    }

    @Test
    void scannedKnownUpc_addsItem_andDismissesSearchKeyboard() {
        pos.addController(controller);
        pos.start();
        reset(view);

        pos.dispatchPosEvent(scanned(WIDGET.getUpc()));

        assertThat(notifications.countOf(PosEventType.ITEM_ADDED)).isEqualTo(1);
        // A successful add dismisses the Quick Add search keyboard — stale once the item is in.
        verify(view).dismissSearchKeyboard();
    }

    @Test
    void quickAddPressed_unknownUpc_dispatchesError_leavesBasketUnchanged() {
        pos.addController(controller);
        pos.start();
        reset(view);

        pos.dispatchPosEvent(quickAdd("nope"));

        assertThat(pos.getTransactionService().getCurrentTransaction().getLineItems()).isEmpty();
        assertThat(notifications.countOf(PosEventType.ERROR)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("UPC_NOT_FOUND");
        assertThat(notifications.countOf(PosEventType.ITEM_ADDED)).isZero();
        verify(view, never()).updateBasket(any(), any());
    }

    @Test
    void voidLinePressed_voidsSelectedLine_dispatchesLineVoided_andRerenders() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        LineItem line = pos.getTransactionService().getCurrentTransaction().getLineItems().get(0);
        reset(view);

        pos.dispatchPosEvent(voidLine(line));

        assertThat(line.isVoided()).isTrue();
        assertThat(notifications.countOf(PosEventType.LINE_VOIDED)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.LINE_VOIDED)
                .getProperty("lineItem", LineItem.class)).isSameAs(line);
        // Voided line contributes zero — subtotal, tax, and grand total all compare equal
        // to zero regardless of BigDecimal scale.
        verify(view).updateBasket(
                any(),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(zeroByValue()));
    }

    @Test
    void voidLinePressed_withNoSelection_isNoOp() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        reset(view);

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_LINE_PRESSED));

        assertThat(notifications.countOf(PosEventType.LINE_VOIDED)).isZero();
        verify(view, never()).updateBasket(any(), any());
    }

    @Test
    void voidBasketPressedAlone_isNotEnoughToVoid_awaitsConfirmation() {
        // The initial-press event opens the confirmation dialog (owned by
        // VoidBasketConfirmViewController) but must not by itself mutate transaction state or
        // trigger the reset — voiding is deferred to VOID_BASKET_CONFIRM_PRESSED.
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        Transaction original = pos.getTransactionService().getCurrentTransaction();
        reset(view);

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_PRESSED));

        assertThat(pos.getTransactionService().getCurrentTransaction()).isSameAs(original);
        assertThat(original.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        assertThat(notifications.countOf(PosEventType.BASKET_VOIDED)).isZero();
    }

    @Test
    void voidBasketConfirmed_endsCurrent_dispatchesBasketVoided_andStartsFreshTransaction() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        Transaction original = pos.getTransactionService().getCurrentTransaction();
        reset(view);

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_CONFIRM_PRESSED));

        Transaction next = pos.getTransactionService().getCurrentTransaction();
        assertThat(next).isNotNull();
        assertThat(next).isNotSameAs(original);
        assertThat(next.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        assertThat(original.getState()).isEqualTo(TransactionState.VOIDED);
        assertThat(notifications.countOf(PosEventType.BASKET_VOIDED)).isEqualTo(1);
        verify(view).setBasketInputEnabled(true);
        verify(view).setLifecycleInputEnabled(true);
        verify(view).setTenderInputEnabled(false);
        // Fresh transaction opened by beginNewTransaction — empty basket, all zeros.
        verify(view).updateBasket(
                eq(List.of()),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(zeroByValue()));
    }

    @Test
    void voidBasketConfirmed_carriesItemCountGrandTotalAndPriorState_forJournaling() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(quickAdd(GIZMO.getUpc()));

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_CONFIRM_PRESSED));

        PosEvent voided = notifications.lastOf(PosEventType.BASKET_VOIDED);
        assertThat(voided.getProperty("itemCount", Integer.class)).isEqualTo(3);
        assertThat(voided.getProperty("grandTotal", BigDecimal.class))
                .isEqualByComparingTo(new BigDecimal("22.50"));
        assertThat(voided.getProperty("priorState", String.class)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void voidBasketConfirmedAfterTotal_priorStateIsTotaled() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));

        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_CONFIRM_PRESSED));

        PosEvent voided = notifications.lastOf(PosEventType.BASKET_VOIDED);
        assertThat(voided.getProperty("priorState", String.class))
                .as("voiding after Total must be distinguishable from voiding IN_PROGRESS")
                .isEqualTo("TOTALED");
    }

    @Test
    void voidBasketThenQuickAdd_ringsUpTheNextSale() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(new PosEvent(PosEventType.VOID_BASKET_CONFIRM_PRESSED));

        pos.dispatchPosEvent(quickAdd(GIZMO.getUpc()));

        List<LineItem> lines = pos.getTransactionService().getCurrentTransaction().getLineItems();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getItem()).isEqualTo(GIZMO);
        assertThat(notifications.countOf(PosEventType.ITEM_ADDED)).isEqualTo(2);
    }

    @Test
    void totalPressed_movesToTotaled_switchesInputMode_andDispatchesNotification() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        reset(view);

        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));

        assertThat(pos.getTransactionService().getCurrentTransaction().getState())
                .isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.TRANSACTION_TOTALED)).isEqualTo(1);
        verify(view).setBasketInputEnabled(false);
        // Lifecycle input stays ON at TOTALED — the domain still permits voiding the whole
        // transaction, and grouping Void basket with the mutation controls is exactly the bug
        // this rewrite fixes.
        verify(view).setLifecycleInputEnabled(true);
        verify(view).setTenderInputEnabled(true);
    }

    @Test
    void quickAddAfterTotal_isRefusedByService_notByButtonState() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));
        int addedBefore = notifications.countOf(PosEventType.ITEM_ADDED);
        int errorsBefore = notifications.countOf(PosEventType.ERROR);
        List<LineItem> linesBefore = new ArrayList<>(
                pos.getTransactionService().getCurrentTransaction().getLineItems());

        // Dispatch a QUICK_ADD_PRESSED as if a stale click sneaked past the greyed-out button.
        pos.dispatchPosEvent(quickAdd(GIZMO.getUpc()));

        assertThat(pos.getTransactionService().getCurrentTransaction().getLineItems())
                .isEqualTo(linesBefore);
        assertThat(notifications.countOf(PosEventType.ITEM_ADDED)).isEqualTo(addedBefore);
        assertThat(notifications.countOf(PosEventType.ERROR)).isEqualTo(errorsBefore + 1);
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("TOTALED_INVARIANT");
    }

    @Test
    void receiptDismissed_startsFreshTransaction_andResetsInputMode() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));
        // Simulate a tender-controller completing the sale.
        pos.getTransactionService().tenderCash(new BigDecimal("10.00"));
        reset(view);

        pos.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISSED));

        Transaction next = pos.getTransactionService().getCurrentTransaction();
        assertThat(next).isNotNull();
        assertThat(next.getState()).isEqualTo(TransactionState.IN_PROGRESS);
        verify(view).setBasketInputEnabled(true);
        verify(view).setLifecycleInputEnabled(true);
        verify(view).setTenderInputEnabled(false);
        // Fresh transaction opened after receipt dismissal — empty basket, all zeros.
        verify(view).updateBasket(
                eq(List.of()),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(zeroByValue()),
                argThat(zeroByValue()));
    }

    @Test
    void transactionCompleted_isNotHandledByCustomerViewController() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));
        pos.getTransactionService().tenderCash(new BigDecimal("10.00"));
        reset(view);

        // Reset waits for the receipt to be dismissed — TRANSACTION_COMPLETED alone must not
        // wipe the basket display out from under the receipt.
        pos.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED));

        verify(view, never()).updateBasket(any(), any());
        verify(view, never()).setBasketInputEnabled(true);
    }

    @Test
    void tenderPressedEvents_areNotHandledByCustomerViewController() {
        pos.addController(controller);
        pos.start();
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        pos.dispatchPosEvent(new PosEvent(PosEventType.TOTAL_PRESSED));
        Transaction totaled = pos.getTransactionService().getCurrentTransaction();

        // No child tender controller registered — a stale TENDER_CASH_PRESSED must not tender
        // the transaction; the CustomerViewController is deliberately not subscribed.
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));

        assertThat(totaled.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.CASH_TENDERED)).isZero();
    }

    @Test
    void onEnd_unregistersListener_andDisposesView() {
        pos.addController(controller);
        pos.start();
        reset(view);

        pos.removeController(controller);

        // After onEnd, an input event must no longer reach the controller.
        pos.dispatchPosEvent(quickAdd(WIDGET.getUpc()));
        verify(view, never()).updateBasket(any(), any());
        verify(view).dispose();
    }

    // ---- Helpers -----------------------------------------------------------

    /**
     * A Mockito matcher that accepts any {@link BigDecimal} whose value compares equal to
     * zero. Necessary because {@code Transaction} returns unrounded subtotals/discounts/tax
     * ({@code BigDecimal.ZERO}, scale 0) but rounded grand totals (scale 2), and
     * {@link BigDecimal#equals} distinguishes {@code 0} from {@code 0.00}.
     */
    private static org.mockito.ArgumentMatcher<BigDecimal> zeroByValue() {
        return v -> v != null && v.compareTo(BigDecimal.ZERO) == 0;
    }

    private static PosEvent quickAdd(String upc) {
        Map<String, Object> props = new HashMap<>();
        props.put("upc", upc);
        return new PosEvent(PosEventType.QUICK_ADD_PRESSED, props);
    }

    private static PosEvent scanned(String upc) {
        Map<String, Object> props = new HashMap<>();
        props.put("upc", upc);
        props.put("source", "scan");
        return new PosEvent(PosEventType.ITEM_SCANNED, props);
    }

    private static PosEvent voidLine(LineItem line) {
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", line);
        return new PosEvent(PosEventType.VOID_LINE_PRESSED, props);
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
