import {
  Cpu, Database, Globe, Inbox, RadioTower, RotateCcw, Send, ShieldCheck, Zap, type LucideIcon,
} from 'lucide-react'
import { useMemo, useState, type CSSProperties } from 'react'
import { AnimatedBeamPath } from '@/components/ui/animated-beam'
import { sum } from '@/lib/format'
import { useStore, type Rates } from '@/lib/store'
import { cn } from '@/lib/utils'

// The diagram is drawn in a 1000 × 430 coordinate space and scaled to the container's width.
const W = 1000
const H = 430
const ACTIVE = 0.05

type NodeKind = 'engine' | 'store' | 'kafka' | 'worker'

const KIND: Record<NodeKind, { label: string; color: string }> = {
  engine: { label: 'Orchestrator', color: 'var(--primary)' },
  store: { label: 'Storage & checks', color: 'var(--s-compensated)' },
  kafka: { label: 'Kafka', color: 'var(--s-retry)' },
  worker: { label: 'Workers', color: 'var(--s-completed)' },
}

interface DiagramNode {
  id: string
  x: number
  y: number
  w?: number
  h?: number
  title: string
  sub: string
  kind: NodeKind
  icon: LucideIcon
  /** The rate its sparkline follows. */
  rate: keyof Rates | null
}

const NODES: DiagramNode[] = [
  { id: 'api', x: 20, y: 183, title: 'REST API', sub: 'records runs, answers 202', kind: 'engine', icon: Globe, rate: 'runs' },
  { id: 'dispatcher', x: 250, y: 25, title: 'Dispatcher', sub: 'claims ready steps', kind: 'engine', icon: Zap, rate: 'claimed' },
  { id: 'postgres', x: 250, y: 168, w: 350, h: 94, title: 'PostgreSQL — source of truth', sub: 'runs · steps · attempts · outbox', kind: 'store', icon: Database, rate: 'applied' },
  { id: 'outbox', x: 470, y: 25, title: 'Outbox publisher', sub: 'sends committed commands', kind: 'engine', icon: Send, rate: 'published' },
  { id: 'commands', x: 700, y: 25, w: 190, title: 'Kafka', sub: 'flowforge.step-commands', kind: 'kafka', icon: RadioTower, rate: 'published' },
  { id: 'workers', x: 790, y: 168, w: 190, h: 94, title: 'Workers', sub: 'run the step handlers', kind: 'worker', icon: Cpu, rate: 'applied' },
  { id: 'results', x: 700, y: 335, w: 190, title: 'Kafka', sub: 'flowforge.step-results', kind: 'kafka', icon: RadioTower, rate: 'applied' },
  { id: 'listener', x: 470, y: 335, title: 'Result listener', sub: 'applies outcomes', kind: 'engine', icon: Inbox, rate: 'applied' },
  { id: 'recovery', x: 250, y: 335, title: 'Recovery sweeper', sub: 'expires lost attempts', kind: 'engine', icon: RotateCcw, rate: 'expired' },
  { id: 'checker', x: 20, y: 335, title: 'Consistency checker', sub: '10 invariants', kind: 'store', icon: ShieldCheck, rate: null },
]

interface Edge {
  id: string
  from: string
  to: string
  d: string
  label: string
  lx: number
  ly: number
  rate: keyof Rates | null
  /** Position in the loop every step attempt goes round, 1–7. */
  step?: number
  /** A Kafka hop: draw the messages travelling along it. */
  messages?: boolean
  /** A short edge: the label is stacked (number, text, rate) and centred on (lx, ly = top). */
  stack?: boolean
}

const EDGES: Edge[] = [
  { id: 'api', from: 'api', to: 'postgres', d: 'M190 215 L250 215', label: 'insert', lx: 196, ly: 205, rate: 'runs' },
  { id: 'claim', from: 'dispatcher', to: 'postgres', d: 'M335 89 L335 168', label: 'claim', lx: 343, ly: 132, rate: 'claimed', step: 1 },
  { id: 'pending', from: 'postgres', to: 'outbox', d: 'M555 168 L555 89', label: 'pending cmds', lx: 563, ly: 132, rate: 'published', step: 2 },
  { id: 'publish', from: 'outbox', to: 'commands', d: 'M640 57 L700 57', label: 'publish', lx: 670, ly: 14, rate: 'published', step: 3, messages: true, stack: true },
  { id: 'consume', from: 'commands', to: 'workers', d: 'M860 89 L880 168', label: 'consume', lx: 885, ly: 132, rate: 'published', step: 4, messages: true },
  { id: 'report', from: 'workers', to: 'results', d: 'M880 262 L860 335', label: 'report', lx: 885, ly: 305, rate: 'applied', step: 5, messages: true },
  { id: 'deliver', from: 'results', to: 'listener', d: 'M700 367 L640 367', label: 'deliver', lx: 670, ly: 376, rate: 'applied', step: 6, messages: true, stack: true },
  { id: 'apply', from: 'listener', to: 'postgres', d: 'M555 335 L555 262', label: 'apply', lx: 563, ly: 305, rate: 'applied', step: 7 },
  { id: 'expire', from: 'recovery', to: 'postgres', d: 'M335 335 L335 262', label: 'expire', lx: 343, ly: 305, rate: 'expired' },
  { id: 'check', from: 'checker', to: 'postgres', d: 'M105 335 L105 300 Q105 290 120 290 L250 290 L250 262', label: 'reads', lx: 130, ly: 282, rate: null },
]

