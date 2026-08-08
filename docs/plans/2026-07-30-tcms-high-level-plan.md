# TCMS — Test Case Management System: High-Level Plan

**Status:** Draft for review
**Date:** 2026-07-30
**Scope decisions locked in:** self-hosted, **multiple companies per instance** (§6s) · Spring Boot REST + React SPA · CI ingestion + Jira + notifications + Git traceability · dual step format (structured text **or** Gherkin) per case · teams + permission-based RBAC · multi-dimension classification · reports exportable to PDF/MD/HTML · reusable pre/post conditions with bulk assignment · optional review workflow · documentation space with deep-linked test cases · full versioning & audit history · kanban work board · org chart · local auth in v1 with external OIDC SSO in the integrations phase

---

## 1. Product Definition

A self-hosted test case management system serving **one or more companies from a single instance**, where a company is **a separate client** — a distinct organization whose data must be provably invisible to every other company on the instance. A "company" is therefore the top-level isolation boundary: its projects, teams, test assets, documentation, vocabularies, integrations and identity providers are its own, and nothing crosses (§6s). A single-company deployment is just the degenerate case — one company row — so nothing about the small install gets heavier.

**Access, in one sentence per tier:**

| Tier | Scope | Sees |
|---|---|---|
| **Instance Admin** | The instance | Everything, every company. The operator. Access into a client's content is logged in *that client's* audit trail (§6s). |
| **Company Admin** | One or more companies | Everything in the companies they are assigned to; nothing in any other. Assigned by an Instance Admin. |
| **Regular user** | Individual projects | **Only the projects they are explicitly assigned to** — not the company's other projects, and not their names. |

**A Project is a full client project** — an entire product or engagement under test ("Globex Online Banking", "Globex Mobile App"), not a feature area. Feature-level structure lives in the hierarchical **Feature** dimension (§6d: `Payments › Refunds › Partial refund`) and in the suite tree inside a project. This granularity is why the third tier above matters: a project grant hands someone an entire product's test assets, so company membership deliberately conveys no project visibility at all (§6b).

A user in two companies holds **independent grants in each** — Company Admin in one and Test Engineer in another is normal, and neither grant informs the other (§6b).

It owns the *manual and automated test asset lifecycle*: authoring cases, organizing them into suites and plans, executing runs, ingesting automated results, and reporting quality signals traceable back to requirements and forward to defects.

**Explicit non-goals for v1**
- Not a test *runner* — it does not execute automation, it ingests results.
- Not a CI server, and not a defect tracker — it records the defects testing produced and links them to an external tracker (Jira · GitHub · GitLab · Azure DevOps), rather than replacing one. With no tracker configured the local record and the board (§6l) are enough to work; they are not a workflow engine.
- Not public multi-tenant SaaS. Multiple companies, yes — but **provisioned by an instance administrator**, not by self-signup. No billing, no plans, no per-tenant custom domains, no tenant-level rate cards.
- Not cross-company anything. No shared test assets, no cross-company reports, no user-visible list of companies they don't belong to.
- Not a step-definition editor — we author and own the Gherkin *specification*; the glue code lives in the automation repo.

### Comparable products
TestRail, Zephyr Scale, Xray, Qase, TestLink, Kiwi TCMS. The differentiator we target: **first-class automation ingestion + requirement-to-defect traceability in one self-hosted Java artifact**, without per-user SaaS licensing.

---

## 2. Domain Model (conceptual)

The core aggregates. Detailed ERD is a follow-up document.

```
Instance (one deployment)
 └─ Company ─┬─ (slug, display name, branding, settings, status, storage quota)   §6s
             ├─ IdentityProvider[]        (company-scoped SSO config, §6r)
             ├─ Team[]                    (company-scoped, never shared, §6b/§6k)
             ├─ ClassificationDimension[] (company vocabularies, §6d)
             ├─ ApiToken[]                (company-scoped, §6b)
             └─ Project[] ──▼

Project ──────┬─ Folder/Suite (tree)
              │    └─ TestCase ──┬─ stepFormat: STRUCTURED | GHERKIN
              │                  ├─ automationStatus + automationTag + automationKey
              │                  ├─ CaseCondition[] (PRE | POST, ordered)
              │                  │     └─→ Condition (shared, project library)  OR  inline text
              │                  ├─ TestStep[]        (STRUCTURED: action/data/expected)
              │                  ├─ GherkinScenario   (GHERKIN: keyword-typed steps,
              │                  │     ├─ GherkinStep[]   docstring/datatable, Examples)
              │                  │     └─ ExamplesTable ─→ (same store as DataSet)
              │                  ├─ Parameter[] / DataSet
              │                  ├─ Attachment[]
              │                  ├─ CaseClassification[] ─→ ClassificationValue ─→ Dimension
              │                  │     (Test Type · Test Level · Feature · Phase)
              │                  ├─ Tag[]  (free-form escape hatch)
              │                  └─ CaseVersion[]  (immutable history)
              ├─ Requirement ────── (links to TestCase, external tracker key)
              ├─ TestPlanTemplate ── (named plan skeleton — selection query + config
              │                       matrix + exit criteria, no executions; §6v)
              ├─ TestPlan ──┬─ TestRun ──┬─ TestExecution (case × run)
              │             │            │    ├─ releaseVersion (denormalized from run)
              │             │            │    └─ StepResult[] + Evidence[]
              │             │            ├─ releaseVersion · buildNumber · commitSha
              │             │            └─ Milestone
              │             ├─ Configuration (env/browser/OS matrix)
              │             └─ Artifact[] (PLAN scope, §6n)
              ├─ Artifact[] (PROJECT scope — inherited read-only by plans)
              ├─ DocSpace ─→ DocPage (tree) ─→ DocBlock (stable ids)
              │                                     ▲
              │                    DocLink ─────────┘  (TestCase/Suite/Requirement → block)
              ├─ Defect ─┬─ created from a failed execution; local record always exists
              │           ├─ externalRef (tracker · key · url · syncedStatus · syncedAt)
              │           └─ surfaced as a DEFECT work item on the board (§6l)
              ├─ Condition (reusable pre/post condition library, §6f)
              ├─ Review (optional workflow over Case | Suite | Plan, §6g)
              └─ SharedStep (reusable step blocks)

User ──┬─ CompanyMembership ─→ Company     (a user may belong to several companies, §6s)
       ├─ TeamMembership ─→ Team ──┬─ company (owning company — teams never span companies)
       │                           ├─ parentTeam (org chart only — no permission cascade, §6k)
       │                           ├─ lead(s)
       │                           └─ (notification channel, default assignee pool)
       └─ RoleAssignment ─→ Role     (direct grant, scoped to Project | Company | SYSTEM)

Role ─ scope: SYSTEM | COMPANY | PROJECT · builtIn: bool · Permission[]
        Instance Admin (SYSTEM) · Company Admin (COMPANY)
        Test Lead · Test Engineer · Automation Service · Viewer (PROJECT)

Every company-owned table carries company_id, filtered globally (§6s).

Version    (Case | Suite | Plan | DocPage | Condition — immutable JSONB snapshot, §6j)
AuditEvent (append-only: actor, action, targets[], before/after, when)
```

### Key modelling decisions to make deliberately

| Decision | Recommendation | Why |
|---|---|---|
| Tenancy | **Shared schema, `company_id` on every company-owned table**, enforced by a globally-applied Hibernate filter plus a repository-layer mandatory predicate — not by developer discipline | One deployable, one connection pool, one migration run; isolation is a query concern, not an infrastructure one. Schema- or database-per-company multiplies the migration and connection cost for a product whose companies are trusted business units, not strangers. Full design in §6s. |
| Company scope of shared vocabulary | Teams, classification dimensions, IdP config, API tokens and doc spaces are **company-owned**; nothing is instance-shared except the permission catalogue and the seeded built-in roles | Cross-company sharing is the feature that makes isolation unprovable. Seeded vocabularies are *copied* into a new company, not referenced. |
| Versioning | Immutable JSONB snapshot per version of Case / Suite / Plan / DocPage; a run pins the version it executed; separate append-only audit log | Otherwise historical run reports silently change when a case is edited — the #1 thing tools get wrong. Full design in §6j. |
| Shared content (conditions, steps) | **Reference while authoring, snapshot at version-pin time** | Live cases stay DRY and propagate library edits; executed history stays immutable. One mechanism, used by both conditions (§6f) and shared steps. |
| Test case vs execution | Separate entities; `TestExecution` is the case-in-a-run instance | Allows same case in many runs, different configs, per-run assignee and status. |
| Suite/folder tree | Materialized path or closure table, not adjacency-list recursion | Tree reads dominate; need fast subtree queries for "run this folder". |
| Automated vs manual | Same `TestCase` entity with an `automationStatus` enum + optional `automationTag` and `automationKey` | Unified reporting; one case is the manual script *and* the automation contract. See §6c. |
| Step format | Per-case `stepFormat` (STRUCTURED \| GHERKIN), project-level default; **both are first-class, neither is a conversion of the other** | A case authored in Gherkin stays Gherkin. Lossy auto-conversion between formats is the trap — see §6a. |
| Gherkin storage | Parsed AST persisted relationally (steps as rows, keyword-typed) **plus** the canonical `.feature` text | Relational rows give per-step execution results, search, and step reuse analytics; the text blob guarantees lossless round-trip to Git. |
| Statuses | Configurable per project, seeded with Passed/Failed/Blocked/Skipped/Retest/Untested | Teams always want a custom status; retrofitting it is painful. |
| Custom fields | Typed custom-field definitions per project, EAV-with-typed-columns | Every TCMS buyer asks for this within a month. |
| Soft delete | Everywhere on user-authored content | Accidental suite deletion is unrecoverable otherwise. |

---

## 3. Functional Scope by Phase

### Phase 1 — Core (MVP, target: usable by one team)
- **Company foundation — schema, proof and provisioning script** — `company_id` on every company-owned table, the Hibernate filter fed from `CompanyContext`, company-scoped uniqueness constraints, the `COMPANY` role scope, the storage key prefix, `company_membership`, the `provision-company` CLI command, and **the two-company isolation test harness running in CI from day one**. **No company management UI and no switcher** — those are the last phase. The harness is here because a discriminator that has only ever held one value is an untested claim (§6s).
- **Auth & users** — local accounts, BCrypt, JWT session (carrying the active company from day one, always the default), user lifecycle (invite, deactivate). External SSO is deliberately **not** here; it arrives with the integrations (§6r).
- **Teams** — create teams, manage membership, nominate team leads; a user may belong to and lead multiple teams.
- **Roles & permissions** — Instance Admin / Company Admin / Test Lead (per project) / Test Engineer / Automation Service / Viewer; permission-based enforcement; role assignment to a user *or* a team, scoped per project, per company, or system-wide. The `COMPANY` scope resolves correctly from M0.5 — it is assignable by DB insert until the assignment UI learns it in M6. See §6b.
- **Projects** — create, archive, per-project settings, grant access by team or individual; a project always belongs to exactly one company (the default one, in v1).
- **Test case repository** — folder tree, CRUD cases with **either** structured steps (action / test data / expected result) **or** Gherkin steps (Given/When/Then/And/But), preconditions, priority, type, tags, attachments.
- **Gherkin authoring** — syntax-aware editor with keyword autocomplete, server-side parse/validation via the official Gherkin parser, Background and Scenario Outline support, `.feature` preview.
- **Pre/post conditions** — per-case ordered lists, inline or referenced from a project-level reusable `Condition` library, with **bulk attach to a multi-case selection** and suite-level inheritance. See §6f.
- **Reusable step & condition library** — indexed typeahead search (sub-100ms), usage-ranked, with duplicate warning at creation. See §6h.
- **Review workflow (optional)** — Draft → In Review → Approved for cases, suites, and plans; off by default, per-project opt-in. See §6g.
- **Rich text + attachments** — sanitized HTML, file storage abstraction (local FS first).
- **Test plans & runs** — assemble cases into a plan and run, assign testers or teams, execute with per-step results and comments.
- **Plan templates & cloning** — a reusable `TestPlanTemplate` (a named, saved plan skeleton) plus **clone** for cases, plans and runs. See §6v.
- **Evidence capture** — attach screenshots, logs, videos, and files at *either* the step or the execution level, with paste-from-clipboard and drag-drop. Evidence is immutable once a run is closed and always carries author + timestamp.
- **Automation status** — mark any case as Manual / Automated / To Be Automated / Not Automatable, with an automation tag and key when available. See §6c.
- **Classification** — Test Type, Test Level, Feature tree, Execution Phase dimensions + free tags. See §6d.
- **Documentation space** — hierarchical pages with stable block anchors, test cases linked to specific points, bidirectional coverage markers. See §6i.
- **Test artifacts** — an artifact register at both project and test-plan scope (files, doc references, external links, pinned reports), with plan-level inheritance of project artifacts. See §6n.
- **Execution history & statistics** — case pages show a pass/fail timeline attributed to the version executed, with version filter and change markers; suite pages show **pass % per execution**, versioned; plan pages show **current state from the latest execution** with coverage, per-config matrix, blockers and exit criteria. See §6p.
- **Repository status dashboard** — the project landing page: every case's authoring state (Draft / In Review / Approved / Deprecated) crossed with derived health signals (outdated, stale approval, never executed, unlinked), every number a link into a pre-filtered, bulk-actionable case list. See §6o.
- **Basic reporting** — run progress, pass/fail breakdown, per-tester progress, plus the **aggregate execution report** across many runs, all **exportable to PDF / Markdown / HTML**. See §6e.
- **Search & filter** — one search over test id (instant jump), name, feature (with descendants), release version, and every other facet; composable filters, shareable URLs, saved searches. See §6q.
- **Release version** — `releaseVersion` / `buildNumber` on runs, denormalized onto executions; enables per-release filtering, pass rates, and release-to-release regression comparison. See §6q.
- **Versioning & history** — immutable versions of cases, suites, plans and doc pages with type-appropriate diffs and restore, plus an append-only audit log of every action (who / what / when). See §6j.

### Phase 2 — Automation & Integration
- **CI result ingestion** — REST endpoint accepting JUnit XML, TestNG XML, Allure results, Cucumber JSON. Matched to cases via `automationKey`; unmatched results auto-create cases in a quarantine folder (configurable).
- **Cucumber step-level results** — Cucumber JSON carries per-step outcomes, so a Gherkin case ingested from CI lands in the *same* per-step result shape a manual tester produces. Automated and manual runs of the same case become directly comparable.
- **`.feature` export / import** — generate canonical feature files from Gherkin cases (grouped by suite, tags preserved) for the automation repo; import existing `.feature` files into the repository. Direction of truth is configured per project, not bidirectional — see §6a.
- **API tokens** — per-user and per-service tokens for CI.
- **External identity providers (SSO)** — OIDC login against AWS Cognito, Microsoft Entra ID, Google Workspace, Okta, Keycloak, or generic OIDC, alongside retained local accounts. Account linking, JIT provisioning, IdP group claims mapped to roles/teams. Grouped here because it shares the OAuth client, credential-encryption and consent machinery with the integrations below. See §6r.
- **Issue tracker integration — Jira · GitHub Issues · GitLab Issues · Azure DevOps (Boards)** — all four are M4 scope, behind the one `IssueTracker` port (§5). Link cases ↔ issues, one-click defect creation from a failed step with prefilled reproduction, status sync-back via webhook or poll, **back-link written on the tracker side**, and **inline status hover-cards** wherever an issue is referenced. See §6l for the TCMS-side defect view.
- **Notifications** — Slack/Teams incoming webhooks + SMTP. Events: run completed, run failed threshold, case assigned, defect created.
- **Git traceability** — associate a run with commit SHA / branch / PR; link cases to requirements.
- **Requirements & coverage matrix** — requirement → cases → last result → open defects.
- **Org chart** — team hierarchy with leads, members, owned projects and live workload; drill-down to team and person views. See §6k.
- **Kanban board** — personal, team and project boards with priority, deadlines and WIP limits; test executions, reviews and doc/case maintenance tasks side by side. See §6l.

### Phase 3 — Scale & Insight
- **Flaky test detection** — result history per automation key, flake score, quarantine flag.
- **Trend dashboards** — pass rate over time, MTTR on failing cases, execution time trends, coverage drift.
- **Bulk operations & import/export** — CSV/Excel import, TestRail/Xray migration importers.
- **Shared steps & parameterized cases** — data-driven case execution; Scenario Outline `Examples` and structured-case DataSets share one mechanism.
- **Step phrase library** — catalogue of Gherkin step phrases already in use, with autocomplete and near-duplicate detection ("you have 4 variants of `I log in as ...`"). Prevents step-definition explosion, which is the main way BDD suites rot.
- **Undefined-step detection** — compare authored Gherkin against the automation repo's step-definition inventory (Cucumber dry-run JSON upload) and flag scenarios with no matching glue.
- **Milestones & release readiness** — gate view per milestone.
- **SAML 2.0** — for ADFS and older enterprise IdPs. A genuinely separate code path from the OIDC lane delivered in the integrations phase (§6r); build on demand.
- **SCIM provisioning** — push-based user/group sync from the IdP, replacing JIT provisioning for organizations that want it.
- **Webhooks out** — generic outbound webhooks for custom automation.
- **Public REST API + OpenAPI** — first-class, versioned, documented.

### Phase 4 — Multi-company activation (last phase, and deliberately small)

Everything before this point runs on one company, on a schema that has been multi-company-correct and isolation-tested since M0. This phase only adds the surface a *second* company needs. There is no company management UI — companies are provisioned by script at onboarding (§6s) — so what's left is roughly a week:

- **Company switcher & routing** — a company picker in the app bar for users who belong to more than one; the active company lives in the session and in every URL (`/c/{slug}/...`); switching re-issues the session token and clears the permission cache. Users never see a company they don't belong to, including in search, mentions, assignee pickers, notifications and the org chart.
- **Invitation & membership flow** — invite by email into a company; acceptance grants **Viewer with no project access**, and any further capability is an explicit grant (§6b). The invite response never reveals whether the address already has an account (§6s). Accept, decline, remove-from-company.
- **`COMPANY`-scope assignment UI** — the existing role-assignment screen learns the company scope, so an Instance Admin appoints a company's administrators without a DB insert. The one lifecycle operation frequent enough to deserve a UI.
- **Per-company identity** — email-domain → company routing on the login screen, per-company IdP selection, claim→role mapping confined to the company (§6r). The break-glass Instance Admin stays local and instance-level.
- **Suspend honored** — `SUSPENDED` refuses non-Instance-Admin access and returns 503 from ingestion with a clear reason.
- **Per-company branding** — logo, two brand colours driving the MUI theme, display name and favicon; company-branded login page and report headers (§6u). The theme plumbing and the contrast validation are the work; the settings screen is trivial.
- **Per-company metrics** — Micrometer company tags, so the operator can tell which company is loading the instance. Concurrency caps and quotas wait until there is real contention (§6s).

**Not in this phase, deliberately:** company CRUD screens, an instance operations console, archive state, storage-quota management, and any cross-company "all my work everywhere" view.

---

## 4. Tech Stack

### Backend
| Concern | Choice | Rationale |
|---|---|---|
| Language | **Java 21 (LTS)** | Records for DTOs, sealed types for status/result modelling, virtual threads for the ingestion path. |
| Framework | **Spring Boot 3.5.x** | Ecosystem depth for the integrations we need; Spring Modulith keeps modules honest. |
| Modularity | **Spring Modulith** | Enforces module boundaries in a monolith; gives us in-process events and a documented module graph without microservice overhead. Self-hosting demands one deployable. |
| Persistence | **Spring Data JPA / Hibernate 6** | Rich domain, heavy relational traversal. |
| Tenancy isolation | **Hibernate `@Filter` on `company_id`, enabled per session from a `CompanyContext`** (request-scoped), + Postgres RLS as a defence-in-depth option | Hibernate's `DISCRIMINATOR` multi-tenancy strategy fits exactly this shape. RLS is the belt to the filter's braces for self-hosters who want the database to refuse a leak even if the app forgets. §6s. |
| Database | **PostgreSQL 16** | JSONB for custom fields and raw CI payloads, full-text search, `ltree` as an option for the suite tree, recursive CTEs. |
| Migrations | **Liquibase** or **Flyway** | Flyway for plain SQL simplicity; Liquibase if we want DB-agnostic. **Recommend Flyway.** |
| Search | Postgres FTS + `pg_trgm`, dual GIN indexes; Elasticsearch only if volume proves it | Avoid a second datastore until measured; §6h/§6q. Behind a `SearchService` port so it's swappable. |
| Typeahead cache | Caffeine (in-process) for the hot step/condition set | §6h — avoids a DB round trip on the common empty-query dropdown open. |
| Cache/queue | **Redis** (optional, phase 2) | Ingestion job queue and rate-limiting. Start with in-process `@Async` + DB-backed job table; add Redis when justified. |
| Auth | Spring Security, JWT access + refresh; OIDC resource-server mode in the integrations phase | Local-first so self-hosting needs zero external deps. External IdPs (Cognito · Entra · Google · Okta · Keycloak) land with the other integrations — §6r. |
| Credential storage | Encrypted columns, key from config/KMS; never logged | Outbound integration tokens (Jira/Slack/Teams/Git) are a separate lifecycle from sessions (§6r). |
| API docs | springdoc-openapi | Generated OpenAPI feeds the frontend client and public API. |
| File storage | Abstracted `StorageService` — local FS default, S3-compatible (MinIO) adapter; **every key prefixed `company/{companyId}/`** | Self-hosters differ; don't hardcode. Evidence attachments are the bulk of stored bytes. The prefix makes per-company quota, export and deletion a directory operation instead of a table scan (§6s). |
| Gherkin parsing | `io.cucumber:gherkin` (official parser) | Never hand-roll a Gherkin parser; §6a. |
| Report rendering | Thymeleaf → HTML · custom GFM writer → Markdown · headless Chromium → PDF | One layout, three outputs; §6e. |
| Build | **Gradle (Kotlin DSL)** | Faster incremental builds, better multi-module ergonomics than Maven for this shape. |
| Testing | JUnit 5, Testcontainers (Postgres), RestAssured for API, ArchUnit for module rules | Testcontainers is non-negotiable for a JPA-heavy domain. |

### Frontend
| Concern | Choice | Rationale |
|---|---|---|
| Framework | **React 19 + TypeScript**, Vite | Data-grid-heavy app; SPA is the right shape. Vite (not Next.js) — no SEO or SSR need for an internal authenticated tool, and it keeps the artifact a static bundle. |
| UI kit | **MUI** (with MUI X DataGrid) | The grid, tree view, and date pickers we need exist; consistent look without a design team. Its runtime theme also gives us per-company branding (§6u) as a palette swap rather than a stylesheet fork — the theme is built from the active company's two brand colours, with contrast-safe foregrounds derived, not configured. |
| Server state | **TanStack Query** | Cache/invalidation for a CRUD-dominant app. |
| Client state | Zustand for the little that isn't server state | Redux is overkill here. |
| Forms | React Hook Form + Zod | Zod schemas generated from OpenAPI where possible. |
| API client | Generated from OpenAPI (`openapi-typescript` + typed fetch) | Keeps front/back contract enforced at compile time. |
| Editor | TipTap for rich-text steps | Sanitized on the server regardless. |
| Testing | Vitest + React Testing Library; Playwright for E2E | |

### Packaging & Ops
- **Single Docker image** for the backend with the built SPA served as static resources from Spring Boot — one container, one port. Self-hosters get `docker compose up`.
- **docker-compose.yml** shipping app + Postgres + (optional) MinIO.
- **Helm chart** as a phase-3 deliverable for k8s self-hosters.
- **Config** via environment variables (12-factor), documented in a single reference table.
- **Observability** — Actuator + Micrometer, structured JSON logging, optional OTel export. Keep it opt-in; self-hosters shouldn't need a Grafana stack to run this.
- **Backups** — documented `pg_dump` procedure + attachment directory; a built-in export endpoint in phase 3.

---

## 5. Architecture

```
┌─────────────────────────────────────────────┐
│  React SPA (static, served by Spring Boot)  │
└───────────────────┬─────────────────────────┘
                    │ REST /api/v1 (JWT)
┌───────────────────▼─────────────────────────┐
│           Spring Boot Modulith              │
│  ┌───────────────────────────────────────┐  │
│  │ tenancy — CompanyContext + filter     │  │
│  │ (every request enters through it)     │  │
│  ├────────┬────────┬──────────┬──────────┤  │
│  │identity│ project│repository│ execution│  │
│  ├────────┼────────┼──────────┼──────────┤  │
│  │ingestion│integration│documentation│rept│  │
│  └────────┴────────┴──────────┴──────────┘  │
│    ↑ domain events (Modulith) between all   │
└──────┬──────────────────────┬───────────────┘
       │                      │
  ┌────▼─────┐          ┌─────▼──────┐
  │PostgreSQL│          │ FS / MinIO │
  └──────────┘          └────────────┘
       ▲ outbound
  ┌────┴───────────────────────────────┐
  │ Jira · Slack/Teams · SMTP · Git API │
  └────────────────────────────────────┘
```

