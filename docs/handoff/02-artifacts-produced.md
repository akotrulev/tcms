# §2. Artifacts produced

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
