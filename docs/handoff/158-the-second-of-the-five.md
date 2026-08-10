# §158 — the second of §156's five, and the tooltip that explained a comparison nobody makes

**2026-08-09 · one phase · `backend/` plus one line of frontend copy: no migration, no contract
change, no `packages/` source.**

§157 took the first of §156's five writerless health signals. This is the second: `CONDITION_DRIFT`,
plan §6o's *"a referenced shared condition changed since the snapshot"*.

It was **not** the cheapest of the four that were left — it is the one that needed no module this
one may not depend on and no decision nobody has taken, which is a different property and the
reason it was taken next. `test_case_version` and `condition` are both `repository`'s own tables, so
unlike §157 this crossed no module boundary at all, not even a permitted one.

## What §6f actually promises, and where the promise is observable

A case's conditions are **references while it is live and a copy once it is versioned**. That split
is the whole of §6f and it is the good design: edit "Log in as standard user" once and 200 cases
follow, while a run's history stays immutable because `test_case_version.snapshot` froze the
resolved text at version-pin time.

The consequence nobody had built the observer for is that **the two can disagree, and nothing on any
screen said so**. `GET /cases/{id}` resolves the live library row every time it is asked; the
snapshot holds what the API served when the version was written. Between those two moments somebody
can edit the library entry, and the case a tester is handed today is not the case anybody recorded.
`CONDITION_DRIFT` has been in the contract, in `HealthSignal`, in the case grid's facet, in the
dashboard's tile list and on the team board since M0 — at **ERROR** severity, the loudest tier —
counting zero the entire time.

## The finding is the join type, and the fixture that proves it changes nothing else

Soft-deleting a library condition is the worst thing that can happen to a case referencing it.
`CaseConditions.forCase` **drops** an attachment whose entry no longer resolves and every
`ConditionRepository` read filters `deleted_at IS NULL`, so the live case silently renders one
instruction fewer than the snapshot says it should — with nothing on the case itself changed, no
version written, and no audit line on the case.

An inner join between the snapshot's entries and the `condition` table makes **that** case the one
that reports clean, because the row it would have compared against is not selectable. So `lib.id IS
NULL` is a term in the expression and the join is `LEFT`, and the test for it is a fixture whose
entry is **byte-identical** to what the snapshot recorded in every column the comparison reads —
title and body both match; the only difference is `deleted_at`. Every content comparison in the
statement answers "unchanged", and the answer is still drift.

`CD-1` was run as a mutation during the slice and reddened exactly that one test.

## The spelling that looks equivalent and cannot self-heal

The short version of this statement is `condition.updated_at > version.created_at`. It is one join
shorter, it reads as obviously the same question, and it is wrong in the way that matters most for
an ERROR-severity signal: **it can never go false.**

Edit a condition, change it straight back, and `updated_at` has moved for ever. The case carries a
drift flag until somebody happens to save it, and no action available to the person looking at the
flag would clear it. `case_health`'s own table comment is the rule this breaks — *"signals are
derived, never authored … so they self-heal when the underlying condition is fixed. No 'mark as
not-stale' button exists to lie with"* — and a signal you cannot fix by fixing its cause is that
button's absence turned into a trap rather than a safeguard.

This is §157's finding in the same family and one table over. There the timestamp spelling
disagreed with a banner the SPA had rendered since §76; here it disagrees with nothing, which is
worse, because there was nothing to notice it against.

**And the test suite cannot currently tell the two apart.** `CD-6` in the mutation queue records
that honestly rather than claiming coverage the fixture does not have: the state that separates
content from timestamp is *edited and changed back*, and no fixture here expresses it. What the
tests do pin is that the content comparison is the one written; what would pin the argument is a
fixture that bumps `updated_at` and restores the body, and `CD-B3` in the browser queue is the only
place the property is observable today.

## Three smaller decisions, each of which a reasonable reader would spell differently

**The newest snapshot, not the approved one.** Pinning to the approved version is the tempting
narrowing — the plan's §6f paragraph about approvals is right next to the signal — and it would
report a confident zero for every project that uses the status ladder without reviewers, since §6g's
apparatus is opt-in and off by default. That is `STALE_AUTOMATION`'s defect, which sat from `V1` to
M3 selecting the empty set in every deployment. Plan §6o says *"since the snapshot"*, and the
snapshot a case has is the one its last version wrote. `CD-2` reddens exactly the test that pins it.

**Title as well as body.** `CaseConditionRefView` carries a title because the contract requires one
on every condition reference, and the case detail renders it above the steps. Renaming "Log in as
standard user" to "Log in as admin" without touching the body changes what the case tells a tester
to do quite as much as editing a step does. `CD-3`, run, reddens exactly the rename test.

**Attachment changes are not reported, and the asymmetry is deliberate.** Whether a condition is
attached at all is authored content — `CaseEdit` carries `preconditionIds` and `postconditionIds`,
so attaching or detaching moves `CaseVersions.hash` and writes a version whose snapshot re-baselines
from the live library. The case's own arm therefore **cannot** drift by attachment. The *inherited*
arm can: a suite-level attach or detach writes no case version. That gap is left open and written
down at the method rather than closed, because "the set of conditions this case inherits has changed"
is a different question, needs the suite chain walked per case, and would fold a second predicate
into a signal whose name says content.

## The class is deliberately not called `ConditionFacts`

