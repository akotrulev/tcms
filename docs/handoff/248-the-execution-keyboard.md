# §248 — The execution keyboard, and the guard that is the feature

**Area F of `docs/plans/2026-08-18-unplanned-gaps.md`** (master tasks 41–46), executed against the
stepped plan `docs/plans/2026-08-18-gap-f-keyboard-driven-execution.md`, which this area's first
act was to write — as that master plan's Scope Check requires.

**No migration, no contract change, no permission seeded or moved, catalogue still 55, head still
`V67`, and no file under `backend/` or `packages/` touched.** That disjointness is why F was
eligible to run beside area C, and it did, at the user's explicit permission.

> ⚠️ **IMPLEMENTED AND COMPLETELY UNGATED.** The lane ran under the batch-3 directive to run no
> tests and no gates: no `npm run test:run`, no `npm run build`, no `npm run lint`, not one
> targeted file, not locally and not on `shanks`. **32 test arms were written and none has ever
> executed** — 15 in `executionKeys.test.ts`, 17 in `ExecutionPage.test.tsx`. Every claim below is
> read off the code. The suite count quoted anywhere near this area is §243's **1602 / 120** and is
> now stale three times over. This area is *not* done, green, verified or passing.

---

## The defect this feature ships broken everywhere, and where it was written down first

Single-key verdicts on a screen that has a notes box, a per-step comment box and a duration box
means typing *"problem: staging kept failing, skipped for now"* into the notes marks the case
Passed, then Blocked, then Skipped, then navigates away. **The guard arm was written before the
handler existed** — task 3 of the plan creates `ExecutionPage.test.tsx` with the focus-guard arm in
it and task 4 is what turns it green.

`matchShortcut` refuses in this order, and the order is only readability — a modifier chord belongs
to the browser or the OS, a keypress inside a text field belongs to the text, an unbound key
belongs to nobody:

1. `ctrlKey || metaKey || altKey` → `null`. **`shiftKey` is deliberately not among them** and is
   not even on the event shape: `?` is a shifted `/` on most layouts, so a shift guard would delete
   the help binding.
2. `isTypingTarget(target)` → `null`.
3. lookup in the map.

`isTypingTarget` answers **false** for a *disabled* or *read-only* field. Nothing is being typed
into one, and treating it as a typing surface would silently kill every shortcut on a frozen run —
a different refusal, with a different sentence, arriving as a dead keyboard. It reaches for
`closest('[contenteditable]:not([contenteditable="false"])')` rather than reading the attribute off
the target, because the caret in a rich-text host sits in whatever inline node it happens to be in
and the event target is routinely a `span` three levels down.

**The unit arm loops over the map**, asserting `null` for *every* screen binding against both a
`textarea` and an `input`, rather than naming three keys by hand. That is CLAUDE.md's
computed-check rule applied to a catalogue small enough that reading it looks sufficient.

## One map, or the help lies

`frontend/src/features/runs/executionKeys.ts` is the only place a key literal may appear.
`ShortcutOverlay.tsx` renders the array; **a grep proving the renderer contains no key literal is a
step of the task**, not an afterthought. Two sources of truth here fail in exactly one direction
and the failure is silent: a documented shortcut that does nothing, or a working one nobody is told
about.

| Key | Does | Scope |
|---|---|---|
| `p` `f` `s` | Record that verdict | screen |
| `b` | **Opens the precondition checklist** — never a direct write | screen |
| `j` / `k` | Next / previous case, in `queueOrder`'s order | screen |
| `n` | Focus the notes box | screen |
| `?` | The overlay | screen |
| `Escape` | Leave the notes box | **notes** |

### `j`/`k`, because `p` and `n` were unavailable

The obvious pair is `n` for next and `p` for previous. **Both were already taken and neither could
move.** `p` is *Passed* — the verdict letters are the initials the step rows have rendered since
§100 (`P F B S`), so the keyboard and the screen share one vocabulary, and renaming the highest
frequency key in the feature to free up "next" would be trading the common case for the rare one.
`n` is the notes key, which has no other mnemonic at all. Every remaining letter that reads as
"next" or "previous" collides the same way, so the pair comes from the one convention with no
collision left. That makes them *unguessable*, which is precisely why the overlay is part of this
area and not a follow-up: `j`/`k` without discoverable help would be a feature only its author can
use.

### `b` opens the checklist and does not write

The rule `executionMode.ts`, `ExecutionGrid.tsx` and `ExecutionPage.tsx` all carry in as many
words: a tester who could not get the environment up is not looking at a failing feature, and
recording that as Failed poisons the pass rate and sends somebody chasing a defect that does not
exist. Blocked routes through the precondition dialog **in every mode**, and a keyboard that wrote
Blocked in one keystroke would have been its first exception.

