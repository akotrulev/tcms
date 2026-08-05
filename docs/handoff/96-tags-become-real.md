# §96 — Tags become real, and a suite resolves from them

**Date:** 2026-08-05 · **Plan:** `docs/plans/2026-08-04-test-case-authoring-rework.md` (slice §96)
**Status: COMPLETE — SIX PHASES, BUILT AND UNVERIFIED.** Six commits (`988c71a`, `9693f53`,
`03d5235`, `5dd53f5`, `908c780`, `097f7a7`) plus this one. Every gate is green and **not one queued
check has been run**: 14 browser rows (74–87) and 20 mutation rows, zero executed, per §65. Two of
the mutation rows record checks that are **weaker than they look**, and one names a test that
**does not exist yet**. See §9.

**`V31__suite_membership.sql`**, RLS policy count **59 → 61**, `V32` next free. Contract: two new
required fields on `Suite`, three shapes' suite fields **nullable in pairs**, `TestCaseInput.suiteId`
optional, two new query parameters. Backend **1717 → 1741**, frontend **820 → 834** across 56 files.

## 0. What this slice is

§94 decided a step is text that belongs to its case. §95 made a condition the shared object, with a
writer. This one is the last of the authoring rework's three, and it is the structural one:

> **A suite is a resolvable collection, and membership is optional.** A suite's members are the
> cases filed in it, plus cases added individually, plus **every case carrying any of its tags** —
> unioned and deduped. A case may be created freely with no suite and no tags.
> *(Authoring-rework decision 4.)*

Before it, `test_case.suite_id` was `NOT NULL`, so a case that was both a Payments case and a smoke
test had to pick one, and a case nobody had filed yet could not be created at all. Tags were
half-built in the way that is worst: `case_tag` existed since `V1`, `tags` was on `TestCaseInput`,
and `CaseUpdateRequest` **rejected it with a 400** — a column with a reader, a contract field, and
no writer.

## 1. THE FINDING — a nullable foreign key drops rows in four places, and every one is silent

`test_case.suite_id` going nullable is one `ALTER`. What it costs is four queries that read
`suite_id` in a join or an `IN` list, each of which stops returning rows rather than failing:

| Where | The spelling | What it produced |
|---|---|---|
| `ExecutionSource.projectRecordedExecutions` | `JOIN suite s ON s.id = c.suite_id` | The **whole fact row** — not the suite columns, the row — never inserted for an unfiled case. A short fact log, a tick that logs success, every report smaller than the truth. |
| `CaseSpecifications.suiteScope` | `suiteId.in(liveSuiteIds)` | `IN (…)` is never true of NULL, so **every unfiled case is absent from the grid** — the one screen somebody would look for it on — with a total that still counts it. |
| `ProjectRepository.countLiveCasesByProject` | `JOIN suite` | The picker said **6 cases** over a list of **7**. This is the very defect that method was written to fix, arriving from the other side. |
| `CaseFacts.casesInPlanScope` | `JOIN suite cs ON cs.id = c.suite_id` | The one plan-scope query of three that joins the case's *own* folder, so a plan naming an unfiled case would count it and not list it. §32's two-numbers defect. |

The plan named the first. **The other three were found by tests, not by reading** — the picker one
by an assertion that exists solely to compare two screens' numbers, which is the only reason it was
not shipped. The rule this leaves behind is in the orientation cache: **grep for `JOIN suite`
before adding one.**

Everything downstream is decided by the same question, and answered the same way each time: a
NULL group is a **row**, never an omission. `RunSummaryReport` prints `(not filed)`,
`PlanStatistics.bySuite` sends three nulls together, the grid renders muted italic **Not filed**,
and the plan panel labels it. Dropping any of them would make a section stop summing to the
headline above it, in the direction nobody checks.

## 2. Membership is one union with two implementations, and that is forced

`repository.SuiteMembership` is the union — filed ∪ added ∪ tagged, `COUNT(DISTINCT c.id)` — for the
tree, the counts and the case grid. `execution.CaseFacts#inSubtreeOf` is the same union for plan
scope and a suite's execution history.

It exists twice because **ERD §0.2 lets the `execution` module read the repository's tables only
through its one seam class**, and calling across would trade a duplicated predicate for a dependency
`ModuleStructureTest` refuses. So the drift is caught rather than prevented:
**`SuiteScopeAgreementTest`** asserts both resolutions answer the same set — `PlanStatisticsTest`'s
device for the three plan-scope expansions, one module out.

Two properties make that class non-vacuous, and both were repairs:

- **Only ONE of its four live members is *filed* anywhere under the root.** With everything filed,
  the union and the old column answer identically and the whole class proves nothing.
- **It runs inside a `TransactionTemplate`.** `CaseFacts`' methods carry no `@Transactional` — every
  production caller is a service that already has one — so calling them straight from a test runs
  them outside any transaction, `app.current_company` is never set, and RLS answers **zero rows**.
  The first draft compared an empty list to an empty list and reported agreement. The context names
  the company; the transaction is what puts it on the connection.

It also pins the one place the two must **disagree**: the seam **includes soft-deleted cases**,
because a suite's history says what a run covered at the time and dropping a case somebody deleted
last week lowers the totals of runs that finished before the deletion. The repository's union
excludes them, because its readers are the tree and the grid. Asserting only "they agree" would have
made the fixture's deleted case a reason to weaken the class rather than a difference on the record.

## 3. TAGS ARE NOT AUTHORED CONTENT — a recorded departure from the plan's own line

