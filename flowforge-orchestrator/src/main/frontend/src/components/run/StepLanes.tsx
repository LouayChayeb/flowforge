import { ArrowRight, Hourglass, Timer } from 'lucide-react'
import type { CSSProperties } from 'react'
import { Bar, Explain, Panel, StatusBadge } from '@/components/common'
import { BorderBeam } from '@/components/ui/border-beam'
import { SlidingNumber } from '@/components/ui/sliding-number'
import { fmtDur, ms, parseIsoDuration } from '@/lib/format'
import { forwardColumns, stepLabel } from '@/lib/runModel'
import { colorOf } from '@/lib/status'
import { useNow, useStore } from '@/lib/store'
import type { Run, Step } from '@/lib/types'
import { cn } from '@/lib/utils'

/** Whole seconds left, rolling like an odometer. */
function Countdown({ until, now, prefix }: { until: number; now: number; prefix: string }) {
  const seconds = Math.max(0, Math.ceil((until - now) / 1000))
  return (
    <div className="flex items-center gap-1 text-xs font-medium text-s-retry tabular-nums">
      <Timer className="size-3.5" /> {prefix}&nbsp;<SlidingNumber value={seconds} />&nbsp;s
    </div>
  )
}

function StepCard({ step, selected, onSelect }: { step: Step; selected: boolean; onSelect: () => void }) {
  const { config } = useStore()
  const now = useNow(200)
  const color = colorOf(step.status)
  const output = Object.entries(step.output ?? {})

  let progress = null
  if (step.status === 'RUNNING' && step.leaseExpiresAt) {
    const lease = parseIsoDuration(config?.leaseDuration) || 60_000
    const left = Math.max(0, ms(step.leaseExpiresAt)! - now)
    progress = (
      <div className="space-y-1">
        <Bar fraction={left / lease} color="var(--s-running)" />
        <div className="text-[11px] text-muted-foreground tabular-nums">lease {fmtDur(left)} left</div>
      </div>
    )
  } else if (step.status === 'RETRY_WAIT' && step.nextAttemptAt) {
    progress = <Countdown until={ms(step.nextAttemptAt)!} now={now} prefix="retry in" />
  } else if (step.waitFor && step.status === 'PENDING' && step.nextAttemptAt) {
    progress = <Countdown until={ms(step.nextAttemptAt)!} now={now} prefix="fires in" />
  } else if (step.waitFor && step.attemptCount === 0) {
    progress = (
      <div className="flex items-center gap-1 text-xs text-muted-foreground">
        <Hourglass className="size-3.5" /> waits {fmtDur(parseIsoDuration(step.waitFor))}
      </div>
    )
  }

  return (
    <button
      onClick={onSelect}
      style={{ '--c': color } as CSSProperties}
      className={cn(
        'relative flex w-52 flex-col gap-1.5 overflow-hidden rounded-xl border bg-card p-3 text-left shadow-sm transition-[transform,box-shadow,border-color]',
        'hover:-translate-y-0.5 hover:shadow-md',
        step.waitFor && 'border-dashed',
        selected && 'ring-2 ring-primary ring-offset-2 ring-offset-background',
      )}
    >
      <div className="truncate text-sm font-semibold">{stepLabel(step)}</div>
      <div className="font-mono text-[10.5px] tracking-wide text-muted-foreground uppercase">{step.type}</div>
      <div className="flex items-center gap-2">
        <StatusBadge status={step.status} />
        <span className="ml-auto flex items-center gap-1">
          {step.attempts.map((a) => (
            <span key={a.attempt} title={`attempt ${a.attempt}: ${a.status}`} className="size-2 rounded-full" style={{ background: colorOf(a.status) }} />
          ))}
        </span>
        <span className="text-[11px] text-muted-foreground tabular-nums">{step.attemptCount}/{step.maxAttempts}</span>
      </div>
      {progress}
      {output.length > 0 && (
        <div className="truncate rounded-md bg-muted/60 px-1.5 py-1 font-mono text-[10.5px] text-muted-foreground" title="output handed to later steps">
          → {output.map(([k, v]) => `${k}=${String(v)}`).join(', ')}
        </div>
      )}
      {step.status === 'RUNNING' && <BorderBeam size={60} duration={3} colorFrom="var(--s-running)" colorTo="var(--primary)" />}
    </button>
  )
}

function Lane({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-w-max items-stretch gap-3">
      <div className="flex w-6 shrink-0 items-center justify-center">
        <span className="-rotate-90 text-[10px] font-semibold tracking-[0.2em] whitespace-nowrap text-muted-foreground uppercase">{label}</span>
      </div>
      {children}
    </div>
  )
}

const Arrow = () => <ArrowRight className="size-4 shrink-0 self-center text-muted-foreground/50" />

export function StepLanes({ run, selectedId, onSelect }: { run: Run; selectedId: string | null; onSelect: (id: string) => void }) {
  const columns = forwardColumns(run)
  const undo = run.steps.filter((s) => s.kind === 'COMPENSATION')
  return (
    <Panel id="tour-pipeline" title="Steps" hint="click a step for its attempts and state machine · dots are attempts">
      <Explain>
        Each column runs once every step it <b>depends on</b> has completed, so the steps stacked in one column run
        <b> in parallel</b>. A dashed card is a <b>durable timer</b>; “→ key=value” is the output a step hands to later
        steps. When a step fails for good (or the run is cancelled), FlowForge appends a <b>compensation</b> (undo) step
        for each completed step that declared one, most recent first — the lower lane.
      </Explain>
      <div className="space-y-4 overflow-x-auto pb-2">
        <Lane label="forward">
          {columns.map((column, i) => (
            <div key={i} className="flex items-center gap-3">
              {i > 0 && <Arrow />}
              <div className="flex flex-col justify-center gap-2">
                {column.map((s) => <StepCard key={s.stepExecutionId} step={s} selected={s.stepExecutionId === selectedId} onSelect={() => onSelect(s.stepExecutionId)} />)}
              </div>
            </div>
          ))}
        </Lane>
        {undo.length > 0 && (
          <Lane label="undo">
            {undo.map((s, i) => (
              <div key={s.stepExecutionId} className="flex items-center gap-3">
                {i > 0 && <Arrow />}
                <StepCard step={s} selected={s.stepExecutionId === selectedId} onSelect={() => onSelect(s.stepExecutionId)} />
              </div>
            ))}
          </Lane>
        )}
      </div>
    </Panel>
  )
}
