# Remaining-scope assessment — what is left to build

> **→ SUPERSEDED 2026-08-05 by `docs/audits/2026-08-05-remaining-scope-assessment.md` (round 3,
> baseline `ced197e`, §100).** Kept in full rather than deleted, because two of its findings are only
> readable against it: its §1.1 check was run as a plain `grep` despite claiming to exclude
> non-code references, so four tables discussed only in javadoc — including M2's `suite_run_summary`
> and `plan_snapshot` rollups — counted as live, and **its M2 figure of 90% is overstated for that
> reason**. Round 3 §1.1 has the corrected check and §1.4 records where its dependency check produced
> a false positive (MD/HTML export shipped hand-rolled). **Do not quote the numbers below as
> current.**

**Date:** 2026-08-02 · **Baseline commit:** `98d19aa` (handoff §77, per-project review settings)
**Round 2.** Round 1 was written the same day against `33e5843` (§62). Its numbers are kept
struck-through below rather than deleted, so the movement is readable and the reasoning that
produced the old figure is not lost.
**Method:** derived from the repository, weighted by the milestone week estimates the high-level plan
sets out for itself (`docs/plans/2026-07-30-tcms-high-level-plan.md` §7).

> This is a **status assessment, not a plan**. It says what exists and what does not, with the
> evidence for each claim. Sequencing decisions belong in `docs/plans/`.

> **Rebaselined before it landed, and that is the first finding.** Round 2 was drafted against
> `d94b2c0` (§71) and sat uncommitted while six more slices shipped (§72–§77), so its figures were
> stale on the day they were written — an assessment that lags the repository it measures is worth
> less than no assessment, because it is quoted with a date on it. Every count below was **re-derived
> at `98d19aa`**; where the §71 draft's number is interesting it is kept as *(draft §71)*. The
> practical rule: **a scope assessment is derived and committed in the same sitting, or it is
> re-derived before it is.**

**What moved since round 1:** fifteen slices (§63–§77) — the project access screen, the shared-step
library, permission qualifiers on the wire and the `OWN` qualifier reaching the row, evidence attach
/delete with the storage port, classification assignment and the vocabulary editor, the contract-check
verb fix, team management, and then **four lifecycle ladders in a row** (run §72, plan §73, suite §75)
with the plan editor §74 between them, the review record §76 and its per-project settings §77.
Six previously dead tables (`shared_step`, `attachment`, `evidence`, `test_plan_version`, `review`,
`review_participation`) gained readers and writers; five more Flyway versions landed (`V20`–`V23`,
**next free is `V24`**).

---

## 1. How this was derived

Not from `docs/HANDOFF.md`'s narrative — that records what each slice *did*, which systematically
over-represents finished work. Five computed checks against the code instead. The fifth is new this
round, and is the one §71 argued for explicitly.

### 1.1 Tables with schema and no code

~~Twelve~~ ~~Eleven (draft §71)~~ **Eight** of `V1`'s 64 tables have **no JPA entity and no reference
in any native query**. Each is a feature whose schema was designed in M0 and whose behaviour was
never built:

| Table | The feature it belongs to |
|---|---|
| `api_token` | API tokens for CI (M3) |
| `automation_key_mapping` · `ingestion_job` | CI result ingestion (M3) |
| `report_definition` · `report_artifact` | The reporting engine (M2.5) |
| `test_case_draft` | Case drafts / autosave |
| `work_item_link` · `derived_item_annotation` | Work-item linking and the defect view (M4) |

**Closed since round 1:** `shared_step` (§64), `attachment` + `evidence` (§67) — which round 1 called
out separately because they escaped the automated list only by being named in comments — plus
`test_plan_version` (§74), and `review` + `review_participation` (§76). `V23` also *added* a table,
`project_settings` (§77), which is the first row of `V1`-external schema this list has had to
account for.

**`review_comment` is now the table that escapes the check by being named in a comment** — the same
class round 1 flagged for `attachment`/`evidence`, one milestone later. `ReviewParticipation`'s
javadoc says free text is *"the only text on a review until `review_comment` gets a writer"*, and
that mention is the entire reason a `grep` for the table name is not empty. Threaded review comments
are unbuilt.

`attachment` is only **half** claimed: `owner_type` spans six values and §67 implemented two
(execution, step result). Case-level attachments — an M1 deliverable — still have no writer.

### 1.2 Empty modules

