import { AlertTriangle, Code2 } from 'lucide-react'
import { Html } from '@/components/common'
import { COMPONENTS } from '@/lib/knowledge'
import { fmtDur, parseIsoDuration } from '@/lib/format'
import { useStore } from '@/lib/store'

/** Fills the <span data-config="…"> placeholders in the explanations with this instance's settings. */
function useConfigFiller() {
  const { config } = useStore()
  return (html: string) => html.replace(/<span data-config="(\w+)"><\/span>/g, (_, key: string) => {
    if (!config) return '…'
    const values: Record<string, string> = {
      dispatchIntervalMs: `${config.dispatchIntervalMs} ms`,
      outboxIntervalMs: `${config.outboxIntervalMs} ms`,
      recoveryIntervalMs: `${config.recoveryIntervalMs} ms`,
      partitions: String(config.partitions),
      lease: fmtDur(parseIsoDuration(config.leaseDuration)),
    }
    return `<b>${values[key] ?? '?'}</b>`
  })
}

/** What the selected diagram box does, which code does it, and what happens when it fails. */
export function ComponentDetail({ id }: { id: string }) {
  const fill = useConfigFiller()
  const c = COMPONENTS[id]
  if (!c) return null
  return (
    <div className="grid gap-4 rounded-lg border bg-muted/30 p-4 md:grid-cols-[1.3fr_1fr]">
      <div className="space-y-2 text-sm leading-relaxed">
        <div className="flex flex-wrap items-baseline gap-x-3">
          <h3 className="text-base font-semibold">{c.title}</h3>
          <Html html={c.role} className="text-xs text-muted-foreground" />
        </div>
        {c.how.map((p, i) => <Html key={i} as="p" html={fill(p)} className="text-muted-foreground" />)}
      </div>
      <div className="space-y-3">
        <div>
          <h4 className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            <Code2 className="size-3.5" /> In the code
          </h4>
          <ul className="space-y-1.5 text-xs">
            {c.code.map(([name, note]) => (
              <li key={name}><code>{name}</code> <span className="text-muted-foreground">— {note}</span></li>
            ))}
          </ul>
        </div>
        <div className="rounded-md border border-s-retry/30 bg-s-retry/5 p-2.5 text-xs leading-relaxed">
          <div className="mb-1 flex items-center gap-1.5 font-semibold text-s-retry"><AlertTriangle className="size-3.5" /> If it fails</div>
          <Html html={c.failure} className="text-muted-foreground" />
        </div>
      </div>
    </div>
  )
}
