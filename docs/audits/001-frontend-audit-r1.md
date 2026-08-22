# 001 — `frontend/` audit · Round 1 (baseline)

**Date:** 2026-08-22
**Auditor:** `code-auditor` (FEATURE lane — `ep-feature-audit`)
**Mode:** A — BASELINE. No prior register or round exists for this subject.
**Subject:** `frontend/` only. `backend/` and `packages/` were read **as evidence about the contract
and the wire**, never audited.
**Evidence mode:** static only. No build, no test run, no live app, no browser. Per the standing
directive, gates run on `shanks` in a separate pass; nothing here quotes a test result.

## The driving question

> **Where does the SPA diverge from its own stated rules and from the contract it is generated from,
> and which of its surfaces has no observer?**

Chosen because this codebase's defects are not going to be missing null checks — the comments carry
the reasoning and the harness computes most catalogue-wide invariants already. What is left is
(a) rules the repo states and one screen does not follow, (b) values whose *unit or meaning* the
contract never states, and (c) code nothing looks at.

## Scope

| In | Out |
|---|---|
| `frontend/src/**` — all 348 TS/TSX files | `backend/**` (read-only evidence) |
| `frontend/package.json`, `vite.config.ts`, `vitest.config.ts`, `.oxlintrc.json`, `.env.example` | `packages/**` (read-only evidence: the second `schema` copy) |
| `docs/specs/openapi.yaml`, roles spec §A3, `docs/verification/*` as the standards code is judged against | E2E / page objects (none exist; `qa-ui-typescript`'s lane) |
| | Anything requiring execution: bundle size, Lighthouse, runtime behaviour |

Assumption stated rather than asked: "audit the frontend" is taken as *how does it really work and
where is it wrong*, not *does it satisfy a story list*. `openapi.yaml` is therefore used as a
reference standard, not consolidated into a requirement register.

## Orientation

- **Cache gate: TRUST.** `docs/agent-context/fe-react.md` is stamped `Last verified: 2026-08-22`
  at **§249**, and §249 is HEAD (`62875cc5`/`94c471da`). Its spine was re-derived anyway where a
  finding cites it.
- **Version lane, re-verified against `node_modules` this round** (not taken from any document):
  React **19.2.8** · Vite **8.1.5** · TypeScript **6.0.3** · `@mui/material` **9.2.0** ·
  `@mui/x-data-grid` **9.10.1** · `react-router-dom` **7.18.2** · `@tanstack/react-query`
  **5.101.4**. `#confirmed`
- **Layout:** `src/{a11y,api,app,auth,components,features,lib,notifications,test}`; 348 source
  files, 125 of them tests; 19 feature areas; 39 `*Page.tsx`.
- Judged against **its own** stack: Vite SPA, so no RSC/`'use client'` expectations were applied.

---

# Surfaces

Letters are permanent. A later round reuses them.

| | Surface | Verdict this round |
|---|---|---|
| **A** | API & contract layer (`api/client.ts`, generated `schema.d.ts`, the five contract-conformance suites) | **Sound.** Strongest surface in the repo. |
| **B** | Server-state discipline (`api/queries/*`, `keys.ts`, invalidation, `enabled`, timeouts) | **Sound with two systemic gaps** — C2, C8. |
| **C** | Routing & shell (`app/router.tsx`, `AppShell.tsx`, three boundary tiers) | **Sound.** One maintainability observation. |
| **D** | Session & auth (`auth/*`, multi-tab channel, session recovery) | **Sound.** Nothing found. |
| **E** | Permissions & gating (`lib/permissions.ts`, `useCan`, `PermissionGate`) | **Sound.** Catalogue provably in sync. |
| **F** | Shared display layer (`labels`/`format`/`results`/`statusColors`/`DataRegion`/`Identifier`) | **Two leaks** — C6, C10. |
| **G** | Feature screens | **Where every substantive finding lives** — C1, C3, C4, C5, C7. |
| **H** | Resilience, errors, dev fault seam | **Sound.** All three DEV guards are literal. |
| **I** | Accessibility | **One systemic gap** — C9. |
| **J** | Test harness & observability | **Sound harness, uneven coverage** — C11. |
| **K** | Docs & orientation hygiene | **Three drifts** — D1, D2, D3. |

## A — API & contract layer

Computed this round, not read off a document:

- **128 of 128** contract paths are typed by `frontend/src/api/schema.d.ts`. `#confirmed`
- `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` are **byte-identical**
  (`md5 555c049c8037f1cfb3cb4e84cbe70d09`), and both are newer than `openapi.yaml`
  (18:13 vs 18:12). The half-done regeneration this repo has paid for has not happened.
- `api/contractOperations.test.ts:96-183` checks caller→spec with the *method*, derives the verb
  set from `client.ts` rather than hard-coding it, and fails on an unreadable URL expression
  instead of skipping it. `contractParameters` / `contractFixtures` / `contractBodies` /
  `contractDoubles` extend the same discipline to query keys, fixtures, served bodies and doubles.
- Zero `useQuery`/`useMutation`/`useInfiniteQuery` outside `src/api/queries/` (negative claim; search:
  `grep -rn "useQuery(\|useMutation(\|useInfiniteQuery(\|useQueries(" src` minus `src/api/queries/`
  and `*.test.*` → empty). Zero raw `fetch(` outside `api/client.ts` except
  `app/VersionSkewWatcher.tsx:35`, which fetches `/index.html` and is not an API call.

**Rollup.** This surface is doing the job the repo claims for it: the class of defect the harness was
built for (an operation the contract never declared) cannot survive here, and the two generated
copies are pinned to each other by a real comparison rather than a habit. The one structural gap is
directional — every check starts from the caller or from the spec's *declared* paths, and nothing
asks the reverse question "which declared operation does no client reach". That is defensible (the
CLI and MCP are other clients) and is recorded as an observation, not a finding.

## B — Server-state discipline

`keys.ts` defines 68 keys with the reasoning for each placement, and the placements are considered
rather than incidental (`configurations` deliberately outside `runs`; `runSchedules` deliberately
outside `runs`; `caseSearch` deliberately outside `['projects', …]`). Timeout budgets exist in three
classes (`client.ts:37-44`); 13 of 161 calls override the `interactive` default, which is the right
shape — but see C1 for the one place the class is wrong.

**Rollup.** The design is right and two systemic things sit *beside* it rather than in it: prefix
invalidation is spelled by hand at 29 sites instead of through the factory (C2), and one key —
the newest — is invalidated by nothing at all (C8). Neither is a bug a user can see today; both are
the shape where the next reader's correct-looking change goes stale silently.

## C — Routing & shell

- Element-based `<Routes>`, 40 lazy routes each wrapped in its own `<Suspense fallback={<RouteFallback />}>`.
- Three boundary tiers are all mounted: `RootErrorBoundary` (`main.tsx:67`), `RouteErrorBoundary`
  (`AppShell.tsx:496`, wrapping `<Outlet />`), `RegionErrorBoundary` (`DataRegion.tsx:177`).
- **The reset trap is not present.** `ErrorBoundaries.tsx:174-186` gives the route boundary
  `resetKeys={[location.pathname, location.search]}`, so a caught error clears on navigation
  instead of persisting over the next page. This is the single most common React-boundary defect and
  it was already reasoned about in the file's header.

Observation, not a finding: `AppShell.tsx` is **41.7 kB in one file** and `router.tsx` 29.5 kB.
Both are internally sectioned and commented, and §242 put a permission gate in the former on
purpose, so this is noted as the place a future reader will pay interest — not as a defect.

## D — Session & auth

`AuthContext`, `sessionChannel`, `SessionRecoveryProvider`, `SessionGate`, `RequireAuth`,
`adoptSession` (the SSO writer) and `safeStorage` were read. Storage goes through `safeStorage`
everywhere it matters, `sessionStorage` is used only for the version-skew reload guard and the SSO
return path (correctly — tab-scoped), the multi-tab channel has its own suite, and the
`localStorage`-is-not-synchronously-consistent hazard is named at `AuthContext.tsx:135`.
**Nothing found.** No finding is recorded rather than a weak one manufactured.

## E — Permissions & gating

Three-way comparison computed this round:

| Source | Keys |
|---|---|
| `openapi.yaml` `PermissionKey` enum (line 13870) | 55 |
| `frontend/src/lib/permissions.ts` `permissionAreas` | 55 |
| Difference, either direction | **none — and the order is identical** |

Roles spec §A3 mentions two keys the catalogue lacks (`suite:comment`, `plan:comment`); both appear
only in the *argument for not creating them* (`2026-07-30-tcms-roles-permissions-naming.md:174`,
`:597`). Not a drift. `#confirmed`

Ten catalogue keys are never named in production source: `doc:read`, `artifact:read`,
`project:read`, `company:read`, `company:settings`, `company:create`, `company:delete`,
`company:suspend`, `role:manage`, `system:settings`. Seven are read gates the server owns and four
are SYSTEM-scope operator work that has no SPA screen at all. **Expected, not a finding** — recorded
so a later round does not re-derive it.

`useCan`'s fail-open-on-error policy (`auth/useCan.ts:25-33`) was examined specifically for
disclosure risk, since §218/§222 introduced screens whose *reads* are gated. It holds:
`SsoProvidersPage.tsx:60-62,149` passes `allowed` into the read hooks' `enabled`, so a fail-open
produces a request the server refuses rather than data it should not have shown.

## F — Shared display layer

Clean on the checks that matter most: **no** date/number formatting outside `lib/format.ts`
(negative claim; search: `toLocaleDateString|toLocaleTimeString|toLocaleString|Intl.DateTimeFormat|Intl.NumberFormat`
across `src`, excluding `lib/format.ts` and tests → empty). No Tailwind. Every percentage division
found is guarded against a zero denominator (`ResultBar.tsx:41`, `DashboardPage.tsx:393-396,498`,
`PlanStatisticsPage.tsx:1082` returns null below two points). `humanize` is used only on contract
fields that are genuinely open strings (`type: string` at `openapi.yaml:12103`, `:12765`) — checked
because its own docstring forbids more.

Two leaks: C6 (a `formatRatio` re-implemented inline) and C10 (execution-status colour tokens reused
for unrelated semantics).

## G — Feature screens

Traced the newest slice end to end (§249 `/search`), plus `ReportsPage`, `PlanListPage`,
`PlanStatisticsPage`'s trend panel, `CaseHistoryPage`, `CaseFilterBar`, `SsoProvidersPage`,
`DashboardPage`'s three charts and `RunListPage`'s pass-rate cell.

**Rollup.** Every finding of substance is here, and they share one shape: **the newest screen is the
least conformant to the repo's own accumulated rules.** §249 is a well-reasoned screen — the
two-emptinesses design is genuinely good and its predicate is genuinely shared — that nonetheless
skipped three conventions three older screens already established (debounce, typeahead budget,
disambiguation that survives a failed read). The other findings here are older and quieter: a panel
whose copy contradicts its contract (C3), two columns that can never hold a value (C5).

## H — Resilience & the dev fault seam

All six `import.meta.env.DEV` guards are **literal** at the point of use — the four that decide
whether dev-only code ships (`main.tsx:39`, `api/client.ts:187`, `api/client.ts:379`,
`lib/reportClientError.ts:91`) plus two behavioural ones in `app/VersionSkewWatcher.tsx:26,75`.
Literalness is the property `deploy.yml` greps `dist/` for; routing any of them through an imported
constant is how the mock layer once shipped as a 485 kB chunk. `describeError` is the single place an
error becomes copy; `app/queryClient.ts` the single place a failure notifies. No
`dangerouslySetInnerHTML`, no `@ts-ignore`, no `@ts-expect-error`, no `catch {}` anywhere in `src`.

## I — Accessibility

`SkipToContentLink`, `useRouteFocus` (`AppShell.tsx:413`), an `AnnouncerProvider`, **59
`role="alert"` alerts across 52 non-test files**, `aria-label` on every icon-only button I sampled (five checked at
`AppShell.tsx:719,911`, `AuditLogPage.tsx:280`, `ReportsPage.tsx:338,357` — an automated sweep
flagged 14 and **all five verified were false positives**, so the sweep is not reported as a finding).
The real gap is C9: content that changes without a navigation is never announced.

## J — Test harness & observability

The harness is the best-engineered part of this repo and its rules are enforced by sweeps rather than
by review: `renderFailures.test.tsx` sweeps every test source for un-declared boundary trips,
`citedFiles.test.ts` fails on a comment naming a file that is not on disk, `tierClaims.test.ts`
fails on a comment claiming somebody lacks a permission §A3 grants them, `contractPairing.test.ts`
pins the byte-identical region across two stacks.

Two things it does not cover: C11 (twelve components with no observer) and — carried forward from
the cache's own §201 entry, re-read this round and **still true** — `test/queryClient.ts` gives
`createTestQueryClient` no error handlers, so a *region whose fetch failed* renders invisibly in a
test. That is recorded in the cache as a finding already; it is not restated as a new one.

## K — Docs & orientation hygiene

`docs/verification/frontend-browser-checks.md` carries rows for §247, §248 and §249 and correctly
carries none for §246 (`docs/handoff/246-the-company-data-export.md:5` states no file under
`frontend/` was touched). **No landed frontend slice is missing its §65 row** — checked §200–§249
against both queues. The drifts are D1–D3, all in prose that reads as current.

---

# Findings

Capped at 15. Class is orthogonal to severity and says who acts.

## Data issues — the code is right, what it runs on or is documented as is wrong

| ID | Sev | Finding | Evidence (read this round) |
|---|---|---|---|
| **D1** | major | **`CLAUDE.md` — the file the project instructs everyone to read first — states the wrong UI framework major.** It says MUI **v7**; the installed and declared version is **9.2.0**. The orientation cache already recorded the correction in §94 and flagged that both documents were wrong; only the cache was fixed. A reader following `CLAUDE.md` will reach for v7 slot APIs, and the cache's own §249 entry records that exact class of loss (`inputTestId` returning `as never`, v9's `slotProps.htmlInput`). | `CLAUDE.md:264` (“MUI v7 (with X DataGrid/TreeView)”) · `frontend/package.json:31` (`"@mui/material": "^9.2.0"`) · `node_modules/@mui/material/package.json` → `9.2.0` · `docs/agent-context/fe-react.md:2378` (“~~MUI v7~~ → **MUI v9** … both this file and `CLAUDE.md` still said v7”) |
| **D2** | minor | **Two measured test counts for the same day disagree.** `CLAUDE.md` says 1679 “(measured on shanks 2026-08-22)”; the cache says 1692/1692 across 125 files, also measured on shanks on 2026-08-22 and stamped as the first entry in five to quote a real run. 125 test files is confirmable on disk; 1679 is not reachable from anything. A stale count is how the repo's own “eighth drift” note happened. | `CLAUDE.md:128` · `docs/agent-context/fe-react.md:5,26` · `find frontend/src -name '*.test.*' \| wc -l` → **125** |
| **D3** | minor | **A comment asserts an endpoint is unimplemented five months after it shipped, and leaves an instruction that is now due.** `PlanCreateDialog`'s `onCreated` still lands on the plan list because “`/plans/:planId` is the plan STATISTICS route, and that endpoint is deliberately unimplemented server-side (§38)”, closing with “Point this back at the plan the day that endpoint is served.” It is served, the screen is 1,294 lines, and the list's own rows already link to it. Not struck through with the repo's mandatory “→ superseded” marker, so it reads as current reasoning. | `frontend/src/features/plans/PlanListPage.tsx:117-127` (the claim) · `:245,249` (rows already link to `/plans/:planId`) · `backend/src/main/java/com/tcms/execution/PlanStatisticsService.java:317,827` · `frontend/src/features/plans/PlanStatisticsPage.tsx` (exists, 1294 lines) |
| **D4** | nit | **`frontend/.env.local` sets `VITE_API_MODE=live`, a variable deleted with the mock layer in §107.** Untracked and machine-local, so the blast radius is one developer believing a mode switch exists. `.env.example` is clean and does not mention it. | `frontend/.env.local` (2 lines) · `docs/agent-context/fe-react.md:2411` (“`VITE_API_MODE` is gone with the mock (§107)”) · `frontend/.env.example` (no mention) |

