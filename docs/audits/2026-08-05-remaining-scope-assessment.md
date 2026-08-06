# Remaining-scope assessment — what is left to build

**Date:** 2026-08-05 · **Baseline commit:** `ced197e` (§100 phase D, the design comp)
**Round 3.** Rounds 1 and 2 are `docs/audits/2026-08-02-remaining-scope-assessment.md`, both written
that day against `33e5843` (§62) and `98d19aa` (§77). Their figures are quoted below where the
movement is readable; that file is now superseded and says so.
**Method:** derived from the repository, weighted by the milestone week estimates the high-level plan
sets out for itself (`docs/plans/2026-07-30-tcms-high-level-plan.md` §7).

> This is a **status assessment, not a plan**. It says what exists and what does not, with the
> evidence for each claim. Sequencing decisions belong in `docs/plans/`.

> **Derived and committed in one sitting**, which is the rule round 2 wrote for itself after being
> drafted at §71 and landing at §77. Every count below is `grep`- or script-derived at `ced197e`.
> The working tree at derivation time held **§97 phase 3c uncommitted** (the plan document's
> link/unlink service, `PlanDocumentTemplate` deleted); it is counted as **not shipped**.

**What moved since round 2:** twenty-three slices (§78–§100, with §98/§99 planned-not-built and §97
part-shipped). The review workflow finished (§79 comments, §80 assignment, §81 the inbox, §82 the
gating rules), the boards grew a cross-project and a team view (§83–§85), and then **the whole
reporting milestone went from zero to a working pipeline** — the fact log got a writer (§86), the job
record, second store and renderers landed (§87, §88), and four frontend slices put a screen, a view,
a way back and saved definitions on it (§89–§92). Then authoring: the plan document (§93), step
suggestions off a trgm index (§94), condition writers (§95), tags and a resolvable suite (§96), and
§97's plan-detail rework, which is **three of seven phases in**. `V24`–`V32` landed;
**next free is `V33`**.

---

## 1. How this was derived

Six computed checks against the code. The sixth is the one round 2's §4 caveat argued for and did
not run — and it is the check that produces this round's largest correction.

### 1.1 Tables with schema and no code

**Ten of the 70 tables have no writer.** Six have no reference in `src/main/java` at all; four are
named *only in javadoc*, which is the class of miss round 2 flagged twice and then made itself.

| Table | The feature it belongs to | Class |
|---|---|---|
| `api_token` | API tokens for CI (M3) | no reference |
| `automation_key_mapping` · `ingestion_job` | CI result ingestion (M3) | no reference |
| `test_case_draft` | Case drafts / autosave | no reference |
| `work_item_link` · `derived_item_annotation` | Work-item linking and the defect view (M4) | no reference |
| `suite_run_summary` · `plan_snapshot` | **M2's rollups** | comment-only |
| `outbox` | Reliable event delivery | comment-only |
| `instance_setting` | Instance admin surface | comment-only |

**Round 2's own check was blunter than its prose claimed, and this is the first finding.** It said
*"no JPA entity and no reference in any native query"* but was run as a plain `grep` for the table
name, so a table discussed in a javadoc counted as live. Four tables passed that way — and two of
them, `suite_run_summary` and `plan_snapshot`, are named deliverables of M2, which round 2 scored at
90%. The fix is one line of filtering (drop hits whose line begins `*`, `//` or `/*`) and it is now
part of this check. `report_definition` and `report_artifact` left the list honestly (§87, §92).

**Nothing currently escapes by comment that this check cannot see** — the four above are found *by*
the comment filter, not hidden by it. `review_comment`, which round 2 flagged as the escapee of its
round, gained a writer in §79.

`attachment` is still only **one-third** claimed: `owner_type` spans six values
(`CASE`,`STEP`,`EXECUTION`,`STEP_RESULT`,`DOC_PAGE`,`ARTIFACT`) and `Attachment.java` writes two.
Case-level attachments — an M1 deliverable — still have no writer, unchanged since §67.

### 1.2 Module sizes

**No module is empty any more**, and `shared` no longer exists as a package at all — round 2 counted
it at 0 files, and the tree has since been left with nine.

