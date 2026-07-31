# Frontend Phase 5 — hostile self-review

**Date:** 2026-07-31 · **Lane:** C of a three-lane parallel run, worktree `tcms-lane-c`, branch
`lane-c-phase5` · **Scope:** the frontend only; no backend, no Docker, no contract.

**Skills applied**, per the `fe-react` pipeline's Phase 5 row:

- `fe-react:fe-react-code-review` — the React/TS reviewer checklist.
- `fe-react:design-ui-code-audit` — the vendored Vercel Web Interface Guidelines, code level.
- `fe-react:design-ux-review` — Nielsen's ten as review questions, flow friction, IA.
- plus the restraint check ("remove one accessory") the phase gate names.

**Versions detected:** React 19.2 · Vite 8.1.5 · **no Next.js, no RSC**, so every RSC/`'use client'`
row of the reviewer checklist is not applicable and nothing here is deferred to E2E for that reason.
MUI v7 · React Router 7 · TanStack Query v5 · Vitest 4.1.10.

## How the review was run

Two passes, as `design-ux-review` requires: a code read of the ~40 files this lane owns, then a
**driven pass in real Chrome** against `npm run dev` in mock mode, via Playwright, at 1440×900 and
390×844, in light and dark. §6 lesson 5 is the whole argument for the second pass — *"the gates
prove it compiles, not that it works"* — and it held again: **every one of the four defects below
was found by driving the app, and none by reading it.** Two of the four had been on screen since
Phase 2 and had survived three previous browser passes.

The dev-only fault switch was used before any resilience claim was repeated: `tcmsFaults.status(503)`,
`.hang()`, `.expire()`, `.drift()`.

Primary user tasks walked: sign in → pick a project → filter and sort the case list → open a case →
edit it → the board → recover from a mid-session 401 with a half-typed form.

## Findings

Severities are the house scale. Every row is either **FIXED** in this slice or **DEFERRED** with the
reason and the owner.

| # | Sev | Location | Finding | Disposition |
|---|---|---|---|---|
| 1 | major | `src/a11y/AnnouncerProvider.tsx` | The "visually hidden" live region was **1440×900**, adding ~890 px of empty scrollable page below the footer on *every screen in the app*. | **FIXED** |
| 2 | major | `src/features/cases/CaseGrid.tsx` | `setState` during another component's render on every load of the case list — `Cannot update a component (BrowserRouter) while rendering a different component (DataGridRaw)`. | **FIXED** |
| 3 | major | `src/features/board/BoardPage.tsx` | 14 of 24 disabled move buttons; **not one could say why**. The Tooltip carrying the reason can never open on a disabled MUI control. | **FIXED** |
| 4 | major | `src/features/cases/SuiteTree.tsx` | An orphan suite is dropped silently — folder and all its cases vanish with no empty state and no error. Pinned in §36 as characterisation; the deferred decision is made here. | **FIXED** |
| 5 | minor | `src/features/cases/SuiteTree.tsx` + `CaseListPage.tsx` | "Suites" rendered twice, one above the other. Restraint check. | **FIXED** |
| 6 | minor | `src/app/AppShell.tsx` | Footer carried two links to `href="#"` — dead ends in the tab order of every page. Restraint check. | **FIXED** |
| 7 | minor | `src/features/cases/CaseEditorPage.tsx` | `CaseSchema`'s four cross-field refinements untested because the schema was module-private (§36's own gap, §37's named follow-up). | **FIXED** |
| 8 | minor | `src/features/cases/SuiteTree.tsx` | A comment claimed expansion state is "merged forward" on selection change. It is not, and never was. | **FIXED** (comment corrected to the truth) |
| 9 | major | `src/app/queryClient.ts` | `refetchOnWindowFocus: false` globally, with no recorded reason. | **DEFERRED — integration** |
| 10 | minor | `src/api/queries/cases.ts` | No `placeholderData: keepPreviousData`, so a page/filter change drops to a skeleton over data already on screen. | **DEFERRED — another lane's file** |
| 11 | minor | `src/features/runs/ExecutionPage.tsx` | `useUnsavedWork` is wired only to the case editor; the execution screen holds unsaved results too. | **DEFERRED — lane A owns this file** |
| 12 | minor | `src/app/AppShell.tsx` | "Teams & roles" sits under the **Project** nav heading but navigates to the instance-wide `/admin/teams`. | **DEFERRED — see below** |
| 13 | minor | `src/app/AppShell.tsx` | Off a project route the rail silently falls back to `projects[0]`, so the switcher claims a project the user is not in. | **DEFERRED — see below** |
| 14 | nit | `index.html` | No `<meta name="theme-color">`; the guidelines ask for one matching the page background. | **DEFERRED** |
| 15 | — | multi-tab session broadcast | Signing out in one tab does not update its siblings. | **DEFERRED — judged not worth this slice** |

