# 001 — Backend spec-conformance audit, round 1

**Date:** 2026-08-22
**Auditor:** `code-auditor` (SPEC lane, `ep-spec-vs-code-audit`)
**Register:** `docs/audits/001-backend-spec-conformance-requirements.md`
**Baseline commit:** `62875cc5` (§249, `master`) · working tree carried one unrelated modified file
(`docs/plans/2026-08-18-unplanned-gaps.md`), not read as evidence.
**Evidence mode:** **static only.** No Gradle task, no test, no container, no live probe. Bash was
used for `ls`/`grep`/`sed`/`git log` and for four purpose-written comparison scripts over the
migrations, the entity set and the permission catalogue. Every `file:line` below was read this round.

**Stack lane:** Java 21 (`build.gradle.kts:12`), Spring Boot 3.5.6, Gradle Kotlin DSL with a version
catalogue, `-Werror`. 773 main sources / 178 test sources / 68 Flyway migrations (head `V68`) / 84
live tables / 75 mapped entities / 46 controllers / 128 contract paths. Judged against **its own**
lane throughout.

> **What this round is not.** It is not a re-run of `docs/audits/*-remaining-scope-assessment.md`
> (rounds 1–5, last 2026-08-13), which asks *what is left to build*. This asks *does the code do what
> the specs say*. Where the two touch — the unenforced-permission check — this round re-derives the
> number and reports the movement rather than citing it.

---

## 0. Method — the four computed checks, so they can be re-run

Prose was not trusted for any catalogue-wide claim. Four scripts were written and run:

1. **Schema sweep** — parses all 68 migrations for `CREATE TABLE` / `ALTER TABLE ADD COLUMN` /
   `DROP TABLE` / `RENAME`, plus `V1`'s and `V4`'s `company_owned TEXT[]` arrays and every explicit
   `FORCE ROW LEVEL SECURITY`, then compares the `company_id` set, the forced set and ERD §0.1's
   exception list in **both** directions.
2. **Cross-module FK sweep** — maps every table to its module via its entity's `@Table(name = …)`
   and package, then reports every FK whose target module is neither the source module nor a
   foundation module.
3. **Catalogue sweep** — extracts the 55 keys from `V3`, replays every later `INSERT`/`UPDATE` on
   `role_permission` (`V12`, `V15`, `V16`, `V19`, `V40`, `V51`, `V52`), and diffs the resulting matrix
   against roles spec §A3 cell by cell; separately diffs the key set against every key-shaped string
   literal in `backend/src/main/java` with comments stripped, in both directions.
4. **Filter sweep** — every entity mapping a `company_id` column against every entity carrying
   `@Filter(name = CompanyFilter.NAME)`, both directions.

All four are reproducible from this section; none needed a database.

---

## 1. T — Tenancy and isolation

| ID | Verdict | Evidence |
|---|---|---|
| T1 | **Met** `#confirmed` | Schema sweep: 84 tables, 75 carry `company_id`, and the 9 that do not are exactly ERD §0.1's no-column entries (`app_user`, `company`, `instance_role_assignment`, `instance_setting`, `permission`, `role`, `role_permission`, `social_login_attempt`, `user_social_identity`). Only `instance_audit_event` is nullable, which §0.1 prescribes (`V1__initial_schema.sql:1385`) |
| T2 | **Met** `#confirmed` | The exception list in `SchemaConventionTest.java:47-83` is the 14 names ERD §0.1 lists, and `exceptionListHasNoStaleEntries` (`:334`) asserts it against the live schema **in both directions** |
| T3 | **Met** `#confirmed` | `SchemaConventionTest.everyCompanyOwnedTableHasCompanyIdNotNull` (`:93`), derived from `information_schema`, not a list |
| T4 | **Met** `#confirmed` | Filter sweep: 68 entities map `company_id`; 64 carry `@Filter`; the 4 that do not are exactly the exception-list *targets* (`MembershipInvitation`, `ApiTokenLookup`, `SsoLoginAttempt`, `InstanceAuditEvent`). Both directions are machine-checked — `CompanyFilterTest.java:48` and `:71` |
| T5 | **Met** `#confirmed` | `CompanyContextFilter` populates from the session; no controller reads a company from path/header. Negative claim: `grep -rn 'CompanyContext.open' backend/src/main/java` returns only the six scheduler loops, the provisioning CLI and the filter — no request-scoped call site takes an id from `@PathVariable`/`@RequestHeader` |
| T6 | **Met** `#confirmed` | `CompanyContext` exposes `open`/`clear` returning `AutoCloseable`; no setter. `CompanyAwareTransactionManager.java:63` reads it via `currentOrNull()` |
| T7 | **Met** `#confirmed` | `SchemaConventionTest.companyIdLeadsCompositeIndexes` (`:127`), derived from `pg_index` |
| T8 | **Met** `#confirmed` | `CompanyAwareTransactionManager.java:53` — `set_config('app.current_company', ?, true)`, the bindable form of `SET LOCAL`, with the reason for preferring it recorded at `:45-52` |
| T9 | **Met** `#confirmed` | Schema sweep: 71 tables forced, and `SchemaConventionTest.everyCompanyOwnedTableForcesRls` (`:175`) reads `pg_class.relforcerowsecurity` for every table not on the exception list |
| T10 | **Met** `#confirmed` | `V4__rls_policy_tolerates_reset_guc.sql:69-71` rewrites all 56 `V1` policies to the `nullif(…, '')` form and **asserts the rewrite count** (`:78`); all 15 policies created after `V4` use that exact predicate (`V23:115`, `V25:122`, `V27:131`, `V31:152`/`:158`, `V38:166`, `V43:259`, `V46:220`, `V53:142`/`:144`/`:146`, `V54:257`/`:262`/`:267`, `V68:177`) |
| T11 | **Met** `#confirmed` | `SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate` (`:229`) pins the predicate *literally*, rejects any policy not named `company_isolation`, rejects any explicit `WITH CHECK` (`:268`), and asserts the population is **71** (`:309`). Independently recomputed at 71 by the schema sweep — the numbers agree |
| T12 | **Met** `#confirmed` | `V1__initial_schema.sql:1455-1456` — `RAISE EXCEPTION 'RLS list names a table that does not exist: %'` |
| T13 | **Met** `#confirmed` | `CompanyAwareTransactionManager.doBegin` (`:60`), with the "why not a servlet filter" argument at `:19-25` |
| T14 | **Unverifiable** (static) | `PrivilegeSplitTest` exists and `deploy/postgres/initdb/10-tcms-roles.sql` creates both roles, but `BYPASSRLS`/ownership are *runtime* properties. Settled by running `PrivilegeSplitTest` on `shanks`; this round may not |
| T15 | **Met** `#confirmed` | 75 `JpaRepository` interfaces; 72 carry `@Transactional(readOnly = true)` and 3 carry read-write `@Transactional` because they write (`AiProposalRepository`, `CaseFlakeRepository`, `TestCaseDraftRepository` — the last two say so at `:19-21`/`:21`). `RepositoryTransactionTest.everyRepositoryIsTransactional` (`:123`) derives the population **from the live bean factory**, asserts it is non-vacuous (`:143`), and asserts only presence — which is exactly right for the three writers |
| T16 | **Unverifiable** (static) | The paired-fixture suite exists (`RlsIsolationTest`, `CompanyFilterTest`); whether it passes is a gate result this round may not produce |

