package io.github.louaychayeb.flowforge.worker;

import io.github.louaychayeb.flowforge.common.messaging.StepCommand;
import io.github.louaychayeb.flowforge.common.messaging.StepResult;
import io.github.louaychayeb.flowforge.common.messaging.Topics;
import io.github.louaychayeb.flowforge.common.step.StepCommandProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Consumes step commands, executes them and publishes the results.
 *
 * <p>Delivery is at-least-once: the command's offset is committed only after this method returns,
 * which is after the result has been acknowledged by Kafka. If the worker dies in between, the
 * command is delivered again and the step executes again — which is why handlers must be idempotent
 * and why the orchestrator ignores duplicate results.
 */
@Component
public class StepCommandListener {

    private static final Logger log = LoggerFactory.getLogger(StepCommandListener.class);

    private final StepCommandProcessor processor;
    private final KafkaTemplate<String, StepResult> kafka;
    private final Duration publishTimeout;

    public StepCommandListener(StepCommandProcessor processor, KafkaTemplate<String, StepResult> kafka,
                               @Value("${flowforge.kafka.publish-timeout:10s}") Duration publishTimeout) {
        this.processor = processor;
        this.kafka = kafka;
        this.publishTimeout = publishTimeout;
    }

    @KafkaListener(topics = Topics.STEP_COMMANDS)
    public void onCommand(StepCommand command) throws InterruptedException, ExecutionException, TimeoutException {
        StepResult result = processor.process(command);
        kafka.send(Topics.STEP_RESULTS, command.runId().toString(), result)
                .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Step {} ({}) attempt {} of run {} -> {}", command.stepName(), command.stepType(),
                command.attempt(), command.runId(), result.outcome());
    }
}
