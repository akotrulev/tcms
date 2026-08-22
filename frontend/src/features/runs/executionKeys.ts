import type { StatusKind } from '../../api/types';

/**
 * The execution screen's keyboard bindings — **the one map, read by both the handler and the
 * overlay** (§248).
 *
 * <h3>Why one module rather than a handler and a help panel</h3>
 *
 * A shortcut has two halves that must agree: the thing that happens, and the thing the product
 * says happens. Written twice they drift, and the drift is silent in exactly one direction — a
 * documented shortcut that does nothing. So `ShortcutOverlay.tsx` renders *this array* and the
 * handler in `ExecutionPage.tsx` dispatches on *this array*; adding a binding updates the help for
 * free, and removing one cannot leave a row behind.
 *
 * <h3>The guard that is the whole feature</h3>
 *
 * {@link isTypingTarget} is not hygiene. A single-key verdict on a screen with a notes box, a
 * per-step comment box and a duration box means that typing the word "problem" into the notes
 * marks the case Passed, then Blocked. Every naive implementation of this feature ships that, and
 * it is why {@link matchShortcut} refuses before it matches rather than after.
 *
 * <h3>Two scopes, and why `Escape` is one of them</h3>
 *
 * `n` puts the caret in the notes box, where — correctly — every screen shortcut is dead. Without
 * a documented way back out, `n` is a trap, so `Escape` blurs the field. It is handled by the
 * field rather than by the screen listener, which is what {@link matchNotesShortcut} exists to
 * express: it deliberately does **not** apply the focus guard, because firing while a text field
 * has focus is its entire job.
 */

/** What a binding does. A tagged union rather than a callback, so the map stays data. */
export type ExecutionAction =
  | { kind: 'verdict'; verdict: Extract<StatusKind, 'PASSED' | 'FAILED' | 'BLOCKED' | 'SKIPPED'> }
  | { kind: 'move'; direction: -1 | 1 }
  | { kind: 'focusNotes' }
  | { kind: 'toggleHelp' }
  | { kind: 'leaveNotes' };

/**
 * Where a binding is listened for.
 *
 * `screen` bindings are refused while a text field has focus; `notes` bindings exist only there.
 * The two never share a listener, so a key may legitimately appear in both scopes.
 */
export type ShortcutScope = 'screen' | 'notes';

export interface ExecutionShortcut {
  /** The `KeyboardEvent.key` this binds, compared case-insensitively. */
  key: string;
  /** What the overlay prints in its key box. Separate from {@link key} so `Escape` can read `Esc`. */
  display: string;
  /** What the overlay says it does — a sentence for a person, not a symbol. */
  description: string;
  scope: ShortcutScope;
  action: ExecutionAction;
}

/**
 * The bindings.
 *
 * The four verdict letters are the initials the step rows already render (`P F B S`), so the
 * keyboard and the screen use one vocabulary. `j`/`k` carry next/previous because every letter
 * that reads as "next" or "previous" is already a verdict (`p`) or the notes key (`n`) — the
 * overlay is what makes that discoverable, which is why it is part of this feature and not a
 * follow-up.
 *
 * **`b` opens the precondition dialog and does not write.** That rule is defended in
 * `executionMode.ts`, `ExecutionGrid.tsx` and `ExecutionPage.tsx`'s own header: a tester who could
 * not get the environment up is not looking at a failing feature, and recording that as Failed
 * poisons the pass rate. A keyboard that wrote Blocked in one keystroke would be the first
 * exception to a rule three files hold.
 */
export const EXECUTION_SHORTCUTS: readonly ExecutionShortcut[] = [
  { key: 'p', display: 'P', description: 'Record Passed', scope: 'screen', action: { kind: 'verdict', verdict: 'PASSED' } },
  { key: 'f', display: 'F', description: 'Record Failed', scope: 'screen', action: { kind: 'verdict', verdict: 'FAILED' } },
  {
    key: 'b',
    display: 'B',
    description: 'Blocked — opens the precondition checklist, which is what Blocked always asks for',
    scope: 'screen',
    action: { kind: 'verdict', verdict: 'BLOCKED' },
  },
  { key: 's', display: 'S', description: 'Record Skipped', scope: 'screen', action: { kind: 'verdict', verdict: 'SKIPPED' } },
  { key: 'j', display: 'J', description: 'Next case in this run', scope: 'screen', action: { kind: 'move', direction: 1 } },
  { key: 'k', display: 'K', description: 'Previous case in this run', scope: 'screen', action: { kind: 'move', direction: -1 } },
  { key: 'n', display: 'N', description: 'Jump to the notes box', scope: 'screen', action: { kind: 'focusNotes' } },
  { key: '?', display: '?', description: 'Show this list', scope: 'screen', action: { kind: 'toggleHelp' } },
  {
    key: 'Escape',
    display: 'Esc',
    description: 'Leave the notes box, so the keys above work again',
    scope: 'notes',
    action: { kind: 'leaveNotes' },
  },
];

/** The shape both matchers read — a `KeyboardEvent`, narrowed so a test need not synthesise one. */
export interface ShortcutEvent {
  key: string;
  target: EventTarget | null;
  ctrlKey: boolean;
  metaKey: boolean;
  altKey: boolean;
}

/**
 * Whether the event landed somewhere a person is typing.
 *
 * `select` counts: its type-ahead consumes letters, and stealing them would make a status picker
 * unusable by keyboard. A **disabled** or **read-only** field does not count — nothing is being
 * typed into it, and treating it as a typing surface would silently kill the shortcuts on a frozen
 * run, which is a different refusal with a different sentence.
 *
 * `closest('[contenteditable]')` rather than an attribute read on the target itself: the caret in
 * a rich-text host sits in whatever inline node it is in, so the event's target is routinely a
 * `span` three levels down.
 */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
    return !target.disabled && !target.readOnly;
  }
  if (target instanceof HTMLSelectElement) return !target.disabled;
  return target.closest('[contenteditable]:not([contenteditable="false"])') !== null;
}

/**
 * The **screen** binding this keypress means, or `null`.
 *
 * Refuses in three cases, and the order matters only for readability — a modifier chord belongs to
 * the browser or the OS (⌘P is print, ⌘F is find), a keypress inside a text field belongs to the
 * text, and an unbound key belongs to nobody. **`shiftKey` is not among them**: `?` is a shifted
 * `/` on most layouts, so treating shift as a modifier would remove the help key.
 */
export function matchShortcut(event: ShortcutEvent): ExecutionShortcut | null {
  if (event.ctrlKey || event.metaKey || event.altKey) return null;
  if (isTypingTarget(event.target)) return null;
  return find('screen', event.key);
}

/**
 * The **notes** binding this keypress means, or `null` — **no focus guard**, deliberately.
 *
 * This scope exists to be reachable from inside the text field, which is the one place
 * {@link matchShortcut} will never answer.
 */
export function matchNotesShortcut(event: ShortcutEvent): ExecutionShortcut | null {
  if (event.ctrlKey || event.metaKey || event.altKey) return null;
  return find('notes', event.key);
}

function find(scope: ShortcutScope, key: string): ExecutionShortcut | null {
  const lowered = key.toLowerCase();
  return EXECUTION_SHORTCUTS.find((s) => s.scope === scope && s.key.toLowerCase() === lowered) ?? null;
}
