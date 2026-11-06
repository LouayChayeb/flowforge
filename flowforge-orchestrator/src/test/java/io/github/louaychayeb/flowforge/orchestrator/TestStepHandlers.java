package io.github.louaychayeb.flowforge.orchestrator;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepFailureException;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** Succeeds with output {@code {"producedBy": <step name>, "attempt": n}}. */
    public static final String PRODUCE = "TEST_PRODUCE";
    /** Succeeds with output {@code {"echo": <producedBy of step "producer">}}; fails permanently if it is missing. */
    public static final String CONSUME = "TEST_CONSUME";
    /** Succeeds with output {@code {"echo": <producedBy of the signal step "approval">}}: reads a signal's payload. */
    public static final String CONSUME_APPROVAL = "TEST_CONSUME_APPROVAL";
    /** A compensation that fails permanently until {@link #UNDO_ALLOWED} is set, for testing operator retries. */
    public static final String UNDO_WHEN_ALLOWED = "TEST_UNDO_WHEN_ALLOWED";

    /** Opens {@link #UNDO_WHEN_ALLOWED}. Tests that use it must reset it. */
    public static final AtomicBoolean UNDO_ALLOWED = new AtomicBoolean();

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
                handler(PRODUCE, context -> StepHandlerResult.succeeded(
                        Map.of("producedBy", context.stepName(), "attempt", context.attempt()))),
                handler(CONSUME, context -> StepHandlerResult.succeeded(
                        Map.of("echo", context.requireOutput("producer", "producedBy")))),
                handler(CONSUME_APPROVAL, context -> StepHandlerResult.succeeded(
                        Map.of("echo", context.requireOutput("approval", "producedBy")))),
                handler(UNDO_WHEN_ALLOWED, context -> {
                    if (!UNDO_ALLOWED.get()) {
                        throw new StepFailureException.Permanent("undo refused");
                    }
                    return StepHandlerResult.succeeded();
                }),
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
