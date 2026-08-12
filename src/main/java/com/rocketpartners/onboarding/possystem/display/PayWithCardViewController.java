package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.Timer;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Owns the card-tender flow — one controller serving both {@link TenderType#DEBIT} and
 * {@link TenderType#CREDIT}, parameterized by the pressed event.
 *
 * <p><strong>Confirm, then process.</strong> {@link PosEventType#TENDER_DEBIT_PRESSED} and
 * {@link PosEventType#TENDER_CREDIT_PRESSED} open a {@link TenderConfirmView} showing the amount
 * about to be charged; the card is not touched until the cashier confirms via
 * {@link PosEventType#CARD_TENDER_CONFIRM_PRESSED}. {@link PosEventType#CARD_TENDER_CANCELLED}
 * abandons the tender and leaves the transaction re-tenderable. On confirm the controller opens
 * the modal {@link PayWithCardView}, shows "processing", schedules a short simulated-approval delay
 * off the EDT, then commits the tender via
 * {@link com.rocketpartners.onboarding.possystem.service.TransactionService#tenderCard(TenderType, BigDecimal)}
 * for the full amount due (no change).</p>
 *
 * <p>The delay is scheduled via {@link javax.swing.Timer} so it runs off {@link Thread#sleep} on
 * the Swing event dispatch thread — freezing the whole UI mid-"approval" is the specific bug the
 * prompt warns against. Tests inject a synchronous {@link ApprovalScheduler} to avoid a live
 * Swing timer.</p>
 */
public class PayWithCardViewController implements IController, IPosEventListener {

    /**
     * Schedules the approval callback. The default runs it via {@link javax.swing.Timer} after
     * {@link #DEFAULT_APPROVAL_DELAY_MS}. Tests supply a synchronous alternative.
     */
    @FunctionalInterface
    public interface ApprovalScheduler {
        void schedule(Runnable callback);
    }

    /** Default simulated card-approval delay. */
    public static final int DEFAULT_APPROVAL_DELAY_MS = 800;

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.TENDER_DEBIT_PRESSED,
            PosEventType.TENDER_CREDIT_PRESSED,
            PosEventType.CARD_TENDER_CONFIRM_PRESSED,
            PosEventType.CARD_TENDER_CANCELLED));

    private final PayWithCardView view;
    private final TenderConfirmView confirmView;
    private final ApprovalScheduler approvalScheduler;
    private PosComponent parent;

    /**
     * The tender type the cashier chose and is now confirming — DEBIT or CREDIT. Non-null only
     * while the tender-confirmation dialog is open; the confirm handler reads it to begin the right
     * card flow. Cleared on confirm or cancel.
     */
    private TenderType pendingTenderType;

    /**
     * @param view        the modal card dialog this controller drives; must not be {@code null}
     * @param confirmView the tender-confirmation modal shown before processing; must not be
     *                    {@code null}
     */
    public PayWithCardViewController(PayWithCardView view, TenderConfirmView confirmView) {
        this(view, confirmView, defaultScheduler());
    }

    /**
     * Test-facing constructor: inject a synchronous scheduler to avoid a live Swing timer.
     */
    PayWithCardViewController(PayWithCardView view, TenderConfirmView confirmView,
                             ApprovalScheduler approvalScheduler) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        if (confirmView == null) throw new IllegalArgumentException("confirmView must not be null");
        if (approvalScheduler == null) throw new IllegalArgumentException("approvalScheduler must not be null");
        this.view = view;
        this.confirmView = confirmView;
        this.approvalScheduler = approvalScheduler;
    }

    private static ApprovalScheduler defaultScheduler() {
        return callback -> {
            Timer timer = new Timer(DEFAULT_APPROVAL_DELAY_MS, e -> callback.run());
            timer.setRepeats(false);
            timer.start();
        };
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
        confirmView.closeDialog();
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
            case TENDER_DEBIT_PRESSED -> openConfirm(TenderType.DEBIT);
            case TENDER_CREDIT_PRESSED -> openConfirm(TenderType.CREDIT);
            case CARD_TENDER_CONFIRM_PRESSED -> confirmTender();
            case CARD_TENDER_CANCELLED -> cancel();
            default -> { /* not subscribed */ }
        }
    }

    // ---- Handlers ---------------------------------------------------------

    /**
     * Opens the tender-confirmation dialog for a card type. The Pay Debit / Pay Credit press no
     * longer starts processing on its own — it stages the tender type and asks the cashier to
     * confirm the amount first, so a mis-tap is recoverable. Processing begins on
     * {@link PosEventType#CARD_TENDER_CONFIRM_PRESSED}.
     */
    private void openConfirm(TenderType tenderType) {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) return;
        pendingTenderType = tenderType;
        BigDecimal amountDue = tx.grandTotal();
        String title = tenderType == TenderType.DEBIT ? "Pay Debit" : "Pay Credit";
        String instrument = tenderType == TenderType.DEBIT ? "Debit Card" : "Credit Card";
        confirmView.openFor(title, "Confirm the card payment below.",
                instrument + " · " + PosTheme.money(amountDue), amountDue);
    }

    private void confirmTender() {
        if (pendingTenderType == null) return;
        TenderType tenderType = pendingTenderType;
        pendingTenderType = null;
        beginCardTender(tenderType);
    }

    private void cancel() {
        // Abandon the tender — Cancel or ESC on the confirmation dialog. No tender event was or
        // will be dispatched; the transaction stays TOTALED and re-tenderable.
        pendingTenderType = null;
        confirmView.closeDialog();
    }

    private void beginCardTender(TenderType tenderType) {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) return;
        BigDecimal amountDue = tx.grandTotal();
        view.configure(tenderType, amountDue);
        view.showProcessing();
        approvalScheduler.schedule(() -> completeApproval(tenderType, amountDue));
        view.openDialog();
    }

    private void completeApproval(TenderType tenderType, BigDecimal amountDue) {
        Transaction paid;
        try {
            paid = parent.getTransactionService().tenderCard(tenderType, amountDue);
        } catch (RuntimeException ex) {
            // Service already dispatched ERROR — surface it on the dialog and close.
            view.closeDialog();
            return;
        }
        view.showApproved();
        Map<String, Object> props = new HashMap<>();
        props.put("transaction", paid);
        props.put("tenderType", tenderType);
        props.put("amountTendered", amountDue);
        props.put("changeDue", BigDecimal.ZERO);
        parent.dispatchPosEvent(new PosEvent(PosEventType.CARD_TENDERED, props));
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED, props));
        view.closeDialog();
    }
}
