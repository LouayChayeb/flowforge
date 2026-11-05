// Everything the dashboard explains, kept apart from the rendering code.
// Class and method names are the real ones in this repository.

export const COMPONENTS = {
  api: {
    title: 'REST API',
    role: 'Accepts workflow definitions and order requests, and reports progress. It never does step work itself.',
    how: [
      'Starting a run is one database transaction: <code>WorkflowRunService.start()</code> loads the latest version of the workflow, <code>WorkflowRun.start()</code> creates one <code>StepExecution</code> per step and makes every step without dependencies eligible (<code>next_attempt_at = now</code>). Then the API answers <b>202 Accepted</b> with a <code>Location</code> header.',
      'Everything after that happens in the background loops, so a slow worker never makes an HTTP request slow, and a crash after the 202 loses nothing: the run is already in PostgreSQL.',
    ],
    code: [
      ['WorkflowController.startRun()', 'POST /api/workflows/{name}/runs → 202 Accepted'],
      ['WorkflowController.register()', 'POST /api/workflows — stores a new numbered version; runs keep the version they started with'],
      ['WorkflowRunService.start()', 'the transaction that creates the run and its steps'],
      ['RunController.get()', 'GET /api/runs/{id} — the full history: every step, every attempt'],
      ['ApiExceptionHandler', 'turns errors into RFC 7807 problem responses (404, 400)'],
    ],
    failure: 'If the orchestrator dies right after answering, the run is already committed; any other instance picks it up.',
  },
  postgres: {
    title: 'PostgreSQL',
    role: 'The single source of truth. Every decision the engine makes is read from here and written back here.',
    how: [
      'Tables: <code>workflow_definition</code> and <code>step_definition</code> (the recipe), <code>workflow_run</code> (one order), <code>step_execution</code> (one logical step of a run, stable id across retries), <code>step_attempt</code> (one try, never overwritten) and <code>outbox_event</code> (messages waiting to be sent).',
      'Every entity has a <code>lock_version</code> column: Hibernate refuses to save a row that someone else changed in the meantime (optimistic locking). On top of that the engine takes row locks: <code>FOR UPDATE SKIP LOCKED</code> to claim, <code>FOR UPDATE</code> to apply results and expire leases.',
      'The schema is created by Flyway migrations <code>V1</code>–<code>V5</code>; Hibernate only validates that the entities match it.',
    ],
    code: [
      ['StepExecutionRepository.lockClaimable()', 'SELECT … WHERE status IN (PENDING, RETRY_WAIT) AND next_attempt_at <= now FOR UPDATE SKIP LOCKED'],
      ['StepExecutionRepository.findByIdForUpdate()', 'row lock used when a result arrives or a lease expires'],
      ['OutboxEventRepository.lockPending()', 'unpublished outbox rows, SKIP LOCKED'],
      ['db/migration/V2…V5', 'the schema, with CHECK constraints that make impossible states unstorable'],
    ],
    failure: 'Database constraints back up the code: e.g. a RUNNING step must have a lease, a finished run must have completed_at.',
  },
  dispatcher: {
    title: 'Dispatcher (claim loop)',
    role: 'Finds steps that are ready, claims them, and writes the command to send — all in one transaction.',
    how: [
      '<code>EngineWakeup</code> runs it the moment a step becomes ready — right after the transaction that made it ready commits — and <code>EngineScheduler.dispatch()</code> runs it every <span data-config="dispatchIntervalMs"></span> as a safety net. Either way it calls <code>StepDispatcher.dispatchOnce()</code> until there is nothing left, which calls <code>StepStateService.claim()</code>.',
      'For each locked step, <code>StepExecution.start()</code> moves it to <code>RUNNING</code>, appends a new <code>StepAttempt</code> and sets <code>lease_expires_at = now + lease</code>. The <code>StepCommand</code> for the worker is saved as an <code>OutboxEvent</code> <b>in the same transaction</b>.',
      '<code>SKIP LOCKED</code> is what lets several orchestrators run this loop at once: a row another instance is claiming is skipped instead of waited for, so no step is ever claimed twice.',
    ],
    code: [
      ['EngineScheduler.dispatch()', '@Scheduled loop; drain() repeats while there is work'],
      ['StepDispatcher.dispatchOnce()', 'one batch of up to batch-size claims'],
      ['StepStateService.claim()', 'lock → start attempt → write outbox row, one transaction'],
      ['StepExecution.start()', 'PENDING/RETRY_WAIT → RUNNING, new attempt, lease set'],
      ['ClaimedStep.toCommand()', 'builds the StepCommand, with the lease expiry as its deadline'],
    ],
    failure: 'If the orchestrator dies mid-claim the transaction rolls back: the step is still claimable, nothing was sent.',
  },
  outbox: {
    title: 'Outbox publisher',
    role: 'Sends the committed commands to Kafka and marks them sent.',
    how: [
      'Why an outbox: committing to the database and sending to Kafka are two systems. Done separately, a crash between them either loses a command (claimed but never sent) or invents one (sent but rolled back). Writing the command into the same transaction as the claim makes them one atomic fact; this loop only has to deliver it.',
      '<code>EngineWakeup</code> runs it as soon as a claim commits (and <code>EngineScheduler.publishOutbox()</code> every <span data-config="outboxIntervalMs"></span> as a safety net). <code>OutboxPublisher.publishPending()</code> locks a batch of pending rows, sends them all, then waits for every acknowledgement (<code>acks=all</code>, idempotent producer) under one shared timeout, and marks the acknowledged ones published.',
      'Sending the whole batch before waiting is a Phase 5 fix: the one-at-a-time version capped throughput at ~28 runs/s; batching raised it to ~40.',
    ],
    code: [
      ['OutboxPublisher.publishPending()', 'send all, then await all acks; failures stay pending with last_error'],
      ['OutboxEvent.markPublished() / recordFailure()', 'bookkeeping per row: attempts, error'],
    ],
    failure: 'If the process dies after Kafka acknowledged but before the row is marked, the command is sent again. Delivery is at-least-once, so consumers must tolerate duplicates — they do.',
  },
  commands: {
    title: 'Kafka: flowforge.step-commands',
    role: 'Carries "run this step" commands from the orchestrators to the workers.',
    how: [
      'Messages are JSON <code>StepCommand</code>s keyed by run id, so all commands of one run go to the same partition and stay in order.',
      'The topic has <span data-config="partitions"></span> partitions. Workers share them through the consumer group <code>flowforge-workers</code>; each partition is read by exactly one worker thread at a time.',
    ],
    code: [
      ['Topics.STEP_COMMANDS', 'topic name shared by both services'],
      ['StepCommand', 'eventId, runId, stepExecutionId, stepName, stepType, attempt, input, deadline'],
      ['KafkaTopicsConfiguration', 'declares the topics on startup (also declared by the worker, so either can start first)'],
    ],
    failure: 'Kafka keeps messages until a consumer commits past them, so a command survives a worker crash.',
  },
  workers: {
    title: 'Workers',
    role: 'Stateless processes that execute one step attempt per command and report the outcome.',
    how: [
      '<code>StepCommandListener.onCommand()</code> receives a command and calls <code>StepCommandProcessor.process()</code>: if the command\'s <b>deadline</b> (its lease) has already passed it is skipped — the orchestrator has given up on that attempt anyway (load shedding). Otherwise the <code>StepHandler</code> for the step type runs.',
      'The handler\'s answer becomes a <code>StepResult</code>: <code>SUCCEEDED</code>, <code>RETRYABLE_FAILURE</code> or <code>PERMANENT_FAILURE</code>. An unknown step type is permanent; an exception is retryable.',
      'The worker sends the result, <b>waits for Kafka to acknowledge it</b>, and only then lets the listener commit the command\'s offset. Die before that and the command is redelivered to another worker — at-least-once.',
      'Real side effects (charging a card) cannot be undone by a database rollback, so handlers get the stable <code>stepExecutionId</code> to use as an <b>idempotency key</b> with the outside service.',
    ],
    code: [
      ['StepCommandListener.onCommand()', 'consume → process → send result → (offset commit)'],
      ['StepCommandProcessor.process()', 'deadline check, handler lookup, error classification'],
      ['StepHandler', 'the interface each step type implements: stepType(), execute(context)'],
      ['ValidateOrder… SendConfirmationHandler', 'the demo order steps; Release/RefundPayment are the undo steps'],
      ['CrashInjector', 'demo only: kill the process right after a given step type'],
    ],
    failure: 'A dead worker\'s partitions move to the others after the 10 s session timeout; its unacknowledged command runs again there.',
  },
  results: {
    title: 'Kafka: flowforge.step-results',
    role: 'Carries outcomes back from the workers to the orchestrators.',
    how: [
      'JSON <code>StepResult</code>s, keyed by run id. Orchestrators read them through the consumer group <code>flowforge-orchestrator</code>, so with two orchestrators each applies the results of its own partitions.',
    ],
    code: [
      ['StepResult', 'eventId, runId, stepExecutionId, attempt, outcome, errorMessage'],
      ['StepOutcome', 'SUCCEEDED, RETRYABLE_FAILURE, PERMANENT_FAILURE'],
    ],
    failure: 'Results can arrive twice, or late after the attempt was timed out — the listener rejects those as stale.',
  },
  listener: {
    title: 'Result listener',
    role: 'Applies each result to the step, then moves the run forward, retries, compensates or finishes.',
    how: [
      '<code>StepResultListener.onResult()</code> calls <code>StepStateService.recordOutcome()</code>, which locks the step row and then the run row (so outcomes of parallel steps are applied one after the other) and checks that the result is for the step\'s <b>current, still-running</b> attempt. If not — a duplicate, or an attempt already timed out — it throws <code>StaleAttemptException</code> and the result is counted as <b>stale</b> and ignored.',
      'Success: <code>StepExecution.complete()</code> → <code>WorkflowRun.onStepCompleted()</code> makes every step whose dependencies are now all complete eligible — several at once if they are independent — or ends the run.',
      'Retryable failure: <code>failRetryable()</code> → <code>RETRY_WAIT</code> with <code>next_attempt_at = now + backoff</code>, until the step\'s attempt limit; then it fails for good.',
      'Permanent failure: <code>failPermanently()</code> → <code>WorkflowRun.onStepFailed()</code>: nothing new starts; once no parallel sibling is still in flight, compensations are appended for the completed steps, most recently completed first, and the run moves to <code>COMPENSATING</code> — or straight to <code>FAILED</code> if nothing needs undoing.',
    ],
    code: [
      ['StepResultListener.onResult()', '@KafkaListener on step-results'],
      ['StepStateService.recordOutcome()', 'row lock, stale check, apply outcome'],
      ['StepExecution.complete() / failRetryable() / failPermanently()', 'the step state machine'],
      ['WorkflowRun.onStepCompleted() / onStepFailed()', 'the run state machine and compensation'],
      ['RetryPolicy.backoffAfter()', 'initial × multiplier^(attempt−1), capped'],
    ],
    failure: 'Because results and lease expiry both lock the step row, for any attempt exactly one of them wins.',
  },
  recovery: {
    title: 'Recovery sweeper',
    role: 'Notices attempts whose lease expired without a result, and retries them.',
    how: [
      '<code>EngineScheduler.recoverExpiredLeases()</code> runs every <span data-config="recoveryIntervalMs"></span>. <code>StepStateService.recoverExpiredLeases()</code> finds RUNNING steps whose lease is past and handles <b>each one in its own transaction</b>: lock it, re-check it is still expired (a result may have just arrived), then <code>StepExecution.expireLease()</code> marks the attempt <code>TIMED_OUT</code> and retries or fails the step like any retryable failure.',
      'One transaction per step is a Phase 4 fix: a single bad row used to roll back the whole batch, every tick, forever.',
    ],
    code: [
      ['StepStateService.recoverExpiredLeases()', 'per-step transactions via TransactionTemplate'],
      ['StepExecution.expireLease()', 'attempt → TIMED_OUT, then retry-or-fail'],
      ['StepExecutionRepository.findExpiredLeaseIds()', 'RUNNING steps with lease_expires_at < now'],
    ],
    failure: 'This is what makes a run finish even if a worker vanished without Kafka redelivering its command.',
  },
  checker: {
    title: 'Consistency checker',
    role: 'Verifies that the stored history obeys ten rules that must hold whatever crashed or was duplicated.',
    how: [
      '<code>ConsistencyChecker.check()</code> runs ten SQL queries in one read-only, repeatable-read snapshot; each returns the rows breaking its rule. An empty result everywhere means the history is consistent.',
      'The concurrency stress test runs it after three engine instances, duplicated and late messages and lease races. The Engine tab runs it live.',
    ],
    code: [
      ['ConsistencyChecker.check()', 'GET /api/admin/consistency'],
      ['ConsistencyCheckerTest', 'corrupts healthy data on purpose to prove each rule catches it'],
    ],
    failure: 'It reads only; it never repairs. A violation means a bug to investigate.',
  },
};