### 1. The announcer occupied the whole viewport — major, FIXED

`document.documentElement.scrollHeight` was **1792 px** on a page whose `body` was 900. Walking the
DOM for elements overflowing the viewport returned exactly one offender:

```
{ testid: "Announcer", top: 892, bottom: 1792, h: 900, position: "absolute" }
```

The region is styled by hand in `sx`:

```ts
sx={{ position: 'absolute', width: 1, height: 1, margin: -1, clipPath: 'inset(50%)', … }}
```

**MUI's `sx` runs `width`/`height` through the sizing transform, where a number ≤ 1 means a
fraction.** `width: 1` and `height: 1` resolve to `100%`, and `margin: -1` to `-8px` of theme
spacing. The node was a full-viewport absolutely-positioned block. `clipPath` kept it invisible, so
the only symptom was **~890 px of dead white space and a phantom scrollbar on every page in the
product** — visible in every full-page screenshot taken this session, and, before this, in none of
the three previous browser passes' notes.

The interesting part is not the units. `index.css` already carries **one** `.sr-only` rule, correct,
with a comment explaining it. `AnnouncerProvider` reimplemented it. That is CLAUDE.md's shared-layer
rule failing in the small: *"a second implementation of any of these is how a hundred consistent
pages become a hundred slightly different ones."* The fix is `className="sr-only"` and the deletion
of the copy. After: `docH = 900`, announcer box `1×1`.

### 2. `setState` during render on the biggest screen in the product — major, FIXED

Every load of `/projects/:id/cases` logged:

```
Cannot update a component (`BrowserRouter`) while rendering a different component (`DataGridRaw`).
```

Cause: `DEFAULT_SORT` is `keySeq,asc` — the server's stable insertion order — and **`keySeq` is not
one of the grid's ten columns.** MUI X sanitises an unknown field out of `sortModel` during its own
render and echoes the correction through `onSortModelChange` → `onSortChange` → `setSearchParams`,
i.e. a router state update while a different component is rendering. Because the echoed value equals
the default, `setSort` deletes the parameter, the URL does not change, and there is no loop and no
visible symptom — which is exactly why it survived Phases 2, 3 and 4 and three browser passes.

Fixed by filtering `sortModel` to fields the grid actually has, so the model it is given equals the
model it would compute, plus a guard against emitting a sort it is already on. Verified afterwards:
the console is clean, and sorting still round-trips (`?sort=title,asc` → `title,desc` → cleared, with
the row order changing each time).

### 3. Fourteen disabled buttons that refuse to say why — major, FIXED

The board's move buttons are the keyboard alternative to dragging, and a derived card (one mirroring
an execution or a review) may not move freely — so the button is disabled and a Tooltip carries the
reason. Measured on the seeded project: **24 move buttons, 14 disabled, 0 tooltips.** Hovering any
disabled one produced nothing.

MUI sets `pointer-events: none` on a disabled control, so no hover event ever reaches a Tooltip
wrapped directly around it. MUI even warns about it in the console, which is the second thing the
error-free-console discipline bought here. `PermissionGate` documents this trap in its own header
comment and solves it with a span anchor; `BoardPage` had reinvented the broken version. It now uses
the same shape. Verified: hovering a disabled button shows *"This card follows its source, so it
can't be moved to In review"*, and the MUI warning is gone.

