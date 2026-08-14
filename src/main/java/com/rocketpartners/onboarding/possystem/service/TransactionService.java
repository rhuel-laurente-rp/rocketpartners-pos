package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import com.rocketpartners.onboarding.commons.model.TransactionState;
import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;
import com.rocketpartners.onboarding.possystem.component.Barcodes;
import com.rocketpartners.onboarding.possystem.repository.ItemRepository;
import com.rocketpartners.onboarding.possystem.repository.UpcResolver;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stateful facade over one in-flight {@link Transaction}.
 *
 * <p>Holds "the current transaction" the way {@code PosComponent} will delegate to it: start,
 * scan / quick-add, void, total, tender, produce a receipt. Delegates all state-machine and
 * money logic to the aggregate; adds two things on top:</p>
 * <ol>
 *   <li>UPC → {@link Item} resolution via an {@link ItemRepository}.</li>
 *   <li>Error dispatch: on any invariant violation or lookup miss, dispatches a
 *       {@link PosEventType#ERROR} event before rethrowing so the UI layer (or any listener)
 *       hears about it — the prompt's "don't rely on the UI" guarantee.</li>
 * </ol>
 *
 * <p>Error events carry three property keys: {@code code} (short identifier such as
 * {@code UPC_NOT_FOUND} or {@code TOTALED_INVARIANT}), {@code message} (the exception's
 * message), and {@code cause} (the original {@link Throwable}, or absent when the service
 * itself originates the error).</p>
 */
public class TransactionService {

    /** Default upper bound on a line item's quantity — a fat-fingered scanner input guard. */
    public static final int DEFAULT_MAX_LINE_QUANTITY = 999;

    private final ItemRepository itemRepository;
    private final TaxService taxService;
    private final IPosEventDispatcher eventDispatcher;
    private final int maxLineQuantity;

    /**
     * -- GETTER --
     *
     * @return the current in-flight transaction, or {@code null} if none is open
     */
    @Getter
    private Transaction currentTransaction;

    /**
     * @param itemRepository  pricebook lookup; must not be {@code null}
     * @param taxService      supplies the flat rate for each new transaction; must not be {@code null}
     * @param eventDispatcher target for error events; must not be {@code null}
     *                       (pass {@code event -> {}} for a no-op)
     */
    public TransactionService(ItemRepository itemRepository, TaxService taxService,
                              IPosEventDispatcher eventDispatcher) {
        this(itemRepository, taxService, eventDispatcher, DEFAULT_MAX_LINE_QUANTITY);
    }

    /**
     * As {@link #TransactionService(ItemRepository, TaxService, IPosEventDispatcher)}, but with
     * an explicit upper bound on line-item quantities. Any change-qty call above this bound is
     * rejected with an {@code ABOVE_MAX_QUANTITY} error event rather than accepted.
     */
    public TransactionService(ItemRepository itemRepository, TaxService taxService,
                              IPosEventDispatcher eventDispatcher, int maxLineQuantity) {
        if (itemRepository == null) throw new IllegalArgumentException("itemRepository must not be null");
        if (taxService == null) throw new IllegalArgumentException("taxService must not be null");
        if (eventDispatcher == null) throw new IllegalArgumentException("eventDispatcher must not be null");
        if (maxLineQuantity < 1) {
            throw new IllegalArgumentException("maxLineQuantity must be >= 1, got " + maxLineQuantity);
        }
        this.itemRepository = itemRepository;
        this.taxService = taxService;
        this.eventDispatcher = eventDispatcher;
        this.maxLineQuantity = maxLineQuantity;
    }

    /** @return the configured upper bound on line-item quantities */
    public int getMaxLineQuantity() {
        return maxLineQuantity;
    }

    /**
     * Opens a new transaction and remembers it. Rejects the call if another transaction is
     * already in flight (i.e. not yet {@link TransactionState#PAID} or
     * {@link TransactionState#VOIDED}).
     *
     * @return the freshly started transaction
     * @throws IllegalStateException if a transaction is already open
     */
    public Transaction startTransaction() {
        if (currentTransaction != null && !isTerminal(currentTransaction)) {
            IllegalStateException ex = new IllegalStateException(
                    "cannot start a new transaction while another is open (state="
                            + currentTransaction.getState() + ")");
            dispatchError("TRANSACTION_ALREADY_OPEN", ex.getMessage(), null);
            throw ex;
        }
        currentTransaction = new Transaction(taxService.getRate());
        return currentTransaction;
    }

    /**
     * Looks up the UPC in the pricebook and adds the item to the current transaction.
     * If a non-voided line for this UPC already exists, its quantity is incremented instead
     * of a new line being appended.
     *
     * @param upc      the barcode; must not be {@code null}
     * @param quantity units to add; must be at least 1
     * @return the affected line item (existing or newly appended)
     * @throws IllegalArgumentException if the UPC is unknown, or {@code quantity < 1}
     * @throws IllegalStateException    if no transaction is open, or the current one is not
     *                                  {@link TransactionState#IN_PROGRESS}
     */
    public LineItem addItemByUpc(String upc, int quantity) {
        return addItemByUpcDetailed(upc, quantity).getLineItem();
    }

    /**
     * As {@link #addItemByUpc(String, int)}, but returns the full resolution outcome — the
     * affected {@link LineItem} plus which rung of {@link UpcResolver} produced the hit and the
     * normalised key that matched. Callers that want to journal the ladder outcome
     * ({@code CustomerViewController} → {@code ITEM_ADDED}) use this overload.
     *
     * <p>The single-argument {@link #addItemByUpc(String, int)} delegates to this method — the
     * ladder is the only lookup path. Callers that don't care about the rung throw away the
     * extra fields.</p>
     */
    public AddItemOutcome addItemByUpcDetailed(String upc, int quantity) {
        requireCurrentTransaction("addItemByUpc");
        Optional<UpcResolver.Resolution> resolved = UpcResolver.resolve(itemRepository, upc);
        if (resolved.isEmpty()) {
            IllegalArgumentException ex = new IllegalArgumentException("unknown UPC: " + upc);
            // 12-digit input with an invalid check digit is far more likely a scanner misread
            // than an unknown product. Separate code so the popup can prompt the cashier to
            // rescan rather than let them conclude the item is unlisted and hand-key it.
            boolean likelyMisread = upc != null
                    && upc.length() == Barcodes.UPC_A_LENGTH
                    && Barcodes.isValidUpc(upc)
                    && !Barcodes.hasValidUpcACheckDigit(upc);
            Map<String, Object> props = new HashMap<>();
            props.put("code", likelyMisread ? "UPC_MISREAD" : "UPC_NOT_FOUND");
            props.put("message", ex.getMessage());
            props.put("upc", upc);
            eventDispatcher.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));
            throw ex;
        }
        Item item = resolved.get().getItem();
        try {
            currentTransaction.addLineItem(item, quantity);
        } catch (IllegalStateException e) {
            dispatchError("TOTALED_INVARIANT", e.getMessage(), e, "operation", "addItemByUpc");
            throw e;
        } catch (IllegalArgumentException e) {
            dispatchError("INVALID_ARGUMENT", e.getMessage(), e, "operation", "addItemByUpc");
            throw e;
        }
        for (LineItem li : currentTransaction.getLineItems()) {
            if (!li.isVoided() && li.getItem().getUpc().equals(item.getUpc())) {
                return new AddItemOutcome(li, resolved.get().getRung(), resolved.get().getMatchedKey());
            }
        }
        throw new IllegalStateException("line item disappeared after add — should not happen");
    }

    /** Outcome of {@link #addItemByUpcDetailed(String, int)}. */
    public static final class AddItemOutcome {
        private final LineItem lineItem;
        private final UpcResolver.Rung matchedRung;
        private final String matchedKey;

        AddItemOutcome(LineItem lineItem, UpcResolver.Rung matchedRung, String matchedKey) {
            this.lineItem = lineItem;
            this.matchedRung = matchedRung;
            this.matchedKey = matchedKey;
        }

        public LineItem getLineItem() { return lineItem; }
        public UpcResolver.Rung getMatchedRung() { return matchedRung; }
        public String getMatchedKey() { return matchedKey; }
    }

    /**
     * Voids a single line item on the current transaction (soft-delete).
     *
     * @param lineItem the line to void
     * @throws IllegalStateException    if no transaction is open, or the current one is not
     *                                  {@link TransactionState#IN_PROGRESS}
     * @throws IllegalArgumentException if the line is not on the current transaction
     */
    public void voidLine(LineItem lineItem) {
        requireCurrentTransaction("voidLine");
        try {
            currentTransaction.voidLine(lineItem);
        } catch (IllegalStateException e) {
            dispatchError("TOTALED_INVARIANT", e.getMessage(), e, "operation", "voidLine");
            throw e;
        } catch (IllegalArgumentException e) {
            dispatchError("INVALID_ARGUMENT", e.getMessage(), e, "operation", "voidLine");
            throw e;
        }
    }

    /**
     * Updates the quantity of a line item on the current transaction. Guarded by the same
     * TOTALED invariant as {@link #voidLine(LineItem)}: legal only in
     * {@link TransactionState#IN_PROGRESS}.
     *
     * <p><strong>Zero routes through the void path.</strong> {@code newQuantity == 0} is a
     * void — this method delegates to {@link #voidLine(LineItem)} rather than opening a
     * parallel code path. The line stays on the transaction, marked voided, contributes zero
     * to totals, and is omitted from the printed receipt. Two ways to void a line means two
     * sets of bugs; this method deliberately doesn't do that.</p>
     *
     * <p><strong>Unchanged quantity is a no-op.</strong> If {@code newQuantity} equals the
     * line's current quantity, this method returns without touching the transaction and
     * without dispatching any event. No journal entry, no recompute.</p>
     *
     * @param lineItem    a line item on the current transaction
     * @param newQuantity the new quantity; must be non-negative and at most
     *                    {@link #getMaxLineQuantity()}
     * @throws IllegalStateException    if no transaction is open, or the current one is not
     *                                  {@link TransactionState#IN_PROGRESS}
     * @throws IllegalArgumentException if the line is not on the current transaction, is
     *                                  voided, is negative, or above the configured max
     */
    public void updateLineItemQuantity(LineItem lineItem, int newQuantity) {
        requireCurrentTransaction("updateLineItemQuantity");
        if (lineItem == null) {
            IllegalArgumentException ex = new IllegalArgumentException("lineItem must not be null");
            dispatchError("INVALID_ARGUMENT", ex.getMessage(), ex, "operation", "updateLineItemQuantity");
            throw ex;
        }
        if (newQuantity < 0) {
            IllegalArgumentException ex = new IllegalArgumentException(
                    "quantity must be non-negative, got " + newQuantity);
            dispatchError("INVALID_ARGUMENT", ex.getMessage(), ex, "operation", "updateLineItemQuantity");
            throw ex;
        }
        if (newQuantity > maxLineQuantity) {
            IllegalArgumentException ex = new IllegalArgumentException(
                    "quantity " + newQuantity + " exceeds max " + maxLineQuantity);
            dispatchError("ABOVE_MAX_QUANTITY", ex.getMessage(), ex,
                    "operation", "updateLineItemQuantity", "max", maxLineQuantity);
            throw ex;
        }
        // Unchanged quantity on a non-voided line: no-op, no event, no recompute.
        if (!lineItem.isVoided() && lineItem.getQuantity() == newQuantity) {
            return;
        }
        // Zero routes through the shared void path — same operation as the Void Line button.
        if (newQuantity == 0) {
            voidLine(lineItem);
            return;
        }
        try {
            currentTransaction.updateLineItemQuantity(lineItem, newQuantity);
        } catch (IllegalStateException e) {
            dispatchError("TOTALED_INVARIANT", e.getMessage(), e, "operation", "updateLineItemQuantity");
            throw e;
        } catch (IllegalArgumentException e) {
            dispatchError("INVALID_ARGUMENT", e.getMessage(), e, "operation", "updateLineItemQuantity");
            throw e;
        }
    }

    /**
     * Voids the entire current transaction (terminal → {@link TransactionState#VOIDED}) and
     * releases the "current" slot so a new transaction can be started.
     *
     * @return the voided transaction
     * @throws IllegalStateException if no transaction is open, or the current one is already terminal
     */
    public Transaction voidBasket() {
        requireCurrentTransaction("voidBasket");
        Transaction tx = currentTransaction;
        try {
            tx.voidBasket();
        } catch (IllegalStateException e) {
            dispatchError("ILLEGAL_STATE", e.getMessage(), e, "operation", "voidBasket");
            throw e;
        }
        currentTransaction = null;
        return tx;
    }

    /**
     * Finalizes the current transaction (→ {@link TransactionState#TOTALED}). Basket becomes
     * immutable; only tender is legal next.
     *
     * @return the totaled transaction
     * @throws IllegalStateException if no transaction is open, or the current one is not
     *                               {@link TransactionState#IN_PROGRESS}
     */
    public Transaction total() {
        requireCurrentTransaction("total");
        try {
            currentTransaction.total();
        } catch (IllegalStateException e) {
            dispatchError("TOTALED_INVARIANT", e.getMessage(), e, "operation", "total");
            throw e;
        }
        return currentTransaction;
    }

    /**
     * Re-opens the current (finalized) transaction for editing — {@code TOTALED} back to
     * {@code IN_PROGRESS} — so more lines can be rung up after Total. Engine-applied discounts are
     * cleared and recompute on the next {@link #total()}. Rejected (as {@code ILLEGAL_STATE}) if no
     * transaction is open or it is not in {@code TOTALED}.
     *
     * @return the re-opened transaction
     * @throws IllegalStateException if there is no current transaction, or it is not TOTALED
     */
    public Transaction resumeEditing() {
        requireCurrentTransaction("resumeEditing");
        try {
            currentTransaction.resumeEditing();
        } catch (IllegalStateException e) {
            dispatchError("ILLEGAL_STATE", e.getMessage(), e, "operation", "resumeEditing");
            throw e;
        }
        return currentTransaction;
    }

    /**
     * Records a cash tender. The current transaction transitions to
     * {@link TransactionState#PAID} and the "current" slot is released.
     *
     * @param amount cash presented by the customer; must not be {@code null}
     * @return the paid transaction
     * @throws IllegalStateException if the transaction is not {@link TransactionState#TOTALED}
     */
    public Transaction tenderCash(BigDecimal amount) {
        return tenderCash(amount, null);
    }

    /**
     * As {@link #tenderCash(BigDecimal)}, but records the settled amount due — the total the
     * cashier told the customer to pay. Passed non-null when the cashier used the Next Dollar
     * shortcut so the receipt reflects the rounded amount, not the raw grand total.
     *
     * @param amount    cash presented; must not be {@code null}
     * @param amountDue settled amount due; may be {@code null} to default to grand total
     * @return the paid transaction
     */
    public Transaction tenderCash(BigDecimal amount, BigDecimal amountDue) {
        requireCurrentTransaction("tenderCash");
        Transaction tx = currentTransaction;
        try {
            tx.tender(TenderType.CASH, amount, amountDue);
        } catch (IllegalStateException e) {
            dispatchError("TOTALED_INVARIANT", e.getMessage(), e, "operation", "tenderCash");
            throw e;
        } catch (IllegalArgumentException e) {
            dispatchError("INVALID_ARGUMENT", e.getMessage(), e, "operation", "tenderCash");
            throw e;
        }
        currentTransaction = null;
        return tx;
    }

    /**
     * Records a cash tender using the Next Dollar shortcut. Delegates to
     * {@link Transaction#payNextDollar()}: the current transaction's grand total is ceiled up to
     * the next whole dollar, that ceiled figure is recorded as both the cash presented and the
     * settled amount due (so {@link Transaction#changeDue()} is zero), and the transaction
     * transitions to {@link TransactionState#PAID}. The "current" slot is released.
     *
     * @return the paid transaction
     * @throws IllegalStateException if no transaction is open, or it is not
     *                               {@link TransactionState#TOTALED}
     */
    public Transaction payNextDollar() {
        requireCurrentTransaction("payNextDollar");
        Transaction tx = currentTransaction;
        try {
            tx.payNextDollar();
        } catch (IllegalStateException e) {
            dispatchError("TOTALED_INVARIANT", e.getMessage(), e, "operation", "payNextDollar");
            throw e;
        } catch (IllegalArgumentException e) {
            dispatchError("INVALID_ARGUMENT", e.getMessage(), e, "operation", "payNextDollar");
            throw e;
        }
        currentTransaction = null;
        return tx;
    }

    /**
     * Records a card tender (debit or credit). The caller is responsible for passing an amount
     * equal to {@link Transaction#grandTotal()} — cards do not produce change. The current
     * transaction transitions to {@link TransactionState#PAID} and the "current" slot is released.
     *
     * @param tenderType {@link TenderType#DEBIT} or {@link TenderType#CREDIT}
     * @param amount     amount charged; must not be {@code null}
     * @return the paid transaction
     * @throws IllegalArgumentException if {@code tenderType} is {@link TenderType#CASH} or {@code null}
     * @throws IllegalStateException    if the transaction is not {@link TransactionState#TOTALED}
     */
    public Transaction tenderCard(TenderType tenderType, BigDecimal amount) {
        if (tenderType == null) throw new IllegalArgumentException("tenderType must not be null");
        if (tenderType == TenderType.CASH) {
            throw new IllegalArgumentException("tenderCard rejects CASH; use tenderCash");
        }
        requireCurrentTransaction("tenderCard");
        Transaction tx = currentTransaction;
        try {
            tx.tender(tenderType, amount);
        } catch (IllegalStateException e) {
            dispatchError("TOTALED_INVARIANT", e.getMessage(), e, "operation", "tenderCard");
            throw e;
        } catch (IllegalArgumentException e) {
            dispatchError("INVALID_ARGUMENT", e.getMessage(), e, "operation", "tenderCard");
            throw e;
        }
        currentTransaction = null;
        return tx;
    }

    /**
     * Renders the given transaction as a plain-text receipt. The transaction is passed in
     * (rather than read from internal state) because a successful tender clears the current
     * slot — callers keep a reference to the returned {@link Transaction} and pass it here.
     *
     * @param transaction the (typically paid) transaction to render
     * @return multi-line receipt text
     */
    public String generateReceipt(Transaction transaction) {
        return ReceiptFormatter.format(transaction);
    }

    /**
     * As {@link #generateReceipt(Transaction)}, but prepends the store name and lane number
     * to the header. Used by the UI when it has these from the CLI args.
     */
    public String generateReceipt(Transaction transaction, String storeName, Integer laneNumber) {
        return ReceiptFormatter.format(transaction, storeName, laneNumber);
    }

    /**
     * As {@link #generateReceipt(Transaction, String, Integer)}, but also stamps the signed-in
     * cashier onto the header. The cashier code originates at the login screen and is carried on
     * {@code PosComponent}.
     */
    public String generateReceipt(Transaction transaction, String storeName, Integer laneNumber,
                                  String cashier) {
        return ReceiptFormatter.format(transaction, storeName, laneNumber, cashier);
    }

    private void requireCurrentTransaction(String operation) {
        if (currentTransaction == null) {
            IllegalStateException ex = new IllegalStateException(
                    "no transaction is open (operation=" + operation + ")");
            dispatchError("NO_TRANSACTION", ex.getMessage(), null, "operation", operation);
            throw ex;
        }
    }

    private static boolean isTerminal(Transaction tx) {
        return tx.getState() == TransactionState.PAID || tx.getState() == TransactionState.VOIDED;
    }

    private void dispatchError(String code, String message, Throwable cause) {
        dispatchError(code, message, cause, null, null);
    }

    private void dispatchError(String code, String message, Throwable cause, String extraKey, Object extraValue) {
        Map<String, Object> props = new HashMap<>();
        props.put("code", code);
        props.put("message", message);
        if (cause != null) props.put("cause", cause);
        if (extraKey != null) props.put(extraKey, extraValue);
        eventDispatcher.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));
    }

    private void dispatchError(String code, String message, Throwable cause,
                               String key1, Object val1, String key2, Object val2) {
        Map<String, Object> props = new HashMap<>();
        props.put("code", code);
        props.put("message", message);
        if (cause != null) props.put("cause", cause);
        if (key1 != null) props.put(key1, val1);
        if (key2 != null) props.put(key2, val2);
        eventDispatcher.dispatchPosEvent(new PosEvent(PosEventType.ERROR, props));
    }
}
