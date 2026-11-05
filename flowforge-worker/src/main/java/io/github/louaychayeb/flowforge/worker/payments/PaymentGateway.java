package io.github.louaychayeb.flowforge.worker.payments;

import java.math.BigDecimal;

/**
 * The payment provider as the step handlers see it. Every call carries an idempotency key — the step execution
 * id — so calling again after a crash, a redelivery or a timeout cannot charge or refund twice.
 */
public interface PaymentGateway {

    Result charge(String idempotencyKey, String orderId, BigDecimal amount);

    Result refund(String idempotencyKey, String chargeIdempotencyKey);

    /**
     * @param status   what the provider decided
     * @param replayed {@code true} if the provider had already handled this key and only replayed its answer
     * @param message  detail for logs and error messages
     * @param reference the provider's id for what it did (e.g. the charge id), when it reports one
     */
    record Result(Status status, boolean replayed, String message, String reference) {

        public Result(Status status, boolean replayed, String message) {
            this(status, replayed, message, null);
        }
    }

    enum Status {
        /** Done (or already done before, see {@code replayed}). */
        OK,
        /** Refused for good, e.g. a declined card: retrying cannot help. */
        REJECTED,
        /** The provider could not be reached or failed: worth retrying with the same key. */
        UNAVAILABLE
    }
}
