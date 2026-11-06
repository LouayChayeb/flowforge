import { Check, X } from 'lucide-react'
import { Bar, Explain, Panel, StatusBadge } from '@/components/common'
import { NumberTicker } from '@/components/ui/number-ticker'
import { ago, fmtDur, ms, parseIsoDuration } from '@/lib/format'
import { colorOf } from '@/lib/status'
import { useNow, useStore } from '@/lib/store'
import { cn } from '@/lib/utils'

function Counters() {
  const { stats, rates } = useStore()
  if (!stats) return null
  const c = stats.counters
  const items: [string, number, number | undefined, string][] = [
    ['Steps claimed', c.stepsClaimed, rates.claimed, 'StepStateService.claim()'],
    ['Commands published', c.outboxPublished, rates.published, 'OutboxPublisher'],
    ['Results applied', c.resultsApplied, rates.applied, 'StepResultListener'],
    ['Stale results rejected', c.resultsStale, rates.stale, 'duplicates and late results'],
    ['Leases expired', c.leasesExpired, rates.expired, 'recovery sweeper'],
    ['Publish failures', c.outboxFailures, rates.failures, 'retried on the next pass'],
  ]
  return (
    <Panel title="Counters" hint={`instance ${stats.instance}`}>
      <Explain>
        Micrometer counters kept by <code>EngineMetrics</code>, also at <code>/actuator/metrics/flowforge.*</code>. They belong to
        <b> this orchestrator instance</b> and restart at zero with it; with two orchestrators each counts its own share.
      </Explain>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {items.map(([label, value, rate, note]) => (
          <div key={label} className="rounded-lg border bg-muted/20 p-3" title={note}>
            <div className="text-xs text-muted-foreground">{label}</div>
            <NumberTicker value={value} startValue={value} className="text-xl font-semibold text-inherit" />
            <div className="h-4 text-xs text-primary tabular-nums">{rate && rate > 0.05 ? `${rate.toFixed(1)}/s` : ''}</div>
          </div>
        ))}
      </div>
    </Panel>
  )
}

