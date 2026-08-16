# §238 — The number that never moved

**C1** of `docs/audits/2026-08-15-flow-review-and-improvements.md`, slice 6 of the C run, in the
audit's §3.6 order. Backend only. **No migration, no contract change, no permission seeded or
moved** — `suite_version` and `suite.current_version_no` have both existed since `V1`, and
`SuiteView` serves no version number, so nothing crossed the wire that did not cross it before.

Decision on record: *"Fix the sharp edge only: make `suite_version` actually advance on
membership/status change so review pinning means something; leave the other three resources'
versioning as-is."*

## What was wrong

`Suite.create` set `currentVersionNo = 1` and **nothing has ever incremented it.** So:

- `SuiteStatusService` pinned that number to every review it recorded, under a comment saying *"the
  version pinned is the suite's current one, which for a suite means its composition: approving a
  folder is a statement about what was in it on the day, and that is exactly the number an auditor
  needs back."* **Every approval in the product's history pinned 1** — the same number whether the
  folder held four cases or four hundred, and whether they were the same four.
- `SuiteHistoryService`'s version-marker rail was "structurally empty in every deployment", by its
  own header, and the SPA's *"the membership changed during this period"* notice had never fired.
- `SuiteVersion.contentHash` was in `ArchitectureRulesTest.READERLESS_BY_DECISION`, because a digest
  that exists to compare a later version to an earlier one has nothing to compare against on a table
  with one row per suite.

The comment is corrected **in place, struck through**, in this slice's commit — the §152 shape, six
slices running. It was right about what the pin is *for*; what it did not say is that the number
never moved.

## The three decisions this slice had to take

### 1. A bump means the RESOLVED contents changed, not the suite's definition

A suite is flat since §98 and its members are three arms — filed ∪ added ∪ tagged
(`SuiteMembership`). Only two are written on the suite; the third changes when somebody tags a case,
with no write to the suite at all. So "membership changed" was genuinely two different rules.

**The snapshot is the resolved union**, and the reason is not that it reads better. Snapshotting the
*definition* — `suite_case` rows plus `SuiteTagRule`s — would make `suite_version` a second
implementation of "in this suite", which is precisely what `SuiteMembership`'s header exists to
forbid: the tree's counts, the grid's suite filter and a run's scope all resolve the union, and a
recorded composition that contradicts them is worse than no recorded composition, because both
screens look right.

**What the fan-out costs, priced rather than waved at.** Every act changing the resolved set has to
reach the seam, which means the case write paths and not only the two on the suite: one
`suitesByCase` read taken *before* the write, plus one resolve-and-hash per affected suite. What it
does **not** cost is the row-per-tag-edit-per-suite the decision brief worried about — reconciliation
runs once per transaction over a **set**, so a bulk retag of 500 cases that three suites' rules match
writes three version rows. The cost is bounded because a tag has no project-wide rename or
vocabulary-delete surface: `case_tag` is written only on a case write, which is a path already being
reconciled.

### 2. The old pins are NOT backfilled

Every existing review points at v1, and v1 is the only composition ever recorded for that suite — so
the pin is not dangling; it names the only thing there is to name. Rewriting it would assert that an
auditor was shown a version that was never written, in the one table whose purpose is that past
claims stay true. `CaseVersions`' v1-gap argument, unchanged: *history began when it began.*

**The same answer covers a second defect this slice found and the audit did not contain.**
`writeInitial` hard-coded `Composition(List.of())` under the comment *"empty membership, not
unknown: a suite is created before anything can be put in it"* — true when written, and false since
§98, which let a create carry `caseIds` and `tagRules`. **So v1 is a false statement for every suite
created with members.** The writer is fixed; the rows are not, because correcting them needs the
create-time membership and that is exactly what was not recorded.

### 3. `decide()` earns its existence, and its own obituary was accurate

`SuiteVersions`' header said there was no `decide()` sibling *"because a 'has the composition
changed' comparison with exactly one caller that always answers yes is a branch no test can make take
its other path."* This slice is the second caller and the argument expired precisely as written — it
is struck through rather than deleted. An unconditional bump would now be **wrong**, not merely
noisy, in three ways a test takes:

- `SuiteMembershipWriter.reconcile` is set-semantic and idempotent, so a bump would mint a version
  every time anybody pressed Save on the suite dialog (plan §6j's noise rule);
- a case edit changing only the title declares its suites as candidates and must write nothing;
- and the one only a hash over the *resolved* set gets right — **filing a case into a suite that
  already held it through a tag rule.** The filed arm changed; what the suite holds did not.

## The divergence: the status half of C1 is REFUSED

C1's wording is "on membership/status change". The status half is declined, and
`Suite.applyStatus`'s javadoc carries the argument: if approving minted a version, the review would
pin a number **the transition itself created**, when what an auditor needs back is the composition
that *was* approved — the version standing before the move. `TestPlan.applyStatus` declined the same
bump in §73 for the same reason. **The pin is meaningful because that method declines, not despite
it**, and `approvingDoesNotMintItsOwnVersion` asserts it.

This is the second slice in the C run to land against its own wording (§237 was the first).

## The seam

`SuiteVersions` gained `SuiteMembership` and `SuiteRepository`, and three methods:

| | |
|---|---|
| `writeInitial(suite, projectId, …)` | v1, resolving the membership rather than assuming it empty. Still the literal `1`, never `decide` — §29's rule: `currentVersionNo + 1` on a fresh row whose column defaults to 1 makes a created suite take v2 and its v1 never exist. |
| `holdersBefore(projectId, caseIds)` | **Read before a case write, never after.** The half that cannot be recovered later. |
| `reconcile(projectId, suiteIds, …)` | Resolve → hash → if new, write the row and bump the counter, together, in the caller's transaction. Idempotent and order-independent, which is what lets a caller over-declare. |

plus `reconcileAfterCaseWrite(projectId, before, caseIds, …)`, which takes the **union** — one method
rather than "call `holdersBefore`, call it again, union, reconcile" at each of the four case paths.
The union is the step whose wrong answer looks right: reconciling only the after-set leaves every
suite the case *left* pinned to a composition it no longer has, and it passes every gaining
assertion. Taken once, here, rather than four times by hand.

`Suite.applyCompositionVersion(int)` bumps the counter and **stamps no `updatedAt`/`updatedBy`** —
every other mutator on that class is somebody editing *that row*, while this one is reached from the
other direction. Re-stamping would make a suite nobody touched sort to the top of "recently updated"
and name, as its last editor, whoever tagged a case that matched one of its rules. The act is already
attributed, on the `suite_version` row's `created_by`.

**Seven call sites, and `CaseCloneService` is deliberately not one of them** — a clone builds a full
create request (tags and suite included) and delegates to `CaseCreateService`, so the create path
already covers it. The bulk path skips the whole thing on `dryRun`, which is the one place this could
have written on a preview.

## What came free, and what was already built for this day

- **`ExecutionSource.insertNewSuiteRunSummaries` needed no change.** It already resolved
  `max(version_no) WHERE created_at <= last_executed_at` rather than reading
  `suite.current_version_no`. With one row per suite the two readings were indistinguishable — a
  scheduler tick could have written today's number onto a year-old summary and nothing would have
  caught it. The distinction started paying here.
- **The version-marker rail is live and took no backfill**, which is the bet §3 lesson 5 made when
  these rows were written from M1 for a screen that did not exist. `CaseFacts.suiteVersionMarkers`
  recorded the emptiness as *"a property of the writer, not of this query"* — correct, and the writer
  changed.
- `SuiteVersion.contentHash` **left `READERLESS_BY_DECISION`** by exactly the exit that entry
  predicted: not a query somebody added, but a second writer making the comparison mean something.

## What the tests cost, and the one the fixture taught

`SuiteCompositionVersionTest` — 16 tests, `8b…` prefix, driving HTTP end to end. Two fixture facts
worth carrying:

- **Suite review is OFF by default, per artifact type**, so with it unset no `review` row is written
  at all and the pin assertions would read an empty table. The fixture turns
  `project_settings.review_enabled_suite` on; that is what makes `review.subject_version_no`
  observable. It also needs a **second lead** — author-cannot-approve-own-work defaults on, so the
  suite's creator gets a 409 on APPROVED.
- **Every tag rule in that class uses a tag unique to its own test.** These tests share one project
  and nothing rolls back, so a tag — which is *project-wide*, that being the whole nature of the
  third arm — makes one test's "a rule matching nothing" into another's "a rule matching something".
  Found the hard way; it is the same fact the production code is about, arriving from the fixture's
  side.

`SuiteHistoryEndpointTest` was **reworked rather than patched**: it hand-inserted a synthetic v2 by
SQL, "because nothing writes a v2 yet", with a hand-written `change_comment`. That INSERT now
collides with the version the product writes (`uq_suite_version` — all 20 of its tests, the entire
blast radius of this slice on the existing suite). It changes a composition by filing a case now, and
only the *clock* is still moved by SQL. Two consequences worth stating:

- its version numbers are **3 and 4 rather than 1 and 2**, because a suite reaches the end of that
  setup on v3 — created, own case filed, sibling's case claimed — and the assertion is otherwise
  unchanged;
- **the `change_comment` assertion is inverted.** It asserted a fixture-written comment on a column
  production has never written; a composition version has no author's note, because the act is a
  write to some *other* row and there is no request field an author would be commenting in.

## Gates

Run on **shanks**, arrival suite first.

| | Arrival | Departure |
|---|---|---|
| `backend` `./gradlew build` | 3054 / 0 / 0 | **3070 / 0 / 0** (+16), 8m 16s |
| `frontend` `npm run test:run` | 1552 / 116 files | **1552 / 116** (untouched) |
| `packages` `npm run build` + `test:run --workspaces` | 336 | **336** (untouched) |

The arrival backend run was cached — the remote tree was unchanged since §237 — so the figure is read
from the last real run's result XML rather than claimed as a fresh execution.

Browser and mutation rows are appended to the two queues under the `SV` prefix, per §65. **This slice
is built and unverified in both queues.**

## What is left of the C run

**C4** — retire the shared-step library. It is the last one.