This is the §31 "disable, don't hide" contract failing in the only way that matters — a control that
says *no* and will not say *why* is worse than one that is absent, because the user cannot even tell
whether it is their permissions, their data, or a bug.

### 4. The orphan suite — major, FIXED, and a deferred decision made

§36 found that `buildTree` buckets by parent and walks from `null`, so a suite whose `parentId` names
a suite absent from the payload is never reached: it renders **nowhere**, with no empty state and no
error, and every case inside it is unreachable through the tree. It was pinned as a *characterisation*
test — "documented, not endorsed" — on the argument that the endpoint returns a whole project's suite
list, so the input cannot occur today.

Phase 5's judgement is that the deferral was right and the decision is not close. The condition
arrives the first time that list is paginated, filtered, permission-scoped, or served mid-way through
a parent's soft delete, and it arrives **silently**: a folder and its contents disappear with nothing
on screen to say so. Silent data loss is the one failure a navigation tree must not have.

Orphans are now re-homed to the root. That is wrong about hierarchy and right about existence, and
being wrong visibly beats being wrong invisibly. The characterisation test is flipped to a
requirement, and two tests were added: an orphan must not inflate a real root's rolled-up count, and
a **cycle** must still be dropped (every node in one has a parent that *is* present, so it is not an
orphan, and it must not become an infinite recursion now that unresolvable parents are re-homed).
Mutation-checked: reverting `buildTree` fails 2 of the 9 tests in that file.

### 5–6. The restraint check — minor, FIXED

Two accessories, both removed.

- **The suite panel said "Suites" twice** — the `Panel` title, then an overline repeating the word
  directly beneath it. Most visible in dark mode. The row survives because the "New" action does;
  the label does not.
- **The footer carried "Documentation" and "Contact support", both `href="#"`.** Neither destination
  exists. A link that goes nowhere costs a click, teaches the user the footer is decorative, and sits
  in the tab order of every page. They come back when they have somewhere to point — and for a
  self-hosted product the support contact is per-deployment configuration, not a constant to
  hard-code. The footer now carries the build and nothing else, and the entry chunk fell 253.30 kB →
  249.74 kB with it.

### 7. `CaseSchema`'s refinements — minor, FIXED

The follow-up §36 left and §37 reduced to one word. It turned out to be slightly more than one word:
exporting a constant from a component module trips `react(only-export-components)`, and lint is a
0-warning gate here, so the schema moved to `features/cases/caseSchema.ts` instead. **22 new tests**
covering all four cross-field rules — GHERKIN without a scenario, STRUCTURED with zero steps,
NOT_AUTOMATABLE without a reason, AUTOMATED without a key — each asserting the `path` as well as the
verdict, because the whole point of putting them in the schema was that the message lands on the
field that caused it. Mutation-checked: weakening the AUTOMATED refinement fails 3.

### 9. `refetchOnWindowFocus: false` — major, DEFERRED to integration

`app/queryClient.ts` sets it globally. The reviewer checklist calls this out by name: *"Stale-tab
restore revalidates — `refetchOnWindowFocus: false` set globally to quiet refetches"*, major. A tab
left open overnight shows yesterday's counts with no revalidation on return, and every other decision
in that file carries a written reason while this one does not.

**Not fixed here on purpose.** It is a global behaviour change to a file two other lanes are building
against mid-run, and it interacts with the `staleTime: 30_000` above it. It wants a decision (probably
`refetchOnWindowFocus: 'always'` for the read-heavy list screens, or simply removing the override) and
a browser pass of its own, not a drive-by flip from a review lane.

This also **subsumes finding 15**: multi-tab broadcast is worth less than this, because a sibling tab
that would not revalidate on focus anyway cannot act on the broadcast it receives. Sequence them.

### 12–13. IA findings on the rail — minor, DEFERRED