const size = (n: DiagramNode) => ({ w: n.w ?? 170, h: n.h ?? 64 })

/** The first and last point of a path: where the beam starts and ends. */
function endpoints(d: string): [[number, number], [number, number]] {
  const numbers = d.match(/-?\d+(\.\d+)?/g)!.map(Number)
  return [[numbers[0], numbers[1]], [numbers[numbers.length - 2], numbers[numbers.length - 1]]]
}

/** The last minute of one rate, as a faint area chart along the bottom of a box. */
function Sparkline({ values, color }: { values: number[]; color: string }) {
  if (values.length < 2 || !values.some((v) => v > ACTIVE)) return null
  // A 5-sample moving average: per-second samples of bursty traffic are spiky.
  const smooth = values.map((_, i) => {
    const window = values.slice(Math.max(0, i - 4), i + 1)
    return window.reduce((a, b) => a + b, 0) / window.length
  })
  const max = Math.max(0.5, ...smooth)
  const points = smooth.map((v, i) => `${(i / (smooth.length - 1)) * 100},${30 - (v / max) * 28}`)
  return (
    <svg viewBox="0 0 100 30" preserveAspectRatio="none" className="pointer-events-none absolute inset-x-0 bottom-0 h-[42%] w-full" aria-hidden>
      <polygon points={`0,30 ${points.join(' ')} 100,30`} style={{ fill: `color-mix(in srgb, ${color} 13%, transparent)` }} />
    </svg>
  )
}

function StepChip({ step, active }: { step: number; active: boolean }) {
  return (
    <span className={cn('flex size-4 items-center justify-center rounded-full border text-[9.5px] font-semibold tabular-nums',
      active ? 'border-primary bg-primary text-primary-foreground' : 'border-border bg-card')}>
      {step}
    </span>
  )
}

