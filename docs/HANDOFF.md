# TCMS — Session Handoff

**Date:** 2026-07-31 (last session: **effective permissions and disable-don't-hide** — §31)
**State:** Planning and design complete **including multi-company tenancy**. **Backend M0 is COMPLETE**, and **M0.5 now has a usable login** (§18): invitation → activation → sign-in works end to end, `CompanyContext` finally has a production entry point, and the permission evaluator answers over HTTP. **The instance is reachable for the first time.** Frontend complete through **Phase 3** and aligned with the multi-company decisions (§9), and **the two halves have now met (§20)**: `VITE_API_MODE=live` points the SPA at the real backend through a dev proxy, `/accept-invitation` redeems a real CLI-issued link, and a real session survives a reload. **As of §25 the SPA's biggest screen renders live**: the case grid, its suite tree, its facet chips and its filters all run against the real API. **As of §26 that screen raises no errors at all** — its condition library is served, so nothing on it 404s. **As of §27 the case *detail* screen is live too, including §6f's suite-condition inheritance** — which makes the `repository` module's read surface complete. **As of §28 the product has its first WRITE**: editing a case is live end to end, `test_case_version` has a writer so §6j history actually accumulates, `audit_event` has its first row, and the write gate is a permission (`case:edit`) rather than the read gate every role holds. **As of §29 a case can be CREATED live**: `TC-n` is allocated from the company's `key_sequence` by the one non-racing statement, v1 is written with the case so a created case has no history gap, and the `Idempotency-Key` the SPA has sent since Phase 1 is finally read — a retry returns the case it already made. **As of §30 a provisioned company is finally usable without hand-written SQL**: suites can be created, renamed and deleted from the tree, both halves in one slice, so `useCreateSuite` — dead in the data module since Phase 1 — has its first consumer. **As of §31 the SPA finally asks what the caller may do**: `GET /me/permissions` — in the contract and implemented since the mock era, and never once called — now gates eleven write affordances, so an action the server will refuse renders disabled with a reason instead of inviting a 403. Deleting cases and every other module's writes still run on MSW. Frontend Phase 4 (tests) and 5 (review) not started.
**Build status:** `backend` — `./gradlew build` clean, **547/547 tests green against Testcontainers Postgres 17**, migrating as a **non-superuser owner** (§19). `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **and driven in a real browser in both API modes** (§20). **`docker compose up` serves provision → accept-in-the-browser → sign in against a non-superuser owner** (§18, §20), and its aggregate `/actuator/health` now reports **UP** — the `mail`-indicator defect §20 found is **fixed in §21**. CI runs both gates on GitHub Actions.
**Branch:** `master`. Per the git workflow, **work continues on the checked-out branch; do not branch per task.** Last commits: `88af5e5` case create with the non-racing key allocator, `8668102` the first write gated on a permission, `6d617a2` design renders (**a concurrent strand — see §10**).

> **⚠ A file-sync daemon on this machine creates `<name> 2.<ext>` conflict copies, and they BREAK
> THE BUILD.** 14 were sitting in the tree at the start of §30 — `ProjectController 2.java` declares
> `public class ProjectController`, which is a hard `compileJava` error, so the backend did not
> compile on arrival. They are gitignored inside `build/` and untracked in `src/`, so **`git status`
> does not always show them and a clean `git status` does not mean a clean build.** They reappeared
> mid-session the moment a script bulk-deleted and recreated a directory. **Check
> `find . -name "* 2.*" -not -path "./.git/*"` before trusting a build failure**, and prefer editing
> files in place over `rm -rf` + `cp -R` on a source directory.

> **⚠ Re-derive the next slice from the code, not from the last session's "Next" list — §28, and
> again in §29.** §27's three named next steps were all unsupportable; §28's own list then led with
> `/versions` again, and `useCaseVersions` **still has no consumer**. Twice in a row, the same way —
> so it is a property of writing that list at the end of a slice, not a mistake. The orientation
> pass is not optional, and its first question is *which of these has a consumer*. One grep each.

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
./gradlew build      # THE gate: compile with -Werror + all 376 tests via Testcontainers
./gradlew test       # tests only
```

There is deliberately **no H2 fallback**: RLS, `ltree` and `citext` have no H2 equivalent, so an in-memory lane would test a schema the product never runs on. If Testcontainers reports `Could not find a valid Docker environment` while `docker ps` works, it is the Docker-29 API-version floor, already pinned in `build.gradle.kts` — see §11.

```bash
# Frontend — two API modes (§20). MOCK is the default and needs no backend.
cd frontend
npm install
npm run dev          # http://localhost:5173  (mock: sign in with any password of 4+ chars)
npm run build        # THE gate: tsc -b + vite build
npm run lint         # must stay at 0 warnings

# LIVE mode — the SPA against the real Spring Boot API, through the Vite proxy
cp .env.example .env.local && sed -i '' 's/VITE_API_MODE=mock/VITE_API_MODE=live/' .env.local
# …then, from the repo root: docker compose up -d, and provision a company to sign in as:
#   docker compose run --rm backend provision-company --slug=acme --name="Acme" \
#     --admin-email=ops@acme.test --admin-name="A. Admin"
# The CLI prints a single-use /accept-invitation?token=… link. Open it, set a password, sign in.
# After changing .env.local, RESTART vite and clear node_modules/.vite — an in-place restart
# leaves a stale optimize-deps hash and the page renders blank with no error (§20).

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

---

## 20. Frontend — the two halves meet: live API mode, real login, accept-invitation (2026-07-31)

Run through the **`fe-react`** skill (agent definition + `forms` + `data-fetching` + `routing` +
`test-ids` + `ui-rules`), **Mode B — extend**. Closes §18's next-step item 2, which §19 left as the
highest-value work: *"the SPA still runs entirely on MSW, so the two halves have never met."*

**They have now met.** A company provisioned by the CLI, an invitation redeemed in the browser
against Postgres, a session issued by the real `AuthController`, and a page rendering a display name
that came out of `company_membership` — all through the SPA, for the first time.

### The slice

| Path | Change |
|---|---|
| `src/app/apiMode.ts` | **New.** `VITE_API_MODE=mock` (default) `\| live`, read once at module load. A mode that could change at runtime means a session authenticated against one API reading from the other. |
| `src/main.tsx` | MSW starts only in mock mode. **The literal `import.meta.env.DEV` test stays the first statement** — see the defect below. |
| `vite.config.ts` | Dev **proxy** `/api` → `VITE_API_PROXY_TARGET` (default `localhost:8080`). A proxy, not a CORS grant: the shipped product serves the SPA from the same jar as the API, so dev now matches that arrangement instead of inventing a looser one. |
| `.env.example` · `.gitignore` | Every variable documented with its default; `.env*` ignored except the example. |
| `src/auth/{authContextValue,AuthContext}.tsx` | **Session restore.** The stored user is a cache of the last `/me`, not a session — on mount with a token, the server is asked. A **401 clears**; a 500/timeout does **not** (a thirty-second backend restart must not sign everyone out). |
| `src/auth/SessionGate.tsx` · `src/app/router.tsx` | **New.** Holds the router until `/me` answers, so no route fires queries with a token the server has already rejected. Only waits when a token exists — public routes are unaffected. |
| `src/features/auth/AcceptInvitationPage.tsx` · route | **New public route** `/accept-invitation?token=…`, matching `tcms.invitation.accept-path`. Password 12–72 **bytes** (BCrypt's real limit, counted in bytes or the limit lands elsewhere for non-Latin scripts), confirm field, 422 mapped onto the field, **one** non-committal panel for every 404. |
| `src/features/auth/LoginPage.tsx` | Success banner + address carried from a redemption; the seeded mock address no longer prefills in live mode; **401 renders one fixed sentence** rather than the server's reason phrase. |
| `src/api/{client,queries,types}.ts` | Invitation preview/accept hooks (`meta: handled`, `staleTime: Infinity`); `ApiError.detail` gets a floor when `statusText` is empty. |
| `src/mocks/{db,handlers}.ts` | Two seeded invitation tokens (`new-user-token`, `existing-user-token`) covering both accept shapes, single-use; `/me/permissions` reshaped to the contract. |

### Two decisions worth not re-litigating

| Decision | Why |
|---|---|
| **Mock stays the default; live is opt-in** | `npm run dev` needing no Docker, no Postgres and no provisioned company is worth keeping — it is the loop that built ten screens. Live mode is what *proves* the two agree, not what replaces the fast loop. |
| **The router waits for `/me` rather than rendering optimistically** | Optimistic render costs one round trip less and buys a race: the shell paints from the cached user, a dozen route queries 401 in parallel, and the session-expired dialog appears *during first paint* — for a session that simply ended. Waiting removes the state instead of handling it. Verified: a tampered token now lands on `/login` with **no dialog and no notification**. |

### One defect, found by the build output rather than by reading

**Routing the `import.meta.env.DEV` check through the imported `IS_MOCK_API` constant shipped MSW to
production** — handlers, fixtures and all, a **485 kB chunk** in `dist`. Vite substitutes
`import.meta.env.DEV` in place, so Rollup can prove the branch unreachable and drop the dynamic
import; behind an imported binding it cannot. The guard is back as the first statement with the
reason written above it. §6 lesson 8 ("measure the bundle change before believing it") caught this
the same way it caught the DataGrid split — total JS is **1,407,520 → 1,413,895 bytes**, +6.4 kB for
a whole new page, and the entry chunk is unchanged at 124 kB gzip.

### Gate evidence

`npm run build` clean · `npm run lint` 0 warnings · **driven in a real browser in both modes.**

*Mock:* preview → client validation (12-byte floor and confirm-mismatch, both before any request) →
accept → redirect to sign-in with the success banner and the address prefilled · an unknown token →
the one rejection panel · the no-password (second-company) variant renders without a password field.

*Live, against `docker compose up` + a provisioned `northwind`:* the CLI's link previewed
**`O. Persson / ops@northwind.test` read out of Postgres** → password set → 204 → sign-in rejected
once with the single fixed sentence → signed in → **reload kept the session** and the rail showed
the membership display name → **a tampered token signed out cleanly to `/login`, no dialog**.

Everything past authentication 404s in live mode, correctly: the backend has exactly three
controllers (`Auth`, `Invitation`, `Me`), so `/projects` does not exist yet. The app degrades the way
Phase 3 built it to — failed region, one notification, rail and chrome still rendering.

### Two things worth not re-learning

1. **A stale `node_modules/.vite` after an in-place restart renders a blank page with no error at
   all.** Changing `.env.local` makes Vite restart itself; the optimize-deps hash goes stale and
   module fetches fail as *"Failed to fetch dynamically imported module"* with an empty console and a
   200 on every request when curled. Ten minutes went into hunting an app defect that was a dev-server
   cache. **Kill vite and `rm -rf node_modules/.vite` after an env change** — it is in §7 now.
2. **Drift in an unused mock handler is still drift.** `/me/permissions` returned an array of
   per-project entries that no endpoint has ever produced and nothing consumed. It was invisible
   while MSW was the only API and would have failed the first consumer written against it.

### Found, not fixed — a backend defect this pass surfaced → **FIXED in §21**

**`docker compose up` reports `/actuator/health` DOWN on every default deployment.** Both probe
groups are UP and the container healthcheck is green, which is why nobody noticed; the aggregate is
DOWN because the **`mail` health indicator** is trying to reach `:1025`:

```
"mail": { "status": "DOWN", "details": { "location": ":1025",
          "error": "MailConnectException: Couldn't connect to host, port: localhost, 1025" } }
```

Same family as §18 defect 3 and one level up from it: that fix stopped the *custom mailer* from
selecting SMTP on an empty `SPRING_MAIL_HOST`, but Spring's own mail auto-configuration still sees
the property as *present* and registers its health indicator. Anything polling the documented
`http://localhost:8080/actuator/health` sees a healthy instance reporting DOWN. Left for a backend
slice because the right fix is a decision (disable the indicator, or stop passing an empty host at
all) and this was a frontend pass — reproduced with
`docker compose run --rm -e MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always -p 127.0.0.1:8099:8080 backend`.

### Deliberately not done

- **No runtime response validation**, still §5's largest resilience gap — and live mode makes it
  matter more, since a real API can now drift from the contract in a way MSW never could.
- **No admin-initiated invitation UI.** The route redeems a link; issuing one is still the CLI's job
  (`POST /companies/{id}/invitations` does not exist — §18).
- **No `/me/permissions` consumer.** The hook is not written; `lib/permissions.ts` still reads from
  the fixture role catalogue, which is honest only in mock mode.
- **Live mode past authentication is untestable** until the backend grows the project/case endpoints.
  Everything in §4's ten screens still runs on MSW, and that is not a frontend gap.

### Next

1. ~~**Backend M1** is now the constraint: `/projects` and the repository endpoints~~ → **first
   slice DONE, §22.** `/projects` and `/projects/{id}` serve live; the rest of M1 remains.
2. ~~The `mail` health defect above~~ → **DONE, §21.**
3. Frontend **Phase 4 (Vitest + RTL)** is unchanged and still unblocked; `AcceptInvitationPage`'s
   byte-counted password rule and the `SessionGate`/restore matrix are now on its target list, both
   pure and both load-bearing.

---

## 21. Backend — the default install stops reporting itself DOWN (2026-07-31)

Run through the **`be-java`** skill (agent definition + `ep-systematic-debugging`), **Mode A — fix**.
Closes §20's *"found, not fixed"*. **One line of production configuration; no Java changed.**

### The chain, verified against Boot's own source rather than reasoned about

1. `compose.yaml` writes `SPRING_MAIL_HOST: ${SMTP_HOST:-}` and `.env.example` ships `SMTP_HOST=`
   empty, so the container receives the property **present and empty**.
2. Boot's `MailSenderAutoConfiguration` gates on `@ConditionalOnProperty("spring.mail.host")`, which
   matches a property that is *present* — **an empty string is present** — so a `JavaMailSender` is
   built with no host.
3. `MailHealthContributorAutoConfiguration` is `@ConditionalOnBean(JavaMailSenderImpl.class)`, so the
   indicator registers because that bean exists.
4. `MailHealthIndicator.doHealthCheck` calls `testConnection()`; an empty host resolves to localhost,
   so the component is DOWN with `"location": ":1025"` — the exact string §20 captured live.
5. The aggregate takes the **worst** of its components → `/actuator/health` = **503**, while both
   probe groups stay UP because they contain only their own indicators. That is why the container
   healthcheck (`/actuator/health/readiness`) was green the whole time and nobody noticed.

### The fix, and why it is a policy decision rather than a workaround

`management.health.mail.enabled: false` in `application.yml`, with the reasoning in the file. Three
arguments, in order of weight:

1. **A mail outage is deliberately survivable here.** `SmtpInvitationMailer` swallows send failures and
   `InvitationMailTest` pins that ("a dead relay does not undo the company it was announcing", §18's
   mutation survivor). An indicator that turns a *tolerated* failure into an aggregate DOWN
   contradicts a decision the suite already enforces.
2. **DOWN is an instruction, not a note** — an orchestrator restarts or depools the container. Neither
   action can reach an SMTP relay, so the signal is **wrong**, not merely noisy.
3. **`testConnection()` runs on every poll**, opening a real SMTP connection each time. At the image's
   30s `HEALTHCHECK` interval that is ~2,900 connections a day to a third-party relay — a shape relays
   rate-limit. This argument holds *even when a relay is configured*, which is why the switch is
   unconditional rather than conditioned on the host being set.

**Why the application and not `compose.yaml`:** compose interpolation cannot omit a key conditionally,
and any other templated deployment produces the same empty value. The app is the only place that
covers every caller. The rejected alternative — an `EnvironmentPostProcessor` stripping a blank
`spring.mail.host` before auto-configuration sees it — fixes the root cause properly but is a lot of
clever machinery for one property, and it would silently change behaviour for anything else reading it.

### What changed

| Path | Change |
|---|---|
| `backend/src/main/resources/application.yml` | `management.health.mail.enabled: false`, plus the three arguments above and the note that the unused empty-host `JavaMailSender` is **known, not missed**. |
| `backend/src/test/java/com/tcms/ops/HealthReportTest.java` | **New, 3 assertions.** The aggregate is 200/UP · no `mailHealthContributor` bean · **a canary** asserting an empty host *still* builds a `JavaMailSender`, so if a future Boot version rejects a blank value the test fails and says the config line is now dead. Also re-pins §18's fix: the mailer must still be the unconfigured fallback. |

### Why 177 green tests could not see it

`EndpointAccessTest` already asserted `/actuator/health` answers **200** — and passed, because the
default test context sets **no** `spring.mail.host` at all, so no indicator is ever registered.
`InvitationMailTest` sets a real host pointing at GreenMail, so its indicator is genuinely UP.
**Neither context used compose's spelling of "no relay" — the property present and empty — and that is
the only spelling that fails.** The gap was a missing *configuration*, not a missing assertion, which
is a fourth species alongside §19's "harness weaker than the deployment": the harness was not weaker
here, it was configured differently from every real install.

### Gate evidence — run, not just built

`./gradlew build` clean, **180 tests** (177 + 3). The two new assertions were watched **fail first**
against the unfixed config, for the right reason: `expected: 200 but was: 503`, and the
`mailHealthContributor` bean present.

Then proved against the live stack with `.env`'s `SMTP_HOST=` empty — the defect's own condition:

| Assertion | Result |
|---|---|
| `GET /actuator/health` | **HTTP 200**, `{"status":"UP","groups":["liveness","readiness"]}` |
| Components with `SHOW_DETAILS=always` (§20's own repro command) | `db` · `diskSpace` · `livenessState` · `ping` · `readinessState` · `ssl` — all UP, **no `mail` component at all** |
| Container healthcheck | `Up (healthy)` |
| `provision-company` on the live stack | the **"No SMTP relay is configured"** WARN still fires, the link still prints, no stack trace — §18 defect 3's fix survives |

### Defect class swept — instance-only

The class is *"an empty string is present, so a presence gate reads unconfigured as configured."*
Both sides checked mechanically rather than by reading: **`SPRING_MAIL_HOST` is the only variable in
`compose.yaml` with an empty default (`:-}`)**, the only other property gate in main code is
`MailConfiguration`'s already-hardened `@ConditionalOnExpression(… isBlank())`, and there are **no
custom health indicators**. Nothing else to fix.

### Deliberately not done

- **The unused `JavaMailSender` is still in the context** when the host is empty, pointed at nowhere.
  Nothing autowires it (`MailConfiguration` selects the fallback mailer on the same blank check), so it
  is inert — but it is a live landmine for the first code that injects `JavaMailSender` without
  checking. Recorded in `application.yml` and covered by the canary assertion rather than removed,
  because removing it means excluding a Boot auto-configuration conditionally.
- **`compose.yaml` untouched.** Changing `${SMTP_HOST:-}` cannot express "omit the key", and the app
  now tolerates the empty value from any source.
- **No mail monitoring replacement.** Relay reachability is an operational concern with an operational
  home; it is not a readiness gate for an application that works without it.

---

## 22. Backend M1, slice 1 — the project list and detail, and the second gate over HTTP (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**. Closes
§20's next-step item 1 *for its first slice*: **live mode now reaches past the login screen.**

### What this is, and what M1 still is

M1's contract is 30+ endpoints. This slice is **`GET /projects` + `GET /projects/{projectId}`** — the
two the SPA calls immediately after sign-in, and therefore the whole difference between "live mode
stops at login" and "live mode renders the product". Everything else in M1 (classification, statuses,
suites, cases, plans, runs, docs, artifacts, dashboard, reports) is untouched.

### The headline: both access gates are now observable over HTTP

`PermissionEvaluatorTest` has proved the second gate as a *permission* question since M0. This is the
first time it is proved as an *answer to a request* — a different claim, and the gap between them is
where the defect would have lived: the evaluator can be perfectly correct while the endpoint above it
lists every project in the company because nobody wired the filter in. **That failure would have
passed all 180 existing tests.**

| Path | Purpose |
|---|---|
| `project/ProjectController.java` | The two endpoints. Caller from the token, company from the context, neither ever a parameter. |
| `project/ProjectService.java` | The grant gate applied to every row the company filter admits; assembles the view. |
| `project/ProjectView.java` | The contract's `Project` shape. A DTO **so `companyId` cannot leave the building** — echoed back, it invites the next author to accept it on the way in. |
| `project/ProjectAccess.java` | **The port** (below). |
| `identity/IdentityProjectAccess.java` | The adapter. Delegates every authorization decision to `PermissionEvaluator`. |
| `project/ProjectRepository` · `identity/RoleAssignmentRepository` | Two grouped projections — case counts, project-scope role names. |
| `project/ProjectEndpointTest.java` | **New, 24 assertions.** |

### The decision that shaped the slice: a port, not a wider dependency

Listing projects needs two things `project` cannot compute — does the caller hold a grant, and what is
their role called. Both live in `identity`. The obvious move is to import `PermissionEvaluator`, and
**Spring Modulith failed the build on it, correctly**: `identity` already depends on `project`,
because the evaluator resolves a project through `ProjectRepository` to check it belongs to the active
company. Both modules' `package-info` files already said the edge is one-way.

So the dependency is **inverted rather than reversed**: `project` declares `ProjectAccess`, and
`identity` — which may already see `project` — implements it. Nothing was added to
`project`'s `allowedDependencies`. Both `package-info` files now say so explicitly, including the
instruction **not** to "fix" a future Modulith failure here by widening the declaration.

> **This is the good case.** The architecture test refused a change that looked obviously right, and
> the refusal produced a better design than the one being attempted. A module rule that never blocks
> anything is not being enforced.

### Two contract fields that needed a decision, and where the decision came from

| Field | Resolution |
|---|---|
| **`myRole`** | The contract already settled it: *"the caller's role on this project, or null when they hold no project grant."* So a **Company Admin gets `null`** — they reach every project through a grant carrying no project id, and labelling each project "Company Admin" would state a fact about the *company*. Where a caller holds two project-scope grants (directly and via a team), **"strongest" is derived from the role's permission count**, read from the same `role_permission` table authorization reads — never a hard-coded ranking of names, which is §6 lesson 10 in a new costume. |
| **`caseCount`** | Live cases, **excluding soft-deleted** (`test_case.deleted_at`), or the number contradicts the case list on the next screen. Read with a native `GROUP BY` **from the `project` module**, because a Java edge `project → repository` is forbidden and SQL is where the coupling can live — recorded as the deliberate trade it is, with §3 lesson 4's event-updated read model named as the long-run answer *once anything writes a case*. Nothing does yet, so a live count is both correct and cheaper than a counter that can drift. |

### Why visibility is N evaluations rather than one clever join

A single native query joining `role_assignment`, `team_membership` and `role_permission` would return
visible project ids in one round trip. It is the wrong shape: a **second authorization-resolution path
beside `PermissionEvaluator`** — the bug class §6b names, and the same reason §18 kept grant claims
out of the token. The cost is accepted with its ceiling stated: a company holds *a handful of whole
products* (§1), and the two things that would otherwise be N+1 *per project* are each one grouped
query, so what remains is N evaluations, not 3N queries. The evaluator's Caffeine cache improves this
for free when it lands, with no call site changed.

### Mutation-verified — five mutations, and the two survivors were the interesting part

| Mutation | Result |
|---|---|
| Grant gate removed from `listVisible` | **Killed**, 3 tests |
| `deleted_at IS NULL` removed from the count | **Killed**, 3 tests |
| `company_id = :companyId` removed from the count | **Survived, benignly.** RLS independently scopes `test_case`. The same documented survivor `RoleAssignmentRepository` already carries — defence in depth working, not dead code, and load-bearing the moment a deployment takes V1 up on its offer to disable RLS. |
| `project_id IS NOT NULL` removed from `findProjectRoleNames` | **Survived for a bad reason → gap closed.** The company-scope row comes back with a null project id, lands in the result map under a **null key** no project id matches, and vanishes. `HashMap` tolerating a null key was doing the predicate's job, so the test could not tell *excluded by the query* from *silently dropped by the consumer*. Assertion moved to where the predicate lives; the mutation now dies. |
| Strongest-wins comparator inverted | **Killed** — by a case that had **no coverage at all** until this run. |

The strongest-wins fixture needed a **second team**: adding a Test Lead grant to the existing QA team
would also have reached `TEAM_MEMBER` and quietly broken "a team grant reveals only the team's
project". Two fixtures sharing a team is how one test's setup becomes another test's failure.

### Gate evidence — run, not just built

`./gradlew build` clean, **204 tests** (180 + 24). `npm run build` + `npm run lint` clean, 0 warnings,
**entry chunk unchanged at 124 kB gzip**.

Then **driven in a real browser in live mode** against `docker compose up` + a provisioned `westwind`,
with two projects and 6 cases (2 soft-deleted) seeded directly so the list had something real to say:

| Assertion | Result |
|---|---|
| `GET /projects` ordering | by name — Mobile App before Online Banking |
| `caseCount` vs psql ground truth | **4** for OLB (6 rows, 2 soft-deleted) and **0** for MOB |
| `companyId` in any response | absent |
| Unknown project id | **404, 0 bytes** |
| The SPA after sign-in | **the project picker rendered both projects from Postgres**, with the membership display name in the rail |
| Reload | session survived — `SessionGate` + restore |
| A project-scope grant added live | `Your role: Test Lead` appears on OLB and **not** on MOB, same user, same page — the company-scope grant correctly contributes no label |

### One defect the browser found and the gates could not — §6 lesson 5, again

**`Your role:` rendered as a dangling label with nothing after it.** `myRole` is correctly `null` for a
Company Admin, and `ProjectsPage`/`DashboardPage` printed the caption unconditionally. **Invisible in
mock mode**, where every fixture project carries a role — and visible on the very first real Company
Admin session, which is exactly the shape of defect live mode exists to surface.

Fixed by **omitting the line, not filling it**: the API deliberately does not say what their company
role is at this endpoint, and inventing "Company Admin" would be the UI making a claim the response
never made. Both branches then verified live on one page.

*Said out loud per `CLAUDE.md`: the two-line null guard did not re-enter the `fe-react` phase pipeline.
It introduces no pattern a skill owns — it is a conditional render in existing markup.*

### Deliberately not done

- **The rest of M1.** `/projects/{id}/classification` and `/statuses` are the natural next slice: both
  vocabularies are already seeded per-company by `provision-company`, so they are mostly projection.
- **No pagination on `/projects`.** The contract declares a bare array and a company holds a handful of
  products. It becomes wrong if that assumption does, and the contract would have to change first.
- **No project create/rename/archive.** `project:create` exists in the catalogue and the entity already
  has `rename`/`describe`/`archive`; no endpoint calls them. Reads first, so the visibility model is
  settled before anything writes through it.
- **`ProjectView` is assembled per request, uncached.** Same reasoning as the evaluator's own deferred
  cache: a wrongly-keyed cache here is a cross-company leak.
- **No `/me/permissions` consumer on the frontend** (still §20). `lib/permissions.ts` reads the fixture
  role catalogue, which stays honest only in mock mode.

### Next

1. **M1 slice 2** — `/projects/{id}/classification` + `/statuses`, then the suite tree. Each new
   endpoint now inherits the gate: read through `ProjectService`-shaped visibility, or re-answer "may
   they?" and diverge from the evaluator.
2. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
3. The evaluator's cache, the `pv` cache, the `OWN` qualifier, an RLS-disabled lane for §13's
   surviving mutation.

---

## 23. Backend M1, slice 2 — the two project vocabularies, and the gate that is now shared (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`, then `code-review`), **Mode B
— extend**. Closes §22's next-step item 1: **`GET /projects/{id}/classification` and
`/projects/{id}/statuses`**.

### The headline: the second gate stopped being something each endpoint remembers

§22 wired both access gates into `GET /projects/{id}`. This slice's real deliverable is that the
gate became **one private method** — `ProjectService.visibleProject` behind the public
`readVisible(userId, projectId, read)` — that every project-scoped read is built on. A new endpoint
gets both gates by construction rather than by discipline.

The property is measured, not asserted: deleting the grant check from that one method now fails
**five tests across two endpoint classes**, not just the class whose author removed it. That is the
whole argument for a higher-order method over a copied two-line filter.

| Path | Purpose |
|---|---|
| `project/ClassificationDimension · ClassificationValue · CaseStatus` | The three entities. First `ltree` mapping in the codebase (below). |
| `project/…Repository` ×3 | One purposeful query each. All three `@Transactional(readOnly = true)` per §15. |
| `project/ClassificationDimensionView · ClassificationValueView · CaseStatusView` | The contract's shapes. `companyId` **and** `projectId` withheld. |
| `project/Cardinality · StatusKind` | The two closed enums, `@Enumerated(STRING)`. |
| `project/ProjectConfigurationService` | Borrows the gate; assembles dimensions + values in two queries. |
| `project/ProjectService` | `readVisible` extracted; `findVisible` now reads through it. |
| `project/ProjectController` | The two endpoints, on the existing controller — one contract tag, one gate. |
| `project/ProjectConfigurationEndpointTest` | **New, 24 tests.** |

No module edge was added: classification and statuses are ERD §3, i.e. the `project` module
already. No migration, no `openapi.yaml` change — the contract was already right and was implemented
as written.

### The defect this slice was most likely to ship, and why it would have looked fine

**`classification_dimension.project_id` is nullable, and NULL means company-level.** Test Type, Test
Level and Execution Phase are seeded that way; only Feature is per-project. A query filtering
`project_id = :projectId` therefore returns **one** dimension — the project's empty Feature tree —
answers 200, and leaves the case editor with a single empty picker. Nothing fails.

So the test asserts the four keys **by name**, not by count, and
`ClassificationDimensionRepository.findForProject` carries the reasoning inline. Mutating the
predicate to the equality form kills 5 tests.

There is a sharper corollary worth keeping: `project_id IS NULL` matches rows in **every** company,
so for exactly the rows the SPA needs most the project predicate scopes *nothing* — `company_id` is
the only thing between two clients' vocabularies. Both companies in the fixture are seeded by the
same V2 function, so every key and every value name collides and only ids can prove the scoping.

### `ltree` is the second extension type that would have failed at boot

`classification_value.path` is `ltree`, which reports as `Types#OTHER` while a `String` field maps
to `varchar` — the exact trap `AppUser.email` documents for `citext`. `columnDefinition = "ltree"`
is what satisfies Hibernate's fall back to comparing type *names*.

**Verified by removing it:** the application context fails to build at all
(`SchemaManagementException` from `AbstractSchemaValidator`), taking all 24 tests with it. Good
failure — at boot, not at the first query. `suite.path` is the next `ltree` column and will need the
same annotation.

### Mutation-verified — seven mutations, no survivors

| Mutation | Result |
|---|---|
| Dimension predicate reduced to `project_id = :projectId` | **Killed**, 5 tests |
| `deletedAt IS NULL` removed from values | **Killed** — a retired value came back as pickable |
| Values ordered by name instead of position | **Killed** — Accessibility first reads plausible |
| Grant gate removed from the shared `visibleProject` | **Killed, 5 tests across BOTH endpoint classes** |
| Statuses read company-wide instead of per project | **Killed**, 2 tests |
| Empty dimension given `null` instead of `[]` | **Killed** — `values` is `required`, so the SPA cannot null-check it |
| `columnDefinition = "ltree"` removed | **Killed** — 24/24, at context startup |

Unlike §22 there is no documented survivor. The seven were chosen to be the plausible wrong versions
of this slice, not arbitrary edits.

### One finding from the `code-review` phase, fixed rather than argued with

`findVisible` initially called the **public `@Transactional` `readVisible` on `this`** — a
self-invocation, which the skill's checklist rates a blocker. It was harmless here (the outer method
had already opened the transaction), but "harmless self-invocation" is a claim a reader has to verify
every time. Restructured so the gate is a **private unannotated method** both public entry points
call: no self-invocation, and still exactly one implementation of the gate. The M4 mutation was
re-run afterwards and still kills the same five tests, so the refactor kept the property it was
worth having.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **228 tests** (204 + 24). Frontend untouched, so no frontend
gate: `openapi.yaml` did not change and `schema.d.ts` already carried both shapes.

