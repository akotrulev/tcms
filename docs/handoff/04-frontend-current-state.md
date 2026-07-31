# §4. Frontend — current state

**Versions:** React 19.2, Vite 7 (no Next.js), MUI v7, TypeScript strict + `erasableSyntaxOnly`.

### Phase 0 — Scaffold: DONE
### Phase 1 — Architecture foundations: DONE (walking skeleton closed 2026-07-30)
### Phase 2 — Feature build: DONE (all 10 screens; verified in-browser)
### Phase 3 — Quality layers: DONE (2026-07-30; see §5 for what shipped and what was left open)

**Decision record:**

| Decision | Choice |
|---|---|
| Render target | **Vite SPA + React Router 7.** Internal authenticated tool, no SEO/SSR need. No RSC, so no `'use client'` boundary exists — the skills' RSC lane does not apply. |
| Route topology | `/login` public; everything else behind `<RequireAuth>` under a persistent `<AppShell>` with `<Outlet/>`. Project-scoped routes under `/projects/:projectId/*` because keys are ambiguous without project context. |
| Component library | **MUI v7** + MUI X DataGrid/TreeView. |
| Styling authority | **MUI `theme` + `sx`, single authority.** No Tailwind. Tokens as CSS custom properties bridged into `createTheme` — one colour system. |
| Server state | **TanStack Query.** No server data in any client store. |
| Client state | Session in Context; **filter state in the URL** (`useSearchParams`) per plan §6o/§6q. No Zustand. |
| Breakpoints | **MUI's values adopted wholesale** (xs 0 / sm 600 / md 900 / lg 1200 / xl 1536), mirrored in `src/app/breakpoints.ts`. |

