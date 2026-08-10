package com.rocketpartners.onboarding.possystem.repository;

import com.rocketpartners.onboarding.commons.model.Item;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@link UpcResolver} ladder against ambiguous pricebook data. Two entries that
 * collide after any rung of normalisation would give us a lookup whose result depends on
 * iteration order — "which item gets rung up" would be undefined.
 *
 * <p>Also pins the invariant that every UPC in the pricebook is a non-empty digit-only
 * string. The parser doesn't enforce that today; this test does.</p>
 */
class PricebookCollisionTest {

    @Test
    void everyUpc_isNonEmptyDigitString() {
        Map<String, Item> items = PricebookTsv.loadFromClasspath("/pricebook.tsv");
        for (String upc : items.keySet()) {
            assertThat(upc).as("UPC must be non-empty").isNotEmpty();
            for (int i = 0; i < upc.length(); i++) {
                char c = upc.charAt(i);
                assertThat(c >= '0' && c <= '9')
                        .as("UPC '%s' must be digits only", upc)
                        .isTrue();
            }
        }
    }

    @Test
    void noTwoUpcs_collideUnderNormalisation() {
        Map<String, Item> items = PricebookTsv.loadFromClasspath("/pricebook.tsv");

        // For each pricebook UPC, record every key it could match through the ladder — its
        // exact form, plus the leading-zero-stripped form. If two different pricebook UPCs
        // ever map to the same normalised key at ANY rung, the ladder becomes ambiguous.
        Map<String, String> keyToUpc = new HashMap<>();
        for (String upc : items.keySet()) {
            Set<String> keys = normalisedForms(upc);
            for (String key : keys) {
                String prior = keyToUpc.put(key, upc);
                if (prior != null && !prior.equals(upc)) {
                    throw new AssertionError(
                            "Pricebook UPCs '" + prior + "' and '" + upc
                                    + "' collide on normalised key '" + key + "'. "
                                    + "The UpcResolver ladder would be ambiguous — "
                                    + "which item gets rung up depends on iteration order.");
                }
            }
        }
    }

    private static Set<String> normalisedForms(String upc) {
        Set<String> forms = new HashSet<>();
        forms.add(upc);
        String stripped = UpcResolver.stripLeadingZeros(upc);
        if (!stripped.isEmpty()) forms.add(stripped);
        return forms;
    }
}
