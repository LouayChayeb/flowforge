import { Lightbulb } from 'lucide-react'
import type { CSSProperties, ReactNode } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { humanize } from '@/lib/format'
import { colorOf, LIVE_STATUSES } from '@/lib/status'
import { useStore } from '@/lib/store'
import type { AnyStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

/** A status pill in the status's own colour; live states get a pulsing dot. */
export function StatusBadge({ status, size = 'sm', className }: { status: string; size?: 'sm' | 'lg'; className?: string }) {
  const live = LIVE_STATUSES.has(status as AnyStatus)
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1.5 rounded-full border font-medium whitespace-nowrap',
        size === 'lg' ? 'px-3 py-1 text-sm' : 'px-2 py-0.5 text-[11px]',
        className,
      )}
      style={{
        '--c': colorOf(status),
        color: 'var(--c)',
        background: 'color-mix(in srgb, var(--c) 12%, transparent)',
        borderColor: 'color-mix(in srgb, var(--c) 30%, transparent)',
      } as CSSProperties}
      title={status}
    >
      <span className={cn('size-1.5 rounded-full bg-current', live && 'live-dot')} />
      {humanize(status)}
    </span>
  )
}

/** A small coloured dot, e.g. in legends and table rows. */
export function Dot({ status, className }: { status?: string | null; className?: string }) {
  return <span className={cn('inline-block size-2 shrink-0 rounded-full', className)} style={{ background: colorOf(status) }} />
}

/** Renders one of the explanation snippets from knowledge.js, which are trusted, static HTML. */
export function Html({ html, className, as: Tag = 'span' }: { html: string; className?: string; as?: 'span' | 'div' | 'p' | 'li' }) {
  return <Tag className={cn('prose-ff', className)} dangerouslySetInnerHTML={{ __html: html }} />
}

/** An explanation box, shown only while guide mode is on. */
export function Explain({ children, className }: { children: ReactNode; className?: string }) {
  const { guide } = useStore()
  if (!guide) return null
  return (
    <div className={cn(
      'prose-ff flex gap-2.5 rounded-lg border border-primary/20 bg-primary/5 px-3 py-2.5 text-[13px] leading-relaxed text-muted-foreground',
      className,
    )}>
      <Lightbulb className="mt-0.5 size-4 shrink-0 text-primary" />
      <div>{children}</div>
    </div>
  )
}

/** A card with a title row: the standard container of every view. */
export function Panel({ id, title, hint, actions, children, className, contentClassName }: {
  id?: string
  title: ReactNode
  hint?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
  contentClassName?: string
}) {
  return (
    <Card id={id} className={cn('gap-0 overflow-hidden py-0', className)}>
      <CardHeader className="flex flex-row flex-wrap items-center gap-x-3 gap-y-1 border-b px-4 py-3 [.border-b]:pb-3">
        <CardTitle className="text-sm font-semibold">{title}</CardTitle>
        {hint && <span className="text-xs text-muted-foreground">{hint}</span>}
        {actions && <div className="ml-auto flex items-center gap-2">{actions}</div>}
      </CardHeader>
      <CardContent className={cn('space-y-3 p-4', contentClassName)}>{children}</CardContent>
    </Card>
  )
}

/** A definition list of label / value rows. */
export function KeyValues({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-[minmax(110px,auto)_1fr] gap-x-4 gap-y-2 text-sm">
      {rows.map(([k, v], i) => (
        <div key={i} className="contents">
          <dt className="text-muted-foreground">{k}</dt>
          <dd className="min-w-0 break-words">{v}</dd>
        </div>
      ))}
    </dl>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="px-4 py-8 text-center text-sm text-muted-foreground">{children}</div>
}

/** A horizontal bar with a value label, for counts and countdowns. */
export function Bar({ fraction, color, className }: { fraction: number; color: string; className?: string }) {
  return (
    <div className={cn('h-1.5 overflow-hidden rounded-full bg-muted', className)}>
      <div
        className="h-full rounded-full transition-[width] duration-300 ease-out"
        style={{ width: `${Math.max(0, Math.min(1, fraction)) * 100}%`, background: color }}
      />
    </div>
  )
}