`reporting` and `shared` contain **0 Java files**, unchanged since round 1 and unchanged since M0.
Live module sizes: `repository` 110 · `execution` 85 · `identity` 60 · `project` 50 ·
`documentation` 37 · `tenancy` 16 · `work` 14 · `auth` 12 · `provisioning` 9. **`project` nearly
doubled** (28 → 50) across §76 and §77, which is where the review record and its settings landed
rather than in a `review` module — a placement §76 argued for explicitly.

### 1.3 Contract coverage is not product coverage

The contract declares ~~42~~ ~~49 (draft §71)~~ **55 paths; 53 have controllers**. The two without
are `/projects/{projectId}/reports` and `/projects/{projectId}/reports/generate`. `/shared-steps` —
a dead end in round 1 — now has one.

**This number is misleading on its own and should not be quoted as progress.** The contract covers
the MVP surface. Ingestion, SSO, issue trackers, exports, clone and templates are not in
`openapi.yaml` *at all*, so they cannot show up as missing paths.

### 1.4 Absent dependencies

No Gherkin or Cucumber parser in `backend/gradle/libs.versions.toml`, so the "syntax-aware editor,
server-side parse/validation via the official Gherkin parser" of Phase 1 is still a free-text field
with a Zod rule. Nor is there a Markdown or PDF renderer — M2.5's HTML/MD/PDF export has no
dependency behind it either.

### 1.5 Permissions in the catalogue with no consumer (new this round)

§71 turned a recurring discovery into a search: *a permission key with no `grep` hit for its quoted
literal in `src/main/java` is a feature nobody built.* It has now caught a feature **seven times
running** — `case:approve` (§62), `library:manage` (§64), `company:vocabulary` (§69),
`company:teams` (§71), the three plan keys (§73), the suite keys (§75), and the three review keys
(§76). Nothing else in this document has that hit rate.

