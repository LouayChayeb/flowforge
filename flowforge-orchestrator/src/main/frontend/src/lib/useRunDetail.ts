import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError } from './api'
import { isActiveRun } from './status'
import type { Command, Payments, Run } from './types'

export interface RunDetail {
  run: Run | null
  commands: Command[]
  payments: Payments | null
  missing: boolean
  refresh: () => Promise<void>
}

/** Loads one run with its outbox commands and payment record; polls fast while it is still moving. */
export function useRunDetail(runId: string | null): RunDetail {
  const [run, setRun] = useState<Run | null>(null)
  const [commands, setCommands] = useState<Command[]>([])
  const [payments, setPayments] = useState<Payments | null>(null)
  const [missing, setMissing] = useState(false)
  const current = useRef(runId)
  current.current = runId

  const refresh = useCallback(async () => {
    const id = current.current
    if (!id) return
    try {
      const [nextRun, nextCommands] = await Promise.all([
        api<Run>(`/api/runs/${id}`),
        api<Command[]>(`/api/runs/${id}/commands`),
      ])
      if (current.current !== id) return
      setRun(nextRun)
      setCommands(nextCommands)
      setMissing(false)
      const orderId = nextRun.input?.orderId
      if (orderId) {
        api<Payments>(`/api/admin/payments?orderId=${encodeURIComponent(String(orderId))}`)
          .then((p) => current.current === id && setPayments(p))
          .catch(() => setPayments(null))
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) setMissing(true)
    }
  }, [])

  useEffect(() => {
    setRun(null)
    setCommands([])
    setPayments(null)
    setMissing(false)
    if (!runId) return
    refresh()
  }, [runId, refresh])

  const active = run ? isActiveRun(run.status) : true
  useEffect(() => {
    if (!runId) return
    const timer = setInterval(refresh, active ? 1000 : 4000)
    return () => clearInterval(timer)
  }, [runId, active, refresh])

  return { run, commands, payments, missing, refresh }
}
