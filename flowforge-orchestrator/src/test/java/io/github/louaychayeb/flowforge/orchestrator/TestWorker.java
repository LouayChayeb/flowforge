package io.github.louaychayeb.flowforge.orchestrator;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepCommandProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A worker running inside the test context: consumes real step commands from Kafka, runs
 * {@link TestStepHandlers} through the same {@link StepCommandProcessor} the real worker uses,
 * and publishes real results. Three consumer threads share the partitions, like three workers.
 *
 * <p>{@link Chaos} makes it misbehave on demand: slow down before reporting, and report results twice.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestWorker {

    @Bean
    Chaos testWorkerChaos() {
        return new Chaos();
    }

    @Bean
    Listener testWorkerListener(KafkaTemplate<String, StepResult> kafka, Chaos chaos) {
        return new Listener(new StepCommandProcessor(TestStepHandlers.all(), Clock.systemUTC()), kafka, chaos);
    }

    /** Off by default; a test turns it on and must turn it off again. */
    public static class Chaos {

        private volatile boolean enabled;
        private volatile double delayProbability;
        private volatile int maxDelayMillis;
        private volatile double duplicateProbability;

        public void enable(double delayProbability, int maxDelayMillis, double duplicateProbability) {
            this.delayProbability = delayProbability;
            this.maxDelayMillis = maxDelayMillis;
            this.duplicateProbability = duplicateProbability;
            this.enabled = true;
        }

        public void disable() {
            enabled = false;
        }

        void maybeDelay() throws InterruptedException {
            if (enabled && ThreadLocalRandom.current().nextDouble() < delayProbability) {
                Thread.sleep(ThreadLocalRandom.current().nextInt(maxDelayMillis + 1));
            }
        }

        boolean duplicate() {
            return enabled && ThreadLocalRandom.current().nextDouble() < duplicateProbability;
        }
    }

    public static class Listener {

        private final StepCommandProcessor processor;
        private final KafkaTemplate<String, StepResult> kafka;
        private final Chaos chaos;

        Listener(StepCommandProcessor processor, KafkaTemplate<String, StepResult> kafka, Chaos chaos) {
            this.processor = processor;
            this.kafka = kafka;
            this.chaos = chaos;
        }

        // The orchestrator's consumer defaults to StepResult; this listener reads commands.
        @KafkaListener(topics = Topics.STEP_COMMANDS, groupId = "test-worker", concurrency = "3",
                properties = "spring.json.value.default.type=io.github.louaychayeb.flowforge.common.messaging.StepCommand")
        public void onCommand(StepCommand command) throws Exception {
            Optional<StepResult> outcome = processor.process(command);
            if (outcome.isEmpty()) {
                return; // past its deadline, like the real worker
            }
            StepResult result = outcome.get();
            chaos.maybeDelay();
            kafka.send(Topics.STEP_RESULTS, command.runId().toString(), result).get();
            if (chaos.duplicate()) {
                kafka.send(Topics.STEP_RESULTS, command.runId().toString(), result).get();
            }
        }
    }
}
