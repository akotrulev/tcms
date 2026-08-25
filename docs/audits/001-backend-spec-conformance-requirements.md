# 001 — Backend spec-conformance register

**Date:** 2026-08-22
**Auditor:** `code-auditor` (SPEC lane, `ep-spec-vs-code-audit`)
**Subject:** `backend/` — Spring Boot 3.5.6 / Java 21 / Gradle, Spring Modulith monolith,
Flyway-owned DDL, Postgres 17 with RLS.
**Baseline commit:** `62875cc5` (§249, `master`)

> **This file is the spine, not a report.** It is the deduped, ID-stable consolidation of the
> authoritative sources listed below. **IDs are permanent.** Verdicts live in
> `001-backend-spec-conformance-audit-r<N>.md`, never here. A later round appends with the next
> free ID marked `Added r<N>`; nothing is renumbered, ever, because every round-over-round claim
> is keyed on these IDs.

## Sources consolidated, in the precedence order the repo declares

| # | Source | Contributes |
|---|---|---|
| S1 | `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | §A1–A7 scope model, roles, permission matrix (§A3 authoritative), Agent grant (§A6a), naming (§B1–B3) |
| S2 | `docs/specs/openapi.yaml` | The REST contract — 128 paths |
| S3 | `docs/specs/2026-07-30-tcms-erd.md` | Entity design; **§0.1** `company_id` rule + RLS exception list; **§0.2** module boundaries |
| S4 | `docs/plans/2026-07-30-tcms-high-level-plan.md` | §6a–§6v decisions, milestones M0–M6 |
| S5 | `docs/specs/tenancy-invariants.md` | The three enforcement layers, the two RLS traps, the repository-`@Transactional` rule |
| S6 | `CLAUDE.md` working agreement | Config-text rule (§108), computed-check rule, contract-first rule |

**Deviation from lane discipline, stated rather than hidden.** `ep-spec-vs-code-audit` requires the
register to be saved *before* product code is read. On this run the orientation pass (stack/version
detection, layout, build discovery) necessarily read `build.gradle.kts`, `application.yml` and three
representative sources before consolidation. No requirement below was *derived from* code: every row
cites its source document and section. Where a source is silent the row says so rather than being
back-filled from the implementation.

**Not in scope of this register:** `frontend/`, `packages/`, `deploy/`. Rows touch those trees only
where a backend requirement's enforcement mechanism lives there (Z16, V3).

---

## T — Tenancy and isolation (S3 §0.1, S5)

| ID | Requirement | Source |
|---|---|---|
| T1 | `company_id UUID NOT NULL` on every company-owned table, including join tables, version tables, audit events, read models and the raw CI payload store | S3 §0.1.1, S5 |
| T2 | The exception list is closed and decided: `company`, `app_user`, `permission`, `role`, `role_permission`, `instance_role_assignment`, `flyway_schema_history`, `instance_setting`, `instance_audit_event`, `api_token_lookup`, `membership_invitation`, `sso_login_attempt`, `user_social_identity`, `social_login_attempt`. Nothing may be added without a decision | S3 §0.1 |
| T3 | A **schema test** asserts T1 over every table not on the exception list, so a new table fails the build until it complies | S3 §0.1.1, S5.1 |
| T4 | A globally-applied Hibernate filter (`@FilterDef` + `@Filter`) enabled per session from `CompanyContext` | S3 §0.1.2 |
| T5 | `CompanyContext` is populated from the authenticated session — **never** from a request parameter, header or path segment | S3 §0.1.2, S5.2 |
| T6 | `CompanyContext` is a scope guard (`try (var ignored = open(id))`), never a setter, and is **not** `InheritableThreadLocal` | S5.2 |
| T7 | `company_id` **leads** every composite index and every previously-global uniqueness constraint | S3 §0.1.3 |
| T8 | Postgres RLS enabled by default, policies on `company_id`, GUC `app.current_company` set once per transaction with `SET LOCAL` | S3 §0.1.4 |
| T9 | `FORCE ROW LEVEL SECURITY`, not merely `ENABLE` — the owner bypasses plain `ENABLE` | S3 §0.1.4, S5 |
| T10 | An unset GUC yields **zero** rows, not all rows. Both spellings of unset — never-set (NULL) and reset-on-a-pooled-connection (`''`) — must collapse to NULL, hence `nullif(…, '')` | S3 §0.1.4, S5 |
| T11 | Every isolation policy carries the **same** predicate; none has been widened, renamed, or given an explicit `WITH CHECK` | S5 (implied), S3 §0.1.4 |
| T12 | The migration fails loudly if the policy list drifts (a named table that does not exist raises) | S3 §0.1.4 |
| T13 | `CompanyAwareTransactionManager` hooks `doBegin`, so both layers are established for *every* transaction, not just web requests | S5.3 |
| T14 | The app connects as an unprivileged role (`tcms_app`) owning nothing and holding no `BYPASSRLS`; Flyway runs as a separate owner role; neither test-harness role is a superuser | S5.3 |
| T15 | **A repository interface must carry `@Transactional`** — without it, derived and `@Query` methods run outside any transaction, the GUC is never set, and every RLS-protected read returns zero rows | S5 |
| T16 | Isolation is proven by a paired-fixture suite: two companies with deliberately identical-looking data, plus a third persona (member, no project grant) | S3 §0.1 closing |

## Z — Authorization (S1)

| ID | Requirement | Source |
|---|---|---|
| Z1 | Three scopes — `SYSTEM`, `COMPANY`, `PROJECT`. A company-level question is answered from the SYSTEM and COMPANY terms only | S1 §A1, §A3 |
| Z2 | Code checks **permissions, never role names** | S1 §A2, S4 §6b |
| Z3 | **No wildcard permission.** Instance Admin's grant is derived by joining the whole catalogue, never a `'*'` sentinel | S1 §A3 (via `V3`), S6 |
| Z4 | `SYSTEM`-scope grants live in `instance_role_assignment`, a separate instance-level table — never a nullable `company_id` on `role_assignment` | S3 §2, S1 §A1 |
| Z5 | The catalogue is exactly **55 keys** in the areas Repository/Review/Execution/Work/Reporting/Documentation/Artifacts/Project/Company/System | S1 §A3 |
| Z6 | The §A3 matrix holds cell-for-cell for the six built-in roles (IA/CA/TL/TE/AS/V) as seeded | S1 §A3 |
| Z7 | No grant carries `OWN` or `PLAN_SCOPE` as of `V52`; every `role_permission.qualifier` is `ALL` | S1 §A3, §A5 |
| Z8 | Automation Service holds exactly six keys: `case:read`, `case:create`, `run:create`, `result:submit`, `evidence:attach` + `project:read` (`V16`) — the smallest grant in the product | S1 §A6 |
| Z9 | `Agent` is **Test Engineer entire, minus `company:read`**, derived from Test Engineer's rows so the two cannot drift. It is **no longer a ceiling** | S1 §A6a (rewritten 2026-08-15) |
| Z10 | `expiresAt` is **required** for an `Agent` token, optional for every other role, enforced server-side rather than in the form | S1 §A7 |
| Z11 | A token never exceeds its issuer: `effective = evaluate(owner, project) ∩ permissionsOf(role) ∩ [the token's project]` | S1 §A6a, S4 §6p decision C |
| Z12 | `company:read` is granted to every member explicitly, via the non-assignable `Company Member` baseline (`V15`), not implicitly by membership | S1 §A2, §A3 |
| Z13 | `Automation Service` and `Agent` are non-assignable to people; `Agent` is still issuable to a token and listed by `GET /roles` | S1 §A2, §A6a |
| Z14 | Defaults: Viewer at every entry point (invitation, SSO JIT, project assignment); Company Admin sees every project in the company with no per-project grant | S1 §A7 |
| Z15 | Every catalogue key has an evaluation site, **or** a recorded decision naming the gate that answers it instead | S1 §A3 (`doc:read`/`artifact:read` annotations) |
| Z16 | Prose that names a role and a key is a claim about the catalogue and must be true of it | S1 (§212 rule), S6 |

## M — Module boundaries (S3 §0.2, S4 §5)

| ID | Requirement | Source |
|---|---|---|
| M1 | Modules are Java *packages* under `com.tcms`, not Gradle subprojects; the list is pinned so an addition is a deliberate edit | S4 §5 |
| M2 | Cross-module **FKs** are permitted only toward the foundation modules; everything else crosses via domain events | S3 §0.2 |
| M3 | The foundation set is `tenancy`, `identity`, `project`, `storage` (§125) — and `api` (§146) | S3 §0.2, S4 §125/§146 |
| M4 | The boundary is **enforced by ArchUnit, not convention** | S3 §0.2 |
| M5 | `tenancy` is depended on by everything and depends on nothing | S4 §5 |

## C — Configuration and secrets (S6, §108)

| ID | Requirement | Source |
|---|---|---|
| C1 | `${VAR:?msg}` and `${VAR:-x}` are shell/Compose forms and mean nothing to Spring. In `application*.yml` a **required** property is `${VAR}` with **no colon** | S6 |
| C2 | No profile-specific file reintroduces a default for a secret | S6 |
| C3 | A required-property guarantee binds the *app*; the *deployment* must also pass every no-colon variable | S6 (§216) |
| C4 | `@ConfigurationProperties` binding does **not** throw on an unresolvable placeholder — the literal text is passed through — so every no-colon secret needs value validation that rejects the literal | S6 (§216, measured 2026-08-15) |
| C5 | Every configured blob root is created and owned by the runtime user in the image, bind-mounted in production at the same path, and persisted locally | S6 (§107/§110/§160) |
| C6 | A schema-wide or catalogue-wide invariant needs a **computed** check over the *source text*, not a careful reading | S6 |

## P — Provisioning and async context (S5, S4 §6s)

| ID | Requirement | Source |
|---|---|---|
| P1 | Companies are created by exactly one path — `CompanyProvisioningService`, driven by the CLI. Idempotent, never updating | S5 |
| P2 | Only that path may call `tcms_seed_company_defaults` / `tcms_seed_project_defaults` | S5 |
| P3 | `CompanyContextTaskDecorator` clears the context on every task, so an async handler that forgets fails closed | S5 |
| P4 | `CompanyScopedEvent` is the only thing a handler may re-establish context from | S5 |
| P5 | A scheduled job over company-owned data must open a context per company; the tempting instance-wide query returns zero rows and logs success | S5, S3 §0.1.4 |

## N — Naming and keys (S1 §B)

| ID | Requirement | Source |
|---|---|---|
| N1 | Display keys are company-scoped, allocated from `key_sequence` | S1 §B2 |
| N2 | The hierarchy is Instance → Company → Project → suites; a suite is a flat named collection since §98 | S4 §1, §98 |
| N3 | Previously-global uniqueness constraints (`project.key`, `team.name`, `api_token.name`, `test_case.automation_key`, `key_sequence`, classification values) are company-scoped | S3 §0.1.3, S1 §B3 |

## X — Contract (S2)

| ID | Requirement | Source |
|---|---|---|
| X1 | `openapi.yaml` is the contract: every declared operation has a handler | S2, S6 (§145) |
| X2 | Every added schema carries its `required` array, including on inline nested objects | S6 |
| X3 | Server enums and contract enums agree, or the divergence is a recorded, pinned decision | S6 (§161) |
| X4 | One media type for every refusal; a 400/409/422 always carries a body; a 401/404 carries none | S6 (§148/§149/§162) |
| X5 | No refusal echoes the request | S6 (§163) |

## V — Verification discipline (S6 §65)

| ID | Requirement | Source |
|---|---|---|
| V1 | Browser passes and backend mutation checks are deferred and batched; a slice records its row in the matching queue and runs nothing inline | S6 §65 |
| V2 | A slice in an **unrun** queue is *built and unverified*, and must be described that way | S6 §65 |
| V3 | Gates run on `shanks` via `~/bin/tcms-remote`, not locally, and not from a subagent | S6, standing directive |
| V4 | A mutation row that predicts zero reds is still a row; the measured number is the deliverable | `docs/verification/backend-mutation-checks.md` header |

---

**Totals:** 54 requirements in 7 sections · 0 `⚠️ CONFLICT` pairs found between sources during
consolidation · 0 rows out of scope. Where two sources overlapped (S3 §0.1 vs S5 on the enforcement
layers; S1 §A3 vs S1 §A6a on Agent) the higher-precedence source was taken and the other's wording
was merged rather than dropped — no source was silently overruled.
