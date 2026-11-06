import { Ban, Braces, CheckCheck, ExternalLink, RotateCcw } from 'lucide-react'
import type { OperatorAction } from './OperatorDialog'
import { Html, KeyValues, Panel, StatusBadge } from '@/components/common'
import { Button, buttonVariants } from '@/components/ui/button'
import { RUN_STATES } from '@/lib/knowledge'
import { ago, fmtClock, fmtDur, ms } from '@/lib/format'
import { isActiveRun } from '@/lib/status'
import { useNow, useStore } from '@/lib/store'
import type { Payments, Run } from '@/lib/types'
import { scenarioOf } from '@/lib/workflows'
import { StateMachine, type Transition } from './StateMachine'

const RUN_SM: Record<string, [number, number]> = {
  RUNNING: [20, 70], COMPLETED: [200, 10], COMPENSATING: [200, 70], FAILED: [200, 130],
  COMPENSATED: [380, 30], COMPENSATION_FAILED: [380, 90], CANCELLED: [380, 150],
}
const RUN_TRANSITIONS: Transition[] = [
  { from: 'RUNNING', to: 'COMPLETED', label: 'last step done' },
  { from: 'RUNNING', to: 'COMPENSATING', label: 'a step failed for good, or the run was cancelled' },
  { from: 'RUNNING', to: 'FAILED', label: 'failed, nothing to undo' },
  { from: 'COMPENSATING', to: 'COMPENSATED', label: 'all undone' },
  { from: 'COMPENSATING', to: 'COMPENSATION_FAILED', label: 'an undo step failed' },
  { from: 'RUNNING', to: 'CANCELLED', label: 'cancelled, nothing to undo' },
  { from: 'COMPENSATING', to: 'CANCELLED', label: 'cancelled, all undone' },
]

function RunStateMachine({ run }: { run: Run }) {
  const compensated = run.steps.some((s) => s.kind === 'COMPENSATION')
  const visited = new Set(['RUNNING', run.status])
  if (compensated) visited.add('COMPENSATING')
  const taken = new Set<string>()
  if (run.status === 'COMPLETED') taken.add('RUNNING>COMPLETED')
  if (run.status === 'FAILED') taken.add('RUNNING>FAILED')
  if (compensated) taken.add('RUNNING>COMPENSATING')
  if (run.status === 'COMPENSATED') taken.add('COMPENSATING>COMPENSATED')
  if (run.status === 'COMPENSATION_FAILED') taken.add('COMPENSATING>COMPENSATION_FAILED')
  if (run.status === 'CANCELLED') taken.add(compensated ? 'COMPENSATING>CANCELLED' : 'RUNNING>CANCELLED')
  return <StateMachine states={RUN_SM} transitions={RUN_TRANSITIONS} visited={visited} taken={taken} current={run.status}
    descriptions={RUN_STATES} width={520} height={190} />
}

function PaymentsLine({ payments }: { payments: Payments | null }) {
  if (!payments) return <span className="text-muted-foreground">—</span>
  if (!payments.configured) return <span className="text-muted-foreground">simulated in the worker (provider not connected)</span>
  if (!payments.reachable) return <span className="text-muted-foreground">provider unreachable</span>
  const charges = payments.charges ?? []
  if (!charges.length) return <span className="text-muted-foreground">no charge for this order</span>
  return (
    <div className="space-y-1">
      {charges.map((c) => (
        <div key={c.idempotencyKey}>
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge status={c.status} /> {String(c.amount)} · {c.requests} request{c.requests === 1 ? '' : 's'}
            {c.requests > 1 && <span className="text-xs text-s-completed">({c.requests - 1} duplicate{c.requests > 2 ? 's' : ''} absorbed — charged once)</span>}
          </div>
          <div className="font-mono text-[11px] text-muted-foreground">key {c.idempotencyKey}</div>
        </div>
      ))}
    </div>
  )
}

