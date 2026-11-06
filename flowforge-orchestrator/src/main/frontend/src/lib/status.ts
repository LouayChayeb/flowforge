import type { AnyStatus, RunStatus } from './types'

/** One colour per status, from the --s-* tokens in index.css. */
export const STATUS_COLOR: Record<string, string> = {
  RUNNING: 'var(--s-running)',
  COMPLETED: 'var(--s-completed)',
  FAILED: 'var(--s-failed)',
  COMPENSATING: 'var(--s-compensating)',
  COMPENSATED: 'var(--s-compensated)',
  COMPENSATION_FAILED: 'var(--s-compfailed)',
  CANCELLED: 'var(--s-pending)',
  PENDING: 'var(--s-pending)',
  RETRY_WAIT: 'var(--s-retry)',
  SUCCEEDED: 'var(--s-completed)',
  RETRYABLE_FAILURE: 'var(--s-retry)',
  PERMANENT_FAILURE: 'var(--s-failed)',
  TIMED_OUT: 'var(--s-timedout)',
}

export const colorOf = (status: string | null | undefined) => (status && STATUS_COLOR[status]) || 'var(--primary)'

export const LIVE_STATUSES: ReadonlySet<AnyStatus> = new Set(['RUNNING', 'COMPENSATING', 'RETRY_WAIT'])

export const RUN_STATUS_ORDER: RunStatus[] = [
  'RUNNING', 'COMPENSATING', 'COMPLETED', 'COMPENSATED', 'FAILED', 'COMPENSATION_FAILED', 'CANCELLED',
]

export const isActiveRun = (status: RunStatus) => status === 'RUNNING' || status === 'COMPENSATING'
