package io.github.louaychayeb.flowforge.orchestrator.operator;

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
import java.util.UUID;

/**
 * Manual interventions on runs: cancelling, and retrying a compensation that failed. Each one is a single
 * transaction that locks every step of the run and then the run — the same order the engine uses — so it can
 * neither interleave with a result being applied nor deadlock with it, and each one is recorded in the audit log.
 */
@Service
public class OperatorService {

    /** Attempts a retried compensation gets on top of those it used. */
    static final int RETRY_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);

    private final WorkflowRunRepository runs;
    private final StepExecutionRepository steps;
    private final OperatorActionRepository actions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OperatorService(WorkflowRunRepository runs, StepExecutionRepository steps, OperatorActionRepository actions,
                           ApplicationEventPublisher events, Clock clock) {
        this.runs = runs;
        this.steps = steps;
        this.actions = actions;
        this.events = events;
        this.clock = clock;
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
