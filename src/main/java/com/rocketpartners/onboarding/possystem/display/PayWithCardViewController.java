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
 * {@link TenderType#CREDIT}, parameterized by the pressed event. Opens a modal
 * {@link PayWithCardView}, shows "processing", schedules a short simulated-approval delay off
 * the EDT, then commits the tender via
 * {@link com.rocketpartners.onboarding.possystem.service.TransactionService#tenderCard(TenderType, BigDecimal)}
 * for the full amount due (no change).
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
            PosEventType.TENDER_CREDIT_PRESSED));

    private final PayWithCardView view;
    private final ApprovalScheduler approvalScheduler;
    private PosComponent parent;

    /**
     * @param view the modal card dialog this controller drives; must not be {@code null}
     */
    public PayWithCardViewController(PayWithCardView view) {
        this(view, defaultScheduler());
    }

    /**
     * Test-facing constructor: inject a synchronous scheduler to avoid a live Swing timer.
     */
    PayWithCardViewController(PayWithCardView view, ApprovalScheduler approvalScheduler) {
        if (view == null) throw new IllegalArgumentException("view must not be null");
        if (approvalScheduler == null) throw new IllegalArgumentException("approvalScheduler must not be null");
        this.view = view;
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
            case TENDER_DEBIT_PRESSED -> beginCardTender(TenderType.DEBIT);
            case TENDER_CREDIT_PRESSED -> beginCardTender(TenderType.CREDIT);
            default -> { /* not subscribed */ }
        }
    }

    // ---- Handlers ---------------------------------------------------------

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
