# Slice 8 — Keyboard-driven execution

**Lane:** frontend only · **Migration:** none · **Depends on:** Slices 3 and 4

---

## Prompt

```text
Implement **Slice 8 — Keyboard-driven execution** from `docs/plans/2026-08-18-unplanned-gaps.md`.

**First act: run `ep-writing-plans` on this slice alone** and save it as
`docs/plans/2026-08-18-gap-8-keyboard-execution.md`. Then execute with `ep-executing-plans`.

Read first: `docs/agent-context/fe-react.md`, then the Slice 8 section of the master plan. Use
**`/fe-react`**, and load `fe-react-accessibility` — this slice is an accessibility change as much
as a speed one.

**What this slice is:** recording results is the highest-volume repetitive task in the product and
every single one costs a pointer round-trip. Add single-key status entry on the execution screen,
next/previous case, a key to focus the notes field, and a discoverable shortcut overlay.

**Bind at the screen, not globally.** A global handler is how a shortcut fires on a page that has
nothing to do with execution.

**Depends on Slices 3 and 4** — it binds their controls (duration entry, bulk selection). Landing
it first means rebinding later.

**Handoff:** next free § from `docs/HANDOFF.md`. Orientation cache in this slice's own commit.
```

---

## The three traps this slice turns on

**1. Shortcuts must not fire while a text field has focus.** This is the defect every
implementation of this feature ships first: the tester types "blocked by DB" into the notes field
and the `b` sets the status to Blocked. Guard on the event target, and **test that case explicitly** —
it is the acceptance gate, not an edge case.

**2. Every shortcut needs a visible, focusable equivalent.** This is an accelerator, never the only
route to an action. A keyboard-only *and* a pointer-only user must both be able to work.

**3. A status change must be announced.** Screen-reader users get nothing from a silent DOM
mutation — put the change through a live region.

**And one design rule:** the key map lives in **one module**, consumed by both the handler and the
overlay. Two lists are how the help screen ends up lying about what the keys do.

## Standing rules that bind this slice

- **Tests run on `shanks`** — `~/bin/tcms-remote frontend npm run test:run`, plus `npm run build`
  and `npm run lint` (lint is a gate at **0 warnings**, not advisory).
- **Never build your own `QueryClient` in a test** — use `createTestQueryClient`
  (`frontend/src/test/queryClient.ts:41`). Only that helper's caches are visible to the render- and
  query-failure checks, and `renderFailures.test.tsx` sweeps every test source to keep the opt-out
  list empty.
- **Use `src/test/fixtures.ts`.** The harness compares every mocked body against `openapi.yaml` and
  fails a test that serves a shape the server could not send.
- **Beware debounce-fed popups.** If any suggestion popup is in play, capturing a row and clicking
  it must be **one retried step**, not two awaited ones — the node detaches between them and the
  click fires nothing. See `pickSuggestion` and the §186 note in CLAUDE.md.
- **New display logic goes in the shared layer**, not the page.
- Browser-check rows into the queue; run nothing (§65).
