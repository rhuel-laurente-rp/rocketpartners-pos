package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Owns the void-basket confirmation flow. Opens the modal
 * {@link VoidBasketConfirmView} when {@link PosEventType#VOID_BASKET_PRESSED} arrives, and
 * closes it on either outcome ({@link PosEventType#VOID_BASKET_CONFIRM_PRESSED} or
 * {@link PosEventType#VOID_BASKET_DECLINED}).
 *
 * <p>Voiding itself and the "start a fresh transaction" reset both live in
 * {@link CustomerViewController} — this controller is only responsible for the dialog. That
 * split keeps the confirmation dialog independent of the aggregate state machine: the dialog
 * dispatches events, and whichever controller cares about a given event reacts.</p>
 *
 * <p><strong>Scanner suspension.</strong> {@link ScannerViewController} already listens for
 * {@link PosEventType#VOID_BASKET_PRESSED} and treats it as a suspend / focus-restore signal,
 * matching the treatment of every other modal-driving event in the app.</p>
 */
public class VoidBasketConfirmViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.VOID_BASKET_PRESSED,
            PosEventType.VOID_BASKET_CONFIRM_PRESSED,
            PosEventType.VOID_BASKET_DECLINED));

    private final VoidBasketConfirmView view;
    private PosComponent parent;

    /**
     * @param view the modal void-basket confirmation dialog this controller drives; must not be
     *             {@code null}
     */
    public VoidBasketConfirmViewController(VoidBasketConfirmView view) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        this.view = view;
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
        view.closeDialog();
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        switch (event.getType()) {
            case VOID_BASKET_PRESSED -> openDialog();
            // Both outcomes just close the dialog — the CustomerViewController owns the state
            // transition on confirm; the JournalListener captures the decline.
            case VOID_BASKET_CONFIRM_PRESSED, VOID_BASKET_DECLINED -> view.closeDialog();
            default -> { /* not subscribed */ }
        }
    }

    private void openDialog() {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) return;
        int itemCount = 0;
        for (LineItem li : tx.getLineItems()) {
            if (!li.isVoided()) itemCount += li.getQuantity();
        }
        if (itemCount == 0) {
            // Nothing to discard. The Void basket button is disabled in this state so a real
            // press shouldn't land here — but a stale click from an event-source race would.
            // Silently no-op rather than opening a dialog with an empty summary.
            return;
        }
        BigDecimal grandTotal = tx.grandTotal();
        view.openFor(itemCount, grandTotal);
    }
}
