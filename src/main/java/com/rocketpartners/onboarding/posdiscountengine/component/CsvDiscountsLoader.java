package com.rocketpartners.onboarding.posdiscountengine.component;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountCategory;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import com.rocketpartners.onboarding.posdiscountengine.entity.TargetType;
import com.rocketpartners.onboarding.posdiscountengine.repository.DiscountRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the {@code DISCOUNT_RULES} table from the {@code discounts.csv} classpath resource at
 * startup, so the database — not code — is the authority on what rules exist.
 *
 * <p>Parsing is deliberately lenient: a malformed row is logged and skipped rather than aborting
 * the whole seed, because one bad line in a data file should not take the service down. (Contrast
 * with {@code PricebookTsv}, where a malformed row is fatal — that file is smaller and hand-curated.)
 * The parse step is exposed as a static, Spring-free method so it can be unit-tested directly.</p>
 */
@Slf4j
@Component
public class CsvDiscountsLoader implements CommandLineRunner {

    static final String RESOURCE_PATH = "discounts.csv";

    /** Number of columns every well-formed data row must have. */
    static final int EXPECTED_COLUMNS = 12;

    private final DiscountRuleRepository repository;

    public CsvDiscountsLoader(DiscountRuleRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) throws IOException {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("discounts seed resource not found: " + RESOURCE_PATH);
        }
        List<DiscountRule> rules;
        try (InputStream in = resource.getInputStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            rules = parse(reader);
        }

        int seeded = 0;
        for (DiscountRule rule : rules) {
            // Idempotent across restarts: only insert a code we haven't seen before.
            if (repository.findByCode(rule.getCode()).isEmpty()) {
                repository.save(rule);
                seeded++;
            }
        }
        log.info("Discount seed complete: parsed {} rule(s), inserted {} new.", rules.size(), seeded);
    }

    /**
     * Parses discount rules from a CSV reader. The first non-blank line is treated as a header and
     * skipped. Blank lines and rows that fail to parse (wrong column count, unknown enum value,
     * unparseable number) are logged and skipped; every valid row is returned.
     *
     * <p>Static and Spring-free on purpose — unit tests exercise it directly.</p>
     */
    static List<DiscountRule> parse(Reader reader) throws IOException {
        List<DiscountRule> rules = new ArrayList<>();
        try (BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            int lineNumber = 0;
            boolean headerSeen = false;
            while ((line = buffered.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                if (!headerSeen) {
                    headerSeen = true; // first non-blank line is the header
                    continue;
                }
                DiscountRule rule = parseRow(line, lineNumber);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    private static DiscountRule parseRow(String line, int lineNumber) {
        String[] parts = line.split(",", -1);
        if (parts.length != EXPECTED_COLUMNS) {
            log.warn("Skipping malformed discount row at line {}: expected {} columns, got {}",
                    lineNumber, EXPECTED_COLUMNS, parts.length);
            return null;
        }
        try {
            return DiscountRule.builder()
                    .code(required(parts[0], "code"))
                    .description(required(parts[1], "description"))
                    .category(DiscountCategory.valueOf(required(parts[2], "category")))
                    .targetType(TargetType.valueOf(required(parts[3], "targetType")))
                    .targetValue(blankToNull(parts[4]))
                    .discountType(DiscountType.valueOf(required(parts[5], "discountType")))
                    .amount(parseAmount(parts[6]))
                    .buyQuantity(parseInteger(parts[7]))
                    .getQuantity(parseInteger(parts[8]))
                    .priority(Integer.parseInt(required(parts[9], "priority").trim()))
                    .exclusivityGroup(blankToNull(parts[10]))
                    .active(Boolean.parseBoolean(required(parts[11], "active").trim()))
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Skipping malformed discount row at line {}: {}", lineNumber, e.getMessage());
            return null;
        }
    }

    private static String required(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing required field '" + field + "'");
        }
        return value;
    }

    private static String blankToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal parseAmount(String raw) {
        String value = blankToNull(raw);
        return value == null ? null : new BigDecimal(value);
    }

    private static Integer parseInteger(String raw) {
        String value = blankToNull(raw);
        return value == null ? null : Integer.valueOf(value);
    }
}