## Code issues — the logic is absent, incomplete, or inconsistent

| ID | Sev | Finding | Evidence (read this round) |
|---|---|---|---|
| **C1** | major | **The cross-project search fires one request per keystroke, and does it under the wrong timeout class.** `onChange` writes straight to the URL, the URL is the query key, so typing *“payment gateway”* issues ~14 requests against the app's only company-wide, multi-project read. Three older search boxes in this same repo debounce — and `ConditionsPanel`'s comment records *why* there is no shared hook to reuse, so the convention was known and available. `CaseFilterBar`'s own comment states the URL half of the rule. Separately, the hook takes `TIMEOUTS.interactive` (10 s) although `client.ts` defines `typeahead: 3_000` for exactly this and the two other typeaheads use it. Cheap to fix, and the cache's §249 “deliberately absent” list does **not** claim the omission was a decision. | `frontend/src/features/cases/CaseSearchPage.tsx:106` (`onChange={… setParam('q', event.target.value)}`) · `frontend/src/api/queries/cases.ts:122-137` (no debounce; `keepPreviousData` masks the flicker but not the traffic), `:130` (`timeoutMs: TIMEOUTS.interactive`) · `frontend/src/api/client.ts:37-44` (`typeahead: 3_000` — “a typeahead that waits 10s is broken”) · `frontend/src/features/cases/CaseFilterBar.tsx:67-68,86-90` (300 ms) · `ConditionsPanel.tsx:207-218` (250 ms, “this project has no shared debounce hook”) · `CaseEditorPage.tsx:195-204` (250 ms) · `docs/agent-context/fe-react.md:45-48` (absences list — debounce not among them) |
| **C2** | major | **29 hand-built query-key prefix arrays bypass the single `keys` factory**, across seven query modules. Because `keys.cases(p, filters, sort)` carries the filter object, prefix invalidation needs the prefix — and there is no `keys.casesRoot(p)` to give it, although `keys.workItemRoot` proves the pattern was available. Renaming a prefix inside `keys.ts` therefore breaks up to 29 invalidations with **no type error**: an invalidation targeting a key nothing uses is a silent no-op whose only symptom is stale data. Partly covered incidentally by component tests that assert a refetch after a write, and there is one keys-level test (`orgChartInvalidation.test.tsx`) — but nothing structural for the `cases`/`plans`/`runs`/`artifacts`/`conditions` prefixes. | `frontend/src/api/queries/cases.ts:309,346,378,404,645,688,801` · `library.ts:104,146,147` · `suites.ts:74,103` · `runs.ts:111,112,141,142,167,168,480` · `plans.ts:234` · `proposals.ts:106,107,122` · `documentation.ts:289,346,390,412,422,595` · `keys.ts:37-38` (the shape being duplicated), `:303` (`workItemRoot` — the pattern that exists) |
| **C3** | major | **The Saved-reports panel presents a per-caller list as if it were the project's, and adds an Owner column that can only ever name the caller.** The contract is unambiguous — *“**Yours, not the project's.** … two people asking for the same project's saved reports receive different lists”* — and explicitly warns that a list which quietly means “mine” is indistinguishable from one meaning “everyone's”. The screen renders exactly that ambiguity: panel titled “Saved reports”, empty state “No saved reports”, and an **Owner** column per row. A lead who knows a colleague saved a report and cannot see it reads this as data loss. (The `report:view` gate on **Delete** was checked and is *correct* — `openapi.yaml:5661` requires that key and the row is the caller's own. The comment at `ReportsPage.tsx:70-74` already defends it. Not a finding.) | `frontend/src/features/reports/ReportsPage.tsx:165` (title), `:172-173` (empty copy), `:296-299` (Owner header), `:313-315` (`definition.owner.displayName`) · `docs/specs/openapi.yaml:5568-5580` (“Yours, not the project's … otherwise indistinguishable”), `:5591-5593` |
| **C4** | minor | **§249's two-emptinesses design collapses to the wrong sentence whenever the disambiguating read fails.** `readerHasNoProjects` is `projects.data?.length === 0`, which is `false` when the `useProjects` query **errored** or has not resolved — so a member with no grants is shown *“No case matches”*, the precise confident falsehood the screen was built to prevent. The failure is silent: nothing renders the projects query's error. A third branch (“cannot say”) is what the design needs. | `frontend/src/features/cases/CaseSearchPage.tsx:80-81`, `:136-152` · `docs/agent-context/fe-react.md:28-32` (the rule the screen states for itself) |
| **C5** | minor | **The plan list's “Pass rate” and “Coverage” columns are structurally always em dashes**, and the file's header says those two columns are the whole reason the screen is a table rather than cards. The server sets both to `null` on every row, permanently and by design, with the reason recorded on the backend. No comment on the frontend side says so, so the screen promises a comparison it cannot make. | `frontend/src/features/plans/PlanListPage.tsx:33-36` (“a lead comparing four plans wants the pass rate and coverage columns to line up”), `:300-305` (`formatRatio(plan.passRate)` / `(plan.coverage)`) · `backend/.../execution/TestPlanView.java:23-28` (“**null, always, in this slice**”) · `backend/.../execution/PlanService.java:27-38` (“they are still null … the deeper reason … would survive making it cheap”) |
| **C6** | minor | **`formatRatio` is re-implemented inline at a call site**, against the explicit instruction in the module that owns it. `format.ts` keeps the two helpers apart *“so the unit is explicit at the call site — mixing 0.89 and 89 into one helper is how a report ends up showing 0.9%”*; `CaseHistoryPage` writes `formatPercent(x * 100, 0)`. Currently correct (`bandOf` in `lib/flake.ts` confirms the 0–1 scale), which is what makes it the kind of duplication that survives. | `frontend/src/features/history/CaseHistoryPage.tsx:118` · `frontend/src/lib/format.ts:125-138` · `frontend/src/lib/flake.ts:63-67` (thresholds `0.2`/`0.5` — the scale) |
| **C7** | minor | **The search screen implements one of the endpoint's seven filter parameters, and two branches of its shared predicate are unreachable.** `/cases/search` declares `q`, `projectId`, `ownerId`, `tag`, `lifecycleStatus`, `automationStatus`, `priority` and `sort`; `CaseSearchFilters` types seven of them; the screen offers only `q` — while rendering lifecycle, automation and priority as columns with no way to filter or sort on them. Consequently `isSearchable`'s `ownerId`/`tag` bounding arms can never be reached from any control, so the “one predicate, no drift” property is real but half-exercised. | `frontend/src/features/cases/CaseSearchPage.tsx:69-73` (only `q`, `page`, `size`), `:169-176` (the columns) · `frontend/src/api/queries/cases.ts:83-93` (the wider type), `:105-112` (`isSearchable`) · `docs/specs/openapi.yaml:3031-3078` (the parameters) |
| **C8** | minor | **Nothing anywhere invalidates `keys.caseSearch`.** Its exclusion from the `['projects', …]` root is deliberate and well argued; the total absence of *any* invalidation is not addressed anywhere. A case renamed, retired or deleted stays in a held search result until a refetch trigger fires. Negative claim, and the search that establishes it: every `invalidateQueries`/`removeQueries`/`setQueryData` target across `src` — 29 hand-built arrays plus every `keys.*` reference — and **none** is `['case-search', …]`. | `frontend/src/api/queries/keys.ts:346-357` (the reasoning, which stops at exclusion) · full invalidation inventory across `src/api/queries/*.ts` — no `case-search` target · `docs/agent-context/fe-react.md:36-43` (seam table; silent on invalidation) |
| **C9** | nit | **Content that changes without a navigation is never announced.** `AnnouncerProvider`/`useAnnounce` exist and work, and are consumed by **2 of 39** pages (`BoardPage`, `ExecutionPage`). Every filtered list — cases, cross-project search, plans, runs, audit — replaces its rows in place with no live region and no announcement, so a screen-reader user typing a filter gets silence. Errors are announced (59 `role="alert"` alerts); results are not. | `frontend/src/a11y/useAnnounce.ts`, `a11y/AnnouncerProvider.tsx` · consumers: `features/board/BoardPage.tsx`, `features/runs/ExecutionPage.tsx` only (negative claim; search: `grep -rln useAnnounce src/features src/app`) · `frontend/src/features/cases/CaseSearchPage.tsx:157-163` (the count, rendered silently) |
| **C10** | nit | **Execution-status colour tokens are reused for unrelated semantics**, which is what §183's one-table rule exists to stop. `--color-passed`/`--color-failed` mean *a test result* everywhere in `lib/statusColors.ts` and `ResultBar`; they are also used for a user's account status, a task being overdue, and the sign of a trend delta. | `frontend/src/features/admin/TeamsPage.tsx:273-275` (`ACTIVE` → `--color-passed`, else `--color-deprecated`) · `features/board/BoardPage.tsx:515,654` (overdue) · `features/plans/PlanStatisticsPage.tsx:1142` (delta sign) · `features/ingestion/IngestionJobPage.tsx:246` |
| **C11** | minor | **Twelve components, ~3,400 lines, are referenced by no test file at all.** Largest first: `ConditionsPanel` 509 (shared-condition resolution order — the file whose own comment warns that re-sorting destroys a server-decided order), `CaseHistoryPage` 422, `PlanDocumentPanel` 340, `SuiteHistoryPage` 296, `ProjectAccessPage` 289, `PlanCreateDialog` 277, `ExternalLinkDialog` 255, `TeamDialog` 251, `RunListPage` 219, `TeamMembersDialog` 213, `CaseVersionPanel` 160, `ArtifactsPage` 123. Verified by name, not by sibling filename, so components tested from a neighbouring file are excluded. | computed: for each, `grep -rl <Name> src --include='*.test.tsx' --include='*.test.ts'` → 0 · `features/cases/ConditionsPanel.tsx:50-51` (the resolution-order hazard it carries) · `features/cases/CaseVersionPanel.tsx:24` (records a past defect in exactly this area) |