**Section rollup — T.** The tenancy invariant is the strongest thing in this codebase and the only
section where every claim I could settle statically settled clean. It is not held by care: it is held
by six computed checks (`SchemaConventionTest` ×5, `CompanyFilterTest` ×2,
`RepositoryTransactionTest`), each of which derives its population from the live catalogue or bean
factory rather than from a maintained list, and two of which assert their own population is
non-vacuous before asserting anything about it. My four independent sweeps reproduced its numbers
exactly — 84 tables, 71 policies, 14 exceptions, 4 unfiltered targets — which is the only kind of
agreement worth having here. **Two rows are `Unverifiable` for the same reason and neither is a
concern: they are runtime properties and the directive for this run forbids running gates.** The
next action for T is not in this section; it is F5, which observes that the *prose* describing this
machinery has fallen behind the machinery by 15 policies.

## 2. Z — Authorization

| ID | Verdict | Evidence |
|---|---|---|
| Z1 | **Met** `#confirmed` | `PermissionEvaluator.java:32-62` documents the three terms and why SYSTEM is not behind the company gate; `evaluateCompanyWide` (`:293`) reads the COMPANY and SYSTEM terms only |
| Z2 | **Met** `#confirmed` | Negative claim, named search: `grep -rn '"Test Lead"\|"Company Admin"\|"Instance Admin"\|"Test Engineer"\|"Automation Service"\|"Viewer"\|"Agent"'` over `backend/src/main/java` returns **one** hit, `CompanyProvisioningService.java:87`, which resolves the seeded admin role during provisioning rather than deciding access. The eight seeded role *ids* in `Role.java:46-120` are used for identity and assignability, not as gates — `RoleGrantService` refuses via `isAssignable()`, "not through a comparison with this constant" (`Role.java:105-106`) |
| Z3 | **Met** `#confirmed` | `V3__seed_permissions_and_roles.sql:170-172` derives Instance Admin by `SELECT key FROM permission`; the argument against a sentinel is recorded at `:16-21` and again in `Permission.java:14`, `PermissionSet.java:148`, `RoleView.java:15`. Catalogue sweep found no `'*'` row and no `'*'` literal |
| Z4 | **Met** `#confirmed` | `instance_role_assignment` has no `company_id` and no policy (schema sweep); ERD §2 records why a NULL `company_id` is permanently unreachable; `PermissionEvaluator.evaluateInstanceWide` (`:335`) is callable with no context open |
| Z5 | **Met** `#confirmed` | Catalogue sweep: 55 keys in `V3:40-92`; §A3's ten tables sum to 55 |
| Z6 | **Met** `#confirmed` | Catalogue sweep replayed `V3` + `V15` + `V16` + `V51` + `V52` and compared every cell of §A3's ten tables. **Zero divergences.** Notable cells confirmed: TE holds `suite:delete`/`artifact:delete`/`run:close`/`result:amend`/`doc:publish` from `V52:167-174` and `plan:delete` from `V51:128-130`; TE holds no `*:approve` and none of the four project-configuration keys; CA holds no SYSTEM key |
| Z7 | **Met** `#confirmed` | `V52:180-185` de-qualifies the last `PLAN_SCOPE` rows for Test Engineer **and** Agent; the sweep found no surviving `OWN` or `PLAN_SCOPE` writer after `V52` |
| Z8 | **Met** `#confirmed` | `V3:249-258` (five keys) + `V16:47-48` (`project:read`) |
| Z9 | **Divergent** | The **data** is right — `V52:207-212` selects Agent's rows *from* Test Engineer's excluding `company:read`, so the effective grant is Test Engineer entire minus that one key, matching §A6a. The **code's prose is not**: see **F1** |
| Z10 | **Met** `#confirmed` | `ApiTokenService.java:280` — `request.expiresAt() == null && Role.AGENT_ID.equals(request.roleId())` → a field error at `:295`. Server-side, as §A7 requires |
| Z11 | **Met** `#inferred` | `PermissionEvaluator.cappedToToken` is applied on all three evaluation paths (`:326`, `:341`); the intersection is by construction. Marked `#inferred` because I read the call sites and the helper, not a test proving a Viewer's agent token resolves to Viewer's set |
| Z12 | **Met** `#confirmed` | `V15:92-105` seeds the `Company Member` role holding exactly `company:read`; `Role.java:82-95` records why; `AdminReach.readsCompany` (`:58-65`) asks it at company scope |
| Z13 | **Met** `#confirmed` | `V41` and `V40` set `assignable = false`; `Role.java:100-107` records that `GET /roles` still returns Agent and `ApiTokenService` still accepts it — "three different questions … and `assignable` answers only the first" |
| Z14 | **Met** `#inferred` | Read at `InvitationService` and `FederatedIdentityService`; not driven |
| Z15 | **Partially met** | Catalogue sweep: **10** of 55 keys have no non-comment literal in `backend/src/main/java`. Two — `doc:read`, `artifact:read` — carry the recorded §A3 annotation naming the project-read gate instead, so they satisfy Z15's second limb. The other **8** do not: `company:settings`, `company:create`, `company:delete`, `company:suspend`, `user:manage`, `role:manage`, `system:settings`, `instance:audit`. See **F4** |
| Z16 | **Not met** | `Role.java:112-115` is a live, present-tense claim about what a credential cannot do that the catalogue contradicts, and the rule built to catch exactly this class structurally cannot see it. See **F1** and **F2** |

