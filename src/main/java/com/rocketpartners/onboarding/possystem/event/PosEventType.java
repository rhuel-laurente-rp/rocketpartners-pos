package com.rocketpartners.onboarding.possystem.event;

/**
 * The kinds of {@link PosEvent} that flow through the POS's event bus.
 *
 * <p>Two families of events:</p>
 * <ul>
 *   <li><strong>Input events</strong> — dispatched by views or controllers to signal a user
 *       action; consumed by controllers or {@code PosComponent}.</li>
 *   <li><strong>Lifecycle / notification events</strong> — dispatched by {@code PosComponent}
 *       after transaction state changes; consumed by any listener that renders or reacts to
 *       Transaction state.</li>
 * </ul>
 *
 * <p>This enum starts with the lifecycle shape the current milestone requires. The finer-grained
 * UI-layer request events named in {@code docs/Phase 1/event-flow.md} (e.g. {@code UPC_ENTERED},
 * {@code REQUEST_ADD_ITEM}) will continue to be added alongside their views and controllers
 * in later commits.</p>
 */
public enum PosEventType {

    /**
     * A quick-add button on a view was pressed. Input event; carries a {@code upc} property
     * naming the pricebook UPC the button was bound to.
     */
    QUICK_ADD_PRESSED,

    /**
     * The Void Line button on a view was pressed. Input event; carries a {@code lineItem}
     * property naming the currently selected basket row (may be absent when no row is selected —
     * consumers should treat that as a no-op).
     */
    VOID_LINE_PRESSED,

    /** The Void Basket button on a view was pressed. Input event; no properties. */
    VOID_BASKET_PRESSED,

    /** The Total button on a view was pressed. Input event; no properties. */
    TOTAL_PRESSED,

    /**
     * The Pay Cash button was pressed. Input event; no properties. Opens the cash-entry
     * dialog — actual tender waits for {@link #CASH_CONFIRM_PRESSED}.
     */
    TENDER_CASH_PRESSED,

    /** The Pay Debit button was pressed. Input event; no properties. */
    TENDER_DEBIT_PRESSED,

    /** The Pay Credit button was pressed. Input event; no properties. */
    TENDER_CREDIT_PRESSED,

    /**
     * The Exact Amount button on the cash dialog was pressed. Input event; no properties.
     * Sets the total payable ({@code Amount Due}) to the transaction's grand total; does not
     * tender. Change is computed against the (possibly adjusted) amount due at Confirm time.
     */
    CASH_EXACT_PRESSED,

    /**
     * The Next Dollar button on the cash dialog was pressed. Input event; no properties.
     * Sets the total payable ({@code Amount Due}) to the transaction's grand total rounded up
     * to the next whole dollar; does not tender. Change is computed against the adjusted
     * amount due at Confirm time.
     */
    CASH_NEXT_DOLLAR_PRESSED,

    /**
     * The Confirm button on the cash dialog was pressed. Input event; carries the raw
     * {@code cashReceived} string entered by the cashier for the controller to validate.
     */
    CASH_CONFIRM_PRESSED,

    /**
     * The Cancel button on the cash dialog was pressed. Input event; no properties. Closes
     * the dialog; the transaction remains {@code TOTALED} and re-tenderable.
     */
    CASH_CANCEL_PRESSED,

    /** A UPC was scanned or entered on {@code ScannerView}; carries a {@code upc} property. */
    ITEM_SCANNED,

    /**
     * The cashier submitted the scan field manually (typed digits then hit Enter). Input
     * event; carries the raw field text as a {@code raw} property. The controller validates
     * and, if the input parses as a UPC, dispatches {@link #ITEM_SCANNED}; otherwise
     * {@link #ERROR}. Distinct from {@link #ITEM_SCANNED} so tests can tell the two paths
     * apart.
     */
    SCAN_SUBMIT_PRESSED,

    /** An item was added to the basket (new line or quantity increment). */
    ITEM_ADDED,

    /** A single line item was voided. */
    LINE_VOIDED,

    /** The whole transaction was voided (terminal). */
    BASKET_VOIDED,

    /** The transaction was totaled — basket is frozen; tender is next. */
    TRANSACTION_TOTALED,

    /**
     * Cash tender recorded. Carries {@code amountTendered} (BigDecimal, cash presented) and
     * {@code changeDue} (BigDecimal, change owed to the customer) properties.
     */
    CASH_TENDERED,

    /**
     * Card tender recorded; {@code tenderType} property distinguishes DEBIT vs CREDIT.
     * Carries {@code amountTendered} (BigDecimal, always equal to grand total) and
     * {@code changeDue} (BigDecimal, always zero) properties.
     */
    CARD_TENDERED,

    /**
     * Payment recorded; the receipt is about to be displayed. Carries the paid
     * {@code transaction} (Transaction) plus {@code tenderType} (TenderType),
     * {@code amountTendered} (BigDecimal), and {@code changeDue} (BigDecimal). The tender
     * controllers dispatch this; {@link com.rocketpartners.onboarding.possystem.display.ReceiptViewController}
     * reacts by rendering the receipt.
     */
    TRANSACTION_COMPLETED,

    /**
     * The dismiss button on the receipt dialog was pressed. Input event; no properties. The
     * cashier is done reviewing the receipt.
     */
    RECEIPT_DISMISS_PRESSED,

    /**
     * The receipt was dismissed and the POS is ready for the next customer. Notification
     * event; no properties. Consumers ({@code CustomerViewController}) reset the basket
     * display and start a new transaction.
     */
    RECEIPT_DISMISSED,

    /**
     * A POS-level error occurred (e.g. bad UPC lookup, illegal state, journal unreachable).
     *
     * <p>Standard properties: {@code code} (short identifier such as {@code UPC_NOT_FOUND},
     * {@code TOTALED_INVARIANT}, {@code INVALID_CASH_AMOUNT}, {@code UNDERPAYMENT},
     * {@code NO_TRANSACTION}, {@code INVALID_ARGUMENT}), {@code message} (human-readable
     * detail), and {@code cause} (Throwable, when present). May also carry
     * {@code operation}, {@code upc}, and other kind-specific keys.</p>
     */
    ERROR
}
