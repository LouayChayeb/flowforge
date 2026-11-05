package io.github.louaychayeb.flowforge.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * An API shaped like a real payment provider's: every mutating call needs an {@code Idempotency-Key} header, and
 * a repeated call answers with the original result plus {@code Idempotent-Replayed: true}.
 *
 * <ul>
 *   <li>{@code 201} charge captured, {@code 200} the same charge replayed</li>
 *   <li>{@code 402} charge declined (also on replay)</li>
 *   <li>{@code 422} the key was already used for a different request</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final PaymentLedger ledger;

    public PaymentController(PaymentLedger ledger) {
        this.ledger = ledger;
    }

    public record ChargeRequest(@NotBlank String orderId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
    }

    public record RefundRequest(@NotBlank String chargeIdempotencyKey) {
    }

    @PostMapping("/charges")
    public ResponseEntity<PaymentLedger.Charge> charge(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                       @Valid @RequestBody ChargeRequest request) {
        PaymentLedger.Outcome<PaymentLedger.Charge> outcome = ledger.charge(key, request.orderId(), request.amount());
        HttpStatus status = "DECLINED".equals(outcome.value().status()) ? HttpStatus.PAYMENT_REQUIRED
                : outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).header(REPLAYED, String.valueOf(outcome.replayed())).body(outcome.value());
    }

    @PostMapping("/refunds")
    public ResponseEntity<PaymentLedger.Refund> refund(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                       @Valid @RequestBody RefundRequest request) {
        PaymentLedger.Outcome<PaymentLedger.Refund> outcome = ledger.refund(key, request.chargeIdempotencyKey());
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED, String.valueOf(outcome.replayed())).body(outcome.value());
    }

    @GetMapping("/charges")
    public List<PaymentLedger.Charge> charges(@RequestParam(required = false) String orderId,
                                              @RequestParam(defaultValue = "20") int limit) {
        return ledger.charges(orderId, Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/summary")
    public PaymentLedger.Summary summary() {
        return ledger.summary();
    }

    @ExceptionHandler(PaymentLedger.IdempotencyKeyReused.class)
    ProblemDetail keyReused(PaymentLedger.IdempotencyKeyReused e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(PaymentLedger.ChargeNotFound.class)
    ProblemDetail notFound(PaymentLedger.ChargeNotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingKey(MissingRequestHeaderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The " + IDEMPOTENCY_KEY + " header is required");
    }
}