The plan said `CaseEdit` validates and persists tags. It does not. They ride **beside** the edit on
`CaseEdit.Outcome.Valid`, so `CaseVersions#hash` never sees one.

`CaseVersions`' whole argument is that the hash is over *authored* content, or the version list
fills with entries whose diff is empty — §6j's noise. Labelling a case `sprint-42` changes nothing
about what the test says. **`CaseBulkService` already held this position** in the only way that was
observable before now: its `AddTags` deliberately does not stamp `updatedAt`, "because a tag is not
authored content and moving the timestamp would make every 'edited recently' list a list of who ran
a bulk tag."

It has a second consequence worth having: §95's bulk-hash trap becomes **structurally unavailable**.
A bulk action reconstructs a request without mentioning tags, and since tags are not hashed there is
nothing for that omission to mis-hash. The mock mirrors it — a tag change does not bump the version
there either.

## 4. AND versus OR, in the two places it points opposite ways

The grid's `?tag=` filter **ANDs**: two chips must shorten the list. A suite's `tagRules` **union**:
a suite claiming `smoke` and `payments` holds every case carrying either.

That is not an inconsistency, it is the direction of the intent — a list is being **narrowed** in one
and a collection **assembled** in the other — and both are stated on the contract and in the helper
text under each control, because nothing on screen can otherwise tell them apart. Intersection for a
suite is the other defensible reading and it is refused for a concrete reason: a two-tag suite that
silently holds nothing is indistinguishable from an empty project.

## 5. What a suite may do to a case, and what it may not

`SuiteMembershipWriter` reconciles the two arms a suite **owns** — its tag rules and its
individually-added cases — and never the third. **Filing is `test_case.suite_id` and belongs to the
case's own write path**: a suite edit that could re-file cases would let two screens write one
column, and the loser is whichever saved second. So a suite may *claim* a case and may never *move*
one.

`caseIds` is on the contract, validated against the project's live cases (a sibling project's id
satisfies the foreign key, the company column and RLS — only the project predicate refuses it), and
**authored by no screen**: the dialog offers tag rules only. Named here rather than left to be found
missing.

## 6. The counts stop summing, and the UI has to say so

`Suite.caseCount` is now resolved membership. A case can be filed in one folder, added to a second
and tagged into a third, so **the tree's counts no longer sum to the project's case count**. That is
the design, and it is on the contract, on the tooltip over every count, and in this section, because
a reader who subtracts otherwise finds a discrepancy and reports a bug in a correct number.

The fixture's `Smoke` suite (TS-10) exists for the same reason: **every other folder in mock mode
answers the same number under the old rule and the new one**, so without a folder whose membership
is a query, none of this is observable without a backend.

## 7. The frontend defect this slice shipped and fixed

Both new tag controls replaced MUI's `slotProps` in order to add a `data-testid`. MUI v9 puts the
selected **chips** in `slotProps.input.startAdornment`, so replacing it drops them: every tag was
held by the form, sent on save, and **invisible on screen** — an author types a tag, sees nothing,
and types it again.

`npm run build` and `oxlint` were green, and **the payload assertion passed against it**. The fix is
`...params.slotProps` first, which the step-action `Autocomplete` has done correctly since §94 — the
precedent was in the same file. The tests now assert a chip is **visible**, which is the only check
that fails.

## 8. Gates

| | Before | After |
|---|---|---|
| Backend | 1715 | **1741** (build clean, `-Werror`) |
| Frontend tests | 820 | **834** across 56 files |
| Frontend build / lint | clean / 0 | clean / 0 |
| RLS policies | 59 | **61** |
| Flyway head | `V30` | **`V31`**, `V32` free |

Bundle, measured per the working agreement: `CaseEditorPage` 88.19 → 89.15 kB raw, `CaseListPage`
435.09 → 436.36 kB, entry **unchanged** at 336.84 kB.

## 9. What is NOT done — and the queues nobody ran

**This slice is built and unverified.** 14 browser rows (74–87) and 20 mutation rows are queued and
**zero have been run**, per §65. Read that as the status, not as a formality:

- **One mutation row names a test that does not exist**: a plan scoped on a tag-defined suite,
  creating a run, end to end. The agreement test covers the predicate one layer below where it
  matters.
- **Two rows record checks weaker than they look**: the project predicate on the tag arm is asserted
  through a count rather than through a leak (the sibling project has no tagged cases), and dropping
  `suite_id IS NOT NULL` from `countLiveCasesBySuite` reddens **nothing** today.

Still not built:

- **No screen authors `suite_case`.** The endpoint validates it; the dialog offers tag rules only.
  A case-picker on the suite dialog, or an "Add to suite" bulk action, is the natural next slice.
- **`CaseUpdateRequest` still refuses two fields** — `ownerId` and `automationTags`. The second is
  deliberate: `case_automation_tag` is CI selection (§6c), a different table and a different job, so
  accepting `tags` was not a reason to accept it.
- **No tag suggestions anywhere.** Both tag controls offer an empty option list, because there is no
  endpoint that lists a project's tags and a curated one is what left `shared_step` empty. A
  "tags in use" endpoint is the obvious follow-up and is not this slice's.
- **Membership is not versioned.** `suite_version` holds composition, so a tag rule changing is
  exactly what a v2 would be for — and nothing writes one today (`SuiteVersions` writes v1 and
  nothing since). That is the composition-versioning slice.
- **§97 is unblocked.** `docs/plans/2026-08-05-test-plan-detail-rework.md` sits behind this slice
  for one reason — its decision D2, "no suite when adding a case", needed `test_case.suite_id`
  nullable. It is.
