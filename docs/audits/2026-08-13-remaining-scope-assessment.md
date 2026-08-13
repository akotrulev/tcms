# Remaining-scope assessment — what is left to build

**Date:** 2026-08-13 · **Baseline commit:** `0df1d73` (§190 records, the last commit on `master`)
**Round 5.** Round 4 is `docs/audits/2026-08-11-remaining-scope-assessment.md` (`4a8db0a`, §166);
round 3 is `docs/audits/2026-08-05-remaining-scope-assessment.md`; rounds 1 and 2 are
`docs/audits/2026-08-02-remaining-scope-assessment.md`. Round 4 is now superseded and says so in its
header. **Method:** derived from the repository, weighted by the milestone week estimates the
high-level plan sets out for itself (`docs/plans/2026-07-30-tcms-high-level-plan.md` §7).

> This is a **status assessment, not a plan**. It says what exists and what does not, with the
> evidence for each claim. Sequencing decisions belong in `docs/plans/`.

> **Derived and committed in one sitting**, the rule round 2 wrote for itself. Every count below was
> re-derived at `0df1d73` rather than carried forward — which is load-bearing this round, because
> **the first check re-derived contradicted round 4** (§1.5).

> **A concurrent lane was mid-slice in this working tree throughout the derivation** — §191's
> handoff section, a `V50__declines_follow_the_filing.sql` and edits to five `repository` classes,
> all uncommitted at `0df1d73`. **Nothing here reads the working tree**: every count is taken from
> the committed baseline, and where that makes a statement immediately perishable it says so (§5.4).
> This is round 4 §5.4's situation with the roles reversed, and the same discipline applies —
> re-derive rather than carry forward, and **do not edit the other lane's files**.

**What moved since round 4:** twenty-four slices (§167–§190). No milestone changed state. The work
was almost entirely the contract-and-seam audit continuing in this document's own method: the
`MEMBER_DIVERGENCE` map emptied (§167, §168, §169), `HealthSignal` got its last two writers (§181,
§182), two tables that shipped with readers and no writer got one (§186, §187), and **git
traceability shipped** (§177) — the first M4 shot-list row to close since the milestone opened.
`V46`–`V49` landed; **next free is `V50`**.

**The headline is not a percentage.** It is that two of round 4's own retirements do not survive
re-derivation, and one of them retired the highest-yield check this document has ever run on a count
that was wrong by five keys. §1.5.

---

## 1. How this was derived

Round 4 ran seven checks and retired three of them (§1.2 module sizes, §1.5 unenforced permissions,
§1.6 dead columns). **§1.6's retirement holds and got stronger — it is six standing build gates now.
§1.5's does not hold and the check is reinstated, narrowed.** §1.1 needed a change of question.

### 1.1 Tables with schema and no code — the question was wrong, and the build rule inherits the flaw

**Three of the 74 tables have no reference at all in `backend/src/main/java`**, unchanged from round
4 (74, not 73, because `V46` added `case_doc_link`):

| Table | The feature it belongs to | Class |
|---|---|---|
| `work_item_link` · `derived_item_annotation` | The defect view (§6l, M4) | no reference |
| `instance_setting` | The instance-admin settings surface (M6) | no reference |

**But "no reference" is the wrong question and §186 and §187 are the proof.** Both closed a table
that this check has passed in every round: `suite_condition` had a reader, an index, a response
shape, a usage count and no writer; `case_condition_optout` had an entity, an `@IdClass`, a
documented query, a filter and an argued design note, and nothing could create a row. A table with a
rich read path scores well on a reference count and is exactly the defect.

Asking the writer question instead — an `INSERT`/`UPDATE` in `src/main`, native or mapped — finds a
**fourth**:

> **`defect_link` has a reader and no writer, anywhere in the product.**

`TestExecutionRepository.findDefectLinks` reads it, `DefectLinkView` shapes it, `TestExecutionView`
and `TestExecutionSummaryView` carry it, `openapi.yaml` declares `defects` on two schemas, and
**three SPA surfaces render it** — `RunDetailPage`, `CaseHistoryPage` and `PlanStatisticsPage`'s
blocker list. `new DefectLink(` occurs nowhere; there is no `DefectLink` entity at all; no migration
or service writes the table. Every one of those three screens has told every user in the product's
history that this execution has no linked defects, and that sentence has never once been derived
from anything. **This is §166's shape** — declared, rendered, visible, and false — and §186's and
§187's, twice more.