**Section rollup — Z.** Fourteen of sixteen rows hold, and the two that do not are the same story
seen twice: **the authorization *data* is correct and machine-pinned; the authorization *prose* is
not, and the one place prose is machine-checked has a blind spot exactly where the product's most
powerful credential is described.** The data half is genuinely impressive — `V52` expresses Agent as
a relationship to Test Engineer rather than a snapshot, `SeedDataTest` asserts the delta in both
directions, and my independent replay of seven migrations against §A3's ten tables produced zero
divergences. That makes F1 sharper rather than softer: the only thing left that can mislead a
maintainer about this credential is a sentence, and a sentence is what is wrong. The remaining gap,
F4's eight unenforced keys, is scope rather than defect and has **improved** since the last
assessment. Next action: F1, then F2, in that order — F2 without F1 leaves the false sentence
standing while widening the net.

## 3. M — Module boundaries

| ID | Verdict | Evidence |
|---|---|---|
| M1 | **Met** `#confirmed` | 13 packages under `com/tcms/` matching the cache's list; `ModuleStructureTest` hard-codes it |
| M2 | **Not met** | Cross-module FK sweep found **8** database FKs whose target module is neither the source's nor a foundation module. See **F3** |
| M3 | **Divergent** | The code's foundation set is five (`ArchitectureRules.FOUNDATION_MODULES`, per the orientation cache); ERD §0.2:73 still names four and omits `api`. See **F5** |
| M4 | **Not met, as stated** | ArchUnit reads bytecode. A `REFERENCES` clause in a `.sql` file is not bytecode, and no test in `backend/src/test` reads migrations for FK targets — negative claim, searched: `grep -rn 'FOREIGN\|REFERENCES' backend/src/test/java/com/tcms/{architecture,tenancy}` returns two prose hits and no rule. See **F3** |
| M5 | **Met** `#inferred` | Stated in `tenancy/package-info.java` and enforced by `ArchitectureRulesTest`; I read the declaration, not the rule's assertions |

**Section rollup — M.** The *Java* boundary is real, machine-checked and, from everything I read,
respected. The *database* boundary named in the same ERD sentence is neither: eight FKs cross it
today, all of them in `V1` and therefore predating the rule's own §125 revision, and the mechanism
the sentence names cannot see the artifact the rule is about. This is one finding with two halves —
a rule that is factually violated, and an enforcement claim that is structurally false — and the
important half is the second, because it is what lets a ninth edge land silently. Next action is a
decision, not a fix: the spec owner has to say whether M2 governs DDL at all. F3 states both options
and picks neither.

## 4. C — Configuration and secrets

