package io.github.louaychayeb.flowforge.orchestrator.engine;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps each workflow run in one distributed trace although its work keeps leaving the thread it started on:
 * a run waits in PostgreSQL between steps, commands wait in the outbox, and every hop to Kafka and back is a new
 * thread. Normal context propagation would start a new trace at each of those gaps.
 *
 * <p>So the trace context is stored with the data that crosses the gap, as a W3C {@code traceparent} string:
 * a run remembers the context it started under, every claimed attempt gets a span that is a child of it, and the
 * attempt's context is stored on its outbox row. The publisher sends the command inside that context, so Kafka's
 * {@code traceparent} header carries it to the worker, whose spans — the handler, the call to the payment provider,
 * the result going back — all join the same trace.
 *
 * <p>When tracing is off (no tracer, or {@code management.tracing.enabled=false}) every method is a no-op and
 * nothing is stored.
 */
@Component
public class RunTracing {

    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-0[01]$");

    private final ObjectProvider<Tracer> tracerProvider;

    public RunTracing(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    /**
     * Records a short "run started" span — a child of the current span, e.g. the HTTP request, if there is one —
     * and returns its context to store on the run, or {@code null} when tracing is off.
     */
    public String runStarted(String workflow, String runId) {
        Tracer tracer = tracer();
        if (tracer == null) {
            return null;
        }
        Span span = tracer.nextSpan().name("workflow.run " + workflow)
                .tag("flowforge.run.id", runId).tag("flowforge.workflow", workflow).start();
        span.end();
        return format(span.context());
    }

    /**
     * Records the claim of one attempt as a span in the run's trace and returns that span's context, to be stored
     * on the attempt's outbox row; {@code null} when tracing is off or the run has no trace.
     */
    public String stepClaimed(String runTraceParent, String runId, String stepName, String stepType, int attempt) {
        Tracer tracer = tracer();
        TraceContext parent = parse(tracer, runTraceParent);
        if (parent == null) {
            return null;
        }
        Span span = tracer.spanBuilder().setParent(parent).name("step " + stepName)
                .tag("flowforge.run.id", runId).tag("flowforge.step.name", stepName)
                .tag("flowforge.step.type", stepType).tag("flowforge.step.attempt", String.valueOf(attempt)).start();
        span.event("claimed");
        span.end();
        return format(span.context());
    }

    /**
     * Runs {@code work} — sending one outbox event — inside a "publish" span that continues the stored trace, so
     * the Kafka producer's instrumentation puts that trace in the message headers.
     */
    public <T> T inTrace(String traceParent, String spanName, Supplier<T> work) {
        Tracer tracer = tracer();
        TraceContext parent = parse(tracer, traceParent);
        if (parent == null) {
            return work.get();
        }
        Span span = tracer.spanBuilder().setParent(parent).name(spanName).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.get();
        } finally {
            span.end();
        }
    }

    /** The trace id of a stored {@code traceparent}, for linking the dashboard to the tracing UI. */
    public static String traceId(String traceParent) {
        if (traceParent == null) {
            return null;
        }
        Matcher m = TRACEPARENT.matcher(traceParent);
        return m.matches() ? m.group(1) : null;
    }

    private Tracer tracer() {
        Tracer tracer = tracerProvider.getIfAvailable();
        return tracer == null || tracer == Tracer.NOOP ? null : tracer;
    }

    private static TraceContext parse(Tracer tracer, String traceParent) {
        if (tracer == null || traceParent == null) {
            return null;
        }
        Matcher m = TRACEPARENT.matcher(traceParent);
        if (!m.matches()) {
            return null;
        }
        return tracer.traceContextBuilder().traceId(m.group(1)).spanId(m.group(2)).sampled(true).build();
    }

    private static String format(TraceContext context) {
        if (context == null || context.traceId() == null || context.spanId() == null) {
            return null;
        }
        String traceId = context.traceId();
        if (traceId.length() == 16) {
            traceId = "0000000000000000" + traceId;
        }
        return "00-" + traceId + "-" + context.spanId() + "-01";
    }
}