export const RUN_STATES = {
  RUNNING: 'Forward steps are being executed: each as soon as its dependencies completed, independent ones in parallel.',
  COMPLETED: 'Every forward step succeeded.',
  FAILED: 'A step failed for good and nothing before it needed undoing.',
  COMPENSATING: 'A step failed for good and nothing else is in flight; the undo steps for what already succeeded are running, most recently completed first.',
  COMPENSATED: 'Every undo step succeeded: the order failed, but cleanly — nothing is left half-done.',
  COMPENSATION_FAILED: 'An undo step itself failed. The system cannot restore consistency on its own: a human has to look — and once the cause is fixed, an operator can retry it (POST /api/runs/{id}/retry).',
  CANCELLED: 'An operator cancelled the run (POST /api/runs/{id}/cancel). Nothing new started, steps already running were allowed to finish, and everything that had completed was undone.',
};

export const STEP_STATES = {
  PENDING: 'Waiting for its turn. It becomes claimable when every step it depends on has completed (next_attempt_at is set).',
  RUNNING: 'Claimed: an attempt is open and the step holds a lease until a result arrives or the lease expires.',
  RETRY_WAIT: 'The last attempt failed in a retryable way (or timed out). Claimable again once its backoff is over.',
  COMPLETED: 'One attempt succeeded. Terminal: no later message can change it.',
  FAILED: 'Failed permanently, or ran out of attempts. Terminal.',
};

