package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.service.TransactionService;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Opens the {@link ReceiptView} modal in response to
 * {@link PosEventType#TRANSACTION_COMPLETED}, renders the receipt string produced by
 * {@link TransactionService#generateReceipt(Transaction, String, Integer)}, and dispatches
 * {@link PosEventType#RECEIPT_DISMISSED} when the cashier presses Dismiss so the
 * {@link CustomerViewController} can reset to idle and open a fresh transaction.
 *
 * <p>Formatting lives in the service: this controller does not re-derive line totals, does not
 * re-apply tax, and does not filter voided lines — the string it receives from
 * {@code generateReceipt} is passed to {@link ReceiptView#setReceiptText(String)} verbatim.
 * That way the receipt layout is authoritative in one place, and the view/controller pair are
 * cheap enough to replace.</p>
 */
public class ReceiptViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.TRANSACTION_COMPLETED,
            PosEventType.RECEIPT_DISMISS_PRESSED));

    private final ReceiptView view;
    private final String storeName;
    private final Integer laneNumber;
    private PosComponent parent;

    /**
     * @param view       the modal receipt dialog this controller drives; must not be {@code null}
     * @param storeName  header label for the receipt; may be {@code null}
     * @param laneNumber lane number for the receipt header; may be {@code null}
     */
    public ReceiptViewController(ReceiptView view, String storeName, Integer laneNumber) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        this.view = view;
        this.storeName = storeName;
        this.laneNumber = laneNumber;
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
            case TRANSACTION_COMPLETED -> openReceipt(event);
            case RECEIPT_DISMISS_PRESSED -> dismiss();
            default -> { /* not subscribed */ }
        }
    }

    private void openReceipt(PosEvent event) {
        Transaction paid = event.getProperty("transaction", Transaction.class);
        if (paid == null) return;
        String text = parent.getTransactionService().generateReceipt(paid, storeName, laneNumber);
        view.setReceiptText(text);
        view.openDialog();
    }

    private void dismiss() {
        view.closeDialog();
        parent.dispatchPosEvent(new PosEvent(PosEventType.RECEIPT_DISMISSED));
    }
}
