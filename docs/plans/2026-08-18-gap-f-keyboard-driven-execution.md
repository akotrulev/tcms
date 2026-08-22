# Keyboard-Driven Execution Implementation Plan

**Date:** 2026-08-18 · **Written by:** `ep-writing-plans` · **Area F of `docs/plans/2026-08-18-unplanned-gaps.md`** (master tasks 41–46) · **Handoff section `§248`**

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a tester record a run's results without a pointer — single-key verdicts, next/previous case, and a key to the notes box — on the screen where the product's highest-volume repetitive act happens.

**Architecture:** One key-map module (`executionKeys.ts`) that both the handler and the overlay read, so a documented shortcut cannot be one that does nothing. The handler is a `useEffect` inside `ExecutionForm` — the component that already holds the mutation, the run, the notes buffer and the freeze — so the bindings are added on mount and removed on unmount and are the *screen's*, never the app's. Every shortcut gets a visible focusable equivalent and every act it performs is announced through the app's single polite live region. **No contract change, no backend file, no migration, no new dependency.**

**Tech Stack:** React 19 + TypeScript strict · MUI v7 · TanStack Query · Vitest + RTL · `a11y/AnnouncerProvider`'s live region · `auth/useCan`'s `Gate`.

---

## Scope Check — one screen, and what is deliberately left alone

This plan covers **one subsystem**: the execution *detail* screen (Focus and Split modes) and the two new modules beside it. It does not touch the contract, the backend, `router.tsx`, or any screen outside `frontend/src/features/runs/`.

### The test-coverage decision, stated explicitly

Master task 42 says *"extend `ExecutionPage.test.tsx`"*. **That file does not exist.** Neither does `ExecutionQueue.test.tsx`, nor `executionSelection.test.ts`. §244 `ED-M8` already records that **nothing in `frontend/src` drives `useSubmitResult` at all**. The decision, and its reason:

| File | This plan | Why |
|---|---|---|
| `ExecutionPage.test.tsx` | **CREATED** (T3, extended by T5–T8) | Area F is *entirely* behaviour on this screen, and its headline defect — a shortcut firing while a text field has focus — is only observable with the real form mounted. A pure test of `matchShortcut` cannot see the notes box. Creating it is the cheapest honest way to cover this area, and it incidentally becomes the **first automated driver of `useSubmitResult`** in the SPA. |
| `ExecutionQueue.test.tsx` | **NOT created** | Area F does not change the rail. It reads `orderedQueue`, which is `queueOrder.ts` and already has `queueOrder.test.ts`. Writing a rail test here would be covering §100's work under §248's number. |
| `executionSelection.test.ts` | **NOT created** | That module is §245's, it lives on `RunDetailPage`, and area F touches neither. Absorbing it would be taking area B's debt under area F's number — the thing this plan is told not to do quietly. |

**The new file is scoped to the keyboard feature.** It does not become a general test of the execution screen: durations, preconditions, step marks, evidence, defects, the 409 conflict panel and the suggested verdict stay uncovered by automation, exactly as §244 left them (`ED-M9`, and `§244 ED-1..ED-6` in the browser queue). A test file that tried to cover all of that would be a different slice.

### What this plan is built on, and how to read a surprise

Areas **A** (§244, execution duration) and **B** (§245, bulk assignment) shipped **completely ungated** — no build, no lint, no test run. This plan binds A's control (`durationIsNonsense` gates the verdict, so it must gate the keyboard too) and sits beside B's work. **If something on these screens looks wrong, it is a finding to report, not evidence that this area's edit caused it.**

### Gates

**This area runs no gates.** Under the batch directive, `npm run test:run`, `npm run build` and `npm run lint` are **not run** — locally or on `shanks`. Every test this plan writes is written out in full and left unexecuted. T10 is the gate task and it is **deferred, not passed**. Nothing here may be described as done, green, verified or passing: the correct description is **implemented and completely ungated**.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `frontend/src/features/runs/executionKeys.ts` | **create** | The one key map. The bindings, what each does, and the two pure matchers — including the focus guard. No React, no DOM ownership, no MUI. |
| `frontend/src/features/runs/executionKeys.test.ts` | **create** | The pure half: uniqueness of the map, case-insensitivity, the modifier guard, and the focus guard against every typing target. |
| `frontend/src/features/runs/ShortcutOverlay.tsx` | **create** | Renders the map. Holds no bindings of its own — adding one to the map updates the overlay for free. |
| `frontend/src/features/runs/ExecutionPage.tsx` | **modify** | Binds the handler at the screen, adds the visible equivalents (Previous/Next, Keyboard shortcuts), announces every act, and passes `modeParam` into the form. |
| `frontend/src/features/runs/ExecutionPage.test.tsx` | **create** | The integration half, including the arm this feature ships broken everywhere. |
| `docs/verification/frontend-browser-checks.md` | **modify** | The `§248 KB-*` rows, lane `A3`. |

### The bindings

| Key | Does | Scope |
|---|---|---|
| `p` | Record **Passed** | screen |
| `f` | Record **Failed** | screen |
| `b` | Open the **Blocked** checklist dialog — never a direct write | screen |
| `s` | Record **Skipped** | screen |
| `j` | Next case in the run | screen |
| `k` | Previous case in the run | screen |
| `n` | Focus the **Notes** box | screen |
| `?` | Open the shortcuts overlay | screen |
| `Escape` | Leave the Notes box (blur it) | **notes** |

**Why `j`/`k` and not `n`/`p`:** `p` is Passed and `n` is notes. Every letter that reads as "next" or "previous" is either a verdict or the notes key, so the pair comes from the one convention with no collision left. The overlay is what makes them discoverable, which is why it is part of the feature rather than a follow-up.

**Why `b` opens a dialog:** `executionMode.ts` and `ExecutionGrid.tsx` both carry the rule in as many words — a tester who could not get the environment up is not looking at a failing feature, and recording that as Failed poisons the pass rate and sends somebody chasing a defect that does not exist. **Blocked routes through the precondition dialog in every mode**, and a keyboard that wrote Blocked in one keystroke would be the first exception to a rule three files defend.

**Why `Escape` is in the map but scoped:** once `n` puts the caret in the notes box, every screen shortcut is correctly dead. Without a documented way back out, `n` is a trap. `Escape` blurs the field, it is handled by the field itself rather than by the global listener, and it is in the same map so the overlay lists it.

### The two pure matchers

- `matchShortcut(event)` — **screen** scope only. Answers `null` when the event's target is a typing surface, when a modifier is held, or when no binding matches.
- `matchNotesShortcut(event)` — **notes** scope only, and deliberately does **not** apply the focus guard: it exists precisely to fire while a text field has focus.

### The accessibility equivalents

Every shortcut is an accelerator, not the only route:

| Key | Visible, focusable equivalent | New? |
|---|---|---|
| `p` `f` `b` `s` | The four verdict buttons in **Record the result** | existing |
| `j` `k` | **Previous case** / **Next case** buttons | **new** (T5) |
| `n` | The Notes field itself, reachable by Tab | existing |
| `?` | The **Keyboard shortcuts** button | **new** (T8) |
| `Escape` | Shift-Tab out of the Notes field | existing |

Every act — a verdict, a move, a refusal — is announced through `useAnnounce()`, the app's single polite live region (`a11y/AnnouncerProvider`). Without it a keystroke that worked and a keystroke that did nothing are the same experience for a screen-reader user.

### The one hazard this feature introduces, and how it is answered

`j` is a single keystroke and this screen holds **unsaved work in four `useState` buffers**. `ExecutionPage.tsx`'s own comment says what losing it costs: *"the marks on this screen are the only record of what happened — losing them means running the test again, not retyping a title."*

