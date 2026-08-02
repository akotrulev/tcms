# Remaining-scope assessment — what is left to build

**Date:** 2026-08-02 · **Baseline commit:** `33e5843` (handoff §62, `cases/bulk`)
**Method:** derived from the repository, weighted by the milestone week estimates the high-level plan
sets out for itself (`docs/plans/2026-07-30-tcms-high-level-plan.md` §10, lines 2042–2051).

> This is a **status assessment, not a plan**. It says what exists and what does not, with the
> evidence for each claim. Sequencing decisions belong in `docs/plans/`.

---

## 1. How this was derived

Not from `docs/HANDOFF.md`'s narrative — that records what each slice *did*, which systematically
over-represents finished work. Four computed checks against the code instead:

### 1.1 Tables with schema and no code

Twelve of `V1`'s 64 tables have **no JPA entity and no reference in any native query**. Each is a
feature whose schema was designed in M0 and whose behaviour was never built:

| Table | The feature it belongs to |
|---|---|
| `api_token` | API tokens for CI (M3) |
| `automation_key_mapping` · `ingestion_job` | CI result ingestion (M3) |
| `report_definition` · `report_artifact` | The reporting engine (M2.5) |
| `review_comment` · `review_participation` | The review workflow's reviewer apparatus (M1.5) |
| `shared_step` | The shared-step library (M1.5) |
| `test_case_draft` | Case drafts / autosave |
| `test_plan_version` | Plan versioning (§6j) |
| `work_item_link` · `derived_item_annotation` | Work-item linking and the defect view (M4) |

**`attachment` and `evidence` have no entity either** — they escape the list above only because the
words appear in comments. There is no upload path anywhere in the product.

### 1.2 Empty modules

`reporting` and `shared` contain **0 Java files**. Both are declared in the architecture.

### 1.3 Contract coverage is not product coverage

The contract declares **42 paths; 39 have controllers**. The three without are
`/shared-steps`, `/reports` and `/reports/generate` — the two dead ends §61 and §62 both recorded.

**This number is misleading on its own and should not be quoted as progress.** The contract covers
the MVP surface. Ingestion, SSO, issue trackers, uploads, exports, clone and templates are not in
`openapi.yaml` *at all*, so they cannot show up as missing paths.

### 1.4 Absent dependencies

No Gherkin or Cucumber parser in `backend/gradle/libs.versions.toml`, so the "syntax-aware editor,
server-side parse/validation via the official Gherkin parser" of Phase 1 is a free-text field with a
Zod rule.

---

## 2. Shot list

### 2.1 Not started

| # | Functionality | Milestone | Evidence |
|---|---|---|---|
| 1 | CI result ingestion (JUnit · TestNG · Allure · Cucumber) | M3 | `ingestion_job`, `automation_key_mapping` unused |
| 2 | API tokens for CI | M3 | `api_token` unused |
| 3 | Quarantine flow for unmatched results | M3 | no code |
| 4 | Reporting engine — `ReportModel`, async job pipeline | M2.5 | `reporting` module empty; `report_definition` unused |
| 5 | HTML / Markdown / PDF export | M2.5 | no renderer, no dependency |
| 6 | Aggregate execution report | M2.5 | `/reports` has no controller and no SPA hook |
| 7 | Evidence capture, attachments, file-storage port | M2 | `attachment` + `evidence` have no entity |
| 8 | Evidence-bundle export | M2.5 | depends on 5 and 7 |
| 9 | Shared-step library | M1.5 | `shared_step` unused; `useSharedSteps` has 0 call sites |
| 10 | Plan templates + clone case / plan / run (§6v) | M1–M2 | no code, no table |
| 11 | SSO / OIDC (Cognito · Entra · Google · Okta · Keycloak) + LDAP | M4 | no code |
| 12 | Issue trackers ×4 (Jira · GitHub · GitLab · Azure DevOps) | M4 | no code |
| 13 | Notifications (Slack / Teams webhooks, SMTP events) | M4 | mailer exists for invitations only |
| 14 | Requirements & coverage matrix | M4 | no code |
| 15 | Git traceability (commit / branch / PR on a run) | M4 | no code |
| 16 | Org chart | M4 | no code |
| 17 | Plan versioning · case drafts / autosave | M1–M2 | `test_plan_version`, `test_case_draft` unused |
| 18 | Rich text + sanitized HTML | M1 | plain text throughout |
| 19 | **All of Phase 3** — flake detection, trend dashboards, CSV/Excel import, TestRail/Xray migration, parameterized cases, step-phrase library, undefined-step detection, milestones, SAML, SCIM, outbound webhooks | P3 | no code |