## Logical conflicts — two things that cannot both hold; somebody must decide

| ID | Sev | Conflict | Decision owner | Evidence |
|---|---|---|---|---|
| **L1** | major | **`passRate` is two different units under one name, and the contract states neither.** `TestPlanSummary.passRate` is read by the plan list as a **0–1 ratio** (`formatRatio`, which multiplies by 100). `PlanStatistics.trend[].passRate` is a **0–100 percentage** — the backend computes `round(passed*1000/executed)/10` — and is read as one (`formatPercent`). Both are bare `type: number` in `openapi.yaml` with no unit in the description. Every other percentage in the SPA is 0–100 (`lib/results.passRate` → `percentOf`). Latent **only** because `TestPlanSummary.passRate` is `null` on every response today; the day it gets a writer, the plan list renders it 100× wrong and nothing on either side has to disagree for that to happen. Two candidate resolutions (declare the unit on both fields and align the client; or rename one) — **the auditor is not choosing one.** | contract / backend owner (`openapi.yaml` + `execution` module), with the frontend applying the consequence | `docs/specs/openapi.yaml:10344` (`passRate: { type: number, nullable: true }`), `:12076` (`passRate: { type: number }`) · `frontend/src/features/plans/PlanListPage.tsx:301` (`formatRatio`) · `frontend/src/features/plans/PlanStatisticsPage.tsx:1138` (`formatPercent`) · `frontend/src/lib/format.ts:130-138` (the two units, kept apart on purpose) · `backend/.../PlanStatisticsService.java:325,860` (`Math.round(row.passed() * 1000.0 / row.executed()) / 10.0`) · `backend/.../TestPlanView.java:23` (always null) |
| **L2** | minor | **Eight source files suppress a lint rule the linter does not run.** `.oxlintrc.json` enables `react/rules-of-hooks` and `react/only-export-components` and nothing else; `react-hooks/exhaustive-deps` is not among the configured rules or plugins. So `// eslint-disable-next-line react-hooks/exhaustive-deps` disables nothing, and — the part that matters — **no dependency-array checking runs anywhere in this project**, in a codebase with debounce timers, autosave buffers and a documented deliberate missing dependency array (§248). The comments assert a check that does not exist. Enable the rule (and expect the eight to need individual verdicts, some of which may be real defects) or delete the comments — but the current state claims a guard it does not have. | frontend lead | `frontend/.oxlintrc.json` (whole file, 8 lines) · suppressions at `features/plans/PlanDeleteDialog.tsx:49`, `features/admin/RemoveUserDialog.tsx:57`, `features/docs/LinkBlockDialog.tsx:122`, `features/account/AccountSecurityPage.tsx:260`, `features/cases/SuiteDialogs.tsx:588`, `features/cases/QuarantineDialog.tsx:84`, `features/cases/CaseDeleteDialog.tsx:63`, `features/cases/ExternalLinkDialog.tsx:85` · `docs/agent-context/fe-react.md:107` (“the missing dependency array is deliberate”) |

