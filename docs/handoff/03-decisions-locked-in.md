# §3. Decisions locked in (do not re-litigate without reason)

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
