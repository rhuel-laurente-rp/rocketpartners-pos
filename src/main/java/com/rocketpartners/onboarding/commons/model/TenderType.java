package com.rocketpartners.onboarding.commons.model;

/**
 * Payment method used to settle a {@link Transaction}.
 */
public enum TenderType {
    /** Physical cash. Change may be due; see {@link Transaction#changeDue()}. */
    CASH,
    /** Debit card. Amount tendered equals grand total; no change. */
    DEBIT,
    /** Credit card. Amount tendered equals grand total; no change. */
    CREDIT
}
