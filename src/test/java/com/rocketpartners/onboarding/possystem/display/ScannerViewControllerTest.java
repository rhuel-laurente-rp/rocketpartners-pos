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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
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
    void digitKeystrokes_areConsumedOptimistically_notLeakedToTheFocusedField() {
        // Optimistic capture: a digit is held in the buffer (consumed) until timing reveals
        // whether it's a scan or human typing, so nothing leaks into the focused field mid-burst.
        ensureInProgress();

        boolean consumed1 = typed('0');
        boolean consumed2 = typed('4');

        assertThat(consumed1).isTrue();
        assertThat(consumed2).isTrue();
    }

    @Test
    void nonDigitKeystrokes_passThrough_soLettersReachTextFields() {
        // Letters are never buffered — typing into a search field is unaffected.
        ensureInProgress();

        assertThat(typed('a')).isFalse();
        assertThat(typed('.')).isFalse();
    }

    @Test
    void scannerTerminator_thatClosesABurst_isConsumed() {
        ensureInProgress();

        burst("049000053418", 5);
        boolean consumedEnter = typed('\n');

        assertThat(consumedEnter).isTrue();
    }

    @Test
    void terminator_thatDidNotCloseABurst_passesThrough() {
        ensureInProgress();

        boolean consumed = typed('\n');

        assertThat(consumed).isFalse();
    }

    @Test
    void keyEventDelivered_whileFocusOnQuickAddButton_stillReachesBuffer() {
        ensureInProgress();

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
    void hardwareBurstAndManualEntry_carryDistinctSourceTags() {
        // The journal distinguishes hardware reads from manual entry via the ITEM_SCANNED "source"
        // tag: a fast burst is "scan", a field submit is "manualScan". Removing the Scan button
        // (Enter is now the only manual trigger) must not collapse that distinction.
        ensureInProgress();

        burst("049000053418", 5);
        typed('\n');
        String hardwareSource = notifications.lastOf(PosEventType.ITEM_SCANNED)
                .getProperty("source", String.class);

        Map<String, Object> props = new HashMap<>();
        props.put("raw", "049000053418");
        pos.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));
        String manualSource = notifications.lastOf(PosEventType.ITEM_SCANNED)
                .getProperty("source", String.class);

        assertThat(hardwareSource).isEqualTo("scan");
        assertThat(manualSource).isEqualTo("manualScan");
        assertThat(hardwareSource).isNotEqualTo(manualSource);
    }

    @Test
    void manualEnter_withEmptyField_paintsInlineErrorAndDispatchesNoErrorEvent() {
        ensureInProgress();

        Map<String, Object> props = new HashMap<>();
        props.put("raw", "");
        pos.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
        // No ERROR dispatched — INVALID_BARCODE is painted inline by the controller.
        assertThat(notifications.countOf(PosEventType.ERROR)).isZero();
        verify(view).setInlineError(ScannerViewController.MSG_BARCODE_NOT_RECOGNISED);
    }

    @Test
    void letterBurst_passesThrough_isNotCapturedAsAScan() {
        // Under optimistic capture, non-digits are never buffered — a run of letters is human
        // typing that passes straight through to the focused field, dispatching no scan. (Invalid
        // barcodes now surface only via manual submit; see manualEnter tests.)
        ensureInProgress();

        burst("banana", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
        assertThat(notifications.countOf(PosEventType.ERROR)).isZero();
    }

    @Test
    void keypadEmittedDigits_reachBufferAndComplete_theScan() {
        ensureInProgress();

        burst("049000053418", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
    }

    @Test
    void twelveDigitUnknown_withBadCheckDigit_paintsInlineMisreadHint() {
        // 049000053417 — same digits as pricebook COKE UPC 049000053418 but with a bad
        // last digit. Not in the pricebook. TransactionService dispatches UPC_MISREAD;
        // scanner controller paints it inline.
        pos.getTransactionService().startTransaction();
        pos.addController(new CustomerViewController(mock(CustomerView.class)));

        burst("049000053417", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err).isNotNull();
        assertThat(err.getProperty("code", String.class)).isEqualTo("UPC_MISREAD");
        verify(view).setInlineError(ScannerViewController.MSG_BARCODE_MISREAD);
    }

    @Test
    void twelveDigitUnknown_withGoodCheckDigit_paintsInlineItemNotFound() {
        pos.getTransactionService().startTransaction();
        pos.addController(new CustomerViewController(mock(CustomerView.class)));

        burst("012345678905", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err).isNotNull();
        assertThat(err.getProperty("code", String.class)).isEqualTo("UPC_NOT_FOUND");
        verify(view).setInlineError(
                ScannerViewController.MSG_ITEM_NOT_FOUND_PREFIX + "012345678905");
    }

    @Test
    void unknownShortUpc_paintsInlineItemNotFound_leavesTransactionUnchanged() {
        Transaction tx = ensureInProgress();

        CustomerView customerView = mock(CustomerView.class);
        pos.addController(new CustomerViewController(customerView));
        tx = pos.getTransactionService().getCurrentTransaction();

        // Short unknown code (not 12 digits) — misread heuristic does not apply, so we get
        // UPC_NOT_FOUND from the service, painted inline by the scanner controller.
        burst("999", 5);
        typed('\n');

        assertThat(tx.getLineItems()).isEmpty();
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        PosEvent err = notifications.lastOf(PosEventType.ERROR);
        assertThat(err).isNotNull();
        assertThat(err.getProperty("code", String.class)).isEqualTo("UPC_NOT_FOUND");
        verify(view).setInlineError(ScannerViewController.MSG_ITEM_NOT_FOUND_PREFIX + "999");
    }

    @Test
    void scanRejected_whenTransactionIsTotaled_paintsInlineLockHintNotDialog() {
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(COKE.getUpc(), 1);
        Transaction tx = pos.getTransactionService().total();

        burst("049000053418", 5);
        typed('\n');

        assertThat(tx.getState()).isEqualTo(TransactionState.TOTALED);
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
        // No ERROR event — the SCAN_LOCKED case is inline-only.
        assertThat(notifications.countOf(PosEventType.ERROR)).isZero();
        verify(view).setInlineError(ScannerViewController.MSG_SCAN_LOCKED);
    }

    @Test
    void transactionTotaledEvent_locksView() {
        ensureInProgress();
        pos.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_TOTALED));

        verify(view).setLocked(true);
    }

    @Test
    void receiptDismissed_unlocksView() {
        ensureInProgress();

        pos.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_TOTALED));
        // onStart already called setLocked(false); the totaled event calls setLocked(true).
        // After RECEIPT_DISMISSED the view must be unlocked again.
        verify(view).setLocked(true);
        pos.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISSED));

        verify(view, atLeastOnce()).setLocked(false);
    }

    @Test
    void tenderCashPressed_suspendsCapture_burstDoesNotDispatch() {
        ensureInProgress();

        // TENDER_CASH_PRESSED opens the cash modal — capture suspends so a scanner burst can't
        // leak into it.
        pos.dispatchPosEvent(new PosEvent(PosEventType.TENDER_CASH_PRESSED));
        assertThat(controller.isSuspended()).isTrue();

        burst("049000053418", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
    }

    @Test
    void transactionCompleted_suspendsCapture_thenReceiptDismissed_resumes() {
        ensureInProgress();

        // The receipt modal opens on TRANSACTION_COMPLETED → capture suspends; a burst fired
        // while the receipt is up must not dispatch.
        pos.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED));
        assertThat(controller.isSuspended()).isTrue();

        burst("049000053418", 5);
        typed('\n');
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();

        // Dismissing the receipt resumes capture and starts a fresh sale — a burst now dispatches.
        pos.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISSED));
        assertThat(controller.isSuspended()).isFalse();

        burst("049000053418", 5);
        typed('\n');
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
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
    void fieldClearedAndFocusRestored_afterAcceptedScan() {
        ensureInProgress();

        burst("049000053418", 5);
        typed('\n');

        verify(view).clearScanField();
        // onStart requests focus, and every accepted scan requests focus again.
        verify(view, atLeastOnce()).requestScanFieldFocus();
    }

    @Test
    void rejectedManualSubmit_keepsFieldTextForRetry() {
        // On rejected manual entry the field must NOT be cleared — the wrong text stays in
        // place (and setInlineError selects it) so the cashier sees what they typed and the
        // next keystroke replaces it wholesale. Clearing would leave the Scan button disabled
        // with no obvious reason.
        ensureInProgress();

        Map<String, Object> props = new HashMap<>();
        props.put("raw", "bananana1234");
        pos.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));

        verify(view, times(0)).clearScanField();
        verify(view).setInlineError(ScannerViewController.MSG_BARCODE_NOT_RECOGNISED);
        verify(view, atLeastOnce()).requestScanFieldFocus();
    }

    @Test
    void keystroke_clearsInlineErrorFromPreviousScan() {
        // Lock the transaction so a scan burst is rejected inline (SCAN_LOCKED), then confirm the
        // next keystroke clears the stale hint before the buffer processes it.
        pos.getTransactionService().startTransaction();
        pos.getTransactionService().addItemByUpc(COKE.getUpc(), 1);
        pos.getTransactionService().total();

        burst("049000053418", 5);
        typed('\n');
        verify(view).setInlineError(ScannerViewController.MSG_SCAN_LOCKED);

        typed('0');
        // Any keystroke reaching the dispatcher clears the error before the buffer accepts it.
        verify(view, atLeastOnce()).clearInlineError();
    }

    @Test
    void demoHotkey_replaysCannedUpc_whenDebugOn() {
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

    @Test
    void acceptedScan_pulsesTheField() {
        ensureInProgress();

        pos.addController(new CustomerViewController(mock(CustomerView.class)));
        // Re-seed transaction since adding CustomerViewController opens its own.
        pos.getTransactionService().getCurrentTransaction();

        burst("049000053418", 5);
        typed('\n');

        // ITEM_SCANNED → CustomerViewController → ITEM_ADDED → scanner pulses.
        verify(view, atLeastOnce()).pulseGo();
    }

    @Test
    void nonScanError_stillOpensModal_notInline() {
        // A cash-flow error is not a scan-bar concern — the scanner controller must not paint
        // it inline. Dispatch a non-scan ERROR code and verify no inline call is made.
        ensureInProgress();

        Map<String, Object> props = new HashMap<>();
        props.put("code", "INVALID_CASH_AMOUNT");
        props.put("message", "cash received is not a valid number: banana");
        pos.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));

        // No inline call for a non-scan code.
        verify(view, times(0)).setInlineError(org.mockito.ArgumentMatchers.anyString());
    }

    // ---- Manual entry into the focused scan field -------------------------

    @Test
    void humanTypingIntoFocusedScanField_passesThrough_isNotHeldOrCapturedAsBurst() {
        // Regression: with focus on the bar's own scan field, the app-wide dispatcher must NOT run
        // its optimistic digit capture. Previously each digit was consumed and held, so a
        // human-speed UPC left only a fragment in the field — Enter then submitted the trailing
        // digit as its own scan ("Item Not Found — 2"), or the text was garbled and no inline
        // error fired at all. Every keystroke must pass straight through (dispatcher returns false)
        // so the field accumulates the full text.
        installCaptureController(() -> scanField, new ManualScheduler());
        ensureInProgress();

        for (char c : "012345678905".toCharArray()) {
            boolean consumed = typed(c);
            assertThat(consumed)
                    .as("digit '%s' typed into the focused scan field must pass through, not be consumed", c)
                    .isFalse();
            tickAdvance(120);   // human speed — each gap exceeds the 50ms burst threshold
        }

        // The dispatcher must not have manufactured a scan from the held fragments, and the Enter
        // terminator likewise passes through so the field's own action submits the whole text.
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED))
                .as("no scan may be dispatched from held fragments while typing into the field")
                .isZero();
        assertThat(typed('\n'))
                .as("Enter into the focused scan field passes through to the field's own submit")
                .isFalse();
    }

    @Test
    void manualSubmitOfUnknownUpc_inlineErrorSurvivesTheTrailingEnterKeystroke() {
        // Reproduces the reported regression: hand-typing an unknown UPC and pressing Enter showed
        // no inline error. Swing fires the field's Enter action on KEY_PRESSED — that sets
        // "Item Not Found — …" — and then delivers KEY_TYPED('\n') for the same keypress. The
        // dispatcher must NOT clear the inline error on that trailing terminator, or the just-shown
        // message is wiped before the cashier can read it.
        pos.getTransactionService().startTransaction();
        pos.addController(new CustomerViewController(mock(CustomerView.class)));
        installCaptureController(() -> scanField, new ManualScheduler());

        // The field's Enter action, as Swing fires it on KEY_PRESSED.
        Map<String, Object> props = new HashMap<>();
        props.put("raw", "012345678905");
        pos.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));
        verify(view).setInlineError(
                ScannerViewController.MSG_ITEM_NOT_FOUND_PREFIX + "012345678905");

        // The trailing terminator keystroke for the same Enter must leave the error alone.
        clearInvocations(view);
        typed('\n');
        verify(view, times(0)).clearInlineError();
    }

    @Test
    void fastTypingIntoFocusedScanField_stillPassesThrough_notCapturedAsBurst() {
        // Even at scanner speed, input into the *focused scan field* is not the "wrong component"
        // problem the burst detector exists to solve — the field handles it and submits on Enter.
        // So a fast entry here must not be swallowed and re-dispatched as an ITEM_SCANNED by the
        // dispatcher; it passes through, leaving submission to the field's Enter action.
        installCaptureController(() -> scanField, new ManualScheduler());
        ensureInProgress();

        for (char c : "049000053418".toCharArray()) {
            assertThat(typed(c)).isFalse();
            tickAdvance(5);     // scanner speed
        }
        assertThat(typed('\n')).isFalse();

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
    }

    // ---- Global scanning: optimistic capture + replay ---------------------

    @Test
    void fastBurst_withFocusInSearchField_addsToBasket_leavesFieldAndFilterUntouched() {
        QuickAddPanel quickAdd = new QuickAddPanel(
                List.of(new Item("111", "Alpha", new BigDecimal("1.00")),
                        new Item("222", "Beta", new BigDecimal("2.00"))),
                item -> { });
        JTextField search = quickAdd.getSearchFieldForTest();
        installCaptureController(() -> search, new ManualScheduler());
        ensureInProgress();
        int before = quickAdd.filteredSortedForTest().size();

        burst("049000053418", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        assertThat(search.getText()).isEmpty();
        assertThat(quickAdd.filteredSortedForTest()).hasSize(before);
    }

    @Test
    void digitsTypedAtHumanSpeed_appearInSearchField_andFilterTheGrid() {
        QuickAddPanel quickAdd = new QuickAddPanel(
                List.of(new Item("207", "Cola 207", new BigDecimal("1.00")),
                        new Item("999", "Water", new BigDecimal("2.00"))),
                item -> { });
        JTextField search = quickAdd.getSearchFieldForTest();
        ManualScheduler sched = new ManualScheduler();
        installCaptureController(() -> search, sched);
        ensureInProgress();

        // Human speed: each inter-digit gap exceeds the 50ms burst threshold.
        typed('2'); tickAdvance(120);
        typed('0'); tickAdvance(120);
        typed('7'); tickAdvance(120);
        sched.fire();  // stale timeout flushes the trailing held digit

        assertThat(search.getText()).isEqualTo("207");
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
        assertThat(quickAdd.filteredSortedForTest()).hasSize(1);
    }

    @Test
    void burstAbandonedMidway_replaysHeldDigitsIntoTheFocusedField() {
        JTextField field = new JTextField();
        installCaptureController(() -> field, new ManualScheduler());
        ensureInProgress();

        typed('0'); tickAdvance(5);
        typed('4'); tickAdvance(120);   // the next gap exceeds the burst threshold
        typed('9');                     // slow digit → "04" replayed, "9" newly held

        assertThat(field.getText()).isEqualTo("04");
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isZero();
    }

    @Test
    void burst_withFocusOnVoidBasketButton_doesNotVoidTheBasket() {
        JButton voidBasket = new JButton("Void Basket");
        installCaptureController(() -> voidBasket, new ManualScheduler());
        ensureInProgress();

        burst("049000053418", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.VOID_BASKET_PRESSED)).isZero();
        assertThat(notifications.countOf(PosEventType.BASKET_VOIDED)).isZero();
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
    }

    @Test
    void fastBurst_whileQwertyOpen_addsToBasket_leavesSearchUntouched_andDismissesKeyboard() {
        // A scan while the on-screen QWERTY is up: the item must reach the basket, the search text
        // and grid filter must be untouched, and the keyboard must dismiss (a successful scan means
        // the cashier found the item another way). The dismissal is driven by CustomerViewController
        // -> CustomerView.dismissSearchKeyboard(); we bridge the mock view to the real panel so the
        // whole path is exercised without a live JFrame.
        QuickAddPanel quickAdd = new QuickAddPanel(
                List.of(new Item("111", "Cola", new BigDecimal("1.00")),
                        new Item("222", "Water", new BigDecimal("2.00"))),
                item -> { });
        quickAdd.setCapacityForTest(2, 4);
        JTextField search = quickAdd.getSearchFieldForTest();

        CustomerView customerView = mock(CustomerView.class);
        doAnswer(inv -> { quickAdd.hideKeyboard(); return null; })
                .when(customerView).dismissSearchKeyboard();
        pos.addController(new CustomerViewController(customerView));   // opens an IN_PROGRESS tx
        installCaptureController(() -> search, new ManualScheduler());

        // Cashier is searching: keyboard open, a query typed.
        quickAdd.fireSearchFocusGainedForTest();
        search.setText("co");
        assertThat(quickAdd.isKeyboardVisibleForTest()).isTrue();
        int filteredBefore = quickAdd.filteredSortedForTest().size();

        burst("049000053418", 5);
        typed('\n');

        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED)).isEqualTo(1);
        assertThat(search.getText()).as("scan must not touch the search text").isEqualTo("co");
        assertThat(quickAdd.filteredSortedForTest())
                .as("scan must not touch the grid filter").hasSize(filteredBefore);
        assertThat(quickAdd.isKeyboardVisibleForTest())
                .as("a successful scan dismisses the keyboard").isFalse();
    }

    @Test
    void onScreenKeypadTaps_areNeverMistakenForScannerInput() {
        // The keypad mutates the target field's Document directly; it fires no KeyEvents, so the
        // application-wide KeyEventDispatcher that captures scanner bursts never sees them. Tapping
        // a full UPC's worth of digit keys must therefore dispatch no ITEM_SCANNED — the digits
        // simply land in the field, exactly as intended.
        JTextField field = new JTextField();
        OnScreenKeypad keypad = new OnScreenKeypad(field, false);
        installCaptureController(() -> field, new ManualScheduler());
        ensureInProgress();

        for (char c : "049000053418".toCharArray()) {
            keypad.getKeyForTest(String.valueOf(c)).doClick();
        }

        assertThat(field.getText()).isEqualTo("049000053418");
        assertThat(notifications.countOf(PosEventType.ITEM_SCANNED))
                .as("keypad taps must not be captured as a scanner burst").isZero();
    }

    // ---- Helpers -----------------------------------------------------------

    private void installCaptureController(java.util.function.Supplier<java.awt.Component> focus,
                                          ManualScheduler scheduler) {
        pos.removeController(controller);
        installer = new CapturingInstaller();
        controller = new ScannerViewController(
                view, buffer, false, installer, clockTs::get, focus, scheduler);
        pos.addController(controller);
    }

    /** A replay scheduler whose pending flush the test fires by hand (no real timer). */
    static final class ManualScheduler implements ScannerViewController.ReplayScheduler {
        private Runnable pending;

        @Override
        public Runnable after(long delayMs, Runnable task) {
            pending = task;
            return () -> {
                if (pending == task) pending = null;
            };
        }

        void fire() {
            Runnable t = pending;
            pending = null;
            if (t != null) t.run();
        }
    }

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
