import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { api, ApiError } from './api'

/**
 * The operator signed in to this page. Operator actions (cancel, retry, signals) need the OPERATOR role; the API is
 * stateless HTTP Basic, so the credentials live in memory for this page only — never in storage — and go with each
 * operator call.
 */
interface Session {
  name: string
  roles: string[]
  authorization: string
}

interface Auth {
  session: Session | null
  /** Checks the credentials against the server; throws ApiError (401) when they are wrong. */
  signIn: (name: string, password: string) => Promise<Session>
  signOut: () => void
}

const AuthContext = createContext<Auth | null>(null)

const basic = (name: string, password: string) =>
  `Basic ${btoa(String.fromCharCode(...new TextEncoder().encode(`${name}:${password}`)))}`

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)

  const signIn = useCallback(async (name: string, password: string) => {
    const authorization = basic(name, password)
    const me = await api<{ name: string; roles: string[] }>('/api/auth/me', { headers: { Authorization: authorization } })
    const next = { name: me.name, roles: me.roles, authorization }
    setSession(next)
    return next
  }, [])

  const signOut = useCallback(() => setSession(null), [])

  const value = useMemo(() => ({ session, signIn, signOut }), [session, signIn, signOut])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): Auth {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('useAuth must be used inside <AuthProvider>')
  return auth
}

export const isOperator = (session: Session | null) => !!session?.roles.includes('OPERATOR')

/** A wrong or expired login: the dialog asks again instead of showing an error. */
export const isAuthError = (e: unknown) => e instanceof ApiError && (e.status === 401 || e.status === 403)
