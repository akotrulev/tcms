# fe-react — orientation cache

**Baseline commit:** `6de8c9a9` · **Written:** 2026-08-29 · **Verdict on creation:** FULL (no cache existed)

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
| Server state | **TanStack Query owns all of it.** `src/api/queries/`, one file per domain behind a barrel. **One** `keys` factory in `keys.ts` — never add a second |
| Client state | Session in Context; **filter state lives in the URL** (`useSearchParams`) |
| Component library | **MUI v9** + `@mui/icons-material`; X: `x-data-grid` (1 file), `x-tree-view` (2), `x-date-pickers` (**0 — an unused dependency**) |
| Styling authority | MUI `theme` + `sx`. Tokens are CSS custom properties in `index.css`, bridged into `createTheme` in `app/theme.ts`, pinned by `app/themeTokenParity.test.ts` |
| Provider order | `app/Providers.tsx` — **nesting order is load-bearing and documented in the file**: the query client is constructed *innermost* because its global error handlers consume the notification and session-recovery providers |
| Errors | `lib/describeError.ts` is the one place an error becomes user-facing copy (owns `isTransient()`); `app/queryClient.ts` is the one place a failure raises a notification; `components/ErrorBoundaries.tsx` has the root/route/region tiers |
| Data states | `components/DataRegion.tsx` — loading/loaded/failed/empty/stale |
| Display logic | **Shared layer, never the page**: `lib/labels.ts` (each `satisfies Record<Union, string>`, so a new enum member is a compile error until labeled), `lib/format.ts`, `lib/results.ts`, `lib/permissions.ts`, `components/Identifier` for anything monospace |
| `data-testid` | Broad coverage, `Domain.Thing` convention, per-file `TestIdPrefix` const. **Tests query by testid and role, not MUI class** — only 8 files touch MUI internals |
| MUI testid workaround | `lib/muiTestIds.ts` — `inputTestId` / `selectTestId`, because MUI types slot props too narrowly to accept `data-*` |
| Dev fault switches | `lib/devFaults.ts` behind `src/api/client.ts`; `tcmsFaults.{status,hang,expire,drift,only,off}()` in the console. Both guards are a **literal** `import.meta.env.DEV` so Rollup drops the module — routing that check through an imported constant ships it |

## Test-harness rules the suites enforce

- **Contract conformance** — every exchange crossing a `fetch` double is compared against
  `openapi.yaml`. Use `src/test/fixtures.ts`; anchor path-keyed mock arms (`/^\/projects\/[^/]+$/`)
  so a sub-resource isn't caught by a prefix.
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

- **Nothing in `docs/specs/assets/renders/` is implemented.** Nine render families, 42 screens,
  drawn 2026-08-28 and never ported. `docs/plans/007-render-design-implementation.md` is the plan
  for it; before that plan lands, any screen this agent touches is still MUI.
- The renders were opened at 1680px only. No claim holds at 1280px, against a real API, or with a
  fifty-step case (design-language §0, §9.1).
- E2E / Playwright surfaces belong to the `qa-ui-typescript` peer; none are mapped here.

## Session log

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
