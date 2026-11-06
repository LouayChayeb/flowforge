import { Keyboard, Moon, Search, Sparkles, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Kbd } from '@/components/ui/kbd'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { TextShimmer } from '@/components/ui/text-shimmer'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useStore, type View } from '@/lib/store'
import { cn } from '@/lib/utils'

const TABS: [View, string][] = [['live', 'Live'], ['run', 'Run detail'], ['engine', 'Engine'], ['kafka', 'Kafka'], ['learn', 'Learn']]

export function Header({ dark, onTheme, onPalette, onTour, onShortcuts }: {
  dark: boolean
  onTheme: () => void
  onPalette: () => void
  onTour: () => void
  onShortcuts: () => void
}) {
  const { view, setView, guide, setGuide, connected, stats, rates } = useStore()
  const busy = (rates.claimed ?? 0) > 0.05

  return (
    <header className="sticky top-0 z-40 border-b bg-background/75 backdrop-blur-xl">
      <div className="mx-auto flex max-w-[1600px] flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5">
        <div className="flex items-center gap-2.5">
          <img src="./favicon.svg" alt="" className="size-8" />
          <div className="leading-tight">
            <div className="font-semibold tracking-tight">FlowForge</div>
            {busy
              ? <TextShimmer as="div" className="text-[11px]" duration={1.6}>durable workflow engine · working</TextShimmer>
              : <div className="text-[11px] text-muted-foreground">durable workflow engine · live</div>}
          </div>
        </div>

        <Tabs value={view} onValueChange={(v) => setView(v as View)} className="order-last w-full sm:order-none sm:w-auto">
          <TabsList className="w-full sm:w-auto">
            {TABS.map(([id, label]) => <TabsTrigger key={id} value={id}>{label}</TabsTrigger>)}
          </TabsList>
        </Tabs>

        <div className="ml-auto flex items-center gap-1.5">
          <button onClick={onPalette}
            className="hidden h-8 items-center gap-2 rounded-lg border bg-muted/40 px-2.5 text-xs text-muted-foreground transition-colors hover:bg-muted md:flex">
            <Search className="size-3.5" /> Search or jump to… <Kbd>Ctrl K</Kbd>
          </button>
          <label className="flex cursor-pointer items-center gap-2 px-1.5 text-xs text-muted-foreground" title="Show the explanation boxes everywhere (g)">
            <Switch checked={guide} onCheckedChange={setGuide} size="sm" /> Guide
          </label>
          <Tooltip>
            <TooltipTrigger render={<Button variant="ghost" size="icon-sm" onClick={onTour} aria-label="Take the tour"><Sparkles /></Button>} />
            <TooltipContent>Take the tour</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger render={<Button variant="ghost" size="icon-sm" onClick={onShortcuts} aria-label="Keyboard shortcuts"><Keyboard /></Button>} />
            <TooltipContent>Keyboard shortcuts (?)</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger render={<Button variant="ghost" size="icon-sm" onClick={onTheme} aria-label="Toggle theme">{dark ? <Sun /> : <Moon />}</Button>} />
            <TooltipContent>Light / dark (t)</TooltipContent>
          </Tooltip>
          <div className="flex items-center gap-1.5 pl-1.5 text-xs text-muted-foreground" title={stats ? `instance ${stats.instance}` : undefined}>
            <span className={cn('size-2 rounded-full', connected ? 'live-dot bg-s-completed text-s-completed' : 'bg-s-failed')} />
            <span className="hidden lg:inline">{connected ? stats?.instance ?? 'connected' : 'orchestrator unreachable'}</span>
          </div>
        </div>
      </div>
    </header>
  )
}