So **a move is refused while there is unsaved work**, using the dirty flag `useUnsavedWork` is already computed from. The buttons render disabled with the reason in a tooltip (§31, disable-don't-hide) and the keyboard announces the same sentence. A confirm dialog was rejected: this repo has no shared confirm component, and adding one to area F would be new machinery in the smallest area of the plan. `§248 KB-9` asks the live pass whether the refusal is too strict — that is a judgement this plan cannot settle from a file.

---

**Tasks** — in the order they are done:

| # | Task | Deliverable | Depends on |
|---|---|---|---|
| 1 | T1 — The key map and its matchers | `executionKeys.ts` exports the nine bindings and two matchers; `executionKeys.test.ts` covers the focus guard, the modifier guard, case-insensitivity and per-scope key uniqueness | none |
| 2 | T2 — The overlay that renders the map | `ShortcutOverlay.tsx` renders one row per binding, derived from `EXECUTION_SHORTCUTS`, with no key literal of its own | T1 |
| 3 | T3 — The failing screen tests | `ExecutionPage.test.tsx` exists with the harness, the verdict arm and **the arm that fails on every shipped implementation of this feature** — typing `p` in the notes box | T1 |
| 4 | T4 — Bind the verdicts at the screen | Pressing `p`/`f`/`s` on the execution screen records that verdict; `b` opens the precondition dialog; the listener is removed on unmount | T3 |
| 5 | T5 — Next and previous case | `j`/`k` move through the run in queue order, and **Previous case** / **Next case** buttons do the same visibly | T4 |
| 6 | T6 — The notes key and the way back out | `n` focuses the notes box; `Escape` inside it blurs; shortcuts are live again afterwards | T4 |
| 7 | T7 — The live region | Every verdict, move and refusal is announced in the app's polite live region | T4, T5 |
| 8 | T8 — The discoverable overlay | `?` and a visible **Keyboard shortcuts** button both open the overlay, which lists every binding in the map | T2, T4 |
| 9 | T9 — The deferred verification rows | `docs/verification/frontend-browser-checks.md` carries the `§248 KB-1..KB-10` rows in lane `A3`, none run | T8 |
| 10 | T10 — The gate task, deferred | The gate commands are written down and **explicitly not run**; the area is recorded as implemented and completely ungated | T9 |

---

### T1 — The key map and its matchers

**Deliverable:** `frontend/src/features/runs/executionKeys.ts` exports `EXECUTION_SHORTCUTS` (nine bindings), `isTypingTarget`, `matchShortcut` and `matchNotesShortcut`; `executionKeys.test.ts` covers the focus guard against every typing surface, the modifier guard, case-insensitivity, and per-scope key uniqueness.
**Depends on:** none

**Files:**
- Create: `frontend/src/features/runs/executionKeys.ts`
- Test: `frontend/src/features/runs/executionKeys.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/runs/executionKeys.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import {
  EXECUTION_SHORTCUTS,
  isTypingTarget,
  matchNotesShortcut,
  matchShortcut,
} from './executionKeys'

/**
 * The key map's own arms (§248).
 *
 * The first two describe the defect this feature ships broken everywhere: a single-key shortcut
 * that fires while the tester is typing a sentence into the notes box. Everything else here is a
 * property of the map rather than of one binding, which is the schema-wide-invariant rule applied
 * to a catalogue small enough that reading it looks sufficient — and is not.
 */

/** A keydown as the matchers read it, with the target the arm is about. */
function press(key: string, target: EventTarget | null, modifiers: Partial<Record<'ctrlKey' | 'metaKey' | 'altKey' | 'shiftKey', boolean>> = {}) {
  return {
    key,
    target,
    ctrlKey: modifiers.ctrlKey ?? false,
    metaKey: modifiers.metaKey ?? false,
    altKey: modifiers.altKey ?? false,
    shiftKey: modifiers.shiftKey ?? false,
  }
}

describe('isTypingTarget', () => {
  it('answers true for every surface a person types into', () => {
    const input = document.createElement('input')
    const textarea = document.createElement('textarea')
    const select = document.createElement('select')
    const editable = document.createElement('div')
    editable.setAttribute('contenteditable', 'true')

    expect(isTypingTarget(input)).toBe(true)
    expect(isTypingTarget(textarea)).toBe(true)
    expect(isTypingTarget(select)).toBe(true)
    expect(isTypingTarget(editable)).toBe(true)
  })

  it('answers true for a node INSIDE a contenteditable, not only the host', () => {
    const editable = document.createElement('div')
    editable.setAttribute('contenteditable', 'true')
    const span = document.createElement('span')
    editable.append(span)
    expect(isTypingTarget(span)).toBe(true)
  })

  it('answers false for the body, a button and nothing at all', () => {
    expect(isTypingTarget(document.body)).toBe(false)
    expect(isTypingTarget(document.createElement('button'))).toBe(false)
    expect(isTypingTarget(null)).toBe(false)
  })

  it('answers false for a disabled or readonly input — nobody is typing into it', () => {
    const disabled = document.createElement('input')
    disabled.disabled = true
    expect(isTypingTarget(disabled)).toBe(false)
  })
})

describe('matchShortcut', () => {
  it('resolves the verdict keys', () => {
    expect(matchShortcut(press('p', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'PASSED' })
    expect(matchShortcut(press('f', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'FAILED' })
    expect(matchShortcut(press('b', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'BLOCKED' })
    expect(matchShortcut(press('s', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'SKIPPED' })
  })

  it('resolves the movement and the notes keys', () => {
    expect(matchShortcut(press('j', document.body))?.action).toEqual({ kind: 'move', direction: 1 })
    expect(matchShortcut(press('k', document.body))?.action).toEqual({ kind: 'move', direction: -1 })
    expect(matchShortcut(press('n', document.body))?.action).toEqual({ kind: 'focusNotes' })
    expect(matchShortcut(press('?', document.body))?.action).toEqual({ kind: 'toggleHelp' })
  })

  it('is case-insensitive — caps lock is not a different keyboard', () => {
    expect(matchShortcut(press('P', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'PASSED' })
  })

  it('THE ARM THIS FEATURE SHIPS BROKEN: no shortcut fires while a text field has focus', () => {
    const notes = document.createElement('textarea')
    for (const shortcut of EXECUTION_SHORTCUTS.filter((s) => s.scope === 'screen')) {
      expect(matchShortcut(press(shortcut.key, notes))).toBeNull()
    }
  })

  it('ignores a keypress carrying a modifier — ⌘P is print, not Passed', () => {
    expect(matchShortcut(press('p', document.body, { metaKey: true }))).toBeNull()
    expect(matchShortcut(press('p', document.body, { ctrlKey: true }))).toBeNull()
    expect(matchShortcut(press('p', document.body, { altKey: true }))).toBeNull()
  })

  it('does not treat shift as a modifier — `?` needs it', () => {
    expect(matchShortcut(press('?', document.body, { shiftKey: true }))?.action).toEqual({ kind: 'toggleHelp' })
  })

  it('answers null for a key nobody bound, and never throws on one', () => {
    expect(matchShortcut(press('z', document.body))).toBeNull()
    expect(matchShortcut(press('Enter', document.body))).toBeNull()
    expect(matchShortcut(press('Escape', document.body))).toBeNull()
  })
})

describe('matchNotesShortcut', () => {
  it('fires INSIDE a text field, which is the whole point of the scope', () => {
    const notes = document.createElement('textarea')
    expect(matchNotesShortcut(press('Escape', notes))?.action).toEqual({ kind: 'leaveNotes' })
  })

  it('does not answer a screen-scope key', () => {
    const notes = document.createElement('textarea')
    expect(matchNotesShortcut(press('p', notes))).toBeNull()
  })
})

describe('the map itself', () => {
  it('binds each key at most once per scope — computed, not read', () => {
    for (const scope of ['screen', 'notes'] as const) {
      const keys = EXECUTION_SHORTCUTS.filter((s) => s.scope === scope).map((s) => s.key.toLowerCase())
      expect(new Set(keys).size).toBe(keys.length)
    }
  })

  it('gives every binding something the overlay can render', () => {
    for (const shortcut of EXECUTION_SHORTCUTS) {
      expect(shortcut.display.trim()).not.toBe('')
      expect(shortcut.description.trim()).not.toBe('')
    }
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

**DEFERRED — do not run it.** Under the batch directive no test is executed by this lane. Were it run, the expected failure is `Failed to resolve import "./executionKeys"`. Record the arm as written and unexecuted; the live pass and the post-batch gate run are what will execute it.

- [ ] **Step 3: Write the module**

Create `frontend/src/features/runs/executionKeys.ts`:

```ts
import type { CaseStatusKind } from '../../api/types';

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
  | { kind: 'verdict'; verdict: Extract<CaseStatusKind, 'PASSED' | 'FAILED' | 'BLOCKED' | 'SKIPPED'> }
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
  /** What the overlay prints in its `<kbd>`. Separate from {@link key} so `?` can read as `?`. */
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
 * unusable by keyboard. A **disabled** field does not count — nothing is being typed into it, and
 * treating it as a typing surface would silently kill the shortcuts on a frozen run, which is a
 * different refusal with a different sentence.
 *
 * `closest('[contenteditable]')` rather than an attribute read on the target itself: the caret in
 * a rich-text host sits in whatever inline node it is in, so the event's target is routinely a
 * `<span>` three levels down.
 */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement) {
    return !target.disabled && !('readOnly' in target && target.readOnly);
  }
  return target.closest('[contenteditable]:not([contenteditable="false"])') !== null;
}

