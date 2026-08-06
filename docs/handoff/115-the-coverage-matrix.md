# §115. The coverage matrix — the report a writer unblocked two slices ago, and the gate that stopped being a negation

**Date:** 2026-08-07 · **Branch:** `master` · **Gates:** backend `./gradlew build` **BUILD
SUCCESSFUL, 1870 tests / 383 classes, 0 failures, 0 skipped**; frontend `npm run build` clean,
`npm run lint` 0 warnings, `npm run test:run` **962/962**.

## Why this slice and not another

M2.5's reporting plan (R1–R8, F1–F2) finished at §114, and `HANDOFF.md`'s standing rule is that the
next slice is **re-derived from the code, not from the last plan's tail** (§28, §29, §46). The
derivation:

The reporting plan's §8 lists what it deliberately did not cover. Most entries still have the
blocker they were written with — PDF is an unmade image/infrastructure decision, S3 is deferred
(#28), evidence-bundle export needs the `shared` module decision E declines, scheduled reports need
a notification channel that does not exist, Mermaid's SVG pre-render needs the same headless
renderer PDF does. **Two entries had one blocker between them and it is gone**:

> **Doc-structured reports and Mermaid.** §6i's half. Blocked on `doc_link` having a writer, which
> it does not (§2.1) […]
> **Coverage matrix report.** Same blocker — it is *defined* as a query over `doc_link`.

§93 gave `doc_link` its first writer (`BlockLinkService`), and `DocBlockView`'s header already
carried the supersession. So the coverage matrix was buildable and nobody had noticed.

**Two things the orientation cache had wrong, both in the same direction** — it lags, which is
normal and is why `CLAUDE.md` says to trust the body and re-grep. §95's "still owed" phases 2–4 have
landed: conditions **are** attachable through the API and the frontend **does** have call sites for
the GET/POST/PUT. Neither was a blocker for this slice, but both would have been picked up as work.

## What shipped

Two new files in `reporting`, one new test class, prose in the contract, and one frontend arm.
**No migration** — `V35` is still head and `V36` still free. **No table, no column, no permission
key**; policy count stays **61**. It became requestable **by existing**: `ReportComputations` is a
registry over every `ReportComputation` bean, so nothing in the controller, the validation or a list
was edited — R3 built that seam for exactly this.

| File | What it is |
|---|---|
| `reporting/DocumentationSource.java` | The module's **second** foreign-table seam — `doc_page`, `doc_block`, `doc_link` |
| `reporting/CoverageMatrixReport.java` | `type()` = `coverage_matrix`; the spine, the ladder, the `DISTINCT ON` over `execution_fact` |
| `reporting/CoverageMatrixReportTest.java` | 18 tests, fixture prefix **`c5`**, slug `cvm-` |
| `docs/specs/openapi.yaml` | Prose only — a third `type` bullet and a `parameters` paragraph; `gen:api` moved JSDoc and **no type** |
| `frontend/…/GenerateReportDialog.tsx` | The third type, its document picker, its emptiness gate |

### A second seam, not three more methods on `ExecutionSource`

The rule this codebase settled on is **one seam per foreign module**, never one per module —
`work.BoardFacts` reads two and says so in its name, and `documentation` holds `PlanFacts` **and**
`CatalogueFacts` for this reason. `ExecutionSource` already carries seven tables across three
modules; a fourth module's three tables would make the one file whose entire purpose is to keep the
coupling *reviewable* the file nobody can review.

**The case labels are deliberately not in the new seam.** They are `test_case` —
`repository`'s — and `ExecutionSource.caseLabels` has served them since R7. A second copy would be
one table read two ways inside one module, which is the drift the single-seam rule exists to
prevent.

**The project bound is `findPage`'s alone.** `doc_block` and `doc_link` carry no `project_id` at
all, so the only route to one is the page; re-deriving it per query would join `doc_page` three
times to re-answer a settled question. So `findPage` answers it once and refuses, and the other two
methods take the id it returned — which means **calling them with a page id that did not come from
`findPage` reads a sibling project's document**. They are package-private, take a page id rather
than a project id, and are called from one place. RLS scopes to the company and no further; this is
the sixth file in the product layering a project predicate over it, and see below.

### The decision of the slice: what counts as a statement

A coverage report has to decide which blocks are candidates for coverage, and the tempting answer —
all of them — is one this module has already argued against in writing. `DocBlockView` refuses to
serve a zeroed inline `coverage` marker because a zero *"does not read as 'unknown', it reads as 'no
test case verifies this statement'"*. Making every paragraph a row of this report is the same claim,
in the document somebody hands an auditor.

So a **statement** is a `list` block, **or** any block that already carries a `VERIFIES` link:

- §93 made `level` a third arm on `list` precisely so a nested test-scenario outline is linkable per
  bullet — *"a `doc_link` targets a block, so a scenario a case can verify must BE a block, so one
  bullet is one block"*. The bullets are what the document intends to be verifiable, which is the
  question being asked.
- A block somebody has linked is one they have asserted is coverable **by asserting it is covered**,
  and a report that hid the very rows a reader created would be unusable.

Consequence, and it is a feature: **a page of prose reports no statements**, with `Coverage` as an
em dash and a sentence saying so — not `0.0%`, which tells a reader their charter is an untested
specification.

### `VERIFIES` only, and the two relationships that are not a rounding error

`LinkRelationship`'s header says it: a link recorded as `VERIFIES` when the case merely `REFERENCES`
the statement *"is what turns a coverage number into a false claim"*. This query is where that
sentence holds or does not. A block whose only links are `REFERENCES` or `DERIVED_FROM` is
**uncovered**, which will look wrong to whoever created those links and is the entire reason the
author was asked to choose a relationship. `source_type = 'TEST_CASE'` for the same argument one step
out: the CHECK permits `SUITE`, and a suite link is a claim about a collection rather than evidence
of a test.

### The ladder is pessimistic, and the two absences are different problems

Not covered (no `VERIFIES` link) → **Failing** (any linked case whose latest result is not a pass) →
Not run (covered, nothing recorded) → Verified. **Failing outranks verified**: four green and one
red is not a verified requirement, and §46's rule is that a roll-up must not take the direction that
makes the report look better than the truth. **Not run stays distinct from Not covered** — *nobody
has tested this* and *this fails* have different owners, and folding the first into the third tells
somebody to go and write a test that already exists.

### The policy is fixed to LATEST, and that is a decision rather than a default

Slice-plan decision D: a report that quietly means `LATEST` has a default nobody chose.
`AggregateExecutionReport` makes the policy a parameter because it asks *"how did this release
go"*, where every execution is evidence. A coverage matrix asks *"is this statement verified now"*,
and there is one answer. `WORST` over a case fixed three weeks ago prints a failure that no longer
exists, against a requirement, in the document a release board reads. The `ORDER BY` is
`resolvedCte`'s `LATEST` arm clause for clause — including `NULLS LAST` and the `execution_id`
tiebreak — and the metadata prints the policy rather than leaving a reader to assume it.

**Check both when either moves.** Two answers to "the latest result" that disagree only on ties is
the worst kind of disagreement, because it is reproducible and rare.

## The frontend gate that stopped being a negation, and it would have shipped broken

R6 spelled the run gate `!isAggregate`. With two report types that was the same statement as *"this
type needs a run"*. **At three it stops being one**: `coverage_matrix` is not the aggregate, so it
would have inherited the run gate and been **unreachable in exactly the projects that have
documentation and no runs** — which is §66's shape and, precisely, the defect R6 itself was written
to fix, arriving a second time through the shape of that fix rather than through the code.

The gates now key on what a type **needs** (`needsRun`, `isCoverage`), so a fourth report opts in
rather than being enrolled by not being the others. The second emptiness gate is real rather than
symmetry: a coverage matrix's whole spine is a document, so a project with no pages has nothing to
report on.

`GenerateReportDialog.test.tsx` gains four tests, and the negated-gate mutation was run: it reddens
`can be asked for in a project with no runs at all` and `cannot be asked for in a project with no
documentation`. The passing version of those two tests is worth nothing; the reddening is the point.

## Five mutations were RUN, and that is a stated deviation from §65

The class went **18/18 green first time** — the exact state §114 was criticised for (*"THIS SLICE
HAS NO RED RUN"*), and §18 of the mutation queue concedes that its structural substitute *"is sound
and it is still an argument"*. So five mutations were run during the slice rather than queued:

| Mutation | Reddened |
|---|---|
| `latestResults` resolves to the **oldest** result (`ASC`) | `theResultIsTheLatestOne` + the ladder test |
| The link query drops `relationship = 'VERIFIES'` | `onlyVerifiesCounts` + the ladder test |
| `stateOf` returns `VERIFIED` on the first pass (optimistic) | `oneFailingCaseMakesTheStatementFailing`, **and nothing else** |
| `isStatement` → `return true` | four tests, including both denominator tests |
| The link query drops its `DISTINCT` | `duplicateLinksCountOnce`, alone |

The fixture is therefore **not vacuous** — R6's trap, where inverting `LATEST` passed a whole class
because the fixture's two multi-result cases were exact mirrors. The two results here are
deliberately not mirrored: `FIXED` failed and then passed, and reversing time makes it fail.

**This is not licence to run the cheap checks during a slice.** §72–§76 did that and were reversed
for it. It is the specific remedy the previous section of the queue asked for, and the four
remaining rows (CM-6…CM-9) stay unrun.

## Deliberately not built, named rather than dropped

- **`openDefects`.** §6e's catalogue line is *"Requirement → cases → last result → open defects"*.
  There is no defect anywhere in this product — `IssueTracker` adapters are M4 and `work` items are
  not defects. Named as absent rather than rendered as an empty column, which would read as *"no
  defects are open against this requirement"* — a claim, where an absence is not one.
- **The subtree.** §6i offers *"a `DocPage` (or a subtree)"*. One `path <@` predicate away, and left
  out because a page's descendants are a different *document* in every space this product seeds; a
  scope that silently swept in three sibling specifications would be wrong in the direction nobody
  checks. When a caller wants one it is a parameter, and the header already prints what was asked.
- **`DocBlockView.coverage`** is **still** absent, and its reason has not moved. That marker is
  `documentation`'s, its five required fields include `passed`/`failed`/`notRun`, and those are two
  modules that package may not name. This report is the *other* side of the same question — a
  `reporting` computation reading three modules through two seams — and building it does not make
  the inline marker any more reachable from where it would have to be served.
- **Mermaid.** The other half of §6i's report story, and untouched: it needs a `DocBlockType` member
  that does not exist and an SVG pre-render for export that needs the same headless renderer PDF
  does. Still blocked, and blocked on infrastructure rather than on a writer.

## Carried debt

**The project-over-RLS predicate is now in six files with nothing checking any of them** —
`DocumentationSource.findPage` joins `PlanFacts`, `CatalogueFacts`, `ExecutionSource.findRun`,
`.caseLabels` and `.caseVersionNumbers`. R7-6 and R8-7 each recorded it as a gap for one method;
CM-6 is the third recording. **The row that closes all six is one two-project fixture written once
for the seam**, and it should stop being re-recorded per method every slice.

**The frontend suite is flaky under full-suite load.** The `npm run test:run` before this slice's
tests were added reported 2 failures in `CaseEditorPage.test.tsx` — a file this slice does not touch
— which passed 23/23 in isolation immediately afterwards, and the full suite has been green on every
run since. Recorded because a suite that fails 2-in-958 under load and passes on retry is a suite
whose next real failure gets retried away.
