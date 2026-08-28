# The nine renders, grounded against the backend

**Status:** analysis only, nothing implemented · **Date:** 2026-08-28
**Subject:** [`docs/specs/assets/renders/`](assets/renders/) — nine family renders, 42 screens
**Written rules:** [`2026-08-28-design-language.md`](2026-08-28-design-language.md)
**Decided in:** [`docs/plans/005-renders-backend-support.md`](../plans/005-renders-backend-support.md)
— this document is the analysis; 005 is what gets built (~~11 build · 5 defer · 13 drop~~ →
**17 build · 6 defer · 14 drop**, 005 revision 2, splits counted separately). Where the
two disagree, **005 wins**: it checked the entities and the permission catalogue, and it corrected
five things here, each struck through in place below rather than deleted.

**Excluded:** case authoring. Already grounded and costed in
[`docs/plans/003-case-authoring-redesign.md`](../plans/003-case-authoring-redesign.md); nothing here
restates it or contradicts it.

Every claim below was checked against `docs/specs/openapi.yaml`, the controller surface under
`backend/src/main/java/com/tcms/`, and the derivation code — not against memory of them. Where a
render and the contract agree, that is recorded too, because the expensive mistake on a document
this size is rebuilding something that already ships.

---

## The finding in one paragraph

**The backend already carries most of these forty-two screens.** Repeatedly the render arrived
independently at a shape the contract had already argued itself into — the org chart's three grant
arms, per-paragraph documentation coverage, deleted runs keeping their row, the version that ran
being named on every read surface, import never deleting. What is missing clusters into three
piles of very different cost: **ten field additions to shapes that already exist**, **seventeen
new derivations or endpoints**, and **two places where the render contradicts a standing backend
decision** rather than merely outrunning it.

~~Only one change needs a Flyway number.~~ → **corrected 2026-08-28 by plan 005**, which checked
this pile against the entities rather than against the contract: ~~**five**~~ **four** do (N8 plus
three §3 rows — the audit-IP row was dropped outright in 005 Q4), of which 005 ships **one**. See §3's header and
N8. The counts in this paragraph were wrong in the same direction and for the same reason — §7's
table had them right while the sentence a reader skims did not.

---

## 1. Two conflicts — decisions, not gaps

These are the two items where building the render as drawn means *reversing something the backend
decided on purpose*. Both need an answer before the family they belong to is built.

### C1 — The "Refused" sixth state has no machine-readable shape

`09-states.html` draws a sixth data-region state: *"Not shown to you — reading the audit trail
needs `audit:view`. M. Kalcheva and R. Dimitrov can grant it."* Neutral colour, no retry. As a
design decision it is right, and it is the render's own best idea: refused is not a failure.

`Problem` (`openapi.yaml:8049`) is `{type, title, status, detail, errors}`. There is **no
permission member**. The 403 response's own description says the body "usually has something
actionable to say — which permission, and at what scope" — but it says it in `detail`, as prose a
client would have to parse. And nothing anywhere answers *who can grant it*.

Two additions, and each is a decision:

1. **A `permission` (and scope) member on the 403 body.** `Problem` is pinned in both directions
   by `ErrorBodyPolicyTest.theContractsProblemIsThisRecord`, so this is a contract change plus a
   record component plus the two envelope writers — mechanically cheap, and the test means it
   cannot be half-done.
2. **A "who can grant this here" read.** New. Naming holders is consistent with policy already
   stated in this contract — `OrgChartPerson.companyRoles` argues that "a company's administrators
   are not a secret from its members" — but it is a disclosure decision and should be written down
   as one rather than inherited by analogy.

**The state's reach is narrower than the render implies.** A read the caller holds no grant for
answers a **bodiless 404**, by §6t and `ErrorBodyPolicyTest` — not a 403. So Refused can only ever
render *after* the read gate has passed. The render's own example is the wrong kind: see C2.

### C2 — "A reader without the permission still sees the screen, read-only" is false on four of nine settings screens

`07-settings-admin.html` states two governing rules for the family, and the second is that a reader
without the permission still sees the page in read-only with a sentence naming who to ask —
"because the rail does not hide these entries, so the page must not be a dead end for whoever
follows the link."

Checked against every settings read:

| Screen | GET gate | Read-only view possible today |
|---|---|---|
| Review (`/projects/{id}/settings`) | ungated read | **yes** |
| Classification (`/projects/{id}/classification`) | ungated read | **yes** |
| Teams, Roles, Role assignments, Users | ungated read | **yes** |
| Project access (`/projects/{id}/members`) | 403 | no |
| Notifications (`/projects/{id}/notification-channels`) | `integration:manage` | no |
| API tokens (`/api-tokens`) | `token:issue` | no |
| Single sign-on (`/identity-providers`) | `company:idp` | no |
| Audit trail (`/audit-events`) | `audit:view` | no — **and correctly so** |

