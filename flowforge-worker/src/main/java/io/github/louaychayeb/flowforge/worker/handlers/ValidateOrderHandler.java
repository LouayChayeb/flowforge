package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.springframework.stereotype.Component;

/**
 * Rejects orders with missing fields or a non-positive amount. Invalid input will not become valid on retry, so the
 * {@code require…} accessors fail it permanently.
 */
@Component
public class ValidateOrderHandler implements StepHandler {

    @Override
    public String stepType() {
        return "VALIDATE_ORDER";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        order.requireOrderId();
        order.requireCustomerId();
        order.requirePositiveAmount();
        return StepHandlerResult.succeeded();
    }
}
