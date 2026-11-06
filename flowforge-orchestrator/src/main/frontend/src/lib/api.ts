import type { Problem } from './types'

/** A failed API call, carrying the server's problem details (code, detail, traceId…). */
export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem | null

  constructor(status: number, problem: Problem | null) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }

  get code(): string | undefined {
    return this.problem?.code
  }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...options.headers },
  })
  if (!response.ok) {
    let problem: Problem | null = null
    try {
      problem = (await response.json()) as Problem
    } catch {
      // not JSON: a proxy error page, say
    }
    throw new ApiError(response.status, problem)
  }
  return (response.status === 204 ? null : await response.json()) as T
}

export const post = <T>(path: string, body: unknown, headers: Record<string, string> = {}) =>
  api<T>(path, { method: 'POST', body: JSON.stringify(body), headers })
