package com.rocketpartners.onboarding.commons.model;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A wholesale: its line items, discounts, tender, and lifecycle state.
 *
 * <p>{@code Transaction} is the aggregate root of the domain model. It owns the state machine
 * that decides which operations are legal at any moment; those checks live here and are the
 * single source of truth. UI concerns like disabling buttons are a nicety, not a guarantee.</p>
 *
 * <p><strong>State machine.</strong> Starts in {@link TransactionState#IN_PROGRESS}. See
 * {@link TransactionState} for the full transition diagram.</p>
 *
 * <p><strong>Money.</strong> All monetary computations use {@link BigDecimal}. Intermediate
 * totals ({@link #subtotal()}, {@link #discountTotal()}, {@link #taxTotal()},
 * {@link LineItem#extendedTotal()}) are <em>not</em> rounded — precision is preserved so
 * intermediate rounding errors do not compound. Rounding happens exactly once, at
 * {@link #grandTotal()}, to scale 2 with {@link RoundingMode#HALF_UP}.</p>
 *
 * <p><strong>Tax.</strong> A flat {@code taxRate} is injected via the constructor and applied
 * after totaling, to {@code subtotal − discountTotal}. Tax is not a per-line concern. Keeping
 * the rate as a field avoids introducing a service layer prematurely; a more sophisticated tax
 * model can replace this without changing the aggregate's contract.</p>
 *
 * <p>See {@code docs/Phase 1/domain-model.md} for the broader domain model.</p>
 */
@Getter
public class Transaction {

    /** Stable, unique identifier for this transaction. */
    private final String transactionId;

    /** Wall-clock time this transaction was opened. */
    private final Instant createdAt;

    private final List<LineItem> lineItems = new ArrayList<>();

    private final List<Discount> discounts = new ArrayList<>();

    /** Flat sales-tax rate applied to the post-discount subtotal (e.g. {@code 0.07} for 7%). */
    private final BigDecimal taxRate;

    /** Current lifecycle state. Starts at {@link TransactionState#IN_PROGRESS}. */
    private TransactionState state;

    /** How the sale was tendered; {@code null} until {@link #tender(TenderType, BigDecimal)}. */
    private TenderType tenderType;

    /** Cash amount presented by the customer; {@code null} until tendered. */
    private BigDecimal cashTendered;

    /**
     * The customer-facing total the cashier settled at — {@link #grandTotal()} by default, but
     * may be higher when a cash tender used a rounding shortcut (e.g. Next Dollar rounded
     * $7.30 up to $8.00 so the cashier will not be giving a change in decimal).
     * {@code null} until tendered.
     */
    private BigDecimal amountDue;

    /**
     * Opens a new transaction with a freshly generated short id and the current instant.
     *
     * <p>The id is the first 8 hex chars of a random {@link UUID}. {@link #getCreatedAt()}
     * carries the wall-clock time separately, so the id is not required to encode it —
     * cashier-readable brevity wins over collision-proof entropy at this scale.</p>
     *
     * @param taxRate flat sales-tax rate; must not be {@code null}
     */
    public Transaction(BigDecimal taxRate) {
        this(UUID.randomUUID().toString().substring(0, 23), Instant.now(), taxRate);
    }

    /**
     * Opens a new transaction with an explicit id and creation time; primarily useful for tests.
     *
     * @param transactionId non-null id
     * @param createdAt     non-null creation time
     * @param taxRate       non-null sales-tax rate
     */
    public Transaction(String transactionId, Instant createdAt, BigDecimal taxRate) {
        if (transactionId == null) throw new IllegalArgumentException("transactionId must not be null");
        if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
        if (taxRate == null) throw new IllegalArgumentException("taxRate must not be null");
        this.transactionId = transactionId;
        this.createdAt = createdAt;
        this.taxRate = taxRate;
        this.state = TransactionState.IN_PROGRESS;
    }

    /** @return an unmodifiable view of the line items on this transaction */
    public List<LineItem> getLineItems() {
        return Collections.unmodifiableList(lineItems);
    }

    /** @return an unmodifiable view of the discounts applied to this transaction */
    public List<Discount> getDiscounts() {
        return Collections.unmodifiableList(discounts);
    }

    /**
     * Adds a product to the basket. If a non-voided line item for the same UPC already exists,
     * its quantity is incremented; otherwise a new line item is appended.
     *
     * @param item     the pricebook item; must not be {@code null}
     * @param quantity units to add; must be at least 1
     * @throws IllegalStateException    if the transaction is not {@link TransactionState#IN_PROGRESS}
     * @throws IllegalArgumentException if {@code item} is null or {@code quantity < 1}
     */
    public void addLineItem(Item item, int quantity) {
        requireState("addLineItem", TransactionState.IN_PROGRESS);
        if (item == null) throw new IllegalArgumentException("item must not be null");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be >= 1, got " + quantity);
        for (LineItem existing : lineItems) {
            if (!existing.isVoided() && existing.getItem().getUpc().equals(item.getUpc())) {
                existing.setQuantity(existing.getQuantity() + quantity);
                return;
            }
        }
        lineItems.add(new LineItem(item, quantity));
    }

    /**
     * Voids the given line item (soft-delete: the line remains on the transaction but
     * contributes zero to totals).
     *
     * @param lineItem a line item that belongs to this transaction
     * @throws IllegalStateException    if the transaction is not {@link TransactionState#IN_PROGRESS}
     * @throws IllegalArgumentException if the given line item is not on this transaction
     */
    public void voidLine(LineItem lineItem) {
        requireState("voidLine", TransactionState.IN_PROGRESS);
        if (!lineItems.contains(lineItem)) {
            throw new IllegalArgumentException("line item is not part of this transaction");
        }
        lineItem.setVoided(true);
    }

    /**
     * Updates the quantity of a line item on this transaction. The line must belong to this
     * transaction and must not be voided; use {@link #voidLine(LineItem)} to remove a line,
     * not a quantity of zero.
     *
     * @param lineItem    a line item on this transaction
     * @param newQuantity the new quantity; must be at least 1
     * @throws IllegalStateException    if the transaction is not {@link TransactionState#IN_PROGRESS}
     * @throws IllegalArgumentException if the line is not on this transaction, is voided, or
     *                                  {@code newQuantity < 1}
     */
    public void updateLineItemQuantity(LineItem lineItem, int newQuantity) {
        requireState("updateLineItemQuantity", TransactionState.IN_PROGRESS);
        if (!lineItems.contains(lineItem)) {
            throw new IllegalArgumentException("line item is not part of this transaction");
        }
        if (lineItem.isVoided()) {
            throw new IllegalArgumentException("cannot update quantity of a voided line item");
        }
        if (newQuantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1, got " + newQuantity);
        }
        lineItem.setQuantity(newQuantity);
    }

    /**
     * Voids the entire transaction. Terminal.
     *
     * <p>Legal in {@link TransactionState#IN_PROGRESS} or {@link TransactionState#TOTALED};
     * throws in {@link TransactionState#PAID} or {@link TransactionState#VOIDED}.</p>
     *
     * @throws IllegalStateException if the transaction is not
     *                               {@link TransactionState#IN_PROGRESS} or
     *                               {@link TransactionState#TOTALED}
     */
    public void voidBasket() {
        requireState("voidBasket", TransactionState.IN_PROGRESS, TransactionState.TOTALED);
        this.state = TransactionState.VOIDED;
    }

    /**
     * Finalizes the basket: freezes line-item mutation and moves to
     * {@link TransactionState#TOTALED}. Discounts and tender are legal next.
     *
     * @throws IllegalStateException if the transaction is not {@link TransactionState#IN_PROGRESS}
     */
    public void total() {
        requireState("total", TransactionState.IN_PROGRESS);
        this.state = TransactionState.TOTALED;
    }

    /**
     * Applies a discount computed by the discount engine to this transaction.
     * Legal only between {@link #total()} and {@link #tender(TenderType, BigDecimal)}.
     *
     * @param discount the engine-computed discount; must not be {@code null}
     * @throws IllegalStateException    if the transaction is not {@link TransactionState#TOTALED}
     * @throws IllegalArgumentException if {@code discount} is null
     */
    public void applyDiscount(Discount discount) {
        requireState("applyDiscount", TransactionState.TOTALED);
        if (discount == null) throw new IllegalArgumentException("discount must not be null");
        discounts.add(discount);
    }

    /**
     * Records a card payment and moves to {@link TransactionState#PAID}. Terminal.
     *
     * <p>Card-only overload — accepts {@link TenderType#DEBIT} or {@link TenderType#CREDIT}.
     * Cards produce no change, so callers pass a single amount equal to {@link #grandTotal()};
     * that value is stored as both {@code cashTendered} and {@code amountDue}, keeping
     * {@link #changeDue()} at zero. For cash use
     * {@link #tender(TenderType, BigDecimal, BigDecimal)}.</p>
     *
     * @param type      payment method; must be {@link TenderType#DEBIT} or {@link TenderType#CREDIT}
     * @param amountDue amount charged; must not be {@code null}
     * @throws IllegalStateException    if the transaction is not {@link TransactionState#TOTALED}
     * @throws IllegalArgumentException if either argument is null, or {@code type} is
     *                                  {@link TenderType#CASH}
     */
    public void tender(TenderType type, BigDecimal amountDue) {
        requireState("tender", TransactionState.TOTALED);
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (amountDue == null) throw new IllegalArgumentException("amountDue must not be null");
        if (type != TenderType.DEBIT && type != TenderType.CREDIT) throw new IllegalArgumentException("type must be DEBIT or CREDIT");
        this.tenderType = type;
        this.cashTendered = amountDue;
        this.amountDue = amountDue;
        this.state = TransactionState.PAID;
    }

    /**
     * Records a cash payment and moves to {@link TransactionState#PAID}. Terminal.
     *
     * <p>Cash-only overload — records both the cash the customer handed over and the
     * customer-facing amount the cashier settled at. Pass a non-null {@code amountDue} when it
     * differs from {@link #grandTotal()} — the Next Dollar shortcut is the canonical case. When
     * {@code amountDue} is {@code null} the transaction's amount due defaults to
     * {@link #grandTotal()} at read time.</p>
     *
     * @param type         payment method; must be {@link TenderType#CASH}
     * @param cashTendered amount the customer presented; must not be {@code null}
     * @param amountDue    settled amount due; may be {@code null} to mean "same as grand total"
     * @throws IllegalStateException    if the transaction is not {@link TransactionState#TOTALED}
     * @throws IllegalArgumentException if {@code type} is not {@link TenderType#CASH},
     *                                  {@code cashTendered} is null, or a non-null
     *                                  {@code amountDue} is below {@link #grandTotal()}
     */
    public void tender(TenderType type, BigDecimal cashTendered, BigDecimal amountDue) {
        requireState("tender", TransactionState.TOTALED);
        if (type == null) throw new IllegalArgumentException("tender type must not be null");
        if (cashTendered == null) throw new IllegalArgumentException("cashTendered must not be null");
        if (type != TenderType.CASH) throw new IllegalArgumentException("type must be CASH");
        // A settled amount due below the grand total would let changeDue() frame an underpayment
        // as change owed. The aggregate owns this invariant — callers must not settle for less
        // than the sale is worth. A null amountDue means "same as grand total" and is always safe.
        if (amountDue != null && amountDue.compareTo(grandTotal()) < 0) {
            throw new IllegalArgumentException(
                    "amountDue " + amountDue + " must be >= grand total " + grandTotal());
        }
        this.tenderType = type;
        this.cashTendered = cashTendered;
        this.amountDue = amountDue == null ? null : amountDue.setScale(2, RoundingMode.HALF_UP);
        this.state = TransactionState.PAID;
    }

    /**
     * Cash tender using the Next Dollar shortcut. Ceils {@link #grandTotal()} up to the next
     * whole dollar, records that ceiled figure as <em>both</em> the cash tendered and the
     * settled {@link #amountDue}, and moves to {@link TransactionState#PAID}. Terminal.
     *
     * <p>Because tendered equals amountDue, {@link #changeDue()} is exactly zero — the cashier
     * hands back no coins. On a $17.70 basket the amount due becomes $18.00, the customer pays
     * $18.00, and change is $0.00.</p>
     *
     * <p><strong>What this asserts.</strong> Calling this commits to the customer having handed
     * over exactly the ceiled amount. It is a single, immediate action with no intermediate
     * entry step — a customer offering a larger bill (e.g. $20.00 on a $17.70 basket) must be
     * handled through the manual cash-entry path instead, where change is computed against the
     * true grand total. The ceiled figure is always {@code >= grandTotal()}, so the settled
     * amount can never underpay.</p>
     *
     * @throws IllegalStateException if the transaction is not {@link TransactionState#TOTALED}
     */
    public void payNextDollar() {
        requireState("payNextDollar", TransactionState.TOTALED);
        BigDecimal ceiled = grandTotal().setScale(0, RoundingMode.CEILING).setScale(2, RoundingMode.HALF_UP);
        tender(TenderType.CASH, ceiled, ceiled);
    }

    /**
     * Sum of {@link LineItem#extendedTotal()} across all non-voided line items. Not rounded.
     *
     * @return the raw subtotal; never {@code null}
     */
    public BigDecimal subtotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (LineItem li : lineItems) {
            if (!li.isVoided()) {
                sum = sum.add(li.extendedTotal());
            }
        }
        return sum;
    }

    /**
     * Sum of {@link Discount#getAppliedAmount()} across all applied discounts. Not rounded.
     *
     * @return the total discount reduction; never {@code null}
     */
    public BigDecimal discountTotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Discount d : discounts) {
            sum = sum.add(d.getAppliedAmount());
        }
        return sum;
    }

    /**
     * The transaction-level tax: {@code (subtotal − discountTotal) × taxRate}. Not rounded.
     * Tax is applied after totaling, on the post-discount subtotal — not per line item.
     *
     * @return the tax due on this transaction; never {@code null}
     */
    public BigDecimal taxTotal() {
        return subtotal().subtract(discountTotal()).multiply(taxRate);
    }

    /**
     * The customer-facing final total: {@code subtotal − discountTotal + taxTotal}, rounded to
     * scale 2 with {@link RoundingMode#HALF_UP}. This is the sole rounding site.
     *
     * @return the grand total, scale 2; never {@code null}
     */
    public BigDecimal grandTotal() {
        BigDecimal raw = subtotal().subtract(discountTotal()).add(taxTotal());
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Change due to the customer: {@code cashTendered − amountDue()}, rounded to scale 2 with
     * {@link RoundingMode#HALF_UP}. Returns {@link BigDecimal#ZERO} if the transaction has not
     * been tendered, or if the tender type is not {@link TenderType#CASH}.
     *
     * <p>The Next Dollar shortcut records an {@code amountDue} above {@link #grandTotal()};
     * change is measured against that settled amount, not the raw grand total, so a
     * "hand over $8 for a $7.30 total" tender yields $0.00 change — not $0.70.</p>
     *
     * @return change due, scale 2; never {@code null}
     */
    public BigDecimal changeDue() {
        if (cashTendered == null || tenderType != TenderType.CASH) {
            return BigDecimal.ZERO;
        }
        return cashTendered.subtract(amountDue()).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The customer-facing amount the cashier settled at. Defaults to {@link #grandTotal()};
     * differs when the tender used the Next Dollar shortcut, in which case the customer paid
     * a rounded-up amount to avoid receiving change.
     *
     * @return the settled amount due, scale 2; never {@code null}
     */
    public BigDecimal amountDue() {
        return amountDue == null ? grandTotal() : amountDue;
    }

    private void requireState(String operation, TransactionState... allowed) {
        for (TransactionState s : allowed) {
            if (state == s) return;
        }
        throw new IllegalStateException(
                "operation '" + operation + "' is not legal in state " + state
                        + "; allowed: " + Arrays.toString(allowed));
    }
}
