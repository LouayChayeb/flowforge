# Performance on Kubernetes

Phase 5 deployed FlowForge to Minikube with the [Helm chart](../deploy/helm/flowforge) and measured how much work it handles, how fast, and what limits it. Every number below comes from [`loadtest/LoadTest.java`](../loadtest/LoadTest.java), an open-model load generator: it submits runs at a fixed rate whatever the response times, so an overloaded system cannot hide by slowing the load down.

## Setup

| | |
|---|---|
| Cluster | Minikube 1.37 (Kubernetes 1.34), Docker driver, **one node with 6 CPUs and 8 GB**, on a Windows laptop |
| Deployment | Chart defaults: 2 orchestrators, 3 workers, PostgreSQL and single-node Kafka (12 partitions) — all on that one node |
| Workflow | The 5-step `order-processing` workflow; 70% of orders complete, 30% fail at payment or shipment and are compensated (2–3 extra steps) |
| Client | Load generator on the host, through `kubectl port-forward` |
| Method | Each measurement runs 60 s after a warm-up run (see *Measurement notes*). Latency is `completedAt − createdAt` of each run, both server timestamps. Throughput is runs finished over the window from the first submission to the last completion |

Every run in every test below ended in the status its input dictates, and the [consistency checker](correctness.md) found no violations afterwards.

## Results

### Throughput and latency (chart defaults)

| Offered load | Throughput | Step attempts/s | Run latency p50 | p95 | p99 |
|---|---|---|---|---|---|
| 5 runs/s | 4.8 runs/s | 24 | 2.5 s | 3.1 s | 3.3 s |
| 20 runs/s | 19.0 runs/s | 95 | 2.1 s | 3.5 s | 4.4 s |
| 30 runs/s | 29.0 runs/s | 145 | 1.8 s | 2.6 s | 3.0 s |
| 40 runs/s | 38.4 runs/s | 192 | 2.6 s | 5.4 s | 7.5 s |
| 50 runs/s | **40.1 runs/s** (saturated) | 201 | 10.2 s | 22.9 s | 27.3 s |

The single node saturates at about **40 runs/s, or 200 step attempts/s**. Beyond that the excess queues: latency grows, but nothing fails, nothing times out, and nothing is retried — the system degrades into a backlog, not into errors.

At saturation the node's CPUs are ~95% busy, shared by PostgreSQL (the largest consumer), Kafka and the five JVMs, and the orchestrators' result consumers are the stage that falls behind (consumer lag ~700 against ~90 for the workers). There is no single slow component to blame: everything competes for six cores.

### Finding: the outbox published one message at a time

The first measurement was poor: at 10 runs/s the run latency p50 was ~10 s. Breaking a step's life down from the stored timestamps showed where the time went:

| Phase of one step | Average |
|---|---|
| Ready → claimed (dispatch loop) | 150 ms |
| Claimed → command published to Kafka (outbox loop) | **1,106 ms** |
| Claimed → result applied | 1,882 ms |

The outbox publisher sent each command and waited for Kafka's acknowledgement before sending the next, so each orchestrator could publish only one command per broker round trip — and under load a round trip on this node took tens of milliseconds. It now sends the whole batch and then waits for all the acknowledgements under one shared deadline; events whose send failed stay pending and are retried, the rest are marked published. Order is unaffected: a run's commands share a key, hence a partition, and the idempotent producer keeps order per partition. [`OutboxPublisherTest.sendsTheWholeBatchBeforeWaitingForAcknowledgements`](../flowforge-orchestrator/src/test/java/io/github/louaychayeb/flowforge/orchestrator/outbox/OutboxPublisherTest.java) pins the behaviour.

Same cluster, same procedure, both warmed up:

| | Sequential outbox | Pipelined outbox |
|---|---|---|
| 30 runs/s: latency p50 / p99 | 4.3 s / 12.3 s | 1.8 s / 3.0 s |
| 40 runs/s: throughput | 28.1 runs/s (saturated) | 38.4 runs/s |
| 40 runs/s: latency p50 | 28.4 s | 2.6 s |
| Saturation point | ~28 runs/s (141 attempts/s) | ~40 runs/s (201 attempts/s) |

