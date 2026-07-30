# Kiwi TCMS — What They Have That We Don't

**Status:** Research note, for plan amendment
**Date:** 2026-07-30
**Companion to:** `docs/plans/2026-07-30-tcms-high-level-plan.md`
**Direction:** This document is deliberately one-directional — it catalogues **Kiwi TCMS capabilities the TCMS plan does not cover**. The reverse comparison (where TCMS is differentiated) is not repeated here; see §1/§6s/§6a/§6b/§6j of the plan.

**Verification basis:** kiwitcms.org feature page, readthedocs (stable), and the GitHub org API (33 repos, licenses and last-push dates read 2026-07-30). Kiwi was **not** installed and run — feature *presence* is documented-claim-level, not hands-on-verified. Anything marked ⚠ needs hands-on confirmation before it drives a scope decision.

---

## 1. Real gaps — worth acting on

Ranked by how much the omission would cost us, not by size.

### 1.1 A third-party extension mechanism ⭐ most strategically important

Kiwi ships a **documented plugin architecture** and has 8 automation-framework plugins, 12 bug-tracker integrations, a GitHub App and a multi-tenancy layer — most of them in *separate repos with separate licenses*. That is how a small team got that much integration surface: it didn't write it all.

The TCMS plan has **ports** (`IssueTracker`, `StorageService`, `SearchService` — §4/§5) but **no extension point a third party can build against**. Ports are internal seams for *us*; they are not a plugin API. For a self-hosted open-source product, that difference compounds: every integration is ours to write, forever.

> **Recommendation:** decide explicitly whether TCMS wants third-party extensions. If yes, the cheapest version is a *result-parser* SPI plus an *outbound-webhook* contract (the latter is already Phase 3), not a general plugin host. If no, say so as a non-goal in §1 and accept that the integration list is bounded by our own throughput.

### 1.2 Enterprise-grade auth we haven't scoped at all: LDAP and Kerberos

Kiwi supports **username/password, Kerberos (GSSAPI), LDAP, and OAuth** today, via `python-social-auth`, `django-python3-ldap` and `social-auth-kerberos`.

The plan's §6r covers OIDC only — Cognito, Entra ID, Google Workspace, Okta, Keycloak, generic OIDC — with SAML 2.0 deferred to Phase 3. **LDAP and Kerberos appear nowhere in the plan.**

This is a genuine gap, not a stylistic one. A large share of self-hosters running on-prem have Active Directory / LDAP and no OIDC issuer. §6r's reasoning for deferring SSO to M4 is sound, but the *provider list* is missing the two most common on-prem cases.

> **Recommendation:** add LDAP bind authentication to §6r's provider list (Spring Security has first-class support; it is days, not weeks). Kerberos/SPNEGO can stay out — it is genuinely niche and painful — but state that as a decision rather than an omission.

### 1.3 Client-side automation plugins, and 4 result formats we don't parse

Two separate gaps hiding in one place.

**Format coverage.** Plan §3 Phase 2 ingests JUnit XML, TestNG XML, Allure, and Cucumber JSON. Kiwi additionally covers:

| Format / framework | Kiwi | Plan |
|---|---|---|
| junit.xml | ✅ | ✅ |
| TestNG | ✅ | ✅ |
| Allure | ❌ | ✅ |
| Cucumber JSON | ⚠ WIP only (see 3.1) | ✅ |
| **Robot Framework** | ✅ | ❌ |
| **pytest (native plugin)** | ✅ | ❌ |
| **PHPUnit** | ✅ | ❌ |
| **TAP (Test Anything Protocol)** | ✅ | ❌ |
| **Django test runner** | ✅ | ❌ |

Robot Framework and TAP are the two that matter — Robot has a large installed base in exactly the enterprise QA teams this product targets, and TAP is trivial to parse.

**Delivery model.** More important than the format list: Kiwi's plugins are **client-side** — they run inside the test process and push results. The plan's model is *upload a result file to a REST endpoint*. The push model is better for adoption (`pip install`, one env var, done) versus asking a team to add a CI step that finds and POSTs an artifact.

