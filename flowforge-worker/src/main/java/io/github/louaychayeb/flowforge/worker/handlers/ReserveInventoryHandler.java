package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Simulated inventory reservation. Outputs the {@code reservationId}, which {@link ReleaseInventoryHandler} needs to
 * undo it. Derived from the step execution id, so a repeated attempt yields the same reservation.
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
        String orderId = OrderInput.from(context.input()).requireOrderId();
        String reservationId = "res_" + context.stepExecutionId().toString().substring(0, 8);
        log.info("Reserved inventory for order {} (reservation {})", orderId, reservationId);
        return StepHandlerResult.succeeded(Map.of("reservationId", reservationId));
    }
}