The pattern is sharper than "four screens are gated": **three of the four are gated on the write
key.** `company:idp`, `token:issue` and `integration:manage` each govern *changing* the thing, and
reading it is gated on the same key. So the render's SSO frame — which draws the full provider
list and the claimed email domains under a "Read-only for you" banner to a caller who lacks the
permission — is not a screen this API can serve at all.

Three ways out, and this document does not pick one:

- **Split each key into a read and a write half.** Honest, and it is three new permissions in the
  catalogue plus the roles spec §A3 matrix plus both convenience copies.
- **Keep the gate and drop the rule for those four screens**, letting the refusal be the page.
  Cheapest, and it makes family 7's second governing rule conditional rather than governing.
- **Keep the gate and un-list the rail entry**, which is what the audit trail already does
  deliberately — and which the render endorses for exactly one screen.

**The audit trail is the one that is already right.** The render says it "is the one rail entry
that is *hidden* without its permission, and deliberately so", and `GET /audit-events` is gated on
`audit:view` at company scope with the reasoning written out. Render and contract agree.

---

## 2. New backend work — a derivation or an endpoint, not a field

| # | Render | What it draws | Where it stands |
|---|---|---|---|
| **N1** | 01 · Plan list | `Pass rate`, `Coverage` | **The render is already honest about this** and calls it "the one open backend follow-on". `TestPlanSummary.passRate` / `.coverage` (`openapi.yaml:10958`) are declared nullable with *"Null until a writer exists."* `PlanStatistics` computes both per plan; the list needs a batched writer. |
| **N2** | 01 · Plan list | `Exit criteria — 2 of 3` | A third column with the same problem. `Criterion.met` is computed inside `PlanStatistics` only; `TestPlanSummary` carries no criteria at all. |
| **N3** | 06 · Dashboard | **By feature**, "approved cases, and how their last run went" | `DashboardBreakdown` (`openapi.yaml:12580`) segments are keyed by `LifecycleStatus` — authoring status. The render splits by last-run result (Passed / Failed / Blocked / Never run). A different breakdown, not a relabel. |
| **N4** | 06 · Dashboard | Activity → **Archived 31** | `recentActivity` (`openapi.yaml:12533`) is `{date, created, modified}`, and carries an explicit note on why there is no `approved` series: nothing records *when*. `archived` has the identical problem and would need the same answer. |
| **N5** | 06 · Dashboard | Automation coverage **"+6 pts"** | No historical comparison point exists anywhere in the schema. |
| **N6** | 04 · Org chart | The **Gaps** panel | `OrgChart` has no gaps member, and the client **cannot** compute one: `OrgChartAssignment.roleName` (`openapi.yaml:14305`) is marked *"Display only — nothing may branch on this string; code checks permissions, never role names"*. "Atlas Ledger has no Test Lead → nobody can approve a plan there" is a permission-coverage computation and belongs on the server. The render's own caption — *"a gap is computed from the role catalogue, not typed in"* — is the requirement. |
| **N7** | 07 · Review | The **blast-radius counts** | "116 cases currently in review", "4 draft pages", "7 items with one approval", "Q3 compliance sweep is in draft and would be blocked from running". None are on `ProjectSettings` (`openapi.yaml:8537`). This is the family's headline rule — *"every setting states its blast radius against live data"* — so it is not decoration that can ship later without the screen losing its argument. |
| **N8** | 07 · Review | The **Documentation pages** review toggle | `ProjectSettings` has `reviewEnabledCase` / `Suite` / `Plan` (`openapi.yaml:8554`) and no doc member. ~~The only change here needing a migration.~~ → **corrected by plan 005: this is the most expensive item in the document, not the cheapest.** `project/ReviewSubjectType.java` is a closed enum whose every member carries its own permission key (`CASE`→`case:review`, `SUITE`→`suite:review`, `PLAN`→`plan:review`), so a fourth subject type needs a new catalogue key, roles spec §A3, and **both** convenience copies — which `packages/client/src/test/matrixCopies.test.ts` pins. A boolean column was never the shape of this. |
| **N9** | 01 · Projects | `Automated 64%`, `Last run 01:17` | `Project` (`openapi.yaml:8878`) carries `caseCount` and `myRole` only. |
| **N10** | 08 · Sign in | *"2 attempts left before this address is locked for 15 minutes"* | Nothing exists. `tcms.rate-limit` is **per-token request throttling**, not per-address auth lockout, and `POST /auth/login`'s single 401 is designed to distinguish nothing at all. A counted lockout is a **deliberate partial reversal** of that concealment rule on the one surface a stranger can reach — a decision, not a counter. |
| **N11** | 08 · Sign in | *"Forgot your password?"* | No endpoint. |
| **N12** | 07 · Account security | The entire page | No `/me/password`, no session list, no sign-out-others. The `/me` surface is `/me`, `/me/permissions`, `/me/social-identities` and nothing else. |
| **N13** | 08 · Accept invitation | Inviter, role, projects, expiry | `InvitationPreview` (`openapi.yaml:8798`) is `{email, displayName, passwordRequired}`. Four additions **on the one unauthenticated surface**, so each is a disclosure decision on the same terms as that endpoint's standing rule that *every* failure is a bodiless 404 lest it become an oracle for which addresses exist. |
| **N14** | 05 · Feature import | The **per-step diff** | `ImportRow` (`openapi.yaml:9983`) gives `action`, `reason`, `caseKey`. The render draws struck-through old step text beside the new, per changed step, and names that the panel's entire point: *"The import shows the diff, not the count."* A new payload on the PREVIEW response. |
| **N15** | 01 · CI ingestion | **Re-apply** | No endpoint. Raw payload ✓ (`/ingestions/{jobId}/report`) and binding an unmatched test ✓ (`/automation-mappings`) both exist. |
| **N16** | 02 · Proposal | *"Nearest existing cases, by step overlap"* | No such read. |
| **N17** | 09 · Not found | *"The highest case key in this project is TC-1284"* | Needs a read. Within §6t: it is a bound on a project the caller can already open, not an existence claim about one they cannot. |

