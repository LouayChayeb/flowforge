import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Kbd } from '@/components/ui/kbd'

const SHORTCUTS: [React.ReactNode, string][] = [
  [<><Kbd>Ctrl</Kbd> <Kbd>K</Kbd></>, 'Command palette: views, runs, scenarios'],
  [<><Kbd>1</Kbd> – <Kbd>5</Kbd></>, 'Live · Run detail · Engine · Kafka · Learn'],
  [<><Kbd>j</Kbd> / <Kbd>k</Kbd></>, 'Next / previous run in the list'],
  [<Kbd>/</Kbd>, 'Search the code map'],
  [<Kbd>g</Kbd>, 'Toggle guide mode'],
  [<Kbd>t</Kbd>, 'Toggle light / dark'],
  [<Kbd>?</Kbd>, 'This list'],
]

export function ShortcutsDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>Keyboard shortcuts</DialogTitle></DialogHeader>
        <dl className="grid grid-cols-[auto_1fr] items-center gap-x-6 gap-y-2.5 text-sm">
          {SHORTCUTS.map(([keys, text], i) => (
            <div key={i} className="contents">
              <dt className="flex items-center gap-1 whitespace-nowrap">{keys}</dt>
              <dd className="text-muted-foreground">{text}</dd>
            </div>
          ))}
        </dl>
      </DialogContent>
    </Dialog>
  )
}