export function RunHeader({ run, payments, onOperator }: {
  run: Run
  payments: Payments | null
  onOperator: (action: OperatorAction) => void
}) {
  const { config } = useStore()
  const now = useNow(500)
  const end = run.completedAt ? ms(run.completedAt)! : now
  const traceUrl = run.traceId && config?.traceUrl ? config.traceUrl.replace('{traceId}', run.traceId) : null
  const canCancel = run.status === 'RUNNING' && !run.cancelRequestedAt
  const actions = run.operatorActions ?? []
  const awaiting = run.steps.filter((s) => s.type === 'SIGNAL' && s.kind === 'FORWARD' && s.status === 'PENDING' && s.nextAttemptAt)

  return (
    <Panel
      id="tour-run-head"
      title={<span className="text-base">{String(run.input?.orderId ?? 'Run')} <span className="font-normal text-muted-foreground">· {scenarioOf(run.input, run.workflow)}</span></span>}
      hint={<StatusBadge status={run.status} size="lg" />}
      actions={
        <>
          {awaiting.map((s) => (
            <Button key={s.stepExecutionId} size="sm" onClick={() => onOperator({ kind: 'signal', step: s.name })}
              title={`Deliver the ${s.name} signal: POST /api/runs/{id}/signals/${s.name}`}>
              <CheckCheck /> Approve: {s.name}
            </Button>
          ))}
          {canCancel && (
            <Button variant="outline" size="sm" onClick={() => onOperator({ kind: 'cancel' })}
              className="border-s-failed/40 text-s-failed hover:bg-s-failed/10 hover:text-s-failed">
              <Ban /> Cancel run
            </Button>
          )}
          {run.status === 'COMPENSATION_FAILED' && (
            <Button size="sm" onClick={() => onOperator({ kind: 'retry' })}><RotateCcw /> Retry compensation</Button>
          )}
          {traceUrl && (
            <a href={traceUrl} target="_blank" rel="noreferrer" className={buttonVariants({ variant: 'ghost', size: 'sm' })}
              title="The run as one distributed trace: every claim, Kafka hop, worker and payment call">
              <ExternalLink /> Trace
            </a>
          )}
          <a href={`/api/runs/${run.runId}`} target="_blank" rel="noreferrer" className={buttonVariants({ variant: 'ghost', size: 'sm' })}>
            <Braces /> JSON
          </a>
        </>
      }
    >
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,520px)]">
        <KeyValues rows={[
          ['Run id', <span className="font-mono text-xs">{run.runId}</span>],
          ['Workflow', `${run.workflow} v${run.version}`],
          ...(run.idempotencyKey ? [['Idempotency key', <span className="font-mono text-xs" title="A second start with this key returns this run instead of starting another">{run.idempotencyKey}</span>] as [string, React.ReactNode]] : []),
          ['Started', <>{fmtClock(run.createdAt)} <span className="text-xs text-muted-foreground">({ago(run.createdAt, now)})</span></>],
          ['Finished', run.completedAt ? fmtClock(run.completedAt) : <span className="text-muted-foreground">not yet</span>],
          ['Duration', <span className="tabular-nums">{fmtDur(end - ms(run.createdAt)!)}</span>],
          ['Now at', run.currentStep ?? <span className="text-muted-foreground">nothing — the run is {isActiveRun(run.status) ? 'between steps' : 'finished'}</span>],
          ['Payment provider', <PaymentsLine payments={payments} />],
          ...(run.cancelRequestedAt ? [['Cancel requested', fmtClock(run.cancelRequestedAt)] as [string, string]] : []),
          ...(actions.length ? [['Operator actions', (
            <div className="space-y-1.5">
              {actions.map((a) => (
                <div key={a.performedAt} className="text-sm">
                  <b>{a.action === 'CANCEL' ? 'Cancel' : a.action === 'SIGNAL' ? 'Signal' : 'Retry compensation'}</b> by {a.actor} at {fmtClock(a.performedAt)}
                  <span className="text-muted-foreground"> (was {a.runStatusBefore.toLowerCase().replaceAll('_', ' ')})</span>
                  {a.reason && <div className="text-xs text-muted-foreground italic">“{a.reason}”</div>}
                </div>
              ))}
            </div>
          )] as [string, React.ReactNode]] : []),
          ['Input', <pre className="overflow-x-auto rounded-md bg-muted/60 p-2 font-mono text-xs">{JSON.stringify(run.input ?? {}, null, 2)}</pre>],
        ]} />
        <div className="space-y-2">
          <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Run state machine</h4>
          <RunStateMachine run={run} />
          <p className="text-sm"><b>{run.status}</b>: <Html html={RUN_STATES[run.status] ?? ''} className="text-muted-foreground" /></p>
        </div>
      </div>
    </Panel>
  )
}