---

# Verified clean

Recorded so round 2 spends its time elsewhere. Each was computed or read this round.

1. 128/128 contract paths typed; both generated copies byte-identical and newer than the spec.
2. Permission catalogue: 55 = 55, same order, no drift against `openapi.yaml` or roles spec §A3.
3. No `useQuery`/`useMutation` outside `api/queries/`; no raw `fetch` outside `api/client.ts`.
4. All four `import.meta.env.DEV` guards literal → the dev fault seam cannot ship.
5. Route error boundary carries `resetKeys` on pathname **and** search.
6. Every percentage denominator guarded; `TrendChart` returns null below two points.
7. No date/number formatting outside `lib/format.ts`. No Tailwind. No `dangerouslySetInnerHTML`,
   `@ts-ignore`, `@ts-expect-error`, or empty `catch`.
8. §65 queue rows present for every landed frontend slice §200–§249; correctly absent for §246.
9. `.env.example` accurate; nothing secret in any committed env file; `dist/` not tracked.
10. Icon-only buttons: automated sweep flagged 14, five sampled, **all five false positives** — no
    finding raised.

# Open questions

| # | Question | Why it needs an owner |
|---|---|---|
| **Q1** | **What unit is `passRate` in, on each of the two schemas that declare it?** | The one question here whose answer changes code on both sides. Until it is answered, `PlanListPage.tsx:301` is a coin flip that currently lands on `null`. → L1 |
| **Q2** | Was §249's missing debounce a decision or an omission? | The cache's “deliberately absent” list is unusually explicit and does not mention it, which reads as an omission — but only the author can settle it. → C1 |
| **Q3** | Should `react-hooks/exhaustive-deps` be enforced? | Turning it on is a policy call with unknown cost until it runs. → L2 |
| **Q4** | Is the Saved-reports panel meant to be per-caller forever, or is `shared_with_team_id` coming? | Decides whether C3 is “fix the copy” or “fix the copy *and* keep the column”. |
| **Q5** | Do the twelve unobserved components need tests, or are they queued for the live browser pass instead? | §65 makes “observed by a browser pass, not a unit test” a legitimate answer — but no queue row claims these. → C11 |

