package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepFailureException;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.worker.payments.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Charges the order through the payment provider. The step execution id is the idempotency key, and it is the
 * same on every attempt and every redelivery — so if this step runs twice (a worker died after charging and
 * before reporting), the provider recognises the second request and the customer is charged once.
 */
@Component
public class ChargePaymentHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(ChargePaymentHandler.class);

    private final PaymentGateway payments;

    public ChargePaymentHandler(PaymentGateway payments) {
        this.payments = payments;
    }

    @Override
    public String stepType() {
        return "CHARGE_PAYMENT";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        String orderId = order.requireOrderId();
        String key = context.stepExecutionId().toString();
        PaymentGateway.Result result = payments.charge(key, orderId, order.requirePositiveAmount());
        switch (result.status()) {
            case REJECTED -> throw new StepFailureException.Permanent(result.message());
            case UNAVAILABLE -> throw new StepFailureException.Retryable(result.message());
            case OK -> {
                if (result.replayed()) {
                    log.warn("Charge for order {} was already made (idempotency key {}): the provider replayed it, "
                            + "the customer was not charged again", orderId, key);
                } else {
                    log.info("Charged {} for order {} (idempotency key {})", order.amount(), orderId, key);
                }
            }
        }
        // Later steps (the confirmation, the refund) can refer to the charge by the provider's id.
        return StepHandlerResult.succeeded(result.reference() == null ? Map.of() : Map.of("chargeId", result.reference()));
    }
}
