package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receipt header carries the signed-in cashier (from the login screen, via PosComponent) and a
 * plain integer transaction id.
 */
class ReceiptFormatterHeaderTest {

    private static Transaction totaled() {
        Transaction tx = new Transaction("42", Instant.EPOCH, BigDecimal.ZERO);
        tx.addLineItem(new Item("UPC-W", "Widget", new BigDecimal("1.00")), 1);
        tx.total();
        return tx;
    }

    @Test
    void cashierLine_isPrinted_whenAnOperatorIsSupplied() {
        String receipt = ReceiptFormatter.format(totaled(), "Rocket Store", 1, "OP7");
        assertThat(receipt).contains("Cashier:");
        assertThat(receipt).contains("OP7");
    }

    @Test
    void cashierLine_isOmitted_whenOperatorIsNullOrBlank() {
        assertThat(ReceiptFormatter.format(totaled(), "Rocket Store", 1, null))
                .doesNotContain("Cashier:");
        assertThat(ReceiptFormatter.format(totaled(), "Rocket Store", 1, "   "))
                .doesNotContain("Cashier:");
    }

    @Test
    void transactionId_rendersAsTheIntegerItWasGiven() {
        String receipt = ReceiptFormatter.format(totaled(), "Rocket Store", 1, "OP7");
        assertThat(receipt).contains("Transaction: 42");
    }
}
