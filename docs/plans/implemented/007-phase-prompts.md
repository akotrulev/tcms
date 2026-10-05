# 007 — Prompts for the remaining phases (T6–T17)

**Status (filed 2026-10-05):** Delivered — every prompt here (T6–T17) has run; T17 `91c4bd6a` gated green on shanks. This file is the prompt companion to [007-render-design-implementation.md](007-render-design-implementation.md), filed with it. Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Written 2026-08-29**, after T5 landed (`5a4d7226`). One prompt per task, grouped into waves. A
wave's tasks own disjoint files and may run at the same time; the next wave starts only after the
whole previous wave is on `master`.

**Gates run once, at the very end.** Per the user's directive of 2026-08-29, none of T6–T16 runs
`npm run build`, `npm run lint` or `npm run test:run`. The single gate pass — frontend, packages and
backend, on `shanks` — is **T17**, after all the work is done. Each prompt below says so.

## The rules that make a wave safe — read before starting two

`docs/specs/parallel-work-rules.md` records three silent losses of committed work from two agents in
**one** tree. So a parallel lane is a **separate `git worktree`**, never a second session in this
checkout:

```bash
git worktree add ../tcms-t9 -b lane/t9 master     # one per lane; delete after it lands
```

- **One writer per file.** The file lists below are the ownership map; a lane that needs a file
  outside its list stops and says so rather than editing it.
- **`frontend/package.json` / `package-lock.json` belong to one lane per wave** (named below).
- **No gates in any lane, and none between waves (user directive, 2026-08-29).** ~~Gates run
  locally inside the lane; one shanks pass runs after the wave is on `master`.~~ → superseded.
  T6–T16 do **not** run `npm run build`, `npm run lint` or `npm run test:run` at all; each lane
  writes its code and commits. The single gate pass is **T17**, on `shanks`, after every task has
  landed. **Do not run `~/bin/tcms-remote` from a lane** — it rsyncs *this* working tree to shanks,
  and two lanes doing that overwrite each other.
- **Landing:** each lane commits on its branch; land them onto `master` one at a time
  (`git rebase master` in the lane, then fast-forward).
- Every prompt below ends by ticking its checkboxes in `docs/plans/implemented/007-render-design-implementation.md`
  and recording anything the code disproved, struck through, the way T4 and T5 did.

---

## Wave 1 — `T6` ∥ `T9` (two lanes)

### T6 — Family 8, Threshold

```
Execute T6 of docs/plans/implemented/007-render-design-implementation.md — read the plan's §0–§4, D1–D5, the
T1–T5 "what landed" notes, and the T6 section first; then docs/specs/2026-08-28-design-language.md
and docs/specs/assets/renders/08-threshold.html with its tcms.css.

Port LoginPage, AcceptInvitationPage and SsoCallbackPage (frontend/src/features/auth/) from MUI to
components/ui + components/kit, matching 08-threshold.html: more identity than anywhere inside,
fewer controls than anywhere inside, the email deciding the sign-in method before a password is
asked for. Every data-testid stays byte-identical; the existing *.test.tsx files are the regression
net and are not edited to accommodate the port. Use /fe-react.

You own only frontend/src/features/auth/**. Do not touch package.json, AppShell, Providers, or any
other feature. If a primitive you need is missing from components/ui, say so in the report rather
than improvising one in the page.

Done when: grep -rn "@mui" frontend/src/features/auth returns nothing. Do NOT run npm run build,
npm run lint or npm run test:run — no gate runs in this task; the one gate pass is T17, on shanks,
after every task has landed. Commit only the auth files:
  feat(frontend): T6 — family 8, the threshold, ported to the kit
Then tick T6's checkboxes in the plan and note anything the code disproved.
```

### T9 — TanStack Table *(owns `package.json` this wave)*

