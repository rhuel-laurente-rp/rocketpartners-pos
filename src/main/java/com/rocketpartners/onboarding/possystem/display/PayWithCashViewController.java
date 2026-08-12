package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
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
 * Owns the cash-tender flow. Three ways in, two of them one tap.
 *
 * <p><strong>Step one — mode choice.</strong> On {@link PosEventType#TENDER_CASH_PRESSED} the
 * controller opens {@link CashModeChoiceView}, seeded with the transaction's exact grand total
 * and its next-dollar rounded companion.</p>
 *
 * <p><strong>Exact Amount and Next Dollar confirm, then tender.</strong>
 * {@link PosEventType#CASH_EXACT_PRESSED} and {@link PosEventType#CASH_NEXT_DOLLAR_PRESSED} no
 * longer tender on the tile press: each opens a {@link TenderConfirmView} showing the figure the
 * cashier is about to settle, and the tender is deferred to
 * {@link PosEventType#CASH_TENDER_CONFIRM_PRESSED}. On confirm, Exact settles the grand total in
 * cash ({@link TransactionService#tenderCash(BigDecimal)} — two-arg tender, {@code amountDue} left
 * null so it falls back to the grand total, change $0.00) and Next Dollar settles the ceiled amount
 * via {@link TransactionService#payNextDollar()} (amountDue set to the ceiled figure, change
 * $0.00). Neither opens the entry dialog — the confirmation is the whole interaction and the
 * receipt follows directly. {@link PosEventType#CASH_TENDER_BACK_PRESSED} returns to the mode
 * choice without tendering. Choosing a mode asserts the customer handed over exactly that amount;
 * a customer offering more goes through Other Amount.</p>
 *
 * <p><strong>Other Amount opens the entry dialog.</strong> On
 * {@link PosEventType#OTHER_CASH_AMOUNT_PRESSED} the controller opens {@link PayWithCashView}
 * seeded with the grand total as the amount owed. The field is editable so the cashier keys
 * what the customer handed over; {@link PosEventType#CASH_CONFIRM_PRESSED} validates and tenders
 * via {@link TransactionService#tenderCash(BigDecimal)} — change is {@code cashReceived −
 * grandTotal()}, measured against the true grand total, so overpayment change may include
 * coins.</p>
 *
 * <p><strong>Zero grand total is rejected on every path.</strong> A totalled empty basket would
 * otherwise let a mis-tap complete a sale of nothing. {@link #tenderableTransaction()} rejects a
 * grand total of $0.00 with an {@code INVALID_ARGUMENT} error rather than tendering — the last
 * checkpoint before the terminal one-tap transitions.</p>
 *
 * <p><strong>Cancel vs Back.</strong> Cancel on the mode-choice dialog (or ESC on either dialog)
 * dispatches {@link PosEventType#CASH_CANCEL_PRESSED} and abandons the flow: both dialogs close
 * and the transaction stays {@link TransactionState#TOTALED}, re-tenderable. The entry dialog's
 * footer button is <em>Back</em>, not Cancel: {@link PosEventType#CASH_ENTRY_BACK_PRESSED}
 * returns to the mode choice without tendering, so a cashier who meant Exact Amount need not
 * re-open Pay Cash. ESC on the entry dialog is the separate full-exit path. Cancelling or
 * backing out at any point dispatches no tender event.</p>
 *
 * <p><strong>Journal.</strong> The dispatched events themselves feed {@link
 * com.rocketpartners.onboarding.possystem.component.JournalListener}. For the one-tap modes both
 * the mode-selection event (Exact / Next Dollar) and the {@link PosEventType#CASH_TENDERED}
 * event (tender type, amount tendered, amountDue, change due) land on the wire, so the log shows
 * which mode produced the tender even though selection and tender were a single action.</p>
 */
public class PayWithCashViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.TENDER_CASH_PRESSED,
            PosEventType.CASH_EXACT_PRESSED,
            PosEventType.CASH_NEXT_DOLLAR_PRESSED,
            PosEventType.CASH_TENDER_CONFIRM_PRESSED,
            PosEventType.CASH_TENDER_BACK_PRESSED,
            PosEventType.OTHER_CASH_AMOUNT_PRESSED,
            PosEventType.CASH_CONFIRM_PRESSED,
            PosEventType.CASH_ENTRY_BACK_PRESSED,
            PosEventType.CASH_CANCEL_PRESSED));

    private final CashModeChoiceView choiceView;
    private final PayWithCashView entryView;
    private final TenderConfirmView confirmView;
    private PosComponent parent;

    /**
     * The mode a cashier chose on the mode-choice dialog and is now confirming — Exact Amount or
     * Next Dollar. Non-null only while the tender-confirmation dialog is open; the confirm handler
     * reads it to decide which tender to settle. Cleared on tender, Back, or abandon.
     */
    private PayWithCashView.Mode pendingMode;

    /**
     * The transaction's grand total (tax included) at the moment the flow opened. Cached so the
     * mode-choice and entry dialogs stay stable across re-opens (Back), and so a stale confirm
     * still validates against the amount the cashier saw on screen. Reset on tender or abandon.
     */
    private BigDecimal grandTotal;

    /**
     * The amount the manual-entry (Other Amount) dialog validates against and measures change
     * from — the grand total. Non-null only while the entry dialog is open. Change is
     * {@code cashReceived − amountDue}, so Other Amount computes against the true grand total.
     */
    private BigDecimal amountDue;

    /**
     * @param choiceView  mode-choice modal; must not be {@code null}
     * @param entryView   Other-Amount entry modal; must not be {@code null}
     * @param confirmView tender-confirmation modal for the Exact / Next Dollar one-tap modes; must
     *                    not be {@code null}
     */
    public PayWithCashViewController(CashModeChoiceView choiceView, PayWithCashView entryView,
                                     TenderConfirmView confirmView) {
        if (choiceView == null) throw new IllegalArgumentException("choiceView must not be null");
        if (entryView == null) throw new IllegalArgumentException("entryView must not be null");
        if (confirmView == null) throw new IllegalArgumentException("confirmView must not be null");
        this.choiceView = choiceView;
        this.entryView = entryView;
        this.confirmView = confirmView;
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
        choiceView.closeDialog();
        entryView.closeDialog();
        confirmView.closeDialog();
    }

    // ---- IPosEventListener ------------------------------------------------

    @Override
    public Set<PosEventType> getListeningEventTypes() {
        return LISTEN_TYPES;
    }

    @Override
    public void onPosEvent(PosEvent event) {
        switch (event.getType()) {
            case TENDER_CASH_PRESSED -> openChoice();
            case CASH_EXACT_PRESSED -> openTenderConfirm(PayWithCashView.Mode.EXACT);
            case CASH_NEXT_DOLLAR_PRESSED -> openTenderConfirm(PayWithCashView.Mode.NEXT_DOLLAR);
            case CASH_TENDER_CONFIRM_PRESSED -> confirmTender();
            case CASH_TENDER_BACK_PRESSED -> backFromConfirm();
            case OTHER_CASH_AMOUNT_PRESSED -> openEntry();
            case CASH_CONFIRM_PRESSED -> confirm(event);
            case CASH_ENTRY_BACK_PRESSED -> backToChoice();
            case CASH_CANCEL_PRESSED -> cancel();
            default -> { /* not subscribed */ }
        }
    }

    // ---- Step one: mode choice --------------------------------------------

    private void openChoice() {
        Transaction tx = tenderableTransaction();
        if (tx == null) return;
        grandTotal = tx.grandTotal().setScale(2, RoundingMode.HALF_UP);
        choiceView.openFor(grandTotal, nextDollar(grandTotal));
    }

    // ---- One-tap modes: confirm, then tender ------------------------------

    /**
     * Opens the tender-confirmation dialog for a one-tap mode. The tile press no longer tenders on
     * its own — it stages the mode and asks the cashier to confirm the figure first, so a mis-tap
     * is recoverable. The tender is settled on {@link PosEventType#CASH_TENDER_CONFIRM_PRESSED}.
     */
    private void openTenderConfirm(PayWithCashView.Mode mode) {
        Transaction tx = tenderableTransaction();
        if (tx == null) return;
        grandTotal = tx.grandTotal().setScale(2, RoundingMode.HALF_UP);
        pendingMode = mode;
        BigDecimal amount = mode == PayWithCashView.Mode.NEXT_DOLLAR
                ? nextDollar(grandTotal) : grandTotal;
        // One modal on screen at a time — close the choice before the confirmation opens, mirroring
        // the Other Amount transition.
        choiceView.closeDialog();
        confirmView.openFor("Confirm Payment", "Confirm the cash payment below.",
                mode.label() + " · " + PosTheme.money(amount), amount);
    }

    private void confirmTender() {
        if (pendingMode == null) return;
        PayWithCashView.Mode mode = pendingMode;
        Transaction tx = tenderableTransaction();
        if (tx == null) {
            // Basket no longer tenderable (e.g. voided out from under the dialog). Abandon the
            // staged confirmation rather than tendering; the service already dispatched ERROR.
            pendingMode = null;
            confirmView.closeDialog();
            return;
        }
        Transaction paid;
        try {
            if (mode == PayWithCashView.Mode.NEXT_DOLLAR) {
                // payNextDollar ceils the grand total, records that ceiled figure as both cash
                // tendered and amountDue, and tenders it — change $0.00.
                paid = parent.getTransactionService().payNextDollar();
            } else {
                // Two-arg tenderCash leaves amountDue null; Transaction#amountDue() then falls back
                // to grandTotal(), and change is grandTotal − grandTotal = $0.00.
                BigDecimal exact = tx.grandTotal().setScale(2, RoundingMode.HALF_UP);
                paid = parent.getTransactionService().tenderCash(exact);
            }
        } catch (RuntimeException ex) {
            return; // service already dispatched ERROR
        }
        completeCashTender(paid);
    }

    private void backFromConfirm() {
        // Return to the mode choice without tendering — a cashier who mis-tapped a tile can pick
        // another. No tender event; transaction stays TOTALED.
        if (grandTotal == null) return;
        pendingMode = null;
        confirmView.closeDialog();
        choiceView.openFor(grandTotal, nextDollar(grandTotal));
    }

    // ---- Other Amount: entry + confirm ------------------------------------

    private void openEntry() {
        Transaction tx = tenderableTransaction();
        if (tx == null) return;
        grandTotal = tx.grandTotal().setScale(2, RoundingMode.HALF_UP);
        // Other Amount owes the exact grand total; the entry dialog validates the keyed cash
        // against it and measures change from it. Close the choice dialog first so exactly one
        // modal is on screen.
        amountDue = grandTotal;
        choiceView.closeDialog();
        entryView.openFor(amountDue, PayWithCashView.Mode.EXACT);
    }

    private void confirm(PosEvent event) {
        if (amountDue == null) return;
        String raw = event.getProperty("cashReceived", String.class);
        if (raw == null) raw = entryView.getCashReceivedText();

        BigDecimal cashReceived;
        try {
            cashReceived = new BigDecimal(raw.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            dispatchTenderError("INVALID_CASH_AMOUNT",
                    "cash received is not a valid number: " + raw);
            entryView.showError("Enter a valid dollar amount.");
            return;
        }
        if (cashReceived.signum() < 0) {
            dispatchTenderError("INVALID_CASH_AMOUNT",
                    "cash received must be non-negative: " + cashReceived);
            entryView.showError("Amount must be non-negative.");
            return;
        }
        if (cashReceived.compareTo(amountDue) < 0) {
            dispatchTenderError("UNDERPAYMENT",
                    "cash received " + cashReceived
                            + " is less than amount due " + amountDue);
            entryView.showError("Amount is less than the amount due.");
            return;
        }

        Transaction paid;
        try {
            // Two-arg tenderCash: amountDue left null so change is measured against the grand
            // total — an overpayment on Other Amount yields real change, coins included.
            paid = parent.getTransactionService().tenderCash(cashReceived);
        } catch (RuntimeException ex) {
            entryView.showError("Tender rejected: " + ex.getMessage());
            return;
        }
        completeCashTender(paid);
    }

    // ---- Navigation / cancel ----------------------------------------------

    private void backToChoice() {
        // Return to the mode choice without tendering. No tender event; transaction stays
        // TOTALED. Re-seed from the cached grand total so the tiles show the same figures.
        if (grandTotal == null) return;
        amountDue = null;
        entryView.closeDialog();
        choiceView.openFor(grandTotal, nextDollar(grandTotal));
    }

    private void cancel() {
        // Abandon the whole flow — Cancel on the choice dialog or ESC on any dialog. Close all
        // three defensively (at most one is showing) so the lane lands back on the totaled
        // transaction with tender buttons live. No tender event was or will be dispatched.
        choiceView.closeDialog();
        entryView.closeDialog();
        confirmView.closeDialog();
        grandTotal = null;
        amountDue = null;
        pendingMode = null;
    }

    // ---- Shared tender completion -----------------------------------------

    /**
     * Emits {@link PosEventType#CASH_TENDERED} and {@link PosEventType#TRANSACTION_COMPLETED} for
     * a paid transaction, reading tender type, amount tendered, amountDue, and change due off the
     * aggregate so all three paths report consistent figures. Closes every cash dialog and clears
     * the cached amounts.
     */
    private void completeCashTender(Transaction paid) {
        Map<String, Object> props = new HashMap<>();
        props.put("transaction", paid);
        props.put("tenderType", TenderType.CASH);
        props.put("amountTendered", paid.getCashTendered());
        props.put("amountDue", paid.amountDue());
        props.put("changeDue", paid.changeDue());

        choiceView.closeDialog();
        entryView.closeDialog();
        confirmView.closeDialog();
        grandTotal = null;
        amountDue = null;
        pendingMode = null;
        parent.dispatchPosEvent(new PosEvent(PosEventType.CASH_TENDERED, props));
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED, props));
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Rounds up to the next whole dollar at scale 2. A whole-dollar input returns unchanged
     * ($7.00 → $7.00), a fractional input rounds up ($7.30 → $8.00).
     */
    static BigDecimal nextDollar(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.CEILING).setScale(2);
    }

    /**
     * @return the current transaction if it is present and has a positive grand total; otherwise
     *         {@code null}. A zero grand total is rejected with an {@code INVALID_ARGUMENT} error
     *         so a totalled empty basket can never be tendered — the guard that keeps a mis-tap
     *         from completing a sale of nothing.
     */
    private Transaction tenderableTransaction() {
        Transaction tx = parent.getTransactionService().getCurrentTransaction();
        if (tx == null) return null; // nothing open; service would reject any tender too
        if (tx.grandTotal().signum() <= 0) {
            dispatchTenderError("INVALID_ARGUMENT", "cannot tender a zero-total transaction");
            return null;
        }
        return tx;
    }

    private void dispatchTenderError(String code, String message) {
        Map<String, Object> props = new HashMap<>();
        props.put("code", code);
        props.put("message", message);
        props.put("operation", "tenderCash");
        parent.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));
    }
}