/**
 * The **screen** binding this keypress means, or `null`.
 *
 * Refuses in three cases, and the order matters only for readability — a modifier chord belongs to
 * the browser or the OS (`⌘P` is print, `⌘F` is find), a keypress inside a text field belongs to
 * the text, and an unbound key belongs to nobody. **`shiftKey` is not among them**: `?` is a
 * shifted `/` on most layouts, so treating shift as a modifier would remove the help key.
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
```

- [ ] **Step 4: Confirm the type this imports exists**

Run: `grep -n "CaseStatusKind" frontend/src/api/types.ts`
Expected: a line exporting it. If the union is spelled differently in `api/types.ts`, use that spelling — the four literals must come from the contract's status kinds and not be re-declared here.

- [ ] **Step 5: Run the tests**

**DEFERRED.** `npm run test:run` is not run by this lane. Move on.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/runs/executionKeys.ts frontend/src/features/runs/executionKeys.test.ts
git commit -m "feat(runs): the execution key map, and the focus guard it exists for (§248)

One module both the overlay and the handler read, so a documented shortcut
cannot be one that does nothing. matchShortcut refuses before it matches:
typing 'p' into the notes box must not mark the case Passed.

Tests written, NOT RUN — no gate was executed for this commit."
```

---

### T2 — The overlay that renders the map

**Deliverable:** `frontend/src/features/runs/ShortcutOverlay.tsx` renders one row per binding in `EXECUTION_SHORTCUTS`, with no key literal of its own.
**Depends on:** T1 (the map it renders)

**Files:**
- Create: `frontend/src/features/runs/ShortcutOverlay.tsx`

- [ ] **Step 1: Write the component**

```tsx
import {
  Box,
  Dialog,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material';
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

export function ShortcutOverlay({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth data-testid="Execution.Shortcuts.Overlay">
      <DialogTitle>Keyboard shortcuts</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
          Every one of these has a button on the screen too — they are a faster route, never the
          only one. Nothing here fires while you are typing in a box.
        </Typography>

        {(Object.keys(SCOPE_HEADINGS) as ShortcutScope[]).map((scope) => {
          const rows = EXECUTION_SHORTCUTS.filter((shortcut) => shortcut.scope === scope);
          if (rows.length === 0) return null;
          return (
            <Box key={scope} sx={{ mb: 2 }}>
              <Typography variant="caption" sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                {SCOPE_HEADINGS[scope]}
              </Typography>
              <Stack component="dl" sx={{ m: 0, mt: 1, gap: 1 }}>
                {rows.map((shortcut) => (
                  <Stack
                    key={`${shortcut.scope}-${shortcut.key}`}
                    direction="row"
                    sx={{ alignItems: 'baseline', gap: 1.5 }}
                    data-testid="Execution.Shortcuts.Row"
                  >
                    <Box
                      component="dt"
                      sx={{
                        fontFamily: 'var(--font-mono)',
                        fontSize: '0.75rem',
                        fontWeight: 600,
                        minWidth: 40,
                        textAlign: 'center',
                        px: 0.75,
                        py: 0.25,
                        border: '1px solid',
                        borderColor: 'divider',
                        borderRadius: 0.5,
                        flexShrink: 0,
                      }}
                    >
                      {shortcut.display}
                    </Box>
                    <Typography component="dd" variant="body2" sx={{ m: 0 }}>
                      {shortcut.description}
                    </Typography>
                  </Stack>
                ))}
              </Stack>
            </Box>
          );
        })}
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 2: Check nothing in the file names a key**

Run: `grep -nE "'[pfbsjkn?]'|\"Escape\"" frontend/src/features/runs/ShortcutOverlay.tsx`
Expected: **no output.** A hit means a key literal crept into the renderer and the two-sources-of-truth defect is back.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/features/runs/ShortcutOverlay.tsx
git commit -m "feat(runs): the shortcut overlay, rendered from the key map (§248)

No key literal in the renderer — adding a binding updates the help for free.

NOT GATED: no build, lint or test run was executed for this commit."
```

---

### T3 — The failing screen tests

**Deliverable:** `frontend/src/features/runs/ExecutionPage.test.tsx` exists with the harness, the verdict arm, and the arm that fails on every shipped implementation of this feature — typing `p` in the notes box must record nothing.
**Depends on:** T1 (the map the screen will dispatch on)

**Files:**
- Create: `frontend/src/features/runs/ExecutionPage.test.tsx`

> **This is the repo's first `ExecutionPage.test.tsx`, and therefore the first thing in `frontend/src` that drives `useSubmitResult`** (§244 `ED-M8`). It is scoped to the keyboard feature — see this plan's Scope Check for what it deliberately does not cover.

- [ ] **Step 1: Write the test file**

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route, Routes, useLocation } from 'react-router-dom'
import { api } from '../../api/client'
import type { ExecutionResultInput } from '../../api/queries'
import type { CaseStatus, TestExecution } from '../../api/types'
import { AnnouncerProvider } from '../../a11y/AnnouncerProvider'
import { anExecution, aProject, aRun } from '../../test/fixtures'
import { heldPermissions } from '../../test/permissions'
import { createTestQueryClient } from '../../test/queryClient'
import { TestProviders } from '../../test/TestProviders'
import { ExecutionPage } from './ExecutionPage'

/**
 * The execution screen's keyboard (§248) — and the first automated driver of `useSubmitResult`
 * anywhere in `frontend/src`.
 *
 * The arms are the four ways a keyboard on this screen can be wrong: it fires while somebody is
 * typing, it writes Blocked without the checklist, it acts where the screen would have refused
 * (frozen run, missing permission), and it does something a screen reader is never told about.
 *
 * Scoped to the keyboard. Durations, preconditions, step marks, evidence, defects and the 409
 * panel are NOT covered here — §244 left them to the browser queue and this file does not quietly
 * adopt them.
 */

const STATUSES: CaseStatus[] = [
  { id: 'st-passed', name: 'Passed', kind: 'PASSED', countsAsPass: true },
  { id: 'st-failed', name: 'Failed', kind: 'FAILED', countsAsPass: false },
  { id: 'st-blocked', name: 'Blocked', kind: 'BLOCKED', countsAsPass: false },
  { id: 'st-skipped', name: 'Skipped', kind: 'SKIPPED', countsAsPass: false },
]

