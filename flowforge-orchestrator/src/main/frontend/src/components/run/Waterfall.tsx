import type { CSSProperties, ReactNode } from 'react'
import { Dot, Explain, Panel } from '@/components/common'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { fmtClock, fmtDur, ms, parseIsoDuration } from '@/lib/format'
import { stepLabel } from '@/lib/runModel'
import { colorOf } from '@/lib/status'
import { useNow } from '@/lib/store'
import type { Run, Step } from '@/lib/types'
import { cn } from '@/lib/utils'

interface Segment {
  key: string
  from: number
  to: number
  kind: 'attempt' | 'live' | 'wait' | 'mark'
  color?: string
  tip: ReactNode
}

const done = (s: Step | undefined) => ms(s?.attempts.find((a) => a.status === 'SUCCEEDED')?.finishedAt)

function segmentsOf(step: Step, run: Run, now: number): Segment[] {
  const segs: Segment[] = []
  if (step.waitFor) {
    // A timer: hatched from the moment it was armed until it fired (or until now).
    const wait = parseIsoDuration(step.waitFor) ?? 0
    const forward = new Map(run.steps.filter((s) => s.kind !== 'COMPENSATION').map((s) => [s.name, s]))
    const deps = (step.dependsOn ?? []).map((n) => forward.get(n))
    const armed = step.nextAttemptAt ? ms(step.nextAttemptAt)! - wait : deps.length ? Math.max(...deps.map((d) => done(d) ?? 0)) : ms(run.createdAt)!
    const fired = ms(step.attempts[0]?.startedAt)
    const stop = fired ?? (step.nextAttemptAt ? Math.min(ms(step.nextAttemptAt)!, now) : null)
    if (armed && stop) segs.push({ key: 'timer', from: armed, to: stop, kind: 'wait', tip: `durable timer, ${fmtDur(wait)}` })
    if (step.nextAttemptAt && !fired) segs.push({ key: 'due', from: ms(step.nextAttemptAt)!, to: ms(step.nextAttemptAt)!, kind: 'mark', tip: `due ${fmtClock(step.nextAttemptAt)}` })
    if (fired) segs.push({ key: 'fired', from: fired, to: fired, kind: 'mark', tip: `fired ${fmtClock(step.attempts[0].startedAt)}` })
    return segs
  }
  step.attempts.forEach((a, i) => {
    const s0 = ms(a.startedAt)!
    const s1 = ms(a.finishedAt) ?? now
    const prev = step.attempts[i - 1]
    if (prev?.finishedAt) {
      segs.push({ key: `wait-${a.attempt}`, from: ms(prev.finishedAt)!, to: s0, kind: 'wait', tip: `backoff ${fmtDur(s0 - ms(prev.finishedAt)!)}` })
    }
    segs.push({
      key: `a-${a.attempt}`, from: s0, to: s1, kind: a.finishedAt ? 'attempt' : 'live', color: colorOf(a.status),
      tip: <>attempt {a.attempt}: <b>{a.status}</b> · {fmtDur(s1 - s0)}{a.errorMessage ? <> · {a.errorMessage}</> : null}</>,
    })
  })
  if (step.status === 'RETRY_WAIT' && step.nextAttemptAt) {
    const last = ms(step.attempts.at(-1)?.finishedAt)
    if (last) segs.push({ key: 'retry', from: last, to: Math.min(now, ms(step.nextAttemptAt)!), kind: 'wait', tip: `backoff until ${fmtClock(step.nextAttemptAt)}` })
  }
  return segs
}

