# Correctness under concurrency

Phase 4 asks one question: with several orchestrator instances, several workers, duplicated and delayed messages, and attempts timing out while their results are in flight, does the stored history ever become wrong?

FlowForge answers it in three parts: **invariants** that define "correct", a **checker** that evaluates them against the database, and **tests** that create the worst conditions they can and then run the checker.

## The invariants

[`ConsistencyChecker`](../flowforge-orchestrator/src/main/java/io/github/louaychayeb/flowforge/orchestrator/consistency/ConsistencyChecker.java) evaluates ten invariants, each a SQL query that returns the rows breaking it. They run in one repeatable-read snapshot, so the result is consistent even while the engine is running.

| Invariant | What must hold |
|---|---|
| `attempt-history-complete` | Every step's attempts are numbered 1..attemptCount, no gaps, no extras |
| `single-open-attempt` | Only a `RUNNING` step has an open attempt, and it is its latest |
| `exactly-one-success` | A `COMPLETED` step has exactly one successful attempt, its last; any other step has none |
| `no-step-ahead-of-run` | No step beyond the run's current position was attempted or made eligible |
| `sequential-in-time` | Each step's first attempt started only after the previous step succeeded |
| `completed-run-is-complete` | A `COMPLETED` run has every step completed and nothing compensated |
| `finished-run-is-quiet` | A finished run has nothing running, retrying or eligible |
| `compensation-targets` | Each compensation undoes a distinct completed step that declared that compensation |
| `compensated-run-is-compensated` | A `COMPENSATED` run has compensations, and all of them completed |
| `command-for-every-attempt` | Every attempt has its step command in the outbox — the claim and the command committed together |

The same checks are available on a running system:

```bash
curl localhost:8080/api/admin/consistency
curl "localhost:8080/api/admin/consistency?workflow=order-processing"
```

```json
{ "checkedAt": "…", "workflow": null, "invariantsChecked": 10, "consistent": true, "violations": [] }
```

A checker that never fails proves nothing, so [`ConsistencyCheckerTest`](../flowforge-orchestrator/src/test/java/io/github/louaychayeb/flowforge/orchestrator/consistency/ConsistencyCheckerTest.java) corrupts healthy histories with SQL — a second successful attempt, a step made eligible ahead of the run, a completed run with a failed step — and asserts each corruption is reported.

## How concurrent operations are kept apart

| Operation | Locking |
|---|---|
| Claim a step (dispatcher) | `SELECT … FOR UPDATE SKIP LOCKED` on claimable steps; the claim, the attempt row and the outbox command commit together |
| Publish a command (outbox) | `SELECT … FOR UPDATE SKIP LOCKED` on pending outbox rows |
| Record a result (listener) | `SELECT … FOR UPDATE` on the step (waits) |
| Expire a lease (recovery) | `SELECT … FOR UPDATE` on each expired step, in its own transaction, re-checking that it is still expired |
| Everything | Optimistic `lock_version` on every entity as a backstop |

Recording a result and expiring a lease both lock the step first, so for any attempt exactly one of them wins. The loser sees an attempt that is no longer active and is rejected as stale. [`LeaseRaceTest`](../flowforge-orchestrator/src/test/java/io/github/louaychayeb/flowforge/orchestrator/engine/LeaseRaceTest.java) fires both at the same 40 steps from two threads and checks that every step ended in exactly one of the two outcomes, never both and never neither.

## The stress test

[`ConcurrencyStressTest`](../flowforge-orchestrator/src/test/java/io/github/louaychayeb/flowforge/orchestrator/engine/ConcurrencyStressTest.java) runs against real PostgreSQL and Kafka:

- 40 runs of a 4-step workflow; every fourth run is flagged to fail at the end and must be compensated
- 3 concurrent "orchestrator instances", each running the dispatch, outbox and recovery loops
- 3 worker consumers that report late 25% of the time (up to 2 s) and report twice 20% of the time
- a 1.5 s lease, so late reports regularly lose the race against lease expiry
- one step that fails retryably 30% of the time
- a background thread that keeps re-sending random outcomes for attempts that already finished

It then requires: no exception from any engine loop, every run in the status its input dictates, no invariant violated, and — so the clean result means something — that leases did expire and stale results were rejected.

A typical run:

```text
Stress run: 40 runs, attempts=616, timed out=404, retryable failures=22,
            stale results rejected=6478, leases expired=404, stale results injected=6313
```

## What the tests found

Writing these tests changed the code twice.

**1. One bad step could block all recovery.** Lease recovery originally expired a whole batch of steps in one transaction. The stress test picked up a deliberately corrupted step from another test, recovering it threw, and the rollback undid the recovery of every other step in the batch — on every tick, forever. Recovery now handles each step in its own transaction and logs a step it cannot recover instead of failing the batch. [`RecoveryTest.aStepThatCannotBeRecoveredDoesNotBlockTheOthers`](../flowforge-orchestrator/src/test/java/io/github/louaychayeb/flowforge/orchestrator/engine/RecoveryTest.java) guards it.

**2. Overload turned into a retry storm.** With 40 runs started at once and deliberately slow workers, commands queued for longer than the lease. Attempts timed out and were retried, and workers kept executing the timed-out commands anyway. Wasted work grew the queue, which caused more timeouts, until runs ran out of attempts. No invariant was broken — every run ended in a consistent, explainable state — but nearly every run ended *wrong*. The fix is load shedding: each `StepCommand` now carries its lease deadline, and workers skip commands that arrive after it. Skipped commands cost nothing, so a backlog drains instead of growing. In the run above, 404 of 616 attempts timed out during the initial burst, and every run still finished correctly.

## Live run

Two orchestrator instances (both running all three loops), three workers, and 200 orders spread across both orchestrators: 70% normal, 15% with a declined payment, 15% with a refused shipment. One worker was killed hard (`Stop-Process -Force`) after the first 100 orders were submitted.

```text
submitted 200 runs in 12s
all runs finished after 30s
statuses: COMPLETED=143, COMPENSATED=57; unexpected: 0
consistency: consistent=True, invariants=10, violations=0
orchestrator 8085: claimed=463 published=550 applied=368
orchestrator 8086: claimed=538 published=451 applied=633
worker 8082: executed 597 steps
worker 8083: executed 116 steps   (killed)
worker 8084: executed 288 steps
```

Both orchestrators shared the claiming, publishing and result handling, and the totals match: 1,001 claims, 1,001 published commands, 1,001 executions and 1,001 applied results.

## Metrics

| Metric | Meaning |
|---|---|
| `flowforge.steps.claimed` | Step attempts claimed |
| `flowforge.outbox.published` / `flowforge.outbox.failures` | Commands sent to Kafka / failed sends (retried) |
| `flowforge.results{result=applied}` | Results applied to a step |
| `flowforge.results{result=stale}` | Duplicate or late results rejected |
| `flowforge.leases.expired` | Attempts timed out by recovery |

All are under `/actuator/metrics/<name>`.

## Limits

- Lease deadlines are compared across machines, so they assume clocks are roughly synchronised. Skew only shifts when work is shed or retried; it cannot break an invariant, because the orchestrator's lock-protected state is the only authority.
- A result that keeps failing for a reason other than staleness is redelivered ten times by the Kafka error handler and then dropped. The step's lease then expires and the attempt is retried, so this costs time, not correctness.
- External side effects remain at-least-once; see the README's failure semantics.
