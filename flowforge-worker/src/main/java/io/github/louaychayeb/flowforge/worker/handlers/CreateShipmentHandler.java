package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simulated shipment request. Orders shipped to country {@value #UNSUPPORTED_COUNTRY} are rejected, a
 * permanent failure after inventory and payment succeeded — which triggers compensation of both.
 */
@Component
public class CreateShipmentHandler implements StepHandler {

    static final String UNSUPPORTED_COUNTRY = "XX";

    private static final Logger log = LoggerFactory.getLogger(CreateShipmentHandler.class);

    @Override
    public String stepType() {
        return "CREATE_SHIPMENT";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        if (UNSUPPORTED_COUNTRY.equals(context.input().get("country"))) {
            return StepHandlerResult.permanentFailure("Cannot ship order " + order.orderId() + " to country "
                    + UNSUPPORTED_COUNTRY);
        }
        log.info("Requested shipment for order {}", order.orderId());
        return StepHandlerResult.succeeded();
    }
}
