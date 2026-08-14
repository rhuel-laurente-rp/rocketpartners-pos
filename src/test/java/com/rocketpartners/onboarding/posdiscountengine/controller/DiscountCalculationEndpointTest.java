package com.rocketpartners.onboarding.posdiscountengine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rocketpartners.onboarding.commons.dto.LineItemDto;
import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of {@code POST /discounts/calculate} against the real seeded H2
 * ({@code SENIOR_20}, {@code BOGO_MONSTER} on UPC {@code 070847811169} @ 3.29).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiscountCalculationEndpointTest {

    private static final String MONSTER_UPC = "070847811169";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void calculate_appliesBogoThenSenior_withExactRunningNetFigures() throws Exception {
        TransactionDto request = new TransactionDto("t1", null,
                List.of(new LineItemDto(MONSTER_UPC, "Monster", 7, new BigDecimal("3.29"))),
                null, List.of("SENIOR_20"));

        MvcResult result = mockMvc.perform(post("/discounts/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode discounts = root.get("discounts");

        // Application order: promotional (priority 1) then eligibility (priority 2).
        assertEquals(2, discounts.size());
        assertEquals("BOGO_MONSTER", discounts.get(0).get("discountId").asText());
        assertEquals("SENIOR_20", discounts.get(1).get("discountId").asText());

        // BOGO: 2 free units x 3.29 = 6.58; net = 23.03 - 6.58 = 16.45.
        assertMoney("6.58", discounts.get(0).get("appliedAmount"));
        // Senior: 20% of 16.45 = 3.29 (NOT 20% of the 23.03 subtotal, which would be 4.61).
        assertMoney("3.29", discounts.get(1).get("appliedAmount"));
        assertMoney("9.87", root.get("discountTotal"));
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/discounts/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownEligibilityCode_returns400() throws Exception {
        TransactionDto request = new TransactionDto("t1", null,
                List.of(new LineItemDto(MONSTER_UPC, "Monster", 1, new BigDecimal("3.29"))),
                null, List.of("NOT_A_REAL_CODE"));

        mockMvc.perform(post("/discounts/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conflictingExclusivityCodes_returns400() throws Exception {
        // SENIOR_20 and EMPLOYEE_5 both belong to the CUSTOMER_ELIGIBILITY exclusivity group.
        TransactionDto request = new TransactionDto("t1", null,
                List.of(new LineItemDto(MONSTER_UPC, "Monster", 1, new BigDecimal("3.29"))),
                null, List.of("SENIOR_20", "EMPLOYEE_5"));

        mockMvc.perform(post("/discounts/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyBasket_returnsEmptyDiscountList() throws Exception {
        TransactionDto request = new TransactionDto("t1", null, List.of(), null, List.of());

        MvcResult result = mockMvc.perform(post("/discounts/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("discounts").isEmpty(), "empty basket yields no discounts");
        assertMoney("0.00", root.get("discountTotal"));
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())),
                "expected " + expected + " but was " + actual.asText());
    }
}
