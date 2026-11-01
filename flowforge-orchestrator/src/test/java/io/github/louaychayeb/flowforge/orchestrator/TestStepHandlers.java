package io.github.louaychayeb.flowforge.orchestrator;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.function.Function;

/**
 * Deterministic handlers for exercising each engine path.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestStepHandlers {

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

    @Bean
    StepHandler okHandler() {
        return handler(OK, context -> StepHandlerResult.succeeded());
    }

    @Bean
    StepHandler flakyHandler() {
        return handler(FLAKY, context -> context.attempt() < 3
                ? StepHandlerResult.retryableFailure("flaky failure on attempt " + context.attempt())
                : StepHandlerResult.succeeded());
    }

    @Bean
    StepHandler alwaysRetryableHandler() {
        return handler(ALWAYS_RETRYABLE, context -> StepHandlerResult.retryableFailure("service unavailable"));
    }

    @Bean
    StepHandler permanentHandler() {
        return handler(PERMANENT, context -> StepHandlerResult.permanentFailure("rejected"));
    }

    @Bean
    StepHandler throwingHandler() {
        return handler(THROWS, context -> {
            throw new IllegalStateException("boom");
        });
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
