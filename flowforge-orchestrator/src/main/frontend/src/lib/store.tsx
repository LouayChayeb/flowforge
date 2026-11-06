import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api } from './api'
import { ms } from './format'
import type {
  ConsistencyReport, EngineConfig, Invariant, KafkaInfo, Payments, RunStatus, RunSummary, Stats,
} from './types'

export type View = 'live' | 'run' | 'engine' | 'kafka' | 'learn'
export const VIEWS: View[] = ['live', 'run', 'engine', 'kafka', 'learn']

/** Per-second rates derived from two consecutive /api/admin/stats samples. */
export interface Rates {
  claimed: number
  published: number
  applied: number
  stale: number
  expired: number
  failures: number
  runs: number
}

interface Store {
  view: View
  setView: (view: View) => void
  runId: string | null
  openRun: (runId: string, options?: { switchView?: boolean }) => void
  filter: RunStatus | ''
  setFilter: (filter: RunStatus | '') => void
  guide: boolean
  setGuide: (on: boolean) => void
  connected: boolean
  stats: Stats | null
  rates: Partial<Rates>
  /** The last minute of rates, one sample per stats poll, oldest first: for sparklines. */
  rateHistory: Partial<Rates>[]
  runs: RunSummary[]
  config: EngineConfig | null
  kafka: KafkaInfo | null
  consistency: ConsistencyReport | null
  invariants: Invariant[]
  payments: Payments | null
  clockOffset: number
  /** Polls the stats again shortly: after starting a run, so it shows up at once. */
  pollSoon: () => void
}

const StoreContext = createContext<Store | null>(null)

function stored(key: string, fallback: string) {
  try {
    return localStorage.getItem(key) ?? fallback
  } catch {
    return fallback
  }
}

function remember(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // storage unavailable: the preference lasts for this page only
  }
}

/** Runs fn now and every intervalMs; a slow call is never overlapped by the next one. */
function usePoll(fn: () => Promise<void>, intervalMs: number) {
  const latest = useRef(fn)
  latest.current = fn
  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const tick = async () => {
      try {
        await latest.current()
      } finally {
        if (!stopped) timer = setTimeout(tick, intervalMs)
      }
    }
    tick()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [intervalMs])
}

export function StoreProvider({ children }: { children: ReactNode }) {
  const [view, setViewState] = useState<View>(() => (location.hash.slice(1) as View) || 'live')
  const [runId, setRunId] = useState<string | null>(null)
  const [filter, setFilter] = useState<RunStatus | ''>('')
  const [guide, setGuideState] = useState(() => stored('ff-guide', 'on') !== 'off')
  const [connected, setConnected] = useState(false)
  const [stats, setStats] = useState<Stats | null>(null)
  const [rates, setRates] = useState<Partial<Rates>>({})
  const [rateHistory, setRateHistory] = useState<Partial<Rates>[]>([])
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [config, setConfig] = useState<EngineConfig | null>(null)
  const [kafka, setKafka] = useState<KafkaInfo | null>(null)
  const [consistency, setConsistency] = useState<ConsistencyReport | null>(null)
  const [invariants, setInvariants] = useState<Invariant[]>([])
  const [payments, setPayments] = useState<Payments | null>(null)
  const [clockOffset, setClockOffset] = useState(0)
  const previous = useRef<{ stats: Stats; runs: RunSummary[] } | null>(null)

  const setView = useCallback((next: View) => {
    setViewState(next)
    history.replaceState(null, '', `#${next}`)
  }, [])

  const setGuide = useCallback((on: boolean) => {
    setGuideState(on)
    remember('ff-guide', on ? 'on' : 'off')
  }, [])

  const openRun = useCallback((id: string, { switchView = true } = {}) => {
    setRunId(id)
    if (switchView) setView('run')
  }, [setView])

  const pollStats = useCallback(async () => {
    try {
      const [nextStats, nextRuns] = await Promise.all([
        api<Stats>('/api/admin/stats'),
        api<RunSummary[]>(`/api/runs?limit=40${filter ? `&status=${filter}` : ''}`),
      ])
      setClockOffset((ms(nextStats.now) ?? Date.now()) - Date.now())
      const before = previous.current
      if (before) {
        const dt = Math.max(0.2, ((ms(nextStats.now) ?? 0) - (ms(before.stats.now) ?? 0)) / 1000)
        const d = (k: keyof Stats['counters']) => Math.max(0, nextStats.counters[k] - before.stats.counters[k]) / dt
        const newRuns = nextRuns.filter((r) => !before.runs.some((o) => o.runId === r.runId)).length
        const next = {
          claimed: d('stepsClaimed'), published: d('outboxPublished'), applied: d('resultsApplied'),
          stale: d('resultsStale'), expired: d('leasesExpired'), failures: d('outboxFailures'), runs: newRuns / dt,
        }
        setRates(next)
        setRateHistory((h) => [...h.slice(-59), next])
      }
      previous.current = { stats: nextStats, runs: nextRuns }
      setStats(nextStats)
      setRuns(nextRuns)
      setConnected(true)
    } catch {
      setConnected(false)
    }
  }, [filter])

  usePoll(pollStats, 1000)
  usePoll(async () => {
    try {
      setKafka(await api<KafkaInfo>('/api/admin/kafka'))
    } catch {
      setKafka(null)
    }
  }, 2000)
  usePoll(async () => {
    try {
      setPayments(await api<Payments>('/api/admin/payments'))
    } catch {
      setPayments(null)
    }
  }, 3000)
  usePoll(async () => {
    try {
      if (!invariants.length) setInvariants(await api<Invariant[]>('/api/admin/consistency/invariants'))
      setConsistency(await api<ConsistencyReport>('/api/admin/consistency'))
    } catch {
      // keep the last report; it shows when it was checked
    }
  }, 5000)

  useEffect(() => {
    if (connected && !config) api<EngineConfig>('/api/admin/config').then(setConfig).catch(() => {})
  }, [connected, config])

  // A filter change should not wait for the next tick.
  useEffect(() => {
    pollStats()
  }, [pollStats])

  const pollSoon = useCallback(() => {
    setTimeout(pollStats, 150)
    setTimeout(pollStats, 600)
  }, [pollStats])

  const value = useMemo<Store>(() => ({
    view, setView, runId, openRun, filter, setFilter, guide, setGuide, connected, stats, rates, rateHistory, runs, config,
    kafka, consistency, invariants, payments, clockOffset, pollSoon,
  }), [view, setView, runId, openRun, filter, guide, setGuide, connected, stats, rates, rateHistory, runs, config, kafka,
    consistency, invariants, payments, clockOffset, pollSoon])

  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>
}

export function useStore(): Store {
  const store = useContext(StoreContext)
  if (!store) throw new Error('useStore must be used inside <StoreProvider>')
  return store
}

/** The server's clock, re-rendering every intervalMs: for countdowns and "x s ago". */
export function useNow(intervalMs = 250): number {
  const { clockOffset } = useStore()
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(timer)
  }, [intervalMs])
  return now + clockOffset
}
