package com.rocketpartners.onboarding.possystem.service;

import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.TenderType;
import com.rocketpartners.onboarding.commons.model.Transaction;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptFormatterDiscountTest {

    /** Mirrors {@link ReceiptFormatter}'s private line width — the only geometry the tests share. */
    private static final int LINE_WIDTH = 40;

    private static final Item ITEM = new Item("UPC-X", "Thing", new BigDecimal("3.79"));

    private Transaction totaled() {
        Transaction tx = new Transaction("t1", Instant.EPOCH, new BigDecimal("0.07"));
        tx.addLineItem(ITEM, 7); // subtotal 26.53
        tx.total();
        return tx;
    }

    /** The per-discount line (starts with "Discount: ", not the "Discount Total:" row). */
    private static String discountLine(String receipt) {
        for (String line : receipt.split("\n")) {
            if (line.startsWith("Discount: ")) return line;
        }
        throw new AssertionError("no per-discount line in receipt:\n" + receipt);
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
    void longDescription_isTruncatedWithEllipsis_andTheAmountStillAligns() {
        Transaction tx = totaled();
        tx.applyDiscount(new Discount("LONG", "Super Duper Extra Long Loyalty Discount Name",
                DiscountType.PERCENT_OFF, new BigDecimal("10"), new BigDecimal("3.79")));

        String receipt = ReceiptFormatter.format(tx);
        String line = discountLine(receipt);

        // Clipped: the full description does not survive, an ellipsis marks the cut, and the line
        // is still exactly the receipt width with the amount flush right — no overlap.
        assertThat(receipt).doesNotContain("Super Duper Extra Long Loyalty Discount Name");
        assertThat(line).contains("…");
        assertThat(line).hasSize(LINE_WIDTH);
        assertThat(line).startsWith("Discount: ");
        assertThat(line).endsWith("-3.79");
    }

    @Test
    void shortDescription_isLeftUntouched() {
        Transaction tx = totaled();
        tx.applyDiscount(new Discount("SENIOR_20", "Senior Disc 20%", DiscountType.PERCENT_OFF,
                new BigDecimal("20"), new BigDecimal("3.79")));

        String line = discountLine(ReceiptFormatter.format(tx));
        assertThat(line).contains("Discount: Senior Disc 20%");
        assertThat(line).doesNotContain("…");
        assertThat(line).endsWith("-3.79");
    }

    @Test
    void everyDescriptionInDiscountsCsv_rendersAWellFormedAlignedLine() throws IOException {
        // The shipped seed carries descriptions of every length — short ones print in full, long
        // ones are ellipsised. Either way the line must stay exactly the receipt width with the
        // amount flush right: that alignment is the durable guarantee, not "everything fits".
        for (String description : seedDescriptions()) {
            Transaction tx = totaled();
            tx.applyDiscount(new Discount("C", description, DiscountType.PERCENT_OFF,
                    new BigDecimal("10"), new BigDecimal("3.79")));
            String line = discountLine(ReceiptFormatter.format(tx));
            assertThat(line).as("discount line for %s", description)
                    .hasSize(LINE_WIDTH)
                    .startsWith("Discount: ")
                    .endsWith("-3.79");
        }
    }

    /** Reads the description column of every rule in the seed {@code discounts.csv}. */
    private static List<String> seedDescriptions() throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = ReceiptFormatterDiscountTest.class.getResourceAsStream("/discounts.csv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                out.add(line.split(",", -1)[1]);
            }
        }
        return out;
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
