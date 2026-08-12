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

    /**
     * The Change Qty button on a view was pressed. Input event; carries a {@code lineItem}
     * property naming the currently selected basket row. The
     * {@code ChangeQuantityViewController} opens a modal spinner dialog in response.
     */
    CHANGE_QTY_PRESSED,

    /**
     * The Confirm button on the change-quantity dialog was pressed. Input event; carries
     * {@code lineItem} and {@code newQuantity} (Integer) properties.
     */
    CHANGE_QTY_CONFIRM_PRESSED,

    /**
     * The Cancel button on the change-quantity dialog was pressed. Input event; no
     * properties. Closes the dialog with the line's quantity unchanged.
     */
    CHANGE_QTY_CANCEL_PRESSED,

    /**
     * The Void Basket button on the main window was pressed. Input event; no properties.
     * Opens {@link com.rocketpartners.onboarding.possystem.display.VoidBasketConfirmView}. Voiding
     * is deferred until the cashier confirms via {@link #VOID_BASKET_CONFIRM_PRESSED}; this event
     * alone must not mutate transaction state.
     */
    VOID_BASKET_PRESSED,

    /**
     * The Void basket confirm button on the confirmation dialog was pressed. Input event; no
     * properties. The controller reacts by calling voidBasket and starting a fresh transaction.
     */
    VOID_BASKET_CONFIRM_PRESSED,

    /**
     * The cashier declined the void-basket confirmation (Keep basket, ESC, or window close).
     * Notification event; carries {@code itemCount} (Integer, sum of non-voided quantities at
     * the moment of the prompt) and {@code grandTotal} (BigDecimal, grand total at the moment
     * of the prompt) properties. Captured explicitly — a lane racking up near-voids is exactly
     * the pattern a shrink review looks for.
     */
    VOID_BASKET_DECLINED,

    /** The Total button on a view was pressed. Input event; no properties. */
    TOTAL_PRESSED,

    /**
     * The Discount button on the main window was pressed. Input event; no properties.
     *
     * <p><strong>Not yet wired to a feature.</strong> The button is present in the actions row
     * but disabled — applying a discount while a transaction is {@code IN_PROGRESS} is a domain
     * change (widening {@link com.rocketpartners.onboarding.commons.model.Transaction#applyDiscount()}'s
     * state rule and making running-subtotal discounts track basket mutations) that belongs on a
     * dedicated {@code feature/in-progress-discounts} branch with its own tests, not bundled into
     * a layout refactor. This event constant and the button's listener exist so the wiring is in
     * place; nothing dispatches it until the button is enabled by that feature.</p>
     */
    DISCOUNT_PRESSED,

    /**
     * The Pay Cash button was pressed. Input event; no properties. Opens the cash-mode-choice
     * dialog. From there {@link #CASH_EXACT_PRESSED} and {@link #CASH_NEXT_DOLLAR_PRESSED} open a
     * confirmation dialog and defer tender to {@link #CASH_TENDER_CONFIRM_PRESSED}; only
     * {@link #OTHER_CASH_AMOUNT_PRESSED} opens the amount-entry dialog and defers tender to
     * {@link #CASH_CONFIRM_PRESSED}.
     */
    TENDER_CASH_PRESSED,

    /**
     * The Pay Debit button was pressed. Input event; no properties. Opens the card-tender
     * confirmation dialog; the actual card processing is deferred to
     * {@link #CARD_TENDER_CONFIRM_PRESSED}.
     */
    TENDER_DEBIT_PRESSED,

    /**
     * The Pay Credit button was pressed. Input event; no properties. Opens the card-tender
     * confirmation dialog; the actual card processing is deferred to
     * {@link #CARD_TENDER_CONFIRM_PRESSED}.
     */
    TENDER_CREDIT_PRESSED,

    /**
     * The Confirm button on the card-tender confirmation dialog was pressed. Input event; no
     * properties. The controller reacts by opening the card-processing dialog and committing the
     * tender for the pending tender type (DEBIT or CREDIT). The confirmation dialog alone must not
     * charge the card — that is deferred to this event so a mis-tap on Pay Debit / Pay Credit is
     * recoverable.
     */
    CARD_TENDER_CONFIRM_PRESSED,

    /**
     * The card-tender confirmation was cancelled — Cancel or ESC on the confirmation dialog.
     * Input event; no properties. Closes the dialog with no tender; the transaction remains
     * {@code TOTALED} and re-tenderable.
     */
    CARD_TENDER_CANCELLED,

    /**
     * The Exact Amount tile on the cash-mode-choice dialog was pressed. Input event; carries a
     * {@code prefillAmount} property (BigDecimal, the transaction's grand total) for journalling
     * which mode produced the tender. <strong>Opens the cash-tender confirmation dialog</strong>
     * seeded with the grand total; the tender is deferred to {@link #CASH_TENDER_CONFIRM_PRESSED}.
     */
    CASH_EXACT_PRESSED,

    /**
     * The Next Dollar tile on the cash-mode-choice dialog was pressed. Input event; carries a
     * {@code prefillAmount} property (BigDecimal, the grand total rounded up to the next whole
     * dollar) for journalling. <strong>Opens the cash-tender confirmation dialog</strong> seeded
     * with the ceiled amount; the tender is deferred to {@link #CASH_TENDER_CONFIRM_PRESSED}.
     */
    CASH_NEXT_DOLLAR_PRESSED,

    /**
     * The Confirm button on the cash-tender confirmation dialog was pressed (Exact Amount / Next
     * Dollar paths). Input event; no properties. The controller settles the pending cash amount
     * for the pending mode (grand total for Exact, ceiled figure for Next Dollar) and goes
     * straight to the receipt. Deferring the tender to this event makes a mis-tap on a mode tile
     * recoverable.
     */
    CASH_TENDER_CONFIRM_PRESSED,

    /**
     * The Back button on the cash-tender confirmation dialog was pressed. Input event; no
     * properties. Returns to the mode-choice dialog without tendering — a cashier who mis-tapped
     * a mode tile can pick another. The transaction stays {@code TOTALED}.
     */
    CASH_TENDER_BACK_PRESSED,

    /**
     * The Other Amount button on the cash-mode-choice dialog was pressed. Input event; no
     * meaningful properties (the amount is unknown until typed). Opens the amount-entry dialog
     * so the cashier can key what the customer actually handed over; tender is deferred to
     * {@link #CASH_CONFIRM_PRESSED}, and change is computed against the true grand total.
     */
    OTHER_CASH_AMOUNT_PRESSED,

    /**
     * The Confirm button on the cash-entry dialog was pressed (Other Amount path only). Input
     * event; carries the raw {@code cashReceived} string entered by the cashier for the
     * controller to validate.
     */
    CASH_CONFIRM_PRESSED,

    /**
     * The Back button on the cash-entry dialog was pressed. Input event; no properties. Returns
     * to the mode-choice dialog without tendering — the cashier who meant Exact or Next Dollar
     * need not re-open Pay Cash. The transaction stays {@code TOTALED}.
     */
    CASH_ENTRY_BACK_PRESSED,

    /**
     * The cash flow was abandoned entirely — Cancel on the mode-choice dialog, or ESC on either
     * cash dialog. Input event; no properties. Closes both dialogs; the transaction remains
     * {@code TOTALED} and re-tenderable via a fresh {@link #TENDER_CASH_PRESSED}. Dispatches no
     * tender event.
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

    /**
     * The cashier tapped the scan bar's keypad button to open the on-screen numeric keypad for
     * manual barcode entry. UI-open event: {@link com.rocketpartners.onboarding.possystem.display.ManualBarcodeEntryViewController}
     * opens the modal keypad dialog in response. The dialog's own confirm re-uses
     * {@link #SCAN_SUBMIT_PRESSED}, so manual keypad entry validates on the same path as a typed
     * or scanned barcode. Carries no properties.
     */
    MANUAL_ENTRY_PRESSED,

    /** An item was added to the basket (new line or quantity increment). */
    ITEM_ADDED,

    /** A single line item was voided. */
    LINE_VOIDED,

    /**
     * A line item's quantity was changed. Notification event; carries {@code lineItem} and
     * {@code newQuantity} (Integer) properties. A change to zero is surfaced as a
     * {@link #LINE_VOIDED} event instead — the two paths share a single implementation in
     * {@code TransactionService}.
     */
    QUANTITY_CHANGED,

    /**
     * The whole transaction was voided (terminal).
     *
     * <p>Standard properties on a confirmed void: {@code itemCount} (Integer, sum of non-voided
     * quantities), {@code grandTotal} (BigDecimal, grand total at the moment of the void), and
     * {@code priorState} (String, the {@link com.rocketpartners.onboarding.commons.model.TransactionState}
     * name the transaction was in before {@code voidBasket()} — {@code IN_PROGRESS} or
     * {@code TOTALED}). Voiding after Total is the more interesting case operationally and is
     * distinguishable via {@code priorState}.</p>
     */
    BASKET_VOIDED,

    /** The transaction was totaled — basket is frozen; tender is next. */
    TRANSACTION_TOTALED,

    /**
     * Cash tender recorded. Carries {@code tenderType} (TenderType, always CASH),
     * {@code amountTendered} (BigDecimal, cash presented), {@code amountDue} (BigDecimal, the
     * settled amount the customer owed — the grand total, or the ceiled figure for Next Dollar),
     * and {@code changeDue} (BigDecimal, change owed to the customer) properties.
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
     * <p>Standard properties: {@code code} (short identifier), {@code message} (human-readable
     * detail), and {@code cause} (Throwable, when present). May also carry {@code operation},
     * {@code upc}, {@code raw}, {@code max}, and other kind-specific keys.</p>
     *
     * <p>The full {@code code} vocabulary dispatched today, and where each originates:</p>
     * <ul>
     *   <li>{@code UPC_NOT_FOUND}, {@code UPC_MISREAD} — {@code TransactionService.addItemByUpc}</li>
     *   <li>{@code INVALID_BARCODE}, {@code SCAN_LOCKED} — {@code ScannerViewController}</li>
     *   <li>{@code INVALID_CASH_AMOUNT}, {@code UNDERPAYMENT} — {@code PayWithCashViewController}</li>
     *   <li>{@code TOTALED_INVARIANT}, {@code NO_TRANSACTION}, {@code INVALID_ARGUMENT},
     *       {@code TRANSACTION_ALREADY_OPEN}, {@code ABOVE_MAX_QUANTITY}, {@code ILLEGAL_STATE}
     *       — {@code TransactionService} (state-machine and argument guards)</li>
     * </ul>
     */
    ERROR
}
