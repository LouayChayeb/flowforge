import { Play, Zap } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Explain, Panel } from '@/components/common'
import { ComponentDetail } from '@/components/live/ComponentDetail'
import { Diagram } from '@/components/live/Diagram'
import { KpiStrip } from '@/components/live/KpiStrip'
import { RunsTable } from '@/components/live/RunsTable'
import { BURST_MIX, Scenarios } from '@/components/live/Scenarios'
import { Button } from '@/components/ui/button'
import { useStartRun } from '@/lib/actions'
import { orderInput, SCENARIOS } from '@/lib/workflows'

/** Start traffic without scrolling down to the scenarios. */
function QuickStart() {
  const start = useStartRun()
  const [busy, setBusy] = useState(false)
  const burst = async () => {
    setBusy(true)
    await Promise.all(Array.from({ length: 20 }, (_, i) =>
      start(orderInput(SCENARIOS.find((s) => s.id === BURST_MIX[i % BURST_MIX.length])!.input), { quiet: true })))
    setBusy(false)
    toast.success('Sent 20 orders', { description: 'Watch the beams and the messages go round the loop.' })
  }
  return (
    <>
      <Button size="sm" variant="outline" onClick={() => start(orderInput({}))}><Play /> One order</Button>
      <Button size="sm" onClick={burst} disabled={busy}><Zap /> Send 20</Button>
    </>
  )
}

export function LiveView() {
  const [component, setComponent] = useState('dispatcher')
  return (
    <div className="space-y-4">
      <KpiStrip />
      <Panel id="tour-diagram" title="How a step travels" hint="click any box for what it does and the code behind it" actions={<QuickStart />}>
        <Explain>
          Every step of every order goes round this loop once per attempt, in the numbered order: <b>(1) claimed</b> from
          PostgreSQL and written to the outbox in the same transaction → <b>(2–3) published</b> to Kafka → <b>(4) executed</b> by
          a worker → <b>(5–6) result</b> back through Kafka → <b>(7) applied</b> to the database, which makes the next step ready.
          The database is the single source of truth; Kafka only moves messages.
        </Explain>
        <Diagram selected={component} onSelect={setComponent} />
        <ComponentDetail id={component} />
      </Panel>
      <div className="grid items-start gap-4 xl:grid-cols-[380px_minmax(0,1fr)]">
        <Scenarios />
        <RunsTable />
      </div>
    </div>
  )
}
