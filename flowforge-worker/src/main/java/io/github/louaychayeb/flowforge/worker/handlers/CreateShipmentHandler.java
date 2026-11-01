package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simulated shipment request.
 */
@Component
public class CreateShipmentHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateShipmentHandler.class);

    @Override
    public String stepType() {
        return "CREATE_SHIPMENT";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        log.info("Requested shipment for order {}", order.orderId());
        return StepHandlerResult.succeeded();
    }
}