/*
  Prefix `2c` — pre-assigned to this area and grepped across backend/src, frontend/src and
  packages/*/src before minting. Three executions, so `j` and `k` both have somewhere to go and
  the middle one is neither end of the queue.
*/
const EX_1 = '2c000000-0000-4000-8000-000000000001'
const EX_2 = '2c000000-0000-4000-8000-000000000002'
const EX_3 = '2c000000-0000-4000-8000-000000000003'

const ROUTE = `/projects/p1/runs/r1/executions/${EX_2}`

function anExecutionSummary(id: string, caseKey: string) {
  return {
    id,
    caseId: `case-${id}`,
    caseKey,
    caseTitle: `The ${caseKey} case`,
    status: null,
    assignee: null,
    quarantined: false,
    durationMs: null,
  }
}

/** Everything this screen reads. `runStatus` and `permissions` are what the arms vary. */
function mockReads({
  runStatus = 'IN_PROGRESS',
  permissions = ['result:submit', 'result:amend', 'run:execute'],
  execution = anExecution({ id: EX_2, caseKey: 'TC-2', caseTitle: 'The TC-2 case', lockVersion: 3 }),
}: {
  runStatus?: string
  permissions?: string[]
  execution?: TestExecution
} = {}) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions as never)
    if (/^\/projects\/[^/]+$/.test(path)) return aProject({ id: 'p1' })
    if (/^\/projects\/[^/]+\/statuses$/.test(path)) return STATUSES
    if (/^\/projects\/[^/]+\/runs\/[^/]+$/.test(path)) {
      return {
        ...aRun({ id: 'r1', status: runStatus as never }),
        executions: [
          anExecutionSummary(EX_1, 'TC-1'),
          anExecutionSummary(EX_2, 'TC-2'),
          anExecutionSummary(EX_3, 'TC-3'),
        ],
      }
    }
    if (/^\/projects\/[^/]+\/runs\/[^/]+\/executions\/[^/]+$/.test(path)) return execution
    throw new Error(`unmocked read: ${path}`)
  }) as never)
}

/** Every result this screen writes, recorded, so an arm can assert the body — or its absence. */
function mockSubmit() {
  const sent: ExecutionResultInput[] = []
  vi.spyOn(api, 'patch').mockImplementation((async (path: string, body: ExecutionResultInput) => {
    if (!/^\/projects\/[^/]+\/runs\/[^/]+\/executions\/[^/]+$/.test(path)) {
      throw new Error(`unmocked write: ${path}`)
    }
    sent.push(body)
    return anExecution({ id: EX_2, caseKey: 'TC-2', status: STATUSES[0], lockVersion: 4 })
  }) as never)
  return sent
}

/** The URL, readable by an assertion — `j` and `k` are navigations and nothing else shows them. */
function Where() {
  const location = useLocation()
  return <span data-testid="Where">{location.pathname}</span>
}

async function renderScreen(route = ROUTE) {
  render(
    <TestProviders client={createTestQueryClient()} userId="user-1" route={route}>
      <AnnouncerProvider>
        <Routes>
          <Route path="/projects/:projectId/runs/:runId/executions/:executionId" element={<ExecutionPage />} />
          <Route path="/projects/:projectId/runs/:runId" element={<div data-testid="RunDetail" />} />
        </Routes>
        <Where />
      </AnnouncerProvider>
    </TestProviders>,
  )
  // The form is what binds the keys, so no arm may press one before it is on screen.
  await screen.findByTestId('Execution.Actions')
}

describe('ExecutionPage keyboard', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('records Passed on `p`', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await user.keyboard('p')

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toMatchObject({ statusId: 'st-passed' })
  })

  it('THE DEFECT THIS FEATURE SHIPS BROKEN: typing in the notes box records nothing', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.CommentField'))
    // Every screen shortcut, typed as a word a tester would really write. Under a naive
    // implementation this marks the case Passed, then Blocked, then Skipped, then navigates.
    await user.keyboard('problem: staging kept failing')

    expect(sent).toHaveLength(0)
    expect(screen.getByTestId('Execution.CommentField')).toHaveValue('problem: staging kept failing')
  })
})
```

- [ ] **Step 2: Check the fixture the harness assumes**

Run: `grep -n "countsAsPass\|kind" frontend/src/api/types.ts | head -20`
Expected: `CaseStatus` carries `id`, `name`, `kind`, `countsAsPass`. If it carries more required fields, add them to the four literals above — `contractDoubles.ts` compares every served body against `openapi.yaml`, so a `CaseStatus` missing a required property fails the test rather than passing quietly.

- [ ] **Step 3: Run the tests to verify they fail**

**DEFERRED — do not run.** Were they run, both arms would fail: nothing binds `p` yet, so the first records nothing and the second passes vacuously. That vacuous pass is exactly why the guard arm is written *before* the handler — after T4 it is the only thing standing between the notes box and four verdicts.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/runs/ExecutionPage.test.tsx
git commit -m "test(runs): the execution screen's first test file, and the focus-guard arm (§248)

ExecutionPage.test.tsx did not exist; nothing in frontend/src drove
useSubmitResult (§244 ED-M8). Scoped to the keyboard feature, not a general
test of the screen.

Red on purpose: T4 binds the handler. NOT RUN — no gate was executed."
```

---

### T4 — Bind the verdicts at the screen

**Deliverable:** Pressing `p`, `f` or `s` on the execution screen records that verdict through the same mutation the buttons use; `b` opens the precondition dialog rather than writing; the listener is registered on mount and removed on unmount, so the bindings are the screen's and not the app's.
**Depends on:** T3 (the arms it turns green)

**Files:**
- Modify: `frontend/src/features/runs/ExecutionPage.tsx` (`ExecutionForm`)
- Test: `frontend/src/features/runs/ExecutionPage.test.tsx` (add three arms)

- [ ] **Step 1: Add the refusal arms to the test file**

Append inside `describe('ExecutionPage keyboard', …)`:

```tsx
  it('`b` opens the precondition checklist and writes nothing', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await user.keyboard('b')

    // The dialog's own title, which BlockedDialog renders. Blocked is the one verdict that is
    // never one keystroke, in every mode — the rule executionMode.ts and ExecutionGrid.tsx both
    // defend, and a keyboard would be the first thing to break it.
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(sent).toHaveLength(0)
  })

  it('records nothing on a run that has ended', async () => {
    const user = userEvent.setup()
    mockReads({ runStatus: 'COMPLETED' })
    const sent = mockSubmit()
    await renderScreen()
    await screen.findByTestId('Execution.RunClosed')

    await user.keyboard('p')

    expect(sent).toHaveLength(0)
  })

  it('records nothing for a caller who holds neither result key', async () => {
    const user = userEvent.setup()
    mockReads({ permissions: ['run:execute'] })
    const sent = mockSubmit()
    await renderScreen()
    await waitFor(() => expect(screen.getByTestId('Execution.Action.PASSED')).toBeDisabled())

    await user.keyboard('p')

    expect(sent).toHaveLength(0)
  })

  it('unbinds when the screen unmounts — the keys are this screen\'s, not the app\'s', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    const { unmount } = render(
      <TestProviders client={createTestQueryClient()} userId="user-1" route={ROUTE}>
        <AnnouncerProvider>
          <Routes>
            <Route path="/projects/:projectId/runs/:runId/executions/:executionId" element={<ExecutionPage />} />
          </Routes>
        </AnnouncerProvider>
      </TestProviders>,
    )
    await screen.findByTestId('Execution.Actions')

    unmount()
    await user.keyboard('p')

    expect(sent).toHaveLength(0)
  })
```

