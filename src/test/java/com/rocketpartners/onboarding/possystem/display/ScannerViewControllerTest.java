package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.component.BarcodeInputBuffer;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.repository.inmemory.InMemoryItemRepository;
import com.rocketpartners.onboarding.possystem.service.TaxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTextField;
import java.awt.KeyEventDispatcher;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScannerViewControllerTest {

    private static final Item COKE = new Item("049000053418", "COCA COLA CAN", new BigDecimal("1.99"));

    private PosComponent pos;
    private ScannerView view;
    private JTextField scanField;
    private BarcodeInputBuffer buffer;
    private ScannerViewController controller;
    private RecordingListener notifications;
    private CapturingInstaller installer;
    private AtomicLong clockTs;

    @BeforeEach
    void setUp() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put(COKE.getUpc(), COKE);
        pos = new PosComponent(
                new InMemoryItemRepository(items),
                new TaxService(BigDecimal.ZERO),
                "Test Store",
                1,
                false);
        view = mock(ScannerView.class);
        scanField = new JTextField();
        when(view.getScanField()).thenReturn(scanField);
        buffer = new BarcodeInputBuffer();
        installer = new CapturingInstaller();
        clockTs = new AtomicLong(1000L);
        controller = new ScannerViewController(view, buffer, false, installer, clockTs::get);
        notifications = new RecordingListener(EnumSet.allOf(PosEventType.class));
        pos.register(notifications);
        pos.addController(controller);
        pos.start();
    }

    private void tickAdvance(long ms) {
        clockTs.addAndGet(ms);
    }

    private boolean typed(char c) {
        // Match the JDK's own KEY_TYPED convention: keyCode is VK_UNDEFINED, char carries it.
        KeyEvent e = new KeyEvent(scanField, KeyEvent.KEY_TYPED, clockTs.get(),
                0, KeyEvent.VK_UNDEFINED, c);
        return installer.currentDispatcher.dispatchKeyEvent(e);
    }

    private void burst(String s, long gapMs) {
        for (int i = 0; i < s.length(); i++) {
            typed(s.charAt(i));
            tickAdvance(gapMs);
        }
    }

    private Transaction ensureInProgress() {
        pos.getTransactionService().startTransaction();
        return pos.getTransactionService().getCurrentTransaction();
    }

    @Test
    void scannerBurst_dispatchesItemScanned_withUpc() {
        ensureInProgress();

        burst("049000053418", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        PosEvent scanned = notifications.lastOf(PosEventType.ITEM_SCANNED);
        assertThat(scanned.getProperty("upc", String.class)).isEqualTo("049000053418");
        assertThat(scanned.getProperty("source", String.class)).isEqualTo("scan");
    }

    @Test
    void nonTerminatorKeystrokes_passThrough_soTextFieldsReceiveTyping() {
        ensureInProgress();

        // Individual digits are NOT consumed — they pass through so the scan field (and any
        // other JTextField, e.g. cash-received) receives them normally. Only the terminator
        // that closes a scanner burst gets consumed.
        boolean consumed1 = typed('0');
        boolean consumed2 = typed('4');

        assertThat(consumed1).isFalse();
        assertThat(consumed2).isFalse();
    }

    @Test
    void scannerTerminator_thatClosesABurst_isConsumed() {
        ensureInProgress();

        burst("049000053418", 5);
        boolean consumedEnter = typed('\n');

        // Consume the terminator so it doesn't ALSO fire the scan field's Enter action.
        assertThat(consumedEnter).isTrue();
    }

    @Test
    void terminator_thatDidNotCloseABurst_passesThrough() {
        ensureInProgress();

        // Enter with an empty buffer — e.g. cashier pressed Enter in the scan field after
        // typing manually. Buffer emits nothing; dispatcher must NOT consume, so the field's
        // own Enter action can fire.
        boolean consumed = typed('\n');

        assertThat(consumed).isFalse();
    }

    @Test
    void keyEventDelivered_whileFocusOnQuickAddButton_stillReachesBuffer() {
        ensureInProgress();

        // The KeyEventDispatcher sits at the KeyboardFocusManager level, so it sees a key
        // event even if focus was on a JButton (Quick Add) at the time. We simulate that by
        // firing a KEY_TYPED whose source is a JButton, not the scan field.
        JButton quickAdd = new JButton("Coca Cola");
        for (char c : "049000053418".toCharArray()) {
            KeyEvent e = new KeyEvent(quickAdd, KeyEvent.KEY_TYPED, clockTs.get(),
                    0, KeyEvent.VK_UNDEFINED, c);
            installer.currentDispatcher.dispatchKeyEvent(e);
            tickAdvance(5);
        }
        KeyEvent enter = new KeyEvent(quickAdd, KeyEvent.KEY_TYPED, clockTs.get(),
                0, KeyEvent.VK_UNDEFINED, '\n');
        installer.currentDispatcher.dispatchKeyEvent(enter);

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.ITEM_SCANNED)
                .getProperty("upc", String.class)).isEqualTo("049000053418");
    }

    @Test
    void manualEnter_submitsScanField_dispatchesItemScannedWhenValid() {
        ensureInProgress();

        Map<String, Object> props = new HashMap<>();
        props.put("raw", "049000053418");
        pos.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        assertThat(notifications.lastOf(PosEventType.ITEM_SCANNED)
                .getProperty("source", String.class)).isEqualTo("manualScan");
    }

    @Test
    void manualEnter_withEmptyField_producesInvalidBarcodeError() {
        ensureInProgress();

        Map<String, Object> props = new HashMap<>();
        props.put("raw", "");
        pos.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_BARCODE");
    }

    @Test
    void nonNumericInput_isRejectedAsInvalidBarcode() {
        ensureInProgress();

        // "banana\n" — non-digit chars in a fast burst.
        burst("bananabanan1", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_BARCODE");
    }


    @Test
    void unknownUpc_producesItemNotFoundErrorFromService_leavesTransactionUnchanged() {
        Transaction tx = ensureInProgress();

        // Trigger a valid scan whose UPC isn't in the pricebook. The controller dispatches
        // ITEM_SCANNED; CustomerViewController would call service.addItemByUpc which raises
        // the UPC_NOT_FOUND error via the service. For an isolated test we add a customer
        // controller so the full loop happens.
        CustomerView customerView = mock(CustomerView.class);
        pos.addController(new CustomerViewController(customerView));
        // A brand-new tx opened when the customer controller onStart ran; use that.
        tx = pos.getTransactionService().getCurrentTransaction();

        burst("999999999999", 5);
        typed('\n');

        assertThat(tx.getLineItems()).isEmpty();
        // The scanner controller dispatched ITEM_SCANNED, the CustomerViewController tried
        // service.addItemByUpc, and the service dispatched UPC_NOT_FOUND.
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err).isNotNull();
        assertThat(err.getProperty("code", String.class)).isEqualTo("UPC_NOT_FOUND");
    }

    @Test
    void scanRejected_whenTransactionIsTotaled_dispatchesScanLockedError() {
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(COKE.getUpc(), 1);
        Transaction tx = pos.getTransactionService().total();

        burst("049000053418", 5);
        typed('\n');

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err.getProperty("code", String.class)).isEqualTo("SCAN_LOCKED");
    }

    @Test
    void tenderCashPressed_suspendsCapture_burstIsIgnored() {
        ensureInProgress();

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        assertThat(controller.isSuspended()).isTrue();

        burst("049000053418", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
    }

    @Test
    void cashCancel_resumesCapture() {
        ensureInProgress();

        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        pos.dispatchPosEvent(new PosEvent(PosEventType.CASH_CANCEL_PRESSED));
        assertThat(controller.isSuspended()).isFalse();

        burst("049000053418", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
    }

    @Test
    void receiptDismissed_resumesCapture() {
        ensureInProgress();

        pos.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED));
        assertThat(controller.isSuspended()).isTrue();
        pos.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISSED));
        assertThat(controller.isSuspended()).isFalse();
    }

    @Test
    void fieldClearedAndFocusRestored_afterAcceptedScan() {
        ensureInProgress();

        burst("049000053418", 5);
        typed('\n');

        verify(view).clearScanField();
        verify(view, times(2)).requestScanFieldFocus(); // once at start, once after scan
    }

    @Test
    void fieldClearedAndFocusRestored_afterRejectedScan() {
        ensureInProgress();

        // Non-numeric burst — rejected as INVALID_BARCODE. (Wrong-length is no longer a
        // rejection reason: the pricebook carries UPCs of assorted lengths.)
        burst("bananana1234", 5);
        typed('\n');

        // Rejected scan: the completed handler still clears & refocuses so the cashier can
        // rescan. Focus is also restored on the ERROR event that the rejection dispatches.
        verify(view).clearScanField();
        verify(view, org.mockito.Mockito.atLeast(2)).requestScanFieldFocus();
        assertThat(notifications.lastOf(PosEventType.ERROR).getProperty("code", String.class))
                .isEqualTo("INVALID_BARCODE");
    }

    @Test
    void demoHotkey_replaysCannedUpc_whenDebugOn() {
        // Rebuild with debug=true.
        pos.removeController(controller);
        installer = new CapturingInstaller();
        controller = new ScannerViewController(view, buffer, true, installer, clockTs::get);
        pos.addController(controller);
        ensureInProgress();

        KeyEvent f12 = new KeyEvent(scanField, KeyEvent.KEY_PRESSED, clockTs.get(),
                0, KeyEvent.VK_F12, KeyEvent.CHAR_UNDEFINED);
        boolean consumed = installer.currentDispatcher.dispatchKeyEvent(f12);

        assertThat(consumed).isTrue();
        PosEvent scanned = notifications.lastOf(PosEventType.ITEM_SCANNED);
        assertThat(scanned).isNotNull();
        assertThat(scanned.getProperty("upc", String.class)).isEqualTo(ScannerViewController.DEMO_UPC);
    }

    @Test
    void demoHotkey_isInert_whenDebugOff() {
        ensureInProgress();

        KeyEvent f12 = new KeyEvent(scanField, KeyEvent.KEY_PRESSED, clockTs.get(),
                0, KeyEvent.VK_F12, KeyEvent.CHAR_UNDEFINED);
        boolean consumed = installer.currentDispatcher.dispatchKeyEvent(f12);

        assertThat(consumed).isFalse();
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
    }

    @Test
    void onEnd_uninstallsDispatcher() {
        assertThat(installer.installed).isTrue();

        pos.removeController(controller);

        assertThat(installer.uninstalled).isTrue();
    }

    // ---- Helpers -----------------------------------------------------------

    /** Captures the installed dispatcher so the test can drive it synchronously. */
    static final class CapturingInstaller implements ScannerViewController.KeyDispatchInstaller {
        KeyEventDispatcher currentDispatcher;
        boolean installed;
        boolean uninstalled;

        @Override
        public Runnable install(KeyEventDispatcher dispatcher) {
            this.currentDispatcher = dispatcher;
            this.installed = true;
            return () -> {
                this.uninstalled = true;
                this.currentDispatcher = null;
            };
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
