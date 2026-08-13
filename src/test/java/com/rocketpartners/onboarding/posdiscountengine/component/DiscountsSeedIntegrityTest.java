package com.rocketpartners.onboarding.posdiscountengine.component;

import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import com.rocketpartners.onboarding.posdiscountengine.entity.TargetType;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the invariant that a UPC-targeted rule in {@code discounts.csv} names a UPC that actually
 * exists in {@code pricebook.tsv}. A rule pointing at a non-existent UPC would fail silently at
 * evaluation time (the later branch), so we catch it at build time here.
 *
 * <p>Reads {@code pricebook.tsv} directly rather than through {@code possystem}'s parser — the
 * discount engine must not import from {@code possystem} (see {@code CLAUDE.md}).</p>
 */
class DiscountsSeedIntegrityTest {

    @Test
    void everyUpcTargetedRuleResolvesToAKnownPricebookEntry() throws IOException {
        List<DiscountRule> rules = loadSeedRules();
        Set<String> pricebookUpcs = loadPricebookUpcs();

        List<DiscountRule> upcRules = rules.stream()
                .filter(r -> r.getTargetType() == TargetType.UPC)
                .toList();

        assertFalse(upcRules.isEmpty(), "expected at least one UPC-targeted rule in the seed data");
        for (DiscountRule rule : upcRules) {
            assertNotNull(rule.getTargetValue(),
                    "UPC-targeted rule " + rule.getCode() + " must carry a targetValue");
            assertTrue(pricebookUpcs.contains(rule.getTargetValue()),
                    "rule " + rule.getCode() + " targets UPC " + rule.getTargetValue()
                            + " which is not in pricebook.tsv");
        }
    }

    private List<DiscountRule> loadSeedRules() throws IOException {
        try (InputStream in = resource("/discounts.csv");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return CsvDiscountsLoader.parse(reader);
        }
    }

    private Set<String> loadPricebookUpcs() throws IOException {
        Set<String> upcs = new HashSet<>();
        try (InputStream in = resource("/pricebook.tsv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", -1);
                upcs.add(parts[0].trim());
            }
        }
        return upcs;
    }

    private InputStream resource(String path) {
        InputStream in = getClass().getResourceAsStream(path);
        assertNotNull(in, "classpath resource not found: " + path);
        return in;
    }
}