- [ ] **Step 2: Import the map and the announcer in `ExecutionPage.tsx`**

Add to the import block at the top of `frontend/src/features/runs/ExecutionPage.tsx`:

```tsx
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useAnnounce } from '../../a11y/useAnnounce';
import { useCan } from '../../auth/useCan';
import {
  EXECUTION_SHORTCUTS,
  matchNotesShortcut,
  matchShortcut,
  type ExecutionAction,
} from './executionKeys';
import { ShortcutOverlay } from './ShortcutOverlay';
import { orderedQueue } from './queueOrder';
```

`useState` and `type ReactNode` are already imported from `react` — extend that line rather than adding a second one. `ShortcutOverlay` and `orderedQueue` are consumed in T5 and T8; add them then if the lint rule for unused imports would otherwise trip.

- [ ] **Step 3: Pass `modeParam` into the form**

`ExecutionForm` needs it to keep `?mode=` across a `j`/`k` move (T5). In `ExecutionPage`'s `DataRegion` child:

```tsx
        <DataRegion testId="Execution.Content" state={{ ...execution, refetch: () => void execution.refetch() }}>
          {(data) => (
            <ExecutionForm
              execution={data}
              statuses={statuses.data ?? []}
              projectId={projectId}
              runId={runId}
              executionId={executionId}
              modeParam={modeParam}
            />
          )}
        </DataRegion>
```

and in `ExecutionForm`'s props:

```tsx
function ExecutionForm({
  execution,
  statuses,
  projectId,
  runId,
  executionId,
  modeParam,
}: {
  execution: TestExecution;
  statuses: CaseStatus[];
  projectId: string;
  runId: string;
  executionId: string;
  /** The `?mode=` to carry across a keyboard move, or null when the mode is the default. */
  modeParam: string | null;
}) {
```

- [ ] **Step 4: Add the gate, the announcer and the dispatcher**

Inside `ExecutionForm`, after `const suggestedKind = suggestedVerdict(…)` and before the `return`:

```tsx
  const announce = useAnnounce();
  const gate = useCan(projectId);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  /*
    The permission a verdict needs depends on the row, exactly as the four buttons decide it: a
    first result is `result:submit`, amending one that is already there is `result:amend`. Asking
    the same question here rather than a simpler one is what stops the keyboard being a way past a
    gate the buttons enforce — a shortcut that 403s is worse than a disabled button, because
    nothing on screen ever said no.
  */
  const verdictPermission = execution.status ? 'result:amend' : 'result:submit';

  /**
   * What a screen shortcut does, and every reason it may do nothing.
   *
   * The refusals are announced rather than silent. A keypress that worked and a keypress that was
   * refused look identical to somebody not watching the verdict panel, and identical to *everybody*
   * using a screen reader — which is the whole argument for the live region this calls into.
   */
  const runShortcutAction = (action: ExecutionAction) => {
    if (action.kind === 'toggleHelp') {
      setShortcutsOpen((open) => !open);
      return;
    }
    if (action.kind === 'focusNotes') {
      commentRef.current?.focus();
      announce('Notes. Press Escape to leave the box.');
      return;
    }
    if (action.kind === 'move') {
      moveBy(action.direction);
      return;
    }
    if (action.kind === 'leaveNotes') {
      commentRef.current?.blur();
      announce('Left the notes box. The result keys work again.');
      return;
    }

    // A verdict, and the four gates the buttons already carry, in the same order.
    if (frozen) {
      announce('This run has ended, so its results can no longer change.');
      return;
    }
    if (!gate.can(verdictPermission)) {
      announce(gate.reason(verdictPermission) ?? 'You cannot record a result here.');
      return;
    }
    if (durationIsNonsense) {
      announce('Give a number of seconds in “How long it took”, or leave it empty.');
      return;
    }
    if (submit.isPending) return;

    if (action.verdict === 'BLOCKED') {
      // Blocked is never one keystroke — it opens the checklist, in every mode. See
      // `executionKeys.ts` and this file's own header for the rule.
      setBlockedDialog(true);
      announce('Blocked. Say which precondition did not hold.');
      return;
    }
    const status = byKind(action.verdict);
    if (!status) return;
    announce(`Recording ${statusLabels[action.verdict]} for ${execution.caseKey}.`);
    void record(status.id);
  };

  /*
    Bound at the screen, not globally: added when this form mounts and removed when it unmounts, so
    the bindings die with the screen they belong to. `document` rather than a wrapper div because
    the tester's focus is legitimately all over this page — a step's verdict button, the evidence
    list, nothing at all — and a container listener would answer only some of those.

    Grid mode never mounts this component (`Workspace` renders the grid INSTEAD of its children),
    so Grid has no shortcuts. That is deliberate and not an omission: a single-key verdict there
    would have to mean "the row I am on", and Grid has no such thing.
  */
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const shortcut = matchShortcut(event);
      if (!shortcut) return;
      // Only after a match: an unbound key, or one typed into a box, must reach the page
      // untouched. Preventing first would swallow the tester's own text.
      event.preventDefault();
      runShortcutAction(shortcut.action);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  });
```

> **The `useEffect` deliberately has no dependency array.** The handler closes over `frozen`, the
> gate, the duration parse, the mutation's pending flag and the queue position — all of which change
> between renders. A dependency list here would be a hand-maintained list of exactly the values whose
> staleness is invisible: a stale closure would refuse a verdict on a run that has since loaded, or
> allow one on a run that has since closed. Re-registering one listener per render is cheap and
> cannot be wrong. Say this in the code, not only here.

Add that paragraph as a comment above the `useEffect`.

- [ ] **Step 5: Add the notes ref**

`focusNotes` needs a handle on the comment field. Beside the other `useState` declarations in `ExecutionForm`:

```tsx
  /* The notes box, so `n` can put the caret in it and `Escape` can take it back out. A ref rather
     than an id lookup: two executions could be mounted at once during a route transition, and
     `getElementById` would find whichever rendered first. */
  const commentRef = useRef<HTMLTextAreaElement>(null);
```

and wire it on the field, which T6 finishes:

```tsx
                slotProps={{ htmlInput: { 'data-testid': 'Execution.CommentField', ref: commentRef } }}
```

- [ ] **Step 6: Add `moveBy`, a stub until T5**

`runShortcutAction` references `moveBy`; T5 implements it. To keep the tree buildable at this task's boundary, add it now with the queue arithmetic it will keep:

```tsx
  /* Where this case sits in the run, in the rail's order — `queueOrder.ts` owns that order and
     this reads it rather than re-deriving one, so the keyboard and the rail cannot disagree about
     what "next" means. */
  const queue = orderedQueue(run.data?.executions ?? []);
  const position = queue.findIndex((candidate) => candidate.id === executionId);

  const moveBy = (direction: -1 | 1) => {
    if (position < 0) return;
    const target = queue[position + direction];
    if (!target) {
      announce(direction === 1 ? 'This is the last case in the run.' : 'This is the first case in the run.');
      return;
    }
    const suffix = modeParam ? `?mode=${modeParam}` : '';
    navigate(`/projects/${projectId}/runs/${runId}/executions/${target.id}${suffix}`);
    announce(`Case ${position + 1 + direction} of ${queue.length}. ${target.caseKey}, ${target.caseTitle}.`);
  };
```

T5 adds the unsaved-work refusal in front of it and the visible buttons beside it.

- [ ] **Step 7: Run the tests**

**DEFERRED.** Not run by this lane.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/runs/ExecutionPage.tsx frontend/src/features/runs/ExecutionPage.test.tsx
git commit -m "feat(runs): single-key verdicts, bound at the screen (§248)

p/f/s record; b opens the precondition checklist, because Blocked is never one
keystroke in any mode. The keyboard asks the same permission the buttons ask,
respects the freeze and the unreadable-duration refusal, and announces every
refusal — a silent no is indistinguishable from a broken key.