| ID | Verdict | Evidence |
|---|---|---|
| C1 | **Met** `#confirmed` | The three secrets are spelled with no colon — `application.yml:127` (`TCMS_JWT_SECRET`), `:163` (`TCMS_IDENTITY_SECRET_KEY`), `:435` (`TCMS_INTEGRATION_SECRET_KEY`). `ConfigConventionTest.noShellPlaceholderSyntax` (`:96`) reads the **source text** with comments stripped (`:81`) and rejects any default beginning `?` or `-`; `jwtSecretHasNoDefault` (`:133`) pins the JWT line as a literal string. I re-derived every placeholder in both YAML files by hand: no `:?` or `:-` survives outside comments |
| C2 | **Met** `#confirmed` | `noProfileReintroducesAJwtDefault` (`:152`) sweeps every `application*.yml`; `application-prod.yml` contains no placeholder at all (read in full, 34 lines) |
| C3 | **Met** `#confirmed` | `everyRequiredPlaceholderIsSuppliedInProduction` (`:278`) reads `deploy/compose.prod.yaml` and requires every env-var-shaped no-colon placeholder to appear there |
| C4 | **Met** `#confirmed` | All three no-colon values reject the literal placeholder: `${TCMS_IDENTITY_SECRET_KEY}` and `${TCMS_INTEGRATION_SECRET_KEY}` fail Base64 decoding (`IntegrationProperties.java:49-74`), and `${TCMS_JWT_SECRET}` is 18 characters against `@Size(min = 32)`. The population is closed — those are the only three no-colon placeholders in either file |
| C5 | **Met** `#confirmed` | Three rules over a **derived** root population with a non-vacuity floor (`ConfigConventionTest.java:216-222`), checked against `Dockerfile`, `deploy/compose.prod.yaml` and `compose.yaml` |
| C6 | **Met, with one gap in reach** | Observed throughout. The one reach gap: `noShellPlaceholderSyntax` (`:108`) tests `startsWith("?")` and `startsWith("-")` only, so shell's `${VAR:+x}` and `${VAR:=x}` would pass. Neither occurs today (searched both files) — recorded as **F7**, a nit |

**Section rollup — C.** §108's family is closed, and closed the right way: every rule reads the
**text** rather than a resolved value, the population of secrets is small and fully enumerated, and
each of the three has an independent value-validation backstop against the exact failure §216
measured — a `@ConfigurationProperties` binder passing the literal placeholder through. I attempted
to break this section three ways (a fourth secret, a comment-hidden placeholder, an unvalidated
no-colon String) and found nothing. The only residue is F7's two unhandled shell operators, which is
a widening of a rule rather than a defect. Nothing here needs doing before the sections above.

## 5. P — Provisioning and async context

| ID | Verdict | Evidence |
|---|---|---|
| P1 | **Met** `#inferred` | `CompanyProvisioningService` is the only caller of the seed functions I found; idempotency is asserted by `CompanyProvisioningServiceTest`, not by me |
| P2 | **Met** `#confirmed` | Negative claim: `grep -rn 'tcms_seed_company_defaults\|tcms_seed_project_defaults' backend/src/main` hits only `provisioning/` |
| P3 | **Met** `#confirmed` | `AsyncTenancyConfiguration.java:43-46` declares the decorator as a **bean**, and `:24-29` records that the four explicit `*Customizer` beans it replaced were measured **inert** — mutating them changed no result — and were deleted rather than kept as reassurance. That paragraph is the strongest single piece of engineering hygiene I read this round |
| P4 | **Met** `#inferred` | `CompanyScopedEvent.java:16-36`; not driven |
| P5 | **Met** `#confirmed` | All six `@Scheduled` methods were read. Five open a per-company context: `ReportJobScheduler.java:84`, `ExecutionFactScheduler.java:112`, `CaseHealthScheduler.java:76`, `OutboxScheduler.java:79`, `RunScheduleScheduler.java:110`. The sixth, `SsoLoginAttemptPurge.purgeExpired` (`:63`), correctly opens none: both tables it deletes from (`sso_login_attempt`, `social_login_attempt`) are on the §0.1 exception list and carry no policy — verified in the schema sweep, not assumed from the comment |

**Section rollup — P.** Clean. The async half of tenancy is the half that historically fails
silently, and this codebase has made it fail *closed* by construction — the context is not
inheritable, the decorator clears it on every task, and the one job that legitimately runs without a
context does so against tables I independently confirmed carry no policy. The only thing found here
is cosmetic: two scheduler comments assert a start-time stagger their own annotations contradict
(**F6**). I chased whether that was a real concurrency defect and it is not —
`spring.threads.virtual.enabled: true` (`application.yml:82-84`) puts scheduling on
`SimpleAsyncTaskScheduler` with unbounded concurrency rather than the one-thread
`ThreadPoolTaskScheduler` default, so a collision costs nothing. Recorded as minor for that reason,
and the negative result is recorded too, because the same two comments would be a real finding on any
build that flips that flag.

## 6. N — Naming and keys

| ID | Verdict | Evidence |
|---|---|---|
| N1 | **Met** `#inferred` | `key_sequence` carries `company_id NOT NULL` and is policed (schema sweep); `DisplayKeyAllocationTest` exists |
| N2 | **Met** `#confirmed` | `V33__flatten_suites.sql`; `suite_case`/`suite_tag_rule` are the flat arms |
| N3 | **Met** `#confirmed` | `SchemaConventionTest.companyIdLeadsCompositeIndexes` (`:127`) covers uniqueness constraints as indexes; `V21__team_name_case_insensitive_unique.sql` is the company-scoped `team.name` |

