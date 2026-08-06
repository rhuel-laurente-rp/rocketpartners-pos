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
 * {@code REQUEST_ADD_ITEM}, {@code TOTAL_PRESSED}) will be added alongside their views and
 * controllers in a later commit.</p>
 */
public enum PosEventType {

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
