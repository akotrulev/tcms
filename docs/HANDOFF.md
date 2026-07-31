# TCMS — Session Handoff

**Date:** 2026-07-31 (last session: **the test harness stopped migrating as a superuser** — §19)
**State:** Planning and design complete **including multi-company tenancy**. **Backend M0 is COMPLETE**, and **M0.5 now has a usable login** (§18): invitation → activation → sign-in works end to end, `CompanyContext` finally has a production entry point, and the permission evaluator answers over HTTP. **The instance is reachable for the first time.** Frontend complete through **Phase 3** and aligned with the multi-company decisions (§9) — but it still runs entirely on MSW, so **the two halves have never met**. Frontend Phase 4 (tests) and 5 (review) not started.
**Build status:** `backend` — `./gradlew build` clean, **177/177 tests green against Testcontainers Postgres 17**, now **migrating as a non-superuser owner** (§19). `frontend` — `npm run build` clean, `npm run lint` 0 warnings. **`docker compose up` healthy on a fresh volume, with provision → accept → sign in → 48 permissions proved against a non-superuser owner** (§18). CI runs both gates on GitHub Actions.
**Branch:** `master`. Per the git workflow, **work continues on the checked-out branch; do not branch per task.** Last commits: `a0fdcd1` M0.5 recorded, `0514402` the login defect compose found, `dd0e5f2` login + filter chain.

> **✅ The suite's superuser owner is gone — §19.** `IntegrationTestBase` now creates a **non-superuser
> `tcms_owner`** before Flyway, mirroring `deploy/postgres/initdb/10-tcms-roles.sql`, and `PrivilegeSplitTest`
> pins every property of the split — including that **`FORCE ROW LEVEL SECURITY` covers the owner**, the
> assertion the old harness structurally could not make and the one V7's HIGH defect turned on. The
> container's superuser is now used only for ground truth in assertions.


> **✅ The §10 concerns from the previous handoff are resolved.** The two uncommitted doc files are committed (`d08590b`) and `§6v` exists — the dangling reference is gone. §10 is retained as the record of that strand's decisions, not as an outstanding warning.
>
> **⚠ The DDL is no longer unexecuted — and running it found two defects.** See §11.
>
> **⚠ Every repository interface now needs `@Transactional`, and a test enforces it.** Without it a
> repository's *query methods* run outside any transaction, so `app.current_company` is never set and
> every RLS-protected read returns **zero rows**. Fail-closed, but it silently emptied the permission
> evaluator. See §15.

---

## 0. IMPORTANT — Use the installed plugin skills

This project is being built with the **`fe-react`, `be-java`, `data`, `qa-java`, `devops` and `business-analysis` plugin skills**. They are not optional colour — the work so far follows them, and continuing without them will produce inconsistent output.

**At the start of any new session:**

1. **Frontend work** → invoke the `fe-react:fe-react` skill (or `Skill` tool with `fe-react:fe-react`). It loads the agent definition at
   `~/.claude/plugins/cache/skills-library/fe-react/<hash>/frontend/react/agents/fe-react.md`
   and runs a **phased pipeline**. Read the phase table there and load **only that phase's `SKILL.md` files** before producing anything they cover. Skills live at
   `~/.claude/plugins/cache/skills-library/fe-react/<hash>/frontend/react/skills/<name>/SKILL.md`.
   - Phase 0–1 are **already done** (see §4). Next session enters **Phase 2 — Feature build**, which requires loading:
     `component-writer`, `routing`, `forms`, `data-fetching`, `test-ids`, `ui-rules`, `responsive-design`, `animation`, `design/ux-writing`.
   - The agent runs **inline**, not as a subagent, unless asked otherwise.
2. **Backend work** → `be-java:be-java` skill. Also `be-java:be-java-database-code` for JPA/persistence and `data:data-postgresql` / `data:data-migrations` for schema and migration work.
3. **Never improvise a pattern a skill owns.** The skill wins over instinct, and over general guidance, for its domain. If a listed skill is missing on disk, say so and proceed with general rules — never substitute invented guidance.
4. The user's global `CLAUDE.md` requires: **no remote/cloud planning**, and all generated `.md` artifacts saved **into this project** under `docs/plans/`, `docs/specs/`, `docs/audits/` — never to a global directory.

---

## 1. What this project is

A **self-hosted, Java-based test case management system** serving **one or more companies from a single instance**, where a **company is a separate CLIENT organisation**. It owns the manual and automated test asset lifecycle: authoring cases, organizing suites and plans, executing runs, ingesting automated results, and reporting quality signals traceable from documentation through to defects.

**The hierarchy, because getting it wrong propagates into every example:**

| Level | What it is | Example |
|---|---|---|
| Instance | One deployment, operated by you | — |
| **Company** | **A separate client organisation.** The isolation boundary. | Acme Corporation |
| **Project** | **A full client project** — an entire product or engagement. *Not* a feature area. | Acme Online Banking · Acme Mobile App |
| Suite tree | Structure within one project | `Onboarding` › `KYC checks` |
| Feature dimension | The feature-level classification axis (§6d) | `Payments` › `Refunds` › `Partial refund` |

So "Payments" is a **Feature** or a suite folder, never a project. A company holds a handful of whole products, not dozens of features.

