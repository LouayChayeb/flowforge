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
 * Simulated fraud screening. In the order workflow it runs in parallel with the inventory reservation: both only
 * need a valid order, and the charge waits for both.
 *
 * <p>Demo options: {@code fraud: true} rejects the order permanently — while the reservation may still be running,
 * which shows how a parallel failure waits for its siblings before compensating — and {@code fraudCheckDelayMs}
 * makes the check slow (capped at {@value #MAX_DELAY_MS} ms), so the two parallel steps visibly overlap.
 */
@Component
public class CheckFraudHandler implements StepHandler {

    static final long MAX_DELAY_MS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(CheckFraudHandler.class);

    @Override
    public String stepType() {
        return "CHECK_FRAUD";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        Object delay = context.input().get("fraudCheckDelayMs");
        if (delay instanceof Number millis && millis.longValue() > 0) {
            try {
                Thread.sleep(Math.min(millis.longValue(), MAX_DELAY_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepFailureException.Retryable("Interrupted during the fraud check", e);
            }
        }
        Object fraud = context.input().get("fraud");
        if (Boolean.TRUE.equals(fraud) || "true".equals(fraud)) {
            throw new StepFailureException.Permanent("Order " + order.orderId() + " flagged as fraudulent");
        }
        log.info("Fraud check passed for order {}", order.orderId());
        return StepHandlerResult.succeeded(Map.of("riskScore", 0.07));
    }
}