**Module boundaries (Spring Modulith packages):**
- `tenancy` — companies, company membership, provisioning, `CompanyContext` and the isolation filter. Depended on by everything; depends on nothing. Present from M0 with a single default company; grows a UI and a management API in phase 4 (§6s).
- `identity` — users, external identities & IdP config, claim→role mapping, roles, tokens, permissions
- `project` — projects, memberships, custom field definitions, statuses
- `repository` — suites, cases, steps, shared steps, versions, tags
- `execution` — plans, runs, executions, step results, configurations
- `ingestion` — CI payload parsing, automation-key matching, quarantine
- `integration` — Jira, notification channels, Git, outbound webhooks
- `documentation` — doc spaces, pages, blocks, links, backlinks, artifact register
- `reporting` — `ExecutionFact` read model, dashboards, coverage matrix, aggregate/trend reports, renderers
- `storage` — **added 2026-08-07 (§125, decision #51)**: the `attachment` row and the two blob ports (`EvidenceStore`, `ReportStore`) with their filesystem adapters. It is a **fourth foundation module** — `execution` and `reporting` both need bytes back synchronously inside a request and a download cannot be an event — and `ArchitectureRules.FOUNDATION_MODULES` and `ModuleStructureTest`'s list changed to say so
- ~~`shared` — storage, audit, attachments, search~~ → **`shared` — audit, search.** Storage and attachments left it before it was built, and the reason is the bullet above rather than tidiness: a foundation module every other module may depend on **must not be a grab bag**. Bundled as originally written, making storage reachable from everywhere would have made audit and search reachable from everywhere too, which is how a `shared` module becomes the place layering goes to die. `storage` earns the edge by depending on `tenancy` alone and holding no domain concept; `audit` and `search` would not, so what is left of `shared` stays a normal module

**Cross-module rule:** modules communicate via published domain events and exposed API packages only. ArchUnit + Modulith verification tests enforce it in CI. This is what keeps the monolith from rotting and preserves the option to split `ingestion` or `reporting` out later.

**Cross-company rule:** a domain event carries the `companyId` of its origin and is only ever handled within that company's context. An async handler that loses the company context and runs unfiltered is the realistic way a multi-company instance leaks — so the event envelope carries it explicitly and the handler re-establishes `CompanyContext` before touching a repository, rather than inheriting from a thread-local that may have been recycled (§6s).

### Integration architecture notes
- **CI ingestion** must be async and idempotent. Endpoint accepts the payload, persists it raw (JSONB/blob), returns 202 with a job id; a worker parses and applies. An idempotency key per upload prevents duplicate runs from CI retries.
- **Issue trackers behind an anti-corruption layer** — one `IssueTracker` port with **four M4 adapters: Jira, GitHub Issues, GitLab Issues, Azure DevOps Boards**. Building four at once is the decision that keeps the port honest: a port with a single implementation always leaks that implementation's vocabulary, and Jira's is the least representative of the four. Anything beyond these (Bugzilla, Redmine, Linear) is an adapter, not a project.
  - **The port's contract is narrow on purpose:** `createIssue(defect) → externalRef` · `fetchStatus(externalRef[]) → status[]` · `writeBackLink(externalRef, executionUrl)` · `onWebhook(payload) → statusChange[]`. Nothing about workflows, sprints or custom fields crosses it.
  - **Back-link on the tracker side is part of filing, not an extra feature.** Creating an issue also posts a comment (or link field) on the tracker pointing at the failed execution. Without it, traceability only works from inside TCMS, and the developer who receives the bug has no route back to the evidence — which is the half that makes the integration worth having.
  - **Status hover-cards** — anywhere an issue key is rendered (execution page, defect board, coverage matrix, run summary report), hovering resolves title, status, assignee and updated-at from a **short-TTL cache**, never a synchronous call to the tracker on page render. A cold or failed lookup shows the key with a neutral "status unavailable" state; it must never block or error the page it sits on.
  - **Per-project status mapping** (`tracker status → open/closed + board column`) with a documented default per adapter, because no two Jira workflows agree.
- **Notifications** driven off domain events, per-project channel config, with a delivery-attempt log. Never let a failing Slack webhook fail a test run transaction.

---

## 6. Cross-Cutting Concerns

- **Security** — server-side authorization on every endpoint (project membership + role), input sanitization for rich text (OWASP Java HTML Sanitizer), attachment type/size limits and virus-scan hook, rate limits on ingestion, secrets never logged, CSRF strategy documented for the JWT model, **federated-identity hardening per §6r** (state/PKCE verification, issuer + `token_use` validation, no auto-link on unverified email, integration credentials encrypted at rest), **and company isolation per §6s** — company resolved server-side from the session and membership, never trusted from a request parameter; every company-owned query filtered; ids are UUIDs so a guessed id from another company is not reachable *and* not enumerable.
- **Performance targets** — test case list of 50k cases paginated under 300ms; ingestion of a 10k-result JUnit XML under 30s; run execution page interaction under 100ms perceived; step/condition typeahead under 100ms p99 (§6h).
- **Data retention** — configurable purge of old run results and raw CI payloads; attachments are the storage hog.
- **i18n** — externalize UI strings from day one even if only English ships.
- **Accessibility** — WCAG 2.1 AA for the execution screens at minimum; testers live in them.

- **Company isolation** — one company's activity must not degrade another's. Ingestion jobs and report generation are queued with a per-company concurrency cap and a per-company storage quota, so a 500k-result nightly upload in one company doesn't starve another's run page (§6s).

The subsections below (§6a–§6u) detail the designs referenced throughout.

---

## 6a. Dual Step Format — Design

The goal: **one test case serves both a manual tester and an automation suite.** Gherkin is the format that makes this possible, because a Gherkin scenario is simultaneously a human-readable script and an automation contract. But forcing every case into Gherkin is wrong — exploratory, setup-heavy, and UI-detail cases read far better as structured steps. So the format is a per-case choice with a project-level default.

### The two formats

| | STRUCTURED | GHERKIN |
|---|---|---|
| Step shape | `action` · `test data` · `expected result` | `keyword` (Given/When/Then/And/But) · `text` · optional DocString / DataTable |
| Expected results | Explicit per step | Implicit — `Then` steps *are* the assertions |
| Parameterization | DataSet table bound to `${placeholders}` | `Scenario Outline` + `Examples` (same storage, same execution expansion) |
| Best for | UI-detail, setup-heavy, exploratory, one-off regression | Behaviour specs, anything targeted for automation, requirement-traceable flows |
| Automation join | `automationKey` → JUnit/TestNG/Allure result | `automationKey` **and** scenario name/tag → Cucumber JSON, with per-step mapping |

### Rules that keep this honest

1. **No lossy auto-conversion.** Offer a one-time, explicitly-confirmed "convert format" action that shows a diff of what will be lost (structured→Gherkin drops the per-step expected-result column into `Then` steps; Gherkin→structured discards keyword semantics). Never convert silently, never on save. A background auto-converter is the single fastest way to destroy an authored suite.
2. **Execution is format-agnostic.** `TestExecution` and `StepResult` are shared. Both formats render as an ordered list of steps a tester marks pass/fail with evidence. The Gherkin renderer shows the keyword as a styled prefix and treats `Then` rows as assertion rows; that is the *only* execution-time difference.
3. **Parse server-side with the real parser.** Use `io.cucumber:gherkin` for parsing and validation — never hand-roll. Store the AST relationally *and* keep the canonical serialized `.feature` text so round-tripping to Git is byte-stable.
4. **Reporting never branches on format.** Pass rate, coverage matrix, and flake detection query `TestExecution`/`StepResult`, which know nothing about step format.

### Sync direction — decide per project, never bidirectional

Three viable modes; a project picks exactly one:

- **TCMS is the source of truth** — cases authored here, `.feature` files generated and committed to the automation repo by a CI job. Developers never hand-edit generated features.
- **Git is the source of truth** — `.feature` files in the repo are imported on push (webhook), cases become read-only mirrors in TCMS. Manual testers execute against them but can't edit them.
- **Decoupled** — cases authored in TCMS, automation written independently, linked only by `automationKey`/tag. Least coupling, no sync machinery, and honestly the right default for most teams.

**Do not build a bidirectional merge.** Two-way sync of a structured document with no merge semantics produces conflicts nobody can resolve, and it is where every "BDD sync" integration in this product category has failed. Mode is a project setting, enforced by making the non-authoritative side read-only in the UI.

### Impact on the plan
- M1 grows: two editors, the Gherkin parser, format-aware rendering. Estimate moves 3–4 → **4–5 weeks**.
- M3 gains real value: Cucumber step-level ingestion is what makes the shared case pay off.
- `.feature` export/import is M4 scope, and only if a project picks a non-decoupled sync mode.

---

## 6b. Teams, Roles & Permissions — Design

### Shape

**Four concepts, kept separate:**

- **Company** — the isolation boundary (§6s). Everything below belongs to exactly one company. A user must be a member of a company before any grant inside it means anything.
- **Team** — a durable group of people ("QA Platform", "Payments Squad"). Teams exist independently of projects and are reused across them — **but never across companies**.
- **Role** — a named bundle of permissions. Scoped `SYSTEM` (instance-wide), `COMPANY`, or `PROJECT`.
- **RoleAssignment** — grants a Role to a **User or a Team**, on a **Project, a Company, or system-wide**. This is the only way anyone gets any capability.

Granting to a team is the point of the feature: add someone to "Payments Squad" and they inherit access to all six projects that squad works on, without touching six membership lists. Offboarding is one removal.

### Effective permissions

```
effective(user, project) =
    ∅  unless user is a member of project.company                       ← gate, not a union term
      ⋃ permissions of roles granted directly to user on project
    ⋃ ⋃ permissions of roles granted to any team the user belongs to, on project
    ⋃ ⋃ permissions of roles granted to user or their teams on project.company   (COMPANY scope)
    ⋃ ⋃ permissions of roles granted to user or their teams at SYSTEM scope
```

**Company membership is a precondition, not a permission source.** A non-member resolves to the empty set no matter what stale grants exist, so removing someone from a company revokes everything in one row-delete without hunting down per-project assignments. It is the one place the model is *not* a pure union, and that asymmetry is deliberate: it makes offboarding provable.

**COMPANY-scope grants apply to every project in that company, present and future** — which is precisely what makes Company Admin (below) work, and precisely why nothing else should be granted at that scope casually.

**Company membership grants no project visibility whatsoever.** This is the third access tier and it must be stated explicitly, because it is the rule most easily lost in implementation: a regular user sees **only the projects they hold a grant on**. Not the company's project list, not project names in a picker, not counts on a dashboard, not results in search. Being a member of Acme means you may *authenticate into* Acme; it means nothing about what you can see inside it.

So there are two independent gates, and both must hold before any row is visible:

```
company_id filter (§6s)   →   is this row's company one I'm a member of?
project grant (§6b)        →   do I hold any role on this specific project?
```

Consequences that need building rather than assuming:

- **The project list endpoint returns granted projects only** — for regular users. **A Company Admin sees every project in their company, present and future, with no per-project grant required** (confirmed): that is the point of the COMPANY scope, and a client's own administrator being unable to see one of their products would be indefensible. Instance Admins get all, in every company.
- **Every project picker, filter dropdown, assignee list, cross-project search and dashboard tile is built from the same granted-project set**, resolved server-side. A UI that requests "all projects in my company" and filters client-side is the defect this rule exists to prevent.
- **A wrong-project URL 404s, not 403s** — mirroring the company rule, because a 403 confirms the project exists. Applies to project keys and display keys alike, since `TC-1` is trivially guessable in a way a UUID is not — which is also why display keys never appear as API path identifiers (§6t).
- **Cross-project reports and boards are scoped to granted projects**, so an aggregate report (§6e) run by a regular user silently covers less than the same report run by a Company Admin. The report header must state the scope it actually covered, or two people will compare incomparable numbers.

**Union of allows, with no explicit deny.** Deny rules require precedence ordering, and precedence ordering makes "why can't Ana edit this case?" unanswerable without a debugger. If a team grants too much, the fix is a narrower role — not a deny override. This is a deliberate simplification and worth defending.

Resolution is hot-path (every request), so **it happens once at token issue and travels in the JWT** — see "Permissions in the session token" below. Where a grant set must still be resolved server-side (API tokens, scheduled jobs, the `prjOverflow` case), cache it per `(user, project)` with invalidation on any role/team/assignment/company-membership change. Cache keys **must include the company id** — a cache keyed on `(userId, projectId)` alone is correct, but one keyed on a project *slug* or a short name is not, since those are only unique within a company.

### Permission catalogue

Format `resource:action`, checked individually — roles are just bundles, and nothing in the code ever checks a *role* name.

| Area | Permissions |
|---|---|
| Repository | `suite:manage` *(create, rename, move, reorder)* · `suite:delete` *(folder + subtree — Lead+)* · `case:read` · `case:create` · `case:edit` · `case:delete` · `library:manage` *(shared conditions & steps)* |
| Review | `case:comment` *(comment only — Viewer+)* — then — `case:review` · `suite:review` · `plan:review` *(give feedback)* — distinct from — `case:approve` · `suite:approve` · `plan:approve` *(final sign-off)* |
| Execution | `plan:create` · `plan:edit` · `plan:delete` · `run:create` · `run:execute` · `run:close` · `result:submit` · `result:amend` · `evidence:attach` · `evidence:delete` |
| Defects & work | `defect:create` · `workitem:manage` *(kanban, §6l)* |
| Reporting | `report:view` · `report:export` |
| Documentation | `doc:read` · `doc:edit` · `doc:publish` |
| Artifacts | `artifact:read` · `artifact:edit` · `artifact:delete` *(project-scope edit is Lead-only; sign-off artifacts are write-once)* |
| Project | `project:read` · `project:audit` *(this project's history — Lead+)* · `project:settings` · `project:members` · `integration:manage` |
| Company *(COMPANY scope)* | `company:read` *(granted to every member)* · `company:settings` · `company:members` · `company:teams` · `company:vocabulary` · `company:idp` · `project:create` · `token:issue` · `audit:view` *(company-wide log)* — all implicitly bounded to the company they are granted on |
| System *(SYSTEM scope only)* | `company:create` · `company:delete` · `company:suspend` · `user:manage` · `role:manage` · `system:settings` · `instance:audit` |

**Three permissions exist because the plan described features without governing them:** `defect:create` (filing defects is in the Test Engineer description and Jira integration creates them), `workitem:manage` (the kanban board had no permission model), and `library:manage` (editing a shared condition propagates to every case referencing it). All three are **Test Engineer and up** — see the note on informed consent below.

**Three permissions split existing ones where the boundary was wrong:** `suite:delete` out of `suite:manage` (deleting a subtree is the most destructive repository action, so it sits with leads while contributors keep organising the tree), `project:audit` out of `audit:view` (without it a Test Lead could not read their own project's history — an oversight), and `case:comment` below `*:review` (so a Viewer, including the client themselves, can leave feedback without any write access).

**`library:manage` is gated by an impact modal, not by role.** Editing a shared condition or step shows a confirmation listing the affected cases before the change commits. The control is *informed consent*, not authority — a permission gate here would route routine maintenance through a lead for no safety gain, since the editor is the person who knows whether the change is right.

**`report:export` is granted wherever `report:view` is**, Viewer included: if you can read it, you can export it. The two stay separate in the catalogue so a company can clone a role that drops export, but the built-ins grant them together. §6e's note about exports leaving the access-control boundary is documentation of a risk, not a default restriction.

The split of the old "System" area into **Company** and **System** is the load-bearing change for multi-company. `team:manage`, `project:create`, `token:issue` and `audit:view` were instance-wide capabilities; they become company-scoped, so a Company Admin can create projects, teams and CI tokens freely without gaining anything outside their company. Only company lifecycle, global user records, the role catalogue and instance settings stay at `SYSTEM`.

Two distinctions worth noting: `result:submit` (record your own result) is separate from `result:amend` (change someone else's — a lead capability), and `case:approve` exists so review workflows are possible later without a schema change.

### Built-in roles

| Role | Scope | Intent |
|---|---|---|
| **Instance Admin** | SYSTEM | **The instance operator, with access to everything in every company.** Provisions and suspends companies; manages global user records and the role catalogue; instance settings and instance audit. **Assigns each company its administrators — the main thing this role does day to day.** At least one always exists; the last one cannot be removed or demoted, and it must be able to authenticate locally (§6r break-glass). Because companies are separate clients, this role's reach into client content is a disclosed, audited capability — see the operator-access note in §6s. |
| **Company Admin** | COMPANY | **Full control of the company/companies they are assigned to, and nothing outside them.** Everything a Test Lead can do, on *every* project in that company, plus create projects, manage the company's teams and members, its classification vocabularies, its integrations and its IdP config, and issue its API tokens. Appoints Test Leads within the company. Cannot see, name, or reach any other company. Assigned by an Instance Admin. |
| **Test Lead** | PROJECT | **Full control of the project(s) they are assigned to** — everything a Test Engineer can do, plus delete others' cases, delete suites and plans, amend others' results, close runs, approve items, read the project audit trail, and manage project settings, members and integrations. Effectively Company Admin scoped to one project. Assigned by a Company Admin (or an Instance Admin). |
| **Test Engineer** *(the working contributor role — granted explicitly, never by default)* | PROJECT | **Create and edit test suites, test cases, test plans; create and execute runs; submit results with evidence; review any item and leave review feedback; file defects; manage kanban work items; maintain the shared condition/step library; view and export reports.** Cannot give final approval, delete others' content, amend others' results, close runs, delete a suite subtree, read the project audit trail, or change project settings. |
| **Automation Service** | PROJECT | Non-human, attached to API tokens rather than people. Deliberately the smallest role in the product, because a leaked CI token is the realistic breach: `case:read` · `run:create` · `result:submit` · `evidence:attach` · `case:create` **restricted to the quarantine folder**. Everything else denied — it cannot edit or delete a real case, close a run, or read reports. |
| **Viewer** | PROJECT | Read-only, **plus commenting (`case:comment`) and report export**. Built for the stakeholder — often the client themselves — who reviews test coverage without writing any of it. |

### Multiple leaderships — a consequence of the model, not a special case

**One user can be Test Lead of several teams and several projects simultaneously, and Company Admin of several companies.** This needs no special support because `RoleAssignment` is a many-to-many edge carrying a scope and a target — Ana can hold Company Admin on *Acme*, Test Lead on *Globex Online Banking* and *Globex Partner Portal*, Test Engineer on *Globex Mobile App*, and nothing at all in *Initech*. Permissions are always resolved **per project, within a company**, never globally, so her elevated rights in Acme grant her nothing in Globex.

*(Note the granularity: a project is a whole client product or engagement — "Globex Online Banking" — not a feature area. "Payments" or "Refunds" is a **Feature** value (§6d) or a suite folder inside such a project, never a project of its own. This matters for access control: a project grant hands someone an entire product's test assets, which is why company membership deliberately grants no project visibility at all.)*

This is the shape the multi-company requirement asks for directly: **the Instance Admin assigns administrators per company, and each of them administrates only the companies they hold a grant on.** There is no "administrator of all companies" role short of Instance Admin, and holding Company Admin on three companies is three rows, not a special tier.

Three distinct notions of "lead" that should not be conflated:

| | **Company Admin** (company role) | **Test Lead** (project role) | **Team Lead** (team attribute) |
|---|---|---|---|
| Scope | A company | A project | A team |
| Grants | Full control of every project in that company, plus company configuration, teams, members, vocabularies, IdP and tokens | Full control of that project's test assets | Manage the team's membership; default reviewer/assignee for the team's work |
| Held by | Any number of users, on any number of companies | Any number of users, on any number of projects | Any number of users, on any number of teams |
| Assigned by | Instance Admin | Company Admin or Instance Admin | Company Admin or Test Lead |

A user is commonly several at once — Company Admin of *Acme*, lead of the "Banking QA" team *and* Test Lead on the two client products that team owns — but the grants come from different places, and keeping them separate means reorganizing teams never silently changes who can delete test cases.

**Instance Admin is the only role with implicit access to everything**, and it's system-scoped. There is deliberately no "admin of all companies" shortcut; that's just Instance Admin.

**Instance Admin genuinely reaches into client content** — decided, and it is the pragmatic answer for a self-hosted instance whose operator has database access anyway: a role that couldn't read a client's test cases while its holder could run `psql` would be security theatre. Two things make it defensible rather than a quiet backdoor:

- **Operator access is audited in the client's own trail.** When an Instance Admin reads or changes something inside a company, the event lands in that company's audit log (§6j) marked as instance-operator access, not only in the instance log. A client asking "has anyone outside our team looked at this?" gets a real answer.
- **It is disclosed, not discovered.** The tier table in §1 states it plainly. For separate-client hosting, "the operator can see your data" is a contract term, and the product should not imply otherwise.

**Viewer is the default everywhere; Test Engineer is always an explicit grant.** Both the company invitation and a project assignment default to read-only, and someone becomes a contributor only when an admin says so. For separate-client hosting this is the right default — the cost of an unintended Viewer is nothing, while the cost of an unintended contributor is edits to a client's test assets — and it means the same default rule holds at both levels rather than needing an exception. The trade is one extra deliberate step per person joining a project, which is the correct place to spend it.

Test Engineer is nonetheless a *contributor* role, not a restricted one — once granted, it withholds only destructive and configuration capability. The full grant:

```
suite:manage  · case:read · case:create · case:edit · case:delete (OWN)
library:manage                                     ← shared conditions & steps
plan:create   · plan:edit
run:create    · run:execute · result:submit · evidence:attach · evidence:delete (OWN)
case:comment  · case:review · suite:review · plan:review    ← feedback, not sign-off
defect:create · workitem:manage
report:view   · report:export · project:read · doc:read · doc:edit
artifact:read · artifact:edit (plan scope only)
company:read
```

Three separate lines divide Test Engineer from Test Lead, and it's worth naming them individually:

1. **Creative vs destructive** — a Test Engineer is never blocked from *producing* test assets, only from unmaking **someone else's**. Deleting their own case or evidence is fine (`OWN` below); deleting another's, deleting a suite subtree, amending another's result, and closing a run are not.
2. **Review vs approve** — see below.
3. **Content vs configuration** — project settings, members, integrations and the project audit trail are Lead-only.

**Ownership caveat:** `case:edit` applies to any case in the project, but *destructive* actions are additionally gated by ownership — a Test Engineer may delete evidence they attached and delete a case they authored, **in any state**. Authorship confers full control of that case; soft delete everywhere (§2) keeps it recoverable, and the audit log records who removed what. Implement as an `OWN` qualifier on the permission check resolved against `createdBy`, not as separate `case:edit:own` permissions.

### Review is not approval

The distinction that makes the Test Engineer role work: **anyone can review, only Company Admin and Test Lead can approve.** There are three tiers, not two, because a read-only stakeholder still has something useful to say:

| | `case:comment` — Viewer and up | `*:review` — Test Engineer and up | `*:approve` — Test Lead and Company Admin only |
|---|---|---|---|
| Can do | Read an item in full and leave threaded comments | Everything a commenter can, plus formally participate in a review: mark "looks good to me", request changes, reject | Everything a reviewer can, plus move the item to its final approved/Done state |
| Effect | Informational. Recorded and visible, but carries no review standing | Advisory. Recorded, visible, part of the audit trail, and countable toward a peer-review prerequisite — but does not change the item's status | Authoritative. Transitions the item and stamps the sign-off. |
| Built for | The stakeholder — often **the client themselves** — who reviews coverage without writing any of it | The peer who works in the product every day | The person accountable for "this is Done" |

This is the peer-review-then-sign-off model, and it's the right shape: peer review is where the actual quality feedback comes from — a Test Engineer who works in the feature every day catches more than a lead skimming 40 cases — while accountability for "this is Done" stays with someone answerable for it.

Practical consequences:
- **A Test Engineer's approval is recorded but non-binding.** The UI must show this honestly ("2 peer reviews · awaiting Test Lead approval"), not present a button that silently does nothing.
- **Peer review can be made a prerequisite** — a project may require N peer reviews before an item is even eligible for Lead approval. Off by default; valuable for teams that want the lead to be a rubber stamp on already-vetted work rather than the sole reviewer.
- **Rejection is available to reviewers**, not just approvers. Sending work back doesn't need authority; blessing it does.
- Applies uniformly to **cases, suites, and plans** (§6g).

**The six built-in roles are the whole role set in v1. They are seeded, immutable, and not cloneable — there is no custom-role creation at all.** Decided deliberately: the permission catalogue is designed so the built-ins cover the real tiers, and a role a client can edit is a role that has to be reasoned about in every support conversation, every audit and every upgrade. If a client genuinely needs "Test Engineer plus `run:close`", the honest answers are to grant them Test Lead or to revisit whether the built-in boundary is drawn correctly — both better outcomes than a proliferation of near-identical bespoke roles.

The *catalogue* is still permission-based rather than role-based (nothing in the code checks a role name), so adding clone-and-edit later is additive — a `role` table with `builtIn: false` rows and a designer UI, no change to enforcement. That stays a phase-3 option, on evidence of real demand rather than in anticipation of it.

### Permissions in the session token

**Decided: the JWT carries the caller's grants, so no request resolves permissions from the database.** This removes the class of bug where a permission check is skipped, mis-cached or resolved inconsistently between two code paths — the resolution happens once, at token issue, and every downstream check reads the same immutable input.

**What makes this cheap is the §6b decision that roles are immutable built-ins.** Because the six roles are seeded and not editable (see "Deliberate v1 limits"), `role → permission[]` is a **compile-time constant**, not data. So the token does not need to carry permissions at all — it carries *role assignments*, and expansion to the permission set is an in-memory lookup with no I/O:

```
claims:
  sub   user id (UUID)
  co    active company id            ← always present, §6s
  pv    permission version (int)     ← the revocation hook, see below
  sys   [roleId]                     ← SYSTEM-scope grants
  cmp   [roleId]                     ← COMPANY-scope grants, on `co`
  prj   { projectId: [roleId] }      ← PROJECT-scope grants, active company only
```

Team-derived grants are **flattened into `prj`/`cmp` at issue time** — the token carries effective assignments, not team memberships, so nothing downstream needs to re-walk team edges. This is the whole §6b resolution rule evaluated once.

**Three things this must not become:**

1. **Not the authority — a cache with an integrity check.** `pv` is a per-user counter, bumped on *any* change to a role assignment, team membership, company membership, user status or company status. Every request compares the token's `pv` against the current value from an in-process Caffeine cache (already in the stack, §4); a mismatch returns 401 with a re-auth hint and the refresh flow mints a fresh token. That check is **one integer comparison against a cached value — not a permission resolution** — so the hot path stays I/O-free while revocation stays prompt.

   Being explicit about the trade, because it is the real cost: **without the `pv` check, a revoked grant would stay live until the token expired.** That is precisely the "permission problem" this design is meant to prevent, so the epoch check is mandatory, not an optimization — and access-token TTL stays short (~10 minutes) as a second line of defence. A JWT carrying permissions and *no* revocation channel would be worse than resolving from the database.

2. **Not a client-side authority.** The SPA can decode the token, and it may use it exactly as it uses `GET /api/v1/me/permissions` today — to hide affordances. Hidden ≠ protected still holds; every action is independently checked server-side against the token-derived context.

3. **Not unbounded in size.** `prj` is scoped to the **active company only** (§6s), which bounds it naturally — but a Company Admin with 200 projects would still bloat every request header. Two mitigations: **COMPANY-scope grants are not expanded per project** (a Company Admin carries one `cmp` entry covering every project, present and future — the same reason that scope exists), and if `prj` exceeds a configured entry count the token sets a `prjOverflow` flag and those grants resolve server-side per request. The common case is I/O-free; the pathological case degrades to today's behaviour instead of failing.

Company switching re-issues the token (already required by §3 Phase 4), which is what keeps `co` and `prj` consistent — and is why `prj` may safely omit other companies' projects.

### Enforcement

- **Server is authoritative, always.** Spring Security with a custom `PermissionEvaluator`; endpoints annotated `@PreAuthorize("hasPermission(#projectId, 'PROJECT', 'case:edit')")`. The evaluator reads the token-derived grant set (above) rather than querying, but the annotation contract is unchanged. Enforcement lives at the service boundary, not the controller, so ingestion and scheduled jobs pass through the same gate.
- **Non-JWT callers resolve the same way.** API tokens (§6b) and scheduled jobs have no session token, so they resolve their grant set server-side and populate the *same* context object. One `PermissionEvaluator`, one input shape, two ways of filling it — never two enforcement paths.
- **Frontend gets `GET /api/v1/me/permissions?projectId=` ** and uses it purely to hide affordances. Hidden ≠ protected; every hidden action is independently rejected server-side.
- **Row-level scoping** — a case query is always constrained by the projects the caller can read. Enforce in the repository layer via a mandatory project predicate, not by trusting callers to pass one.
- **Company scoping is separate from, and prior to, permission scoping.** The `company_id` filter (§6s) removes foreign rows from existence; the permission check then decides what the caller may do with the rows that remain. Two layers, both mandatory — because a permission bug inside a company is a privilege escalation, while a filter bug across companies is a data breach, and they deserve independent defences. A missing permission annotation must never be the only thing standing between two companies.
- **API tokens carry a role and a company**, never the full rights of the issuing user. A CI token should hold Automation Service in one company and nothing more, so a leaked token can't delete a suite *or* reach another company.
- **Every assignment change is audited** — who granted what to whom, when, in which company. Company-scoped grants and company membership changes are audited at both levels: in the company's own audit log and in the instance log, since "who made Ana an admin of Acme" is an instance-operator question.
- **ArchUnit test** asserting no service method mutating project-scoped data is reachable without an authorization annotation. Cheap, and catches the omission that causes the eventual CVE.

### Deliberate v1 limits
- No explicit deny, no role hierarchy, no per-suite or per-case ACLs. Project is the finest access boundary; company is the coarsest.
- **No custom roles at all in v1** — not even clone-and-edit. The six built-ins are the complete set; a client needing something between two tiers gets the higher one, or tells us the built-in boundary is wrong. Enforcement is permission-based regardless, so adding custom roles later is additive (phase 3, on demand). When it lands, custom roles must be **company-owned** — a client editing "Test Engineer" must never mutate a row another client reads.
- Teams may be **nested structurally for org-chart purposes (§6k), but permissions never cascade through the hierarchy** — a parent team's role grants do not flow to child teams. Structure is for people; access is explicit. This keeps the §6b resolution rule flat and answerable. Nesting is also confined to one company — a parent and child team always share a `company_id`, validated on reparent alongside the cycle check.

---

## 6c. Automation Status & Tagging — Design

Every case declares where it sits on the manual↔automated spectrum. This is the field the whole automation-coverage story is built on, and it is deliberately richer than a boolean.

### `automationStatus`

| Value | Meaning |
|---|---|
| `MANUAL` | Executed by a person. No automation intended. |
| `TO_BE_AUTOMATED` | Backlog — a person runs it today, automation is planned. |
| `AUTOMATED` | Automation exists and reports results into TCMS. |
| `NOT_AUTOMATABLE` | Deliberately excluded (exploratory, hardware-dependent, visual judgement). Requires a reason note. |

`NOT_AUTOMATABLE` matters as much as `AUTOMATED`: without it, every automation-coverage percentage is measured against an unreachable 100%, and the metric gets ignored. Coverage is reported as `AUTOMATED / (total − NOT_AUTOMATABLE)`.

`TO_BE_AUTOMATED` gives the automation backlog a queryable home — "show me high-priority cases awaiting automation, ordered by manual execution cost" is the report that justifies automation headcount.

### `automationTag` and `automationKey`

Two different fields, often confused:

- **`automationTag`** — the tag the automation suite uses to select this case: `@smoke`, `@regression`, `@TC-1042`, a TestNG group, a JUnit `@Tag`. **Optional, multi-valued, free-form.** For Gherkin cases these are the scenario's own tags and stay in sync with the `.feature` representation automatically. Its job is *selection* — "run everything tagged `@smoke`".
- **`automationKey`** — the single stable identifier the automation *result* carries back: FQCN#method, a Cucumber scenario URI+line, or an explicit annotation value. **Optional, single-valued, unique per company** — matching the display-key scope (§6t). Its job is *matching* — joining an incoming CI result to this case. Company scope means a CI job posts results without needing to know which project owns the case, and a case moved between projects doesn't change where its key resolves.

Both are optional because a case can be marked `AUTOMATED` before the key is known, and a team may run tag-based selection with no per-case key at all.

### Matching strategy for ingestion

Resolution order when a CI result arrives, first hit wins:

1. Exact `automationKey` match.
2. Explicit case-ID annotation embedded in the test name (`@TC-1042`, configurable regex per project).
3. Normalized full test name → previously-seen key (learned mapping table).
4. No match → quarantine folder, optionally auto-creating a case stub with `automationStatus = AUTOMATED`.

The learned-mapping table is what stops the ingestion from being brittle: once a human confirms "this test name means this case", the mapping persists across renames of everything except the test itself.

### Drift detection

The failure mode this design defends against: a case marked `AUTOMATED` whose automation silently stopped reporting. Flag any `AUTOMATED` case with **no ingested result in N days** (configurable) as **stale automation** on the coverage dashboard. Equally, flag cases receiving results while marked `MANUAL` — that's an unlinked automation the team doesn't know it has.

### Reporting this enables
- Automation coverage % per project/suite, honestly denominated.
- Automation backlog (`TO_BE_AUTOMATED`) sized and prioritized.
- Manual execution cost — time spent running cases that are flagged for automation.
- Stale-automation list.

---

## 6d. Classification — Dimensions, Not Tag Soup

A single flat `tags` list is how test repositories become unsearchable. `@payments`, `@api`, `@smoke`, and `@regression` are four *different kinds* of statement about a case, and collapsing them means you can never ask "how many API tests cover Refunds?" without string-matching conventions nobody enforced.

So: **a case is classified along several independent, project-configurable dimensions**, plus a free-tag escape hatch.

### Seeded dimensions

| Dimension | Cardinality | Vocabulary | Example values |
|---|---|---|---|
| **Test Type** | single | controlled, seeded | Functional · Performance · Security · Usability · Accessibility · Compatibility · Localization · Recovery |
| **Test Level / Layer** | single | controlled, seeded | Unit · Component · API · Integration · UI / E2E · Database · Contract |
| **Feature / Component** | **multi**, hierarchical | controlled, project-owned | `Payments` › `Refunds` › `Partial refund` |
| **Execution Phase** | multi | controlled, seeded | Smoke · Sanity · Regression · Acceptance · Exploratory |
| **Automation** | — | see §6c | `automationStatus` + `automationTag` + `automationKey` |
| **Free tags** | multi | uncontrolled | anything — the escape hatch |

**Test Type and Test Level are separate on purpose.** "Functional" and "API" are not alternatives — an API test is almost always functional, and a *non*-functional API test is a performance test hitting the API layer. Modelling them as one enum forces a false choice and is the specific mistake worth avoiding here. With both axes you can express "non-functional / performance / API" precisely, and report a real type×level coverage matrix.

**Feature is hierarchical and controlled.** Free-text feature tags degrade into `payments`, `Payments`, `payment`, `pmts` within a quarter. A project-owned tree, editable by Project Admins, with rename-propagates-everywhere, keeps it usable — and makes the requirement→feature→case→run→defect traceability chain actually joinable. Feature is also the dimension most worth surfacing in reports, since it maps to how the product is discussed.

### Implementation

- One generic mechanism: `ClassificationDimension` (name, cardinality, controlled?, hierarchical?) → `ClassificationValue` (tree-capable) → `CaseClassification` join. Seeded dimensions are just rows; a project can add its own (`Risk Level`, `Regulatory Scope`, `Browser`) without a schema change.
- Dimensions are **project-scoped in configuration but company-shared in vocabulary where sensible** — Test Type and Test Level should be consistent across a company's projects or cross-project reporting is meaningless. Recommend: Test Type / Level / Phase are **company-level** and locked; Feature is per-project. "Company-level" rather than instance-level is the multi-company correction (§6s): each company gets its own copy of the seeded vocabulary at provisioning time, editable by its Company Admin, because one company renaming "Compatibility" must not rename it for another. Cross-*company* reporting is a non-goal, so nothing is lost.
- Filtering is dimension-aware — the case list gets a faceted filter per dimension, not one tag box.
- **Gherkin interplay:** a Gherkin scenario's `@tags` map to dimensions on import where they match a known value, and fall through to free tags where they don't. On export, dimension values serialize back out as tags. This is the only sane way to keep `.feature` files and structured classification consistent.
- Free tags stay, because a controlled vocabulary you can't escape gets worked around with naming conventions in the title field.

### Reporting this enables
- Type × Level coverage matrix — "we have no security tests below the UI layer."
- Automation coverage sliced by feature — "Refunds is 20% automated, Checkout is 90%."
- Non-functional coverage gaps per feature.
- Run composition — what proportion of the nightly regression is UI vs API (a proxy for suite runtime and flakiness risk).

---

## 6e. Reporting & Export — Design

Reports must leave the tool. A test summary that only exists behind a login is useless for a release sign-off email, an audit file, or a client deliverable. Every report is exportable to **PDF, Markdown, and HTML**.

### Architecture: one model, three renderers

The mistake to avoid is writing each report three times. Instead:

```
ReportDefinition (type + parameters + filters)
        │
        ▼
  ReportModel  ← pure data: title, metadata, sections, tables, chart series
        │
   ┌────┴────┬─────────┐
   ▼         ▼         ▼
 HTML       MD        PDF        (+ CSV/XLSX for tabular reports)
```

A report is computed **once** into a serializable `ReportModel`, then handed to a renderer. Adding a fourth format later (DOCX, Confluence) is one renderer, not N report rewrites. The on-screen report view consumes the same model via JSON, so what you see is what exports.

### Renderers

| Format | Approach | Notes |
|---|---|---|
| **HTML** | Server-side template (Thymeleaf) → **single self-contained file** | CSS inlined, images/charts embedded as base64 data URIs. Must open correctly from a filesystem with no network — that's the whole point of the format. **Mermaid diagrams from doc-structured reports (§6i) are pre-rendered to inline SVG at generation time**, never left as a script tag: a self-contained file that needs a CDN to draw its diagrams is not self-contained. |
| **Markdown** | Direct from `ReportModel` — GFM tables, headings | Charts degrade to tables plus an optional linked PNG. **Mermaid passes through as a fenced ` ```mermaid ` block** — GitHub, GitLab and most wikis render it natively, so the source text is the better output here than an image. Ideal for pasting into a PR, a wiki, or a tracker comment. Keep it clean and diffable. |
| **PDF** | Render the HTML, then **Playwright/Chromium → PDF** | Reuses the HTML renderer entirely — one layout to maintain. Headers/footers with page numbers, report title, generation timestamp. |

**On the PDF engine choice:** the alternatives are OpenPDF/iText (imperative layout — a second layout implementation to maintain) or Flying Saucer (XHTML+CSS 2.1 only, no modern CSS, charts render badly). Headless Chromium costs ~300MB in the image and needs a process pool, but it renders exactly what the browser shows and it's the only option where the PDF and the on-screen report can't drift. **Recommend Chromium**, with the container running it as a sidecar-capable component so self-hosters with tight images can disable PDF and keep HTML/MD.

### Report catalogue (v1)

| Report | Contents |
|---|---|
| **Run summary** | Single run: status breakdown, per-tester progress, duration, failed cases with evidence thumbnails, defects raised |
| **Aggregate execution report** | **Many executions across many runs rolled into one document** — see below. The release/regression-cycle report. |
| **Test plan / release readiness** | Multi-run rollup against a milestone, gate criteria, open blockers |
| **Coverage matrix** | Requirement → cases → last result → open defects |
| **Automation coverage** | Per §6c — automated / to-be-automated / not-automatable, sliced by feature |
| **Classification matrix** | Type × Level grid per §6d |
| **Release comparison** | What passed in release X and fails in Y, per §6q — new failures, fixed cases, still-failing, newly-covered |
| **Repository health** | Lifecycle + health-signal snapshot per §6o, exportable for a release review |
| **Case export** | The suite itself as a readable document — the "send QA the test spec" deliverable |
| **Trend** *(phase 3)* | Pass rate, flakiness, execution time over time |

### Aggregate execution report — spanning many runs

The most-requested report in any TCMS, and the one with real design content: **"how did we do across this whole test cycle?"** — where a cycle is dozens of runs, several environments, both manual and CI-ingested results, over weeks.

**Selection.** The report's scope is a *query*, not a hand-picked list: any combination of milestone, test plan(s), date range, environment/configuration, suite subtree, classification facets (§6d), assignee or team, and automation status. Saved as part of the `ReportDefinition` so the same cycle report regenerates next release with one click.

**The central problem: one case, many results.** A case may be executed 15 times across the selection — twice manually, thirteen times by nightly CI, on three browsers, some passing, some not. What does the report say about that case? This must be an **explicit, user-chosen aggregation policy**, never an implicit default buried in a query:

| Policy | Result for the case | Use when |
|---|---|---|
| **Latest** | The most recent execution wins | Default. "Where do we stand right now?" |
| **Latest per configuration** | One result per environment/browser, shown as a matrix | Cross-platform release sign-off |
| **Worst** | Any failure makes it failed | Conservative release gates |
| **Best** | Any pass makes it passed | Only defensible when re-runs are legitimate fixes — offer it, but warn |
| **All** | Every execution listed chronologically | Audit trail, investigating a specific case |

Getting this wrong is how reports lie. Two teams reading "94% pass" can mean entirely different things, so **the chosen policy and the full selection criteria are printed in the report header**, not just recorded in metadata.

**What it contains:**
- Headline totals with the aggregation policy stated plainly, and *both* execution count and distinct-case count (they differ, and conflating them inflates numbers).
- Case × configuration result matrix, where a config dimension was selected.
- **Inconsistency callout** — cases that both passed and failed within the selection. This is the single most valuable section: it's the flaky-test and environment-instability signal, and it's invisible in any single-run report.
- Failure analysis grouped by suite, by feature (§6d), and by failure message similarity — clustering "the same failure 40 times" into one entry instead of forty rows.
- Manual vs automated split; first-attempt vs after-retry pass rate.
- Defects raised during the cycle, with current Jira status.
- Coverage of the selection against the documented spec (§6i) where doc links exist.
- Per-tester and per-team throughput.

**Version divergence.** Runs in the selection may have pinned *different versions* of the same case (§2). The report must show the version each result belongs to and flag divergence rather than silently blending them — otherwise you are averaging results for two different tests presented as one.

**Performance.** This is the report that touches millions of rows. It cannot be a live join over `TestExecution` at render time.
- Maintain a **read model** (`ExecutionFact`) in the `reporting` module — denormalized execution rows carrying case, run, plan, config, classification, automation status, result, timestamps, and case version — updated off domain events. Star-schema-ish, deliberately duplicating dimensions to avoid joins.
- Pre-aggregate per `(run, case)` and per `(plan, case)` incrementally as results land, so the aggregate report reads summaries, not raw facts.
- Index on the selection dimensions; partition `ExecutionFact` by time once volume justifies it.
- Always async (already the §6e contract) with progress reporting — a cycle report over 500k executions is a minute-scale job, and that's acceptable if the user can see it working.
- Cache generated output keyed by definition + data watermark; regenerating an unchanged cycle report should be instant.

**Trend variant.** The same read model answers "pass rate per week over the last quarter" and the flakiness scoring in phase 3 — building `ExecutionFact` in M2.5 is what makes phase 3 reporting cheap rather than a second data pipeline.

### Delivery mechanics

- **Async generation with a job record.** A 10k-case export must not block a request thread or die at the gateway timeout. `POST /reports/{type}:generate` → 202 + job id → poll or SSE → signed download URL. Small reports may complete inline as an optimization, but the async path is the contract.
- **Generated reports are artifacts** — stored, retained per policy, listed under the project with who generated them and with what filters. Release sign-offs get referenced months later.
- **Scheduled reports** *(phase 2)* — nightly run summary emailed or posted to the team's Slack channel, reusing the notification config.
- **Permissions** — `report:view` to see, `report:export` to generate a file. Separate because exports leave the access-control boundary entirely; some organizations want that gated even where viewing isn't.
- **Branding** — logo and report header, defaulting to the **company's** brand (§6u) with a per-project override. Trivial to build, and it's what makes the PDF client-presentable — which matters more when the companies *are* clients and the PDF is a deliverable they forward internally.
- **Determinism** — every export embeds generation timestamp, the filter set used, and the generating user. A report you can't reproduce is a report nobody trusts.

---

## 6f. Pre / Post Conditions — Design

A case carries two ordered lists: **preconditions** (state required before execution) and **postconditions** (cleanup / state to restore after). Each entry is either **inline text** (ad hoc, this case only) or a **reference to a shared `Condition`** from a project-level library.

The shared library is the point. "1. Log in as a standard user, 2. Navigate to Billing" is written once and attached to 200 cases; when the login flow changes, it's edited once.

### Condition entity

```
Condition
  ├─ title            "Log in as standard user"
  ├─ type             PRE | POST | BOTH
  ├─ body             rich text, or ordered steps for multi-step conditions
  ├─ parameters       ${role}, ${env} — bound per case at attach time
  ├─ usageCount       denormalized, shown before editing
  └─ version[]        immutable history
```

Conditions are **project-scoped** (confirmed — §6d's Test Type / Level / Phase vocabularies are company-scoped, but conditions and execution statuses are not: two different client products legitimately have different login flows and different status vocabularies), searchable, and foldered like cases. A condition may itself hold ordered steps — "Log in" is realistically three actions — so `body` is a step list, not just a string.

**Editing a shared condition is a Test Engineer action gated by informed consent, not by role** (§6b `library:manage`). Because an edit propagates to every case referencing it, the editor sees a confirmation modal listing the affected cases before the change commits. Routing routine library maintenance through a lead buys no safety — the person editing is the person who knows whether the change is right — but committing it blind does cause damage.

### The versioning interaction — the decision that matters

§2 already establishes that a run pins the case *version* it executed. Shared conditions break that guarantee unless handled deliberately: edit the "Login" condition and every historical run report silently changes.

**Resolution: reference while authoring, snapshot at pin time.**

- While a case is being authored, `CaseCondition` holds a **reference** — so library edits propagate, which is the entire value proposition.
- When a `CaseVersion` is created (and therefore when a run pins one), the conditions are **resolved and snapshotted** into that version, with the condition id + version recorded alongside the frozen text.
- Result: live cases stay DRY and propagate; executed history is immutable and reproducible.

This is the same reference-vs-copy problem shared steps have, and both must resolve it identically — one mechanism, used twice.

### Bulk assignment

The core requested workflow: **select N cases → attach a set of conditions → one click.**

- **Selection** comes from anywhere cases are listed — search results, a suite subtree, a filter by classification, or manual multi-select. "Select all matching this filter" must work across pagination, not just the visible page.
- **Modes**, explicitly chosen, never guessed:
  - **Append** — add to the end of the existing list (the default)
  - **Prepend** — insert before existing conditions (right for a login that must come first)
  - **Replace** — discard existing conditions of that type and set these
- **Ordering** is preserved from the order chosen in the dialog. Per-case manual reordering afterwards is allowed and survives later bulk operations.
- **Dedupe by default.** If a case already references "Login", appending "Login, Navigate to Billing" adds only the second. Duplicates are almost always a mistake; a checkbox allows them for the rare case where they aren't.
- **Preview before commit.** Show exactly how many cases will change, how many are already compliant, and how many are skipped (locked, under review, no permission). Mass edits without a preview are how people destroy a suite at 5pm.
- **Transactional and reversible.** The whole operation commits or rolls back as one unit, is recorded as a single `AuditEvent` with the case-id set, and is undoable from the audit log. Bulk detach and bulk reorder use the same machinery.
- **Permission** — `case:edit` on every case in the selection. Cases the caller can't edit are reported as skipped, not silently dropped, and never partially applied.

### Suite-level inheritance (recommended addition)

Bulk-apply is a point-in-time push: cases added to the suite *later* don't get the conditions. So also allow conditions attached to a **folder/suite**, inherited by every case beneath it, shown greyed on the case with an "inherited from *Billing*" label and an opt-out toggle.

Inheritance handles the standing rule ("everything under Billing requires login"); bulk-apply handles the retrofit and the irregular subset. Teams need both, and inheritance is what stops bulk-apply from being re-run forever.

### Format interplay

- **Gherkin cases:** preconditions map naturally onto `Background`. On `.feature` export, a case's preconditions serialize as the scenario's Background; on import, a Background becomes preconditions. Postconditions have no Gherkin equivalent — serialize them as a comment block and keep them authoritative in TCMS.
- **Execution UI:** preconditions render as a checklist *before* step 1, postconditions *after* the last step. Both are markable, and a failed precondition should offer **Blocked** as the natural result for the whole case — that is precisely what Blocked means, and wiring it here removes the most common source of miscategorized failures.
- **Postconditions don't fail a case by default.** A failed cleanup is flagged on the run (environment may be dirty) without turning a passed test red. Make that configurable per project.

---

## 6g. Review Workflow — Design

Test cases, suites, and plans can each go through an **optional** review before being considered authoritative. Optional is the operative word: enabled per project and per artifact type, off by default, and a project that never turns it on should see no extra UI at all.

### States

```
                    ┌── peer review (Test Engineer+) ──┐
                    │   comment · LGTM · request changes│
                    ▼                                   │
DRAFT ──submit──▶ IN_REVIEW ─────────────────────────────┘
                      │
                      │ approve  ── Admin / Test Lead ONLY ──
                      ▼
                  APPROVED / DONE ──edit──▶ DRAFT*
                      │
                   deprecate
                      ▼
                  DEPRECATED

  reject (any reviewer) : IN_REVIEW ──▶ REJECTED ──▶ DRAFT
```

**Only Admin and Test Lead can perform the final transition into APPROVED/Done.** Peer review by Test Engineers happens inside `IN_REVIEW` and accumulates on the item without moving it — the state machine has exactly one gate, held by `*:approve`.

`*` What an edit to an APPROVED artifact does is a project setting:
- **Re-review** — edits return it to DRAFT and approval must be re-earned (strict; right for regulated work).
- **Retain** — edits are allowed in place and approval sticks, with the approval marked *stale* and the diff since approval visible (pragmatic; right for most teams).

Recommend **Retain + stale marker** as the default, and Re-review as the opt-in for teams that need it. Forcing full re-approval on a typo fix is how review processes get abandoned.

### Scope differences

| Artifact | What review means | Notes |
|---|---|---|
| **Test case** | The script is correct, complete, and executable | The common case. Bulk-submit and bulk-approve are essential — nobody approves 200 cases one at a time. |
| **Suite** | The suite's *composition* is right — coverage is adequate, nothing missing | Reviewing a suite does **not** implicitly approve its cases. Keep the two independent or the audit trail becomes meaningless. |
| **Plan** | Sign-off that this is the right scope to execute for the release | Often the gate that matters most — it's the release-readiness signature. |

### Mechanics

- **Two tiers** — peer review (`*:review`, any Test Engineer) is advisory; final approval (`*:approve`, Admin/Test Lead only) is the gate. See §6b.
- **Reviewers** — assign a user *or* a team (§6b); any member of the team can action it. Configurable minimum peer reviews before approval is offered (default 0), and an author-cannot-approve-own-work rule (default on) — the single most valuable setting here.
- **Comments** — threaded, anchored to a specific step or field where possible, resolvable. Review without inline comments degrades into Slack conversations that vanish.
- **Permissions** — `case:approve` already exists in §6b; add `suite:approve` and `plan:approve`. Submitting for review needs only edit rights.
- **Diff view** — for a case under re-review, show what changed since the last approval. Without it, reviewers rubber-stamp.
- **Notifications** — review requested / approved / rejected / comment added, through the §6b channels.
- **Gating (opt-in, off by default):** a project may require APPROVED status before a case can be added to a run, or before a plan can be started. Powerful and genuinely obstructive — must be an explicit choice, with a documented lead override for the 2am release.
- **Bulk review** — submit, approve, or reject a filtered selection in one action, reusing the §6f bulk machinery (preview, transactional, single audit event).
- **Audit** — every transition recorded with actor, timestamp, and comment. This is usually the actual reason a team wants review at all: proving to an auditor that someone competent signed off.

### Interaction with conditions and versioning
An approval applies to a **specific `CaseVersion`**, including its snapshotted conditions (§6f). Editing a shared condition therefore does *not* silently invalidate approvals on cases referencing it — but it should surface a warning listing the approved cases whose live content now differs from what was approved. That warning is the honest middle ground between "approvals mean nothing" and "one condition edit un-approves 200 cases."

---

## 6h. Reusable Step & Condition Search — Indexing

Shared steps and conditions (§6f) are only reused if they're **findable at authoring speed**. If searching the library is slower than retyping the step, every author retypes it, and the library fills with near-duplicates — the exact rot the shared library exists to prevent. So this search is a typeahead, and the budget is **sub-100ms end-to-end, p99**.

### Query characteristics

Different from case search, and the difference drives the design:

- **Small corpus** — hundreds to low tens of thousands of rows per project, not millions.
- **Short queries** — 3–20 characters, fired on every keystroke (debounced ~150ms).
- **Partial words and typos** — the author types `logi`, `log in`, or `lgoin` and expects "Log in as standard user".
- **Read-dominant** — the library changes rarely, is queried constantly.
- **Ranking matters more than recall** — the right step must be in the top 3, not merely present in 200 results.

### Index design (PostgreSQL)

Two complementary GIN indexes on the same table, because neither alone is sufficient:

| Index | Purpose | Handles |
|---|---|---|
| **GIN on `tsvector`** (FTS) | Word-level relevance | "login user" → matches "Log in as standard user" regardless of order; stemming; stopwords |
| **GIN on `gin_trgm_ops`** (`pg_trgm`) | Substring & fuzzy | `logi` prefix, `lgoin` typo, mid-word matches — everything FTS structurally cannot do |

- Store a **materialized `tsvector` column** (`search_vector`), maintained by a trigger or a generated column — never compute `to_tsvector()` at query time, which defeats the index entirely.
- Weight the vector: title `setweight('A')`, step bodies `setweight('B')`, tags `setweight('C')`. A match in the title should always outrank a match buried in step three.
- Index `project_id` alongside, and always filter by it — the query planner needs the project predicate to keep the scan small.

### Ranking

Relevance alone gives the wrong answer here. Blend:

```
score =  w1 · ts_rank(search_vector, query)          -- textual relevance
       + w2 · similarity(title, query)                -- trigram closeness
       + w3 · log(1 + usage_count)                    -- popularity
       + w4 · exact_prefix_match(title, query)        -- strong boost
       + w5 · recency_of_last_use_by_this_user        -- personal affinity
```

The **`usage_count` boost is what makes this feel right**: the step attached to 400 cases should surface above a near-identical one used twice. It also creates a healthy feedback loop — popular steps get reused, duplicates starve. `usage_count` is denormalized on `Condition`/`SharedStep` (already needed for §6f's pre-edit impact warning) and refreshed on attach/detach.

### Why not Elasticsearch

At this corpus size Postgres with proper GIN indexes answers in single-digit milliseconds. Elasticsearch would add a second datastore, a sync pipeline, an operational failure mode, and ~1GB of RAM to a **self-hosted** product where "one container plus Postgres" is a headline feature. Consistent with the §4 stance: Postgres FTS in v1, revisit only on measured evidence. If it ever becomes necessary, the search sits behind a `StepSearchService` port so swapping the implementation touches one class.

### Making it feel instant

- **Debounce 150ms** client-side; cancel in-flight requests on new keystrokes.
- **Cache the hot set** — the top ~200 most-used steps per project in Caffeine, served without a DB round trip. Covers the majority of typeahead opens, where the author hasn't typed anything yet.
- **Cap results at 20** with `LIMIT` — nobody scrolls a typeahead.
- **Return highlight offsets** (`ts_headline` or client-side) so matched substrings are marked in the dropdown; it's what makes results feel accurate.
- **Preview on hover** — the step's full body, so authors can distinguish two similarly-titled steps without leaving the dialog.

### Duplicate prevention (the real payoff)

The same trigram index powers the §3 "step phrase library" feature:
- **At creation time**, warn before saving: "3 similar steps already exist" with a similarity threshold (~0.6) — catching the duplicate *before* it enters the library is far cheaper than cleaning up later.
- **Periodic near-duplicate report** for librarians, with a merge action that repoints all referencing cases to the survivor (safe, because §6f references rather than copies).

### Verification
Seed a benchmark dataset (50k steps, realistic phrasing) in a Testcontainers test and assert p99 typeahead latency stays under budget. Search performance regresses silently otherwise — usually the day someone drops the trigram index in a migration.

---

## 6i. Documentation Space — Design

A project gets a **documentation space**: a hierarchical set of pages holding specs, requirements, feature descriptions, test strategy, and release notes. Two things make it more than a wiki: **test cases link to specific points inside a page**, and **documentation is the structural basis for reports**.

### Structure

```
DocSpace (per project)
  └─ DocPage (tree, ordered)
       ├─ slug, title, status: DRAFT | PUBLISHED | ARCHIVED
       ├─ content        block-structured (Markdown/rich text)
       ├─ DocBlock[]     every block carries a STABLE id
       ├─ DocVersion[]   immutable history + diff
       └─ Attachment[]
```

### Stable anchors — the decision the whole feature rests on

A link must point at *a specific point in a page*, and survive that page being edited. Anchors derived from heading text (`#user-login-flow`) break the moment someone fixes a typo in the heading, and they break **silently** — the link still resolves to the top of the page and nobody notices the traceability is gone.

**So: every block gets an immutable generated id at creation**, persisted with the content and never derived from the text. A link targets `page-id + block-id`. Rename the heading, reorder the sections, rewrite the paragraph — the link holds. Human-readable heading anchors are additionally emitted for URL friendliness, but the *stored* link always uses the stable id.

This is a small implementation detail with an outsized effect: it's the difference between traceability that degrades to noise in six months and traceability people still trust in year three.

### Linking model

`DocLink` is a generic edge: `(source) ←→ (target, anchor, linkType)`.

| Link | Meaning |
|---|---|
| TestCase → DocBlock | "This case verifies this specific requirement statement" |
| Suite → DocPage | "This suite covers this feature spec" |
| Requirement → DocBlock | Requirements (§2) may live *as* doc blocks rather than as separate rows |
| Defect → DocBlock | "The documented behaviour here is what we violated" |
| DocPage → DocPage | Ordinary cross-references, backlink-tracked |

**Links are bidirectional in the UI.** The doc page shows "**14 test cases** cover this section — 12 passing, 1 failing, 1 not run" inline beside the block. That inline coverage marker is the feature that makes people actually maintain the links, because it makes the documentation immediately more useful to its readers than plain prose.

**External documents too.** Not everything will be migrated in. Support external references (Confluence page, Google Doc, URL + fragment) as a `DocLink` target with an `EXTERNAL` kind — same traceability graph, no anchor stability guarantee, clearly marked as such.

### Change impact — where this pays off

When a doc block changes, every test case linked to it is **flagged as potentially stale**, with a diff of what changed in the documentation since the case was last touched. Wired into the review workflow (§6g), a spec edit can optionally move linked approved cases to a "needs re-review" queue.

This closes the loop that manual processes always drop: the spec changed, and now somebody actually knows which 23 test cases might be wrong.

### Documentation as the basis for reports

Two distinct mechanisms, both feeding the §6e `ReportModel` pipeline:

**1. Doc-structured coverage reports.** A report can take a `DocPage` (or a subtree) as its *spine*: walk the document's own headings in order, and under each, render the linked cases with their latest results. The output reads as the specification with verification evidence inline — "here is what we said the system does, and here is the proof it does it." That is exactly the artifact an auditor, a client, or a release board wants, and it is dramatically more legible than a flat test-run table. It also surfaces **documented behaviour with no linked test case** as an explicit coverage gap, in the reader's own document structure.

**2. Documentation pages as report templates.** A page may embed live placeholders — `{{run_summary:planId}}`, `{{coverage_matrix:featureId}}`, `{{automation_coverage}}` — that resolve at generation time. Authors write the narrative (scope, environment, risks, sign-off) once, and the data fills itself in on every export. This is what turns a "test summary report" from a document somebody rewrites each release into a template that regenerates in seconds.

Both paths render through the existing HTML / Markdown / PDF renderers (§6e) — the documentation space needs **no separate export machinery**, which is a strong argument for storing doc content block-structured rather than as an opaque HTML blob. The `ReportModel` abstraction already anticipated this.

### Practical details

- **Editing** — block-based Markdown/rich-text editor; content stored structurally (blocks with ids), not as an HTML string. Sanitized server-side like all rich text.
- **Versioning** — page versions with diff view; a report generated against a doc records which version it used, satisfying §6e's determinism requirement.
- **Search** — reuses the §6h Postgres FTS infrastructure; doc content joins the same weighted `tsvector` approach. Cross-entity search ("find cases *and* docs mentioning refunds") is then one query.
- **Permissions** — `doc:read` / `doc:edit` / `doc:publish` added to the §6b catalogue. Member gets read + edit; publish is a Lead capability. Docs are frequently the artifact a wider audience (PM, support) needs read access to, so a **doc-only Viewer** role variant is worth seeding.
- **Import** — Markdown and AsciiDoc import; optional **docs-as-code sync** from a Git repo path (phase 3), following the §6a rule: one direction of truth per space, never bidirectional.
- **Templates** — seeded page templates for Test Strategy, Test Plan, and Release Report so teams don't start from a blank page.
- **Mermaid diagrams** — a `mermaid` block type rendered client-side from its source text. Test strategy documents, environment topologies and state diagrams are substantially diagrams, and the alternative is people pasting screenshots of diagrams they drew elsewhere — which then rot invisibly, can't be diffed, and don't survive the §6j version snapshot as anything but an opaque image. Storing the **source text** in the block means diagrams version and diff like any other content. Sanitize as data, never as HTML, and render with a node/complexity cap so a pathological diagram can't hang the page.

### Deliberate limits
No real-time collaborative editing (operational transforms / CRDTs are a project of their own — use optimistic locking with a clear conflict message instead), no page-level ACLs beyond project scope, no comment threads in v1 beyond what §6g's review provides.

---

## 6j. Versioning & History — Design

Test cases, suites, plans, and documentation pages are all versioned, and every change is attributable: **who changed what, when, and ideally why.**

These are **two distinct mechanisms** and conflating them produces a system that does neither job well:

| | **Version** | **Audit event** |
|---|---|---|
| Answers | "What did this look like at version 7?" | "Who did what, when?" |
| Shape | Immutable content snapshot of one aggregate | Append-only record of an action |
| Restorable | Yes | No |
| Covers | Case · Suite · Plan · DocPage · Condition · SharedStep | *Everything*, including non-versioned acts: role grants, run execution, bulk operations, logins, exports, approvals |
| Retention | Thinnable (except pinned) | Append-only, never edited |

You need both. Versions without an audit log can't explain a role grant or a bulk operation; an audit log without versions can't reconstruct or restore content.

### Versioned aggregates — and what a "version" means for each

The unit of versioning is the **aggregate**, not the row, so a version captures a coherent whole:

| Aggregate | A version captures |
|---|---|
| **TestCase** | Title, steps (either format), pre/post conditions **resolved and snapshotted** (§6f), classification, automation metadata, custom fields |
| **Suite** | Its **composition** — member cases, ordering, nesting, suite-level conditions and metadata. Explicitly **not** the content of its cases; those version independently. Conflating the two makes every case edit bump every ancestor suite. |
| **TestPlan** | Its **scope** — selected suites/cases, configurations, milestone, entry/exit criteria |
| **DocPage** | Full block content with stable block ids (§6i) |

### Storage

**Full snapshot per version, serialized to JSONB, with diffs computed on read.** Deltas would save storage and cost far more in complexity: reconstructing version 43 means replaying 43 deltas, and one buggy delta silently corrupts everything after it. Snapshots make restore trivial, reconstruction O(1), and corruption local. Test content is small; storage is cheap.

Each version row carries `versionNumber` · `createdBy` · `createdAt` · `changeComment` · `parentVersion` · `contentHash`. The hash lets us skip creating a version when a save changed nothing — which happens constantly with autosave.

### When a version is created

Not on every keystroke — 400 versions of noise makes history useless.

- Autosave writes to a **working draft**, not a version.
- An explicit save/publish creates a version, with an **optional** change comment. Optional, not required: mandatory comments produce "update" a thousand times, which is worse than nothing. Prompt for a comment only on significant changes (steps altered, approval invalidated).
- Bulk operations (§6f) create **one version per affected item** but a **single audit event** covering the whole set — so the audit log reads "Ana attached 2 conditions to 143 cases" rather than 143 lines.

### Immutability guarantees

A version is **pinned** — permanently protected from any retention policy — when it is:
- referenced by a test run (§2),
- the subject of an approval (§6g),
- cited by a generated report (§6e).

Pinned versions can never be purged, because each is the evidentiary basis of something that happened. Everything else may be thinned by policy (e.g. keep all versions from the last 12 months, then monthly).

**Restore never deletes.** Restoring version 3 creates version 12 with version 3's content, attributed to whoever restored it. History is append-only in both mechanisms.

### Audit event

```
AuditEvent
  ├─ actor          user or API token (tokens are actors too — §6b)
  ├─ action         case.updated · role.granted · run.closed · report.exported …
  ├─ targets[]      one or many (bulk ops)
  ├─ projectId      for scoping and access control
  ├─ before/after   changed fields only, for cheap field-level history
  ├─ context        IP, user agent, request id, and the originating bulk-operation id
  └─ occurredAt
```

Emitted from **domain events**, not scattered through controllers, so the ingestion path and scheduled jobs are audited on the same footing as UI actions. Queryable per entity ("history of this case"), per actor ("everything Ana did last week"), and per project ("all approvals this quarter") — the last being what an auditor actually asks for.

### Diff presentation

Type-appropriate, because a generic JSON diff is unreadable:
- **Case** — side-by-side step diff with inserted/removed/moved steps marked; changed classification and conditions called out separately.
- **Suite / Plan** — tree diff: cases added, removed, reordered.
- **Doc page** — block-level diff keyed on stable block ids (§6i), so a moved section shows as *moved*, not as a delete plus an insert. This is the payoff of stable ids beyond linking.
- **Compare any two versions**, not just adjacent ones — the question is usually "what changed since we approved this?", which may span many versions.

### Implementation note

**Hand-rolled version tables plus one `AuditEvent` table**, rather than Hibernate Envers. Envers audits entity-by-entity, which fights the aggregate-snapshot model we need (a case version must include its conditions and steps as one unit), its revision tables grow awkwardly, and it can't naturally express non-entity actions like an export or a login. We'd end up with both anyway. Building it deliberately costs little and keeps history queryable with plain SQL — which matters, because history queries are exactly what people write ad hoc during an incident.

---

## 6k. Org Chart & Team Structure — Design

A visual organizational view built from teams: who is in which team, who leads it, how teams nest, and what each team owns. **The chart is always one company's chart** — there is no instance-wide org chart, and a person who belongs to two companies appears in each one's chart showing only that company's teams and projects (§6s).

### Structure

Teams gain an optional `parentTeam`, forming a hierarchy — "QA Chapter" → "Payments Squad", "Checkout Squad". Rendered as a standard org chart, collapsible, searchable.

**Two things this deliberately is not:**

1. **Not a permission hierarchy.** Per §6b, nesting is *structural only* — a parent team's role grants do not cascade to child teams. If Payments Squad needs access to a project, it is granted explicitly. Cascading org structure into access control is how permission models become unauditable, and the org chart is a presentation feature, not an authorization one.
2. **Not strictly a tree.** A user may belong to several teams (§6b), so the people layer is a graph even when the team layer is a tree. Render it honestly: a person appearing under two squads shows in both, with an indicator, rather than being arbitrarily assigned to one. Pretending otherwise produces a chart people stop trusting.

### What each node shows

- Team name, lead(s), member count, member avatars.
- **Projects the team holds roles on**, with the role — the single most useful piece of information on the chart, since it answers "who owns this project?" in one glance.
- Optional live workload: open assignments, in-flight runs, pending reviews. Turns the chart from a static diagram into a capacity view — which is what makes a lead actually open it.

### Drill-down

- **Team view** — members with their per-project roles, owned projects, recent activity, aggregate execution stats (§6e) scoped to that team.
- **Person view** — teams they belong to, roles per project, current assignments, review queue, recent activity from the audit log (§6j). Doubles as the onboarding page ("what does this person actually work on") and the offboarding checklist.

### Practical details

- **Editing** — drag-and-drop reparenting for Admins; team lead can manage own membership. Cycle prevention on reparent is a required validation, not an afterthought.
- **Permissions** — `company:teams` to edit structure; the chart itself is readable by any authenticated **member of that company**. Org structure is not sensitive *within* a company, and hiding it defeats the purpose — but it is absolutely sensitive across companies, which is the one place the "readable by anyone" rule is narrowed.
- **Export** — renders through the §6e pipeline to PDF/HTML like any other report; it's a common thing to paste into a wiki or a status deck.
- **Vacancy / unassigned** — surface users belonging to no team, and teams with no lead. These gaps are the reason someone opens an org chart in the first place.
- **Scale** — fine to compute server-side and render client-side; org charts are hundreds of nodes, not millions. No special machinery needed.

Phase-wise this is **not core to testing** and belongs in phase 2, after teams and roles exist and have real data to display. Built earlier it's an empty diagram.

---

## 6l. Kanban Board & Work Items — Design

A board for daily work, showing **test executions alongside every other kind of task** — "update the checkout test cases", "document the refund flow", "review Ana's suite" — with priority and deadlines.

### The central design decision: derived items, not copies

Two kinds of thing land on the board, and they must be modelled differently:

| | **Task** (first-class) | **Derived item** (projection) |
|---|---|---|
| Examples | "Update test cases for the new checkout", "Write release notes" | An assigned test execution · a pending review request (§6g) · a case flagged stale by a doc change (§6i) · a quarantined flaky test · **a failed-result follow-up** (added 2026-08-02, §78) · a linked tracker issue (#25, read-only from cache) |
| Source of truth | The task itself | The underlying entity |
| Status | Stored on the task | **Computed from the source, never duplicated** |
| Created by | A person | The system, from existing state |

**The trap to avoid:** materializing test executions as task rows that carry their own status. The moment a card's status is stored separately from the execution's, the two diverge — a tester marks the case Passed in the run, the card sits in "In Progress" forever, and within a week nobody trusts the board. Since the board is meant to be the daily view, that failure is fatal to the feature.

So derived items are **views over the source entity**, resolved at query time:
- Their status maps from the source's own state via a per-board column mapping.
- **Moving a derived card performs the underlying action** — dragging an execution card to Done opens the execution to be completed rather than silently marking it done; dragging a review card to Done requires the approval (§6g), and is refused with a clear message if the user lacks `*:approve`.
- Columns a derived item cannot legally occupy simply reject the drop, with the reason shown.

A common request will be "let me add a personal note to that execution card." Support it as task **metadata attached to the derived item** (a note, a personal due date, a flag) without letting it own the status. That satisfies the need without breaking the invariant.

### Work item model

```
WorkItem  (abstract view over Task | DerivedItem)
  ├─ title, description
  ├─ kind            TASK · EXECUTION · REVIEW · DOC_UPDATE · CASE_MAINTENANCE · DEFECT
  ├─ status          → mapped to a board column
  ├─ priority        Critical · High · Medium · Low
  ├─ dueDate         + overdue / due-soon derivation
  ├─ assignee        user (or team, for an unclaimed pool)
  ├─ projectId       scoping and permissions
  ├─ links[]         to case / suite / plan / run / doc page / defect
  └─ labels[]        reuses the §6d free-tag mechanism
```

Tasks are ordinary versioned, audited entities (§6j) and live in the `project` module; the board itself is a read-model concern.

### Boards

- **Personal — "My Work"** — everything assigned to me across all projects I can see, in one place. This is the view most people will keep open all day, and it must aggregate across projects; a per-project-only board fails the person working on three.
- **Team board** — one team's work (§6b), swimlanes by member or by priority.
- **Project board** — everything in a project, filtered.

Columns are configurable per board (default `To Do · In Progress · Blocked · In Review · Done`), with optional **WIP limits** — worth having, since the most common real problem is twelve simultaneously-open executions.

### Priority and deadlines

- Priority is a plain four-level enum, sortable, colour-coded, and **distinct from test case priority** — a P1 test case may be a low-priority thing to *update*. Conflating them is a subtle, persistent confusion; keep the fields and their names separate.
- **Due dates** on tasks; derived items inherit a deadline from their source where one exists (a run's end date, a milestone date, a review SLA). Overdue and due-soon are computed, never stored.
- A **calendar/deadline view** as a secondary presentation of the same data — some people plan by date rather than by column.
- Notifications (§6b) on assignment, approaching deadline, and overdue.

### Where items come from

Derived items are generated off domain events, so the board reflects the system without anyone maintaining it:
- Test execution assigned → card appears; execution completed → card leaves.
- Review requested (§6g) → card for each reviewer.
- Doc block changed → "verify affected cases" card for cases flagged stale (§6i).
- Case marked `TO_BE_AUTOMATED` (§6c) → optional automation-backlog card.
- Ingested failure on a case you own → triage card.

Each generator is individually toggleable per project. All of them on by default would bury the board on day one — and a board nobody can face is worse than no board.

### Defects live here — and this is the local fallback

**Decided: the board is where defects are tracked, and it is what makes TCMS useful with no tracker configured.** §1's non-goal stands — we are not building a defect tracker — but a `Defect` record is already in the domain model (§2), created from a failed execution, and `DEFECT` is already a `WorkItem` kind. Those two facts, plus a board column mapping, are the whole feature.

Two modes, and the board is the same view in both:

| | **No external tracker** (default) | **External tracker configured** (§3 Phase 2) |
|---|---|---|
| Where the defect lives | The local `Defect` record is the whole thing | The local record is the **stub**; the tracker owns the defect |
| Status | Stored on the local record, moved on the board | **Synced from the tracker**, read-only on the board — a derived item per §6l's central rule |
| Filing | Created from a failed step, prefilled with reproduction | Same action, additionally creates the tracker issue and stores `externalRef` |
| The board shows | All open defects, by project / assignee / severity | The same view, statuses reflecting the tracker |

**The point of keeping the local record in both modes:** it is the join between the failed execution, its evidence, and the external issue. Without it, integrating a tracker would mean the traceability chain (§6e coverage matrix, §6p history) has to query Jira to answer "which defects came out of this run" — which is slow, breaks when the token expires (§6r), and returns nothing at all for a self-hoster with no tracker.

**So the bug-progress dashboard is ours regardless of tracker.** This is the explicit intent: a team using Jira still gets a TCMS-side view of every defect that came out of testing — open count by project, by severity, by feature (§6d), age, and which run produced it — without opening Jira and without rebuilding a Jira dashboard. Cross-tracker too: a company using GitHub Issues on one project and Jira on another sees one board.

**What this deliberately is not:** no workflow editor, no custom defect fields, no sprint or estimation machinery, no comment threads mirrored from the tracker. When a tracker is configured it is authoritative for everything except our own link and our own view. Status mapping is a per-project config (`tracker status → board column`) with a documented default, because every Jira workflow is different and guessing produces a board that is subtly wrong.

**Sync mechanics** follow §5: webhook where the tracker supports it, poll as fallback, `syncedStatus` + `syncedAt` on the local record so staleness is visible rather than silent (the §6p freshness rule applies — a defect column showing three-week-old statuses presented as current is the same failure mode).

### Practical details

- **Permissions** — a user sees only work items in projects they can read; assigning to others needs `plan:edit`-level rights within the project. The personal board is filtered by the same §6b resolution as everything else. Filing a defect is `defect:create`; moving a *local* defect card is the same permission, while an externally-owned defect card is read-only regardless of role.
- **Performance** — the board is a read model over several sources; cache per user with event-driven invalidation, and cap derived generation with sensible limits. It's opened dozens of times a day, so it must be fast.
- **Real-time** — SSE for live column updates on team boards; ties in with the §8 open question about concurrent run execution, and the two should share one transport rather than growing separate ones.
- **Not a project management tool.** No sprints, burndowns, story points, or dependency graphs. If a team wants those, the answer is the Jira integration (§3 phase 2) — and it's worth considering surfacing linked Jira issues as derived items on the board rather than reimplementing any of it.

Phase 2: it needs executions, reviews and docs to exist before it has anything to show.

---

## 6m. Test Case — Field Specification

The authoritative field list, consolidating what §6a–§6l describe piecemeal.

### Identity & description

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | The **actual** primary key and the only thing any FK, API path or join uses. Not shown to users. |
| `displayKey` | string, immutable | Human-facing only: **type prefix + per-company sequence**, `TC-1`. Unique **per company**, across all its projects — so it is unambiguous everywhere inside a client without a qualified form. Never reused, never renumbered; it ends up in commit messages, Jira tickets and spreadsheets, so it must outlive everything. Full spec in §6t. |
| `title` | string, required | Single line, indexed (§6h). Enforce a sane max (~200 chars) — long titles are a symptom of a case that should be split. |
| `description` | rich text, optional | Purpose and context. Distinct from preconditions: *why* this test exists, not what must be true before it runs. |
| `objective` | rich text, optional | What this case proves. Optional and often skipped; valuable for regulated work. |

### Classification & metadata

| Field | Type | Notes |
|---|---|---|
| `testType` | single-select, controlled | Functional · Performance · Security · Usability · … (§6d) |
| `testLevel` | single-select, controlled | Unit · Component · API · Integration · UI/E2E · … (§6d) |
| `features[]` | multi-select, hierarchical | From the project's feature tree (§6d) |
| `executionPhase[]` | multi-select | Smoke · Sanity · Regression · Acceptance (§6d) |
| `tags[]` | multi, free-form | The escape hatch. Deliberately uncontrolled. |
| `priority` | enum | Critical · High · Medium · Low. **Not** the same field as kanban task priority (§6l). |
| `severity` | enum, optional | Impact if this fails. Separate from priority — some teams track both, most don't; make it an optional field rather than a mandatory one. |
| `estimatedDuration` | duration, optional | Feeds run planning and the manual-execution-cost report (§6c) |
| `owner` | user | Distinct from `createdBy` — ownership transfers, authorship doesn't |
| `status` | enum | Draft · In Review · Approved · Deprecated (§6g) |
| `automationStatus` | enum | Manual · To Be Automated · Automated · Not Automatable (§6c) |
| `automationTag[]` | multi, free-form | Selection tags: `@smoke`, TestNG group (§6c) |
| `automationKey` | string, unique per company | Match target for CI ingestion (§6c) |

### Related documents — linking to a specific point

`relatedDocuments[]` — a list of `DocLink` edges (§6i), each with an optional relationship type (*verifies* · *derived from* · *references*).

**On "link to lines":** for internal documentation, we deliberately do **not** anchor to line numbers. Line numbers shift on every edit above them, and they shift *silently* — the link still resolves, just to the wrong content, which is worse than a broken link because nobody notices. §6i's stable block ids are the durable equivalent: a link targets `page-id + block-id`, survives edits, reordering and rewrites, and the UI presents it exactly as you'd want ("jump to this point in the doc"). Functionally you get line-level precision; structurally it's anchored to something that can't drift.

Where a genuine line reference is unavoidable — a spec or code file living in Git rather than in the doc space — support it as an `EXTERNAL` link of the form `repo + path + line range + commit SHA`. **Pinning the SHA is mandatory**, because that is the only thing that makes a line number mean anything six months later. Render it with a "may be outdated — pinned to commit `abc123`" marker, and offer a resolve-to-latest action.

| Link target | Anchor | Stability |
|---|---|---|
| Internal doc page | `blockId` | Stable across all edits |
| External Git file | `path + lines + commitSha` | Stable only against that commit; flagged as such |
| External URL (Confluence, Drive) | URL fragment | Best-effort, no guarantee, marked |

Each link is bidirectional — the doc block shows the cases that verify it, with live pass/fail state (§6i).

### Preconditions & postconditions

`preconditions[]` and `postconditions[]` — ordered lists (§6f). Each entry is either:
- **inline** — free text or steps, local to this case, or
- **a reference** to a shared `Condition` from the project library, optionally with parameter bindings (`${role}` → `admin`), or
- **inherited** — from an ancestor suite, shown read-only with its origin labelled and an opt-out toggle.

Resolved and snapshotted into the case version at pin time (§6f/§6j), so history stays reproducible while live cases keep propagating library edits.

### Steps & expected results

Per §6a, the shape depends on `stepFormat`:

**STRUCTURED** — `steps[]`, each with:

| Field | Notes |
|---|---|
| `index` | Display order; stable `stepId` underneath so results and comments survive reordering |
| `action` | Rich text — what the tester does |
| `testData` | Optional; separated from the action so it can be parameterized and varied per data set |
| `expectedResult` | **Per step.** The primary place expectations live. |
| `attachments[]` | Reference screenshots, sample files |
| `sharedStepRef` | Optional — this step is a reference to a shared step block (§6h) |

**GHERKIN** — `gherkinSteps[]`, each with `keyword` (Given/When/Then/And/But), `text`, optional DocString or DataTable. Expectations are carried by `Then` steps rather than a separate column.

**Plus, for both formats:** `expectedOutcome` — an optional case-level statement of the overall expected end state. Per-step expected results answer "did this step behave?", the case-level one answers "did the test achieve its purpose?" Some teams only use one; supporting both costs nothing and prevents the common workaround of stuffing the overall outcome into the last step.

`stepId` stability matters as much as doc block ids: results, comments and evidence anchor to it, so inserting a step at position 2 must not silently reassign three runs' worth of evidence.

### Data & custom fields

| Field | Notes |
|---|---|
| `parameters[]` / `dataSets[]` | Parameterized execution; unified with Gherkin `Examples` (§6a) |
| `customFields` | Project-defined typed fields (§2) — typed columns over JSONB, filterable and reportable, not a free-text blob |

### System fields

`createdBy` · `createdAt` · `lastModifiedBy` · `lastModifiedAt` · `version` · `deletedAt` (soft delete) — all covered by §6j, all queryable in the history view.

### Field-level design rules

1. **Every list-typed field has stable ids under the display order.** Steps, conditions, doc links. Reordering must never reassign meaning.
2. **Required fields are only `title` and `stepFormat`.** Everything else is optional. Mandatory-field enthusiasm is what makes people paste rubbish into forms; enforce completeness through the review workflow (§6g), where a human can judge, not through validation.
3. **Custom fields are typed and first-class** — filterable, reportable, and part of the version snapshot.
4. **Nothing is stored twice.** Classification, conditions and doc links are all references resolved at read time and snapshotted only at version-pin time.

---

## 6n. Test Artifacts — Design

A dedicated home for the supporting material a test effort produces and depends on, at **two scopes**: the project (durable, cross-release) and the test plan (cycle-specific).

This is distinct from three things it will otherwise get confused with:

| | Lives in | Scope |
|---|---|---|
| **Evidence** (§6e) | Attached to an execution or step | One result |
| **Documentation** (§6i) | Doc space, authored in-app, block-linkable | Narrative, evolving |
| **Reports** (§6e) | Generated from data | Point-in-time output |
| **Test artifacts** *(this section)* | Artifact register | Files and references supporting a project or a cycle |

The rule of thumb: documentation is *written*, artifacts are *held*.

### Two scopes

**Project-level** — durable material outliving any single cycle:
- Test strategy, standards, conventions
- Environment setup guides, topology diagrams
- Reusable test data sets, seed scripts, fixture files
- Tooling configuration, automation framework docs
- Compliance and regulatory reference material
- Templates (report, plan, charter)

**Plan-level** — everything specific to one cycle:
- Cycle-specific test data and environment snapshot / config at time of execution
- Entry and exit criteria evidence
- Risk assessment and mitigation log for this release
- Sign-off documents and approval records
- Generated reports pinned as cycle artifacts (§6e)
- Defect summary, known-issues list, release notes contribution

**Plan-level views inherit project-level artifacts read-only**, clearly marked as inherited with their origin. A tester opening a plan should see the environment guide without hunting for it, and must not be able to edit the project-wide document from inside one cycle. This mirrors the condition-inheritance model (§6f) — same idea, same UI language, worth keeping consistent.

### Artifact model

```
Artifact
  ├─ scope        PROJECT | PLAN
  ├─ category     Strategy · TestData · Environment · SignOff · Compliance ·
  │               Tooling · Template · Other   (project-configurable)
  ├─ kind         FILE | DOC_PAGE_REF | EXTERNAL_URL | GENERATED_REPORT
  ├─ title, description
  ├─ version[]    §6j — full history, who/when/why
  ├─ tags[]       §6d free tags
  ├─ links[]      to suites, cases, requirements, doc blocks
  └─ status       Active | Superseded | Archived
```

**Four kinds, one register.** An artifact is not always an uploaded file:
- **FILE** — uploaded through the existing `StorageService` (§4), so local FS or S3/MinIO, with the size and type limits and virus-scan hook from §6.
- **DOC_PAGE_REF** — a pointer to a page in the documentation space (§6i). The test strategy should be *authored* as a doc page and *registered* as an artifact, not uploaded as a stale Word file. This is the option to steer people toward.
- **EXTERNAL_URL** — Confluence, SharePoint, a Git path. Not everything will be migrated in, and pretending otherwise just means people stop using the register.
- **GENERATED_REPORT** — a report (§6e) pinned into the register, freezing that output as a cycle artifact.

Supporting references rather than only uploads is what keeps the register from becoming a graveyard of outdated copies — the single most common failure mode for a feature like this.

### The payoff: the evidence bundle

**When a test plan closes, its artifact set freezes** into an immutable bundle: the plan's artifacts, the inherited project artifacts *as they were at that moment*, the pinned reports, the sign-off records, and the pinned case versions (§6j).

That bundle exports as a single archive — or a single PDF with an index — and it is exactly what an auditor, a client, or a regulatory submission asks for. Assembling it by hand takes days and is the sort of task that gets done badly under release pressure. Making it a button is, in my view, the strongest argument for building this section at all, and it's why artifacts need to be versioned and scope-inherited rather than being a flat file list.

### Practical details

- **Versioning** — full §6j treatment: who uploaded/changed what, when, with restore. Superseding an artifact keeps the old version reachable and marks it *Superseded* rather than deleting it.
- **Permissions** — `artifact:read` / `artifact:edit` / `artifact:delete` added to §6b. Test Engineer gets read + edit; project-scope artifacts are Test Lead-only to edit, since they affect every cycle. Sign-off artifacts should be write-once.
- **Search** — indexed with the §6h infrastructure: title, description, tags, and extracted text from PDFs and Office files where feasible. An artifact nobody can find is an artifact nobody uses.
- **Retention** — artifacts attached to a closed plan are pinned and exempt from purge, like pinned versions (§6j). Storage growth here is real: environment snapshots and test data files are large, so surface per-project artifact storage usage in project settings.
- **Templates** — seed the project artifact set from a template on project creation, so a new project starts with the right empty slots rather than a blank page.
- **Staleness** — flag project artifacts untouched for N months. Environment guides rot silently, and a wrong setup guide costs more than a missing one.

Phase 1 for the register and both scopes; the evidence-bundle export lands with reporting in M2.5.

---

## 6o. Test Repository Status Dashboard — Design

The project's landing page: **the authoring state of every test case at a glance, with every number a link.**

This answers a different question from the execution reports in §6e. Those ask *"did the tests pass?"*; this asks **"is our test repository in good shape?"** — what's finished, what's half-written, what's rotted. Conflating the two produces a dashboard that answers neither, so they stay separate views over separate read models.

### Two independent axes

The critical modelling point: **"Done" is a stored status, "Outdated" is a derived signal, and a case can be both.** An approved case whose spec changed last week is simultaneously Done and Outdated — and that combination is the single most important thing the dashboard exists to surface. Modelling "Outdated" as another value in the status enum would make it mutually exclusive with Done, which is exactly wrong.

**Axis 1 — Lifecycle status** (stored, one value, §6g):

| Status | Meaning |
|---|---|
| **Draft** | Being written. "In progress." |
| **In Review** | Submitted, awaiting peer review or Test Lead approval |
| **Approved** | Signed off. "Done." |
| **Deprecated** | Retired but retained for history |

**Axis 2 — Health signals** (derived, zero or many, recomputed continuously):

| Signal | Derived from |
|---|---|
| **Outdated — spec changed** | A linked doc block changed after the case was last edited (§6i) |
| **Outdated — stale approval** | Case edited since its approval (§6g) |
| **Outdated — condition drift** | A referenced shared condition changed since the snapshot (§6f) |
| **Stale automation** | Marked `AUTOMATED` but no ingested result in N days (§6c) |
| **Never executed** | Approved but never run |
| **Long unexecuted** | No execution in N days |
| **Unlinked** | No requirement or doc link — invisible to traceability |
| **Failing repeatedly** | Failed the last N executions (from `ExecutionFact`, §6e) |
| **Orphaned** | Owner deactivated or left the project |
| **Incomplete** | Approved but missing steps, expected results, or classification |

None of these are stored on the case — all are computed, so they self-heal when the underlying condition is fixed. No sync job to drift out of date, no "mark as not-stale" button to lie with.

### Layout

**Top row — lifecycle tiles**, each a count and a link:
`Draft 142 · In Review 23 · Approved 891 · Deprecated 67`

**Second row — attention tiles**, ordered by urgency, each a link:
`Outdated 78 · Stale approval 31 · Never executed 44 · Stale automation 12 · Unlinked 156`

**Then breakdowns**, each cell clickable:
- **By suite / feature** (§6d) — a stacked bar per feature showing lifecycle distribution. Immediately shows which area is half-written.
- **By owner** — who has drafts sitting unfinished, who has a review queue building up.
- **By automation status** (§6c) — cross-tabulated with lifecycle, since "approved but not yet automated" is the automation backlog.
- **Recent activity** — cases created, approved, and modified over the last 30 days, from the audit log (§6j). Shows whether the repository is actively maintained or abandoned.

### Every number is a link — the actual requirement

A dashboard whose numbers you can't click is a poster. Each tile, bar segment, and table cell navigates to the **case list pre-filtered to exactly that population**, and:

- The filter state lives **in the URL**, so the view is shareable, bookmarkable, and survives a refresh. "Here are the 78 outdated cases" should be a link you can paste into Slack.
- The destination is the normal case list with all its usual capabilities — so from "78 outdated" you can immediately multi-select and **bulk-act**: reassign, submit for review, attach conditions (§6f), or bulk-approve. Landing on a read-only list would waste the click.
- Filters compose. Clicking "Outdated", then a feature bar, gives outdated cases in that feature. This falls out naturally if the dashboard emits filter predicates rather than bespoke queries per tile.

That compose-and-act loop — *see the problem, select the population, fix it in bulk* — is what makes the dashboard a working tool rather than a status report.

### Scoping and permissions

- Default scope is the project; filterable to a suite subtree, a feature, a team (§6b), or an owner.
- A **personal view** ("my cases needing attention") shares the same read model and belongs on the kanban board (§6l) as derived items rather than as a second dashboard.
- Everything respects §6b resolution — counts include only cases the viewer can read, so two users may legitimately see different totals. Worth noting in the UI to pre-empt the support question.

### Implementation

- Backed by a **`CaseHealth` read model** in the `reporting` module, updated off domain events (case edited, doc block changed, approval granted, result ingested, condition modified). Computing ten signals across 50k cases on page load would be a live-join disaster — the same reasoning that produced `ExecutionFact` (§6e).
- Thresholds (`N` days for stale automation, long-unexecuted, etc.) are project settings, not constants.
- Cache per (user, scope) with event-driven invalidation; target sub-500ms load, since this is the page people open first every morning.
- **Exportable** through the §6e pipeline like any other report — a repository health snapshot is a reasonable thing to attach to a release review.

### Deliberate limits
No configurable widget layout, no user-defined dashboards, no drag-and-drop tiles in v1. A well-chosen fixed layout beats a customizable one that everyone leaves at defaults; revisit only if people ask for specific tiles we don't have.

---

## 6p. Execution History & Statistics — Case, Suite, Plan — Design

Three levels of the same idea, sharing one read model:

| Level | Leads with | A data point is |
|---|---|---|
| **Case** | Pass/fail timeline, current version | One execution |
| **Suite** | Pass % per execution, versioned | One run of the suite |
| **Plan** | Current state from the latest execution | The plan's readiness right now |

All three are **attributed to the version that was executed and filterable by version.**

This is the direct payoff of pinning versions to runs (§6j). Without version attribution, a case's history is a meaningless mix of results for what may be substantively different tests — and every "70% pass rate" it reports is a number nobody should act on.

### The core problem: results span versions

A case edited three times has results belonging to three different tests. Blending them is wrong in both directions: old failures make a fixed case look broken, and old passes make a newly-broken case look healthy.

**Resolution: version is a first-class axis of the history, and the headline statistic defaults to the current version.**

- The **summary strip** at the top of the case page reports on the **current version only**, labelled explicitly: *"Current version (v7): 12 executions · 11 passed · 1 failed · last run 2 hours ago."* Unqualified aggregate numbers across all versions are the thing to avoid showing by default.
- The **timeline below shows everything**, banded and colour-marked by version, so nothing is hidden — it's contextualized rather than filtered away.
- A **version filter** switches between *Current version*, *All versions*, or any specific version. It's a first-class control, not buried in an advanced-filter drawer.

### Version-change markers — the diagnostic payoff

The timeline draws a horizontal marker wherever the case changed, labelled with the version, author, date, and change comment (§6j), linking to the diff.

This makes causation visible: a run of green results, a marker for v5, then three reds immediately after, is an instantly readable story — *the edit broke it*. That single visual answers the most common question asked on a case page ("why did this start failing?") without anyone opening a diff, and it's the strongest argument for putting version markers on the timeline rather than in a separate history tab.

### What each history entry shows

| Column | Notes |
|---|---|
| Result | Passed · Failed · Blocked · Skipped · Retest, with the project's custom statuses |
| **Case version** | The pinned version, linked to its diff |
| **Release version** | The build tested against (§6q); filterable, and the basis of "first failed in 4.3" |
| Date / duration | |
| Executed by | Person, or the API token / CI job for ingested results (§6c) |
| Run & plan | Linked |
| Configuration | Environment, browser, OS (§2) |
| Evidence | Thumbnail strip, expanding to full evidence and per-step results |
| Defects | Linked Jira issues raised from this execution |
| Source | Manual vs ingested — visually distinguished, since they fail for different reasons |

Each row expands in place to the per-step results, so diagnosing a failure never requires leaving the case page.

### Filters

Version, date range, result, configuration, manual vs automated, run, and plan — all composing, all reflected in the URL so a filtered history is shareable (consistent with §6o).

The two that carry the most weight: **version** (is this still true of the test as it exists now?) and **configuration** (does it only fail on Firefox?).

### Derived insight on the case page

Cheap to compute from the same data, and worth surfacing inline:
- **Flakiness** — pass/fail alternation *within a single version and configuration*, which is the only scoping under which flakiness means anything. Alternation across versions is just change, and reporting it as flake is a false signal.
- **First-failed** — the earliest execution of the current failing streak, with the version in force at that point.
- **Stability by configuration** — a compact matrix; "fails only on Safari" should be readable in one glance.
- **Execution cost trend** — average duration over time, feeding the manual-cost report (§6c).

### Implementation

- Served from **`ExecutionFact`** (§6e), which already carries `caseVersion` — this was specified for exactly this purpose, and it's why the read model must not be deferred.
- Index on `(caseId, executedAt DESC)` with `caseVersion` in the covering index; the summary strip reads a pre-aggregated per-`(case, version)` rollup rather than scanning.
- Paginate the timeline (default 50, infinite scroll); a nightly-automated case accumulates thousands of executions and the page must open instantly regardless.
- **Deleted runs must not orphan history** — soft delete only (§2), with the entry marked rather than vanishing. History that quietly loses rows is worse than no history.
- The same read model and query shape power the phase-3 trend and flakiness reports; this is a UI over data that already exists rather than a new pipeline.

---

### Suite execution history

The same feature on a suite page, with one difference in what a data point *is*: a case history entry is a single result, **a suite history entry is one execution of the suite, summarized as a pass percentage.**

```
v3 ████████████░░░  87%   Nightly Regression #412 · 2026-07-28 · 41/47 passed · Chrome
v3 ██████████████░  94%   Nightly Regression #411 · 2026-07-27 · 44/47 passed · Chrome
─────────────────── suite v3 — Ana added 4 cases — 2026-07-26 ───────────────────
v2 ███████████████ 100%   Release 4.2 Sign-off  · 2026-07-25 · 43/43 passed · Chrome
```

Each row: **pass %**, absolute counts, the run it came from, date, configuration, and the **suite version** in force. Rows expand to the per-case breakdown, each case linking to its own history (above). Version-change markers appear on the same timeline, so a pass rate dropping right after "4 cases added" reads as what it is — new cases, not a regression.

**What counts as one suite execution.** A suite isn't executed directly; runs are. A history entry is therefore **the set of executions of this suite's cases within one run**, grouped by `(run, suite, configuration)` — which is the unit people mean by "last night's regression run of the Checkout suite". Where a run covers only part of a suite, show the coverage alongside the percentage (*"87% of 47 executed — 6 not run"*), because a 100% pass rate over 3 of 47 cases is otherwise actively misleading.

**Pass % must state its denominator.** Offer two, chosen by a visible toggle, never silently:
- **Of executed** — `passed / executed`. Answers "of what we ran, how much worked?"
- **Of total in suite** — `passed / cases in suite`. Answers "how much of this suite is verified?" Not-run cases count against it.

Default to *of executed* with the not-run count shown beside it. Anything else surprises people, but the toggle matters because release gates usually want the second.

**Nested suites** roll up: a parent suite's percentage covers its whole subtree, expandable to per-child-suite percentages. Compute from the leaf case results, not by averaging child percentages — averaging percentages over unequal case counts is simply wrong arithmetic, and it's an easy mistake to ship.

**The version question is sharper for suites.** A suite version is its *composition* (§6j), so filtering by version has two legitimate meanings:
- **As-executed** *(default)* — the cases that were in the suite at that time. Reproduces history faithfully; what an auditor wants.
- **Current membership** — today's case list, back-projected over past runs. Answers "how would today's suite have done?"; useful, but it is a reconstruction, not a record.

Label them plainly and default to *as-executed*. The distinction is invisible until a suite's membership changes, at which point silently picking the wrong one makes historical percentages irreproducible.

**Implementation** — pre-aggregate per `(run, suite, configuration)` into a `SuiteRunSummary` rollup as results land, refreshed on late-arriving ingestion. Computing suite percentages over `ExecutionFact` at page load means scanning every execution of every descendant case; for a nightly suite of 500 cases over a year that's hundreds of thousands of rows per page view. Store the counts (passed, failed, blocked, skipped, not-run, total), derive percentages on read, and keep both suite version and membership snapshot on the rollup.

---

### Test plan statistics — current state from the latest execution

The third level of the same idea. A plan page leads with **where this plan stands right now**, computed from the most recent execution of each case in its scope.

This is a different question from the aggregate execution report (§6e), and both are needed. The aggregate report asks *"what happened across the whole cycle?"* and spans many runs; this asks **"are we ready?"** and reflects only the latest state. Mixing them produces a number that is neither.

**Defining "latest".** A plan is typically covered by several runs — a nightly CI run, a manual regression run, a re-run of failures — so "the latest execution" is resolved **per case, not per run**: for each case in the plan's scope, take its most recent execution within the plan. This is the *Latest* aggregation policy from §6e, and reusing it rather than inventing plan-specific logic keeps the plan page and the cycle report mutually explicable.

Where the plan spans multiple configurations, default to **latest per configuration** and show the config matrix — a plan passing on Chrome and failing on Safari must not average into a single reassuring number.

**What the plan page leads with:**

```
Release 4.3 Regression          Plan v2 · 312 cases · 4 configurations

  ███████████████████░░░   89% passed        278 passed · 21 failed · 6 blocked
  Coverage  ████████████████████░  97%        305 executed · 7 not run
  Latest activity: Nightly #418, 2 hours ago
```

- **Pass %** with the same explicit denominator toggle as suites — *of executed* by default, *of total in plan* available, never silent.
- **Coverage** as its own headline figure, not a footnote. "89% passed" over 40% coverage is not a green light, and separating the two makes that impossible to miss.
- **Status breakdown** including custom statuses (§2).
- **Per-configuration matrix** — pass % per environment/browser.
- **Per-suite breakdown** — reuses `SuiteRunSummary` directly, each row linking to the suite history above.
- **Blockers** — failed and blocked cases listed with their defects, sorted by case priority. This is the list a release meeting actually reads.
- **Entry / exit criteria** — where defined on the plan, evaluated against current numbers and shown as met/unmet. Turns the plan page into the release gate rather than a dashboard someone interprets.
- **Not-run cases** — explicitly listed, not merely counted. Unexecuted cases are the most commonly overlooked release risk.
- **Trend sparkline** — pass % over the plan's successive runs, showing whether it's converging or degrading. One line, high value: a plan at 89% and rising means something quite different from 89% and falling.

**Versioning.** A plan version is its *scope* (§6j), so the same as-executed vs current-scope distinction applies as for suites, defaulting to as-executed. Cases added to the plan after the last run appear as *not run* rather than silently deflating the pass rate — the coverage figure absorbs them, which is exactly what it's for.

**Freshness must be visible.** Every figure carries the timestamp of the latest execution it derives from, and stale data (nothing executed in N days) is marked. A plan page showing 94% from three weeks ago, presented as current, is the most dangerous screen in the product.

**Implementation** — a `PlanSnapshot` read model maintained off the same events as `SuiteRunSummary`, holding the current latest-result-per-case per configuration. Updated incrementally as results land, including on late-arriving CI ingestion. Pinned into the evidence bundle (§6n) when the plan closes, so the sign-off numbers are frozen with the artifacts that justify them.

---

## 6q. Search, Filtering & Release Version — Design

### Release version — a new first-class field

**`releaseVersion` on the test run, denormalized onto every execution.** The answer to "what build was this tested against?", and once present it becomes one of the most-used filters in the product.

**Where it lives.** Set on the `TestRun` (a run tests one build) and **copied onto each `TestExecution` and into `ExecutionFact`** at write time. The denormalization is deliberate: nearly every interesting query filters executions by release, and joining back to the run on every one of them is the join that would show up in every slow-query report. It also means a later correction to the run's version does not silently rewrite history — the execution records what it was tested against at the time.

CI ingestion supplies it in the upload payload (§3 phase 2); manual runs prompt for it at run creation, pre-filled from the plan's milestone where one exists.

**Storage and ordering.** Store the raw string exactly as given, plus a **parsed sort key**. Naive string ordering puts `1.10` before `1.9`, which breaks every "latest release" query and every trend chart — and it breaks quietly. Parse semver-shaped values into `(major, minor, patch, prerelease)`; fall back to raw collation for values that don't parse, and don't reject non-semver input. Real teams use `2026.07-rc2` and `build-4471`, and a field that rejects them just gets left empty.

**Related but distinct fields**, all separate columns:

| Field | Meaning |
|---|---|
| `releaseVersion` | What was tested — `4.3.0` |
| `buildNumber` | Which build of it — `#4471` |
| `commitSha` / `branch` | Exact code (§3 phase 2 Git traceability) |
| `environment` / `configuration` | Where it ran (§2) |

Conflating release with build is the common shortcut, and it costs you the ability to ask "did 4.3.0 get better between build 4470 and 4471?" — which is precisely the question during a release stabilization.

**What it unlocks:**
- Filter any history or report to one release (§6p, §6e).
- Pass rate per release, and the regression comparison between two releases — *"what passed in 4.2 and fails in 4.3"*, which is the highest-value comparison in the product and is trivially available once the field exists.
- **First-failed-in release** on a case's history (§6p) — far more actionable than a date.
- Per-release plan statistics and evidence bundles (§6n) scoped to the release being signed off.

### Search

Search covers **test id, name, feature, release version** and the rest of the filterable surface. One search entry point, not a different box per entity.

**By display key** — the fast path, and it needs special handling: exact and prefix matching on the immutable display key (§6m, §6t). Typing `TC-1042` must **jump straight to the case**, not return a relevance-ranked list with it somewhere in the middle. Detect the key pattern in the query and short-circuit. Because display keys are **unique per company** (§6t), this is unambiguous with no project context to resolve against and no qualified second form to parse — one of the concrete simplifications company-scoped numbering buys. A key the caller cannot reach (a project they hold no grant on, §6b) returns **not found**, identically to one that doesn't exist. Keys get pasted from Jira tickets, commit messages and spreadsheets constantly, so this is the single most frequent search in daily use.

**By name** — weighted FTS plus trigram over the title, reusing the §6h index design (title `setweight('A')`, body `'B'`). Handles partial words and typos, which title search must, since people search half-remembered names.

**By feature** — a facet over the hierarchical feature dimension (§6d), selecting a node **and its descendants** by default. Choosing `Payments` must return cases under `Payments › Refunds`; an exact-node-only match makes the hierarchy pointless. Offer an "this node only" toggle.

**By release version** — filters over executions rather than cases, so it answers *"cases executed in 4.3"* and composes with a result filter to give *"cases that failed in 4.3"*. Support ranges and "latest N releases", since comparing consecutive releases is the main use.

**Everything composes.** Filters are predicates over one query model, so `feature:Payments release:4.3 result:failed automationStatus:manual` is a single query with no bespoke endpoint. This is the same mechanism the dashboard tiles (§6o) emit, and reusing it means dashboard drill-downs land in the ordinary search UI with the filters visible and editable.

**Two interfaces over one engine:**
- **Faceted UI** — the default. Filter chips with counts, discoverable, no syntax to learn.
- **Query syntax** for power users — `project:OLB feature:Refunds release:>=4.2 status:approved`, with free text matching name and description. Anything expressible in the UI is expressible in text and vice versa, and the URL carries the query so results are shareable (consistent with §6o and §6p).

**Saved searches** — named, personal or shared with a team (§6b), and reusable as report definitions (§6e) and kanban board filters (§6l). "Failed in 4.3 under Payments" is a view people return to daily during a release; making them rebuild it each time is the difference between a tool that's used and one that's tolerated.

**Scope** — search defaults to the current project, with an explicit all-projects mode that respects §6b permissions, so results never reveal a project the caller can't read.

**Implementation** — one `SearchQuery` model compiled to SQL against the case table and `ExecutionFact` (§6e), reusing the §6h GIN indexes. Release version, feature ids and result status all need indexing on `ExecutionFact`; that table is already the join target for these questions, which is why the release denormalization above matters. Cap results, paginate, and return facet counts in the same round trip — computing counts in a second request makes the UI flicker.

---

## 6r. External Identity & Integration Credentials — Design

### Three concerns, deliberately not conflated

The requested list — Cognito, Microsoft, Google, Jira — spans **three different problems** that share the word "OAuth" and nothing else. Treating them as one system is the most consequential mistake available here, so they are separated first:

| Concern | Question it answers | Mechanism | Lifetime |
|---|---|---|---|
| **Authentication (SSO)** | Who is this human? | OIDC / SAML against the org's IdP | One login session |
| **Outbound integration authorization** | May TCMS act against Jira / Slack / GitHub? | OAuth 2.0 3LO, PAT, or service account — **per integration, per project, within one company** | Months; refreshed independently |
| **Inbound machine access** | May this CI job post results? | API tokens / client credentials, scoped to one company (§6b) | Long-lived, revocable |

**Jira is not an identity provider for this product.** Atlassian does offer OAuth 2.0 3LO and you *could* sign in with it, but coupling login to Jira means every TCMS user needs a Jira licence, losing Jira access locks people out of their own test history, and the token needed to *create a defect* has a different scope, consent model and lifetime than a login session. Authenticate against the organization's IdP; authorize Jira separately as an integration. The same applies to Slack and GitHub.

Google and Microsoft appear in **both** columns and that is exactly why the distinction matters: Google Workspace is a legitimate IdP *and* the owner of Drive credentials; Entra ID authenticates people *and* guards Teams webhooks. One is a login, the other is a stored credential, and they must not share a code path.

### Identity providers — one OIDC path wherever possible

| Provider | Protocol | Provider-specific notes |
|---|---|---|
| **AWS Cognito** | OIDC | Access tokens carry **no `aud`** and must be checked for `token_use=access` — the standard Cognito trap. `cognito:groups` is the group claim. |
| **Microsoft Entra ID** | OIDC | Tenant-scoped issuer. `roles`/`groups` require app-registration configuration, and groups may arrive as opaque GUIDs rather than names — see the overage warning below. |
| **Google Workspace** | OIDC | Use the `hd` claim to restrict to your domain, or anyone with a Google account can attempt login. **No group claims by default** — group-based role mapping needs the Admin SDK Directory API. |
| **Okta** | OIDC | Groups claim configurable per authorization server. |
| **Keycloak** | OIDC | Self-hosted; the natural companion to a self-hosted product. |
| **Generic OIDC** | OIDC | Discovery URL + client id/secret. Covers everything not named above and is what makes this list non-exhaustive. |
| **LDAP / Active Directory** | LDAP(S) bind | **In scope for M4.** Not OIDC at all — a genuinely separate code path. See the subsection below. |
| **SAML 2.0** | SAML | ADFS and older enterprise IdPs. A **second code path** — build only on demand, and say so rather than implying parity. |
| **Local accounts** | — | Always retained. See below. |

Implementation is **one generic OIDC client driven by per-provider configuration**, not seven integrations. The provider-specific rows above are configuration and claim-mapping differences, not separate adapters — with two honest exceptions: SAML, and LDAP/AD, which are genuinely different protocols.

### LDAP / Active Directory — the on-prem case

Included deliberately, because the OIDC list above assumes an issuer that many on-prem self-hosters simply do not have. A large share of target deployments run Active Directory with no OIDC endpoint in front of it, and telling those teams to stand up Keycloak first is telling them to evaluate a different product.

It is **not** an OIDC provider with different settings — it is a directory you bind against, and the differences that matter are these:

- **Authentication is a bind, not a redirect.** The user's credentials arrive at TCMS (over the local login form) and TCMS binds to the directory as that user. There is no browser redirect, no authorization code, no PKCE. Consequence: **LDAPS or StartTLS is mandatory, not recommended** — a plain-LDAP bind puts the user's directory password on the wire, and unlike OIDC there is no third party absorbing that risk. Refuse to save a provider config with an `ldap://` URL unless an explicit `allowInsecure` flag is set for lab use.
- **The stable identity key is `objectGUID` (AD) or `entryUUID` (OpenLDAP) — never the DN.** A DN encodes the user's position in the tree, so moving someone between OUs changes it, and keying `user_identity` on the DN silently orphans the account and its authorship. This is the LDAP equivalent of §6r's "never key on email" rule, and it is the single most common way LDAP integrations break. Store the DN as a mutable attribute for display and re-bind, not as the key.
- **`user_identity` still applies unchanged.** An LDAP identity is `(company_id, issuer = provider id, subject = objectGUID)`. It slots into the existing table with no schema change, which is what makes adding it cheap.
- **Groups come from a search, not a claim.** Either read `memberOf` on the user entry or search groups for `member=<dn>`, configurable — directories differ, and `memberOf` is not always populated. **Nested groups need an explicit recursive resolution** (or AD's `LDAP_MATCHING_RULE_IN_CHAIN`), bounded by a depth limit; unbounded recursion on a large directory is a real outage.
- **Group → role mapping reuses §6b's `claim_role_mapping` exactly.** An LDAP group is just another `(provider, claim, value)` tuple, and every rule in "Claim → role mapping" below applies without modification: map to roles or teams, never permissions; IdP-derived grants are read-only in the UI; unmapped groups grant nothing.
- **Service-account bind for lookups**, with credentials stored per §6r's encrypted-credential rules, and connection pooling — a bind per request is slow enough to be noticed.
- **Deprovisioning is a poll, not a push.** LDAP has no logout or removal notification, so a scheduled reconciliation deactivates users no longer present (§6r's "SSO never deletes users" rule holds). Until SCIM lands (phase 3), this poll *is* the deprovisioning path.
- **Referrals and paging** — AD returns referrals and caps result sizes; unhandled, group resolution silently truncates for exactly the largest groups. Same failure shape as the Entra overage claim below, and worth handling at the same time.

Spring Security has first-class LDAP support, so the work is configuration, group resolution and the identity-key discipline above — not a protocol implementation.

### Provider configuration is per company

Each company configures its own providers — Acme authenticates against Entra, Globex against Keycloak, on the same instance:

- `identity_provider` carries a `company_id`; a Company Admin configures their own and cannot see another's.
- **`(provider_issuer, subject)` is unique per company, not per instance.** The same human logging into two companies from the same corporate IdP is one `app_user` with two `company_membership` rows — but a *different* company's IdP asserting the same `sub` must never resolve to that user. Key `user_identity` on `(company_id, issuer, subject)` and treat issuer trust as company-local. Getting this wrong is the multi-company account-takeover vector.
- **Login-screen routing, with no pre-authentication company picker.** Because companies are separate clients, an unauthenticated caller must never be shown a list of companies — that leaks your client roster. The login form takes an email, resolves the domain to a company via a configured `email_domain → company` map, and redirects to that company's provider. When the domain maps to **several** companies, the user authenticates first and *then* chooses among their own memberships; when it maps to **none**, the response is identical to the mapped case (generic "check your email / contact your administrator"), so login cannot be used as an oracle for which companies exist.
- **Per-company login URLs are the better path where clients expect their own entry point** — `/{companySlug}/login` resolves one company's providers directly, with the generic form as fallback. It also gives each client a brandable landing page without per-company custom domains.
- **Claim→role mappings are company-scoped** and may only grant roles within that company. A mapping rule that could grant `SYSTEM` scope is rejected at save time, not just filtered at login — an IdP group must never be able to mint an Instance Admin.
- **JIT provisioning creates the user *and* the company membership**, both within the company whose provider authenticated them, and nothing else.
- **The break-glass Instance Admin authenticates locally, at the instance level**, unaffected by any company's IdP being misconfigured. This is the account that fixes a broken company (§6s).

### Local accounts never go away

Non-negotiable for a self-hosted product:
- It must be installable and usable **before** any IdP is configured.
- It must survive IdP misconfiguration or outage — an unreachable IdP cannot mean nobody can log in to look at the test results.
- There must be a **break-glass Admin**. §6b already requires at least one Admin to always exist; that account must be able to authenticate locally.

So SSO is **additive**: local auth remains a first-class path, and a project may run any mix.

### Flow

- **Authorization Code + PKCE** in the browser. TCMS is the confidential client.
- **TCMS issues its own session token after callback and does not forward the IdP token to the SPA.** Three reasons: an IdP access token is scoped for the IdP's APIs and not ours; its lifetime is wrong for our session; and keeping our own session means the entire §6b permission model stays unchanged. The IdP proves identity once — TCMS owns the session.
- **Refresh tokens are held server-side**, never in the browser.
- **Logout** always invalidates the local session. RP-initiated logout at the IdP is opt-in per provider, because signing a tester out of every corporate app because they closed a test run is rarely what anyone wants.
- Multi-tab: a logout broadcasts to sibling tabs (already required by the frontend state rules) so one tab can't keep rendering as signed-in.

### Account identity, linking, provisioning

- **Identity is `(provider, subject)` — never email.** Emails change, get reassigned, and are not unique across providers. The IdP `sub` is the stable key.
- A **`user_identity`** table links identities to one TCMS user, so a user may hold several (local + Entra during migration, or two IdPs after an acquisition). This is what makes moving to SSO possible without recreating every account.
- **Email is a linking hint, never the key.** On first SSO login, matching an existing user by email is allowed **only when the IdP reports the email verified**, and it is a configurable policy — auto-linking on an unverified email is an account-takeover vector.
- **JIT provisioning is configurable per provider**: create the user on first successful login as a **Viewer with no project access** (the same default as an invitation — §6b — so SSO cannot be a side door to broader capability than the invite flow grants), or require a pre-existing invitation. **Default to invitation-required**; auto-create is correct only when the IdP is authoritative for the whole company, which for an internal tool is often true but should be a deliberate choice — and it is a *per-company* choice, since one company's IdP being authoritative says nothing about another's.
- **Deprovisioning: SSO never deletes users.** An IdP-removed user is deactivated (§6b `DEACTIVATED`), preserving authorship on every version, result and audit event (§6j). SCIM-based provisioning is a later addition, not v1.

### Claim → role mapping — where SSO meets §6b

This is the seam where an identity system can quietly destroy an authorization model. It must not bypass §6b:

- **Map IdP groups/claims to TCMS roles or teams — never directly to permissions.** A rule is `(provider, claim, value) → role | team, scope`. Roles remain the only thing that carries permissions.
- **Mapped grants are marked IdP-derived and are read-only in the UI.** Otherwise an admin edits a grant that the next login silently overwrites — one of the most confusing failure modes in enterprise software.
- **Unmapped claims grant nothing. Fail closed.** A new group appearing in the IdP must never confer access by accident.
- A user may hold both IdP-derived and locally-granted roles; **effective permissions remain the union** (§6b) with no new precedence rules. The "no explicit deny" decision holds — SSO does not introduce one.
- **Entra ID group-claim overage:** past roughly 150–200 groups, Entra drops the `groups` claim and substitutes a Graph API pointer. Unhandled, role mapping silently stops working *for exactly the users who are in the most groups* — typically the senior people. Handle the overage claim explicitly or document the limitation.
- Group names are unstable in some IdPs (Entra GUIDs); map on the immutable identifier and store a human label for display.

### Outbound integration credentials

A separate table with a separate lifecycle from user sessions:

- **Per integration, per project** — Jira, Slack, Teams, GitHub/GitLab, SMTP.
- **Two modes**, chosen per integration:
  - **Service credential** — one account acts for all of TCMS. Simpler, one consent, no per-user licence requirement. The audit trail shows "TCMS" as the actor.
  - **Per-user OAuth (3LO)** — actions are attributed to the real person. Better audit, but every user must consent and hold a licence in the target system.

  Recommend **service credential as the default, with per-user available specifically for defect creation** — because "who filed this bug" is a question people actually ask, while "who sent this Slack notification" is not.
- **Encrypted at rest** with a key from configuration or KMS. Never plaintext in Postgres, never in an audit event, never in an error message or log line.
- **Expiry and refresh state surfaced in project settings.** An expired Jira token that silently stops creating defects — while runs keep passing and failing — is the realistic failure, and it is invisible unless the UI shows credential health.
- Sits behind the existing `IssueTracker` / notification ports (§5), so credential handling is per-adapter and adding Azure DevOps later doesn't touch the identity code.

### Placement: with the integrations, not in phase 1

**Phase 1 ships local accounts only** (§3). External identity lands in the integrations phase (M4), for two reasons that hold up:

1. **It shares machinery with the integrations.** The OIDC client, encrypted credential storage, token refresh, and consent handling are the same plumbing Jira/Slack/Teams need. Building them once, together, is cheaper than building an auth-only version and then generalizing it.
2. **Deferring is genuinely cheap here** — a claim worth justifying rather than asserting. Every table anchors on `app_user.id` (a UUID), with `email` as an ordinary unique column and `created_by` FKs pointing at the user row. Adding federation is therefore **purely additive**: create `identity_provider` / `user_identity` / `claim_role_mapping`, backfill one `local` identity row per existing user, add `source` to `role_assignment`. No authored artifact's `created_by` moves, and no existing row is rewritten.

The one thing phase 1 must **not** do is key identity on email, or let `created_by` reference anything other than `app_user.id`. Both are already the case in the ERD, which is what makes this deferral safe rather than a bet.

Consequences when it does land:
- Provider configuration is **admin UI + environment config**, so a self-hoster wires Cognito or Keycloak without a rebuild. It is built **company-scoped from the start** (`identity_provider.company_id`, keyed per §6s) even while only the default company exists — the column and the composite key cost nothing in M4 and are a painful retrofit in phase 4, because reissuing every `user_identity` key is exactly the migration that risks mislinking accounts.
- The login screen gains provider buttons driven by the configured providers, with the local email/password form retained.
- Existing users are linked, not recreated — which is exactly what `user_identity` exists for.

---

## 6s. Multi-Company Tenancy — Design

One instance serves several companies. A company is the **top-level isolation boundary**: projects, teams, test assets, documentation, vocabularies, integrations, tokens and identity providers all belong to exactly one, and nothing crosses.

**Sequencing, stated plainly:** the *column, the filter and the isolation proof* land in **M0**; the small user-facing tail (switcher, per-company IdP routing, `COMPANY`-scope assignment UI) is the **last phase** (§3 Phase 4, M6). That split is the whole trick — the expensive, irreversible half of tenancy is the discriminator threaded through every table, index and uniqueness constraint, and it must not be deferred. What *can* be deferred is small.

**Adding a company is not a product feature.** It happens once per company, at onboarding, via a **provisioning script** run by the operator — not through an admin UI. That single decision removes most of what a multi-tenant product normally costs: no company CRUD screens, no instance operations console, no archive/delete flows, no quota management UI. What remains is the schema discipline, the proof that it works, and about a week of surface.

**What the script does not excuse.** A row created by hand still needs code to mean anything:
- A `status = SUSPENDED` column no code reads suspends nothing. Decide which lifecycle flags exist and honor them, or don't add the columns.
- The provisioning script is a **tested, idempotent deliverable** — a Spring Boot CLI command or a parameterized SQL function — not a wiki page of `INSERT`s. A company needs its built-in roles, classification vocabularies, execution statuses, default settings and first Company Admin seeded consistently; hand-rolled inserts produce half-provisioned companies whose failure mode is confusing application errors discovered weeks later. That debugging costs more than the script.
- **Deletion is not a hand operation.** FK cascades across ~60 tables is how you corrupt a database. Default policy is **suspend, never delete**, which avoids writing a purge script at all. If a company must genuinely be removed, it is an export followed by a scripted purge, both tested.

### Why a company, not just more projects

A project boundary already exists, so the fair question is what a company adds. Three things a project cannot express:

1. **A separate people space.** Two companies have different teams, different org charts, and users who must not see each other. Project membership can hide *content*, but the assignee picker, the mention autocomplete, the org chart and the search index all leak the *people* — and those are the leaks nobody notices until a customer does.
2. **A separate configuration space.** Statuses, classification vocabularies, review policy, IdP config and integration credentials are per-company decisions. Modelling them per-project means either duplicating them N times or sharing them across a boundary they shouldn't cross.
3. **A separate administrator.** The requirement driving this: an Instance Admin appoints administrators per company who administrate *only* their companies. Without a company row there is nothing for that grant to point at, and "Test Lead on all 14 of these projects" is not the same thing — it silently fails to include the fifteenth project created next week.

### Isolation strategy: shared schema with a discriminator

| Option | Verdict |
|---|---|
| **Shared schema, `company_id` discriminator** | **Chosen.** One migration run, one connection pool, one deployable. Isolation is enforced by a globally-applied Hibernate filter plus repository-layer predicates, optionally backed by Postgres RLS. |
| Schema per company | Rejected. Migrations multiply by company count and Flyway across N schemas becomes a deploy-time risk that grows with success. Connection pooling and the JPA metamodel both fight it. |
| Database per company | Rejected for v1. It is the honest answer for mutually hostile tenants, but our companies are business units or clients of one operator, and the operational cost — N backups, N migrations, N pools — is disproportionate. Noted as the escape hatch if a company ever needs physical separation for a compliance reason; the `company_id` design does not preclude extracting one company later. |

Concretely:

- **`company_id NOT NULL` on every company-owned table** — including join tables, version tables, audit events, read models and the raw CI payload store. A table without it must be one of the deliberate instance-level exceptions listed below, and a schema test enforces exactly that list.
- **A Hibernate `@Filter` enabled per session** from a request-scoped `CompanyContext`, populated by a servlet filter from the authenticated session — never from a query parameter, header or path segment supplied by the client. The path segment is for readable URLs and is *validated against* the session's company, not trusted as its source.
- **`company_id` leads every composite index** and every uniqueness constraint that was previously global: project key, team name, API token name, company-scoped classification values (Test Type / Level / Phase), **display-key counters and `automationKey`** (§6t, §6c). Project-scoped identifiers — condition titles, shared steps, execution status names, Feature values, free tags — are unique within their project and therefore company-unique by construction, since a project belongs to exactly one company.
- **UUID primary keys, so foreign ids are neither reachable nor enumerable.** Sequential ids plus a filter bug degrade into an enumerable oracle; UUIDs mean a missed filter is still not a browsable one.
- **Postgres RLS enabled by default** — policies on `company_id` with the app setting the tenant GUC per transaction. **Decided: on, because companies are separate clients** (§1). It costs a little planner time and buys a database-level guarantee, which is the difference between "our filter is correct" and "the database will not serve another client's rows even if the application forgets." Documented as disableable for the performance-sensitive, but the shipped default is on. For business units this would be optional; for separate clients it is the answer to "prove our data is separate."
- **Deliberate instance-level tables (no `company_id`):** `app_user` (one identity, many companies), `company`, `permission` catalogue, built-in role templates, Flyway history, instance settings, instance audit log. Nothing else.

### Users span companies; everything else does not

**Decided: one account may belong to several companies, holding independent permissions in each.** This is a requirement, not an edge case — so the model is `app_user` at instance level with `company_membership` as the join, and email unique **instance-wide**.

The rejected alternative was a user row per company with email unique per company. It is genuinely simpler — no switcher, no cross-company leak surface, no disclosure problem — but it makes "the same person in two companies" two unrelated accounts with two passwords and two audit identities, which is exactly the thing that must work here. Ruled out on the requirement, not on elegance.

What "independent permissions per company" means concretely, and it falls straight out of §6b rather than needing new machinery:

- Grants are `(user, role, scope, target)` edges. Ana holding Company Admin on *Acme* and Test Engineer on one project in *Globex* is two rows; neither informs the other.
- Resolution is always `effective(user, project)` **within one company**, gated on membership of that company. There is no global effective-permission set for a user, and no endpoint should offer one.
- Switching companies re-resolves permissions from scratch. The permission cache is keyed including the company id, so a stale entry from company A can never answer a question about company B.
- The audit trail records the company on every event, so "what did Ana do" is always answered per company. An Instance Admin can correlate across companies; nobody else can.

The consequence to handle honestly: **an instance-unique email means one company's admin can discover that an address already exists.** Mitigations, all required:

- The invite flow **never** reports whether the address is already a user. It always says "invitation sent". If the user exists, they receive a "you've been invited to Acme" mail and accept from inside the product; if not, they get a signup link. Same response either way.
- Membership is never implicit. An existing user gains nothing in the new company until they accept, and **acceptance grants Viewer with no project access** — the correct default when companies are separate clients, since a contractor joining client B must not land with edit rights or a project list. Project grants are an explicit, separate act by that company's admin.
- A user's company list is theirs alone. No admin, at any scope below Instance Admin, can see which other companies a user belongs to.
- Display names and avatars are **per-membership**, not global — a contractor may be "A. Kotrulev" in one company and "Aleks (Vendor)" in another, and neither company's directory should be able to overwrite the other's.

### Request scoping and URLs

- Every company-scoped route carries the company slug: `/c/{companySlug}/projects/{projectKey}/cases/{ref}`. Readable, shareable, and it makes a wrong-company link fail loudly rather than silently resolving to whatever the session had active.
- **The session holds the active company.** A request whose path company ≠ session company either switches (if the user is a member) or 404s — **404, not 403**, because a 403 confirms the company exists.
- **Switching companies re-issues the session token** with the new active company and clears the permission cache. It does not clear the login.
- API clients pass the company in the path too, and an API token is bound to one company at issue time — the path must match or the call is rejected.

### What isolation must be proven, not assumed

**This is an M0 deliverable, not an M6 one** — and it is the single most important correction to the sequencing. A `company_id` column that has only ever held one value is not isolation, it is an untested claim: the filter, the uniqueness constraints and the async context propagation are all unexercised until a second company exists. Discovering they are wrong on the day you onboard a real second company is the worst available timing. Two fixture companies in CI from the start is what converts the hedge into a fact, and it costs days.

The harness, not the UI, is the actual deliverable:

- **A paired-fixture integration suite:** provision two companies with deliberately identical-looking data (same project key, same case references, same team names, same user email as a member of both), then exercise **every** read endpoint as company A and assert byte-for-byte that no company-B row, name, id or count appears. Written once, it catches the leak that a code review never will.
- **A third fixture persona: a company member with no project grant.** Exercise the same endpoints and assert they see nothing — no project names, no counts, no search hits, no dashboard tiles. This is the §6b third-tier rule, and it fails quietly in exactly the places the cross-company assertions don't cover, because those rows *are* in the caller's company.
- **A cross-company user fixture:** the same account holding Company Admin in A and Test Engineer in B, asserting that acting in B never resolves A's elevated permissions — including immediately after switching companies, which is where a stale permission cache would show up.
- **A schema test** asserting every table not on the exception list has `company_id NOT NULL` and leads its indexes. New tables fail the build until they comply — the only mechanism that survives a year of feature work.
- **An ArchUnit rule** that no repository interface declares a native or JPQL query bypassing the filter without an explicit, reviewed `@InstanceScoped` annotation.
- **A test that async paths carry the company** — an ingestion job, a report generation, a scheduled purge and an event handler each executed with a foreign company active, asserting they still operate on their own company's rows. Thread-local context loss across an `@Async` boundary is the realistic leak, and it is invisible in synchronous tests.

### Company lifecycle — operator-run, not a UI

| Operation | How | Where |
|---|---|---|
| **Create / provision** | `provision-company` CLI command: create the company, seed built-in roles, classification vocabularies, execution statuses and default settings, create the first Company Admin as an invitation, write an instance audit event. One transaction, idempotent, re-runnable. Seeds are **copied in**, so a company's later edits are its own. ~~CLI only.~~ → **also from the instance operator console since 2026-08-07** — the same service behind `POST /api/v1/instance/companies`, so re-running to top up a company after a release is a button rather than an SSH session. The CLI survives as break-glass. | M0 (script), tested from M0; **UI in M6** |
| **Assign / change company administrators** | `COMPANY`-scope role assignment. The only lifecycle operation frequent enough to want a UI, since leads change far more often than companies are created — so the existing role-assignment screen learns the `COMPANY` scope. | M6 (UI); DB insert works before that |
| **Suspend** | `status = SUSPENDED`, honored in code: all non-Instance-Admin access refused, ingestion returns 503 with a clear reason, data untouched. The answer to "we're offboarding a client next month", and far safer than deletion. **Note as of 2026-08-07: the column has had a writer (`Company.suspend()`) and NO READER anywhere in `src/main` since V1** — "the check is trivial" is true and the check does not exist, so suspension is currently a no-op. Nothing exposes it, so nothing is broken today; it becomes a defect the moment a suspend control ships. Both land together in M6. | M6 (the check is trivial — **and still unwritten**) |
| **Delete** | **Default policy: don't.** Suspend indefinitely instead. If genuinely required, it is a scripted export followed by a scripted purge across every table plus the `company/{id}/` storage prefix — never hand-run SQL against ~60 tables of cascades. | On demand, not scheduled |
| **Export** | Whole-company export (data + attachments), scripted. **Required, not optional, because companies are separate clients** — "we're leaving, give us our data" is a contractual request, and it is also what makes the database-per-company escape hatch answerable. | Script, alongside provisioning |

No archive state in v1 — suspend covers the same need and one fewer state is one fewer thing to get wrong in the permission evaluator.

### Operator access to client data

Companies are separate clients, so the Instance Admin's reach into their content (§6b) needs handling as a disclosed capability rather than an unexamined convenience:

- **Audited in the client's own trail.** Instance-operator reads and writes inside a company land in that company's audit log, flagged as operator access, not just in the instance log. This is cheap — one flag on the audit event — and it is the difference between an auditable arrangement and a backdoor.
- **No cross-company aggregate view of content.** The operator can enter a company and can see instance-level counts and health, but there is no screen that renders two clients' test data side by side. Nothing needs it, and building it creates a surface where a filter bug becomes a cross-client disclosure.
- **Support impersonation, if ever built, is explicit and time-boxed** — never a silent session swap. Not in v1; noted so it doesn't get added casually.

**Client-facing isolation evidence is deferred** — a nice-to-have, not a v1 deliverable. The isolation itself is v1 and non-negotiable (the harness, RLS, the schema test); what's deferred is *packaging it for a client* — a human-readable architecture note, an exportable summary of the RLS policies, or a report showing the isolation suite passing. Cheap to produce later precisely because the harness and policies already exist and are versioned; it becomes worth doing the first time a client's security review asks. Until then a green CI build is the evidence.

### Noisy-neighbour containment

One instance, several companies, one Postgres — so contention is real. But with a handful of companies onboarded by script rather than dozens self-signing-up, this is a **watch-it-then-fix-it** concern, not an up-front build:

- **Ship immediately (near-free):** Micrometer tags by company on request, ingestion and report timers, so the operator can answer "which company is making the instance slow" the first time it's asked. Company count is small and bounded, so this is a safe tag — unlike project or user.
- **Ship when a second company actually generates load:** per-company concurrency caps on ingestion and report generation, and per-company queue fairness (round-robin across companies, not FIFO across the instance) so a 500k-result nightly upload in one company can't stall another's execution page.
- **Ship if storage becomes contended:** per-company storage quota with a soft warning and a hard stop on attachment upload. Evidence is the growth risk (§6n) and it is the resource one company can exhaust for everyone. The `company/{id}/` key prefix already makes measuring it a directory operation.
- **Ship with the ingestion endpoint:** per-company rate limits keyed on the token's company. This one is cheap enough to do at the same time as the endpoint's existing rate limiting.

### Deliberate limits

- **No cross-company anything.** No shared cases, no shared conditions, no cross-company reports, no instance-wide search. If two companies genuinely need one test asset, they are one company.
- **Branding is a logo, two colours, a display name and a favicon** (§6u) — no custom CSS, no custom fonts, no per-company layout, no custom domains, no billing. Enough for a client to see their own product; not enough to make every upgrade a per-client regression risk.
- **No per-company schema or database in v1.** Documented escape hatch, not a feature.
- ~~**No company management UI**~~ → **superseded 2026-08-07: there is one, folded into M6** (`docs/plans/2026-08-07-instance-operator-console.md`). **No self-service signup — that half still stands.** Companies are still provisioned only by an Instance Admin, now through a console as well as the script; nobody signs themselves up, and the company roster is never exposed pre-authentication. Company count therefore stays small and bounded, so the decisions that rested on that (metric tags, in-process caches, seeded copies, deferred quotas) are unaffected. What the reversal costs is the "user-facing tail is about a week" estimate, which roughly doubles.
- **No cross-company view for any non-Instance-Admin user.** A user in three companies works in one at a time; there is no unified inbox, no combined kanban board, no "all my assignments everywhere" screen. Tempting, and it is the fastest way to leak one company's data into another's UI. If it is ever built, it must be a deliberate, separately-audited surface listing only titles and companies the caller is a member of.

---

## 6t. Display Keys — Design

**Decided:** UUIDs are the real keys everywhere in the backend. On top of them sits a purely human-facing `displayKey` of the form **type prefix + number**, numbered **from 1 per company** and unique across **all of that company's projects**.

```
TC-1    test case          TP-1   test plan
TS-1    test suite         TR-1   test run
DOC-1   documentation page
```

Acme has exactly one `TC-1`, and it lives in whichever project created it. No key for conditions, shared steps, defects (Jira owns those), review requests, artifacts, milestones or configurations — each is reached from its parent or picked from a typeahead, and every extra prefix is another counter row plus another ambiguous token in search.

**No automation prefix.** `automationStatus` (§6c) carries that, and it is designed to change over a case's life; an immutable key encoding automation state starts lying the day the case gets automated.

### Why company scope, and what it removes

A company holds a handful of whole client projects (§1 — a Project is a full product or engagement, not a feature area), so one namespace per client is comfortable. The payoff is that **`TC-1` is unambiguous everywhere inside a company**, which deletes an entire class of design problem that per-project numbering would have created:

- **One form, everywhere.** Reports (§6e), search results, kanban cards (§6l), coverage matrices, notifications and Jira defect bodies all render `TC-1`. Showing the owning project alongside it is a display nicety, not a disambiguation requirement.
- **Search is trivial.** A key pattern in the query short-circuits straight to the item — no project context to resolve against, no ambiguous-match list, no qualified second form to parse (§6q).
- **Moving a case between projects is a non-event.** The key travels with it and cannot collide, because the namespace is the company. Cross-company moves don't exist (§6s).
- **The API still never accepts a display key as a path identifier.** Paths take UUIDs; keys resolve through an explicit lookup endpoint (`GET /companies/{id}/resolve?key=TC-1`). Display keys are trivially guessable and UUIDs are not, so this stays a hard rule.

### The one cost worth naming: an inference channel

Because the sequence is company-wide, a user granted one project can infer the company's total case volume from key numbers, and can try `TC-N` for a case in a project they cannot see. Two existing rules contain it, and both are load-bearing rather than incidental:

- An unauthorized or unresolvable key returns **not found**, never forbidden — identically, so the response cannot be used as an oracle (§6b).
- Display keys never appear in API paths, so there is no route to enumerate.

Per-project numbering would have leaked less, but at the cost of the ambiguity handling above. This is the accepted trade.

### Allocation

- **A counter row per `(company, entityType)`**, incremented with `UPDATE … RETURNING` so the row lock serializes allocation. Not `MAX(n)+1`, which races under concurrent creation and eventually issues duplicate keys — the classic version of this bug.
- **Bulk creation and CI quarantine auto-create must reserve N numbers in one batch**, then assign. All case creation in a company serializes on one counter row — microseconds per increment, but importing 5,000 cases one round trip at a time would be pathological.
- **Gaps are acceptable and will happen.** A rolled-back transaction consumes a number. Do not reclaim them: gap-free numbering requires serializing all creation, and nobody cares that `TC-7` never existed.
- Uniqueness constraint is `(company_id, entity_type, number)` — `company_id` leading, consistent with §6s.

### Immutability

- The key is **assigned at creation and never changes**, including when a case moves to another project within the company. Since the namespace is company-wide, a move can never collide.
- Deleted keys are never reissued.
- "Moved from" is part of the case's audit trail (§6j) — the key doesn't record it, and shouldn't.

---

## 6u. Per-Company Branding & Identity in the UI

Companies are separate clients (§1), so each one's users should see their own organization's product, not the operator's. Branding is **company-scoped configuration**, set at provisioning and editable by a Company Admin.

| Element | Scope | Notes |
|---|---|---|
| **Logo** | Company; project may override | App bar, login page for that company (`/{slug}/login`, §6r), report headers (§6e), exported PDFs. |
| **Primary + accent colour** | Company | Drives the MUI theme at runtime. **Two colours, not a stylesheet** — see the constraint below. |
| **Display name** | Company | Used everywhere the product refers to "your organization", in report titles and in email subjects. |
| **Favicon** | Company | Cheap, and it matters when a tester keeps three clients' tabs open. |

### The constraint that keeps this from breaking the product

**Clients pick colours from a constrained palette, not arbitrary CSS.** §6 commits to WCAG 2.1 AA on the execution screens, and a client-chosen brand colour is exactly how that commitment silently breaks — a light-on-light accent renders a run's Pass/Fail buttons unreadable.

- Accept a primary and accent colour, then **derive the full theme programmatically** (MUI palette generation), computing contrast-safe foreground colours rather than accepting them.
- **Validate contrast at save time** and reject or auto-adjust a colour that can't meet AA against the surfaces it will be used on. Tell the admin what was adjusted; don't silently ignore their choice.
- **Semantic status colours are not brandable.** Pass/Fail/Blocked/Skipped keep their meanings instance-wide. A client whose brand colour is red must not end up with red "Passed" chips, and testers moving between two clients' instances must not have to relearn what green means.
- No custom CSS, no custom fonts, no per-company layout. Those are unbounded support commitments for a self-hosted tool, and they turn every upgrade into a per-client regression risk.

### Per-membership display names

**Decided (§6s):** a user's display name and avatar are attributes of their *company membership*, not of the global account. One person may be "A. Kotrulev" in one client and "Aleks (Contractor)" in another; neither client can see or overwrite the other's.

- The global `app_user` keeps a canonical name for the Instance Admin's directory only.
- Every user-facing rendering site — assignee pickers, mentions, execution history, audit entries, review comments, report author lines, org chart, kanban avatars — resolves the name through the **active company's membership**. This is why it must be decided in M0: it's a resolution rule threaded through every surface that shows a person, and retrofitting it means auditing all of them.
- Historical records render the name **as it was at the time** where the audit trail already snapshots it (§6j), and the current membership name otherwise. Don't retroactively rewrite who did what.

---

## 6v. Plan Templates & Cloning — Design

Three operations that look like one feature and are not: **clone a case**, **clone a run**, and **instantiate a plan from a template**. All three exist because re-assembling work by hand is the reason teams keep a spreadsheet alongside the TCMS.

### Why a template is not just a clone

| | **Clone** | **Template** |
|---|---|---|
| Produces | A copy, detached immediately | A new plan built from a definition |
| Relationship to source | None after creation | The template persists and is reused |
| Content | Whatever the source had, frozen at copy time | A **selection query** re-evaluated at instantiation |
| Right for | "Like this one, but different" | "Every release, we run this" |

The distinction that matters: **a plan template stores the *query*, not the case list.** "All Approved cases in `Payments` tagged Regression, on Chrome and Firefox" instantiated next release picks up the twelve cases added since — a clone would not. Storing a frozen case list would recreate the spreadsheet problem in the database, and it is the single design decision this section exists to get right.

```
TestPlanTemplate
  ├─ name, description, projectId
  ├─ selection        the §6q filter expression (facets, classification, status, feature subtree)
  ├─ configurations[] the env/browser/OS matrix to create runs for
  ├─ exitCriteria     carried onto instantiated plans (§6p)
  ├─ defaultAssignees team or user pool
  └─ milestone?       optional default
```

**Instantiation is explicit and previewable.** Selecting a template shows the resolved case list *before* creating anything — count, and what changed since last time ("+12 new, −3 deprecated"). Silent resolution is how someone runs a release plan that quietly lost half its coverage.

**Reuse existing machinery, don't duplicate it:** `selection` is a §6q saved-search expression, and instantiated plans are ordinary `TestPlan` rows with no template pointer at execution time — so nothing in §6p statistics or §6e reporting needs to know templates exist.

### Cloning — the rules that keep it safe

Clone is cheap to build and easy to get subtly wrong. The rules:

| Entity | What copies | What deliberately does **not** |
|---|---|---|
| **TestCase** | Steps (either format, §6a), conditions, classification, parameters, custom fields, attachments (~~by reference, not byte copy~~ → **byte copy, §129**) | Version history, execution history, review state (**resets to Draft**), display key (**newly allocated**, §6t), `automationKey` (**never** — it is unique per company, §6c) ~~and nothing else~~ → **and the `AUTOMATED` status with it, §129** |
| **TestPlan** | Case selection **including §97's sections and the tag arm**, configurations, **both** criteria lists, both scope-prose fields, milestone | Runs, executions, results |
| **TestRun** | The plan reference, case set, configurations, ~~assignees~~ → **§129: a run has no assignee to copy** (the column is on `test_execution`, one per case); the clone offers to assign the whole checklist instead | **All results, evidence, defects, and timestamps** — a cloned run starts Untested. **And the approval-gate override (§129)**: it is a statement about a request, nothing stores it, and inheriting it would make one documented 2am exception permanent |

Four rules that apply across all three:

1. **`automationKey` is never copied.** It is unique per company (§6c), so copying it either violates the constraint or — worse, if uniqueness were relaxed — makes an ingested CI result match two cases and land somewhere unpredictable. Clone leaves it empty and says so in the UI.

   > **→ §129 (2026-08-08): this rule and "copy the automation status" are JOINTLY UNSATISFIABLE, and neither document noticed.** `CaseEdit.validate` refuses `AUTOMATED` without a key, so a clone that copies the status and drops the key is refused by the product's own validator and **a clone of an automated case is impossible**. Found by the endpoint's first test run, not by reading either document. The resolution is **`AUTOMATED` → `TO_BE_AUTOMATED`**, which is not a compromise but the accurate state — intended to be automated, no automation yet, which is exactly what §6c makes the key optional for. Every other status copies unchanged; a blanket reset would tell a reader that a `NOT_AUTOMATABLE` case with a written reason is merely pending. Relaxing the validator instead is the wrong fix: an `AUTOMATED` case with no key is one §117's matcher can never match, so it reports as automated for ever while no result ever lands on it.
2. **Review state resets.** A clone of an Approved case is Draft (§6g). Approval attaches to reviewed content by a named person; carrying it to a copy nobody read is exactly the kind of laundered sign-off an audit is meant to catch.
3. **A clone is a new aggregate, not a version.** It gets its own version-1 snapshot (§6j) with a `clonedFrom` provenance field recorded in the audit event — so "where did this case come from" is answerable — but no shared history and no linkage that could propagate an edit.

   > **→ §129: this rule is what rules out the obvious plan implementation.** Cloning a plan by creating it and then editing it gives version 1 a snapshot of a plan with no sections and no criteria, and version 2 a second later — a clone born with a history saying somebody rewrote it. So `PlanCreateService.write` was extracted to take a validated edit instead of a request, and the clone supplies one the narrow create contract could not have expressed. Provenance is a **second** audit row (`case.cloned` / `plan.cloned` / `run.cloned`) beside the created one, naming both display keys: the created row is what every existing audit reader already understands, and `*.cloned` is what makes "where did this come from" a column filter rather than a parse.
4. ~~**Deep vs shallow on the suite tree is an explicit choice.** Cloning a folder asks whether to include subfolders~~ → **SUPERSEDED by §98**: a suite has been **flat** since the organization-model restatement — a named collection of three arms, not a folder with children — so there is no subfolder question left to ask. What survives of this rule is the second half: **cloning into another project** is allowed only when the target shares the company (§6s) and revalidates classification values against the target's vocabularies (§6d), cross-project clones being where dangling references get created. §129 does **not** build that: the endpoint is `/projects/{projectId}/cases/{caseId}/clone` and the destination suite is resolved in that project, so cross-project is structurally absent rather than refused, and it stays its own slice.

**Bulk clone** ("clone these 30 cases into that suite") reuses the §6f bulk-selection mechanism rather than growing its own. Not built in §129 — it belongs in `BulkAction`, not in a per-row endpoint.

> **Attachments: "by reference, not byte copy" was wrong and §129 reversed it.** `AttachmentStore` is a handful of methods over an opaque key with **no reference count**, and nothing can acquire one without that interface learning what a row is — which is the property that keeps the "may I reach these bytes" decision in one place (§125). So two `attachment` rows sharing one `storage_key` means deleting either one destroys the other's bytes: the survivor renders as a file that 404s, with nothing having failed and no error anywhere. The choice was therefore a byte copy or nothing, and the copy is on the **port** (`AttachmentStore.copy`) rather than in the caller, so an S3 adapter can implement it as a server-side `CopyObject` and move no bytes at all. The cost is stated rather than hidden: cloning a case with a 25 MB file copies 25 MB.

### Placement

- **Clone case** — M1, with the repository.
- **Clone plan, clone run, plan templates** — M2, with execution. Clone-run is the highest-value of the three (re-running a regression cycle against a new build is the most repeated action in the product) and should not slip.

Both were previously implied by "bulk operations" in Phase 3; they are daily-use operations and belong in Phase 1.

---

## 7. Delivery Milestones

| Milestone | Content | Rough size |
|---|---|---|
| **M0 — Foundation** | Repo structure, Gradle multi-module, Docker Compose, Postgres + Flyway, CI pipeline, auth skeleton, one vertical slice (project CRUD) end-to-end, **`tenancy` module: `company` + `company_membership`, `company_id` convention, `CompanyContext` + Hibernate filter, company-first index and uniqueness rules, the schema test that enforces them, the `provision-company` script, the two-company isolation harness in CI, **per-company display-key allocation with batch reservation (§6t)** and **per-membership display-name resolution (§6u)** | 2–3 weeks |
| **M0.5 — Identity & access** | Users (local auth), teams, roles with the `SYSTEM`/`COMPANY`/`PROJECT` scope enum, split permission catalogue (§6b), `PermissionEvaluator` with the company-membership gate **and the granted-projects-only rule** (project list, pickers and search all built from the granted set server-side), effective-permission endpoint, ArchUnit authorization guard, **the no-project-grant fixture persona**, **grants carried in the JWT with the `pv` revocation epoch (§6b)** | 2 weeks |
| **M1 — Test repository** | Suites, cases, **both step formats** (structured + Gherkin editor & parser), automation status/tag/key, classification, versioning, attachments, search, **clone case (§6v)** | 4–5 weeks |
| **M1.5 — Reuse & review** | Condition/shared-step library, indexed typeahead search, bulk attach + suite inheritance, optional review workflow | 3 weeks |
| **M2 — Execution** | Plans, runs, configurations, execution UI, **evidence capture**, run summary report, `SuiteRunSummary` + `PlanSnapshot` rollups, case/suite/plan history & statistics, **plan templates + clone plan/run (§6v)** | 5–6 weeks |
| **M2.5 — Docs, artifacts & reporting** | Documentation space with stable anchors + linking, artifact register (both scopes), `ExecutionFact` + `CaseHealth` read models, repository status dashboard, `ReportModel` abstraction, HTML/MD/PDF renderers, async job pipeline, aggregate execution report, doc-structured reports, evidence-bundle export, **Mermaid blocks + SVG pre-render for export (§6i)** | 7–8 weeks |
| **M3 — Automation ingestion** | Result parsers, automation-key matching, API tokens, quarantine flow | 2–3 weeks |
| **M4 — Integrations & workflow** | **External identity (OIDC SSO: Cognito/Entra/Google/Okta/Keycloak + generic **and LDAP/AD**), account linking, claim→role mapping**, **four `IssueTracker` adapters (Jira · GitHub · GitLab · Azure DevOps) with back-link + status hover-cards** and encrypted OAuth/PAT credentials, notifications, Git links, requirements & coverage matrix, kanban board + work items **incl. the defect view (§6l)**, org chart | **8–10 weeks** |
| **M5 — Hardening & release** | Perf pass, security review, backup/restore docs, install guide, v1.0 image | 2 weeks |
| **M6 — Multi-company activation** *(last, §3 Phase 4 / §6s)* | Company switcher + `/c/{slug}` routing + session switching, invitation & membership flow, `COMPANY`-scope assignment UI, per-company IdP + email-domain login routing, suspend honored, **per-company branding (§6u)**, per-company metric tags **· plus the instance operator console — see the row below** | ~~**1.5–2 weeks**~~ → **3–4 weeks** |
| **M6 addition — instance operator console** *(folded in 2026-08-07)* | Instance-Admin-only `/instance` route tree: provision a company from the UI, company roster + detail, suspend/reinstate, re-run seeds, invite a person into any company, and a first-boot bootstrap that emails an activation link to the instance's own sender address. **Reverses §6s's "no company management UI"** — see the decision log. Full task breakdown in `docs/plans/2026-08-07-instance-operator-console.md`. | **+1.5–2 weeks**, inside M6 |

Each milestone ends with a deployable image and updated install docs — self-hosted software lives or dies on the install experience.

**Why M6 is small, and why M0 grew instead.** The cost of multi-tenancy is not the UI, it is the discriminator threaded through every table, index and uniqueness constraint — plus the proof that it holds. Both are M0. Because companies are provisioned by an operator script rather than through an admin UI (§6s), the deferred remainder is genuinely a week of surface, not a phase.

What makes the ordering safe rather than optimistic is a short list of constraints that must hold in M0–M5, each a build-breaking test rather than a good intention:

- `company_id NOT NULL` on every company-owned table, enforced by the schema test.
- `company_id` leading every composite index and every previously-global uniqueness constraint — project key, team name, token name, `automationKey` — plus the per-company display-key counter (§6t).
- UUID primary keys, so a foreign id is neither reachable nor enumerable.
- `user_identity` keyed on `(company_id, issuer, subject)` when SSO lands in M4.
- The company id in every domain-event envelope, re-established in every `@Async` handler.
- No code path reading the company from client input.
- **The two-company CI fixture green from M0 onward** — the one that makes the six above true rather than intended.

Ship those and M6 is additive. Skip any of them and M6 becomes a migration across every table in the schema, which is the version of this work that kills projects.

---

## 8. Open Questions

### Resolved — 2026-07-30 (carried into the ERD, `docs/specs/`)

| # | Decision |
|---|---|
| 7 | **Concurrent execution: yes, with live updates.** Optimistic locking (`@Version`) on executions and step results; SSE channel shared with the kanban board (§6l). |
| 18 | **Requirements are documentation blocks.** No separate `requirement` table — traceability is `test_case → doc_link → doc_block`. §2's `Requirement` collapses into §6i. |
| — | **Custom fields deferred to phase 2.** v1 ships the fixed field set in §6m. Adding them later is a migration, not a rewrite. |
| — | **Versions written on explicit save only.** Autosave goes to a draft buffer; a version row is written on save and skipped when `content_hash` is unchanged. |
| — | **Steps stored relationally**, one row per step with a stable id, so `step_result` carries a real FK and steps are independently indexable. |
| — | **Read models are event-updated tables**, not materialized views — always fresh, incremental cost. |
| — | **`company_id` on every company-owned table from day one** (renamed from the earlier `tenant_id` hedge — it is now a real modelled entity, not a placeholder). Default company row, a globally-applied Hibernate filter, `company_id` leading every composite index. This is no longer a hedge against a hypothetical: **multiple companies per instance is in scope, delivered as the final phase** (§3 Phase 4, §6s, M6). §1's non-goal narrowed accordingly — not *public multi-tenant SaaS*, but multiple admin-provisioned companies, yes. |
| — | **Multi-company activation is the last delivery phase (M6, ~1–1.5 weeks)**, with the schema, filter, provisioning script **and the two-company isolation harness** all in M0. The harness is M0 because a discriminator that has only ever held one value is an untested claim. Constraint list that keeps the deferral safe: §6s and the note under §7. |
| — | ~~**Companies are provisioned by an operator script at onboarding, not through an admin UI.** Creating a company is a once-per-company event. This removes company CRUD screens, the instance operations console, archive state and quota management from scope — and is the reason M6 is a week rather than a phase. The script is still a tested, idempotent deliverable, not hand-written SQL. §6s.~~ → **superseded 2026-08-07: an instance operator console is in scope, folded into M6.** The reasoning above was sound and its cost is now being paid back deliberately: M6 roughly doubles, because the sentence "M6 is a week rather than a phase" was *load-bearing on this exclusion*. What changed is the requirement, not the analysis — every company lifecycle action must be reachable from the UI by an Instance Admin. Scope: provision, roster, detail, suspend/reinstate, re-run seeds, invite into any company. **Still excluded:** company rename, delete, quota and archive management. The `provision-company` script survives unchanged as the break-glass path and as the only way to create an Instance Admin. Plan: `docs/plans/2026-08-07-instance-operator-console.md`. |
| — | **Granting Instance Admin stays CLI-only, deliberately** (decided 2026-08-07). A fresh instance mints its first operator by emailing a one-shot activation link to its own configured sender address on first boot; every operator after that is `grant-instance-admin` over an existing account. There is no HTTP path and no UI control that creates a SYSTEM-scope grant, which is what `RoleGrantService`'s standing 400 on SYSTEM-scope roles protects. This is the one exception to "all actions via the UI", and it is the exception on purpose: minting an operator should require a shell on the box. §6b, §6s. |
| — | **Default lifecycle policy is suspend, never delete.** Avoids writing a purge script across ~60 tables of cascades; deletion, if ever needed, is a scripted export + scripted purge. §6s. |
| — | **Company administrators are assigned per company by the Instance Admin** and administrate only the companies they are assigned to — a `COMPANY`-scope `RoleAssignment`, not a new tier. §6b. |
| — | **One account may belong to several companies with independent permissions in each.** Confirmed requirement. So `app_user` is instance-level, email is unique instance-wide, `company_membership` is the join, and the switcher is in scope. The simpler alternative (a user row per company, email unique per company) was ruled out on this requirement. Membership is a *precondition* for any permission, which makes offboarding a single row-delete. §6b/§6s. |
| — | **No cross-company views for anyone but an Instance Admin** — no unified inbox, no combined board, no "my work everywhere". A user works in one company at a time. §6s. |
| 43 | **Companies are separate clients**, not business units of one organization. Consequences, all now decided rather than optional: Postgres **RLS on by default**; whole-company **export is a required deliverable**, not a nice-to-have; **no pre-authentication company picker** on the login screen (it would leak the client roster); default role on accepting an invitation is **Viewer with no project access**; and the Instance Admin's reach into client data is a **disclosed, client-audited** capability. §1, §6s. |
| 44/48 | Folded into 43 above — RLS default and invitation default both follow from separate-client hosting. |
| 46 | **Instance Admin does have access to everything, including client content.** The alternative (operator holds ordinary grants to work in a company) was recommended for audit cleanliness but rejected: an operator with database access anyway would make it security theatre. Mitigated by logging operator access in the *client's* audit trail and disclosing it plainly. §6b/§6s. |
| 43 | **Display keys: type prefix + per-company sequence from 1, unique across all the company's projects** — `TC-1`, `TP-1`, `TS-1`, `TR-1`, `DOC-1`. UUIDs are the real backend keys and the only thing API paths and FKs use; the display key is presentation only, and never appears in an API path. Company scope means one unambiguous form everywhere — no qualified `PROJ / TC-1` variant, trivial search, and moving a case between projects can't collide. Costs a company-wide counter row (batch-reserve for bulk import) and a minor key-inference channel, contained by returning *not found* for unreachable keys. **§6t**. |
| — | **No display key** for conditions, shared steps, defects (Jira owns those), reviews, artifacts, milestones or configurations; **no automation prefix** — `automationStatus` carries that and is designed to change. **§6t**. |
| — | **`automationKey` is company-scoped too**, matching display keys — a CI job posts results without knowing which project owns the case. **§6c**. |
| — | **Project is a full client project** — an entire product or engagement ("Globex Online Banking"), not a feature area. Feature-level structure lives in the hierarchical Feature dimension (§6d) and the suite tree inside a project. This is why a project grant is significant and company membership deliberately grants no project visibility. **§1, §6b**. |
| — | **Conditions, shared steps and execution statuses are project-scoped; Test Type / Level / Phase are company-scoped.** Resolves the plan's internal contradiction (§6f said project, §6s implied company). Two client products legitimately differ in login flow and status vocabulary; what counts as a "Performance test" should not. **§6d, §6f, §6s**. |
| — | **Project key: 2–10 uppercase alphanumerics, immutable, unique per company**, suggested from the project name. Used in URLs and labels — no longer inside case keys, since those are company-scoped. **§6m**. |
| — | **Company slug is immutable after provisioning; display name and branding are freely editable.** The slug is in every URL and shared link; clients rebrand more often than they need a new identifier. **§6s, §6u**. |
| 44 | **Per-membership display names and avatars: yes** — resolved through the active company's membership at every rendering site, decided in M0 because it is a resolution rule threaded through every surface that shows a person. **§6u**. |
| — | **Permission catalogue changes** (§6b): added `suite:delete` (Lead+), `project:audit` (Lead+), `case:comment` (Viewer+), `defect:create`, `workitem:manage`, `library:manage` (all TE+); `case:delete` is `OWN` for Test Engineer in any state; `report:export` granted wherever `report:view` is, Viewer included; `company:read` granted to every company member. |
| — | **Shared-library edits are gated by an impact modal, not by role** — the editor sees the affected cases before committing. Informed consent beats authority here, since the editor is the person who knows whether the change is right. **§6b, §6f**. |
| — | **Automation Service is the smallest role in the product**: `case:read`, `run:create`, `result:submit`, `evidence:attach`, and `case:create` **restricted to the quarantine folder**. A leaked CI token cannot touch the real repository. **§6b**. |
| — | **Role names: Instance Admin · Company Admin · Test Lead · Test Engineer · Automation Service · Viewer.** "Admin" and "Lead" signal different kinds of authority and Test Lead is standard TCMS terminology, so no audit line ever needs a scope qualifier. **§6b**. |
| — | **Viewer is the default everywhere** — on accepting a company invitation, on SSO JIT provisioning, and on project assignment. Test Engineer is always an explicit grant. One rule at both levels, and the cost of an unintended Viewer is nothing while the cost of an unintended contributor is edits to a client's test assets. **§6b, §6r, §6s**. |
| — | **A Company Admin sees every project in their company**, present and future, with no per-project grant required. A client's own administrator being unable to see one of their products would be indefensible. **§6b**. |
| — | **No custom roles in v1 — not even clone-and-edit.** The six built-ins are the complete set; a client needing something between two tiers gets the higher one, or the built-in boundary is wrong and should be fixed. Enforcement stays permission-based, so adding a role designer later is additive (phase 3, on demand). **§6b**. |
| — | **Client-facing isolation evidence deferred** to a future nice-to-have. The isolation itself (harness, RLS, schema test) is v1 and non-negotiable; only *packaging it for a client's security review* is deferred, and it's cheap later because the artifacts already exist. **§6s**. |
| — | **Per-company branding: logo, two brand colours, display name, favicon** — driving the MUI theme at runtime, with contrast validated at save time and semantic status colours (Pass/Fail/Blocked) deliberately not brandable. No custom CSS or fonts. Ships in M6 with the rest of the multi-company surface. **§6u**. |
| — | **Three access tiers, and the third one is a real rule:** Instance Admin (everything) → Company Admin (everything in assigned companies) → regular user (**only the projects they are explicitly granted, not the company's project list**). Company membership grants authentication into a company and zero project visibility. §6b. |

#### Resolved — 2026-07-30, second pass (from the Kiwi TCMS gap analysis, `docs/audits/2026-07-30-kiwi-tcms-capability-gaps.md`)

| # | Decision |
|---|---|
| — | **Grants travel in the JWT** — `sys`/`cmp`/`prj` role assignments plus a `pv` revocation epoch, so no request resolves permissions from the database. Viable only because the six built-in roles are immutable, making `role → permission[]` a compile-time constant. **The `pv` epoch check is mandatory, not an optimization** — without it a revoked grant survives until token expiry. **§6b**. |
| — | **Plan templates + clone for case, plan and run** — moved out of the Phase 3 "bulk operations" bucket into M1/M2, because they are daily-use operations. A template stores the **selection query**, not a frozen case list. `automationKey` is never cloned; review state resets to Draft. **§6v**. |
| — | **LDAP / Active Directory is in scope for M4**, alongside the OIDC providers. A genuinely separate code path (bind, not redirect), keyed on `objectGUID`/`entryUUID` and **never** the DN. Included because many on-prem self-hosters have AD and no OIDC issuer. Kerberos/SPNEGO remains out of scope. **§6r**. |
| — | **Defects are tracked on the kanban board, and that is the no-tracker fallback.** A local `Defect` record always exists as the join between failed execution, evidence and any external issue; with a tracker configured its status is synced and read-only. **TCMS keeps its own bug-progress dashboard regardless of tracker** — including across projects using different trackers. Not a workflow engine. **§6l**. |
| — | **Four `IssueTracker` adapters in M4: Jira · GitHub Issues · GitLab Issues · Azure DevOps Boards.** Building four at once is what keeps the port from leaking Jira's vocabulary. Plus **tracker-side back-link on filing** (traceability must work from the developer's end too) and **cached status hover-cards** wherever an issue key renders — never a synchronous tracker call on page render. **§5, §3 Phase 2**. |
| — | **Mermaid diagrams in the documentation space**, source text stored in the block so diagrams version and diff like any other content; pre-rendered to inline SVG for HTML/PDF export, passed through as a fenced block for Markdown. **§6i, §6e**. |
| — | **TAP and Robot Framework result parsers: not now.** Revisit on real demand; the M3 parser set stays JUnit XML · TestNG · Allure · Cucumber JSON. **§3 Phase 2**. |
| — | **Third-party extension mechanism (plugin SPI): still open** — see Outstanding #43. The one item from the gap analysis not decided in this pass, and it should be settled before M3 hardens the ingestion contract. |

### Outstanding

1. ~~**Case versioning depth**~~ — **resolved 2026-08-01: full snapshot per edit.** Ratifies what is already built — §29 writes v1 with the case and §49's timeline reads snapshots directly — so the decision costs no migration. Event-sourced diffs were rejected because every read would reconstruct, and because the version pin `test_execution.case_version_id` depends on a snapshot being directly readable (§6j, §6p). Retention (thinning unpinned versions) stays open as #22; note any thinning policy must not orphan a run-pinned version.
2. ~~**License model**~~ — **resolved 2026-08-02: private, not published.** No public repo, no published images, no external contributors. Widest dependency latitude, since nothing is distributed. **This pulls against #43**, which chose a *public* result-parser SPI on the Kiwi argument that contributors build the integration breadth — with a private license there is no contributor population for the SPI to serve. #43 stands on its other leg (the seam is worth keeping still even for our own parsers), but the stability-across-releases commitment it made is now owed to nobody, and should not be used to justify freezing an interface prematurely.
3. ~~**Migration from an existing tool**~~ — **resolved 2026-08-02: yes — TestRail, Zephyr and Xray, all three, funnelled into a single adapter.** The importer moves from phase 3 into **M4**. One import port with three source readers, which is the same argument that keeps `IssueTracker` honest (§5): building three at once is what stops the port from leaking TestRail's vocabulary. Three consequences to carry into the slice: **(a)** TestRail is its own REST API while Zephyr and Xray are Jira-resident, so the latter two ride M4's Jira auth and are therefore **Cloud-only** by #4; **(b)** custom fields are deferred to phase 2 (§6m), so a foreign custom field has nowhere to land — it must be **reported as dropped, never silently discarded**; **(c)** re-import needs an identity rule, exactly as §51 found for #9 — without a stable foreign-id→case mapping the second import duplicates the suite.
4. ~~**Jira flavour**~~ — **resolved 2026-08-02: Cloud only.** OAuth 2.0 3LO, REST v3, ADF for issue bodies. Data Center / Server is out of scope — a PAT lane and wiki-markup bodies are a second adapter wearing one name. This also fixes #3's Zephyr and Xray readers to their Cloud APIs.
5. ~~**Expected data volume**~~ — **resolved 2026-08-02: small — under 10k cases, under 100 runs/day.** Postgres full-text search is sufficient; **no dedicated search engine and no message queue** — ingestion needs no more than a job table. Recorded as the **sizing assumption M3 builds against**, not as a cap: the number to revisit if a client arrives an order of magnitude larger, and the reason to keep the search behind a narrow interface rather than spreading `tsquery` through the codebase.
6. ~~**Deployment target**~~ — **resolved 2026-08-02: both — Docker Compose on a VM now, a Helm chart before v1.0.** Compose stays the v1 target and the Hetzner runbook stays the deployment of record. **The cost is on #28 and must not be discovered late:** the filesystem evidence adapter is a single mounted directory, which under Kubernetes is an RWO volume that pins the pod to one node and makes a second replica impossible. **The S3/MinIO adapter is therefore a prerequisite of the Helm chart, not an optional upgrade beside it** — the chart lands after that adapter or it ships a single-replica deployment that cannot scale, which is most of the reason to have a chart at all.
7. ~~**Concurrent execution**~~ — **not open: answered by the implementation in §42, and by the ERD in the same breath.** Yes, several testers work one run at once — ERD §5's `test_execution` row says so in its own notes column (*"Concurrent execution — multiple testers on one run (resolved #7)"*), and `TestExecution.lockVersion` carries `@Version` under a javadoc citing this entry by number: *"here it is load-bearing rather than defensive… two people opening the same execution is the ordinary case rather than the race."* **The locking half is built and the granularity is a decision, not an accident** — exactly one lock per contended thing: `TestExecution` has it, `StepOutcome` deliberately does not (step rows are only written as part of recording a result, and a second lock beneath would fail a save the caller's `If-Match` could not have anticipated), and `TestRun` deliberately does not (§74 found it unannotated, fixed `TestPlan` and left the run alone, because a run-level `@Version` makes two testers collide on the product's most-used write). **The SSE half was never built and is not required by the answer**: `grep` finds no `SseEmitter`, no `text/event-stream`, no `EventSource`. Optimistic locking makes a concurrent run *correct*; a live channel would only make it *pleasant*, and its absence costs a 409 and a refetch on the rare true collision. Phase 3 if anyone asks — **the word in the original entry was already "probably".**
8. ~~**Default step format**~~ — **resolved 2026-08-01: structured steps are the project default.** Matches the relational step model already decided above (one row per step with a stable id, so `step_result` carries a real FK), so the default and the storage model agree. Gherkin remains available per case; the M1 polish budget goes to the structured editor.
9. ~~**Sync mode**~~ — **resolved 2026-08-01: `.feature` files are IMPORTED into TCMS.** The automation repo is the source of truth and TCMS ingests. Two consequences for M4, both load-bearing: a `.feature` parser is in scope (export is **not**), and **re-import needs an identity rule** — without a stable mapping from scenario to case, the second import duplicates the whole suite. `automationKey` is the natural key and is already company-scoped (§6c), which is what makes this workable. **Note the tension with #8:** the editor default is structured while Gherkin arrives by import, so imported cases are second-class in the editor until the Gherkin view gets budget. Decide that in M4, not before.
10. ~~**Custom roles**~~ — **resolved:** the six built-ins are the complete v1 set. No custom roles, no clone-and-edit; a role designer is a phase-3 option on demand. (§6b)
11. ~~**PDF engine**~~ — **resolved 2026-08-02: headless Chromium, and the ~300MB image cost is accepted.** One renderer for HTML and PDF, so the two exports cannot drift, and Mermaid pre-rendered to inline SVG (§6i) works without a second styling lane. (§6e)
12. ~~**Classification scope**~~ — **resolved:** Test Type / Level / Phase are company-scoped (seeded per company at provisioning, editable by its Company Admin); Feature is project-scoped. (§6d)
13. ~~**Report retention**~~ — **resolved 2026-08-02: 90 days for generated report artifacts; anything attached to a release sign-off is retained indefinitely.** Bounds a disk that #28's filesystem adapter made finite. **The expiry job needs a pin, and it is the same shape as #22/#1's run-pinned version:** a `report_artifact` referenced by a sign-off must be unreachable to the sweeper, and that has to be a property the job reads rather than a rule it is trusted to remember.
14. ~~**Edit-after-approval**~~ — **resolved by fact 2026-08-02: it is a per-project setting, built in §77.** `project_settings.edit_after_approval` (`EditAfterApproval`) carries both modes with **Retain + stale marker as the default**, which is the direction this entry recommended. Recorded here so it is not re-asked. (§6g)
15. ~~**Review gating**~~ — **resolved 2026-08-01: warn, never block.** Run creation surfaces how many cases in scope are not APPROVED and proceeds anyway. No project setting, no predicate in scope resolution — an unapproved case in a run is a workflow problem, not a data-integrity one, and a setting nobody configures buys nothing. This is a **read-side change only**; run scope resolution stays approval-blind.
16. ~~**Suite-level condition inheritance**~~ — **resolved by fact: built in §27**, not deferred. The case detail renders §6f inheritance live. The entry was stale; recorded here so it is not re-asked.
17. ~~**Aggregation policy default**~~ — **resolved 2026-08-01: `LATEST` is the default and `BEST` is REMOVED from the enum.** "Best result wins" is how a red suite gets reported green, and the release-gate reading it would be traded against — `WORST` — is already in the enum, so dropping `BEST` costs nothing. `AggregationPolicy` becomes `LATEST · LATEST_PER_CONFIG · WORST · ALL`. **Not yet applied** — it is a contract change (`openapi.yaml` + `gen:api`) touching `frontend/src/lib/labels.ts`, `backend/.../execution/AggregationPolicy.java` and ~10 assertions in `PlanStatisticsEndpointTest` that use `BEST` as the deliberately-disagreeing policy; most survive by switching to `WORST`, which still discriminates against `LATEST`. One lane per the contract-holder rule.
18. ~~**Requirements: doc blocks or separate entity?**~~ — **resolved 2026-07-30, and this entry is a stale duplicate of that decision.** The Resolved table above already records *"Requirements are documentation blocks. No separate `requirement` table — traceability is `test_case → doc_link → doc_block`"*, and §2's `Requirement` collapsed into §6i accordingly. Struck 2026-08-02 rather than deleted, so the duplication is visible as the failure mode it is — this is the fourth entry (#16, #33, #36, #18) found answered inside the same document that kept asking it. (§6i)
19. ~~**Docs-as-code sync**~~ — **resolved 2026-08-02: yes — Git-sourced doc import moves up from phase 3.** The spec lives in a repo as Markdown, so the documentation space ingests it. **It inherits #9's risk verbatim:** without a stable identity per doc block across imports, the second import duplicates the whole space. Decide the identity rule with the parser, not after it.
20. ~~**Project Admin role**~~ — **resolved:** folded into Test Lead; the tier between Test Lead and Instance Admin is Company Admin (§6b). No further tier needed.
21. ~~**Peer-review prerequisite**~~ — **resolved by fact 2026-08-02: built in §77.** `project_settings.required_peer_reviews` is per project and copied onto each `review` row at submission; §77 also shipped `author_may_approve_own_work`, which §6g calls the single most valuable setting here. Recorded so it is not re-asked. (§6b)
22. ~~**Version retention**~~ — **resolved 2026-08-02: keep every version indefinitely.** No thinning, so no purge job and **no way to orphan the run-pinned version #1 depends on** — the risk that entry flagged is retired rather than managed. The bound on storage is evidence files (#28), not case text.
23. ~~**Change comments**~~ — **resolved 2026-08-02: optional.** Mandatory produces a thousand comments reading "update", which is worse than an empty field: it makes the timeline unreadable while looking like a record.
24. ~~**Board derived-item generators**~~ — **resolved 2026-08-02: all of them are on by default, and the list grew by one.** §6l's four (assigned execution · pending review · case stale by a doc change · quarantined flaky test), plus linked tracker issues (#25), plus a **sixth generator added in this round — failed-result follow-ups.** ~~Recommend executions and reviews only; the rest opt-in.~~ → **superseded: nothing is opt-in.**

    **This makes #24 a capping question rather than a toggle question, and that is the substantive consequence.** §6l already says to *"cap derived generation with sensible limits"* because the board is opened dozens of times a day; with every generator on, that line stops being prudence and becomes a requirement of the first board slice. Three rules it must carry:

    - **Failed-result follow-ups dedupe to one card per case per run**, never one per failed step, or a bad run buries the board — which is precisely the volume risk that made "the rest opt-in" the original recommendation.
    - **The reviews generator must respect §77's per-project opt-in**, or a project that switched review off gets cards for an apparatus it disabled.
    - **The quarantined-flaky generator has no data until M3 ingestion exists.** On-by-default here means *on when it ships*, not a card source that is silently empty and mistaken for working.
25. ~~**Jira issues on the board**~~ — **resolved 2026-08-02: yes, as read-only cards.** Status comes from the cached hover-card data (§5) — **never a synchronous tracker call on render**, which is the rule that keeps a board with a slow tracker from becoming a slow board. Consistent with "TCMS keeps its own bug-progress dashboard regardless of tracker". (§6l)
26. ~~**Git line-linking**~~ — **resolved 2026-08-02: build it — `repo + path + lines + commitSha`.** Follows directly from #19: the specs do live in a repo. **The commit sha is mandatory, not optional metadata** — an unpinned line range silently points at different code after the next commit, which is a link that lies rather than a link that breaks. (§6m)
27. ~~**Case-level `expectedOutcome`**~~ — **resolved 2026-08-02: keep both.** Costs nothing and prevents the "stuff the overall outcome into the last step" workaround. (§6m)
28. ~~**Artifact storage target**~~ — **resolved 2026-08-01: a storage port with a filesystem adapter now, S3 later.** Evidence and artifacts land on a mounted directory; the port exists from day one so the S3/MinIO adapter is additive. **The cost was stated and accepted:** a filesystem adapter has no signed URL, so **uploads proxy through the API** — the upload control therefore ships with a request-size limit rather than a browser-direct PUT — and the VM's disk becomes stateful and backup-critical, which the Hetzner runbook must cover. Per-project **quota remains open**; it is the growth risk §6n names, and the filesystem adapter makes it more pressing rather than less, because the bound is one disk.
29. ~~**Sign-off artifacts write-once**~~ — **resolved 2026-08-02: editable by a Test Lead, with an audit entry — NOT write-once.** This goes against the recommendation and the cost is recorded rather than glossed: a sign-off record is treated as a **document that can be corrected**, not as immutable evidence, so its trustworthiness rests entirely on the audit trail carrying the before/after. **That makes the audit entry load-bearing** — a correction that is not audited is indistinguishable from a rewritten history, so the edit path must fail if the audit write fails, not proceed past it.
30. ~~**Health-signal thresholds**~~ — **resolved 2026-08-02: 14 days (stale automation) and 90 days (long unexecuted), instance-wide.** ~~§6o says both are project settings~~ → **superseded**: they stay instance-wide constants, which is what §53 observed the code already does, so this decision is a no-op on the existing implementation rather than a migration. The trade accepted: a project whose suite runs quarterly will be flagged permanently and cannot turn it off, and moving to per-project later is an additive `project_settings` column (§77) plus a resolution rule, not a rework.
31. ~~**Suite pass % denominator**~~ — **resolved 2026-08-02: render both, always, side by side and labelled** — *of executed* and *of total in suite*. No default and no toggle, because the failure this prevents is not picking the wrong denominator but **not knowing which one you are reading**: §65's lesson was two contradictory percentages on one screen, and one unlabelled number is the same defect with the contradiction hidden. `lib/results.ts` owns the arithmetic for both. (§6p)
32. ~~**Suite version filter default**~~ — **resolved 2026-08-02: as-executed membership.** The run shows the cases the suite held when it ran; a later suite edit cannot retroactively change a past run's coverage. Current-membership reconstruction stays available as the other reading, but it answers a different question — "what would this run cover now" — and must be labelled as such. (§6p)
33. ~~**Entry/exit criteria**~~ — **resolved by fact 2026-08-02: built.** Plans carry machine-evaluable criteria as structured predicates and the plan page evaluates them; §74 shipped the editor over a **closed metric vocabulary**, which is what stops a misspelled metric from reading as an unset one. Recorded so it is not re-asked. (§6p)
34. ~~**Stale-plan threshold**~~ — **resolved 2026-08-02: 14 days without execution.** Deliberately the same number as #30's stale-automation threshold — one figure to remember rather than two that drift. (§6p)
35. ~~**Release version source**~~ — **resolved 2026-08-02: semver is first-class; everything else sorts lexically.** `1.2.3` and `1.2.3-rc1` parse into a real ordering (pre-release sorting *below* the release it precedes); an unparseable string **falls back to lexical sort rather than erroring**, because a run whose version does not parse must still be listable. (§6q)
36. ~~**Release version required or optional**~~ — **resolved by fact: optional.** `releaseVersion` is `nullable: true` on every run schema in `openapi.yaml`, so the contract already answers it in the recommended direction. The entry was stale; recorded here so it is not re-asked.
37. ~~**Which IdP is actually yours?**~~ — **resolved 2026-08-02: Keycloak.** M4's claim mapping and group handling are built and tested against a real issuer rather than a generic abstraction — and because Keycloak self-hosts, **it goes into compose as a real test target**, which is the whole value of naming it: the alternative was an OIDC lane whose only counterparty was a mock. The other providers become configuration against the same code path. (§6r)
38. ~~**JIT provisioning default**~~ — **resolved 2026-08-02: invitation required.** No auto-creation on first SSO login. Membership stays a deliberate act, which is what "companies are separate clients" (#43) demands — an IdP authoritative for one organisation is not authoritative for who may enter a client's company.
39. ~~**Auto-link by verified email**~~ — **resolved 2026-08-02: permitted, strictly when the IdP marks the email verified.** `email_verified` is a **gate, not a hint**: linking on an unverified claim is an account-takeover path, so the absence of the claim must be read as false rather than as permission.
40. ~~**Is SAML needed?**~~ — **resolved 2026-08-02: no.** M4 is OIDC (#37) + LDAP/AD only. Revisit only if a real IdP requires it — it is a second protocol implementation, not a config row. (§6r)
41. ~~**Jira credential mode**~~ — **resolved 2026-08-02: a service account by default, with per-user OAuth opt-in for defect creation.** Works on day one; a user who wants their own name on the bug connects their account. (§6r)
42. ~~**Which health signals ship in v1?**~~ — **resolved 2026-08-02: four — outdated-spec, stale-approval, never-executed, unlinked.** The four that drive an action. The remaining six of §6o wait for real data to calibrate against. Note this leaves **#30's 14-day stale-automation threshold with no signal reading it yet** — the number is decided, the signal is not in the v1 four.
43. ~~**Third-party extension mechanism — do we want one at all?**~~ — **resolved 2026-08-01: option (a), a narrow result-parser SPI + the Phase 3 outbound webhooks.** It must be **designed into** M3's ingestion contract rather than retrofitted, which was the whole reason this question had a deadline. Understand what is being committed to: making the parser interface public is a **stability commitment across releases**, so the seam has to be worth keeping still. Nothing beyond result parsing becomes an extension point. Original wording below.

    **(original)** (from the Kiwi gap analysis) Kiwi's integration breadth comes from contributors building against a documented plugin API; our ports are internal seams, not an extension point. **This must be answered before M3 freezes the ingestion contract**, because a result-parser SPI is nearly free if designed in and expensive to retrofit. Options: (a) a narrow result-parser SPI + the Phase 3 outbound webhooks, (b) nothing, declared as an explicit non-goal so the integration list is bounded by our own throughput. Recommend (a) — the SPI is the ingestion pipeline's existing parser interface made public, which is a documentation and stability commitment more than new code.
44. ~~**Tracker status-mapping defaults**~~ — **resolved 2026-08-02: opinionated `tracker status → open/closed` defaults per adapter, plus a visible "review this mapping" prompt.** An unconfigured integration that silently reports every defect as open is worse than a wrong-but-editable guess; the prompt is what stops the guess from quietly becoming permanent. (§5)
**All roles, permissions and naming questions are now resolved** — see the table above and `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`.

**Decision round, 2026-08-01** (handoff §51): **#1, #8, #9, #15, #17, #28 and #43 are resolved above**, and **#16 and #36 were already resolved by fact** — built and contracted respectively, and stale only in this list. Of the six previously flagged as changing M1/M2 design, that leaves **#4 (Jira flavour) and #5 (data volume)**, both deferred as touching M4 and the search/ingestion sizing rather than the next slice, and **#7**, which is not open at all:

> **⚠ #7 appears twice and the two entries disagree.** The Resolved table above records *"Concurrent execution: yes, with live updates — optimistic locking (`@Version`) on executions and step results; SSE channel shared with the kanban board"*, while #7 below still asks the question. **The contradiction is left standing by an explicit decision on 2026-08-01, not by oversight.** This is §46's decay lesson recurring in this document itself: a resolved decision that was never struck from the outstanding list gets re-asked every session. Implementation follows the Resolved table — `@Version` on `execution` and `step_result`, because it is free now and a retrofit onto existing rows is not; SSE is deferred until the kanban board needs it.

~~Of the rest, **#37 ("which IdP is actually yours") is the one that gets more expensive the longer it waits**~~ → **answered in round 2: Keycloak.**

**Decision round 2, 2026-08-02** (handoff §78): **every remaining outstanding question is now resolved** — #2, #3, #4, #5, #6, #11, #13, #19, #22, #23, #24, #25, #26, #27, #29, #30, #31, #32, #34, #35, #37, #38, #39, #40, #41, #42, #44, plus the per-project quota split out of #28 (**an instance-wide soft limit with a warning, not a per-project hard quota**). **Four more were resolved by fact rather than decided** — #14 and #21 by §77's `project_settings`, #33 by §74's entry/exit criteria editor, and **#18 by the Resolved table forty lines above it** — which is §51's finding recurring for the third time: *the orientation pass's second question is "is this still blocked".* **#7 remains deliberately contradictory**, per the box above; that is the one entry in this list nobody should try to tidy.

~~**Nothing in §8 is open.**~~ The next thing that decays this list is not an unanswered question but
an unrecorded one — a decision taken in a slice and never written back here. **That is exactly what
happened, and round 3 below is the correction.** Between §78 and §122 the product grew M2.5's whole
reporting lane and all of M3, and the questions those slices left behind were recorded in handoff
sections and audit findings rather than here. None of #45–#60 is a *new* uncertainty; every one of
them was already visible in the code as a writerless table, an unconsumed enum value, an unenforced
permission key or a decision taken and never applied. **The list did not stay closed — it stopped
being read.**

### Decision round 3 — 2026-08-07 (handoff §123)

**Derived from the code, not from this document**, per §28/§29/§46: the six computed checks in
`docs/audits/2026-08-05-remaining-scope-assessment.md` §1, re-run at `e43e1b3`. Sixteen questions,
**thirteen decided and three deferred with a reason** — a deferral recorded as a deferral is a
different thing from an entry nobody answered, and the three below say what they are waiting on.

| # | Question | Decision |
|---|---|---|
| 45 | **What is the next block of work?** | **Close the small gaps** — the finishing work on features that already ship. Not M4, and **not the verification drain**, which stays deferred under §65. See the honesty note below. |
| 46 | **Register the shanks self-hosted runner?** | **Yes, now.** `.github/workflows/tests.yml` has been written and inert since §113; registering it makes the nightly `./gradlew build` a *Linux* observer. §58's defect class — anything sensitive to Linux-vs-macOS — currently reaches master unobserved, and 2014 tests run on exactly one machine. |
| 47 | **Apply #17's `BEST` removal?** | **Yes.** Resolved 2026-08-01 and never applied, which is this section's own failure mode in miniature. Contract change: `openapi.yaml` + `gen:api`, `AggregationPolicy.java`, `lib/labels.ts`, and ~10 `PlanStatisticsEndpointTest` assertions using `BEST` as the deliberately-disagreeing policy — most survive by switching to `WORST`, which still discriminates against `LATEST`. |
| 48 | **`DocBlockType` is 2 of 6 consumed.** | **Build all four missing renderers** — `PARAGRAPH`, `CODE`, `TABLE`, `IMAGE`. `PageContent` branches on `HEADING` and `LIST` only, so a documentation space stores a table block and renders it as nothing. `IMAGE` depends on #49's `DOC_PAGE` owner. |
| 49 | **`attachment.owner_type` is 2 of 6 written.** | **`CASE`, `DOC_PAGE` and `ARTIFACT` get writers. `STEP` deliberately does not** — a step's attachment belongs to the recorded result (`STEP_RESULT`, already written), not to the case definition. |
| 50 | **Then what happens to `STEP`?** | **Dropped from the `CHECK` constraint.** A migration narrowing `owner_type` to the five values with writers. Leaving it would keep the enum-with-no-consumer check reporting one permanent dead value, which is how a check stops being read. |
| 51 | **Does `Attachment` move out of `execution`?** | **Yes — extract a `storage` module.** `Attachment`'s own javadoc names this as the tidier home *and* the exact condition for doing it — "a second caller to design against" — which #49 creates twice over. **`EvidenceStore` and `ReportStore` move with it**: two storage ports currently living outside any storage module. Costs §3's module list amended and `ModuleStructureTest`'s hard-coded list **and** its cross-module FK rule changed. `forExecution`/`forStepResult` exist precisely so no call site spells an owner type, which is what makes the move mechanical. **This is its own slice and #49 depends on it.** |
| 52 | **The `outbox` table has no writer.** | **Build it — reliable event delivery.** `CompanyScopedEvent`'s javadoc has asserted that a failed handler lands there since M0, and nothing has ever written a row, so the code makes a promise it does not keep. Cross-cutting: every `@Async` path. **Sequence it after #46** — it is the widest-blast-radius change in this round and `AsyncCompanyContextTest` is exactly the suite whose timing a Linux gate sees differently. |
| 53 | **`artifact:delete` and `run:execute` have no consumer.** | **Wire both gates.** Called the cheap wins in round 2 *and* round 3 of the scope assessment and taken in neither. The features ship, the evaluator exists, the keys exist; today both are gated by something other than their own key. |
| 54 | **The instance/company admin surface.** | **DEFERRED — waiting on M6.** `instance_setting` unwritten and `role:manage`, `user:manage`, `system:settings`, `audit:view`, `instance:audit`, `project:audit`, `company:settings` unenforced. Company settings, branding, the switcher and per-company IdP routing are all M6 work; the screens belong with them rather than as a separate slice now. §122 gave the operator tier a CLI writer, which is what makes the wait survivable. **Note the standing cost:** #29 made sign-off records editable *on the strength of the audit trail*, and nothing can currently read that trail. |
| 55 | **When does the S3/MinIO adapter land?** | **DEFERRED — not yet, but the path is now recorded.** Compose on a VM stays the v1 target (#6). **The correction is that the Helm chart blocks on TWO adapters, not one** — `EvidenceStore` (§67) and `ReportStore` (§87) — and nothing in the repository said so before this entry. That is precisely the "must not be discovered late" #6 warned about, discovered exactly late enough to still be free. |
| 56 | **Is the §108 signing key rotated?** | **No — not done, and it is the oldest open action in the repository.** Production signed every session token with a 59-character string that is in this repo. The code is fixed and `ConfigConventionTest` reads the shipped text, but **the key is still public**, and `TCMS_JWT_SECRET` must exist in `/etc/tcms/tcms.env` before the next deploy or the app refuses to start. A written procedure is owed; the `sudo` half is the operator's. |
| 57 | **`test_case_draft` has no writer.** | **DONE 2026-08-08 — §128.** **Build the draft buffer.** The Resolved table above already decided *"versions written on explicit save only; autosave goes to a draft buffer"* — the version half shipped in §29 and the buffer half never did, so the editor has no autosave at all. This is #47's shape a second time: a decision recorded, half-applied, and never re-read. |
| 58 | **Rich text + sanitized HTML (M1).** | **DEFERRED to after M4.** It touches every authoring surface and every renderer, needs a sanitizer dependency and a stored-XSS test lane, and nothing is blocked on it. Plain text is legible and safe. **Revisit with the Markdown option first** — the report renderers already emit Markdown, so there is an existing lane and no new storage shape. |
| 59 | **§6v — plan templates and clone.** | **Clone only.** Clone case / plan / run genuinely reuses the version snapshot and needs no new table. **Templates are dropped from M1–M2**, not merely postponed: they need a table and a new concept, and the §6q saved-search argument that put them here was about the *expression*, not the workflow. §9's estimate paragraph is corrected accordingly. |
| 60 | **§110 has no handoff section.** | **Write it from the commits.** The browser pass is part-driven (`9cccb2a`, `ccf9eea`: the `F` lane 6 of 9 rows, no product defect, three harness findings) and the slice log jumps 109 → 111. The evidence is still in `docs/verification/2026-08-06-live-pass-findings.md`; it is the failure mode with the shortest half-life in this repository (audit §5.3), and it gets harder every session. |

**#45 is the entry to be honest about, and this paragraph is the honesty.** The scope assessment's
§5.2 calls the unrun verification queues the largest single block of remaining work — ~790 rows,
essentially none executed, covering every slice from §65 to §122 — and choosing to close small gaps
first does **not** retire that. It grows it: #47 through #57 each add rows to both queues. The
deferral is the standing §65 policy and it remains the user's call, but the queues must be described
as what they are. **Every slice in an unrun queue is *built and unverified*, and that now includes
everything this round ships.**

**Fixed execution order.** Not a suggestion — sequenced on dependency and on what each step lets the
*next* step observe, and #51 in particular must not be folded into #49.

**Steps 1 and 2 have a written procedure:** `docs/plans/2026-08-07-linux-gate-and-key-rotation.md`.

| Step | Item | Why here |
|---:|---|---|
| 1 | ~~**#46** register the shanks runner~~ | **DONE 2026-08-06 — and nobody noticed until 2026-08-08.** The runner is online with the `tcms-lab` label and `tests.yml` has run the full suite on Linux twice, green both times (`783fab1` by hand, `c73d27e` on the nightly, `BUILD SUCCESSFUL in 6m16s`). **Steps 6 through 8 therefore landed on the Linux observer this row was written for, and every document in the repo said otherwise the whole time** — including this table. The sixth instance of a blocker note outliving its blocker, and the first where the missing thing was already working. The registration command in the procedure document was also wrong and is corrected there: `ssh -t … < script` allocates no TTY, so `sudo` has nowhere to prompt. |
| 2 | **#56** JWT rotation | The oldest open action, and a live instance is signing tokens with a public key while it waits. **Not optional and not carryable**: `application.yml`'s colon-less spelling means a box whose env file lacks the variable cannot be deployed to at all, so the next release is blocked until this is done. |
| 3 | ~~**#60** write §110's section~~ | **DONE 2026-08-07 — `docs/handoff/110-the-live-pass-runs.md`.** Six product findings in ~60 driven rows, none reachable by any gate; six of the fourteen findings were about the observer rather than the product. |
| 4 | ~~**#47 + #53**~~ | **DONE 2026-08-07 — §124**, three phases. Two corrections to this row's estimate, both worth carrying into the steps below. **`#53` was not "near-zero design"**: `artifact:delete` had no endpoint at all, so wiring the gate meant building the operation, and `run:execute` needed a decision about *where* the check sits that the first draft got wrong. **And `#47` touched two enums, not one** — `reporting` carries a deliberate copy. A round-3 row's scope is a starting estimate, not a bound. |
| 5 | ~~**#51** extract `storage`~~ | **DONE 2026-08-07 — §125**, two phases. The estimate held: `ModuleStructureTest`, `ArchitectureRules` and §3 changed, and nothing else did — `Attachment`'s `forExecution`/`forStepResult` meant no call site spelled an owner type, so the move was a package line and two modifiers. **Two things the row did not say.** The rule change is not a widening of an existing category: `storage` is the first foundation module that is not a domain concept, so `ArchitectureRules` now carries the three properties that made it defensible and a **new ArchUnit rule** checks them from the code — Modulith enforces a declaration but cannot object to that declaration being *widened*, and adding `identity` to it is one legal word. And **§3's list already had a home for this** — `shared — storage, audit, attachments, search` — which is the option that was refused: a foundation module may not be a grab bag. |
| 6 | **#49 + #50** attachment owners | Depends on 5. `CASE`, `DOC_PAGE`, `ARTIFACT` get writers; `STEP` leaves the `CHECK`. |
| 7 | ~~**#48** doc blocks~~ | **DONE 2026-08-08 — §127**, three phases, no migration. **The row's own estimate was a third of the work.** "Build all four missing renderers" is accurate about `paragraph` and `code`; the other two had **no data to render** — `table` had no cells and `image` no file anywhere in the contract — and a fourth field, a list bullet's indent, had a reader since §93 and no way to author it. **Two things the row could not have said.** `PageVersions.SnapshotBlock` had to grow with `BlockContent` or the version history silently stops moving: the hash decides whether a version is written, so a content field the snapshot omits is one somebody can rewrite for ever while the page saves and reads back correct — and every *other* test about tables passes against that. And the SPA had **two** `BlockView`s branching on `blockType` that **disagreed**, so one stored outline rendered as an outline on one screen and as flat prose on the other. The dependency on step 6 held exactly as written: `IMAGE` names a `DOC_PAGE` attachment, which is why an image block cannot exist on a create. |
| 8 | ~~**#57** draft buffer~~, then **#59** clone | **#57 DONE 2026-08-08 — §128**, two phases, no migration. The row's estimate held on scope and missed the two things the slice turned on. **`lockVersion` had to be on the buffer**, because a restored draft's save carries an `If-Match` from the *fresh* read and is therefore correct — so the stale-editor check passes and silently overwrites the edit made while the draft sat there; the buffer is the only thing that knows what the draft was written *against*. And **there is no promote endpoint**, though `V1`'s own comment reads like there should be: it would be a second write path into `test_case` whose input is by construction unvalidated. Also worth carrying into #59: `case_id` **is** the primary key, so the buffer is per case and not per person, and that consequence had to be surfaced on the wire (`updatedBy`) rather than designed away. **#59 clone is still open** and is the next thing an agent can do alone. |
| 9 | **#52** the outbox | **IN PROGRESS 2026-08-08** — `docs/plans/2026-08-08-the-outbox.md`. Last, deliberately: widest blast radius of the round, and it does land on a Linux gate green for eight steps, which is the one thing about this ordering that worked exactly as written even though step 1 was believed undone throughout. Two things the row could not have said. **The outbox is not only durability — it is the only way two feature modules may exchange a shape**, because the §3 rule has no exemption for an event type, so a typed cross-module Spring event is unbuildable here and `event_type TEXT` + `payload JSONB` is the mechanism rather than a storage detail. And **the invitation email cannot go through it**, which looked like the obvious first producer: only a digest of the token is stored, so a payload carrying the accept URL is a live credential at rest and a retry could not reconstruct it anyway. |

**#54, #55 and #58 are not in the order** because they are deferred, and each names what it waits
on: M6, the Helm chart, and after-M4 respectively.

---

## 9. Immediate Next Steps

1. ~~Resolve open questions 1, 4, 5, 7, 8, 9 — they change the M1/M2 design.~~ — **done, in two rounds: §51 (2026-08-01) and §78 (2026-08-02). §8 has no outstanding entries left.** The roles/permissions and naming decisions were settled from the start and recorded in `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, which is authoritative for the seed migration.
2. ~~Produce the detailed ERD + REST API surface~~ — **done.** `docs/specs/2026-07-30-tcms-erd.md` (63 tables, `company_id` throughout, RLS), `V1__initial_schema.sql` regenerated and statically validated, and `docs/specs/openapi.yaml` aligned. The permission catalogue still needs writing as a **seed migration** (`V2__triggers_and_seed.sql`), sourced from `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`.
3. ~~Produce the **M0 implementation plan** and scaffold the repo — including the `tenancy` module, the schema test, the `provision-company` script and the two-company isolation harness.~~ — **done.** All four shipped in M0 and all four still stand; `IntegrationTestBase`'s non-superuser role split and `SchemaConventionTest` are the harness's living parts.
4. **The current order is §8's decision round 3 table** — nine steps, #46 first and #52 last. This list is kept for the record; **do not read items 1–3 as current work.** M0 through M3 are complete.

> **The estimate below is the original plan's and has not been re-derived since.** Read
> `docs/audits/2026-08-05-remaining-scope-assessment.md` for the current figure — and note that it is
> itself stale by 21 slices, because M2.5's reporting lane and the whole of M3 closed after its
> baseline. Two corrections it already needs: **plan templates are dropped** rather than absorbed
> (#59), and **rich text moves out of M1 to after M4** (#58).

**Total revised estimate: 35–46 weeks** to v1.0 including multi-company — 34–45 to a fully-featured single-company release at M5 (with ~1 extra week already absorbed into M0 for the tenancy foundation and harness), plus 1–1.5 for M6. **The +1–2 over the previous 34–44 is entirely M4**: three additional `IssueTracker` adapters and LDAP/AD. Plan templates and cloning (§6v) absorb into M1/M2 without moving them, since both reuse existing machinery — the version snapshot for clones, the §6q saved-search expression for templates. (The first draft was 14–19, before teams/RBAC, dual step format, evidence capture, automation metadata, classification dimensions, multi-format report export, the reusable condition library with bulk assignment, the optional review workflow, the documentation space, the aggregate execution report and multi-company were added.)

**Multi-company costs roughly two weeks in total**, split as ~1 week of unavoidable M0 foundation and ~1 week of deferrable M6 surface. That is the whole point of provisioning companies by script: the layer is worth building, the *product* around the layer mostly isn't.

Because M6 is last and self-contained, **M5 is a legitimate release point.** A single-company v1.0 can ship and be used in production while M6 is built. And because the isolation harness runs from M0, onboarding company #2 is a script run against a schema that has been proven multi-company all along — not a leap of faith.

That is a substantial v1. If it needs to fit a smaller window, the most deferrable items — in the order I'd cut them, without damaging the core product — are:

1. **Review workflow** (§6g) — genuinely optional, and few teams enable it in month one.
2. **PDF export** (§6e) — HTML and Markdown cover most needs at a fraction of the cost and image size.
3. **Doc-as-report-template placeholders** (§6i) — doc-structured coverage reports are the valuable half; templating can follow.
4. **`.feature` export/import** (§6a) — drop entirely if the sync mode is *decoupled*.
5. **Org chart** (§6k) — genuinely nice-to-have; teams and roles work fine without the picture.
6. **Extra IdPs beyond the one you actually use** (§6r) — build the generic OIDC client plus *your* provider; the rest are config rows added when someone asks.
7. **Multi-company activation** (§6s) — already last, already only ~1–1.5 weeks, and it can slip indefinitely without touching M0–M5. The *foundation and the isolation harness* in M0 cannot slip at all.

**Two phase-1 constraints that protect the deferred work:**
- Identity must stay keyed on `app_user.id`, never on email, and `created_by` must FK to the user row. That already holds in the ERD, and it is what makes adding SSO in M4 additive rather than a migration across every authored artifact (§6r).
- Every company-owned table must carry `company_id NOT NULL` with it leading the composite indexes and every previously-global uniqueness constraint, and no code path may read the company from client input. That is what makes M6 additive rather than a schema-wide migration (§6s).

Everything in M0–M2 is load-bearing. Three things specifically must **not** be deferred, because all are rewrites rather than optimizations if retrofitted:
- **The `company_id` discriminator and its filter** (§6s) — retrofitting a tenancy column across every table, index and uniqueness constraint in a live schema is the single most expensive change available in this plan. The column ships in M0; the feature ships last.
- **The read models — `ExecutionFact`, `SuiteRunSummary`, `PlanSnapshot`, `CaseHealth`** (§6e, §6p, §6o) — reporting built on live joins cannot be converted to read models incrementally. They carry `company_id` like everything else.
- **Versioning and audit** (§6j) — history you didn't record from day one is history you never get back. Even if diff and restore UI ships later, *write the versions and audit events from M1*.
