import type { EngineConfig } from './types'

/** Milliseconds since the epoch, or null. */
export const ms = (iso: string | null | undefined): number | null => (iso ? new Date(iso).getTime() : null)

/** 950 → "950 ms", 2500 → "2.50 s", 75000 → "1 min 15 s". */
export function fmtDur(millis: number | null | undefined): string {
  if (millis == null || Number.isNaN(millis)) return '—'
  const m = Math.abs(millis)
  if (m < 1000) return `${Math.round(millis)} ms`
  if (m < 60_000) {
    const seconds = millis / 1000
    return Number.isInteger(seconds) ? `${seconds} s` : `${seconds.toFixed(m < 10_000 ? 2 : 1)} s`
  }
  const min = Math.floor(m / 60_000)
  const sec = Math.round((m % 60_000) / 1000)
  return sec ? `${min} min ${sec} s` : `${min} min`
}

/** "PT1M", "PT0.5S", "PT60S" → milliseconds. */
export function parseIsoDuration(text: string | null | undefined): number | null {
  if (!text) return null
  const match = /PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?/.exec(text)
  if (!match) return null
  return ((+match[1] || 0) * 3600 + (+match[2] || 0) * 60 + (+match[3] || 0)) * 1000
}

/** RetryPolicy.backoffAfter(): initial × multiplier^(attempt−1), capped at max. */
export function retryBackoff(config: EngineConfig | null, failedAttempt: number): number | null {
  if (!config) return null
  const init = parseIsoDuration(config.initialBackoff) ?? 0
  const max = parseIsoDuration(config.maxBackoff) ?? Infinity
  return Math.min(init * config.backoffMultiplier ** (failedAttempt - 1), max)
}

/** 14:03:07.412 */
export function fmtClock(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.toLocaleTimeString([], { hour12: false })}.${String(d.getMilliseconds()).padStart(3, '0')}`
}

export function ago(iso: string, now: number): string {
  const diff = now - (ms(iso) ?? now)
  if (diff < 5000) return 'just now'
  if (diff < 60_000) return `${Math.round(diff / 1000)} s ago`
  if (diff < 3_600_000) return `${Math.round(diff / 60_000)} min ago`
  return new Date(iso).toLocaleString()
}

export const sum = (o: Record<string, number> | null | undefined) => Object.values(o ?? {}).reduce((a, b) => a + b, 0)

export const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

export const humanize = (status: string) => status.toLowerCase().replaceAll('_', ' ')
