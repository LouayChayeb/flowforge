package io.github.louaychayeb.flowforge.orchestrator.handlers;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Typed view of the input of the sample order-processing workflow.
 */
record OrderInput(String orderId, String customerId, BigDecimal amount) {

    static OrderInput from(Map<String, Object> input) {
        Object amount = input.get("amount");
        return new OrderInput(
                asString(input.get("orderId")),
                asString(input.get("customerId")),
                amount instanceof Number number ? new BigDecimal(number.toString()) : null);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