### `Escape` is a second scope, not an exception in the first

Once `n` puts the caret in the box, every screen shortcut is correctly dead — so without a
documented way out, `n` is a trap. `matchNotesShortcut` deliberately **does not** apply the focus
guard, because firing while a text field has focus is its entire job, and it is handled by the
field's own `onKeyDown` (with `stopPropagation`, so it does not also close a dialog above the
form). Asking the screen listener to make an exception for one key would have been a hole in the
guard the whole feature is about.

## Bound at the screen, and the missing dependency array is the decision

The listener is added in a `useEffect` inside `ExecutionForm` and removed on unmount, so the
bindings die with the screen rather than living in the app. `document` rather than a wrapper div,
because the tester's focus on this page is legitimately everywhere — a step's verdict button, the
evidence list, nothing at all — and a container listener would answer only some of those.

**Grid mode never mounts `ExecutionForm`** (`Workspace` renders the grid *instead of* its
children), so Grid has no shortcuts. Deliberate: a single-key verdict there would have to mean "the
row I am on", and Grid has no such thing.

**The `useEffect` has no dependency array, and that is the design rather than an omission.** The
handler closes over `frozen`, the `useCan` gate, the duration parse, the pending mutation and the
queue position — every one of which changes between renders. A dependency list here would be a
hand-maintained list of exactly the values whose staleness is invisible: a stale closure refuses a
verdict on a run that has since finished loading, or *allows* one on a run that has since closed —
and neither shows up as an error, only as a keyboard that disagrees with the buttons beside it.
Re-registering one listener per render is cheap and cannot be wrong. The reasoning is in the code,
not only here, because the next reader's instinct will be to add the array.

## The keyboard is not a way past a gate

It asks the **same per-row permission the buttons ask** — `result:submit` for a first result,
`result:amend` to change one that is already there — and it honours the freeze and §244's
unreadable-duration refusal. A shortcut that 403s is worse than a disabled button, because nothing
on screen ever said no.

**Every refusal is announced**, through `useAnnounce()` and the app's single polite live region. A
keypress that worked and one that was refused are the same experience for anybody not watching the
verdict panel, and for *everybody* using a screen reader. Three arms read the region back: a
recorded verdict, a refusal on a frozen run, and where a move landed.

Accessibility is part of the feature rather than a follow-up, so **every shortcut has a visible
focusable equivalent**:

| Key | Equivalent | New? |
|---|---|---|
| `p` `f` `b` `s` | The four verdict buttons | existing |
| `j` `k` | **Previous case** / **Next case** | **new** |
| `n` | The notes field, reachable by Tab | existing |
| `?` | **Keyboard shortcuts (?)** | **new** |

The Previous/Next pair is the one that had to be built: Focus mode has no queue rail at all and
Split drops the rail below `lg`, so without them "next case" would be keyboard-only on the
narrowest screen — the one most likely to be a tablet on a test bench. Each tooltip wraps a `Box`,
because MUI puts `pointer-events: none` on a disabled button and a tooltip wrapped directly around
one greys out and explains nothing — the trap `PermissionGate.tsx` documents and solves the same
way.

## A move is refused while there is unsaved work

`j` is one keystroke and this form holds four unsaved buffers. `ExecutionPage.tsx`'s own
`useUnsavedWork` comment says what losing them costs: *the marks on this screen are the only record
of what happened — losing them means running the test again, not retyping a title.*

