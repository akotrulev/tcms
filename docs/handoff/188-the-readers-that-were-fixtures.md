# §188 — the seven readers that were fixtures, and the assignment that wrote nothing

*2026-08-13. Three phases. `backend/` + one frontend comment. **No migration, no contract change.***

§187 measured its own successor and wrote the numbers down rather than taking them:

> **the eight findings phase 3 measured and did not take** — seven readerless columns and one
> writerless, visible only once the column rules' corpus excludes `src/test`. `TestRun.releaseSortKey`
> is the one to read first: §151 shipped that column *as a fix* and nothing in the product consults
> it, so the repair and the mirror defect landed in the same slice. Taking this means narrowing the
> corpus, excluding `@Version` from the writer rule, and answering all eight — it is a slice, and
> the numbers are above so it can be scoped before it is started.

Both corpora are `src/main` now. The numbers held exactly — 6 → 22 writerless with fifteen
`lockVersion`, 11 → 18 readerless — which is worth one sentence on its own: **a slice scoped from a
prior slice's measurement did not need re-scoping once it started**, and every slice in this repo
that was scoped from a reading has.

The count of findings did not hold. It was nine.

---

## What shipped

**Phase 1 — the writer rule's corpus**, `@Version` skipped, and the three `change_comment` columns.

**Phase 2 — the reader rule's corpus**, seven columns argued at their fields, and one javadoc
corrected.

**Phase 3 — `SeedDataTest.areasMatchTheSpa`**, the check for the sentence that is `permission.area`'s
only reason to exist, plus its line in `build.gradle.kts`.

No migration in any of it, and no column was deleted or given a reader. **This is an observer slice,
and the honest description of nine of its findings is that they are now stated where a build can see
them.** One is a real correction and it is in phase 2.

## THE FINDING — `= null` is a writer, and it is the one spelling no corpus change can reach

`V1` gives all four version tables a `change_comment`. Only `test_case_version` has an endpoint that
takes one. So three of the four columns are unwritten, and `everyMappedColumnHasAWriter` reported
**one**:

| | | Reported |
|---|---|:--:|
| `DocPageVersion.of` | omits the field | ✅ |
| `SuiteVersion.of` | `version.changeComment = null;` | — |
| `TestPlanVersion.of` | `version.changeComment = null;` | — |

The rule reads bytecode, because a writer is an assignment and an assignment is exactly what bytecode
records — that argument is right and is why the rule works. An explicit `null` is an assignment. So
the two tables that *said* they had no comment were silent to the rule and the one that merely
omitted the field was the only finding.

This is not the fixture problem in a new place. **It is inside `src/main`**, where narrowing the
corpus cannot reach it, and it is invisible to the reader rule's mirror as well: a no-op write is a
write. What makes it worse than an ordinary blind spot is how it reads — `x = null` in a factory is
tidy defensive initialisation, the sort of line a reviewer adds for symmetry with the field above it,
and adding one to `DocPageVersion.of` would have been the obvious way to "fix" phase 1's red run.
Both are deleted; all three columns are in `WRITERLESS_BY_DECISION` with the argument at each field.

`§188 NR-2` is the mutation row, and there is no rule behind it. Detecting an assignment of the
literal `null` needs the constant on the operand stack, which ArchUnit's field-access model does not
carry — so what stands between this and the next instance is a paragraph and a queue row, which is
precisely the arrangement §152 exists to complain about. Recorded as such rather than dressed up.

## The sentence that had been this rule's foundation, and why it was answering a different question

`everyMappedColumnHasAReader` has said since §154 that a test which SELECTs a column is that column's
reader, and it cited §152: *a seam whose only consumer is a test is a seam with a consumer.*
`AuditEvent.actorTokenId` was the worked example, in the rule's comment and at the field.

§187 is where the distinction that undoes it got its name, one granularity up. §153's rule is about
**seams** — a method exists in order to be called, so a test calling it is a caller, and
`everyPublicBeanMethodIsExercised` counts it. **A column is not a seam. It is a value**, and this
rule's own failure message has always said so: *"that is not waste, it is an unverified value."* A
fixture that selects a column proves the write happened. It does not make anything consult the value,
and consultation is the only property that can make a wrong value matter.

