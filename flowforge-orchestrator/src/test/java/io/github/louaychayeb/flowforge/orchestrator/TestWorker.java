package io.github.louaychayeb.flowforge.orchestrator;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepCommandProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * A worker running inside the test context: consumes real step commands from Kafka, runs
 * {@link TestStepHandlers} through the same {@link StepCommandProcessor} the real worker uses,
 * and publishes real results.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestWorker {

    @Bean
    Listener testWorkerListener(KafkaTemplate<String, StepResult> kafka) {
        return new Listener(new StepCommandProcessor(TestStepHandlers.all()), kafka);
    }

    public static class Listener {

        private final StepCommandProcessor processor;
        private final KafkaTemplate<String, StepResult> kafka;

        Listener(StepCommandProcessor processor, KafkaTemplate<String, StepResult> kafka) {
            this.processor = processor;
            this.kafka = kafka;
        }

        // The orchestrator's consumer defaults to StepResult; this listener reads commands.
        @KafkaListener(topics = Topics.STEP_COMMANDS, groupId = "test-worker",
                properties = "spring.json.value.default.type=io.github.louaychayeb.flowforge.common.messaging.StepCommand")
        public void onCommand(StepCommand command) throws Exception {
            kafka.send(Topics.STEP_RESULTS, command.runId().toString(), processor.process(command)).get();
        }
    }
}
