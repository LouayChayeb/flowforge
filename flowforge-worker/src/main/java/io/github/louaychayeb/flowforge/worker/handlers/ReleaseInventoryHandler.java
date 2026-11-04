package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compensation for {@link ReserveInventoryHandler}: releases the simulated reservation.
 */
@Component
public class ReleaseInventoryHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(ReleaseInventoryHandler.class);

    @Override
    public String stepType() {
        return "RELEASE_INVENTORY";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        log.info("Released inventory reservation for order {}", order.orderId());
        return StepHandlerResult.succeeded();
    }
}