| Module | Files | Round 2 (§77) |
|---|---:|---:|
| `repository` | 130 | 110 |
| `execution` | 88 | 85 |
| `project` | 63 | 50 |
| `identity` | 61 | 60 |
| `documentation` | 51 | 37 |
| **`reporting`** | **39** | **0** |
| `tenancy` | 16 | 16 |
| `work` | 14 | 14 |
| `auth` | 12 | 12 |
| `provisioning` | 9 | 9 |

`reporting` going 0 → 39 is the single largest structural change of the round. It is also the check
that has now been *retired as a signal*: with no module at zero, "empty module" cannot find anything
next round, and §1.6 is what replaces it.

### 1.3 Contract coverage is not product coverage

The contract declares **68 paths and all 68 have controllers** — round 2 had 53 of 55, the two
missing being `/reports` and `/reports/generate`, both served by §87–§92. Verified by matching each
`paths:` key against class-level plus method-level `@*Mapping` values with `/api/v1` stripped and
path variables normalised; there are also **zero controller routes not in the contract**.

**This is now completely uninformative as a progress measure and should not be quoted as one.** It
reads 100% while ingestion, SSO, issue trackers, exports, clone and templates are not in
`openapi.yaml` at all, so they cannot appear as missing paths. What it still proves is a *hygiene*
property — no drift in either direction between contract and controllers — and that is the only
thing to claim for it from here on.

### 1.4 Absent dependencies — and where the check produced a false positive

`backend/gradle/libs.versions.toml` still has **no Gherkin or Cucumber parser**, so Phase 1's
"syntax-aware editor, server-side parse/validation via the official Gherkin parser" is still a
free-text field with a Zod rule. It has **no PDF renderer** and no headless browser, which
`ReportFormat.PDF`'s own javadoc records as a request-time refusal rather than a wire narrowing.
There is **no S3 SDK** and **no Helm chart** (`deploy/` holds `compose.prod.yaml`, `deploy.sh` and
the initdb scripts), both of which §78 put before v1.0.

**But this check called MD/HTML export missing and it shipped.** Round 2 read "no Markdown or PDF
renderer" off the dependency list; §87 and §88 then wrote `MarkdownReportRenderer` and
`HtmlReportRenderer` by hand, deliberately with no template engine. **An absent dependency predicts
an absent feature only where the feature cannot reasonably be hand-rolled** — a Gherkin grammar and a
PDF engine qualify, a Markdown emitter does not. The check keeps its place with that bound written on
it.

### 1.5 Permissions in the catalogue with no consumer

Still the highest-yield check in this document, though its yield is now falling because the cheap
rows have been taken. **18 of 55 permissions have no consumer in `src/main/java`** (21 at round 2).
Three left the list: `case:comment` (§79) and `report:view` + `report:export` (§90, §92) — the
eighth, ninth and tenth consecutive features this check has caught before anything else did.

| Unenforced key | What it means is missing |
|---|---|
| `token:issue` · `integration:manage` | M3 API tokens, M4 integrations |
| `defect:create` | The defect view (§6l, M4) — `work_item_link` has no writer either |
| `company:idp` · `company:suspend` · `company:create` · `company:delete` | M6 activation + instance admin |
| `company:settings` | Company branding, policies and defaults — no screen, no endpoint |
| `role:manage` · `user:manage` · `system:settings` | Admin surface not yet gated by its own key |
| `audit:view` · `instance:audit` · `project:audit` | Audit views |
| `artifact:delete` · `run:execute` | Verbs on shipped features — near-zero design cost |
| `artifact:read` · `doc:read` | **Not holes** — see below |

