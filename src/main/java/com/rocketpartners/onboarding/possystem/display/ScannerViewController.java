package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.component.BarcodeInputBuffer;
import com.rocketpartners.onboarding.possystem.component.Barcodes;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the barcode-input flow.
 *
 * <p>Two capture paths, both landing in the same place:</p>
 * <ul>
 *   <li><strong>Scanner bursts.</strong> A scanner is a fast keyboard that types into
 *       whatever component currently has focus — including the wrong one (a Quick Add button
 *       the cashier last clicked). The controller solves this at the source: on {@code
 *       onStart} it registers a {@link KeyEventDispatcher} with
 *       {@link KeyboardFocusManager}, which sees every KEY_TYPED event application-wide
 *       before it reaches any focused component. Every keystroke is fed to a
 *       {@link BarcodeInputBuffer}; when the buffer emits a completed barcode the controller
 *       validates it as a UPC and dispatches {@link PosEventType#ITEM_SCANNED}. Chars that
 *       are part of an active scanner burst are consumed (do not propagate to the focused
 *       component); everything else is left alone so the scan field's own typing still
 *       works.</li>
 *   <li><strong>Manual typing.</strong> The cashier types digits into the scan field and
 *       hits Enter. The field's action listener fires
 *       {@link PosEventType#SCAN_SUBMIT_PRESSED} carrying the field's raw text; the
 *       controller validates and dispatches {@link PosEventType#ITEM_SCANNED} on success.</li>
 * </ul>
 *
 * <p><strong>Focus discipline.</strong> After every user interaction — quick add, void,
 * cash-dialog dismiss, receipt dismiss, error popup dismiss — the controller calls
 * {@link ScannerView#requestScanFieldFocus()}. That's a second layer on top of the
 * application-wide dispatcher; the dispatcher is the real fix, but returning focus makes the
 * cursor visible in the right place.</p>
 *
 * <p><strong>When scanning is off.</strong> A scan attempted while the transaction is
 * {@link TransactionState#TOTALED} is rejected with an ERROR event ({@code SCAN_LOCKED}) so
 * the cashier gets feedback instead of a silent dead field. Scan capture is also suspended
 * while a modal dialog is open (cash tender, receipt) so keystrokes can't leak into it.</p>
 *
 * <p><strong>Debug hotkey.</strong> When the {@code debug} flag is on, F12 replays a canned
 * UPC through the same path as a real scan, so demos work without hardware on the desk.</p>
 */
public class ScannerViewController implements IController, IPosEventListener {

    /** Canned UPC replayed by the debug hotkey — a Coca-Cola can from the sample pricebook. */
    public static final String DEMO_UPC = "049000053418";

    /** {@link KeyEvent} key code the demo hotkey listens for. */
    public static final int DEMO_HOTKEY = KeyEvent.VK_F12;

    /**
     * Marshals the addition of a {@link KeyEventDispatcher} to the platform's
     * {@link KeyboardFocusManager}. Extracted so tests can bypass the real focus manager.
     */
    @FunctionalInterface
    public interface KeyDispatchInstaller {
        /** @return a {@link Runnable} that, when run, removes the previously installed dispatcher */
        Runnable install(KeyEventDispatcher dispatcher);
    }

    /**
     * Supplies a monotonic clock reading for buffer timestamps. Default {@link
     * System#currentTimeMillis}; tests supply a controllable clock.
     */
    @FunctionalInterface
    public interface Clock {
        long millis();
    }

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.SCAN_SUBMIT_PRESSED,
            // The scan bar suspends during any modal-driving flow so keystrokes can't leak.
            PosEventType.TENDER_CASH_PRESSED,
            PosEventType.TENDER_DEBIT_PRESSED,
            PosEventType.TENDER_CREDIT_PRESSED,
            PosEventType.CHANGE_QTY_PRESSED,
            PosEventType.VOID_BASKET_PRESSED,
            PosEventType.CASH_CANCEL_PRESSED,
            PosEventType.CASH_TENDERED,
            PosEventType.CARD_TENDERED,
            PosEventType.CHANGE_QTY_CONFIRM_PRESSED,
            PosEventType.CHANGE_QTY_CANCEL_PRESSED,
            PosEventType.VOID_BASKET_CONFIRM_PRESSED,
            PosEventType.VOID_BASKET_DECLINED,
            PosEventType.TRANSACTION_COMPLETED,
            PosEventType.RECEIPT_DISMISSED,
            // Focus-restore triggers.
            PosEventType.ITEM_ADDED,
            PosEventType.LINE_VOIDED,
            PosEventType.QUANTITY_CHANGED,
            PosEventType.BASKET_VOIDED,
            PosEventType.TRANSACTION_TOTALED,
            PosEventType.ERROR));

    private final ScannerView view;
    private final BarcodeInputBuffer buffer;
    private final KeyDispatchInstaller keyInstaller;
    private final Clock clock;
    private final boolean debug;

    private PosComponent parent;
    private Runnable uninstallDispatcher;

    /**
     * {@code true} while a modal dialog is open. Buffer inputs are dropped and the
     * KeyEventDispatcher returns false (does not consume) so the modal's own inputs work.
     */
    private boolean suspended;

    /**
     * Production constructor. Installs on the platform {@link KeyboardFocusManager}, uses
     * {@link System#currentTimeMillis} for timestamps.
     *
     * @param view     the scan bar view; must not be {@code null}
     * @param buffer   the burst-detection buffer; must not be {@code null}
     * @param debug    when {@code true}, the demo hotkey is armed
     */
    public ScannerViewController(ScannerView view, BarcodeInputBuffer buffer, boolean debug) {
        this(view, buffer, debug,
                d -> {
                    KeyboardFocusManager mgr = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                    mgr.addKeyEventDispatcher(d);
                    return () -> mgr.removeKeyEventDispatcher(d);
                },
                System::currentTimeMillis);
    }

    /**
     * Test-facing constructor: inject the focus-manager installer and clock so tests can
     * feed synthetic keystrokes at controlled timestamps without touching real Swing state.
     */
    ScannerViewController(ScannerView view, BarcodeInputBuffer buffer, boolean debug,
                          KeyDispatchInstaller keyInstaller, Clock clock) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        if (keyInstaller == null) throw new IllegalArgumentException("keyInstaller must not be null");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.view = view;
        this.buffer = buffer;
        this.debug = debug;
        this.keyInstaller = keyInstaller;
        this.clock = clock;
    }

    // ---- IController ------------------------------------------------------

    @Override
    public void onStart(PosComponent parent) {
        this.parent = parent;
        parent.register(this);
        this.uninstallDispatcher = keyInstaller.install(this::onKeyEvent);
        view.setStatusHint(ScannerView.STATUS_READY);
        view.requestScanFieldFocus();
    }

    @Override
    public void onEnd() {
        if (uninstallDispatcher != null) {
            try {
                uninstallDispatcher.run();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup; a swing shutdown may already have torn things down.
            }
            uninstallDispatcher = null;
        }
        if (parent != null) {
            parent.unregister(this);
            parent = null;
        }
    }

    // ---- KeyEventDispatcher -----------------------------------------------

    /**
     * Application-wide keystroke handler. Feeds every KEY_TYPED into the buffer so scanner
     * bursts are detected regardless of what has focus, but ONLY consumes the event when the
     * buffer actually emits a completed burst — i.e. on the terminator that closes a
     * scanner-speed sequence. Every non-terminator keystroke, and every terminator that did
     * NOT close a burst, passes through so text components (scan field, cash-received field,
     * etc.) receive normal typing.
     *
     * <p>The rationale is that non-scanner keystrokes ('8', '.', 'a') are inert on non-text
     * components — typing digits at a Quick Add button does nothing. The only keystroke that
     * would leak destructively is the terminator (Enter would activate the button), and that
     * is exactly what the "consume on emit" rule prevents.</p>
     *
     * <p>Package-private so tests can invoke it directly with synthetic events.</p>
     */
    boolean onKeyEvent(KeyEvent e) {
        // Debug demo hotkey: KEY_PRESSED so we don't require a printable char.
        if (debug && e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == DEMO_HOTKEY) {
            triggerDemoScan();
            return true;
        }
        if (e.getID() != KeyEvent.KEY_TYPED) return false;
        // Suspend while a modal dialog is open: buffer stays reset, events pass through so
        // the modal's own text fields (e.g. cash-received) receive normal typing.
        if (suspended) {
            buffer.reset();
            return false;
        }
        char c = e.getKeyChar();
        Optional<String> completed = buffer.accept(c, clock.millis());
        if (completed.isPresent()) {
            handleCompleted(completed.get());
            // Consume the terminator so a scanner Enter doesn't ALSO fire the scan field's
            // Enter action (which would double-dispatch as a manual submit).
            return true;
        }
        return false;
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        switch (event.getType()) {
            case SCAN_SUBMIT_PRESSED -> handleManualSubmit(event);

            // Suspend while modals are up. Card modal opens on TENDER_*_PRESSED; the modal
            // closes when the payment completes (CASH_TENDERED / CARD_TENDERED). The
            // change-qty dialog opens on CHANGE_QTY_PRESSED and closes on the confirm/cancel
            // events.
            case TENDER_CASH_PRESSED, TENDER_DEBIT_PRESSED, TENDER_CREDIT_PRESSED,
                 CHANGE_QTY_PRESSED, VOID_BASKET_PRESSED,
                 TRANSACTION_COMPLETED -> suspendCapture();
            case CASH_CANCEL_PRESSED, CASH_TENDERED, CARD_TENDERED,
                 CHANGE_QTY_CONFIRM_PRESSED, CHANGE_QTY_CANCEL_PRESSED,
                 VOID_BASKET_CONFIRM_PRESSED, VOID_BASKET_DECLINED -> resumeCapture();
            // Receipt dismissal semantically starts a fresh sale: force STATUS_READY
            // unconditionally, independent of the transaction-state check resumeCapture
            // uses, so a lingering TOTALED state (or a late TRANSACTION_COMPLETED handler
            // firing after this) cannot leave the scan bar showing "Locked — press Total".
            case RECEIPT_DISMISSED -> resumeReady();

            case TRANSACTION_TOTALED -> view.setStatusHint(ScannerView.STATUS_LOCKED);

            // Focus-restore hooks. After any interaction that isn't the modal-driving ones
            // above, put the cursor back on the scan field so the next scan lands there.
            case ITEM_ADDED, LINE_VOIDED, QUANTITY_CHANGED, BASKET_VOIDED, ERROR -> restoreScanFocus();

            default -> { /* not subscribed */ }
        }
    }

    private void handleManualSubmit(PosEvent event) {
        String raw = event.getProperty("raw", String.class);
        if (raw == null) raw = "";
        submitBarcode(raw.trim(), "manualScan");
        view.clearScanField();
        view.requestScanFieldFocus();
    }

    private void handleCompleted(String raw) {
        submitBarcode(raw, "scan");
        view.clearScanField();
        view.requestScanFieldFocus();
        view.setStatusHint(ScannerView.STATUS_READY);
    }

    private void submitBarcode(String raw, String operation) {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx != null && tx.getState() == TransactionState.TOTALED) {
            dispatchError("SCAN_LOCKED",
                    "cannot scan while transaction is totaled — press Total to tender",
                    operation, raw);
            return;
        }
        if (!Barcodes.isValidUpc(raw)) {
            dispatchError("INVALID_BARCODE",
                    "not a valid UPC: '" + raw + "'",
                    operation, raw);
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("upc", raw);
        props.put("source", operation);
        parent.dispatchPosEvent(new PosEvent(PosEventType.ITEM_SCANNED, props));
    }

    private void suspendCapture() {
        suspended = true;
        buffer.reset();
        view.setStatusHint(ScannerView.STATUS_LOCKED);
    }

    private void resumeCapture() {
        suspended = false;
        buffer.reset();
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        boolean totaled = tx != null && tx.getState() == TransactionState.TOTALED;
        view.setStatusHint(totaled ? ScannerView.STATUS_LOCKED : ScannerView.STATUS_READY);
        restoreScanFocus();
    }

    /**
     * As {@link #resumeCapture()} but forces {@link ScannerView#STATUS_READY} regardless of
     * transaction state. Called on {@link PosEventType#RECEIPT_DISMISSED} — the receipt was
     * shown for the just-paid transaction, and the very next thing the {@link
     * CustomerViewController} does is open a fresh IN_PROGRESS transaction, so the correct
     * end-state hint is always "Ready to scan". Avoids a race where the still-TOTALED old
     * transaction (or a re-delivery of the outer TRANSACTION_COMPLETED event that opened the
     * receipt in the first place) leaves the hint stuck on STATUS_LOCKED.
     */
    private void resumeReady() {
        suspended = false;
        buffer.reset();
        view.setStatusHint(ScannerView.STATUS_READY);
        restoreScanFocus();
    }

    private void restoreScanFocus() {
        if (!suspended) {
            view.requestScanFieldFocus();
        }
    }

    private void dispatchError(String code, String message, String operation, String raw) {
        Map<String, Object> props = new HashMap<>();
        props.put("code", code);
        props.put("message", message);
        props.put("operation", operation);
        props.put("raw", raw);
        parent.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));
    }

    private void triggerDemoScan() {
        submitBarcode(DEMO_UPC, "demoScan");
        view.clearScanField();
        view.requestScanFieldFocus();
    }

    // ---- Package-private test hooks ---------------------------------------

    boolean isSuspended() {
        return suspended;
    }
}
