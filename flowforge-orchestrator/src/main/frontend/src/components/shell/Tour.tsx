import { useEffect, useLayoutEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '@/components/ui/button'
import { useStore, type View } from '@/lib/store'

interface TourStep {
  view: View
  target: string
  title: string
  text: string
}

export const TOUR: TourStep[] = [
  { view: 'live', target: 'tour-scenarios', title: 'Start an order', text: 'Each button is a different input to the same workflow. Start with Normal order, then try the failures. The API answers immediately; the work happens in the background.' },
  { view: 'live', target: 'tour-diagram', title: 'How a step travels', text: 'Every step attempt goes round this loop. Beams flow along the edges that carry traffic right now. Click any box for what it does and the exact classes and methods behind it.' },
  { view: 'live', target: 'tour-runs', title: 'Recent runs', text: 'Every order, newest first, with its status and where it is now. Click one to open it — or press j / k.' },
  { view: 'run', target: 'tour-pipeline', title: 'A run, step by step', text: 'Forward steps on top, undo steps below if the run compensated. Dots are attempts; a running step shows its lease counting down. Click a step for its attempts and state machine.' },
  { view: 'run', target: 'tour-waterfall', title: 'Where the time went', text: 'Every attempt on one time axis: parallel steps side by side, retries and timers as hatched waiting.' },
  { view: 'run', target: 'tour-timeline', title: 'What happened, and why', text: 'The run\'s history rebuilt from the database: claimed, sent, result applied, retried, compensated. Each event explains which code did it.' },
  { view: 'engine', target: 'tour-leases', title: 'Leases and retries', text: 'What the engine is waiting on right now: attempts that hold a lease, and steps backing off before a retry. Counters, database totals, the 10 invariants and the live configuration are on this tab too.' },
  { view: 'kafka', target: 'tour-crash', title: 'Break something', text: 'Kafka partitions, which worker owns which, and lag. Follow these steps to kill a worker mid-step and watch the system recover.' },
  { view: 'learn', target: 'tour-journey', title: 'Learn', text: 'The life of one order, every failure mode, the guarantees, a map of every class, and a glossary. Guide mode shows explanation boxes everywhere; switch it off once you know your way around.' },
]

/** A highlight ring around a panel and a card explaining it, one step after another. */
export function Tour({ index, onIndex }: { index: number | null; onIndex: (i: number | null) => void }) {
  const { setView, runId, runs, openRun } = useStore()
  const [rect, setRect] = useState<DOMRect | null>(null)
  const step = index == null ? null : TOUR[index]

  useEffect(() => {
    if (!step) return
    if (step.view === 'run' && !runId && runs[0]) openRun(runs[0].runId, { switchView: false })
    setView(step.view)
  }, [step, runId, runs, openRun, setView])

  useLayoutEffect(() => {
    if (!step) return
    let frame = 0
    const place = () => {
      const target = document.getElementById(step.target) ?? document.querySelector('main')
      if (!target) return
      setRect(target.getBoundingClientRect())
    }
    const timer = setTimeout(() => {
      const target = document.getElementById(step.target)
      target?.scrollIntoView({ block: 'center' })
      place()
    }, 60)
    const onMove = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(place) }
    addEventListener('scroll', onMove, true)
    addEventListener('resize', onMove)
    return () => {
      clearTimeout(timer)
      cancelAnimationFrame(frame)
      removeEventListener('scroll', onMove, true)
      removeEventListener('resize', onMove)
    }
  }, [step])

  if (!step || index == null || !rect) return null
  const height = Math.min(rect.height, innerHeight - 40)
  const below = rect.top + height + 16
  const top = below + 210 < innerHeight ? below : Math.max(12, rect.top - 226)
  const left = Math.min(Math.max(12, rect.left), innerWidth - 372)
  const last = index === TOUR.length - 1

  return createPortal(
    <>
      <div className="pointer-events-none fixed z-50 rounded-xl ring-2 ring-primary transition-all duration-300"
        style={{ left: rect.left - 6, top: rect.top - 6, width: rect.width + 12, height: height + 12, boxShadow: '0 0 0 9999px rgb(0 0 0 / 0.45)' }} />
      <div className="fixed z-50 w-[360px] space-y-2 rounded-xl border bg-popover p-4 text-popover-foreground shadow-2xl" style={{ left, top }}>
        <div className="text-xs text-muted-foreground">{index + 1} / {TOUR.length}</div>
        <h3 className="font-semibold">{step.title}</h3>
        <p className="text-sm leading-relaxed text-muted-foreground">{step.text}</p>
        <div className="flex gap-2 pt-1">
          <Button variant="ghost" size="sm" onClick={() => onIndex(null)}>Close</Button>
          <span className="flex-1" />
          {index > 0 && <Button variant="outline" size="sm" onClick={() => onIndex(index - 1)}>Back</Button>}
          <Button size="sm" onClick={() => onIndex(last ? null : index + 1)}>{last ? 'Done' : 'Next'}</Button>
        </div>
      </div>
    </>,
    document.body,
  )
}