```
Execute T9 of docs/plans/implemented/007-render-design-implementation.md — read the plan's §1, D5, the T3 "what
landed" note (the kit's Table exports), and the T9 section first; then the Table rule in
docs/specs/2026-08-28-design-language.md §5 and the case table in
docs/specs/assets/renders/01-index-tables.html.

npm install @tanstack/react-table in frontend/. Re-implement frontend/src/features/cases/CaseGrid.tsx
(336 lines) on TanStack Table with manualPagination / manualSorting / manualFiltering driving the
existing query hooks — server-side paging/sort/filter is how the grid behaves today and a
client-side load of the full table is the named anti-pattern. Keep the deterministic default sort
with its unique tiebreaker. Port the column definitions one-for-one; the one visual change is the
kit's rule — a quiet second line in the cell, never a twelfth column. Cells do not wrap; the region
scrolls (the design language's §9.5 finding). Extend components/kit/Table.tsx only if the grid
needs something it lacks. Every data-testid stays byte-identical; do not edit existing tests to
accommodate the port. Use /fe-react.

You own: frontend/src/features/cases/CaseGrid.tsx and its test, frontend/src/components/kit/Table.tsx
and kit tests, frontend/package.json, frontend/package-lock.json. Nothing else — CaseListPage.tsx
is T10's and T8's.

Done when: grep -rn "x-data-grid" frontend/src returns nothing. Do NOT run build, lint or
test:run — no gate runs in this task; T17 is the single gate pass. Commit:
  feat(frontend): T9 — CaseGrid on TanStack Table, x-data-grid unimported
Then tick T9's checkboxes in the plan.
```

---

## Wave 2 — `T8` alone

It edits six pages that belong to later families (the `useSnackbar` call sites), so nothing may run
beside it.

### T8 — Family 9, States, and the notification surface on sonner

```
Execute T8 of docs/plans/implemented/007-render-design-implementation.md — read §1 (the notistack row), the
task-table note on why T8 runs before T7, and the full T8 section first; then
docs/specs/2026-08-28-design-language.md §7 and docs/specs/assets/renders/09-states.html.

1. Port frontend/src/features/NotFoundPage.tsx and features/help/{HelpPage,RoleMatrix,prose,sections}.tsx
   to the kit. Help is the fourth document surface (§3): DocumentSurface at the prose measure.
2. Replace notistack with sonner, preserving the split Providers.tsx documents: success/info
   toasts auto-dismiss, failures never do and are raised only from app/queryClient.ts. Mount
   ui/sonner's <Toaster/> exactly where SnackbarProvider sits — the nesting order in Providers.tsx
   is load-bearing and documented in the file. Give the six pages (BoardPage:15, CaseListPage:4,
   CaseEditorPage:17, DocPageEditorPage:18, RunDetailPage:19, ExecutionPage:16) a
   useNotify-shaped success/info call in place of useSnackbar — change the import and the call
   only, nothing else in those files; they are ported by their own tasks. Drop the SnackbarProvider
   wrap from the six tests that mount it. Strike through the notistack mention in
   notifications/NotificationsProvider.tsx:23.
3. Add the never-auto-dismiss test from the plan (fake timers, 30s, alert still present).
Use /fe-react.

You own: features/NotFoundPage.tsx, features/help/**, notifications/**, app/Providers.tsx,
app/queryClient.ts, and — import + call only — the six pages and six tests named above.

Done when: grep -rn "notistack" frontend/src returns nothing. Do NOT run build, lint or test:run —
no gate runs in this task; T17 is the single gate pass. Commit:
  feat(frontend): T8 — family 9, the states, and one notification surface on sonner
Then tick T8's checkboxes in the plan and record anything the code disproved.
```

---

## Wave 3 — `T7` ∥ `T10` ∥ `T13` (three lanes)

Disjoint: T7 owns the editor, `CaseDocument`, `ConditionEditDialog` and one `AppShell` addition;
T10 owns the ten index pages; T13 owns board/org-chart and `ClassificationPicker`. `CaseEditorPage`
imports `ClassificationPicker` — T13 changes its internals, T7 its caller; the props contract must
not change, and T13 says so if it must.

### T7 — The case editor, both frames