export const ATTEMPT_STATES = {
  RUNNING: 'Sent to a worker, no result yet.',
  SUCCEEDED: 'The worker reported success.',
  RETRYABLE_FAILURE: 'The worker reported a temporary problem (timeout, outage). The step will be retried.',
  PERMANENT_FAILURE: 'The worker reported a problem retrying cannot fix (declined card, invalid data).',
  TIMED_OUT: 'No result before the lease expired. The recovery sweeper closed it; a result arriving later is ignored.',
};

export const TIMELINE_WHY = {
  created: 'POST /runs → <code>WorkflowRunService.start()</code>. One transaction stores the run and one <code>step_execution</code> row per step; the steps without dependencies are made eligible, and <code>EngineWakeup</code> runs the dispatcher as soon as it commits.',
  claimed: '<code>StepStateService.claim()</code> locked this step with <code>SKIP LOCKED</code>, opened attempt <b>{attempt}</b> (<code>StepExecution.start()</code>), set the lease and wrote the command to the outbox — one transaction.',
  published: '<code>OutboxPublisher.publishPending()</code> sent the command to <code>flowforge.step-commands</code>, got Kafka\'s acknowledgement, and marked the outbox row published.',
  succeeded: 'A worker ran the handler and reported success. <code>StepStateService.recordOutcome()</code> locked the step, checked this is its current attempt, and <code>StepExecution.complete()</code> + <code>WorkflowRun.onStepCompleted()</code> made every step whose dependencies are now complete eligible (or finished the run).',
  retryable: 'A worker reported a retryable failure. <code>StepExecution.failRetryable()</code> put the step in <code>RETRY_WAIT</code> with a backoff from <code>RetryPolicy</code> (or failed it, if this was its last allowed attempt).',
  exhausted: 'A worker reported a retryable failure, but this was the step\'s last allowed attempt, so <code>StepExecution.failRetryable()</code> failed the step for good and <code>WorkflowRun.onStepFailed()</code> decided what the run does next — here, compensate.',
  permanent: 'A worker reported a permanent failure. <code>StepExecution.failPermanently()</code> ended the step and <code>WorkflowRun.onStepFailed()</code> decided what the run does next.',
  timedout: 'No result before the lease expired. The recovery sweeper (<code>StepStateService.recoverExpiredLeases()</code>) locked the step, re-checked it, and <code>StepExecution.expireLease()</code> closed the attempt as TIMED_OUT.',
  compensating: 'A step failed for good. Once no other step was in flight, <code>WorkflowRun</code> appended an undo step for each completed step with <code>compensateWith</code>, most recently completed first. The run is now COMPENSATING.',
  finished: 'The run reached a terminal status and recorded <code>completed_at</code>. Nothing can change it any more.',
  waiting: 'Backoff in progress: the dispatcher will claim this step again once <code>next_attempt_at</code> has passed.',
  timer: 'A durable timer (a WAIT step). Its due time was stored in <code>next_attempt_at</code> when its dependencies completed; the dispatcher claimed it once due and <code>StepStateService.fireTimer()</code> completed it in the same transaction — no command, no worker. A restart in between changes nothing: the due time is in the database.',
  cancelled: 'An operator called <code>POST /api/runs/{id}/cancel</code>. <code>OperatorService.cancel()</code> locked the steps and the run, recorded an <code>operator_action</code> row, and <code>WorkflowRun.cancel()</code> withdrew every eligible step. Once nothing was in flight, the completed steps were compensated.',
  retried: 'An operator called <code>POST /api/runs/{id}/retry</code>. <code>WorkflowRun.retryCompensation()</code> reopened the failed undo step with extra attempts (its history is kept) and put the run back into COMPENSATING.',
  inflight: 'The command is with a worker (or queued in Kafka). If no result comes back before the lease expires, the recovery sweeper will time this attempt out.',
};