Listener added on mount, removed on unmount: the bindings are the screen's.

NOT GATED: no build, lint or test run was executed for this commit."
```

---

### T5 — Next and previous case

**Deliverable:** `j` and `k` move through the run in the rail's order, and **Previous case** / **Next case** buttons on the screen do the same thing visibly; both refuse at the ends of the queue and while there is unsaved work, with the reason said out loud.
**Depends on:** T4 (`moveBy`, the announcer, the dispatcher)

**Files:**
- Modify: `frontend/src/features/runs/ExecutionPage.tsx`
- Test: `frontend/src/features/runs/ExecutionPage.test.tsx`

- [ ] **Step 1: Write the arms**

```tsx
  it('`j` and `k` walk the run in the rail\'s order', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('j')
    await waitFor(() => expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_3}`))

    await user.keyboard('k')
    await waitFor(() => expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_2}`))
  })

  it('does not move past the end of the run', async () => {
    const user = userEvent.setup()
    mockReads({ execution: anExecution({ id: EX_3, caseKey: 'TC-3', lockVersion: 1 }) })
    mockSubmit()
    await renderScreen(`/projects/p1/runs/r1/executions/${EX_3}`)

    await user.keyboard('j')

    expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_3}`)
  })

  it('refuses to move while there is unsaved work, rather than discarding it', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.CommentField'))
    await user.keyboard('staging was down')
    // Out of the box, or the shortcut is correctly guarded and the arm proves nothing.
    await user.keyboard('{Escape}')
    await user.keyboard('j')

    expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_2}`)
    expect(screen.getByTestId('Execution.Nav.Next')).toBeDisabled()
  })

  it('offers the move as buttons too — the keys are an accelerator, not the only route', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.Nav.Next'))

    await waitFor(() => expect(screen.getByTestId('Where')).toHaveTextContent(`/executions/${EX_3}`))
  })
```

- [ ] **Step 2: Put the unsaved-work refusal in front of the move**

Replace `moveBy` from T4 with:

```tsx
  /*
    `j` is one keystroke and this form holds unsaved work in four buffers. The header on
    `useUnsavedWork` above says what losing it costs: the marks on this screen are the only record
    of what happened, so losing them means running the test again.

    So a move is REFUSED while there is unsaved work rather than confirmed — this repo has no
    shared confirm dialog, and inventing one in the smallest area of the plan would be new
    machinery guarding one control. The buttons carry the reason in a tooltip (§31,
    disable-don't-hide) and the keyboard announces the same sentence. `§248 KB-9` asks the live
    pass whether that is too strict; it is a judgement no file can settle.
  */
  const unsavedWork = markedSomeStep || typedSomething || untickedAPrecondition;
  const moveRefusal = unsavedWork
    ? 'Record or clear this result first — moving on would discard what you have marked here.'
    : undefined;

  const moveBy = (direction: -1 | 1) => {
    if (position < 0) return;
    if (moveRefusal) {
      announce(moveRefusal);
      return;
    }
    const target = queue[position + direction];
    if (!target) {
      announce(direction === 1 ? 'This is the last case in the run.' : 'This is the first case in the run.');
      return;
    }
    const suffix = modeParam ? `?mode=${modeParam}` : '';
    navigate(`/projects/${projectId}/runs/${runId}/executions/${target.id}${suffix}`);
    announce(`Case ${position + 1 + direction} of ${queue.length}. ${target.caseKey}, ${target.caseTitle}.`);
  };
```

- [ ] **Step 3: Add the visible equivalents**

In the **Record the result** panel, immediately above the existing `Execution.Cancel` button:

```tsx
              {/*
                The visible half of `j` and `k`. Every shortcut on this screen has one — an
                accelerator is a second route to something, never the only route to it, and Focus
                mode has no queue rail at all (nor does Split below `lg`, where the rail is
                dropped). Without these two buttons, "next case" would be keyboard-only on the
                narrowest screen, which is the one most likely to be a tablet on a test bench.
              */}
              <Stack direction="row" sx={{ gap: 1, mt: 1 }}>
                <Tooltip title={moveRefusal ?? (position <= 0 ? 'This is the first case in the run.' : 'Previous case (K)')}>
                  <Box sx={{ flex: 1 }}>
                    <Button
                      fullWidth
                      size="small"
                      disabled={position <= 0 || !!moveRefusal}
                      onClick={() => moveBy(-1)}
                      data-testid="Execution.Nav.Previous"
                    >
                      Previous case
                    </Button>
                  </Box>
                </Tooltip>
                <Tooltip
                  title={
                    moveRefusal ??
                    (position < 0 || position >= queue.length - 1
                      ? 'This is the last case in the run.'
                      : 'Next case (J)')
                  }
                >
                  <Box sx={{ flex: 1 }}>
                    <Button
                      fullWidth
                      size="small"
                      disabled={position < 0 || position >= queue.length - 1 || !!moveRefusal}
                      onClick={() => moveBy(1)}
                      data-testid="Execution.Nav.Next"
                    >
                      Next case
                    </Button>
                  </Box>
                </Tooltip>
              </Stack>
```

> The `Box` inside each `Tooltip` is not decoration. MUI puts `pointer-events: none` on a disabled
> button, so a `Tooltip` wrapped directly around one greys out and explains nothing — the same trap
> `PermissionGate.tsx` documents and solves the same way.

- [ ] **Step 4: Run the tests**

**DEFERRED.**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/runs/ExecutionPage.tsx frontend/src/features/runs/ExecutionPage.test.tsx
git commit -m "feat(runs): next/previous case, by key and by button (§248)

j/k walk queueOrder's order, so the keyboard and the rail cannot disagree about
what 'next' means. Both ends refuse, and so does a move that would discard
unsaved marks — one keystroke must not cost a test run.

Previous/Next buttons are the visible equivalent; Focus mode has no rail and
Split drops it below lg, so without them 'next case' would be keyboard-only.

NOT GATED: no build, lint or test run was executed for this commit."
```

---

### T6 — The notes key and the way back out

**Deliverable:** `n` puts the caret in the notes box, `Escape` inside it blurs the field, and the screen shortcuts are live again afterwards.
**Depends on:** T4 (the ref and the dispatcher)

**Files:**
- Modify: `frontend/src/features/runs/ExecutionPage.tsx`
- Test: `frontend/src/features/runs/ExecutionPage.test.tsx`

- [ ] **Step 1: Write the arms**

```tsx
  it('`n` puts the caret in the notes box', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('n')

    expect(screen.getByTestId('Execution.CommentField')).toHaveFocus()
  })

  it('`Escape` leaves the notes box, and the verdict keys work again after it', async () => {
    const user = userEvent.setup()
    mockReads()
    const sent = mockSubmit()
    await renderScreen()

    await user.keyboard('n')
    await user.keyboard('it passed on the third try')
    expect(sent).toHaveLength(0)

    await user.keyboard('{Escape}')
    expect(screen.getByTestId('Execution.CommentField')).not.toHaveFocus()

    await user.keyboard('p')
    await waitFor(() => expect(sent).toHaveLength(1))
    // The note the tester typed travels with the verdict — Escape leaves the box, it does not
    // discard what is in it.
    expect(sent[0]).toMatchObject({ statusId: 'st-passed', comment: 'it passed on the third try' })
  })
```

- [ ] **Step 2: Handle the notes-scope key on the field itself**

On the Notes `TextField` in `ExecutionForm`:

```tsx
              <TextField
                label="What happened"
                fullWidth
                multiline
                minRows={3}
                value={comment}
                onChange={(event) => setComment(event.target.value)}
                /*
                  The one binding that must fire while a text field has focus, which is why it has
                  its own scope in the map and its own matcher. Handled here rather than in the
                  screen listener because that listener refuses typing targets on purpose — asking
                  it to make an exception would be a hole in the guard this whole feature is about.

                  `stopPropagation`, so Escape here does not also close a dialog that happens to be
                  open above the form.
                */
                onKeyDown={(event) => {
                  const shortcut = matchNotesShortcut(event);
                  if (!shortcut) return;
                  event.preventDefault();
                  event.stopPropagation();
                  runShortcutAction(shortcut.action);
                }}
                helperText="Anything the next reader needs — an error message, the data you used, what you tried."
                slotProps={{ htmlInput: { 'data-testid': 'Execution.CommentField', ref: commentRef } }}
              />
```

- [ ] **Step 3: Run the tests**

**DEFERRED.**

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/runs/ExecutionPage.tsx frontend/src/features/runs/ExecutionPage.test.tsx
git commit -m "feat(runs): n jumps to the notes, Escape comes back out (§248)

Once the caret is in the box every screen shortcut is correctly dead, so
without a documented way out 'n' would be a trap. Escape is handled by the
field itself — asking the screen listener to make an exception would be a hole
in the guard the feature exists for.

NOT GATED: no build, lint or test run was executed for this commit."
```

---

### T7 — The live region

**Deliverable:** Every verdict, move and refusal the keyboard performs is announced in the app's single polite live region, asserted by a test that reads that region.
**Depends on:** T4, T5

**Files:**
- Test: `frontend/src/features/runs/ExecutionPage.test.tsx`
- Modify: `frontend/src/features/runs/ExecutionPage.tsx` (only if an arm finds a missing announcement)

> The `announce(...)` calls landed with T4 and T5, because a refusal written without its sentence
> is a refusal that ships silent. This task is where they are **asserted** — an announcement nothing
> reads back is indistinguishable from no announcement at all.

- [ ] **Step 1: Write the arms**

```tsx
  it('announces the verdict it recorded', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('p')

    // The announcer clears and re-sets in a microtask, so this is a find, never a get.
    expect(await screen.findByTestId('Announcer')).toHaveTextContent('Recording Passed for TC-2.')
  })

  it('announces a refusal, so a dead key and a working one are not the same experience', async () => {
    const user = userEvent.setup()
    mockReads({ runStatus: 'COMPLETED' })
    mockSubmit()
    await renderScreen()
    await screen.findByTestId('Execution.RunClosed')

    await user.keyboard('p')

    expect(await screen.findByTestId('Announcer')).toHaveTextContent('This run has ended')
  })

  it('announces where a move landed, including which case and how far through', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('j')

    expect(await screen.findByTestId('Announcer')).toHaveTextContent('Case 3 of 3. TC-3')
  })
```

- [ ] **Step 2: Reconcile the sentences**

Read the three `announce(...)` sites in `ExecutionForm` against the three assertions above. They must match **exactly** — if T4's wording drifted, change the code or the assertion so one sentence exists, not two. Do not add a second announcement to satisfy an assertion.

- [ ] **Step 3: Run the tests**

**DEFERRED.**

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/runs/ExecutionPage.test.tsx frontend/src/features/runs/ExecutionPage.tsx
git commit -m "test(runs): the live region says what the keyboard did (§248)

A keypress that worked and one that was refused look identical to anybody not
watching the verdict panel, and identical to everybody using a screen reader.
Three arms read the announcer back: a recorded verdict, a refusal, and a move.

NOT GATED: no build, lint or test run was executed for this commit."
```

---

### T8 — The discoverable overlay

**Deliverable:** `?` and a visible **Keyboard shortcuts** button both open the overlay, which lists every binding in `EXECUTION_SHORTCUTS` — asserted against the map rather than against a count.
**Depends on:** T2 (the component), T4 (the `toggleHelp` action)

**Files:**
- Modify: `frontend/src/features/runs/ExecutionPage.tsx`
- Test: `frontend/src/features/runs/ExecutionPage.test.tsx`

- [ ] **Step 1: Write the arms**

```tsx
  it('`?` opens the overlay, and it lists every binding in the map', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.keyboard('?')

    const overlay = await screen.findByTestId('Execution.Shortcuts.Overlay')
    // Derived from the map, not a literal count: adding a binding must update the help for free,
    // and an assertion on `9` would pass a build where the overlay silently dropped one.
    expect(within(overlay).getAllByTestId('Execution.Shortcuts.Row')).toHaveLength(EXECUTION_SHORTCUTS.length)
    for (const shortcut of EXECUTION_SHORTCUTS) {
      expect(within(overlay).getByText(shortcut.description)).toBeInTheDocument()
    }
  })

  it('opens the overlay from a button too', async () => {
    const user = userEvent.setup()
    mockReads()
    mockSubmit()
    await renderScreen()

    await user.click(screen.getByTestId('Execution.Shortcuts.Open'))

    expect(await screen.findByTestId('Execution.Shortcuts.Overlay')).toBeInTheDocument()
  })
```

Add to that file's imports:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import { EXECUTION_SHORTCUTS } from './executionKeys'
```

- [ ] **Step 2: Render the overlay and its button**

In the **Record the result** panel, below the `Execution.Cancel` button:

```tsx
              {/*
                The visible route to the help, and it is not optional: an overlay reachable only by
                a keyboard shortcut is circular help for precisely the people who do not yet know
                the shortcuts.
              */}
              <Button
                size="small"
                onClick={() => setShortcutsOpen(true)}
                data-testid="Execution.Shortcuts.Open"
                sx={{ mt: 0.5 }}
              >
                Keyboard shortcuts (?)
              </Button>
```

and beside the existing `<BlockedDialog …>` at the end of `ExecutionForm`'s tree:

```tsx
      <ShortcutOverlay open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} />
```

- [ ] **Step 3: Run the tests**

**DEFERRED.**

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/runs/ExecutionPage.tsx frontend/src/features/runs/ExecutionPage.test.tsx
git commit -m "feat(runs): the shortcuts overlay, by ? and by button (§248)

Help reachable only by a keyboard shortcut is circular for exactly the people
who do not know the shortcuts yet. The arm counts rows against the map rather
than against a literal, so a binding the overlay drops fails the test.

NOT GATED: no build, lint or test run was executed for this commit."
```

---

### T9 — The deferred verification rows

**Deliverable:** `docs/verification/frontend-browser-checks.md` carries a `§248` section with ten `KB-*` rows in lane `A3`, all unrun, and the backend mutation queue is explicitly recorded as gaining nothing.
**Depends on:** T8 (the last behaviour a row can describe)

**Files:**
- Modify: `docs/verification/frontend-browser-checks.md`

- [ ] **Step 1: Re-read the tail of the file before editing it**

Run: `tail -40 docs/verification/frontend-browser-checks.md`
Expected: the `§245 BA-*` table. **A concurrent lane appends here too** — re-read immediately before writing, append below whatever is there, and never rewrite a row you did not add.

- [ ] **Step 2: Append the section**