> **Recommendation:** keep the file-upload endpoint as the primitive — it is the right substrate and it is idempotent (§5). Add **one** reference client-side plugin as a thin wrapper over it (pytest or Robot, whichever the answer to open question #1 favours), and document the contract so others can be contributed. Add TAP and Robot XML parsers to M3; they are parser work against an existing pipeline, not new architecture.

### 1.4 Cloning is not in the plan

Kiwi clones **TestPlans, TestCases, and TestRuns** as a first-class action.

The plan mentions "Bulk operations & import/export" in Phase 3, but **clone is never listed** for any entity. This is a daily-use operation in a TCMS — "run last release's regression plan against 4.3" is the single most common thing a test lead does, and re-assembling a plan by hand is the reason people keep using spreadsheets.

> **Recommendation:** clone-plan and clone-run belong in **M2** (Execution), not Phase 3. Clone-case belongs in M1. All three are cheap given the versioning model already snapshots content (§6j) — a clone is a new aggregate seeded from a version snapshot.

### 1.5 No internal defect tracker means TCMS doesn't work standalone

Kiwi has an **internal bug tracker** — it can be the defect system itself, with the external trackers as an option.

Plan §1 non-goals this explicitly ("not a defect tracker (it links to Jira, doesn't replace it)"). That is a defensible product boundary, but the consequence is under-stated: **a self-hoster with no Jira/GitLab/Redmine has nowhere for `defect:create` (§6b) to go.** The entire failed-step → defect → traceability story (§6e, Phase 2) is inert without an external tracker. Kiwi is useful on day one with zero integrations configured; TCMS is not.

> **Recommendation:** this doesn't require building a bug tracker. The minimum honest answer is a **local `Defect` record** as the default `IssueTracker` adapter — the entity already exists in §2's model, it already carries the link to a failed execution, and it costs a table plus a list view. It makes the traceability chain complete for an unintegrated install, and it composes with the port design rather than fighting it.

### 1.6 Bug-tracker breadth: 12 vs 1

Kiwi integrates Azure Boards, BitBucket Issues, Bugzilla, GitHub Issues, GitHub Issues via App, GitLab Issues, JIRA, its own tracker, Mantis BT, OpenProject, Redmine, and Trac — with 1-click filing, automatic back-linking, and hover-card bug info.

The plan has **Jira in M4**, with GitHub Issues and Azure DevOps named as Phase 3 additions behind the `IssueTracker` port (§5).

The architecture is right and this is not an emergency — but the list above is effectively our integration roadmap, pre-prioritized by someone with adoption data. Two specific Kiwi behaviours are worth copying regardless of tracker count:

- **automatic back-link** — filing a defect adds a comment on the *tracker* side pointing back to the execution. Without it, traceability is one-directional and the Jira-only user can't navigate back.
- **hover-card bug info** — resolve and cache tracker status/title inline. §6e's reports show defect links; showing a stale link with no status is the version of this feature people stop trusting.

Neither is in the plan.

### 1.7 Mermaid diagrams in Markdown

Kiwi renders Mermaid.js diagrams from Markdown (and the Enterprise build rasterizes them to PNG for export).

The plan's documentation space (§6i) uses TipTap rich text with stable block anchors, and §6e renders reports to HTML/MD/PDF. **Mermaid is not mentioned.** For a documentation space whose whole purpose is holding test strategy and environment guides, diagram support is not decoration — those documents are 40% diagrams in practice.

> **Recommendation:** cheap win, high visibility. Mermaid renders client-side for the doc space; for PDF export the headless-Chromium renderer already in §4 handles it with no extra dependency.

### 1.8 Notification granularity: per-entity subscribers

Kiwi attaches **email notification lists to TestPlans, TestCases, and TestRuns individually** — per-object subscribers.

Plan §3 Phase 2 defines a fixed event list: run completed, run failed threshold, case assigned, defect created. There is no "watch this case" / "notify this plan's subscribers" concept. For a review workflow (§6g) and a shared condition library (§6f) — where an edit propagates to many cases — a watch mechanism is the natural way people expect to be told.

> **Recommendation:** low priority, but note that §6f's `library:manage` impact modal and a watch list solve adjacent problems. Worth one line in §6g.

### 1.9 Parameters at run level, and data-driven execution shipping today

Kiwi carries **parameters on TestPlan, TestCase, TestRun and TestExecution** — four levels.

The plan has `Parameter[] / DataSet` on the case (§2) and defers **data-driven case execution to Phase 3** ("Shared steps & parameterized cases"). Kiwi has this now, and run-level parameters (e.g. one parameterized plan executed against three tenants) are a shape our model doesn't express at all.

> **Recommendation:** don't move Phase 3 work forward. Do check that the M2 `Configuration` entity (§2) isn't quietly being asked to do run-level parameters' job — those are different concepts and conflating them is a schema mistake.

---

## 2. Kiwi advantages that are our deliberate non-goals — with the cost named

These are **not** action items. They are recorded so the trade is explicit and nobody re-litigates it later without the cost in front of them.

| Kiwi capability | Our position | The cost we're accepting |
|---|---|---|
| **Multi-database: MariaDB ≥10.4, MySQL ≥8.0.11, PostgreSQL ≥13, SQLite** | Postgres 16 only (§4) | Real. Some on-prem shops mandate MySQL or SQL Server. But the plan's data strategy *is* Postgres — JSONB versions (§6j), FTS + `pg_trgm` search (§6h/§6q), `ltree` suite trees, recursive CTEs, `UPDATE…RETURNING` key allocation (§6t), optional RLS (§6s). Portability would mean giving up most of that. Correct call; state it in the install docs so it's not a surprise. |
| **SQLite backend → zero-dependency evaluation install** | Compose ships Postgres (§4) | Minor but real conversion friction. A `docker compose up` that needs a DB container is still one command, so the gap is small. Not worth breaking 1️⃣ above for. |
| **Schema-per-tenant multi-tenancy** (`django-tenants` fork, FQDN routing) | Rejected explicitly (plan line 1731) in favour of shared-schema `company_id` | None — this is us being right, and it is our lead differentiator (§6s). Recorded here only because it looks like a "they have it, we don't" and isn't. Note Kiwi's is FQDN-routed, so it needs per-tenant DNS; ours is `/c/{slug}` with a session-resolved company. |
| **Shipped UI translations + "translation mode"** | §6 externalizes strings from day one, English only ships | Small. Architecture is parity; only the translation *content* is missing, which is a community-contribution problem, not an engineering one. |
| **Internal bug tracker as a full feature** | §1 non-goal | See 1.5 — the non-goal is right, but the minimal local `Defect` fallback is not the same thing as building a tracker. |

---

## 3. Claimed by Kiwi, but actually parity or better in the plan

Recorded so this list doesn't get mistaken for a gap on a second reading.

### 3.1 Gherkin / Cucumber — the nuance behind the earlier claim

An earlier comparison stated Kiwi "has no equivalent" to §6a's dual step format. That holds, with a correction worth having on record: **`kiwitcms/cucumber-plugin` exists** (GPL-3.0, actively pushed 2026-07-24) — but its README says "Work in progress", it has **no tags or releases**, and 0 stars. It is a *result parser*, not a Gherkin authoring format.

So the substance is unchanged: Kiwi has no per-case Gherkin authoring, no keyword-typed step storage, and no per-step Cucumber result mapping. §6a remains differentiated. But we should not claim Kiwi is *unaware* of Cucumber — they're building toward the ingestion half.

### 3.2 Telemetry reports

Kiwi has **4**: testing break-down, status matrix, execution trends, flaky tests.

The plan covers all four and considerably more (§6e aggregate execution report, coverage matrix, type×level matrix per §6d, PDF/MD/HTML rendering, `ExecutionFact` read model). Two notes:

- **Kiwi's flaky-test detection ships today**; ours is Phase 3. Not a gap in design, only in sequencing — but it's a headline feature to concede in a bake-off.
- **"Status matrix"** is a specific named view — cases × runs, cells coloured by result. Worth confirming §6p's plan/suite pages actually produce this shape, because it's the view testers ask for by name. ⚠

### 3.3 Already covered

| Kiwi claim | Plan equivalent |
|---|---|
| Personal API tokens | §3 Phase 2 / M3 — per-user and per-service, scoped to a role *and* a company (§6b) — stronger than Kiwi's |
| Test matrix generation on TestRun | §2 `Configuration (env/browser/OS matrix)`, M2 |
| Attachments on plans/cases/runs | §3 M1 + §6 evidence capture at step *and* execution level — stronger |
| History on plans/cases/runs/executions | §6j immutable versions + run-pinned version + append-only audit — substantially stronger |
| Test case review on TestPlan | §6g review workflow with the three-tier comment/review/approve split (§6b) — stronger |
| Responsive design | Not explicitly addressed in §4's frontend section. ⚠ Minor, but MUI DataGrid-heavy screens don't become responsive by accident. |
| No import/export by design — Kiwi **refuses** Excel/XML/CSV import/export and directs users to the API | **We're ahead.** Plan Phase 3 has CSV/Excel import plus TestRail/Xray migration importers. This is a stated Kiwi non-goal and a clean opening — migration friction is the #1 reason teams stay on TestRail. |

---

## 4. Ecosystem and maturity gap

Not features, but they belong in any honest comparison:

- **"over 2 million downloads"** (their repo tagline); we are pre-alpha.
- **33 repos**, published Docker images, a Python client library (`tcms-api`, LGPL-2.1), and a community `api-scripts` repo.
- **GitHub Marketplace listing**, GitHub App, and GitHub Marketplace billing integration.
- **Funding and presence:** Tidelift subscription, OpenCollective sponsors, FOSDEM stands, commercial support offering.
- **Enterprise build** (`kiwitcms/enterprise`, AGPL-3.0) bundling django-prometheus, sentry-sdk, django-storages, django-ses, psycopg-pool, certbot/Let's Encrypt automation, versioned static files.

Two items from that Enterprise bundle are absent from our §4 Packaging & Ops and are worth stealing:

- **Sentry-style error tracking** — §4 has Actuator + Micrometer + optional OTel, which is metrics and traces. It has no error-aggregation story. For self-hosted software where you can't read the customer's logs, an opt-in error reporter is how you find out what's breaking.
- **Automated TLS via certbot in the compose story** — a genuine self-hosting nicety. §4's compose ships app + Postgres + optional MinIO, with TLS left as the operator's problem.

---

## 5. Their license patchwork — a constraint, not a gap

Recorded because it is the operative reason none of the above can be copied rather than reimplemented. Read from the GitHub org API on 2026-07-30:

| Component | License |
|---|---|
| `Kiwi` (core) | **GPL-2.0** |
| `tenants` (multi-tenancy) | **AGPL-3.0** |
| `enterprise`, `github-app`, `github-marketplace`, `trackers-integration`, `gitops`, `gssapi` | **AGPL-3.0** |
| `pytest-plugin`, `robotframework-plugin`, `junit-plugin`, `phpunit-plugin`, `tap-plugin`, `junit.xml-plugin`, `django-plugin`, `cucumber-plugin`, `api-scripts` | **GPL-3.0** |
| `tcms-api` (Python client) | **LGPL-2.1** |
| `django-tenants` (their fork) | MIT |
| `openldap` | Apache-2.0 |

Three consequences:

1. **GPLv2 core + AGPLv3 add-ons are mutually incompatible** — they cannot lawfully be combined into one work. Kiwi manages this by keeping them separate distributions. A Java port absorbing both could not.
2. **The pieces most worth studying are the most restrictively licensed** — multi-tenancy and every integration are AGPL-3.0 (network copyleft).
3. **Therefore: read for design, write from scratch.** Feature lists, format specs, and data-model concepts are not protected expression; code is, and a language port is still a derivative work.

---

## 6. Proposed plan amendments

Concrete, in priority order. Nothing here is more than a few days except 1️⃣.

| # | Change | Milestone | Plan section to edit |
|---|---|---|---|
| 1 | Add **clone** for case / plan / run | M1, M2 | §3 Phase 1; §2 |
| 2 | Add **local `Defect` fallback** as the default `IssueTracker` adapter, so traceability works with zero integrations | M2 or M4 | §1 non-goals (qualify), §5 |
| 3 | Add **LDAP bind auth** to the provider list | M4 | §6r |
| 4 | Add **TAP + Robot Framework** result parsers | M3 | §3 Phase 2 |
| 5 | Add **Mermaid** rendering in the doc space and PDF export | M2.5 | §6i, §6e |
| 6 | Add **tracker back-link + status hover-card** to the Jira integration | M4 | §5 integration notes |
| 7 | Decide the **third-party extension** question — result-parser SPI, or an explicit non-goal | Pre-M3 | §1, §5 |
| 8 | Add **opt-in error reporting** and document a **TLS story** in packaging | M5 | §4 Packaging & Ops |
| 9 | Ship **one reference client-side result-push plugin** | M3 | §3 Phase 2 |
| 10 | Confirm §6p produces a **cases × runs status matrix** view ⚠ | M2 | §6p |

Open questions this raises for the plan's §8 list:

- **Is LDAP/AD the actual auth reality for target self-hosters, or OIDC?** Changes 3️⃣ from "add it" to "add it *first*". Relates to existing open question #38 (JIT provisioning).
- **Do we want third-party extensions at all?** This is a strategy question, not a technical one, and it should be answered before M3 hardens the ingestion contract.

---

## Sources

- [kiwitcms/Kiwi](https://github.com/kiwitcms/Kiwi) — core, GPL-2.0
- [Kiwi TCMS features](https://kiwitcms.org/features/) — feature, integration, auth and DB support claims
- [Kiwi TCMS legal information](https://kiwitcms.org/legal/) — "mostly GPLv2 and AGPLv3+"
- [kiwitcms/tenants](https://github.com/kiwitcms/tenants) · [kiwitcms-tenants on PyPI](https://pypi.org/project/kiwitcms-tenants/0.4.4/) — schema-per-tenant, FQDN routing, AGPL-3.0
- [kiwitcms/enterprise](https://github.com/kiwitcms/enterprise) — Enterprise bundle contents
- [Kiwi TCMS import/export docs](https://kiwitcms.readthedocs.io/en/stable/import-export/index.html) — the stated refusal to support Excel/XML/CSV
- [Kiwi TCMS API docs](https://kiwitcms.readthedocs.io/en/stable/api/index.html) — XML-RPC / JSON-RPC
- [Legacy reports become Telemetry](https://kiwitcms.org/blog/kiwi-tcms-team/2019/03/03/legacy-reports-become-telemetry/) — the four telemetry reports
- GitHub org API, `orgs/kiwitcms/repos`, read 2026-07-30 — repo list, languages, SPDX licenses, push dates
