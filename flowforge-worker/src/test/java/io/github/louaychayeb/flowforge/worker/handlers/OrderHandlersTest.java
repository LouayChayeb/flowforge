package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;
import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepFailureException;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import io.github.louaychayeb.flowforge.worker.payments.SimulatedPaymentGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderHandlersTest {

    private static final Map<String, Object> VALID_ORDER = Map.of(
            "orderId", "ORD-1042", "customerId", "CUS-91", "amount", 249.90);

    private final SimulatedPaymentGateway payments = new SimulatedPaymentGateway(new BigDecimal("10000"));

    @Test
    void validatesCompleteOrders() {
        assertThat(outcome(new ValidateOrderHandler(), context(VALID_ORDER))).isEqualTo(StepOutcome.SUCCEEDED);
    }

    @Test
    void rejectsOrdersWithMissingFieldsAsInvalidInput() {
        Map<String, Object> missingCustomer = new HashMap<>(VALID_ORDER);
        missingCustomer.remove("customerId");

        assertThatThrownBy(() -> new ValidateOrderHandler().execute(context(missingCustomer)))
                .isInstanceOf(StepFailureException.InvalidInput.class)
                .hasMessage("customerId is required");
    }

    @Test
    void rejectsNonPositiveAndNonNumericAmounts() {
        Map<String, Object> free = new HashMap<>(VALID_ORDER);
        free.put("amount", 0);
        Map<String, Object> text = new HashMap<>(VALID_ORDER);
        text.put("amount", "lots");

        assertThat(outcome(new ValidateOrderHandler(), context(free))).isEqualTo(StepOutcome.PERMANENT_FAILURE);
        assertThatThrownBy(() -> new ValidateOrderHandler().execute(context(text)))
                .isInstanceOf(StepFailureException.InvalidInput.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void chargesProduceTheProvidersChargeIdAndDeclinesArePermanent() {
        Map<String, Object> large = new HashMap<>(VALID_ORDER);
        large.put("amount", 10_000.01);
        ChargePaymentHandler charge = new ChargePaymentHandler(payments);

        StepHandlerResult ok = charge.execute(context(VALID_ORDER));
        assertThat(ok.outcome()).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(ok.output()).containsKey("chargeId");
        assertThatThrownBy(() -> charge.execute(context(large)))
                .isInstanceOf(StepFailureException.Permanent.class)
                .hasMessageContaining("declined");
    }

    @Test
    void aChargeThatRunsTwiceChargesOnce() {
        ChargePaymentHandler charge = new ChargePaymentHandler(payments);
        StepContext sameStep = context(VALID_ORDER);

        StepHandlerResult first = charge.execute(sameStep);
        // Redelivered after a crash: same step execution id, so the same idempotency key.
        StepHandlerResult second = charge.execute(sameStep);

        assertThat(second.output()).isEqualTo(first.output());
        assertThat(payments.charge(sameStep.stepExecutionId().toString(), "ORD-1042", new BigDecimal("249.90")).replayed())
                .as("the provider has seen this key before").isTrue();
    }

    @Test
    void shipmentsProduceATrackingNumberAndRefuseUnsupportedCountries() {
        Map<String, Object> nowhere = new HashMap<>(VALID_ORDER);
        nowhere.put("country", "XX");

        assertThat(new CreateShipmentHandler().execute(context(VALID_ORDER)).output()).containsKey("trackingNumber");
        assertThatThrownBy(() -> new CreateShipmentHandler().execute(context(nowhere)))
                .isInstanceOf(StepFailureException.Permanent.class);
    }

    @Test
    void aFlakyCarrierFailsRetryablyForTheRequestedNumberOfAttempts() {
        Map<String, Object> flaky = new HashMap<>(VALID_ORDER);
        flaky.put("shipmentFailures", 2);
        CreateShipmentHandler handler = new CreateShipmentHandler();

        assertThatThrownBy(() -> handler.execute(context(flaky, 1)))
                .isInstanceOf(StepFailureException.Retryable.class)
                .hasMessage("Carrier API timed out (attempt 1)");
        assertThat(outcome(handler, context(flaky, 2))).isEqualTo(StepOutcome.RETRYABLE_FAILURE);
        assertThat(outcome(handler, context(flaky, 3))).isEqualTo(StepOutcome.SUCCEEDED);
    }

    @Test
    void theConfirmationUsesTheOutputsOfEarlierSteps() {
        StepContext withTracking = new StepContext(UUID.randomUUID(), UUID.randomUUID(), "send-confirmation", 1,
                VALID_ORDER, null, Map.of("create-shipment", Map.of("trackingNumber", "TRK1"),
                "charge-payment", Map.of("chargeId", "ch_1")));

        assertThat(outcome(new SendConfirmationHandler(), withTracking)).isEqualTo(StepOutcome.SUCCEEDED);
        assertThatThrownBy(() -> new SendConfirmationHandler().execute(context(VALID_ORDER)))
                .as("without the shipment's output the workflow is wired wrongly")
                .isInstanceOf(StepFailureException.Permanent.class)
                .hasMessageContaining("trackingNumber");
    }

    @Test
    void compensationsSucceed() {
        assertThat(outcome(new ReleaseInventoryHandler(), context(VALID_ORDER))).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(outcome(new RefundPaymentHandler(payments), undo(VALID_ORDER))).isEqualTo(StepOutcome.SUCCEEDED);
    }

    @Test
    void aRefundCanBeMadeToFailForTheCompensationFailedDemo() {
        Map<String, Object> stuck = new HashMap<>(VALID_ORDER);
        stuck.put("refundFails", true);

        assertThat(outcome(new RefundPaymentHandler(payments), undo(stuck))).isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    @Test
    void aRefundWithoutAChargeReferenceFailsPermanently() {
        assertThat(outcome(new RefundPaymentHandler(payments), context(VALID_ORDER)))
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    @Test
    void theFraudCheckRejectsFlaggedOrders() {
        Map<String, Object> flagged = new HashMap<>(VALID_ORDER);
        flagged.put("fraud", true);

        assertThat(outcome(new CheckFraudHandler(), context(VALID_ORDER))).isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(outcome(new CheckFraudHandler(), context(flagged))).isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    /** What the worker would report: a returned result, or the classification of a thrown StepFailureException. */
    private static StepOutcome outcome(StepHandler handler, StepContext context) {
        try {
            return handler.execute(context).outcome();
        } catch (StepFailureException e) {
            return e.isRetryable() ? StepOutcome.RETRYABLE_FAILURE : StepOutcome.PERMANENT_FAILURE;
        }
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
