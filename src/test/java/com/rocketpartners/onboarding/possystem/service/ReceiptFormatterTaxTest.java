package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receipt's Tax line derives its rate from the transaction rather than hard-coding "7%". The
 * receipt already printed a rate before this change — it was the literal {@code Tax (7%)}; the fix
 * is that the rate now tracks {@link Transaction#getTaxRate()} so a re-configured rate can't leave
 * a stale label behind.
 */
class ReceiptFormatterTaxTest {

    private static final Item ITEM = new Item("UPC-X", "Thing", new BigDecimal("10.00"));

    private static Transaction totaledWithRate(String rate) {
        Transaction tx = new Transaction("t1", Instant.EPOCH, new BigDecimal(rate));
        tx.addLineItem(ITEM, 1);
        tx.total();
        return tx;
    }

    @Test
    void sevenPercent_rendersAsSeven_notSevenPointZeroZero() {
        String receipt = ReceiptFormatter.format(totaledWithRate("0.07"));
        assertThat(receipt).contains("Tax (7%):");
        assertThat(receipt).doesNotContain("7.00%");
    }

    @Test
    void changedRate_changesTheLabel() {
        assertThat(ReceiptFormatter.format(totaledWithRate("0.085"))).contains("Tax (8.5%):");
        assertThat(ReceiptFormatter.format(totaledWithRate("0.10"))).contains("Tax (10%):");
    }

    @Test
    void zeroRate_rendersAsZero() {
        assertThat(ReceiptFormatter.format(totaledWithRate("0"))).contains("Tax (0%):");
    }
}
