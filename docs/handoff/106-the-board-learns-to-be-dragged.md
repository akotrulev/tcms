# §106 — The board learns to be dragged: one DnD library, a keyboard drag, and a write that can be taken back

**Date:** 2026-08-06 · **Lane:** frontend only · **Migrations:** none · **Contract:** unchanged

The ask was *"also we need drag and drop column change on the kanban board"*.

**Drag and drop was already there** — and finding that is what made this slice worth doing rather
than closing as done. It was the browser's **native HTML5 drag**: `draggable` on the card,
`onDragOver`/`onDrop` on the column, shipped in §53 and untouched since. It works on a desktop with
a mouse, and:

- **it does not work on touch at all.** Native HTML5 drag has no touch support — no polyfill, no
  partial behaviour, nothing. A board is exactly the screen somebody opens on a tablet in a
  stand-up, and there the cards simply could not be moved. Nothing on screen said so.
- **it had no keyboard path.** (The "→ column" buttons did, and still do — see below.)
- **it was a second drag-and-drop implementation** in a codebase that adopted `@dnd-kit` in §94.
  `ui-rules` §8's renderer divergence, in the one area where the two implementations shared not a
  single line: sensors, cancellation, the null drop and the accessible story were solved once in
  `features/cases/` and solved differently, or not at all, here.

So the ask is read as: make the gesture actually work, on the library this project already has.

## What changed

| File | Change |
|---|---|
| `features/board/cardMove.ts` | **new** — `resolveCardMove` + `refusalFor`: the four outcomes of a drop and the sentence a refusal is worded with |
| `features/board/cardMove.test.ts` | **new** — 9 tests |
| `features/board/boardKeyboard.ts` | **new** — `stepToAdjacentColumn` (pure) + `boardKeyboardCoordinates` (the `KeyboardSensor` getter) |
| `features/board/boardKeyboard.test.ts` | **new** — 7 tests |
| `features/board/BoardPage.tsx` | native HTML5 drag → `@dnd-kit`; `BoardColumn` extracted as the droppable; a drag handle; `DragOverlay` |
| `features/board/BoardPage.test.tsx` | two drag-driven tests rewritten, +3 for the write |
| `api/queries/workItems.ts` | `useMoveWorkItem` gains `onMutate` / `onError` / `onSettled` |
| `mocks/handlers/workItems.ts` | the `allowedTransitions` 409 the contract declares and this handler never answered |

**No contract change, no `gen:api`, no migration, no backend file touched.** `PATCH
/work-items/{id}` already exists, already declares both 409s, and already serves
`allowedTransitions` on every card — the endpoint this needed was built in §53.

## The library decision: `useDraggable` + `useDroppable`, not `SortableContext`

§94's step list is `SortableContext` + `useSortable`, and copying it here would be wrong. A step
drag is a **reorder within one list**; a card drag is a **move between containers**, and the board
has no within-column order to preserve (cards sort by overdue, then priority, then id — a
deterministic sort the user does not author). Sortable would have to invent a rank the model does
not have.

Two smaller consequences of that, each deliberate:

- **`pointerWithin`, not `closestCenter`.** The step list uses closest-centre because its targets
  are rows of similar size. A column is a big rectangle and a card is small: closest-centre picks
  the column whose *centre* is nearest, so releasing near the left edge of a wide column lands in
  its neighbour. For containers, "the pointer is inside it" is the rule the user believes they are
  following.
- **A `DragOverlay`.** With `useDraggable` alone the card stays put while an invisible drag happens
  and the gesture gives no feedback until release. The original stays and fades rather than being
  removed — pulling it out collapses the column under the pointer mid-drag.

**A handle, not a draggable card** — §94's argument one screen over. The card carries a link and up
to six buttons; making the whole card draggable turns every attempt to press one into a drag.

## The keyboard story, which is two paths and neither is optional

**The "→ column" buttons stay, and they remain the better path.** They were the non-drag route
before this slice and they are unchanged: one per legal target, disabled-with-a-reason for the
rest. Nothing about the drag replaces them.

**And the handle itself is operable**, because §94's rule is that a focusable handle which does
nothing on Space is worse than no handle. That needed new code: `@dnd-kit`'s two stock coordinate
getters do not fit a board. `sortableKeyboardCoordinates` requires a `SortableContext` this screen
does not have, and the default getter nudges **25 pixels per keypress** — across a four-column
board, forty presses to cross the screen, landing wherever the arithmetic stops. Both are
technically "keyboard accessible" and neither is usable.

So `boardKeyboardCoordinates` makes **←/→ jump a whole column**, which is the unit the gesture is
actually about, and leaves ↑/↓ on the stock nudge. It returns **null at either end** rather than
wrapping: a wrap moves a card four columns on one keypress.

The arithmetic is in `stepToAdjacentColumn`, pure and tested, for `resolveStepMove`'s recorded
reason — a jsdom keypress through the sensor exercises `@dnd-kit` rather than the decision. It
resolves the current column by **nearest centre rather than containment**, because mid-drag the
card is routinely over a gutter, over two columns, or over none.

## The null drop, in a pure helper

`resolveCardMove` has four outcomes and three of them are not moves — dropped outside every column,
dropped back where it started, dropped on an id that is not a column. §94's `move(i, i)` defect in
its board spelling, and here it is worse than a dirty form: it would be a **`PATCH` writing an
unchanged status**, taking a version, an audit row and a board refresh for a gesture that changed
nothing.