```
Execute T7 of docs/plans/implemented/007-render-design-implementation.md — read §0 (the seven items), §4 (item
6 is deferred: expectedOutcome and description stay), D5, the T3 and T5 "what landed" notes, and
the full T7 section including Step 0 and the re-checked line references. Then the two specs it
names — docs/specs/2026-08-28-case-authoring-redesign.md and 2026-08-28-case-authoring-rail.md,
which win where they and the render disagree — and docs/specs/assets/case-authoring-redesign.html
frames A and B.

Step 0 first: a TopStripSlot in AppShell.tsx's <header> (context + portal target) so the page can
put its commit bar beside the breadcrumb; tested. That is the only AppShell change.
Then Steps 1–7: panel chrome off; the three starts as described cards with keyboard hints; the
blank-state scaffold with a ghost hint per keyword replacing the placeholder at CaseDocument.tsx:280;
every rail row a kit/RailRow (quiet at rest, helper text inside the opened control;
notAutomatableReason stays reachable and its refusal visible — the automation popover must not
close on a server refusal); 15px sans at 1.75 for step text with mono only for keyword, gutter
number and step data — change CaseDocument's SHARED block, both mirror halves read from it, and
the caret must not drift from the completion popup; the commit bar into the top-strip slot and the
PageHeader H1 gone; Notes loses its panel, keeps its two fields in the narrative column. Drop the
selectTestId import at CaseEditorPage.tsx:67 with the Selects it served. Add the three tests in
Step 8. The existing 524-line test must pass unedited except where it asserts a Panel title.
Use /fe-react.

You own: features/cases/CaseEditorPage.tsx, CaseDocument.tsx, ConditionEditDialog.tsx, their tests,
and app/AppShell.tsx (+test) for Step 0 only. Not ClassificationPicker (T13), not CaseGrid or
CaseListPage (T9/T10).

Done when: the steps above are written. Do NOT run build, lint or test:run — no gate runs in this
task; T17 is the single gate pass. Commit:
  feat(frontend): T7 — the case editor as the render draws it
Then tick T7's checkboxes and record the rail width answer for the rail spec's open question 1
at 1280px, as plan §5 asks.
```

### T10 — Family 1, Index & table

```
Execute T10 of docs/plans/implemented/007-render-design-implementation.md — read D5, the T3 and T9 "what
landed" notes, and the T10 section; then docs/specs/2026-08-28-design-language.md §5 (Table) and
§8, and docs/specs/assets/renders/01-index-tables.html.

Port the ten index screens onto kit/Table, one per step (no test run per screen — nothing is run here):
cases/CaseListPage, cases/CaseSearchPage, runs/RunListPage, plans/PlanListPage,
proposals/ProposalsPage, reports/ReportsPage, ingestion/IngestionsPage, runs/RunSchedulePage
(drops its lib/muiTestIds import), artifacts/ArtifactsPage, projects/ProjectsPage. The render's
rules: filters as removable Chips in one row under the toolbar; proportion before number
(ResultBar); an em dash for no value, never a zero; a count is a link to its rows and the URL
carries the filter — filter state stays in useSearchParams. CaseListPage's useSnackbar was already
replaced by T8; do not reintroduce it. Every data-testid byte-identical; existing tests unedited.
Use /fe-react. This is one of the two tasks the plan says to split if context runs short — split
at a screen boundary, commit what is written, and say which screens remain.

You own the ten page files and their tests only. Not CaseGrid.tsx (T9, landed), not the kit.

Done when: grep -rn "@mui" over those ten files returns nothing. Do NOT run build, lint or
test:run — no gate runs in this task; T17 is the single gate pass. Commit:
  feat(frontend): T10 — family 1, ten index screens on the kit's Table
Then tick T10's checkboxes.
```

### T13 — Family 4, Board & people

```
Execute T13 of docs/plans/implemented/007-render-design-implementation.md — read D5, the T3 "what landed"
note, §1 (x-tree-view is one file), and the T13 section; then
docs/specs/2026-08-28-design-language.md §5 and docs/specs/assets/renders/04-board-and-people.html.

Port board/BoardPage, board/MyWorkPage, board/TeamBoardPage and org-chart/OrgChartPage to the
kit. The board is the only drag surface, so WCAG 2.2's drag rule bites: every drag keeps a
single-pointer alternative; @dnd-kit stays. Replace cases/ClassificationPicker.tsx's TreeView
with a flat components/ui/command combobox — values are a flat vocabulary per dimension — keeping
its props contract unchanged (CaseEditorPage is being ported in another lane against the current
props; if the contract must change, stop and say so) and dropping its lib/muiTestIds import.
BoardPage's useSnackbar was already replaced by T8. data-testids byte-identical; existing tests
unedited. Use /fe-react.

You own: features/board/**, features/org-chart/**, features/cases/ClassificationPicker.tsx and
its test. Nothing else in features/cases.

Done when: grep -rn "x-tree-view" frontend/src returns nothing. Do NOT run build, lint or
test:run — no gate runs in this task; T17 is the single gate pass. Commit:
  feat(frontend): T13 — family 4, board and people; x-tree-view unimported
Then tick T13's checkboxes.
```

---

## Wave 4 — `T11` ∥ `T15` (two lanes)

### T11 — Family 2, Record as document

