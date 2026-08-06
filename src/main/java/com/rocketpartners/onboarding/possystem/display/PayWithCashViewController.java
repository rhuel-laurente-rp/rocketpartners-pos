package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.possystem.component.IController;
import com.rocketpartners.onboarding.possystem.component.PosComponent;
import com.rocketpartners.onboarding.possystem.event.IPosEventListener;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.service.TransactionService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Owns the cash-tender flow. Opens a modal {@link PayWithCashView} when
 * {@link PosEventType#TENDER_CASH_PRESSED} arrives, listens for the dialog's four input events
 * ({@link PosEventType#CASH_EXACT_PRESSED}, {@link PosEventType#CASH_NEXT_DOLLAR_PRESSED},
 * {@link PosEventType#CASH_CONFIRM_PRESSED}, {@link PosEventType#CASH_CANCEL_PRESSED}), and
 * commits the tender via {@link TransactionService#tenderCash(java.math.BigDecimal)}.
 *
 * <p><strong>Amount Due semantics.</strong> The dialog carries an adjustable {@code Amount Due}
 * — the total the customer will hand over — that starts at the transaction's grand total.
 * The two quick-fill buttons change <em>that</em> amount, not the Cash Received field:</p>
 * <ul>
 *   <li>Exact Amount → amount due = grand total (unchanged from the default).</li>
 *   <li>Next Dollar → amount due = grand total rounded up to the next whole dollar
 *       ({@code $7.30 → $8.00}, {@code $7.00 → $7.00} since it is already whole).</li>
 * </ul>
 *
 * <p>Change is computed against the (possibly adjusted) amount due:
 * {@code change = cashReceived − amountDue}.</p>
 *
 * <p>Validation lives here, not in the view: non-numeric or negative input is rejected inline
 * (the dialog stays open, an error event is dispatched, and the transaction is untouched);
 * underpayment (cash received below amount due) is likewise rejected inline. Overpayment is
 * valid and produces change.</p>
 *
 * <p>On Confirm success, dispatches {@link PosEventType#CASH_TENDERED} and
 * {@link PosEventType#TRANSACTION_COMPLETED} — both carrying the tender type, amount tendered,
 * and change due — then closes the dialog. On Cancel, the dialog closes and the transaction is
 * left {@link com.rocketpartners.onboarding.commons.model.TransactionState#TOTALED}, so the
 * cashier can retry a different tender.</p>
 */
public class PayWithCashViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.TENDER_CASH_PRESSED,
            PosEventType.CASH_EXACT_PRESSED,
            PosEventType.CASH_NEXT_DOLLAR_PRESSED,
            PosEventType.CASH_CONFIRM_PRESSED,
            PosEventType.CASH_CANCEL_PRESSED));

    private final PayWithCashView view;
    private PosComponent parent;

    /**
     * The amount the customer is expected to hand over — the total payable. Defaults to the
     * transaction's grand total and can be adjusted up to the next whole dollar via the Next
     * Dollar button; is cleared to {@code null} when no cash dialog is open.
     */
    private BigDecimal amountDue;

    /**
     * @param view the modal cash dialog this controller drives; must not be {@code null}
     */
    public PayWithCashViewController(PayWithCashView view) {
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
            case TENDER_CASH_PRESSED -> openDialog();
            case CASH_EXACT_PRESSED -> setExactAmount();
            case CASH_NEXT_DOLLAR_PRESSED -> setNextDollar();
            case CASH_CONFIRM_PRESSED -> confirm(event);
            case CASH_CANCEL_PRESSED -> cancel();
            default -> { /* not subscribed */ }
        }
    }

    // ---- Handlers ---------------------------------------------------------

    private void openDialog() {
        BigDecimal grandTotal = currentGrandTotal();
        if (grandTotal == null) return;
        amountDue = grandTotal.setScale(2, RoundingMode.HALF_UP);
        view.setAmountDue(amountDue);
        view.setCashReceivedText("");
        view.clearStatus();
        view.openDialog();
    }

    private void setExactAmount() {
        BigDecimal grandTotal = currentGrandTotal();
        if (grandTotal == null) return;
        amountDue = grandTotal.setScale(2, RoundingMode.HALF_UP);
        view.setAmountDue(amountDue);
        view.clearStatus();
    }

    private void setNextDollar() {
        BigDecimal grandTotal = currentGrandTotal();
        if (grandTotal == null) return;
        amountDue = nextDollar(grandTotal);
        view.setAmountDue(amountDue);
        view.clearStatus();
    }

    /**
     * Rounds the given amount up to the next whole dollar, expressed at scale 2. A whole-dollar
     * input is already at the next dollar and returns unchanged (e.g. {@code $7.00 → $7.00}).
     * A fractional input rounds up ({@code $7.30 → $8.00}).
     */
    static BigDecimal nextDollar(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.CEILING).setScale(2);
    }

    private void confirm(PosEvent event) {
        if (amountDue == null) return;
        String raw = event.getProperty("cashReceived", String.class);
        if (raw == null) raw = view.getCashReceivedText();

        BigDecimal cashReceived;
        try {
            cashReceived = new BigDecimal(raw.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            dispatchTenderError("INVALID_CASH_AMOUNT", "cash received is not a valid number: " + raw);
            view.showError("Enter a valid dollar amount.");
            return;
        }
        if (cashReceived.signum() < 0) {
            dispatchTenderError("INVALID_CASH_AMOUNT", "cash received must be non-negative: " + cashReceived);
            view.showError("Amount must be non-negative.");
            return;
        }
        if (cashReceived.compareTo(amountDue) < 0) {
            dispatchTenderError("UNDERPAYMENT",
                    "cash received " + cashReceived + " is less than amount due " + amountDue);
            view.showError("Underpayment — enter at least $"
                    + amountDue.toPlainString() + ".");
            return;
        }

        Transaction paid;
        try {
            paid = parent.getTransactionService().tenderCash(cashReceived, amountDue);
        } catch (RuntimeException ex) {
            // Service already dispatched an error; surface it inline and leave the dialog open
            // so the cashier can retry (e.g. tender was pressed with no totaled transaction).
            view.showError("Tender rejected: " + ex.getMessage());
            return;
        }

        BigDecimal changeDue = paid.changeDue();
        Map<String, Object> props = new HashMap<>();
        props.put("transaction", paid);
        props.put("tenderType", TenderType.CASH);
        props.put("amountTendered", cashReceived);
        props.put("amountDue", amountDue);
        props.put("changeDue", changeDue);
        parent.dispatchPosEvent(new PosEvent(PosEventType.CASH_TENDERED, props));
        // Close the dialog before dispatching completion so the receipt modal opens over the
        // main frame rather than the cash dialog. Change due is on the receipt — no need for a
        // separate confirmation popup.
        view.closeDialog();
        amountDue = null;
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED, props));
    }

    private void cancel() {
        view.closeDialog();
        amountDue = null;
    }

    // ---- helpers ----------------------------------------------------------

    private BigDecimal currentGrandTotal() {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        return tx == null ? null : tx.grandTotal();
    }

    private void dispatchTenderError(String code, String message) {
        Map<String, Object> props = new HashMap<>();
        props.put("code", code);
        props.put("message", message);
        props.put("operation", "tenderCash");
        parent.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));
    }
}