Then **driven live** against `docker compose up` + a provisioned `slice2` company, one project seeded
by `tcms_seed_project_defaults`, with a hand-built two-level Feature tree and a retired node:

| Assertion | Result |
|---|---|
| `/classification` dimension count vs psql | **4** — `test_type`, `test_level`, `execution_phase`, `feature`, in seed order |
| Locked / hierarchical / cardinality | company-level three locked + SINGLE/SINGLE/MULTI; `feature` unlocked, hierarchical, MULTI |
| The Feature tree | `Refunds` carries `parentId` = Payments and `path` = `Payments.Refunds` |
| Retired value | **absent** — 2 live of 3 rows, matching psql |
| `/statuses` vs psql | **6**, position order, colours matching the frontend design tokens |
| `Retest` | `isFinal=false`, `countsAsPass=false` — the two booleans are independent |
| `companyId` / `projectId` in any response | absent at every level |
| Unknown project id, both endpoints | **404, 0 bytes** |
| Anonymous, both endpoints | **401** |
| **The caller's only grant deleted, then re-requested** | `/projects` → `[]`, both endpoints → **404, 0 bytes**, for a project that genuinely exists in their own company with populated vocabularies. Persona 3, live. |
| Grant restored (re-running `provision-company`) | both endpoints → **200** |

