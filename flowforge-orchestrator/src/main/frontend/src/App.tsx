import { useCallback, useEffect, useRef, useState } from 'react'
import { CommandPalette } from '@/components/shell/CommandPalette'
import { Header } from '@/components/shell/Header'
import { ShortcutsDialog } from '@/components/shell/ShortcutsDialog'
import { Tour } from '@/components/shell/Tour'
import { BlurFade } from '@/components/ui/blur-fade'
import { DotPattern } from '@/components/ui/dot-pattern'
import { Toaster } from '@/components/ui/sonner'
import { TooltipProvider } from '@/components/ui/tooltip'
import { AuthProvider } from '@/lib/auth'
import { StoreProvider, useStore, VIEWS } from '@/lib/store'
import { cn } from '@/lib/utils'
import { EngineView } from '@/views/EngineView'
import { KafkaView } from '@/views/KafkaView'
import { LearnView } from '@/views/LearnView'
import { LiveView } from '@/views/LiveView'
import { RunView } from '@/views/RunView'

function useTheme() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  const toggle = useCallback(() => {
    setDark((d) => {
      const next = !d
      document.documentElement.classList.toggle('dark', next)
      try {
        localStorage.setItem('ff-theme', next ? 'dark' : 'light')
      } catch {
        // storage unavailable
      }
      return next
    })
  }, [])
  return { dark, toggle }
}

function Shell() {
  const { view, setView, guide, setGuide, runs, runId, openRun } = useStore()
  const { dark, toggle } = useTheme()
  const [palette, setPalette] = useState(false)
  const [shortcuts, setShortcuts] = useState(false)
  const [tour, setTour] = useState<number | null>(null)
  const [query, setQuery] = useState('')
  const search = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPalette((p) => !p)
        return
      }
      if (e.key === 'Escape') { setTour(null); return }
      const typing = e.target instanceof Element && e.target.closest('input, textarea, select, [role=dialog], [contenteditable]')
      if (e.ctrlKey || e.metaKey || e.altKey || typing) return
      if (e.key >= '1' && e.key <= '5') setView(VIEWS[Number(e.key) - 1])
      else if (e.key === '?') setShortcuts(true)
      else if (e.key === '/') { e.preventDefault(); setView('learn'); setTimeout(() => search.current?.focus(), 50) }
      else if (e.key === 'g') setGuide(!guide)
      else if (e.key === 't') toggle()
      else if ((e.key === 'j' || e.key === 'k') && runs.length) {
        const i = runs.findIndex((r) => r.runId === runId)
        const next = runs[Math.min(runs.length - 1, Math.max(0, i + (e.key === 'j' ? 1 : -1)))] ?? runs[0]
        openRun(next.runId, { switchView: view === 'run' })
      }
    }
    addEventListener('keydown', onKey)
    return () => removeEventListener('keydown', onKey)
  }, [view, setView, guide, setGuide, toggle, runs, runId, openRun])

  return (
    <div className="relative min-h-svh">
      <DotPattern className={cn('pointer-events-none fixed inset-0 -z-10 text-foreground/[0.07]',
        '[mask-image:radial-gradient(1100px_circle_at_50%_0%,white,transparent_75%)]')} />
      <Header dark={dark} onTheme={toggle} onPalette={() => setPalette(true)} onTour={() => setTour(0)} onShortcuts={() => setShortcuts(true)} />
      <main className="mx-auto max-w-[1600px] px-4 py-4">
        <BlurFade key={view} duration={0.25} offset={4}>
          {view === 'live' && <LiveView />}
          {view === 'run' && <RunView />}
          {view === 'engine' && <EngineView />}
          {view === 'kafka' && <KafkaView />}
          {view === 'learn' && <LearnView query={query} setQuery={setQuery} searchRef={search} />}
        </BlurFade>
      </main>
      <footer className="mx-auto max-w-[1600px] px-4 pb-6 text-xs text-muted-foreground">
        FlowForge dashboard · React, Tailwind, shadcn/ui, Magic UI and Motion Primitives · press <b>?</b> for shortcuts
      </footer>
      <CommandPalette open={palette} onOpenChange={setPalette} onTour={() => setTour(0)} onTheme={toggle} />
      <ShortcutsDialog open={shortcuts} onOpenChange={setShortcuts} />
      <Tour index={tour} onIndex={setTour} />
      <Toaster theme={dark ? 'dark' : 'light'} position="bottom-right" richColors closeButton />
    </div>
  )
}

export default function App() {
  return (
    <StoreProvider>
      <AuthProvider>
        <TooltipProvider delay={200}>
          <Shell />
        </TooltipProvider>
      </AuthProvider>
    </StoreProvider>
  )
}