This repo has **no shared confirm dialog**, and inventing one inside the smallest area of the plan
to guard one control was rejected. So the move is refused, with the sentence in the buttons'
tooltips (§31, disable-don't-hide) and in the live region. **That is a judgement this area could
not settle from a file**, and it is handed to the live pass rather than asserted — see `KB-9`
below.

## The test-coverage decision, and exactly what it closes of `ED-M8`

Master task 42 said *"extend `ExecutionPage.test.tsx`"*. **That file did not exist.** Neither did
one for `ExecutionQueue.tsx` or `executionSelection.ts`. The decision was made in the plan, in
writing, rather than by silently taking or skipping the gap:

| File | Decision | Why |
|---|---|---|
| `ExecutionPage.test.tsx` | **created** | The headline defect is only observable with the real form mounted — a pure test of `matchShortcut` cannot see the notes box. |
| `ExecutionQueue.test.tsx` | **not created** | F does not change the rail; it reads `orderedQueue`, which already has `queueOrder.test.ts`. |
| `executionSelection.test.ts` | **not created** | §245's module, on `RunDetailPage`, a screen F never touches. Writing it here would be absorbing area B's debt under area F's number. |

### What this closes of §244 `ED-M8`, and what it does not

`ED-M8` records that **nothing in `frontend/src` drives `useSubmitResult` at all.** That is no
longer true: `ExecutionPage.test.tsx` drives it, asserting the body that goes out (`statusId`, and
the preserved `comment`) and — in five separate arms — that **nothing** goes out when the screen
should refuse.

**Precisely what is closed:** `useSubmitResult` now has an automated caller, and the *keyboard's*
path into it is covered — the write, and the four refusals (frozen run, missing permission,
unreadable duration, typing in a text box).

**Precisely what is not:** every other property of that mutation remains unobserved. Nothing
asserts the `If-Match` header the hook derives from `lockVersion`; nothing drives the four **verdict
buttons**; nothing covers the duration control §244 built, the precondition checklist, the step
marks, the evidence or defect panels, the 409 conflict panel, or the suggested verdict. **`ED-M9`
is untouched** — the two sites that must send `durationMs` without showing it
(`ExecutionGrid.mark`, `BlockedFromGrid`) still have no automated observer, and this area added
none. Read `ED-M8` as *narrowed*, not as resolved, and do not delete it.

## The two rows that a green suite could never answer

Both are in `docs/verification/frontend-browser-checks.md`, lane `A3`, ids `§248 KB-1..KB-10`, none
run (§65):

- **`KB-9` needs a human judgement, not a verdict.** It drives the unsaved-work refusal and then
  asks the reader to *say whether the refusal is right*. One keystroke discarding a run's marks is
  the failure it prevents; being unable to skip a case without clearing it is the cost. If the pass
  says it is too strict, the answer is a confirm dialog — new shared machinery — and that is a
  decision for whoever reads the row, not for this area.
- **`KB-10` needs a real screen reader** (VoiceOver). Nothing in jsdom can tell you whether an
  announcement is *heard*, whether it is spoken twice, or whether `polite` actually waits. Until
  that row runs, the accessibility half of this feature is a claim rather than an observation, and
  it should be described that way.

`KB-1` is deliberately first in the lane: it is the focus guard, and if a single character of the
typed sentence triggers anything, the whole feature is the defect it was built to prevent.

**Nothing was added to `docs/verification/backend-mutation-checks.md`**, and that is stated in the
queue rather than left silent: area F is frontend-only, and the only write it can cause is
`PATCH …/executions/{id}` — the same request the verdict buttons already send, already covered by
`§244 ED-*` and the M2 rows. A mutation row here would be a second copy of an existing one.

## Files

**Created**
- `frontend/src/features/runs/executionKeys.ts` — the map, `isTypingTarget`, `matchShortcut`, `matchNotesShortcut`.
- `frontend/src/features/runs/executionKeys.test.ts` — 15 arms.
- `frontend/src/features/runs/ShortcutOverlay.tsx` — renders the map, no key literal of its own.
- `frontend/src/features/runs/ExecutionPage.test.tsx` — 17 arms; the repo's first test for this screen.
- `docs/plans/2026-08-18-gap-f-keyboard-driven-execution.md` — the stepped plan, ten tasks.

**Modified**
- `frontend/src/features/runs/ExecutionPage.tsx` — the dispatcher, the listener, the notes ref and
  its `onKeyDown`, the Previous/Next pair, the shortcuts button, the overlay, and `modeParam`
  threaded into `ExecutionForm` so a keyboard move keeps `?mode=`.
- `docs/verification/frontend-browser-checks.md` — `§248 KB-1..KB-10`.

## Commits

`98e35de` the plan · `782a93a` key map + guard arms · `12a1584` overlay · `e94290d` the first
`ExecutionPage.test.tsx` · `c77e1fd` verdict binding · `022bee1` next/previous · `6fe1f70` notes
key and Escape · `576fcf3` live-region arms · `cd6fb67` overlay wiring · `ab8ecc6` queue rows ·
`9e30b08` `inputRef` fix. Each is a state whose edits are complete and self-consistent; **none is a
state whose gate passed**, because no gate was run.

## What the next reader should check first

1. **Run the gates.** `npm run test:run`, `npm run build`, `npm run lint` on `shanks`. 32 arms have
   never executed and two of them (the announcer sentences, the overlay row count) assert exact
   strings and a derived length.
2. **`KB-1`, before anything else on this screen.**
3. Area A (§244) and area B (§245) are also ungated. A spot check of A's central claim held —
   `durationMs` is sent from all three call sites (`ExecutionGrid.tsx:181`,
   `ExecutionPage.tsx:271,464`) — but that is one claim of many, and this area verified none of B.
