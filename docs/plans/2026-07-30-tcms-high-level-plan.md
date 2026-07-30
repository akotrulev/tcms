# TCMS — Test Case Management System: High-Level Plan

**Status:** Draft for review
**Date:** 2026-07-30
**Scope decisions locked in:** single-org self-hosted · Spring Boot REST + React SPA · CI ingestion + Jira + notifications + Git traceability

---

## 1. Product Definition

A self-hosted test case management system for a single organization running several teams. It owns the *manual and automated test asset lifecycle*: authoring cases, organizing them into suites and plans, executing runs, ingesting automated results, and reporting quality signals traceable back to requirements and forward to defects.

**Explicit non-goals for v1**
- Not a test *runner* — it does not execute automation, it ingests results.
- Not a CI server, not a defect tracker (it links to Jira, doesn't replace it).
- Not multi-tenant SaaS. One deployment = one organization.
- No BDD feature-file authoring/round-tripping in v1 (candidate for v3).

### Comparable products
TestRail, Zephyr Scale, Xray, Qase, TestLink, Kiwi TCMS. The differentiator we target: **first-class automation ingestion + requirement-to-defect traceability in one self-hosted Java artifact**, without per-user SaaS licensing.

---

## 2. Domain Model (conceptual)

The core aggregates. Detailed ERD is a follow-up document.

```
Organization
 └─ Project ──┬─ Folder/Suite (tree)
              │    └─ TestCase ──┬─ TestStep[]
              │                  ├─ Parameter[] / DataSet
              │                  ├─ Attachment[]
              │                  ├─ Tag[]
              │                  └─ CaseVersion[]  (immutable history)
              ├─ Requirement ────── (links to TestCase, external Jira key)
              ├─ TestPlan ──┬─ TestRun ──┬─ TestExecution (case × run)
              │             │            │    └─ StepResult[]
              │             │            └─ Milestone
              │             └─ Configuration (env/browser/OS matrix)
              ├─ Defect (link to Jira issue, created from failed execution)
              └─ SharedStep (reusable step blocks)

User ─ Role ─ ProjectMembership
AuditEvent (append-only, every mutation)
```

### Key modelling decisions to make deliberately

| Decision | Recommendation | Why |
|---|---|---|
| Case versioning | Immutable snapshot per case version; a run pins the version it executed | Otherwise historical run reports silently change when a case is edited. This is the #1 thing tools get wrong. |
| Test case vs execution | Separate entities; `TestExecution` is the case-in-a-run instance | Allows same case in many runs, different configs, per-run assignee and status. |
| Suite/folder tree | Materialized path or closure table, not adjacency-list recursion | Tree reads dominate; need fast subtree queries for "run this folder". |
| Automated vs manual | Same `TestCase` entity with a `source` discriminator (MANUAL / AUTOMATED / HYBRID) + `automationKey` | Unified reporting; `automationKey` (e.g. FQCN#method) is the join target for CI ingestion. |
| Statuses | Configurable per project, seeded with Passed/Failed/Blocked/Skipped/Retest/Untested | Teams always want a custom status; retrofitting it is painful. |
| Custom fields | Typed custom-field definitions per project, EAV-with-typed-columns | Every TCMS buyer asks for this within a month. |
| Soft delete | Everywhere on user-authored content | Accidental suite deletion is unrecoverable otherwise. |

---

## 3. Functional Scope by Phase

### Phase 1 — Core (MVP, target: usable by one team)
- **Auth & users** — local accounts, BCrypt, JWT session; roles: Admin, Lead, Tester, Read-only. Project-scoped membership.
- **Projects** — create, archive, per-project settings.
- **Test case repository** — folder tree, CRUD cases with structured steps (action / test data / expected result), preconditions, priority, type, tags, attachments.
- **Rich text + attachments** — sanitized HTML, file storage abstraction (local FS first).
- **Test plans & runs** — assemble cases into a run, assign testers, execute with per-step results, comments, evidence attachments.
- **Basic reporting** — run progress, pass/fail breakdown, per-tester progress.
- **Search & filter** — across cases by tag/priority/text/custom field.
- **Audit log** — who changed what, when.

### Phase 2 — Automation & Integration
- **CI result ingestion** — REST endpoint accepting JUnit XML, TestNG XML, Allure results, Cucumber JSON. Matched to cases via `automationKey`; unmatched results auto-create cases in a quarantine folder (configurable).
- **API tokens** — per-user and per-service tokens for CI.
- **Jira integration** — link cases ↔ Jira issues, one-click defect creation from a failed step with prefilled reproduction, status sync-back via webhook or poll.
- **Notifications** — Slack/Teams incoming webhooks + SMTP. Events: run completed, run failed threshold, case assigned, defect created.
- **Git traceability** — associate a run with commit SHA / branch / PR; link cases to requirements.
- **Requirements & coverage matrix** — requirement → cases → last result → open defects.

### Phase 3 — Scale & Insight
- **Flaky test detection** — result history per automation key, flake score, quarantine flag.
- **Trend dashboards** — pass rate over time, MTTR on failing cases, execution time trends, coverage drift.
- **Bulk operations & import/export** — CSV/Excel import, TestRail/Xray migration importers.
- **Shared steps & parameterized cases** — data-driven case execution.
- **Milestones & release readiness** — gate view per milestone.
- **SSO** — OIDC/SAML (Keycloak, Okta, Entra ID) as an auth provider alongside local accounts.
- **Webhooks out** — generic outbound webhooks for custom automation.
- **Public REST API + OpenAPI** — first-class, versioned, documented.

---

## 4. Tech Stack

### Backend
| Concern | Choice | Rationale |
|---|---|---|
| Language | **Java 21 (LTS)** | Records for DTOs, sealed types for status/result modelling, virtual threads for the ingestion path. |
| Framework | **Spring Boot 3.5.x** | Ecosystem depth for the integrations we need; Spring Modulith keeps modules honest. |
| Modularity | **Spring Modulith** | Enforces module boundaries in a monolith; gives us in-process events and a documented module graph without microservice overhead. Self-hosting demands one deployable. |
| Persistence | **Spring Data JPA / Hibernate 6** | Rich domain, heavy relational traversal. |
| Database | **PostgreSQL 16** | JSONB for custom fields and raw CI payloads, full-text search, `ltree` as an option for the suite tree, recursive CTEs. |
| Migrations | **Liquibase** or **Flyway** | Flyway for plain SQL simplicity; Liquibase if we want DB-agnostic. **Recommend Flyway.** |
| Search | Postgres FTS in v1; Elasticsearch only if volume proves it | Avoid a second datastore until measured. |
| Cache/queue | **Redis** (optional, phase 2) | Ingestion job queue and rate-limiting. Start with in-process `@Async` + DB-backed job table; add Redis when justified. |
| Auth | Spring Security, JWT access + refresh; OIDC resource-server mode in phase 3 | Local-first so self-hosting needs zero external deps. |
| API docs | springdoc-openapi | Generated OpenAPI feeds the frontend client and public API. |
| File storage | Abstracted `StorageService` — local FS default, S3-compatible (MinIO) adapter | Self-hosters differ; don't hardcode. |
| Build | **Gradle (Kotlin DSL)** | Faster incremental builds, better multi-module ergonomics than Maven for this shape. |
| Testing | JUnit 5, Testcontainers (Postgres), RestAssured for API, ArchUnit for module rules | Testcontainers is non-negotiable for a JPA-heavy domain. |

### Frontend
| Concern | Choice | Rationale |
|---|---|---|
| Framework | **React 19 + TypeScript**, Vite | Data-grid-heavy app; SPA is the right shape. Vite (not Next.js) — no SEO or SSR need for an internal authenticated tool, and it keeps the artifact a static bundle. |
| UI kit | **MUI** (with MUI X DataGrid) | The grid, tree view, and date pickers we need exist; consistent look without a design team. |
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
│  ┌────────┬────────┬────────┬────────────┐  │
│  │identity│ project│repository│ execution │  │
│  ├────────┼────────┼────────┼────────────┤  │
│  │ingestion│ integration │ reporting     │  │
│  └────────┴────────┴────────┴────────────┘  │
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
- `identity` — users, roles, tokens, permissions
- `project` — projects, memberships, custom field definitions, statuses
- `repository` — suites, cases, steps, shared steps, versions, tags
- `execution` — plans, runs, executions, step results, configurations
- `ingestion` — CI payload parsing, automation-key matching, quarantine
- `integration` — Jira, notification channels, Git, outbound webhooks
- `reporting` — read models, dashboards, coverage matrix, trends
- `shared` — storage, audit, attachments, search

**Cross-module rule:** modules communicate via published domain events and exposed API packages only. ArchUnit + Modulith verification tests enforce it in CI. This is what keeps the monolith from rotting and preserves the option to split `ingestion` or `reporting` out later.

### Integration architecture notes
- **CI ingestion** must be async and idempotent. Endpoint accepts the payload, persists it raw (JSONB/blob), returns 202 with a job id; a worker parses and applies. An idempotency key per upload prevents duplicate runs from CI retries.
- **Jira** behind an anti-corruption layer — a `IssueTracker` port with a Jira adapter, so a GitHub Issues or Azure DevOps adapter is a phase-3 addition, not a rewrite.
- **Notifications** driven off domain events, per-project channel config, with a delivery-attempt log. Never let a failing Slack webhook fail a test run transaction.

---

## 6. Cross-Cutting Concerns

- **Security** — server-side authorization on every endpoint (project membership + role), input sanitization for rich text (OWASP Java HTML Sanitizer), attachment type/size limits and virus-scan hook, rate limits on ingestion, secrets never logged, CSRF strategy documented for the JWT model.
- **Performance targets** — test case list of 50k cases paginated under 300ms; ingestion of a 10k-result JUnit XML under 30s; run execution page interaction under 100ms perceived.
- **Data retention** — configurable purge of old run results and raw CI payloads; attachments are the storage hog.
- **i18n** — externalize UI strings from day one even if only English ships.
- **Accessibility** — WCAG 2.1 AA for the execution screens at minimum; testers live in them.

---

## 7. Delivery Milestones

| Milestone | Content | Rough size |
|---|---|---|
| **M0 — Foundation** | Repo structure, Gradle multi-module, Docker Compose, Postgres + Flyway, CI pipeline, auth skeleton, one vertical slice (project CRUD) end-to-end | 1–2 weeks |
| **M1 — Test repository** | Suites, cases, steps, versioning, attachments, tags, search | 3–4 weeks |
| **M2 — Execution** | Plans, runs, configurations, execution UI, basic reports | 3–4 weeks |
| **M3 — Automation ingestion** | Result parsers, automation-key matching, API tokens, quarantine flow | 2–3 weeks |
| **M4 — Integrations** | Jira, notifications, Git links, requirements & coverage matrix | 3–4 weeks |
| **M5 — Hardening & release** | Perf pass, security review, backup/restore docs, install guide, v1.0 image | 2 weeks |

Each milestone ends with a deployable image and updated install docs — self-hosted software lives or dies on the install experience.

---

## 8. Open Questions

1. **Case versioning depth** — full snapshot per edit, or event-sourced diffs? Snapshots are simpler and storage is cheap; confirm.
2. **License model** — permissive OSS (Apache 2.0), source-available (BSL), or private? Affects dependency choices and whether we publish images.
3. **Migration from an existing tool** — is anyone migrating off TestRail/Zephyr/Xray? If yes, importer moves from phase 3 into M4.
4. **Jira flavour** — Cloud, Data Center, or both? Auth and API differ materially (OAuth 2.0 3LO vs PAT).
5. **Expected data volume** — number of cases, runs/day, retention window. Drives the search and ingestion decisions.
6. **Deployment target** — bare Docker on a VM, or Kubernetes? Moves the Helm chart's priority.
7. **Concurrent execution** — do multiple testers work the same run simultaneously? If yes, we need per-execution optimistic locking and probably a live-update channel (SSE).

---

## 9. Immediate Next Steps

1. Resolve open questions 1, 4, 5, 7 — they change the M1/M2 design.
2. Produce the **detailed ERD + REST API surface** document.
3. Produce the **M0 implementation plan** and scaffold the repo.
