# §98 — suites become flat

**Date:** 2026-08-06 · **Plan:** `docs/plans/2026-08-05-organization-model-restatement.md` (M2)
**Two phases, two commits.** `V33__flatten_suites.sql` is the head; **`V34` next free**; no table
added or dropped, so the **RLS policy count stays at 61**.

A suite stops being a folder in a tree and becomes what the product's restated model says it is: a
**named collection**. `suite.parent_id`, `suite.path` and the GIST index over that path are gone,
along with `Suite.parentId`/`Suite.path` on the wire and `SuiteInput.parentId` on the way in.

---

## The phases, and why the plan's five became two

The plan sketched five: contract · migration · backend · frontend · the record. Two of its seams do
not survive contact with the gates.

- **The contract cannot be its own phase.** A contract-only commit drops two fields the client reads,
  so `npm run build` fails and the commit is a state whose gate was not run — which is the one thing
  the working agreement says a phase commit must not be. Contract and client are **phase 1**.
- **The migration cannot be separated from the entity.** `hibernate.ddl-auto: validate` rejects a
  mapping for a column that no longer exists, so a commit holding `V33` while `Suite.java` still maps
  `path` fails **every** test at context startup. Migration and backend are **phase 2**.

The **ordering** is the part that matters and it is the reverse of the instinct: client first,
server second. Between the two commits the backend still serves `parentId` and `path`, and a client
that no longer reads them simply ignores two extra fields. The other order — server first — leaves
a client asking for a field the server has stopped sending, which is a broken screen between two
commits rather than a redundant one.

---

## THE FINDING: THE COUNT AND THE GRID HAVE ANSWERED DIFFERENT QUESTIONS SINCE §96

Clicking a suite in the rail filtered the case grid on **`suite_id IN (…)`** — the *filed* arm alone
— while the number rendered beside that suite was **`Suite.caseCount`**, which §96 made the full
resolved union: filed ∪ added ∪ tagged. A suite whose whole definition is a tag rule therefore
showed **a count of two over a grid of none**.

Neither number is wrong-looking on its own. A suite holding no cases is an ordinary thing; a count
of two beside it is an ordinary thing. Nothing but reading them **together** finds it, which is
§40's plan-statistics defect and §52's suite-history defect for the third time — *two numbers on one
screen with nothing to identify the wrong one.*

**It survived two slices because every fixture suite was a folder people filed into.** Under that
shape the union and the filed arm answer identically, so every assertion passed and the mock agreed
with the server. §96 added exactly one suite that does not (`Smoke`, TS-10) and nothing clicked it.

**§98 is what makes it ordinary rather than exotic**: with no folders left, a tag rule is the normal
way to define a suite that spans work, so the defect would have moved from one fixture row to most
of them.

Fixed at both ends by making the two **one query**:

- Server — `CaseScope` gains `memberCaseIds`, resolved once by `CaseService` through
  `SuiteMembership.caseIdsIn` (the same union that produces `countsBySuite`), and
  `CaseSpecifications.suiteScope` predicates on the **case's own id** when a suite was named.
- Mock — `suiteMemberCaseIds` returns **ids**, and both the count and the filter read it. Returning
  a number would have left two call sites free to diverge again.

`mocks/db/suites.test.ts` is a new file, and its **first** assertion is the non-vacuity one: the
fixture must contain a suite claiming a case it does not file. Without that row, every assertion
below it is equally true of the defective implementation — which is precisely how this survived.

---

## `execution_fact.suite_path` OUTLIVES `suite.path`, ON PURPOSE

The fact log carries `suite_id` and `suite_path` per row, frozen on conflict since §86 so a
recompute cannot re-file last quarter's results under this quarter's taxonomy.

After `V33` those stored paths describe a hierarchy that **no longer exists**, and that is
**correct**: they say where the case was filed *when it ran*. The migration therefore updates,
nulls and reformats **nothing** in `execution_fact`, and the column keeps its `ltree` type. The
first person to notice a dotted path in a report of a flat-suite product will want to fix it; the
migration header, the column, `ExecutionSource`'s javadoc and this section all exist to be found by
that person first.

