import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { EXECUTION_SHORTCUTS, type ShortcutScope } from './executionKeys';

/**
 * What the keys do, on screen (§248).
 *
 * **Every row comes from `EXECUTION_SHORTCUTS`.** There is no key literal in this file and there
 * must not be one: a help panel that names its own keys is the second source of truth this
 * feature's whole design exists to avoid, and its failure mode is silent — a documented shortcut
 * that does nothing, or a working one nobody is told about.
 *
 * A dialog rather than a translucent sheet: it takes focus, `Escape` closes it, and it is
 * reachable by the button beside it. A keyboard feature whose help could only be opened by a
 * keyboard shortcut would be circular for exactly the people who most need it.
 */

const SCOPE_HEADINGS = {
  screen: 'Anywhere on this screen',
  notes: 'While the notes box has focus',
} satisfies Record<ShortcutScope, string>;

const SCOPES = Object.keys(SCOPE_HEADINGS) as ShortcutScope[];

export function ShortcutOverlay({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid="Execution.Shortcuts.Overlay">
        <DialogHeader>
          <DialogTitle>Keyboard shortcuts</DialogTitle>
        </DialogHeader>

        <p className="mt-4 mb-4 text-sm text-ink-2">
          Every one of these has a button on the screen too — they are a faster route, never the
          only one. Nothing here fires while you are typing in a box.
        </p>

        {SCOPES.map((scope) => {
          const rows = EXECUTION_SHORTCUTS.filter((shortcut) => shortcut.scope === scope);
          if (rows.length === 0) return null;
          return (
            <div key={scope} className="mb-4">
              <span className="text-xs tracking-[0.05em] text-ink-2 uppercase">
                {SCOPE_HEADINGS[scope]}
              </span>
              <dl className="m-0 mt-2 flex flex-col gap-2">
                {rows.map((shortcut) => (
                  <div
                    key={`${shortcut.scope}-${shortcut.key}`}
                    className="flex items-baseline gap-3"
                    data-testid="Execution.Shortcuts.Row"
                  >
                    <dt className="min-w-10 shrink-0 rounded-[2px] border border-line px-1.5 py-0.5 text-center font-mono text-xs font-semibold text-ink">
                      {shortcut.display}
                    </dt>
                    <dd className="m-0 text-sm text-ink">{shortcut.description}</dd>
                  </div>
                ))}
              </dl>
            </div>
          );
        })}
      </DialogContent>
    </Dialog>
  );
}