`*Facts` means one thing in this module and it is worth keeping it meaning that: rows **another
module owns**, read through exactly one file. `ExecutionFacts` is the strict case — ArchUnit
*forbids* the `repository` ↔ `execution` edge, so the one-file rule is what keeps the crossing
findable. `ApprovalFacts` is the weaker one, a convention over a permitted edge toward `project`.

Every table named here belongs to `repository` itself, so a third `*Facts` would tell a reader there
is a third crossing to account for and there is not. §157 had to spend a paragraph saying its two
are not the same kind; a fourth reading of one suffix is one too many, and `ConditionDrift` — named
for the signal it derives — costs nothing to read.

## The tooltip, which is the reason one frontend line is in a backend commit

`StatusChip.tsx` has explained this signal since M0 as *"A shared condition this case relies on has
changed since the case was last **reviewed**."* The server compares against the case's last
**version**, and review does not enter into it — a project with review switched off raises this
signal exactly as readily.

That wording was harmless for as long as the signal could never fire. This commit is the one that
makes it fire, so it is the commit where the sentence becomes a tooltip explaining the wrong
comparison to the person deciding whether to act on an ERROR chip. Corrected to *"last saved"*, with
the reason recorded at the line. It is the same defect §157 found from the other side — one question,
several surfaces — caught this time before the surfaces could disagree in public.

## What a zero means now, per signal

**Three of ten, down from five.** `outdated_spec_changed`, `unlinked` and `orphaned` remain columns
whose `false` on an existing row is indistinguishable from a check that ran. The SPA's panel must go
on saying *"No health signal was raised"* rather than *"Nothing needs attention"* until all three
have writers, for the reason §156 gave and §157 repeated: the sentence is narrowed to a claim about
the checks precisely because the SPA cannot know which signals the server derives.

Three surfaces gained output with no code of their own changed, and this is the **second** slice
running to be able to say that:

- `DashboardService.health` renders a **Shared condition changed** tile — ERROR, so above every
  tile §157 added.
- The case grid's health facet returns a non-empty set for `healthSignal=CONDITION_DRIFT`.
- `work.BoardFacts.casesNeedingAttention` produces drift cards. Its javadoc predicted §157 in as
  many words and has been struck through twice now; the bet — that reading a column with no writer
  is cheap when the read is generic — has paid out two for two with a diff of zero in that file.

## Documents corrected rather than added to

`CaseHealth`'s class header and three of its field javadocs, `HealthSignal`'s header and its
`CONDITION_DRIFT` constant, `CaseHealthRecomputeService`'s header, `DashboardService.health`'s and
`BoardFacts.casesNeedingAttention`'s each carried a count, and each moves in this commit. §145's
lesson and its ninth instance; §157 found the cost of missing one *inside this very file family*,
where two javadocs fifty lines apart disagreed about one column.

`ArchitectureRulesTest.WRITERLESS_BY_DECISION` loses `CaseHealth.conditionDrift` — **thirteen
entries to twelve**, the second exit from that map taken by writing the column rather than deleting
it, and the rule itself is unchanged both times. Its comment now says so: an entry there is a
decision to leave a column empty, not a permit to keep it that way, and the population falling twice
without the rule moving is what makes that sentence true rather than aspirational.

## The borrowed empty column, one slice on

§157 recorded that *"a test that demonstrates a hazard by borrowing an empty column has a dependency
on that column staying empty, and nothing states it"*, and moved `CaseHealthRecomputeTest`'s clobber
test onto `unlinked` because §157 had just given `stale_approval` a writer. This slice did not have
to move it again — `unlinked` is still writerless — but the remaining borrowable columns are now
**two**: `unlinked` and `orphaned`. The next derivation of either will move that test a third time,
and it is worth knowing in advance which one it will be.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL in 7m33s — 2429 tests across 503 result classes, 0 failures.** §158 adds **+9 tests, +1 result class**: `CaseHealthRecomputeTest$ConditionDriftSignal` — named for the signal and not `ConditionDrift`, because a nested class colliding with the production class it tests is a two-import file waiting to happen. |
| `cd frontend && npm run build` / `lint` / `test:run` | **run — build green, oxlint 0 warnings, 1205 tests passing.** One line of copy is in this commit, so all three were run rather than declared unaffected. |
| `packages/` | unchanged — not run. No `packages/` source is in this commit. |

`CD-1`, `CD-2` and `CD-3` were **run** as mutations during the slice and each reddened exactly the
one test it predicted; `CD-4`…`CD-7` are recorded and not run. That is three inline runs against a
queue policy that says zero, and §157 ran one — the honest count across the two slices is four, and
it is written at the top of the `CD-*` block rather than left for a reader to total up. One browser
block, `CD-B1`…`CD-B3`, in lane 4 beside `HP-1`…`HP-3` and `SA-B1`…`SA-B3`; its fixture is the
cheapest of the three, needing no review settings and no second account.

## What is next

§157's list, minus what this slice took:

1. **The three remaining health derivations** — `unlinked` and `outdated_spec_changed`
   (`documentation`, which this module may **not** depend on, so those two are a seam and not merely
   a statement), and `orphaned`, which needs a decision before it needs code: "owner left the
   project" is the permission model's question, and answering it in SQL here would be a second
   implementation of it.
2. **An archive operation for a project**, or the decision not to have one — `archived` is a
   required contract field with no way to become true.
3. **`IngestionJob.rawStorageKey`**, still: §67's port plus a read operation, and known since §156
   to have no writer either.
