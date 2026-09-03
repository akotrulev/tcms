# fe-react — orientation cache

**Baseline commit:** `306a36c5` · **Last verified:** 2026-09-04 · **Verdict this run (015 T5):** DELTA
(014 M4's import screens and 015 T1–T4's contract had moved `openapi.yaml`, `schema.d.ts`,
`fixtures.ts` and `devFaults.ts` since the baseline below; no `package.json` / lockfile / `tsconfig`
change, so the version lane held. T5 added the account, repositories and assistant-panel seams
recorded below.)

**Session log**
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
