package io.github.louaychayeb.flowforge.orchestrator.api;

import io.github.louaychayeb.flowforge.orchestrator.definition.InvalidWorkflowDefinitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain exceptions to RFC 9457 problem responses. Validation and malformed-request errors
 * are handled by {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidWorkflowDefinitionException.class)
    ProblemDetail invalidDefinition(InvalidWorkflowDefinitionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