**The rule §186 built cannot see it, and the reason is a population, not a bug.**
`ArchitectureRulesTest.everyEntityIsWritableByTheProduct` iterates *entities*. Nine of the 74 tables
have no `@Table` mapping and are reached by native SQL only:

| Native-only table | Written by |
|---|---|
| `execution_fact` · `plan_snapshot` · `suite_run_summary` | `ExecutionSource` (§101 rollups, §87 pipeline) |
| `key_sequence` | `DisplayKeys`, `V5` |
| `role_permission` | `V3`, `V12`, `V16`, `V19` (seed) |
| `work_item_link` · `derived_item_annotation` · `instance_setting` | nothing — the three above |
| **`defect_link`** | **nothing, and it has a reader and three screens** |

So the entity rule's corpus is 65 of 74 tables, and the nine it omits are the ones most likely to be
half-built, because a table nobody mapped is a table nobody owned. **Extending that rule to the
catalog rather than to the entity graph is a slice**, and §190 just built the instrument for it —
`SchemaCommentTest` reads `pg_description` and the live catalog from a migrated container, so the
table list is already available in a test that runs.

### 1.2 Module sizes — still retired

Retained for context. `shared` is **zero files** and has been the whole time, which is worth one
sentence given §125 refused it as `storage`'s home: it is a package name in the module list and
nothing else.

| Module | Files | Round 4 |
|---|---:|---:|
| `repository` | 170 | 160 |
| `execution` | 119 | 119 |
| `identity` | 71 | 71 |
| `project` · `documentation` | 63 · 63 | 64 · 57 |
| `reporting` | 43 | 43 |
| `tenancy` | 28 | 28 |
| `auth` | 17 | 17 |
| `storage` · `work` | 13 · 13 | 13 · 13 |
| `provisioning` | 12 | 12 |
| `api` | 7 | 6 |
| `shared` | **0** | — |

Context only. Do not quote it as progress.

### 1.3 Contract coverage — the member layer is where the last three defects were

**96 paths (94 at round 4), all served, zero controller routes outside the contract** — pinned by
`ContractConventionTest`, so this is a gate rather than a measurement now.

The layer that still moves is member coverage. Round 4 recorded §165 turning `RESPONSE_RECORDS` into
79 two-way pairings over 78 pairs. **§170 found the population that rule could not see** — not the
inline shapes it predicted (25, smaller than expected) but **45 named schemas, a third of the
contract's object shapes**, invisible because of how the guarding rule asked its question. Both
populations are compared now: **144 pairings**, and `MEMBER_DIVERGENCE_BY_DECISION` is `Map.of()`.

### 1.4 Absent dependencies

Unchanged in all four respects, third round running. `backend/gradle/libs.versions.toml` has **no
Gherkin or Cucumber grammar**, **no PDF renderer and no headless browser**, **no S3 SDK**; `deploy/`
holds `compose.prod.yaml`, `deploy.sh`, `postgres/` and `shanks/` — **no Helm chart**. No
`oidc`/`saml`/`scim`/`ldap` reference in `src/main` (one comment in `libs.versions.toml` saying an
external provider would add a second `JwtDecoder`), no `mermaid`, no webhook, no sanitizer in either
stack.

Round 4's bound stands and is worth restating because it has been confirmed twice: an absent
dependency predicts an absent feature *only where the feature cannot reasonably be hand-rolled*.
Markdown and HTML export shipped hand-written; §117 wrote four ingestion parsers with no parser
dependency. A Gherkin grammar and a PDF engine still qualify.

### 1.5 Permissions with no consumer — REINSTATED, and round 4's count was wrong by five

Round 4 retired this check as exhausted, on the finding that all ten remaining keys belonged to
features with no code. **Re-derived under round 4's own stated filter — a non-comment occurrence of
the key string in `backend/src/main/java`, which is how every gate in this codebase spells a
permission (`static final String EDIT_CASE = "case:edit"`) — the count is 15, not 10.** Run against
round 4's own baseline `89ce051` it was **also 15**, so this is not drift: five keys were on the
list and were reported as having left it.

