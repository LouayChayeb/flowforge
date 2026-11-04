package io.github.louaychayeb.flowforge.worker;

import io.github.louaychayeb.flowforge.common.step.StepContext;
import io.github.louaychayeb.flowforge.common.step.StepHandler;
import io.github.louaychayeb.flowforge.common.step.StepHandlerResult;

import java.time.Duration;

/**
 * Makes a handler take a fixed time, standing in for the call to an external service a real step would make.
 * The demo handlers return instantly, which would make worker capacity meaningless in a load test.
 * Enabled with {@code flowforge.worker.simulated-latency}; off by default.
 */
final class SimulatedLatency implements StepHandler {

    private final StepHandler delegate;
    private final Duration latency;

    SimulatedLatency(StepHandler delegate, Duration latency) {
        this.delegate = delegate;
        this.latency = latency;
    }

    @Override
    public String stepType() {
        return delegate.stepType();
    }

    @Override
    public StepHandlerResult execute(StepContext context) {
        try {
            Thread.sleep(latency.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepHandlerResult.retryableFailure("Interrupted");
        }
        return delegate.execute(context);
    }
}
