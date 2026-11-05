package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;
import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.worker.payments.SimulatedPaymentGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderHandlersTest {

    private final SimulatedPaymentGateway payments = new SimulatedPaymentGateway(new BigDecimal("10000"));

    private static final Map<String, Object> VALID_ORDER = Map.of(
            "orderId", "ORD-1042", "customerId", "CUS-91", "amount", 249.90);

    @Test
    void validatesCompleteOrders() {
        assertThat(new ValidateOrderHandler().execute(context(VALID_ORDER)).outcome())
                .isEqualTo(StepOutcome.SUCCEEDED);
    }

    @Test
    void rejectsOrdersWithMissingFieldsPermanently() {
        Map<String, Object> missingCustomer = new HashMap<>(VALID_ORDER);
        missingCustomer.remove("customerId");

        StepHandlerResult result = new ValidateOrderHandler().execute(context(missingCustomer));

        assertThat(result.outcome()).isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThat(result.errorMessage()).isEqualTo("customerId is required");
    }

    @Test
    void rejectsNonPositiveAmounts() {
        Map<String, Object> free = new HashMap<>(VALID_ORDER);
        free.put("amount", 0);

        assertThat(new ValidateOrderHandler().execute(context(free)).outcome())
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    @Test
    void declinesLargePayments() {
        Map<String, Object> large = new HashMap<>(VALID_ORDER);
        large.put("amount", 10_000.01);

        assertThat(new ChargePaymentHandler(payments).execute(context(VALID_ORDER)).outcome())
                .isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(new ChargePaymentHandler(payments).execute(context(large)).outcome())
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    @Test
    void refusesShipmentsToUnsupportedCountries() {
        Map<String, Object> nowhere = new HashMap<>(VALID_ORDER);
        nowhere.put("country", "XX");

        assertThat(new CreateShipmentHandler().execute(context(VALID_ORDER)).outcome())
                .isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(new CreateShipmentHandler().execute(context(nowhere)).outcome())
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    @Test
    void compensationsSucceed() {
        assertThat(new ReleaseInventoryHandler().execute(context(VALID_ORDER)).outcome())
                .isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(new RefundPaymentHandler(payments).execute(undo(VALID_ORDER)).outcome())
                .isEqualTo(StepOutcome.SUCCEEDED);
    }

    @Test
    void aFlakyCarrierFailsRetryablyForTheRequestedNumberOfAttempts() {
        Map<String, Object> flaky = new HashMap<>(VALID_ORDER);
        flaky.put("shipmentFailures", 2);
        CreateShipmentHandler handler = new CreateShipmentHandler();

        StepHandlerResult first = handler.execute(context(flaky, 1));
        assertThat(first.outcome()).isEqualTo(StepOutcome.RETRYABLE_FAILURE);
        assertThat(first.errorMessage()).isEqualTo("Carrier API timed out (attempt 1)");
        assertThat(handler.execute(context(flaky, 2)).outcome()).isEqualTo(StepOutcome.RETRYABLE_FAILURE);
        assertThat(handler.execute(context(flaky, 3)).outcome()).isEqualTo(StepOutcome.SUCCEEDED);
    }

    @Test
    void aRefundCanBeMadeToFailForTheCompensationFailedDemo() {
        Map<String, Object> stuck = new HashMap<>(VALID_ORDER);
        stuck.put("refundFails", true);

        assertThat(new RefundPaymentHandler(payments).execute(context(stuck)).outcome())
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    @Test
    void aChargeThatRunsTwiceChargesOnce() {
        ChargePaymentHandler charge = new ChargePaymentHandler(payments);
        StepContext sameStep = context(VALID_ORDER);

        assertThat(charge.execute(sameStep).outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        // Redelivered after a crash: same step execution id, so the same idempotency key.
        assertThat(charge.execute(sameStep).outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(payments.charge(sameStep.stepExecutionId().toString(), "ORD-1042", new BigDecimal("249.90")).replayed())
                .as("the provider has seen this key before").isTrue();
    }

    @Test
    void aRefundWithoutAChargeReferenceFailsPermanently() {
        assertThat(new RefundPaymentHandler(payments).execute(context(VALID_ORDER)).outcome())
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    @Test
    void theFraudCheckRejectsFlaggedOrders() {
        Map<String, Object> flagged = new HashMap<>(VALID_ORDER);
        flagged.put("fraud", true);

        assertThat(new CheckFraudHandler().execute(context(VALID_ORDER)).outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(new CheckFraudHandler().execute(context(flagged)).outcome())
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    private static StepContext undo(Map<String, Object> input) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), "undo", 1, input, UUID.randomUUID());
    }

    private static StepContext context(Map<String, Object> input) {
        return context(input, 1);
    }

    private static StepContext context(Map<String, Object> input, int attempt) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), "step", attempt, input);
    }
}
