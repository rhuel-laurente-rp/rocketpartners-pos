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
 * Owns the two-step cash-tender flow.
 *
 * <p><strong>Step one — mode choice.</strong> On {@link PosEventType#TENDER_CASH_PRESSED} the
 * controller opens {@link CashModeChoiceView}, seeded with the transaction's exact grand total
 * and its next-dollar rounded companion. Each button dispatches back with a
 * {@code prefillAmount} property: the exact grand total for {@link PosEventType#CASH_EXACT_PRESSED}
 * or the next-dollar amount for {@link PosEventType#CASH_NEXT_DOLLAR_PRESSED}.</p>
 *
 * <p><strong>Step two — enter and confirm.</strong> On either mode event the controller closes
 * the choice dialog and opens {@link PayWithCashView} with the mode-inflected amount due. That
 * amount <em>is</em> the pre-fill and is also the reference the entry dialog validates
 * against — one number, both jobs. The field stays fully editable so a $20 bill on a $17.70
 * basket is handled by typing over the pre-fill.</p>
 *
 * <p><strong>Change semantics.</strong> Change is measured against the settled
 * grand-total-amount-due, not the raw grand total. A $7.30 basket rung up as Next Dollar has a
 * settled total of $8.00: hand over $20 and change is $12.00, not $12.70. The service records
 * the settled amount alongside the tender via
 * {@link TransactionService#tenderCash(BigDecimal, BigDecimal)}, so {@link Transaction#changeDue()}
 * returns the same figure the customer sees on-screen.</p>
 *
 * <p><strong>Cancel semantics.</strong> Cancel at either step closes the open dialog and
 * leaves the transaction {@link TransactionState#TOTALED} — re-tenderable via a fresh
 * {@code TENDER_CASH_PRESSED}, which restarts at step one. There is no Back affordance between
 * steps two and one; a wrong mode choice means cancelling and starting over.</p>
 *
 * <p><strong>Journal.</strong> The dispatched events themselves feed {@link
 * com.rocketpartners.onboarding.possystem.component.JournalListener}: opening the flow, the
 * mode selected, the confirmed tender with change due, and cancellation at either step all
 * land on the wire without any explicit journal call here.</p>
 */
public class PayWithCashViewController implements IController, IPosEventListener {

    private static final Set<PosEventType> LISTEN_TYPES = Collections.unmodifiableSet(EnumSet.of(
            PosEventType.TENDER_CASH_PRESSED,
            PosEventType.CASH_EXACT_PRESSED,
            PosEventType.CASH_NEXT_DOLLAR_PRESSED,
            PosEventType.CASH_CONFIRM_PRESSED,
            PosEventType.CASH_CANCEL_PRESSED));

    private final CashModeChoiceView choiceView;
    private final PayWithCashView entryView;
    private PosComponent parent;

    /**
     * The transaction's grand total (tax included) at the moment the flow opened. The mode
     * inflection is applied on top when step two opens. Cached at flow-open time so a stale
     * confirm still validates against the amount the cashier saw on screen.
     */
    private BigDecimal grandTotal;

    /**
     * The mode-inflected amount the customer must pay — equal to {@link #grandTotal} for
     * {@link PayWithCashView.Mode#EXACT}, or that total ceiling'd to the next whole dollar for
     * {@link PayWithCashView.Mode#NEXT_DOLLAR}. This is what the entry dialog shows and what
     * change is measured against.
     */
    private BigDecimal amountDue;

    /**
     * @param choiceView step-one modal; must not be {@code null}
     * @param entryView  step-two modal; must not be {@code null}
     */
    public PayWithCashViewController(CashModeChoiceView choiceView, PayWithCashView entryView) {
        if (choiceView == null) throw new IllegalArgumentException("choiceView must not be null");
        if (entryView == null) throw new IllegalArgumentException("entryView must not be null");
        this.choiceView = choiceView;
        this.entryView = entryView;
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
            case CASH_EXACT_PRESSED -> openEntryFromMode(event, PayWithCashView.Mode.EXACT);
            case CASH_NEXT_DOLLAR_PRESSED ->
                    openEntryFromMode(event, PayWithCashView.Mode.NEXT_DOLLAR);
            case CASH_CONFIRM_PRESSED -> confirm(event);
            case CASH_CANCEL_PRESSED -> cancel();
            default -> { /* not subscribed */ }
        }
    }

    // ---- Handlers ---------------------------------------------------------

    private void openChoice() {
        BigDecimal total = currentGrandTotal();
        if (total == null) return;
        grandTotal = total.setScale(2, RoundingMode.HALF_UP);
        choiceView.openFor(grandTotal, nextDollar(grandTotal));
    }

    private void openEntryFromMode(PosEvent event, PayWithCashView.Mode mode) {
        if (grandTotal == null) return;
        BigDecimal prefill = event.getProperty("prefillAmount", BigDecimal.class);
        if (prefill == null) {
            // Defensive — mode events should always carry a prefill. Fall back to exact.
            prefill = grandTotal;
        }
        amountDue = prefill.setScale(2, RoundingMode.HALF_UP);
        // Close the choice dialog before opening the entry dialog so exactly one modal is on
        // screen at any time.
        choiceView.closeDialog();
        entryView.openFor(amountDue, mode);
    }

    /**
     * Rounds up to the next whole dollar at scale 2. A whole-dollar input returns unchanged
     * ($7.00 → $7.00), a fractional input rounds up ($7.30 → $8.00).
     */
    static BigDecimal nextDollar(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.CEILING).setScale(2);
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
            // Three-arg tender: record the settled amount alongside the cash presented so
            // Transaction#changeDue() measures against the mode-inflected total.
            paid = parent.getTransactionService().tenderCash(cashReceived, amountDue);
        } catch (RuntimeException ex) {
            entryView.showError("Tender rejected: " + ex.getMessage());
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
        entryView.closeDialog();
        grandTotal = null;
        amountDue = null;
        parent.dispatchPosEvent(new PosEvent(PosEventType.TRANSACTION_COMPLETED, props));
    }

    private void cancel() {
        // Cancel is fired by either dialog. Close both defensively — one will be a no-op — so
        // no matter which step the cashier bailed at, the flow lands back on the totaled
        // transaction with tender buttons live.
        choiceView.closeDialog();
        entryView.closeDialog();
        grandTotal = null;
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