No SPA screen was driven, and that is not an omission: the only consumers are `CaseListPage` and
`ExecutionPage`, which also need `/suites` and `/cases`. Verification is therefore at the HTTP layer
against psql ground truth — stated plainly rather than dressed up as a browser pass.

### Two things found and deliberately not fixed

1. **`case_status.color` is nullable in `V1` and `required` in `openapi.yaml`.** The seed sets all
   six and nothing else writes a status, so the invariant holds *by luck*. Pinned by a test
   (`everyStatusHasAColour`) and documented on `CaseStatusView.color` rather than papered over in the
   view — inventing a fallback colour would be the UI making a claim the data never made. The real
   close is `ALTER COLUMN color SET NOT NULL` plus a colour validator, and both belong with the
   status-editing endpoint that first makes a null reachable.
2. **`provision-company` prints `existed company membership (invited)` for a membership that is
   ACTIVE.** The label is a static description of the step
   (`ProvisionCompanyCommand.java:190`), so on a re-run it states a status that is no longer true.
   Cosmetic, pre-existing, operator-facing only — noted rather than scope-crept into this slice.

### Deliberately not done

- **No writes.** No vocabulary editor, no status editor; the entities carry no factory methods,
  because a factory with no caller is where the `company_id` rule gets forgotten. Add one with its
  endpoint.
- **No `controlled` on the wire.** Mapped, unrendered, no consumer.
- **Sibling ordering inside a Feature tree is unowned.** `position` is per dimension, and nothing
  allocates positions for tree nodes yet — the writer's problem when a writer exists.
- **No caching.** Same reasoning as the evaluator's own deferred cache: a wrongly-keyed cache here
  is a cross-company leak. Vocabularies are the most cacheable thing in the product and will be the
  most tempting; key on `(companyId, projectId)` or not at all.

### Next

1. **M1 slice 3 — `/projects/{id}/suites`**, then `/cases`. Both go through
   `ProjectService.readVisible`; the suite tree brings the second `ltree` column and the first
   endpoint whose response the SPA renders as a *screen* rather than as reference data, so live mode
   reaches the case list.
2. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
3. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.

---

## 24. Backend M1, slice 3 — the suite tree, and the `repository` module's first endpoint (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`, then `code-review`), **Mode B
— extend**. Closes §23's next-step item 1's first half: **`GET /projects/{projectId}/suites`**.

### The headline: the shared gate held from another module

§23 made `ProjectService.readVisible` the one implementation of "may this caller see this project".
This slice is the first endpoint **outside the `project` module** built on it, which is the case it
was extracted for — and it needed no new module edge to work. `repository` declares
`allowedDependencies = {"tenancy", "project"}`, hands a user id to `project`, and is handed back
either the project or nothing. It never asks `identity` anything.

Deleting the grant check from `visibleProject` now fails **eight tests across three endpoint
classes**. That number is the property, and it went up without anyone maintaining a list.

| Path | Purpose |
|---|---|
| `repository/package-info.java` | **The fifth module.** Why the name means *the test repository* and not "the persistence layer". |
| `repository/Suite · Condition · SuiteCondition` | Three entities. Second `ltree` column in the codebase, first `List<String>` over `jsonb`. |
| `repository/…Repository` ×3 | One purposeful query each, all `@Transactional(readOnly = true)` per §15. |
| `repository/SuiteView · SuiteConditionView · LifecycleStatus` | The contract's shapes. `companyId` **and** `projectId` withheld. |
| `repository/SuiteService` | Borrows the gate; three queries, grouped in memory. |
| `repository/SuiteController` | The endpoint. Separate controller — the path expresses scope, not ownership. |
| `repository/SuiteEndpointTest` | **New, 25 tests.** |

No migration and no `openapi.yaml` change: the contract was already right and was implemented as
written. The frontend is untouched, so no frontend gate — `schema.d.ts` already carried `Suite`.

### The defect this slice was most likely to ship, and why it would have looked fine

**`caseCount` is the suite's own live cases, not its subtree's.** The rollup is done client-side —
`SuiteTree.tsx` adds each node's count to its children's — so a subtree count here is counted again
at every ancestor and a two-level tree reports roughly double. Both numbers are plausible, the
endpoint answers 200, and nothing fails. The SPA's own fixture says it out loud (a parent folder
with children carries `caseCount: 0`) and that is where the semantics were read from rather than
guessed.

### Two exclusions that are decisions, not oversights

Both are recorded on `SuiteConditionView` and both are pinned by a test that says what a future
failure means:

1. **An inline condition attachment has no title, and `title` is `required`.** `suite_condition`
   stores either a `condition_id` or an `inline_body` and nothing else, and `CaseConditionInput`
   has no field a writer could send a title in — so the inline form is a state the API cannot
   describe. Inventing one (the first body line, "Inline condition") would be the response making
   a claim the data never made, which is exactly what §22's dangling `Your role:` and §23's
   fallback status colour were fixed by *not* doing. Excluded at the query; nothing in the product
   can create such a row today.
2. **`kind` (PRE/POST) has nowhere to go.** The column exists and the distinction is real, but
   `CaseConditionRef` carries no field for it. Adding one is a contract change with no consumer on
   the other side — `Suite.conditions` has **zero** readers in the SPA today.

A third case drops out quietly and is named rather than left to be discovered: an attachment whose
library condition has been **retired** resolves to nothing and is omitted, because serving it would
offer a withdrawn precondition as current.

### Mutation-verified — twelve mutations, two survivors and both instructive

| Mutation | Result |
|---|---|
| `caseCount` rolled up over the subtree | **Killed** |
| `deletedAt IS NULL` removed from the suite query | **Killed**, 2 tests |
| `deleted_at IS NULL` removed from the case count | **Killed**, 2 tests |
| Ordered by `position` instead of `path` | **Survived → fixture fixed → killed** (below) |
| Grant gate replaced by company-only visibility | **Killed**, 3 tests |
| Inline attachments no longer excluded | **Survived → test moved → killed** (below) |
| Retired library entries no longer excluded | **Killed** |
| Empty `conditions` become `null` instead of `[]` | **Killed**, 2 tests |
| Absent `caseCount` becomes `null` instead of `0` | **Killed**, 19 tests |
| `columnDefinition = "ltree"` removed | **Killed** — 25/25, at context startup |
| `project_id` removed from the case count | **Survived, structurally** |
| `company_id` removed from the case count | **Survived, structurally** |

**The ordering survivor was a fixture defect.** Every suite carried `position = 0`, so an
`ORDER BY position` query returned parents first by luck and the assertion could not fail. Fixed by
making sibling positions *descend* as the tree deepens (Payments 5, Refunds 3, Partial 1) — so the
wrong query genuinely returns a child before its parent. A fixture that cannot tell the right query
from the wrong one proves nothing.

**The inline survivor was §22's survivor in a new costume.** An inline row has a null `conditionId`,
the service resolves it with `library.get(null)`, and `HashMap` answers null — so the row dropped
out whether or not the query excluded it, and every endpoint assertion stayed green. `HashMap`
tolerating a null key was doing the predicate's job again. Fixed by asserting **at the repository**,
where the decision actually lives.

**The two tenancy survivors are structural and are documented as such** on
`SuiteRepository.countLiveCasesBySuite`. The result is keyed on `suite_id` — a UUID primary key —
so a case in another project or company hangs off a suite id that is never looked up, and no
fixture can make the difference observable. They stay for reasons that are not "in case the join
key changes": `company_id` leads `test_case_suite_idx` and is what makes the query index-covered,
and `project_id` bounds the aggregate instead of grouping the whole company and discarding most.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **253 tests** (228 + 25).

Then **driven live** against `docker compose up` + a provisioned `slice3` company, with a
three-level tree, a soft-deleted folder, a soft-deleted case and all three attachment shapes seeded
directly:

| Assertion | Result |
|---|---|
| Ordering, against positions 5/3/1 | **Payments → Refunds → Partial refunds** — a position ordering would have inverted it |
| `caseCount` vs psql ground truth | **0 / 2 / 1**; psql agrees, and the deleted case is excluded |
| The soft-deleted folder | **absent** |
| Live reference attachment | resolved with title and two-element body, `inline`/`inherited` both false |
| Inline attachment, retired-condition attachment | **both absent**, `conditions: []` |
| Another company's populated suite | absent from the response — a live cross-company check |
| `companyId` / `projectId` at any level | absent |
| Unknown project id | **404, 0 bytes** · anonymous → **401** |
| **The caller's only grant deleted, then re-requested** | `/projects` → `[]`, `/suites` → **404, 0 bytes**, for a populated project in their own company. Persona 3, live. |
| Grant restored (re-running `provision-company`) | **200** |

No SPA screen was driven, and that is not an omission: the only consumer is `CaseListPage`, which
also needs `/cases`. Verification is therefore at the HTTP layer against psql ground truth — stated
plainly rather than dressed up as a browser pass.

### One finding from the `code-review` phase

`ProjectEndpointTest`'s fixture comment claimed "the `repository` module does not exist yet", which
this slice made false. Corrected, and pointed at `SuiteEndpointTest`'s fixture as the stronger
arrangement to copy: it writes through the **app role** under RLS rather than as the superuser, so a
fixture that could not exist under policy cannot silently pass.

Writing that test also re-taught §15's lesson from the other side: `CompanyContext.open` must wrap
the transaction, not sit inside it. The transaction manager reads the context in `doBegin`, so a
context opened after the transaction started arrives too late and RLS answers **zero rows** —
fail-closed and silent, and it read like a broken query.

### Deliberately not done

- **No writes.** No suite create/rename/move. Reparenting rewrites every descendant's `path` in one
  statement, which is why `Suite` has no setters and why the move endpoint is a slice of its own.
- **`GET /suites/{suiteId}` and `/history`** — the same `Suite` shape and a `SuiteRunSummary`
  respectively. The second needs `execution`, which does not exist.
- **No `TestCase` entity.** The counts are native SQL against `test_case`, which is this module's
  own table. `/cases` is the next slice and will map it.
- **No caching.** Same reasoning as the evaluator's own deferred cache: a wrongly-keyed cache here
  is a cross-company leak.
- **`Suite.conditions` has no SPA consumer** — implemented because the contract marks it required
  and hard-coding `[]` is a lie the moment anything writes a suite condition.

### Next

1. **M1 slice 4 — `/projects/{id}/cases`**, the list the SPA's biggest screen reads. It brings the
   first `TestCase` entity, the first server-side filtering and paging, and it is what finally makes
   live mode render a *screen* rather than reference data.
2. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
3. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.

---

## 25. Backend M1, slice 4 — the case list, and the screen that finally renders live (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**. Closes
§24's next-step item 1: **`GET /projects/{projectId}/cases`** — filtering, sorting, paging and
facets, the first `TestCase` entity, and the first endpoint whose response the SPA renders as a
*working screen* rather than as reference data.

### The headline: the SPA's biggest screen runs on the real API

`CaseListPage` needs three endpoints — the suite tree (§24), the classification vocabularies (§23)
and this one. With the third in place the screen renders live end to end: the tree with its counts,
the grid, the facet chips with their counts, URL-backed filters, and paging. That was the point of
sequencing these three slices in this order.

| Path | Purpose |
|---|---|
| `repository/TestCase` | **The first case entity.** Plain UUID FKs, no associations — see its header. |
| `repository/CaseTag · CaseTagId · CaseClassification · CaseClassificationId` | The two join tables, `@IdClass` per the `CompanyMembershipId` precedent. |
| `repository/CaseHealth · CaseHealthRepository` | The §6o read model. Mapped, queried, and **empty** — nothing computes it yet. |
| `repository/Priority · AutomationStatus · StepFormat · HealthSignal` | The contract's enums. `HealthSignal` carries its `CaseHealth` attribute name so filter, facet and row all resolve one source. |
| `repository/CaseQuery · CaseScope` | What was asked, and what that turned out to mean. Kept apart on purpose. |
| `repository/CaseSpecifications · CaseSort · CaseSearch` | One predicate, the `ORDER BY` allow-list, and the five queries that share them. |
| `repository/CaseService · CaseController` | The gate, the query budget, and the edge. |
| `repository/CaseOwners` | The one class that may touch `identity`. |
| `repository/*View` | `TestCaseSummaryView`, `UserRefView`, `FacetView`, `CasePageView`. |
| `repository/CaseListEndpointTest` | **New, 53 tests.** |

### The module now depends on `identity`, and the distinction is the point

`repository`'s package comment used to say "not `identity`", and the reason it gave was
authorization. That reason still holds and nothing changed it: the gate is still
`ProjectService.readVisible`, borrowed from `project`, and no class in the package asks what a
caller holds. What the case list added is a *different* question with a different failure mode —
**what is this person called** — which every grid showing a person needs, which §6u says must
resolve through the active company's membership, and which creates no cycle (`identity` depends on
`tenancy` and `project`, never on this module).

The `ProjectAccess` port-and-adapter shape was deliberately **not** repeated. That inversion exists
because `project` *cannot* depend on `identity`; this module can, so a port would mean making
`identity` depend on the test repository to hand back a display name — an arrow pointing the wrong
way to avoid one pointing the right way. `CaseOwners` is the only class permitted the edge, and the
package comment says so.

### One contract change: `TestCaseSummary.owner` is nullable