export const JOURNEY = [
  ['You start an order', 'POST /api/workflows/order-processing/runs. <code>WorkflowRunService.start()</code> stores the run and its six steps in one transaction and answers 202. Only <i>validate-order</i> is eligible — it is the one step with no dependencies.'],
  ['A dispatcher claims the first step', 'Right after that commit, <code>EngineWakeup</code> runs the dispatch loop: it locks the step (<code>FOR UPDATE SKIP LOCKED</code>), opens attempt 1 with a 60 s lease, and writes a <code>StepCommand</code> to the outbox — one transaction.'],
  ['The outbox publisher sends it', 'As soon as the claim commits, the publisher sends pending commands to <code>flowforge.step-commands</code> (keyed by run id), waits for the acknowledgements, and marks them published.'],
  ['A worker executes it', 'The worker owning that partition checks the deadline, runs <code>ValidateOrderHandler</code>, sends a <code>StepResult</code>, waits for the ack, and only then commits the command\'s offset.'],
  ['The orchestrator applies the result', '<code>StepResultListener</code> locks the step, checks the attempt is current, marks it COMPLETED, and the run makes <i>reserve-inventory</i> and <i>check-fraud</i> eligible together — they run in parallel, and <i>charge-payment</i> waits for both. The loop repeats for every step.'],
  ['A step fails — temporarily', 'A retryable failure puts the step in RETRY_WAIT for 1 s, then 2 s, 4 s… (<code>RetryPolicy</code>), up to its attempt limit. Try <i>Flaky carrier</i>.'],
  ['A step fails — for good', 'A permanent failure (declined card, impossible address) or exhausted attempts make the run compensate: nothing new starts, any parallel step still in flight is allowed to finish, then undo steps run for what completed, most recent first — refund, then release inventory. Try <i>Fraud detected</i>, <i>Payment declined</i> or <i>Undeliverable address</i>.'],
  ['A worker dies mid-step', 'Its command was never committed, so Kafka redelivers it to another worker after the 10 s session timeout, and the step runs again with the same idempotency key. If no worker gets it, the lease expires and the sweeper retries. Try <i>Slow shipment</i> and kill the worker.'],
  ['A result arrives twice, or late', 'The listener only accepts a result for the step\'s current running attempt; anything else is stale and ignored. Visible as <i>stale results</i> on the Engine tab.'],
  ['The run finishes', 'COMPLETED, COMPENSATED, FAILED or COMPENSATION_FAILED, with <code>completed_at</code>. The consistency checker can verify its whole history at any time.'],
];