### On N12 — the contract already points at an action it cannot perform

`DELETE /me/social-identities/{provider}` refuses unlinking the account's last way in with the
sentence *"Set a password or link another provider first."* There is no set-password endpoint.

**Half a defect, corrected 2026-08-28 by plan 005**: the refusal names *two* remedies and the
second one exists (`POST /me/social-identities/{provider}`), so a caller is not left with no way
out — they are left with one of the two they were offered. Worth fixing, and it does not on its own
carry the argument for building this page, which is how §7 below used it.

And the two halves of the render's session panel cost very differently. `/auth/logout`'s own
description says the session token is stateless with nothing server-side to invalidate, naming the
`pv` epoch as the real revocation mechanism — so **"Sign out others" is buildable today** by
bumping it. **"4 sessions · this one, Sofia, since 08:41" is not**: nothing records sessions.

---

## 3. Field additions to shapes that already exist

~~Cheap — a contract change, both generated copies, a view component. No migration except where
noted.~~ → **corrected 2026-08-28 by plan 005.** This pile was assembled by reading the *contract*,
so "the field is not on the wire" was recorded without asking whether it is in the *database*. It
is not, for four of the ten: `RunSchedule` has no paused columns, `ApiToken` has no revocation
reason and a scalar `project_id`, and `AuditEvent` has no IP column. Those four are a migration
each. One row runs the other way — see `grantedBy` below. **The pile presented here as the cheapest
is not uniformly cheap, and the row-by-row cost is in plan 005, not here.**

