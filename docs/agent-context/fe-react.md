# fe-react — orientation cache

**Baseline commit:** `ac848172` · **Last verified:** 2026-08-31 · **Verdict this run:** DELTA (the delta since `f6315aeb` was 012 T1–T5 — backend, contract and both generated schema copies — so the version lane and every frontend seam held; the seams below moved because 012 T6 added the project board's data layer)

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
| Routing | React Router 7 (`react-router-dom`), 9 `<Route>` in `src/app/router.tsx` |

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
| Client state | Session in Context; **filter state lives in the URL** (`useSearchParams`) |
| Component library | **shadcn/ui over Radix** in `components/ui/` (18 files) + the house kit in `components/kit/` (16). ~~MUI v9~~ → gone in T16; `@mui/*`, `@emotion/*`, `notistack` are in no import and no `package.json` |
| Styling authority | **Tailwind v4, single authority.** Preflight on, one stylesheet (`src/index.css`), no `sx`, no CSS-in-JS, no CSS Modules. Tokens are custom properties bridged into utility names by `@theme inline` (*inline* is load-bearing — it emits `var(…)` so the `[data-color-scheme]` swap moves every utility). `app/tokenSystem.test.ts` checks the system against itself |
| Lines & washes | `border-line` / `border-line-strong` / `bg-line` / `bg-wash-1..3` / `bg-rail` — added in the audit-fix pass to replace 277 `white/N` alpha literals the T16 port left. **A raw `-white/` alpha in `src/` now fails `tokenSystem.test.ts`**; use these |
| Session token | **In memory only** — `auth/accessToken.ts`, never `localStorage`. A reload signs the user out (no refresh endpoint until backend M4). Cross-tab sign-in carries the token on `sessionChannel`'s `signed-in` message |
| Provider order | `app/Providers.tsx` — **nesting order is load-bearing and documented in the file**: the query client is constructed *innermost* because its global error handlers consume the notification and session-recovery providers |
| Errors | `lib/describeError.ts` is the one place an error becomes user-facing copy (owns `isTransient()`); `app/queryClient.ts` is the one place a failure raises a notification; `components/ErrorBoundaries.tsx` has the root/route/region tiers |
| Data states | `components/DataRegion.tsx` — loading/loaded/failed/empty/stale |
| Display logic | **Shared layer, never the page**: `lib/labels.ts` (each `satisfies Record<Union, string>`, so a new enum member is a compile error until labeled), `lib/format.ts`, `lib/results.ts`, `lib/permissions.ts`, `components/Identifier` for anything monospace |
| `data-testid` | ~1470 (676 literal + 794 composed), `Domain.Thing` PascalCase, per-file `TestIdPrefix` const. `ClassificationPicker.tsx:150` documents `Classification.Value.<id>` as a **locator contract QA page objects key on** — do not rename it here |
| Field ↔ error wiring | `components/kit/Field.tsx` clones its child to add `aria-describedby` pointing at `${htmlFor}-message`. Callers pass nothing; do not re-add per-call-site ids |
| Dev fault switches | `lib/devFaults.ts` behind `src/api/client.ts`; `tcmsFaults.{status,hang,expire,drift,only,tracker,issue,rank,wip,off}()` in the console. Both guards are a **literal** `import.meta.env.DEV` so Rollup drops the module — routing that check through an imported constant ships it. `issue('conflict')` fabricates the 409 that names the legal moves (and since 012 T6 answers the board's move endpoint too, which its own comment had always promised); `rank('conflict')` is the 409 that proves the rank rollback, `rank('rebalance')` the 200 that must **drop** the patch, and `wip()` the breach on a move that still lands. Every arm's path test is **anchored** — an unanchored `startsWith('/issues')` would fault the detail read |
| Issue screens | `features/tracker/` — `WorkTablePage` (`/projects/:id/work`, five views on `?view=`), `IssueNewPage` (`/work/new`), `IssueDetailPage` (**`/issues/:key`, outside the project segment**, dispatching on `type.level` to `EpicPanels`/`StoryPanels`/`TaskPanels`). `TypeIcon` derives its colour from `level` + `countsTowardCompletion`, never from the type's name |
| Project front door | `/projects/:projectId` is **`features/projects/ProjectOverviewPage.tsx`** (011 T7). ~~`features/dashboard/DashboardPage.tsx`~~ no longer exports a page — it exports `DashboardBody` and `HealthPanel`, the repository-dashboard **regions**, which the overview mounts below its delivery half. **The file keeps its name deliberately**: `DashboardPage.tsx` is cited from `lib/statusColors.ts`, from `HealthTileOrderTest.java` in the backend suite, and from `renders/06-analytics.html` |
| The three boards | `BoardPage` and `TeamBoardPage` mount `Board` (kanban). **`MyWorkPage` does not** — `my-work.html` is two tables over ONE `GET /board?mine=true`, split on `kind === 'ISSUE'`, plus a private notes panel (011 T7). `linkFor` lives in `features/board/cardLink.ts`, not in `BoardPage.tsx`: a file exporting a component and a function is oxlint's `react(only-export-components)`, and the gate is zero warnings |
| Project settings | `/projects/:projectId/settings/{general,workflow,classification,review,notifications,trackers}` — six screens, all **routed for everybody** and read-only for a caller without the write key (§31). `general` holds the archive button (011 T7 moved it off the overview's header, with `ArchiveProject.test.tsx`) |
| A DELETE with a body | **`api.delWithBody(path, body, options)`** — the one in the product, for the workflow status removal. Registered in `test/contractDoubles.ts`'s `METHODS` **and** `SENDS_A_BODY`; a body smuggled into `api.del`'s options is invisible to that seam, which reads the second argument positionally |
| Empty states | `DataRegion`'s `EmptyState` takes `kind` / `title` / `body` / `action` and, since 011 T7, an optional lucide **`icon`**. `kit/StateBlock`'s `empty` is the other one and has a fixed description — it is not the one to reach for when the panel has to say what the missing thing IS |

## Test-harness rules the suites enforce

- **Contract conformance** — every exchange crossing a `fetch` double is compared against
  `openapi.yaml`. Use `src/test/fixtures.ts`; anchor path-keyed mock arms (`/^\/projects\/[^/]+$/`)
  so a sub-resource isn't caught by a prefix. `/boards/{id}` vs `/boards/{id}/cards` is exactly the
  collision. **`drivingAFailedWrite()` / `drivingAFailedRead()` / `renderingIntoAnErrorBoundary()`
  take NO arguments** — the reason goes in a comment beside the call, not in a parameter.
- **Never build your own `QueryClient` in a test** — only `createTestQueryClient`'s has the caches.
- A test whose render trips an error boundary **fails** unless it calls
  `renderingIntoAnErrorBoundary()` / `drivingAFailedRead()` / `drivingAFailedWrite()` and says why.
- **Cited files** — `packages/client/src/test/citedFiles.test.ts` fails if a path named in a comment
  is not on disk. It scans `frontend/src` for **`.ts`/`.tsx` only** (`.css` is not scanned), and a
  stale citation here **reddens the `packages` job**, not the frontend one.
- **Tier claims** — `tierClaims.test.ts` fails if a comment claims somebody lacks a permission the
  roles spec §A3 says they hold.

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
- **012 T6 left twelve of `projectBoards.ts`'s fourteen hooks with no caller** — the reads and the
  label/board writes typecheck against the contract and are unexercised until T7–T9. `useUpdateBoard`
  is the one whose behaviour is worth doubting: it invalidates `keys.board(board.id)` from the
  *response's* id rather than the argument's, which is right and untested.
- **Nothing in 012 M2 renders.** `?view=board` still shows M1's placeholder panel until T7, and the
  three new fault arms have been driven through mocked `api.*` calls but never armed in a browser
  against a real backend.

## Session log

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
- **2026-08-29** — FULL orientation, no code written. Compared `CaseEditorPage`'s New-case state
  against `assets/case-authoring-redesign.html` frame B; found the IA implemented and the design
  not, in seven places. Measured the MUI footprint (156 files, but only 8 coupled to internals; X
  packages in 3 files; `x-date-pickers` unused). Verified the design-language spec's claim that the
  render kit is the app's dark theme — true: `--steel` **is** `--color-action-primary`, and the port
  is two token additions, one split, three hue changes. Wrote
  `docs/plans/007-render-design-implementation.md` (17 tasks, Tailwind v4 + shadcn/ui). Cache
  created.
