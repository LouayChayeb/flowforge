# FlowForge

[![CI](https://github.com/LouayChayeb/flowforge/actions/workflows/ci.yml/badge.svg)](https://github.com/LouayChayeb/flowforge/actions/workflows/ci.yml)

**A durable workflow execution engine that runs multi-step jobs reliably — even when processes crash, messages are duplicated, or downstream services are unavailable.**

FlowForge is a compact, explicit take on the durable-execution model used by workflow orchestration platforms such as Temporal. It does not aim to compete with them on features. Its goal is to implement — and prove with tests — the hard parts of reliable distributed execution:

- transaction boundaries between a database and a message broker (transactional outbox)
- at-least-once delivery and idempotent processing
- an explicit step state machine with concurrency control
- leases, retries with backoff, and recovery after process failure
- saga-style compensation for partially completed business operations

> **Status:** All five phases complete, plus parallel steps, instant dispatch, an idempotent payment provider and end-to-end tracing. It runs on Kubernetes from a Helm chart and handles 30–40 five-step workflow runs per second (up to 200 step executions/s) on a single 6-CPU Minikube node; at light load a run takes about 0.34 s from request to completion — see [performance](docs/performance.md). Commands go through a transactional outbox, running steps hold leases, failed runs are compensated, and a [consistency checker](docs/correctness.md) verifies ten invariants of the execution history. They hold under a stress test with concurrent engine instances, duplicated and late messages, and lease races, and in a live run with two orchestrators, three workers and a killed worker. See the [crash-recovery demo](docs/recovery-demo.md) and the [roadmap](#roadmap).

---

## The problem

An e-commerce order has to be validated, inventory reserved, the payment charged, a shipment created and a confirmation sent. Written as plain method calls:

```java
validateOrder(order);
reserveInventory(order);
chargePayment(order);
createShipment(order);
sendConfirmation(order);
```

If the process crashes right after `chargePayment`, what happens on restart? Start over and charge the customer twice? Skip ahead and lose track of the reservation? And what if the payment call *timed out* but the charge actually went through?

FlowForge is the machinery that remembers what should happen, records what has happened, decides what to retry, and resumes safely after an interruption.

## How it works

```mermaid
flowchart LR
    client([Client application]) -- REST --> api[Orchestrator<br/>Spring Boot]
    api <--> db[(PostgreSQL<br/>definitions · runs · steps<br/>attempts · outbox)]
    workers -- Idempotency-Key --> psp[Payment provider<br/>simulated]
    db --> outbox[Outbox publisher]
    outbox -- step commands --> kafka{{Kafka}}
    kafka -- commands --> workers[Workers<br/>Spring Boot]
    workers -- step results --> kafka
    kafka -- results --> api
```

| Component | Responsibility |
|---|---|
| **Orchestrator** (`flowforge-orchestrator`) | REST API, workflow definitions, the step state machine, scheduling the next eligible step, recovery |
| **PostgreSQL** | Durable source of truth: definitions, runs, step executions, attempt history, outbox |
| **Kafka** | Transports step commands to workers and results back. It moves messages; it is *not* the workflow engine |
| **Workers** (`flowforge-worker`) | Execute individual steps (validate, reserve, check fraud, charge, ship, notify) and report outcomes |
| **Payment provider** (`flowforge-payments`) | A simulated external service that remembers idempotency keys, so a charge step that runs twice charges once |
| **Common** (`flowforge-common`) | Message contracts shared by orchestrator and workers |

More detail, including how each failure mode is handled, is in [docs/architecture.md](docs/architecture.md).

## Dashboard

The orchestrator serves a live dashboard at its root, **http://localhost:8080/** (no separate frontend, no build step). It is meant for demos and for learning how the engine works:

- **Live** — one-click orders for every outcome (normal, fraud detected in a parallel step, flaky carrier, carrier down, declined payment, undeliverable address, invalid order, failed refund, slow shipment), and a diagram of how a step travels with live rates per edge. Clicking a component explains what it does and which classes and methods do it.
- **Run detail** — the run's state machine and the path it took, its steps laid out by dependency (parallel steps side by side) and its undo steps, with attempts and lease countdowns, what the payment provider recorded for the order, a link to the run's distributed trace, and a timeline rebuilt from the stored attempts and outbox rows, each event explaining the code behind it.
- **Engine** — counters, leases and retry backoffs in progress, database totals, the ten invariants checked every 5 s, the payment provider's ledger (duplicates absorbed, orders charged twice: 0), and the configuration the instance runs with.
- **Kafka** — which consumer owns which partition, committed offsets and lag, and a guide to killing a worker and watching its partitions move.
- **Learn** — the life of an order, failure modes and what handles them, the guarantees, a searchable map of every class, and a glossary.

"Take the tour" walks through it; "Guide mode" shows the explanations everywhere.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/workflows` | Register a workflow definition; posting an existing name registers its next version |
| `GET` | `/api/workflows/{name}` | Latest version of a definition |
| `POST` | `/api/workflows/{name}/runs` | Start a run; returns `202 Accepted` immediately with a `Location` to poll |
| `GET` | `/api/runs/{runId}` | Run status, every step's state and the full attempt history |
| `GET` | `/api/admin/consistency` | Check the stored history against FlowForge's [invariants](docs/correctness.md); `?workflow=` to narrow it |

Read-only endpoints behind the dashboard:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/workflows` | Latest version of every workflow |
| `GET` | `/api/runs?limit=&status=` | Most recent runs with progress summaries |
| `GET` | `/api/runs/{runId}/commands` | The run's step commands: when each was written to the outbox and sent to Kafka |
| `GET` | `/api/admin/stats` | This instance's counters, database-wide counts, leased and waiting steps |
| `GET` | `/api/admin/config` | The engine configuration this instance runs with |
| `GET` | `/api/admin/kafka` | Topics, consumer groups, partition ownership and lag |
| `GET` | `/api/admin/consistency/invariants` | Names and meaning of the ten invariants |

Errors are returned as RFC 9457 problem details (`application/problem+json`).

**1. Register a workflow** ([example file](docs/examples/order-processing.json)):

```bash
curl -X POST localhost:8080/api/workflows -H "Content-Type: application/json" \
     -d @docs/examples/order-processing.json
```

**2. Start a run:**

```bash
curl -i -X POST localhost:8080/api/workflows/order-processing/runs -H "Content-Type: application/json" \
     -d '{"input": {"orderId": "ORD-1042", "customerId": "CUS-91", "amount": 249.90}}'
```

```json
{ "runId": "7c0e…", "workflow": "order-processing", "version": 1, "status": "RUNNING" }
```

**3. Follow it:**

```bash
curl localhost:8080/api/runs/7c0e…
```

```json
{
  "runId": "7c0e…",
  "workflow": "order-processing",
  "status": "COMPLETED",
  "currentStep": null,
  "steps": [
    { "name": "validate-order",    "type": "VALIDATE_ORDER",    "status": "COMPLETED", "attemptCount": 1, "attempts": [ … ] },
    { "name": "reserve-inventory", "type": "RESERVE_INVENTORY", "status": "COMPLETED", "attemptCount": 1, "attempts": [ … ] },
    { "name": "check-fraud",       "type": "CHECK_FRAUD",       "status": "COMPLETED", "attemptCount": 1, "attempts": [ … ] },
    { "name": "charge-payment",    "type": "CHARGE_PAYMENT",    "status": "COMPLETED", "attemptCount": 1, "attempts": [ … ] },
    { "name": "create-shipment",   "type": "CREATE_SHIPMENT",   "status": "COMPLETED", "attemptCount": 1, "attempts": [ … ] },
    { "name": "send-confirmation", "type": "SEND_CONFIRMATION", "status": "COMPLETED", "attemptCount": 1, "attempts": [ … ] }
  ]
}
```

The example workflow runs `reserve-inventory` and `check-fraud` **in parallel** — both only need a valid order — and `charge-payment` waits for both (`"dependsOn": ["reserve-inventory", "check-fraud"]`). A step without `dependsOn` waits for the step before it; `"dependsOn": []` starts at once.

The sample handlers are simulated. These inputs show the other paths:

| Input | What happens | Final run status |
|---|---|---|
| `"amount": 0` | `validate-order` fails permanently; nothing to undo | `FAILED` |
| `"amount": 50000` | `charge-payment` is declined after inventory was reserved → `RELEASE_INVENTORY` runs | `COMPENSATED` |
| `"country": "XX"` | `create-shipment` fails after reserve and charge → `REFUND_PAYMENT`, then `RELEASE_INVENTORY` | `COMPENSATED` |
| `"shipmentFailures": 2` | `create-shipment` fails retryably twice, backs off 1 s then 2 s, succeeds on attempt 3 | `COMPLETED` |
| `"shipmentFailures": 3` or more | `create-shipment` runs out of attempts → refund, then release | `COMPENSATED` |
| `"country": "XX", "refundFails": true` | Shipment refused, then the refund is rejected too | `COMPENSATION_FAILED` |
| `"shipmentDelayMs": 8000` | `create-shipment` takes 8 s — time to watch its lease or kill its worker | `COMPLETED` |
| `"fraud": true` | `check-fraud` fails while its parallel sibling `reserve-inventory` succeeds; the run waits for the sibling, then releases the inventory | `COMPENSATED` |

Steps opt into compensation with `"compensateWith": "<STEP_TYPE>"` in the definition. Compensations appear in the run's `steps` with `"kind": "COMPENSATION"`, and go through the same retries and leases as forward steps. If a compensation itself fails, the run ends `COMPENSATION_FAILED` for manual attention.

## How a step executes

```mermaid
sequenceDiagram
    participant D as Orchestrator<br/>dispatcher
    participant DB as PostgreSQL
    participant P as Orchestrator<br/>outbox publisher
    participant K as Kafka
    participant W as Worker
    participant L as Orchestrator<br/>result listener
    D->>DB: one transaction: lock step (SKIP LOCKED),<br/>step → RUNNING + lease, insert attempt,<br/>insert StepCommand into outbox
    P->>DB: lock pending outbox rows
    P->>K: StepCommand (key = runId)
    P->>DB: mark published
    K->>W: deliver to one worker in the group
    W->>W: run StepHandler (a charge calls the payment<br/>provider with Idempotency-Key = step id)
    W->>K: StepResult
    W-->>K: commit command offset
    K->>L: deliver result
    L->>DB: lock step + run, apply outcome,<br/>release lease, make ready steps eligible
```

1. **Claim:** as soon as a step becomes ready — the transaction that made it ready has just committed — the dispatcher wakes up (a timer every 500 ms is the safety net), locks eligible steps with `SELECT … FOR UPDATE SKIP LOCKED`, moves them to `RUNNING` with a lease, opens an attempt row and writes the `StepCommand` to the **outbox** — all in one transaction. There is no moment where the database says "dispatched" but no command exists.
2. **Publish:** woken by the claim's commit, the outbox publisher sends pending commands to `flowforge.step-commands` (keyed by run id, so a run's messages stay ordered) and marks them published. If Kafka is down, commands wait in the outbox and go out when it is back.
3. **Execute:** one worker in the `flowforge-workers` consumer group runs the handler, publishes a `StepResult` to `flowforge.step-results`, and only then commits the command's offset (at-least-once).
4. **Record:** the orchestrator applies the result through the state machine:
   - success makes every step whose dependencies are now complete eligible (independent steps run in parallel), or completes the run
   - a retryable failure schedules a retry with exponential backoff, or fails the step once attempts run out
   - a permanent failure fails the step immediately
   - a failed step stops new steps from starting, waits for parallel steps still in flight, then compensates the completed steps that declare a compensation, most recently completed first
5. **Recover:** a sweeper times out `RUNNING` steps whose lease expired without a result (worker died, command lost) and retries them like any retryable failure.

Every state change is validated (terminal states can never be left), results must name the attempt they belong to, and every entity carries an optimistic-lock version. A duplicated or late result names an attempt that is no longer active, so it is rejected and dropped without touching state.

## Failure semantics

| What goes wrong | What FlowForge does |
|---|---|
| Orchestrator dies between claiming a step and publishing its command | The command was committed to the outbox with the claim; the publisher sends it after restart |
| Kafka is unavailable | Commands stay in the outbox and are published when it recovers |
| Outbox publisher sends a command, then dies before marking it published | The command is sent again; the duplicate result is rejected as stale |
| Worker dies mid-step | Its uncommitted command is redelivered to another worker; if no result arrives, the lease expires and the step is retried |
| A result and a lease expiry race for the same attempt | Both lock the step row first; exactly one wins, the other is rejected as stale |
| Workers fall behind and commands outlive their lease | Each command carries its lease deadline; workers skip expired commands instead of doing wasted work |
| A result arrives twice, or late from a timed-out attempt | Rejected: it does not name the step's active attempt |
| A step keeps failing | Bounded retries with exponential backoff, then the step fails |
| A later step fails permanently | Completed steps are compensated in reverse order of completion |
| A step fails while a parallel sibling is running | No new step starts; the sibling finishes (and is undone if it succeeded) before compensation begins |
| A worker charges, then dies before reporting | The charge step runs again with the same idempotency key; the payment provider replays the first charge, so the customer is charged once |

What it does **not** guarantee on its own: an external side effect (a charge, an email) can execute more than once if a worker dies after doing it and before reporting it. Handlers receive a stable `stepExecutionId` to use as an idempotency key with the external system; that is the only way to make such a side effect exactly-once. The demo's simulated payment provider (`flowforge-payments`) shows it working: it stores every key, answers a repeat with the original charge and `Idempotent-Replayed: true`, and its ledger proves no order is ever charged twice.

## Tech stack

| Technology | Used for |
|---|---|
| Java 17 + Spring Boot 3 | Orchestrator API, orchestration logic, workers |
| Spring Data JPA + Hibernate | Persistence mapping and transactional operations |
| PostgreSQL + Flyway | Durable workflow state, versioned schema migrations |
| Apache Kafka | Asynchronous step commands and results |
| Testcontainers | Integration tests against real PostgreSQL and Kafka |
| Docker / Docker Compose | Reproducible local infrastructure and images |
| Kubernetes + Helm | Deploying, restarting and scaling orchestrator and workers |
| OpenTelemetry + Jaeger | One distributed trace per run, across the database, Kafka and the payment provider |
| Micrometer + Prometheus + Grafana | Metrics, latency histograms and a ready-made dashboard |
| GitHub Actions | Tests, Docker image builds and Helm chart lint on every push |

## Getting started

**Prerequisites:** JDK 17+, Docker Desktop.

```bash
# 1. Start PostgreSQL, Kafka and Kafka UI
docker compose up -d

# 2. Build and run all tests
./mvnw verify

# 3. Install the modules so each one can be run on its own
./mvnw install -DskipTests

# 4. Run the orchestrator (http://localhost:8080)
./mvnw -pl flowforge-orchestrator spring-boot:run

# 5. Run the simulated payment provider (http://localhost:8084)
./mvnw -pl flowforge-payments spring-boot:run

# 6. Run one or more workers, each in its own terminal, pointing at the payment provider
./mvnw -pl flowforge-worker spring-boot:run -Dspring-boot.run.arguments=--flowforge.worker.payments-url=http://localhost:8084
./mvnw -pl flowforge-worker spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082 --flowforge.worker.payments-url=http://localhost:8084"
```

Without `--flowforge.worker.payments-url` a worker simulates payments in memory. Give the orchestrator `--flowforge.insight.payments-url=http://localhost:8084` to show the provider's ledger on the dashboard.

**Traces and metrics (optional):** `docker compose --profile observability up -d` adds Jaeger (http://localhost:16686), Prometheus (http://localhost:9090) and Grafana with the FlowForge dashboard (http://localhost:3000). Start the services with `FLOWFORGE_TRACING_ENABLED=true` (or `--management.tracing.enabled=true`) to send traces, and the orchestrator with `--flowforge.insight.trace-url=http://localhost:16686/trace/{traceId}` for "open trace" links on the dashboard.

On Windows use `mvnw.cmd` instead of `./mvnw`. If port 8080 is taken, start the orchestrator with `-Dspring-boot.run.arguments=--server.port=8085`.

Kafka distributes the three partitions of `flowforge.step-commands` across the running workers. Start or stop workers while runs are in progress and watch the partitions rebalance in Kafka UI.

| Service | URL |
|---|---|
| Orchestrator health | http://localhost:8080/actuator/health |
| Worker health | http://localhost:8081/actuator/health |
| Payment provider | http://localhost:8084/v1/summary |
| Kafka UI | http://localhost:8090 |
| PostgreSQL | `localhost:5432`, db/user/password `flowforge` |

### On Kubernetes

Docker images, a Helm chart and Minikube instructions are in [deploy/README.md](deploy/README.md):

```bash
helm install flowforge deploy/helm/flowforge -n flowforge --create-namespace --wait
```

## Project structure

```
flowforge/
├── flowforge-common/          # Messages (StepCommand, StepResult, Topics), StepHandler SPI, StepCommandProcessor
├── flowforge-orchestrator/    # REST API, state machine, dispatcher, persistence
│   └── src/main/java/…/orchestrator/
│       ├── api/               # Controllers, DTOs, error handling
│       ├── definition/        # Workflow definitions and versioning
│       ├── run/               # Runs, step executions, attempts, state machine
│       ├── engine/            # Claiming, result listener, lease recovery, retry policy, scheduler, wake-ups, tracing, metrics
│       ├── outbox/            # Transactional outbox and its publisher
│       ├── consistency/       # Invariant checker behind /api/admin/consistency
│       └── insight/           # Read-only queries and Kafka inspection for the dashboard
│   └── src/main/resources/static/   # The dashboard (plain HTML, CSS and JavaScript)
├── flowforge-worker/          # Kafka command listener
│   └── src/main/java/…/worker/
│       ├── handlers/          # Sample order-processing step handlers
│       └── payments/          # Payment gateway: HTTP to the provider, or simulated
├── flowforge-payments/        # Simulated payment provider: idempotency keys, its own ledger
├── deploy/helm/flowforge/     # Helm chart: orchestrator, workers, payments, PostgreSQL, Kafka, observability
├── deploy/observability/      # Prometheus and Grafana configuration for docker compose
├── loadtest/                  # Open-model load generator and Kubernetes runner
├── docs/                      # Architecture, correctness and performance notes
├── Dockerfile                 # Layered, non-root image for either service
├── docker-compose.yml         # Local PostgreSQL + Kafka + Kafka UI (+ Jaeger, Prometheus, Grafana)
└── .github/workflows/ci.yml   # Tests, image builds, chart lint
```

## Roadmap

- [x] **Phase 0 — Scaffold:** multi-module build, local infrastructure, CI
- [x] **Phase 1 — Core engine (no Kafka):** workflow definitions, runs, sequential step execution, explicit state machine, persisted transitions, run status API
- [x] **Phase 2 — Asynchronous execution:** move steps to worker processes; commands and results over Kafka; consumer groups and partitioning
- [x] **Phase 3 — Durability and recovery:** transactional outbox, idempotent consumers, bounded retries with exponential backoff, leases and a recovery sweeper, compensation — plus the [crash-recovery demo](docs/recovery-demo.md)
- [x] **Phase 4 — Concurrency and correctness:** multiple workers and orchestrators on the same workload; a consistency checker, a lease-race test and a chaos stress test prove no double-claiming and no stale-message corruption — see [docs/correctness.md](docs/correctness.md)
- [x] **Phase 5 — Deployment and performance:** Docker images, Helm chart, Minikube deployment, throughput/latency measurements, documented guarantees and limits — see [deploy/README.md](deploy/README.md) and [docs/performance.md](docs/performance.md)
- [x] **Beyond the plan:** a live, explanatory [dashboard](#dashboard); instant dispatch on commit instead of polling ([performance](docs/performance.md#instant-dispatch)); parallel steps with `dependsOn`; a simulated payment provider that proves a step run twice charges once; one OpenTelemetry trace per run, Prometheus metrics and a Grafana dashboard

## License

[MIT](LICENSE)
