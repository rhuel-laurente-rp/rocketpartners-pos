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

    /** The Pay Cash button was pressed. Input event; no properties (Pay-Next-Dollar semantics). */
    TENDER_CASH_PRESSED,

    /** The Pay Debit button was pressed. Input event; no properties. */
    TENDER_DEBIT_PRESSED,

    /** The Pay Credit button was pressed. Input event; no properties. */
    TENDER_CREDIT_PRESSED,

    /** A UPC was scanned or entered on {@code ScannerView}; carries a {@code upc} property. */
    ITEM_SCANNED,

    /** An item was added to the basket (new line or quantity increment). */
    ITEM_ADDED,

    /** A single line item was voided. */
    LINE_VOIDED,

    /** The whole transaction was voided (terminal). */
    BASKET_VOIDED,

    /** The transaction was totaled — basket is frozen; tender is next. */
    TRANSACTION_TOTALED,

    /** Cash tender recorded (includes Pay Next Dollar). */
    CASH_TENDERED,

    /** Card tender recorded; {@code tenderType} property distinguishes DEBIT vs CREDIT. */
    CARD_TENDERED,

    /** Receipt printed; the transaction is fully complete. Terminal notification. */
    TRANSACTION_COMPLETED,

    /** A POS-level error occurred (e.g. bad UPC lookup, illegal state, journal unreachable). */
    ERROR
}
