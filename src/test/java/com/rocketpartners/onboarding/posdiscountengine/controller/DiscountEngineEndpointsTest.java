package com.rocketpartners.onboarding.posdiscountengine.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the whole discount engine (which seeds H2 from {@code discounts.csv} via
 * {@link com.rocketpartners.onboarding.posdiscountengine.component.CsvDiscountsLoader}) on a random
 * port and exercises both HTTP endpoints end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscountEngineEndpointsTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void health_returns200() {
        ResponseEntity<String> response = rest.getForEntity("/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() != null && response.getBody().contains("UP"));
    }

    @Test
    void rulesEndpoint_returnsOnlyActiveRulesOfRequestedCategory() {
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                "/discounts/rules?category=ELIGIBILITY",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> rules = response.getBody();
        assertFalse(rules == null || rules.isEmpty(), "expected the seeded eligibility rules");

        for (Map<String, Object> rule : rules) {
            assertEquals("ELIGIBILITY", rule.get("category"), "only ELIGIBILITY rules expected");
            assertEquals(Boolean.TRUE, rule.get("active"), "only active rules expected");
        }

        List<Object> codes = rules.stream().map(r -> r.get("code")).toList();
        assertTrue(codes.contains("SENIOR_20"), "seed senior rule should be present");
        assertTrue(codes.contains("VETERAN_15"), "seed veteran rule should be present");
        assertFalse(codes.contains("BOGO_MONSTER"), "promotional rule must not appear under ELIGIBILITY");
    }
}
