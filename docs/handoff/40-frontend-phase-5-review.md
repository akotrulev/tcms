# §40. Frontend Phase 5 — the hostile self-review, and the four defects it found (2026-07-31)

Run through the **`fe-react`** skill, Phase 5: `fe-react-code-review` + `design-ui-code-audit` +
`design-ux-review`, plus the restraint check the phase gate names. Executed as **lane C** of a
second three-lane parallel run, in the worktree `tcms-lane-c` on `lane-c-phase5`. Frontend only —
no backend, no Docker, no contract.

The full findings table, with evidence per row, is
[`docs/audits/2026-07-31-frontend-phase-5-review.md`](../audits/2026-07-31-frontend-phase-5-review.md).
This section is what changed and what it means.

**Versions:** React 19.2 · Vite 8.1.5 · **no Next.js, no RSC**, so the reviewer checklist's entire
`'use client'` / RSC lane is not applicable here and nothing was deferred to E2E for that reason.

## What the review found

Four defects, all major, **all four found by driving the app in a real browser and none by reading
it**. Two of them had been on screen since Phase 2 and had survived three previous browser passes.
That is §6 lesson 5 for the fourth time, and it is now less a lesson than a property of this
codebase's verification: the gates prove it compiles, the browser proves it works, and the two find
disjoint sets of bugs.

1. **The screen-reader announcer was 1440×900 and put ~890 px of dead scrollable page under every
   screen in the app.** `AnnouncerProvider` hand-wrote a visually-hidden rule in MUI `sx`, where a
   number ≤ 1 on `width`/`height` means a **fraction** — so `height: 1` resolved to `100%`, not
   `1px`. `clipPath` kept it invisible, so the only symptom was a phantom scrollbar and a screen
   that would not stop scrolling. `index.css` has carried a correct `.sr-only` rule the whole time.
   **This is CLAUDE.md's shared-layer rule failing in the small** — a second implementation of
   something the shared layer already owns — and it is the best evidence that rule has produced.
2. **`setState` during another component's render, on every load of the case list.** The default
   sort is `keySeq,asc` and `keySeq` is not one of the grid's columns, so MUI X sanitised the
   unknown field out **during its own render** and echoed the correction back through
   `setSearchParams`. React logged
   `Cannot update a component (BrowserRouter) while rendering a different component (DataGridRaw)`
   every single time. Nothing else went wrong — the echoed value equalled the default, so the URL
   never changed — which is precisely why three browser passes walked past it. **A React error in
   the console with no visible symptom is still a defect**, and it is the kind that stops being
   harmless the moment concurrent rendering has a reason to replay the render.
3. **Fourteen of the board's twenty-four move buttons were disabled and not one could say why.**
   MUI puts `pointer-events: none` on a disabled control, so the Tooltip carrying the reason can
   never open. `PermissionGate`'s own header comment documents this exact trap and solves it with a
   span anchor; `BoardPage` had independently reinvented the broken version. This is §31's
   *disable, don't hide* failing in the only way that matters — a control that says **no** and will
   not say **why** is worse than an absent one, because the user cannot tell whether it is their
   permissions, their data, or a bug.
4. **`SuiteTree` silently dropped an orphan suite** — folder and every case inside it gone from the
   tree, with no empty state and no error. §36 pinned this as a *characterisation* test,
   "documented, not endorsed", and explicitly deferred the decision. **The decision is made here:
   orphans are surfaced at the root.** It arrives the first time that list is paginated, filtered,
   permission-scoped, or served mid-way through a parent's soft delete, and it arrives silently.
   Re-homing to the root is wrong about hierarchy and right about existence; being wrong visibly
   beats being wrong invisibly. The test is flipped to a requirement, and two were added — an orphan
   must not inflate a real root's rolled-up count, and a **cycle** must still be dropped without
   recursing forever.

## The restraint check — and yes, it changed something

Two accessories removed, both of them things a reader would have called fine:

- The suite panel **said "Suites" twice**, one directly above the other — the panel title and an
  overline repeating it. Obvious in a screenshot, invisible while writing either file.
- The footer carried **"Documentation" and "Contact support", both `href="#"`**. Neither destination
  exists. A link that goes nowhere costs a click, teaches the reader the footer is decorative, and
  sits in the tab order of every page. They come back when there is somewhere to point — and for a
  self-hosted product the support contact is per-deployment configuration, not a constant. The entry
  chunk fell 253.30 kB → 249.74 kB with them.

Neither is severe. Both are the kind of thing that only ever gets removed if somebody is explicitly
asked to remove one, which is the whole argument for the check being in the gate.

## Also done: §36's and §37's named follow-up

