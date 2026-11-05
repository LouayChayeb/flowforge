package io.github.louaychayeb.flowforge.payments;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The provider's ledger. Every operation is keyed by the caller's idempotency key and decided by a unique
 * constraint, so two concurrent requests with the same key cannot both charge: one inserts, the other finds the
 * row and replays its result.
 */
@Component
public class PaymentLedger {

    private final NamedParameterJdbcTemplate jdbc;
    private final BigDecimal declineAbove;
    private final MeterRegistry metrics;
    private final Clock clock;

    public PaymentLedger(NamedParameterJdbcTemplate jdbc, @Value("${flowforge.payments.decline-above:10000}")
                         BigDecimal declineAbove, MeterRegistry metrics, Clock clock) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.declineAbove = declineAbove;
        this.clock = clock;
    }

    public record Charge(UUID chargeId, String idempotencyKey, String orderId, BigDecimal amount, String status,
                         String declineReason, Instant createdAt, Instant refundedAt, int requests) {
    }

    /** The result of a request: the stored charge, and whether this request was a repeat that changed nothing. */
    public record Outcome<T>(T value, boolean replayed) {
    }

    public static class IdempotencyKeyReused extends RuntimeException {
        IdempotencyKeyReused(String message) {
            super(message);
        }
    }

    public static class ChargeNotFound extends RuntimeException {
        ChargeNotFound(String key) {
            super("No charge with idempotency key " + key);
        }
    }

    /**
     * Charges once per idempotency key. A repeat with the same key and the same request returns the original
     * outcome — captured or declined — and only counts the request; a repeat with a different order or amount is
     * refused, as real providers do, because it means the caller reused a key by mistake.
     */
    @Transactional
    public Outcome<Charge> charge(String key, String orderId, BigDecimal amount) {
        Instant now = clock.instant();
        boolean declined = amount.compareTo(declineAbove) > 0;
        int inserted = jdbc.update("""
                INSERT INTO payments.charge (id, idempotency_key, order_id, amount, status, decline_reason, created_at,
                                             requests, last_request_at)
                VALUES (:id, :key, :order, :amount, :status, :reason, :now, 1, :now)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("key", key).addValue("order", orderId)
                .addValue("amount", amount).addValue("status", declined ? "DECLINED" : "CAPTURED")
                .addValue("reason", declined ? "Amount exceeds the card limit of " + declineAbove : null)
                .addValue("now", Timestamp.from(now)));
        Charge charge = lockCharge(key);
        if (inserted == 1) {
            count("charge", declined ? "declined" : "new");
            return new Outcome<>(charge, false);
        }
        if (!charge.orderId().equals(orderId) || charge.amount().compareTo(amount) != 0) {
            throw new IdempotencyKeyReused("Idempotency key " + key + " was first used for order " + charge.orderId()
                    + " and amount " + charge.amount() + ", not order " + orderId + " and amount " + amount);
        }
        count("charge", "replayed");
        jdbc.update("UPDATE payments.charge SET requests = requests + 1, last_request_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("id", charge.chargeId()));
        return new Outcome<>(lockCharge(key), true);
    }

    public record Refund(UUID refundId, String idempotencyKey, UUID chargeId, String chargeKey, BigDecimal amount,
                         Instant createdAt, int requests) {
    }

    /**
     * Refunds a captured charge once. A repeat of the same refund replays it; refunding a charge that was declined
     * is a no-op success, since there is no money to give back.
     */
    @Transactional
    public Outcome<Refund> refund(String key, String chargeKey) {
        Instant now = clock.instant();
        Charge charge = jdbc.query("SELECT * FROM payments.charge WHERE idempotency_key = :key FOR UPDATE",
                new MapSqlParameterSource("key", chargeKey), PaymentLedger::charge).stream().findFirst()
                .orElseThrow(() -> new ChargeNotFound(chargeKey));
        List<Refund> existing = findRefund(key);
        if (!existing.isEmpty()) {
            Refund refund = existing.get(0);
            if (!refund.chargeId().equals(charge.chargeId())) {
                throw new IdempotencyKeyReused("Idempotency key " + key + " already refunded another charge");
            }
            count("refund", "replayed");
            jdbc.update("UPDATE payments.refund SET requests = requests + 1, last_request_at = :now WHERE id = :id",
                    new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("id", refund.refundId()));
            return new Outcome<>(findRefund(key).get(0), true);
        }
        BigDecimal amount = "CAPTURED".equals(charge.status()) ? charge.amount() : BigDecimal.ZERO;
        int inserted = jdbc.update("""
                INSERT INTO payments.refund (id, idempotency_key, charge_id, amount, created_at, requests, last_request_at)
                VALUES (:id, :key, :charge, :amount, :now, 1, :now)
                ON CONFLICT (charge_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("key", key)
                .addValue("charge", charge.chargeId()).addValue("amount", amount).addValue("now", Timestamp.from(now)));
        if (inserted == 0) {
            // Refunded before under another key: the money is already back, so this refund has nothing to do.
            return new Outcome<>(jdbc.query("SELECT r.*, c.idempotency_key AS charge_key FROM payments.refund r "
                            + "JOIN payments.charge c ON c.id = r.charge_id WHERE r.charge_id = :id",
                    new MapSqlParameterSource("id", charge.chargeId()), PaymentLedger::refund).get(0), true);
        }
        count("refund", "new");
        if ("CAPTURED".equals(charge.status())) {
            jdbc.update("UPDATE payments.charge SET status = 'REFUNDED', refunded_at = :now WHERE id = :id",
                    new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("id", charge.chargeId()));
        }
        return new Outcome<>(findRefund(key).get(0), false);
    }

    public List<Charge> charges(String orderId, int limit) {
        return jdbc.query("""
                SELECT * FROM payments.charge
                WHERE (CAST(:order AS varchar) IS NULL OR order_id = :order)
                ORDER BY created_at DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("order", orderId).addValue("limit", limit), PaymentLedger::charge);
    }

    public record Summary(long charges, long captured, long declined, long refunded, long duplicateRequestsAbsorbed,
                          long refunds, BigDecimal capturedAmount, long ordersChargedMoreThanOnce) {
    }

    /**
     * Totals, including the two numbers the demo is about: how many duplicate requests were absorbed, and how many
     * orders were charged more than once (always zero).
     */
    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT count(*) AS charges,
                       count(*) FILTER (WHERE status = 'CAPTURED') AS captured,
                       count(*) FILTER (WHERE status = 'DECLINED') AS declined,
                       count(*) FILTER (WHERE status = 'REFUNDED') AS refunded,
                       coalesce(sum(requests - 1), 0)
                         + (SELECT coalesce(sum(requests - 1), 0) FROM payments.refund) AS duplicates,
                       (SELECT count(*) FROM payments.refund) AS refunds,
                       coalesce(sum(amount) FILTER (WHERE status = 'CAPTURED'), 0) AS captured_amount,
                       (SELECT count(*) FROM (SELECT order_id FROM payments.charge WHERE status <> 'DECLINED'
                                              GROUP BY order_id HAVING count(*) > 1) twice) AS twice
                FROM payments.charge
                """, new MapSqlParameterSource(), (rs, i) -> new Summary(rs.getLong("charges"), rs.getLong("captured"),
                rs.getLong("declined"), rs.getLong("refunded"), rs.getLong("duplicates"), rs.getLong("refunds"),
                rs.getBigDecimal("captured_amount"), rs.getLong("twice")));
    }

    /** flowforge.payments.requests{operation, result}: new, replayed (a duplicate absorbed) or declined. */
    private void count(String operation, String result) {
        metrics.counter("flowforge.payments.requests", "operation", operation, "result", result).increment();
    }

    private Charge lockCharge(String key) {
        return jdbc.query("SELECT * FROM payments.charge WHERE idempotency_key = :key FOR UPDATE",
                new MapSqlParameterSource("key", key), PaymentLedger::charge).get(0);
    }

    private List<Refund> findRefund(String key) {
        return jdbc.query("SELECT r.*, c.idempotency_key AS charge_key FROM payments.refund r "
                + "JOIN payments.charge c ON c.id = r.charge_id WHERE r.idempotency_key = :key",
                new MapSqlParameterSource("key", key), PaymentLedger::refund);
    }

    private static Charge charge(ResultSet rs, int row) throws SQLException {
        return new Charge(rs.getObject("id", UUID.class), rs.getString("idempotency_key"), rs.getString("order_id"),
                rs.getBigDecimal("amount"), rs.getString("status"), rs.getString("decline_reason"),
                instant(rs, "created_at"), instant(rs, "refunded_at"), rs.getInt("requests"));
    }

    private static Refund refund(ResultSet rs, int row) throws SQLException {
        return new Refund(rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
                rs.getObject("charge_id", UUID.class), rs.getString("charge_key"), rs.getBigDecimal("amount"),
                instant(rs, "created_at"), rs.getInt("requests"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
