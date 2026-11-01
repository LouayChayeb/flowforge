package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.messaging.StepOutcome;
import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderHandlersTest {

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

        assertThat(new ChargePaymentHandler().execute(context(VALID_ORDER)).outcome())
                .isEqualTo(StepOutcome.SUCCEEDED);
        assertThat(new ChargePaymentHandler().execute(context(large)).outcome())
                .isEqualTo(StepOutcome.PERMANENT_FAILURE);
    }

    private static StepContext context(Map<String, Object> input) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), "step", 1, input);
    }
}