**`CaseSchema`'s four cross-field refinements are tested.** §37 said the follow-up was one word;
it was slightly more, because exporting a constant from a component module trips
`react(only-export-components)` and lint is a 0-warning gate — so the schema moved to
`features/cases/caseSchema.ts` rather than being exported from the page. **22 new tests**, covering
GHERKIN-without-a-scenario, STRUCTURED-with-zero-steps, NOT_AUTOMATABLE-without-a-reason and
AUTOMATED-without-a-key, each asserting the `path` as well as the verdict — the whole reason those
rules live in the schema is that the message must land on the field that caused it.

## Deliberately not fixed, and why

- **`refetchOnWindowFocus: false` globally** (`app/queryClient.ts`) — a `major` by the reviewer
  checklist's own table, and the only decision in that carefully-commented file with no written
  reason. A tab left open overnight shows yesterday's numbers. **Not flipped from a review lane**:
  it is a global behaviour change to a file two other lanes are building against, it interacts with
  the `staleTime: 30_000` above it, and it deserves its own decision and browser pass. → integration.
- **Multi-tab session broadcast** — deferred, and *sequenced behind* the item above rather than
  merely postponed: a sibling tab that would not revalidate on focus cannot act on a broadcast it
  receives. Fix the refetch policy first or the broadcast is decoration.
- **`useCases` has no `placeholderData: keepPreviousData`**, so a page or filter change drops to a
  skeleton over data already on screen — `src/api/queries/**`, another lane's file. → integration.
- **`useUnsavedWork` on the execution screen** — `features/runs/**` is lane A's, and lane A is
  rewriting it right now. → integration. **The board does *not* need it**: its writes are immediate
  single-field mutations with no unsaved buffer, so §5's "the execution screen and board should
  register too" is right about one of the two.
- **Two IA findings on the rail** — "Teams & roles" filed under the *Project* heading while
  navigating to the instance-wide `/admin/teams`, and the rail falling back to `projects[0]` off a
  project route so the switcher claims a project the user is not in. Both are in a file I own, and
  both were left because the fix is a nav-structure product decision, not an edit.

## Gate evidence

```
npm run build     → tsc -b + vite build clean; entry 249.74 kB / 78.87 kB gzip (was 253.30)
npm run lint      → oxlint, exit 0, no output — 0 warnings
npm run test:run  → Test Files 11 passed (11) · Tests 225 passed (225)   [203 + 22]
find "* 2.*"      → empty
```

Every fix re-verified in Chrome afterwards: `documentElement.scrollHeight` 1792 → 900 with the
announcer measuring 1×1; the case list's console clean with sorting still round-tripping through the
URL; the disabled board button showing *"This card follows its source, so it can't be moved to In
review"*; one "Suites"; a footer of one line. The resilience claims were re-driven with `tcmsFaults`
rather than repeated on faith — failed first load, hung endpoint, and a mid-session 401 over a dirty
new-case form whose title survived intact.

Two mutation checks, per §6 lesson 13: reverting `buildTree` fails 2 of `SuiteTree.test.tsx`'s 9,
and weakening the AUTOMATED refinement fails 3 of `CaseSchema.test.ts`'s 20. Both restored, `git
status` clean.

## Was Phase 5 worth running? — yes, and not for the reason the phase name suggests

The honest answer has two halves.

**The code-level pass on its own would not have justified the slice.** Reading ~40 files against the
reviewer checklist and the Vercel guidelines produced a comment that lied about behaviour, a
duplicated heading, and a missing `<meta name="theme-color">`. This codebase has been written
carefully, against those same skills, with the reasons in the files — a checklist re-read mostly
confirms what the files already say about themselves.

**The driven pass justified it three times over.** Four majors, every one of them invisible to `tsc`,
to oxlint, and to 203 passing unit tests, and two of them invisible to three previous humans clicking
through the app — because finding them needed *reading the console* and *hovering a control that was
already greyed out*, which is not what a person exercising a feature does. The three that were
already fixable were fixed in an afternoon.

The transferable rule is narrower than "run Phase 5": **an empty console is a gate, and hovering the
disabled thing is a test step.** Both are mechanical, both are cheap, and between them they found
three of this session's four defects.

The one place the review's *judgement* mattered more than its procedure was the orphan suite. §36 did
the right thing by pinning it and refusing to decide; a review pass is exactly where that deferred
decision gets made, and making it took ten minutes of arguing about which wrong answer is less wrong.
A findings list with no decisions in it would have handed that back untouched a third time.

## One lesson for §6

15. **A number ≤ 1 in MUI's `sx` is a fraction, not a pixel — and the general form of that is: do
    not reimplement a shared-layer rule in a styling DSL you have not read the transform for.**
    `height: 1` produced a full-viewport element that was invisible, typechecked, linted clean, and
    added ~890 px of dead scroll to every page in the product for two phases. The correct rule
    already existed twelve lines from where it was needed. Same family as lessons 9 and 13: the
    invariant needed *measuring* (`documentElement.scrollHeight` vs `body.scrollHeight`), not
    reading.
