package com.rocketpartners.onboarding.commons.model;

/**
 * The lifecycle state of a {@link Transaction}.
 *
 * <p>Transitions:</p>
 * <pre>
 *   IN_PROGRESS ── total() ────▶ TOTALED ── tender() ─▶ PAID   (terminal)
 *       │                          │
 *       └── voidBasket() ─▶ VOIDED ◀── voidBasket()             (terminal)
 * </pre>
 *
 * <p>{@code PAID} and {@code VOIDED} are terminal — no further transitions are legal.</p>
 */
public enum TransactionState {
    /** Basket is open. Line items may be added or voided; the transaction may be totaled or voided outright. */
    IN_PROGRESS,
    /** Basket is frozen. Discounts may be applied; only tender is legal next (or void). */
    TOTALED,
    /** Tender received. Terminal. */
    PAID,
    /** Transaction was voided. Terminal. */
    VOIDED
}