| Render | Wants | Has |
|---|---|---|
| 01 · Run list | *"started by M. Kalcheva"* | **`TestRun` (`openapi.yaml:11690`) has no `createdBy` on the wire.** `source` ∈ MANUAL/INGESTED/SCHEDULED ✓, and `RunSchedule` already documents the null-createdBy case for a scheduled run — so the field is expected to exist behind the wire and is simply undeclared. |
| 01 · Run list | *"ingested from `junit` · GitHub Actions #4471"* | The format and the CI job live on `IngestionJob`; there is **no back-link from `TestRun` to the job**. `buildNumber` / `commitSha` / `branch` exist; no CI job URL. |
| 01 · Run schedules | *"Paused 8 days ago by A. Ivanov"* | `RunSchedule` (`openapi.yaml:11629`) has `enabled` as a bare boolean. No `pausedAt`, `pausedBy` or reason. `lastFireError` is a different fact and must not be conscripted for this. |
| 01 · Case list | *"Last run failed on `nightly-eu` · 2 defects linked"* | `TestCaseSummary.lastResult` (`openapi.yaml:9241`) is `{statusKind, executedAt, releaseVersion}` — no configuration name, no defect count. |
| 07 · API tokens | *"Where it works: Reflex Payments, Halo"* | `ApiToken` (`openapi.yaml:13794`) has a single nullable `projectId`. |
| 07 · API tokens | *"Reason recorded: 'laptop returned'"* | No revocation reason. `revokedAt` ✓. |
| 07 · Audit trail | *"from 10.4.2.19"* | `AuditEvent` (`openapi.yaml:8145`) has no IP. Actor, token, target, `changes` and `bulkOperationId` all ✓. |
| 07 · Access & roles | The **Granted by** column | `RoleAssignment` (`openapi.yaml:14589`) has `grantedAt`, no `grantedBy` — **on the wire only.** The column is already stored and already written (`identity/RoleAssignment.java:69`, `granted_by`), which this row missed by reading the contract and not the entity. Declaring it is the whole change: no migration, no new derivation. |
| 01 · Reports | The **Size** column | `ReportJob` (`openapi.yaml:12798`) has no size. Joinable from `Artifact.sizeBytes`, so possibly client-side. |
| 09 · Help | *"Filed 94 · Added 32 · Tagged 20"* | `Suite` (`openapi.yaml:9048`) gives `caseCount`, `tagRules` and `caseIds`. **`added` is derivable** from `caseIds`; filed and tagged are not. |

---

## 4. Where the render and the backend already agree

Recorded so nobody builds these twice. In several the render reasoned its way to the contract's own
argument without having read it.

- **The org chart's three fills.** `OrgChartAssignment.via` ∈ `DIRECT | TEAM | INHERITED`, with a
  Company Admin producing **no cell at all** and appearing on the person instead. The render's
  legend and its caption — *"authority over the company is not a default for its projects"* — are
  the contract's own words, including the note that a matrix built the obvious way draws direct and
  team grants identically and omits company-wide ones entirely.
- **Every read screen names the version that ran.** `TestExecutionSummary.caseVersionNo`,
  `ExecutionHistoryEntry.caseVersionNo`, `SuiteRunSummary.suiteVersionNo`.
- **A deleted run keeps its row.** `runDeleted` on both history shapes, with the reasoning already
  written down.
- **Per-paragraph documentation coverage.** `DocBlock.coverage` (`openapi.yaml:12960`) is
  `{linkedCases, passed, failed, notRun, caseKeys}` — coverage per *block*, which is exactly the
  render's "the chip sits under the clause it judges". *"Linked spec changed"* is the
  `OUTDATED_SPEC_CHANGED` health signal plus `DocLink.stale`.
- **Import never deletes, and says what it will do.** `FeatureImportPlan` carries PREVIEW/APPLY,
  `orphans`, and `ImportTotals` as one count per action — the render's button copy
  (*"Import 14 scenarios — creates 9, updates 3, leaves 2 alone"*) is served.
- **A plan is a scope decision plus its evidence.** `scopeText` / `outOfScopeText` hold prose ✓;
  `Criterion` is machine-evaluable ✓; `PlanStatistics.trend[].passRate` is documented as a
  percentage 0-100 with one decimal ✓; `bySuite` / `byConfiguration` ✓; `notRun` ✓.
- **Cross-project search is bounded by the caller's own grants**, with `total` computed over the
  same narrowed set, and a member holding no grants getting `200` with `total: 0` rather than a
  403. The render's caption says this almost verbatim. The **one** part not served is *"4 of 9 in
  this company"* — the endpoint deliberately refuses to state a company's portfolio size, so that
  sentence has to go or become "4 projects".
- **An em dash means no value, never zero.** `TestCaseSummary.lastResult` is nullable, and the
  field carries the incident where every case in the product read "Never run".
- **Failed is never mistakable for empty, and stale is labelled.** `PlanStatistics.stale` **and**
  `computedAt` as two separate fields, `RepositoryDashboard.healthComputedAt`,
  `SuiteHistoryResponse.computedAt` — all exist precisely to draw the distinction family 9 is about.
- **Filter chips with live counts.** `Facet` (`openapi.yaml:8244`) is served alongside results
  "so filter counts render without a second request", always present, empty when the page is.
- **The board.** Five columns including `BLOCKED` as a real column ✓, `WorkItem.overdue` ✓,
  `allowedTransitions` ✓, and `/work-items` crossing projects for **My work** ✓.
- **The notification delivery log.** `attempt` and `error` per row ✓. The render's escalation banner
  (*"nobody has been told about a plan approval since Tuesday"*) is a client derivation over it.
