import { AlertTriangle } from 'lucide-react'
import { Explain, Panel } from '@/components/common'
import { fmtDur, parseIsoDuration } from '@/lib/format'
import { useStore } from '@/lib/store'
import type { KafkaGroup } from '@/lib/types'
import { cn } from '@/lib/utils'

const MEMBER_COLORS = ['#5c7cfa', '#40c057', '#fab005', '#9775fa', '#f06595', '#22b8cf', '#7950f2', '#fd7e14', '#868e96']

function GroupPanel({ group, messages }: { group: KafkaGroup; messages: number }) {
  const colorOf = new Map(group.members.map((m, i) => [m.clientId, MEMBER_COLORS[i % MEMBER_COLORS.length]]))
  const who = group.topic === 'flowforge.step-commands' ? 'workers' : 'orchestrators'
  return (
    <Panel title={<code className="bg-transparent px-0 text-sm">{group.topic}</code>}
      hint={<>read by <code>{group.groupId}</code> ({who}) · {group.state.toLowerCase()}</>}>
      <div className="text-sm text-muted-foreground">
        {messages} messages written · total lag <b className={cn(group.lag > 0 ? 'text-s-retry' : 'text-foreground')}>{group.lag}</b>
      </div>
      <div>
        <h4 className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Members</h4>
        {group.members.length === 0 ? (
          <p className="text-sm text-s-failed">No live member: nobody is reading this topic. {who === 'workers' && 'Start a worker.'}</p>
        ) : (
          <div className="space-y-1.5">
            {group.members.map((m) => (
              <div key={m.clientId} className="flex flex-wrap items-center gap-2 text-sm">
                <span className="size-3 rounded-sm" style={{ background: colorOf.get(m.clientId) }} />
                <code>{m.clientId}</code>
                <span className="text-xs text-muted-foreground">{m.host}</span>
                <span className="ml-auto text-xs">partitions {m.partitions.join(', ') || '—'}</span>
              </div>
            ))}
          </div>
        )}
      </div>
      <div>
        <h4 className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Partitions</h4>
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-4 xl:grid-cols-6">
          {group.partitions.map((p) => (
            <div key={p.partition} className="rounded-md border border-l-4 bg-muted/20 p-2 text-xs"
              style={{ borderLeftColor: p.owner ? colorOf.get(p.owner) : 'var(--border)' }}>
              <div className="font-semibold">#{p.partition}</div>
              <div className="text-muted-foreground tabular-nums">{p.committed} / {p.endOffset}</div>
              <div className={cn('tabular-nums', p.lag > 0 ? 'font-semibold text-s-retry' : 'text-muted-foreground')}>lag {p.lag}</div>
            </div>
          ))}
        </div>
      </div>
    </Panel>
  )
}

function CrashGuide() {
  const { config } = useStore()
  const lease = fmtDur(parseIsoDuration(config?.leaseDuration) || 60_000)
  return (
    <Panel id="tour-crash" title="Try it: kill a worker">
      <ol className="list-decimal space-y-3 pl-5 text-sm leading-relaxed">
        <li>Click <b>Slow shipment</b> on the Live tab. Its <i>create-shipment</i> step will take 8 s.</li>
        <li>
          Within those 8 s, kill the worker that runs it. Locally (PowerShell, in the project folder):
          <pre className="mt-1.5 overflow-x-auto rounded-md bg-muted/60 p-2.5 font-mono text-xs">{`Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object CommandLine -match 'flowforge-worker' |
  Select-Object -First 1 | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`}</pre>
          On Kubernetes: <code>kubectl delete pod -n flowforge -l app.kubernetes.io/name=worker --force --grace-period=0</code> (one pod is enough).
        </li>
        <li>Watch this tab: within ~10 s the dead member disappears and its partitions are reassigned. The command it never
          acknowledged is delivered again, and <i>create-shipment</i> runs a second time on another worker — same attempt number,
          same idempotency key.</li>
        <li>
          If no worker is left at all, nothing happens until the lease ({lease}) expires; then the recovery sweeper times the
          attempt out and retries it as soon as a worker returns. Start a worker again with:
          <pre className="mt-1.5 overflow-x-auto rounded-md bg-muted/60 p-2.5 font-mono text-xs">java -jar flowforge-worker/target/flowforge-worker-0.1.0-SNAPSHOT.jar --server.port=8082</pre>
        </li>
        <li>Open the run: its timeline shows the gap, and the consistency check still passes.</li>
      </ol>
    </Panel>
  )
}

export function KafkaView() {
  const { kafka } = useStore()
  return (
    <div className="space-y-4">
      <Explain>
        Kafka keeps each topic as numbered <b>partitions</b>. Every message of a run has the run id as its key, so it always lands
        on the same partition and is read in order. A <b>consumer group</b> splits the partitions among its members; each member
        remembers how far it got (its committed <b>offset</b>), and <b>lag</b> = messages not yet processed. When a member dies,
        Kafka notices after the session timeout (10 s) and hands its partitions to the others — uncommitted messages are delivered
        again, which is why every step must be safe to run twice.
      </Explain>
      {kafka && !kafka.reachable && (
        <div className="flex items-center gap-2 rounded-lg border border-s-failed/40 bg-s-failed/10 p-3 text-sm">
          <AlertTriangle className="size-4 text-s-failed" />
          <span><b>Kafka is unreachable:</b> {kafka.error}. Commands stay safely in the outbox until it is back.</span>
        </div>
      )}
      {kafka?.reachable && (
        <div className="grid items-start gap-4 xl:grid-cols-2">
          {kafka.groups.map((g) => (
            <GroupPanel key={g.groupId} group={g} messages={kafka.topics.find((t) => t.name === g.topic)?.messages ?? 0} />
          ))}
        </div>
      )}
      <CrashGuide />
    </div>
  )
}
