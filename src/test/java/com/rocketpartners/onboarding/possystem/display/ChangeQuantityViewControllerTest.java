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
import com.rocketpartners.onboarding.possystem.service.TransactionService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChangeQuantityViewControllerTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    private PosComponent pos;
    private ChangeQuantityView view;
    private ChangeQuantityViewController controller;
    private RecordingListener notifications;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(WIDGET.getUpc(), WIDGET);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(new BigDecimal("0.07")),
                "Test Store",
                1,
                false);
        view = mock(ChangeQuantityView.class);
        controller = new ChangeQuantityViewController(view);
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
        pos.addController(controller);
        pos.start();
    }

    private LineItem addWidget(int qty) {
        pos.getTransactionService().startTransaction();
        return pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), qty);
    }

    private static PosEvent pressedFor(LineItem line) {
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", line);
        return new PosEvent(PosEventType.CHANGE_QTY_PRESSED, props);
    }

    private static PosEvent confirmFor(LineItem line, int qty) {
        Map<String, Object> props = new HashMap<>();
        props.put("lineItem", line);
        props.put("newQuantity", qty);
        return new PosEvent(PosEventType.CHANGE_QTY_CONFIRM_PRESSED, props);
    }

    @Test
    void changeQtyPressed_opensDialogForSelectedLine() {
        LineItem line = addWidget(2);

        pos.dispatchPosEvent(pressedFor(line));

        verify(view).openFor(line);
    }

    @Test
    void changeQtyPressed_onAlreadyVoidedLine_doesNotOpenDialog() {
        LineItem line = addWidget(2);
        pos.getTransactionService().voidLine(line);

        pos.dispatchPosEvent(pressedFor(line));

        verify(view, never()).openFor(line);
    }

    @Test
    void confirm_increasingQuantity_recomputesTotals_andDispatchesQuantityChanged() {
        LineItem line = addWidget(1); // subtotal 10.00
        Transaction tx = pos.getTransactionService().getCurrentTransaction();

        pos.dispatchPosEvent(confirmFor(line, 3));

        assertThat(line.getQuantity()).isEqualTo(3);
        assertThat(tx.subtotal()).isEqualByComparingTo("30.00");
        assertThat(tx.taxTotal()).isEqualByComparingTo("2.10");
        assertThat(tx.grandTotal()).isEqualByComparingTo("32.10");
        assertThat(notifications.countOf(PosEventType.QUANTITY_CHANGED)).isEqualTo(1);
        PosEvent qc = notifications.lastOf(PosEventType.QUANTITY_CHANGED);
        assertThat(qc.getProperty("lineItem", LineItem.class)).isSameAs(line);
        assertThat(qc.getProperty("newQuantity", Integer.class)).isEqualTo(3);
        verify(view).closeDialog();
    }

    @Test
    void confirm_decreasingQuantity_recomputesTotals() {
        LineItem line = addWidget(5); // subtotal 50.00
        Transaction tx = pos.getTransactionService().getCurrentTransaction();

        pos.dispatchPosEvent(confirmFor(line, 2));

        assertThat(line.getQuantity()).isEqualTo(2);
        assertThat(tx.subtotal()).isEqualByComparingTo("20.00");
        assertThat(tx.taxTotal()).isEqualByComparingTo("1.40");
        assertThat(tx.grandTotal()).isEqualByComparingTo("21.40");
        assertThat(notifications.countOf(PosEventType.QUANTITY_CHANGED)).isEqualTo(1);
    }

    @Test
    void confirmQuantityThree_yieldsSameTotalsAsScanningThreeTimes() {
        // Rebuild in a NO_TAX service for simplicity — the invariant that concerns us is that
        // update-quantity and add-item route through the same recompute path.
        pos = new PosComponent(
                new InMemoryItemRepository(Map.of(WIDGET.getUpc(), WIDGET)),
                new TaxService(BigDecimal.ZERO),
                "Test Store", 1, false);
        view = mock(ChangeQuantityView.class);
        controller = new ChangeQuantityViewController(view);
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
        pos.addController(controller);
        pos.start();

        LineItem line = addWidget(1);
        pos.dispatchPosEvent(confirmFor(line, 3));
        BigDecimal viaChangeQty = pos.getTransactionService().getCurrentTransaction().grandTotal();

        // Reset and ring up by scanning three times.
        pos.getTransactionService().voidBasket();
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        pos.getTransactionService().addItemByUpc(WIDGET.getUpc(), 1);
        BigDecimal viaScan = pos.getTransactionService().getCurrentTransaction().grandTotal();

        assertThat(viaChangeQty).isEqualByComparingTo(viaScan);
    }

    @Test
    void confirmNegative_isRejectedWithErrorEvent_leavesLineUnchanged() {
        LineItem line = addWidget(2);

        pos.dispatchPosEvent(confirmFor(line, -1));

        assertThat(line.getQuantity()).isEqualTo(2);
        assertThat(notifications.countOf(PosEventType.QUANTITY_CHANGED)).isZero();
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err.getProperty("code", String.class)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void confirmAboveMax_isRejectedWithAboveMaxErrorEvent() {
        LineItem line = addWidget(1);

        pos.dispatchPosEvent(confirmFor(line, 99999));

        assertThat(line.getQuantity()).isEqualTo(1);
        assertThat(notifications.countOf(PosEventType.QUANTITY_CHANGED)).isZero();
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err.getProperty("code", String.class)).isEqualTo("ABOVE_MAX_QUANTITY");
    }

    @Test
    void confirmWhileTotaled_isRejectedWithErrorEvent() {
        LineItem line = addWidget(2);
        pos.getTransactionService().total();

        pos.dispatchPosEvent(confirmFor(line, 3));

        assertThat(line.getQuantity()).isEqualTo(2);
        assertThat(notifications.countOf(PosEventType.QUANTITY_CHANGED)).isZero();
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err.getProperty("code", String.class)).isEqualTo("TOTALED_INVARIANT");
    }

    @Test
    void confirmOnAlreadyVoidedLine_isRejectedWithErrorEvent() {
        LineItem line = addWidget(2);
        pos.getTransactionService().voidLine(line);

        pos.dispatchPosEvent(confirmFor(line, 3));

        assertThat(line.isVoided()).isTrue();
        assertThat(line.getQuantity()).isEqualTo(2);
        assertThat(notifications.countOf(PosEventType.QUANTITY_CHANGED)).isZero();
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err.getProperty("code", String.class)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void confirmUnchangedQuantity_isNoOp_dispatchesNoEvent() {
        LineItem line = addWidget(3);
        int errsBefore = notifications.countOf(PosEventType.ERROR);
        int qcBefore = notifications.countOf(PosEventType.QUANTITY_CHANGED);

        pos.dispatchPosEvent(confirmFor(line, 3));

        assertThat(notifications.countOf(PosEventType.ERROR)).isEqualTo(errsBefore);
        assertThat(notifications.countOf(PosEventType.QUANTITY_CHANGED)).isEqualTo(qcBefore);
        verify(view).closeDialog();
    }

    @Test
    void cancelPressed_leavesLineUntouched_andCloses() {
        LineItem line = addWidget(2);

        pos.dispatchPosEvent(pressedFor(line));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CHANGE_QTY_CANCEL_PRESSED));

        assertThat(line.getQuantity()).isEqualTo(2);
        assertThat(notifications.countOf(PosEventType.QUANTITY_CHANGED)).isZero();
        verify(view).closeDialog();
    }

    @Test
    void changeQtyDialog_doesNotSuspendScannerCapture_onOpenOrClose() {
        // Aligned with today's behaviour: ScannerViewController reacts to CHANGE_QTY_PRESSED
        // by calling resumeCapture(), not suspendCapture(), so isSuspended() stays false the
        // whole way through. The class Javadoc claims a suspend/resume dance; that mismatch
        // is tracked in docs/known-issues.md as a follow-up.
        com.rocketpartners.onboarding.possystem.component.BarcodeInputBuffer buffer =
                new com.rocketpartners.onboarding.possystem.component.BarcodeInputBuffer();
        ScannerView scannerView = mock(ScannerView.class);
        javax.swing.JTextField scanField = new javax.swing.JTextField();
        org.mockito.Mockito.when(scannerView.getScanField()).thenReturn(scanField);
        ScannerViewController.KeyDispatchInstaller noopInstaller = d -> () -> {};
        ScannerViewController scannerController = new ScannerViewController(
                scannerView, buffer, false, noopInstaller, () -> 0L);
        pos.addController(scannerController);

        LineItem line = addWidget(2);
        pos.dispatchPosEvent(pressedFor(line));
        assertThat(scannerController.isSuspended()).isFalse();

        pos.dispatchPosEvent(new PosEvent(PosEventType.CHANGE_QTY_CANCEL_PRESSED));
        assertThat(scannerController.isSuspended()).isFalse();
    }

    // ---- helpers -----------------------------------------------------------

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