**+43% capacity** from one change.

### Latency floor: polling intervals

At light load a 5-step run takes ~2.5 s, about 500 ms per step, and almost none of that is work: a step waits for the next dispatch tick (every 500 ms), then for the next outbox tick (every 200 ms). The intervals are configurable, so the floor can be traded against efficiency:

| Intervals (dispatch / outbox) | p50 at 5 runs/s | p99 at 5 runs/s | Throughput at 40 runs/s offered |
|---|---|---|---|
| 500 ms / 200 ms (default) | 2,474 ms | 3,287 ms | 38.4 runs/s |
| 100 ms / 50 ms | **523 ms** | 690 ms | 27.5 runs/s (saturated) |

Polling 5× faster cut the latency floor 5×, but cost a third of the peak capacity. The likely cause is batching: with short ticks each dispatch or outbox transaction carries fewer steps, so the same work needs more transactions and commits, and commits are expensive on this node (a synced 4 KB write takes ~16 ms). So shortening the intervals is the wrong fix; the next section is the right one.

### Instant dispatch

Instead of waiting for a tick, the engine now runs the dispatcher the moment a step becomes ready — right after the transaction that made it ready commits — and the outbox publisher the moment a claim commits ([`EngineWakeup`](../flowforge-orchestrator/src/main/java/io/github/louaychayeb/flowforge/orchestrator/engine/EngineWakeup.java)). The ticks stay as a safety net. It took three designs to get right, and the measurements are the reason:

| Design | p50 at 5 runs/s | Peak throughput |
|---|---|---|
| Polling only (500 ms / 200 ms ticks) | 2,478 ms | ~32–38 runs/s (see below) |
| v1: every wake-up starts its own pass | **114 ms** | 32–35 runs/s, 8–16% below polling |
| v2: one runner per loop, 20 ms linger | 317–333 ms | worse under heavy overload (one run fell to 23 runs/s) |
| **v3 (current): v2, and a busy loop ignores wake-ups** | **342 ms** | **same as polling** (interleaved A/B below) |

- **v1** was the fastest at light load but, under load, gave each orchestrator two dispatchers and two publishers (the tick's and the wake-up's) competing for the same rows and each taking small batches — the batching problem again.
- **v2** gave each loop exactly one runner per instance ([`EngineLoops`](../flowforge-orchestrator/src/main/java/io/github/louaychayeb/flowforge/orchestrator/engine/EngineLoops.java)): ticks and wake-ups only *request* a pass, and a request during a pass makes it go round once more. A 20 ms linger lets work that appears together share a pass. Under heavy overload the single runner then never paused — every result requested another, nearly empty, pass.
- **v3** adds one rule: after a pass that handled at least a full batch, the loop is *busy* and ignores wake-ups, leaving the work to pile up for the next tick and its full batches; as soon as a pass finds less than a batch, wake-ups run at once again. Light load gets immediate dispatch, heavy load gets batching, and the switch needs no tuning.

