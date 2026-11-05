package io.github.louaychayeb.flowforge.orchestrator.insight;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Reads the payment provider's ledger for the dashboard, so it can show what the provider saw: how many charges,
 * how many duplicate requests it absorbed, and that no order was charged twice. The orchestrator itself never
 * talks to the provider; only workers do.
 */
@Component
public class PaymentsInspector {

    private final String baseUrl;
    private final RestClient client;

    public PaymentsInspector(@Value("${flowforge.insight.payments-url:}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = baseUrl.isBlank() ? null : RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record PaymentsView(boolean configured, boolean reachable, String url, String error, JsonNode summary,
                               JsonNode charges) {
    }

    /** Totals, plus the charges of one order if {@code orderId} is given. */
    public PaymentsView view(String orderId) {
        if (client == null) {
            return new PaymentsView(false, false, null, "flowforge.insight.payments-url is not set", null, null);
        }
        try {
            JsonNode summary = client.get().uri("/v1/summary").retrieve().body(JsonNode.class);
            JsonNode charges = orderId == null ? null : client.get()
                    .uri("/v1/charges?orderId={orderId}", orderId)
                    .retrieve().body(JsonNode.class);
            return new PaymentsView(true, true, baseUrl, null, summary, charges);
        } catch (RestClientException e) {
            return new PaymentsView(true, false, baseUrl, e.getMessage(), null, null);
        }
    }
}