```
Execute T11 of docs/plans/implemented/007-render-design-implementation.md — read D1 (ReportView keeps its
light surface; do not "fix" it), D5, the T3 and T7 "what landed" notes, and the T11 section; then
docs/specs/2026-08-28-design-language.md §3 and §5 and
docs/specs/assets/renders/02-record-as-document.html.

Port the seven document screens onto kit/DocumentSurface and kit/Timeline at the 74ch measure,
one per step: cases/CaseDetailPage, runs/RunDetailPage (drops its lib/muiTestIds import; its
useSnackbar was replaced by T8), ingestion/IngestionJobPage, proposals/ProposalPage,
history/CaseHistoryPage, history/SuiteHistoryPage, reports/ReportViewPage. review/ReviewPanel.tsx
is reached from CaseDetail and drops its lib/muiTestIds import here. data-testids byte-identical;
existing tests unedited. Use /fe-react.

You own the seven page files, review/ReviewPanel.tsx, and their tests. Not ExecutionPage (T12),
not the settings tree (T15).

Done when: grep -rn "@mui" over those files returns nothing. Do NOT run build, lint or test:run —
no gate runs in this task; T17 is the single gate pass. Commit:
  feat(frontend): T11 — family 2, seven records as documents
Then tick T11's checkboxes.
```

### T15 — Families 6 & 7, Analytics and Settings

```
Execute T15 of docs/plans/implemented/007-render-design-implementation.md — read D5, the T3 note (Figure is
Tile/HBar/Sparkline), the T4 note (a refusal names the permission and the role — no person can be
named on this codebase), and the full T15 section with its thirteen-screen list and split points;
then docs/specs/2026-08-28-design-language.md §6 (computed, not judged: untested is the empty
track; Activity is three small multiples; no pie, donut or gauge; y from zero) and §5, and
docs/specs/assets/renders/06-analytics.html and 07-settings-admin.html.

Steps 1–2: dashboard/DashboardPage, plans/PlanStatisticsPage. Steps 3–8: settings/Review,
Classification, Notification, Tracker settings pages (+ NotificationChannelDialog),
admin/ProjectAccessPage, audit/AuditLogPage. Steps 9–13: admin/TeamsPage (+ TeamDialog,
TeamMembersDialog), admin/ApiTokensPage, admin/SsoProvidersPage (+ ProviderFormDialog),
account/AccountSecurityPage, account/TrackerConnectionsPage. Each of the six lib/muiTestIds
importers in this set drops its import. Settings rules: every setting states its blast radius
against live data; revoked and expired rows stay on screen, dimmed, with date and reason. Split
after step 2 and after step 8 if context runs short — commit what is written, say what remains.
data-testids byte-identical; existing tests unedited. Use /fe-react.

You own: features/dashboard/**, features/plans/PlanStatisticsPage*, features/settings/**,
features/admin/**, features/audit/**, features/account/**. Nothing under features/cases, runs,
docs or reports.

Done when: grep -rn "@mui" over those directories returns nothing. Do NOT run build, lint or
test:run — no gate runs in this task; T17 is the single gate pass. Commit per split:
  feat(frontend): T15 — families 6 and 7, analytics and settings
Then tick T15's checkboxes.
```

---

## Wave 5 — `T12` ∥ `T14` (two lanes)

### T12 — Family 3, Execution

```
Execute T12 of docs/plans/implemented/007-render-design-implementation.md — read D5, the T5 note (the rail
already collapses on /runs/:runId/executions/:executionId), the T11 note, and the T12 section;
then docs/specs/2026-08-28-design-language.md §4–§5 and docs/specs/assets/renders/03-execution.html.

Port runs/ExecutionPage.tsx: number keys for verdicts, the current step at full ink, "last time"
on screen before it is needed, and the failure panel in place of runs/BlockedDialog.tsx's dialog —
that file drops its lib/muiTestIds import AND the comment at :105 that cites
`lib/muiTestIds.ts` by path (packages/client/src/test/citedFiles.test.ts fails on a path not on
disk once T16 deletes it, and it reddens the packages job). ExecutionPage's useSnackbar was
replaced by T8. Add the keyboard-contract test from the plan. data-testids byte-identical;
existing tests unedited. Use /fe-react.

You own: features/runs/ExecutionPage.tsx, features/runs/BlockedDialog.tsx, their tests.

Done when: grep -rn "@mui\|muiTestIds" over those files returns nothing. Do NOT run build, lint
or test:run — no gate runs in this task; T17 is the single gate pass. Commit:
  feat(frontend): T12 — family 3, the execution runner
Then tick T12's checkboxes.
```