The peak comparison needed care. Over a day of load tests the database grew to 72,000 runs and 360,000 outbox rows, and capacity drifted down for *every* build — the polling build measured 37.8 runs/s in the morning and 32.4 in the evening. Single back-to-back measurements of different builds were therefore unreliable (they overstated v1's cost), so the final comparison is interleaved on one build, toggling only `flowforge.engine.wakeup`, at 40 runs/s offered:

| Wake-ups | 1st run | 2nd run | Mean |
|---|---|---|---|
| off (polling) | 32.3 runs/s | 32.4 runs/s | 32.4 |
| on (v3) | 34.5 runs/s | 31.7 runs/s | 33.1 |

Within noise of each other: instant dispatch made light-load runs **7× faster (2.5 s → 0.34 s)** at no measurable cost in peak throughput. The difference between v1's 114 ms and v3's 342 ms is the 20 ms linger paid at two hops per step; `flowforge.engine.wakeup-linger` can trade it back.

### Worker scaling

The demo step handlers return instantly, which makes worker capacity meaningless, so for this test every step sleeps 100 ms (`worker.simulatedLatency`), standing in for a call to an external service. One worker pod has 3 consumer threads, so it should manage ~30 steps/s, or ~6 runs/s. Offered load: 12 runs/s (~60 steps/s).

| Workers | Throughput | Step attempts/s | Run latency p50 | p95 |
|---|---|---|---|---|
| 1 | 5.5 runs/s (saturated) | 27.5 | 64 s | 83 s |
| 3 | 10.4 runs/s | 52.1 | 3.5 s | 10.7 s |

One worker saturates where predicted. Three workers carry the load, but with a long tail, and the reason is the partition layout: 12 partitions spread over 9 consumer threads gives three threads two partitions each, and those threads are fully busy at this load while the others idle half the time. Parallelism is governed by partitions, not pods — the chart defaults `kafka.partitions` to 12 so that 2, 3, 4 or 6 worker pods with 3 threads each divide it evenly or nearly so.

Even with one worker at twice its capacity, no lease expired and no attempt was retried: commands waited in Kafka for less than the 60 s lease, and overload showed up only as queueing.

### Killing pods under load

At 20 runs/s, about 30 s into the measured minute, one worker pod and one orchestrator pod were force-deleted (`kubectl delete pod --grace-period=0 --force`, no graceful shutdown). The load generator stayed connected to the surviving orchestrator.

| Orchestrator consumer session timeout | Throughput | Run latency p50 | p95 | p99 |
|---|---|---|---|---|
| Kafka default (45 s) | 10.8 runs/s | 55.1 s | 71.1 s | 76.7 s |
| 10 s (now the default) | 14.9 runs/s | **2.9 s** | 28.4 s | 31.8 s |

In both runs every one of the 1,200 runs ended in the right status, the consistency checker found nothing, no lease expired and no attempt was retried. Kubernetes replaced both pods, and the uncommitted commands and results of the dead ones were redelivered to the survivors.

**Finding: the orchestrator waited 45 s to notice a dead peer.** Workers already used a 10 s Kafka session timeout, but the orchestrator's result consumers had Kafka's default of 45 s. When an orchestrator died, the results on its partitions waited until the group noticed it was gone — 45 s in which a share of all runs could not advance. With 10 s the median run no longer notices the failure. The remaining tail belongs to the runs whose messages were on the dead pods' partitions: they wait out the session timeout, the rebalance, and a node busy starting two replacement JVMs.

The whole cluster also survived a full restart: Docker Desktop was stopped overnight with Minikube running, and on `minikube start` every pod came back on its own, with all state in PostgreSQL and Kafka's volumes.

## Measurement notes

- **Warm-up matters.** Right after a deployment the JVMs are cold, and the first measurement of the pipelined outbox at 10 runs/s reported a p50 of 11 s, against 2.4 s a minute later. Every number above follows a warm-up run; an earlier, cold comparison overstated the outbox improvement and is not reported.
- **The load generator is gentle with its own polling.** It finds out when runs finish by fetching them, and each fetch loads a run's full history. Fetching a large backlog flat out competed with the engine for the database and lowered the measured throughput, so it polls with 4 requests in flight and pauses 3 s between rounds.
- **Capacity drifts as the database grows.** The outbox keeps every command ever sent — the `command-for-every-attempt` invariant relies on it — and after a day of load tests it held 360,000 rows; peak throughput fell by about 15% over that day. Compare builds interleaved on the same database, never across hours.
- **One node is the ceiling.** PostgreSQL, Kafka and all the services share six cores, so these numbers describe this cluster, not the design's limit. On separate machines the orchestrators' and workers' capacity would grow with replicas until PostgreSQL became the bottleneck.

## Next steps

- **Outbox retention.** Archive published outbox rows of finished runs (or partition the table by time), and scope the `command-for-every-attempt` invariant to unarchived runs, so capacity stops drifting with history.
- **Cheaper result handling.** Applying a result loads the step, its attempts, the run and its steps; a narrower query path would cut database work per step.
- **A multi-node run**, with PostgreSQL and Kafka on their own nodes, to find where horizontal scaling stops.
