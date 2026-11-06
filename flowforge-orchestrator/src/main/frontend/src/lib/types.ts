// Shapes of the orchestrator's JSON responses (see RunDtos, WorkflowDtos and the insight endpoints).

export type RunStatus =
  | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'COMPENSATING' | 'COMPENSATED' | 'COMPENSATION_FAILED' | 'CANCELLED'
export type StepStatus = 'PENDING' | 'RUNNING' | 'RETRY_WAIT' | 'COMPLETED' | 'FAILED'
export type AttemptStatus = 'RUNNING' | 'SUCCEEDED' | 'RETRYABLE_FAILURE' | 'PERMANENT_FAILURE' | 'TIMED_OUT'
export type AnyStatus = RunStatus | StepStatus | AttemptStatus
export type StepKind = 'FORWARD' | 'COMPENSATION'

export type Input = Record<string, unknown>

export interface RunSummary {
  runId: string
  workflow: string
  status: RunStatus
  input: Input | null
  currentStep: string | null
  currentStepKind: StepKind | null
  activeSteps: number
  attempts: number
  failedAttempts: number
  createdAt: string
  completedAt: string | null
}

export interface Attempt {
  attempt: number
  status: AttemptStatus
  startedAt: string
  finishedAt: string | null
  errorMessage: string | null
}

export interface Step {
  stepExecutionId: string
  name: string
  type: string
  kind: StepKind
  status: StepStatus
  attemptCount: number
  maxAttempts: number
  dependsOn: string[] | null
  nextAttemptAt: string | null
  leaseExpiresAt: string | null
  waitFor: string | null
  output: Record<string, unknown> | null
  attempts: Attempt[]
}

export interface OperatorAction {
  action: 'CANCEL' | 'RETRY_COMPENSATION'
  actor: string
  reason: string | null
  runStatusBefore: RunStatus
  performedAt: string
}

export interface Run {
  runId: string
  workflow: string
  version: number
  status: RunStatus
  input: Input | null
  currentStep: string | null
  createdAt: string
  completedAt: string | null
  traceId: string | null
  cancelRequestedAt: string | null
  operatorActions: OperatorAction[] | null
  steps: Step[]
}

export interface Command {
  stepExecutionId: string
  attempt: number
  publishedAt: string | null
  deadline: string | null
  publishAttempts: number
}

export interface LeasedStep {
  runId: string
  stepName: string
  kind: StepKind
  attempt: number
  leaseExpiresAt: string
}

export interface WaitingStep {
  runId: string
  stepName: string
  kind: StepKind
  status: StepStatus
  attempt: number
  maxAttempts: number
  nextAttemptAt: string
}

export interface Counters {
  stepsClaimed: number
  outboxPublished: number
  resultsApplied: number
  resultsStale: number
  leasesExpired: number
  outboxFailures: number
}

export interface Stats {
  now: string
  instance: string
  counters: Counters
  database: {
    runs: Record<string, number>
    steps: Record<string, number>
    attempts: Record<string, number>
    outboxPending: number
    outboxPublished: number
    oldestPendingCommand: string | null
    leased: LeasedStep[]
    waiting: WaitingStep[]
  }
}

export interface EngineConfig {
  dispatchIntervalMs: number
  outboxIntervalMs: number
  recoveryIntervalMs: number
  partitions: number
  leaseDuration: string
  initialBackoff: string
  maxBackoff: string
  backoffMultiplier: number
  batchSize: number
  resultListenerThreads: number
  schedulerEnabled: boolean
  wakeup: boolean
  traceUrl: string | null
}

export interface KafkaGroup {
  groupId: string
  topic: string
  state: string
  lag: number
  members: { clientId: string; host: string; partitions: number[] }[]
  partitions: { partition: number; owner: string | null; committed: number; endOffset: number; lag: number }[]
}

export interface KafkaInfo {
  reachable: boolean
  error: string | null
  topics: { name: string; messages: number }[]
  groups: KafkaGroup[]
}

export interface Invariant {
  name: string
  description: string
}

export interface ConsistencyReport {
  consistent: boolean
  checkedAt: string
  violations: { invariant: string; count: number; sample: string[] }[]
}

export interface Charge {
  status: string
  amount: string | number
  requests: number
  idempotencyKey: string
}

export interface Payments {
  configured: boolean
  reachable: boolean
  url: string | null
  error: string | null
  summary: {
    charges: number
    captured: number
    declined: number
    refunded: number
    duplicateRequestsAbsorbed: number
    ordersChargedMoreThanOnce: number
    capturedAmount: string | number
  } | null
  charges?: Charge[]
}

/** RFC 9457 problem details, as returned by ApiExceptionHandler. */
export interface Problem {
  type?: string
  title?: string
  status?: number
  detail?: string
  code?: string
  traceId?: string
  errors?: { field: string; message: string }[]
  [key: string]: unknown
}
