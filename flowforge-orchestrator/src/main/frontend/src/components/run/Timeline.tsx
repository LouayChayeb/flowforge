import { ChevronRight } from 'lucide-react'
import { useState } from 'react'
import { Html, Panel } from '@/components/common'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { fmtClock, fmtDur, ms } from '@/lib/format'
import { buildTimeline } from '@/lib/runModel'
import { useNow, useStore } from '@/lib/store'
import type { Command, Run } from '@/lib/types'

export function Timeline({ run, commands }: { run: Run; commands: Command[] }) {
  const { config } = useStore()
  const now = useNow(1000)
  const [open, setOpen] = useState<Set<string>>(new Set())
  const events = buildTimeline(run, commands, config, now)
  const start = ms(run.createdAt)!

  const toggle = (key: string) => setOpen((prev) => {
    const next = new Set(prev)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    return next
  })

  return (
    <Panel id="tour-timeline" title="What happened, in order" hint="rebuilt from the stored attempts and outbox rows · expand an event for the code behind it">
      <ol className="relative ml-[72px] border-l">
        {events.map((e, i) => {
          const key = `${e.kind}-${e.title}-${i}`
          return (
            <li key={key} className="relative pb-3 pl-5 last:pb-0">
              <span className="absolute top-1 -left-[72px] w-14 text-right font-mono text-[11px] text-muted-foreground tabular-nums" title={fmtClock(e.iso)}>
                +{fmtDur(e.t - start)}
              </span>
              <span className="absolute top-1.5 -left-[5px] size-2.5 rounded-full ring-4 ring-card" style={{ background: e.color }} />
              <Collapsible open={open.has(key)} onOpenChange={() => toggle(key)}>
                <CollapsibleTrigger className="group flex w-full items-start gap-1 text-left">
                  <span className="text-sm font-medium">{e.title}</span>
                  <ChevronRight className="mt-0.5 ml-1 size-3.5 shrink-0 text-muted-foreground transition-transform group-data-[panel-open]:rotate-90" />
                  <span className="text-[11px] text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100">why?</span>
                </CollapsibleTrigger>
                {e.detail && <div className="text-xs text-muted-foreground">{e.detail}</div>}
                <CollapsibleContent>
                  <Html as="div" html={e.why} className="mt-1.5 rounded-md bg-muted/50 p-2.5 text-xs leading-relaxed text-muted-foreground" />
                </CollapsibleContent>
              </Collapsible>
            </li>
          )
        })}
      </ol>
    </Panel>
  )
}