**Section rollup — N.** Nothing to report. Three rows, no divergence, and the invariant that
actually matters here (a previously-global uniqueness constraint that forgot its `company_id`) is
covered by the same derived index check that carries T7 rather than by a separate reading.

## 7. X — Contract

| ID | Verdict | Evidence |
|---|---|---|
| X1 | **Met** `#inferred` | `ContractConventionTest` and the §145 rule exist; 128 paths against 46 controllers was **not** traced operation-by-operation this round |
| X2 | **Unverifiable** (this round) | Not traced. Settled by `ContractConventionTest`'s pairing rules, whose assertions I read the names of and not the bodies |
| X3 | **Met** `#inferred` | `ContractEnumTest` exists; the `DIVERGENCE_BY_DECISION` map was reported at 1 entry by the 2026-08-13 assessment and not re-derived here |
| X4 | **Met** `#inferred` | `ErrorBodyPolicyTest`, `ErrorDispatchStatusTest` exist |
| X5 | **Met** `#inferred` | §163's rule exists |

**Section rollup — X.** **This section is thin and says so.** The contract surface is 128 paths and
five machine rules, and a defensible verdict on X1–X5 is a whole audit of its own — tracing it inside
this round's budget would have produced five confident-looking `#inferred` rows and no evidence,
which is worse than an admitted gap. What I can say is that the *mechanisms* exist and are named;
what I cannot say is that they are complete or non-vacuous, and X2 is `Unverifiable` rather than
`Met` for that reason. **This is the highest-value scope for round 2** and the one place where this
round's coverage is genuinely weak rather than merely deferred.

---

## 8. Findings

Nine findings. Every one carries a `file:line` read this round, a concrete failure scenario, and a
named owner. Direction only — no fix is applied or drafted.

### Data issues

*None.* No finding in this round is a case of correct code running on wrong data. The two candidates
— the seeded permission matrix and the seeded role set — were computed against the spec cell by cell
and agree exactly.

### Code issues

**F1 — `Role.java`'s Agent Javadoc states a capability ceiling that `V51` and `V52` deleted.**
Severity: **major.** Owner: `be-java`.

`backend/src/main/java/com/tcms/identity/Role.java:112-115`:

> `It is the ceiling for an agent acting on behalf of the person who issued the token: Test Engineer`
> `minus {@code library:manage} (gated by an impact modal an API caller never sees), minus`
> `{@code case:delete} and {@code evidence:delete} (both {@code OWN}, and {@code OWN} follows the`
> `token's owner, so the agent would delete what the human authored).`

Three of that sentence's four claims are false at `V68`:

- Agent **holds** `library:manage`, `case:delete` and `evidence:delete`. `V52__the_tier_line_is_approvals_and_configuration.sql:207-212` inserts Agent's grant by selecting *every* Test Engineer row except `company:read`, and it runs after statement 1 (`:167-174`) has widened Test Engineer. Computed, not read: the catalogue sweep's replay gives Agent 32 keys, Test Engineer 33, delta `{company:read}`.
- `OWN` carries **no rows at all** since `V51`, so "both `OWN`" has no premise. `role_permission.qualifier`'s own comment says so in capitals (`V52:220-225`).
- "It is the ceiling" is the exact sentence roles spec §A6a **struck through and replaced**, with a recorded objection: *"an agent token that is leaked, or driven by any other client, can delete every case, plan, suite, artifact and evidence row its owner can, with no consent step in the path."*

**Failure scenario.** A maintainer sizing the blast radius of a leaked `Agent` token reads this
constant — the natural place to look, and the only Java artifact that describes the role — and
concludes the credential cannot delete. It can, for every deletable artifact its owner can reach.
The consent prompt the spec substituted lives in the MCP server, and the bearer authenticates
against `/api/*` directly. §A6a says explicitly that if this ever bites, its struck-through
paragraph is the one that predicted it; this Javadoc is that paragraph, un-struck, in the code.
Direction: re-base the paragraph on `V52` and mark the old text superseded rather than deleting it,
per the working agreement's superseded-decisions rule.

**F2 — `tierClaims.test.ts` cannot see a claim about `Agent` or `Company Member`, which is where the
product's two credential-only roles are described.** Severity: **major.** Owner: `be-java` (the rule
lives in `packages/`, but the corpus it fails to cover is `backend/src`).

`packages/client/src/test/tierClaims.test.ts:141-148` — `NAMED` enumerates six spellings: `AS`, `V`,
`TL`, `TE`, `CA`, `IA`. `Agent` and `Company Member` are absent. The matrix the rule checks against
is `roleMatrix`/`specMatrix` over roles spec **§A3**, which has no Agent column at all — Agent's
grant is §A6a and Company Member's is §A2. `backend/src` **is** in the corpus
(`packages/client/src/test/corpus.ts`, fourth entry), so the file is read and the sentence is
skipped.

**Failure scenario.** This is precisely how F1 survived. The rule's own header says it exists because
*"copy that names a role is a claim about the catalogue, and nothing checks it"* and that reading for
tier claims by hand twice in two days missed a whole corpus each time — and it then left uncovered
the two roles whose whole purpose is to be a capability ceiling on a credential. Every future
sentence about Agent is unchecked, in the corpus where a wrong one is read as a safety argument.
Direction: the fix is not simply adding two `NAMED` rows — §A6a's grant is expressed as a *delta from
Test Engineer*, so the matrix source needs an Agent column derived that way before a rule can check
against it. Sequence this after F1, not before.

