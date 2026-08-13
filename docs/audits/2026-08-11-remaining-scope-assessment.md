# Remaining-scope assessment — what is left to build

> **⚠ SUPERSEDED 2026-08-13 by `docs/audits/2026-08-13-remaining-scope-assessment.md` (round 5,
> baseline `0df1d73`, §190).** Twenty-four slices later the total went 24.4% → **22.3%** remaining,
> one shot-list row closed (git traceability, §177) and one opened (the audit trail's read path).
> **Two of this document's own conclusions do not survive re-derivation.** §1.5 retired the
> unenforced-permission check on a count of ten; under its own stated filter the count was
> **fifteen, at this document's own baseline** — `artifact:read` and `doc:read` are argued
> exemptions, but `audit:view`, `project:audit` and `company:settings` are holes, and the first two
> are a table with ~20 writers and no read path anywhere. §1.1's "tables with no code" question is
> the wrong one: §186 and §187 each closed a table this check had passed in every round, and asking
> for a *writer* instead finds `defect_link` — read by `TestExecutionRepository`, declared on the
> contract, rendered by three SPA screens, written by nothing. Read round 5; this file is history.

**Date:** 2026-08-11 · **Baseline commit:** `89ce051` (§166 docs, the last commit on `master`)
**Round 4.** Round 3 is `docs/audits/2026-08-05-remaining-scope-assessment.md` (`ced197e`, §100);
rounds 1 and 2 are `docs/audits/2026-08-02-remaining-scope-assessment.md`. Their figures are quoted
below where the movement is readable. Round 3 is now superseded and says so in its header.
**Method:** derived from the repository, weighted by the milestone week estimates the high-level plan
sets out for itself (`docs/plans/2026-07-30-tcms-high-level-plan.md` §7).

> This is a **status assessment, not a plan**. It says what exists and what does not, with the
> evidence for each claim. Sequencing decisions belong in `docs/plans/`.

> **Derived and committed in one sitting**, the rule round 2 wrote for itself. Every count below is
> `grep`- or script-derived at `89ce051`. **Two commits landed mid-derivation** — §165 and §166, from
> a concurrent session — and every check was re-run against the new HEAD rather than carried
> forward. §166 closed a gap this round had already written down, which is recorded in §1.7 rather
> than quietly deleted.

**What moved since round 3:** sixty-six slices (§101–§166). **M3 went from zero to complete** —
API tokens (§116), four ingestion parsers (§117), the token screen (§118), the ingestion history and
unmatched binder (§119), automation drift (§120), flake detection and quarantine (§121), the operator
tier's first writer (§122). Decision round 3 (§123) then ran nine steps that closed almost every dead
table and dead enum this document has ever listed: the two unwired permission keys (§124), `storage`
as a fourth foundation module (§125), attachment owners (§126), the four unrendered block types
(§127), the case draft buffer (§128), clone (§129), the outbox (§131). Reporting finished its
catalogue (§111, §112, §114) and gained the coverage matrix (§115). Then a whole stack that is in no
milestone: `@tcms/client`, `@tcms/cli` and `@tcms/mcp` (§135–§143), with `ai_proposal` (§138) and
rate limiting (§141). §144–§166 are a contract-and-seam audit run in this document's own method.
`V33`–`V45` landed; **next free is `V46`**.

---

## 1. How this was derived

Round 3 ran six computed checks. All six were re-run. **Two of them have now been mechanized into
build gates and are no longer this document's to run** (§1.6), which is the most consequential change
to the method since round 1, and **one has genuinely exhausted itself** (§1.5). A seventh check
appears, inherited from §165.

### 1.1 Tables with schema and no code

**Three of the 73 tables have no writer** (ten of 70 at round 3). The comment filter round 3 added —
drop hits whose line begins `*`, `//` or `/*` — is retained, and nothing escapes by comment.

| Table | The feature it belongs to | Class |
|---|---|---|
| `work_item_link` · `derived_item_annotation` | The defect view (§6l, M4) | no reference |
| `instance_setting` | The instance-admin settings surface (M6) | no reference |

**All three are one unstarted feature each**, which is the point: this check no longer finds
*forgotten* work. Round 3's other seven closed as `api_token` (§116), `ingestion_job` +
`automation_key_mapping` (§117), `test_case_draft` (§128), `outbox` (§131), and `suite_run_summary` +
`plan_snapshot` (§101). `attachment`, which round 3 called *"still only one-third claimed"*, is
**fully claimed** since §126 — `CASE`, `DOC_PAGE` and `ARTIFACT` got writers and `STEP` left the
`CHECK` in `V39`.

### 1.2 Module sizes — retired, and for a second reason

Round 3 retired this as a progress signal because no module was at zero. It is retired again, harder:
**two modules have appeared since and neither was found by this check.** `storage` (§125) and `api`
(§146) were both created by a decision recorded in a slice. A check that counts files in a fixed list
of packages cannot see a package that did not exist when the list was written.

| Module | Files | Round 3 |
|---|---:|---:|
| `repository` | 160 | 130 |
| `execution` | 119 | 88 |
| `identity` | 71 | 61 |
| `project` | 64 | 63 |
| `documentation` | 57 | 51 |
| `reporting` | 43 | 39 |
| `tenancy` | 28 | 16 |
| `auth` | 17 | 12 |
| **`storage`** | **13** | — (§125) |
| `work` | 13 | 14 |
| `provisioning` | 12 | 9 |
| **`api`** | **6** | — (§146) |

Context only. Do not quote it as progress.

### 1.3 Contract coverage — the hygiene property got much stronger

**94 paths, all served by a controller, and zero controller routes not in the contract** (68 at round
3). Round 3's verdict stands — this measures hygiene, not product — but the *hygiene* it measures is
no longer path-level. §144 fixed a wrong `required` set, §148 and §149 audited which 4xx bodies are
promises, §162 pinned the media type on every refusal, §163 settled `instance` versus `path`, and
**§165 turned `RESPONSE_RECORDS` from four one-directional entries into 79 two-way pairings** over 78
name-matched pairs.

That is why §165 and §166 found anything at all: a path-level check reads 100% while a schema
declares a member nothing serves. **Path coverage is a floor the contract cleared a year ago; member
coverage is where the defects were.**

### 1.4 Absent dependencies

Unchanged in all four respects. `backend/gradle/libs.versions.toml` has **no Gherkin or Cucumber
grammar**, so Phase 1's *"server-side parse/validation via the official Gherkin parser"* is still a
free-text field with a Zod rule. There is **no PDF renderer and no headless browser**. There is **no
S3 SDK**, and `deploy/` holds `compose.prod.yaml`, `deploy.sh`, `postgres/` and `shanks/` — **no Helm
chart**.

**Round 3's bound on this check earned a second confirmation.** It said an absent dependency predicts
an absent feature *only where the feature cannot reasonably be hand-rolled*, after MD and HTML export
shipped hand-written. §117 then wrote **four ingestion parsers** — JUnit, TestNG, Allure, Cucumber —
with no parser dependency at all. A Gherkin *grammar* and a PDF engine still qualify; a results
format does not.

### 1.5 Permissions with no consumer — exhausted

**10 of 55 have no consumer in `src/main/java`** (18 at round 3, 21 at round 2). Eight left the list:
`token:issue` (§116), `artifact:delete` and `run:execute` (§124 — the cheap wins round 3 flagged for
the third time), `audit:view` and `project:audit`, `company:settings`, and the two round 3 argued were
not holes.

| Unenforced key | What it means is missing |
|---|---|
| `integration:manage` | M4 integrations — SSO, trackers, notifications |
| `defect:create` | The defect view (§6l, M4) — `work_item_link` has no writer either |
| `company:idp` · `company:suspend` · `company:create` · `company:delete` | M6 activation |
| `role:manage` · `user:manage` · `system:settings` · `instance:audit` | The instance-admin surface — `instance_setting` has no writer either |

**This check should be retired after this round.** It has been the highest-yield check in every
previous assessment, and its yield is now zero *by construction*: every one of the ten remaining keys
belongs to a feature with no code anywhere, and each is already named in §2.1. A permission with no
consumer used to mean *somebody forgot*; it now means *nobody has started*, which §2.1 says more
directly. It found ten features before anything else did, across four rounds. It is done.

### 1.6 Dead columns, enums and members — no longer this document's check

Round 3 introduced "enum values with no consumer" and ran it once as a script. **It is now five
standing populations, each enforced by a test that fails the build**, built across §154–§165:

| Population | Members | What it pins |
|---|---:|---|
| `READERLESS_BY_DECISION` | 15 | a column written and never read |
| `WRITERLESS_BY_DECISION` | 5 | a column read and never written — the worse half |
| `UNEXERCISED_BY_DECISION` | 4 | a seam nothing in production calls |
| `DIVERGENCE_BY_DECISION` | 7 | a server enum and its wire enum that disagree |
| `MEMBER_DIVERGENCE_BY_DECISION` | 3 | a schema member one side declares and the other does not |

**This is the round's largest structural change and it is to the method, not to the product.** A new
dead column, a new unserved enum value or a new one-sided schema member now reddens
`./gradlew build` on the commit that introduces it. Round 3's §1.6 caught `DocBlockType` a milestone
late; the successor catches it the same day, and every exemption is a named entry with its argument
attached rather than a silence.

**Two corrections to round 3's own findings:**

- **`DocBlockType` 2 of 6 is closed** (§127) — and round 3's check would still report 2 of 6 today,
  **for the wrong reason**. `PARAGRAPH` and `CODE` have no type-specific branch in `PageContent`
  because they need none: they are plain text, and the four that branch (`HEADING`, `LIST`, `TABLE`,
  `IMAGE`) do so because they carry level, rows or an attachment. All six are authorable in
  `DocPageEditorPage` and rendered by `DocBlockBody`. **"Constant referenced outside its declaration"
  and "value the product serves" are different questions**, and this is the check's false-positive
  direction, recorded so round 5 does not re-raise it.
- **`ReportFormat` 4 of 6 stands.** `CSV` and `XLSX` have no `ReportRenderer` and no reference
  anywhere in `src/main/java`; `PDF` is refused at request time with a recorded reason. The enum is
  honest about its own gaps and the product claim "HTML/MD/PDF export" is still 2 of 3.

### 1.7 Declared-but-unserved contract members (new this round, inherited from §165)

The successor to §1.6, one layer out: **a member the contract declares and the server never sends, or
the server sends and the contract never declares.** Both directions shipped defects this week.

| Member | Direction | Consequence |
|---|---|---|
| `TestPlan.keySeq` | server-only | sent on all seven `TestPlan` operations since §74; no generated client could name it. **§100 widened this exact schema by hand and missed it.** |
| `TestCaseSummary.lastResult` | contract-only | **declared from M0 and served by nothing**, so `CaseGrid`'s Last result column and the case detail chip read *"Never run"* for every case in the product — including one a tester had recorded a result on seconds earlier. Closed by §166 (`V45`), as a read model fed by the existing `execution.results_changed` outbox event. |
| `TestCase.relatedDocuments` | contract-only, **open** | a link made from a doc block to a case is never shown on the case; `CaseDetailPage` renders an empty panel. Pinned as `MEMBER_DIVERGENCE_BY_DECISION` — the build fails if either side closes without the other. |

**The shape is the one this document has found in four consecutive rounds** — schema present,
behaviour absent — and §166 is its most expensive instance yet, because unlike a dead column it was
*visible to every user of the product* and looked like a working feature reporting a true fact.

---

## 2. Shot list

### 2.1 Not started

| # | Functionality | Milestone | Evidence |
|---|---|---|---|
| 4 | PDF export | M2.5 | no renderer, no headless browser; `ReportFormat.PDF` refuses at request time |
| 5 | CSV / XLSX export | M2.5 | `ReportFormat` declares both; no renderer, no reference in main |
| 6 | Doc-structured reports · Mermaid blocks + SVG pre-render (§6i) | M2.5 | no code; no `Mermaid` reference anywhere in the repository |
| 7 | Evidence-bundle export | M2.5 | no code; both its dependencies (§67, §87) closed long ago |
| 10 | SSO / OIDC (Cognito · Entra · Google · Okta · Keycloak) + LDAP | M4 | no `oidc`/`saml`/`scim` reference in main; `company:idp` unenforced |
| 11 | Issue trackers ×4 (Jira · GitHub · GitLab · Azure DevOps) | M4 | no code; `integration:manage` unenforced |
| 12 | Notifications (Slack / Teams webhooks, SMTP events) | M4 | mailer exists for invitations only; no webhook reference |
| 14 | Git traceability (commit / branch / PR on a run) | M4 | `IngestionRequest` **accepts** `commitSha` and nothing stores or shows it |
| 16 | Rich text + sanitized HTML | M1 | plain text throughout; no sanitizer dependency |
| — | The defect view | M4 | `work_item_link` + `derived_item_annotation` unused; `defect:create` unenforced |
| 20 | Company settings — branding, policies, defaults | M6 | `company:settings` now consumed, but no branding/policy surface exists |
| — | Instance-admin settings surface | M6 | `instance_setting` unused; `system:settings`, `role:manage`, `user:manage`, `instance:audit` all unenforced |
| — | S3 adapter, then the Helm chart | M5 | no S3 SDK, no `Chart.yaml`; §78 put both before v1.0 |
| 21 | Phase 3 remainder — trend dashboards, TestRail/Xray migration, parameterized cases, undefined-step detection, milestones, SAML, SCIM, outbound webhooks | P3 | no code. **Flake detection left this row** (§121); the step-phrase library is served by §94's corpus |

**Left this list since round 3:** CI ingestion, API tokens, quarantine, the M2 rollups, clone,
requirements & coverage matrix, case drafts, case-level attachments, doc blocks beyond heading and
list, and the outbox — **ten of round 3's twenty-one**. **Added:** nothing that was previously
built. The three additions above (defect view, instance-settings surface, S3/Helm) were all inside
round 3's rows and are broken out here because their parent rows have otherwise closed.

### 2.2 Partially built

**`Left` is the percentage still to build**, not the percentage done. Per-feature, and it does not map
one-to-one onto §3's milestone column.

| # | Functionality | Done | Not done | Left |
|---|---|---|---|---|
| 23 | Gherkin authoring (M1) | `STRUCTURED`/`GHERKIN` formats, free-text source, cross-field validation | Official parser, keyword autocomplete, Background + Scenario Outline, `.feature` preview. **§117's four parsers read *results*, not `.feature` source** — the grammar dependency is still absent | ~75% |
| 24 | Reporting (M2.5) | The pipeline (§86–§92) and now the **catalogue**: `RunSummaryReport`, `AggregateExecutionReport` (§111) with the inconsistency callout and manual/automated split (§112) and version divergence (§114), `CoverageMatrixReport` (§115) | PDF / CSV / XLSX; doc-structured reports; Mermaid + SVG; evidence-bundle export | ~15% ~~45%~~ |
| 25 | Shared steps & library typeahead (M1.5 §6h) | Both libraries with writers (§64, §95), project-scoped search, derived usage counts, the step corpus on a trgm index (§94) | The sub-100ms p99 budget; duplicate warning at creation; **both trgm indexes still spelled on the bare column while both `findLibrary` queries filter `LOWER(title)`** — unchanged since `V30` recorded it | ~25% |
| 26 | Classification (M1) | Assignment with the reconcile bound (§68), the vocabulary value editor (§69), `V20` sibling-name uniqueness | **`classification_dimension` still has no write service** — dimensions seed-only, unchanged across three rounds | ~20% |
| 27 | Evidence & attachments (M1–M2) | Both ports, attach + delete with `OWN`, session-authenticated download, **all six `owner_type` values claimed (§126)**, `storage` as its own module (§125), and **the verbatim CI upload finally stored (§160)** | **The S3 adapter** — still a prerequisite of the Helm chart, and still blocking from two ports rather than one | ~25% ~~35%~~ |
| 28 | Suites, cases & tags (M1) | Everything round 3 listed, plus §98's flattening shipped, clone (§129), the draft buffer (§128), case-level attachments (§126), and `lastResult` finally served (§166) | Rich text | ~10% ~~20%~~ |
| 29 | Plans & the plan document (M1–M2) | Lifecycle, editor, statistics, the document, **all seven of §97's phases**, §99's run scoping, clone (§129), `keySeq` declared (§165) | Plan templates (§6v) | ~10% ~~40%~~ |
| 30 | Kanban / work items (M4) | Board live end to end, drag that works on a keyboard (§106), the cross-project and team boards (§83–§85), a fourth card generator (§121) | WIP limits, personal boards, the defect view, `work_item_link`, `defect:create`, generation capping | ~65% ~~35%~~ |
| 31 | Org chart & teams (M0.5 / M4) | Team CRUD + membership, orphan-and-cycle surfacing, guarded delete, the team board | Live workload, person view, team leads as a managed attribute | ~30% |
| 32 | Multi-company activation (M6) | Invitation, acceptance, membership removal, session epoch, project access screen, **and the operator tier's first writer (§122)** | Company switcher, `/c/{slug}` routing (**still no such route in the SPA**), session switching, COMPANY-scope assignment UI, per-company IdP + email-domain routing, per-company branding, the instance-settings surface | ~55% ~~60%~~ |
| 33 | Hardening & release (M5) | Hetzner box, GHCR image, privilege split in production, the design comp, **a second deploy target on the homelab box (§113)**, **the nightly Linux gate live and green (§113)**, **the signing key removed from the repository and `ConfigConventionTest` written (§108)** | Perf pass, security review, backup/restore, install guide, S3 → Helm, and **both verification queues, re-accumulated after being paid once** (§5.2) | ~55% ~~70%~~ |
| — | `case_health` (cross-cutting) | 6 of 10 signals have writers — `failing_repeatedly` (§86), `never_executed` / `long_unexecuted` (§53), `stale_automation` (§120), `stale_approval` (§157), `condition_drift` (§158) | `outdatedSpecChanged`, `orphaned`, `unlinked` and `incomplete` are **writerless by recorded decision**, each with its argument at `CaseHealth`'s header and pinned by `ArchitectureRulesTest` | — |

### 2.3 Complete

Everything round 3 listed, plus: **M3 end to end** — API tokens, four-format ingestion, the history
and unmatched binder, automation drift, flake detection and quarantine (§116–§121) · the operator tier
(§122) · attachment owners (§126) · the four block payloads (§127) · the case draft buffer (§128) ·
clone case, plan and run (§129) · the outbox (§131) · token attribution (§132) · the `Agent` role and
`role.assignable` (§133, §134) · the M2.5 report catalogue (§111–§115) · **a third stack that is in no
milestone**: `@tcms/client`, `@tcms/cli`, `@tcms/mcp`, `ai_proposal` and rate limiting (§135–§143) ·
one error envelope and the `api` module (§146–§149) · the unit-test lane (§150) · §154–§166's seam,
column, enum and contract-member audits with their five standing rules.

**M3 is complete**, and it was 0% in three consecutive rounds. **M1, M1.5 and M2 are within a
rounding error of complete**, with rich text, plan templates and the classification-dimension writer
the only named gaps between them.

---

## 3. Percentage remaining

Weighted by the plan's own milestone estimates (midpoints of its ranges).

| Milestone | Weeks | r1 (§62) | r2 (§77) | r3 (§100) | **r4 (§166)** | Weeks left |
|---|---:|---:|---:|---:|---:|---:|
| M0 — Foundation | 2.50 | 100% | 100% | 100% | 100% | 0.00 |
| M0.5 — Identity & access | 2.00 | 95% | 100% | 100% | 100% | 0.00 |
| M1 — Test repository | 4.50 | 75% | 88% | 92% | **96%** | 0.18 |
| M1.5 — Reuse & review | 3.00 | 45% | 85% | 95% | **96%** | 0.12 |
| M2 — Execution | 5.50 | 65% | 90% | 92% | **96%** | 0.22 |
| M2.5 — Docs, artifacts & reporting | 7.50 | 50% | 50% | 72% | **85%** | 1.13 |
| M3 — Automation ingestion | 2.50 | 0% | 0% | 0% | **100%** | 0.00 |
| M4 — Integrations & workflow | 9.00 | 12% | 18% | 24% | **32%** | 6.12 |
| M5 — Hardening & release | 2.00 | 40% | 35% | 30% | **45%** | 1.10 |
| M6 — Multi-company activation | 1.75 | 35% | 40% | 40% | **45%** | 0.96 |
| **Total** | **40.25** | | | 14.84 | | **9.83** |

**M5 moved forwards for the first time in three rounds**, and the reason is §5.1: the standing risk
every previous round opened with is closed. It is still the milestone most likely to move backwards
again, for the reason §5.2 gives.

**M2.5's 85% is no longer the generous figure round 3 flagged.** That round scored 72% on
infrastructure serving one report and said a report-count-weighted reading would put it near 60%.
There are three computations now and the aggregate one carries four sections, so the catalogue and
the pipeline agree. What is left is genuinely export formats, not the engine.

### The headline numbers

- **~24% of the full plan remains** — 9.8 of 40.25 weeks (**24.4%**; 36.9% at round 3, 43.5% at round
  2, 53.0% at round 1).
- **~23% remains to M5**, which the plan calls a legitimate release point (8.9 of 38.5 weeks).
- **~13% remains to a core TCMS without the M4 integrations block** (3.7 of 29.5 weeks, **12.6%**).

**Sixty-six slices moved the total by 5.0 weeks — 0.076 plan-weeks per slice**, against round 3's
0.10 and round 2's 0.25. **The average conceals the whole story and the decomposition is the
finding:** §116–§121 alone moved 2.5 weeks in six slices (**0.42 each**, the highest rate this
project has ever recorded), because M3 was a milestone at zero with two dead tables in it. The other
sixty slices moved 2.5 weeks between them — **0.042 each, half of round 3's already-halved rate.**

That is the same law round 3 stated, now with a much larger sample: **the slices that move the number
are the ones that close a milestone at zero, and there are none left.** Everything remaining is
either an integration block or the deepening of a feature that already works.

### Where the remaining weight sits

**M4 alone is 6.1 of the 9.8 weeks — 62% of everything remaining**, up from 45% at round 3 not
because M4 grew but because everything around it finished. The five things inside it with no code at
all (SSO, issue trackers, notifications, git traceability, the defect view) are ~5.5 of those weeks.

**With M3 closed, there is no longer a second block.** M2.5's 1.1 weeks are export formats, M5's 1.1
are release hygiene and two adapters, M6's 1.0 is a switcher and a settings surface. What is left of
M1, M1.5 and M2 combined is **0.5 weeks**.

**The shape of the remainder has changed again, and this time it is binary.** Round 3 said the pre-M4
product was essentially built and essentially unverified. The pre-M4 product is now essentially built,
**verified once, and re-accumulating** — and everything else is M4.

---

## 4. Four caveats

1. **The per-milestone completion figures are judgment**, made against each milestone's own
   deliverable list. The evidence for each is §2; the week weights are the plan's, not this
   document's.
2. **The denominator is wrong in the optimistic direction, and this is new.** The 40.25 weeks are the
   plan's estimate of the plan's own scope. §135–§143 shipped **a third npm stack** — a typed client,
   a CLI with 25 commands and an MCP server with 21 tools — plus `ai_proposal` and rate limiting, and
   **none of it is in any milestone**. The percentage complete is measured against a scope the
   product has already exceeded. A round 5 that wants a defensible denominator has to add a row.
3. **A completion percentage counts what a check can see**, and §1.6 is now five checks that see
   further than any round could. Where a percentage and a `*_BY_DECISION` entry disagree, the entry
   is the one with an argument attached.
4. **Built is not verified, and this round it is a smaller gap than last round in one direction and a
   larger one in the other.** §5.2.

---

## 5. Standing risks this assessment surfaces

### 5.1 CLOSED — a machine now runs the backend test suite

Every previous round opened with this. `.github/workflows/tests.yml` runs the full `./gradlew build`
at 01:17 UTC on `[self-hosted, tcms-lab]` — the homelab box, registered since 2026-08-06 — and it is
a **Linux** observer, so it is a differently-shaped gate rather than merely a free one. §58 and §60's
class of defect is observable again.

**The bound, because it is easy to overstate:** `ci.yml` is still `-x test`, and the nightly runs on
the **default branch only**. Between a push and 01:17 UTC nothing downstream runs a backend test, and
a pushed lane branch is *backed up*, not *observed* — which is the shape §161 found in this
repository's own documentation. A developer's own `./gradlew build` before pushing is still the gate;
the nightly tells you the next morning what your machine structurally cannot see.

### 5.2 The queues were paid in full, and have re-accumulated

This is the first round that can report a payment, and the first that has to report a relapse.

| Queue | Last full run | Rows queued since |
|---|---|---:|
| `backend-mutation-checks.md` | **2026-08-05, executed end to end** — 182 rows on `4b582ec`, every mutation reverted by exact path, **9 ZERO-UNEXPECTED findings** | **365** |
| `frontend-browser-checks.md` | **§110** — §109's thirteen lanes driven live in two sittings, ~60 rows confirmed against their exact wording, **14 findings, 6 of them the product**, *none reachable by any gate* | **123** |

**Both passes justified themselves and neither has been repeated.** The mutation pass found nine
tests that assert nothing they claim to; the live browser pass found six product defects no automated
gate could reach, including an invitation link silently swallowed when opened while signed in. The
recording half of §65's policy keeps working. The executing half was paid once, six days ago, and
**488 rows have accrued since** — which now covers all of M3, the entire `packages/` stack, and
§144–§166.

By the working agreement's own words every slice behind those rows is **built and unverified**. §3
counts them as built, because they are. It does not count them as verified, because nothing has.
**Draining both queues remains unestimated work that belongs to M5 and is not in the 9.8 weeks.**

### 5.3 Two filesystem-only storage ports still block the same downstream work

Third round running, verbatim. `EvidenceStore` (§67) and `ReportStore` (§87) each have a single
filesystem adapter; §78 made the S3 adapter a prerequisite of the Helm chart rather than an optional
upgrade. **Nothing has moved on either since round 3 flagged it**, and §125 rehoming both ports into
`storage` made the pair easier to see without making either shorter.

### 5.4 The concurrency rule was tested during this derivation and held

Two commits — §165 and §166 — landed on `master` from a concurrent session between this document's
first check and its last. Nothing was lost, because the two lanes touched disjoint files and this one
re-ran its checks against the new HEAD instead of carrying the old numbers forward. **That is the
outcome the rule is for, not evidence the rule is unnecessary:** `docs/plans/2026-07-31-parallel-execution-rework.md`
§0's three silent losses were all in one tree with a clean `git status` throughout. The relevant
discipline here was re-deriving rather than trusting, which §1.7 shows was load-bearing — §166 closed
a gap this document had already written into §2.
