package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simulated inventory reservation.
 */
@Component
public class ReserveInventoryHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(ReserveInventoryHandler.class);

    @Override
    public String stepType() {
        return "RESERVE_INVENTORY";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        log.info("Reserved inventory for order {} (reservation key {})", order.orderId(), context.stepExecutionId());
        return StepHandlerResult.succeeded();
    }
}