**F3 — the DDL has 8 cross-module foreign keys toward non-foundation modules, and the enforcement
ERD §0.2 names cannot see them.** Severity: **major.** Owner: **spec/product** for the rule,
`data-architect` for the schema half — see also the Logical conflicts section, where the
undecided half of this sits as F3-C.

Computed by the cross-module FK sweep. All eight are in `V1__initial_schema.sql`:

| Source (module) | Column | Target (module) | Line |
|---|---|---|---|
| `plan_scope_item` (execution) | `suite_id` | `suite` (repository) | `V1:734` |
| `plan_scope_item` (execution) | `case_id` | `test_case` (repository) | `V1:735` |
| `test_execution` (execution) | `case_id` | `test_case` (repository) | `V1:836` |
| `test_execution` (execution) | `case_version_id` | `test_case_version` (repository) | `V1:837` |
| `step_result` (execution) | `step_id` | `test_step` (repository) | `V1:878` |
| `defect_link` (execution) | `case_id` | `test_case` (repository) | `V1:902` |
| `automation_key_mapping` (execution) | `case_id` | `test_case` (repository) | `V1:1359` |
| `artifact` (documentation) | `plan_id` | `test_plan` (execution) | `V1:1026` |

Negative claim, with its search: no test reads the migrations for FK targets —
`grep -rn 'FOREIGN\|REFERENCES' backend/src/test/java/com/tcms/architecture backend/src/test/java/com/tcms/tenancy`
returns two prose mentions and no rule. ArchUnit operates on bytecode; a `REFERENCES` clause is not
bytecode. So ERD §0.2's *"This is enforced by ArchUnit, not convention"* is false of the artifact the
sentence is about.

**Failure scenario.** The eight that exist are shipped and harmless today. The cost is the ninth: a
migration adding, say, `work_item_link.execution_id → test_execution` would couple `work` to
`execution` in the database with every gate green, and the coupling would surface years later as an
unscoped schema migration blocking a module split or an event-ised boundary. This is the shape the
working agreement names outright — *a schema-wide invariant needs a computed check, not a careful
reading* — applied to the one schema-wide invariant that does not have one.

**F4 — 8 catalogue keys have no evaluation site and no recorded alternative gate.** Severity:
**minor** (scope, not defect). Owner: `be-java` / product.

Computed: `company:settings`, `company:create`, `company:delete`, `company:suspend`, `user:manage`,
`role:manage`, `system:settings`, `instance:audit`. Method: every key-shaped literal in
`backend/src/main/java` with block and line comments stripped, diffed against `V3:40-92` in both
directions. The reverse direction is clean — the only non-catalogue key-shaped literal in main code
is `about:blank` (`api/Problem.java`, `api/ProblemErrorAttributes.java`,
`repository/CaseAttachmentController.java`), which is an RFC 7807 type URI.

**Movement since the last assessment.** `docs/audits/2026-08-13-remaining-scope-assessment.md`
§1.5 reported 13 under this same narrower question. Five have since gained a real consumer:
`integration:manage`, `defect:create`, `company:idp`, `audit:view`, `project:audit`. **13 → 8**, and
the audit-trail hole that §1.5 called out as its highest-yield finding is closed —
`identity/AuditLogController` and `AuditLogService` exist and are gated. The remaining eight are the
instance-operator and company-configuration surfaces, i.e. M6 and the CLI; there is no
`/companies`, `/company/settings` or instance-admin path among the contract's 128.

**Failure scenario.** Low. The concrete risk is §A3's own recorded one: a gate written for the first
time against a key nothing else asks cannot be shown to *discriminate*, because no persona a test can
build lacks it. That makes the first consumer of each of these eight the expensive one, and it is
worth knowing before it is written rather than after.

**F5 — three authoritative documents carry counts and lists the schema has outgrown.** Severity:
**minor.** Owner: `be-java` / `data-architect`.

- `docs/specs/tenancy-invariants.md:27` — *"hands the application `DROP POLICY` on all 56 tables"* — and `:36` — *"`SchemaConventionTest` pins the predicate on all 56 policies."* The count is **71** (`SchemaConventionTest.java:309`, and my independent sweep). Fifteen policies have been added since the number was written.
- `docs/specs/2026-07-30-tcms-erd.md:73` names four permitted FK targets and omits `api`, the fifth foundation module since §146.
- `docs/specs/2026-07-30-tcms-erd.md:76` lists ten modules including `shared`. There are **13** and `shared` was never built — §146 built `api` instead.
- `backend/src/main/java/com/tcms/tenancy/CompanyAwareTransactionManager.java:36-39` lists eight instance-level tables. ERD §0.1's list is **14**; the six missing are `membership_invitation`, `api_token_lookup`, `sso_login_attempt`, `user_social_identity`, `social_login_attempt`, `flyway_schema_history`.