`test_case.owner_id` has always permitted null while the contract marked `owner` required. Resolved
toward the schema: `openapi.yaml` now carries `allOf` + `nullable: true`, types were regenerated,
and `CaseGrid` / `CaseDetailPage` render "Unassigned". The two alternatives were worse — hiding the
row loses a case from its own project's list, and inventing an owner puts a person on the wire whose
id nothing can resolve. `db.ts` passes `?? undefined` for the derived work items' assignee, which is
optional rather than nullable in the contract.

### Three decisions that are decisions, not omissions — each pinned by a test that names its own successor

1. **`releaseVersion` matches nothing.** The filter is defined over executions and `execution` does
   not exist, so zero cases have been executed in any release. Implemented as an explicit
   never-matching predicate rather than an ignored parameter: an ignored filter returns the entire
   project and labels it "executed in 4.3.0", which is worse than an error because it is
   indistinguishable from a filter that found everything.
2. **`healthSignal` matches nothing and the health facet is empty.** Same shape: `case_health` is a
   read model nothing writes yet, so no case raises a signal. Real predicate, real query, honest
   zero — it starts working when the computation lands, with no change here.
3. **`lastResult` is absent rather than null.** Not marked required, and the SPA already renders a
   missing `lastResult` as "Never run", which is exactly true.

### Enums sort by meaning, and the mock is the one that is wrong

`priority` stored as text sorts CRITICAL, HIGH, **LOW, MEDIUM**. Every value is in a plausible
place, the column looks sorted, nothing fails. `CaseSort.byDeclaredOrder` builds a CASE expression
from the enum's own `values()`, so the order is the declaration order and a new member is ordered
correctly the moment it is declared. `lifecycleStatus` and `automationStatus` have the same problem
and the same fix. **The SPA's mock sorts all three alphabetically** (`localeCompare` in
`handlers.ts`) — recorded rather than matched.

### The defect running the app found, and the gates could not

**The project picker said "4 cases" over a list showing 3.** `GET /projects` counts live cases;
this endpoint excludes cases whose *suite* is soft-deleted (soft-deleting a folder does not touch
the cases under it, and a row whose folder is gone names a place the user cannot navigate to — which
is what `SuiteRepository` already documented as the intent). Two queries, two screens, both numbers
plausible, `./gradlew build` green and 306 tests passing.

Fixed in `ProjectRepository.countLiveCasesByProject` by joining to a live suite, and pinned by a
test that asserts the **equality of the two endpoints** rather than two expected numbers — expected
numbers drift apart silently; their agreement is the actual requirement. Same family as the two
contradictory percentages on the plan-statistics screen (§5), and the same reason the working
agreement says the gates prove it compiles, not that it works.

### Mutation-verified — twenty mutations, three survivors, two of them weak tests