`release_sort_key` is the proof, and it is not hypothetical. Written on three tables, indexed on
three tables, copied out of `test_execution` into `execution_fact` by `ExecutionSource` — and there
is no `WHERE` and no `ORDER BY` over it anywhere in `src/main`. That is how it sorted a release below
its own release candidate from M0 to §151 with the whole suite green, and **this rule was silent
throughout, because §151's own tests name the column while asserting the fix.** A rule whose corpus
includes the tests written *about* a defect cannot report that defect.

## The nine, and what each of them is

Seven readerless, one writerless, plus the two the `= null` was hiding.

| Column | Why it stays, in one line |
|---|---|
| `TestRun.releaseSortKey` | §151's finding 3, recorded and deliberately not acted on: the reader is §6q's latest-release view and is a feature. |
| `TestExecution.releaseSortKey` | The same, one table along — and the hardest to see, because `ExecutionSource` *does* select it, into a column nothing orders by either. |
| `AuditEvent.actorTokenId` | §132 spent a slice giving it a writer. The audit trail still has no screen, which is `bulkOperationId`'s argument since §152. |
| `ReportArtifact.generatedAt` | Its field javadoc predicted this entry in as many words, including the reason it was not yet in the map. |
| `SuiteVersion.contentHash` | A hash exists so a *later* version can be compared to an earlier one, on the one version table that has never written a second row. |
| `Permission.area` | Below. |
| `ClassificationDimension.controlled` | Mapped, not on the wire, one value in every deployment — the cheap end of the list. |
| `DocPageVersion.changeComment` | No endpoint takes one. |
| `SuiteVersion` / `TestPlanVersion.changeComment` | The same, and the two the `= null` hid. |

**Two of them arrive by the argument that used to be the argument against them.** `actorTokenId` and
`generatedAt` were each cited — at the field, and in the rule's own comment — as the example proving
a test SELECT counts. Both entries now read as inversions of their own field javadocs, and the
javadocs say so rather than being rewritten. That is the shape to keep: a decision that reverses is
worth more with its reversal visible than tidied.

## One correction, in the file that already contained the fact contradicting it

`TestExecution.releaseSortKey`'s javadoc said:

> `test_execution_release_idx` leads on this column, so leaving it null while the release is set
> makes the release-filtered aggregate — the one every report runs — skip the rows it is about.

There is no such aggregate. Every release filter in the product, the reports' included, is an exact
match on `release_version`; nothing filters or orders by the sort key at all. So the consequence has
no query to occur in, and — separately — a null is still indexed in a btree, so it would not "skip"
anything even if one existed.

§152's shape, in the same file where §151's finding 3 had already recorded the fact that contradicts
it. The two lived four fields apart for thirty-seven sections.

## `permission.area` — a readerless column whose whole justification is a claim about another stack

Nothing in the backend reads it. No endpoint serves it, no query selects it, `PermissionRepository`
declares no method. Its argument for existing is one sentence, in `V3`'s header and in `Permission`'s
javadoc since M0: it *"matches `lib/permissions.ts` exactly, because both sides group a 55-item list
for humans and two different groupings would be two different products."*

Nothing checked it, and the three checks that look like they would cannot:

| Check | Compares | Sees the area? |
|---|---|:--:|
| `SeedDataTest.catalogueMatchesTheContract` | seeded **keys** ⇄ `openapi.yaml` | — |
| the SPA's `satisfies Record<PermissionKey, Area>` | its own **keys** ⇄ the contract | — |
| `SeedDataTest.catalogueRowsAreComplete` | that the column is **non-empty** | value never compared |

Both ends are pinned to the same 55 keys and neither looks at the value. A permission moved from
`repository` to `project` on one side alone typechecks, seeds, and passes every one of them.
`areasMatchTheSpa` reads `frontend/src/lib/permissions.ts` and compares the two maps.

The third row is the sharpest of the three, because it is the closest miss: `catalogueRowsAreComplete`
selects `area`, asserts it is non-empty, and its own failure message says *"it must match
lib/permissions.ts"* — the claim, restated inside a check, by a check that does not make it. That is
the same sentence in a third file, and the third file is a test.

**`V3` is not edited.** A comment change is a checksum change and `baseline-on-migrate` is false, so
correcting a shipped migration's prose is a failed `validate` on every deployed database — the
file's own header says forward-only means never editing it. The record went to `Permission` instead,
which is the class the reader arrives at anyway.

