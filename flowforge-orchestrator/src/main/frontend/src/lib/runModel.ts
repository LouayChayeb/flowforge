// Pure functions that turn a run's stored history into what the run view shows.
import { RUN_STATES, TIMELINE_WHY } from './knowledge'
import { fmtClock, fmtDur, ms, retryBackoff } from './format'
import { STATUS_COLOR } from './status'
import type { Attempt, Command, EngineConfig, Run, Step } from './types'
import { scenarioOf } from './workflows'

export const stepLabel = (step: Step) => (step.kind === 'COMPENSATION' ? `undo ${step.name}` : step.name)

const succeededAt = (step: Step | undefined) => ms(step?.attempts.find((a) => a.status === 'SUCCEEDED')?.finishedAt)

/** Forward steps grouped by dependency depth: every step in a column can run at the same time. */
export function forwardColumns(run: Run): Step[][] {
  const forward = run.steps.filter((s) => s.kind !== 'COMPENSATION')
  const byName = new Map(forward.map((s) => [s.name, s]))
  const depth = new Map<string, number>()
  const depthOf = (s: Step): number => {
    if (!depth.has(s.name)) {
      const deps = (s.dependsOn ?? []).map((n) => byName.get(n)).filter((d): d is Step => !!d)
      depth.set(s.name, deps.length ? 1 + Math.max(...deps.map(depthOf)) : 0)
    }
    return depth.get(s.name)!
  }
  const columns: Step[][] = []
  for (const s of forward) (columns[depthOf(s)] ??= []).push(s)
  return columns.filter(Boolean)
}

/** The step to show first: the one in motion, else the last one attempted. */
export function defaultStep(run: Run): Step | undefined {
  return run.steps.find((s) => s.status === 'RUNNING' || s.status === 'RETRY_WAIT')
    ?? [...run.steps].reverse().find((s) => s.attemptCount > 0)
    ?? run.steps[0]
}

export const commandFor = (commands: Command[], step: Step, attempt: Attempt) =>
  commands.find((c) => c.stepExecutionId === step.stepExecutionId && c.attempt === attempt.attempt)

export type TimelineKind =
  | 'created' | 'claimed' | 'published' | 'succeeded' | 'retryable' | 'exhausted' | 'permanent' | 'timedout'
  | 'compensating' | 'finished' | 'waiting' | 'inflight' | 'timer' | 'operator'

export interface TimelineEvent {
  t: number
  iso: string
  kind: TimelineKind
  title: string
  detail: string
  why: string
  color: string
}

