package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Listens for {@link PosEventType#ERROR} events and shows them to the cashier as a modal
 * {@link JOptionPane}.
 *
 * <p><strong>No {@code ErrorPopupView} class.</strong> Deliberate exception to the
 * dumb-view/controller pattern the rest of {@code display} follows. {@link JOptionPane} is
 * already a self-contained modal view — wrapping it in a hollow {@code ErrorPopupView} class
 * with no layout, no fields, and one pass-through method would be ceremony. The controller
 * treats {@code JOptionPane} as the view; there is no rendering logic here to separate.</p>
 *
 * <p><strong>Three requirements the tests pin down:</strong></p>
 * <ol>
 *   <li><em>EDT marshalling.</em> Phase 2's journal client and Phase 3's discount-engine call
 *       will dispatch {@code ERROR} events from background threads. Every Swing interaction is
 *       marshalled through {@link SwingUtilities#invokeLater(Runnable)} (via the injectable
 *       {@link EdtInvoker}, which tests replace with a synchronous runner).</li>
 *   <li><em>No stacked dialogs.</em> A cashier can fire many errors in seconds by waving a
 *       scanner over unknown barcodes. A boolean flag guards the presenter — while a dialog is
 *       showing, further errors are dropped rather than opening a second dialog behind the
 *       first.</li>
 *   <li><em>Non-destructive.</em> Nothing in this controller touches transaction state. The
 *       cashier dismisses the popup and the current transaction is exactly as it was.</li>
 * </ol>
 *
 * <p>Message mapping: {@code code} → cashier-readable string. Unknown codes fall through to a
 * generic message. A missing {@code message} property never renders as {@code null} — a
 * fallback is used.</p>
 */
public class ErrorPopupViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES =
            Collections.unmodifiableSet(EnumSet.of(PosEventType.ERROR));

    private static final String GENERIC_FALLBACK = "An unexpected error occurred.";
    private static final String TITLE = "Error";

    /** Marshals a task onto the Swing event dispatch thread. Default: {@link SwingUtilities#invokeLater}. */
    @FunctionalInterface
    public interface EdtInvoker {
        void invoke(Runnable r);
    }

    /**
     * Renders a message to the user. The default calls {@link JOptionPane}; tests inject a
     * capturing double to avoid a real modal dialog.
     */
    @FunctionalInterface
    public interface ErrorPresenter {
        /**
         * Called on the EDT; must block until the user dismisses the dialog. When it returns,
         * the controller re-arms itself for the next error.
         */
        void show(Component parent, String title, String message);
    }

    private final Component ownerForDialogs;
    private final EdtInvoker edtInvoker;
    private final ErrorPresenter presenter;
    private final Runnable onDismiss;

    private volatile boolean dialogShowing;

    private PosComponent parent;

    /**
     * Production constructor: EDT marshalling via {@link SwingUtilities#invokeLater},
     * {@link JOptionPane} as the presenter, focus returned to {@code focusOnDismiss} when the
     * cashier dismisses.
     *
     * @param ownerForDialogs the parent frame; may be {@code null}
     * @param focusOnDismiss  component whose {@link Component#requestFocusInWindow()} is called
     *                        after dismiss so the cashier can scan again; may be {@code null}
     */
    public ErrorPopupViewController(JFrame ownerForDialogs, Component focusOnDismiss) {
        this(ownerForDialogs,
                SwingUtilities::invokeLater,
                (owner, title, message) ->
                        JOptionPane.showMessageDialog(owner, message, title, JOptionPane.ERROR_MESSAGE),
                focusOnDismiss == null ? null : focusOnDismiss::requestFocusInWindow);
    }

    /**
     * Test-facing constructor: inject a synchronous invoker and a capturing presenter so tests
     * do not open real modal dialogs.
     */
    ErrorPopupViewController(Component ownerForDialogs, EdtInvoker edtInvoker,
                             ErrorPresenter presenter, Runnable onDismiss) {
        if (edtInvoker == null) throw new IllegalArgumentException("edtInvoker must not be null");
        if (presenter == null) throw new IllegalArgumentException("presenter must not be null");
        this.ownerForDialogs = ownerForDialogs;
        this.edtInvoker = edtInvoker;
        this.presenter = presenter;
        this.onDismiss = onDismiss;
    }

    // ---- IController ------------------------------------------------------

    @Override
    public void onStart(PosComponent parent) {
        this.parent = parent;
        parent.register(this);
    }

    @Override
    public void onEnd() {
        if (parent != null) {
            parent.unregister(this);
            parent = null;
        }
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        if (event.getType() != PosEventType.ERROR) return;

        // Coalesce burst errors: if a dialog is already up, drop this one on the floor. The
        // cashier can retrigger the underlying action after dismissing; a stack of dialogs is
        // never useful.
        if (dialogShowing) return;
        dialogShowing = true;

        String code = event.getProperty("code", String.class);
        String message = event.getProperty("message", String.class);
        String userMessage = cashierMessage(code, message, event);

        edtInvoker.invoke(() -> {
            try {
                presenter.show(ownerForDialogs, TITLE, userMessage);
            } finally {
                dialogShowing = false;
                if (onDismiss != null) {
                    try {
                        onDismiss.run();
                    } catch (RuntimeException ignored) {
                        // Refocus failure is not a reason to leave the flag stuck.
                    }
                }
            }
        });
    }

    /**
     * Maps an error event to a cashier-readable message. Falls back to
     * {@link #GENERIC_FALLBACK} on unknown codes and on a {@code null} message; never returns
     * the string {@code "null"}.
     */
    static String cashierMessage(String code, String message, PosEvent event) {
        if (code == null) code = "";
        switch (code) {
            case "UPC_NOT_FOUND":
                String upc = event.getProperty("upc", String.class);
                return upc != null
                        ? "Item not found: " + upc
                        : "Item not found.";
            case "INVALID_BARCODE":
                String raw = event.getProperty("raw", String.class);
                return (raw == null || raw.isEmpty())
                        ? "Not a valid barcode."
                        : "Not a valid barcode: " + raw;
            case "SCAN_LOCKED":
                return "Scanning is locked — press Total to tender.";
            case "INVALID_CASH_AMOUNT":
                return "Invalid cash amount. Enter a valid, non-negative number.";
            case "UNDERPAYMENT":
                return "Cash received is less than the amount due.";
            case "TOTALED_INVARIANT":
            case "NO_TRANSACTION":
                return "That action isn't allowed right now.";
            case "INVALID_ARGUMENT":
                // Fall back to the technical message when we have one — the aggregate's
                // "quantity must be >= 1" is at least specific.
                return (message == null || message.isBlank())
                        ? "Invalid input for this action."
                        : "Invalid input: " + message;
            default:
                return (message == null || message.isBlank()) ? GENERIC_FALLBACK : message;
        }
    }

    // Package-private test helper.
    boolean isDialogShowing() {
        return dialogShowing;
    }
}