| Mutation | Result |
|---|---|
| Grant gate replaced by company-only visibility | **Killed** |
| `deletedAt IS NULL` removed from the case query | **Killed** |
| Live-suite bound dropped (deleted folders' cases return) | **Killed** |
| Priority sorted alphabetically instead of by declared order | **Killed** |
| Ordered by the text key instead of `keySeq` | **Killed** |
| LIKE metacharacters no longer escaped | **Killed** |
| `TC-n` short-circuit removed — searched as text | **Killed** |
| `description` dropped from free-text search | **Killed** |
| Classification descendants no longer included | **Killed** |
| Retired classification values become matchable | **Killed** |
| `releaseVersion` / `healthSignal` silently ignored | **Killed**, both |
| `total` computed from the page | **Killed** |
| Owner name read from the account instead of the membership | **Killed** |
| Zero-count facet values no longer dropped | **Killed** |
| Unowned case given an invented owner | **Killed** |
| Empty classification set widened to "no filter" | **Killed** |
| `keySeq` tie-breaker dropped | **Survived → fixture fixed → killed** |
| Facet query drops the request's enum filters | **Survived → test added → killed** |
| `project_id` predicate removed | **Survived, structurally** |

**The tie-breaker survivor was a fixture defect, and it is §24's ordering survivor one level up.**
Cases were inserted in key order, so Postgres returned a small table in physical order and a query
with no tie-breaker came back correctly *by luck*. Fixed by inserting rows that tie on priority in
the **opposite** order to their `keySeq` — TC-10 before TC-2, TC-5 before TC-3. A fixture that
cannot distinguish the right query from the wrong one proves nothing.

**The facet survivor was a genuinely missing test.** `suiteId` is resolved into the *scope* before
the predicate is built, so the one facet test that existed would still have passed had the facet
query dropped every filter carried on the *request*. `countsFollowAnEnumFilter` closes it.

**The `project_id` survivor is structural** and documented as such in `CaseSpecifications`: the
suite scope is already the project's own live tree and a suite id is a UUID primary key, so a case
in another project hangs off a suite that is never in the set. It stays because it is what makes the
query reachable through `test_case_lifecycle_idx`, and because three predicates stating the same
intent is what makes a future mismatch return nothing rather than something.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **307 tests** (253 + 53 + 1 on `ProjectEndpointTest`'s
neighbour). `npm run build` clean, `npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, two provisioned companies, and a hostile dataset
seeded through the **app role** under RLS:

| Assertion | Result |
|---|---|
| Default order | **TC-1, TC-2, TC-10** — a text ordering would have inverted the last two |
| `sort=priority,asc` | **CRITICAL, MEDIUM, LOW** — alphabetical would have put LOW second |
| Soft-deleted case, live case in a soft-deleted suite | **both absent**; psql confirms 5 rows in the table and 3 served |
| `q=TC-1` | **TC-1 only**, never TC-10 |
| `q=refund` | title **and** description matches |
| `q=100%` | **one row** — unescaped it would have matched everything |
| `suiteId=Payments` (a parent) | its subtree, 3 rows |
| `featureId=Payments` (a parent) | **2 rows**, descendants included |
| `releaseVersion=4.3.0` · `healthSignal=NEVER_EXECUTED` | **0 rows**, not the whole project |
| `priority=NOPE` | **400**, not a dropped filter |
| Another client's project | **404, 0 bytes**; their case title appears nowhere in this client's list |
| **The caller's only grant deleted, then re-requested** | `/projects` → `[]`, `/cases` → **404, 0 bytes**. Persona 3, live |
| Grant restored | **200** |
| Picker `caseCount` vs list `total` | **3 and 3** — the defect above, after the fix |

And **in the browser**, in live mode: sign in → project picker → Test cases. Suite tree with counts,
facet chips carrying real counts (Draft 2 · Approved 1 · Manual 2 · Automated 1 · Critical 1),
"3 cases", the grid ordered TC-1 · TC-2 · TC-10, Owner reading "S. Admin" and **"Unassigned"**,
Health "—", Last result "Never run". Typing `100%` into the search box narrowed it to one row and
**re-computed every facet** to match.

### One finding the browser pass surfaced, and it is not this slice's

`CaseListPage` also fetches **`GET /projects/{projectId}/conditions?type=PRE`** for its bulk-action
dialog, and that endpoint **does not exist** — so the screen raises one "Something didn't load /
Not Found" notification on every load even though everything on it works. It was invisible before
this slice only because `/cases` 404'd first. The `Condition` entity and its repository already
exist (§24), so the endpoint is small; it is left out because it is a different endpoint from the
one this slice scoped.

*(The 503s visible in the network log during that pass were the backend restarting mid-check; the
retry policy recovered every one of them, which is `isTransient()` working as designed.)*

### Deliberately not done

- **No writes.** No create, edit, bulk or delete. Creating a case allocates `key`/`key_seq` from the
  company's `key_sequence` row and must write `test_case_version` v1 in the same transaction, which
  is a slice of its own.
- **`GET /cases/{caseId}`** — the full `TestCase` shape, with steps, conditions and versions.
- **`owner` is not sortable.** It would need `identity`'s table inside this module's `ORDER BY`.
  The SPA's grid offers the header and both modes fall back to `keySeq`; a real gap, recorded rather
  than papered over.
- **`search_vector` is unused.** `q` is a trigram `LIKE` over title and description — Criteria
  cannot express `@@` without a custom function, and a native query would drop the Hibernate company
  filter. `description` has no trigram index; the row set is already bounded to one project.
- **Facets count the filtered set, including each filter's own contribution**, matching the mock.
  Drop-own-filter faceting is more useful, is four more queries, and is a product decision with a UI
  consequence rather than a detail to slip in.

### Next

1. **`GET /projects/{projectId}/conditions`** — small, and it removes the one error notification on
   the case list screen.
2. **`GET /projects/{projectId}/cases/{caseId}`** — the case detail screen, the natural pair to this
   slice and the last read the repository module owes the SPA.
3. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
4. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.

---

## 26. Backend M1, slice 5 — the condition library, and the last error on the case list (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**. Closes
§25's next-step item 1: **`GET /projects/{projectId}/conditions`** — the reusable pre/post condition
library (plan §6f, §6h), the smallest slice on the list and the one that removes the error
notification §25's browser pass found.

### The headline: the biggest screen in the product now loads clean

`CaseListPage` fetches this endpoint for its bulk-action dialog on **every** load, so until now the
screen raised one "Something didn't load / Not Found" notification even though everything visible on
it worked. It was invisible before §25 only because `/cases` 404'd first — which is the useful half
of the observation: **an error notification the user learns to ignore is one that no longer reports
anything.** The screen now loads with an empty notification stack and a clean console.

| Path | Purpose |
|---|---|
| `repository/ConditionType` | `PRE`/`POST`/`BOTH`, and `matching()` — where BOTH is folded in. |
| `repository/ConditionQuery` | The two parameters, normalized once at the edge; owns the `LIKE` escaping. |
| `repository/ConditionView` | The contract's `Condition`. Distinct from `SuiteConditionView` — see its header. |
| `repository/ConditionService · ConditionController` | The borrowed gate and the edge. |
| `repository/Condition` | **`type` and `usageCount` are now mapped** — the readers they were waiting for. |
| `repository/ConditionRepository` | `findLibrary` beside the existing `findLiveByIds`. |
| `db/migration/V9__condition_project_index.sql` | The index V1 forgot. |
| `repository/ConditionLibraryEndpointTest` | **New, 30 tests.** |

### `type=PRE` includes `BOTH`, and that is the decision worth reading

An exact match answers 200 with a plausible list and silently withholds exactly one row: the entry
an author declared reusable in **either** position. Under exact matching a `BOTH` entry appears in
no picker at all — not the precondition one, not the postcondition one — so **marking a condition
usable everywhere would be the one way to make it unreachable everywhere.** The filter asks "what
may I attach *as* a precondition", not "what is filed under the literal string PRE".

`type=BOTH` resolves to `BOTH` alone, on the same reading: it asks for the entries usable in either
position, which is exactly the ones declared that way.

**The SPA's mock matched the type exactly**, and here the mock was wrong rather than merely
different — so it was fixed rather than recorded (`handlers.ts`), and a `BOTH` fixture row was added
so the fold-in is reachable in mock mode at all. With only PRE and POST rows the two readings return
identical lists and the difference is invisible until live mode.

### V1 forgot an index on `condition`, and this is the first query to notice

`condition` came out of V1 with two GIN indexes and **no b-tree at all**, so
`company_id = ? AND project_id = ? AND deleted_at IS NULL` — the shape of every read here, and the
shape RLS adds to any read that forgets — had nothing to seek on. Every sibling table already
carries it: `suite_project_idx`, `test_case_suite_idx`, `test_case_lifecycle_idx`. `V9` adds
`condition_project_idx` mirroring `suite_project_idx` exactly — `company_id` leading per ERD §0.1
point 3, partial on `deleted_at IS NULL` because retired entries accumulate permanently. Verified
with `EXPLAIN` against the running compose stack: `Index Scan using condition_project_idx`.

### Three decisions that are decisions, not omissions

1. **`usageCount` is zero on every row, and is served as zero.** The column is `NOT NULL DEFAULT 0`
   and the writers that would maintain it do not exist, so the stored value is the honest answer —
   §25's `case_health` shape exactly. It starts ranking the moment something counts, with no change
   here. **The SPA now omits the "used by N" annotation at zero** rather than rendering "used by 0"
   on every row: same call as §22's dangling "Your role:" and §23's invented status colour, and it
   also stops the order reading as a ranking when it is currently just the title.
2. **`q` is a `LIKE` over the title, not `@@` over `search_vector`.** The vector exists and is
   populated (V2, title A + body B) and is the right §6h corpus; HQL cannot express `@@` without a
   custom function and a native query would drop the Hibernate company filter — the same constraint
   and the same choice as the case list's free text. Consequences, both pinned by tests: the search
   is **title-only** (matching the mock) and is a substring rather than a stemmed match.
3. **Retired entries drop out.** Retiring is a soft delete because attachments survive it; serving
   one would offer a withdrawn precondition for attachment to a *new* case, which is the one place
   the withdrawal was supposed to bite.

### Mutation-verified — eleven mutations, one structural survivor, and **two fixture defects**

| Mutation | Result |
|---|---|
| `PRE` matches `PRE` only (exact type match) | **Killed** |
| `deletedAt IS NULL` removed from `findLibrary` | **Killed** |
| `projectId` predicate removed | **Killed** |
| Gate replaced by unconditional visibility | **Killed** |
| `type` filter silently ignored | **Killed** |
| Blank `q` not normalized to null | **Killed** |
| `usageCount` served as a constant 0 | **Killed** |
| `@Enumerated(STRING)` → JPA default (ORDINAL) | **Killed at boot** — `ddl-auto: validate` |
| `ORDER BY usageCount` dropped | **Survived → fixture fixed → killed** |
| `LIKE` metacharacters no longer escaped | **Survived → fixture fixed → killed** |
| `id` tie-breaker dropped | **Survived, structurally** |

**Both survivors were the same defect, and it is §25's tie-breaker survivor twice over: a fixture
that cannot distinguish the right query from the wrong one proves nothing.**

- *Ordering.* "Log in" was seeded at 412 and "Log out" at 203 — which orders them the **same way the
  title does**, so dropping `usageCount` from the `ORDER BY` changed nothing. Inverted, so the two
  rankings now disagree.
- *Escaping.* The library carried one title with a `%` and one with a `_`, and that is not enough:
  unescaped, `%100%%` still matches only the "100" row and `%user_account%` still matches only the
  underscored one. **Each metacharacter needs a near-miss beside it** — "Refund 1000 EUR in full"
  and "Seed a user account row" — so the escaped and unescaped readings return different answers.
  Both were added and both mutations then died.

The `id` tie-breaker survivor is structural and documented in `ConditionRepository`: the remaining
tie needs two entries with the same usage count *and* the same title in one project, and Postgres
returns a small table in physical order, so no fixture can make the unstable query answer
differently on two consecutive reads.

### One thing the test harness got wrong, worth keeping

**`MockMvc` does not percent-decode a query string appended to the URL template.** `?q=100%25`
arrived at the controller as the six characters `100%25`, matched nothing, and the escaping test
failed for a reason that had nothing to do with escaping. Every parameter is now bound with
`.param(name, value)` — which is also what a real client produces after the container has decoded
the request, so the value under test is the value the endpoint will actually see.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **337 tests** (307 + 30). `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, two provisioned companies, and a library seeded
through the **app role** under RLS:

| Assertion | Result |
|---|---|
| No filter | 7 live rows; the retired entry absent, `Log out` (412) first |
| `type=PRE` | 6 rows **including the BOTH row**, `Log out` absent |
| `type=POST` | `Log out` and the BOTH row, nothing else |
| `type=BOTH` | the BOTH row alone |
| `q=100%` | **one row** — unescaped it would also have returned "Refund 1000 EUR in full" |
| `q=user_account` | **one row** — unescaped it would also have returned "user account" |
| `q=` and `q=  ` | the whole library, not an empty search |
| `q=account menu` (body text) | **0 rows** — the title-only decision, visible |
| `type=NOPE` | **400**, not a dropped filter |
| Another client's project | **404, 0 bytes**; their condition title appears nowhere in this client's library |
| `EXPLAIN` on the library query | `Index Scan using condition_project_idx` |

And **in the browser**, in live mode: the case list loads with **no notification and no 404 in the
console**, and the bulk dialog's precondition picker lists the library most-used first, with the
retired entry absent, the POST-only entry absent, **the BOTH entry present**, and the zero-count row
rendering with no "used by" annotation while the others keep theirs.

*(One pre-existing console error remains and is not this slice's: MUI X `DataGridRaw` warns "Cannot
update a component while rendering a different component" under React 19. It predates this work and
is visible on any case-list load.)*

### Deliberately not done

- **No writes.** No condition authoring, editing or retirement — which is also what would make
  `usage_count` and the inline-attachment shape (§24) answerable rather than recorded.
- **`GET /projects/{projectId}/shared-steps`** — the sibling library, same shape, same table
  structure. Left out because it is a different endpoint with no consumer raising an error today.
- **No relevance ranking.** §6h specifies relevance blended with `usageCount`; this is
  `usageCount` alone, because relevance needs the `@@` path above.
- **Drop-own-filter faceting, `search_vector`, and the `kind` field on `CaseConditionRef`** — all
  unchanged from §24/§25 and all still recorded there.

### Next

1. **`GET /projects/{projectId}/cases/{caseId}`** — the case detail screen, the last read the
   `repository` module owes the SPA, and the natural pair to §25.
2. **`GET /projects/{projectId}/shared-steps`** — small, and it finishes the two reusable libraries.
3. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
4. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.

---

## 27. Backend M1, slice 6 — the case detail, and the SPA's order defect the mock could not show (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**. Closes
§26's next-step item 1: **`GET /projects/{projectId}/cases/{caseId}`** — the last read the
`repository` module owed the SPA, and the largest response in the product.

### The headline: §6f's condition inheritance is implemented, and it has exactly one implementation

The case detail is the first endpoint that had to *resolve* anything rather than filter it. Its
condition lists are assembled by walking the case's suite ancestry, and the order is `V1`'s own
comment made real: **inherited walking `suite.path` upward, outermost folder first, then the case's
own, deduped by library condition.** All of it lives in one class (`CaseConditions`) because a second
implementation of a resolution order is a second answer to "what must a tester do before step 1".

| Path | Purpose |
|---|---|
| `repository/CaseConditions` | **The §6f resolution order.** Ancestry walk, opt-outs, dedupe, both kinds. |
| `repository/CaseConditionRefView` | The contract's `CaseConditionRef`, from a case's viewpoint. |
| `repository/ConditionKind` | `PRE`/`POST` — what an *attachment* is, vs `ConditionType`'s what an entry *may be*. |
| `repository/CaseCondition · CaseConditionRepository` | The case's own attachments. |
| `repository/CaseConditionOptout · …Id · …Repository` | The opt-out beside the inherited marker. |
| `repository/TestStep · StepKeyword · TestStepView · TestStepRepository` | The steps, both formats. |
| `repository/CaseAutomationTag · …Repository` | Selection tags — *not* `case_tag`, per §6c. |
| `repository/CaseSummaries` | **Extracted from `CaseService`** — see below. |
| `repository/DimensionKeys` | The three dimension keys, now that there are two readers. |
| `repository/TestCaseDetailView · CaseDetailService` | The response and its service. |
| `repository/TestCaseRepository` | `findLiveInProject` — the second gate, *inside the query*. |
| `db/migration/V10__suite_condition_suite_index.sql` | The index V1 forgot, again. |
| `repository/CaseDetailEndpointTest` | **New, 39 tests.** |

### The grid's renderer is now shared, and that is the point of `CaseSummaries`

`TestCase` in the contract is `allOf: [TestCaseSummary, …]`, so the detail owes **every** field the
grid owes. Rebuilding that inside a detail service is how "a hundred consistent pages become a
hundred slightly different ones" — and the specific failure is quiet: the grid drops a **retired**
classification value, a second implementation renders it, and one case shows one Test Type in the
list and another on its own page. Nothing errors and neither screen reveals which is right. So the
rendering moved out of `CaseService` unchanged — same code, same comments — and both callers use it.
`CaseService` keeps only what a *list* has: scope resolution, paging, facets.

### Two path segments are two gates, and the second one is the one that is easy to miss

`ProjectService.readVisible` answers "may this caller see this project", borrowed unchanged as
always. `TestCaseRepository.findLiveInProject` answers the question a **two**-segment path raises
and a one-segment path does not: **is this case in that project.** Without it,
`/projects/{granted}/cases/{ungranted}` serves the ungranted case's full text — title, steps,
conditions, owner's name and email — and **every cross-company test in the suite still passes**,
because the row genuinely is in the caller's company. It is the §24/§25 persona-3 lesson one level
deeper: the leak is *inside* one tenant. Both failures, plus a soft-deleted case and a nonexistent
one, collapse into one bodiless 404 — measured at four bytes of nothing each, because a different
content length is a distinguishable answer too.

### Three decisions that are decisions, not omissions

1. **A condition attached to the case's *own* folder is `inherited: true`.** The case did not attach
   it, and editing it still reaches every sibling — which is exactly what the SPA's warning is for.
   Marking it local would remove the warning from the commonest case there is: the standing rule on
   the folder the author is looking at.
2. **The two kinds dedupe separately.** "Reset the test payment data" inherited as a `PRE` and
   attached by the case as its own `POST` is two actions at two times — precisely what a `BOTH`
   library entry exists for — and one shared dedupe silently drops the second.
3. **`severity` is nullable, and the contract was wrong.** `priority` is `NOT NULL DEFAULT 'MEDIUM'`
   and `severity` has no default at all — the schema saying they are different fields. `openapi.yaml`
   now says `nullable: true`; defaulting it to the priority would put a judgement on the wire that
   nobody made, indistinguishable from one that was. Same divergence, same resolution, as §25's
   `owner`.

### `V1` forgot an index on `suite_condition`, which is V9's omission one table over

`suite_condition` came out of `V1` with nothing but its primary key, while both queries against it
seek on `suite_id` — the suite tree's lookup (§24) and now the ancestry walk. Its sibling
`case_condition` got `case_condition_case_idx (case_id, kind, position)` in `V1` and this table got
nothing. `V10` mirrors it with `company_id` leading per ERD §0.1 point 3. **Not** partial: this table
has no `deleted_at` — detaching is a real `DELETE`, and retirement is a soft delete one level down on
`condition`, which is what V9's partial index is for. Verified with `EXPLAIN` against the running
compose stack: `Index Scan using suite_condition_suite_idx`.

### Mutation-verified — fifteen mutations, two survivors, and both were informative

| Mutation | Result |
|---|---|
| Ancestry walked nearest-first (`.reversed()` dropped) | **Killed** |
| `projectId` predicate removed from `findLiveInProject` | **Killed** |
| `deletedAt IS NULL` removed from `findLiveInProject` | **Killed** |
| Gate replaced by unconditional project visibility | **Killed** |
| Opt-outs ignored | **Killed** |
| The two kinds share one dedupe set | **Killed** |
| Inherited entries no longer marked `inherited` | **Killed** |
| Own attachments emitted before inherited ones | **Killed** |
| Retired library entries served as current | **Killed** |
| `ORDER BY tag` dropped from automation tags | **Killed** |
| `severity` defaulted to the priority when unset | **Killed** |
| `@Enumerated(STRING)` dropped from `CaseCondition.kind` | **Killed** |
| Inline attachments no longer excluded (suite side) | **Killed** — by §24's repository-level test |
| Inline attachments no longer excluded (case side) | **Survived → test added → killed** |
| `ORDER BY position` dropped from the step query | **Survived, structurally — documented** |

- *Inline exclusion.* An inline row has a null `conditionId`, the resolver looks it up with
  `resolved.get(null)`, and `HashMap` answers null — so the row drops out **whether or not the query
  ever excluded it**. Every endpoint assertion stays green. §24 had already found this on the suite
  side and answered it with an assertion against the *repository*; the case side needed the same one,
  and mutation is what showed that the two guards are not interchangeable.
- *Step ordering.* The fixture writes the steps at positions 3, 1, 2 in that physical order
  specifically so an unordered query would come back wrong — and with the clause removed the
  response still arrives 1, 2, 3, because the plan is an index scan on `test_step_case_idx` whose
  second column *is* `position`. No fixture can beat that. The clause stays because **an index is not
  a contract**: replace it, or let the planner pick a bitmap scan once a case is large enough, and
  every past screenshot is filed against a list that reads in a different order than it was written.
  Recorded on `TestStepRepository` rather than left as a passing clause nobody can account for.

### The browser pass found a real defect, and it was in the SPA

**`CaseDetailPage` re-sorted both condition lists by `position` — destroying the resolution order the
server had just computed.** `position` is sibling order *within one suite* and says nothing about
which suite comes first, so the render interleaved depths: a rule set three folders down appeared
ahead of the one it depends on. The same trap `SuiteRepository` records for the flat suite list,
where ordering by position lets a child precede its own parent.

**It was invisible against the mock and could only be invisible there**: the mock hands out one flat
ascending sequence with the inherited entry first, so position order and resolution order agree in
mock mode and *only* in mock mode. Lesson §6.5 again — the gates prove it compiles, the mock proves
the shape, and neither proves the order. Fixed by rendering the server's order, with the reasoning in
the file. (The steps sort stays: all steps belong to one case, so `position` is a total order there.)

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **376 tests** (337 + 39). `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, two provisioned companies, and a three-deep suite
chain seeded through the **app role** under RLS:

| Assertion | Result |
|---|---|
| The full case | 200 — every summary and detail field, both version numbers distinct (v7 / lock 3) |
| Precondition order | cookie banner → log in (both *Payments*) → seed payment (*Refunds*) → reset (*Partial refunds*) → the case's own, unmarked |
| The opted-out entry | absent from TC-1 — **and present on TC-2**, which inherits the same condition from a second attachment |
| The retired entry, the inline entries | absent, all three |
| Postconditions | inherited *Payments* entry, then the case's own `BOTH` entry — the separate dedupe, visible |
| Steps | 1, 2, 3 despite being written 3, 1, 2; no keywords on a structured case |
| The unauthored case | 200 — "Not set", "No steps written yet", "Unassigned", empty arrays |
| Another client's case through this client's path | **404, 0 bytes** |
| Another client's case through its own path | **404, 0 bytes** |
| The other client's admin reading this client's case | **404, 0 bytes** |
| `RIVAL CO CONFIDENTIAL` in any response of this client's | **0 occurrences** |
| Unauthenticated | 401 |
| `EXPLAIN` on the ancestry query | `Index Scan using suite_condition_suite_idx` |

And **in the browser**, in live mode: the detail page renders the whole case with the inherited
markers naming their folders, a 404 degrades to an inline "Not found" region with the rail intact and
one notification (no blank page), and the console carries nothing but the expected client-error
report for that 404.

### Deliberately not done

- **No writes.** No PATCH, no DELETE, no create — so `lockVersion` is served and never yet checked,
  and the inline-attachment gap stays unresolvable (nothing can create one).
- **`relatedDocuments` and `lastResult` are omitted**, not served as `[]`/`null`: both are sourced
  from modules that do not exist (`documentation`, `execution`). Neither is contract-required and the
  SPA already renders their absence correctly. Same call as §25's `lastResult`.
- **`docstring` / `datatable` on a step, and `parameter_bindings` on an attachment** — real columns
  with no field on the contract's shapes. Mapping them would put values on an entity nothing can
  serialize.
- **`GET /projects/{projectId}/shared-steps`** — still the sibling library, still unbuilt; it is what
  would give `TestStep.sharedStepId` a name rather than a bare id.
- **`kind` on `CaseConditionRef` for the *suite* endpoint** — narrower now and still open: a case has
  two arrays, so which array a row is in says what the field would have; a suite has one and
  genuinely loses the distinction.

### Next

1. **`GET /projects/{projectId}/cases/{caseId}/versions` and `/history`** — the two reads still
   hanging off the case detail screen, and the first thing that needs `test_case_version`.
2. **`GET /projects/{projectId}/shared-steps`** — small, and it finishes the two reusable libraries.
3. **The first write.** `PATCH /projects/{projectId}/cases/{caseId}` is the natural one: it makes
   `lockVersion` mean something, forces the version-writing path (§3 lesson 5), and is the only thing
   that can answer the inline-condition question.
4. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
5. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.

---

## 28. Backend M1, slice 7 — the first write, and why §27's own next-step list was wrong (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**.
Plan: `docs/plans/2026-07-31-case-update-first-write.md`.

### The slice changed before it started, and the orientation pass is why

§27 named `/cases/{caseId}/versions` and `/history` as next. All three of its premises were wrong:

| §27's claim | What the code said |
|---|---|
| `/versions` is a read the SPA is waiting for | **`useCaseVersions` has no consumer.** A dead hook. So is `useSharedSteps` (item 2). |
| `/versions` is "the first thing that needs `test_case_version`" | True, and that was the problem: **nothing wrote `test_case_version`** — no entity, no seed, no writer. It would have answered `[]` for every case in the product while §27's detail endpoint already served `currentVersionNo` from `test_case.current_version_no` (`NOT NULL DEFAULT 1`). One case reading **"v7" on its detail page and "no versions" on its version list** — §5-defect-3's class, two screens of one case disagreeing. |
| `/history` is a `repository` read | Tagged `[repository, reporting]`, and `entries` needs `test_execution` + `test_run` + `case_status` + `project_configuration` + defect links + evidence counts — **the whole `execution` module, which does not exist.** |

So §27's item 3 came first. **`PATCH /projects/{projectId}/cases/{caseId}` — the first write in the
product.** `/versions` is now a small follow-on with real data behind it.

**The lesson, and it is new:** a "next" list written at the end of a slice is written from what the
*contract* still owes, not from what the *code* can support. Both dead hooks and the empty table were
one grep away. **Re-derive the next slice from the code at the start of a session, not from the last
session's closing paragraph.**

| Path | Purpose |
|---|---|
| `project/GateResult` | **The write gate's tri-state.** A read's `Optional` cannot carry 403. |
| `project/ProjectAccess · ProjectService.writeGranted` | `canDo(user, project, permission)`, and the write gate beside `readVisible`. |
| `repository/CaseUpdateRequest · CaseEdit` | The wire shape, and the validated/normalized edit — one place every rule lives. |
| `repository/CaseSteps` | **The reconcile.** Updates in place; never replaces. |
| `repository/CaseVersions · TestCaseVersion · …Repository` | §6j: the snapshot, the hash, skip-when-unchanged. |
| `tenancy/AuditEvent · AuditEventRepository` | The client's own trail — first writer. |
| `repository/CaseUpdateService · ApiProblem` | Five outcomes, and the error envelope. |
| `repository/CaseUpdateEndpointTest` | **New, 58 tests.** |

### The gate a write needs is not the gate a read needs, and every role holds the read one

Every read in `repository` borrows `readVisible`, which asks **`project:read` — held by all six
built-in roles**, because that is what makes a granted project visible. A write gated the same way is
**a write a Viewer may perform**, and a Viewer is specifically the client's own stakeholder, invited
to read coverage without touching it (roles spec §A7). Nothing in a suite whose callers are all leads
would show it. `writeGranted` names its permission, so calling the wrong gate is visible in the diff.

**403 exists here, and it does not weaken the 404-not-403 rule.** That rule conceals *existence* from
a caller who cannot see the thing; 403 is reached only *after* the read gate has passed, when the
caller is looking at the case in the editor. A 404 there would tell them their case had been deleted
— false, alarming, and about the one thing a test repository must never appear to have done. An
unreachable project or a case in a sibling project still answers **404, 0 bytes**. The gate checks
visibility **first** for exactly this reason: checking the permission first makes 403-vs-404 an oracle
for what exists, and the mutation that reorders them is killed.

### `case:edit` is NOT `OWN`-qualified — a contradiction inside the authoritative spec

The permission table says plain ✓ for Test Engineer; **§A5 listed `case:edit` among the
`OWN`-qualified**; §A3's own principle one screen up — *"a Test Engineer is never blocked from
producing, only from unmaking someone else's work"* — names `case:delete` and `evidence:delete` and
deliberately not this. Two of three readings agree and the third contradicts the principle it sits
under. **§A5 is corrected, struck through with the reasoning**, per the working agreement. Editing is
producing; deleting is unmaking. The `OWN` qualifier is still owed to `case:delete`.

### Four contract divergences, all found by reading rather than by failing

1. **`If-Match` vs body `lockVersion`.** The contract always specified the header; the SPA and mock
   put it in the body, and it worked because no server read either. **The header wins** — a PATCH body
   states desired state, and a precondition as a field is a value on the input schema that must never
   be persisted. Both sides adapted in this commit.
2. **No 403 in the contract.** Added `responses.Forbidden`, with the reasoning above in its
   description.
3. **`Problem` had no `errors` field** while the client has read `body.errors` since Phase 1 and the
   mock has emitted it since. Now declared, keyed by input-schema field name.
4. **`TestCase`'s `allOf` branch had no `required` array** — handoff §6 lesson 3, again, and this time
   `tsc` found it the moment a consumer needed `lockVersion`. Fixed for all fourteen fields;
   `relatedDocuments` stays out because the server omits it (§27).

### Steps are reconciled, and the alternative is the most expensive defect in the slice

`test_step.id` is stable and `step_result` references it, so **a step that keeps its id keeps every
result and every piece of evidence** (§3 lesson 4). Delete-and-reinsert is four lines shorter and
reassigns every id on every save: nothing fails, the screen looks right, and every past result is
orphaned from the action it described. **The steps come out correct either way — only the ids
differ**, so the ids are what the tests assert.

**A limitation recorded rather than guarded:** `step_result.step_id` has **no `ON DELETE` clause**, so
deleting a step that has ever been executed will raise a foreign-key violation. Every delete succeeds
today because nothing writes `test_execution`. The moment execution ships, removing a step from a case
somebody ran becomes a 500. The decision it needs is a product one — soft-delete the step, refuse the
edit, or detach the result — and it belongs to the slice that can write a result to prove the choice.

### The hash is over the authored edit, not the rendered case — and that is not interchangeable

`snapshot` answers *what did this case say* (the full resolved case, as `GET` served it — resolved so
a shared condition edited today cannot change what a tester was told last March). `content_hash`
answers *did the author change anything*, and is taken over **`CaseEdit`**: the normalized set of
fields a save can change.

Hashing the snapshot is the plausible shortcut and it breaks the property the hash exists for. The
snapshot carries values that move on their own — the owner's display name, health signals derived
from run results, `updatedAt` — so **a colleague renaming themselves, or a nightly run turning a case
red, would make the next save look like an edit.** The mutation that does this is killed. Hashing
`CaseEdit` also makes the coupling self-maintaining: when a later slice makes conditions editable they
join `CaseEdit` to be validated at all, and join the hash in the same commit.

**The first version of every existing case is v2, and the v1 gap is deliberate.** Every case predates
this path, so its first edit writes v2 and its v1 is permanently absent. Labelling the edit v1 would
claim the edited text was the original; backfilling a synthetic v1 from the current row would assert
that the pre-edit content was the post-edit content, in the one table whose purpose is that past
claims stay true. History began when it began.

### Six contract fields are refused with a 400, not accepted and dropped

`ownerId`, `classificationValueIds`, `tags`, `automationTags`, `preconditions`, `postconditions` are
on `TestCaseInput`, none is sent by any client, and each needs a write path this slice does not build.
**Absent means unchanged (correct PATCH semantics); present means 400.** Accepting and ignoring them
is a save that reports success and discards what the user typed — the failure the mock's own handler
comment names. A later slice deletes the boundary in one place. Mirrored in the mock so mock mode
cannot pass a payload live mode rejects.

### Mutation-verified — eighteen mutations, one survivor, and chasing it found a real defect

| Mutation | Result |
|---|---|
| Write gate ignores the permission (a Viewer may write) | **Killed** |
| Permission checked before visibility (403 leaks existence to persona 3) | **Killed** |
| Steps replaced instead of updated (every id reassigned) | **Killed** |
| `If-Match` check removed (stale editor overwrites) | **Killed** |
| A version written on every save (hash ignored) | **Killed** |
| No version ever written | **Killed** |
| New version reuses the stored number instead of `+1` | **Killed** |
| Hash taken over the rendered snapshot instead of the authored edit | **Killed** |
| Audit row not written | **Killed** |
| Automation-key check no longer excludes the case being edited | **Killed** |
| The six unsupported fields accepted and dropped | **Killed** |
| Case-in-project predicate dropped | **Killed** |
| Blank optional text stored as `''` instead of null | **Killed** |
| Gherkin save keeps the structured steps | **Killed** |
| `advanceVersion` never called | **Killed** |
| A foreign step id adopted instead of refused | **Killed** |
| Step position not rewritten from payload order | **Killed** |
| `saveAndFlush` → `save` | **Survived — and it exposed a defect in my own reasoning** |

**The survivor was informative in the way §27's was not.** The class javadoc claimed the early flush
turns a write race into a 409 "rather than a 500 raised at commit" — and **nothing converted
`OptimisticLockingFailureException` at all**, so it would have been a 500 either way. Fixed with
`CaseController.onWriteRace`, which has to be an `@ExceptionHandler` rather than a `catch`: by the
time the exception exists the transaction is rollback-only, so returning a tidy outcome from inside it
fails again at commit with `UnexpectedRollbackException`. The flush now stands on the one property it
genuinely has — a constraint fails at its cause, before the version and audit rows are written on top
of it — and **that path remains untested**, because reaching it needs two interleaved transactions and
this suite has no mechanism for that. Recorded rather than implied.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **434 tests** (376 + 58). `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, two provisioned companies, and a seeded Viewer:

| Assertion | Result |
|---|---|
| First real edit | **v7 → v8**, lock 3 → 4 |
| Identical payload re-saved | **still v8**, lock 4 → 5 — the §6j skip, live |
| Version rows | 8 and 9 only, distinct hashes, author and comment recorded, `pinned` false |
| Audit rows | one per save incl. the no-ops, `{"versionNo":N,"newVersion":true/false}` |
| Step reconcile | reorder **preserved all three ids**, content edited in place, omitted step deleted |
| Stale `If-Match` | **409**; correct → 200; quoted `"5"` → 200; `banana` → **400**; `W/"3"` → 400; absent → 200 |
| Blank title / refused field | **400**, `errors` keyed on `title` / on `ownerId` **and** `tags` together |
| **Viewer: GET then PATCH** | **200 then 403**, case untouched, body names no permission key |
| Other client's admin | **404, 0 bytes** |
| Unauthenticated | **401** |

And **in the browser**, live mode: the editor loaded the real case showing "v7 → v8", a title edit
saved with the toast **"Saved TC-1 as v8"**, the detail page rendered the new title at v8 with §6f's
inherited markers intact, and a second edit produced v9 — both audited, both versioned, console clean.

**What the browser did *not* verify, stated plainly:** the 409 rendering in the editor's own UI. Two
attempts to create a stale editor failed because TanStack Query keeps the editor's copy fresh
(refetch on focus/mount), which is the app working correctly rather than a gap — so a stale save could
not be staged from the outside. The 409 is covered by eight backend tests and by curl above; its
**UI presentation is unproven**, and the SPA's conflict copy has only ever been exercised against the
mock.

### Deliberately not done

- **No POST (create) and no DELETE.** Create is the harder half — allocating `key`/`key_seq` from the
  company's `key_sequence` row and writing v1 in the same transaction — and DELETE needs the `OWN`
  qualifier that `case:delete` is still owed.
- **`lifecycleStatus` is not editable**, matching `TestCaseInput`: promotion through draft → review →
  approved is the review workflow's (§6g), and a status an ordinary edit can set is an approval anyone
  grants themselves by saving.
- **A case cannot move between projects** — `projectId` is `updatable = false`. Moving one would
  invalidate every suite, classification value and condition attached to it; it is not an edit.
- **`ApiProblem` duplicates `InvitationController.ProblemBody`.** The right home is the `shared`
  module the architecture lists and that does not exist yet; creating it means a package, a Modulith
  declaration, and an entry in every module's `allowedDependencies`. Visible duplication beat a quiet
  module.
- **The bulk endpoint** — same table, same validation, and a `bulk_operation_id` on `audit_event`
  waiting for it.

### Two findings that are not this slice's to fix

1. **No `created_by` column anywhere in the schema has an FK to `app_user`** — 23 columns, zero
   references, while §3 lesson 10 states this "already holds". It does not. Fixing it is its own
   migration and needs an `ON DELETE` decision under "suspend, never delete".
2. **The `/history` response's inline object has no `required` array** — lesson §6.3 again.
   `CaseHistoryResponse` is hand-written in `queries.ts` to work around it, and it should be fixed
   when `/history` is built.

### Next

1. **`GET /projects/{projectId}/cases/{caseId}/versions`** — now worth serving: it has real rows, and
   it is what makes the v2-not-v1 gap visible to a reader. Still needs a consumer in the SPA.
2. **`POST /projects/{projectId}/cases`** — create, with `key_sequence` allocation and v1 written in
   the same transaction. The other half of authoring, and it makes `/versions` gapless for new cases.
3. **The `execution` module**, which unblocks `/history` — the one screen with a real link that 404s
   live — and turns `pinned` into something that means anything.
4. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked. `CaseEditorPage`'s conflict copy
   is now a first-class target, since live mode cannot reach it.
5. The evaluator's cache, the `OWN` qualifier (owed to `case:delete`), an RLS-disabled lane for §13's
   surviving mutation, and the `case_status.color` migration alongside the status editor.

---

## 29. Backend M1, slice 8 — case create, the key allocator, and v1 (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**.
Plan: `docs/plans/2026-07-31-case-create-second-write.md`.

### §28's lesson applied to §28's own next list, and it changed the slice again

§28 named `/versions` first. One grep: **`useCaseVersions` still has zero consumers** —
`CaseDetailPage` does not call it — which is the same dead-hook trap §28 caught in §27, one slice
later. `useCreateCase` *is* wired to a real screen (`CaseEditorPage mode="create"`, route
`cases/new`, reached from two places on `CaseListPage`), so item 2 came first.

**This is now twice in a row that the closing "Next" list was wrong in the same way**, which makes it
a property of writing that list at the end of a slice rather than a mistake. The orientation pass is
not optional and its first question is *which of these has a consumer*.

| Path | Purpose |
|---|---|
| `tenancy/DisplayKeys` | **The one company-scoped key allocator.** One statement, range-capable. |
| `repository/CaseCreateService` | Create orchestration; five outcomes incl. `Replayed`. |
| `repository/TestCase.create` | The factory §28 said the create endpoint would bring. |
| `repository/CaseVersions.initial` | v1, explicitly — see below. |
| `V11__test_case_creation_idempotency.sql` | `created_idempotency_key` + its partial unique index. |
| `tenancy/DisplayKeyAllocationTest` · `repository/CaseCreateEndpointTest` | **New, 57 tests.** |

### The allocator is in `tenancy`, and it allocates a range on day one

Five entity types share `key_sequence` (`TC- TS- TP- TR- DOC-`), so an allocator in `repository`
makes `documentation` import it to number a page — and makes a second copy the obvious alternative.
Two copies of this statement is two chances to reach for `MAX(key_seq) + 1`, which is what `V1`'s own
table comment and `V5`'s header both exist to refuse: concurrent creation reads the same maximum and
issues the same key, failing under exactly the load a client importing a suite produces and never in
a test.

**A range, though today's only caller asks for one.** ERD §5 and plan §6t both say bulk import and CI
quarantine auto-create *must* reserve N in one statement. The SQL is byte-identical for n=1 and
n=500, so the range API costs nothing now and means the bulk slice has nothing to invent.

**Zero rows throws**, naming `provision-company`. An `UPDATE … RETURNING` that matches nothing is an
empty result, not an error — precisely the shape `V5` says "invites the wrong repair".

### v1 is written explicitly, and `decide()` would have got it wrong

`CaseVersions.decide` answers `currentVersionNo + 1`, and a fresh row's counter is `NOT NULL DEFAULT
1` — so a created case would take **v2** and its v1 would never exist. That gap is honest for cases
predating the write path and indefensible for one created after it: every case in the product would
have a missing first version, looking like the same historical accident. `initial(CaseEdit)` returns
`Decision(1, true, hash)` and reuses the same hash function, so a create followed by an unchanged
save still writes no v2.

### Idempotency is implemented, because ignoring the header was the §28 failure one endpoint later

The SPA has sent `Idempotency-Key` on this endpoint since Phase 1 — one key per mounted editor, with
a comment explaining that per-attempt keys are the mistake that makes the header look right and do
nothing — and **the mock has always honoured it while no server read it**. Mock mode was proving a
property live mode did not have. Same class as the `If-Match`-in-the-body divergence.

Stored as a column on `test_case` rather than in a general request table: the key identifies the
creation of one row, so retention is automatic and there is no new RLS policy or exception-list
decision. The honest cost is that a second create endpoint adds its own column; generalising waits
for a second real shape. Unique on **`(company_id, project_id, created_idempotency_key)`** with the
lookup naming the same three — a constraint wider than its lookup is a validated request that 500s on
insert, which `existsByAutomationKeyOtherThan`'s header already documents from the other direction.
**Replay answers 200, a fresh create 201**, matching the mock and letting a client tell "I made this"
from "I already had this".

### `case:create`, and the one qualifier that is NOT implemented

Viewer does not hold it; the other four human roles do. **Automation Service holds it "quarantine
folder only" and that restriction is not enforced** — there is no quarantine folder in the schema
(`suite` has no marker column, `project_configuration` names no default suite) because the concept
arrives with CI ingestion in M3, and `V3`'s comment already calls it "a service-layer rule: a
permission cannot carry a folder". So that role could currently create in any suite of a granted
project. It is unreachable through a client today — API tokens are M3, the only authenticator is a
password login, so the role can only be attached by hand-written SQL — and inventing a
`suite.is_quarantine` column to gate a caller that cannot yet exist would be a schema change made to
satisfy a test. **Recorded in the service javadoc and owed to the ingestion slice**, beside the `OWN`
qualifier owed to `case:delete`.

### Contract, and the wart left alone

Added: `Idempotency-Key` as a component parameter, `BadRequest` as a component response, and
`400/403/404` + `Location` on the POST (which declared **only 201**). `400` also added to the PATCH,
which has been able to answer it since §28. **Not fixed:** the path-level `parameters` block attaches
every *search* query param to `/cases`, so they apply to `POST` too — a real wart, and unpicking it
touches the list endpoint's generated types, which nothing else in this slice goes near.

### Mutation-verified — twelve mutations, and the one survivor was a bad test

| Mutation | Result |
|---|---|
| Gate asks `case:read` (a Viewer may create) | **Killed** |
| First version numbered by `decide()`, so v1 becomes v2 | **Killed** |
| No version row written at all | **Killed** |
| Key allocated before validation, so a typo burns a number | **Killed** |
| Idempotency lookup skipped entirely | **Killed** |
| Idempotency lookup drops the project predicate | **Killed** |
| Owner left unset · new case created `APPROVED` | **Killed** |
| Audit row not written · automation-key check always free | **Killed** |
| Step positions not rewritten from payload order | **Killed** |
| Blank `Idempotency-Key` stored as-is | **Survived — the test was wrong** |

The survivor sent `"   "` and `""` — **two different blanks, which do not collide even without the
trim**, so the assertion could not fail. Fixed to send the *same* blank twice, then killed. Worth
keeping: a test that exercises the right code path can still be structurally unable to observe the
defect, and only the mutation says which kind you wrote.

### Two id collisions the full suite found and the single-class run could not

`CaseCreateEndpointTest` was written on the `c0…` prefix, already owned by
`ConditionLibraryEndpointTest` — so **its no-grant persona became my granted Engineer** and three of
*its* tests failed. `DisplayKeyAllocationTest` had the same collision with `CaseDetailEndpointTest`'s
`d0…` and passed anyway, by luck. Both moved to free prefixes (`b1…`, `b2…`). This is handoff §19's
defect exactly, in a file whose own header warns about it — the warning does not help unless
something enumerates the prefixes already taken, which one `grep -rhoE` does.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **491 tests** (434 + 57). `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, a freshly provisioned company and a seeded Viewer:

| Assertion | Result |
|---|---|
| Three creates | **TC-1, TC-2, TC-3**, each v1, `DRAFT`, owner = creator, `Location` set |
| Same `Idempotency-Key` twice | **201 then 200**, same id, no `Location` on the replay |
| Retry with a *drifted* body | **200 with the original case**, not a 400 |
| Rejected payloads | blank title / `ownerId` / foreign `suiteId` / 201-char key → **400**, keyed on the field |
| A refused create | **burns no key** — the next create was TC-1 |
| **Viewer: GET then POST** | **200 then 403**, body names no permission key, nothing written |
| Unreachable project · unauthenticated | **404 with 0 bytes** · **401** |
| Rows | `test_case_version` v1 per case; `audit_event` `case.created` with `{"key":"TC-1",…}`; counter at 4 |

And **in the browser**, live mode: New case → the real suite picker → **"Created TC-4"** at **v1**,
Draft; the editor then read **"Edit TC-4 · v1 → v2"** and saved **"Saved TC-4 as v2"** — the created
case's history is gapless from the UI's own point of view — with TC-4 in the grid and the SPA's own
`Idempotency-Key` stored on the row.

**What the browser did *not* verify, stated plainly:** the retry path through the UI. TanStack Query
resolves the mutation before a second Save is possible, so the duplicate-intent case cannot be staged
from the outside — same shape as §28's unprovable 409. It is covered by seven backend tests and by
curl above.

### One console error, and it is not this slice's

`Cannot update a component (BrowserRouter) while rendering a different component (DataGridRaw)` on
the case list. `CaseGrid.tsx` is untouched by this slice (`git diff` empty, last changed in
`ec0f707`), so it is **pre-existing from the case-list slice** and is recorded rather than absorbed
or silently fixed. It is a React 19 + MUI X DataGrid setState-in-render warning; the page works.

### Deliberately not done

- **No DELETE** — still needs the `OWN` qualifier `case:delete` is owed.
- **A create still cannot make a suite**, so a freshly provisioned project has nowhere to put a case:
  the live pass had to seed one suite by hand. **`suite:manage` is now the smallest thing standing
  between a provisioned company and a usable product**, which it was not before this slice.
- The six refused fields, `lifecycleStatus`, the bulk endpoint, and the `created_by` FK gap (§28
  finding 1) are all unchanged.

### Next

1. **`POST/PATCH/DELETE /projects/{projectId}/suites`** — re-derived, not inherited: a company can now
   be provisioned and given cases only if someone writes a suite row by hand. `suite:manage` is held
   by Test Engineer and above, `suite:delete` by leads, and `DisplayKeys.EntityType.SUITE` is already
   built and tested.
2. **`GET …/cases/{caseId}/versions`** — now has real rows *and* a v1 to show, but **still no SPA
   consumer**; it wants the detail page's history panel in the same slice, or it is another dead hook.
3. **The `execution` module** — unblocks `/history` (the one screen with a live link that 404s) and
   turns `pinned` into something that means anything.
4. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
5. The `OWN` qualifier, the quarantine-folder gate (with M3), the evaluator's cache, and the
   pre-existing DataGrid setState-in-render warning above.

---

## 30. Backend M1, slice 9 — suite writes, and the first slice that shipped both halves (2026-07-31)

Run through the **`be-java`** skill (Mode B — extend) and then the **`fe-react`** skill (Mode B,
re-entering Phase 2). Plan: `docs/plans/2026-07-31-suite-writes.md`.

### The orientation pass changed the slice's SHAPE, not its subject

§29's list named suite writes first and that survived the re-derivation — a provisioned company had
no folders, and a case requires one, so §29's own live pass had to insert a suite with hand-written
SQL. What the pass *added* was the half the list omitted: **`useCreateSuite` had zero consumers**,
exactly like `useCaseVersions` before it. A backend-only slice would have left it dead for a fourth
consecutive slice. So this is the first slice to ship the endpoint and its screen together, and the
"which of these has a consumer" question is what produced that.

`useCaseVersions` is **still** dead. Fourth slice running.

| Path | Purpose |
|---|---|
| `repository/SuiteWriteService` | Create / rename / delete, sealed `Outcome`, two permissions. |
| `repository/SuiteEdit` | Validation, with `validateCreate` and `validateRename` genuinely different. |
| `repository/SuiteVersions` · `SuiteVersion` · `SuiteVersionRepository` | v1 composition version at create. |
| `repository/Suite` | Factory + `applyEdit` + `softDelete`; `key_seq` and the audit columns now mapped. |
| `repository/SuiteWriteEndpointTest` | **New, 56 tests.** |
| `features/cases/SuiteDialogs.tsx` | Create / rename / delete dialogs — `useCreateSuite`'s first consumer. |

### The `ltree` path label is the key sequence, and that is the load-bearing decision

`V2` ships `tcms_ltree_label()`, which slugs a *name*, and it is the wrong tool for a suite.
Classification values are seeded once and never renamed; **suites are renamed constantly, and a
name-derived path makes every rename a subtree rewrite** — contradicting `Suite`'s own account,
where reparenting is what rewrites paths. A slug is also not unique (`"UI / E2E"` and `"ui e2e"`
collide under one parent) and can be empty (`"…"` → `''`, not a valid label, failing at insert with
a message about nothing). The label is `s` + `key_seq`: unique by construction, stable across
renames, never empty. **Proven over the wire** — a live rename left the path at `s2`.

### Two permissions, because collapsing them reverts a boundary the spec fixed

`suite:manage` (create, rename) is Test Engineer and above; `suite:delete` is leads only. The roles
spec lists that split as a boundary that was *previously wrong and got corrected*, so gating both on
`suite:manage` is not a simplification, it is the reversion. One test carries it, and it is the only
one that goes red for that mutation.

### What this slice REFUSES, and why the refusal is the feature

1. **Moving.** A `PATCH` whose `parentId` differs from the stored one is a **400 naming the reason**,
   not a silently dropped field. Reparenting rewrites every descendant's path and needs cycle
   prevention; until that exists the only honest answers are "done" and "refused", and dropping the
   field is the third one — where a client shows the folder in its new place, reloads, and finds it
   back, with the server having reported success.
2. **Deleting anything non-empty.** `suite:delete` is specced "folder + subtree" and the cascade is
   deliberately absent: a folder delete that soft-deletes the cases beneath it unmakes *other
   people's* work through a permission whose sibling, `case:delete`, carries an `OWN` qualifier
   precisely to stop that — and with no preview. **409 naming the counts.** The cascade belongs to a
   slice that can show its work; `BulkResult` already has the shape.

### A rename writes NO version, and that follows from the schema rather than from taste

`V1`'s comment on `suite_version.snapshot` defines a suite version as **composition** (member cases,
order, nesting) and explicitly *not* content, "otherwise every case edit bumps every ancestor suite".
A name is content. So create writes v1 (§29's precedent: a row created after the write path exists
must not be born with the same gap as the rows predating it) and **rename writes nothing** — a
bump there is the start of the spiral the comment exists to refuse, and it looks like diligence.
Case-move, reorder and reparent owe the later bumps; each is named in `SuiteVersions`' header.

