package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compensation for {@link ChargePaymentHandler}: refunds the simulated charge.
 */
@Component
public class RefundPaymentHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundPaymentHandler.class);

    @Override
    public String stepType() {
        return "REFUND_PAYMENT";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        // Like the charge, a real refund call would carry the step execution id as its idempotency key.
        log.info("Refunded {} for order {} (idempotency key {})", order.amount(), order.orderId(),
                context.stepExecutionId());
        return StepHandlerResult.succeeded();
    }
}
