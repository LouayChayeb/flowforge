package io.github.louaychayeb.flowforge.orchestrator.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simulated customer notification.
 */
@Component
public class SendConfirmationHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(SendConfirmationHandler.class);

    @Override
    public String stepType() {
        return "SEND_CONFIRMATION";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        log.info("Sent confirmation for order {} to customer {}", order.orderId(), order.customerId());
        return StepHandlerResult.succeeded();
    }
}