/** Every attempt of every step on one time axis: where the run spent its time, and what ran in parallel. */
export function Waterfall({ run, selectedId, onSelect }: { run: Run; selectedId: string | null; onSelect: (id: string) => void }) {
  const now = useNow(200)
  const start = ms(run.createdAt)!
  const end = Math.max(run.completedAt ? ms(run.completedAt)! : now,
    ...run.steps.map((s) => (s.status === 'PENDING' || s.status === 'RETRY_WAIT') && s.nextAttemptAt ? ms(s.nextAttemptAt)! : 0))
  const span = Math.max(end - start, 1)
  const pct = (t: number) => Math.min(100, Math.max(0, ((t - start) / span) * 100))
  const width = (a: number, b: number) => Math.max(0.4, ((Math.min(b, end) - Math.max(a, start)) / span) * 100)
  const nowAt = pct(now)

  return (
    <Panel id="tour-waterfall" title="Waterfall" hint="every attempt on one time axis · solid = executing, hatched = waiting on purpose">
      <Explain>
        This is where parallelism and waiting become visible: two bars side by side are two workers on the same order at the
        same time; a hatched stretch is time the run spent waiting on purpose — a retry backoff or a durable timer — while
        holding no worker and no thread.
      </Explain>
      <div className="relative grid grid-cols-[112px_minmax(0,1fr)_52px] gap-y-1 sm:grid-cols-[170px_minmax(0,1fr)_64px]">
        {run.steps.map((step) => {
          const busy = step.attempts.reduce((t, a) => t + ((ms(a.finishedAt) ?? now) - ms(a.startedAt)!), 0)
          const duration = step.waitFor ? fmtDur(parseIsoDuration(step.waitFor)) : step.attempts.length ? fmtDur(busy) : '—'
          const selected = step.stepExecutionId === selectedId
          return (
            <div key={step.stepExecutionId} onClick={() => onSelect(step.stepExecutionId)}
              className={cn('col-span-3 grid cursor-pointer grid-cols-subgrid items-center rounded-md py-0.5 transition-colors hover:bg-muted/60',
                selected && 'bg-primary/10 hover:bg-primary/10')}>
              <div className={cn('flex min-w-0 items-center gap-2 px-2 text-xs', step.kind === 'COMPENSATION' && 'italic')} title={step.type}>
                <Dot status={step.status} /> <span className="truncate">{stepLabel(step)}</span>
              </div>
              <div className="relative h-[18px] rounded bg-[repeating-linear-gradient(90deg,transparent_0_calc(25%-1px),var(--border)_calc(25%-1px)_25%)]">
                {segmentsOf(step, run, now).map((s) => (
                  <Tooltip key={s.key}>
                    <TooltipTrigger
                      render={s.kind === 'mark'
                        ? <span className="absolute top-0 -translate-x-1/2 text-[13px] leading-[18px]" style={{ left: `${pct(s.from)}%` }}>⏱</span>
                        : <span
                            className={cn('absolute rounded-[3px]', s.kind === 'wait' ? 'seg-wait top-1 h-2.5' : 'top-[3px] h-3', s.kind === 'live' && 'seg-live')}
                            style={{ left: `${pct(s.from)}%`, width: `${width(s.from, s.to)}%`, '--c': s.color, background: s.kind === 'attempt' ? s.color : undefined } as CSSProperties}
                          />}
                    />
                    <TooltipContent>{s.tip}</TooltipContent>
                  </Tooltip>
                ))}
              </div>
              <div className="pr-2 text-right text-xs text-muted-foreground tabular-nums">{duration}</div>
            </div>
          )
        })}
        <div />
        <div className="relative h-4 text-[10.5px] text-muted-foreground tabular-nums">
          {[0, 0.25, 0.5, 0.75, 1].map((f) => (
            <span key={f} className={cn('absolute whitespace-nowrap', f === 0 ? '' : f === 1 ? '-translate-x-full' : '-translate-x-1/2')} style={{ left: `${f * 100}%` }}>
              +{fmtDur(span * f)}
            </span>
          ))}
        </div>
        <div />
        {!run.completedAt && (
          <div className="pointer-events-none absolute inset-y-0 bottom-4 left-[112px] right-[52px] sm:left-[170px] sm:right-[64px]">
            <div className="absolute inset-y-0 border-l-2 border-primary" style={{ left: `${nowAt}%` }} title="now" />
          </div>
        )}
      </div>
    </Panel>
  )
}
