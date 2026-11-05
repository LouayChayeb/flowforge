package io.github.louaychayeb.flowforge.worker.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Calls the payment provider ({@code flowforge-payments}) over HTTP, the way a real integration would: the step
 * execution id goes in the {@code Idempotency-Key} header, and the response status decides whether the step
 * succeeded, failed for good, or should be retried.
 */
public class HttpPaymentGateway implements PaymentGateway {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;

    public HttpPaymentGateway(RestClient.Builder builder, String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Result charge(String idempotencyKey, String orderId, BigDecimal amount) {
        return call("/v1/charges", idempotencyKey, Map.of("orderId", orderId, "amount", amount));
    }

    @Override
    public Result refund(String idempotencyKey, String chargeIdempotencyKey) {
        return call("/v1/refunds", idempotencyKey, Map.of("chargeIdempotencyKey", chargeIdempotencyKey));
    }

    private Result call(String path, String key, Map<String, Object> body) {
        try {
            return client.post().uri(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        boolean replayed = "true".equals(response.getHeaders().getFirst("Idempotent-Replayed"));
                        String text = new String(response.getBody().readAllBytes());
                        if (status.is2xxSuccessful()) {
                            return new Result(Status.OK, replayed, status.value() + (replayed ? " replayed" : ""),
                                    field(text, "chargeId", "refundId"));
                        }
                        if (status.value() == 402) {
                            return new Result(Status.REJECTED, replayed, "Payment declined: " + detail(text));
                        }
                        if (status.is4xxClientError()) {
                            return new Result(Status.REJECTED, false, "Payment provider refused: " + detail(text));
                        }
                        return new Result(Status.UNAVAILABLE, false, "Payment provider error " + status.value());
                    });
        } catch (ResourceAccessException e) {
            // Timeout or connection refused. The charge may or may not have happened; retrying with the same key
            // is safe either way, which is the whole point of the key.
            return new Result(Status.UNAVAILABLE, false, "Payment provider unreachable: " + e.getMessage());
        }
    }

    /** The first of {@code names} present in a JSON body, or {@code null}. */
    private static String field(String body, String... names) {
        try {
            JsonNode json = JSON.readTree(body);
            for (String name : names) {
                if (json.hasNonNull(name)) {
                    return json.get(name).asText();
                }
            }
        } catch (IOException e) {
            // not JSON: no reference
        }
        return null;
    }

    /** The decline reason of a charge, or the {@code detail} of a problem response. */
    private static String detail(String body) {
        try {
            JsonNode json = JSON.readTree(body);
            for (String field : new String[] {"declineReason", "detail"}) {
                if (json.hasNonNull(field)) {
                    return json.get(field).asText();
                }
            }
        } catch (IOException e) {
            // not JSON: fall through
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