export function Diagram({ selected, onSelect }: { selected: string; onSelect: (id: string) => void }) {
  const { rates, rateHistory, stats, kafka, consistency, config } = useStore()
  const [hover, setHover] = useState<string | null>(null)

  const metrics = useMemo(() => {
    const m: Record<string, string> = {}
    if (!stats) return m
    const db = stats.database
    const c = stats.counters
    const running = (db.runs.RUNNING ?? 0) + (db.runs.COMPENSATING ?? 0)
    m.api = `${running} run${running === 1 ? '' : 's'} in progress`
    m.dispatcher = `${c.stepsClaimed} claimed · every ${config?.dispatchIntervalMs ?? '?'} ms`
    m.postgres = `${sum(db.runs)} runs · ${sum(db.steps)} steps · ${sum(db.attempts)} attempts · ${db.outboxPending} pending`
    m.outbox = `${c.outboxPublished} sent · ${c.outboxFailures} failed`
    m.listener = `${c.resultsApplied} applied · ${c.resultsStale} stale`
    m.recovery = `${c.leasesExpired} expired · ${db.leased.length} leased`
    const commandsGroup = kafka?.groups.find((g) => g.topic === 'flowforge.step-commands')
    const resultsGroup = kafka?.groups.find((g) => g.topic === 'flowforge.step-results')
    const topic = (name: string) => kafka?.topics.find((t) => t.name === name)?.messages ?? 0
    m.commands = kafka?.reachable ? `${topic('flowforge.step-commands')} msgs · lag ${commandsGroup?.lag ?? '?'}` : 'unreachable'
    m.results = kafka?.reachable ? `${topic('flowforge.step-results')} msgs · lag ${resultsGroup?.lag ?? '?'}` : 'unreachable'
    // Each worker process runs several consumer threads; their client ids end in -0, -1, -2…
    const processes = commandsGroup ? new Set(commandsGroup.members.map((x) => x.clientId.replace(/-\d+$/, ''))).size : 0
    m.workers = commandsGroup ? `${processes} worker${processes === 1 ? '' : 's'} · ${commandsGroup.members.length} threads` : '…'
    m.checker = consistency ? (consistency.consistent ? '✓ all 10 hold' : `✗ ${consistency.violations.length} broken`) : '…'
    return m
  }, [stats, kafka, consistency, config])

  const rateOf = (key: keyof Rates | null) => (key ? rates[key] ?? 0 : 0)
  const activeEdges = EDGES.filter((e) => rateOf(e.rate) > ACTIVE)
  const activeNodes = new Set(activeEdges.flatMap((e) => [e.from, e.to]))
  // Hovering a box focuses it and its connections; everything else fades back.
  const focusEdges = hover ? new Set(EDGES.filter((e) => e.from === hover || e.to === hover).map((e) => e.id)) : null
  const focusNodes = hover ? new Set([hover, ...EDGES.filter((e) => focusEdges!.has(e.id)).flatMap((e) => [e.from, e.to])]) : null
  const kafkaDown = kafka && !kafka.reachable

  return (
    <div className="space-y-3">
      <div className="overflow-x-auto overflow-y-hidden py-1">
        <div className="relative min-w-[860px] select-none" style={{ aspectRatio: `${W} / ${H}` }}>
          {/* edges and the messages travelling on the Kafka hops */}
          <svg viewBox={`0 0 ${W} ${H}`} className="absolute inset-0 size-full" aria-hidden>
            <defs>
              <marker id="ff-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M0 0 L10 5 L0 10 z" className="fill-muted-foreground/60" />
              </marker>
              <filter id="ff-glow" x="-50%" y="-50%" width="200%" height="200%">
                <feGaussianBlur stdDeviation="2.5" result="blur" />
                <feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
              </filter>
            </defs>
            {EDGES.map((e) => {
              const active = rateOf(e.rate) > ACTIVE
              const faded = focusEdges && !focusEdges.has(e.id)
              return (
                <path key={e.id} d={e.d} fill="none" markerEnd="url(#ff-arrow)" strokeWidth={active ? 1.75 : 1.25}
                  strokeDasharray={e.rate === null ? '4 4' : undefined}
                  className={cn('transition-[opacity,stroke] duration-300', active ? 'stroke-primary/60' : 'stroke-muted-foreground/40',
                    faded && 'opacity-15', focusEdges?.has(e.id) && 'stroke-primary')} />
              )
            })}
            {/* Magic UI's beam on every edge carrying traffic right now, drawn along the edge itself */}
            {activeEdges.map((e) => {
              const [start, end] = endpoints(e.d)
              return (
                <AnimatedBeamPath key={`beam-${e.id}`} d={e.d} start={start} end={end} pathOpacity={0} pathWidth={3}
                  duration={Math.max(0.6, 2.2 - Math.log10(1 + rateOf(e.rate)))}
                  gradientStartColor="var(--primary)" gradientStopColor="var(--s-running)"
                  className={cn('transition-opacity', focusEdges && !focusEdges.has(e.id) && 'opacity-15')} />
              )
            })}
            {activeEdges.filter((e) => e.messages).map((e) => {
              const rate = rateOf(e.rate)
              const dots = rate > 8 ? 3 : rate > 2 ? 2 : 1
              const duration = Math.max(0.7, 1.8 - Math.log10(1 + rate) * 0.6)
              return Array.from({ length: dots }, (_, i) => (
                <circle key={`${e.id}-${i}`} r={3.5} filter="url(#ff-glow)" style={{ fill: KIND.kafka.color }}>
                  <animateMotion dur={`${duration}s`} begin={`${(i * duration) / dots}s`} repeatCount="indefinite" path={e.d} />
                </circle>
              ))
            })}
          </svg>

          {/* edge labels: the loop order, what happens there, and the live rate */}
          {EDGES.map((e) => {
            const rate = rateOf(e.rate)
            const active = rate > ACTIVE
            const faded = focusEdges && !focusEdges.has(e.id)
            return (
              <div key={e.id}
                className={cn('pointer-events-none absolute flex items-center gap-1 text-[11px] whitespace-nowrap transition-opacity',
                  e.stack ? '-translate-x-1/2 flex-col gap-0.5' : '-translate-y-1/2',
                  active ? 'text-primary' : 'text-muted-foreground', faded && 'opacity-20')}
                style={{ left: `${(e.lx / W) * 100}%`, top: `${((e.stack ? e.ly : e.ly - 4) / H) * 100}%` }}>
                {e.stack ? (
                  <>
                    <span className="flex items-center gap-1">
                      <StepChip step={e.step!} active={active} />
                      {active && <span className="rounded bg-primary/15 px-1 font-mono text-[10px] tabular-nums">{rate.toFixed(1)}/s</span>}
                    </span>
                    <span className={cn(active && 'font-medium')}>{e.label}</span>
                  </>
                ) : (<>
                {e.step && (
                  <StepChip step={e.step} active={active} />
                )}
                <span className={cn(active && 'font-medium')}>{e.label}</span>
                {active && <span className="rounded bg-primary/15 px-1 font-mono text-[10px] tabular-nums">{rate.toFixed(1)}/s</span>}
                </>)}
              </div>
            )
          })}

          {/* the components */}
          {NODES.map((n) => {
            const { w, h } = size(n)
            const color = KIND[n.kind].color
            const Icon = n.icon
            const isSelected = n.id === selected
            const isActive = activeNodes.has(n.id)
            const faded = focusNodes && !focusNodes.has(n.id)
            const broken = (n.id === 'checker' && consistency && !consistency.consistent) || (n.kind === 'kafka' && kafkaDown)
            return (
              <div
                key={n.id}
                role="button"
                tabIndex={0}
                aria-pressed={isSelected}
                onClick={() => onSelect(n.id)}
                onMouseEnter={() => setHover(n.id)}
                onMouseLeave={() => setHover(null)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(n.id) } }}
                style={{
                  '--k': broken ? 'var(--s-failed)' : color,
                  left: `${(n.x / W) * 100}%`, top: `${(n.y / H) * 100}%`, width: `${(w / W) * 100}%`, height: `${(h / H) * 100}%`,
                } as CSSProperties}
                className={cn(
                  'group absolute flex cursor-pointer flex-col justify-between gap-0.5 overflow-hidden rounded-xl border bg-card/95 px-2.5 py-1.5 backdrop-blur-sm',
                  'transition-[border-color,box-shadow,opacity,transform] duration-300 hover:-translate-y-0.5 focus-visible:outline-none',
                  'hover:border-[color-mix(in_srgb,var(--k)_60%,transparent)] focus-visible:ring-2 focus-visible:ring-ring',
                  isActive && 'border-[color-mix(in_srgb,var(--k)_55%,transparent)] shadow-[0_0_22px_-6px_var(--k)]',
                  isSelected && 'border-[var(--k)] shadow-[0_0_0_3px_color-mix(in_srgb,var(--k)_28%,transparent)]',
                  broken && 'border-[var(--k)]',
                  faded && 'opacity-35',
                )}
              >
                <div className="relative flex min-w-0 items-start gap-2">
                  <span className="mt-px flex size-[22px] shrink-0 items-center justify-center rounded-md"
                    style={{ background: 'color-mix(in srgb, var(--k) 16%, transparent)', color: 'var(--k)' }}>
                    <Icon className={cn('size-3.5', isActive && n.id === 'recovery' && 'animate-spin [animation-duration:3s]')} />
                  </span>
                  <div className="min-w-0">
                    <div className="truncate text-[12.5px] leading-tight font-semibold">{n.title}</div>
                    <div className="truncate text-[11px] leading-tight text-muted-foreground">{n.sub}</div>
                  </div>
                </div>
                {n.rate && <Sparkline values={rateHistory.map((r) => r[n.rate!] ?? 0)} color={color} />}
                <div className="relative min-w-0 truncate font-mono text-[10.5px] leading-tight" style={{ color: 'var(--k)' }}>{metrics[n.id] ?? ''}</div>
              </div>
            )
          })}

        </div>
      </div>

      {/* legend */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[11px] text-muted-foreground">
        {(Object.keys(KIND) as NodeKind[]).map((k) => (
          <span key={k} className="flex items-center gap-1.5">
            <span className="size-2.5 rounded-[3px]" style={{ background: KIND[k].color }} /> {KIND[k].label}
          </span>
        ))}
        <span className="mx-1 h-3 w-px bg-border" />
        <span className="flex items-center gap-1.5">
          <span className="h-0.5 w-5 rounded-full bg-gradient-to-r from-primary to-s-running" /> traffic right now
        </span>
        <span className="flex items-center gap-1.5">
          <span className="size-2 rounded-full bg-s-retry shadow-[0_0_6px_var(--s-retry)]" /> a Kafka message
        </span>
        <span className="flex items-center gap-1.5">
          <span className="flex size-3.5 items-center justify-center rounded-full border bg-card text-[8.5px] font-semibold">1</span>
          the order a step goes round the loop
        </span>
        <span className="ml-auto hidden md:inline">hover a box to focus it · sparklines show the last minute</span>
      </div>
    </div>
  )
}
