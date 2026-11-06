import { X } from 'lucide-react'
import { Empty, Panel, StatusBadge } from '@/components/common'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ago, fmtDur, humanize, ms } from '@/lib/format'
import { colorOf, RUN_STATUS_ORDER } from '@/lib/status'
import { useNow, useStore } from '@/lib/store'
import type { RunStatus } from '@/lib/types'
import { cn } from '@/lib/utils'
import { scenarioOf } from '@/lib/workflows'

/** Status chips: counts per status, one click to filter. */
function StatusChips() {
  const { runs, filter, setFilter } = useStore()
  const counts: Record<string, number> = {}
  for (const r of runs) counts[r.status] = (counts[r.status] ?? 0) + 1
  const shown = RUN_STATUS_ORDER.filter((s) => counts[s] || s === filter)
  if (!shown.length) return null
  return (
    <div className="flex flex-wrap gap-1.5 px-4 pt-3">
      {filter && (
        <button onClick={() => setFilter('')}
          className="inline-flex items-center gap-1 rounded-full border border-primary bg-primary/10 px-2.5 py-0.5 text-xs">
          show all <X className="size-3" />
        </button>
      )}
      {shown.map((s) => (
        <button key={s} onClick={() => setFilter(s === filter ? '' : s)}
          className={cn('inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs transition-colors hover:border-[var(--c)]',
            s === filter && 'border-primary bg-primary/10')}
          style={{ '--c': colorOf(s) } as React.CSSProperties}>
          <span className="size-2 rounded-full bg-[var(--c)]" />
          {humanize(s)} <b className="tabular-nums">{counts[s] ?? 0}</b>
        </button>
      ))}
    </div>
  )
}

export function RunsTable() {
  const { runs, runId, openRun, filter, setFilter } = useStore()
  const now = useNow(1000)

  return (
    <Panel
      id="tour-runs"
      title="Recent runs"
      hint="click a run to open it · j / k to move"
      contentClassName="p-0 space-y-0"
      actions={
        <Select value={filter || 'ALL'} onValueChange={(v) => setFilter(v === 'ALL' ? '' : (v as RunStatus))}>
          <SelectTrigger size="sm" className="w-44"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All statuses</SelectItem>
            {RUN_STATUS_ORDER.map((s) => <SelectItem key={s} value={s}>{humanize(s)}</SelectItem>)}
          </SelectContent>
        </Select>
      }
    >
      <StatusChips />
      {runs.length === 0 ? (
        <Empty>{filter ? 'No run has this status right now.' : 'No runs yet. Start an order on the left.'}</Empty>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Order</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Now at</TableHead>
              <TableHead className="text-right">Attempts</TableHead>
              <TableHead className="text-right">Duration</TableHead>
              <TableHead className="pr-4 text-right">Started</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {runs.map((r) => {
              const end = r.completedAt ? ms(r.completedAt)! : now
              return (
                <TableRow key={r.runId} onClick={() => openRun(r.runId)}
                  className={cn('cursor-pointer', r.runId === runId && 'bg-primary/5')}>
                  <TableCell className="pl-4">
                    <div className="font-medium">{String(r.input?.orderId ?? r.runId.slice(0, 8))}</div>
                    <div className="text-xs text-muted-foreground">{scenarioOf(r.input, r.workflow)}</div>
                  </TableCell>
                  <TableCell><StatusBadge status={r.status} /></TableCell>
                  <TableCell className="text-sm">
                    {r.currentStep ? (
                      <>
                        {r.currentStepKind === 'COMPENSATION' && <span className="text-s-compensating">undo </span>}
                        {r.currentStep}
                        {r.activeSteps > 1 && <span className="ml-1 text-xs text-muted-foreground" title="steps running in parallel">+{r.activeSteps - 1}</span>}
                      </>
                    ) : <span className="text-muted-foreground/50">—</span>}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {r.attempts}
                    {r.failedAttempts > 0 && <span className="ml-1 text-xs text-s-failed">({r.failedAttempts} failed)</span>}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{fmtDur(end - ms(r.createdAt)!)}</TableCell>
                  <TableCell className="pr-4 text-right text-xs text-muted-foreground">{ago(r.createdAt, now)}</TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
    </Panel>
  )
}