New rows still need a value, and the one chosen is **`'s' || key_seq`** — which is *exactly the path
a root suite always had*. `Suite` derived its label that way (unique by construction, stable across
renames, never empty, letter-prefixed so a segment is recognisable as a suite among other trees),
and **a flat suite is a root**. So a report spanning the migration prints deep paths for old runs
and single labels for new ones, which is the truth about how the project was organised at each of
those moments.

The **`ltree` extension stays** — checked rather than assumed, as the plan insisted. Three other
columns use it: `doc_page.path`, `classification_value.path`, and this one. `DROP EXTENSION` would
take all three.

---

## THE PLAN IS WRONG ABOUT `SuiteScopeAgreementTest`, AND THE CORRECTION IS THE USEFUL PART

The plan called it the slice's "one genuine simplification":

> `CaseFacts#inSubtreeOf` — with no subtree it becomes membership, which `SuiteMembership` already
> answers, so **one of §96's two duplicated resolution predicates disappears** and
> `SuiteScopeAgreementTest` loses its reason to exist.

**It does not.** The duplication was never caused by the subtree. It is caused by **ERD §0.2**: the
`execution` module may read the repository's tables only through its own seam class, so calling
`SuiteMembership` would trade a duplicated predicate for a dependency `ModuleStructureTest` refuses.
Flattening does not touch that boundary.

What flattening *does* change is that the two predicates are now supposed to be **identical** rather
than merely equivalent — which makes the agreement test **stricter**, not redundant. It stays,
`inSubtreeOf` is renamed `memberOf`, and both the method and the test carry the correction so the
next reader finds a decision rather than an oversight.

Its fixture needed rebuilding for a reason worth stating: **every arm hung off the child suite**, so
after flattening the class asserted a union over a suite that resolved to two of its four members
and went red. The child is gone and the three arms attach to the subject directly.

---

## Only the FILED arm blocks a delete, and that is the narrower of two readings

"The suite must be empty" lost half its meaning (there are no child suites) and the other half
narrowed deliberately. A case merely **added** to a suite, or **matched** by one of its tag rules,
lives elsewhere and only *appears* in the collection — so deleting the collection takes **a claim,
never a case**. Filing is different in kind: `test_case.suite_id` is where the case *is*.

`countLiveChildren` + `countLiveCasesInSubtree` collapse into **`countLiveCasesFiledIn`**, and the
409's sentence names one count instead of two.

The same split governs **conditions**: a suite's conditions reach the cases **filed** in it and
deliberately **not** the other two arms. Widening is a decision with a slice behind it, not a side
effect of flattening — a case's preconditions must not change because somebody else tagged it.

---

## "New suite inside" is REMOVED, not disabled

`ui-rules`' disable-don't-hide governs an action the **state** forbids — one a reader could earn by
changing something. There is no state in which a suite nests inside another, so a greyed row would
advertise a capability the product does not have and send somebody hunting for the permission that
unlocks it. Asserted in both directions: the item is absent, and `Rename` beside it is present, so
the absence is the item being gone rather than the menu never rendering.

---

## What went with the tree, and why none of it left a hole

- **The descendant-inclusive rollup.** The number shown is now the number sent, so the display
  cannot disagree with the click. `lib/suites.ts` is deleted; `SuiteHistoryPage` reads
  `suite.caseCount`, closing §52's two-numbers defect for the strongest available reason — **there
  is only one of them**.
- **§36's orphan-surfacing rule**, promoted to a requirement in Phase 5 because a suite whose parent
  was absent from the payload rendered *nowhere*, silently. With no parent there is nothing to be
  orphaned from: every row in the payload is a row on screen, which is what that rule was reaching
  for and could only approximate.
- **Cycle guarding.** It survives in `lib/classification.ts`, over a tree that is still real.
- **Controlled expansion**, and the MUI warning behind it. All of it answered one question — *is the
  selected row on screen?* — whose answer on a flat list is always yes.
- **Ordering by `path`.** It existed to make the payload *constructible*; `key_seq` replaces it
  because what an order must still be is **total and stable**, and a name is neither (two suites may
  share one).

---

## The test sweep, and the part of it that was not mechanical

35 test files insert suites through raw SQL naming both columns. They were swept by a script that
parses the column and value lists, drops the two positions, and **renumbers the JDBC parameter
indices that follow** — because `statement.setString(6, …)` names a `?` by position, and an edit
that touched only the SQL would leave every fixture compiling while binding the wrong values to the
wrong columns. That is the class of defect this repository keeps finding in its own mocks.

