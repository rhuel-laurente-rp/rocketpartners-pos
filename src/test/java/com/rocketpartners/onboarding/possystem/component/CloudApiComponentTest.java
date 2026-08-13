package com.rocketpartners.onboarding.possystem.component;

import com.rocketpartners.onboarding.commons.dto.LineItemDto;
import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full HTTP path — request, status, body, parse, and validation — against a real
 * local {@link HttpServer}, plus the resilience contract when the engine is unreachable or lies.
 */
class CloudApiComponentTest {

    private HttpServer server;
    private CloudApiComponent api;

    @AfterEach
    void tearDown() {
        if (api != null) api.close();
        if (server != null) server.stop(0);
    }

    /** Starts a server that answers every request with the given status and body, and points the client at it. */
    private void serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        api = new CloudApiComponent("http://localhost:" + server.getAddress().getPort());
    }

    private static TransactionDto request(String subtotal) {
        return new TransactionDto("t1", null,
                List.of(new LineItemDto("UPC-X", "Thing", 1, new BigDecimal(subtotal))),
                new BigDecimal(subtotal), List.of());
    }

    @Test
    void fetchRules_parsesFieldsAndIgnoresUnknowns() throws IOException {
        serve(200, "[{\"id\":1,\"code\":\"SENIOR_20\",\"description\":\"Senior 20%\","
                + "\"category\":\"ELIGIBILITY\",\"targetType\":\"TRANSACTION\","
                + "\"discountType\":\"PERCENT_OFF\",\"amount\":20,"
                + "\"exclusivityGroup\":\"CUSTOMER_ELIGIBILITY\",\"active\":true,\"priority\":2}]");

        CloudApiComponent.RulesResult result = api.fetchEligibilityRules();

        assertThat(result.ok()).isTrue();
        assertThat(result.rules()).hasSize(1);
        EligibilityRule rule = result.rules().get(0);
        assertThat(rule.code()).isEqualTo("SENIOR_20");
        assertThat(rule.discountType()).isEqualTo(DiscountType.PERCENT_OFF);
        assertThat(rule.amount()).isEqualByComparingTo("20");
        assertThat(rule.exclusivityGroup()).isEqualTo("CUSTOMER_ELIGIBILITY");
    }

    @Test
    void calculate_success_scalesAppliedAndNormalisesNullAmount() throws IOException {
        // One PERCENT_OFF with an odd-scale appliedAmount, one PROMO whose amount is null.
        serve(200, "{\"discounts\":["
                + "{\"discountId\":\"SENIOR_20\",\"description\":\"Senior 20%\",\"type\":\"PERCENT_OFF\","
                + "\"amount\":20,\"appliedAmount\":3.306},"
                + "{\"discountId\":\"BOGO\",\"description\":\"BOGO\",\"type\":\"PROMO\","
                + "\"amount\":null,\"appliedAmount\":2.00}],\"discountTotal\":5.31}");

        CloudApiComponent.CalculateResult result = api.calculate(request("26.53"));

        assertThat(result.ok()).isTrue();
        assertThat(result.discounts()).hasSize(2);
        Discount senior = result.discounts().get(0);
        // appliedAmount scaled to 2 decimals (3.306 -> 3.31).
        assertThat(senior.getAppliedAmount()).isEqualByComparingTo("3.31");
        assertThat(senior.getAppliedAmount().scale()).isEqualTo(2);
        // A null PROMO amount is normalised to ZERO so nothing downstream hits a null.
        Discount promo = result.discounts().get(1);
        assertThat(promo.getAmount()).isEqualByComparingTo("0");
    }

    @Test
    void calculate_nullAppliedAmount_rejectsWholeResponse() throws IOException {
        serve(200, "{\"discounts\":[{\"discountId\":\"X\",\"description\":\"X\",\"type\":\"PERCENT_OFF\","
                + "\"amount\":20,\"appliedAmount\":null}],\"discountTotal\":0}");
        assertThat(api.calculate(request("26.53")).ok()).isFalse();
    }

    @Test
    void calculate_negativeAppliedAmount_rejectsWholeResponse() throws IOException {
        serve(200, "{\"discounts\":[{\"discountId\":\"X\",\"description\":\"X\",\"type\":\"PERCENT_OFF\","
                + "\"amount\":20,\"appliedAmount\":-1.00}],\"discountTotal\":-1.00}");
        assertThat(api.calculate(request("26.53")).ok()).isFalse();
    }

    @Test
    void calculate_discountTotalExceedingSubtotal_rejectsWholeResponse() throws IOException {
        serve(200, "{\"discounts\":[{\"discountId\":\"X\",\"description\":\"X\",\"type\":\"FIXED_AMOUNT_OFF\","
                + "\"amount\":10,\"appliedAmount\":10.00}],\"discountTotal\":10.00}");
        // Subtotal is only 5.00 — a total exceeding it is a broken engine; reject the whole response.
        assertThat(api.calculate(request("5.00")).ok()).isFalse();
    }

    @Test
    void calculate_nonTwoHundred_isFailure() throws IOException {
        serve(500, "boom");
        assertThat(api.calculate(request("26.53")).ok()).isFalse();
    }

    @Test
    void calculate_emptyDiscounts_isSuccessNotFailure() throws IOException {
        serve(200, "{\"discounts\":[],\"discountTotal\":0}");
        CloudApiComponent.CalculateResult result = api.calculate(request("26.53"));
        assertThat(result.ok()).isTrue();
        assertThat(result.discounts()).isEmpty();
    }

    @Test
    void engineUnreachable_isFailureNotException() {
        // Point at a port nothing is listening on — a connection refused must return a failed
        // result, never throw into checkout.
        api = new CloudApiComponent("http://localhost:1");
        assertThat(api.calculate(request("26.53")).ok()).isFalse();
        assertThat(api.fetchEligibilityRules().ok()).isFalse();
    }
}