```markdown
---

## §248 — the execution keyboard (area F of the gaps plan)

⚠️ **Unrun, and the code behind them is completely ungated.** Area F was built under the batch-3
no-gates directive: no `npm run test:run`, no `npm run build`, no `npm run lint`, not even a single
targeted file. The `executionKeys.test.ts` arms and the eighteen `ExecutionPage.test.tsx` arms
exist and **have never executed**. Read every row below as resting on nothing but the code.

Ids are `§248 KB-*`; `KB` was grepped against this file and the mutation queue before minting.

**Lane `A3` — execution.** Every row is the execution *detail* screen in Focus or Split mode,
which `A3` already drives: `TR-1` with cases to record into. **No new fixture is asked for** — the
run `A3` builds has more than two cases, which is all `KB-4` needs. `KB-10` needs a screen reader
(VoiceOver on the pass's Mac is enough) and is the one row that cannot be done by looking.

**Nothing is added to `docs/verification/backend-mutation-checks.md`.** Area F is frontend-only:
the only write it can cause is `PATCH …/executions/{id}`, which is the same request the verdict
buttons already send and which `§244 ED-*` and the M2 rows already cover. A mutation row here would
be a second copy of an existing one.

| Row | Drive | Expect |
|---|---|---|
| `§248 KB-1` | ⚠️ **The defect this feature ships broken everywhere, and the reason to do this row first.** Open a case in **Focus** mode, click into **What happened**, and type a real sentence containing every bound letter — *"problem: staging kept failing, skipped for now"* | The sentence lands in the box **in full** and **nothing is recorded**: no verdict, no navigation, no dialog. If a single character of it triggers anything, stop and report it — the guard is the feature. Repeat in the per-step **What actually happened** box and in **How long it took**, which are the two other typing surfaces on this screen. |
| `§248 KB-2` | With focus on the page background, press `p` | The result records as **Passed** and the screen returns to the run, exactly as the button does. Then press `f` on another case, and `s` on a third. |
| `§248 KB-3` | Press `b` | The **precondition checklist dialog** opens — it does **not** record. Blocked is never one keystroke in any mode, which is the rule `executionMode.ts`, `ExecutionGrid.tsx` and the execution screen all carry. Cancel the dialog and confirm nothing was written. |
| `§248 KB-4` | Press `j`, then `j` again, then `k` | The screen moves to the next case in the run **in the rail's order** (by case key, `queueOrder.ts`), then the next, then back. In **Split** mode the rail's highlight and its *"Case n of m"* follow along, and `?mode=split` survives every move. At the last case, `j` does nothing and says so. |
| `§248 KB-5` | Press `n`, type a note, press `Escape`, then press `p` | `n` puts the caret in **What happened**; the typed note is untouched by the keys in it; `Escape` takes focus out; `p` then records **Passed** *with the note attached*. This is the loop the feature is for — describe, then verdict, without a pointer. |
| `§248 KB-6` | Press `?` | The **Keyboard shortcuts** overlay opens and lists **every** binding, including `Escape`, each with a sentence. Close it with `Escape`. Then open it again from the **Keyboard shortcuts (?)** button — an overlay reachable only by a shortcut is circular help. |
| `§248 KB-7` | On a **completed** run, press `p`. Then, as an account holding neither `result:submit` nor `result:amend` (a Viewer), press `p` on a live run | **Nothing is recorded** in either case, and in both the reason is *announced* — the freeze sentence, and the permission sentence. ⚠️ **This is the row that catches a keyboard used as a way past a gate the buttons enforce.** A shortcut that 403s is worse than a disabled button, because nothing on screen ever said no. |
| `§248 KB-8` | Type `abc` into **How long it took (seconds)**, click out of the box, then press `p` | **Nothing is recorded** and the refusal is announced. The verdict buttons already disable here (`§244 ED-3`) because the body states total state and would silently clear the duration — the keyboard must refuse for the same reason and not route around it. |
| `§248 KB-9` | ⚠️ **A judgement this plan could not settle — report an opinion, not just a pass/fail.** Mark two steps, then press `j` | The move is **refused** with *"Record or clear this result first…"*, and **Previous case** / **Next case** are disabled with that reason in their tooltips. The marks survive. **Then say whether the refusal is right**: the alternative is a confirm dialog, which this repo has no shared component for. One keystroke discarding a run's marks is the failure it prevents; being unable to skip a case without clearing it is the cost. |
| `§248 KB-10` | ⚠️ **Needs a screen reader** (VoiceOver, ⌘F5). With it on, press `p`, then `j`, then `p` on a completed run | Each act is **spoken**: the verdict being recorded, the case moved to and how far through the run it is, and the refusal. Nothing is spoken twice, and nothing interrupts — the region is `polite`. Without this row the a11y half of the feature is a claim rather than an observation. |
```

- [ ] **Step 3: Confirm the ids are still unique**

Run: `grep -c "KB-" docs/verification/frontend-browser-checks.md docs/verification/backend-mutation-checks.md`
Expected: `10` in the browser queue (the ten rows), `0` in the mutation queue.

- [ ] **Step 4: Commit — and say whose rows are in the diff**

```bash
git add docs/verification/frontend-browser-checks.md
git status --short docs/verification/
git commit -m "docs: §248 KB-1..KB-10, the execution keyboard's deferred browser rows

Lane A3, no new fixture. KB-1 is the focus guard and goes first. KB-9 asks the
pass for a judgement this plan could not settle from a file, and KB-10 needs a
real screen reader. Nothing is added to the mutation queue — area F is
frontend-only and its one write is already covered by §244's rows.

None run (§65)."
```

> If `git status` shows the other lane's rows in the same file, **say so in the commit message**
> rather than letting the commit read as though every row in the diff is this lane's.

---

### T10 — The gate task, deferred

**Deliverable:** The gate commands for this area are written down and explicitly **not run**, and the area is recorded as implemented and completely ungated.
**Depends on:** T9

**Files:** none

- [ ] **Step 1: Write down what the gate would be**

These are the commands the post-batch pass runs on `shanks`. **This lane runs none of them:**

```bash
~/bin/tcms-remote frontend npm run test:run   # DEFERRED — not run by this lane
~/bin/tcms-remote frontend npm run build      # DEFERRED — not run by this lane
~/bin/tcms-remote frontend npm run lint       # DEFERRED — not run by this lane
```

- [ ] **Step 2: Record the state honestly**

The area is **implemented and completely ungated**. It is not done, not green, not verified and not passing. Two counts that will move and are *not* known: the frontend suite count (quoted at §243's **1602 / 120**, already stale twice over) and the lint warning count.

- [ ] **Step 3: Do NOT close the area**

Master task 46 says *"`fe-react.md` seam entry; queue rows; handoff; push"*. **The queue rows are T9; the rest is deliberately out of this plan.** Editing `docs/agent-context/fe-react.md`, `docs/agent-context/be-java.md` or `docs/HANDOFF.md`, and creating `docs/handoff/248-*.md`, is a file conflict with any concurrent lane even when the code is disjoint — the master plan's parallelism section says so in as many words (*"One writer for `docs/agent-context/*.md` … stagger the closes"*). The close is staggered by whoever is coordinating the batch, and when it happens **all three files are re-read immediately before editing**.

---

## Self-Review

**1. Spec coverage.** Master tasks 41–46 map to: 41 → T1; 42 → T1 and T3 (and the guard arm is written before the handler, as required); 43 → T4, T5, T6; 44 → T5's buttons, T8's button, T7's live region; 45 → T2 and T8; 46 → T9 and T10, with the close explicitly excluded and why. The prompt's four named requirements — the focus guard, one key map, bind at the screen, a11y as part of the feature — each have a task and a browser row.

**2. Placeholders.** None. Every code step carries the code; every gate step says *deferred* and names what would have run rather than pretending.

**3. Type consistency.** `ExecutionShortcut` / `ExecutionAction` / `ShortcutScope` are declared in T1 and used unchanged in T2, T4, T6, T8. `matchShortcut` and `matchNotesShortcut` keep their names throughout. `runShortcutAction` is declared in T4 and called in T4 and T6. `moveBy` is introduced in T4 (so the tree builds) and extended in T5. `commentRef` is declared in T4 and read in T4 and T6.

**4. Order.** T1 → T2 (renders the map) → T3 (dispatches on the map) → T4 (turns T3 green) → T5/T6 (extend T4's dispatcher) → T7 (asserts T4/T5's announcements) → T8 (needs T2 and T4) → T9 → T10. Every `Depends on` points backwards. The one boundary that is deliberately red is T3→T4, which is TDD's red step and is called out where it happens.

**5. Deliverables.** All ten state a result. T10's is a record, which is the honest deliverable of a task whose commands are not run.
