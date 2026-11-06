import { useId } from 'react'
import { humanize } from '@/lib/format'
import { colorOf } from '@/lib/status'
import { cn } from '@/lib/utils'

export interface Transition {
  from: string
  to: string
  label?: string
  /** A custom path; by default a curve from the right edge of `from` to the left edge of `to`. */
  d?: string
}

/**
 * A small state diagram: the states a run (or step) went through are filled in, the current one glows,
 * and the transitions it took are drawn solid.
 */
export function StateMachine({ states, transitions, visited, taken, current, descriptions, width, height, nodeWidth = 130, nodeHeight = 30 }: {
  states: Record<string, [number, number]>
  transitions: Transition[]
  visited: Set<string>
  taken: Set<string>
  current: string
  descriptions: Record<string, string>
  width: number
  height: number
  nodeWidth?: number
  nodeHeight?: number
}) {
  const marker = useId().replace(/:/g, '')
  const path = (t: Transition) => {
    if (t.d) return t.d
    const [x1, y1] = states[t.from]
    const [x2, y2] = states[t.to]
    const sx = x1 + nodeWidth
    const sy = y1 + nodeHeight / 2
    const ey = y2 + nodeHeight / 2
    const mx = (sx + x2) / 2
    return `M${sx} ${sy} C${mx} ${sy} ${mx} ${ey} ${x2 - 2} ${ey}`
  }
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="w-full" role="img" aria-label={`State machine, now ${current}`}>
      <defs>
        <marker id={marker} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
          <path d="M0 0 L10 5 L0 10 z" className="fill-muted-foreground/60" />
        </marker>
      </defs>
      {transitions.map((t) => {
        const isTaken = taken.has(`${t.from}>${t.to}`)
        return (
          <path key={`${t.from}>${t.to}`} d={path(t)} fill="none" markerEnd={`url(#${marker})`}
            className={cn(isTaken ? 'stroke-primary' : 'stroke-muted-foreground/25')} strokeWidth={isTaken ? 2 : 1.25}
            strokeDasharray={isTaken ? undefined : '4 4'}>
            {t.label && <title>{t.label}</title>}
          </path>
        )
      })}
      {Object.entries(states).map(([name, [x, y]]) => {
        const isVisited = visited.has(name)
        const isNow = current === name
        const c = colorOf(name)
        return (
          <g key={name}>
            <title>{descriptions[name] ?? name}</title>
            <rect x={x} y={y} width={nodeWidth} height={nodeHeight} rx={nodeHeight / 2}
              style={{
                fill: isVisited ? `color-mix(in srgb, ${c} ${isNow ? 26 : 12}%, var(--card))` : 'var(--card)',
                stroke: isVisited ? c : 'var(--border)',
                strokeWidth: isNow ? 2.5 : 1.25,
                filter: isNow ? `drop-shadow(0 0 6px color-mix(in srgb, ${c} 60%, transparent))` : undefined,
              }} />
            <text x={x + nodeWidth / 2} y={y + nodeHeight / 2 + 4} textAnchor="middle"
              className={cn('text-[11px] font-medium', isVisited ? 'fill-foreground' : 'fill-muted-foreground/60')}>
              {humanize(name)}
            </text>
          </g>
        )
      })}
    </svg>
  )
}