### Mutation-verified — fourteen mutations, and the one survivor was a missing FIXTURE

| Mutation | Result |
|---|---|
| Delete gated on `suite:manage` · create gated on `project:read` | **Killed** |
| Subtree count drops its project predicate | **Killed** |
| Path slugged from the name · move silently ignored on PATCH | **Killed** |
| Key allocated before validation · v1 written as v2 | **Killed** |
| `findLiveInProject` drops `deletedAt` · drops `projectId` | **Killed** |
| Blank description stored as-is · `If-Match` ignored · parent unchecked · audit detail unescaped | **Killed** |
| **Emptiness check ignores child suites** | **Survived — no fixture could see it** |

The survivor is the instructive one. Every non-empty folder in the fixture also held **cases**
somewhere beneath, so the subtree case count refused them anyway — and the counts still flowed into
the message, so even the wording assertion passed. Fixed by adding a parent whose only occupant is
an **empty** child, where the case count is zero and only the child check stands in the way. §29's
lesson in a new costume: a test that exercises the right code path can still be structurally unable
to observe the defect, and only the mutation says which kind you wrote.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **547 tests** (491 + 56). `npm run build` clean,
`npm run lint` 0 warnings.

**Live**, against `docker compose up` and a real session:

| Assertion | Result |
|---|---|
| Create from the tree | **TS-2**, path **`s2`** (not a slug of the name), `DRAFT`, creator stamped |
| Rows written | `suite_version` v1 with `{"caseIds": [], "parentId": null}` + a 64-char hash; `audit_event` `suite.created` |
| Rename | name changed, `lock_version` 0→1 (so `If-Match` was read), **path still `s2`**, still exactly one version row, `suite.renamed` |
| Delete a folder holding 4 cases | **409 in the dialog**: "This suite still holds 4 cases. Move or delete them first." Folder kept |
| Delete the empty folder | **204**, row survives with `deleted_at` set, `suite.deleted` |
| Create inside a collapsed folder | child at path **`payments.s3`**, parent auto-expanded so it is visible |

