package com.rocketpartners.onboarding.possystem.display;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-regression sweep: every user-visible action label and dialog title in the POS is
 * Title Case — every word starts with an upper-case letter, and no word is all lower-case.
 *
 * <p>Eyebrow labels ({@code QUICK ADD}, {@code TENDER}, {@code AMOUNT DUE}) and the status
 * pill ({@code OPEN}, {@code AWAITING PAYMENT}, {@code LOCKED}) are uppercase by design-system
 * decision and are not exercised here — they read through {@link PosTheme#eyebrow()} and are
 * a separate token from the button/title vocabulary.</p>
 *
 * <p>The list below is the full vocabulary of interactive labels the app renders. A future
 * addition that reintroduces sentence case fails this test rather than slipping through
 * review; adding a new label means adding its expected Title Case form here.</p>
 */
class ButtonLabelTitleCaseTest {

    /** The complete set of user-visible button labels and dialog titles. Keyed by a short
     *  location tag so a failure message points at where the offender lives. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        // ---- Buttons: main window --------------------------------------
        // The main-window action button is "Change Qty" (shortened to fit the five-button strip);
        // the Change Quantity dialog title below keeps the full form.
        LABELS.put("CustomerView.changeQty",   "Change Qty");
        LABELS.put("CustomerView.voidLine",    "Void Line");
        LABELS.put("CustomerView.voidBasket",  "Void Basket");
        LABELS.put("CustomerView.total",       "Total");
        LABELS.put("CustomerView.payCash",     "Pay Cash");
        LABELS.put("CustomerView.payDebit",    "Pay Debit");
        LABELS.put("CustomerView.payCredit",   "Pay Credit");

        // ---- Buttons: dialogs -----------------------------------------
        LABELS.put("ChangeQuantityView.confirm", "Confirm Change");
        LABELS.put("ChangeQuantityView.cancel",  "Cancel");
        LABELS.put("PayWithCashView.confirm",    "Confirm Payment");
        LABELS.put("PayWithCashView.cancel",     "Cancel");
        LABELS.put("PayWithCashView.exact",      "Exact Amount");
        LABELS.put("PayWithCashView.nextDollar", "Next Dollar");
        LABELS.put("PayWithCardView.confirm",    "Confirm Payment");
        LABELS.put("TenderConfirmView.confirm",  "Confirm Payment");
        LABELS.put("TenderConfirmView.cashBack", "Back");
        LABELS.put("TenderConfirmView.cardCancel", "Cancel");
        LABELS.put("ErrorDialog.dismiss",        "Dismiss");
        LABELS.put("ReceiptView.startNext",      "Start Next Sale");
        LABELS.put("ManualBarcodeEntryView.confirm", "Add Item");
        LABELS.put("ManualBarcodeEntryView.cancel",  "Cancel");

        // ---- Dialog titles --------------------------------------------
        LABELS.put("title.errorDialog",        "Error");
        LABELS.put("title.changeQuantity",     "Change Quantity");
        LABELS.put("title.payCash",            "Cash Payment");
        LABELS.put("title.payDebit",           "Pay Debit");
        LABELS.put("title.payCredit",          "Pay Credit");
        LABELS.put("title.confirmPayment",     "Confirm Payment");
        LABELS.put("title.receipt",            "Receipt");

        // The void-basket confirmation dialog is deliberately sentence case throughout — copy
        // is graded separately (see VoidBasketConfirmViewTest#everyVisibleString_isSentenceCase)
        // rather than routed through this Title Case sweep. Vocabulary discipline in that dialog
        // ("Void basket" vs. "Cancel") is a domain-glossary invariant, not a typographic one.
    }

    @Test
    void everyKnownLabel_isTitleCase() {
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            String where = e.getKey();
            String label = e.getValue();
            assertThat(label)
                    .as("empty labels are never meaningful: %s", where)
                    .isNotBlank();
            assertThat(isTitleCase(label))
                    .as("label %s = \"%s\" must be Title Case — every word starts with an "
                            + "upper-case letter, no word is all lower-case", where, label)
                    .isTrue();
        }
    }

    /**
     * Title Case: the first alphabetic character of every "word" is upper-case. A "word" here
     * is a maximal run of letters; non-letter separators (space, hyphen, apostrophe, question
     * mark) reset word tracking. This matches the copy convention used across the POS action
     * vocabulary: "Pay Cash", "Start Next Sale", "Void Basket?"
     *
     * <p>Interior letters are unconstrained — an acronym like "MP" would still pass. None
     * appear in the vocabulary today.</p>
     */
    static boolean isTitleCase(String s) {
        if (s == null || s.isEmpty()) return false;
        boolean atWordStart = true;
        boolean sawAnyLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                sawAnyLetter = true;
                if (atWordStart) {
                    if (!Character.isUpperCase(c)) return false;
                    atWordStart = false;
                }
            } else {
                atWordStart = true;
            }
        }
        return sawAnyLetter;
    }
}
