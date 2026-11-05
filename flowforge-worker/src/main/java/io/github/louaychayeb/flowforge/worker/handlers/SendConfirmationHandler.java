package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Simulated customer notification. Uses the outputs of earlier steps — the charge id and the tracking number — which
 * is exactly what step outputs are for.
 */
@Component
public class SendConfirmationHandler implements StepHandler {

    static final String CHARGE_STEP = "charge-payment";
    static final String SHIPMENT_STEP = "create-shipment";

    private static final Logger log = LoggerFactory.getLogger(SendConfirmationHandler.class);

    @Override
    public String stepType() {
        return "SEND_CONFIRMATION";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        Object trackingNumber = context.requireOutput(SHIPMENT_STEP, "trackingNumber");
        Object chargeId = context.outputOf(CHARGE_STEP).get("chargeId");
        log.info("Sent confirmation for order {} to customer {}: charge {}, tracking {}", order.requireOrderId(),
                order.requireCustomerId(), chargeId, trackingNumber);
        return StepHandlerResult.succeeded(Map.of("notified", order.requireCustomerId()));
    }
}
