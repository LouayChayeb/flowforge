import { useEffect, useState } from 'react'
import { Empty, Panel } from '@/components/common'
import { OperatorDialog, type OperatorAction } from '@/components/run/OperatorDialog'
import { RunHeader } from '@/components/run/RunHeader'
import { StepDetail } from '@/components/run/StepDetail'
import { StepLanes } from '@/components/run/StepLanes'
import { Timeline } from '@/components/run/Timeline'
import { Waterfall } from '@/components/run/Waterfall'
import { Button } from '@/components/ui/button'
import { defaultStep } from '@/lib/runModel'
import { useStore } from '@/lib/store'
import { useRunDetail } from '@/lib/useRunDetail'

export function RunView() {
  const { runId, runs, openRun, setView, pollSoon } = useStore()
  const { run, commands, payments, missing, refresh } = useRunDetail(runId)
  const [stepId, setStepId] = useState<string | null>(null)
  const [operator, setOperator] = useState<OperatorAction | null>(null)

  useEffect(() => setStepId(null), [runId])

  if (!runId || missing) {
    return (
      <Panel title="Run detail">
        <Empty>
          {missing ? 'That run no longer exists.' : 'No run selected.'}{' '}
          {runs[0]
            ? <Button variant="link" className="px-1" onClick={() => openRun(runs[0].runId)}>Open the latest run</Button>
            : <Button variant="link" className="px-1" onClick={() => setView('live')}>Start an order on the Live tab</Button>}
        </Empty>
      </Panel>
    )
  }
  if (!run) return <Panel title="Run detail"><Empty>Loading…</Empty></Panel>

  const selected = run.steps.find((s) => s.stepExecutionId === stepId) ?? defaultStep(run)

  return (
    <div className="space-y-4">
      <RunHeader run={run} payments={payments} onOperator={setOperator} />
      <StepLanes run={run} selectedId={selected?.stepExecutionId ?? null} onSelect={setStepId} />
      {selected && <StepDetail step={selected} commands={commands} />}
      <Waterfall run={run} selectedId={selected?.stepExecutionId ?? null} onSelect={setStepId} />
      <Timeline run={run} commands={commands} />
      <OperatorDialog runId={run.runId} action={operator} onClose={() => setOperator(null)} onDone={() => { refresh(); pollSoon() }} />
    </div>
  )
}
