package io.github.louaychayeb.flowforge.orchestrator;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Deterministic handlers, run by {@link TestWorker}, for exercising each engine path.
 */
public final class TestStepHandlers {

    /** Always succeeds. */
    public static final String OK = "TEST_OK";
    /** Fails retryably on attempts 1 and 2, succeeds on attempt 3. */
    public static final String FLAKY = "TEST_FLAKY";
    /** Always fails retryably. */
    public static final String ALWAYS_RETRYABLE = "TEST_ALWAYS_RETRYABLE";
    /** Always fails permanently. */
    public static final String PERMANENT = "TEST_PERMANENT";
    /** Always throws. */
    public static final String THROWS = "TEST_THROWS";
    /** Compensation that always succeeds. */
    public static final String UNDO = "TEST_UNDO";
    /** Fails retryably 30% of the time, at random. */
    public static final String SOMETIMES = "TEST_SOMETIMES";
    /** Fails permanently if the run input has {@code "fail": true}, succeeds otherwise. */
    public static final String FAIL_IF_FLAGGED = "TEST_FAIL_IF_FLAGGED";

    private TestStepHandlers() {
    }

    static List<StepHandler> all() {
        return List.of(
                handler(OK, context -> StepHandlerResult.succeeded()),
                handler(FLAKY, context -> context.attempt() < 3
                        ? StepHandlerResult.retryableFailure("flaky failure on attempt " + context.attempt())
                        : StepHandlerResult.succeeded()),
                handler(ALWAYS_RETRYABLE, context -> StepHandlerResult.retryableFailure("service unavailable")),
                handler(PERMANENT, context -> StepHandlerResult.permanentFailure("rejected")),
                handler(UNDO, context -> StepHandlerResult.succeeded()),
                handler(SOMETIMES, context -> ThreadLocalRandom.current().nextDouble() < 0.3
                        ? StepHandlerResult.retryableFailure("random failure")
                        : StepHandlerResult.succeeded()),
                handler(FAIL_IF_FLAGGED, context -> Boolean.TRUE.equals(context.input().get("fail"))
                        ? StepHandlerResult.permanentFailure("flagged to fail")
                        : StepHandlerResult.succeeded()),
                handler(THROWS, context -> {
                    throw new IllegalStateException("boom");
                }));
    }

    private static StepHandler handler(String type, Function<StepContext, StepHandlerResult> body) {
        return new StepHandler() {
            @Override
            public String stepType() {
                return type;
            }

            @Override
            public StepHandlerResult execute(StepContext context) {
                return body.apply(context);
            }
        };
    }
}
