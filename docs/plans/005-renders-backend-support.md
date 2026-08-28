# 005 — Backend support for the nine renders

**Status:** proposed · nothing implemented, nothing run · **Date:** 2026-08-28 · **Revision 2**
**Initiative number:** `005-`. The brief said `004-`; `docs/plans/004-issue-tracker-integration.md`
already holds it.
**Position: 005 goes first.** It takes `openapi.yaml` ahead of
[`003`](003-case-authoring-redesign.md) and [`004`](004-issue-tracker-integration.md), and both
rebase onto it. Revision 1 assumed the opposite; §8 is rewritten and ~~**003 and 004 must be
amended** — see §8.2 for the exact edits~~ → **003 and 004 were amended 2026-08-28**; §8.2 records
what changed and why it is not what revision 2 first prescribed.
**Migrations:** this plan claims **`V71`, and nothing else**. `V70` stays reserved (§255
`gherkin_source` deleter, `002-`). Disk high-water is `V69`. ~~Under the new position 003 moves to
`V72` and 004 to `V73`–`V75` — a uniform **+1** shift, §8.2.~~ → **No shift. 003 released its claim
(its only migration is in a blocked phase — §8.3's own hole); 004 keeps `V72`–`V74`.** §8.2.
**Input:** [`docs/specs/2026-08-28-renders-backend-grounding.md`](../specs/2026-08-28-renders-backend-grounding.md)
— verified, not trusted. Its five errors have since been **corrected in place** by the coordinator,
each struck through with `→ corrected by plan 005`; that document now names this one as deciding
where the two disagree. §1 is kept as the record of what was found and why, not as a live dispute.
**Excluded:** case authoring (003). Nothing here restates or contradicts it.

---

## 0. The decision in one paragraph

Across the grounding document's 34 findings this plan takes **37 discrete decisions** — splits
counted separately, since three findings resolve differently in their halves — and they fall
**17 build · 6 defer · 14 drop**. The majority of the drops are answered by changing the *drawing*
rather than the backend: fourteen design changes against seventeen build items, and the design
changes are the cheaper half by a wide margin. The document's costing is inverted at both ends: the
item it called the only one needing a migration (**N8**, documentation review) is the **most**
expensive thing in it, because review subjects are a closed enum bound to permission keys; while the
item listed last in a table of ten (**`RoleAssignment.grantedBy`**) is **free** — the column already
exists and only the wire is missing. The architectural constraint the brief predicted does shape the
plan, but not by blocking: `project` is a foundation module and cannot see `documentation` or
`execution`, so the two screens needing counts across that edge (**N7**, **N8**) are **SPI extensions
in the shape of `ReviewSubjectDirectory`**, not queries. That reframing, and the sequencing
consequences of going first (§8), are the plan's main content.

---

## 1. Where the grounding document was wrong

It is a good document. Roughly a dozen load-bearing claims were re-read against the contract and the
code, and **every gate, schema shape and absent field it asserts is accurate**. Five things were not.
All five are now corrected in place in that document; this section is the record of the finding.

### 1.1 The summary paragraph miscounted its own piles

*"fifteen field additions … fifteen new derivations"* — §2 has **17** rows and §3 has **10**. §7's
cost table had it right. The paragraph a reader skims was the one that was wrong.

### 1.2 §3 was not migration-free, and its header said it was

> *"Cheap — a contract change, both generated copies, a view component. No migration except where noted."*

Verified against the entities, **three** of the ten need new stored columns — down from four, because
the audit-IP row is now dropped outright (Q4, §6):

| §3 row | Entity | Verdict |
|---|---|---|
| `RunSchedule` "Paused 8 days ago by A. Ivanov" | `execution/RunSchedule.java` — `enabled` at :103, no paused columns | needs a migration |
| `ApiToken` revocation reason | `identity/ApiToken.java:71-123` — no reason column | needs a migration |
| `ApiToken` multi-project | `identity/ApiToken.java:96` — scalar `projectId` | needs a migration **and** a join table |

None was noted. **All three are dropped** below, partly for this reason — so no §3 row survives into
a migration, and `V71` is this plan's only one.

### 1.3 `RoleAssignment.grantedBy` is already stored — the row is free

§3 said *"`RoleAssignment` has `grantedAt`, no `grantedBy`"*. True **of the wire only**.
`identity/RoleAssignment.java:69` is `private UUID grantedBy;`. A pure contract addition over data
already being written — the best cost-to-value ratio in the document, listed as if it were the same
kind of thing as multi-project tokens.

### 1.4 N8 was the most expensive item, not the cheapest

§2 called `review_enabled_doc` *"the only change here needing a migration"*, framing the migration as
the cost. It is the smallest part. `project/ReviewSubjectType.java:30-42` is a closed enum whose
three members each **carry a permission key**:

```
CASE("case:review"), SUITE("suite:review"), PLAN("plan:review")
```

A fourth needs a **56th catalogue key** (`doc:review`) — the roles spec §A3 plus
`permission-matrix-summary.md` plus `README.md`, both copies pinned by
`packages/client/src/test/matrixCopies.test.ts` — plus a `ReviewSubjectDirectory` implementation in
`documentation`, plus the `V71` column, plus the review apparatus working for a subject type that has
never had one. A slice, not a field.

### 1.5 The "live defect" was half a defect

§6.2 and N12 said the `/me/social-identities` refusal *"names a remedy the API does not offer"*. The
sentence at `openapi.yaml:508` is *"Set a password or link another provider first."* It names **two**
remedies and the second exists — `POST /me/social-identities/{provider}`, in the same path item. A
copy defect with one impossible half, not a dead end.

---

## 2. C1 — the Refused state and the 403 body

**Decision: build the first half, drop the second.**

### C1a — `permission` and `scope` on `Problem` → **BUILD** (slice S1)

Verified: `Problem` at `openapi.yaml:8049` is `required: [type, title, status, detail]` with `errors`
the sole optional member. No permission member; the 403's actionable content is prose in `detail`.

Right to build, and **now first in the sequence** — see §8.3. It is small, pinned in both directions
by `ErrorBodyPolicyTest.theContractsProblemIsThisRecord`, and family 9 draws Refused on all 42
screens by inheritance.

**A cost the grounding document did not name.** That schema's comment argues across three superseded
revisions (§162 → §163 → §164) that **`errors` is the one optional member**, and
`bothEnvelopesSerializeTheSameKeys` checks it. Adding two optional members reverses a claim the file
spends thirty lines defending. Per the working agreement the old reasoning is struck through and
kept. Budget the comment, not just the field.

**A third member, added by grounding against 003 (2026-08-28): `line`.** Nullable integer, set
only by the four gherkin refusals (`CaseController.java:264` — `gherkin-syntax`, `-outline`,
`-many-scenarios`, `-empty`), which today name the line only inside `detail`'s prose. 003 C4 makes
the refusal *"the whole product"* and needs it structured to anchor the sentence to a line. It lands
here rather than in 003 Phase 2 because this slice is the one reopening the §162–§164 argument, and
that argument should be reopened once, not twice. Every other refusal leaves it null.

**Scope:** `openapi.yaml`, `com.tcms.api.Problem` record components (`permission`, `scope`,
`line`), both envelope writers, `ErrorBodyPolicyTest` expectations, both generated copies. Gate: all
three stacks.

### C1b — a "who can grant this here" read → **DROP**

A new endpoint plus a disclosure decision, for one sentence.

1. **Its reach is tiny.** The grounding document establishes this itself and then does not apply it:
   a read the caller holds no grant for answers a **bodiless 404**, not a 403. Refused renders only
   *after* the read gate has passed.
2. **The disclosure is already made elsewhere.** `OrgChartPerson.companyRoles` argues that a
   company's administrators are not a secret from its members, and the org chart serves them.
3. **Cost is a whole endpoint** with the same permission-coverage computation as N6, but per-refusal
   and on the hot path.

**Design change instead:** Refused names the permission (from C1a) and says *"Ask a Company Admin"*,
linking the org chart. No named individuals.

---

## 3. C2 — read-only settings screens · **DECIDED**

**Q1 answered: accepted.** This is a decision taken, not a recommendation pending.

**Un-list the three credential rail entries** — API tokens, Single sign-on, Notifications — for
callers without the key, exactly as Audit trail already does and as the render already endorses for
that one screen. **Family 7's second governing rule becomes conditional:**

> *"A rail entry the caller can see leads to a page they can read."*

**No key splitting. No backend cost.** The design spec carries this change.

**Why not split the keys.** The read gate is not an oversight. `identity/ApiTokenService.java:47-52`
carries a section header — *"Every gate is `token:issue`, including the read"* — and argues it:

> *"The list is not filtered down to what a caller may see, the way `GET /role-assignments` is: it is
> refused. There is no partial-visibility story for credentials — a token belongs to the company
> rather than to a project — so an empty array would be a claim that this company has no tokens,
> told to somebody who simply may not ask."*

Splitting `token:issue` reverses that. And a read-only SSO page discloses issuer URLs, client IDs and
claimed email domains — reconnaissance for whoever is trying to get in;
`IdentityProviderService.java:30-42` already treats these as the strictest credentials in the module.

**Cost of the rejected option, for the record:** three new catalogue keys, §A3, two convenience copies
pinned by `matrixCopies.test.ts`, three service-layer gate splits, and a reversal of an argued
decision needing the struck-through-and-kept treatment — all to render a page listing things the
reader may not touch.

### The fourth screen is different, and it exposes an inconsistency

**Project access** (`/projects/{id}/members`, 403) is not a credential. Who is on a project is not a
secret from the company — and **the org chart already discloses it**: `OrgChartAssignment`
(`openapi.yaml:14305`) is `{projectId, roleName, via}` per person, company-wide. The members endpoint
refuses a fact a shipped endpoint serves.

~~**Q2 answered: deferred, and it stays open.** Either a read-gate bug on `/projects/{id}/members` or an
over-disclosure on the org chart; not this initiative's call. **Blocks the Project access screen
only** — do not draw or build it until answered.~~ → **Q2 decided 2026-08-28: the members read is
relaxed.** Who is on a project is not a secret from the company — the org chart already says so — so
`GET /projects/{id}/members` takes `project:read`; every write on the path keeps `project:manage`.
No new key, no migration, one gate line plus its contract description, so it rides in **S1** (the
contract wave). The Project access screen is unblocked; it renders read-only for a caller without
`project:manage`, which is family 7's conditional rule applied correctly this time — the entry is
visible *because* the page is readable.

---

## 4. The architectural finding — N7 and N8 are SPI extensions

**The wall.** `project/package-info.java` declares `allowedDependencies = {"api", "tenancy"}`.
`ProjectSettings` lives in `project`. The Review screen's blast-radius counts (**N7**) span:

| Count | Owning module | Reachable from `project`? |
|---|---|---|
| "116 cases currently in review" | `project` (review rows) | **yes** |
| "7 items with one approval" | `project` | **yes** |
| "4 draft pages" | `documentation` | **no** |
| "Q3 compliance sweep would be blocked from running" | `execution` | **no** |

`documentation` and `execution` both depend on `project`, so the dependency points the wrong way, and
`CompanyScopedEvent` does not help — that seam is asynchronous write propagation, not a synchronous
read.

**The pattern that solves it, already in the codebase.** `project/ReviewSubjectDirectory.java:40` is
an **interface declared in the foundation module and implemented by the leaves that own the tables**
— its own comment says *"interface here, implementations in the modules that own the tables"*.
Implementors today: `repository/CaseReviewSubjects`, `repository/SuiteReviewSubjects`,
`execution/PlanReviewSubjects`, `integration/ReviewSubjects`. Dependency inversion, ArchUnit-clean.

**So N7 and N8 are the same move:** declare an SPI in `project`, implement it in the module owning the
data. More than "a count", and much less than a sixth foundation module — the outcome to avoid, and
the one a naive read of N7 walks into.

A second, weaker precedent exists and must **not** be used here:
`reporting/CoverageMatrixReport.java:509` reads `documentation` through an **opaque id with no foreign
key** (*"`doc_link.source_id` is opaque with no foreign key behind it — `documentation` may not hold a
key into `repository`"*). ArchUnit cannot see a raw-SQL cross-module read, which is exactly why it is
the wrong tool from a foundation module: it would invert the architecture invisibly.

---

## 5. Verdict on every finding

### 5.1 §1 conflicts

| # | Verdict | Reason |
|---|---|---|
| C1a — `permission`/`scope` on 403 | **BUILD** (S1) | Family 9 cannot render Refused without it. Test-pinned, so it cannot half-land. |
| C1b — "who can grant it" read | **DROP** | One sentence, a new endpoint, a disclosure decision; the org chart already serves the answer. |
| C2 — read-only settings rule | **DROP the rule** (design) · **decided, Q1** | The credential read gate is argued in code. Un-list the entry, as Audit trail already does. |
| C2 — Project access specifically | ~~**DEFER** (Q2, open)~~ → **BUILD** (S1) | Q2 decided: the members read relaxes to `project:read`; writes stay `project:manage`. |

### 5.2 §2 — new derivations and endpoints

| # | Verdict | Reason |
|---|---|---|
| **N1** plan list pass rate / coverage | **BUILD** (S5) | Contract already declares them nullable with *"Null until a writer exists"* — the shape is agreed, only the batched writer is missing. |
| **N2** exit criteria "2 of 3" | **BUILD** (S5) | Same computation, same batch, same trip. Splitting it from N1 would be two passes over one dataset. |
| **N3** dashboard by-last-run breakdown | **BUILD** (S8) | A genuinely different segmentation from `LifecycleStatus`, correctly server-side, and `reporting` owns the data cleanly. |
| **N4** Activity → Archived | **DROP** (design) | `recentActivity` already carries a written note on why there is no `approved` series: **nothing records when**. Archived is identical, cannot be backfilled, and a series reading zero for all history is worse than an absent one. |
| **N5** automation coverage "+6 pts" | **DROP** (design) | No historical comparison point exists anywhere in the schema. A time-series subsystem for one delta chip. |
| **N6** org chart Gaps panel | **BUILD** (S6) | Correctly server-side — the client is *forbidden* to branch on `roleName` (`openapi.yaml:14305`). `identity` may depend on `project`, so no architectural cost. |
| **N7** Review blast radius | **BUILD, reduced** (S7) | Review-owned counts only, served from `project`. Doc-page and plan counts need the §4 SPI — **in S9b**, so the screen ships without them first. |
| **N8** documentation review toggle | **BUILD** (S3 + S9b) | Not a field. New enum member + 56th permission key + §A3 + two pinned copies + a `documentation` SPI impl + `V71`. Split: the column lands early and inert (S3), the apparatus late (S9b). See §8.3. |
| **N9** project automated % / last run | **DEFER** | Two derivations on the project *list* — an N+1 risk on the most-hit read in the product. Wants measuring first. |
| **N10** sign-in lockout | **SPLIT — BUILD silent (S10), DROP the counter** (design) | Lockout is a real security improvement independent of any render. *"2 attempts left"* deliberately partially reverses the concealment rule on the one surface a stranger can reach, and tells an attacker how to pace. Lock out; do not count out loud. |
| **N11** forgot password | **DEFER** | A real subsystem (reset tokens, expiry, email delivery). Its own initiative. |
| **N12** account security page | **SPLIT** | Set password → **BUILD** (S9a): closes the impossible half of the `/me/social-identities` refusal. Sign out others → **BUILD** (S9a): the `pv` epoch already exists (`openapi.yaml:93-103`). Session list → **DROP** (design): nothing records sessions, and the token is stateless *by an argued decision*. |
| **N13** InvitationPreview additions | **DROP** (design) | Four disclosures on the **one unauthenticated surface**, whose standing rule is that *every* failure is a bodiless 404 lest it become an oracle for which addresses exist. |
| **N14** import per-step diff | **DEFER** (Q3, unchanged) | Genuinely good and the panel's stated point — but feature import overlaps 003's territory (003's Phase 3 stub flags that its own change *"could make every imported case report as changed"*). Sequence after 003 lands, or it is planned twice. |
| **N15** CI re-apply | **DEFER** | Raw payload and unmatched-test binding both already ship. Convenience, not capability. |
| **N16** "nearest existing cases by step overlap" | **DROP** (design) | A similarity-search subsystem for one advisory panel, in 003's territory. |
| **N17** "the highest case key is TC-1284" | **DROP** (design) | A backend read to make a 404 page chattier. |

### 5.3 §3 — field additions

| Row | Verdict | Reason |
|---|---|---|
| `RoleAssignment.grantedBy` | **BUILD** (~~S2~~ S1) | **Free** — `identity/RoleAssignment.java:69` already stores it. Wire only. |
| `TestRun.createdBy` | **BUILD** (~~S2~~ S1) | Already behind the wire; `source`'s own description names `SCHEDULED` as *"the one value whose run has no `createdBy`"*. Nullable. |
| `TestCaseSummary.lastResult` + configuration name | **BUILD** (~~S2~~ S1) | Already in the join that produces `lastResult`. |
| `TestCaseSummary.lastResult` + defect count | **DROP** | A per-row count on a list read. N+1 for a badge. |
| `Suite` filed / added / tagged counts | **BUILD** (~~S2~~ S1) | The three arms are the suite's definition; `caseCount` alone cannot explain itself. |
| `ReportJob.size` | **BUILD** (~~S2~~ S1) | `Artifact.sizeBytes` exists and is joinable. Serve it rather than making every client join. |
| `TestRun` → ingestion job back-link | **DEFER** | Needs a column or a new join; `buildNumber`/`commitSha`/`branch` already carry most of the sentence. |
| `RunSchedule` pausedAt / pausedBy / reason | **DROP** (design) | A migration for a subtitle. `RunSchedule` already stores `updatedAt` (:148) and `updatedBy` (:151) — draw *"Paused · last changed 8 days ago by A. Ivanov"*, or drop the line. Do **not** conscript `lastFireError`. |
| `ApiToken` multi-project | **DROP** | Migration + join table + model change, to render *"Where it works: Reflex Payments, Halo"*. The scalar `projectId` is the model; changing it is a product decision. |
| `ApiToken` revocation reason | **DROP** | Migration for one line of prose nobody queries. |
| `AuditEvent` source IP | **DROP** (design) · **Q4** | Moved to §6.14. |

### 5.4 §5 — copy that will lie

| Item | Verdict |
|---|---|
| *"Not run in 90 days"* | **Design change** — `tcms.case-health.long-unexecuted` is a configurable `Duration`. Reword to *"Not run recently"*. Serving the threshold means a config-disclosure endpoint for one adverb. |
| Flakiness definition + the null-below-floor state | **Design change** — the render has no state for `flakeScore == null`, which the contract requires be rendered as *"3 results, not enough to score"* and never as blank or `0`. **The render must gain a sixth state.** Zero backend. |
| `FAILING_REPEATEDLY` copy | **Verify before shipping copy** — derived in `reporting/ExecutionSource.java:244`, tuned by `reporting/ReportingProperties.java:28`. Read the predicate; do not paraphrase it. **Read 2026-08-28 in B3 — see below.** |

#### What `FAILING_REPEATEDLY` actually computes — read 2026-08-28 (build batch B3)

Recorded here so B5/B6's copy is honest and nobody has to re-derive it from SQL.
`ExecutionSource.recomputeFailingRepeatedly` raises the signal for a live case when, and only when:

- take that case's **`window` most recent rows in `execution_fact`** — default **3**
  (`tcms.reporting.failing-repeatedly-window`, `ReportingProperties.failingRepeatedlyWindow`),
  ordered `executed_at DESC NULLS LAST, execution_id DESC`;
- **every one of them** has `status_kind = 'FAILED'`. The count of `FAILED` in the window is
  compared to the **window size**, not to the number of rows found — so a case with only one or two
  recorded results **never** raises it, however badly it did.

Four things the copy must not say:

1. **It is `FAILED`, not "did not pass."** `BLOCKED`, `SKIPPED`, `RETEST` and `UNTESTED` are all
   not-a-pass and none of them counts. A case blocked three times running does not raise it.
2. **It is a count of executions, not a period.** There is no "in the last N days" — a case run
   quarterly and one run nightly both raise it after three consecutive failures.
3. **It spans every run, plan and configuration in the project.** The window is the case's own
   history in `execution_fact`, unfiltered by run, plan, release or environment — so three failures
   across three different configurations raise it exactly as three in one do.
4. **It reads the fact log, which is history**: a soft-deleted *run* is still in it (deleting a run
   does not un-run it), while a soft-deleted *case* gets no `case_health` row at all.

Wording that is true: *"failed the last 3 times it ran."* Wording that is not: "failing for N days",
"failed 3 of the last 10", "failing in this run", "not passing recently".

### 5.5 §6 — documentation corrections

| Item | Verdict |
|---|---|
| `RepositoryDashboard.healthComputedAt` stale comment (`openapi.yaml:12504`) | **BUILD** (~~S4~~ S1). **Confirmed stale**: `repository/CaseHealthRecomputeService.java:22` states it derives ten of eleven `HealthSignal` members and `reporting/ExecutionSource.java:244` derives `FAILING_REPEATEDLY`. Strike the *"no writer yet … null in every deployment today"* sentence; **keep** the null-vs-empty distinction, still exactly right. |
| `/me/social-identities` refusal (`openapi.yaml:508`) | **BUILD** (~~S4~~ S1 copy, S9a capability). See §1.5 — half the remedy exists. |

---

## 6. Where the design changes instead of the backend

Fourteen, consolidated — the cheap half of the answer, and the design spec has to absorb them.

1. **Family 7's second governing rule becomes conditional** — a rail entry the caller can see leads to
   a page they can read; entries they cannot read are not listed. (C2, **decided**)
2. **The Refused state names a permission and a role, never a person.** (C1b)
3. **Drop the Archived series** from dashboard activity. (N4)
4. **Drop "+6 pts"** from automation coverage. (N5)
5. **Drop the session list** from Account security; keep set-password and sign-out-others. (N12)
6. **Drop the attempt counter** from the sign-in lockout message. (N10)
7. **Drop inviter / role / projects / expiry** from Accept invitation. (N13)
8. **Drop "the highest case key is TC-1284"** from Not found. (N17)
9. **Drop "nearest existing cases by step overlap"** from Proposal. (N16)
10. **Drop the linked-defect count** from the case list's last-result line. (§3)
11. **Redraw the run-schedule subtitle** against `updatedAt` / `updatedBy`. (§3)
12. **Add a sixth Flakiness state** for `flakeScore == null`. (§5)
13. **Reword "Not run in 90 days"** to something a tuned instance does not falsify. (§5)
14. **Drop the source IP from the audit trail.** (§3, **Q4 decided**) No column, no write path, no
    retention question, no migration. Two reasons, and the first is the one that settles it: an IP is
    **the one field that would turn the audit trail into personal data carrying a retention
    obligation** — a compliance surface the product does not have today and would acquire for one
    line of a row's subtitle. The second is that it would be **null for every existing row**, so the
    column lies about the past for as long as the trail is useful, and the trail's whole value is
    historical. Draw the actor, the token and the target, all of which are already served.

**"4 of 9 in this company" becomes "4 projects"** as well — the search endpoint deliberately refuses
to state a company's portfolio size (grounding §4, already noted there).

Fourteen design changes against seventeen build items. That ratio is the result.

---

## 7. Slices

Sequential, one lane at a time. Each is independently shippable and independently revertable. Every
slice touching `openapi.yaml` regenerates both copies and adapts both sides in one commit.

**The order below is not revision 1's.** Going first changes what should land first — see §8.3.

| # | Slice | Contains | Migration | Gates |
|---|---|---|---|---|
| **S1** | **The contract wave** — the refusal carries its permission (C1a), the free wire fields, the doc corrections | `Problem.permission` + `.scope` + `.line`; `api.Problem` record; both envelope writers; the §162–§164 comment amended · `RoleAssignment.grantedBy`, `TestRun.createdBy`, `lastResult.configurationName`, `Suite` arm counts, `ReportJob.size` · `healthComputedAt` comment struck, `/me/social-identities` refusal reworded. **Merged 2026-08-28 from S1 + S2 + S4**: all three are migration-free contract edits, and §8.1's rule is as few waves as possible — one regeneration for 003 instead of three. | none | backend · frontend · packages |
| ~~**S2**~~ | ~~Free wire fields~~ → folded into S1 | | | |
| **S3** | `review_enabled_doc`, inert | The column only — **no wire, no writer, no enum member**. Claims `V71` and releases the numbering block on 003/004. | **`V71`** | backend |
| ~~**S4**~~ | ~~Documentation corrections~~ → folded into S1 | | | |
| **S5** | Plan list statistics (N1, N2) | Batched `passRate` / `coverage` / exit-criteria writer on `TestPlanSummary` | none | backend · frontend · packages |
| **S6** | Org chart gaps (N6) | Permission-coverage computation in `identity`; `OrgChart.gaps` | none | backend · frontend · packages |
| **S7** | Review blast radius, review-scoped (N7 part) | Counts servable from within `project` only | none | backend · frontend · packages |
| **S8** | Dashboard by-last-run breakdown (N3) | New `DashboardBreakdown` segmentation in `reporting` | none | backend · frontend · packages |
| **S9a** | Account security, reduced (N12) | `POST /me/password`; sign-out-others via the `pv` epoch | none expected — see §9 | backend · frontend · packages |
| **S9b** | Documentation review (N8) + the rest of N7 | `ReviewSubjectType.DOC`; `doc:review` catalogue key; §A3 + both convenience copies; `documentation` SPI impl; `reviewEnabledDoc` reaches the wire | none — `V71` already landed in S3 | backend · frontend · packages |
| **S10** | Sign-in lockout, silent (N10) | Per-address lockout on the existing `tcms.rate-limit` machinery (`auth/RateLimitProperties.java`); no counter in the response | ~~outside the block — next free number when written~~ → **none** — decided 2026-08-28, §8.4 | backend · frontend |

**Frontend note:** every slice's frontend half is a view change only. No slice requires a new page
except S9a.

---

## 8. Going first — the consequences

> **Build order superseded 2026-08-28** by [`006-build-batches.md`](006-build-batches.md): one operator, sequential batches by agent and by files touched, one contract wave, gates run once at the end. The section below remains the record of *why* the pieces depend on each other.

### 8.1 Why the position changes the plan rather than just its order

003 and 004 rebase onto this plan's contract. Rebase cost is proportional to how much of
`openapi.yaml` 005 changes **and how many separate waves it lands in**. So the discipline going first
is the opposite of revision 1's: land the contract surface **early and in as few waves as possible**,
and put the changes the downstream plans actually collide with at the very front. Value-first and
cheap-first are no longer the ordering principle; **collision-first** is.

### 8.2 What changed in 003 and 004 — ~~a uniform +1~~ → 003 releases, 004 stands

**Applied 2026-08-28.** Revision 2 prescribed the +1 shift below; grounding the three plans together
showed it was the wrong fix. 003's only migration is in **Phase 3, which is blocked** — so the shift
would have moved a claimed-but-unwritten number rather than removed one, and §8.3's second
consequence applies to it verbatim: if 004 writes its first migration before 003's blocked phase ever
ships, 003's held number is refused on every migrated database with a green build. The fix is the
same rule §8.3 already states for this plan's S10: **do not claim what is not yet designed.**

| | Was | Is |
|---|---|---|
| `002-` (§255 `gherkin_source` deleter) | `V70` | `V70` — unchanged, still reserved |
| **005 (this plan)** | — | **`V71`** (S3) |
| **003** case authoring | `V71` | **none** — Phase 3 takes the next free number if and when it unblocks |
| **004** issue tracker | `V72`, `V73`, `V74` | **`V72`, `V73`, `V74` — unchanged**; `V72` may not be written before `V71` has landed |
| **005 S10** lockout | — | ~~next free number when written~~ → **none**: rate-limit machinery, no DDL (§8.4) |

**Edits made:** 003's header (claim released, neighbours named) and its Phase 3 database line; 003's
*Sequencing* gained the cross-plan order table all three plans point at; 004's header (`V71` is 005's,
`V72`–`V74` unchanged) and its *Sequencing*; 004's D12 paragraph now closes Q6. Each is struck
through and kept per the working agreement.

~~The original prescription, superseded:~~

Both record their migration numbers in prose. **This plan does not own them and does not edit them.**
The edit is a uniform **+1** shift, because 005 claims exactly one number:

| | Was | Becomes |
|---|---|---|
| `002-` (§255 `gherkin_source` deleter) | `V70` | `V70` — unchanged, still reserved |
| **005 (this plan)** | — | **`V71`** |
| **003** case authoring | `V71` | **`V72`** |
| **004** issue tracker | `V72`, `V73`, `V74` | **`V73`, `V74`, `V75`** |

**`docs/plans/003-case-authoring-redesign.md` — two lines:**

- **:6** — *"`V70` is reserved for it — any migration here starts at `V71`"* → starts at **`V72`**;
  add that `V71` is claimed by 005.
- **:389** — *"**Database (`V71`, never `V70`):**"* → **`V72`**. The "never `V70`" clause stays true.

**`docs/plans/004-issue-tracker-integration.md` — the header block plus twenty in-body mentions.**
Every `V72`→`V73`, `V73`→`V74`, `V74`→`V75`:

- **:8–:10** the claim block — *"Migrations start at `V72`"* → `V73`; *"`V71` is claimed by 003"* →
  `V71` is claimed by **005** and `V72` by **003**; *"claims `V72`, `V73`, `V74`"* → **`V73`, `V74`,
  `V75`**.
- `V72` → `V73` at **:85, :206, :259, :289, :492, :693**.
- `V73` → `V74` at **:98, :309, :315, :316, :322, :546**.
- `V74` → `V75` at **:95, :327, :538, :602, :669, :739**.
- **:713** — *"Nothing here runs in parallel with `003`, which holds `openapi.yaml` and `V71`"* →
  **`openapi.yaml` is held by 005**; 003 holds `V72`. Rewrite the sentence, do not just renumber it.
- **:718** — *"which is why `V72`–`V74` are claimed at the top"* → **`V73`–`V75`**.

Line numbers are as of this revision; 004 was revised while this plan was being written, so
**re-grep before editing** (`grep -n 'V7[0-9]'`) rather than trusting the numbers above.

### 8.3 The numbering trap that sets the slice order

`docs/specs/parallel-work-rules.md` is explicit: **`out-of-order` is unset, so a `V13` landing after
a `V14` is refused on any already-migrated database — and Testcontainers cannot see it**, because it
migrates a fresh container every time. The gate stays green and the lab breaks.

That interacts badly with the obvious reading of "005 goes first". If 005 takes the contract first but
its migration-bearing slice ships **late**, then 003's `V72` lands before 005's `V71`, and `V71` is
then **refused on every already-migrated database** — with a green build. Claiming a low number and
shipping it late is worse than claiming a high one.

**Three consequences, and they are the reason the slice order changed:**

1. **`V71` is split out of N8 into its own slice, S3, and lands third.** The column is added
   **inert** — no wire, no writer, no enum member. A column nothing reads is invisible and harmless,
   and it releases the numbering block on both downstream plans within three slices rather than at
   the end of the initiative. The apparatus that gives it meaning is S9b, and it needs no further
   DDL.
2. **005 claims one number, not a block.** Revision 1 would have claimed `V71`–`V72` with the second
   held for the lockout. A claimed-but-unwritten number is a **hole**: if 003 takes `V72` and 005
   later writes its own `V72`, the same out-of-order refusal applies. 004's comment-only `V73`
   (:309) is the repo's precedent for consuming a claimed number rather than leaving a gap, but the
   cleaner answer here is not to claim what is not yet designed. **The same rule, applied to 003, is
   why 003 now holds no number** (§8.2): its only migration belongs to a blocked phase.
3. **S1 leads.** `Problem` is inherited by **every refusal in both downstream plans** — and 003's own
   C4 makes the parse refusal *"the whole product"*. Landing `Problem.permission` first means 003
   never writes its refusal bodies against the old shape and then rewrites them — and `line` (C1a)
   is the member 003's C4 actually depends on. ~~S2's
   `TestCaseSummary` change is the other collision (003 is case authoring), so it follows
   immediately.~~ → S2 and S4 are folded into S1 (2026-08-28), so the collision lands in the same
   wave and 003 regenerates once.

**Ordering constraint, stated plainly: S1 and S3 must both land before 003 begins.** After S3
there is no numbering or contract coupling left, and S5–S10 may be reordered by product priority or
interleaved with 003/004 at the coordinator's discretion — **recommended not to**: each touches the
contract, so each interleave is a regeneration for the plan in flight. Default: after 004.

### 8.4 S10 is deliberately outside the block — and, decided, needs no number

**Decided 2026-08-28: lockout state lives in the existing `tcms.rate-limit` machinery**
(`auth/RateLimitProperties.java:49`), keyed per address. No table, no migration; state resets on a
restart, which is acceptable for a lockout whose only job is pacing. The paragraph below is kept as
the reasoning that applied while storage was undecided.


The sign-in lockout may need stored attempt state; that is undesigned (§9). Rather than claim a
number for it now, **S10 takes the next free number at the time it is written** — after 004's
~~`V75`, so `V76`~~ `V74`, so `V75` or later. It is independent of every render and of both downstream plans, so nothing waits
on it and it creates no hole.

### 8.5 What the contract handover costs

Per `parallel-work-rules.md`: *"`openapi.yaml` and `npm run gen:api` belong to exactly one lane;
`schema.d.ts` is generated and two regenerations do not merge. Resolve the contract, then regenerate
— never hand-merge the output."*

So the handover is not a merge. When 003 and 004 pick the contract up they **re-run `npm run gen:api`
in both places** against 005's `openapi.yaml` and take the generated output wholesale; any attempt to
reconcile `schema.d.ts` or `packages/client/src/schema.ts` by hand is the failure that rule exists to
prevent. 005 must therefore leave the contract in a **landed, green state** — not mid-slice — before
either plan starts. That is the same boundary as §8.3's constraint, for a different reason.

**~~One catalogue collision to settle before 004 finalises.~~ Settled — Q6 closed 2026-08-28.** The
cache recorded 004's open question 5 as live; 004 revision 5 has since answered it as **D12: no new
catalogue key, the catalogue stays at 55** — visibility is project assignment plus the viewer's own
tracker credential, and there is nothing left for a key to gate. So **`doc:review` (S9b) is the 56th,
uncontested**, and 004's D12 paragraph says so.

---

## 9. What is unverified

- **Nothing here was built and nothing was run.** No gate was executed for this plan; verification is
  a single post-batch pass on `shanks`, outside this run.
- **The renders have never been driven in a browser.** Their own index says so. Every "the design
  requires X" statement is a reading of static HTML, not of a working screen.
- **Agreement about a shape is not evidence a screen works** at real content length.
- ~~**S9a's migration column says "none expected"** — not checked at storage level.~~ → checked
  2026-08-28: `app_user.password_hash` exists (`V1__initial_schema.sql:81`, `identity/AppUser.java:68`,
  written by `activate`). **S9a needs no DDL.**
- ~~**S10's storage is undecided.**~~ → decided 2026-08-28: the existing `tcms.rate-limit` machinery;
  no migration (§8.4).
- **N9 is deferred on a suspicion, not a measurement.** The N+1 risk on the project list is plausible,
  not proven.
- **The `FAILING_REPEATEDLY` predicate was located but not read** (`reporting/ExecutionSource.java:244`).
  §5.4's copy must not ship before somebody reads it.
- ~~**004's line numbers in §8.2 are already stale-prone.** Re-grep before amending.~~ → applied
  2026-08-28; the superseded prescription is kept under §8.2 for the record.
- ~~**The +1 shift assumes 003 and 004 have not since claimed further numbers.**~~ → no shift was
  applied (§8.2). Before writing **any** migration under any of the three plans:
  `grep -n 'V7[0-9]' docs/plans/*.md` and `ls backend/src/main/resources/db/migration | sort -V | tail -1`.
- ~~**`Problem.line` (S1) is sized by reading, not by building** — whether the parser already reports
  a line was not checked.~~ → checked 2026-08-28: `GherkinSyntaxException.Reason.SYNTAX` documents
  that *"the message carries its line, column and wording"*. The line exists at parse time; S1 is a
  controller change that surfaces it as a member instead of prose. No parser work.

---

## 10. Questions

### Answered

| # | Question | Answer |
|---|---|---|
| **Q1** | C2 — un-list the three credential rail entries and make family 7's second rule conditional? | **Accepted.** Recorded as decided in §3 and §6.1. No backend cost. |
| **Q3** | Does feature import (N14) belong to 003 or to a follow-on here? | **Unchanged — deferred** pending 003 landing. Do not plan it twice. |
| **Q4** | Audit IP — retention and privacy position? | **Dropped from the render.** No column, no write path, no migration. Moved to §6.14; §1.2's migration count drops from four to three. |
| **Q5** | Is 005 sequenced after 003/004, or does it take the contract first? | **005 takes the contract first.** §8 rewritten; `V71` claimed; ~~003 and 004 shift +1~~ → 003 releases its claim, 004 unchanged (§8.2); slice order re-derived. |
| **Q6** | `doc:review` (S9b) and 004's possible `integration:manage` read key are both "the 56th catalogue key". Which is which? | **`doc:review` is the 56th.** 004 rev 5 D12 adds no key; the catalogue stays at 55 until S9b. Closed 2026-08-28, §8.5. |
| **Q2** | Is the 403 on `GET /projects/{id}/members` correct, given the org chart already discloses per-project role assignments company-wide? | **The gate is wrong; relax the read to `project:read`**, writes stay `project:manage`. Rides in S1. Decided 2026-08-28, §3. |
| **Q7** | S10 lockout storage — table, cache, or `tcms.rate-limit`? | **`tcms.rate-limit`.** No migration. Decided 2026-08-28, §8.4. |

### Open

**None as of 2026-08-28.** Every question this plan raised is in the table above. What remains
unverified — as opposed to undecided — is §9.
</content>