- **Empty vocabularies.** `ClassificationDimension.values` — the render's Classification screen and
  the case editor's rail are two views of the same emptiness, which is what makes the identical
  sentence in both places honest.

---

## 5. Copy that will lie unless it is checked

The design-language spec's open problem 4 says the health-signal copy in the renders is invented
and needs checking. It is right, and grounding found two instances already wrong.

- **"Not run in 90 days" is configurable.** `LONG_UNEXECUTED` fires on
  `tcms.case-health.long-unexecuted`, a `Duration` property. Hard-coding 90 into UI copy makes the
  dashboard lie on any instance that tunes it — and `CaseHealthProperties` refuses to start on a
  non-positive value specifically because a wrong window reads as a data catastrophe rather than as
  a misconfiguration.
- **"Flaky · 25% — two of the last five runs disagreed on the same version"** is close to the real
  definition but not it. `CaseFlake` is `alternations ÷ transitions`, where transitions are adjacent
  outcome pairs **inside one `(configuration, case version)` cell**, and blocked/skipped/retest
  results are removed *before* the sequence is formed. Critically, `flakeScore` is **null below an
  evidence floor**, which is a different claim from `0` — the contract spells out that a client must
  render that as "3 results, not enough to score" and not as a blank. The render's *"Flakiness"*
  panel has no such state.
- **"Disagreed with themselves in 3 of the last 5 runs"** for `FAILING_REPEATEDLY` is unverified —
  that signal is derived in `reporting.ExecutionSource`, not in `CaseHealthRecomputeService`, and
  its actual predicate should be read before the sentence ships.

---

## 6. Two documentation corrections found while grounding

Not design changes. Recorded here because both are the kind of stale sentence that sends the next
reader after the wrong defect.

1. **`RepositoryDashboard.healthComputedAt`'s comment is stale** (`openapi.yaml:12504`). It says
   *"`case_health` is a read model with no writer yet (plan §6o), so this is null in every
   deployment today."* It is not: `repository.CaseHealthRecomputeService` derives ten of the eleven
   `HealthSignal` members and `reporting.ExecutionSource` derives the eleventh
   (`FAILING_REPEATEDLY`), which that service's own header states. The distinction the field draws —
   *"nothing is wrong"* against *"nobody has looked"* — survives; the claim about today's
   deployments does not. Strike it per the working agreement on superseded decisions.
2. **The `/me/social-identities` refusal names a remedy that does not exist** — see N12.

---

## 7. Cost, and what to sequence first

| Pile | Count | Shape of the work |
|---|---|---|
| Contract-only field additions (§3) | 10 | `openapi.yaml`, **both** generated copies, a view component each. ~~No migration.~~ → ~~**four of the ten need one**~~ **three of the ten need one** (audit IP dropped, 005 Q4) — and 005 drops all three, so none ships; one needs only the declaration (`grantedBy`). |
| New derivations / endpoints (§2) | 17 | Service work; four are read models. |
| Flyway migrations | ~~**1**~~ ~~**5**~~ → **1** | ~~N8 plus the four §3 rows that need columns.~~ → **`V71`**, N8's column, landed inert in 005 S3; every §3 row needing a column is dropped. ~~`V71` or later~~ ~~`V75` or later: plan 003 holds `V71` and plan 004 holds `V72`–`V74`~~ → grounded 2026-08-28: `V70` is the `gherkin_source` deleter, **005 holds `V71`, 003 holds no number, 004 holds `V72`–`V74`** (005 §8.2). |
| Policy decisions (§1) | 2 | 403 body + grantor lookup; the settings read-gate rule. Both block a whole family. |
| Subsystems that do not exist | 1 | Session records, for Account security (N12). |

**Two of these gate a whole family and should be answered first**, because building the family
around a rule the backend refuses is the expensive order:

- **C2 before family 7 is built.** Four of nine settings screens cannot render the read-only view
  the family's second governing rule requires.
- **C1 before family 9 is built.** The Refused state is drawn on all 42 screens by inheritance, and
  it needs a permission on the wire to render at all.

**One item is a live defect rather than an unbuilt screen** and is independent of every render:
the social-identity refusal pointing at a set-password endpoint that does not exist — though it
offers a second remedy that does, so it is a narrowed dead end rather than a total one.

## What this document does not claim

Nothing was built and nothing was run. This is a comparison of nine drawings against a contract and
the code behind it — it does not say the drawings are right, and where a render and the contract
agree that is agreement about a *shape*, not evidence that the screen works at real content length.
The renders themselves have not been driven in a browser, which their own index says out loud.
