import { Activity, Gauge, ShieldCheck, Workflow } from 'lucide-react'
import type { ReactNode } from 'react'
import { BorderBeam } from '@/components/ui/border-beam'
import { Card } from '@/components/ui/card'
import { NumberTicker } from '@/components/ui/number-ticker'
import { useStore } from '@/lib/store'
import { cn } from '@/lib/utils'

function Kpi({ icon, label, children, note, live, tone }: {
  icon: ReactNode
  label: string
  children: ReactNode
  note: ReactNode
  live?: boolean
  tone?: 'good' | 'bad'
}) {
  return (
    <Card className="relative gap-1 overflow-hidden px-4 py-3">
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        {icon}
        {label}
      </div>
      <div className={cn('text-2xl font-semibold tracking-tight tabular-nums',
        tone === 'good' && 'text-s-completed', tone === 'bad' && 'text-s-failed')}>
        {children}
      </div>
      <div className="truncate text-xs text-muted-foreground">{note}</div>
      {live && <BorderBeam size={80} duration={5} colorFrom="var(--primary)" colorTo="var(--s-running)" />}
    </Card>
  )
}

/** The headline numbers: what is running, how fast, how well, and whether the history is consistent. */
export function KpiStrip() {
  const { stats, rates, runs, consistency } = useStore()
  const db = stats?.database
  const inProgress = (db?.runs.RUNNING ?? 0) + (db?.runs.COMPENSATING ?? 0)
  const stepsPerSecond = rates.claimed ?? 0
  const finished = runs.filter((r) => r.completedAt)
  const completed = finished.filter((r) => r.status === 'COMPLETED').length
  const share = finished.length ? Math.round((completed / finished.length) * 100) : 0
  const broken = consistency?.violations.length ?? 0

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <Kpi icon={<Workflow className="size-3.5" />} label="Runs in progress" live={inProgress > 0}
        note={`${db ? Object.values(db.runs).reduce((a, b) => a + b, 0) : 0} runs stored in total`}>
        <NumberTicker value={inProgress} className="text-inherit" />
      </Kpi>
      <Kpi icon={<Gauge className="size-3.5" />} label="Steps claimed / s" live={stepsPerSecond > 0.05}
        note={`${(rates.applied ?? 0).toFixed(1)} results applied / s`}>
        {stepsPerSecond > 0.05 ? stepsPerSecond.toFixed(1) : '0'}
      </Kpi>
      <Kpi icon={<Activity className="size-3.5" />} label="Completed" note={`of the ${finished.length} finished runs listed`}>
        <NumberTicker value={share} className="text-inherit" />%
      </Kpi>
      <Kpi icon={<ShieldCheck className="size-3.5" />} label="Invariants" tone={consistency ? (broken ? 'bad' : 'good') : undefined}
        note={consistency ? (broken ? `${broken} broken — see the Engine tab` : 'the stored history is consistent') : 'checking…'}>
        {consistency ? `${10 - broken}/10` : '…'}
      </Kpi>
    </div>
  )
}
