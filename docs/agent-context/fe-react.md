# fe-react — orientation cache

**Baseline commit:** `b8d1c317` · **Last verified:** 2026-09-05 · **Verdict this run (018 T4):** DELTA
(T1–T3 of plan 018 moved `openapi.yaml`, both `schema.d.ts`/`schema.ts`, `api/types.ts`,
`test/fixtures.ts` and `settingsUpdate.ts`; no `package.json` / lockfile / `tsconfig` /
`vite.config.ts` change, so the version lane held. T4 is the first task to add a fault arm that
fabricates a **population rather than a failure**, and the first to gate a Company rail entry on a
non-credential key.)

**Session log**
- 2026-09-05 — 018 T4: `features/clients/` (`ClientsPage`, `ClientPage`, `ClientCreateDialog`,
  `ClientContactsPanel`, `BillingSlot`), `api/queries/clients.ts` and its keys, the `clients` fault
  arm, the two client status label maps, `/clients` + `/clients/:clientId`, and the gated Clients
  rail entry.

~~**Baseline commit:** `2cea9609` · **Verdict (017 T6):** DELTA~~
- 2026-09-04 — 017 T6: the timesheet's fourth KPI tile and My Work's Capacity + meter, both reading
  `useMyCapacity` over the loaded period's bounds and both in their own nested region; the Staffing
  section of `docs/features.md`; plan 009 closed for M7 (`44f8d39a`, `fe75d161`).

~~**Baseline commit:** `f4b0992d` · **Verdict (017 T5):** DELTA~~
- 2026-09-04 — 017 T5: `/projects/:projectId/staffing`, `/directory/:userId` and its Profile, Skills
  and Absence panels, `AllocationDialog` (the inline 409), `AllocationRow`, `staffingWindow.ts`, and
  the project rail entry. Fixed the window missing from `keys.staffing.absences` and turned
  `PersonCard`'s disabled `Profile` into a link — see the four rows below (`21ff78d6`).

~~**Baseline commit:** `ecc71e7d` · **Verdict (017 T4):** DELTA~~
- 2026-09-04 — 017 T4: `/staffing` and `/directory`, the staffing query module and keys, the
  `staffing` fault arm, `capacityCopy.ts`, the absence labels, one irregular plural, and the two
  Company rail entries. Took a `DirectoryFilters` type into `api/types.ts` — see the row below
  (`ecc71e7d`).

~~**Baseline commit:** `a2bf3b72` · **Verdict (016 T5):** DELTA~~
- 2026-09-04 — 016 T5: `TimePanels.tsx`, the `Logged` detail row, the work table's `Logged` column
  and bulk rollup read, My Work's This-period panel, General settings' Time panel, `formatHours`,
  and the module close-out in 009. **Took a contract + backend slice inside an `fe` task** — see the
  `trackTime` row below (`a2bf3b72`).

~~**Baseline commit:** `90667f1e` · **Verdict (016 T4):** DELTA~~
- 2026-09-04 — 016 T4: `/timesheets` and `/timesheets/approvals`, the time query module, D-b/D-d's
  labels, the `time` fault arm, `periods.ts`, and the rail entry. Found `CaseGrid.test.tsx` red on a
  clean `90667f1e` — two selection tests, not this task's (`90667f1e`).

~~**Baseline commit:** `306a36c5` · **Last verified:** 2026-09-04 · **Verdict (015 T5):** DELTA (014 M4's import screens and 015 T1–T4's contract had moved `openapi.yaml`, `schema.d.ts`, `fixtures.ts` and `devFaults.ts`; no version-lane change. T5 added the account, repositories and assistant-panel seams)~~
- 2026-09-04 — 015 T5: assistant profile + connect, project repositories, the AI-eligible marker on
  three surfaces, the task page's agent-context and pull-request panels, D33's close gate on General
  settings, the help entry. Also repaired six suites 013 had left red (`306a36c5`).

~~**Baseline commit:** `71d7fce1` · **Last verified:** 2026-09-01 · **Verdict:** DELTA (013 T1–T4 moved the contract, `schema.d.ts`, `labels.ts`, `router.tsx`, `AppShell.tsx`, `index.css`, `tokenSystem.test.ts` and the board files; 012 T9 had landed the board-settings screen and the label palette. No `package.json` / lockfile / `tsconfig` change, so the version lane held. Four sections refreshed, and the seams below moved because T5 built the release screens)~~

Per `ep-agent-orientation-cache`: validate this against a git delta before trusting it. A changed
`frontend/package.json`, `package-lock.json`, `tsconfig*.json` or `vite.config.ts` invalidates the
version lane and everything keyed to it.

## Version lane

| | |
|---|---|
| Render target | **Vite 8 SPA** — no Next.js, no RSC, **no `'use client'` boundary exists** |
| React | 19.2 |
| TypeScript | ~6.0, strict |
| Package manager | **npm** (`package-lock.json`) |
| Compiler | off |
| Routing | React Router 7 (`react-router-dom`), one flat `<Route>` list in `src/app/router.tsx`, every page `lazy()` |

The RSC lane of every skill **does not apply here**. A skill's `'use client'` guidance is a no-op;
do not add the directive and do not report its absence as a finding.

## Build, run & test

```bash
cd frontend
npm run dev        # :5173, proxies /api to :8080 — REQUIRES the backend, there is no mock lane
npm run build      # THE gate: tsc -b + vite build. NOT `tsc --noEmit` — it has reported 0 errors
                   # while the build failed with nine; `tsc -b` uses the stricter config
npm run lint       # oxlint at 0 warnings — a gate, not advisory
npm run test:run   # Vitest + RTL, full suite; test:smoke is what CI runs per push
npm run gen:api    # regenerate src/api/schema.d.ts after ANY openapi.yaml change
```

**Gates run on `shanks` via `~/bin/tcms-remote`, not locally** — standing user directive in
CLAUDE.md. Targeted tests may run locally for debugging.

## Layout

`frontend/src/` — `app/` (shell, router, providers, theme, queryClient) · `components/` (shared) ·
`features/<domain>/` (20 domains, 44 `*Page.tsx`) · `api/` (client + `queries/` barrel) · `lib/`
(display logic) · `auth/` · `notifications/` · `a11y/` · `test/` (fixtures, harness).

148 non-test `.tsx`, 100 test files, ~86k LOC.

## Seams to reuse

