package io.github.louaychayeb.flowforge.orchestrator.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Rejects orders with missing fields or a non-positive amount. Invalid input will not become valid
 * on retry, so these are permanent failures.
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
        if (order.orderId() == null || order.orderId().isBlank()) {
            return StepHandlerResult.permanentFailure("orderId is required");
        }
        if (order.customerId() == null || order.customerId().isBlank()) {
            return StepHandlerResult.permanentFailure("customerId is required");
        }
        if (order.amount() == null || order.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return StepHandlerResult.permanentFailure("amount must be a positive number");
        }
        return StepHandlerResult.succeeded();
    }
}