| Unenforced key | Round 4 said | Reading at `0df1d73` |
|---|---|---|
| `integration:manage` · `defect:create` · `company:idp` · `company:suspend` · `company:create` · `company:delete` · `role:manage` · `user:manage` · `system:settings` · `instance:audit` | unenforced | unenforced — unchanged, all M4/M6 |
| `artifact:read` · `doc:read` | consumed | **javadoc only — and correctly so.** `ArtifactService` and `DocPageService` argue at length that the gate is the *project read* gate, because these two keys are held by every role; the key is named in prose to record the decision. **Not a hole** |
| `audit:view` · `project:audit` | consumed (§122) | **javadoc only, and a real hole.** `AuditEventRepository` has ~20 writers across `repository`, `execution` and `identity`; there is **no audit path on the contract, no controller and no screen**. `ArchitectureRulesTest` says so in passing — *"read only by a test, because the audit trail has no screen yet"* |
| `company:settings` | consumed | **javadoc only.** No branding/policy/defaults surface exists, which round 4's own §2.1 row 20 says |

**The check is reinstated with a narrower question**: a key with no non-comment consumer **and no
argued alternative gate at the site**. Under that question it yields 13, and the finding it produces
that nothing else in this document produced is the audit trail — **a table the product writes to
constantly and cannot read back**. That is a shot-list row round 4 did not have.

The lesson is about the method rather than the keys. Round 4 retired the highest-yield check this
document has ever run, on a count that a five-line loop contradicts, in the same round whose §4
caveat 3 says *"a completion percentage counts what a check can see."* **A check retired on a wrong
reading of its own output is the most expensive kind of retirement**, because the argument for
retiring it ("its yield is zero by construction") is unfalsifiable once nobody runs it.

### 1.6 Dead columns, enums and members — six standing build gates, and one is empty

Round 4's largest structural change survives and grew. There are **six** populations now, each
pinned to an exact set by a test that fails the build:

| Population | Round 4 | Now | What it pins |
|---|---:|---:|---|
| `READERLESS_BY_DECISION` | 15 | **18** | a column written and never read |
| `WRITERLESS_BY_DECISION` | 5 | **9** | a column read and never written |
| `UNEXERCISED_BY_DECISION` | 4 | **1** | a seam nothing in production calls |
| `DIVERGENCE_BY_DECISION` | 7 | **1** | a server enum and its wire enum that disagree |
| `MEMBER_DIVERGENCE_BY_DECISION` | 3 | **0** | a schema member one side declares and the other does not |
| **`UNWRITABLE_BY_DECISION`** | — | **1** | an entity whose table the product cannot write at all (§186) |