**Design plan (design/visual-identity):**
- **Thesis:** status colour is load-bearing *data* in a TCMS, so the brand palette deliberately retreats. Brand steel is used for actions and active nav only — never on data. Status owns the entire saturation budget.
- **Color:** `#1B2A41` ink · `#2E6E8E` steel (actions) · `#F5F7FA` surface · `#DDE3EA` hairline. Status: `#2E7D5B` passed · `#C0392B` failed · `#B7791F` blocked · `#6B7280` skipped.
- **Type:** IBM Plex Sans + IBM Plex Mono (technical-documentation lineage; Inter would have been the default reach).
- **Layout:** left rail (project + nav) · main content · detail pages split into content + metadata sidebar.
- **Signature:** **the identifier system** — every key, version, release, percentage and SHA (`TC-1`, `v7`, `4.3.0`, `87%`) in Plex Mono with tabular figures. Functionally correct (similar keys stay distinguishable, keys align in columns) and gives the UI an instrument-panel character from the subject's own world. The format is `TC-1` (company-scoped, §6t) as of the corrections in §9 — the design thesis was unaffected by that change, only the string.
- **Design-critique revision (required by the skill's gate):** critiqued against the three AI-default looks. Palette avoids cream+serif+terracotta and acid-on-black. First layout pass was "white cards with shadows on grey" — the safe SaaS default — and pushing away risked landing on default #3, the hairline-rule broadsheet. **Revised to:** one elevated surface per region, hairline dividers *inside* it, restrained 4px radius. Density comes from row rhythm, not from stripping geometry.

### Files written

| Path | Purpose |
|---|---|
| `src/index.css` | Design tokens only (+ minimal reset, `.identifier`, reduced-motion floor). No component styles. |
| `src/app/theme.ts` | `createTheme` with `cssVariables: { colorSchemeSelector: 'data' }`, light+dark schemes, type scale, component defaults. Exports `statusColors`/`lifecycleColors` maps. |
| `src/app/breakpoints.ts` | TS mirror of MUI breakpoints, for client branching and the QA suite to import. |
| `src/app/Providers.tsx` | QueryClient + ThemeProvider + CssBaseline + SnackbarProvider + AuthProvider, mounted once. |
| `src/auth/authContextValue.ts` | The Context object + `AuthValue` type. Split out so the provider file exports only a component (Fast Refresh). |
| `src/auth/useAuth.ts` | Consumer hook — React 19 `use()`, throws outside the provider. |
| `src/auth/AuthContext.tsx` | `AuthProvider` only. Session state, memoized value. Never holds server data. |
| `src/lib/safeStorage.ts` | Guarded storage wrapper — degrades to in-memory, discards corrupt entries, logs once. |
| `src/api/client.ts` | Fetch wrapper + `ApiError` (with `fieldErrors`) + `TimeoutError` + `isAbort()` + `budget()` + per-class `TIMEOUTS` + `qs()`. |
| `src/api/schema.d.ts` | **Generated** — do not hand-edit. |
| `src/api/types.ts` | Convenience aliases over the generated schema + `CaseFilters`. |
| `src/mocks/fixtures/core.ts` | Users, teams, roles, projects, classification, statuses, configurations, suites, conditions, artifacts, doc pages, plans, runs. |
| `src/mocks/fixtures/cases.ts` | 27 seeded cases carrying the awkward shapes + `buildCaseDetail()`. |
| `src/mocks/fixtures/reporting.ts` | Case/suite history with version markers, dashboard, plan statistics. |

**Fixtures deliberately carry the awkward rows** the UI must handle: a case whose results span three versions with a v5 edit followed by failures; a suite whose membership grew mid-cycle; an `AUTOMATED` case that stopped reporting; a doc block with zero linked cases; a plan at 89% pass over 97% coverage with two unmet exit criteria; an orphaned case owned by a deactivated user.

### Files added 2026-07-30 (Phase 1 close + Phase 2)

**Mock API — stateful.** `db.ts` holds a `structuredClone` of the fixtures and the handlers read *and write* it, so create/edit/result round-trips land in the list. A stateless handler set would have made every write screen untestable.

| Path | Purpose |
|---|---|
| `src/mocks/db.ts` | Mutable store, `PREFIX-N` **per-company** display-key allocation (§9), derived suite counts, lazily-materialized run executions, `recountRun()`, computed (never stored) derived work items. |
| `src/mocks/handlers.ts` | Every path in `openapi.yaml`. Optimistic-concurrency 409s, field-keyed 400s, bulk preview/commit, a real draft→approve guard. 220 ms latency so loading states are reachable. |
| `src/mocks/browser.ts` | `setupWorker`. `public/mockServiceWorker.js` is committed. |
| `src/api/queries.ts` | **The data module.** Every read/write hook, one `keys` factory, query keys carrying every dependency, `enabled` gates, per-endpoint timeout budgets. |
| `src/lib/labels.ts` | Enum→label maps, each `satisfies Record<Union, string>` so a new enum member is a compile error until labeled. |
| `src/lib/format.ts` | One canonical format per type. Timezone always labeled. `compareReleaseVersions` (4.10.0 sorts after 4.9.0). |
| `src/lib/results.ts` | `executedCount` / `passRate` / `sumCounts`. Split from the component so Fast Refresh keeps working. |
| `src/components/DataRegion.tsx` | **The three-state binding.** loading / loaded / failed / empty / **stale-after-failed-refetch**; `isAbort()` never renders as failure; three distinct empty-state kinds. One fix here fixes every list. |
| `src/components/ResultBar.tsx` | Stacked counts, `PassRateSummary` (both denominators side by side), `DenominatorToggle`. |
| `src/components/Identifier.tsx` | The design signature — the single place `.identifier` is applied. |
| `src/components/StatusChip.tsx` | Status / lifecycle / priority / automation / health. Colour is never the only signal. |
| `src/components/PageHeader.tsx` | One page-header + breadcrumb treatment; emits `<title>` via React 19 hoisting (no `react-helmet`). Plus `Panel`. |
| `src/app/AppShell.tsx` | Persistent layout: rail (CSS visibility swap, `Nav.Rail` / `Nav.Drawer`), active item marked three ways, semantic `<footer>`. |
| `src/app/router.tsx` | Route topology; heavy screens `lazy()`-split. `cases/new` declared before `cases/:caseId`. |
| `src/auth/RequireAuth.tsx` | Coarse gate; carries the attempted path so sign-in returns there. |
| `src/features/auth/LoginPage.tsx` | RHF + Zod, field-mapped server errors. |
| `src/features/projects/ProjectsPage.tsx` | Project picker. |
| `src/features/dashboard/DashboardPage.tsx` | §6o — tiles serialize their filter predicate into a `<Link>`; composable, honest automation denominator. |
| `src/features/cases/{CaseListPage,SuiteTree,CaseFilterBar,CaseGrid,BulkActionDialog,useCaseFilters}.tsx` | Suite tree + server-side grid, URL-backed filters, bulk preview-then-commit. |
| `src/features/cases/{CaseDetailPage,CaseEditorPage}.tsx` | Dual step format, inherited-condition markers, stale doc links; editor schema = only fields that persist. |
| `src/features/runs/{RunListPage,RunDetailPage,ExecutionPage}.tsx` | §6 execution — precondition checklist gating Blocked, per-step results, pinned case version. |
| `src/features/plans/{PlanListPage,PlanStatisticsPage}.tsx` | §6p — pass rate + coverage side by side, aggregation policy always stated, exit criteria with target *and* actual. |
| `src/features/history/{CaseHistoryPage,SuiteHistoryPage}.tsx` | §6p — version markers interleaved into the timeline; membership-change caveat fires from the data. |
| `src/features/docs/DocsPage.tsx` | §6i — stable block-id anchors, inline per-block coverage markers. |
| `src/features/artifacts/ArtifactsPage.tsx` | §6n — both scopes with the inheritance marker. |
| `src/features/board/BoardPage.tsx` | §6l — derived cards computed per read; illegal drops refused *before* the request, with keyboard move buttons. |
| `src/features/admin/TeamsPage.tsx` | §6b/§6k — org chart, assignments (team vs direct), roles with permissions expanded. |

### Contract changes made (openapi.yaml is the source of truth — these were changed there, then regenerated)

1. **`BulkResult` reshaped** from counts (`affected` / `alreadyCompliant`) to **lists of keys** (`changed` / `unchanged` / `skipped[{key, reason}]` / `total`). §6f requires the UI to *name* what it touched; `affected: 38` cannot be rendered as a preview, and a blanket toast over a partial failure is the worse half of the defect.
2. **`required` arrays added** to eight inline objects that had none: `RepositoryDashboard.automationCoverage`, `.recentActivity[]`, `DashboardBreakdown.segments[]`, `PlanStatistics.byConfiguration[]` / `.bySuite[]` / `.trend[]`, `DocBlock.coverage`, `User` (status, teams), `RoleAssignment.team`. This is the handoff's own lesson #1 recurring — **if you add a schema, add its `required` array**, or the generated type is all-optional and no consumer can use it without null-checking fields that are always present.
3. Bulk calls now use the contract's own `action` / `payload` naming rather than invented `operation` / `value`.
