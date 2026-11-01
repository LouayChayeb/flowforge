package io.github.louaychayeb.flowforge.common.step;

/**
 * Executes one type of workflow step, such as {@code CHARGE_PAYMENT}.
 *
 * <p>A handler may be invoked more than once for the same logical step (retries, and later
 * redelivered messages), so implementations should be idempotent with respect to
 * {@link StepContext#stepExecutionId()}.
 *
 * <p>Throwing an exception is treated as a retryable failure.
 */
public interface StepHandler {

    /**
     * The step type this handler executes; matched against the {@code type} of a step definition.
     */
    String stepType();

    StepHandlerResult execute(StepContext context);
}
