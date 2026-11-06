import { useCallback } from 'react'
import { toast } from 'sonner'
import { ApiError } from './api'
import { useStore } from './store'
import type { Input } from './types'
import { startRun, WORKFLOW } from './workflows'

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.code ? `${e.message} (${e.code})` : e.message
  return e instanceof Error ? e.message : String(e)
}

/** Starts a run and reports it: a toast with a link to the run, and a quick refresh of the lists. */
export function useStartRun() {
  const { pollSoon, openRun } = useStore()
  return useCallback(async (input: Input, { workflow = WORKFLOW, quiet = false, open = false } = {}) => {
    try {
      const { created, registeredVersion } = await startRun(input, workflow)
      if (registeredVersion != null) {
        toast.info(`Registered ${workflow} version ${registeredVersion}`, {
          description: 'Runs already started keep the version they began with.',
        })
      }
      if (!quiet) {
        toast.success(`202 Accepted — ${String(input.orderId ?? created.runId.slice(0, 8))} stored as RUNNING`, {
          description: 'The API only recorded the run; the engine does the work in the background.',
          action: open ? undefined : { label: 'Open', onClick: () => openRun(created.runId) },
        })
      }
      if (open) openRun(created.runId)
      pollSoon()
      return created
    } catch (e) {
      toast.error('Could not start the run', { description: errorMessage(e) })
      return null
    }
  }, [openRun, pollSoon])
}
