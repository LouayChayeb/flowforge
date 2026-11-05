package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.error.ErrorCode;
import io.github.louaychayeb.flowforge.orchestrator.error.FlowForgeException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns every failure into an RFC 9457 problem response with a stable {@code code} (see {@link ErrorCode}),
 * structured details, and — when tracing is on — the {@code traceId}, so a client's bug report leads straight to
 * the trace and the logs.
 *
 * <ul>
 *   <li>{@link FlowForgeException}s carry their own code and details;</li>
 *   <li>bean-validation failures list every invalid field;</li>
 *   <li>optimistic or pessimistic locking conflicts become {@code 409 CONCURRENT_MODIFICATION}: retrying is safe;</li>
 *   <li>anything else is a {@code 500} whose message never leaks internals; the full stack trace is logged.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** RFC 9457 members and our own; domain details must not replace them. */
    private static final Set<String> RESERVED = Set.of("type", "title", "status", "detail", "instance", "code",
            "traceId", "errors");

    private final ObjectProvider<Tracer> tracer;

    public ApiExceptionHandler(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(FlowForgeException.class)
    ResponseEntity<ProblemDetail> domain(FlowForgeException e) {
        if (e.code().status().is5xxServerError()) {
            log.error("Request failed: {}", e.getMessage(), e);
        } else {
            log.debug("Request rejected ({}): {}", e.code(), e.getMessage());
        }
        ProblemDetail problem = problem(e.code(), e.getMessage());
        e.details().forEach((key, value) -> {
            // Never let a detail overwrite a standard member: "status" must stay the HTTP status code.
            problem.setProperty(RESERVED.contains(key) ? "detail_" + key : key, value);
        });
        return ResponseEntity.status(e.code().status()).body(problem);
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> concurrency(ConcurrencyFailureException e) {
        log.info("Concurrent modification, client may retry: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.CONCURRENT_MODIFICATION.status()).body(problem(
                ErrorCode.CONCURRENT_MODIFICATION,
                "The data changed while the request was being processed; repeating the request is safe"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        ProblemDetail problem = problem(ErrorCode.MALFORMED_REQUEST,
                "Parameter '" + e.getName() + "' has an invalid value: " + e.getValue());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError().body(problem(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred; it has been logged" + traceSuffix()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException e,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        List<Map<String, Object>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.<String, Object>of("field", f.getField(),
                        "message", f.getDefaultMessage() == null ? "is invalid" : f.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED,
                errors.size() == 1 ? "1 field is invalid" : errors.size() + " fields are invalid");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(@NonNull HttpMessageNotReadableException e,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        // The parser's message can quote internal class names; give the client a plain reason.
        return ResponseEntity.badRequest().body(problem(ErrorCode.MALFORMED_REQUEST,
                "The request body is missing or is not valid JSON for this endpoint"));
    }

    private ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        String traceId = currentTraceId();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    private String traceSuffix() {
        String traceId = currentTraceId();
        return traceId == null ? "" : " (trace " + traceId + ")";
    }

    private String currentTraceId() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
