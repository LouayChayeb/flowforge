package io.github.louaychayeb.flowforge.orchestrator.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Claims eligible steps. Each claim writes its step command to the outbox in the same transaction;
 * the {@link io.github.louaychayeb.flowforge.orchestrator.outbox.OutboxPublisher} sends it to the workers,
 * and outcomes come back through {@link StepResultListener}.
 */
@Component
public class StepDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StepDispatcher.class);

    private final StepStateService stateService;
    private final EngineProperties properties;

    public StepDispatcher(StepStateService stateService, EngineProperties properties) {
        this.stateService = stateService;
        this.properties = properties;
    }

    /**
     * Claims one batch of eligible steps.
     *
     * @return the number of steps claimed; {@code 0} means there was nothing to do
     */
    public int dispatchOnce() {
        List<ClaimedStep> claimed = stateService.claim(properties.batchSize());
        for (ClaimedStep step : claimed) {
            log.info("Claimed step {} ({}) attempt {} of run {}", step.stepName(), step.stepType(), step.attempt(),
                    step.runId());
        }
        return claimed.size();
    }
}
