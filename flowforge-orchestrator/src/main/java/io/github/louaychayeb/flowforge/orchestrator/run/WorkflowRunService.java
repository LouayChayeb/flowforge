package io.github.louaychayeb.flowforge.orchestrator.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinition;
import io.github.louaychayeb.flowforge.orchestrator.definition.WorkflowDefinitionRepository;
import io.github.louaychayeb.flowforge.orchestrator.engine.EngineEvents;
import io.github.louaychayeb.flowforge.orchestrator.engine.RunTracing;
import io.github.louaychayeb.flowforge.orchestrator.error.IdempotencyKeyReusedException;
import io.github.louaychayeb.flowforge.orchestrator.error.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class WorkflowRunService {

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowRunRepository runs;
    private final ApplicationEventPublisher events;
    private final RunTracing tracing;
    private final Clock clock;
    /** Sorts map keys, so a request hash does not depend on the order the client wrote its fields in. */
    private final ObjectMapper canonicalJson;

    public WorkflowRunService(WorkflowDefinitionRepository definitions, WorkflowRunRepository runs,
                              ApplicationEventPublisher events, RunTracing tracing, Clock clock,
                              ObjectMapper objectMapper) {
        this.definitions = definitions;
        this.runs = runs;
        this.events = events;
        this.tracing = tracing;
        this.clock = clock;
        this.canonicalJson = objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /** A run as {@link #start} returns it: the one just created, or the one an earlier request with the key made. */
    public record Started(WorkflowRun run, boolean replayed) {
    }

    /**
     * Starts a run of the latest version of the named workflow. Only state is written here; the
     * dispatcher picks the first step up asynchronously (woken as soon as this commits), so this returns
     * immediately.
     */
    @Transactional
    public WorkflowRun start(String workflowName, Map<String, Object> input) {
        return start(workflowName, input, null).run();
    }

    /**
     * Like {@link #start(String, Map)}, but at most once per {@code idempotencyKey}: a request repeating a key returns
     * the run that key started ({@code replayed}) instead of starting another. The same order submitted twice — a
     * retry after a timeout, a double click — is then charged once.
     *
     * <p>Two requests with a new key can race: both find no run and both insert one. The unique index on the key lets
     * only one commit; the other fails with {@link DataIntegrityViolationException}, and the caller then finds the
     * winner with {@link #findByIdempotencyKey} ({@code RunStarter} does both).
     *
     * @throws IdempotencyKeyReusedException if the key already started a run of another workflow or input
     */
    @Transactional
    public Started start(String workflowName, Map<String, Object> input, String idempotencyKey) {
        String hash = idempotencyKey == null ? null : requestHash(workflowName, input);
        if (idempotencyKey != null) {
            Optional<WorkflowRun> existing = runs.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), idempotencyKey, hash);
            }
        }
        WorkflowDefinition definition = definitions.findFirstByNameOrderByVersionDesc(workflowName)
                .orElseThrow(() -> new NotFoundException.WorkflowNotFound(workflowName));
        Instant now = clock.instant();
        WorkflowRun run = WorkflowRun.start(definition, input, now);
        if (idempotencyKey != null) {
            run.assignIdempotencyKey(idempotencyKey, hash);
        }
        run.attachTrace(tracing.runStarted(workflowName, run.getId().toString()));
        // Flushed now, so a concurrent request with the same key fails here, on the unique index, not at commit.
        runs.saveAndFlush(run);
        // Steps without dependencies are claimable now; a timer among them, when it is due.
        run.activeSteps().stream().map(StepExecution::getNextAttemptAt).distinct()
                .forEach(at -> events.publishEvent(new EngineEvents.StepsMayBeReady(at)));
        return new Started(run, false);
    }

    /**
     * The run an Idempotency-Key started, for the request that lost the race to start it.
     *
     * @throws IdempotencyKeyReusedException if that run was started with another workflow or input
     */
    @Transactional(readOnly = true)
    public Optional<Started> findByIdempotencyKey(String workflowName, Map<String, Object> input, String idempotencyKey) {
        String hash = requestHash(workflowName, input);
        return runs.findByIdempotencyKey(idempotencyKey).map(run -> replay(run, idempotencyKey, hash));
    }

    private static Started replay(WorkflowRun run, String key, String hash) {
        if (!run.getRequestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException(key, run.getId());
        }
        run.getDefinition().getName(); // initialise while the session is open: the response names the workflow
        return new Started(run, true);
    }

    /**
     * A fingerprint of what the request asks for: the workflow and the input, with map keys sorted, so the same
     * order sent with its fields in another order still matches.
     */
    private String requestHash(String workflowName, Map<String, Object> input) {
        try {
            byte[] canonical = canonicalJson.writeValueAsBytes(Map.of("workflow", workflowName, "input", input));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Run input cannot be serialised: " + e.getOriginalMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    /**
     * Loads a run and maps it inside the transaction, so lazy associations can be read.
     */
    @Transactional(readOnly = true)
    public <T> T find(UUID runId, Function<WorkflowRun, T> mapper) {
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new NotFoundException.RunNotFound(runId));
        return mapper.apply(run);
    }
}
