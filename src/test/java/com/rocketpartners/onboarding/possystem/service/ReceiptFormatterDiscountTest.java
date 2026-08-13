package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptFormatterDiscountTest {

    private static final Item ITEM = new Item("UPC-X", "Thing", new BigDecimal("3.79"));

    private Transaction totaled() {
        Transaction tx = new Transaction("t1", Instant.EPOCH, new BigDecimal("0.07"));
        tx.addLineItem(ITEM, 7); // subtotal 26.53
        tx.total();
        return tx;
    }

    @Test
    void rendersOneLinePerDiscount_thenTheDiscountTotal() {
        Transaction tx = totaled();
        tx.applyDiscount(new Discount("BOGO", "Buy 2 Get 1", DiscountType.PROMO,
                BigDecimal.ZERO, new BigDecimal("6.58")));
        tx.applyDiscount(new Discount("SENIOR_20", "Senior 20%", DiscountType.PERCENT_OFF,
                new BigDecimal("20"), new BigDecimal("3.79")));

        String receipt = ReceiptFormatter.format(tx);

        assertThat(receipt).contains("Discount: Buy 2 Get 1");
        assertThat(receipt).contains("-6.58");
        assertThat(receipt).contains("Discount: Senior 20%");
        assertThat(receipt).contains("-3.79");
        assertThat(receipt).contains("Discount Total:");
        assertThat(receipt).contains("-10.37");
    }

    @Test
    void noDiscountTransaction_hasNoDiscountSectionAtAll() {
        Transaction tx = totaled();
        tx.tender(TenderType.DEBIT, tx.grandTotal());
        String receipt = ReceiptFormatter.format(tx);
        // Identical to before the feature: no per-discount lines, no "Discount Total" row.
        assertThat(receipt).doesNotContain("Discount");
    }

    @Test
    void promoDiscountWithNullAmount_doesNotBreakTheReceipt() {
        Transaction tx = totaled();
        // A PROMO discount whose rule amount is null (only appliedAmount is meaningful). The receipt
        // reads appliedAmount, never amount, so this must render cleanly rather than NPE.
        tx.applyDiscount(new Discount("BOGO", "Buy 2 Get 1", DiscountType.PROMO,
                null, new BigDecimal("6.58")));
        String receipt = ReceiptFormatter.format(tx);
        assertThat(receipt).contains("Discount: Buy 2 Get 1");
        assertThat(receipt).contains("Discount Total:");
    }
}
