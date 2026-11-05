package io.github.louaychayeb.flowforge.worker.payments;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-process stand-in with the same rules as {@code flowforge-payments} — declines above the limit, one charge
 * per key — for unit tests and for running a worker without the provider. Its memory dies with the worker, so it
 * cannot show what survives a crash; the real provider can.
 */
public class SimulatedPaymentGateway implements PaymentGateway {

    private final BigDecimal declineAbove;
    private final Map<String, Result> charges = new ConcurrentHashMap<>();
    private final Map<String, Result> refunds = new ConcurrentHashMap<>();

    public SimulatedPaymentGateway(BigDecimal declineAbove) {
        this.declineAbove = declineAbove;
    }

    @Override
    public Result charge(String idempotencyKey, String orderId, BigDecimal amount) {
        Result previous = charges.get(idempotencyKey);
        if (previous != null) {
            return new Result(previous.status(), true, previous.message(), previous.reference());
        }
        Result result = amount.compareTo(declineAbove) > 0
                ? new Result(Status.REJECTED, false, "Payment declined: amount exceeds the card limit of " + declineAbove)
                : new Result(Status.OK, false, "captured", "sim_" + idempotencyKey.substring(0, 8));
        Result raced = charges.putIfAbsent(idempotencyKey, result);
        return raced == null ? result : new Result(raced.status(), true, raced.message(), raced.reference());
    }

    @Override
    public Result refund(String idempotencyKey, String chargeIdempotencyKey) {
        Result previous = refunds.putIfAbsent(idempotencyKey, new Result(Status.OK, false, "refunded"));
        return previous == null ? new Result(Status.OK, false, "refunded") : new Result(Status.OK, true, "refunded");
    }
}
