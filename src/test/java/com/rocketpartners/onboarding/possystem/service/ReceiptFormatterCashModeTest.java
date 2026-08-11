package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ReceiptFormatter} already renders each of the three cash-tender outcomes the
 * restructured cash flow produces — no formatting change was needed, only confirmation that the
 * existing {@code Amount Due (mode)} / {@code Change} lines cover all three:
 *
 * <ul>
 *   <li><strong>Exact Amount</strong> — tendered equals the grand total, change $0.00, labelled
 *       {@code Amount Due (Exact)}.</li>
 *   <li><strong>Next Dollar</strong> — the ceiled figure shows on the {@code Amount Due (Next
 *       Dollar)} line, change $0.00.</li>
 *   <li><strong>Other Amount</strong> — tendered as typed, change is the difference against the
 *       true grand total.</li>
 * </ul>
 */
class ReceiptFormatterCashModeTest {

    private static Transaction totaled() {
        Transaction tx = new Transaction(BigDecimal.ZERO); // no tax → grand total == subtotal
        tx.addLineItem(new Item("UPC-W", "Widget", new BigDecimal("7.30")), 1);
        tx.total();
        return tx;
    }

    @Test
    void exactAmount_tenderedEqualsTotal_changeZero() {
        Transaction tx = totaled();
        tx.tender(TenderType.CASH, new BigDecimal("7.30"), null); // two-arg cash → amountDue null

        String receipt = ReceiptFormatter.format(tx);

        assertThat(receipt).contains("TOTAL:");
        assertThat(receipt).contains("Amount Due (Exact):");
        assertThat(receipt).contains("Tender: CASH");
        assertThat(receipt).containsPattern("Change:\\s+0\\.00");
    }

    @Test
    void nextDollar_showsCeiledAmountDueLine_changeZero() {
        Transaction tx = totaled();
        tx.payNextDollar(); // 7.30 → 8.00, amountDue = 8.00

        String receipt = ReceiptFormatter.format(tx);

        assertThat(receipt).containsPattern("Amount Due \\(Next Dollar\\):\\s+8\\.00");
        assertThat(receipt).containsPattern("Tender: CASH\\s+8\\.00");
        assertThat(receipt).containsPattern("Change:\\s+0\\.00");
    }

    @Test
    void otherAmount_tenderedAsTyped_changeIsDifferenceAgainstGrandTotal() {
        Transaction tx = totaled();
        tx.tender(TenderType.CASH, new BigDecimal("10.00"), null); // amountDue null → grand total

        String receipt = ReceiptFormatter.format(tx);

        // Change is measured against the true grand total (7.30), not a ceiled figure.
        assertThat(receipt).containsPattern("Amount Due \\(Exact\\):\\s+7\\.30");
        assertThat(receipt).containsPattern("Tender: CASH\\s+10\\.00");
        assertThat(receipt).containsPattern("Change:\\s+2\\.70");
    }
}