**Failure scenario.** `CLAUDE.md` names all three of these as authoritative and tells every reader to
read them before the handoff narrative. A reader implementing a new module edge toward `api` reads
§0.2 as forbidding it; a reader adding a policy reads "56" and looks for the fifteen missing ones;
a reader adding an exception-list table checks the transaction manager's eight-name list and
concludes theirs is a new kind of thing. Each is a wrong turn taken from a document the repo told
them to trust. This is not the same class as F1 — no safety property is misstated — but it is the
same mechanism, and the counts are all derivable.

**F6 — two scheduler Javadocs assert a start-time stagger their own annotations contradict.**
Severity: **nit.** Owner: `be-java`.

`ReportJobScheduler.java:77-78` — *"it is deliberately not either sibling's, so the three schedulers
do not start in lockstep"* — with `initialDelayString = "PT1M"` at `:80`, which is exactly
`OutboxScheduler.java:75`'s. `RunScheduleScheduler.java:102-103` — *"off its two siblings' two and
three minutes"* — with `"PT3M"` at `:105`, which is exactly `ExecutionFactScheduler.java:108`'s.
`SsoLoginAttemptPurge.java:58-60` says *"the three sibling schedulers"*; there are five.

**Failure scenario, and why it is only a nit.** I checked whether this was a real defect and it is
not: `application.yml:82-84` sets `spring.threads.virtual.enabled: true`, so Boot builds a
`SimpleAsyncTaskScheduler` with unbounded concurrency rather than the pool-size-1
`ThreadPoolTaskScheduler` default, and two jobs sharing an initial delay simply run at once. It stays
recorded for two reasons: it is a present-tense factual claim that is false, and on a build that
flips that flag it becomes a real one — six jobs on one thread, with `ReportJobScheduler` generating
reports inline every 30 seconds ahead of the outbox dispatcher and the run-schedule poller.

**F7 — `noShellPlaceholderSyntax` covers two shell operators, not four.** Severity: **nit.** Owner:
`be-java`.

`ConfigConventionTest.java:108` tests `defaultValue.startsWith("?")` and `startsWith("-")`. Shell's
`${VAR:+alt}` and `${VAR:=default}` would pass the rule while Spring read `+alt` / `=default` as the
literal default — the §108 mechanism exactly. Neither spelling occurs today: I enumerated every
placeholder in `application.yml` and `application-prod.yml`. Direction: the rule's population is
"any default that begins with a shell operator", which is four characters rather than two.

### Logical conflicts

**F3-C — does ERD §0.2's cross-module FK rule govern the DDL, or only Java?** Decision owner:
**the spec/product owner.** Not the auditor's to pick, and both readings are defensible.

The two things that cannot both hold are ERD §0.2:73's rule as written and `V1`'s eight FKs (F3's
table). Three exits, and each has a cost somebody has to accept:

1. **The rule governs DDL and the eight are legacy.** Then §0.2 needs an explicit exception list — exactly as §0.1 has one — and a computed check over the migrations for the ninth. Cost: a new rule and a documented exception list nobody has decided yet.
2. **The rule governs Java only.** Then §0.2's sentence is over-broad and should say so, and the DDL is unconstrained. Cost: the database is permanently free to couple modules the Java layer forbids, and "everything else crosses via domain events" becomes false of the schema.
3. **The rule governs DDL and the eight are defects.** Cost: dropping seven FKs inside `execution`→`repository`, which are the pins §6j's "every result knows exactly what it executed" depends on (`test_execution.case_version_id`, ERD:632). This is almost certainly the wrong exit and is listed so it is visibly rejected rather than silently unconsidered.

**F8-C — §A6a claims Agent's grant "cannot drift" from Test Engineer's *in the data*; the data is a
one-time snapshot and the guarantee is a test.** Decision owner: **the spec owner.** Severity:
**minor.**

`V52:207-212` is `INSERT … SELECT … FROM role_permission te WHERE te.role_id = <TE> AND
te.permission_key <> 'company:read' ON CONFLICT DO NOTHING`. It runs once, forward-only. It cannot
add a key Test Engineer gains later and cannot remove one Test Engineer loses. §A6a's sentence —
*"derived from it in the data (`V52` statement 3 selects Agent's rows from Test Engineer's) so the
two cannot drift"* — attributes the invariant to the data. The actual invariant is
`SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse`, which §A6a also names and which asserts the
delta **in both directions**, so drift is caught — by a test, at build time, not by the schema.

Why it needs a decision rather than a comment fix: the two readings imply different obligations on
the *next* migration that touches Test Engineer's grant. If the invariant is data, that migration
owes nothing. If it is a test, that migration owes a paired update to Agent and the test is what
tells it so. The spec should say which, because the difference only shows up on the day somebody
revokes a key from Test Engineer and Agent silently keeps it — which the current SQL permits and the
current test forbids.

**F9-C — the mutation-check queue has one run record, 2026-08-05, and ~843 rows added since.**
Decision owner: **the project owner** (this is §65 policy, not a code defect). Severity: **major by
accumulation.**

`docs/verification/backend-mutation-checks.md` has exactly one `RUN RECORD` (`:35`, 182 rows on
`4b582ec`, 2026-08-05). The file now carries 78 `## §` sections and 984 table rows; ~843 of those
rows sit after the last executed section. Seventeen days and roughly 120 handoff sections have landed
against an unrun queue. The queue itself already names holes it cannot close by mutation — `§247
SK-M6` (nothing drives `RunScheduleScheduler.fireDueForAllCompanies` at all), `§244 ED-M6` (no test
asserts a hand-recorded duration reaches the Run Summary report), `§243 RN-M3` (no arm drives a
review *rejection* with a channel subscribed, so a sign-off announced for a rejected artifact would
ship).

