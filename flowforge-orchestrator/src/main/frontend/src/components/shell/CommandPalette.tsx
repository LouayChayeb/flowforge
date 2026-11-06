import { BookOpen, Cpu, LayoutDashboard, Moon, Network, Play, Route, Sparkles } from 'lucide-react'
import { StatusBadge } from '@/components/common'
import { Command, CommandDialog, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList, CommandSeparator, CommandShortcut } from '@/components/ui/command'
import { useStartRun } from '@/lib/actions'
import { useStore, type View } from '@/lib/store'
import { orderInput, SCENARIOS, scenarioOf, WORKFLOW } from '@/lib/workflows'

const VIEW_ITEMS: [View, string, typeof LayoutDashboard][] = [
  ['live', 'Live', LayoutDashboard], ['run', 'Run detail', Route], ['engine', 'Engine', Cpu], ['kafka', 'Kafka', Network], ['learn', 'Learn', BookOpen],
]

/** ⌘K: jump to a view, open a recent run, or start a scenario — without leaving the keyboard. */
export function CommandPalette({ open, onOpenChange, onTour, onTheme }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onTour: () => void
  onTheme: () => void
}) {
  const { setView, runs, openRun } = useStore()
  const start = useStartRun()
  const run = (fn: () => void) => { onOpenChange(false); fn() }

  return (
    <CommandDialog open={open} onOpenChange={onOpenChange} title="Command palette" description="Jump to a view, open a run or start an order">
      <Command>
        <CommandInput placeholder="Type a command, a run or a scenario…" />
        <CommandList>
          <CommandEmpty>Nothing matches.</CommandEmpty>
          <CommandGroup heading="Go to">
            {VIEW_ITEMS.map(([view, label, Icon], i) => (
              <CommandItem key={view} value={`go ${label}`} onSelect={() => run(() => setView(view))}>
                <Icon /> {label} <CommandShortcut>{i + 1}</CommandShortcut>
              </CommandItem>
            ))}
          </CommandGroup>
          <CommandSeparator />
          <CommandGroup heading="Start an order">
            {SCENARIOS.map((s) => (
              <CommandItem key={s.id} value={`start ${s.title} ${s.expect}`}
                onSelect={() => run(() => { start(orderInput(s.input), { workflow: s.workflow ?? WORKFLOW, open: !!s.workflow }) })}>
                <Play /> {s.title} <span className="ml-auto"><StatusBadge status={s.expect} /></span>
              </CommandItem>
            ))}
          </CommandGroup>
          {runs.length > 0 && (
            <>
              <CommandSeparator />
              <CommandGroup heading="Recent runs">
                {runs.slice(0, 12).map((r) => (
                  <CommandItem key={r.runId} value={`run ${String(r.input?.orderId ?? '')} ${r.runId} ${scenarioOf(r.input, r.workflow)} ${r.status}`}
                    onSelect={() => run(() => openRun(r.runId))}>
                    <Route /> {String(r.input?.orderId ?? r.runId.slice(0, 8))}
                    <span className="text-muted-foreground">{scenarioOf(r.input, r.workflow)}</span>
                    <span className="ml-auto"><StatusBadge status={r.status} /></span>
                  </CommandItem>
                ))}
              </CommandGroup>
            </>
          )}
          <CommandSeparator />
          <CommandGroup heading="Other">
            <CommandItem value="take the tour" onSelect={() => run(onTour)}><Sparkles /> Take the tour</CommandItem>
            <CommandItem value="toggle theme light dark" onSelect={() => run(onTheme)}><Moon /> Toggle light / dark <CommandShortcut>t</CommandShortcut></CommandItem>
          </CommandGroup>
        </CommandList>
      </Command>
    </CommandDialog>
  )
}