`artifact:read` and `doc:read` carry round 2's reading unchanged and it is still correct:
reachability in `documentation` is decided by the project grant and answers 404-never-403
(`DocPageController`'s own header), so a read key would be a second gate answering a question the
first already answered. A key with no consumer means *nobody built it or nobody needed it*, and the
difference is a per-key judgement, not a count.

`artifact:delete` and `run:execute` are the round's remaining cheap wins — both sit on features that
already exist. They were the cheap wins at round 2 as well and neither was taken, which is worth
saying plainly rather than repeating the recommendation as if it were new.

### 1.6 Enum values with no consumer (new this round)

Round 2's §4 caveat named the check and did not run it: *for each status/enum column, how many
places write it?* Run at `ced197e` over every enum in `src/main/java`, comparing declared constants
against constants referenced anywhere outside their own declaration file:

| Enum | Consumed / declared | The values nothing touches |
|---|---:|---|
| `DocBlockType` | 2 / 6 | `PARAGRAPH`, `CODE`, `TABLE`, `IMAGE` |
| `ReportFormat` | 4 / 6 | `CSV`, `XLSX` |

`DocBlockType` is the real finding. Six block types parse and persist, and `PageContent` branches on
exactly two — `HEADING` and `LIST`. A documentation space that stores a table block and renders it as
nothing is the same shape as §72–§75's four status columns with one writer each: schema, enum and
screens all present, behaviour absent. **This is the check that would have caught those four a
milestone earlier, and it now finds its successor.**

`ReportFormat` is a softer read: `PDF` is refused at request time with a message and a recorded
reason, and `CSV`/`XLSX` have no `ReportRenderer` implementation, so a job asking for either is a
400 by the same path. The enum is honest about its own gaps; the *product* claim "HTML/MD/PDF export"
is 2 of 3.

**One layer down, the same shape again.** `ReportComputation` has exactly **one** implementation —
`RunSummaryReport`. The reporting *pipeline* is built end to end; the reporting *catalogue* is one
report. §2.2 row 4 is scored on that distinction and not on the module's file count.

---

## 2. Shot list

### 2.1 Not started

| # | Functionality | Milestone | Evidence |
|---|---|---|---|
| 1 | CI result ingestion (JUnit · TestNG · Allure · Cucumber) | M3 | `ingestion_job`, `automation_key_mapping` unused; no parser dependency |
| 2 | API tokens for CI | M3 | `api_token` unused; `token:issue` unenforced |
| 3 | Quarantine flow for unmatched results | M3 | no code |
| 4 | PDF export | M2.5 | no renderer, no headless browser; `ReportFormat.PDF` refuses at request time |
| 5 | CSV / XLSX export | M2.5 | `ReportFormat` declares both, no `ReportRenderer` claims either |
| 6 | Doc-structured reports · Mermaid blocks + SVG pre-render (§6i) | M2.5 | no code, no `Mermaid` reference anywhere |
| 7 | Evidence-bundle export | M2.5 | no code; its two dependencies (evidence §67, the pipeline §87) are now both closed |
| 8 | `SuiteRunSummary` + `PlanSnapshot` rollups | M2 | **both tables comment-only — new to this list, see §1.1** |
| 9 | Plan templates + clone case / plan / run (§6v) | M1–M2 | no code, no table. *(Distinct from §97's retired plan-document template.)* |
| 10 | SSO / OIDC (Cognito · Entra · Google · Okta · Keycloak) + LDAP | M4 | no `oidc`/`saml` reference in main |
| 11 | Issue trackers ×4 (Jira · GitHub · GitLab · Azure DevOps) | M4 | no code |
| 12 | Notifications (Slack / Teams webhooks, SMTP events) | M4 | mailer exists for invitations only; no webhook reference |
| 13 | Requirements & coverage matrix | M4 | no code |
| 14 | Git traceability (commit / branch / PR on a run) | M4 | no code |
| 15 | Case drafts / autosave | M1–M2 | `test_case_draft` unused |
| 16 | Rich text + sanitized HTML | M1 | plain text throughout |
| 17 | Case-level attachments | M1 | `attachment.owner_type` has six values; `Attachment.java` writes two |
| 18 | Doc blocks beyond heading and list | M2.5 | **new — `DocBlockType` 2 of 6 consumed (§1.6)** |
| 19 | Reliable event delivery | cross-cutting | `outbox` comment-only; `CompanyScopedEvent`'s own javadoc says a failed handler must land there |
| 20 | Company settings — branding, policies, defaults | M6 | `company:settings` unenforced; no endpoint |
| 21 | **All of Phase 3** — flake detection, trend dashboards, TestRail/Xray migration, parameterized cases, step-phrase library, undefined-step detection, milestones, SAML, SCIM, outbound webhooks | P3 | no code. Importers moved to M4 in §78; **step-phrase library partly pre-empted by §94's corpus** |

**Left this list since round 2:** the reporting engine, the async job pipeline, the aggregate
execution report and HTML/MD export (§86–§92); threaded review comments (§79). **Added:** the M2
rollups and the doc-block types, both of which were previously invisible to the checks rather than
newly broken, plus `outbox`, which is a cross-cutting reliability gap the round-2 method never
surfaced.

### 2.2 Partially built

**`Left` is the percentage still to build**, not the percentage done. It is a per-feature figure and
does not map one-to-one onto §3's milestone column, which weighs a feature against everything else
in its milestone.

| # | Functionality | Done | Not done | Left |
|---|---|---|---|---|
| 22 | Review workflow (M1.5) | Three ladders gated by the edge (§62/§73/§75); the `review`/`review_participation` record (§76); the four `project_settings` behaviours (§77); **threaded comments with resolution (§79, `V24`), reviewer assignment (§80, `V25`), the reviewer's inbox (§81), and §6g's gating rules with the override (§82, `V26`)** | Diff-since-approval | ~5% ~~25%~~ |
| 23 | Gherkin authoring (M1) | `STRUCTURED`/`GHERKIN` formats, free-text source, cross-field validation | Official parser, keyword autocomplete, Background + Scenario Outline, `.feature` preview | ~75% |
| 24 | Reporting (M2.5) | **The whole pipeline (§86–§92)** — `ExecutionFact` refresh + scheduler, `ReportJob` (`V27`) with runner and scheduler, `ReportStore` + filesystem adapter, `ReportModel`/`ReportDocument`/`ReportHeader`, MD + HTML + JSON renderers with startup-enforced one-per-format, the Reports screen, the on-screen view, saved definitions (`V28`), `report:view`/`report:export` gated | **One `ReportComputation` — `RunSummaryReport` (§1.6)**; PDF/CSV/XLSX; doc-structured reports; Mermaid + SVG; evidence-bundle export | ~45% ~~100%~~ |
| 25 | Shared steps & library typeahead (M1.5 §6h) | Condition and shared-step libraries with writers (§64, §95), project-scoped search, `library:manage` gated, usage counts now **derived** rather than reading a column that was always zero (§95); **the step corpus and keystroke suggestions on a correctly-spelled functional trgm index (§94, `V30`)** | The sub-100ms p99 budget; duplicate warning at creation; **`shared_step_title_trgm_idx` and `condition_title_trgm_idx` are spelled on the bare column while both `findLibrary` queries filter `LOWER(title)`, so both libraries sequentially scan and both look indexed** — `V30`'s own comment records this and deliberately does not fix it | ~25% ~~35%~~ |
| 26 | Classification (M1) | Assignment with the reconcile bound (§68), the vocabulary value editor (§69), `V20` sibling-name uniqueness | **`classification_dimension` still has no writer** — dimensions are seedable only, unchanged since round 2 | ~20% |
| 27 | Evidence & attachments (M1–M2) | `EvidenceStore` port, filesystem adapter, attach + delete with `OWN`, session-authenticated download, rows-then-bytes ordering (§67), immutability closed (§72) | S3 adapter — a **prerequisite of the Helm chart** (§78 #6), and now of `ReportStore` too, which is a second filesystem-only port — and case-level owners (4 of 6 `owner_type` values) | ~35% |
| 28 | Suites, cases & tags (M1) | Suite tree, case CRUD, versions, timelines, health signals, search, facets; **tags become writable and a suite resolves from them (§96, `V31`)**, cases may be filed nowhere, run scoping resolves the union | Clone case (§6v); rich text; case-level attachments; **§98 flattens the suite tree (`parent_id` + `path LTREE` go) and is a destructive migration, planned not built** | ~20% |
| 29 | Plans & the plan document (M1–M2) | Lifecycle (§73), the editor and its optimistic lock (§74), statistics, **the plan document with link/adopt (§93, §97 ph.1), six typed sections + scope prose + tags (§97 ph.2/3a/3b, `V29`, `V32`), and M7's reduction to three states** | **§97 phases 3c–7 — the link/unlink service (uncommitted), the form spine, the seven tabs, the Test cases tab, and the whole record.** Plan templates + clone (§6v). §99's direct run scoping | ~40% |
| 30 | Kanban / work items (M4) | Board live end to end, five columns, idempotent create, derived cards, project members; **the cross-project "My Work" board (§83) and the team board with its screen (§84, §85)** | WIP limits, personal boards, the defect view (§6l), `work_item_link`, `defect:create`; generation capping (§78 put every generator on by default and added a sixth) | ~35% ~~45%~~ |
| 31 | Org chart & teams (M0.5 write path / M4 chart) | Team CRUD + membership, orphan-and-cycle surfacing, delete refusing on live grants and child teams, `V21` (§71); **the team board is the first drill-down (§84, §85)** | Live workload, person view, team leads as a managed attribute, `provision-company` deliberately seeds none | ~30% ~~40%~~ |
| 32 | Multi-company activation (M6) | Invitation, acceptance, membership removal, session epoch, `issue-invitation`, the project access screen (§63) | Company switcher, `/c/{slug}` routing (**no such route exists in the SPA**), session switching, `COMPANY`-scope assignment UI, per-company IdP + email-domain routing, per-company branding | ~60% |
| 33 | Hardening & release (M5) | Hetzner box live, runbook executed and corrected, GHCR image, privilege split verified in production; **§100's design comp landed and its browser pass was run** | Perf pass, security review, backup/restore, install guide, **a Linux CI gate that runs the tests**, **the S3 adapter then a Helm chart before v1.0 (§78)**, and **two verification queues that have more than doubled** (§5.2) | ~70% ~~65%~~ |

### 2.3 Complete

Tenancy + RLS + the isolation harness · identity, roles, permissions, the evaluator incl. the
`OWN`/plan-scope qualifiers · team management (§71) · project CRUD and project access (§63) ·
case CRUD, versions, timelines, health signals · search, facets, shareable filters · repository
dashboard · runs, executions, results, configurations · the four lifecycle ladders (§62, §72, §73,
§75) · plan statistics · documentation space · artifact register · bulk operations · the resilience
layer · deployment · **the review workflow end to end (§76–§82)** · **`ExecutionFact` + `CaseHealth`
as a written read model rather than a schema (§86)** · **the report job pipeline and its four screens
(§87–§92)** · **the step corpus and keystroke suggestions (§94)** · **condition and shared-step
libraries with real writers and derived counts (§95)**.

**M1.5 is now complete.** The review workflow was its last open deliverable and §82's gating rules
closed it. Round 2's 85% was carrying exactly the four slices §79–§82 then shipped.

**The largest correction between round 2 and here is a subtraction, not an addition.** Round 2 scored
M2 at 90% while two of its named deliverables — the `SuiteRunSummary` and `PlanSnapshot` rollups —
had no code at all, invisible because both tables are discussed in javadoc. That is the same failure
mode round 2 itself identified for `attachment`/`evidence` at round 1 and for `review_comment` at
round 2, made a third time by the check that was supposed to prevent it. **A table named in a comment
is not a table with a writer**, and the filter is now in §1.1.

---

## 3. Percentage remaining

Weighted by the plan's own milestone estimates (midpoints of its ranges).

| Milestone | Weeks | r1 (§62) | r2 (§77) | **r3 (§100)** | Weeks left |
|---|---:|---:|---:|---:|---:|
| M0 — Foundation | 2.50 | 100% | 100% | 100% | 0.00 |
| M0.5 — Identity & access | 2.00 | 95% | 100% | 100% | 0.00 |
| M1 — Test repository | 4.50 | 75% | 88% | **92%** | 0.36 |
| M1.5 — Reuse & review | 3.00 | 45% | 85% | **95%** | 0.15 |
| M2 — Execution | 5.50 | 65% | 90% | **85%** | 0.83 |
| M2.5 — Docs, artifacts & reporting | 7.50 | 50% | 50% | **72%** | 2.10 |
| M3 — Automation ingestion | 2.50 | 0% | 0% | 0% | 2.50 |
| M4 — Integrations & workflow | 9.00 | 12% | 18% | **24%** | 6.84 |
| M5 — Hardening & release | 2.00 | 40% | 35% | **30%** | 1.40 |
| M6 — Multi-company activation | 1.75 | 35% | 40% | 40% | 1.05 |
| **Total** | **40.25** | | | | **15.23** |

**Two milestones went backwards and neither is an error.**

- **M2, 90% → 85%**, because §1.1's comment filter revealed that its rollup deliverable has no code.
  Nothing was un-built; the previous figure was wrong.
- **M5, 35% → 30%**, because the verification queues grew from 13 slices each to **30 and 32**, and
  §78's Helm chart and its S3 prerequisite are still both unstarted. §100 ran a browser pass and it
  was **mock mode only**, which the queue header itself says is not the one that counts. The
  milestone gained work faster than it did work, for the second round running.

A third figure would have moved backwards on a stricter reading: **M2.5's 72% is generous**, and it
is scored deliberately. The pipeline is genuinely built — that is 39 files, a job record, a store
port, three renderers and four screens — but it serves **one report type**, so the milestone's
"aggregate execution report" line is closer to a template than a catalogue. 72% counts the
infrastructure; a report-count-weighted reading would put it nearer 60%.

### The headline numbers

- **~38% of the full plan remains** — 15.2 of 40.25 weeks (**37.8%**; 43.5% at round 2, 53.0% at
  round 1).
- **~37% remains to M5**, which the plan calls a legitimate release point: *"a single-company v1.0
  can ship and be used in production while M6 is built"* (14.2 of 38.5 weeks, **36.8%**).
- **~25% remains to a core TCMS without the M4 integrations block** (7.3 of 29.5 weeks, **24.9%**).

Twenty-three slices moved the total by **2.3 weeks** — about **0.10 plan-weeks per slice**, against
round 2's **0.25**. **The rate has more than halved and the reason is visible in the shot list:** the
slices that move the number are the ones that close dead schema or an unwritten column, and there are
ten dead tables left where there were twelve, most of them behind whole unstarted milestones. §86–§92
moved M2.5 by 22 points across seven slices; §79–§82 moved M1.5 by 10 across four; §93–§97 and §100,
seven slices of authoring and polish on features that already worked, moved the total by under half a
week between them. That is not a criticism of those slices — §96's nullable key dropped rows in four
places — it is what "deepening a working feature" costs in plan-weeks.

### Where the remaining weight sits

**M4 alone is 6.8 of the 15.2 weeks and is ~24% done** — still the largest single block by a wide
margin, and the five things inside it with no code at all (SSO, issue trackers, notifications,
requirements matrix, Git links) are ~6 of those weeks. **M3 is 2.5 weeks at zero.** Together they are
**9.3 of 15.2 weeks — 61% of everything remaining**, and both are *integration* milestones: parsers,
adapters, protocols, third-party credentials. Neither is more of what has been built so far.

Round 2's second-largest block was reporting; it is now 2.1 weeks and mostly catalogue work. What is
left of M1, M1.5 and M2 combined is **1.3 weeks** of finishing work, and §97's own remaining four
phases are a visible share of it.

**The shape of the remainder has changed.** Round 2 could say the pre-M4 product was essentially
built and essentially unverified. That is more true now, not less — and the unverified half has
doubled while the built half closed.

---

## 4. Four caveats

1. **The per-milestone completion figures are judgment**, made against each milestone's own
   deliverable list. The evidence for each is §2; the week weights are the plan's, not this
   document's.
2. **The remaining weeks are less risky than the elapsed ones.** Tenancy, RLS, the authorization
   model, the contract discipline, the resilience layer and a backend harness of **1758 `@Test`
   methods** across 84 files (plus **601** frontend `it`/`test` declarations across 60 files) are
   done, so most of what is left is *additive*. Both figures are `grep`-derived at `ced197e` and are
   **not** the same measure as a suite run — `it.each` and parameterized cases expand, and §100's
   handoff records the runner at 849 frontend tests against the same 60 files. Compare each figure to
   its own history, never to the other.
3. **A completion percentage counts what was built, not what a column can hold.** Round 2 wrote this
   about status columns; §1.6 now runs it, and it found `DocBlockType` at 2 of 6. The same sentence
   extends one step further this round: **a percentage counts what a check can see**, and a check
   that greps a table name cannot see the difference between a writer and a javadoc.
4. **New this round: an unfinished slice is scored as unfinished.** §97 is three phases of seven and
   its fourth is uncommitted in the working tree. Row 29 counts phases 3c–7 as remaining. A slice
   with shipped commits and no handoff section is the easiest thing in this repository to
   double-count as done — see §5.3.

---

## 5. Standing risks this assessment surfaces

### 5.1 No machine anywhere runs the backend test suite

CI has run `./gradlew build -x test` since §59, and `./gradlew build` on a developer's own macOS
machine is the only gate — which structurally cannot observe a Linux-only defect (§58, §60). All 15.2
remaining weeks land on that footing, and the suite it cannot run has grown from 1443 to **1758**
`@Test` methods since round 2. `docs/plans/2026-08-01-tcms-homelab-target.md` §9 is the standing
proposal.

### 5.2 The deferred-verification queues more than doubled again

§65 made browser passes and backend mutation checks deferred-and-batched, at the user's direction.
The recording half keeps working; the executing half has still not been paid.

| Queue | Slices with rows | Entries | Run |
|---|---:|---:|---:|
| `backend-mutation-checks.md` | **30** (~~13~~) | **186** | **1** — the pre-policy worked example |
| `frontend-browser-checks.md` | **32** (~~13~~) | **366** | **11** — §100 only, mock mode |

**Round 2 said both queues had "roughly doubled in one day". They have roughly doubled again.** By
the working agreement's own words, every slice in an unrun queue is **built and unverified** — and
that now covers the entire review workflow, the entire reporting pipeline, the step corpus, the
condition writers, tags-and-suite-resolution, and the shipped half of §97. **That is most of §2.3 and
essentially all of the 2.3 weeks the total moved this round.**

§100's browser pass is the one exception and its own handoff records why it is a weak one: run at the
user's explicit direction, **mock mode rather than live** — which `CLAUDE.md` names as the mode that
counts, and which the queue header backs with five slices where the mock and the SPA shared one wrong
assumption — with one row unobservable there at all and one width-dependent claim never driven
because `resize_window` had no effect. Eleven of 366.

The percentages in §3 count these slices as built, because they are. They do not count them as
verified, because nothing has. **Draining both queues is unestimated work that belongs to M5 and is
not in the 15.2 weeks**, and at 552 entries it is no longer plausibly a single sitting.

### 5.3 §97 shipped four commits with no handoff section and no index row

> **→ CLOSED at `8e71010`, after this round's baseline.** §97 phases 3c–7 landed the same day: the
> plan-detail rework is complete, `docs/handoff/97-the-plan-detail-rework.md` exists, and the index
> carries its row. The finding is kept because the *mechanism* is unchanged — the gap was invisible
> in the index while it was open, and nothing but a reader noticing would have closed it. §98 and §99
> remain planned behind it; the rest of this section still stands as written.

`CLAUDE.md`: *"Finishing a slice means adding a file and one index row."* §97 has phases 1, 2, 3a and
3b in `git log`, a `V32` on disk, and **no `docs/handoff/97-*.md` and no row in `docs/HANDOFF.md`** —
its phase 7 is where the record was to be written. §100 then shipped a complete record *after* it, so
the index reads §96 → §100 and a reader scanning it sees no gap at all.

This is not a bookkeeping complaint. **The handoff index is the artifact this assessment's §2.3 is
cross-checked against**, and a slice that is invisible there is a slice whose work gets attributed to
whatever landed next. It is also the failure mode with the shortest half-life in this repository — §97
phase 3c is sitting uncommitted right now, and the longer the gap stays open the more of it must be
reconstructed from diffs. §98 and §99 are planned behind it
(`docs/plans/2026-08-05-organization-model-restatement.md`), and §98 is a **destructive migration**
that flattens the suite tree.

### 5.4 Two filesystem-only storage ports now block the same downstream work

`EvidenceStore` (§67) and `ReportStore` (§87) are both ports with a single filesystem adapter. §78
made the S3 adapter a prerequisite of the Helm chart rather than an optional upgrade; there are now
**two** adapters to write before that chart, not one, and nothing in the repository records the
second one as being on that path.