Run over the whole catalogue at `98d19aa`, **21 of 55 permissions have no consumer in Java**
(~~22, draft §71~~ — five were consumed by §73/§75/§76, and the re-derivation surfaced four the
draft's list had not shown). They are not noise; they map cleanly onto the shot list below:

| Unenforced key | What it means is missing |
|---|---|
| `token:issue` · `integration:manage` | M3 API tokens, M4 integrations |
| `report:view` · `report:export` | The reporting engine (M2.5) |
| `case:comment` | Threaded review comments — `review_comment`'s missing writer (M1.5) |
| `defect:create` | The defect view (§6l, M4) |
| `company:idp` · `company:suspend` · `company:create` · `company:delete` | M6 activation + instance admin |
| `company:settings` | Company branding, policies and defaults — no screen, no endpoint |
| `role:manage` · `user:manage` · `system:settings` | Admin surface not yet gated by its own key |
| `audit:view` · `instance:audit` · `project:audit` | Audit views |
| `artifact:delete` · `artifact:read` · `doc:read` · `run:execute` | Verbs and reads on shipped features |

**Two rows need reading carefully, because the search is blunter than it looks.** `artifact:read` and
`doc:read` are *not* holes: reachability in `documentation` is decided by the project grant and
answers 404-never-403 (`DocPageController`'s own header), so a read key would be a second gate
answering a question the first already answered. A key with no consumer means *nobody built it or
nobody needed it*, and the difference has to be checked one key at a time. `case:review` and
`suite:review` left this list in §76 while `case:comment` stayed, **deliberately** — §6g's three
tiers, made observable by `V3` granting comment to Viewer and review to Test Engineer.

The last row's other two are the interesting ones: `artifact:delete` and `run:execute` belong to
features that *are* built, so they are plausible next slices at near-zero design cost, not new
milestones. `plan:delete` was in this row in the §71 draft and shipped in §73 — which is the pattern
working exactly as advertised.

---

## 2. Shot list

### 2.1 Not started

| # | Functionality | Milestone | Evidence |
|---|---|---|---|
| 1 | CI result ingestion (JUnit · TestNG · Allure · Cucumber) | M3 | `ingestion_job`, `automation_key_mapping` unused |
| 2 | API tokens for CI | M3 | `api_token` unused; `token:issue` unenforced |
| 3 | Quarantine flow for unmatched results | M3 | no code |
| 4 | Reporting engine — `ReportModel`, async job pipeline | M2.5 | `reporting` module still 0 files; `report_definition` unused |
| 5 | HTML / Markdown / PDF export | M2.5 | no renderer, no dependency |
| 6 | Aggregate execution report | M2.5 | `/reports` still the only contract path with no controller |
| 7 | Evidence-bundle export | M2.5 | depends on 5; its other dependency (evidence) closed in §67 |
| 8 | Plan templates + clone case / plan / run (§6v) | M1–M2 | no code, no table |
| 9 | SSO / OIDC (Cognito · Entra · Google · Okta · Keycloak) + LDAP | M4 | no code |
| 10 | Issue trackers ×4 (Jira · GitHub · GitLab · Azure DevOps) | M4 | no code |
| 11 | Notifications (Slack / Teams webhooks, SMTP events) | M4 | mailer exists for invitations only |
| 12 | Requirements & coverage matrix | M4 | no code |
| 13 | Git traceability (commit / branch / PR on a run) | M4 | no code |
| 14 | Case drafts / autosave | M1–M2 | `test_case_draft` unused (plan versioning left this row in §74) |
| 15 | Rich text + sanitized HTML | M1 | plain text throughout |
| 16 | Case-level attachments | M1 | `attachment.owner_type` has six values; §67 implemented two |
| 17 | Threaded review comments | M1.5 | `review_comment` has no writer; `case:comment` unenforced |
| 18 | Company settings — branding, policies, defaults | M6 | `company:settings` unenforced; no endpoint |
| 19 | **All of Phase 3** — flake detection, trend dashboards, CSV/Excel import, TestRail/Xray migration, parameterized cases, step-phrase library, undefined-step detection, milestones, SAML, SCIM, outbound webhooks | P3 | no code. **Note the plan moved importers out of P3 in §78** — TestRail + Zephyr + Xray through one adapter, now M4 |

**Left this list since round 1:** evidence capture / attachments / file-storage port (§67), the
shared-step library (§64), and plan versioning (§74, `test_plan_version`'s first writer). The org
chart moved to *partially built* (§71). **Added since the §71 draft:** threaded review comments and
company settings, both of which were previously hidden inside larger rows — §76 and §77 built enough
of the review workflow that what is left of it is now specific enough to name on its own line.

### 2.2 Partially built

**`Left` is the percentage still to build**, not the percentage done — as in round 1. It is a
per-feature figure and does not map one-to-one onto the milestone column in §3, which weighs a
feature against everything else in its milestone.

| # | Functionality | Done | Not done | Left |
|---|---|---|---|---|
| 20 | Review workflow (M1.5) | **Three ladders, one per subject** — case (§62), plan (§73), suite (§75), each gated by the *edge* rather than the target; the `review` / `review_participation` record with `WITHDRAWN` and one open review per subject (`V22`, §76); LGTM / request-changes as advisory participation that cannot write an approval; **the per-project opt-in, `required_peer_reviews`, author-may-approve-own-work and edit-after-approval, all four in `project_settings` (`V23`, §77)** | Threaded comments (`review_comment`, `case:comment`), reviewer *assignment* (participation is currently self-service), diff-since-approval | ~25% ~~65%~~ |
| 21 | Gherkin authoring (M1) | `STRUCTURED`/`GHERKIN` formats, free-text source, cross-field validation | Official parser, keyword autocomplete, Background + Scenario Outline, `.feature` preview | ~75% |
| 22 | Shared steps & library typeahead (M1.5 §6h) | Condition library **and the shared-step library (§64)**, project-scoped search, `library:manage` gated | `tsvector` + `pg_trgm` path (still `LIKE` on title only), the sub-100ms p99 budget, duplicate warning at creation, **`ConditionRepository`'s `ORDER BY usageCount` still ranks by a constant** | ~35% ~~50%~~ |
| 23 | Classification (M1) | Assignment with the reconcile bound (§68), the vocabulary value editor with the dimension-decided gate (§69), `V20` sibling-name uniqueness | **`classification_dimension` still has no writer** — dimensions are seedable only | ~20% |
| 24 | Evidence & attachments (M1–M2) | The `EvidenceStore` port, filesystem adapter, attach + delete with `OWN`, session-authenticated download, rows-then-bytes ordering (§67); **§67's deferred immutability invariant closed in §72** | S3 adapter — **now a prerequisite of the Helm chart rather than an optional upgrade (§78 #6)** — and case-level owners (4 of 6 `owner_type` values) | ~35% ~~40%~~ |
| 25 | Kanban / work items (M4) | Board live end to end, five columns, idempotent create, derived cards, project members | WIP limits, personal + team boards, the defect view (§6l), `work_item_link`, `defect:create`; **§78 put every generator on by default and added a sixth**, so generation capping is now a requirement of the next board slice rather than prudence | ~45% ~~40%~~ |
| 26 | Org chart & teams (M0.5 write path / M4 chart) | `POST`/`PUT`/`DELETE /teams` + membership, orphan-and-cycle surfacing, delete refusing on live grants and child teams, `V21` case-insensitive name uniqueness (§71) | Live workload, drill-down to team and person views, team leads as a managed attribute, `provision-company` deliberately seeds none | ~40% ~~100%~~ |
| 27 | Multi-company activation (M6) | Invitation, acceptance, membership removal, session epoch, `issue-invitation`, the project access screen (§63) | Company switcher, `/c/{slug}` routing, session switching, `COMPANY`-scope assignment UI, per-company IdP + email-domain routing, per-company branding, `CompanyMembership.rename()` **still has no caller** (outstanding since §55) | ~60% ~~65%~~ |
| 28 | Hardening & release (M5) | Hetzner box live, runbook executed and corrected, GHCR image, privilege split verified in production | Perf pass, security review, backup/restore procedure, install guide, **a Linux CI gate that runs the tests**, **the two unrun verification queues** (§5.2), and — new with §78 — **a Helm chart before v1.0, ordered after the S3 adapter** | ~65% ~~60%~~ |

### 2.3 Complete

Tenancy + RLS + the isolation harness · identity, roles, permissions, the evaluator **incl. the
`OWN`/plan-scope qualifiers on the wire and at the row (§65, §66)** · **team management (§71)** ·
project CRUD and project access (§63) · suite tree · case CRUD, versions, timelines, health signals ·
search, facets, shareable filters · repository dashboard · plans, runs, executions, results,
configurations · **the four lifecycle ladders — run start/close/reopen (§72), plan (§73), suite (§75),
case (§62 + §75's rescind fix)** · **the plan editor: scope, entry/exit criteria and the optimistic
lock that could not fail (§74)** · plan statistics · documentation space · artifact register · bulk
operations · the resilience layer (boundaries, retry, session recovery, version skew) · deployment.

**M0.5 is now complete** — teams were its last unbuilt deliverable, and round 1's 95% was carrying
exactly that gap.

**The lifecycle block is the largest single correction between round 1 and here, and it is not a
feature count.** Four status columns had exactly one writer each — the value their factory method
wrote — so on every real instance every run read *Not started*, every plan and every suite read
*Draft*, and three of the four ladders in §6g were unreachable. Round 1 counted all four features as
built, because their schema, their enums, their gates and their screens were. **A status column with
one writer is the shape this assessment is least able to see**, and §72–§75 are what closed it.

---

## 3. Percentage remaining

Weighted by the plan's own milestone estimates (midpoints of its ranges).

| Milestone | Weeks | Complete (r1, §62) | Draft (§71) | **Complete (§77)** | Weeks left |
|---|---:|---:|---:|---:|---:|
| M0 — Foundation | 2.5 | 100% | 100% | 100% | 0.00 |
| M0.5 — Identity & access | 2.0 | 95% | 100% | 100% | 0.00 |
| M1 — Test repository | 4.5 | 75% | 85% | **88%** | 0.54 |
| M1.5 — Reuse & review | 3.0 | 45% | 65% | **85%** | 0.45 |
| M2 — Execution | 5.5 | 65% | 80% | **90%** | 0.55 |
| M2.5 — Docs, artifacts & reporting | 7.5 | 50% | 50% | 50% | 3.75 |
| M3 — Automation ingestion | 2.5 | 0% | 0% | 0% | 2.50 |
| M4 — Integrations & workflow | 9.0 | 12% | 18% | 18% | 7.38 |
| M5 — Hardening & release | 2.0 | 40% | 40% | **35%** | 1.30 |
| M6 — Multi-company activation | 1.75 | 35% | 40% | 40% | 1.05 |
| **Total** | **40.25** | | | | **17.52** |

**M5 went backwards, and that is not an error.** §78 put a Helm chart in scope before v1.0 and the
two verification queues have grown by six slices since the draft — the milestone gained work faster
than it did work. A completion figure that can only rise is a figure that is not being derived.

### The headline numbers

- **~44% of the full plan remains** — 17.5 of 40.25 weeks (**43.5%**; 46.5% at the §71 draft, 53.0%
  in round 1).
- **~43% remains to M5**, which the plan calls a legitimate release point: *"a single-company v1.0
  can ship and be used in production while M6 is built"* (16.5 of 38.5 weeks, **42.8%**).
- **~31% remains to a core TCMS without the M4 integrations block** (9.1 of 29.5 weeks, **30.8%**).

Fifteen slices have moved the total by **3.8 weeks** of the plan's own weighting; the last six moved
it **1.2**. That is the honest rate: each slice is a fraction of a plan-week, and the slices that
closed dead schema or an unwritten column (§64, §67, §71, §72–§76) moved it more than the slices that
deepened working features (§65, §66, §68, §69).

### Where the remaining weight sits

Unchanged in shape, and now sharply concentrated. **M4 alone is 9 weeks and is ~18% done** — still
the largest single block by a wide margin, and the four blocks inside it that have no code at all
(SSO, issue trackers, notifications, requirements matrix) are ~7 of those weeks. The second largest
is **reporting, half of M2.5's 7.5 weeks, still sitting at zero** — it is now the only remaining
*module-sized* hole in the pre-M4 product, and the source of the only two contract paths without a
controller.

**Those two blocks are now 11 of the 17.5 weeks left.** Everything else — M1, M1.5 and M2 together —
is 1.5 weeks of finishing work. The pre-M4, pre-reporting product is essentially built and
essentially unverified, which is why §5.2 matters more this round than last.

The near-term cheap wins are elsewhere and there are fewer of them than at the §71 draft, because
§73 took one: `artifact:delete` and `run:execute` sit on features that already exist.

---

## 4. Three caveats

1. **The per-milestone completion figures are judgment**, made against each milestone's own
   deliverable list in the plan's table. The evidence for each is §2 above; the week weights are the
   plan's, not this document's.
2. **The remaining weeks are less risky than the elapsed ones.** Tenancy, RLS, the authorization
   model, the contract discipline, the resilience layer and a backend harness of **1443 `@Test`
   methods** (plus **402** frontend `it`/`test` declarations) are done, so most of what is left is
   *additive* rather than structural. That lowers the risk per week; it does not lower the count.
   Both figures are `grep`-derived at `98d19aa` and are **not** the same measure as a suite run —
   `it.each` and parameterized cases expand — so they are comparable to each other over time and not
   to a runner's output.
3. **New this round: a completion percentage counts what was built, not what a column can hold.**
   §72–§75 are the correction, and the check that would have caught them earlier is cheap —
   *for each status/enum column, how many places write it?* One writer means the feature's ladder
   does not exist, however finished its schema and its screens look. That check is not yet in §1.

---

## 5. Standing risks this assessment surfaces

### 5.1 No machine anywhere runs the backend test suite

CI has run `./gradlew build -x test` since §59, and `./gradlew build` on a developer's own macOS
machine is the only gate — which structurally cannot observe a Linux-only defect (§58, §60). Every
one of the 17.5 remaining weeks lands on that footing.
`docs/plans/2026-08-01-tcms-homelab-target.md` §9 is the standing proposal.

### 5.2 New this round: the deferred-verification queues have only grown

§65 made browser passes and backend mutation checks deferred-and-batched, at the user's direction.
The policy is working as designed on the *recording* half and has not yet been paid on the
*executing* half:

- `docs/verification/backend-mutation-checks.md` — **13 slices queued** (§65–§69, §71–§77),
  ~~6 at the §71 draft~~, **0 drained**.
- `docs/verification/frontend-browser-checks.md` — **13 slices queued** (§65–§77), ~~7 at the §71
  draft~~, **0 drained**.

**Both queues roughly doubled in one day.** §72–§76 also ran a handful of mutations inline and
recorded them as `RUN`, which the user corrected the same day: the policy defers *all* of them, and
the exception had started to look like the rule. Both queue headers and `CLAUDE.md` now say **write
the row, run nothing** — so the queues will keep growing at one slice per slice until they are
drained deliberately.

By the working agreement's own words, every slice in an unrun queue is **built and unverified**.
That now covers §64's shared-step library, §67's evidence path, §68/§69's classification work, §71's
team management **and all four lifecycle ladders plus the review record and its settings** — which
is most of what §2.3 gained this round, and most of the 1.2 weeks the total moved. The percentages
above count them as built, because they are; they do not count them as verified, because nothing has.

Draining both queues is unestimated work that belongs to M5 and is not in the 17.5 weeks.
