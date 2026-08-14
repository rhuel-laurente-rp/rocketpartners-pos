package com.rocketpartners.onboarding.commons.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-numbered transaction id is a zero-padded sequential integer (e.g. {@code 0000001}), not a
 * random UUID — short and cashier-readable, and it prints on the receipt as a running number.
 */
class TransactionIdTest {

    @Test
    void autoId_isAZeroPaddedInteger_notAUuid() {
        String id = new Transaction(BigDecimal.ZERO).getTransactionId();
        // Seven digits, zero-padded — no UUID hex or hyphens.
        assertThat(id).matches("\\d{7}");
        assertThat(Integer.parseInt(id)).isPositive();
    }

    @Test
    void consecutiveTransactions_getIncreasingIds() {
        int first = Integer.parseInt(new Transaction(BigDecimal.ZERO).getTransactionId());
        int second = Integer.parseInt(new Transaction(BigDecimal.ZERO).getTransactionId());
        assertThat(second).isGreaterThan(first);
    }
}