### Three defects the browser found and the gates could not

1. **An empty-name submit displayed nothing at all.** The dialog's `useEffect` called
   `reset(defaults)` with `defaults` in its dependency array — and `defaults` is an object literal
   built by the caller on every render. A failed submit set the field errors, React re-rendered, the
   identity changed, the effect re-ran, and `reset()` wiped the errors before anyone saw them. The
   reset was not needed at all: each dialog is conditionally rendered, so it mounts fresh and
   `defaultValues` already carries the right values. **Handoff lesson 6 exactly** — correct types,
   wrong runtime graph, both gates green.
2. **The server's actionable 409 never reached the user.** `describeError` collapsed *every* 409 to
   the lost-update copy ("Reload to see their version"), throwing away "This suite still holds 4
   cases. Move or delete them first." — and telling the user to reload, which changes nothing. The
   two 409s want opposite advice, so `ApiError` now carries RFC-7807 `title` and `describeError`
   reads it to tell them apart. **`ApiProblem` had already made this distinction server-side**
   (`stale()` vs `conflict(title, detail)`); the client threw it away one layer later.
3. **A MUI X Tree View warning my own slice made routine.** `defaultExpandedItems` is an
   *uncontrolled* default, and every write now invalidates the suite query, so it changed on every
   create/rename/delete. Made controlled — which also fixed the behaviour underneath: creating a
   suite inside a collapsed folder used to leave it invisible.

### The 500 the live pass hit, which was NOT this slice's code

The first live create answered **500 — `duplicate key value violates unique constraint
uq_suite_key`**. The allocator was right and the *data* was wrong: **every earlier slice's live pass
inserted its suite with hand-written SQL, bypassing `key_sequence`**, so all six companies had a
`TS-1` row and a counter still reading 1. Repaired with
`UPDATE key_sequence SET next_value = MAX(key_seq)+1`, which is the documented repair `V5`'s header
describes. Recorded rather than absorbed, because anyone following the handoff's own live-pass
instructions will hit it, and because it is the argument for `provision-company` being the only path
that creates rows.

### The rule this slice could not follow, and why it was not half-followed

`ui-rules` requires **disable-don't-hide**: an action the backend will refuse should render disabled
with a why-tooltip, never render-then-reject. **The SPA cannot do that for any of these actions** —
`CurrentUser` carries `isSystemAdmin` and teams and *no permission list*, so nothing tells the client
whether this user holds `suite:manage` on this project. A Viewer therefore sees Delete, clicks it,
and gets a 403 rendered in the dialog.

This is a **pre-existing class, not this feature's** — "New case" has behaved this way since Phase 2.
The fix is one contract change (the caller's effective permissions on a project) that closes it
everywhere at once. Gating suites alone would leave the rest of the app inconsistent and make the
eventual fix touch two patterns instead of one, which is precisely the "second implementation" the
working agreement warns about. **It is the highest-value frontend item outstanding.**

### The mock was aligned, because it was proving properties live mode lacked

Three divergences, all the `If-Match`-in-the-body family: the mock read `lockVersion` from the
**body**, slugged `path` from the **name**, accepted a parent from outside the project, applied
`lifecycleStatus`, and had **no DELETE at all**. All corrected.

### Deliberately not done

- **Moving/reparenting and reordering** — the endpoint *and* its UI belong to one slice, since a move
  endpoint with no drag-and-drop would be the dead-hook trap from the other direction.
- **Cascading delete** — owed to a slice that can preview what it will touch.
- **Composition version bumps** on case membership changes — owed to the case-move and case-delete
  slices, named in `SuiteVersions`.
- `ClassificationValue.path` has the same `ltree` mapping and **not** the `@ColumnTransformer` that
  `Suite.path` needed; it is unbroken only because nothing in Java inserts one yet, and it will fail
  identically on the day something does.

### Next

1. **The caller's effective permissions on a project, in the contract** — the disable-don't-hide gap
   above. One change, closes the class everywhere, and every future affordance needs it.
2. **`POST/PATCH/DELETE` on cases' remaining verb — `DELETE /cases/{caseId}`** — still needs the `OWN`
   qualifier `case:delete` is owed, and `useDeleteCase` **does** have a consumer, unlike `/versions`.
3. **`GET …/cases/{caseId}/versions`** — real rows and a v1 to show, and **still no SPA consumer after
   four slices**. Only worth doing in the same slice as the detail page's history panel.
4. **The `execution` module** — unblocks `/history`, the one screen with a live link that 404s.
5. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked, and `SuiteEdit`-style pure logic
   plus `describeError`'s now-branching 409 are good first targets.

---

## 31. Frontend — the caller's effective permissions, and disable-don't-hide everywhere at once (2026-07-31)

Run through the **`fe-react`** skill (Mode B — extend, re-entering Phase 2). Plan:
`docs/plans/2026-07-31-effective-permissions-disable-dont-hide.md`.

### The orientation pass changed the slice's PREMISE, and this is the third slice running

§30 named this first and described it as **"one contract change (the caller's effective permissions
on a project)"**. The orientation pass found that premise wrong in the most useful direction:

- **`GET /me/permissions` was already in the contract** — since the mock era — and **already
  implemented**. `MeController.permissions()` answers the three genuinely different questions
  (SYSTEM only · SYSTEM+COMPANY · SYSTEM+COMPANY+PROJECT), refuses a `userId` parameter on purpose,
  and `AuthEndpointTest` covers it. The mock handler existed and had been aligned in an earlier slice.
