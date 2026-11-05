package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.worker.payments.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compensation for {@link ChargePaymentHandler}: refunds the charge. It knows which charge to refund because a
 * compensation command carries the id of the step it undoes — the charge's idempotency key — and it uses its own
 * step execution id as the refund's key, so it too can safely run twice.
 *
 * <p>With {@code refundFails: true} in the input the refund is rejected permanently, so a demo can show the one
 * outcome the engine cannot fix by itself: {@code COMPENSATION_FAILED}, which needs a human.
 */
@Component
public class RefundPaymentHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundPaymentHandler.class);

    private final PaymentGateway payments;

    public RefundPaymentHandler(PaymentGateway payments) {
        this.payments = payments;
    }

    @Override
    public String stepType() {
        return "REFUND_PAYMENT";
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        OrderInput order = OrderInput.from(context.input());
        Object fails = context.input().get("refundFails");
        if (Boolean.TRUE.equals(fails) || "true".equals(fails)) {
            return StepHandlerResult.permanentFailure("Refund rejected by the payment provider for order "
                    + order.orderId());
        }
        if (context.compensatesStepExecutionId() == null) {
            return StepHandlerResult.permanentFailure("No charge to refund: the command does not say which step it undoes");
        }
        String chargeKey = context.compensatesStepExecutionId().toString();
        PaymentGateway.Result result = payments.refund(context.stepExecutionId().toString(), chargeKey);
        return switch (result.status()) {
            case OK -> {
                log.info("Refunded order {} (charge key {}){}", order.orderId(), chargeKey,
                        result.replayed() ? " — already refunded, provider replayed it" : "");
                yield StepHandlerResult.succeeded();
            }
            case REJECTED -> StepHandlerResult.permanentFailure(result.message());
            case UNAVAILABLE -> StepHandlerResult.retryableFailure(result.message());
        };
    }
}
