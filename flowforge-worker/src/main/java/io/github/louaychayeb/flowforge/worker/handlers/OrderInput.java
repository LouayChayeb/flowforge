package io.github.louaychayeb.flowforge.worker.handlers;

import io.github.louaychayeb.flowforge.common.step.StepFailureException;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Typed view of the input of the sample order-processing workflow. The {@code require…} accessors throw
 * {@link StepFailureException.InvalidInput} — a permanent failure — so a handler states what it needs instead of
 * repeating null checks.
 */
record OrderInput(String orderId, String customerId, BigDecimal amount) {

    /**
     * @throws StepFailureException.InvalidInput if {@code amount} is present but not a number
     */
    static OrderInput from(Map<String, Object> input) {
        return new OrderInput(asString(input.get("orderId")), asString(input.get("customerId")),
                asAmount(input.get("amount")));
    }

    String requireOrderId() {
        return require(orderId, "orderId");
    }

    String requireCustomerId() {
        return require(customerId, "customerId");
    }

    BigDecimal requirePositiveAmount() {
        if (amount == null || amount.signum() <= 0) {
            throw new StepFailureException.InvalidInput("amount must be a positive number");
        }
        return amount;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new StepFailureException.InvalidInput(field + " is required");
        }
        return value;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal asAmount(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            throw new StepFailureException.InvalidInput("amount is not a number: " + value);
        }
    }
}