- **Nothing in the SPA had ever called it.** No hook, no key, no consumer.

So the "contract change" was one `required` array, and the slice was entirely frontend. Same lesson
as §28 and §29 in a third costume: **the previous session's next-step list describes the intent, not
the state.** Here the list was pessimistic rather than optimistic — it named work already done — but
it was wrong in the same way, and one grep found it.

| Path | Purpose |
|---|---|
| `docs/specs/openapi.yaml` | Named `EffectivePermissions` **with `required`**; `projectId` nullable. |
| `src/auth/useCan.ts` | **New.** The loading/failed policy, in one place. |
| `src/components/PermissionGate.tsx` | **New.** A denied action → a disabled control with a why-tooltip. |
| `src/lib/permissions.ts` | `permissionLabels`, `satisfies Record<PermissionKey, string>`. |
| `src/api/queries.ts` | `keys.permissions` + `useMyPermissions`. |
| `features/cases/*` · `features/runs/ExecutionPage.tsx` | Eleven affordances gated. |
| `src/mocks/handlers.ts` | Answers from the project's own `myRole` instead of always Test Lead. |

### Unknown is DENIED, failed is ALLOWED — the asymmetry is the design

Three states, three answers, and only two of them are obvious:

- **Loading → disabled** ("Checking your permissions…"). Disabled-then-enabled costs a grey moment.
  Enabled-then-disabled is *the defect itself*: the click lands in the window before the answer does.
- **Failed → enabled.** Fail-open, and it is the only rule here that looks wrong. Hidden is not
  protected (§6b) — the server rejects independently — so a wrong *enable* costs one 403 in a dialog,
  which is exactly how the app has behaved its whole life. A wrong *disable* tells a Test Lead they
  may not do their job, with no retry path and no error anywhere, because the failure is in a query
  that renders nothing. The cheap failure is the enabled one.

### The gate is one component, and the browser proved why in the first five minutes

`PermissionGate` owns the mechanic because MUI sets `pointer-events: none` on a disabled control, so
a `Tooltip` wrapped directly around one **never fires** — the button greys out and explains nothing,
which is the *worse* half of the original defect. The denied branch wraps the child in a span.

Then the first browser pass found the cost of that span: **the suite tree's kebab menu rendered its
three items side by side on one line.** `MenuList` is a plain `<ul>`, and an `inline-flex` anchor
inside it makes the items flow inline instead of stacking. Fixed with a `wrapper="block"` prop —
one prop, in the one component, rather than a layout patch at three call sites. The wrapper also
carries `role="none"`, because a generic between `role="menu"` and `role="menuitem"` can break the
relationship some screen readers rely on. **Both gates were green while the menu was unreadable.**

### Every write affordance, not just the suite ones

§30 refused to gate suites alone precisely so this slice could sweep the class. Derived from the
mutation hooks in `queries.ts` and their consumers, not from memory:

| Affordance | Permission |
|---|---|
| Suite tree: New · New suite inside · Rename | `suite:manage` |
| Suite tree: Delete | `suite:delete` |
| Case list: New case (header + empty state) | `case:create` |
| Case list: Edit selected (bulk) | `case:edit` |
| Case detail: Edit case | `case:edit` |
| Case editor: Save | `case:create` / `case:edit` |
| Execution: Passed · Failed · Blocked · Skipped | `result:submit` |

Two consequences worth keeping:

- **The editor's Save is gated as well as the button that navigates to it**, because `/cases/new`
  and `/cases/:id/edit` are real URLs. Gating only the entry point makes a permission *look*
  enforced while the form stays fully usable up to the 403.
- **A denied "Edit case" renders a plain `<button>`, not a disabled link.** MUI's `disabled` on
  `component={RouterLink}` only adds `aria-disabled` and kills pointer events — the anchor keeps its
  `href`, so Enter on a focused link and a middle-click both still navigate. Verified in the DOM:
  `{tag: "BUTTON", href: null, disabled: true}`. A disabled-looking link that opens the editor
  anyway is worse than no gate, because it looks like the gate worked.

**Empty-state copy follows the verdict too.** "Create the first test case for this project" beside a
button the reader cannot press is an instruction they cannot follow; a Viewer now reads "Ask a test
engineer or lead to add the first case."

### The mock had to be made less permissive before it could prove anything

The handler answered **Test Lead for every project**, which was harmless while nothing called it and
became actively misleading the moment something did: a mock that grants everything can only render
the enabled branch, so the whole feature would have been unprovable in the mode the app is developed
in. It now answers from the project's own `myRole` — and the fixtures already carried MOB, where the
caller is a **Viewer**, seeded that way in an earlier slice because read-only is the default on
joining. No new fixture was needed; the awkward row was already there.

### Gate evidence — run, not just built

`npm run build` clean, `npm run lint` 0 warnings, console clean (no MUI warnings).

**Mock**, both branches on real screens:

| Assertion | Result |
|---|---|
| MOB (Viewer): New case · suite New | **disabled**, tooltip "You don't have permission to create test cases in this project." |
| MOB: kebab menu | three items **stacked** and greyed; hovering Delete shows "…delete suites in this project." |
| MOB: `/cases/new` reached by URL | **Create case disabled** — the deep link does not bypass the gate |
| MOB: case detail Edit | `{tag: "BUTTON", href: null, disabled: true}` |
| OLB (Test Lead): the same controls | **enabled**, Delete still danger-red, and creating a suite still works end to end |

**Live**, against `docker compose up` and a real session (`ops@acme.test`, Company Admin):

| Assertion | Result |
|---|---|
| `GET /me/permissions?projectId=…` | 200, **48 permissions, sorted**, `projectId` echoed |
| `GET /me/permissions` (no project) | 200, **`projectId: null`** — which is why the contract needed `nullable` *and* `required` |
| Affordances | enabled, matching the 48 |
| Role temporarily switched to **Viewer** in Postgres, page reloaded | **New case and suite New both disabled, with the tooltip** — the list still loads, because `case:read` survives |
| Role restored | enabled again |

Flipping the row in Postgres is how the denied branch was proved *live*: the only other account with
a Viewer grant on that project (`viewer@acme.test`) has a password nobody recorded, and there is no
password-reset path. **The assignment was restored immediately** and re-verified.

### Two things this slice observed and did not fix

1. **`provision-company` creates no project** — a fresh company's admin lands on an empty project
   list with `project:create` and **no UI that creates one**. Every earlier live pass worked around
   it with hand-written SQL, which is also how §30's `key_sequence` collision was born. This is the
   next real gap in the live loop.
2. **Board drag-and-drop is ungated** (`workitem:manage`). The affordance is a drag, not a control,
   so disabling it needs a different mechanic than a tooltip — named rather than half-done.

### Dead hooks, now four and counting

`useCaseVersions` (five slices), plus `useDeleteCase`, `useCreatePlan`, `useCreateRun` and
`useCreateWorkItem` — all with zero consumers. Listed here rather than given affordances they have
not earned. `useCreateSuite` left this list in §30; nothing left it this slice.

### Next

1. **`DELETE /cases/{caseId}`** — `useDeleteCase` has no consumer *and* no endpoint, so the slice owes
   both halves, plus the `OWN` qualifier `case:delete` carries. The gate is already there to hang it on.
2. **A project-creation path** — the finding above. Without it, "provisioned company" still means
   "provisioned company plus some SQL", and every live pass pays for it.
3. **`GET …/cases/{caseId}/versions` with the detail page's history panel** — still only worth doing
   as one slice, and still the oldest dead hook.
4. **The `execution` module** — unblocks `/history`, the one screen with a live link that 404s.
5. Frontend **Phase 4 (Vitest + RTL)** — `useCan`'s three-state policy is now the best first target in
   the codebase: pure, branchy, and load-bearing for eleven affordances.

## 32. Backend M1, slice 10 + frontend — case delete, and the `OWN` qualifier it forced (2026-07-31)

Run through the **`be-java`** skill (Mode B — extend) plus `data:data-migrations` for V12, then the
frontend half. Plan: `docs/plans/2026-07-31-case-delete-and-the-own-qualifier.md`.

### The orientation pass agreed with §31's list — the first time in four slices

§31 said the slice owed both halves, and it did: no `DELETE` on `CaseController`, `useDeleteCase`
with zero call sites. What the pass *added* was the size of the third thing §31 mentioned in a
subordinate clause — "plus the `OWN` qualifier `case:delete` carries" — which turned out to be the
whole slice. The delete itself is 60 lines shaped exactly like the suite delete above it.

### `OWN` had no way to be expressed, and three files said so

Roles spec §A5: `case:delete` and `evidence:delete` are `OWN` for Test Engineer — authorship confers
full control, and a Test Engineer is never blocked from *producing*, only from unmaking **someone
else's** work. `PermissionEvaluator`, `ProjectAccess#canDo` and V3's own header each recorded the
qualifier as owed. Two implementations were available before this slice and both are wrong: grant
every Test Engineer the delete (they unmake colleagues' cases), or withhold it (an author cannot
remove their own mistake).

**V12 adds `role_permission.qualifier` (`'ALL' | 'OWN'`, default `'ALL'`)** and sets `OWN` on exactly
two seeded rows. Three decisions in it:

- **On the grant, not the permission.** `case:delete` is unqualified for a lead and `OWN` for an
  engineer — the same key, two answers, so the key cannot carry it.
- **Not `case:delete:own`.** V3 refused that before this slice existed: it doubles the catalogue and
  still cannot express "in any state".
- **Not "is the caller a Test Engineer".** Code checks permissions, never role names (§6b). A
  name-keyed rule mis-answers the first custom role, silently, in the direction that grants more.

**Strongest wins.** A Company Admin who also holds a project's Test Engineer grant keeps `ALL` —
`PermissionQualifier` is declared weakest-first so `compareTo` *is* the merge rule. The failure that
prevents: granting someone an *extra* role quietly taking capability away.

### Where the decision is made, and the one thing the type system does not enforce

`repository`'s package doc is absolute: *a permission check appearing anywhere in this package is a
defect regardless of what the dependency list permits.* So the row-level question crosses the
boundary as a **fact**, not a verdict:

```
CaseDeleteService → ProjectService.writeGrantedByAuthorship → AuthorshipGate.mayActOn(case.createdBy)
                                                            → ProjectAccess.canDoOnAuthored
                                                            → PermissionEvaluator.qualifierOf
```

The service supplies the row's author and is told yes or no; it never learns what its grant *is*, so
it has nothing to re-interpret and cannot grow a second reading of `OWN`. The rejected alternative —
returning the qualifier and letting each module compare — puts an authorization rule in every module
that owns a deletable row.

**The honest gap, recorded rather than papered over:** a callback that never calls `mayActOn` gets
the unqualified behaviour, and no type prevents that. Which is why the assertion lives *outside*, in
`CaseDeleteEndpointTest.engineerCannotDeleteSomeoneElses` — drop the qualifier and it is the only
test in the suite that goes red.

**A null `created_by` is not the caller's own.** Cases predating `POST` carry NULL; under `OWN` that
denies. Fail-open on exactly the rows nobody can vouch for is the wrong direction.

### Two 403s, deliberately worded differently

A Viewer holds nothing. A Test Engineer holds `case:delete` and is looking at a colleague's case.
Telling the second "you do not have permission to delete test cases in this project" sends someone
to ask an admin for a permission they already hold, about a case they are looking at, having deleted
three of their own that morning. `Outcome.NotTheAuthor` exists only to carry that sentence.

| Path | Purpose |
|---|---|
| `V12__role_permission_own_qualifier.sql` | The column, the CHECK, and the two `OWN` rows. |
| `identity/PermissionQualifier.java` | **New.** `NONE < OWN < ALL`; ordering is the merge rule. |
| `identity/PermissionEvaluator#qualifierOf` | Re-checks all three gates; unknown key throws. |
| `project/AuthorshipGate.java` · `ProjectService#writeGrantedByAuthorship` | **New.** The port and the gate. |
| `repository/CaseDeleteService.java` · `CaseController#delete` | **New.** 204 / 404 / two 403s / audit. |
| `tenancy/AuditEvent#jsonString` | Moved off `SuiteWriteService` — the case delete needed the same escaper, and two copies diverge on the input neither was tested with. |
| `features/cases/CaseDeleteDialog.tsx` | **New.** Names the case; renders the refusal in place. |

### The SPA cannot see the qualifier, and that is §31's policy rather than a gap

`GET /me/permissions` answers keys, not qualifiers, so Delete is enabled for a Test Engineer on every
case and the `OWN` refusal arrives as a 403 in the dialog. §31's asymmetry applies unchanged: a wrong
*enable* costs one 403 the user is already looking at; a wrong *disable* tells an engineer they may
not do their job. Which makes rendering the refusal **in the dialog** load-bearing — a toast at the
corner, or a dialog that closes on error, and the only sentence that explains it is gone.

### The browser pass found a defect that had nothing to do with the delete

Deleting one case dropped the Payments folder's count by **two**. The cause was pre-existing and
visible on every load without being legible: `Suite.caseCount` is the suite's **own** cases
(`SuiteView` says so, and the SPA's tree rolls the subtree up), and the **mock** answered the
*subtree* count — so every parent rendered its descendants twice. Payments read 40 in a project with
27 cases. Fixed by splitting `suiteCaseCount` (subtree — still what the delete's emptiness check
asks) from `suiteOwnCaseCount` (the wire meaning). Counts now reconcile: 19 + 3 + 3 + 2 = 27.

A single delete is what made it legible: a number that is merely too large looks like data, and a
number that moves by two when one row leaves does not.

### Gate evidence

**Backend** — `./gradlew build` green. `CaseDeleteEndpointTest` is 20 assertions over HTTP against
real Postgres with RLS: both 403s and their differing messages, the orphan row, strongest-wins from a
Company Admin with an engineer grant, 404 for ungranted/foreign/absent projects and for a case in a
sibling project, second delete 404, soft delete verified in the row, audit `case.deleted` carrying a
title with a quote and a backslash, no audit row for a refusal. `PermissionEvaluatorTest` gained the
qualifier matrix — including that **`case:edit` is `ALL` for a Test Engineer**, pinning the
2026-07-31 reversal so restoring the superseded reading fails the build.

**Frontend** — `npm run build` clean, `npx oxlint` 0 warnings, console free of errors and MUI
warnings. Mock: Test Lead deletes TC-3 → dialog names the case → 204 → redirect to the list, 27 → 26,
Refunds 18 → 17, detail 404s; MOB (Viewer) → Delete **disabled** with "You don't have permission to
delete test cases in this project", and the mock's own `DELETE` answers 403 with the same sentence,
so the gate and the handler read one source.

**Not done: a live pass.** The endpoint is covered by integration tests against real Postgres, but
the SPA was not pointed at `docker compose` for this slice — a live delete needs a company with a
project, a suite and a case, which is still hand-written SQL (§31's finding 1, still open). Stated
rather than implied.

### Next

1. **A project-creation path** — now blocking twice over: §31 named it, and this slice skipped its
   live pass for the same reason. "Provisioned company" still means "provisioned company plus SQL".
2. **`GET …/cases/{caseId}/versions` with the detail page's history panel** — `useCaseVersions` is
   six slices old and still the oldest dead hook.
3. **The `execution` module** — unblocks `/history`, the one screen with a live link that 404s. It
   also owns `evidence:delete`, whose `OWN` qualifier V12 seeded and nothing reads.
4. Frontend **Phase 4 (Vitest + RTL)** — `useCan` remains the best first target.
5. **Board drag-and-drop is still ungated** (`workitem:manage`) — unchanged from §31.
