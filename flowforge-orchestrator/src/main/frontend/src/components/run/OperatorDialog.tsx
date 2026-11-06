import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { post } from '@/lib/api'
import { errorMessage } from '@/lib/actions'

export type OperatorKind = 'cancel' | 'retry'

const TEXT: Record<OperatorKind, { title: string; description: string; confirm: string; done: string }> = {
  cancel: {
    title: 'Cancel this run?',
    description: 'Nothing new will start. Steps already running finish, then every completed step is undone (the charge refunded, the stock released) and the run ends CANCELLED.',
    confirm: 'Cancel run',
    done: 'Cancel accepted: nothing new starts, completed steps are undone.',
  },
  retry: {
    title: 'Retry the failed compensation?',
    description: 'The undo step that failed is reopened with 3 more attempts; its earlier attempts stay in the history. Do this once the cause (say, the payment provider) is fixed.',
    confirm: 'Retry compensation',
    done: 'Compensation reopened with 3 more attempts.',
  },
}

function storedActor() {
  try {
    return localStorage.getItem('ff-actor') ?? ''
  } catch {
    return ''
  }
}

/** Confirms an operator action, asking who does it and why: both are kept in the run's audit trail. */
export function OperatorDialog({ runId, kind, onClose, onDone }: {
  runId: string
  kind: OperatorKind | null
  onClose: () => void
  onDone: () => void
}) {
  const [actor, setActor] = useState(storedActor)
  const [reason, setReason] = useState('')
  const [sending, setSending] = useState(false)

  useEffect(() => {
    if (kind) setReason('')
  }, [kind])

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!kind) return
    const who = actor.trim() || 'anonymous'
    try {
      localStorage.setItem('ff-actor', who)
    } catch {
      // storage unavailable
    }
    setSending(true)
    try {
      await post(`/api/runs/${runId}/${kind}`, { reason: reason.trim() || null }, { 'X-Actor': who })
      toast.success(TEXT[kind].done)
      onClose()
      onDone()
    } catch (err) {
      toast.error('Refused', { description: errorMessage(err) })
    } finally {
      setSending(false)
    }
  }

  const text = kind ? TEXT[kind] : TEXT.cancel
  return (
    <Dialog open={kind !== null} onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent>
        <form onSubmit={submit} className="space-y-4">
          <DialogHeader>
            <DialogTitle>{text.title}</DialogTitle>
            <DialogDescription>{text.description}</DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5">
            <Label htmlFor="op-actor">Your name</Label>
            <Input id="op-actor" value={actor} onChange={(e) => setActor(e.target.value)} maxLength={100} placeholder="anonymous" />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="op-reason">Reason <span className="font-normal text-muted-foreground">(kept in the audit trail)</span></Label>
            <Textarea id="op-reason" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} rows={3} autoFocus />
          </div>
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>Never mind</Button>
            <Button type="submit" disabled={sending} variant={kind === 'cancel' ? 'destructive' : 'default'}>{text.confirm}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