export const FAILURES = [
  ['Database commits but the Kafka send fails', 'Transactional outbox: the command is in the same transaction as the claim; the publisher retries until Kafka acknowledges.', '—'],
  ['Kafka delivers a message twice', 'Results are matched on step id + attempt; a duplicate or old result is rejected as stale.', 'Engine tab: stale results'],
  ['A worker dies mid-step', 'Redelivery after the 10 s session timeout; if nothing comes back, the lease expires and the sweeper retries.', 'Slow shipment + kill'],
  ['A step keeps failing', 'Bounded retries with exponential backoff, retryable vs permanent errors.', 'Flaky carrier, Carrier down'],
  ['A business step fails after others succeeded', 'Saga compensation: undo steps run in reverse order.', 'Payment declined, Undeliverable'],
  ['A step fails while a parallel sibling is still running', 'No new step starts; the sibling finishes (and is undone if it succeeded) before compensation begins.', 'Fraud detected'],
  ['A charge step runs twice (worker died after charging)', 'The payment provider remembers the idempotency key and replays the first charge; the customer is charged once.', 'Engine tab: payment provider'],
  ['An undo step fails too', 'The run stops in COMPENSATION_FAILED for a human to resolve.', 'Refund fails'],
  ['Two orchestrators claim at once / a result races a lease expiry', 'SKIP LOCKED for claims; row locks for results and expiry, so exactly one wins.', 'Run two orchestrators'],
  ['Workers fall behind', 'Commands carry their deadline; expired ones are skipped so a backlog drains instead of storming.', 'Send 20 mixed orders'],
];

export const GUARANTEES = {
  yes: [
    'Each logical step attempt changes the stored state <b>exactly once</b>: duplicates and late results are rejected.',
    'A run never loses a step: claims and commands commit together, and every claimed attempt either gets a result or times out.',
    'A step starts only after every step it depends on completed (independent steps run in parallel); undo steps run one at a time, after everything in flight finished; a finished run can never change again.',
    'Several orchestrators and workers can run at once without claiming the same step twice.',
  ],
  no: [
    'Message delivery is <b>at least once</b>, so a step\'s handler can run more than once.',
    'External side effects are therefore not automatically exactly-once: they are safe only if the external service accepts an idempotency key (FlowForge passes <code>stepExecutionId</code>) or can be reconciled.',
    'Lease deadlines assume roughly synchronised clocks; skew changes when work is retried, never the correctness of the history.',
  ],
};

export const GLOSSARY = [
  ['Workflow definition', 'A named, versioned list of steps (the recipe). Registering it again creates version n+1; running orders keep their version.'],
  ['Run', 'One execution of a workflow for one input, e.g. one order.'],
  ['Dependencies / parallel steps', 'A step lists the earlier steps it waits for (dependsOn). Steps whose dependencies are all complete run at the same time; a step with none starts at once. Omitted, it waits for the previous step.'],
  ['Wake-up (instant dispatch)', 'Running the dispatch and outbox loops the moment work appears, after the transaction that created it commits, instead of only on a timer. The timer stays as a safety net.'],
  ['Payment provider', 'flowforge-payments: a simulated external service that remembers idempotency keys. The proof that a step executed twice charges once.'],
  ['Step execution', 'One logical step of a run. Its id stays the same across retries, which is what makes it usable as an idempotency key.'],
  ['Attempt', 'One try of a step. Attempts are appended, never overwritten, so the full history of failures stays visible.'],
  ['Transactional outbox', 'Writing an outgoing message into the database in the same transaction as the state change, and sending it afterwards. Makes "changed state" and "will send message" one atomic fact.'],
  ['Lease', 'A deadline on a claimed attempt. If no result arrives before it, the attempt is presumed lost and retried.'],
  ['SKIP LOCKED', 'A PostgreSQL locking option: rows locked by another transaction are skipped instead of waited for. Lets several dispatchers claim different steps at the same time.'],
  ['Optimistic locking', 'Every row has a version number; saving a row that changed since it was read fails. A backstop against lost updates.'],
  ['At-least-once delivery', 'A message may be delivered more than once, but never zero times. Achieved by committing the offset only after the work is done.'],
  ['Idempotency', 'Doing something twice has the same effect as doing it once. Required of every step handler, because of at-least-once delivery.'],
  ['Idempotency key', 'A unique id sent with a request so the receiver can recognise a repeat. FlowForge uses the step execution id.'],
  ['Stale result', 'A result for an attempt that is no longer the step\'s running one — a duplicate, or one that arrived after the lease expired. Ignored.'],
  ['Retryable vs permanent failure', 'Retryable: might work next time (timeout, outage). Permanent: will never work (invalid input, declined). Decided by the handler.'],
  ['Exponential backoff', 'Waiting longer after each failure: 1 s, 2 s, 4 s… up to a maximum, so a struggling service is not hammered.'],
  ['Saga / compensation', 'A long business process without one big transaction. If a later step fails, earlier completed steps are undone by explicit compensating steps, in reverse order.'],
  ['Load shedding', 'Dropping work that is no longer useful. Workers skip commands whose deadline passed, so a backlog shrinks instead of growing.'],
  ['Kafka partition', 'A topic is split into partitions; order is guaranteed within a partition. Messages with the same key (here: run id) go to the same one.'],
  ['Consumer group', 'Consumers sharing the work of a topic: each partition is assigned to exactly one member at a time.'],
  ['Offset / lag', 'Offset: a position in a partition. Committed offset: how far a group has processed. Lag: messages written but not yet processed.'],
  ['Rebalance', 'Reassigning partitions when a group member joins or leaves (or stops sending heartbeats for the session timeout).'],
  ['Step output', 'A small JSON object a step returns on success (at most 64 KB), stored on its row and handed to every later step: release-inventory reads the reservationId reserve-inventory produced, send-confirmation the tracking number.'],
  ['Durable timer', 'A WAIT step: "wait 20 s, then continue". Its due time is stored, so it survives restarts and needs no worker; the orchestrator completes it itself.'],
  ['Cancellation', 'An operator stops a running run: nothing new starts, running steps finish, completed ones are undone, and the run ends CANCELLED.'],
  ['Operator action', 'A manual intervention (cancel, retry compensation), recorded with who, why and the status before it in operator_action — an audit trail.'],
  ['Problem details (RFC 9457)', 'The standard JSON error format the API returns, with a stable code (RUN_NOT_FOUND, OPERATION_NOT_ALLOWED…), a type URN and the trace id.'],
  ['Invariant', 'A rule the stored data must always satisfy, e.g. "a completed step has exactly one successful attempt".'],
];

