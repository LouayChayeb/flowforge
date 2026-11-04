# Crash-recovery demo

This demo kills a worker at the worst possible moment — **after it charged the customer, before it reported the charge** — and shows that FlowForge still finishes the order correctly.

## What happens

1. Worker A is started with crash injection for `CHARGE_PAYMENT`. It executes the charge, then dies abruptly (`Runtime.halt`, the equivalent of `kill -9`): no result is published and the command's Kafka offset is never committed.
2. The orchestrator still shows `charge-payment` as `RUNNING` on attempt 1, holding a lease.
3. Worker B is started. Kafka notices that worker A left the consumer group (10 s session timeout), hands its partitions to worker B, and redelivers the uncommitted command.
4. Worker B executes the charge again — with the **same idempotency key** (`stepExecutionId`), so a real payment provider would recognise the retry and not charge twice — and reports the result.
5. The orchestrator records the result for attempt 1 and moves on. The run completes with every step executed exactly once *as far as workflow state is concerned*.

If no worker had come back, the lease would have expired after 60 s and the recovery sweeper would have retried the step as attempt 2 — and a late result for attempt 1 would then have been rejected as stale.

## Run it

Start the infrastructure and build:

```bash
docker compose up -d
./mvnw install -DskipTests
```

Terminal 1 — orchestrator:

```bash
./mvnw -pl flowforge-orchestrator spring-boot:run
```

Terminal 2 — worker A, set to crash after charging:

```bash
./mvnw -pl flowforge-worker spring-boot:run \
  -Dspring-boot.run.arguments="--flowforge.worker.crash-after-step-type=CHARGE_PAYMENT"
```

Terminal 3 — register the workflow and start an order:

```bash
curl -X POST localhost:8080/api/workflows -H "Content-Type: application/json" \
     -d @docs/examples/order-processing.json

curl -X POST localhost:8080/api/workflows/order-processing/runs -H "Content-Type: application/json" \
     -d '{"input": {"orderId": "ORD-1042", "customerId": "CUS-91", "amount": 249.90}}'
```

Worker A logs `CRASH INJECTED after executing step charge-payment` and exits. Check the run (use the `runId` from the response):

```bash
curl localhost:8080/api/runs/<runId>
```

`charge-payment` is `RUNNING`, attempt 1, with a `leaseExpiresAt`. Now start worker B, without crash injection:

```bash
./mvnw -pl flowforge-worker spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

Within about 10 seconds, worker B receives the redelivered `CHARGE_PAYMENT` command and the run finishes. Check the run again: it is `COMPLETED`, `charge-payment` has a single attempt, and worker B's log shows the charge with the same idempotency key worker A used.

## Recorded output

From a run on Windows 11 with Docker Desktop (log prefixes trimmed):

```text
worker A: Charged 249.9 for order ORD-1042 (idempotency key 7eb96839-79cd-45e3-80bd-2664b65a0599)
worker A: CRASH INJECTED after executing step charge-payment (CHARGE_PAYMENT) attempt 1 — result not reported
          (process exits with code 137)

run ORD-1042: RUNNING
  validate-order     COMPLETED  attempts: SUCCEEDED
  reserve-inventory  COMPLETED  attempts: SUCCEEDED
  charge-payment     RUNNING    attempts: RUNNING
  create-shipment    PENDING
  send-confirmation  PENDING

(worker B started; 12 s later)

worker B: Charged 249.9 for order ORD-1042 (idempotency key 7eb96839-79cd-45e3-80bd-2664b65a0599)

run ORD-1042: COMPLETED
  validate-order     COMPLETED  attempts: SUCCEEDED
  reserve-inventory  COMPLETED  attempts: SUCCEEDED
  charge-payment     COMPLETED  attempts: SUCCEEDED
  create-shipment    COMPLETED  attempts: SUCCEEDED
  send-confirmation  COMPLETED  attempts: SUCCEEDED
```

Both workers called the payment step with the same idempotency key, and the workflow recorded one successful attempt.

## Other things to try

- **Kill the orchestrator** (Ctrl+C or kill it) while runs are in flight, then restart it. Claimed steps whose commands were still in the outbox are published after the restart; nothing is lost or repeated.
- **Stop Kafka** (`docker compose stop kafka`), start a run, then `docker compose start kafka`. The command waits in `outbox_event` and goes out once Kafka is back.
- **Compensation:** start an order with `"amount": 50000` (payment declined → inventory released) or `"country": "XX"` (shipment refused → payment refunded, then inventory released). The run ends `COMPENSATED`.
