package io.github.louaychayeb.flowforge.orchestrator.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Simulated payment charge. Amounts above {@value #DECLINE_ABOVE} are declined, which is a permanent
 * failure, so the decline path can be demonstrated.
 */
@Component
public class ChargePaymentHandler implements StepHandler {

    static final String DECLINE_ABOVE = "10000";

    private static final Logger log = LoggerFactory.getLogger(ChargePaymentHandler.class);

    @Override
    public String stepType() {
        return "CHARGE_PAYMENT";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        if (order.amount() != null && order.amount().compareTo(new BigDecimal(DECLINE_ABOVE)) > 0) {
            return StepHandlerResult.permanentFailure("Payment declined: amount exceeds " + DECLINE_ABOVE);
        }
        // The step execution id is stable across retries, so a real payment provider would receive it
        // as the idempotency key and never charge the same step twice.
        log.info("Charged {} for order {} (idempotency key {})", order.amount(), order.orderId(),
                context.stepExecutionId());
        return StepHandlerResult.succeeded();
    }
}
