import { Check, Search, X } from 'lucide-react'
import { forwardRef, useMemo, type ReactNode } from 'react'
import { Html, Panel } from '@/components/common'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { CODE_MAP, FAILURES, GLOSSARY, GUARANTEES, JOURNEY } from '@/lib/knowledge'

/** Highlights every occurrence of the query. */
function Mark({ text, query }: { text: string; query: string }): ReactNode {
  if (!query) return text
  const parts = text.split(new RegExp(`(${query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi'))
  return parts.map((p, i) => (i % 2 ? <mark key={i} className="rounded bg-primary/25 px-0.5 text-foreground">{p}</mark> : p))
}

export const CodeSearch = forwardRef<HTMLInputElement, { value: string; onChange: (v: string) => void }>(
  function CodeSearch({ value, onChange }, ref) {
    return (
      <div className="relative w-64">
        <Search className="absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
        <Input ref={ref} value={value} onChange={(e) => onChange(e.target.value)} placeholder="Filter, e.g. lease, outbox, claim" className="h-8 pl-8" />
      </div>
    )
  },
)

function CodeMap({ query }: { query: string }) {
  const q = query.trim().toLowerCase()
  const packages = useMemo(() => CODE_MAP.map((p) => ({
    ...p,
    classes: p.classes.filter(([name, kind, does, methods]) =>
      !q || [p.module, p.pkg, name, kind, does, ...methods.flat()].join(' ').toLowerCase().includes(q)),
  })).filter((p) => p.classes.length), [q])
  if (!packages.length) return <p className="text-sm text-muted-foreground">Nothing matches.</p>
  return (
    <div className="space-y-6">
      {packages.map((p) => (
        <div key={`${p.module}/${p.pkg}`}>
          <div className="mb-2 flex flex-wrap items-baseline gap-x-3">
            <h3 className="text-sm font-semibold">{p.module} / <code>{p.pkg}</code></h3>
            <span className="text-xs text-muted-foreground">{p.text}</span>
          </div>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {p.classes.map(([name, kind, does, methods]) => (
              <div key={name} className="rounded-lg border bg-muted/20 p-3 text-sm transition-colors hover:border-primary/40">
                <div className="flex items-baseline gap-2">
                  <span className="font-mono font-semibold"><Mark text={name} query={q} /></span>
                  <span className="rounded bg-primary/10 px-1.5 text-[10px] text-primary">{kind}</span>
                </div>
                <div className="mt-1 text-xs text-muted-foreground"><Mark text={does} query={q} /></div>
                {methods.length > 0 && (
                  <ul className="mt-2 space-y-1 text-xs">
                    {methods.map(([sig, text]) => (
                      <li key={sig}><code><Mark text={sig} query={q} /></code>{text && <span className="text-muted-foreground"> — <Mark text={text} query={q} /></span>}</li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

export function LearnView({ query, setQuery, searchRef }: {
  query: string
  setQuery: (q: string) => void
  searchRef: React.Ref<HTMLInputElement>
}) {
  return (
    <div className="space-y-4">
      <Panel id="tour-journey" title="The life of one order, step by step">
        <ol className="relative space-y-4 border-l pl-6">
          {JOURNEY.map(([title, text], i) => (
            <li key={title} className="relative">
              <span className="absolute top-0 -left-[37px] flex size-6 items-center justify-center rounded-full border bg-card text-xs font-semibold text-primary">{i + 1}</span>
              <div className="text-sm font-semibold">{title}</div>
              <Html as="div" html={text} className="text-sm leading-relaxed text-muted-foreground" />
            </li>
          ))}
        </ol>
      </Panel>
      <div className="grid items-start gap-4 lg:grid-cols-[1.4fr_1fr]">
        <Panel title="What can go wrong, and what handles it" contentClassName="p-0">
          <Table>
            <TableHeader><TableRow><TableHead className="pl-4">Failure</TableHead><TableHead>Mechanism</TableHead><TableHead className="pr-4">Try it</TableHead></TableRow></TableHeader>
            <TableBody>
              {FAILURES.map(([failure, mechanism, tryIt]) => (
                <TableRow key={failure}>
                  <TableCell className="pl-4 align-top font-medium whitespace-normal"><Html html={failure} /></TableCell>
                  <TableCell className="align-top text-xs whitespace-normal text-muted-foreground"><Html html={mechanism} /></TableCell>
                  <TableCell className="pr-4 align-top text-xs whitespace-normal text-muted-foreground"><Html html={tryIt} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Panel>
        <Panel title="Guarantees">
          <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Guaranteed</h4>
          <ul className="space-y-2">
            {GUARANTEES.yes.map((g) => (
              <li key={g} className="flex gap-2 text-sm"><Check className="mt-0.5 size-4 shrink-0 text-s-completed" /><Html html={g} className="text-muted-foreground" /></li>
            ))}
          </ul>
          <h4 className="pt-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Not guaranteed (and why that is honest)</h4>
          <ul className="space-y-2">
            {GUARANTEES.no.map((g) => (
              <li key={g} className="flex gap-2 text-sm"><X className="mt-0.5 size-4 shrink-0 text-s-retry" /><Html html={g} className="text-muted-foreground" /></li>
            ))}
          </ul>
        </Panel>
      </div>
      <Panel title="Code map: every class" hint="what each class is for and what its methods do · press / to search"
        actions={<CodeSearch ref={searchRef} value={query} onChange={setQuery} />}>
        <CodeMap query={query} />
      </Panel>
      <Panel title="Glossary">
        <div className="grid gap-x-6 gap-y-3 md:grid-cols-2">
          {GLOSSARY.map(([term, definition]) => (
            <div key={term} className="text-sm">
              <div className="font-semibold">{term}</div>
              <Html html={definition} className="text-muted-foreground" />
            </div>
          ))}
        </div>
      </Panel>
    </div>
  )
}