### T14 — Family 5, Authoring

```
Execute T14 of docs/plans/implemented/007-render-design-implementation.md — read D5, the T3 and T11 notes,
and the T14 section; then docs/specs/2026-08-28-design-language.md §3 (the 70ch prose measure)
and docs/specs/assets/renders/05-authoring.html.

Port docs/DocsPage, docs/DocPageEditorPage (its useSnackbar was replaced by T8) and
cases/FeatureImportPage at the 70ch prose measure. The three rules the render carries: coverage is
per paragraph, not per page; draft pages stay in the tree, dimmed; "Write one" sits on the
untested clause and opens the case editor pre-seeded with that paragraph as the objective. The
import shows the diff, not the count, never deletes, and its button counts what it will do.
data-testids byte-identical; existing tests unedited. Use /fe-react.

You own: features/docs/**, features/cases/FeatureImportPage.tsx and its test.

Done when: grep -rn "@mui" over those files returns nothing. Do NOT run build, lint or test:run —
no gate runs in this task; T17 is the single gate pass. Commit:
  feat(frontend): T14 — family 5, authoring at the prose measure
Then tick T14's checkboxes.
```

---

## Wave 6 — `T16` alone, then `T17` alone (sequential, in this checkout)

### T16 — MUI removed, preflight on

```
Execute T16 of docs/plans/implemented/007-render-design-implementation.md — read D2, D3 (both citations and
the ownership table), D4, the T5 note (useColorScheme is the last @mui import; the 64px collapsed
rail), plan §5, and the full T16 section.

Step 1 first: grep -rn "@mui\|@emotion\|notistack" frontend/src must be empty — a hit means a
screen task is unfinished; finish that, do not proceed around it. Then: delete lib/muiTestIds.ts
and app/theme.ts; fix the citation at components/ui/ui.test.tsx:20 (strike the path through, say
the file was deleted here); re-home the theme toggle that Providers.tsx's ThemeProvider +
CssBaseline and AppShell's useColorScheme carried onto the data-mui-color-scheme attribute (or
its renamed successor) BEFORE removing them — a no-op toggle typechecks; rewrite
themeTokenParity.test.ts as tokenSystem.test.ts with a header saying what it stopped checking and
why; turn preflight on; npm uninstall the eight packages; the layout damage preflight causes is fixed
in T17 (nothing is built here, so it cannot be seen here); update docs/specs/assets/renders/tcms.css:6-7 (no theme.ts), CLAUDE.md's
Frontend section (lines 210 and 219: MUI, "No Tailwind"), and strike §9.2 of the design language
through with "→ superseded" (T5 closed it). Decide the collapsed rail width (64 vs the render's
60) and say which.

Done when: grep -rn "muiTestIds" frontend/src is empty. Do NOT run build, lint or test:run — no
gate runs in this task; T17 is the single gate pass, and the layout damage preflight causes is
therefore found and fixed THERE, not here. Commit:
  feat(frontend): T16 — MUI removed, preflight on, one styling authority
Then tick T16's checkboxes.
```

### T17 — Gates on shanks and the bundle measurement

```
Execute T17 of docs/plans/implemented/007-render-design-implementation.md — read the T17 section and
docs/specs/shanks-runner-details.md first (BUILD FAILED with "Could not write XML test results"
and zero assertion failures is an fd-limit problem: ulimit -n 65535).

This is the FIRST build or test run since the last gated task (T9) — every per-task gate for T6–T16 was moved here by
the user's gates-run-once directive, so treat this as a fix-up task, not a formality: expect
accumulated type/lint/test failures plus the layout damage Tailwind's preflight causes once T16
turned it on. Fix in the order build → lint → tests and re-run until all four gates are green.

~/bin/tcms-remote sync, then frontend build / lint / test:run, packages npm run build (this is
where citedFiles.test.ts lives), and backend ./gradlew build (permissions.ts is a backend test
input). Measure the production bundle — total JS, largest chunk, CSS — against the 5933ca27
baseline; a regression is a finding, not a rounding error. Drive tcmsFaults.status(503,30),
hang(), expire(), drift() in a browser against a real backend; drift() must read as FAILED, not
empty. Write a Results section into the plan with the gate output, the bundle table and what the
browser pass found. Commit:
  fix: T17 — gates green on shanks, bundle measured
```