Both are `design-ux-review` label-predictability findings, both in `AppShell.tsx` (mine), and both
deferred deliberately because the *right* fix is a nav-structure decision rather than an edit:

- **"Teams & roles" sits under the "Project" heading and goes to `/admin/teams`**, which is
  instance-wide. The label does not predict its destination, and the grouping actively misleads — a
  user changing "Teams & roles" from inside Acme Online Banking has no reason to think they are
  leaving the project. It wants either its own "Administration" group or a project-scoped teams
  screen; that is a product call.
- **Off a project route the rail falls back to `projects[0]`.** On `/projects` the switcher displays
  "Acme Online Banking" and the rail renders that project's nav, while the page is a project *picker*
  — a you-are-here violation, and the same shape as the pre-existing `workItems.ts → projects[0]`
  fallback §37 recorded. The honest render is an empty switcher and no project nav.

## What the review confirmed still works

Repeated rather than assumed, with the fault switch, after the fixes:

| Claim | Evidence |
|---|---|
| Failed first load scopes to its region | `Board.Content.Error` + retry, rail/top bar/filters all still rendering, one notification |
| Failed load raises exactly one notification | 1, not one per region |
| Filters survive a failed load | search box, chips and suite tree all still mounted with their values |
| Hung endpoint eventually fails rather than spinning forever | loading at 3/10/20/35 s, **failed at ~50 s** — the timeout budget plus three backed-off retries. It ends; it takes longer than "the deadline" implies, noted below |
| Mid-session 401 recovers in place | `SessionExpired.Dialog` over a *dirty* new-case form, title still `"Half-typed title that must survive"`, URL unchanged at `/cases/new` |
| Dark mode is an authored palette | verified visually; `color-scheme: dark` set on `<html>` |
| No horizontal overflow at 390 px | `scrollWidth === innerWidth` on both the case list and the board |
| Keyboard focus lands in `<main>` after a client route change | first Tab continues from the breadcrumb inside `main`, as designed |
| Deep-linked `?suiteId=` opens its ancestors | three-level tree renders expanded to the selected leaf |

One qualification worth writing down: the effective deadline on a hung endpoint is the **per-request
timeout × four attempts** (~50 s), not the per-request timeout. That is defensible — a transient
fault deserves its retries — but "leaves loading at the deadline" in §5 reads as though it were the
former. Not a defect; a correction to the claim.

## Gate evidence

```
$ npm run build
> tsc -b && vite build
✓ 1710 modules transformed.
dist/assets/index-C8DkK9RX.js         249.74 kB │ gzip:  78.87 kB   (was 253.30)
dist/assets/CaseListPage-DZgB7IIV.js  535.29 kB │ gzip: 157.56 kB
✓ built in 350ms

$ npm run lint          # oxlint
(no output, exit 0 — 0 warnings)

$ npm run test:run
 Test Files  11 passed (11)
      Tests  225 passed (225)      (203 before: +22)

$ find . -name "* 2.*" -not -path "./.git/*" -not -path "*/node_modules/*"
(empty)
```

Two mutation checks, per §6 lesson 13 — a green test is not evidence until it has been seen go red:

| Mutation | Result |
|---|---|
| `buildTree`: restore `suite.parentId ?? null` | 2 failed in `SuiteTree.test.tsx`, both the new orphan assertions |
| `caseSchema`: `!!automationKey?.trim()` → `automationKey !== undefined` | 3 failed in `CaseSchema.test.ts` |

Both files were restored and `git status` checked clean afterwards.

## Lane discipline

Nothing was touched in `docs/specs/openapi.yaml`, `npm run gen:api`, `src/api/queries/**`,
`src/mocks/**`, `features/plans/**`, `features/runs/**`, `features/admin/**`, or `backend/`.
`useCan.ts` and `PermissionGate.tsx` were **read** and found correct; findings 3 and 12–13 reference
them without changing them. Findings 10 and 11 land in other lanes' files and are handed over rather
than fixed, per §37's rule that "the file another lane is asserting on" is ownership.