**Three access tiers** (§6b): Instance Admin (everything, every company) → Company Admin (everything in the companies they're assigned to) → regular user (**only the projects they are explicitly granted** — company membership conveys authentication and *zero* project visibility).

**Explicit non-goals:** not a test runner (it ingests results), not a defect tracker (it links to Jira), **not public multi-tenant SaaS** (companies are provisioned by an operator script, not self-signup — no billing, no custom domains), not a step-definition editor. No cross-company anything.

---

## 2. Artifacts produced

| File | Lines | What it is |
|---|---|---|
| `docs/plans/2026-07-30-tcms-high-level-plan.md` | 2052 | **The master design document.** 21 design sections (§6a–§6u), functional scope by phase, milestones M0–M6, 42 open questions. |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | 340 | **AUTHORITATIVE for the permission catalogue, role grants and naming.** The seed-migration source. The ERD defers to it where they overlap. |
| `docs/specs/2026-07-30-tcms-erd.md` | 760 | Entity-relationship design — Mermaid per module, field tables, reasoning. §0.1 is the `company_id` rule and is the most important part. |
| `backend/src/main/resources/db/migration/V1__initial_schema.sql` | 1464 | First Flyway migration. **64 tables, 56 RLS policies (all FORCED).** **Executed and green against Postgres 17.10** (§11). Comments carry the *why*. |
| `docs/specs/openapi.yaml` | ~2100 | REST contract — the source of frontend types and mock handlers. **Aligned with multi-company** (§9): `PermissionKey` enum, six roles, `COMPANY` scope, `TC-1` keys, UUID-only path params. Unaffected by the §11 schema change — `RoleAssignment` exposes `role.scope`, never `companyId`. |
| `docs/audits/2026-07-30-kiwi-tcms-capability-gaps.md` | — | Competitive gap analysis vs Kiwi TCMS. **Committed**; its §6 is now a disposition record of what was accepted, reshaped or dropped. See §10. |
| `docs/audits/2026-07-30-v1-schema-first-execution.md` | — | **Evidence for the first real execution of V1** — measured counts, the trap-2 result, and the two defects it found, with reproduction steps. Read this before touching RLS or the exception list. |
| `backend/` | — | Spring Boot 3.5.6 + Java 21 + Gradle (**rooted in `backend/`, not the repo root**). `tenancy` · `identity` · `project` modules, schema test, two-company isolation harness, async-context harness. 87/87 green (§11–§14). |
| `frontend/` | — | Vite + React 19 + TS + MUI v7. Phase 3 complete **and multi-company-aligned** (§9). |

### Plan section index (`docs/plans/...`)

| § | Topic |
|---|---|
| 6a | Dual step format — structured **or** Gherkin per case |
| 6b | Teams, roles & permissions — **Instance Admin / Company Admin / Test Lead / Test Engineer / Automation Service / Viewer**, three scopes (SYSTEM/COMPANY/PROJECT) |
| 6c | Automation status & tagging |
| 6d | Classification — Test Type × Test Level × Feature tree × Phase |
| 6e | Reporting & export (PDF/MD/HTML) + aggregate execution report |
| 6f | Pre/post conditions with bulk assignment |
| 6g | Optional review workflow |
| 6h | Reusable step & condition search (indexing) |
| 6i | Documentation space with stable block anchors |
| 6j | Versioning & history |
| 6k | Org chart & team structure |
| 6l | Kanban board & work items |
| 6m | Test case field specification |
| 6n | Test artifacts (project + plan scope) |
| 6o | Repository status dashboard |
| 6p | Execution history & statistics — case, suite, plan |
| 6q | Search, filtering & release version |
| 6r | External identity (OIDC SSO) & integration credentials — **M4, not phase 1**; per-company IdP config |
| 6s | **Multi-company tenancy** — isolation strategy, the `company_id` rule, lifecycle, the isolation harness |
| 6t | **Display keys** — `TC-1` etc., company-scoped, and why UUIDs stay the real keys |
| 6u | **Per-company branding & per-membership display names** |

---

## 3. Decisions locked in (do not re-litigate without reason)

### Product / architecture
- Self-hosted, **multiple client companies per instance** · Spring Boot REST + React SPA · one deployable (SPA served from the Spring Boot jar).
- **Monorepo**: `backend/` + `frontend/` + `docs/`. Justified by the shared `openapi.yaml` contract and the single shipped artifact.
- Spring Modulith monolith. Modules: **`tenancy` ·** `identity · project · repository · execution · documentation · reporting · work · shared`. Cross-module FKs allowed only toward **`tenancy`**/`identity`/`project`; everything else via domain events, enforced by ArchUnit. `tenancy` is depended on by everything and depends on nothing.

### Multi-company — decided 2026-07-30 (plan §6s, §6t, §6u)

| Decision | Detail |
|---|---|
| **Companies are separate clients** | Not business units. Drives: RLS on by default · whole-company export required · no pre-auth company picker (would leak the client roster) · Viewer as the invitation default. |
| **Sequencing: foundation M0, feature LAST (M6)** | The `company_id` discriminator, filter, constraints, provisioning script **and the two-company isolation harness** are M0. The surface (switcher, invitations UI, branding, per-company IdP) is M6 at ~1.5–2 weeks. Total cost ~2 weeks, split. |
| **Companies are provisioned by an operator script** | `provision-company` CLI — tested and idempotent, not hand-written SQL. This is *why* M6 is small: no CRUD screens, no ops console, no archive state, no quota UI. |
| **Default lifecycle policy: suspend, never delete** | Avoids a purge script across ~60 tables of cascades. `SUSPENDED` must be honored in code. |
| **One account, many companies, independent permissions each** | `app_user` is instance-level, email unique **instance-wide**, `company_membership` is the join. Membership **gates** the permission union rather than contributing to it — so offboarding is one row update. |
| **Company Admin is assigned per company by the Instance Admin** | A `COMPANY`-scope `RoleAssignment`, not a new tier. Sees every project in their company, present and future. Three companies = three rows. |
| **Regular users see only granted projects** | Not the company's project list, names, or counts. Two independent gates: company filter, then project grant. |
| **Instance Admin does reach into client content** | Deliberate — an operator with `psql` makes the restriction theatre. Mitigated by auditing operator access in **the client's own** trail and disclosing it plainly. |
| **No custom roles in v1** | Not even clone-and-edit. Six built-ins are the complete set. Enforcement stays permission-based so a designer is additive later. |
| **Viewer is the default at every entry point** | Company invitation, SSO JIT provisioning, and project assignment. Test Engineer is always an explicit grant. |
| **Display keys are company-scoped** | `TC-1` `TP-1` `TS-1` `TR-1` `DOC-1`, numbered from 1 per **company**. No key for executions/conditions/defects. No automation prefix. |
| **Branding: logo + two colours + name + favicon** | Theme *derived* from the two colours with contrast validated at save; **status colours are not brandable**. No custom CSS. |
| **Per-membership display names** | Resolved through the active company's membership at every rendering site. In M0 because it threads through every surface showing a person. |

### Resolved open questions (2026-07-30)
| # | Decision |
|---|---|
| 7 | **Concurrent execution: yes, with live updates.** `lock_version` on executions/step results; SSE shared with the kanban board. |
| 18 | **Requirements are documentation blocks.** No `requirement` table — traceability is `test_case → doc_link → doc_block`. |
| — | **Custom fields deferred to phase 2.** |
| — | **Versions written on explicit save only** (autosave → draft buffer; skipped when `content_hash` unchanged). |
| — | **Steps stored relationally**, stable `test_step.id` so `step_result` FKs cleanly and reordering never reassigns evidence. |
| — | **Read models are event-updated tables**, not materialized views. |
| — | ~~`tenant_id` as a hedge~~ → **superseded.** `company_id` is a real modelled entity on every company-owned table. Requires: a globally-applied Hibernate filter fed from the session (never client input), `company_id` **leading** every composite index and previously-global uniqueness constraint, RLS on by default, and the schema test that enforces all of it. |
| — | ~~Keys are `PREFIX_NNNN` per project~~ → **superseded.** Keys are **`PREFIX-N`, sequential per COMPANY**: `TC-` case · `TS-` suite · `TP-` plan · `TR-` run · `DOC-` doc page. No zero-padding (`key_seq` owns ordering). **No key for executions** — dropped entirely. |
| — | **Auth: local accounts (BCrypt + JWT) in v1.** External OIDC SSO — Cognito, Entra ID, Google Workspace, Okta, Keycloak, generic — ships in **M4 with the integrations**, since it shares the OAuth client, encrypted credential storage and consent machinery with Jira/Slack/Teams. Local login is retained permanently as the break-glass path (§6r). |
| — | **Jira is NOT an identity provider.** Authentication (who is this human) and outbound integration authorization (may TCMS act against Jira) are separate concerns with different scopes, lifetimes and consent models. Do not merge their code paths (§6r). |

### Load-bearing details that are expensive to retrofit
1. **A run pins the case *version* it executed** (`test_execution.case_version_id`). Without it, editing a case silently rewrites history in every past report.
2. **Shared conditions/steps: reference while authoring, snapshot at version-pin time.** DRY while live, immutable once executed.
3. **`doc_block.id` is immutable and never derived from text.** Heading-text anchors break *silently* when a typo is fixed.
4. **Read models must ship from M1** — `execution_fact`, `suite_run_summary`, `plan_snapshot`, `case_health`. Retrofitting these onto live joins is a rewrite, not an optimization.
5. **Versioning + audit must be written from M1** even if the diff/restore UI comes later. History you didn't record is history you never get back.
6. **Keys don't sort lexicographically** — `TC-10000` sorts before `TC-9999`. Every keyed table stores `key_seq BIGINT` alongside `key TEXT`; order by `key_seq`, display `key`. Same class of bug as `release_sort_key`.
7. **`suite_run_summary` stores counts, never percentages** — the "of executed" vs "of total" toggle needs both, and nested rollups must sum leaf counts (averaging child percentages over unequal case counts is wrong arithmetic).
8. **Kanban derived items are never materialized.** Their status is computed from the source, so a card can't diverge from its execution.
9. **Aggregation policy must be displayed on any multi-run report.** Two teams reading "94% pass" otherwise mean different things.
10. **Identity must stay keyed on `app_user.id`, never email**, and `created_by` must FK to the user row. This already holds, and it is the single constraint that keeps M4's SSO work additive (new tables + a backfill) rather than a migration touching every authored artifact (§6r).
11. **`company_id` on every company-owned table, from M0** — including **join tables and version tables**, which is where it is actually forgotten (see §6). Retrofitting the discriminator across every table, index and uniqueness constraint is the most expensive change available in this project. The *feature* can slip indefinitely; the column cannot.
12. **`user_identity` must be keyed on `(company_id, issuer, subject)` when SSO lands in M4** — per-company issuer trust. A different client's IdP asserting the same `sub` must never resolve to an existing user. This is the multi-company account-takeover vector, and it is the one part of the M4 deferral that is *not* purely additive if got wrong.
13. **Unreachable display keys return NOT FOUND, never FORBIDDEN** — identically, so the response can't be used as an oracle. Company-wide key numbering makes `TC-N` guessable, and this rule plus "display keys never appear in API paths" are what contain the inference channel. Both are load-bearing, not incidental.
14. **Async handlers must re-establish `CompanyContext` from the event envelope**, not inherit a thread-local. Context loss across an `@Async` boundary is the realistic way a multi-company instance leaks, and it is invisible in synchronous tests.

---

## 4. Frontend — current state

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

---

## 5. NOT done — next session starts here

### Phase 2 gate — PASSED (evidence)

`npm run build` clean · `npm run lint` 0 warnings · 206 `data-testid` sites, **0 collisions** (the six apparent duplicates are per-file `TestIdPrefix` constants resolving distinctly, or mutually exclusive render branches — see the audit command in §7) · no page-level horizontal overflow at 390 px (the DataGrid scrolls inside its own container) · console clean, no key/hydration warnings.

**Driven in a real browser end to end**, not just built: sign-in → project → dashboard tile → filtered list → case detail → edit → save (**title changed, v3 → v4, toast, list reflects it**) → run → execution → untick a precondition → Blocked dialog pre-filled with the unmet condition → record → **run counts recomputed** (Blocked 0→1, Untested 27→26, filter chips updated). Version pinning confirmed visually: the run still shows TC_0003 at **v3** after the case was edited to v4.

### Three defects found in the Phase 2 browser pass

1. **Theme toggle was a no-op on a system-dark machine.** It read `mode === 'dark'`, but the default mode is `system` — so the control offered "switch to dark" on an already-dark page and the first click only moved `system` to the equivalent explicit value. Now resolves via `systemMode`.
2. **Only the 60 px key cell in the case grid navigated**, not the title. The affordance technically existed but the row read as broken. The title is a `<Link>` now too.
3. **Plan statistics contradicted itself**: the headline showed 91.1% ("of executed") while the exit criterion showed 89.1% (of all in scope), with nothing explaining the difference — the exact ambiguity §6p exists to remove. The exit-criteria panel now states its denominator.

### Phase 3 — quality layers: DONE (2026-07-30)

Skills applied: `error-handling` (+ both `references/`), `accessibility`, `performance`, `seo`.

**The gap the handoff had not spotted, and the most severe one: there was not a single error boundary in the app**, while every heavy route is `lazy()`. Any render throw or failed chunk download blanked the entire SPA.

| Area | What shipped |
|---|---|
| Boundaries | `components/ErrorBoundaries.tsx` — root (outside the providers, so a theme/query-client throw is caught), per-route (**inside** the shell, so the rail survives and you can navigate away from a broken page; `resetKeys` on pathname), per-region. `DataRegion` wraps its own children in one. |
| Notifications | `notifications/` — top-right, **never auto-dismisses**, close button, copyable correlation id, deduped by `correlationId ?? code\|region` with a count, **rolled up by cause**, capped at 3 + "N more". Raised from **one** place (`QueryCache`/`MutationCache` `onError`); call sites with better copy opt out via `meta: { handled: true }`. notistack stays for success toasts only. |
| Retry | Was a blanket `retry: 1` that retried 4xx too. Now `isTransient()` (5xx/network/timeout/408/429 only), exponential backoff, **honours `Retry-After`**, stops after 3. Mutations never auto-retry. |
| Session | `auth/SessionRecoveryProvider` — a mid-session 401 raises **re-auth in place**; the route stays mounted and unsaved work survives. 403 never touches the session. |
| Version skew | `app/versionSkew.ts` + `VersionSkewWatcher` — `__BUILD_ID__` stamped by Vite, chunk-load errors recovered by a **`sessionStorage`-guarded single reload**, prompted (never forced) when work is unsaved. |
| Idempotency | `newIntentKey()` — one key **per user intent, not per attempt**, on creates. The mock handler genuinely dedupes on it, so the property is provable. |
| A11y | Skip link (first focusable), `<main tabIndex={-1}>` focused on route change but **not** on first load, one persistent polite live region announcing each page. |
| Reporting | `lib/reportClientError.ts` — one sink, rate-limited + deduped, fed by boundaries, `window.onerror`, `unhandledrejection`, and React 19's `onCaughtError`/`onRecoverableError`. |
| SEO | Correctly **scoped out**: an authenticated internal tool has no crawlable surface. Placeholder `<title>frontend</title>` fixed, `noindex` meta, `robots.txt` disallowing everything. Reasoning recorded in the files. |

**Files added in Phase 3**

| Path | Purpose |
|---|---|
| `src/components/ErrorBoundaries.tsx` | The three tiers + the shared stale-build fallback. |
| `src/lib/describeError.ts` | **One** place an error becomes user-facing copy. Both the inline region and the notification read from it, so a 503 is worded identically in both. Also owns `isTransient()`. |
| `src/lib/reportClientError.ts` | The single reporting sink. Rate-limited, deduped, drops `AbortError`. |
| `src/lib/globalErrorHandlers.ts` | `window.onerror` + `unhandledrejection` — the two paths boundaries cannot see. |
| `src/notifications/{NotificationsProvider,notificationsContextValue,useNotify}` | The persistent stack. Dedupe, roll-up, cap. |
| `src/auth/{SessionRecoveryProvider,sessionRecoveryContextValue,useSessionRecovery}` | The 401 ladder's rung 3, `useUnsavedWork`, and `SessionReplayBridge`. |
| `src/a11y/{AnnouncerProvider,announcerContextValue,useAnnounce,useRouteFocus}` | Live region + route-change focus. |
| `src/components/SkipToContentLink.tsx` | First focusable element in the DOM. |
| `src/app/queryClient.ts` | Retry policy + the one global raise point for query/mutation failures. |
| `src/app/versionSkew.ts` · `src/app/VersionSkewWatcher.tsx` | Chunk-error detection + the guarded reload; the proactive on-focus check. |
| `src/mocks/faults.ts` | Dev-only fault injection (below). |
| `src/build-env.d.ts` · `vite.config.ts` | `__BUILD_ID__`, and the recorded reasoning for the chunk-size limit. |
| `public/robots.txt` · `index.html` | The SEO subset, with why-the-rest-doesn't-apply written down. |

Modified: `Providers.tsx` (nesting order is load-bearing — see its header comment), `main.tsx`, `DataRegion.tsx`, `PageHeader.tsx`, `AppShell.tsx`, `api/client.ts`, `api/queries.ts`, `lib/format.ts`, `mocks/{db,handlers,browser}.ts`, `CaseEditorPage.tsx`.

**Fault injection — `src/mocks/faults.ts`.** None of the above can be triggered by using the app normally, so the mock API grew a console-driven fault switch: `tcmsFaults.status(503, 30)` · `.hang()` · `.expire()` · `.drift()` · `.only('/cases')` · `.off()`. Dev-only, tree-shaken from production. **Use it before claiming any resilience behaviour still works.**

**Verified in a real browser** (see §6 lesson 5), not just built: 503 → inline failed region + one notification while the search box, filters, suite tree and New case all keep rendering · load-then-fail-refetch → **19 rows kept, marked "Showing data from 11:25 UTC", retry offered, no failed body** · hung endpoint → leaves loading at the deadline · 401 with a half-typed case title → dialog in place, **title still there afterwards**, no redirect · one 503 across three regions → **one** notification reading "Occurred 3 times" · notification still present at **61 s** · retry from the notification → full recovery · route change → focus lands on `<main>`, announced.

### Three defects found in the Phase 3 browser pass

1. **`SessionRecoveryProvider` called `useQueryClient()` while mounted above `QueryClientProvider`** — it has to be above, because the query client's error handlers need `reportUnauthorized` at construction. Threw "No QueryClient set" and the root boundary caught it: **the app rendered nothing but the error page**. Fixed with a registration bridge (`SessionReplayBridge`) mounted below the client.
2. **`reportUnauthorized` closed over `user`**, so the query client captured it at mount when `user` was `null` — the guard would then have swallowed every 401 for the life of the session and the recovery dialog would never have appeared. Now reads a ref.
3. **A drifted response crashed the whole route** rather than the region, because `isEmpty(data)` — a predicate over the response body — was evaluated in `DataRegion` *outside* its own boundary. Moved into a child component.

### Phases 4–5 — next

**Unblocked.** The §9 corrections landed, so Phase 4 tests will be written against shapes that survive. `lib/permissions.ts` and `lib/results.ts` are the highest-value targets — both pure and both load-bearing.

- **Phase 4:** Vitest + RTL. Nothing is installed yet. Highest-value targets are the logic-bearing units, not the markup: `useCaseFilters` (URL round-tripping), `lib/results` (denominator arithmetic), `lib/format` (`compareReleaseVersions`), `lib/describeError` (the retryable/isAuth matrix), `DataRegion`'s state matrix, `app/versionSkew` (the once-only guard), and the `CaseSchema` cross-field refinements.
- **Phase 5:** hostile self-review — `code-review` + `design/ui-code-audit` + `design/ux-review`, with the "remove one accessory" restraint check.

### Phase 3 items deliberately left open

- **No runtime response validation.** A 200 whose body has drifted is currently caught by the region boundary — the page degrades correctly — but it is *not* categorized, and drift that renders **without throwing** (a renamed optional field silently reading `undefined`) is not caught at all. The real fix is parsing each response at the fetch boundary; that is `data-fetching`'s territory and roughly 40 schemas, so it is its own slice. **This is the largest remaining resilience gap.**
- **`CaseListPage` stays at 527 kB raw / 155 kB gzip**, over the per-route budget. Measured alternative: forcing MUI X DataGrid into its own `manualChunks` entry defeated tree-shaking — the page fell to 72 kB but the grid chunk came out at 728 kB and total JS went from ~909 kB to ~1275 kB, with no cache reuse since `CaseGrid.tsx` is the only consumer. Reverted; `chunkSizeWarningLimit` raised to 600 with the reasoning in `vite.config.ts`. The entry chunk is **124 kB gzip, inside budget**, and the grid is behind a lazy route. The real lever is dropping DataGrid for a plain table — a Phase 5 conversation.
- **Multi-tab session broadcast** (`BroadcastChannel`) — signing out in one tab does not yet update its siblings.
- **`useUnsavedWork` is wired only to the case editor.** The execution screen and board should register too.
- **No Lighthouse/CWV measurement.** Bundle budgets were checked; LCP/INP/CLS were not measured, and that needs the app served rather than in dev.

### Known gaps deliberately left in Phase 2 scope

- **Evidence upload** is modelled in the contract and rendered where present, but there is no upload control on the execution screen (needs the signed-URL path, not a form POST).
- **Case create/edit does not assign features** — the feature dimension is MULTI + hierarchical and needs its own tree picker; the editor shows existing assignments read-only rather than offering a flat select that could set a parent where a leaf is required.
- **Suite create/rename UI** — the handler and mutation hook exist, the tree has no affordance yet.
- **Review/approve actions** are deliberately absent from the editor (status is not an editable dropdown, per §3); the actions themselves are not built.
- **SSE / live updates** (open question #7 resolved as "yes, with live updates") — not wired. The `realtime` skill was not loaded because nothing pushes yet. Snapshot→live handoff is a Phase 3+ task.

### Backend — M0 STARTED (2026-07-30), see §11 for what shipped and what remains

**Done:** Gradle + Spring Boot 3.5 + Java 21 scaffold (rooted in `backend/`, not the repo root), Flyway running V1 against Testcontainers Postgres 17, the `tenancy` module (`CompanyContext` + Hibernate filter + RLS GUC per transaction), the `project` vertical slice, **the schema test**, and **the two-company isolation harness**. 13/13 green at the time, mutation-verified — see §12–§14 for what landed after.

**Still not started** — everything below, plus: the `identity` module, Docker Compose, CI, ArchUnit/Modulith verification, `provision-company`, the third and fourth harness personas, and the async-context test.

- `V2__triggers_and_seed.sql`: `search_vector` generated columns, SINGLE-cardinality classification trigger, team **cycle + company-consistency** trigger, and all seed data — **the revised permission catalogue** (per `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, which is authoritative), **the six** built-in roles, **per-company** classification dimensions, default case statuses, default configuration per project.
- **The `provision-company` CLI command** — an M0 deliverable, not an M6 one. Creates the company, seeds its vocabularies/statuses/settings, creates the first Company Admin as an invitation, writes an instance audit event. Tested and idempotent.
- **The M0 isolation harness** — the highest-value backend deliverable and easy to postpone by mistake:
  - paired-fixture suite: two companies with deliberately identical-looking data (same project key, same display keys, same team names, same user email in both), every read endpoint exercised as company A asserting no company-B row, name, id or count appears;
  - a **third persona**: a company member with *no project grant*, asserting they see nothing — those rows *are* in the caller's company, so the cross-company assertions structurally cannot catch that leak;
  - a **cross-company user**: Company Admin in A, Test Engineer in B, asserting no permission bleed across a company switch (where a stale cache would show);
  - a **schema test**: every table not on the §0.1 exception list has `company_id NOT NULL` leading its indexes — this is what catches new tables a year from now;
  - an **async-context test**: ingestion, report generation, a scheduled purge and an event handler each run with a foreign company active.
- Gradle multi-module setup, Spring Boot app, Testcontainers.
- **Run V1 against a real Postgres.** It has never executed — see §6.

---

## 6. Known risks and open items

**~~The DDL has never been run.~~ → SUPERSEDED 2026-07-30.** It has now been executed against Postgres 17.10 and it runs clean; all three extensions are present in the stock image; **trap 2 passes**; and both RLS traps below are now covered by tests rather than warnings. Two defects were found in the process — see §11 and `docs/audits/2026-07-30-v1-schema-first-execution.md`. The original note is kept below because its list of what static analysis *cannot* catch is still the right checklist for V2.

Static validation of the pre-execution file confirmed: 63 tables, no duplicate names, all `REFERENCES` targets resolve, no forward references, index/comment targets valid, parentheses balanced, **every company-owned table carries `company_id`, and the RLS policy list exactly matches that set in both directions**. What static analysis **cannot** catch:
- `ltree` / `pg_trgm` / `citext` extension availability (some managed hosts gate extensions)
- The `IMMUTABLE` requirement on any generated `search_vector` expression
- Trigger definitions (not in V1)
- **The two RLS traps below** — both silent when wrong

**The two RLS traps, worth their own line because both fail quietly:**
1. **`FORCE ROW LEVEL SECURITY`, not just `ENABLE`.** A table's *owner* bypasses plain `ENABLE`, and in most compose setups the app connects as the schema owner — so plain `ENABLE` protects nothing. The app role must also not hold `BYPASSRLS`. `FORCE` is in the DDL; verify the role.
2. **An unset GUC must yield zero rows, not all rows.** The policy is `company_id = current_setting('app.current_company', true)::uuid`; the `true` makes a missing setting return NULL, and `= NULL` is never true, so a request without company context sees nothing. Get the flag or the comparison wrong and a context-less query returns **the entire instance**. This needs its own explicit test — a green suite does not prove it.

**Do this first on the backend:** stand up Postgres via Testcontainers, run V1, then write the test for trap 2 before anything else.

**Eight open ERD points** are listed in `docs/specs/2026-07-30-tcms-erd.md` §10. The three worth attention: the polymorphic `attachment` table (the one place the schema knowingly drops referential integrity); **key allocation now contending on one `key_sequence` row per `(company, entity_type)`** — company-wide rather than per-project, so contention is higher by the number of projects a client runs, and range allocation is mandatory rather than advisable; and the **display-key inference channel** (see §3 lesson 13).

**42 open questions** in the plan §8. All roles/permissions/naming questions are now **resolved**. Blocking nothing right now, but these change M1/M2 design: case versioning depth (#1), Jira flavour (#4), data volume (#5), concurrent execution (#7), default step format (#8), Gherkin sync mode (#9).

**Two verification lessons from this session, both worth keeping:**

1. **`tsc --noEmit` is not the gate.** It reported 0 errors while `npm run build` (which runs `tsc -b` against the stricter project config) failed with nine. Always verify with **`npm run build`**. The root cause of those errors was that `openapi.yaml` originally declared no `required` arrays, so every generated property was optional and the fixtures couldn't satisfy their own types — fixed by adding `required` to 39 schemas. **If you add a schema, add its `required` array.**
2. **Don't chain `echo` after an install command.** An earlier `npm install -D msw openapi-typescript ... | tail -2 && echo "installed"` printed success while both packages silently failed to install, and that went unnoticed until `npm run gen:api` errored much later. Verify installs by reading `package.json`, not by trusting an exit code you've masked.

**Three more from the 2026-07-30 session:**

3. **Lesson 1 recurred, and will keep recurring.** Nine of the build errors in this session were `possibly undefined` on properties that are *always* present — because eight inline objects nested inside already-`required` schemas had no `required` array of their own. `required` on the parent does not descend. Grep for `type: object` with `properties:` and no sibling `required:` before writing consumers.
4. **Don't invent a response shape — read the contract first.** `BulkResult`, `EntityVersion`, `HealthSignal` and `AggregationPolicy` were all coded against a guessed shape and all four had to be reworked. Where the contract was genuinely wrong for the UI (`BulkResult` counts vs lists), the fix belongs in `openapi.yaml`; where it wasn't, the code was wrong. Check which before editing either.
5. **The browser found three defects the build could not.** A no-op theme toggle, a grid whose title didn't navigate, and two contradictory percentages on one screen all typechecked and linted clean. **Run the app.** The gates prove it compiles, not that it works.

**Three more from the Phase 3 session:**

6. **Lesson 5 recurred, and harder.** The Phase 3 work built clean and linted clean while rendering **nothing but the root error page** — a provider ordering bug (`useQueryClient` above `QueryClientProvider`) that is invisible to the type system because both are valid types in valid positions. Two of the three defects that session were of exactly this kind: correct types, wrong runtime graph. `npm run build` cannot see provider ordering, hook closure staleness, or effect timing.
7. **A resilience feature you cannot trigger is a resilience feature you have not tested.** Every claim in the gate — timeout, stale-refetch, 401-in-place, roll-up, no-auto-dismiss — needed a fault the app has no way to produce on its own. Building `tcmsFaults` first would have been faster than building it after the third awkward attempt to fake a failure by hand. **Build the fault switch before the feature it proves.**
8. **Measure the bundle change before believing it.** Splitting DataGrid into its own chunk looked obviously right and made total JS ~40% worse by defeating tree-shaking. The build output is the arbiter, not the reasoning.

**Two more from the multi-company session:**

9. **A schema-wide invariant needs a mechanical check, not a careful reading.** Regenerating `V1` and then cross-checking every table against the `company_id` rule found **five join tables with no `company_id`** (`case_classification`, `case_tag`, `case_automation_tag`, `case_condition_optout`, `work_item_link`) and **four version/annotation tables absent from the RLS list** (`suite_version`, `test_plan_version`, `doc_page_version`, `derived_item_annotation`). Both classes are severe: a join table without the column is unfilterable *and* un-RLS-able, so a query starting from `case_classification` could cross clients; a version table holds a full snapshot of client content. **Neither was visible reading the file top to bottom** — only by scripting the comparison. This is lesson 5's family (the gates prove it compiles, not that it's right) applied to schema, and it is the argument for the M0 schema test being mandatory rather than a formality.
10. **A sentinel value in data is a lie waiting to happen.** The fixture modelled "holds everything" as `permissions: ['*']`. It read fine and typechecked — until the catalogue grew. A sentinel cannot distinguish *complete coverage* from *a value that happens to be present*, so adding a permission Instance Admin didn't hold would still have rendered "every permission". Derive the claim from the real set (`allPermissions.every(...)`) and a gap shows up as a gap. Same family as lesson 9: the invariant needs computing, not asserting.
11. **Making a contract enum out of a string field turns the compiler into the migration checklist.** `PermissionKey` went from `type: string` to a 55-member enum in `openapi.yaml`; regenerating found every stale permission literal in the codebase in one pass, including the `'*'` above. Where a field has a closed set, spell it out in the contract — the cost is a few lines of YAML and the return is that drift becomes a build error.
12. **Write the decision *and* its reversal into the docs, not just the new state.** Several of this session's changes superseded earlier decisions that were themselves written down with reasons — `tenant_id`-as-hedge, per-project keys, five roles, Test Engineer as the default. Leaving only the new value would make the next reader think the old reasoning was never considered. Struck-through rows with "→ superseded" in §3 cost nothing and prevent re-litigating.

---

## 7. How to run

**The two stacks are independent.** Gradle lives in `backend/`, npm in `frontend/`; neither build file sits at the repo root. Nothing at the root needs to be run.

```bash
# Backend — REQUIRES Docker running (every test boots a real Postgres 17)
cd backend
./gradlew build      # THE gate: compile with -Werror + all 87 tests via Testcontainers
./gradlew test       # tests only
```

There is deliberately **no H2 fallback**: RLS, `ltree` and `citext` have no H2 equivalent, so an in-memory lane would test a schema the product never runs on. If Testcontainers reports `Could not find a valid Docker environment` while `docker ps` works, it is the Docker-29 API-version floor, already pinned in `build.gradle.kts` — see §11.

```bash
# Frontend — no backend needed, MSW intercepts /api/v1 in dev
cd frontend
npm install
npm run dev          # http://localhost:5173  (sign in with any password of 4+ chars)
npm run build        # THE gate: tsc -b + vite build
npm run lint         # must stay at 0 warnings

# Regenerate types after ANY openapi.yaml change
npm run gen:api

# Audit test-id collisions (should print only per-file-prefix or exclusive-branch dupes)
grep -rhoE 'data-testid=(\{`[^`]*`\}|"[^"]*")' src | sed -E 's/data-testid=//' | sort | uniq -d
```

**Driving the resilience behaviour** (dev only — from the browser console):

```js
tcmsFaults.status(503, 30)  // every request 503s, with Retry-After: 30
tcmsFaults.hang()           // requests never answer — proves the timeout budget
tcmsFaults.expire()         // the next request 401s once — proves in-place session recovery
tcmsFaults.drift()          // a 200 with the wrong shape — must read as FAILED, not empty
tcmsFaults.only('/cases')   // scope the fault to one endpoint family
tcmsFaults.off()
```

Faults live in module state, so a **full page reload clears them** — arm the fault *after* the page has loaded when testing a failed refetch. Client error reports are also kept on `window.__tcmsErrors` in dev, so a caught error can be read back instead of expanded by hand in the console.

**The mock API is stateful and in-memory** — it resets on reload. `mockServiceWorker.js` lives in `public/` and is committed; re-run `npx msw init public/ --save` only if it is ever deleted.

`npm run gen:api` is wired for this. `npm run lint` (oxlint) must stay at 0 warnings — it is part of the phase gate, not advisory.

**Why `gen:api` runs via `npx` rather than a local dependency:** the Vite scaffold pinned **TypeScript 6.0.3**, and `openapi-typescript@7` peer-requires `^5.x`. Installing it locally needs `--legacy-peer-deps` or a TypeScript downgrade — neither is worth it for a build-time codegen tool that only reads YAML and emits a `.d.ts` (it never typechecks this project). `npx --yes openapi-typescript@7` resolves its own TypeScript and sidesteps the conflict. Revisit when `openapi-typescript` supports TS 6.

**Later, for production packaging:** Gradle should invoke the frontend build and copy `frontend/dist` into `backend/src/main/resources/static`, while `npm run dev` stays fully independent for day-to-day UI work. Conflating those is what makes monorepos feel slow.

---

## 8. Working agreement for this project

- **Load the plugin skills before producing anything they cover** (§0). This is the single most important line in this document.
- Verify with `npm run build`, never `tsc --noEmit` alone — **and then run the app** (§6, lesson 5).
- `openapi.yaml` is the contract — change it there, regenerate, then adapt both sides in one commit. Read it before coding against a shape; don't guess.
- **New display logic goes in the shared layer, not the page.** `lib/labels.ts` for enums, `lib/format.ts` for formats, `lib/results.ts` for count arithmetic, `DataRegion` for the three states, `Identifier` for anything monospace. A second implementation of any of these is how a hundred consistent pages become a hundred slightly-different ones.
- Save generated `.md` artifacts into this project under `docs/`, never to a global directory.
- The plan document is long by design; §6a–§6q each state the *decision and its reason*. Read the relevant section before changing behaviour it covers — several decisions look arbitrary until you see the failure mode they prevent.

---

## 9. Multi-company alignment of the frontend — DONE 2026-07-30

The frontend was written against the pre-multi-company design. It built clean the whole time, so it was *silently* out of date — the harder kind to notice. This is now corrected. Scope was **corrections only**: no company switcher, no `/c/{slug}` routing, no per-company branding (those wait for backend M6). Routes stayed project-scoped by decision.

Run through the `fe-react` skill (Mode B — extend), phases: contract → types → data → display.

### What landed

| Change | Where |
|---|---|
| Display keys `TC_0042` → **`TC-1`**, company-scoped, no zero-padding | `mocks/db.ts` allocator · 93 literals swept across `fixtures/{core,cases,reporting}.ts`, `index.css`, `Identifier.tsx`, `CaseFilterBar.tsx`, `PlanStatisticsPage.tsx` |
| **Executions lost their display key entirely** — reached via run or case | `openapi.yaml` (`TestExecutionSummary.key`, `ExecutionHistoryEntry.executionKey` removed) · `db.ts` · `ExecutionPage.tsx` · `RunDetailPage.tsx` |
| Role set 5 → **6** (Instance Admin + Company Admin), `COMPANY` scope | `openapi.yaml` `Role` · `fixtures/core.ts` |
| **`PermissionKey` as a 55-member contract enum** | `openapi.yaml` — the compiler now rejects any stale permission string |
| **`lib/permissions.ts` — new.** Catalogue as runtime data, exhaustive via `satisfies Record<PermissionKey, Area>` | mirrors the `lib/labels.ts` convention |
| Viewer is the default grant; projects are whole client products (`OLB`, `MOB`) | `fixtures/core.ts` |
| `automationKey` documented company-unique; `Project.key` pattern `^[A-Z0-9]{2,10}$`; `CaseId` param **UUID-only** | `openapi.yaml` |
| Route justification corrected — the old one was falsified | `app/router.tsx` |

### Two findings beyond the brief

1. **The `'*'` permission wildcard was a fiction.** The fixture gave Instance Admin `permissions: ['*']`; no such permission exists in the model (§6b — code checks permissions, never role names, and there is no wildcard). Worse than cosmetic: a sentinel keeps reading as "every permission" after a permission is *added that the role does not hold*. Coverage is now **derived** by comparing against the catalogue, so a gap renders as a gap. `TeamsPage` also groups permissions by area, which is what makes a 55-item list legible.
2. **`ExecutionHistoryEntry.executionKey` had no consumer** — a contract field nothing could populate once executions lost their key. Removed rather than left dangling.

### Gate evidence

`npm run build` clean · `npm run lint` 0 warnings · **36/36 assertions** in a throwaway `tsx` script (catalogue integrity, the three Test Engineer → Test Lead boundary lines, Company Admin holds no SYSTEM permission, grouping lossless for all six roles, key format, Viewer default) · 0 new testid collisions (the 6 pre-existing are the documented `TestIdPrefix` constants).

### ⚠ The browser pass did NOT happen

The Chrome extension was not connected. Per this project's own lessons 5–7, **the gates prove compilation, not correctness** — and this slice changed a table's column structure (`RunDetailPage` lost its "Execution" column, row→detail moved onto the title) and added grouped permission rendering. Both typecheck while potentially looking broken.

**Before trusting this visually, run `npm run dev` and check:** `/projects/{id}/runs/{runId}` (the executions table) and Teams & roles (the six roles, Instance Admin showing "every permission (55)", others grouped by area).

### Still deferred to M6

Company switcher · `/c/{slug}` routing and session switching · per-company branding (theme derived from two brand colours with contrast validation; status colours stay fixed) · invitation flow · per-membership display names · route flattening (now *possible* since keys are unambiguous, deliberately not done so the URL shape is designed once).

---

## 10. A SECOND, CONCURRENT WORK STRAND — read before editing the plan

**Another session was actively editing `docs/plans/…` while this handoff was being written.** The plan changed under me twice in one sitting. Nothing here is broken, but **assume the plan is ahead of this handoff** and re-read its §8 resolved tables before acting on anything in §3 above.

That strand is driven by **`docs/audits/2026-07-30-kiwi-tcms-capability-gaps.md`** — a one-directional competitive analysis of Kiwi TCMS capabilities the plan didn't cover (committed in `2e22120` for safekeeping). It produced a **"Resolved — second pass"** decision table in the plan's §8, and these are the ones that change scope:

| Decision | Impact |
|---|---|
| **Grants travel in the JWT** — `sys`/`cmp`/`prj` assignments + a `pv` revocation epoch, so no request resolves permissions from the DB | Viable *only because* the six built-in roles are immutable, making `role → permission[]` a compile-time constant. **The `pv` epoch check is mandatory** — without it a revoked grant survives until token expiry. Lands M0.5. |
| **`§6v` Plan Templates & Cloning — now written** (it was a dangling reference earlier; that is resolved) | Clone case → M1; plan templates + clone plan/run → M2. A template stores the **selection query**, not a frozen case list. `automationKey` is never cloned; review state resets to Draft. |
| **LDAP / Active Directory in scope for M4** | Separate code path (bind, not redirect). LDAPS mandatory. Identity keyed on `objectGUID`/`entryUUID`, **never the DN** — the LDAP equivalent of "never key on email". Kerberos/SPNEGO stays out of scope. |
| **Four `IssueTracker` adapters in M4** — Jira · GitHub · GitLab · Azure DevOps | Building four at once is what stops the port leaking Jira's vocabulary. Plus tracker-side back-links on filing, and **cached** status hover-cards — never a synchronous tracker call during render. **M4 grew to 8–10 weeks.** |
| **Defects tracked on the kanban board**, local `Defect` record always exists | This is the no-tracker fallback, and it means TCMS keeps its own bug-progress view regardless of tracker — including across projects on different trackers. Not a workflow engine. |
| **Mermaid diagrams in the doc space** | Source stored in the block so diagrams version and diff; pre-rendered to inline SVG for HTML/PDF, passed through as a fence for Markdown. M2.5. |
| **TAP and Robot Framework parsers: not now** | M3 parser set stays JUnit XML · TestNG · Allure · Cucumber JSON. |

### The one open question that has a deadline

**Outstanding #43 — do we want a third-party extension mechanism at all?** The only gap-analysis item not decided. It **must be answered before M3 freezes the ingestion contract**, because a result-parser SPI is nearly free if designed in and expensive to retrofit. The plan recommends (a): make the ingestion pipeline's existing parser interface public — a documentation and stability commitment more than new code. The alternative is declaring it a non-goal so the integration list stays bounded by our own throughput.

### What this strand has NOT done yet

- **The ERD and `V1__initial_schema.sql` do not reflect `TestPlanTemplate` or the reshaped `Defect`** (local record + `externalRef` + board work item). Both need a pass if those amendments stand.
- The JWT-grant decision has schema implications too — the `pv` revocation epoch needs a home.
- `openapi.yaml` has no plan-template, clone, or defect endpoints.

### Uncommitted at the time of writing

`docs/plans/2026-07-30-tcms-high-level-plan.md` — left uncommitted **deliberately**, because the other session was mid-edit and committing would have interleaved two authors in one diff. Commit it from a fresh session once that strand is quiet.

---

## 11. Backend M0 — first build (2026-07-30)

Docker became available, so the session ran the plan's own Immediate Next Step #3. Run through the **`be-java`** skill (agent definition + `pattern-writer` + `database-code`).

### The headline: V1 works, and running it found two defects

`V1__initial_schema.sql` had been statically validated but **never executed**. It runs clean on Postgres 17.10 — 63 tables, 56 RLS policies, all `FORCE`, and `ltree`/`pg_trgm`/`citext` all present in the stock image (the largest unquantified risk, since some managed hosts gate extensions). Full evidence in **`docs/audits/2026-07-30-v1-schema-first-execution.md`**.

**Trap 2 passes.** Verified as a non-owner role: with no `app.current_company` set, a query returns **zero** rows while the superuser sees the rows exist. The highest-severity silent-inversion risk in the schema is now closed empirically rather than by reasoning.

| # | Finding | Fix |
|---|---|---|
| 1 (HIGH) | **SYSTEM-scope grants were unreachable.** `role_assignment.company_id` was nullable to mean SYSTEM scope, but the policy is a bare equality and `NULL = anything` is never true — so an Instance Admin grant was invisible in *every* company context, permanently. It presents as an authorization denial, not a schema fault, and the tempting repair is `BYPASSRLS` on the app role, which destroys isolation for all 56 tables. Widening the policy is **also** wrong: measured, it regresses trap 2. | SYSTEM grants moved to **`instance_role_assignment`** (instance-level, no RLS, **user-only** — a team is company-owned, so a SYSTEM grant to a team lets a client-controlled entity confer operator rights). `role_assignment` is now `company_id NOT NULL`, removing the last nullable discriminator under RLS. |
| 2 (LOW) | **`role_permission` was missing from the ERD §0.1 exception list** while correctly having no `company_id` — so the schema test as specified would have failed the build on its first run, and the tempting repair is a meaningless `company_id`. | Corrected the list, not the DDL. Added `instance_role_assignment` too. |

Schema is now **64 tables, 56 policies, zero nullable `company_id` under RLS**.

### What was built

Gradle lives in **`backend/`, not the repo root** — the repo is polyglot, so each stack owns its directory and `cd backend && ./gradlew build` mirrors `cd frontend && npm run build`. Spring Modulith modules are **packages**, not Gradle subprojects, so no subproject split is needed; `settings.gradle.kts` records why and how to add one if that changes.

| Path | Purpose |
|---|---|
| `backend/settings.gradle.kts` · `build.gradle.kts` · `gradle/libs.versions.toml` | Spring Boot 3.5.6, Java 21 toolchain, version catalog, `-Werror`. |
| `tenancy/CompanyContext.java` | **A scope guard, not a setter** — `open()` returns an `AutoCloseable` that restores the previous value, so a company cannot outlive its block. Deliberately **not** `InheritableThreadLocal`: an async handler must re-establish from the event envelope, because inheriting silently makes a leak look correct under test. |
| `tenancy/CompanyAwareTransactionManager.java` | Hooks `doBegin`, so the Hibernate filter **and** the `app.current_company` GUC are established for **every** transaction — not just web requests. Scheduled jobs, `@Async` handlers, ingestion and the provisioning CLI are exactly where context loss is invisible. Uses `set_config(…, true)` rather than literal `SET LOCAL`, because the latter takes no bind parameters. |
| `tenancy/{Company,CompanyStatus,CompanyRepository,CompanyFilter}.java` · `package-info.java` | The `@FilterDef` and the company aggregate. |
| `project/{Project,ProjectRepository}.java` | The M0 vertical slice — a company-owned entity that proves the plumbing. |
| `IntegrationTestBase.java` | One container per run + **the privilege split**: Flyway migrates as the owner, the app connects as `tcms_app`. **The owner became a non-superuser in §19** — until then it was the container's superuser, which is what let V7's defect through. |
| `SchemaConventionTest.java` | **The ERD §0.1 enforcement mechanism.** 5 assertions. |
| `RlsIsolationTest.java` | **The two-company harness.** 8 assertions → 7 in §19, when the role-attribute assertion moved to its own home. |
| ~~`src/test/resources/db/testcallback/afterMigrate__create_app_role.sql`~~ | ~~Creates the unprivileged role.~~ → **superseded and deleted in §19.** Creating the role *after* migrating is what forced the owner to be a superuser. Replaced by `src/test/resources/db/testinit/tcms-roles.sql`, run **before** Flyway, mirroring the deployment's ordering. |

### Two things worth not re-learning

1. **A green isolation suite proves nothing until you break it on purpose.** The suite was mutation-tested: simulating **context bleed** (the transaction manager receiving a stale company) failed exactly the three isolation-dependent tests while the four that shouldn't depend on it kept passing. A blunter mutation — disabling isolation entirely — failed *all* of them, including two pure-unit tests, because `@BeforeEach` seeding broke. The targeted mutation is the one that proves the assertions *discriminate*; the blunt one only proves they run.
2. **That mutation surfaced an untested load-bearing property.** The policies declare `USING` with **no explicit `WITH CHECK`**, so Postgres reuses `USING` as the insert check — the read boundary is also the write boundary. Good behaviour, but implicit in the DDL. Now pinned by its own test, so a later "fix" adding `WITH CHECK (true)` fails instead of silently letting a company plant rows in another's tenancy.

Also: **Docker Engine 29 rejects the API version Testcontainers negotiates**, and reports it as `Could not find a valid Docker environment` — which reads as "no daemon" rather than "version floor". Pinned via `systemProperty("api.version", …)` in the test task (the `DOCKER_API_VERSION` env var is *not* the key docker-java reads).

### Next on the backend, in order

1. ~~`V2__triggers_and_seed.sql`~~ and ~~the migration chain~~ → **done 2026-07-30, see §12.** Shipped as **two** migrations (V2 DDL + V3 seed).
2. ~~**The remaining harness personas** — the no-project-grant member and the cross-company user.~~ → **done 2026-07-30, see §13.** Both landed with the evaluator; the no-project-grant persona found nothing, but building the evaluator behind it found two HIGH defects.
3. ~~**The async-context test**~~ → **done 2026-07-30, see §14.** It found a HIGH defect in V1's RLS policy (the reset-GUC case), fixed in `V4`.
4. **`provision-company` CLI** — now a thinner job than it was: the vocabularies exist as `tcms_seed_company_defaults` / `tcms_seed_project_defaults`, so the CLI creates the company row, opens the context, calls both, creates the first Company Admin as an invitation, and writes an instance audit event. **Nothing else may call those two functions.**
5. Docker Compose, CI, ArchUnit + Modulith verification, ~~the `identity` module~~ (**done, §13**).

### How to run it

```bash
cd backend
./gradlew build      # THE gate: compile (-Werror) + all tests against Testcontainers
./gradlew test       # 87 tests, needs Docker running
```

Docker must be running — every test boots a real Postgres 17. There is no H2 fallback and there should not be: RLS, `ltree` and `citext` have no H2 equivalent, so an in-memory lane would test a schema the product never runs on.

---

## 12. Backend M0 — V2 and V3 (2026-07-30)

Run through the **`data:data-migrations`** and **`data:data-postgresql`** skills. Continues §11's own next-step list, items 1 and 2.

### The migration was split in two, against what the ERD named

The ERD and the roles spec both called for a single `V2__triggers_and_seed.sql`. It shipped as:

| File | Kind | Contents |
|---|---|---|
| `V2__search_vectors_triggers_and_seed_functions.sql` | **DDL** | `search_vector` mechanisms · SINGLE-cardinality classification trigger · team cycle + company-consistency trigger · `tcms_seed_company_defaults` / `tcms_seed_project_defaults` |
| `V3__seed_permissions_and_roles.sql` | **DML** | 55 permissions · the six built-in roles · 183 `role_permission` grants |

The `migrations` skill's rule is one logical change per file with schema and data separate, and it earns its keep immediately here: a grant correction is a two-line corrective migration that does not re-run a trigger definition, and the seed can be re-run by hand during a recovery without touching DDL. Both files are idempotent (`ON CONFLICT DO NOTHING`, `WHERE NOT EXISTS`), which is what makes that safe.

### The `IMMUTABLE` question — measured, and the answer had a third part

ERD §10 point 3 flagged this as the next thing static analysis could not settle. On Postgres 17.10:

| Function | Volatility | Usable in a generation expression |
|---|---|---|
| `to_tsvector(regconfig, text)` | `IMMUTABLE` | yes |
| `to_tsvector(text)` | `STABLE` | **no** — reads `default_text_search_config` |
| `jsonb_to_tsvector(regconfig, jsonb, jsonb)` | `IMMUTABLE` | yes |
| `setweight(tsvector, "char")` | `IMMUTABLE` | yes |
| **`concat_ws(text, VARIADIC "any")`** | **`STABLE`** | **no** |

The regconfig-pinning part was as predicted. `concat_ws` was not, and it is the one that actually bit: it reads as a pure string function, and it is `STABLE` only because its `VARIADIC "any"` arguments pass through type output functions. `||` over `coalesce()` is immutable and is what the multi-field expression uses. Postgres refuses the whole `ALTER TABLE` with "generation expression is not immutable", so this fails loudly — but only once you try it.

### Generated columns for three tables, triggers for one — and why that asymmetry is the design

`test_case`, `condition` and `shared_step` use `GENERATED … STORED`. **A generated column cannot be forgotten**: there is no INSERT path that skips it, no trigger to drop, no window where column and row disagree. `doc_page` cannot use one — a page's text is in `doc_block` rows and a generation expression may only read its own row — so it is trigger-maintained from both sides, and every failure mode that opens has its own assertion: a `DELETE` that leaves deleted text matching, a title edit that wipes the body, a recursion between the two triggers.

Converting the V1 columns needed `DROP COLUMN` + re-add (`ALTER COLUMN … SET GENERATED ALWAYS AS` is a syntax error), and **dropping the column silently drops its GIN index** — verified, and both indexes are recreated in V2. Safe only because V1 has never been deployed outside CI.

### Company-owned seed data cannot live in a migration, and that shaped the file

The handoff's §11 list included "per-company classification dimensions, default case statuses, default configuration per project" in the seed. They are not there, for two independent reasons either of which is sufficient:

1. **There are no companies at migration time.** Each client gets its own *copy* of the vocabulary so one client renaming "Compatibility" doesn't rename it for another (§6d, §6s).
2. **Every table involved has `FORCE ROW LEVEL SECURITY`, which covers the migration role too.** An INSERT would need `app.current_company` set to a placeholder company — encoding a fixture as schema.

So the vocabulary content is still *versioned in a migration*, as two functions that `provision-company` calls per company and per project. Both **refuse to run** unless the active company matches the target, rather than writing rows the policy then hides. The Test Type / Test Level / Phase values, the Feature dimension, the six execution statuses and the Default configuration all live there — and the status colours match the frontend design tokens deliberately, since status colour is load-bearing data and is not brandable (§6u).

### The trigger that guards more than it was asked to

`case_classification.dimension_id` is a *denormalized copy* of `classification_value.dimension_id`, present so the SINGLE-cardinality rule can be enforced at all (a partial unique index cannot reach another table). Writing the trigger surfaced the hole the denormalization itself opens: **a caller that writes a MULTI dimension's id next to a SINGLE dimension's value bypasses the cardinality check entirely** *and* leaves a row lying about which axis it classifies, so every facet count computed from `dimension_id` goes wrong. The copy is now verified rather than trusted, and the mutation run below shows the cascade: disabling that one check broke the MULTI test too, because a mislabelled row shows up under the wrong dimension.

The trigger also takes a `FOR NO KEY UPDATE` lock on the parent case. There is no row to lock for a value that does not exist yet, so two concurrent inserts would each see zero existing rows and both commit.

### Gate evidence

`./gradlew build` clean — **45/45 tests** (13 pre-existing + 32 new across four classes: `SeedDataTest`, `SearchVectorTest`, `IntegrityTriggerTest`, `CompanyVocabularySeedTest`).

**Mutation-verified**, per this project's own lesson that a green suite proves the assertions *run*, not that they *discriminate*. Seven targeted mutations, each failing only what it should:

| Mutation | Tests failed |
|---|---|
| `doc_block` trigger drops `DELETE` | 1 — the both-sides doc_page test |
| `test_case` title weighted `B` instead of `A` | 2 — both generated-vector tests |
| `jsonb_to_tsvector` filter `["string"]` → `["all"]` | 1 — the JSON-keys test |
| SINGLE-cardinality check never fires | 1 |
| Denormalized `dimension_id` agreement check removed | 2 — including the MULTI test, via the cascade above |
| Team cycle walk removed | 1 |
| Seed functions no longer require company context | 1 |

An earlier, **blunter** mutation (a malformed generation expression, so V2 failed outright) took down 20 tests including all the seed ones — which proves only that they run. That contrast is the same lesson §11 recorded for the isolation harness, and it is why the seven above were made one narrow edit at a time.

**Also verified:** the seed matches `openapi.yaml`'s `PermissionKey` in both directions and matches the frontend fixture's grants role-for-role (48 Company Admin / 40 Test Lead / 27 Test Engineer / 8 Viewer / 5 Automation Service, Instance Admin derived). `SeedDataTest` parses `docs/specs/openapi.yaml` directly, so contract drift is a **backend** build failure too, not only a frontend type error.

### Two things worth not re-learning

1. **`@BeforeAll` is too early for anything needing the database.** All four new classes failed on `FATAL: password authentication failed for user "tcms_app"`, which reads as a credentials problem. It is an ordering one: the Spring context — and so Flyway, and so the `tcms_app` role its test-only callback creates — is built when the test *instance* is post-processed, after a static `@BeforeAll` has already run. `SchemaConventionTest` never noticed because it connects as the owner, which always exists. Use `@BeforeEach` with a null guard.
2. **A five-minute throwaway container beats a five-minute Gradle cycle.** Both migrations were dry-run against a scratch `postgres:17` via `psql` before a line of Java existed, which is where the `concat_ws` failure and the drop-column-drops-the-index behaviour were found. The suite then encoded what the probe had already established, instead of being the thing that discovered it.

### Still not started on the backend

~~Unchanged from §11's list, minus its first two items: the remaining harness personas, the async-context test, `provision-company`, Docker Compose, CI, ArchUnit/Modulith verification, and the `identity` module. The permission **evaluator** is M0.5 and is now unblocked.~~ → **superseded by §13**, which landed the `identity` module, the evaluator and both remaining personas. What is left is in §13.

---

## 13. Backend M0.5 — the identity module and the permission evaluator (2026-07-30)

Run through the **`be-java`** skill (agent definition + `pattern-writer` + `database-code` + `code-review`), Mode B — extend. Closes §11's next-step list items 2 and 5's `identity` entry.

### What shipped

| Path | Purpose |
|---|---|
| `identity/{AppUser,CompanyMembership,Team,TeamMembership,Role,RoleAssignment,InstanceRoleAssignment,Permission}.java` | The nine entities, plus `UserStatus` / `MembershipStatus` / `RoleScope` and two `@IdClass` keys. Plain UUID FKs, no associations — matching `Project`. |
| `identity/PermissionEvaluator.java` | **The single place effective permissions are computed.** Nothing else may answer "may this user do X". |
| `identity/PermissionCatalogue.java` · `PermissionSet.java` | The 55 valid keys, loaded once; an immutable result whose `has()` **throws on a key that is not in the catalogue**. |
| `identity/*Repository.java` (8) | Including the two native union projections the evaluator reads. |
| `test/identity/PermissionEvaluatorTest.java` | **Personas 3 and 4**, the gate matrix, the Test Engineer→Test Lead boundary. 24 assertions. |
| `test/identity/AppUserEmailCaseTest.java` | Pins `citext` semantics through the JPA mapping. |
| `test/tenancy/CompanyFilterTest.java` | **The entity-level `company_id` check — it did not exist.** |

### `CompanyFilterTest` was documented but never written

`Project.java` has claimed since M0 that "`CompanyFilterTest` [enforces the filter] over every entity". It did not exist. Four more company-owned entities arrived this session, which is exactly the case it was meant to cover. It now derives the company-owned set from **`pg_class.relrowsecurity`** — the live truth — rather than a fourth hand-maintained copy of the list, and checks **both directions**: an RLS-protected table's entity must carry the filter, and an instance-level entity must *not* (a filter on `app_user` would break the login path). Same family as §6 lesson 9.

### The spec was ambiguous in exactly one place, and it mattered

`role_assignment`'s table comment lists the membership gate above the SYSTEM term, reading as though it gates everything. **It cannot**, and the evaluator resolves it deliberately: SYSTEM-scope grants are **not** behind the company-membership or company-status gate, because

- an Instance Admin **provisions** a company — there is no membership row yet, so gating would lock them out of the company they just created;
- suspension is recoverable only if SYSTEM survives it, and the policy is *suspend, never delete*.

The **account** gate does cover SYSTEM: a DEACTIVATED operator holds nothing, or deactivation is not the instance-wide off switch it is documented to be. All three are pinned by tests.

### Two real defects, both found by a test failing on its first run

| # | Defect | Why it mattered |
|---|---|---|
| 1 (HIGH) | **COMPANY-scope grants leaked onto a foreign project id.** `evaluate(user, projectId)` never checked that the project was in the *active* company. COMPANY-scope rows carry no `project_id`, so asking about a Globex project while Acme was active unioned in Acme's Company Admin grant and answered **yes** — i.e. it answered "does this user hold X *somewhere* in Acme". | The evaluator *is* the authorization gate. RLS still protects the row at read time, so this was defence-in-depth rather than a live leak — but a caller resolving a project id from a URL would get a false allow. Fixed by resolving the project through the company-filtered lookup first; unreachable and non-existent are now indistinguishable (§6t). |
| 2 (HIGH) | **`findByEmail` was case-sensitive.** Hibernate binds the parameter as `varchar`, so `email = ?` against a `citext` column resolves through `text` and compares case-sensitively — while the unique index stays case-insensitive. | The two disagree: `Alice@corp.test` cannot be found by `alice@…` **and** cannot re-register, because the index refuses the duplicate. A user who can neither sign in nor sign up. Invisible to any fixture that uses one casing throughout. Fixed with an explicit `CAST(:email AS citext)`. |

### `citext` vs `ddl-auto: validate` — and the tempting wrong fix

The context refuses to start: *"found [citext (Types#OTHER)], but expecting [varchar(255)]"*. **`@JdbcTypeCode(SqlTypes.OTHER)` is the obvious fix and is wrong** — Hibernate resolves `OTHER` to a binary descriptor and fails with *"Could not convert 'java.lang.String' to '[B'"*. The right lever is `@Column(columnDefinition = "citext")`: Hibernate's validator falls back to comparing the SQL type **name** when the codes differ. Binding stays `setString`, and Postgres coerces on assignment.

### Gate evidence

`./gradlew build` clean — **75/75 tests** (45 pre-existing + 27 evaluator/email + 3 filter).

**Mutation-verified.** Ten targeted mutations, one narrow edit each; nine failed exactly and only what they should:

| Mutation | Tests failed |
|---|---|
| membership gate never shuts | 1 — the INVITED gate |
| SYSTEM term put behind the company gate | 3 — both Instance Admin tests + the suspended-operator one |
| project-in-company check removed | 2 — both foreign-project tests (this is defect 1 reproduced) |
| company SUSPENDED ignored | 1 |
| account gate removed | 1 — the DEACTIVATED operator |
| team branch dropped from the PROJECT query | 2 — team grant + the Lead/Engineer boundary |
| `citext` cast removed from `findByEmail` | 1 (defect 2 reproduced) |
| company filter removed from `CompanyMembership` | 1 — `CompanyFilterTest` |
| `hasAny` short-circuits on first match | 1 |

**The tenth survived, and that is reported rather than hidden:** removing `company_id = :companyId` from the PROJECT-scope query breaks **nothing**, because RLS independently scopes `role_assignment`. That is defence in depth working, not dead code — but it means **no test covers the configuration V1 explicitly permits**, where a deployment disables RLS for planner cost. Noted in `RoleAssignmentRepository`'s javadoc so nobody deletes the predicate on the evidence that it is untested.

### One pre-existing test was changed, deliberately

`RlsIsolationTest` asserted `count(*) FROM instance_role_assignment` **is zero**, which was incidental to the table being empty rather than the property it names ("readable, not blocked"). The new personas seed operator grants, so it went stale. It now seeds its own SYSTEM grant and asserts **the app role sees exactly what the owner sees** — the real claim, and one that cannot go stale as fixtures grow. Verified passing standalone, so no test-ordering dependency was introduced.

### Deliberately not done

- **The `OWN` qualifier** (roles spec A5) — `case:edit` / `case:delete` / `evidence:delete` are OWN-qualified for Test Engineer, resolved against `created_by` at the call site. The evaluator cannot see the row being acted on, so it does not belong here.
- **Caching.** The table comment specifies a cache keyed on `(company_id, user_id, project_id)`. Correctness first: an uncached evaluator is slow, a wrongly-keyed one is a cross-company leak.
- **The JWT grant claims + `pv` revocation epoch** (§10) — still unlanded, and the evaluator is what they would be derived from.

### Still not started on the backend

~~The async-context test,~~ (**done, §14**) `provision-company`, Docker Compose, CI, ArchUnit/Modulith verification — and now also: the evaluator's cache, the `OWN` qualifier, and an RLS-disabled lane for the surviving mutation above.

---

## 14. Backend M0 — the async-context harness, and the reset-GUC defect (2026-07-30)

Run through the **`be-java`** skill (agent definition + `pattern-writer` + `test-driven-development` +
`code-review`), Mode B — extend. Closes §11's next-step list item 3, the **last of M0's four isolation
deliverables**. Full evidence: **`docs/audits/2026-07-30-async-context-and-reset-guc-defect.md`**.

### The headline: writing the harness found a HIGH defect in V1's RLS policy

`V1`'s policy was `company_id = current_setting('app.current_company', true)::uuid`, with the `true`
there so an unset GUC yields NULL and a context-less query sees **zero** rows (trap 2). **That holds
only on a connection where the GUC was never set** — and production has a pool.

Postgres does not return a custom GUC to *undefined* when a transaction ends. Once assigned even once
— including via `set_config(…, is_local => true)`, exactly what `CompanyAwareTransactionManager`
issues per transaction — its reset value for the rest of the **session** is the **empty string**. So
every recycled connection evaluates `''::uuid` and raises:

```
ERROR:  invalid input syntax for type uuid: ""
```

**It fails closed, so it was never a leak.** It was a 500 on every legitimately context-less read
touching a company-owned table — the login path, the provisioning CLI, an operator query, a forgetful
report job — on any warm connection. Never on the first request; always afterwards.

It also means **the existing trap-2 test read stronger than it was**: it opened a fresh connection, so
it only ever covered the never-set case. `RlsIsolationTest` now has a second assertion for the
recycled case, reproducing what the pool actually does.

**Fixed in `V4__rls_policy_tolerates_reset_guc.sql`:** `nullif(current_setting(…, true), '')::uuid`,
collapsing both spellings of "absent" onto NULL. Still a bare equality — **no widening**, so trap 2 is
preserved rather than traded away. The migration rewrites from `pg_policies` (not a fourth copy of the
56-table list), refuses to touch a policy whose predicate is not `V1`'s, and asserts it rewrote exactly
56.

### What shipped

| Path | Purpose |
|---|---|
| `tenancy/CompanyScopedEvent.java` | The event-envelope contract: every cross-module event carries its `companyId`. **The only thing an async handler may re-establish context from.** A new event that forgets it does not compile. |
| `tenancy/CompanyContextTaskDecorator.java` | Makes "the handler forgot" fail **closed**. Every task runs with the context cleared, and the submitting thread's company restored afterwards (a `CallerRuns` rejection or an inline executor runs the task on the *caller's* thread — clearing without restoring would strip a live request's company mid-flight). |
| `tenancy/AsyncTenancyConfiguration.java` | `@EnableAsync` + `@EnableScheduling` + the decorator **as a single bean**. |
| `tenancy/CompanyContext.openFor(event)` | So the correct path is also the shortest one. |
| `db/migration/V4__rls_policy_tolerates_reset_guc.sql` | The defect fix, above. |
| `test/tenancy/AsyncCompanyContextTest.java` | The harness — the four named surfaces plus the mechanism. 8 tests. |
| `test/tenancy/PooledAsyncContextTest.java` | The same guard with `spring.threads.virtual.enabled=false`, where threads are pooled and it stops being insurance. 2 tests. |
| `test/tenancy/RlsIsolationTest` +1 | Trap 2 on a **recycled** connection. |
| `test/tenancy/SchemaConventionTest` +1 | **Every isolation policy carries the same predicate**, and none has an explicit `WITH CHECK`. |

Each of the four surfaces — ingestion, report generation, a scheduled purge, an `@Async`
`AFTER_COMMIT` event handler — runs **with a foreign company active on the submitting thread**, which
is the condition under which "inherited the caller's company" and "re-established its own" look
identical from outside. Two properties are pinned, and both are needed: handlers **re-establish** from
the envelope, and a handler that **forgets** reads zero rows rather than the submitter's. The first
without the second is a convention, and the convention is what gets forgotten.

### Four "safety" beans that did nothing

`AsyncTenancyConfiguration` first carried four `*Customizer` beans installing the decorator by hand on
each executor kind. **Mutating each to stop doing so changed no test result** — Boot 3.5 already
applies a single `TaskDecorator` *bean* to all four builders. Deleted, and the reason recorded in the
class comment so it is not "restored" as a fix later. Code that reads as a safety mechanism while
doing nothing is worse than no code.

### Gate evidence

`./gradlew build` clean — **87/87 tests** (75 pre-existing + 12 new).

**Mutation-verified**, eight mutations, one narrow edit each:

| Mutation | Tests failed |
|---|---|
| `V4` removed (the defect restored) | 2 — forgetful report + recycled-connection trap 2 |
| Decorator reduced to a pass-through | 2 — pooled residue + inline restore |
| One scheduler customizer stops setting the decorator | **0 — survived**, and that is the finding above |
| Transaction manager never establishes isolation (blunt) | 41 — proves only that they run |
| **Worker inherits the submitting thread's company** | 4 — and the forgetful-report test caught it reading **another company's rows** |
| Scheduled sweep leaks company N into N+1 | 1 |
| `@EnableAsync` removed | 3 |
| One policy widened to `USING (true)` | 1 — the new predicate assertion |

### Three things worth not re-learning

1. **A GUC's "unset" and a GUC's "reset" are different values, and only one of them is NULL.** The
   whole trap-2 design rested on `current_setting(…, true)` returning NULL, which it does — until the
   setting has been assigned once on that session, after which it returns `''` forever. Every
   connection in a pool is in the second state after its first company-scoped transaction. **A
   property tested only on a fresh connection is not tested for a pooled application.**
2. **The mutation that survives is the finding.** Three of eight mutations were expected to fail
   things and did; one survived, and it was the only one that told us something new — four beans of
   inert ceremony. Report the survivor rather than quietly re-rolling the mutation.
3. **Tests share a container, so they share a fixture.** This harness first ran against
   `PermissionEvaluatorTest`'s `3333…`/`4444…` company ids and silently saw its projects — an
   assertion failure that looked like a leak and was a collision. Give every test class its own
   company-id namespace, per the schema tests' mnemonic-prefix convention.

### Deliberately not done

- **`@Async` concurrency is unbounded.** `SimpleAsyncTaskExecutor` applies no limit and nothing
  backpressures async work. No production caller exists yet, and inventing a number now would be
  guessing.
- **Async handler failures have nowhere to go.** A `void` `@Async` listener swallows its exception.
  `V1` already carries an `outbox`; wiring it belongs to the module that publishes the first real event.
- **The four surfaces are test fixtures.** `execution`, `reporting` and `work` do not exist yet; what
  is pinned is the tenancy plumbing beneath them.

### Next on the backend, in order

1. ~~**`provision-company` CLI**~~ → **done 2026-07-30, see §15.** It found two more defects: a
   provisioned company could not allocate a display key, and every repository *query method* was
   running outside a transaction.
2. ArchUnit + Modulith verification — and note that `CompanyScopedEvent` is now the contract those
   rules should be written against.
3. Docker Compose, CI.
4. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation.

---

## 15. Backend M0 — `provision-company`, and the two defects writing it found (2026-07-30)

Run through the **`be-java`** skill (agent definition + `pattern-writer` + `database-code` +
`test-driven-development` + `code-review`), Mode B — extend. Closes §14's next-step list item 1 —
**the last M0 product deliverable.** Full evidence:
**`docs/audits/2026-07-30-provision-company-and-repository-transaction-defect.md`**.

### The headline: the first end-to-end use of a company found two fail-closed defects

Neither was a cross-company leak. Both were invisible to the existing suite for the same structural
reason — **nothing had yet tried to use a company end to end.**

| # | Defect | Fix |
|---|---|---|
| 1 (HIGH) | **A provisioned company could not allocate a display key.** Five entity types carry one (`TC-` `TS-` `TP-` `TR-` `DOC-`), and *nothing created their `key_sequence` rows* — `V1` declares the table, `V2` seeds the vocabularies, `V3` the permission catalogue, and no path writes a counter. The ERD's allocator is `UPDATE … RETURNING`, which against a missing row is **not an error but an empty result**, so it presents as "could not allocate a key" and invites two worse repairs: `MAX(key_seq)+1` (the racing allocator the ERD rejects) or `INSERT … ON CONFLICT` on the hot path of every case creation. | **`V5`** — `tcms_seed_company_defaults` also seeds the five counters, idempotently. In a migration, not the CLI, for `V2`'s own two reasons. A company provisioned earlier is topped up by **re-running the CLI**. |
| 2 (HIGH) | **Repository query methods ran outside any transaction.** Isolation is established in `CompanyAwareTransactionManager.doBegin`, which needs a transaction. Spring Data gives one to the CRUD methods *inherited* from `SimpleJpaRepository` (`findById`, `count`, `save`) — and **none** to the ones *declared on the interface*: derived queries and `@Query` methods have no implementation method to read an annotation from. So no `doBegin`, no `app.current_company`, and every RLS-protected read answered **zero rows** while `CompanyContext` held the right company. | **`@Transactional(readOnly = true)` on all eleven repository interfaces**, plus `RepositoryTransactionTest` — which both reproduces the defect *and* derives the repository list from the live context to fail the build on a new one that forgets. |

**Defect 2's blast radius is the part worth remembering.** It covered
`RoleAssignmentRepository`'s two permission-union queries — so **`PermissionEvaluator` resolved to
the empty set for a user who genuinely held grants**, unless the caller already had a transaction
open. Also the membership gate and every project lookup. Fail-closed throughout, so no leak; but each
symptom reads as a bug in the *feature* rather than in the plumbing.

**Why the suite missed it: the suite was shaped around it.** Every existing test wraps its reads in
an explicit `TransactionTemplate` — `PermissionEvaluatorTest`'s `memberOf` helper is
`inCompany(…, () -> tx.execute(…))`, written that way because the bare form did not work, without the
reason ever being chased. Measured with a probe, one line apart on the same thread: derived query →
empty, `count()` → 1 row, and the GUC read from inside the derived query's own execution → `''`.

### What shipped

| Path | Purpose |
|---|---|
| `db/migration/V5__seed_company_key_sequences.sql` | Defect 1's fix. Replaces `tcms_seed_company_defaults` whole (Postgres has no partial-body replacement), adding the five counters. |
| `provisioning/CompanyProvisioningService.java` | **The only provisioning path.** One transaction, company context opened *around* it (the transaction manager reads the context in `doBegin`; opening it inside leaves the GUC unset and RLS `WITH CHECK` refuses every insert). |
| `provisioning/ProvisionCompanyRequest.java` · `ProvisionCompanyResult.java` | Validated in the canonical constructor, so an invalid request cannot be constructed. The result reports **per part** whether each piece was created or already existed. |
| `provisioning/ProvisionCompanyCommand.java` | The CLI. Parsing and reporting only. Exit codes 0 / 2 / 1 = success (incl. top-up) / usage error / failed and rolled back. |
| `tenancy/InstanceAuditEvent.java` + repository | The operator trail. Append-only by construction — no mutator, no `@Version`. Lives in `tenancy` because suspension and admin appointment will write here too. |
| `tenancy/RepositoryTransactionTest.java` | Defect 2's reproduction **and** its mechanical enforcement. |
| `TcmsApplication` | `WebApplicationType.NONE` + banner off for the CLI invocation; a `Clock` bean. |
| `provisioning/package-info.java` | **A new module.** It composes `tenancy` + `identity` + `project`; inside any one of them it would create a sideways edge the module rules forbid, while depending *toward* those three is exactly what they permit. It is a leaf — nothing depends on it. |

### Decisions worth not re-litigating

- **The first Company Admin is `INVITED`, never activated.** An operator who could activate a
  client's administrator on their behalf is an audit finding, not a convenience.
- **Idempotent, and it reports per part.** Re-running is the *supported* way to top up a company
  after a release adds a vocabulary or a counter — which is what makes `V5` need no backfill. "It
  succeeded" is not enough information: an operator who meant to create a new client and sees
  "existed" everywhere has typed a slug belonging to another one.
- **It never *updates*.** A differing display name on a re-run is left alone rather than overwriting
  a rename the client asked for.
- **An existing email is reused, not rejected** — one account, two memberships, a different
  per-membership display name in each (§6u).
- **Both project arguments or neither.** Half a project means guessing the other half, in a field
  that is immutable once cases carry it.
- **No actor is invented for an unattended run.** A synthetic "system" user row is indistinguishable
  from a real operator in the trail.

### Gate evidence

`./gradlew build` clean — **115/115 tests** (87 pre-existing + 28 new).

**Mutation-verified**, eight mutations, one narrow edit each:

| Mutation | Tests failed |
|---|---|
| `V5` no longer seeds `key_sequence` (defect 1 restored) | 2 |
| `ProjectRepository` loses `@Transactional` (defect 2 restored) | 2 — the reproduction **and** the enforcement sweep |
| Membership created `ACTIVE` instead of `INVITED` | 1 |
| Grant idempotency check always says "absent" | 2 |
| `isRequestedBy` uses a prefix match | 1 — `provision-company-dry-run` would have provisioned |
| Half a project accepted | 1 |
| **`saveAndFlush` → `save` before the project seed call** | **0 — survived**, see below |
| Company context opened *inside* the transaction (blunt) | 14 — proves only that they run |

**The survivor is the finding, reported rather than re-rolled:** Hibernate's AUTO flush mode already
flushes ahead of a native query, so the explicit flush is not what makes that call work today — it is
what stops it depending on a flush mode nobody set deliberately. Kept, with the survival recorded in
the class comment. Same disposition as §13's surviving `company_id` predicate.

**Run for real** (lesson 5), from the boot jar against a live `postgres:17`, not from a test: first
run created all five parts and **exited 0** without starting a servlet container; the second reported
`existed` for all five; `--slug=acme` alone printed the named missing argument plus usage and exited
**2**.

### Three things worth not re-learning

1. **A repository's inherited methods and its declared methods do not get the same transaction
   treatment**, and only the declared ones matter for anything with a `WHERE`. Any ambient
   per-transaction mechanism — RLS GUCs, filters, auditing, `SET LOCAL` anything — silently skips
   them unless the interface is annotated. The one-sidedness is what makes it hard to see: `count()`
   works, so the connection, the pool and the policy all look fine.
2. **A suite can be shaped around a defect rather than exposing it.** Every read here was wrapped in
   a `TransactionTemplate` because the bare form "didn't work"; nobody asked why. When a test needs a
   wrapper to pass, the wrapper is a finding until it is explained.
3. **`@JdbcTypeCode(JSON)`, not `columnDefinition = "jsonb"`** — the `citext` trap in a different
   shape, and the difference between the fixes is the point. `columnDefinition` only satisfies
   `ddl-auto: validate`, which compares the SQL type *name*; the parameter is still bound as
   `varchar`, and Postgres does **not** implicitly cast `varchar` to `jsonb` the way it does to
   `citext`. The context starts, every read works, and the **first insert** fails — 14 tests at once.

### Next on the backend, in order

1. ~~**ArchUnit + Modulith verification.**~~ → **done 2026-07-30, see §16.**
2. **Docker Compose and CI.** Everything else in M0 is done.
3. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation.
4. **The invitation/activation path** — provisioning leaves an `INVITED` administrator and there is
   no table, token or email behind that yet. It is the first thing M0.5 needs to be usable.

---

## 16. Backend M0 — ArchUnit + Modulith verification (2026-07-30)

Run through the **`be-java`** skill (agent definition + `code-review`), Mode B — extend. Closes §15's
next-step list item 1. **No new product code** — this slice is entirely enforcement, and it is the
point at which "modules are Java packages" stops being a naming convention. Nothing about it was
enforced by the compiler: every class under `com.tcms` could see every other one.

### The four module declarations

`@ApplicationModule(allowedDependencies = …)` now sits on each `package-info.java`, matching what the
code already does:

| Module | Declares | Why |
|---|---|---|
| `tenancy` | `{}` — **nothing** | Everything depends on it. One edge out puts the isolation layer into a cycle, and "the boundary is established before anything reads a company-owned row" stops being guaranteeable. |
| `project` | `tenancy` | Company-owned, needs the boundary and nothing else. Must **not** reach into `identity` — the grant question is asked *of* the evaluator *about* a project, never the reverse. |
| `identity` | `tenancy`, `project` | The `project` edge is the §13 defect fix: the evaluator resolves the project through the company-filtered lookup before unioning any grant. |
| `provisioning` | `tenancy`, `identity`, `project` | It composes all three; that is why it is its own module (§15). |

**The default for `allowedDependencies` is *unrestricted*, not empty**, so leaving the annotation off
asserts nothing — and `verify()` reports no violation, because there is no constraint to violate. A
new module that simply forgot to declare would silently opt out of the whole rule set. Same failure
shape as a table quietly added without `company_id`, and it has its own test.

### What shipped

| Path | Purpose |
|---|---|
| `test/architecture/ModuleStructureTest.java` | Modulith: `verify()` (declared deps + cycles), the detected-module list, **every module declares** its dependencies, and **no declaration names a module outside `tenancy`/`identity`/`project`**. 4 tests. |
| `test/architecture/ArchitectureRulesTest.java` | The three rules Modulith cannot express (below), plus a fixture precondition on the class sets. 5 tests. |
| `test/architecture/ArchitectureRules.java` | `FOUNDATION_MODULES` and `moduleOf(JavaClass)`, shared so the two tests cannot drift. |
| four `package-info.java` | The declarations above, each with its reasoning. |

**Neither test extends `IntegrationTestBase`** — both are static bytecode analysis, so no Postgres and
no Spring context. A structural rule that only runs when Docker is up is one that gets skipped exactly
when someone is in a hurry.

### The three rules ArchUnit owns, and why Modulith cannot

1. **`tenancy` depends on nothing** — stated from the code side and derived (`com.tcms..` minus
   `com.tcms.tenancy..`), so a module added next year is covered without anyone remembering the file.
2. **`provisioning` is a leaf.** `allowedDependencies` constrains what a module reaches *out* to and
   has **no vocabulary for who reaches in**. The claim was already in its package comment; it is now a
   build failure. The composition root `com.tcms` is exempt, spelled as an exact package so the
   exemption covers `TcmsApplication` and nothing else.
3. **Every `@EventListener`/`@TransactionalEventListener` method takes one `CompanyScopedEvent`.**
   This is §14's whole design: the context is not inheritable, the decorator clears it, so the
   envelope is the handler's only trustworthy source.

### Two class sets, deliberately

Dependency rules import **production only** — a test may import anything, and running them over
`src/test` would report the isolation harness as an architecture violation. The event rule imports
**production *and* tests**, because the only event handler in the codebase today is
`AsyncCompanyContextTest`'s. Over production alone it would match nothing — and ArchUnit **fails a
rule that matches nothing**, which is the property that keeps it honest rather than a nuisance.

### Gate evidence

`./gradlew build` clean — **124/124 tests** (115 pre-existing + 9 new).

**Mutation-verified**, seven mutations, one narrow edit each. No survivors:

| Mutation | Tests failed |
|---|---|
| `tenancy` references `identity` | 2 — the tenancy rule + `verify()` |
| `tenancy` references a root-package class | 2 — the tenancy rule + `verify()`, but see below |
| `identity` references `provisioning` | 3 — leaf + cross-module + `verify()` |
| `implements CompanyScopedEvent` removed from an existing event | **compile failure** — see below |
| A new listener whose event forgets the envelope | 1 — the event rule, alone |
| `project` loses its `@ApplicationModule` | 1 — the declaration sweep, alone |
| `identity` *declares* `provisioning` (no code edge) | 1 — the declaration-contents rule, alone |

### Three things worth not re-learning

1. **A comment claiming a tool's blind spot is a claim, and it was wrong the first time.** The
   tenancy rule was written asserting Modulith "cannot see" a dependency on the composition root.
   Modulith **does** fail it today — as a *cycle* (`tenancy → root:com.tcms → provisioning →
   identity → tenancy`), which exists only because `TcmsApplication` wires the CLI. Remove that one
   import and Modulith goes quiet while the edge remains. The rule still earns its place; the comment
   was rewritten to say what was measured rather than what was assumed.
2. **A mutation can fail at the compiler instead of at the test, and that is a finding too.** Taking
   `implements CompanyScopedEvent` off an existing event does not reach the arch rule — it fails
   compilation at the `CompanyContext.openFor(event)` call. So the compiler already covers events that
   *are* opened. The gap the rule actually closes is **the handler that never calls `openFor`**: it
   compiles, runs, reads zero rows because the decorator cleared the context, and presents as an empty
   report rather than as a missing envelope.
3. **`git checkout --` on an uncommitted file is not a mutation revert, it is a delete.** Two
   mutations were reverted that way mid-run and silently took the session's own uncommitted
   `package-info` edits with them — which then contaminated the next mutation's result (an extra
   failing test that looked like a finding). Snapshot the file, or commit the slice before mutating
   it.

### Deliberately not done

- **No `ApplicationModules` documentation output** (`Documenter` — C4/PlantUML module canvases).
  Modulith can generate them; they belong with the first real cross-module event, when there is a
  graph worth drawing rather than a star around `tenancy`.
- **No rule pinning `@Transactional` on repository interfaces.** `RepositoryTransactionTest` (§15)
  already does it against the live context, which is stronger than a naming-based ArchUnit rule.
- **The publish side of the event contract is uncovered.** `ApplicationEventPublisher.publishEvent`
  takes `Object`, so bytecode carries no argument type and ArchUnit cannot check it. The *handler*
  side is checked, which is where the context is re-established and therefore where forgetting hurts.

---

## 17. Backend M0 — Docker Compose, CI, and the deployment privilege split (2026-07-30)

Run through the **`devops:devops-engineer`** agent (discovery/confirmation gate → `devops/docker` +
`devops/github-actions` + `devops/environment-config`; no cloud, IaC or Kubernetes skill loaded,
because those rows were confirmed as **none**). Closes §15's next-step list item 2 — **M0 is now
complete.**

### The headline: the privilege split existed only in the test suite

`IntegrationTestBase` migrates as `tcms_owner` and connects the app as unprivileged `tcms_app`, with
that role created by a **test-only** Flyway callback whose own comment says *"the production install
guide must create an equivalent role."* No such path existed. Worse, `application.yml` gave Flyway no
credentials of its own, so it inherited the datasource and would have migrated **as `tcms_app`** — a
role that by design owns nothing and can create nothing. The first `docker compose up` would have
failed on V1's first `CREATE TABLE`, and both obvious repairs are catastrophic:

| Tempting repair | What it costs |
|---|---|
| Run the application as the schema owner | Hands it `ALTER POLICY` / `DROP POLICY` on all 56 tables — the isolation layer becomes writable by the thing it constrains. `FORCE` still holds, so **nothing looks wrong.** |
| Grant the app role `BYPASSRLS` or ownership | Voids isolation outright, silently, for every table at once. |

Fixed by giving Flyway its own `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` in `application.yml`
(URL by reference to `spring.datasource.url`, so a typo cannot point migration and application at
two different databases) and by making role creation a **deployment** artifact.

**This is the same family as §6 lesson 9 and §16's "a comment claiming a property is a claim":** the
test harness asserted the split, a comment said production must reproduce it, and nothing did.

### What shipped

| Path | Purpose |
|---|---|
| `backend/Dockerfile` | Multi-stage temurin 21, Boot 3.3+ layered extraction (`-Djarmode=tools`), non-root **UID 10001**, OCI labels, `HEALTHCHECK` on `/actuator/health/readiness`. **147 MB.** The CLI path is preserved deliberately — no server-only entrypoint wrapper. |
| `backend/.dockerignore` | Small context. Carries no `*.jar` rule: excluding `gradle-wrapper.jar` breaks `./gradlew` inside the build stage with an error that reads like a network fault. |
| `deploy/postgres/initdb/10-tcms-roles.sql` | **The split, as the deployment owns it.** `tcms_owner` (non-superuser schema owner, Flyway only) + `tcms_app` (DML only). Passwords via psql `\getenv`, so no credential is committed. |
| `compose.yaml` (repo root) | Local stack. Passwords are `${VAR:?}` — required, never defaulted, so the file cannot be copied to a server with a forgotten password. |
| `.env.example` · `.gitignore` | The shape is committed; the file is not. |
| `deploy/compose.prod.yaml` | The VM. Backend container → **host-native** Postgres over the Docker bridge; `127.0.0.1:8080` publish; `mem_limit`; log rotation. |
| `backend/src/main/resources/application-prod.yml` | Layer 2 of the config model: Hikari at 10 for a 4 GB box, `forward-headers-strategy: framework` (Caddy terminates TLS), graceful shutdown. **No secrets, by construction.** |
| `.github/workflows/ci.yml` | Two independent jobs — backend `./gradlew build` on `ubuntu-latest` (Testcontainers needs the runner's Docker daemon), frontend `npm ci && build && lint`. Gradle wrapper validation. No deploy lane. |
| `frontend/package.json` | `engines: node >=22.12.0`, matching CI. **Note: the local machine is on v20.20.0, below the declared floor** — npm warns, does not fail. |
| `docs/plans/2026-07-30-tcms-hetzner-deployment-runbook.md` | The corrected runbook (below). |

### The Hetzner runbook corrected five things, four of them silent

The supplied runbook is sound generically and wrong for *this* codebase in five places:

1. **Ubuntu 24.04's `postgresql` is 16; everything here is tested on 17.** Now PGDG + `postgresql-17`.
   `ltree`/`pg_trgm`/`citext` exist on 16, so it would probably work — "probably" being the thing
   §6's lessons are about.
2. **`CREATE DATABASE appdb OWNER appuser` is the single-role collapse above.** Now both roles.
3. **`/api/health` does not exist** — it is `/actuator/health/readiness`. A checklist item that 404s
   teaches you to ignore the checklist.
4. **`SPRING_DATASOURCE_*` env names bind, but have no `DB_MIGRATION_*` equivalent** — using them
   silently gives Flyway the application's credentials, i.e. defect 2 by another route.
5. **`-Xmx1g` on a systemd unit** — the app runs containerized now, so heap is sized from the
   container limit via `MaxRAMPercentage`, with box tuning in `JDK_JAVA_OPTIONS`.

Decisions taken with it: **app containerized, Postgres and Caddy host-native** (the DB keeps host page
cache, apt security patches and plain `pg_dump` backups on a 4 GB box); **~~SPA served from the boot
jar (§7)~~ → superseded — Caddy serves `frontend/dist`**, keeping the Gradle and npm builds
independent as `settings.gradle.kts` already argued for.

### Gate evidence — run, not just built

`./gradlew build` **124/124 green** (test XMLs timestamped after the `application.yml` edit, so the
task genuinely re-ran) · `npm ci && npm run build && npm run lint` clean, 0 warnings · both compose
files parse · workflow carries every pinned action, no `@main`/`@latest`, no tabs.

`docker compose up` came up healthy first try, and the properties were then **proved against the live
stack rather than assumed** — this is the first time the isolation model has run outside the test
harness, and against a **non-superuser owner**, which is *stricter* than `IntegrationTestBase` (where
the owner is a superuser):

| Assertion | Result |
|---|---|
| All five migrations' `installed_by` | `tcms_owner` |
| `ltree` / `pg_trgm` / `citext` installed by a non-superuser | yes — they are *trusted* extensions (PG13+), which is what makes the whole split viable |
| `tcms_owner` / `tcms_app` `rolsuper`, `rolbypassrls` | `f`, `f` for both |
| Objects owned by `tcms_app` | **0** |
| Schema | 65 tables (64 + Flyway), 56 policies, 56 forced |
| Pool's actual role (`pg_stat_activity`) | `tcms_app` |
| Trap 2 — no context | **0 rows** |
| Correct company / foreign company | **1** / **0** |
| V4's reset-GUC case on a warm connection | `current_setting(…) = ''` is **true**, query returns 0 — not the pre-V4 error |
| `tcms_app` disabling RLS / dropping a policy / `TRUNCATE` | `must be owner of table` / `does not exist` / `permission denied` |
| Container user | `uid=10001(app)` |
| `provision-company` from the image | exit **2** on usage error, **0** on success, idempotent re-run reports `existed` for all four parts |

### Four things worth not re-learning

1. **A comment saying "the deployment must do X" is not a deployment doing X.** The test callback has
   said so since M0. Compose is the first thing that ever had to, and it found that `application.yml`
   made it impossible. Grep the repo for "the production install must" before trusting any of them.
2. **`docker images` is not the image size.** It reported **545 MB** for a 147 MB image — it counts
   the multi-platform manifest list and attestation artifacts. `docker image inspect --format
   '{{.Size}}'` is the arbiter. This is §6 lesson 8 (measure the bundle change) in a new costume, and
   it nearly bought a needless switch to an Alpine base.
3. **Ubuntu 24.04 base images already have a user at UID/GID 1000**, so the conventional
   `groupadd --gid 1000 app` fails outright. Failing is the *good* case: silently reusing that account
   would run the application as an identity the base image controls. UID 10001.
4. **The guidance said temurin ships no wget; measured, `21-jre-noble` does.** Rather than
   `apt-get install` it (an extra layer and an unpinned package pulled at build time), the Dockerfile
   **asserts** its presence and fails the build if a future base tag drops it. An install would have
   hidden exactly the change the guidance was warning about.

### Deliberately not done

- **No image CVE scanning.** Trivy blocking on criticals belongs in CI, and the gate *policy* is
  `qa-security`'s (`qa-security-image-infra-scan`), not this lane's. The image build it scans is now
  wired and waiting.
- **No deploy lane in CI**, by the confirmed scope. It needs an SSH deploy key, a GitHub Environment
  with required reviewers, and a rollback story — and the box does not exist yet. `deploy.sh` in the
  runbook §8 is the manual path to walk first.
- **No registry.** `docker save | ssh docker load`. Switching `image:` to GHCR is a one-line change.
- **Base images pinned by tag, not digest.** `eclipse-temurin:21-jre-noble` and `postgres:17` float
  their patch level. For `postgres:17` that is deliberate and load-bearing — it must match the tag
  `IntegrationTestBase` boots, and pinning a patch here would let local and CI drift apart.
- **Single instance, so a restart is visible downtime**, and the SPA production build has never been
  verified with MSW off against a real backend. Both are flagged in the runbook's §13.

### ⚠ The one thing that blocks a usable deployment

`provision-company` leaves the first Company Admin **`INVITED`**, and the invitation/activation path
(table, token, email) **still does not exist** (§15's next-step item 4). The runbook therefore stands
up infrastructure that has **no usable login**. That is now the first thing M0.5 needs, and it is
worth more than any remaining M0 polish.

### Next on the backend, in order

1. **The invitation/activation path.** Nothing is reachable without it.
2. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation.
3. When the box exists: walk `deploy.sh` by hand once, then move it into `deploy.yml` behind a
   GitHub Environment. Add the Trivy gate with `qa-security`.

---

## 18. Backend M0.5 — invitation, activation and local login (2026-07-30)

Run through the **`be-java`** skill (agent definition + `pattern-writer` + `database-code` +
`test-driven-development` + `code-review`), Mode B — extend, plus `data:data-migrations` for V6–V8.
Closes §17's *"the one thing that blocks a usable deployment"*. Plan:
**`docs/plans/2026-07-30-tcms-invitation-activation-login.md`**.

**No plugin skill covers local BCrypt+JWT issuance** — the two auth skills on disk (`auth0`,
`cognito`) are provider-side OIDC recipes. Said out loud per `CLAUDE.md`: general Spring Security
rules were used and nothing was invented in their place.

### The headline: the deployment now has a login, and running it found what the suite could not

`provision-company` left an `INVITED` administrator with no table, token or path behind that state.
That is closed. **`docker compose up` → provision → accept → sign in → 48 permissions**, verified on a
fresh volume against a **non-superuser owner**.

Three defects were found by *running* it, after 171 green tests:

| # | Defect | Why the suite was blind to it |
|---|---|---|
| 1 (HIGH) | **`FORCE ROW LEVEL SECURITY` covers the table owner, so `V7`'s `SECURITY DEFINER` company lookup returned zero rows.** Every token came out with **no `co` claim**; the first administrator of a new instance would sign in holding **0 permissions**, with no error anywhere. | **`IntegrationTestBase` migrates as the container's SUPERUSER**, and a superuser bypasses RLS unconditionally. Its own comment has warned since M0 that the test owner is weaker than the deployment's. This is the first time that gap produced a defect — and it will not be the last. |
| 2 (HIGH) | **`provision-company` could not start.** The CLI runs `WebApplicationType.NONE`, where `HttpSecurity` does not exist, so `SecurityConfiguration`'s `filterChain` bean could not be satisfied and the whole context failed. | Every test boots a *web* context. `CliContextStartsTest` covers it now, including that no filter chain is built there. |
| 3 (LOW) | **`@ConditionalOnProperty("spring.mail.host")` matches an empty value**, so `SPRING_MAIL_HOST: ${SMTP_HOST:-}` selected the SMTP mailer with nowhere to send — a connection-refused stack trace on every invitation instead of the "no relay configured" warning. | Nothing tested the unconfigured path *through compose's* spelling of "unset". |

**`V8` fixes defect 1 by removing the privilege escalation rather than making it work**: the function
now runs as the **caller** with RLS fully in force, walking the ACTIVE companies and asking the
ordinary policed question for each. It restores `app.current_company` afterwards — a function that
silently repoints its caller's tenancy discriminator is the exact shape of a cross-company read that
looks ordinary in every log afterwards.

### The two decisions that shaped everything else

| Decision | Detail |
|---|---|
| **`membership_invitation` is instance-level** (ERD §0.1, decided in `V6`) | The accept path runs **before authentication**, so no company context can exist — and `CompanyContext` is never populated from request input. Under RLS the row would be permanently unreachable, the same shape as V1's `role_assignment.company_id IS NULL` defect. **The cost is stated, not glossed:** "this user was invited to this company" sits outside the boundary. Mitigated in the DDL — no email, no name, a SHA-256 of the token, rows consumed then purgeable. The rejected alternative (encode the company id in the token so accept can open a context) keeps the exception list closed *and* makes request input select the tenancy. |
| **The token carries `sub`/`co`/`pv`, not grants** | §6b's `sys`/`cmp`/`prj` claims are **reserved and documented**, not forgotten. They are an optimization for a hot path nobody has measured, and shipping them creates a second authorization-resolution path beside `PermissionEvaluator` — §6b's own stated bug class. **`pv` ships now** because it is the revocation channel whatever the token carries, and retrofitting it means invalidating every token in flight. **TTL is 8h/30d rather than §6b's ~10 minutes, and that reverses when the grant claims land** — recorded in `JwtProperties`. |

### What shipped

| Path | Purpose |
|---|---|
| `V6__membership_invitation_and_permission_version.sql` | The invitation table (no RLS, by decision) + `app_user.permission_version`. |
| `V7` → `V8__companies_for_user_without_definer_rights.sql` | The login-time company lookup. V7's `SECURITY DEFINER` approach and its failure are kept in the file as the record of why V8 looks like it does. |
| `identity/{MembershipInvitation,MembershipInvitationRepository,InvitationService,InvitationTokens,PasswordPolicy,InvitationProperties}.java` | Issue, preview, redeem. 32 CSPRNG bytes, stored as a hex SHA-256 — a database read yields nothing redeemable. |
| `identity/{InvitationMailer,SmtpInvitationMailer,MailConfiguration}.java` | Real SMTP, **sent after commit**, failures logged and swallowed. |
| `identity/IdentityConfiguration.java` | `DelegatingPasswordEncoder` → BCrypt cost 12, so the algorithm is recorded *in* each hash and can be raised later. |
| `auth/` — **a new module** (`{tenancy, identity}`, a leaf) | `SecurityConfiguration` · `JwtConfiguration` · `SessionTokens` · `AuthenticationService` · `PermissionVersionFilter` · `CompanyContextFilter` · `CurrentUserService` · `AuthController` · `MeController` · `InvitationController`. |
| `openapi.yaml` + `frontend/src/api/schema.d.ts` | Two invitation endpoints, `InvitationPreview`, and `required` on `CurrentUser` **including its inline team objects** — lesson #1/#3 recurred here. |
| `compose.yaml` · `.env.example` | `TCMS_JWT_SECRET` (required, no default), `TCMS_BASE_URL`, optional SMTP. |

**`CompanyContextFilter` is the production entry point the tenancy layer never had.** Every company
opened before this slice was opened by a test or the CLI. It is try-with-resources: servlet threads
are pooled, and a context left open is inherited by the next request.

### Decisions worth not re-litigating

- **Every pre-auth failure is one failure, and costs the same time.** Unknown address, wrong password,
  not-yet-activated and deactivated all answer 401 with an empty body — and a miss verifies against a
  **dummy BCrypt hash**, because otherwise "no such account" answers in microseconds and "wrong
  password" in ~250 ms, which reinstates over the network exactly the oracle the shared body removes.
  Invitation redemption collapses five causes into **one** rejection constant, so splitting them apart
  later requires deliberately adding a constant.
- **A rejected password costs the invitation nothing** — the token is consumed only after the password
  is accepted, so a typo cannot burn the only way in.
- **Passwords are 12–72 bytes.** The 72 is BCrypt's: beyond it, input is silently ignored, so two
  passphrases sharing a 72-byte prefix both open the account. Counted in **bytes**, or exactly the
  users whose script is not Latin get past it.
- **Accepting returns 204, not a session.** One token-issuing path, and signing in afterwards proves
  the password round-tripped.
- **Someone joining a second company keeps their password** — the accept path *refuses* one rather
  than ignoring it.
- **A re-run does not replace an outstanding invitation** (it would invalidate a link already in
  someone's inbox); a lapsed one is replaced, and that counts as a change rather than a no-op.
- **`/auth/logout` is honestly documented as a client-side discard.** A stateless token has nothing to
  invalidate; `pv` is the real lever, and a `jti` denylist arrives with refresh tokens.

### Gate evidence

`./gradlew build` clean — **171 tests** (124 pre-existing + 47 new). `npm run build` + `npm run lint`
clean after `gen:api`. **Run for real** on a fresh volume: `co` claim present, `pv` = 1, 48 permissions
including `company:settings` and `case:create`.

**Mutation-verified**, 16 mutations across the slice, one narrow edit each. **Four survived, and each
survivor was a real gap** rather than noise — all four are now killed by exactly one test:

| Survivor | The gap it exposed |
|---|---|
| `isRedeemable` ignores `consumed_at` | Single-use rested on the membership having left INVITED. Anything that put it back would make a spent token work again. |
| the must-be-INVITED gate in `accept` | Without it, a **REMOVED** membership walks straight back to ACTIVE — offboarding undone by a link still in someone's mailbox. |
| the mailer rethrows instead of swallowing | "A mail outage cannot take a provisioned company down with it" was an assertion in a comment. The test now stops the relay outright. |
| the `pv` comparison, and the issuer validator | The only pv test also deactivated the account, so the *status* check one line away caught it. And a forged signature and a foreign issuer look nothing alike — only the first was covered. |

The blunt mutation (storing the token in plaintext) took down twelve tests, which proves only that the
suite runs. Recorded as the blunt one it is.

### Four things worth not re-learning

1. **`FORCE ROW LEVEL SECURITY` covers the owner, so `SECURITY DEFINER` is not an RLS bypass here.**
   The reflex reach for a definer function to answer a pre-auth question is wrong in this schema, and
   it fails *silently and only in production*, because the test harness's owner is a superuser.
2. **The suite cannot see anything that depends on the owner not being a superuser.** This is now a
   known, named gap rather than a footnote in `IntegrationTestBase` — see "Next" below.
3. **Every test boots a web context, so the CLI's context is untested by construction.** The same jar
   being two programs means two startup arrangements, and only one had coverage.
4. **`git checkout --` on an uncommitted file is a delete, not a revert — again.** §16 recorded this
   and it recurred mid-mutation-run, silently wiping the session's uncommitted Phase 3 edits. The
   working rule is now unconditional: **commit the slice before mutating it.**

### Deliberately not done

- **No refresh tokens, no `jti` denylist.** Both belong with the grant claims, where the TTL comes down.
- **No login throttling or lockout.** The constant-time miss closes the *timing* oracle; it does nothing
  about volume, and rate limiting is a separate concern with a separate home.
- **No password reset.** The invitation machinery is most of it — a token table, a mailer, a policy —
  but the flows differ (an existing password must be replaced, not set) and merging them now would
  make the accept path do two jobs.
- **No admin-initiated invitations** (`POST /companies/{id}/invitations`). `InvitationService.issueIfPending`
  is already the whole behaviour; what is missing is the endpoint and its permission check.
- **No `pv` cache.** §6b specifies Caffeine; it is a primary-key read today. Correctness first, exactly
  as the evaluator's own cache was deferred in §13 — a wrongly-keyed cache here accepts revoked sessions.
- **`revokeSessions()` has only two call sites** (activate, deactivate). Grant, team and company-membership
  changes must bump it too; none of those mutation paths exist yet, and that is recorded in the method
  rather than assumed covered.
- **The frontend has no invitation-accept route.** `tcms.invitation.accept-path` points at
  `/accept-invitation`, which the SPA does not serve yet — the link works against the API, not the UI.

### Next on the backend, in order

1. ~~**A non-superuser owner in `IntegrationTestBase`.**~~ → **DONE, §19.**
2. **The frontend accept-invitation route + real login wiring** — the SPA still runs entirely on MSW,
   so the two halves have never met.
3. The evaluator's cache, the `pv` cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving
   mutation.
4. Admin-initiated invitations and password reset, in that order.

---

## 19. Backend — the test harness stopped migrating as a superuser (2026-07-31)

Run through the **`be-java`** skill (agent definition + `ep-systematic-debugging` +
`ep-test-driven-development`), **Mode A — fix**. Closes §18's next-step item 1, which was named there
as *"the highest-value backend work available"*. **Test-harness only: no production code changed.**

### The gap, and why it was the top of the list

`IntegrationTestBase` migrated as the Testcontainers container user — **a superuser** — and a superuser
bypasses RLS unconditionally, `FORCE` included. So every one of the 56 policies was *mandatory in
production and advisory under test*, and **the suite could not assert anything that depends on the owner
not being a superuser.** That is not a hypothetical: it is exactly how V7's `SECURITY DEFINER` company
lookup reached production returning **zero rows** while 171 tests stayed green (§18 defect 1). It took
`docker compose up` to find, and nothing in the suite would have found it later either.

The cause was **ordering**, not intent: `tcms_app` was created by a Flyway `afterMigrate` callback — i.e.
*after* the tables existed — so whatever ran the migration had to already own the schema, and the only
role available at that point was the container's superuser.

### Reproduced first, then fixed

Per Mode A, the failing assertions were written **against the old harness** and watched fail for the
right reasons before anything was changed. Three of `PrivilegeSplitTest`'s seven went red: the owner's
`rolsuper`, "FORCE covers the owner" (the owner saw the row it must not), and "the owner is *policed*,
not denied". Those three are the V7 defect's shape, expressed as assertions.

### What changed

| Path | Change |
|---|---|
| `src/test/resources/db/testinit/tcms-roles.sql` | **New.** The suite's mirror of `deploy/postgres/initdb/10-tcms-roles.sql`, run as a Testcontainers **init script — before Flyway**, which is the deployment's own ordering. Creates non-superuser `tcms_owner` + `tcms_app`, transfers database ownership, and grants the app role through `ALTER DEFAULT PRIVILEGES`. |
| ~~`src/test/resources/db/testcallback/afterMigrate__create_app_role.sql`~~ | **Deleted.** Its ordering was the cause. Its `GRANT … ON ALL TABLES` would also have masked a broken `ALTER DEFAULT PRIVILEGES` in the deployment script indefinitely — the harness now exercises the deployment's *mechanism*, not an equivalent-looking substitute. |
| `IntegrationTestBase.java` | Container user renamed `tcms_super`; Flyway connects as `tcms_owner`; `spring.flyway.locations` back to the production default. `ownerConnection()` **split into two deliberately un-confusable methods**: `superuserConnection()` (ground truth, the only RLS-exempt connection in the suite) and `migrationOwnerConnection()` (owns every table, **still policed**). |
| `PrivilegeSplitTest.java` | **New, 7 assertions.** Role attributes for both roles · FORCE covers the owner · the owner is filtered rather than denied · every migration's `installed_by` is `tcms_owner` · the owner owns every relation and the app role owns none · the app role cannot `DISABLE ROW LEVEL SECURITY`, `DROP POLICY` or `TRUNCATE`. |
| `RlsIsolationTest.java` | Its role-attribute test removed — `PrivilegeSplitTest` owns that claim now — leaving a pointer comment where it was. |
| 4 test files | `ownerConnection()` → `superuserConnection()` at the call sites; `SchemaConventionTest`'s cached field renamed `catalogConnection`, since what it does is read the catalogue. |
| `deploy/postgres/initdb/10-tcms-roles.sql` | Header corrected: it pointed at the deleted callback, and **the direction of the mirroring has reversed** — the suite now follows this file. |

**Naming was the safety mechanism.** `ownerConnection()` was not repurposed to mean the new,
policed owner: a call site that still wanted the old semantics would have compiled and silently changed
meaning. Deleting the name forces every one of them to be looked at.

### Gate evidence

`./gradlew build` clean — **177 tests** (171 − 1 relocated duplicate + 7 new). The three red assertions
are green **for the right reason**: the owner is `rolsuper=f, rolbypassrls=f`, sees **0** of its own
tables' rows with no company context, and sees the company in full with one set. All three trusted
extensions (`ltree`, `pg_trgm`, `citext`) still install — they are *trusted*, which is the only reason a
non-superuser owner is viable, and the suite now proves that rather than the compose run alone.

### Two defects in the new test, both order-dependence, both worth recording

1. **The probe company reused `3333…`, which `PermissionEvaluatorTest` also seeds projects into.** Green
   alone, red in the full suite. Fixed with an id no other class uses — and, more importantly, by
   asserting against **ground truth** (`the owner sees what the superuser sees, for this company`)
   instead of the literal `1`. A hard-coded count couples a test to a fixture never growing.
2. **The seed wrote UUID literals while the assertions read the constant.** Changing the constant left
   the two pointing at different companies, and the symptom — a failed *precondition* — reads exactly
   like a broken privilege split. The seed binds from the constants now. §6 lesson 9's family: if two
   places must agree, make one derive from the other rather than asserting they match.

### Three things worth not re-learning

1. **A test harness weaker than the deployment is a blind spot with a green light on it.** The gap was
   documented in `IntegrationTestBase`'s own comment from M0 and cost a HIGH defect anyway. Documenting
   an untested property is not testing it — the same shape as §17's *"a comment saying the deployment
   must do X is not a deployment doing X"*, one level up.
2. **The harness should reuse the deployment's mechanism, not an equivalent-looking one.** `GRANT ON ALL
   TABLES` after migrating and `ALTER DEFAULT PRIVILEGES` before it produce the same privileges today and
   fail differently forever after. The suite now runs the same ordering and the same statements the
   deployment does, so `deploy/postgres/initdb/` is covered by CI for the first time.
3. **When a rename changes a method's meaning, delete the old name.** Both connections are `Connection`
   and both work; only the compiler refusing the old name makes every call site get re-read.

### Deliberately not done

- **`compose.yaml` and the production path are untouched.** They already had the non-superuser owner
  (§17) — the suite was the thing that did not.
- **No mutation run over the new assertions.** They *were* the mutation: they went red against the old
  harness and green against the new one, which is the same evidence from the other direction.
- **Passwords are literals in `db/testinit/tcms-roles.sql`.** JDBC has no `\getenv`, the container lives
  for one test run on a random port, and the deployment's file — the one that must never carry a
  credential — still uses `\getenv`. Nothing asserts the literals match `IntegrationTestBase`'s
  constants; a mismatch fails every test on the first connection, loudly.
- **`RepositoryTransactionTest`'s and §13's surviving mutation lanes are unchanged.** An RLS-disabled
  lane is still open work (§18 next-step 3), and it is now *easier*: the roles exist before Flyway, so a
  variant harness can grant `BYPASSRLS` to a third role without touching migrations.

### Next on the backend

Unchanged from §18, minus the item this closes: **the frontend accept-invitation route and real login
wiring** is now the highest-value work — the SPA still runs entirely on MSW, so the two halves have
never met.