The conflict, stated plainly: §65's own text says *"a slice in an unrun queue is **built and
unverified**, and must be described that way rather than as done"*, while the orientation cache's
§249 header describes the current state as **"GATES RUN AND GREEN."** Both are true of different
things — the automated suites are green, the mutation queue is unrun — and the two sentences read as
contradictory to anyone deciding whether a feature is verified. That is the decision owed: either
the queue is executed, or the "done" language is qualified. It is not the auditor's to resolve, and
it is the highest-leverage row in this report because it governs how much every *other* green signal
in this repository is worth.

---

## 9. Priority table

| # | Finding | Class | Severity | Owner |
|---|---|---|---|---|
| 1 | F9-C — 843 unrun mutation rows vs "gates green" | conflict | major | project owner |
| 2 | F1 — Agent Javadoc states a deleted ceiling | code | major | `be-java` |
| 3 | F3 / F3-C — 8 DDL cross-module FKs; ArchUnit cannot see them | code + conflict | major | spec owner, then `data-architect` |
| 4 | F2 — `tierClaims` blind to `Agent` / `Company Member` | code | major | `be-java` |
| 5 | F5 — three authoritative docs carry outgrown counts | code | minor | `be-java` / `data-architect` |
| 6 | F4 — 8 keys with no evaluation site (was 13) | code | minor | `be-java` / product |
| 7 | F8-C — "cannot drift in the data" is a test, not the data | conflict | minor | spec owner |
| 8 | F6 — two scheduler comments contradict their annotations | code | nit | `be-java` |
| 9 | F7 — shell-syntax rule covers 2 of 4 operators | code | nit | `be-java` |

**Sequencing, leverage-ordered rather than severity-ordered.**

1. **F9-C first**, because it is the only row that changes what every other signal means. Everything below is a judgement about code; this is a judgement about how much the green suite is evidence of.
2. **F1 then F2**, in that order. F1 is a one-paragraph correction with a real safety consequence. F2 is the mechanism that should have caught it, and doing F2 first leaves the false sentence standing while the net is widened around it.
3. **F3-C before F3.** The eight FKs are a question, not a task; deciding the rule's reach costs a paragraph and settles whether the computed check in F3 is even owed.
4. **F5 with whichever of the above touches its file.** These are prose corrections whose value is entirely in not being deferred again — they are cheap and each is derivable, so they belong in the same commit as the adjacent work rather than in a documentation pass that never gets scheduled.
5. **F4, F8-C, F6, F7** last, together. None blocks anything; all four are one-line or one-paragraph.

---

## 10. Open questions

Five, and the first is the one that most needs an owner.

1. **Is the mutation-check queue going to be run, or is the deferral now permanent?** Owner: project owner. This is F9-C. Seventeen days and ~843 rows is no longer "batched"; the practical question is whether §65 still describes the process. Nothing else in this report is worth acting on before this is answered.
2. **Does ERD §0.2's FK rule govern the DDL?** Owner: spec owner. F3-C's three exits.
3. **What is the intended blast radius of a leaked `Agent` token, and where is it written down?** Owner: product. §A6a records the objection and overrules it; `Role.java` still records the old answer. F1 fixes the sentence; it does not answer whether the decision should stand now that a delete surface exists on the MCP side (§211 says three of the five delete arms are reachable there).
4. **Should the contract surface get its own round?** Owner: auditor + project owner. Section X is five `#inferred`/`Unverifiable` rows over 128 paths and five machine rules. It is the largest un-audited area in this scope and it is where the working agreement puts the most weight (`openapi.yaml` is *the* contract).
5. **Is `CurrentUserService`'s `isSystemAdmin` meant to be a permission question?** Owner: `be-java`. `CurrentUserService.java:92` derives it as *"holds any SYSTEM-scope grant"* — `!instanceGrants.findByUserId(userId).isEmpty()` — under a comment (`:89-91`) saying it is derived "from holding a SYSTEM-scope grant, not from a role NAME". True, and it is not a permission check either. It drives affordances only and every act is independently gated, so it is not a hole today; it becomes one on the day a second SYSTEM-scope role exists. Not filed as a finding because Z2's requirement is *not a role name*, and this satisfies it.

---

## 11. What this round did not cover

Stated so round 2 knows where to start rather than re-deriving it.

- **The contract**, X1–X5. See open question 4.
- **Runtime properties**: T14 (the privilege split) and T16 (the paired-fixture suite). Both need a gate run on `shanks`; the directive for this run forbids it.
- **Test bodies.** I read test *names*, *populations* and *non-vacuity guards* extensively, and assertion bodies only where a verdict turned on them (`SchemaConventionTest`, `ConfigConventionTest`, `RepositoryTransactionTest`, `tierClaims.test.ts`). A rule whose population is right and whose assertion is vacuous would read as `Met` here.
- **The 68 migrations' business content.** Parsed structurally for T1–T12 and F3; not read for correctness of the domain changes they make.
- **`work`, `reporting` and `integration` module internals.** Reached only through the sweeps.
