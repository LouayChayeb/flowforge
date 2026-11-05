package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepFailureException;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Simulated shipment request. Orders shipped to country {@value #UNSUPPORTED_COUNTRY} are rejected, a
 * permanent failure after inventory and payment succeeded — which triggers compensation of both.
 *
 * <p>Two optional input fields make the other failure modes visible in a demo:
 * <ul>
 *   <li>{@code shipmentFailures: n} — the carrier "times out" on the first {@code n} attempts, a retryable
 *       failure. With {@code n} below the step's attempt limit the step succeeds after retries; with more, the
 *       attempts run out and the run is compensated.</li>
 *   <li>{@code shipmentDelayMs: ms} — the call takes this long (capped at {@value #MAX_DELAY_MS} ms), leaving
 *       time to watch the attempt's lease or to kill the worker mid-step.</li>
 * </ul>
 */
@Component
public class CreateShipmentHandler implements StepHandler {

    static final String UNSUPPORTED_COUNTRY = "XX";
    static final long MAX_DELAY_MS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(CreateShipmentHandler.class);

    @Override
    public String stepType() {
        return "CREATE_SHIPMENT";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        long delay = Math.min(asLong(context.input().get("shipmentDelayMs")), MAX_DELAY_MS);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepFailureException.Retryable("Interrupted while waiting for the carrier", e);
            }
        }
        if (context.attempt() <= asLong(context.input().get("shipmentFailures"))) {
            throw new StepFailureException.Retryable("Carrier API timed out (attempt " + context.attempt() + ")");
        }
        if (UNSUPPORTED_COUNTRY.equals(context.input().get("country"))) {
            throw new StepFailureException.Permanent("Cannot ship order " + order.orderId() + " to country "
                    + UNSUPPORTED_COUNTRY);
        }
        String trackingNumber = "TRK" + context.stepExecutionId().toString().substring(0, 8).toUpperCase();
        log.info("Requested shipment for order {} (tracking {})", order.requireOrderId(), trackingNumber);
        return StepHandlerResult.succeeded(Map.of("trackingNumber", trackingNumber));
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
