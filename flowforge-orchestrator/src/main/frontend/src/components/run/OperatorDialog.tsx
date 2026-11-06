import { KeyRound, LogIn } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { errorMessage } from '@/lib/actions'
import { post } from '@/lib/api'
import { isAuthError, isOperator, useAuth } from '@/lib/auth'

export type OperatorAction = { kind: 'cancel' } | { kind: 'retry' } | { kind: 'signal'; step: string }

function text(action: OperatorAction) {
  switch (action.kind) {
    case 'cancel':
      return {
        title: 'Cancel this run?',
        description: 'Nothing new will start. Steps already running finish, then every completed step is undone (the charge refunded, the stock released) and the run ends CANCELLED.',
        confirm: 'Cancel run',
        done: 'Cancel accepted: nothing new starts, completed steps are undone.',
      }
    case 'retry':
      return {
        title: 'Retry the failed compensation?',
        description: 'The undo step that failed is reopened with 3 more attempts; its earlier attempts stay in the history. Do this once the cause (say, the payment provider) is fixed.',
        confirm: 'Retry compensation',
        done: 'Compensation reopened with 3 more attempts.',
      }
    case 'signal':
      return {
        title: `Send the ${action.step} signal?`,
        description: 'The waiting step completes with this payload as its output, and the steps after it run. A webhook would make the same call: POST /api/runs/{id}/signals/{step}.',
        confirm: 'Send signal',
        done: `Signal ${action.step} delivered: the run goes on.`,
      }
  }
}

const path = (runId: string, action: OperatorAction) =>
  action.kind === 'signal' ? `/api/runs/${runId}/signals/${encodeURIComponent(action.step)}` : `/api/runs/${runId}/${action.kind}`

/**
 * Confirms an operator action. Cancelling, retrying and signalling need the OPERATOR role, so the dialog signs the
 * operator in first; the audit trail then records who did it from the login, not from a typed name.
 */
export function OperatorDialog({ runId, action, onClose, onDone }: {
  runId: string
  action: OperatorAction | null
  onClose: () => void
  onDone: () => void
}) {
  const { session, signIn, signOut } = useAuth()
  const [name, setName] = useState('operator')
  const [password, setPassword] = useState('')
  const [reason, setReason] = useState('')
  const [payload, setPayload] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const signedIn = isOperator(session)
  // The last action shown: while the dialog fades out (action already null) it keeps its own words.
  const [shown, setShown] = useState<OperatorAction>({ kind: 'cancel' })
  if (action && action !== shown) setShown(action)

  useEffect(() => {
    if (!action) return
    setReason('')
    setProblem(null)
    if (action.kind === 'signal') {
      setPayload(JSON.stringify({ approved: true, approvedBy: session?.name ?? name }, null, 2))
    }
    // Reset only when a new action opens, not while the operator types.
  }, [action])

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!action) return
    let body: Record<string, unknown> = { reason: reason.trim() || null }
    if (action.kind === 'signal') {
      try {
        body = { ...body, payload: payload.trim() ? JSON.parse(payload) : {} }
      } catch (err) {
        setProblem(`The payload is not valid JSON: ${(err as Error).message}`)
        return
      }
    }
    setSending(true)
    setProblem(null)
    try {
      const current = signedIn ? session! : await signIn(name.trim(), password)
      await post(path(runId, action), body, { Authorization: current.authorization })
      toast.success(text(action).done, { description: `Recorded in the audit trail as ${current.name}.` })
      setPassword('')
      onClose()
      onDone()
    } catch (err) {
      if (isAuthError(err)) {
        signOut()
        setProblem('Those credentials were refused. Operator actions need an account with the OPERATOR role.')
      } else {
        setProblem(errorMessage(err))
      }
    } finally {
      setSending(false)
    }
  }

  const t = text(shown)
  return (
    <Dialog open={action !== null} onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{t.title}</DialogTitle>
            <DialogDescription>{t.description}</DialogDescription>
          </DialogHeader>

          {signedIn ? (
            <div className="flex items-center gap-2 rounded-md bg-muted/50 px-3 py-2 text-sm">
              <KeyRound className="size-4 text-s-completed" /> Signed in as <b>{session!.name}</b>
              <button type="button" onClick={signOut} className="ml-auto text-xs text-muted-foreground hover:text-foreground">sign out</button>
            </div>
          ) : (
            <div className="space-y-3 rounded-lg border border-primary/25 bg-primary/5 p-3">
              <div className="flex items-center gap-2 text-sm font-medium"><LogIn className="size-4 text-primary" /> Operator sign-in</div>
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="op-name">User</Label>
                  <Input id="op-name" value={name} onChange={(e) => setName(e.target.value)} autoComplete="username" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="op-password">Password</Label>
                  <Input id="op-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                    autoComplete="current-password" autoFocus />
                </div>
              </div>
              <p className="text-xs text-muted-foreground">Local demo account: <code>operator</code> / <code>flowforge</code> (set <code>FLOWFORGE_OPERATOR_PASSWORD</code> to change it).</p>
            </div>
          )}

          {shown.kind === 'signal' && (
            <div className="space-y-1.5">
              <Label htmlFor="op-payload">Payload <span className="font-normal text-muted-foreground">(JSON, handed to the next steps)</span></Label>
              <Textarea id="op-payload" value={payload} onChange={(e) => setPayload(e.target.value)} rows={4} className="font-mono text-xs" spellCheck={false} />
            </div>
          )}
          <div className="space-y-1.5">
            <Label htmlFor="op-reason">Reason <span className="font-normal text-muted-foreground">(kept in the audit trail)</span></Label>
            <Textarea id="op-reason" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} rows={2} autoFocus={signedIn} />
          </div>
          {problem && <p className="rounded-md border border-s-failed/40 bg-s-failed/10 px-3 py-2 text-sm text-s-failed">{problem}</p>}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>Never mind</Button>
            <Button type="submit" disabled={sending || (!signedIn && !password)} variant={shown.kind === 'cancel' ? 'destructive' : 'default'}>
              {t.confirm}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