### 2.2 Partially built

| # | Functionality | Done | Not done | Left |
|---|---|---|---|---|
| 20 | Review workflow (M1.5) | The four states, `APPROVED` reachable only from `IN_REVIEW`, the `case:approve` gate, bulk submit/approve (§62) | Reviewer assignment, threaded comments, LGTM / request-changes, the per-project opt-in setting, author-cannot-approve-own-work, diff-since-approval, re-review vs retain | ~65% |
| 21 | Gherkin authoring (M1) | `STRUCTURED`/`GHERKIN` formats, free-text source, cross-field validation | Official parser, keyword autocomplete, Background + Scenario Outline, `.feature` preview | ~75% |
| 22 | Library typeahead (M1.5 §6h) | Condition library, usage-ranked ordering, project-scoped search | `tsvector` + `pg_trgm` path (currently `LIKE` on title only), the sub-100ms p99 budget, duplicate warning at creation | ~50% |
| 23 | Kanban / work items (M4) | Board live end to end, five columns, idempotent create, derived cards, project members | WIP limits, personal + team boards, the defect view (§6l), `work_item_link` | ~40% |
| 24 | Multi-company activation (M6) | Invitation, acceptance, membership removal, session epoch, `issue-invitation` | Company switcher, `/c/{slug}` routing, session switching, `COMPANY`-scope assignment UI, per-company IdP + email-domain routing, per-company branding | ~65% |
| 25 | Hardening & release (M5) | Hetzner box live, runbook executed and corrected, GHCR image, privilege split verified in production | Perf pass, security review, backup/restore procedure, install guide, **a Linux CI gate that runs the tests** | ~60% |

### 2.3 Complete

Tenancy + RLS + the isolation harness · identity, roles, permissions, the evaluator · project CRUD ·
suite tree · case CRUD, versions, timelines, health signals · classification · search, facets,
shareable filters · repository dashboard · plans, runs, executions, results, configurations · plan
statistics · documentation space · artifact register · bulk operations · the resilience layer
(boundaries, retry, session recovery, version skew) · deployment.

---

## 3. Percentage remaining

Weighted by the plan's own milestone estimates (midpoints of its ranges).

| Milestone | Weeks | Complete | Weeks left |
|---|---:|---:|---:|
| M0 — Foundation | 2.5 | 100% | 0.00 |
| M0.5 — Identity & access | 2.0 | 95% | 0.10 |
| M1 — Test repository | 4.5 | 75% | 1.13 |
| M1.5 — Reuse & review | 3.0 | 45% | 1.65 |
| M2 — Execution | 5.5 | 65% | 1.93 |
| M2.5 — Docs, artifacts & reporting | 7.5 | 50% | 3.75 |
| M3 — Automation ingestion | 2.5 | 0% | 2.50 |
| M4 — Integrations & workflow | 9.0 | 12% | 7.92 |
| M5 — Hardening & release | 2.0 | 40% | 1.20 |
| M6 — Multi-company activation | 1.75 | 35% | 1.14 |
| **Total** | **40.25** | | **21.32** |

### The headline numbers

- **~55% of the full plan remains** — 21.3 of 40.25 weeks (53.0%).
- **~50% remains to M5**, which the plan calls a legitimate release point: *"a single-company v1.0
  can ship and be used in production while M6 is built"* (20.2 of 38.5 weeks, 52.4%).
- **~40% remains to a core TCMS without the M4 integrations block** (12.3 of 29.5 weeks, 41.6%).

### Where the remaining weight sits

**M4 alone is 9 weeks and is ~12% done** — it is the largest single block left by a wide margin.
The second largest is **reporting, half of M2.5's 7.5 weeks, sitting at zero**.

---

## 4. Two caveats

1. **The per-milestone completion figures are judgment**, made against each milestone's own
   deliverable list in the plan's table. The evidence for each is §2 above; the week weights are the
   plan's, not this document's.
2. **The remaining weeks are less risky than the elapsed ones.** Tenancy, RLS, the authorization
   model, the contract discipline, the resilience layer and a 1156-test harness are done, so most of
   what is left is *additive* rather than structural. That lowers the risk per week; it does not
   lower the count. By the plan's own accounting, more than half the work is still ahead.

---

## 5. Standing risk this assessment surfaces

**No machine anywhere runs the backend test suite.** CI has run `./gradlew build -x test` since §59,
and `./gradlew build` on a developer's own macOS machine is the only gate — which structurally cannot
observe a Linux-only defect (§58, §60). Every one of the 21 remaining weeks lands on that footing.
`docs/plans/2026-08-01-tcms-homelab-target.md` §9 is the standing proposal.