function LeasesAndWaiting() {
  const { stats, config, openRun } = useStore()
  const now = useNow(250)
  if (!stats) return null
  const lease = parseIsoDuration(config?.leaseDuration) || 60_000
  const { leased, waiting } = stats.database
  return (
    <Panel id="tour-leases" title="Leases and retries right now">
      <Explain>
        A claimed step holds a <b>lease</b>: if no result arrives before it expires, the recovery sweeper times the attempt out
        and retries it. A failed step waits in <code>RETRY_WAIT</code> until its backoff is over. Start <i>Slow shipment</i> or
        <i> Flaky carrier</i> to see these fill up.
      </Explain>
      <div>
        <h4 className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Running (lease countdown)</h4>
        {leased.length === 0 ? <p className="text-sm text-muted-foreground">Nothing running right now.</p> : (
          <div className="space-y-2">
            {leased.map((x) => {
              const left = Math.max(0, ms(x.leaseExpiresAt)! - now)
              return (
                <div key={`${x.runId}-${x.stepName}-${x.attempt}`} className="grid grid-cols-[minmax(0,180px)_1fr_64px] items-center gap-3 text-sm">
                  <button className="truncate text-left text-primary hover:underline" onClick={() => openRun(x.runId)}>
                    {x.kind === 'COMPENSATION' ? 'undo ' : ''}{x.stepName} #{x.attempt}
                  </button>
                  <Bar fraction={left / lease} color="var(--s-running)" />
                  <span className="text-right font-mono text-xs tabular-nums">{fmtDur(left)}</span>
                </div>
              )
            })}
          </div>
        )}
      </div>
      <div>
        <h4 className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Waiting (eligible at)</h4>
        {waiting.length === 0 ? <p className="text-sm text-muted-foreground">No step is waiting to be claimed.</p> : (
          <div className="space-y-1.5">
            {waiting.map((x) => {
              const left = ms(x.nextAttemptAt)! - now
              return (
                <div key={`${x.runId}-${x.stepName}`} className="flex items-center gap-3 text-sm">
                  <button className="truncate text-primary hover:underline" onClick={() => openRun(x.runId)}>
                    {x.kind === 'COMPENSATION' ? 'undo ' : ''}{x.stepName}
                  </button>
                  <StatusBadge status={x.status} />
                  <span className="text-xs text-muted-foreground">attempt {x.attempt}/{x.maxAttempts}</span>
                  <span className="ml-auto font-mono text-xs tabular-nums">{left > 0 ? fmtDur(left) : 'now'}</span>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </Panel>
  )
}

function CountBars({ title, counts, order }: { title: string; counts: Record<string, number>; order: string[] }) {
  const keys = [...order.filter((k) => counts[k] != null), ...Object.keys(counts).filter((k) => !order.includes(k))]
  const max = Math.max(1, ...Object.values(counts))
  return (
    <div>
      <h4 className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h4>
      {keys.length === 0 ? <p className="text-sm text-muted-foreground">none</p> : (
        <div className="space-y-1.5">
          {keys.map((k) => (
            <div key={k} className="grid grid-cols-[150px_1fr_56px] items-center gap-3">
              <StatusBadge status={k} />
              <Bar fraction={counts[k] / max} color={colorOf(k)} className="h-2" />
              <span className="text-right font-mono text-xs tabular-nums">{counts[k]}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function Database() {
  const { stats } = useStore()
  const now = useNow(1000)
  if (!stats) return null
  const db = stats.database
  return (
    <Panel title="What the database holds">
      <p className="text-sm text-muted-foreground">
        Live counts straight from the tables, across all orchestrator instances. Outbox: <b className="text-foreground">{db.outboxPending}</b> pending,{' '}
        {db.outboxPublished} published{db.oldestPendingCommand ? `, oldest waiting ${fmtDur(now - ms(db.oldestPendingCommand)!)}` : ''}.
      </p>
      <CountBars title="Runs (workflow_run)" counts={db.runs} order={['RUNNING', 'COMPENSATING', 'COMPLETED', 'COMPENSATED', 'FAILED', 'COMPENSATION_FAILED', 'CANCELLED']} />
      <CountBars title="Steps (step_execution)" counts={db.steps} order={['PENDING', 'RUNNING', 'RETRY_WAIT', 'COMPLETED', 'FAILED']} />
      <CountBars title="Attempts (step_attempt)" counts={db.attempts} order={['RUNNING', 'SUCCEEDED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'TIMED_OUT']} />
    </Panel>
  )
}

function Invariants() {
  const { consistency, invariants } = useStore()
  const now = useNow(1000)
  const broken = new Map((consistency?.violations ?? []).map((v) => [v.invariant, v]))
  return (
    <Panel title={`Correctness: the ${invariants.length || 10} invariants`}
      actions={consistency && (
        <span className={cn('rounded-full border px-2.5 py-0.5 text-xs font-medium',
          consistency.consistent ? 'border-s-completed/40 bg-s-completed/10 text-s-completed' : 'border-s-failed/40 bg-s-failed/10 text-s-failed')}>
          {consistency.consistent ? 'all hold' : `${broken.size} broken`}
        </span>
      )}>
      <Explain>
        <code>ConsistencyChecker</code> runs one SQL query per rule, all in one repeatable-read snapshot, and lists any rows that
        break it. It checks <b>the history itself</b>, so it would catch a bug however it happened — the stress tests run it after
        chaos. Re-checked every 5 s.
      </Explain>
      <div className="space-y-2">
        {invariants.map((inv) => {
          const v = broken.get(inv.name)
          return (
            <div key={inv.name} className="flex gap-3">
              <span className={cn('mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full',
                v ? 'bg-s-failed/15 text-s-failed' : 'bg-s-completed/15 text-s-completed')}>
                {v ? <X className="size-3" /> : <Check className="size-3" />}
              </span>
              <div className="min-w-0 text-sm">
                <code>{inv.name}</code>
                <div className="text-xs text-muted-foreground">{inv.description}</div>
                {v && <div className="text-xs text-s-failed">{v.count} offending rows, e.g. {v.sample.join(', ')}</div>}
              </div>
            </div>
          )
        })}
      </div>
      {consistency && <p className="text-xs text-muted-foreground">checked {ago(consistency.checkedAt, now)}</p>}
    </Panel>
  )
}

function PaymentsPanel() {
  const { payments } = useStore()
  let body
  if (!payments) body = <p className="text-sm text-muted-foreground">Loading…</p>
  else if (!payments.configured) {
    body = (
      <p className="text-sm text-muted-foreground">
        Not connected: start <code>flowforge-payments</code> and give the orchestrator <code>--flowforge.insight.payments-url=http://localhost:8084</code>{' '}
        (and the workers <code>--flowforge.worker.payments-url=…</code>). Without it, workers simulate payments in memory.
      </p>
    )
  } else if (!payments.reachable || !payments.summary) {
    body = <p className="text-sm text-s-failed">Provider at {payments.url} unreachable: {payments.error}</p>
  } else {
    const s = payments.summary
    const items: [string, number, string][] = [
      ['Charges', s.charges, ''], ['Captured', s.captured, ''], ['Declined', s.declined, ''], ['Refunded', s.refunded, ''],
      ['Duplicates absorbed', s.duplicateRequestsAbsorbed, 'requests that repeated a key and changed nothing'],
      ['Orders charged twice', s.ordersChargedMoreThanOnce, 'must be 0'],
    ]
    body = (
      <>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {items.map(([label, value, note]) => (
            <div key={label} className="rounded-lg border bg-muted/20 p-3" title={note}>
              <div className="text-xs text-muted-foreground">{label}</div>
              <div className={cn('text-xl font-semibold tabular-nums',
                label === 'Orders charged twice' && (value ? 'text-s-failed' : 'text-s-completed'))}>{value}</div>
            </div>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">Captured total {String(s.capturedAmount)} · provider at <code>{payments.url}</code></p>
      </>
    )
  }
  return (
    <Panel id="tour-payments" title="Payment provider" hint="what the outside world saw">
      <Explain>
        A step can run more than once (a worker dies after charging, before reporting), so exactly-once can only come from the
        other side: the <b>simulated provider</b> (<code>flowforge-payments</code>) remembers every <code>Idempotency-Key</code> —
        FlowForge sends the step execution id — and answers a repeat with the original charge instead of charging again.
        <b> Duplicates absorbed</b> counts those repeats; <b>orders charged twice</b> must stay 0.
      </Explain>
      {body}
    </Panel>
  )
}

function ConfigPanel() {
  const { config: c } = useStore()
  if (!c) return null
  const lease = parseIsoDuration(c.leaseDuration)
  const init = parseIsoDuration(c.initialBackoff) ?? 0
  const max = parseIsoDuration(c.maxBackoff) ?? 0
  const schedule = [1, 2, 3, 4, 5].map((n) => fmtDur(Math.min(init * c.backoffMultiplier ** (n - 1), max))).join(', ')
  const items: [string, string, string][] = [
    ['Instant dispatch (wake-up)', c.wakeup ? 'on' : 'off', 'Claim a step the moment it becomes ready (after the transaction that made it ready commits), and send its command as soon as it is claimed, instead of waiting for the next tick. EngineWakeup.'],
    ['Dispatch interval', `${c.dispatchIntervalMs} ms`, c.wakeup ? 'The safety-net poll: catches anything a wake-up missed, e.g. after a crash between commit and wake-up.' : 'How often each orchestrator looks for ready steps — most of a step\'s waiting time at low load.'],
    ['Outbox interval', `${c.outboxIntervalMs} ms`, 'How often pending commands are sent to Kafka.'],
    ['Recovery interval', `${c.recoveryIntervalMs} ms`, 'How often expired leases are looked for.'],
    ['Batch size', String(c.batchSize), 'Steps claimed (and outbox rows sent) per transaction.'],
    ['Lease duration', fmtDur(lease), 'How long an attempt may take before it is presumed lost. Must exceed the slowest step plus time queued in Kafka.'],
    ['Retry backoff', `${fmtDur(init)} × ${c.backoffMultiplier}, max ${fmtDur(max)}`, `Waits after failures 1–5: ${schedule}.`],
    ['Kafka partitions', String(c.partitions), 'Upper bound on parallel workers: one thread per partition at a time.'],
    ['Result listener threads', String(c.resultListenerThreads), 'Consumers of step-results in this instance.'],
    ['Background loops', c.schedulerEnabled ? 'enabled' : 'disabled', 'Off in the integration tests, which drive the loops themselves.'],
  ]
  return (
    <Panel title="Configuration of this instance">
      <div className="divide-y">
        {items.map(([k, v, text]) => (
          <div key={k} className="py-2 first:pt-0 last:pb-0">
            <div className="flex items-baseline gap-3 text-sm"><span className="font-medium">{k}</span><span className="ml-auto font-mono text-xs text-primary">{v}</span></div>
            <div className="text-xs text-muted-foreground">{text}</div>
          </div>
        ))}
      </div>
    </Panel>
  )
}

export function EngineView() {
  return (
    <div className="grid items-start gap-4 lg:grid-cols-2">
      <div className="space-y-4"><Counters /><LeasesAndWaiting /><Database /></div>
      <div className="space-y-4"><Invariants /><PaymentsPanel /><ConfigPanel /></div>
    </div>
  )
}