| Seam | Where |
|---|---|
| Server state | **TanStack Query owns all of it.** `src/api/queries/`, one file per domain behind a barrel. **One** `keys` factory in `keys.ts` — never add a second. `tracker.ts` is the **external** Jira/ADO integration; the product's own issues are `issues.ts` |
| The FOUR boards, and two of the files are one letter apart | `board.ts` is the **composite** board — `GET /board`, four card kinds, columns assembled from the cards by `cardMove.columnsFor` — serving the project, My Work and team boards (~~`workItems.ts`~~, renamed in 011 D-a). **`projectBoards.ts`** is 012 M2's **project** board: `GET /boards/{id}` + `/cards`, issues only, columns are `board_column` rows. ~~`boards.ts`~~ → the long name is the point of the name. `keys.workItems` → **`keys.compositeBoard`** (012 T6) |
| The project board's key roots | `['projects', p, 'boards']` (the picker) · `['boards', id]` (**configuration**) · **`['board-cards', id, filters]`** with `keys.boardCardsRoot = ['board-cards']` · `['projects', p, 'backlog', filters]` with `backlogRoot` · `['projects', p, 'labels']`. Cards are **NOT** under `['boards', id]` and 012 D-u is why: prefix matching means a column edit would evict the cards, and sweeping every board's cards would evict every board's configuration |
| The one optimistic mutation in the product | `useRankIssue` in `projectBoards.ts` — patch, snapshot, rollback, and `rebalanced: true` **drops** the patch rather than rolling it back. `useMoveCard` beside it is deliberately **not** optimistic (scope §12.4 verbatim). The asymmetry is information, not preference: a status change's legality is on the card, a rank change's is the server's neighbours (012 D-c) |
| The move decision, two boards, one file | `features/board/cardMove.ts` — `columnsFor` + `resolveCardMove` + `refusalFor` + `immobileReason` for the composite board; `serverColumnsOf` + `resolveColumnMove` + `columnRefusalFor` at the **foot** of the file for the project board. `serverColumnsOf` exists because `statusIds: readonly string[]` and a status *name* is a string too, so the wrong map typechecks and then refuses every legal drop (012 D-x) |
| Keyboard drag | `features/board/boardKeyboard.ts` — `boardKeyboardCoordinates(columnOrder, laneOrder = [])`. Left/right steps a column; up/down steps a **swimlane** when `laneOrder` is non-empty and otherwise falls through to `@dnd-kit`'s 25px nudge. One `stepAlongAxis` behind both, so the nearest-centre and edge rules exist once. ~~`ColumnRect`~~ → `BandRect` |
| The board's filter | **`BoardFilters` in `api/types.ts`, and it is NOT `IssueFilters`** — they differ in both directions (`statusId`/`parentKey` vs `labelId`/`priority`), so neither widens to cover the other (012 D-v) |
| **One filter, three views, one file** | **`features/board/useWorkFilter.ts`** (012 T8) — reads the URL once and returns `{ filters: { issue, board }, setParam, appendOnce, dropOne, clearFilters }`. `filters.issue` is what `GET /issues` declares, `filters.board` what `/boards/{id}/cards` and `/projects/{id}/backlog` declare; the mapping is D-v's and is **not** a cast. `clearFilters` keeps `view`, `board`, `sort`, `cols` and — since 013 T5 — **`month`**, none of them a filter. **TWO chip hooks live beside it, one per parameter set**: `useBoardFilterChips` (board, backlog) and `useIssueFilterChips` (table, calendar, added in T5 when the calendar became the second `IssueFilters` reader). Hooks rather than functions because some labels are names they look up. ~~`WorkTablePage` keeps its own chip list~~ → it lost it to the second hook, and `useProjectMembers` went with it: the chips were that read's only consumer |
| The two picker sets are NOT the same | Table offers **Level + Status**; board and backlog offer **Label + Assignee + Priority**. `useWorkFilter` unifies how the URL is *read*, never what a view *offers*: a status picker on a board would empty the columns it is drawn from, and the table has no label column in M1. Every view chips **every filter its own endpoint applies**, picker or not — which is how T8 found `epicKey`/`parentKey` had neither on the table |
| Client state | Session in Context; **filter state lives in the URL** (`useSearchParams`) |
| Component library | **shadcn/ui over Radix** in `components/ui/` (18 files) + the house kit in `components/kit/` (16). ~~MUI v9~~ → gone in T16; `@mui/*`, `@emotion/*`, `notistack` are in no import and no `package.json` |
| Styling authority | **Tailwind v4, single authority.** Preflight on, one stylesheet (`src/index.css`), no `sx`, no CSS-in-JS, no CSS Modules. Tokens are custom properties bridged into utility names by `@theme inline` (*inline* is load-bearing — it emits `var(…)` so the `[data-color-scheme]` swap moves every utility). `app/tokenSystem.test.ts` checks the system against itself |
| Lines & washes | `border-line` / `border-line-strong` / `bg-line` / `bg-wash-1..3` / `bg-rail` — added in the audit-fix pass to replace 277 `white/N` alpha literals the T16 port left. **A raw `-white/` alpha in `src/` now fails `tokenSystem.test.ts`**; use these |
| Session token | **In memory only** — `auth/accessToken.ts`, never `localStorage`. A reload signs the user out (no refresh endpoint until backend M4). Cross-tab sign-in carries the token on `sessionChannel`'s `signed-in` message |
| Provider order | `app/Providers.tsx` — **nesting order is load-bearing and documented in the file**: the query client is constructed *innermost* because its global error handlers consume the notification and session-recovery providers |
| Errors | `lib/describeError.ts` is the one place an error becomes user-facing copy (owns `isTransient()`); `app/queryClient.ts` is the one place a failure raises a notification; `components/ErrorBoundaries.tsx` has the root/route/region tiers |
| Data states | `components/DataRegion.tsx` — loading/loaded/failed/empty/stale |
| Display logic | **Shared layer, never the page**: `lib/labels.ts` (each `satisfies Record<Union, string>`, so a new enum member is a compile error until labeled), `lib/format.ts`, `lib/results.ts`, `lib/permissions.ts`, `components/Identifier` for anything monospace |
| Two `lib/format.ts` traps 013 T5 paid for | **`formatAge` cannot answer a future date** — it reads a negative interval as *"just now"*, so a release target rendered as `target 18 Sep 2026 · just now`. **`formatDueIn`** is the one for a deadline (`in 17 days` / `today` / `3 days ago`), and it counts whole UTC days so "tomorrow" does not become "in 0 days" at eleven at night. And `pluralNoun` gained a consonant-`y` → `ies` rule: the readiness tile read **"4 storys"** until it did |
| `TypeIcon` takes name + level, and `countsTowardCompletion` is OPTIONAL | `Pick<IssueTypeRef, 'name' \| 'level'> & { countsTowardCompletion?: boolean }`. Widened in T7 off the whole `IssueTypeRef` (`BoardIssueCard` has no type id, and a synthesised one is a value the component never read); widened again in T8 to make the field optional, because **`BacklogRow` does not carry it at all**. The spike test is `=== false`, never `!` — absent means the payload does not answer, so a backlog Spike renders as the level's green `S` rather than the board's amber `?`. `iconCode` is on both payloads and deliberately unused |
| ~~`label-1` … `label-10` DO NOT EXIST~~ | → **they exist**, added by 012 T9 with the board-settings screen, exactly where that row predicted the decision belonged. Both palettes declare all ten, `lib/statusColors.ts::LABEL_COLORS` is the list, and `app/tokenSystem.test.ts` now **reads that list out of the module** and fails if a slot is in one file and not the other |
| `data-testid` | ~1470 (676 literal + 794 composed), `Domain.Thing` PascalCase, per-file `TestIdPrefix` const. `ClassificationPicker.tsx:150` documents `Classification.Value.<id>` as a **locator contract QA page objects key on** — do not rename it here |
| Field ↔ error wiring | `components/kit/Field.tsx` clones its child to add `aria-describedby` pointing at `${htmlFor}-message`. Callers pass nothing; do not re-add per-call-site ids |
| Dev fault switches | `lib/devFaults.ts` behind `src/api/client.ts`; `tcmsFaults.{status,hang,expire,drift,only,tracker,issue,rank,wip,release,off}()` in the console. Both guards are a **literal** `import.meta.env.DEV` so Rollup drops the module — routing that check through an imported constant ships it. `issue('conflict')` fabricates the 409 that names the legal moves (and since 012 T6 answers the board's move endpoint too, which its own comment had always promised); `rank('conflict')` is the 409 that proves the rank rollback, `rank('rebalance')` the 200 that must **drop** the patch, and `wip()` the breach on a move that still lands. `release('untested')` answers readiness with `hasRun: false` and **null** counts — the state no seeded instance can reach once anything has run, and the one a `?? 0` turns into "nothing failed"; `release('empty')` answers the roadmap `{ releases: [], unscheduled: [] }`, which is **merge day** and an acceptance criterion rather than an edge case. Every arm's path test is **anchored** — an unanchored `startsWith('/issues')` would fault the detail read, and `/releases/{id}` vs `/releases/{id}/readiness` is the same collision one module over |
| Issue screens | `features/tracker/` — `WorkTablePage` (`/projects/:id/work`, five views on `?view=`), `IssueNewPage` (`/work/new`), `IssueDetailPage` (**`/issues/:key`, outside the project segment**, dispatching on `type.level` to `EpicPanels`/`StoryPanels`/`TaskPanels`). `TypeIcon` derives its colour from `level` + `countsTowardCompletion`, never from the type's name |
| The rail's Releases entry | `AppShell.tsx`, in the **Execution** group immediately after Work — `settings-boards.html`'s neighbour order from the mockup rail. **Ungated**, like every item in that rail. The Timeline and Calendar views are deliberately NOT rail entries: they are `?view=` arms of Work (D34) |
| Project front door | `/projects/:projectId` is **`features/projects/ProjectOverviewPage.tsx`** (011 T7). ~~`features/dashboard/DashboardPage.tsx`~~ no longer exports a page — it exports `DashboardBody` and `HealthPanel`, the repository-dashboard **regions**, which the overview mounts below its delivery half. **The file keeps its name deliberately**: `DashboardPage.tsx` is cited from `lib/statusColors.ts`, from `HealthTileOrderTest.java` in the backend suite, and from `renders/06-analytics.html` |
| The project board's screen | **`features/board/ProjectBoardView.tsx`** (012 T7) — five files beside the composite board's, not a directory of their own, because `cardMove.ts` and `boardKeyboard.ts` are shared: `ProjectBoardView` (picker · grouping · `DndContext` · three nested `DataRegion`s) · `Swimlane` (+ a private `LaneCell`) · `BoardCardFace` · `ColumnHeader` · `MoveToMenu`. Mounted as the `board` arm of `WorkTablePage`'s view switch, **not routed**: `?view=board` under `/projects/:id/work` |
| The droppable is a CELL, and this is where the plan was wrong | `` `${columnId}\|${laneId}` ``, spelled ONLY in `ProjectBoardView` (`cellId` / `columnIdOf`). A column has one rect per lane once a full-width lane header sits between the bands, so it cannot be one droppable — the plan's *"register `column.id`"* holds only for a one-lane board (012 D-z). **Both keyboard axes come out of that one flat cell set**: `columnOrder` = the first EXPANDED lane's cells (x is the same in every lane), `laneOrder` = the first column's cell down the expanded lanes (y). A collapsed lane registers nothing and is a stop on neither |
| Column headers are drawn ONCE | Above the lanes, not inside each — two CSS grids on one `--board-columns` template, aligned only because the columns are a fixed **272px**. Repeating them per lane would repeat one project-wide `breaches` entry per lane and put a WIP numerator beside a *lane's* card count (D-z) |
| The WIP cell's two numerators | `ColumnHeader`: no limit → `—`; **breach** → `breach.count / breach.limit ⚠` **as sent** (project-wide, does not shrink under a filter); **under** → cards-drawn `/ limit`, because `breaches` lists only the columns that are over and there is no project-wide count on the wire for the rest. Each state carries a `title` saying which it is. **No `>` in the file** — the comparison is the server's |
| Empty is a state only under a FILTER | **The board's rule; the backlog ports the reason, not the mechanism.** A board with no cards and no filter still draws its columns — that is the board's shape and what a freshly configured one looks like. A *list's* shape IS its rows, so `BacklogView` gives both empties a region and branches the **copy**: `no-filter-match` with the way out when a filter emptied it, `no-records` saying the backlog is finished when nothing did. The filter strip and the panel head sit **above** the region on both, so a failed read never unmounts the controls that would let a reader retry |
| The backlog's screen | **`features/board/BacklogView.tsx`** (012 T8) — one file, two module-private components (`BacklogListRow`, `BulkBar`) and a `MoveButton`. Mounted as the `backlog` arm of `WorkTablePage`'s view switch, **not routed**: `?view=backlog`. **No release sections and no "Move to release"** (D-f) — `release` is M3's table, so the grouping would be one section called "Unscheduled" containing everything |
| The release module | **`features/releases/`** (013 T5) — `ReleaseListPage` (`/projects/:id/releases`) · `ReleaseNewPage` (`/releases/new`) · `ReleaseDetailPage` (`/releases/:releaseId`) · `ReadinessPanels` (the tiles, blockers, epics, evidence) · `RoadmapView` and `CalendarView`, which are the `?view=timeline` / `?view=calendar` **arms of `WorkTablePage`** and not routes · plus two pure modules, `timelineScale.ts` and `releaseBadge.ts`. `api/queries/releases.ts` holds all eleven hooks |
| The release keys, and `readiness` is the OPPOSITE of the board's decision | `['projects', p, 'releases']` (the list) · `['releases', id]` (detail) · **`['releases', id, 'readiness']`** · `['releases']` as `keys.releaseRoot` · `['projects', p, 'roadmap']` · `['issues', key, 'test-cases']` · `['projects', p, 'case-verifications']`. Readiness sits **under** the release's prefix on purpose: it has exactly one parent and changes for the same reasons the release does, so prefix eviction is wanted — unlike the board's cards, which were kept out of `['boards', id]` because a *column edit* would have evicted them. **Every release write invalidates the list AND the roadmap** (`invalidateReleaseReaders`), because they are two renderings of the same rows |
| `keys.releaseRoot` exists for one writer | `PUT /issues/{key}/test-cases` changes a release's verdict **without naming a release** — the payload is a story's cases — so `['releases']` is the only correct sweep available |
| The badge's vocabulary DIVERGES from `releases.html`, deliberately | **`features/releases/releaseBadge.ts`** — `releaseBadge(status, verdict)` returns Released / Ready / Not tested / Not ready. The mockup's Readiness column mixes a status a person set with a verdict the server computed, so the same badge would mean "nobody started" on one row and "somebody did, and it failed" on the next (013 D-e). `doneButFailing` and `blockerVerdict` are in **that** file rather than beside the components, because oxlint's `react(only-export-components)` forbids a module exporting both |
| `hasRun` gates, a count never does | `ReleaseTests.passed/failed/blocked/untested/withResult` are **null** when `hasRun` is false, and the list's Tests cell renders the literal words **`no run yet`** while the detail page renders the *"If nothing had run"* panel. A `?? 0` appears only *inside* a `hasRun` branch. `tcmsFaults.release('untested')` is what makes the state reachable |
| The roadmap's arithmetic is a pure module | **`timelineScale.ts`** — `spanOf` / `spanOfEpic` / `windowOf` / `monthsIn` / `todayAt`, with its own `.test.ts` and no `render()`. A release has a target and no start, so its bar is `min(epic.startDate) … targetDate`, a **point marker** when no epic under it has a start, and **`null`** when it has no target (`2.10`'s row). Month labels are **`en-US`**, not the app's `en-GB`, because `en-GB`'s short September is `Sept` |
| The roadmap has NO filter strip, and that is not an omission | `GET /projects/{id}/roadmap` declares no filter parameters, so a strip would narrow nothing — worse than none. `CalendarView` beside it **does** carry one, because `GET /issues` takes the filter |
| Both reorder affordances end in ONE expression | `BacklogView`'s `place(fromIndex, toIndex)`: `toIndex < fromIndex ? { beforeKey } : { afterKey }` over the row at `toIndex`. The `↑ ↓` buttons and `onDragEnd` both call it, so a drag and an arrow cannot disagree about where a row lands. **There is no `KeyboardSensor`** and that is the design (D-ab): the arrows are the keyboard path, not a fallback |
| Bulk writes over issues are N calls | **`useBulkUpdateIssues(projectId)`** in `api/queries/issues.ts` — `Promise.allSettled` over one `PATCH /issues/{key}` each, returning `{ applied, failed }` and **never rejecting**, because a mixed outcome is ordinary. There is no bulk endpoint over issues (`/cases/bulk` and `/executions/bulk` exist and are not it). The caller names the failures and keeps them selected |
| `Set epic` is refused over a TASK before the request | The contract's depth rule is 400 for *"a parent more than one level above the child"*, so a Task's parent is a Story. `typeLevel` is on every backlog row, so the picker is disabled with that sentence rather than rendered and rejected |
| A `PermissionGate` cannot wrap a list | Its denied branch renders the child inside a tooltip-triggering `<span role="none">` — right around one control, and around an `<ol>` both invalid HTML and a tooltip over every row. `BacklogView` reads `useCan(projectId).can('board:rank')` directly and passes the verdict down as two props. **`ProjectBoardView` has the same shape** around `lanes.map(...)` and was deliberately left alone |
| The three boards | `BoardPage` and `TeamBoardPage` mount `Board` (kanban). **`MyWorkPage` does not** — `my-work.html` is two tables over ONE `GET /board?mine=true`, split on `kind === 'ISSUE'`, plus a private notes panel (011 T7). `linkFor` lives in `features/board/cardLink.ts`, not in `BoardPage.tsx`: a file exporting a component and a function is oxlint's `react(only-export-components)`, and the gate is zero warnings |
| Project settings | `/projects/:projectId/settings/{general,workflow,classification,review,notifications,trackers}` — six screens, all **routed for everybody** and read-only for a caller without the write key (§31). `general` holds the archive button (011 T7 moved it off the overview's header, with `ArchiveProject.test.tsx`) |
| A DELETE with a body | **`api.delWithBody(path, body, options)`** — the one in the product, for the workflow status removal. Registered in `test/contractDoubles.ts`'s `METHODS` **and** `SENDS_A_BODY`; a body smuggled into `api.del`'s options is invisible to that seam, which reads the second argument positionally |
| Empty states | `DataRegion`'s `EmptyState` takes `kind` / `title` / `body` / `action` and, since 011 T7, an optional lucide **`icon`**. `kit/StateBlock`'s `empty` is the other one and has a fixed description — it is not the one to reach for when the panel has to say what the missing thing IS |

| **015 M5's seams** | `features/account/AssistantsPage` (`/account/assistants`) + `ConnectAssistantPage` (`/account/assistants/new`) · `features/settings/RepositoriesSettingsPage` (`/projects/:id/settings/repositories`, and the rail's Repositories entry, which completes that subnav) · `features/tracker/AiEligibleMarker` — **one component on three surfaces** (task header, work-table row, board card face) · `TaskPanels` grew the agent-context and pull-request panels and now exports a second component, `AssistantRailPanel` |
| The assistant keys | `keys.myAssistants = ['me','assistants']` — deliberately **NOT** under `keys.apiTokens`, though they are the same rows on the wire: `/api-tokens` is the company's list behind `token:issue` and `/me/assistants` is the caller's own behind authentication alone, so `['api-tokens','me']` would have an admin's revoke refetch a list most of its readers are refused. `keys.projectRepositories(p)` sits beside `statuses`; `keys.agentContext(k)` sits **under** `keys.issue`'s prefix, wanted — the payload embeds the issue with its caller-scoped `allowedTransitions`, so a move must evict it |
| **ONE settings document, TWO screens** | `features/settings/settingsUpdate.ts::fullSettingsUpdate` — `PUT /projects/{id}/settings` is a full replacement and the server reads an absent boolean as `false`, so a form that saves one switch by sending one switch turns every other one off. Review owns §6g's six controls, **General owns D33's `reviewEnabledIssue`** (mockup + reader expectation), and the field list is written once. This had already bitten: `reviewEnabledIssue` landed on the contract and `ReviewSettingsPage`'s hand-written list was not extended, so saving the review policy silently switched the issue close gate off |
| `allowedTransitions` is CALLER-SCOPED, and `TransitionMenu` tells the two refusals apart | Under D33 the assignee's DONE moves are dropped from `Issue.allowedTransitions` while the project's workflow still declares the edge. So `workflow.transitions` ∧ ¬`allowedTransitions` ⇒ the close guard, and the row says **needs a second person** instead of the flatly wrong *not from In Progress*. No second read: the workflow document is already on screen for the list itself |
| The two shapes with a plaintext secret | `IssuedApiToken` (`/api-tokens`) and **`IssuedAssistant`** (`/me/assistants`). Neither may reach the query cache or a list type. `ConnectAssistantPage` is a **route, not a dialog**, deliberately: a modal invites the dismissal that loses an unrecoverable value, and its test asserts a fresh mount on the *same* `QueryClient` cannot get the secret back |
| **018 Mc's seams** | `features/clients/` — `ClientsPage` (`/clients`) · `ClientPage` (`/clients/:clientId`) · `ClientCreateDialog` · `ClientContactsPanel` · `BillingSlot`. `api/queries/clients.ts` holds all nine hooks. **Both routes sit OUTSIDE the project segment**: one client has many projects, so scoping the list under the switcher would say the opposite of what it answers |
| The client keys, and why the picker is not under `clientsRoot` | `keys.clientsRoot = ['clients']` · `clients.list(status, managerId)` · `clients.one(id)` · **`clients.optionsFor(p)` = `['projects', p, 'client-options']`**. The picker is keyed under the PROJECT because the invalidation decides where a key lives: a client created from the settings picker must refresh that picker, and every project-scoped write already sweeps `['projects', p, …]`. It is also a different gate — `project:settings`, not `client:read` — so a reader holding one must not evict the other's answer. `invalidateClients` sweeps `clientsRoot` **and `['projects']` whole**, because a client's NAME is on every project card that references it and this module cannot know which those are |
| The `clients` fault arm is the only one that fabricates a POPULATION | `tcmsFaults.clients('none' \| 'some')`. Every other arm in `devFaults.ts` answers *what does this screen do when something breaks*; Mc's acceptance criterion is an **absence** — with no clients, six surfaces render nothing — and on a seeded instance that state is unreachable without deleting every client. Both states are `body` verdicts (200s carrying a state), and the matcher is anchored: `/clients` is a prefix of `/clients/{id}`, which answers a `ClientDetail` where the list answers an array |
| The Clients rail entry is GATED, and it is the fourth one that is | `AppShell.tsx`, first under Company after Org chart, on `client:read` — passed as its own `canReadClients` parameter to `projectNav` rather than onto `CredentialReach`, whose subject is *a credential the product keeps*. Same exception 005 C2 made for `token:issue`: an empty array is a claim about the company told to somebody who may not ask, and an entry into that refusal is the same claim one click earlier. It does not weaken `clients-empty.html`'s promise — everybody who could create a client holds the key |
| Two client absences that are boundaries, not omissions | **Hours and invoices are M8's** (018 D-g) — `BillingSlot` is the named reserved region, draws no data and reads no endpoint, and M8 lands its whole five-column table there rather than three of its columns. **The rate card panel and the Contracts panel are not rendered at all**: the first is a Finance panel with nothing behind it, the second belongs to no module in either Mc's or M8's schema (D-h) |
| A `default:` keyword makes a property REQUIRED in both generated clients | `openapi-typescript` emits `ProjectSettingsUpdate`'s six defaulted booleans as required, which is why `settingsUpdate.ts` sends every one. 018 T4 hit it from the other side: `ClientInput.status` carried `default: ACTIVE` and became required, so a create omitting it was a compile error and both CLI/MCP arms would have needed a cast that says the opposite of what the server does. **The keyword was dropped and the behaviour moved into prose** — a genuinely optional request property must not carry one |
| **016 M6's seams** | `features/time/` — `TimesheetPage` (`/timesheets`) · `ApprovalsPage` (`/timesheets/approvals`) · `EntryForm` · `EntryTable` · `RejectDialog` · plus `periods.ts`, a pure module with its own `.test.ts`. `api/queries/time.ts` holds all eleven hooks. **Both routes sit OUTSIDE the project segment**, beside `/my-work`: a period spans projects (016 D-i), so scoping one under the switcher would state the opposite |
| The time keys, and why `approvals` is not under `periods` | `keys.time.currentPeriod(on)` is keyed by the **date**, not the id — the screen opens on "now" and the id is something the *answer* contains, so keying by id would need a round trip to build the key for the first render. `approvals(on)` is deliberately **not** under `periods`: a PM approving somebody else's fortnight must not invalidate their own, and the two answer different permissions. `keys.timeRoot = ['time']` is the sweep **every** write uses, because an approve writes the rollup and therefore stales the approver's queue, the owner's fortnight, their history and the per-issue figures on three other screens |
| **`GET /timesheets/periods/current` WRITES** | It creates the period row, which is what makes `id` available to Submit on a fortnight nobody has logged into. So there is **no create-a-period hook** in the module and looking for one is looking for something the design removed |
| `POST /timesheets/entries` ignores `Idempotency-Key` | The contract declares the header and a `200` replay arm; `V9` gave `time_entry` no column to store a key, so `useLogTime` deliberately does **not** send it — asserting a guarantee nothing upstream provides is the worse failure. The guard is client-side single-flight (`isPending` disables the button) and it does **not** survive a timeout. Closes with a column, not with a client change |
| The lock is SERVED, never derived | `TimesheetPeriod.editable` and `TimeEntry.editable` are both on the wire so one rule has one home. The Add form is present on `period.editable` and the row pencils on `entry.editable`; **nothing in `features/time/` reads `state` to decide either**. A REJECTED period is editable — a rejection *unlocks*, which is the half that reads backwards |
| `projectTotals` does not add up to `loggedHours`, and the screen says so | A project-less entry has no row (the contract bans the literal em dash — a dash in a column of project names is a project called dash) and neither has one against a project the approver cannot see (D-i). `ApprovalsPage` labels the header `logged` / `so far` and names the remainder as `{n}h not attributed here` with the reason in its title, rather than letting two numbers disagree in silence |
| ~~Three KPI tiles on Timesheets, and the fourth stays missing~~ → **FOUR since 017 T6** | ~~D-j: `Period capacity` needs `person_profile`, `allocation` and `absence`, all M7's.~~ M7 landed all three, so the tile is there, second, in a `md:grid-cols-4`. `Tile` from the kit is still **not** used — it is a `<button>` that navigates to its rows and none of the four has rows — which is `ReadinessPanels`' precedent one module over |
| The `time` fault arm | `tcmsFaults.time('rejected')` serves a REJECTED current period with a note (needs a second person otherwise); `time('locked')` answers the three entry writes with the 409 (reachable only through a race, because the form is removed the moment a period locks). `isTimePath` covers `/time/` too, so `only('/time/')` + `status(500)` faults the rollup reads alone |
| **016 T5's seams** | `features/tracker/TimePanels.tsx` — `StoryTimePanel` (the story **rail**, beside `ClosingThisStory`) and `EpicTimePanel` (the epic **body**, mounted by `IssueDetailPage` *below* `EpicPanels` rather than inside it, because it reads its own endpoint). Plus `IssueDetailPage`'s `Logged` Details row, `WorkTablePage`'s `logged` column + footer, `MyWorkPage`'s `ThisPeriodPanel`, and `GeneralSettingsPage`'s `TimePanel`. `lib/format.ts::formatHours` is the one spelling of an hour (`14h` / `0.5h`, `null` → em dash, never `0h`) |
| **`trackTime` reached the wire in a FRONTEND task, and that was a decided deviation** | 016 T5 was cut `fe` + `docs`, and D-g's switch had no contract field: T2 landed `project_settings.track_time`, T3 mapped it, `TimeEntryService` read it, and `ProjectSettings.java` said in as many words that the contract field arrives with the settings screen. T5 added it to `ProjectSettings` + `ProjectSettingsUpdate`, both generated copies, `ProjectSettingsView` / `ProjectSettingsRequest` / the entity / the service, and `TrackTimeSettingTest`. **It is the one boolean in `ProjectSettingsUpdate` the server reads as `true` when omitted** — dropping it takes a project's time surfaces away, where dropping a review opt-in reopens a gate somebody can close again. `settingsUpdate.ts::fullSettingsUpdate` carries it anyway; the server's reading is the safety net, not a licence |
| The bulk rollup, and why it is not a field on `IssueSummary` | **`GET /time/rollup/issues?key=…`** → `TimeRollup[]`, `useIssueTimeRollups(keys, enabled)`, `keys.time.issueRollups(keys)` (under `['time','rollup','issue','bulk', …]` so it shares the single grain's prefix). `IssueSummary` carries `estimateHours` and **no logged hours**, and the epic-rollup operation's own prose claimed otherwise until T5 struck it through. Adding `approvedHours` to `IssueSummary` was refused: `tracker` is a leaf and would then read another leaf's projection on every work-table page load. **A key the caller cannot read comes back ABSENT, not `0h`** — zero is the claim somebody looked |
| The time surfaces are WITHHELD until `useProjectSettings` answers, not defaulted on | `!settings.isPending && (settings.data?.trackTime ?? true)` on both `IssueDetailPage` and `WorkTablePage`. `?? true` alone made the panel appear and then vanish on a project with tracking off — a layout shift and a wrong claim held for a beat, caught by an RTL assertion that ran before the settings read landed. A **failed** settings read still falls back to on, because D-g's column default is on |
| **My Work's time figure is NOT behind `trackTime`, deliberately** | D-g names the `Logged` column, the story and epic panels and the log form's picker — not this. A period spans projects (D-i) and `trackTime` is one project's decision, and `MyWorkPage` names no project at all. The panel is headed **"This period"**, not the mockup's "This week": D-a's fortnight is semi-monthly, so a week is a range the product does not have. Its Capacity half and the meter arrived with 017 T6 — see the row below |
| `TypeIcon`'s `name` is OPTIONAL since 016 T4 | `TimeEntry.subject` carries `typeLevel` and no type name, so the glyph and the sr-only text fall back to the level's own word and the tooltip is absent. Third widening of that component, in its own idiom |
| The rail's Timesheets entry has NO badge, deliberately | The mockup's `SEES_APPROVALS` count needs `GET /timesheets/approvals`, which **403s** without `time:approve` — a **PROJECT-scope** grant with no company-wide "where may I approve" read on the contract. `useCanInCompany` would say no for the PMs it is for; `useCan(activeProjectId)` would key a cross-project count on the switcher, the exact claim the placement avoids. Firing it unconditionally would raise a refused-request notification on every page load for most of the company. The count is on the approvals subtitle instead. Full argument in `AppShell.tsx`'s `TIMESHEETS` comment |
| `Approve all` and `Remind` are absent from the approvals screen | No batch operation on the contract for the first — it would be N irreversible writes with a partial outcome to word, and there is no un-approve — and no endpoint at all behind the second. Named in `ApprovalsPage`'s header so a reader comparing against the mockup finds the reason |
| **017 M7's seams** | `features/staffing/` — `StaffingPage` (`/staffing`) · `DirectoryPage` (`/directory`) · `PersonPage` (`/directory/:userId`) · `ProjectStaffingPage` (`/projects/:projectId/staffing`) · `AllocationTimeline` · `AllocationDialog` · `AllocationRow` (which also exports `DeleteAllocationDialog`) · `OverAllocatedPanel` · `FreeCapacityPanel` · `UtilisationPanel` · `ProfilePanel` · `SkillsPanel` · `AbsencePanel` · `DirectoryFilters` (which also exports **`SkillChips`**) · `PersonCard` · plus `capacityCopy.ts` and `staffingWindow.ts`, two pure modules. `api/queries/staffing.ts` holds all fifteen hooks. **The three COMPANY routes sit OUTSIDE the project segment**, beside `/timesheets`: an allocation spans every project a person is on, so scoping them under the switcher would say the opposite of what they answer. The fourth is inside it, because *who is on THIS project* is a question about one project |
| The staffing keys, and why there are TWO roots | `keys.staffing.{capacity(from,to), myCapacity(from,to), people(filters), person(id,from,to), skills, absences(id,from,to), projectStaffing(p,from,to)}` with **`keys.staffingRoot = ['staffing']`**. The **window is inside every key that has one** — a September–November view and a six-month one are different answers, and a key without it serves the first under the second's heading the moment the picker moves. `projectStaffing` sits under `['projects', p, …]` on purpose so a project-level invalidation reaches it, which means `staffingRoot` does **not** cover it: every write sweeps both, through the module-private `invalidateStaffing(client, projectId?)` |
| Every staffing mutation takes its id as a HOOK argument | `useCreateAllocation(projectId).mutate(input)`, `useCreateAbsence(userId).mutate(input)`, `useUpdateAllocation(projectId).mutate({ allocationId, patch })`. Not per call, because the id is also what the sweep needs and a call-site id is one a call site could get wrong |
| **The directory's match count is NOT served** | `GET /staffing/people` answers a bare `PersonCapacity[]` with no total, so `DirectoryPage` reads the **same hook a second time with the filters dropped** — one extra key (`{from,to}`), fetched once, reused across every filter combination, and identical to the main read (one request) when nothing is applied. 017's plan predicted a served count; it is not there. Counting the filtered array gives "3 of 3", which is not a denominator |
| `DirectoryFilters` is a TYPE in `api/types.ts` **and** a component in `features/staffing/` | The type is beside `BoardFilters` / `BacklogFilters` for their reason — `useStaffingPeople` spreads it into `qs`, so `contractParameters.test.ts` reads the *type*, and a member the contract does not declare is **ignored** by Spring rather than rejected. The component imports it aliased (`type DirectoryFilters as Filters`) |
| The capacity em dash has ONE home | **`features/staffing/capacityCopy.ts`** — `capacityLabel(hours, hasProfile)`, `capacityDetail`, `utilisationLabel`, `freeCapacityLabel`, `overAllocationLabel`, `allocationLabel`, `dayFractionLabel` (T5), `hasProfile(subject)`, and the constants `NO_FIGURE` / `NO_PROFILE` / `UTILISATION_BASIS`. Three states that look identical if written per screen: a number, `—` + *no profile recorded*, and the region's own failed state. **`capacityLabel` consults `hasProfile` as well as the hours** — the server sends the five nulls together, and a caller trusting only the hours prints a stale figure the moment one of them does not arrive |
| `overAllocationLabel` does NOT re-derive the threshold | `overAllocated` is served, computed from the peak **day** (017 D-e), and a client-side `peakPercent > 100` would be a second rule that silently overrides the server's the day it moves. `StaffingPage`'s chip reads `view.overAllocated.length` for the same reason — the subset is served so the chip, the row badge and the breakdown are three renderings of one list |
| The over-allocation tone is `failed`, not the mockup's `b-blocked` | The kit's `blocked` means *something outside stopped this*, and the panel's own kept sentence is **"Flagged, not blocked"** — that tone would be the one word on the screen contradicting the one beside it. `failed` is the kit's red and is what `--color-danger` resolves to |
| The timeline shares the month scale and NOT the span arithmetic | `AllocationTimeline` imports `monthsIn` / `DateWindow` from `features/releases/timelineScale.ts` and keeps a private `spanBetween`. A release has a target and may have no start, so that module's three shapes include a point marker and a `null`; an allocation and an absence always carry both dates. **The window's `to` is inclusive**, so the denominator runs to the day after it — without that, a full-window bar comes out under 100% and the last day is a sliver nothing can fill |
| The `staffing` fault arm, and what it deliberately does not reach | `tcmsFaults.staffing('no-profile' \| 'over-allocated')`. `'no-profile'` is the only way to the five-null body — no fixture and no seeded instance produces it — and its correct rendering is an em dash and *no profile recorded*, never `0h` and never the failed state. **`/projects/{id}/staffing` is outside `isStaffingPath` on purpose**: that operation answers a `ProjectStaffingView`, which has no `profile`, so a company-shaped body served there would be a drifted response rather than a fault |
| **The two COMPANY staffing screens pre-gate; the PROJECT one does not** | `/staffing` and `/directory` check `staffing:directory` before rendering because their refusal has something bespoke to say — *open that project's Staffing* — that no generic refusal produces. `/projects/:projectId/staffing` has nothing bespoke, so it is routed for everybody and `DataRegion`'s **Refused** state does the refusing: it names the permission the server refused on and links the org chart (005 C1b), which is strictly better copy than a hand-written panel. That is also `router.tsx`'s standing shape for a project screen. The plan for T5 said to pre-gate it; this is the deviation and its reason |
| **A conflict on a value the reader just typed is INLINE, not a notification** | `AllocationDialog` reads `save.error` for a `409` and renders the server's `title` + `detail` beside the form — the detail **names the range it collided with**, which is the only actionable half. `applyServerError` there deliberately **returns early on a 409** so it cannot also land in `errors.root`; without that the same refusal appears twice and the weaker sentence is second. The hooks' `meta.handled` is what stops the global handler adding a third. The pattern generalises: a refusal about a field on screen belongs beside it |
| **A window the payload does not carry has ONE derivation** | `features/staffing/staffingWindow.ts` — `windowOver(people, fallback)` and `todayIso()`. `GET /staffing/people` and `GET /staffing/people/{userId}` answer with no `from`/`to` (unlike `/staffing/capacity` and `/projects/{id}/staffing`, which carry theirs), so the widest span in the answer is the only evidence of what the server defaulted to. `DirectoryPage`'s two private helpers moved here in T5 rather than being copied to `PersonPage`: two spellings would let one screen clip a bar the other shows in full |
| `PersonCard`'s two optional props are both about the person page | `profileTo` omitted → **no Profile control** (this is the page it would link to, and no grant makes a self-link useful, so §31 does not apply). `onToggleSkill` omitted → **no skills row**, because `SkillsPanel` owns them there; the chips would otherwise appear twice on one screen under two affordances, and a chip with no handler is what `Chip`'s required `onClick` makes unrepresentable |
| **017 D-h is visible in exactly one control** | `AbsencePanel`'s Add is `isSelf \|\| canRecordForOthers`, and `isSelf` is checked **first and alone**: `staffing:absence` covers *"record absence on somebody else's behalf"*, so your own leave needs no key. A gate consulted before the identity would be the product deciding your own leave is administrative — in a product where that made every capacity figure a lie by omission. Profile and skills are the opposite: `staffing:profile` even on your own row, because contracted hours are what the whole company's figures are denominated over |
| `PUT /staffing/people/{id}/skills` is the SET, and `SkillsPanel` computes it every time | Add, remove and create-then-add each build the **complete next set** and send that. A handler sending only what it touched would clear the rest with a `200` and no symptom until the directory stopped finding the person |
| A comment naming a file in `frontend/src` is checked by the **packages** job | `citedFiles.test.ts` walks `frontend/src` and reddened on `MemberPage.tsx` in T5 — a filename whose **absence was the point of the sentence**. The remedy is that check's own: strike it (`~~x~~`). Cheap to hit and confusing to diagnose, because the failure lands in the wrong workspace |
| `pluralNoun` now has ONE irregular | `lib/format.ts::IRREGULAR_PLURALS` — `person → people`. Every count on these screens is of people and read **"2 persons"**, including inside `EmptyState`, whose `noun` prop is documented singular and inflected there, so the plural could not be passed in. That file's *"pass it already-plural"* sentence is struck through with the reason. An entry earns its place by a screen having been seen to read wrongly without it |
| `useCan`'s pending state is a REFUSAL PANEL, so a test must await the copy | Both staffing screens render the gate's three states in one element, so `findByTestId('…DeniedText')` resolves against *"Checking your permissions…"* and the assertion fails on a screen that is about to be right. Await the **sentence** (`findByText(/Staffing across the company is for/)`), not the element. Cost time in T4 |
| **017 T6: `capacityCopy.ts` is imported from OUTSIDE `features/staffing/`** | `features/time/TimesheetPage.tsx` and `features/board/MyWorkPage.tsx` both import `capacityLabel` and `NO_PROFILE` from it. That is the file's stated purpose — *"what does an em dash mean here" decided once, for the five surfaces that show a capacity figure"* — so a sixth surface imports rather than re-spells. **`capacityDetail` still has no caller**: every landed surface pairs `capacityLabel` with `NO_PROFILE` and writes its own present-case sentence, and the shared half is the absent case |
| **A second module's `DataRegion` nests INSIDE another module's panel** (017 D-m) | The capacity figure is `staffing`'s, the hours beside it are `time`'s, so each of the two screens makes two reads and the second gets its own region — `Timesheet.Kpi.Capacity.Region` inside the KPI grid, `MyWork.Period.Capacity.Region` inside the This-period panel. One region over both would let a staffing outage blank a number `time` served correctly. The **window is the LOADED period's `startsOn`/`endsOn`**, never `periods.ts`' arithmetic (016 D-a), and `GET /staffing/capacity/me` makes both bounds required, so there is no defaulted answer behind a dropped one |
| My Work's meter is data-driven, not region-wrapped | `PeriodMeter` renders only when the capacity payload carries a number, which covers the failed read, the in-flight read and the no-profile person in one condition. It is under the whole `g2` row where `my-work.html` draws it, so it could not sit inside the region that owns the right-hand cell. `.meter.tall` on the app's tokens: 9px, `bg-wash-3` track, `--color-success` fill, `--color-danger` above 100%, clamped and captioned rather than overflowing |
| **A test that renders either of those screens MUST answer `/staffing/capacity/me`** | `MyWorkPage.test.tsx`'s `mockBoard` returns `items` from its default branch, so an unanswered capacity read hands a `BoardCard[]` to a region expecting a `SelfCapacity` — a drifted body, which trips the boundary and fails **every** test in the file through `renderFailures` for a read none of them is about. `TimesheetPage.test.tsx`'s mock throws instead, which is the same failure with a clearer message. Both now take the capacity as a parameter, `aSelfCapacity()` by default, and accept an `Error` for the failed-read arm |
| `openapi-typescript` types a **defaulted** property as REQUIRED | Not the contract's `required` array. `IssueInput.agentEligible` and `ProjectSettingsUpdate.reviewEnabledIssue` both carry `default: false` and are absent from `required`, and both came out non-optional — which is why `IssueNewPage` sends `agentEligible: false` on a form that offers no such control. Three call sites were left red by T1 and fixed in T5 |

## Test-harness rules the suites enforce

- **Contract conformance** — every exchange crossing a `fetch` double is compared against
  `openapi.yaml`. Use `src/test/fixtures.ts`; anchor path-keyed mock arms (`/^\/projects\/[^/]+$/`)
  so a sub-resource isn't caught by a prefix. `/boards/{id}` vs `/boards/{id}/cards` is exactly the
  collision. **`drivingAFailedWrite()` / `drivingAFailedRead()` / `renderingIntoAnErrorBoundary()`
  take NO arguments** — the reason goes in a comment beside the call, not in a parameter.
- **Never build your own `QueryClient` in a test** — only `createTestQueryClient`'s has the caches.
- **`toBeDisabled()` does NOT work on a Radix menu item.** It is a `div` with `role="menuitem"`, and
  `jest-dom` reads the `disabled` *attribute* on form elements only — the assertion passes on an item
  that was never disabled. Read `aria-disabled`, as `PermissionGate.test.tsx` and
  `MoveToMenu.test.tsx` both do.
- **`PermissionGate` REMOUNTS the control it gates.** Its denied branch renders inside a
  `<span role="none">` anchor and drops the anchor when the verdict lands, so a node captured before
  the answer is a detached element that stays `disabled` forever. **Re-query inside the `waitFor`** —
  `ArchiveProject.test.tsx` says so and it cost time again in 012 T7.
- **`TestProviders` mounts no announcer**, so `useAnnounce` is a no-op and an `announce(...)` string
  never reaches the DOM. A test for a keyboard move asserts the **request**, not the announcement.
- A test whose render trips an error boundary **fails** unless it calls
  `renderingIntoAnErrorBoundary()` / `drivingAFailedRead()` / `drivingAFailedWrite()` and says why.
- **Cited files** — `packages/client/src/test/citedFiles.test.ts` fails if a path named in a comment
  is not on disk. It scans `frontend/src` for **`.ts`/`.tsx` only** (`.css` is not scanned), and a
  stale citation here **reddens the `packages` job**, not the frontend one.
- **Tier claims** — `tierClaims.test.ts` fails if a comment claims somebody lacks a permission the
  roles spec §A3 says they hold.
- **An optimistic mutation cannot be tested with the refetch running.** `useRankIssue`'s `onSettled`
  invalidates on **both** paths, and a read double answers with the order it always had — so a naive
  "it reordered" assertion asserts the fixture, and a naive "it snapped back" assertion passes
  against a hook with no rollback at all. Hold the write pending with a hand-resolved promise to see
  the patch; hold the **refetch** pending too to see the rollback (012 D-ab).
- **`PATCH /issues/{key}` answers with the whole `Issue`.** A double returning `{}` is eleven
  contract violations from `contractDoubles.ts`. `test/fixtures.ts::anIssue` is the factory.

## Conventions observed — where this repo narrows the plugin's rules

- **Comments:** the repo's convention is **long doc-comment headers carrying the reason and the
  superseded alternative**, struck through with "→ superseded". This is a deliberate deviation from
  `ep-code-comments`' minimalism; the repo wins, per the agent's own "use the project's convention"
  rule. Do not strip these headers when porting a file.
- **No branching:** CLAUDE.md — *"Work on the current branch. Do not create a new one."* This
  overrides the agent definition's "work on a branch, not the user's default".
- **No parallel lanes:** one subagent at a time, ask before a second. Parallel work was tried four
  times and reversed; two agents in one tree produced three silent losses of committed work.
- **Stage only the files the task touched** (`git add <paths>`, never `git add -A`).

## Known unknowns

- **`src/features/cases/CaseGrid.test.tsx` is RED on `master` and predates 016.** Still red after
  017 T6 (2 failed of 2805, in 1 file of 202), after 017 T4 (2 of 2779, in 1 of 198) and after 016
  T5 (2 of 2705, in 1 of 194) — untouched by any of them, none of which names a cases file. Two selection
  tests — *survives paging* and *select-all reaches the page* — fail with `Number of calls: 0` after
  a `userEvent.click` on the Radix `Checkbox` at `CaseGrid.tsx:118` / `:126`. Verified failing on a
  clean checkout at `90667f1e` with the working tree stashed, so it is not 016's. Not investigated:
  whether the click never reaches the checkbox under jsdom, or `onSelectionChange` genuinely stopped
  being called. Whoever opens that file next owns it.

- ~~**84 tests fail on a clean checkout, in 18 files, and they are T16 port residue.**~~ → **gone.
  164 test files pass on `71777f9a`**, verified on shanks. Nothing in 011 T6 repaired them
  deliberately; they were fixed in the backend lanes between 2026-08-29 and now.
- **Three contract gaps found while building T7 and NOT fixed** — each is a field the frontend
  wanted and the contract does not carry, so the screen names the absence instead:
  `BoardCard.estimateHours` (so `my-work.html`'s Est column is gone), a project rename/`PATCH
  /projects/{projectId}` (so Identity is read-only), and a project-wide latest-result-per-case count
  (so the overview's pass rate is denominated over cases carrying a Feature and says so).
- **`Sub-task` renders as `S`, not the `T` `V3` seeds as its `icon_code`.** `IssueTypeView` omits
  `iconCode` deliberately — `ContractConventionTest` compares the contract's four members both ways
  — so `TypeIcon` uses the type's initial. Not fixed: a contract change for one glyph, and the level
  badge beside the name already answers it.
- **Two disagreements between `tracker`'s contract prose and its code, found while building T6 and
  NOT fixed here** (both are backend):
  1. `IssueDocLink.blockExcerpt` is documented as *"what you linked, not what the block says now"*,
     and `IssueDocLinkFacts.refreshIssueDocLinks` `DELETE`s and re-`INSERT`s the whole projection
     from the block's **current** flattened text. So the excerpt is always current, and the
     "changed since linked" card shows the new text under a caption promising the old one.
  2. There is **no re-confirm operation on the contract**, which is correct — `stale` is a
     comparison, not a stored decision — but 011 T6 Part B step 5 says the button "targets that
     endpoint". `LinkedSpecPanel` sends an empty `PATCH /issues/{key}` instead, which is the
     mechanism `IssueDocLinkFacts`' own javadoc describes. **Unverified against a running server**:
     whether a no-op PATCH moves `updated_at`, and whether the event-driven projection has caught
     up by the refetch.
- ~~**Nothing in `docs/specs/assets/renders/` is implemented.**~~ → 007 landed T6–T16; all 42
  screens are on the render kit.
- The renders were opened at 1680px only. No claim holds at 1280px, against a real API, or with a
  fifty-step case (design-language §0, §9.1).
- E2E / Playwright surfaces belong to the `qa-ui-typescript` peer; none are mapped here.
- **Neither T7's nor T8's screen has been seen in a browser**, and the backlog's drag is now the
  **second** drag in this repository that has never run. Every backlog ranking assertion goes through
  the `↑ ↓` buttons, which prove the *placement* and cannot prove that `pointerWithin` over a stack of
  full-width rows picks the row the reader thinks they are on, or that a row registered as both
  droppable and draggable under one id behaves. Also unverified on the backlog: the row's single
  `flex-wrap` line at 1280 with three label chips on it, `rebalanced: true` reaching the screen, and
  the depth rule actually refusing a `Set epic` over a task — the client disables it first, so the
  400 the contract describes has never been provoked and the two rules have never been compared.
- **Nothing about 012 T7's screen has been seen in a browser.** 18 + 8 component tests, `npm run
  build` and oxlint at zero; none of them is a renderer. Specifically unverified: the two-grid /
  fixed-272px column alignment (jsdom applies no stylesheet), **any drag at all** (the cell droppable
  ids, `pointerWithin` over a flat cell set, the `DragOverlay` copy, and the `columnOrder`/`laneOrder`
  derivation fed real `droppableRects`), a board with **two expanded lanes** — which is the shape
  D-z's column-header decision was taken for — a board whose columns overflow, and the grouping
  picker's full-`BoardInput` round trip against a real server.
- **A collapsed lane does not survive a reload, and the table for it exists.** `board_swimlane` is in
  `V5` and carries exactly this per-viewer state; **no endpoint on the contract reaches it** (no
  `collapsed` field on `Board`/`BoardContents`/`BoardInput`, no operation of its own), so collapse is
  component state. Persisting it is a contract change, not a screen decision.
- **Two more fields the mockup draws and the payload does not carry**, beyond D-f's four: the
  `TC-311 failed` / `4 cases pass` result badge and the `waiting on provider` blocked reason. Neither
  is on `BoardIssueCard`. Priority **is**, and is deliberately not drawn — B3's card face does not
  list it and §12.2 says design the face rather than let it accrete.
- **012 T6 left twelve of `projectBoards.ts`'s fourteen hooks with no caller** — the reads and the
  label/board writes typecheck against the contract and are unexercised until T7–T9. `useUpdateBoard`
  is the one whose behaviour is worth doubting: it invalidates `keys.board(board.id)` from the
  *response's* id rather than the argument's, which is right and untested.
- ~~**Nothing in 012 M2 renders.**~~ → T7, T8 and T9 landed the board, the backlog and the board
  settings. **`?view=board` and `?view=backlog` no longer show a placeholder, and neither do
  `?view=timeline` and `?view=calendar` since 013 T5** — the `WorkTable.NotYet.*` panel and the
  `module` / `body` / `existing` columns of `VIEWS` are gone, because all five views exist.
- **Nothing in 015 M5's frontend has been seen in a browser.** 24 component tests, `npm run build`
  and oxlint at zero; none is a renderer. Specifically unverified: the connect flow end to end
  against a real `/me/assistants` (the token-shown-once path is asserted through mocked `api.post`,
  so the seam itself is what the tests do not exercise), the agent-context panel against a real
  composite, and D33's gate biting on a live project — the last two were smoked through the CLI and
  MCP adapters in T4, but never through this UI.
- **Three panels the mockups draw and this build does not, each blocked by the contract rather than
  by effort.** `profile-assistants.html`'s *"What mine did this week"* is the audit trail filtered to
  the caller's tokens: the trail costs `audit:view`, which most people reading their own profile do
  not hold, and `AuditFilters` carries no token or target parameter, so the question cannot be asked
  even by somebody who does. Its *"My AI-eligible work"* needs a cross-project issue list and
  `GET /issues` requires `projectId`. And `task.html`'s *"What the assistant was refused"* is an
  `issue.close_refused` **audit event** — same gate, same missing target filter — so the live half
  lands in `TransitionMenu` and the historical half does not.
- **`settings-general.html`'s second closing-work row is wrong and was dropped.** It draws *"An
  assistant may never close. Fixed."* and there is no token-specific rule (015 D2/A6): an assistant
  acts as its owner, so with the gate off the same close succeeds for it. Recorded in the page's own
  doc comment and asserted as an absence in `GeneralSettingsPage.test.tsx`.
- **Two CaseGrid selection tests are red and were not absorbed** (`select-all reaches the page`,
  `survives paging`): the click produces no `onSelectionChange` call at all. Either a real defect in
  the case list's bulk-edit selection under TanStack Table v9 or a stale test — a cases-module
  investigation, not a mock arm. Everything else 013 left red was repaired in `306a36c5`.
- **Nothing in 013 M3 has been seen in a browser.** 41 component tests, `npm run build` and oxlint at
  zero; none of them is a renderer. Specifically unverified, and each is something jsdom structurally
  cannot answer:
  - **Every bar on the roadmap is absolutely positioned inside a percentage-width track**, and jsdom
    applies no stylesheet, so `left` and `width` have been asserted as *numbers out of
    `timelineScale`* and never as geometry. A window whose `from` and `to` collapse, a release bar
    that overruns its track, and the `MIN_WIDTH` floor's effect on a two-day span are all unseen.
  - **The month scale and the tracks are two independent grids** — `repeat(n, 1fr)` above,
    percentage offsets below — aligned only because both span the same box. Nothing checks that they
    line up, which is the same class of assumption 012 T7's fixed-272px column headers made.
  - **The calendar's 42-cell grid at 1280px**, and a day cell holding four events.
  - **`tcmsFaults.release('untested')` and `release('empty')` have never been armed in a browser.**
    Both are driven through mocked `api.*` calls in the suite; the seam itself is the one thing the
    tests do not exercise, and the `empty` arm's whole purpose is a state that only exists before
    somebody types a release.
  - **The publish confirmation's copy against a real 409-free server**, and whether the idempotent
    second press reads as a no-op on screen.
- **`useReleases` is read by three screens and `useRelease` by one.** `useUpdateRelease`,
  `useDeleteRelease`, `useIssueTestCases`, `usePutIssueTestCases` and `useCaseVerifications` have
  **no caller yet** — they typecheck against the contract and are exercised only by T6's screens. The
  one worth doubting is `usePutIssueTestCases`: it sweeps `keys.releaseRoot`, which is right and
  untested, because nothing writes a `TEST_CASE` link until `LinkedCasesPanel` lands.
- **The calendar fetches every filtered issue to draw thirty days.** `GET /issues` has no date-range
  parameter, so the month is applied in the client. It shares the Table view's cache key, so
  switching between them costs no second read — which is what makes it acceptable today and what
  would have to change first on a project with tens of thousands of issues.

## Session log

- **2026-09-01** — 013 T5, `fe` (plan `71d7fce1`). The release list, the new-release form, the
  readiness screen, the roadmap and the calendar — and the empty roadmap, which is the module's
  acceptance criterion rather than its edge case. Five things worth carrying.
  **The fault switch found the defect it was built for, before the screen did.** `release('untested')`
  landed at step 3, and writing it forced the question the whole module turns on into the code: what
  does the meter draw when `casesPassed` is `null`? Every branch in the two screens is on
  `tests.hasRun`, never on a count, and the `?? 0`s live *inside* those branches — which is a shape
  that is easy to get right once stated and near-impossible to notice going wrong.
  **`plural(4, 'story')` rendered "4 storys" on the scope tile**, in the one sentence taken verbatim
  from the mockup. `pluralNoun` had no `y → ies` rule because no caller in the tree had ever passed
  such a noun; the fix is in the shared layer, which is where the next one will find it. Its sibling:
  **`formatAge` reads a future date as "just now"**, so `target 18 Sep 2026 · in 17 days` shipped as
  `· just now` until `formatDueIn` existed. Both were caught by a test asserting the mockup's own
  words rather than a selector.
  **`doneButFailing` could not live where the plan put it.** oxlint's
  `react(only-export-components)` is a warning and the lint gate is zero, so `ReadinessPanels.tsx`
  cannot export a function beside its components — the same constraint `features/board/cardLink.ts`
  was created for. It went into `releaseBadge.ts` with `blockerVerdict`.
  **The calendar being the second `IssueFilters` reader forced T8's extraction to happen again**, one
  parameter set over: two chip lists would have regrown exactly the drift `useBoardFilterChips`
  removed, so `useIssueFilterChips` sits beside it and `WorkTablePage` lost its inline copy —
  and `useProjectMembers` with it, since the chips were that read's only consumer. `month` joined
  `CARRIED` for the same reason `board` did in T7: it looks like a filter because fewer things are on
  screen after you press it, and it is *which month you are looking at*.
  **One mockup element the payload cannot support, and it is in the unscheduled lane.**
  `work-timeline.html` draws *"Loyalty tie-in — no dates"* at a `left` and a `width`, both invented.
  So the lane bars the epics that have dates (and no release) and lists the dateless ones as links
  under the track — two shapes, because the data is two shapes, and D-o's rule that nothing
  disappears from the roadmap is met either way.

- **2026-08-31** — 012 T8, `fe` (`8dc709e3`; plan `aeea6b25`). The ranked backlog, and one filter
  across every view — the task that makes D34 true on screen. Five things worth carrying.
  **The extraction's payoff showed up as a test file that needed no edit**: `ProjectBoardView.test.tsx`
  asserts `ProjectBoard.Filters.Applied.<key>` chip ids and a `/boards/b1/cards?labelId=…` path, and
  both survived the board's filter `useMemo` being deleted — which is the only evidence available
  that `useWorkFilter` reads the URL the way the two `useMemo`s did. **The chips had to move into the
  hook too**, not just the read: the board and the backlog take the same six parameters, so two chip
  lists would have regrown the exact drift the hook removes, and the first thing to drift between
  them is *which* filters get a chip at all. Applying that rule to the table's own six then found
  `epicKey` and `parentKey` with neither chip nor picker — a reader who narrowed to one epic on the
  Board and switched to Table was looking at a narrowed table with nothing on screen saying so.
  **Three files the task's list did not predict**, all forced rather than chosen: `useBulkUpdateIssues`
  because there is no bulk endpoint over issues and `ui-rules` §5 wants a per-item report; its barrel
  line; and `TypeIcon.countsTowardCompletion` widened to optional because `BacklogRow` does not carry
  it and `!undefined` would have drawn every backlog row as a Spike. **A `PermissionGate` cannot wrap
  an `<ol>`** — a span containing a list is invalid HTML and the denied branch's tooltip would open
  over every row — so the rank verdict comes from `useCan` and travels down as two props; the board
  has the same shape around `lanes.map(...)` and was left alone rather than fixed in passing.
  **And two test shapes that would have passed against no implementation**: `onSettled` invalidates on
  both paths, so the optimistic reorder had to be asserted with the `PUT` held pending and the
  rollback with the **refetch** held pending as well, which is what leaves the snapshot restore as the
  only thing that could have moved the row back.

- **2026-08-31** — 012 T7, `fe` (`98d547ad`; plan `fc173ba8`). The project board: swimlanes by epic,
  the WIP breach in the column header, and a `⋮ Move to…` on every card. Five things worth carrying.
  **The plan's own trap note was wrong about the droppable and the reason is geometric**: it says
  register `column.id`, and once a full-width lane header sits between the bands a column has one
  rect per lane, so the droppable is a **(lane, column) cell** and the screen parses the column back
  out before calling `resolveColumnMove`. That turned out to be the good outcome — **both keyboard
  axes then come out of the one flat cell set**, `columnOrder` from the first expanded lane and
  `laneOrder` from the first column down the lanes, with no second registration for geometry and
  collapsed lanes dropping off both axes for free. **The column header had to move above the lanes**,
  which is the one structural disagreement with a mockup that never draws two expanded lanes:
  `breaches` is counted over the project, so a per-lane header states one breach N times and puts a
  WIP numerator beside a quantity a WIP limit is not about. **Three of this task's own tests found a
  design defect the reasoning had already flagged and settled the wrong way** — `isEmpty` over "no
  cards anywhere" hid the board's columns on an empty board, which is exactly what a freshly
  configured one looks like; empty is now a state only under a filter. And two harness costs that are
  both already written down somewhere in this repo and were paid again: `toBeDisabled()` is a no-op on
  a Radix menu item (read `aria-disabled`), and `PermissionGate` **remounts** the control it gates, so
  a node held across the verdict stays disabled forever. Two files beyond the task's list, per D-t:
  `TypeIcon`'s prop widened to the three fields it reads, and `WorkTablePage.test.tsx`'s board
  assertion, whose subject this task deleted.

- **2026-08-31** — 012 T6, `fe` (`99133dbc`, `6bf9885a`; plan `ac848172`). The fault switch, the
  project board's data layer and the move decision — **no screen**, deliberately, because the switch
  lands before the feature it proves. Four things worth carrying. **The plan's key shape was wrong
  and its own comment said why**: cards under `['boards', id, 'cards']` cannot both survive a column
  edit and be swept across boards, because TanStack matches by prefix — hence the second root
  `['board-cards']` (D-u). **`serverColumnsOf` is the one function this task added that the plan did
  not ask for**, and it is earned rather than tidy: `statusIds` is `readonly string[]`, a status
  *name* is a string, so mapping names instead of ids typechecks, lints clean and then refuses every
  drop on every board with no error anywhere. **`citedFiles.test.ts` reddened the packages job for a
  file this task deliberately does not have** — two comments saying *"not `boards.ts`"* named an
  absent path, and the fix is the repo's strikethrough notation (`~~x~~ → y`), which is what that
  check's own message tells you. And **two sessions committed into this checkout inside one minute**:
  a `git commit --amend` written against a `git log` read thirty seconds earlier amended the *other*
  session's commit, silently, with no conflict and a clean `git status`. `git reset --soft <theirs>`
  then `git commit -- <paths>` recovered it; the standing lesson is that `git add <paths>` is not
  enough when another session is live — commit **by pathspec**, never from the index (D-y).

- **2026-08-31** — 011 T7, both parts, `fe` (`8bf37408`, `7bd43361`, `f6315aeb`). **The module's
  close-out rode with this task**, which is the point of the cut: it is the session that knows what
  landed. Six decisions worth carrying, all forced rather than chosen. **`api.delWithBody` is a new
  client verb** — the workflow status removal is a DELETE with a body, `api.del`'s second argument is
  `RequestOptions` at thirty call sites, and a body hidden in the options object is invisible to
  `contractDoubles.ts`, which reads argument *position*: it would have compared a timeout budget
  against a schema. **`linkFor` had to leave `BoardPage.tsx`** the moment a second screen wanted it —
  exporting a function beside a component is `react(only-export-components)` and the gate is zero
  warnings; `cardLink.ts` is where it went. **The general settings screen has no rename form**: there
  is no `PATCH /projects/{projectId}` and `Project.key` is documented immutable, so the mockup's two
  inputs and its Save are absent and the key caption keeps only the true half of *"existing links
  keep working if it changes"*. **The archive button moved to that screen's Danger zone** where the
  mockups put it, and `ArchiveProject.test.tsx` moved to `features/settings/` with it. **The release
  panel's action is disabled with its reason** rather than linking to a route that does not exist.
  And the **"Tests passing" tile names its denominator** — there is no project-wide
  latest-result-per-case count on the contract, only `byLastRun`, which holds cases carrying a
  Feature; the tile sums the same rows the figure below it draws, says so, and is *not rendered at
  all* when nothing has run. Two test-harness facts that cost time: **a `PermissionGate` disables
  every control while its verdict is in flight**, so a test that waits for rows and then clicks gets
  a no-op — wait for `toBeEnabled()` and re-query the node, as `ArchiveProject.test.tsx`'s own
  comment says; and a heading whose text depends on a read *arriving empty* must be waited for, not
  read once, or the assertion pins the in-flight state and passes on a page that never switches.

- **2026-08-31** — 011 T6, both parts, `fe` (`3bc9cec7`, `71777f9a`). **`master` was red on
  arrival** and had been since T4: `WorkItem`, `WorkItemStatus` and `WorkItemInput` were gone from
  the regenerated schema while `types.ts` still aliased them — 17 `tsc` errors in 6 files. Part A
  repaired that as its own precondition rather than as scope creep: `workItems.ts` → `board.ts` on
  the renamed `/board` path, `TaskCreateDialog` deleted (`POST /work-items` is gone; the create is
  a route now), and **the board's columns are assembled from the cards** (`cardMove.columnsFor`)
  because a project names its own statuses and no single request answers "every status on this
  board" for the two cross-project ones. Two things worth carrying: the first cut of that sort
  ordered by `position` with unknowns last, which put **`Ready` behind `Done`** on every board
  whose backlog is never a transition target — the fix is kind first, position within the kind; and
  the transition is **not optimistic** (a move can be refused, and M2 gets the rollback machinery
  where a drag earns it), so `useMoveWorkItem`'s optimistic patch and its snapshot-restore test are
  gone with it. `TypeIcon` deliberately does **not** give a Bug the mockup's red: an issue type is
  a row, so a map keyed on the English word breaks silently on a project that renames it, and the
  contract's `level` + `countsTowardCompletion` answer four of the mockup's five colours honestly.
  `LinkBlockDialog` stopped iterating `linkSourceTypeLabels`' keys — `ISSUE` arrived there and
  would have grown a third tab with no picker behind it. `citedFiles.test.ts` caught the one stale
  citation this run produced (`workItems.ts` in `keys.ts`), from the `packages` job, as its header
  predicts.

- **2026-08-29** — fe-react conformance audit (`docs/audits/001-fe-react-conformance-r1.md`, 18
  findings) then Mode FIX over it. **`master` was red on arrival and had been since T16** — 60 `tsc`
  errors in 22 files, CI failing on `7818740c`; the audit's A1 said "unverifiable, static only" and
  should have looked. Repaired all 60 (MUI props on the new primitives, `variant="text"/"contained"`,
  dropped `cn`/`DialogHeader`/`buttonVariants` imports, `PlanCasesTab`'s duplicated import block,
  and the `themeTokenParity` → `tokenSystem` rename `tsconfig.node.json` never followed). Two port
  defects found while doing it and fixed: `ReportView`'s bar chart computed `barPercents` and drew
  no bar, and `PlanCasesTab`'s three reference chips had lost both their JSX braces and their
  remove affordance. Then the findings: 277 `white/N` literals → tokens + a gate; the session token
  → memory; `kit/Field` → `aria-describedby`; `kit/Combobox` → arrow keys + a live `Escape` (it was
  bound to a listbox that never holds focus, in five places); `CaseEditorPage` → keyed by `caseId`;
  seven nav buttons → real links; five stale MUI comments deleted. Suite went 137 red → 84.

- **2026-08-29** — T8 (family 9, the states). **`notistack` is imported by no file.** The split
  survives the swap and is now *two surfaces, one of them sonner*: `<Toaster />` replaces
  `SnackbarProvider` in exactly its position in `app/Providers.tsx` (bottom-right, `visibleToasts`
  3) and takes success/info/warning; `NotificationsProvider` keeps its own portal, its dedupe,
  its ten-second roll-up and its cap, because sonner owns its list and has none of those. The plan's
  "route failures through `toast.error`" was refused for that reason — see T8's landing note.
  The transient surface is a **module function**, `notifications/toast.ts` →
  `toast(message, { variant })`, not a hook: `useNotify()` throws outside `NotificationsProvider`
  and `test/TestProviders.tsx` mounts none, so a hook would have meant *adding* a provider to six
  tests instead of dropping one. sonner's queue is a module singleton, so only `<Toaster />` is
  mounted. **`variant: 'error'` now has no timer** — under notistack the provider owned the
  duration and three page call sites were raising failures onto a 4 s auto-hide, §7 broken past
  every gate. Two facts about sonner 2.0.8 worth keeping: it emits **no `role`** on a toast (the
  aria-live region is `polite`), so the plan's `getByRole('alert')` assertion only holds on
  `NotificationsProvider`; and a toast renders **a tick after** it is raised, so a test needs
  `vi.useFakeTimers({ shouldAdvanceTime: true })` and `findBy*`, never a frozen clock. The
  `testId` option puts a real `data-testid` on the toast. `features/help` is now MUI-free except
  through `DataRegion` and `ScrollX`; **`sections.tsx` (898 lines) needed no edit** — it imports
  only `./prose` and `./RoleMatrix`. Help renders on `DocumentSurface measure="prose"` with no
  `PageHeader`, so the section title is the `<h2>` the tests already expected.

- **2026-08-29** — T9 (the grid engine). `CaseGrid` runs on **TanStack Table v9.2.4** — `useTable`
  + `tableFeatures({ rowPaginationFeature, rowSelectionFeature, rowSortingFeature })`, NOT v8's
  `useReactTable`/`getCoreRowModel()`, and not `stockFeatures`. `@mui/x-data-grid` is now imported
  by no file (its dependency line dies in T16). The grid keeps its whole server-side contract —
  page/size/sort in the URL via `useCaseFilters`, `rowCount` from the server, selection keyed by
  domain id — and renders its own pager (`Cases.Pager.*`), which `DataGrid` used to supply.
  `kit/Table`'s `TableHeaderCell` gained `sortDirection` + `onSort` (a `<button>` in the `<th>`,
  `aria-sort` on it) and lost the decorative `sortable` prop nothing consumed. Two notes for the
  next lane: a heterogeneous column array needs `helper.columns([...])` or `tsc` reports two
  unrelated `ColumnDefBase_Core` types; and `manualFiltering` does not typecheck without
  `columnFilteringFeature`, which is fine — an unregistered feature is a stronger guarantee than a
  flag. Column widths and the kit's second line travel in the `columnMeta` type slot.
- **2026-08-29** — T6 (family 8, the threshold). `features/auth` is the **first MUI-free feature
  directory**: `LoginPage`, `AcceptInvitationPage` and `SsoCallbackPage` render from
  `components/ui` + `components/kit`, plus a local `features/auth/ThresholdLayout.tsx` (the `.thr`
  two-column shell and six one-liners — deliberately not in `kit`, which is §5's nine). `Identifier`
  was **not** used: it is MUI, so the wordmark and the invited address are `.mk`/`.id` inlined; T16
  decides whether `Identifier` is ported or dissolved. RHF + Radix: the remember-me switch needs a
  `Controller` (Radix `Switch` is a button, not a native input) — `register()` alone does not bind
  it. **The full local suite is red and it is not this change** — a nondeterministic 4/16/8 failures
  across a moving set of files, all `Failed to parse URL from /api/v1/…`, present with the change
  stashed; the tree also carried an uncommitted T9 lane. See 007's T6 Step 3.
