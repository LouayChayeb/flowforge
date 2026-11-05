package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compensation for {@link ReserveInventoryHandler}: releases the reservation that step reported in its output.
 */
@Component
public class ReleaseInventoryHandler implements StepHandler {

    static final String RESERVE_STEP = "reserve-inventory";

    private static final Logger log = LoggerFactory.getLogger(ReleaseInventoryHandler.class);

    @Override
    public String stepType() {
        return "RELEASE_INVENTORY";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        String orderId = OrderInput.from(context.input()).requireOrderId();
        // Older runs reserved without reporting an id; releasing by order is still correct for them.
        Object reservationId = context.outputOf(RESERVE_STEP).getOrDefault("reservationId", "order " + orderId);
        log.info("Released inventory reservation {} for order {}", reservationId, orderId);
        return StepHandlerResult.succeeded();
    }
}