// Every main class, by module and package.
export const CODE_MAP = [
  { module: 'flowforge-common', pkg: 'messaging', text: 'The messages both services exchange.', classes: [
    ['StepCommand', 'record', 'A command to run one attempt of one step.', [
      ['eventId, runId, stepExecutionId, stepName, stepType, attempt, input, deadline', 'deadline = the attempt\'s lease expiry; workers skip commands past it']]],
    ['StepResult', 'record', 'The outcome of one attempt, sent back by a worker.', [['eventId, runId, stepExecutionId, attempt, outcome, errorMessage', '']]],
    ['StepOutcome', 'enum', 'SUCCEEDED, RETRYABLE_FAILURE, PERMANENT_FAILURE.', []],
    ['Topics', 'constants', 'The two topic names, flowforge.step-commands and flowforge.step-results.', []],
  ]},
  { module: 'flowforge-common', pkg: 'step', text: 'The contract between the engine and step code.', classes: [
    ['StepHandler', 'interface', 'Implemented once per step type.', [['stepType()', 'which type it handles, e.g. CHARGE_PAYMENT'], ['execute(StepContext)', 'does the work, returns a StepHandlerResult']]],
    ['StepContext', 'record', 'What a handler receives: runId, stepExecutionId (the idempotency key), stepName, attempt, input, and the outputs of completed steps.', [['outputOf(step)', 'what an earlier step produced'], ['requireOutput(step, key)', 'one value, or a permanent failure if it is missing']]],
    ['StepFailureException', 'sealed exception', 'What a handler throws instead of building a failure result.', [['Retryable', 'temporary: retried with backoff'], ['Permanent', 'never works: fails the step'], ['InvalidInput', 'a Permanent for bad input']]],
    ['StepHandlerResult', 'record', 'What a handler returns.', [['succeeded()', ''], ['retryableFailure(msg)', 'try again later'], ['permanentFailure(msg)', 'give up on this step']]],
    ['StepCommandProcessor', 'class', 'Runs a command against the right handler, safely.', [
      ['process(StepCommand)', 'empty if the deadline passed; unknown type → permanent; exception → retryable'],
      ['isExpired(StepCommand)', 'deadline check (load shedding)'], ['stepTypes()', 'the types it can handle']]],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'api', text: 'HTTP endpoints. Thin: they delegate to services.', classes: [
    ['WorkflowController', 'REST', '/api/workflows', [['register()', 'POST — new version of a definition'], ['all()', 'GET — latest version of each'], ['latest()', 'GET /{name}'], ['startRun()', 'POST /{name}/runs → 202']]],
    ['RunController', 'REST', '/api/runs', [['recent()', 'GET — newest runs (dashboard)'], ['get()', 'GET /{id} — full history'], ['commands()', 'GET /{id}/commands — its outbox rows']]],
    ['AdminController', 'REST', '/api/admin', [['consistency()', 'run the invariants'], ['invariants()', 'their names and meaning'], ['stats()', 'counters + database counts'], ['config()', 'this instance\'s settings'], ['kafka()', 'topics, groups, lag']]],
    ['WorkflowDtos / RunDtos', 'records', 'Request and response bodies, with from() mappers from the entities.', []],
    ['ApiExceptionHandler', 'advice', 'Every error as an RFC 9457 problem with a stable code, details and the trace id.', [['domain()', 'FlowForgeException → its own status and code'], ['concurrency()', 'lock conflicts → 409, safe to retry'], ['handleMethodArgumentNotValid()', '400 listing every invalid field'], ['unexpected()', '500, logged, no internals leaked']]],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'error', text: 'The exception hierarchy.', classes: [
    ['ErrorCode', 'enum', 'Each error code with its HTTP status, title and type URN.', []],
    ['FlowForgeException', 'abstract exception', 'Base of every domain error: a code plus structured details.', []],
    ['NotFoundException', 'exceptions', 'WorkflowNotFound, RunNotFound, StepNotFound → 404.', []],
    ['OperationNotAllowedException', 'exception', 'An operator action its current state forbids → 409.', []],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'operator', text: 'Manual interventions, audited.', classes: [
    ['OperatorService', 'service', 'Cancel and retry, each in one transaction with its audit row.', [['cancel(runId, actor, reason)', 'locks steps then run, WorkflowRun.cancel(), wakes the engine'], ['retryCompensation(runId, actor, reason)', 'reopens the failed undo step with 3 more attempts']]],
    ['OperatorAction', 'entity', 'Who did what to which run, why, and the status before.', []],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'definition', text: 'Workflow recipes.', classes: [
    ['WorkflowDefinition', 'entity', 'Name, version, ordered steps.', [['addStep(name, type, maxAttempts, compensationType)', '']]],
    ['StepDefinition', 'entity', 'One step of the recipe: position, type, attempt limit, optional undo type.', []],
    ['WorkflowDefinitionService', 'service', 'Registers and loads definitions.', [['register(name, steps)', 'validates unique names, assigns the next version'], ['latest(name)', ''], ['latestOfEach()', '']]],
    ['WorkflowDefinitionRepository', 'repository', 'findFirstByNameOrderByVersionDesc, findLatestVersion, findLatestOfEach.', []],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'run', text: 'Runs, steps, attempts and their state machines — the domain model.', classes: [
    ['WorkflowRun', 'entity', 'One order. Owns its steps, their dependency graph and the run state machine.', [
      ['start(definition, input, now)', 'creates the forward steps, makes those without dependencies eligible'],
      ['onStepCompleted(step, now)', 'every step whose dependencies are now complete becomes eligible; or COMPLETED / COMPENSATED'],
      ['onStepFailed(step, now)', 'waits for in-flight siblings, then appends compensations (most recently completed first) → COMPENSATING, else FAILED; during compensation → COMPENSATION_FAILED'],
      ['activeSteps() / currentStep()', 'the steps that may run now (several with parallel steps)']]],
    ['StepExecution', 'entity', 'One logical step; the step state machine.', [
      ['start(now, lease)', 'claim: RUNNING, new attempt, lease'], ['complete(attempt, now)', ''],
      ['failRetryable(attempt, error, backoff, now)', 'RETRY_WAIT or FAILED if out of attempts'],
      ['failPermanently(attempt, error, now)', ''], ['expireLease(backoff, now)', 'attempt TIMED_OUT, then retry or fail'],
      ['activeAttempt(n)', 'throws StaleAttemptException unless n is the running attempt']]],
    ['StepAttempt', 'entity', 'One try: number, status, start/finish, error. Appended, never overwritten.', [['finish(outcome, error, now)', '']]],
    ['RunStatus / StepStatus / AttemptStatus / StepKind', 'enums', 'States and their allowed transitions (canTransitionTo, isTerminal). StepKind is FORWARD or COMPENSATION.', []],
    ['StaleAttemptException / IllegalStateTransitionException', 'exceptions', 'A result for an old attempt; a forbidden state change.', []],
    ['WorkflowRunService', 'service', 'start(name, input) and find(runId).', []],
    ['StepExecutionRepository', 'repository', 'The locking queries.', [['lockClaimable(now, limit)', 'SKIP LOCKED'], ['findExpiredLeaseIds(cutoff, limit)', ''], ['findByIdForUpdate(id)', 'pessimistic row lock']]],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'engine', text: 'The background loops that drive execution.', classes: [
    ['EngineScheduler', 'component', 'The three @Scheduled loops; each drains its work before sleeping.', [['dispatch()', ''], ['publishOutbox()', ''], ['recoverExpiredLeases()', '']]],
    ['EngineWakeup', 'component', 'Runs the dispatch and outbox loops right after a commit makes work visible (and when a retry backoff ends), instead of waiting for their ticks. Cut light-load latency from ~2.5 s to well under a second.', [['onStepsMayBeReady(event)', 'after commit: dispatch now, or at the end of a backoff'], ['onCommandsWritten(event)', 'after commit: publish now']]],
    ['EngineEvents', 'records', 'StepsMayBeReady and CommandsWritten, published inside the engine\'s transactions.', []],
    ['StepDispatcher', 'component', 'dispatchOnce(): one batch of claims.', []],
    ['StepStateService', 'service', 'Every state change of a step goes through here.', [
      ['claim(limit)', 'lock, start, write outbox — one transaction'],
      ['recordOutcome(stepId, attempt, result)', 'lock, stale check, apply'],
      ['recoverExpiredLeases(cutoff)', 'one transaction per expired step']]],
    ['StepResultListener', 'Kafka listener', 'onResult(): applies results; counts stale ones.', []],
    ['RetryPolicy', 'component', 'backoffAfter(attempt) = initial × multiplier^(attempt−1), capped at max.', []],
    ['EngineProperties', 'config', 'batch-size, lease-duration, publish-timeout, retry.*', []],
    ['EngineMetrics', 'component', 'Micrometer counters: claimed, applied, stale, leases expired, outbox published / failed.', []],
    ['ClaimedStep', 'record', 'What a claim produced; toCommand() builds the StepCommand.', []],
    ['EngineConfiguration / KafkaTopicsConfiguration', 'config', 'Enables scheduling, provides the clock, declares topics.', []],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'outbox', text: 'The transactional outbox.', classes: [
    ['OutboxEvent', 'entity', 'A message to send: topic, key, JSON payload, created/published, attempts, last error.', []],
    ['OutboxEventRepository', 'repository', 'lockPending(limit) with SKIP LOCKED.', []],
    ['OutboxPublisher', 'component', 'publishPending(): send the batch, await all acks, mark published.', []],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'consistency', text: 'Correctness checks over the stored history.', classes: [
    ['ConsistencyChecker', 'component', 'Ten SQL invariants in one repeatable-read snapshot.', [['check(workflow)', ''], ['invariants()', '']]],
    ['ConsistencyReport', 'record', 'checkedAt, consistent, violations with samples.', []],
  ]},
  { module: 'flowforge-orchestrator', pkg: 'insight', text: 'Read-only views for this dashboard; plain SQL, no locks.', classes: [
    ['InsightQueries', 'component', 'recentRuns(), commandsOfRun(), databaseStats().', []],
    ['KafkaInspector', 'component', 'Uses Kafka\'s AdminClient to describe topics, consumer groups, assignments and lag.', []],
    ['EngineSettings', 'component', 'The configuration this instance runs with.', []],
    ['PaymentsInspector', 'component', 'Reads the payment provider\'s ledger for the dashboard.', []],
  ]},
  { module: 'flowforge-worker', pkg: '(root)', text: 'The execution layer.', classes: [
    ['StepCommandListener', 'Kafka listener', 'onCommand(): process, send the result, wait for the ack, then the offset is committed.', []],
    ['WorkerConfiguration', 'config', 'Builds the StepCommandProcessor (optionally with SimulatedLatency) and declares topics.', []],
    ['CrashInjector', 'component', 'Demo: halt the JVM right after a given step type, before reporting — like kill -9.', []],
    ['SimulatedLatency', 'decorator', 'Makes every handler take a fixed time; used by the load tests.', []],
  ]},
  { module: 'flowforge-worker', pkg: 'handlers', text: 'The demo order steps (one class per step type).', classes: [
    ['ValidateOrderHandler', 'VALIDATE_ORDER', 'Permanent failure if orderId or customerId is missing or amount ≤ 0.', []],
    ['ReserveInventoryHandler', 'RESERVE_INVENTORY', 'Simulated reservation; undone by ReleaseInventoryHandler.', []],
    ['ChargePaymentHandler', 'CHARGE_PAYMENT', 'Charges through the PaymentGateway with the step execution id as Idempotency-Key; a decline is permanent, an unreachable provider retryable. Undone by RefundPaymentHandler.', []],
    ['CreateShipmentHandler', 'CREATE_SHIPMENT', 'Permanent failure for country XX; demo options shipmentFailures (retryable) and shipmentDelayMs.', []],
    ['CheckFraudHandler', 'CHECK_FRAUD', 'Runs in parallel with the reservation. fraud: true rejects the order; fraudCheckDelayMs slows it.', []],
    ['SendConfirmationHandler', 'SEND_CONFIRMATION', 'Simulated email.', []],
    ['ReleaseInventoryHandler / RefundPaymentHandler', 'undo steps', 'Compensations. The refund knows which charge to reverse from the command\'s compensatesStepExecutionId. refundFails: true makes it fail (COMPENSATION_FAILED demo).', []],
    ['OrderInput', 'record', 'Reads orderId, customerId and amount from the run input.', []],
  ]},
  { module: 'flowforge-worker', pkg: 'payments', text: 'How handlers reach the payment provider.', classes: [
    ['PaymentGateway', 'interface', 'charge(key, order, amount) and refund(key, chargeKey), answering OK / REJECTED / UNAVAILABLE.', []],
    ['HttpPaymentGateway', 'class', 'Calls flowforge-payments with an Idempotency-Key header; 402/4xx → permanent, 5xx/timeout → retryable.', []],
    ['SimulatedPaymentGateway', 'class', 'Same rules in memory, for tests or a worker without the provider.', []],
  ]},
  { module: 'flowforge-payments', pkg: '(root)', text: 'A simulated external payment provider with its own ledger.', classes: [
    ['PaymentLedger', 'component', 'One charge and one refund per idempotency key, decided by unique constraints, so concurrent duplicates cannot both charge.', [
      ['charge(key, order, amount)', 'insert, or replay the first outcome; a reused key with a different request is refused'],
      ['refund(key, chargeKey)', 'refund a captured charge once'], ['summary()', 'totals, duplicates absorbed, orders charged twice (always 0)']]],
    ['PaymentController', 'REST', 'POST /v1/charges, POST /v1/refunds (Idempotency-Key required; Idempotent-Replayed: true on repeats), GET /v1/charges, GET /v1/summary.', []],
  ]},
];