Three fixtures needed judgement rather than a script, and all three for one reason: **they used
nesting to reach cases**, so a plan or a history scoped on the parent resolved to nothing.

- `SuiteHistoryEndpointTest` — `Payments` now **claims** the refunds case through `suite_case`
  instead of parenting it. **Every count in the class is unchanged**, which is the property worth
  having: an implementation resolving only the filed arm answers 1 where these expect 2, exactly as
  one ignoring the subtree used to.
- `PlanStatisticsEndpointTest` and `RunCreateEndpointTest` — `PAYMENTS` gains a **tag rule**, and the
  cases filed in `REFUNDS` carry the tag. A tag rule is *live*, which also preserves the fixture's
  later-authored case entering scope with no edit to the plan.
- `CaseDetailEndpointTest` — the condition chain collapses onto **one suite**, and the only rule
  lost is the between-suite ordering. Everything the class was written for is a rule *within* a
  suite or *between* the suite and the case — `position` order, dedupe with the inherited copy
  winning, opt-outs keyed on the attachment, retired entries dropping out, inline attachments
  excluded, the two kinds deduping separately — and all of it still bites.
- `SuiteScopeAgreementTest` — **every arm hung off the child suite**, so after flattening the class
  resolved two of its four members and went red. The child is gone and the arms attach directly.

**And the reporting fixtures were wrong in a way §98 exposed rather than caused.** They wrote
friendly suite paths (`billing`, `checkout`) that **no production suite has ever had** —
`Suite.create` derived its label from `key_seq` from the beginning — so the report's suite rows now
read `s1` and `s2`, which is what a real deployment has always shown. The fixture got *more*
faithful, not less.

---

## Gates

| | |
|---|---|
| Phase 1 | `npm run build` clean, `npm run lint` 0, **909/909 across 65 files** (was 915/65 — `lib/suites.test.ts` went with its subject) |
| Phase 2 | **BUILD SUCCESSFUL — 1746 tests across 347 classes, 0 failures, 0 skipped** (1741 → 1746), counted from the XML rather than the console |

**Seventeen tests went red on the first full run and every one of them was a test asserting the
tree**, not a defect in the change: three subtree case-list filters, five condition-inheritance
assertions over a three-level chain, three child-suite delete refusals, three frozen-path
assertions, two suite-write shapes and one required-field list. They are listed here rather than
summarised because *nothing else went red* — the sweep's parameter renumbering, the membership
seams and the migration all landed without a single behavioural failure.

**And one run before them was a false alarm worth recording**: a build reported **1672 failures of
1748**, which was Testcontainers unable to launch Postgres — `SocketTimeoutException`,
`RetryCountExceededException` — because two `gradlew` invocations were live in one tree. That is
§86's lesson exactly (*"a subagent running the gate is a second lane"*), arriving through a
different door: two *sequential-looking* background builds that overlapped. A catastrophic failure
count is a signal to check the harness before reading a single assertion.

## Found and not fixed

- **`SuiteVersions.Composition` lost `parentId` and is now a one-field record.** A suite's
  structural position is entirely its membership, and the record is a list of case ids that is
  always empty at creation — so the hash it feeds is currently a constant. Pre-existing (nothing
  bumps a suite version yet); §98 makes it more visible rather than worse.
- **`suite.position` is kept and written by nothing.** It was sibling order within a parent; it is
  now order within the project, read by nobody, since the rail sorts by name. Dropping an unused
  column is its own change and `V33`'s subject is the hierarchy.
- **The old `?suiteId=` behaviour has no regression test on the server side** beyond the one the
  mutation queue names — row 11a is the first one to run, and if it goes green the fixture has no
  suite whose membership differs from its filing.

## Verification

**Zero browser rows and zero mutations run**, per §65. **12 browser rows** appended to the mock
pass's M2 lane (`M2-37`…`M2-48`, one of them live-only — the frozen `suite_path` needs a database
that migrated in place), and **6 mutation rows** as finding 11 plus a `V33` row on the Hetzner list.
**M2-38 is the row this slice exists for**: click `Smoke`, and count the grid against the rail.

**Built and unverified.**
