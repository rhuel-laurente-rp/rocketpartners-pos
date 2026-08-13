package com.rocketpartners.onboarding.possystem.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import com.rocketpartners.onboarding.commons.model.Discount;
import com.rocketpartners.onboarding.commons.model.DiscountType;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.Closeable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The POS's HTTP client to the discount engine. This is the <em>only</em> seam between the POS and
 * Phase 3: the two share a classpath, but nothing here (or anywhere in {@code possystem}) imports a
 * {@code posdiscountengine} type. A direct method call would pass every local test and then fail
 * against the deployed load balancer, and would make the containerise-and-deploy exercise pointless.
 *
 * <p><strong>Resilience, modelled on {@link RemoteJournal}.</strong> Every failure mode — connect
 * timeout, connection refused, non-2xx status, malformed body, deserialisation error, or a
 * validation rejection — is caught and turned into an empty/failed result rather than an exception
 * thrown into the checkout flow. A discount engine being down must never stop a sale; it just means
 * no discounts. Connect and response timeouts are both {@value #TIMEOUT_MS} ms.</p>
 *
 * <p><strong>The engine's output is untrusted.</strong> It is a separate service, so
 * {@link #calculate(TransactionDto)} validates every returned discount: a null or negative
 * {@code appliedAmount} rejects the whole response, each {@code appliedAmount} is scaled to two
 * decimals, a null rule {@code amount} (legal for PROMO) is normalised to
 * {@link BigDecimal#ZERO} so nothing downstream hits a null, and a discount total exceeding the
 * request subtotal rejects the whole response. Responses are parsed by hand from a {@link JsonNode}
 * tree rather than bound to {@link Discount} directly — {@code Discount} is an immutable Lombok
 * {@code @Value} with no default constructor, which a plain {@link ObjectMapper} cannot reliably
 * construct, and hand-parsing is where the validation naturally lives anyway.</p>
 */
public class CloudApiComponent implements Closeable {

    /** Connect and response timeout, in milliseconds. Kept a hair over the engine's own budget. */
    public static final int TIMEOUT_MS = 2_000;

    private static final int MONEY_SCALE = 2;

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final CloseableHttpClient client;

    /**
     * @param baseUrl the discount engine base URL (e.g. {@code http://localhost:8080}); must not be
     *                {@code null}. A trailing slash is tolerated.
     */
    public CloudApiComponent(String baseUrl) {
        if (baseUrl == null) throw new IllegalArgumentException("baseUrl must not be null");
        this.baseUrl = stripTrailingSlash(baseUrl);
        // findAndRegisterModules() picks up JavaTimeModule from the classpath so TransactionDto's
        // Instant createdAt serialises cleanly; the engine ignores that field either way.
        this.mapper = new ObjectMapper().findAndRegisterModules();

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(TIMEOUT_MS))
                .setSocketTimeout(Timeout.ofMilliseconds(TIMEOUT_MS))
                .build();
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(TIMEOUT_MS))
                .setResponseTimeout(Timeout.ofMilliseconds(TIMEOUT_MS))
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    // ---- Result types ------------------------------------------------------

    /**
     * Outcome of a rules fetch. {@code error} is {@code null} on success; the list is always
     * non-null (empty on failure) so callers can iterate without a null check.
     */
    public record RulesResult(List<EligibilityRule> rules, String error) {
        public boolean ok() {
            return error == null;
        }

        static RulesResult ok(List<EligibilityRule> rules) {
            return new RulesResult(rules, null);
        }

        static RulesResult fail(String error) {
            return new RulesResult(List.of(), error);
        }
    }

    /**
     * Outcome of a discount calculation. {@code ok} distinguishes a successful (possibly empty)
     * calculation from a failure — a failure is what drives the "Discounts Unavailable" path, while
     * a successful empty list simply means no discounts applied.
     */
    public record CalculateResult(boolean ok, List<Discount> discounts, String error) {
        public static CalculateResult ok(List<Discount> discounts) {
            return new CalculateResult(true, discounts, null);
        }

        public static CalculateResult fail(String error) {
            return new CalculateResult(false, List.of(), error);
        }
    }

    // ---- Endpoints ---------------------------------------------------------

    /**
     * Fetches the active {@code ELIGIBILITY} rules the cashier dialog offers. Called once at
     * startup; the caller caches the result. Returns a failed result (with a debug reason) on any
     * error rather than throwing.
     */
    public RulesResult fetchEligibilityRules() {
        String url = baseUrl + "/discounts/rules?category="
                + URLEncoder.encode("ELIGIBILITY", StandardCharsets.UTF_8);
        try {
            String body = execute(new HttpGet(url));
            List<EligibilityRule> rules = parseRules(body);
            return RulesResult.ok(rules);
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[discount-engine] rules fetch failed: " + reason);
            return RulesResult.fail(reason);
        }
    }

    /**
     * Posts the transaction to {@code /discounts/calculate} and returns the validated discounts to
     * apply, in the order the engine returned them. Returns a failed result on any transport,
     * status, parse, or validation error.
     *
     * @param request the transaction plus selected eligibility codes; must not be {@code null}
     */
    public CalculateResult calculate(TransactionDto request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        try {
            HttpPost post = new HttpPost(baseUrl + "/discounts/calculate");
            String json = mapper.writeValueAsString(request);
            post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            String body = execute(post);
            return parseAndValidateCalculation(body, subtotalOf(request));
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[discount-engine] calculate failed: " + reason);
            return CalculateResult.fail(reason);
        }
    }

    // ---- HTTP --------------------------------------------------------------

    /** Executes a request, returning the body on 2xx and throwing otherwise. */
    private String execute(org.apache.hc.client5.http.classic.methods.HttpUriRequestBase request)
            throws java.io.IOException {
        return client.execute(request, response -> {
            int code = response.getCode();
            String body = response.getEntity() == null
                    ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code);
            }
            return body;
        });
    }

    // ---- Parsing / validation ---------------------------------------------

    private List<EligibilityRule> parseRules(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        List<EligibilityRule> rules = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode node : root) {
                String code = text(node, "code");
                if (code == null || code.isBlank()) {
                    continue; // a rule with no code is unusable by the dialog
                }
                rules.add(new EligibilityRule(
                        code,
                        text(node, "description"),
                        parseType(node.get("discountType")),
                        decimalOrNull(node.get("amount")),
                        text(node, "exclusivityGroup")));
            }
        }
        return rules;
    }

    /**
     * Parses and validates a {@code /discounts/calculate} response. Any invalid discount rejects the
     * whole response — a partial apply could break the engine's carefully-ordered running-net
     * sequencing, so it is safer to apply nothing than to apply some.
     */
    private CalculateResult parseAndValidateCalculation(String body, BigDecimal subtotal)
            throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode discountsNode = root == null ? null : root.get("discounts");
        List<Discount> discounts = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        if (discountsNode != null && discountsNode.isArray()) {
            for (JsonNode node : discountsNode) {
                JsonNode appliedNode = node.get("appliedAmount");
                if (appliedNode == null || appliedNode.isNull()) {
                    return CalculateResult.fail("discount with null appliedAmount");
                }
                BigDecimal applied;
                try {
                    applied = new BigDecimal(appliedNode.asText());
                } catch (NumberFormatException nfe) {
                    return CalculateResult.fail("unparseable appliedAmount: " + appliedNode.asText());
                }
                if (applied.signum() < 0) {
                    return CalculateResult.fail("negative appliedAmount: " + applied);
                }
                applied = applied.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                // amount may legitimately be null/zero for PROMO — normalise so no downstream
                // receipt or summary line hits a null.
                BigDecimal amount = decimalOrNull(node.get("amount"));
                if (amount == null) {
                    amount = BigDecimal.ZERO;
                }
                discounts.add(new Discount(
                        text(node, "discountId"),
                        text(node, "description"),
                        parseType(node.get("type")),
                        amount,
                        applied));
                total = total.add(applied);
            }
        }
        if (subtotal != null && total.compareTo(subtotal) > 0) {
            return CalculateResult.fail("discount total " + total + " exceeds subtotal " + subtotal);
        }
        return CalculateResult.ok(discounts);
    }

    private static BigDecimal subtotalOf(TransactionDto request) {
        return request.getSubtotal();
    }

    private static DiscountType parseType(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return DiscountType.valueOf(node.asText());
        } catch (IllegalArgumentException e) {
            return null; // unknown type is tolerated: only appliedAmount/description are used downstream
        }
    }

    private static String text(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private static BigDecimal decimalOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        try {
            return new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // Best-effort.
        }
    }
}