**The `build.gradle.kts` line is not bookkeeping.** §185 established that a test reading a file
outside `src/` is *green by cache* unless the build declares it, and the mutation run is this
slice's own evidence: the only change in the tree was a `.ts` file in another stack, and `:test`
re-ran. Declared as one **file** and deliberately not `../frontend/src` — that directory is another
stack's whole source tree, and declaring it would re-run every backend test on every unrelated
component edit.

## What the narrowing did for free

`OWN_FILE` is gone. It excluded this file from its own corpus and it was not housekeeping: every key
in `READERLESS_BY_DECISION` is the string `"Entity.field"`, so each argument for keeping a column was
a string literal naming that column, the corpus read the argument as the reader, and five of the
thirteen members then extant deleted themselves. The arguments live in `src/test` and the corpus is
`src/main`, so the shape is now unreachable rather than filtered — which is a better outcome than a
constant, and is the reason the constant could go.

`everyEntityIsWritableByTheProduct` stays, and not out of sentiment. The two rules disagree in one
direction and it is the direction that matters: an entity whose columns are **all** individually
excused passes the column rule entry by entry while remaining a table the product cannot put a row
in. `case_condition_optout` was exactly that — each of its three columns has an obvious argument, and
the finding was never about any of them. One bit per table is a different question from one bit per
column, and §116 asked the table one.

## Gates

Run on `shanks`.

| Phase | Gate | Result |
|---|---|---|
| 1 | `./gradlew build` | **SUCCESSFUL — 2631 tests / 530 classes / 0 failures** |
| 2 | `./gradlew build` | **SUCCESSFUL — 2631 tests / 530 classes / 0 failures** |
| 3 | `./gradlew build` | **SUCCESSFUL — 2632 tests / 530 classes / 0 failures** (+1: `areasMatchTheSpa`) |
| 3 | `frontend`: build · lint 0 warnings | green |

Rows `§188 NR-1`…`NR-5` recorded and **not run** (§65). Two are **holes** — `NR-4` and `NR-5` — and
`NR-5` is a hole by construction, which is the point of it. **`NR-6` is recorded as ✅ RUN**: it is
the mutation-verification of a check written in this slice, taken while writing it, which is what
makes a green first run evidence rather than a coincidence (§175's `GranteeGroup` is the precedent).

**No browser queue row.** Nothing renders differently — the only frontend change is a comment
above `permissionAreas` naming the check that now reads it. §151 shipped the same way and for the
same reason.

**Built and unverified**, in the sense §65 means, though the phrase sits oddly on a slice whose
product is observers: what is unverified is the nine arguments, and each one is a sentence a later
slice is meant to disagree with.

## The next slice

§187's list, minus what this slice took, plus what it opened:

- **the CLI and MCP cannot decline a condition either**, and cannot change a suite's conditions.
  `caseInputFrom` drops `declinedConditionIds` on the **weaker** half of the argument — the shapes
  do line up, so "it does not type" is unavailable — which makes it the first exclusion in
  `rewrite.ts` to revisit. Unchanged from §187 and now the oldest item on the list.
- **resume the browser queue at `A2 — plans`** — unchanged since §186, and the queue now carries
  seven rows in `M2` needing the suite-condition fixture `§186 A1-2` authors.
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- §184's, untaken: **what the SPA computes from a contract enum the server also computes**;
- §185's, untaken: **a rule that fails when a test reads a path outside `src/` that
  `build.gradle.kts` does not declare as an input** — and this slice is the second caller of that
  pattern, so the population is two and the rule is now worth more than when it was proposed;
- §186's, untaken: **`V1`'s comment on `suite_condition` still describes the tree §98 deleted**, and
  §188 is the reason to stop calling that cheap: correcting a shipped migration's comment costs a
  checksum, so it is a *new* migration carrying nothing but `COMMENT ON`;
- §187's, untaken: **the inert decline is state no screen can show and no test can reach**.

**And two this slice opened.**

- **`= null` defeats the writer rule and nothing but prose stands in the way of the next one.** The
  rule would need the assigned constant, which ArchUnit's field-access model does not carry;
  reading the *source text* for `\.field\s*=\s*null` is the shape that would work, and it is the
  reader rule's own technique applied to the writer rule's question.
- **`releaseSortKey` is one decision, not two entries.** Both rows point at §6q's latest-release
  view, and a run has no edit endpoint either (above) — so the release story is now three recorded
  absences that would be answered by one slice.
