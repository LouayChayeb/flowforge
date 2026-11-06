import { ChevronRight, Copy, Play, Send, Zap } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Explain, Panel, StatusBadge } from '@/components/common'
import { Button } from '@/components/ui/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { Textarea } from '@/components/ui/textarea'
import { useStartRun } from '@/lib/actions'
import type { Input } from '@/lib/types'
import { orderInput, SCENARIOS, WORKFLOW } from '@/lib/workflows'

export const BURST_MIX = ['normal', 'normal', 'normal', 'normal', 'normal', 'normal', 'flaky', 'flaky', 'declined', 'xx', 'down', 'normal']

export function Scenarios() {
  const start = useStartRun()
  const [custom, setCustom] = useState(() => JSON.stringify(orderInput({ shipmentFailures: 1 }), null, 2))
  const [busy, setBusy] = useState<string | null>(null)

  const run = async (id: string) => {
    const scenario = SCENARIOS.find((s) => s.id === id)!
    setBusy(id)
    await start(orderInput(scenario.input), { workflow: scenario.workflow ?? WORKFLOW, open: !!scenario.workflow })
    setBusy(null)
  }

  const burst = async () => {
    setBusy('burst')
    await Promise.all(Array.from({ length: 20 }, (_, i) =>
      start(orderInput(SCENARIOS.find((s) => s.id === BURST_MIX[i % BURST_MIX.length])!.input), { quiet: true })))
    setBusy(null)
    toast.success('Sent 20 orders', { description: 'Watch the beams light up and the Engine tab counters climb.' })
  }

  /** The same order sent twice at once, as a double click would: one run, one charge. */
  const twice = async () => {
    setBusy('twice')
    const input = orderInput({})
    const results = await Promise.all([start(input, { quiet: true }), start(input, { quiet: true })])
    setBusy(null)
    const created = results.filter(Boolean) as { runId: string; replayed: boolean }[]
    const runs = new Set(created.map((r) => r.runId)).size
    toast.success(`Sent ${String(input.orderId)} twice: ${runs} run`, {
      description: `${created.filter((r) => r.replayed).length} of the 2 requests got the run the other one started back. Same Idempotency-Key, so no second charge.`,
    })
  }

  const sendCustom = async () => {
    let input: Input
    try {
      input = JSON.parse(custom) as Input
    } catch (e) {
      toast.error('That is not valid JSON', { description: (e as Error).message })
      return
    }
    await start(input)
  }

  return (
    <Panel id="tour-scenarios" title="Start an order" hint="one click = one POST">
      <Explain>
        Each button sends <code>POST /api/workflows/order-processing/runs</code> with a different input. The API only
        <b> records</b> the run and answers <code>202 Accepted</code>; the work happens afterwards, in the background.
        Pick one and watch it move through the diagram.
      </Explain>
      <div className="grid gap-2">
        {SCENARIOS.map((s) => (
          <button
            key={s.id}
            onClick={() => run(s.id)}
            disabled={busy === s.id}
            className="group relative flex flex-col gap-1 rounded-lg border bg-card px-3 py-2.5 text-left transition-[border-color,background,transform] hover:-translate-y-px hover:border-primary/50 hover:bg-primary/[0.04] disabled:opacity-60"
          >
            <div className="flex items-center gap-2">
              <Play className="size-3.5 text-primary opacity-60 transition-opacity group-hover:opacity-100" />
              <span className="text-sm font-medium">{s.title}</span>
              <StatusBadge status={s.expect} className="ml-auto" />
            </div>
            <p className="pl-5.5 text-xs leading-relaxed text-muted-foreground">{s.text}</p>
          </button>
        ))}
      </div>

      <Collapsible>
        <CollapsibleTrigger className="group flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          <ChevronRight className="size-3.5 transition-transform group-data-[panel-open]:rotate-90" />
          Custom input…
        </CollapsibleTrigger>
        <CollapsibleContent className="space-y-2 pt-2">
          <Textarea value={custom} onChange={(e) => setCustom(e.target.value)} spellCheck={false} rows={8} className="font-mono text-xs" />
          <p className="text-xs text-muted-foreground">
            Fields the demo handlers read: <code>orderId</code>, <code>customerId</code>, <code>amount</code>, <code>country</code>,{' '}
            <code>shipmentFailures</code>, <code>shipmentDelayMs</code>, <code>refundFails</code>, <code>fraud</code>
          </p>
          <Button size="sm" onClick={sendCustom}><Send /> Send</Button>
        </CollapsibleContent>
      </Collapsible>

      <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2">
        <Button variant="outline" onClick={burst} disabled={busy === 'burst'}>
          <Zap /> Send 20 mixed orders
        </Button>
        <Button variant="outline" onClick={twice} disabled={busy === 'twice'}
          title="Two identical requests at the same moment: the Idempotency-Key makes the second one return the first run">
          <Copy /> Submit one order twice
        </Button>
      </div>
    </Panel>
  )
}