The fourth outcome is one the step list has no analogue for: **a legal refusal**. It is decided at
drop time and never sent — `ui-rules`, never render an action the backend will always reject. A
derived card goes further and is **not draggable at all** (`useDraggable({ disabled })`), because a
card that lifts and is refused on release is a gesture the product does not have; its refusal stays
discoverable on the disabled buttons' tooltips.

**The refusal sentences moved into that module too**, and that closed a live drift: the drop path
said *"TC-20 mirrors a review… update the review and the card follows"* while the button's tooltip
said *"This card follows its source"*. Two affordances, one rule, and they had already worded it
differently. One test in `cardMove.test.ts` exists only to hold the two equal.

## The write, and why the drag is what made it need work

`useMoveWorkItem` sent the `PATCH` and waited for the invalidation. That is fine behind a button —
a beat of latency reads as the button working. **A drag has a release**: a card that springs back
to where it came from and re-appears elsewhere 300ms later reads as the drop having *missed*, and
users repeat a drop that looks like it failed. That is how one intent becomes three writes.

So the move is now optimistic across **every board key** (the same card is on the project board, My
Work and a team board — `keys.myWork` already records what happens when only one is refreshed),
with `cancelQueries` first so a read already in flight cannot resolve after the write and undo it.

`onError` restores the **snapshot** rather than reversing the edit: between the drop and the failure
another write may have landed, and re-deriving "what it was" from the current cache would reinstate
a stale row for a card nobody touched. `onSettled` re-reads on both paths — success because a move
can change `overdue` and `allowedTransitions`, failure because the rolled-back cache is now a guess
about a server that has just refused something.

The **announcement** is the board's, not the hook's: a hook cannot know whether the failure is on
screen. On failure the board keeps its persistent warning (a toast about a card that has visibly
snapped back is gone before the eye returns to it) **and** announces — the snap-back is otherwise
completely silent to a screen-reader user, which is the one signal a sighted user gets for free.

**Double-submit** is covered by the shape rather than by a flag: a second drag of the same card
starts from the column the optimistic update already put it in, so it is a different move; and a
drop back where it started resolves to `NONE` before any request.

## The mock finding

`PATCH /work-items/{id}` declares **two** 409s — a derived id, and *"the requested column is not in
the card's `allowedTransitions`"*. The handler answered the first and **200** to the second, from
Phase 1 until now.

It never mattered because the illegal request was unreachable: the only way to move a card was a
button the board had already disabled. A drag makes it reachable the moment the client-side guard
is wrong, and a mock that accepts what the server refuses is a mock that certifies the bug (§52 —
handlers are written from the server's rules, not from what one client happens to send).

## Two testing findings worth the next author's time

**A `@dnd-kit` drag cannot be driven in jsdom.** Two existing tests used `fireEvent.dragStart` +
`drop` and asserted the refusal banner; those events mean nothing now. They were rewritten to read
the same sentence off the **disabled button's tooltip** — with the hover on the wrapping span,
because MUI sets `pointer-events: none` on a disabled control and a `Tooltip` wrapped straight
around one never opens. The drop path's copy of that sentence is pinned by `cardMove.test.ts`.

**A never-resolving query breaks RTL, not the product.** The rollback test was first written by
hanging the refetch so that `onError` was the only thing that could restore the card. It failed —
and the reason is that a permanently pending query leaves RTL's `act` wrapper waiting, so **no
state update after it is ever flushed**, including the banner. That cost an hour and is recorded in
the test file. The rollback is now asserted at the hook level with **no `useWorkItems` mounted**: an
invalidation with no observer fetches nothing, so the snapshot restore is the only thing that can
put the card back, and the assertion is synchronous. The obvious version of that test is vacuous —
`onSettled` refetches on failure too, and the mocked board answers with the card where it started.

## Gates

`npm run build` clean · `npm run lint` 0 · `npm run test:run` **976/976 across 72 files** (from
958/70 — 16 new tests in two new files, 3 net in `BoardPage.test.tsx`; no new `api.*` call site, so
§70's contract-hygiene checks generated nothing).

**Mutation-checked**: deleting `onMutate` reddens the optimistic test; deleting `onError` reddens
the cache-snapshot test and nothing else.

**Built and unverified** — 11 browser rows queued (M1-19…M1-29), **none run** (§65), plus 2 mutation
rows (14a, 14b). M1-20 (touch) and M1-21 (keyboard drag) are the two that carry the slice: they are
the capabilities that did not exist before it, and neither is observable from a desktop mouse.

## Found and not fixed

- **The server's half of the transition rule is unasserted as far as this slice can tell.** Mutation
  row 14b names it: if no `WorkItemEndpointTest` case pins the `allowedTransitions` 409, then the
  rule the client now leans on is enforced by a mock and a comment. Not verified either way from
  here.
- **No concurrency guard on rapid successive drags of different cards.** Each is its own mutation
  with its own snapshot; two overlapping failures would restore the older snapshot last. Rare, and
  the `onSettled` refetch corrects it — recorded rather than engineered around.
- **`Board` still receives its cards as a prop**, so the optimistic update is invisible to a
  `Board`-only test. That is why the write is tested through `BoardPage`; it is a testing cost of
  §83's decision, not a defect.