/** The run's history as a list of events, rebuilt from the stored attempts and outbox rows. */
export function buildTimeline(run: Run, commands: Command[], config: EngineConfig | null, now: number): TimelineEvent[] {
  const events: TimelineEvent[] = []
  const add = (iso: string, kind: TimelineKind, title: string, detail: string, why: string, color: string) =>
    events.push({ t: ms(iso)!, iso, kind, title, detail, why: why ?? '', color })

  add(run.createdAt, 'created', 'Run created, first steps made eligible', `input: ${scenarioOf(run.input, run.workflow)}`,
    TIMELINE_WHY.created, 'var(--primary)')

  const forwardByName = new Map(run.steps.filter((s) => s.kind !== 'COMPENSATION').map((s) => [s.name, s]))
  // When this attempt became claimable: its dependencies' successes, the previous attempt, or the previous undo step.
  const readyAt = (step: Step, a: Attempt): number => {
    if (a.attempt > 1) return ms(step.attempts.find((p) => p.attempt === a.attempt - 1)?.finishedAt) ?? ms(a.startedAt)!
    const index = run.steps.indexOf(step)
    if (step.kind === 'COMPENSATION') {
      const prev = run.steps[index - 1]
      if (prev?.kind === 'COMPENSATION') return succeededAt(prev) ?? ms(a.startedAt)!
      return Math.max(...run.steps.filter((s) => s.kind !== 'COMPENSATION').flatMap((s) => s.attempts.map((x) => ms(x.finishedAt) ?? 0)))
    }
    const deps = (step.dependsOn ?? []).map((n) => forwardByName.get(n))
    return deps.length ? Math.max(...deps.map((d) => succeededAt(d) ?? 0)) : ms(run.createdAt)!
  }

  const steps = [...run.steps].sort((a, b) =>
    (ms(a.attempts[0]?.startedAt) ?? Infinity) - (ms(b.attempts[0]?.startedAt) ?? Infinity))
  const firstCompensation = run.steps.find((s) => s.kind === 'COMPENSATION')
  let compensationMarked = false

  for (const step of steps) {
    const label = stepLabel(step)
    if (step.kind === 'COMPENSATION' && !compensationMarked && firstCompensation) {
      compensationMarked = true
      const failedForward = run.steps.find((s) => s.kind !== 'COMPENSATION' && s.status === 'FAILED')
      const at = failedForward?.attempts.at(-1)?.finishedAt ?? run.cancelRequestedAt ?? step.attempts[0]?.startedAt
      if (at) {
        const undos = run.steps.filter((s) => s.kind === 'COMPENSATION').map((s) => s.type).join(' → ')
        add(at, 'compensating', run.cancelRequestedAt && !failedForward ? 'Cancelled: undoing what completed' : 'Run starts compensating',
          `undo steps appended, most recent first: ${undos}`, TIMELINE_WHY.compensating, 'var(--s-compensating)')
      }
    }
    for (const a of step.attempts) {
      if (step.waitFor) {
        add(a.startedAt, 'timer', `Timer ${label} fired`, 'completed by the orchestrator itself — no command, no worker',
          TIMELINE_WHY.timer, 'var(--s-retry)')
        continue
      }
      const cmd = commandFor(commands, step, a)
      const waited = ms(a.startedAt)! - readyAt(step, a)
      const previous = step.attempts.find((p) => p.attempt === a.attempt - 1)
      const backoff = previous && ['RETRYABLE_FAILURE', 'TIMED_OUT'].includes(previous.status) ? retryBackoff(config, previous.attempt) : null
      const waitText = backoff != null && previous
        ? `waited ${fmtDur(waited)}: ${fmtDur(backoff)} retry backoff after attempt ${previous.attempt}, then the next dispatch`
        : `ready → claimed in ${fmtDur(waited)}${config?.wakeup === false ? ` (the dispatcher polls every ${config.dispatchIntervalMs} ms)` : ''}`
      add(a.startedAt, 'claimed', `Claimed ${label} — attempt ${a.attempt} of ${step.maxAttempts}`,
        `${waitText}${cmd?.deadline ? ` · lease until ${fmtClock(cmd.deadline)}` : ''}`,
        (TIMELINE_WHY.claimed ?? '').replace('{attempt}', String(a.attempt)), 'var(--s-running)')
      if (cmd?.publishedAt) {
        add(cmd.publishedAt, 'published', `Command for ${label} sent to Kafka`,
          `${fmtDur(ms(cmd.publishedAt)! - ms(a.startedAt)!)} after the claim${cmd.publishAttempts > 1 ? ` · ${cmd.publishAttempts} send attempts` : ''}`,
          TIMELINE_WHY.published, 'var(--s-retry)')
      }
      if (a.finishedAt) {
        let kind = ({ SUCCEEDED: 'succeeded', RETRYABLE_FAILURE: 'retryable', PERMANENT_FAILURE: 'permanent', TIMED_OUT: 'timedout' } as const)[a.status as 'SUCCEEDED'] as TimelineKind
        const lastAllowed = a.attempt >= step.maxAttempts && step.status === 'FAILED'
        if (kind === 'retryable' && lastAllowed) kind = 'exhausted'
        const titles: Partial<Record<TimelineKind, string>> = {
          succeeded: `${label} succeeded`,
          retryable: `${label} failed — will retry after ${fmtDur(retryBackoff(config, a.attempt))}`,
          exhausted: `${label} failed — out of attempts (${a.attempt}/${step.maxAttempts}), step FAILED`,
          permanent: `${label} failed permanently`,
          timedout: `${label} timed out (lease expired)${lastAllowed ? ' — out of attempts, step FAILED' : ''}`,
        }
        const roundTrip = cmd?.publishedAt ? ` · worker + Kafka round trip ${fmtDur(ms(a.finishedAt)! - ms(cmd.publishedAt)!)}` : ''
        add(a.finishedAt, kind, titles[kind] ?? label, `${a.errorMessage ? `“${a.errorMessage}”` : 'result applied'}${roundTrip}`,
          TIMELINE_WHY[kind], STATUS_COLOR[a.status])
      } else {
        add(new Date(now).toISOString(), 'inflight', `${label} attempt ${a.attempt} is with a worker`, 'waiting for its result…',
          TIMELINE_WHY.inflight, 'var(--s-running)')
      }
    }
    if (step.status === 'RETRY_WAIT' && step.nextAttemptAt) {
      const due = ms(step.nextAttemptAt)!
      add(step.nextAttemptAt, 'waiting', `${label} eligible again`, `backoff ends ${due > now ? `in ${fmtDur(due - now)}` : 'now'}`,
        TIMELINE_WHY.waiting, 'var(--s-retry)')
    }
  }
  for (const action of run.operatorActions ?? []) {
    add(action.performedAt, 'operator',
      `Operator ${action.action === 'CANCEL' ? 'cancelled the run' : 'retried the failed compensation'} (${action.actor})`,
      action.reason ? `“${action.reason}”` : `run was ${action.runStatusBefore}`,
      TIMELINE_WHY[action.action === 'CANCEL' ? 'cancelled' : 'retried'], 'var(--primary)')
  }
  if (run.completedAt) {
    add(run.completedAt, 'finished', `Run ${run.status}`, RUN_STATES[run.status] ?? '', TIMELINE_WHY.finished, STATUS_COLOR[run.status])
  }
  return events.sort((a, b) => a.t - b.t)
}
