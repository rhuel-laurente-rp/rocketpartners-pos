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

import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
 *       either presses Enter or taps the Scan button. Both routes dispatch
 *       {@link PosEventType#SCAN_SUBMIT_PRESSED} carrying the field's raw text; the controller
 *       validates and dispatches {@link PosEventType#ITEM_SCANNED} on success.</li>
 * </ul>
 *
 * <p><strong>Inline error UX.</strong> Four codes route to the scan bar's inline hint rather
 * than to a modal dialog: {@code UPC_NOT_FOUND}, {@code UPC_MISREAD}, {@code INVALID_BARCODE},
 * and {@code SCAN_LOCKED}. Scan failures are frequent and instantly recoverable, and a modal
 * costs a dismissal tap with a queue waiting. Every other error code
 * ({@code TOTALED_INVARIANT}, {@code NO_TRANSACTION}, cash-flow errors, discount-engine
 * failures once they exist) still opens {@link ErrorPopupViewController}'s modal — those
 * require acknowledgement; scan errors don't.</p>
 *
 * <p><strong>Focus discipline.</strong> After every user interaction — quick add, void,
 * cash-dialog dismiss, receipt dismiss, error popup dismiss — the controller calls
 * {@link ScannerView#requestScanFieldFocus()}. That's a second layer on top of the
 * application-wide dispatcher; the dispatcher is the real fix, but returning focus makes the
 * cursor visible in the right place.</p>
 *
 * <p><strong>When scanning is off.</strong> A scan attempted while the transaction is
 * {@link TransactionState#TOTALED} is rejected with the inline {@code SCAN_LOCKED} hint. Scan
 * capture is also suspended while a modal dialog is open (cash tender, receipt) so keystrokes
 * can't leak into it.</p>
 *
 * <p><strong>Debug hotkey.</strong> When the {@code debug} flag is on, F12 replays a canned
 * UPC through the same path as a real scan, so demos work without hardware on the desk.</p>
 */
public class ScannerViewController implements IController, IPosEventListener {

    /** Canned UPC replayed by the debug hotkey — a Coca-Cola can from the sample pricebook. */
    public static final String DEMO_UPC = "049000053418";

    /** {@link KeyEvent} key code the demo hotkey listens for. */
    public static final int DEMO_HOTKEY = KeyEvent.VK_F12;

    // Inline-error copy — Title Case per convention. Public because tests assert on them.
    static final String MSG_ITEM_NOT_FOUND_PREFIX = "Item Not Found — ";
    static final String MSG_BARCODE_NOT_RECOGNISED = "Barcode Not Recognised";
    static final String MSG_BARCODE_MISREAD = "Barcode May Have Been Misread";
    static final String MSG_SCAN_LOCKED = "Locked — Complete Payment";

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

    /**
     * Schedules the deferred "stale flush" that replays held digits when a human types some
     * digits and then stops. Production uses a one-shot {@link javax.swing.Timer} on the EDT;
     * tests inject a controllable scheduler so the flush fires deterministically.
     */
    @FunctionalInterface
    public interface ReplayScheduler {
        /**
         * @return a {@link Runnable} that cancels the pending task if run before it fires
         */
        Runnable after(long delayMs, Runnable task);
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
    private final Supplier<Component> focusOwnerSupplier;
    private final ReplayScheduler replayScheduler;

    /**
     * Digits captured optimistically for the burst currently in flight — a mirror of the buffer's
     * pending payload. When the burst turns out to be human typing (an inter-digit gap exceeds the
     * burst threshold, the stale timeout fires, or a non-digit/terminator interrupts), these are
     * replayed into the focused component instead of being swallowed. When a terminator closes a
     * fast-enough burst they are discarded — the scan was dispatched, the field never saw them.
     */
    private final StringBuilder heldDigits = new StringBuilder();

    /** Canceller for the pending stale-flush, or {@code null} when none is scheduled. */
    private Runnable pendingFlushCancel;

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
     * Test-facing constructor with default replay wiring: replays into the real focus owner and
     * flushes stale holds via a one-shot Swing {@link Timer}.
     */
    ScannerViewController(ScannerView view, BarcodeInputBuffer buffer, boolean debug,
                          KeyDispatchInstaller keyInstaller, Clock clock) {
        this(view, buffer, debug, keyInstaller, clock,
                () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(),
                defaultReplayScheduler());
    }

    /**
     * Full test-facing constructor: also inject the focus-owner supplier (so a test can point
     * replays at a real field) and the replay scheduler (so the stale flush fires on demand).
     */
    ScannerViewController(ScannerView view, BarcodeInputBuffer buffer, boolean debug,
                          KeyDispatchInstaller keyInstaller, Clock clock,
                          Supplier<Component> focusOwnerSupplier, ReplayScheduler replayScheduler) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        if (keyInstaller == null) throw new IllegalArgumentException("keyInstaller must not be null");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        if (focusOwnerSupplier == null) throw new IllegalArgumentException("focusOwnerSupplier must not be null");
        if (replayScheduler == null) throw new IllegalArgumentException("replayScheduler must not be null");
        this.view = view;
        this.buffer = buffer;
        this.debug = debug;
        this.keyInstaller = keyInstaller;
        this.clock = clock;
        this.focusOwnerSupplier = focusOwnerSupplier;
        this.replayScheduler = replayScheduler;
    }

    private static ReplayScheduler defaultReplayScheduler() {
        return (delayMs, task) -> {
            Timer t = new Timer((int) delayMs, e -> task.run());
            t.setRepeats(false);
            t.start();
            return t::stop;
        };
    }

    // ---- IController ------------------------------------------------------

    @Override
    public void onStart(PosComponent parent) {
        this.parent = parent;
        parent.register(this);
        this.uninstallDispatcher = keyInstaller.install(this::onKeyEvent);
        view.setLocked(false);
        view.requestScanFieldFocus();
    }

    @Override
    public void onEnd() {
        cancelStaleFlush();
        heldDigits.setLength(0);
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
        // Suspend while a modal dialog is open: drop the buffer and any held digits, and pass
        // events through so the modal's own text fields (e.g. cash-received) receive typing.
        if (suspended) {
            cancelStaleFlush();
            heldDigits.setLength(0);
            buffer.reset();
            return false;
        }
        // Any keystroke that reaches an inline-error state clears the error — the cashier is
        // rescanning or retyping, and the error hint is now stale.
        view.clearInlineError();

        // Filter on KEY_TYPED's keyChar rather than keyCode: KEY_TYPED synthesises the character
        // regardless of whether the physical key was top-row or numeric-keypad, so a scanner
        // emitting VK_NUMPAD0..VK_NUMPAD9 still surfaces the '0'..'9' char here.
        char c = e.getKeyChar();
        long now = clock.millis();

        if (buffer.getTerminators().contains(c)) {
            Optional<String> completed = buffer.accept(c, now);
            if (completed.isPresent()) {
                if (debug) {
                    buffer.pollLastBurstStats().ifPresent(stats -> System.err.printf(
                            "[scan-calibration] chars=%d gaps=%d min=%dms max=%dms mean=%.1fms%n",
                            stats.getCharCount(), stats.getGapCount(),
                            stats.getMinGapMs(), stats.getMaxGapMs(), stats.getMeanGapMs()));
                }
                // A fast-enough burst closed: the held digits were the scan, never the field's.
                // Discard them, dispatch, and consume the terminator so a scanner Enter doesn't
                // ALSO fire the scan field's Enter action (a double manual submit).
                cancelStaleFlush();
                heldDigits.setLength(0);
                handleCompleted(completed.get());
                return true;
            }
            // A terminator that did not close a burst (empty buffer, or CR+LF suppression): flush
            // any held digits back to the field, then let the terminator through untouched.
            flushHeldDigits();
            return false;
        }

        if (!isDigit(c)) {
            // A non-digit is human typing (product searches contain letters). Flush any held
            // digits first so ordering is preserved ("20" then "Z"), then let it pass through so
            // it reaches the focused field.
            flushHeldDigits();
            return false;
        }

        // Optimistic digit capture. Feed the buffer, then read its pending length: if it dropped
        // its prior payload and restarted with just this digit, the gap since the last digit
        // exceeded the burst threshold (or the stale timeout elapsed) — the prior digits were
        // human typing, so replay them. Either way this digit is held (consumed), so nothing
        // reaches the focused component while the burst is still ambiguous.
        int before = buffer.pendingLength();
        buffer.accept(c, now);
        int after = buffer.pendingLength();
        if (before > 0 && after <= before) {
            replayToFocusOwner(heldDigits.toString());
            heldDigits.setLength(0);
        }
        heldDigits.append(c);
        scheduleStaleFlush();
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Replays held digits into the focused component and clears the hold. Called when a burst is
     * abandoned by a slow gap, a non-digit, a non-closing terminator, or the stale timeout.
     */
    private void flushHeldDigits() {
        cancelStaleFlush();
        if (heldDigits.length() > 0) {
            replayToFocusOwner(heldDigits.toString());
            heldDigits.setLength(0);
        }
        buffer.reset();
    }

    /**
     * Inserts the given digits into the focused component if it is an editable text field —
     * exactly where a human's typing would have landed. Non-text targets (a button, a tile) can't
     * show digits, so there is nothing to replay into and the digits are simply dropped, which is
     * also what real typing on such a component would do.
     */
    private void replayToFocusOwner(String digits) {
        if (digits.isEmpty()) return;
        Component target = focusOwnerSupplier.get();
        if (target instanceof JTextComponent tc && tc.isEditable()) {
            // Append at the document end rather than replaceSelection: a cashier types at the end
            // of the field, and appending is order-preserving regardless of caret state (an
            // unfocused field's caret sits at 0, which would reverse the digits).
            javax.swing.text.Document doc = tc.getDocument();
            try {
                doc.insertString(doc.getLength(), digits, null);
            } catch (javax.swing.text.BadLocationException ignored) {
                // getLength() is always a valid offset; unreachable in practice.
            }
        }
    }

    private void scheduleStaleFlush() {
        cancelStaleFlush();
        // After staleTimeout of silence with digits still held, the input was human typing that
        // stopped — replay it so the digits appear in the field rather than vanishing.
        pendingFlushCancel = replayScheduler.after(buffer.getStaleTimeoutMs(), () -> {
            pendingFlushCancel = null;
            if (heldDigits.length() > 0) {
                replayToFocusOwner(heldDigits.toString());
                heldDigits.setLength(0);
            }
            buffer.reset();
        });
    }

    private void cancelStaleFlush() {
        if (pendingFlushCancel != null) {
            pendingFlushCancel.run();
            pendingFlushCancel = null;
        }
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
                 TRANSACTION_COMPLETED -> resumeCapture();
            case CASH_CANCEL_PRESSED, CASH_TENDERED, CARD_TENDERED,
                 CHANGE_QTY_CONFIRM_PRESSED, CHANGE_QTY_CANCEL_PRESSED,
                 VOID_BASKET_CONFIRM_PRESSED, VOID_BASKET_DECLINED -> resumeCapture();
            // Receipt dismissal semantically starts a fresh sale: force unlocked
            // unconditionally, independent of the transaction-state check resumeCapture
            // uses, so a lingering TOTALED state (or a late TRANSACTION_COMPLETED handler
            // firing after this) cannot leave the scan bar showing the locked hint.
            case RECEIPT_DISMISSED -> resumeReady();

            case TRANSACTION_TOTALED -> view.setLocked(true);

            case ITEM_ADDED -> handleItemAdded();

            // A non-scan error (cash flow, discount engine, etc.) still opens a modal via
            // ErrorPopupViewController. The scan errors — UPC_NOT_FOUND, UPC_MISREAD,
            // INVALID_BARCODE, SCAN_LOCKED — arrive here too because those codes are sometimes
            // dispatched from other layers (TransactionService for UPC_NOT_FOUND / UPC_MISREAD),
            // and this controller is where the scan bar's inline hint lives.
            case ERROR -> handleErrorEvent(event);

            // Focus-restore hooks. After any interaction that isn't the modal-driving ones
            // above, put the cursor back on the scan field so the next scan lands there.
            case LINE_VOIDED, QUANTITY_CHANGED, BASKET_VOIDED -> restoreScanFocus();

            default -> { /* not subscribed */ }
        }
    }

    private void handleManualSubmit(PosEvent event) {
        // Manual submit also counts as "next input" — clear any inline error first so a manual
        // retry doesn't paint over the previous message.
        view.clearInlineError();
        String raw = event.getProperty("raw", String.class);
        if (raw == null) raw = "";
        boolean accepted = submitBarcode(raw.trim(), "manualScan");
        // Clear only on accepted submits. On rejection the field keeps the wrong text and
        // ScannerView.setInlineError() has selected it, so the cashier sees what they typed
        // and the next keystroke replaces it wholesale. Clearing here would erase that
        // context and leave the Scan button disabled with no obvious reason.
        if (accepted) view.clearScanField();
        view.requestScanFieldFocus();
    }

    private void handleCompleted(String raw) {
        // Scanner burst: the field never held the burst text — the buffer captured it via
        // the KeyEventDispatcher — so clearing here doesn't destroy user input regardless of
        // whether the burst was accepted or rejected. Clearing keeps the field in a known
        // state for the next burst.
        submitBarcode(raw, "scan");
        view.clearScanField();
        view.requestScanFieldFocus();
    }

    private void handleItemAdded() {
        // Accepted scan: brief GO pulse on the field border, same duration as the basket's row
        // flash so the two read as one event.
        view.pulseGo();
        restoreScanFocus();
    }

    /**
     * @return {@code true} if the submission passed local validation and an
     *         {@link PosEventType#ITEM_SCANNED} was dispatched; {@code false} if it was
     *         rejected inline (SCAN_LOCKED or INVALID_BARCODE). Downstream errors reached via
     *         the ITEM_SCANNED path (UPC_NOT_FOUND, UPC_MISREAD) still count as "accepted"
     *         here — the submit itself was well-formed.
     */
    private boolean submitBarcode(String raw, String operation) {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx != null && tx.getState() == TransactionState.TOTALED) {
            // Inline, not modal — a scanner burst against a locked terminal must not stack
            // dialogs. The lock also prevents any state mutation regardless.
            view.setInlineError(MSG_SCAN_LOCKED);
            return false;
        }
        if (!Barcodes.isValidUpc(raw)) {
            view.setInlineError(MSG_BARCODE_NOT_RECOGNISED);
            return false;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("upc", raw);
        props.put("source", operation);
        parent.dispatchPosEvent(new PosEvent(PosEventType.ITEM_SCANNED, props));
        return true;
    }

    private void handleErrorEvent(PosEvent event) {
        String code = event.getProperty("code", String.class);
        if (code == null) {
            restoreScanFocus();
            return;
        }
        switch (code) {
            case "UPC_NOT_FOUND" -> {
                String upc = event.getProperty("upc", String.class);
                view.setInlineError(MSG_ITEM_NOT_FOUND_PREFIX + (upc == null ? "" : upc));
            }
            case "UPC_MISREAD" -> view.setInlineError(MSG_BARCODE_MISREAD);
            case "INVALID_BARCODE" -> view.setInlineError(MSG_BARCODE_NOT_RECOGNISED);
            case "SCAN_LOCKED" -> view.setInlineError(MSG_SCAN_LOCKED);
            default -> { /* not a scan-bar concern — ErrorPopupViewController handles it */ }
        }
        restoreScanFocus();
    }

    private void resumeCapture() {
        suspended = false;
        cancelStaleFlush();
        heldDigits.setLength(0);
        buffer.reset();
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        boolean totaled = tx != null && tx.getState() == TransactionState.TOTALED;
        view.setLocked(totaled);
        restoreScanFocus();
    }

    /**
     * As {@link #resumeCapture()} but forces the unlocked state regardless of transaction
     * state. Called on {@link PosEventType#RECEIPT_DISMISSED} — the receipt was shown for the
     * just-paid transaction, and the very next thing the {@link CustomerViewController} does
     * is open a fresh IN_PROGRESS transaction, so the correct end-state is always idle.
     * Avoids a race where the still-TOTALED old transaction (or a re-delivery of the outer
     * TRANSACTION_COMPLETED event that opened the receipt in the first place) leaves the scan
     * bar stuck in the locked mode.
     */
    private void resumeReady() {
        suspended = false;
        cancelStaleFlush();
        heldDigits.setLength(0);
        buffer.reset();
        view.setLocked(false);
        restoreScanFocus();
    }

    private void restoreScanFocus() {
        if (!suspended) {
            view.requestScanFieldFocus();
        }
    }

    private void triggerDemoScan() {
        view.clearInlineError();
        submitBarcode(DEMO_UPC, "demoScan");
        view.clearScanField();
        view.requestScanFieldFocus();
    }

    // ---- Package-private test hooks ---------------------------------------

    boolean isSuspended() {
        return suspended;
    }
}
