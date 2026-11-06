package io.github.louaychayeb.flowforge.orchestrator.operator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.louaychayeb.flowforge.orchestrator.engine.EngineEvents;
import io.github.louaychayeb.flowforge.orchestrator.error.NotFoundException;
import io.github.louaychayeb.flowforge.orchestrator.error.OperationNotAllowedException;
import io.github.louaychayeb.flowforge.orchestrator.run.RunStatus;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecution;
import io.github.louaychayeb.flowforge.orchestrator.run.StepExecutionRepository;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRun;
import io.github.louaychayeb.flowforge.orchestrator.run.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Manual interventions on runs: cancelling, retrying a compensation that failed, and delivering signals. Each one is
 * a single transaction that locks every step of the run and then the run — the same order the engine uses — so it
 * can neither interleave with a result being applied nor deadlock with it, and each one is recorded in the audit log.
 */
@Service
public class OperatorService {

    /** Attempts a retried compensation gets on top of those it used. */
    static final int RETRY_ATTEMPTS = 3;

    /** A signal's payload becomes the step's output, carried in every later command: the same 64 KB cap. */
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);

    private final WorkflowRunRepository runs;
    private final StepExecutionRepository steps;
    private final OperatorActionRepository actions;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public OperatorService(WorkflowRunRepository runs, StepExecutionRepository steps, OperatorActionRepository actions,
                           ApplicationEventPublisher events, Clock clock, ObjectMapper objectMapper) {
        this.runs = runs;
        this.steps = steps;
        this.actions = actions;
        this.events = events;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Cancels a running run. Returns at once; the run then winds down — in-flight steps finish, completed ones are
     * compensated — and ends {@code CANCELLED}.
     *
     * @throws NotFoundException.RunNotFound   if there is no such run
     * @throws OperationNotAllowedException if the run has finished or is compensating a failure
     */
    @Transactional
    public void cancel(UUID runId, String actor, String reason) {
        WorkflowRun run = lock(runId);
        RunStatus before = run.getStatus();
        if (run.isCancelling() && !before.isTerminal()) {
            log.info("Run {} is already being cancelled; nothing to do", runId);
            return;
        }
        Instant now = clock.instant();
        run.cancel(now);
        record(run, OperatorAction.Type.CANCEL, actor, reason, before, now);
        log.info("Run {} cancelled by {} ({} → {}): {}", runId, actor, before, run.getStatus(), reason);
        announce(run, now);
    }

    /**
     * Retries the compensation that failed in a {@code COMPENSATION_FAILED} run, with {@value #RETRY_ATTEMPTS} more
     * attempts.
     *
     * @throws NotFoundException.RunNotFound   if there is no such run
     * @throws OperationNotAllowedException unless the run is {@code COMPENSATION_FAILED}
     */
    @Transactional
    public void retryCompensation(UUID runId, String actor, String reason) {
        WorkflowRun run = lock(runId);
        RunStatus before = run.getStatus();
        Instant now = clock.instant();
        run.retryCompensation(RETRY_ATTEMPTS, now);
        record(run, OperatorAction.Type.RETRY_COMPENSATION, actor, reason, before, now);
        log.warn("Run {}: failed compensation retried by {}: {}", runId, actor, reason);
        announce(run, now);
    }

    /**
     * Delivers the signal a {@code SIGNAL} step is waiting for: the step completes with {@code payload} as its output,
     * and the steps after it become ready. Who sent it is audited, like any other manual intervention.
     *
     * @throws NotFoundException.RunNotFound  if there is no such run
     * @throws NotFoundException.StepNotFound if the run has no signal step of that name
     * @throws OperationNotAllowedException   if the step is not waiting for a signal right now, or the payload is
     *                                        too large to hand to later steps
     */
    @Transactional
    public void signal(UUID runId, String stepName, Map<String, Object> payload, String actor, String reason) {
        WorkflowRun run = lock(runId);
        StepExecution step = run.getSteps().stream()
                .filter(s -> s.isSignal() && s.getName().equals(stepName))
                .findFirst()
                .orElseThrow(() -> new NotFoundException.StepNotFound(runId, stepName));
        Map<String, Object> output = payload == null ? Map.of() : payload;
        int size = payloadSize(output);
        if (size > MAX_PAYLOAD_BYTES) {
            throw new OperationNotAllowedException("A signal payload is handed to every later step, so it is limited to "
                    + MAX_PAYLOAD_BYTES + " bytes; this one has " + size,
                    Map.of("step", stepName, "payloadBytes", size));
        }
        RunStatus before = run.getStatus();
        Instant now = clock.instant();
        step.deliverSignal(output, now);
        record(run, OperatorAction.Type.SIGNAL, actor, reason == null ? "signal " + stepName : reason, before, now);
        log.info("Run {}: signal {} delivered by {}", runId, stepName, actor);
        announce(run, now);
    }

    private int payloadSize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload).length;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Signal payload cannot be serialised: " + e.getOriginalMessage(), e);
        }
    }

    /** Steps first, then the run: the engine's lock order, so an operator action cannot deadlock with a result. */
    private WorkflowRun lock(UUID runId) {
        steps.lockAllOfRun(runId);
        return runs.findByIdForUpdate(runId).orElseThrow(() -> new NotFoundException.RunNotFound(runId));
    }

    private void record(WorkflowRun run, OperatorAction.Type type, String actor, String reason, RunStatus before,
                        Instant now) {
        actions.save(new OperatorAction(run.getId(), type, actor, reason, before, now));
    }

    /** Wakes the dispatcher once this commits: a compensation may now be claimable. */
    private void announce(WorkflowRun run, Instant now) {
        for (StepExecution step : run.activeSteps()) {
            if (step.getNextAttemptAt() != null) {
                Instant at = step.getNextAttemptAt().isAfter(now) ? step.getNextAttemptAt() : now;
                events.publishEvent(new EngineEvents.StepsMayBeReady(at));
            }
        }
    }
}