**The two that grew did not regress.** §188 narrowed the column rules' corpus from `src/main +
src/test` to `src/main` and excluded `@Version` from the writer rule, which converted **seven
columns whose only reader was a fixture** into honest entries — including `TestRun.releaseSortKey`,
where §151 shipped a column *as a fix* and nothing in the product consults it. A fixture standing in
for a reader is the shape §107 deleted the mock layer over, one layer down.

**The three that shrank shrank by deletion, which is the mechanism.** `MEMBER_DIVERGENCE` took ten
entries in its life and **every one left by being closed rather than revised** — §166's two
`lastResult` keys, §167's `relatedDocuments`, §168's `DocBlock.coverage`, §169's six
`DocLink.external*`. `DIVERGENCE` is down to `PermissionQualifier.NONE`, which is server-only and
safe for a reason the constructor enforces. `UNEXERCISED` is down to `Outbox.record`'s OCCURRENCE
arm, kept because deleting it makes three `V1` columns write-constant.

**Correction to round 4 carried forward, still true:** `ReportFormat` is 4 of 6 — `CSV` and `XLSX`
have no renderer and no reference in `src/main`; `PDF` is refused at request time with a recorded
reason at the enum constant. The product claim "HTML/MD/PDF export" is 2 of 3.

### 1.7 Declared-but-unserved contract members — closed as a category

Round 4 opened this check with three entries and one of them open. **All three are closed** (§166,
§167, §168) and the map that holds the category is empty across 144 pairings. The check now lives
entirely in the build; this document has nothing to add to it until an entry reappears.

What replaced it as the live version of the same question is §1.1: **not a member the contract
declares and nothing serves, but a table the product reads and nothing writes.** `defect_link` is
the same defect one layer down, and the reason it outlived the contract-member sweep is that it
*is* served — with an empty array, correctly, forever.

---

## 2. Shot list

### 2.1 Not started

| # | Functionality | Milestone | Evidence |
|---|---|---|---|
| 4 | PDF export | M2.5 | no renderer, no headless browser; `ReportFormat.PDF` refuses at request time |
| 5 | CSV / XLSX export | M2.5 | `ReportFormat` declares both; no renderer, no reference in main |
| 6 | Doc-structured reports · Mermaid blocks + SVG pre-render (§6i) | M2.5 | no code; no `mermaid` reference anywhere in the repository |
| 7 | Evidence-bundle export | M2.5 | no code; both its dependencies (§67, §87) closed long ago |
| 10 | SSO / OIDC (Cognito · Entra · Google · Okta · Keycloak) + LDAP | M4 | no `oidc`/`saml`/`scim`/`ldap` in main; `company:idp` unenforced |
| 11 | Issue trackers ×4 (Jira · GitHub · GitLab · Azure DevOps) | M4 | no code; `integration:manage` unenforced |
| 12 | Notifications (Slack / Teams webhooks, SMTP events) | M4 | mailer exists for invitations only; no webhook reference |
| 16 | Rich text + sanitized HTML | M1 | plain text throughout; no sanitizer in either stack |
| — | The defect view | M4 | `work_item_link` + `derived_item_annotation` unreferenced; `defect:create` unenforced; **and `defect_link` has a reader, a contract member and three screens with no writer** (§1.1) |
| — | **The audit trail's read path** (**new this round**) | M4/M6 | ~20 writers into `audit_event`; **no path on the contract, no controller, no screen**; `audit:view` and `project:audit` unenforced (§1.5) |
| 20 | Company settings — branding, policies, defaults | M6 | `company:settings` unenforced; no branding/policy surface |
| — | Instance-admin settings surface | M6 | `instance_setting` unreferenced; `system:settings`, `role:manage`, `user:manage`, `instance:audit` all unenforced |
| — | S3 adapter, then the Helm chart | M5 | no S3 SDK, no `Chart.yaml`; §78 put both before v1.0 |
| 21 | Phase 3 remainder — trend dashboards, TestRail/Xray migration, parameterized cases, undefined-step detection, milestones, SAML, SCIM, outbound webhooks | P3 | no code |

**Left this list since round 4: one — git traceability (§177).** `TestRun.commitSha` and
`TestRun.branch` existed since `V1`, `IngestionRequest` bound both and **read neither**, and a CI job
that set them got a 2xx and silence. Both have a writer, a contract member and a surface now.

**Added: one — the audit trail's read path**, which is not new work appearing but a check
un-retiring (§1.5). It was inside round 4's list only in the sense that nothing said it was outside.

### 2.2 Partially built

**`Left` is the percentage still to build**, not the percentage done.

| # | Functionality | Done | Not done | Left |
|---|---|---|---|---|
| 23 | Gherkin authoring (M1) | `STRUCTURED`/`GHERKIN` formats, free-text source, cross-field validation | Official parser, keyword autocomplete, Background + Scenario Outline, `.feature` preview — the grammar dependency is still absent | ~75% |
| 24 | Reporting (M2.5) | The pipeline (§86–§92) and the catalogue (§111–§115) | PDF / CSV / XLSX; doc-structured reports; Mermaid + SVG; evidence-bundle export | ~15% |
| 25 | Shared steps & library typeahead (M1.5 §6h) | Both libraries with writers, project-scoped search, derived usage counts, the step corpus on a trgm index (§94) | The sub-100ms p99 budget; duplicate warning at creation; **`condition_title_trgm_idx` and `shared_step_title_trgm_idx` are still `GIN (title gin_trgm_ops)` on the bare column while the queries filter `LOWER(title)`** — unchanged since `V30` recorded the fix for `test_step.action` and did not apply it to these two | ~25% |
| 26 | Classification (M1) | Assignment with the reconcile bound (§68), the vocabulary value editor (§69), `V20` sibling-name uniqueness | **`classification_dimension` still has no write service** — `new ClassificationDimension(` occurs nowhere in `src/main`; dimensions are seed-only, unchanged across **four** rounds | ~20% |
| 27 | Evidence & attachments (M1–M2) | Both ports, attach + delete with `OWN`, session-authenticated download, all six `owner_type` values claimed, `storage` as its own module, the verbatim CI upload stored (§160), **and the storage roots now created by the image with three config rules deriving them (§185)** | **The S3 adapter** — still a prerequisite of the Helm chart, still blocking from two ports | ~25% |
| 28 | Suites, cases & tags (M1) | Everything round 4 listed, plus **suite conditions with a writer (§186)** and **case-level condition opt-outs with a writer (§187)** — the two halves of the inheritance model that had shipped read-only | Rich text | ~8% ~~10%~~ |
| 29 | Plans & the plan document (M1–M2) | Lifecycle, editor, statistics, the document, §99's run scoping, clone, `keySeq` declared | Plan templates (§6v) — no `PlanTemplate` reference in main or on the contract | ~10% |
| 30 | Kanban / work items (M4) | Board live end to end, keyboard drag (§106), cross-project and team boards, a fourth card generator (§121) | WIP limits, personal boards, the defect view, `work_item_link`, `defect:create`, generation capping | ~65% |
| 31 | Org chart & teams (M0.5 / M4) | Team CRUD + membership, orphan-and-cycle surfacing, guarded delete, the team board, `TeamRef` extracted to `api` (§175) | Live workload, person view, team leads as a managed attribute | ~30% |
| 32 | Multi-company activation (M6) | Invitation, acceptance, membership removal, session epoch, project access screen, the operator tier's first writer (§122) | Company switcher, `/c/{slug}` routing (**still no such route — `router.tsx` names the segment in a comment only**), session switching, COMPANY-scope assignment UI, per-company IdP + email-domain routing, per-company branding, the instance-settings surface | ~55% |
| 33 | Hardening & release (M5) | Hetzner box, GHCR image, privilege split in production, the design comp, the homelab deploy target, the nightly Linux gate live and green, the signing key removed (§108), **the gates now run on a Linux shape before the push (`~/bin/tcms-remote`, 2026-08-12)**, **the first-upload 500 fixed as a class (§185)** | Perf pass, security review, backup/restore, install guide, S3 → Helm, and **both verification queues** (§5.2) | ~52% ~~55%~~ |
| — | Documentation & traceability (cross-cutting, M2.5) | Doc links from blocks (§93), **from cases (§167)**, **coverage markers (§168)**, **external targets — repo/path/line and URL (§169)**, a screen to make them (§172), confirmed withdrawal at both ends (§173), **relabelling without destroying the claim (§174)** | Mermaid; doc-structured reports | ~20% |
| — | `case_health` (cross-cutting) | **All ten `HealthSignal` members have a derivation** — §53's two, §120's pair, `reporting`'s `FAILING_REPEATEDLY`, §157, §158, §178's two, **§181's `ORPHANED`** and **§182's `INCOMPLETE`**, which also gave the eleventh boolean its first constant. Severity is on the wire (§179), the tile labels derive from the key (§180), one colour table (§183), one ordering (§184) | — **closed** | 0% |

### 2.3 Complete

Everything round 4 listed, plus: **git traceability** (§177) · the doc-link family end to end
(§167–§169, §172–§174) · **the health-signal family, all ten** (§178–§184) · `suite_condition` and
`case_condition_optout` given writers (§186, §187) · the contract's inline and named member
populations both compared (§170, §171, §176) · `TeamRef` extracted (§175) · the storage-root class of
deployment defect (§185) · the column rules' corpus narrowed off `src/test` (§188) · the CLI and MCP
surfaces for conditions (§189) · `SchemaCommentTest`, which validates comment prose against the live
catalog (§190).

**M1, M1.5 and M2 are within a rounding error of complete**, with rich text, plan templates and the
classification-dimension writer the only named gaps between them — the same three as round 4, which
is the fourth consecutive round for the last of them.

---

## 3. Percentage remaining

Weighted by the plan's own milestone estimates (midpoints of its ranges).

| Milestone | Weeks | r1 (§62) | r2 (§77) | r3 (§100) | r4 (§166) | **r5 (§190)** | Weeks left |
|---|---:|---:|---:|---:|---:|---:|---:|
| M0 — Foundation | 2.50 | 100% | 100% | 100% | 100% | 100% | 0.00 |
| M0.5 — Identity & access | 2.00 | 95% | 100% | 100% | 100% | 100% | 0.00 |
| M1 — Test repository | 4.50 | 75% | 88% | 92% | 96% | **98%** | 0.09 |
| M1.5 — Reuse & review | 3.00 | 45% | 85% | 95% | 96% | **97%** | 0.09 |
| M2 — Execution | 5.50 | 65% | 90% | 92% | 96% | **96%** | 0.22 |
| M2.5 — Docs, artifacts & reporting | 7.50 | 50% | 50% | 72% | 85% | **88%** | 0.90 |
| M3 — Automation ingestion | 2.50 | 0% | 0% | 0% | 100% | 100% | 0.00 |
| M4 — Integrations & workflow | 9.00 | 12% | 18% | 24% | 32% | **37%** | 5.67 |
| M5 — Hardening & release | 2.00 | 40% | 35% | 30% | 45% | **48%** | 1.04 |
| M6 — Multi-company activation | 1.75 | 35% | 40% | 40% | 45% | **45%** | 0.96 |
| **Total** | **40.25** | | | 14.84 | 9.83 | | **8.97** |

### The headline numbers

- **~22% of the full plan remains** — 9.0 of 40.25 weeks (**22.3%**; 24.4% at round 4, 36.9% at round
  3, 43.5% at round 2, 53.0% at round 1).
- **~21% remains to M5**, which the plan calls a legitimate release point (8.0 of 38.5 weeks).
- **~8% remains to a core TCMS without the M4 integrations block** (2.3 of 29.5 weeks, **7.9%**).

**Twenty-four slices moved the total by 0.86 weeks — 0.036 plan-weeks per slice**, against round 4's
0.076 overall and the 0.042 its own decomposition isolated for the sixty non-M3 slices. **The rate
is still falling and round 4's law is why**: the slices that move the number close a milestone at
zero, and there are none left. Nineteen of these twenty-four moved the number by nothing at all —
they closed a divergence, deleted a copy, or gave a column a reader. That is not idle work; it is
the work whose value the denominator cannot express.

**The one slice that did move it is §177**, and it is worth naming because it is the shape of what is
left: a feature the schema had carried since `V1`, the plan had approved, the request already bound,
and nobody had written the seven lines between them. M4's remaining 5.7 weeks are mostly not that
shape — SSO, four trackers and two notification channels are genuinely absent systems.

### Where the remaining weight sits

**M4 alone is 5.7 of the 9.0 weeks — 63% of everything remaining**, essentially unchanged as a share
from round 4's 62%. The four things inside it with no code at all (SSO, issue trackers,
notifications, the defect view) are ~5.0 of those weeks; git traceability was the fifth and it is
gone.

**M2.5's 0.9 weeks are export formats and Mermaid, not the engine.** M5's 1.0 is release hygiene and
two adapters. M6's 1.0 is a switcher and two settings surfaces. What is left of M1, M1.5 and M2
combined is **0.4 weeks**, and two of the three named gaps in it (rich text, plan templates) are
features nobody has asked for since M0.

---

## 4. Four caveats

1. **The per-milestone completion figures are judgment**, made against each milestone's own
   deliverable list. The evidence for each is §2; the week weights are the plan's, not this
   document's.
2. **The denominator is still wrong in the optimistic direction**, unchanged from round 4 and worth
   restating because nothing has fixed it: `@tcms/client`, `@tcms/cli` and `@tcms/mcp` — a typed
   client, 25 CLI commands and 21 MCP tools, plus `ai_proposal` and rate limiting — **are in no
   milestone**, and §189 added to that stack again. The percentage complete is measured against a
   scope the product has already exceeded. A round 6 that wants a defensible denominator has to add
   a row rather than keep noting this.
3. **A completion percentage counts what a check can see** — and this round the binding instance is
   §1.5, where round 4 *stopped* running the check that would have seen three of its own gaps. Where
   a percentage and a `*_BY_DECISION` entry disagree, the entry is the one with an argument attached.
   Where a percentage and a retired check disagree, re-run the check.
4. **Built is not verified.** §5.2, and this round the gap is wider in absolute terms than at round 4
   while being better instrumented.

---

## 5. Standing risks this assessment surfaces

### 5.1 Still closed — and the gate moved to where the defects are

`.github/workflows/tests.yml` runs the full `./gradlew build` nightly on the Linux homelab box, and
since **2026-08-12** the pre-push gates run there too via `~/bin/tcms-remote`, so the developer's own
run is a Linux run rather than a macOS one. §58 and §60's class of defect is now observable *before*
the push rather than the next morning.

**The bound is unchanged and easy to overstate:** `ci.yml` is still `-x test`, the nightly is the
default branch only, and a pushed lane branch is *backed up*, not *observed*.

### 5.2 The queues — one partial payment, and the deferral policy has grown a stated exception

| Queue | Rows now | Added since round 4 | Last execution |
|---|---:|---:|---|
| `backend-mutation-checks.md` | 603 table rows | **+137** | 2026-08-05, end to end, 182 rows on `4b582ec`, 9 ZERO-UNEXPECTED findings |
| `frontend-browser-checks.md` | 674 table rows | **+110** | §110 (2026-08-06, ~60 rows) and **2026-08-12 — `L0` and `A0` complete, `A1` partial, stopped at the user's direction**, two 🔴 |

**The 2026-08-12 sitting is the first browser execution since §110 and it justified itself
immediately.** Both of its 🔴 are closed: the first-upload `AccessDeniedException` — which had killed
*both* sittings six days apart, because nothing created `/var/lib/tcms` — was fixed **as a class** by
§185 (the image creates the roots, `compose.yaml` mounts a named volume, three `ConfigConventionTest`
rules derive the roots from `application.yml`, and the app refuses to start rather than 500ing at the
first write); the second was `suite_condition` having no writer, closed by §186 and §187. **Neither
was reachable by any gate**, which is the third consecutive round in which that sentence is the
argument for the queue existing.

**The policy has a stated exception now and it should be read deliberately.** §65 says *write the
row, run nothing*. §175, §188 and §190 each ran two or three rows inline, on the argument that **the
mutation-verification of a check written in this slice is taken while writing it, which is what
makes a green first run evidence rather than a coincidence.** §190's `SM-2` is the case for it: the
mutation was written to prove the new rule fires and **proved the opposite first** — a sentence-final
identifier was being skipped, so the rule would have shipped green, scanning and blind. That is a
real exception with a real argument, and it is also exactly how §72–§76's "just the cheap one" began.
The distinguishing test is narrow: **the row must mutate the check the same slice is writing.** Any
other inline run is the drift.

The remaining accrual is the risk. Roughly **500 backend mutation rows and ~230 browser rows** have
been written and not run since their queues were last executed end to end. By the working
agreement's own words every slice behind them is **built and unverified**. §3 counts them as built,
because they are. **Draining both queues remains unestimated work that belongs to M5 and is not in
the 8.97 weeks.**

### 5.3 Two filesystem-only storage ports still block the same downstream work

**Fourth round running, verbatim.** `EvidenceStore` (§67) and `ReportStore` (§87) each have a single
filesystem adapter; §78 made the S3 adapter a prerequisite of the Helm chart rather than an optional
upgrade. §185 made the filesystem lane *correct* — the roots exist, are asserted, and fail startup
rather than the first request — which raises the cost of leaving it as the only lane rather than
lowering it.

### 5.4 The Flyway head paragraph in `docs/HANDOFF.md` is stale again

`ls backend/src/main/resources/db/migration` gives **`V49__comments_that_name_live_columns.sql`**
(§190); the handoff's schema paragraph says the head is `V48` and that **`V49` is the next free
number**. The next free number is **`V50`**.

This is the one stale figure in this repository that is a *runtime* failure rather than a
documentation defect — `out-of-order` is unset, so a `V49` written today is refused on any
already-migrated database while passing every Testcontainers run. The paragraph says so about itself,
in the same sentence that instructs the slice adding a migration to correct it. **§184 corrected it
four migrations late; §190 added one and did not correct it**, which is the second occurrence in
seven sections.

**This round did not correct it either, deliberately.** A concurrent lane holds an uncommitted
`V50__declines_follow_the_filing.sql` in this working tree, so the true next free number is `V51` the
moment that lands and `V50` until it does — and `docs/HANDOFF.md` is a shared document another author
is mid-slice in. Writing a number into it from here is the interleaved-edit failure
`docs/plans/2026-07-31-parallel-execution-rework.md` §0 is about, for a figure that would be wrong
within the hour. **The correction belongs to the lane that lands `V50`.**

That is the argument for stopping doing this by hand. A paragraph that has gone stale twice in seven
sections, whose own text explains why staleness there is dangerous, is a candidate for the treatment
§155 gave the last hand-kept tally: **a test that reads the migration directory and the sentence**.
§190 built the instrument for validating comment prose against the live catalog; validating one
sentence against a filename is strictly easier, and it is the only version of this that survives two
authors.
