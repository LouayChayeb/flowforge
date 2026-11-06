import { Html, Panel, StatusBadge } from '@/components/common'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ATTEMPT_STATES, STEP_STATES } from '@/lib/knowledge'
import { fmtClock, fmtDur, ms, parseIsoDuration } from '@/lib/format'
import { commandFor, stepLabel } from '@/lib/runModel'
import type { Command, Step } from '@/lib/types'
import { StateMachine, type Transition } from './StateMachine'

const W = 110
const H = 28
const STEP_SM: Record<string, [number, number]> = {
  PENDING: [10, 50], RUNNING: [150, 50], RETRY_WAIT: [150, 120], COMPLETED: [300, 15], FAILED: [300, 85],
}
const STEP_TRANSITIONS: Transition[] = [
  { from: 'PENDING', to: 'RUNNING', label: 'claimed' },
  { from: 'RUNNING', to: 'COMPLETED', label: 'succeeded' },
  { from: 'RUNNING', to: 'FAILED', label: 'permanent failure, or out of attempts' },
  { from: 'RUNNING', to: 'RETRY_WAIT', label: 'retryable failure or timeout', d: `M${150 + 40} ${50 + H} L${150 + 40} 120` },
  { from: 'RETRY_WAIT', to: 'RUNNING', label: 'backoff over, claimed again', d: `M${150 + 70} 120 L${150 + 70} ${50 + H}` },
]

function StepStateMachine({ step }: { step: Step }) {
  const visited = new Set<string>(['PENDING', step.status])
  for (const a of step.attempts) {
    visited.add('RUNNING')
    if (a.status === 'RETRYABLE_FAILURE' || a.status === 'TIMED_OUT') visited.add('RETRY_WAIT')
  }
  const taken = new Set<string>()
  if (visited.has('RUNNING')) taken.add('PENDING>RUNNING')
  if (visited.has('RETRY_WAIT')) { taken.add('RUNNING>RETRY_WAIT'); if (step.attempts.length > 1) taken.add('RETRY_WAIT>RUNNING') }
  if (step.status === 'COMPLETED') taken.add('RUNNING>COMPLETED')
  if (step.status === 'FAILED') taken.add('RUNNING>FAILED')
  return <StateMachine states={STEP_SM} transitions={STEP_TRANSITIONS} visited={visited} taken={taken} current={step.status}
    descriptions={STEP_STATES} width={420} height={160} nodeWidth={W} nodeHeight={H} />
}

export function StepDetail({ step, commands }: { step: Step; commands: Command[] }) {
  const output = step.output ?? {}
  return (
    <Panel title={<span className="flex items-center gap-2">{stepLabel(step)} <StatusBadge status={step.status} /></span>}
      hint={<span className="font-mono">{step.type}</span>}>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_420px]">
        <div className="space-y-3 text-sm leading-relaxed">
          <Html as="p" html={STEP_STATES[step.status] ?? ''} className="text-muted-foreground" />
          {step.waitFor && step.type === 'SIGNAL' && (
            <p className="rounded-md border border-dashed border-primary/50 bg-primary/5 p-2.5 text-[13px]">
              A <b>signal</b> step: it waits up to <b>{fmtDur(parseIsoDuration(step.waitFor))}</b> for an event from outside
              {step.nextAttemptAt ? <> (deadline <b>{fmtClock(step.nextAttemptAt)}</b>)</> : ' once its dependencies complete'}.
              An operator — or a webhook — delivers it with <code>POST /api/runs/{'{id}'}/signals/{step.name}</code>, and its JSON
              payload becomes this step's output. No signal by the deadline: the step fails and the run undoes what it did.
            </p>
          )}
          {step.waitFor && step.type !== 'SIGNAL' && (
            <p className="rounded-md border border-dashed border-s-retry/50 bg-s-retry/5 p-2.5 text-[13px]">
              A durable timer of <b>{fmtDur(parseIsoDuration(step.waitFor))}</b>: due{' '}
              {step.nextAttemptAt ? <b>{fmtClock(step.nextAttemptAt)}</b> : 'once its dependencies complete'}. The orchestrator
              completes it itself — no worker, no Kafka message — and because the due time is stored, it survives restarts.
            </p>
          )}
          {Object.keys(output).length > 0 && (
            <div>
              <h4 className="mb-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Output, handed to later steps</h4>
              <pre className="overflow-x-auto rounded-md bg-muted/60 p-2.5 font-mono text-xs">{JSON.stringify(output, null, 2)}</pre>
            </div>
          )}
          <p className="text-xs text-muted-foreground">
            Step execution id <code>{step.stepExecutionId}</code> — the same for every attempt, which is why a handler can pass it
            to an external service as an idempotency key. Attempts used: {step.attemptCount} of {step.maxAttempts}.
          </p>
        </div>
        <div>
          <h4 className="mb-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Step state machine</h4>
          <StepStateMachine step={step} />
        </div>
      </div>
      <div className="overflow-hidden rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-10 pl-3">#</TableHead>
              <TableHead>Outcome</TableHead>
              <TableHead>Claimed at</TableHead>
              <TableHead>Sent after</TableHead>
              <TableHead>Took</TableHead>
              <TableHead className="pr-3">Error / meaning</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {step.attempts.length === 0 && (
              <TableRow><TableCell colSpan={6} className="py-6 text-center text-muted-foreground">Not attempted yet — waiting for its turn.</TableCell></TableRow>
            )}
            {step.attempts.map((a) => {
              const cmd = commandFor(commands, step, a)
              return (
                <TableRow key={a.attempt}>
                  <TableCell className="pl-3 tabular-nums">{a.attempt}</TableCell>
                  <TableCell><StatusBadge status={a.status} /></TableCell>
                  <TableCell className="font-mono text-xs">{fmtClock(a.startedAt)}</TableCell>
                  <TableCell className="font-mono text-xs">{cmd?.publishedAt ? `+${fmtDur(ms(cmd.publishedAt)! - ms(a.startedAt)!)}` : '—'}</TableCell>
                  <TableCell className="font-mono text-xs">{a.finishedAt ? fmtDur(ms(a.finishedAt)! - ms(a.startedAt)!) : <span className="text-muted-foreground">open</span>}</TableCell>
                  <TableCell className="pr-3 text-xs whitespace-normal">
                    {a.errorMessage && <div>{a.errorMessage}</div>}
                    <div className="text-muted-foreground">{ATTEMPT_STATES[a.status]}</div>
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </div>
    </Panel>
  )
}