# Sequencing — leverage first, not severity first

1. **D1** — one line in `CLAUDE.md`. It is the first file every contributor and agent reads, and it
   is wrong about the UI framework major. Cheapest correct change in this report.
2. **L1** — answer Q1 and put the unit in `openapi.yaml`. Blocks a 100× rendering error that is
   waiting on an unrelated slice, and it is a one-sentence decision.
3. **C1** — a `useDeferredValue` or a 250–300 ms timer plus `TIMEOUTS.typeahead`. Two-line change,
   removes ~13 of every 14 requests to the app's widest read. Consider extracting the shared
   debounce hook `ConditionsPanel.tsx:211` says does not exist while the four sites are open.
4. **C3** — copy change plus a column decision. Highest ratio of user-visible clarity to effort.
5. **C4** — one third branch in the empty-state ternary; the design already argues for it.
6. **L2** — decide once, then either 8 verdicts or 8 deletions.
7. **C2** — add the missing `*Root` key helpers and replace the 29 literals mechanically.
8. **C5, C6, C7, C8, D2, D3, D4** — small and independent; batch them.
9. **C9, C10, C11** — the standing work. C11 is best sized by starting with `ConditionsPanel`,
   which is both the largest and the one carrying an ordering invariant a test can pin.

# Handoff

- Fixes belong to **`fe-react`**. This table is the handoff artifact; nothing was changed here.
- **L1** crosses into the contract and the `execution` module — route it through
  **`business-analyst`** or the backend owner before any frontend edit.
- **C11** is test-basis input for **`qa-test-case-designer`**; `ConditionsPanel`'s resolution order
  and `CaseSearchPage`'s `isSearchable` boundaries (0, 1, 2 characters, `TC-4`) are BVA seeds.
- Nothing here smells like a vulnerability. `qa-security` is not needed for this round; the
  fail-open policy in `useCan` was examined specifically and is sound because the server is the gate.
- **Round 2 must re-trace every ID from scratch.** IDs and surface letters above are permanent.
  “Resolved” is claimed only after re-reading the cited lines that round — a green gate, a linked
  commit or a “we fixed it” is a lead, not evidence.
