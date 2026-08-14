# §206 — The two corpora nothing scanned, and the room two doorways opened

**Three phases**, `packages/` (one test file) plus `docs/specs/openapi.yaml`, both generated `schema`
copies, **twenty javadoc comments in `backend/src`**, one field's visibility and one new test. No
migration. Nothing on the wire moved: the contract change is one `description`, so both
regenerations are comments-only, and the only non-comment Java edit is `private` → package-private.

§205's opened list, first two items, taken verbatim. It built the cited-files rule over both
TypeScript stacks and named the two corpora it had left out — the contract's own prose, and
`backend/`'s javadoc — predicting the second would be "the one most likely to be non-empty". Both
are non-empty, and the second is non-empty in a way the prediction did not reach: the largest
population was not a path at all, and phase 3 found a pin that consisted entirely of the sentence
claiming it existed.

## Phase 1 — the two corpora

### The contract, and the reason §205 skipped it

§205's header excluded `docs/specs` in one sentence: the two generated files carry `openapi.yaml`'s
prose, *"which cites files freely and is not this corpus's to police."* That is an argument for not
scanning the **copies**. It was read as an argument for not scanning the **source**, and those are
different claims about different files.

The population is one, and §205 had already found it — the `fe-react` orientation cache recorded it
as a consequence *"recorded rather than fixed"*. `WorkItemStatus`'s description has said this since
§54:

> All three land together, and `boardColumns.test.ts` now derives the board's columns from this enum
> so the next member cannot arrive without one.

There is no such file and there never has been. **The check it describes is real, in two places, and
neither is a test**: `workItemColumns` in `frontend/src/lib/labels.ts` is `Object.keys` of a map
declared `satisfies Record<WorkItemStatus, string>`, so labelling a new member gives it a column in
the same edit; and `BoardPage.tsx`'s `_everyStatusHasAColumn` asserts the result still covers the
enum, written as a `Record<Exclude<…>, never>` specifically so the compiler names the missing status.
Both are compile errors. The sentence promised the mechanism a reader would go looking for and named
a file they would not find, which is §205's population exactly — a filename standing in for the check
it describes.

Repaired in the description, and both `schema` copies regenerated. **This is the corpus whose prose
the most readers meet**: `npm run gen:api` copies every description verbatim into `schema.d.ts` *and*
`schema.ts`, so one stale sentence here is three on disk, and the two downstream ones cannot be
repaired where they are read. The generated pair stays out of the corpus, now for the reason that
always applied — a copy reports its source's defect a second time and is not the place it is fixed.

### `backend/src`, and why the rule is not a Java rule

§205 said the same question there *"belongs to a rule of its own"*. True of the **scanner** — javadoc
writes `{@code}` where the other three corpora write a backtick — and false of the **home**.

`{@link}` is not scanned, and that is the whole shape of this corpus. It resolves against the
compiler's symbol table, so a stale `{@link}` is a build failure today. `{@code Foo.java}` is a
formatting instruction and resolves against nothing. **So the citations javadoc can get wrong are
precisely the ones written in the form nothing reads**, which is why §205's prediction was right.

A Java test was the obvious home and is the one place the rule cannot work, for a reason this
repository already owns — §185's Gradle-cache trap, which `build.gradle.kts` documents at length. A
`Test` task's declared inputs are its classpath and its resources, so it sees a `.java` file only
through the class compiled from it. Two consequences, and the second is the one that matters:

1. **A comment edit that does not change a line count leaves the class byte-identical.** Measured:
   `./gradlew test --tests ConfigConventionTest` ran and passed, one word of `CaseSort`'s javadoc
   changed, and the identical command answered `> Task :test UP-TO-DATE` in one second. `:compileJava`
   re-ran; nothing downstream noticed.
2. **A stale citation is usually not caused by editing the citing file at all.** It is caused by a
   rename or a deletion somewhere else, and `backend/`'s declared out-of-source inputs are a
   Dockerfile, two compose files, `deploy`, `docs/specs` and exactly one `.ts` file. Deleting
   `src/mocks/` changed no backend input by one byte.

**Phase 1 asserted (1) as if it covered both, and this slice's own phase-2 gate disproved the strong
form** — nine repairs changed line counts, the line-number table shifted, the class files differed,
and the suite re-ran. Corrected in the header rather than left: (1) is a trap, not a wall, and (2) is
the unconditional half. Vitest has no such cache, so the check runs on every `npm run test:run` and
reads the tree as it is on disk.

The cost is the one §205 already stated, one stack wider: **a stale javadoc citation in `backend/`
reddens the `packages` job.**

### What it found

Two names, and they are different kinds:

| name | cited by | verdict |
|---|---|---|
| `handlers.ts` | `CaseSort`, `ConditionType` | stale — the mock layer was deleted 2026-08-06. Two present-tense paragraphs arguing from it. |
| `testng-results.xml` | `TestNgReportParser`, `ReportFormat` | correct, and **not ours** — the caller's CI output, which is what this product ingests. |

The second is a new kind of by-decision entry and the kind this product is *for*: **a path in the
caller's project, not in ours.** `pom.xml` joined it in the same phase — it had been outside the
pattern until the extension list widened for Java's `.xml`/`.kts`/`.properties`, and `lib/labels.ts`
names it to say why the four ingestion-format labels keep each tool's own capitalisation.

### One more thing phase 1 changed, unprompted

**The vacuity guard is per corpus now.** §205's guard counted the whole population — `files.length >
300` — which was sound with one big root and is not with four: an entry whose directory was renamed
contributes zero files and the total still clears a threshold `frontend/src` meets on its own. A
guard against a silent empty scan, silently scanning less than it says.

## Phase 2 — directories, and the room they open

A cited path ending in `/` is the same claim, so it stayed the same rule: one alternation branch in
the pattern, a trailing marker in the walk so a file called `mocks` cannot satisfy a citation of the
folder. Measured before it was written, because a rule whose failures are mostly noise gets silenced:
**fifteen directories are cited across the four corpora and three do not resolve.**

| name | cited by | verdict |
|---|---|---|
| `allure-results/` | `AllureReportParser` | the caller's tree — third entry of that kind |
| `dist/` | both `harness.ts` copies | ours, but pruned by the walk; exists only after a build |
| `src/mocks/` | `ContractParameterBindingTest` | deleted in §107, and the citation is **entirely right** |

`dist/` is the one entry where the path is ours and the *scanner* is what cannot see it. Written that
way rather than by un-pruning `dist`, which would make the check's answer depend on whether anybody
had run a build.

As a finder, that half is a guard. What it is worth having is the third row.

### The fifteen sentences the rule cannot reach

`ContractParameterBindingTest` names `src/mocks/` in a past-tense sentence about why it stopped
skipping unimplemented operations, and is the one comment in `backend/` that names the mock layer and
is right about it. Sweeping the concept rather than the path: **fifteen comments in `backend/src/main`
argue from the SPA's mock**, and only two of them name a file — which is why phase 1 found two.

Six are correct as written, and they are correct because they are **past tense**: a record of a
defect the code now exists to prevent. `BulkVerdict` — *"The MSW mock violated this and it is the
defect this type exists to prevent"* — is the exemplar; `WorkItemStatus`, `ExecutionSource`,
`CaseBulkService` (twice) and `ReportDefinitionService` are the rest. Left alone. This repository's
rule is to write the superseded decision *and* its reversal, not to delete the history.

Nine made a **present-tense claim about a live second implementation**, which is a different thing:

| file | the claim | repair |
|---|---|---|
| `ConditionRepository` | the search is title-only *"(matching the SPA's mock, which the picker was built against)"* | past tense — the shape the picker **was** built against |
| `CaseSearch` | the facet semantics *"matches the SPA's mock, which is the shape the UI was built and driven against"* | past tense; the product decision stands without it |
| `LifecycleLadder` | the wording is centralised because *"the mock has to be able to copy exactly one wording"* | struck; §52's reason never needed the second one |
| `CaseQuery` | `TC_1` is accepted because *"the SPA's mock also accepts"* it | **the whole stated reason was the mock.** Replaced with the real one: a spelling is accepted because a person types it |
| `CaseController` | *"The MSW mock has answered exactly this since Phase 1"* | past tense |
| `CaseCreateService` | *"the mock has always honoured it"* | past tense, and the property is pinned by a test now |
| `CaseUpdateRequest` | *"the failure the SPA's own mock handler comments on"* | the failure needs no attribution |
| `DocBlockType` | *"the MSW fixtures are written in them"* | struck; the SPA's branches are the load-bearing half |
| `CaseSort` (phase 1) | *"mock and live disagree here on purpose. The mock is the one that is wrong"* | struck with the reversal |
| `ConditionType` (phase 1) | *"The SPA's mock matches the type exactly"* | struck with the reversal |

`CaseQuery` is the one worth naming twice. Every other repair was a tense; there the mock was the
**only** stated reason for a live behaviour, so the sentence read as a justification and was an
orphan — §160's shape (*"there is no adapter"* as a live reason for a port that had existed since
§125) with the polarity flipped.

### The reach, stated rather than implied

**This rule catches a dead reference, not a dead reason.** It found the two doorways into a room
fifteen sentences wide. That is the argument for having it and the exact measure of what it does not
cover, and it is in the check's own header so the next reader does not mistake a green run for a
swept corpus.

A wordlist rule over the concept was considered and rejected. The mock layer is deleted and cannot
come back, so a permanent scanner for the word would guard a closed door — and this repository has
already paid for an ignore list naming the wrong mechanism (§162's `CONTAINER_ERROR_PATH`, which
exempted a file from a ban it had never been capable of violating).

## Phase 3 — the same question, for a class name

A `{@code SomethingTest}` is the same claim one more time: it says *the check exists*. The rule
appends the extension and asks the filesystem for the file that would declare it. **Eight of
sixty-five did not resolve** — more than both path halves found together.

**Only `*Test`, and the boundary is the argument rather than convenience.** 739 class-shaped
`{@code}` tokens occur in `backend/src` and 392 name nothing in this repository: enum constants
(`ABORTED`), SQL keywords (`CASE`, `DISTINCT`), migrations (`V26`), contract schemas
(`TestCaseSummary`), JDK and Spring types (`HttpHeaders`, `ProblemDetail`). A rule over all of them
needs an ignore list bigger than its finding, which is how a check gets silenced rather than fixed.

`*Test` is the tractable subset **because it is the subset where `{@link}` is impossible.** Test
classes are not on `src/main`'s compile classpath, so a javadoc in `main` that names the check
guarding it *cannot* use the checked form — and this repository names its checks from the code they
guard constantly. That is a population of references the compiler is structurally unable to see,
which is the same argument the corpus rests on, one turn tighter.

| citation | truth |
|---|---|
| `ArchitectureTest` ×2 | `ArchitectureRulesTest` |
| `PlanStatisticsTest` ×3 | `PlanStatisticsEndpointTest` |
| `AttachmentOwnerTest` | the assertion inside `CaseAttachmentEndpointTest` |
| `ReportHeaderTest` | `ReportRenderingTest`, which walks `getRecordComponents()` |
| ~~FilesystemEvidenceStoreTest~~ | renamed in §125; the sentence is *about* the rename, so it is struck |
| `ApiSurfaceTest` ×2 | **nothing — and nothing needs to.** Spring refuses to build an ambiguous mapping, so every context test fails |
| `UnmatchedTest` | not a class: a contract schema whose name ends in `Test`. By-decision |
| `AggregateExecutionPolicyTest` ×3 | two are `AggregateExecutionCrossCheckTest`. **The third was a pin that did not exist** |

`ApiSurfaceTest` is worth naming for its kind: a **framework** guarantee written as a local check.
Somebody looking for the test that enforces it finds no such class and concludes nothing enforces
it — the opposite of the truth, produced by a sentence trying to be reassuring.

### The pin that was a sentence

`AggregateExecutionReport.WORST_FIRST` — the severity ladder, worst first, which becomes a SQL
`CASE` — carried this:

> Duplicated for the same reason the enum above it is, and pinned by `AggregateExecutionPolicyTest`
> against `StatusKind`'s own value set so a kind added to the product cannot silently fall through
> to the `ELSE` and rank as the best possible result.

No class of that name has ever existed, and nothing else in the build made that comparison —
`AggregateExecutionCrossCheckTest`, the real class, never mentions `StatusKind`. **So the pin was
the sentence.** The `ELSE` of that `CASE` is the best-possible rank, so a `StatusKind` added and
forgotten does not error: it ranks as the *best* result a case can have, in the worst-first callout
whose only job is naming the worst, inside a report somebody plans a release from. `StatusKind` has
grown a member before — `WIP`, seeded by `V32`.

The check exists now, in the class the comment names, asserting two lists against each other with no
Spring, no fixture and no transaction. `WORST_FIRST` is package-private rather than `private`, which
is the only thing this slice changed that is not a comment. That is stated in its javadoc, because a
weakened modifier with no visible reason is the next reader's puzzle.

## Gates

All on `shanks`.

| phase | gate |
|---|---|
| 1 | packages build ✅ in order · lint ✅ 0 ×3 · **302** ✅ (78 cli / 119 client / 105 mcp, unchanged) · frontend build ✅ · lint ✅ 0 · **1339 across 100** ✅ unchanged · backend `build -x test` ✅ |
| 2 | packages build ✅ · lint ✅ 0 ×3 · **302** ✅ · backend `./gradlew build` ✅ **2652 · 532 classes · 0 failures** |
| 3 | packages build ✅ · lint ✅ 0 ×3 · **302** ✅ · backend `./gradlew build` ✅ **2653 · 0 failures** (+1) |

The `packages` count does not move across any phase: the check is three `it`s and gained corpora and
patterns, not cases. The backend suite ran in full for phases 2 and 3 — ~7m30s each — because those
repairs shifted line numbers, which is the observation that corrected phase 1's claim.

**Mutations, all reverted:**

- un-striking `CaseSort`'s mock paragraph → red, naming `handlers.ts` and the class (the backend
  corpus and the `{@code}` pattern are live);
- setting the contract corpus's extension to one nothing matches → red on *"docs/specs contributed no
  files"* (the new per-corpus vacuity guard, and the old whole-population one would have passed);
- dropping the `src/mocks/` by-decision entry → red, naming the entry and
  `ContractParameterBindingTest` (the directory branch matches and resolves);
- restoring `ArchitectureTest` in `TokenRateLimiter` → red, naming `ArchitectureTest.java` and the
  citer (the class pattern and the appended extension);
- dropping `SKIPPED` from `WORST_FIRST` → red on the new pin, in 16s.

Two of this slice's own sentences were caught by the check while it was being written — the header's
`` `boardColumns.test.ts` `` and `` `handlers.ts` `` — and a third, an illustrative
`SomethingTest.java`, was caught in phase 3. All three were fixed the way the rule says. Worth
recording: the notation is not a workaround for the check, it is the check's statement of what a
name in this repository means.

Two of this slice's own sentences were caught by the check while it was being written — the header's
`` `boardColumns.test.ts` `` and `` `handlers.ts` `` — and both were fixed the way the rule says, by
striking them. Worth recording: the notation is not a workaround for the check, it is the check's
statement of what a name in this repository means.

## Verification

**No rows.** Nothing on the wire moved, no UI moved, no server behaviour moved: the contract diff is
one `description`, and every other edit is a comment or a test. `packages/` still has no persistent
queue, so §203's, §204's and §205's rows stay where they are and are still ⬜ NOT RUN — `§203 PL-1`,
`§204 PL-2`, `§204 PL-3`, `§205 PL-4`.

## The next slice

§205's list, minus what this took, plus what this opened.

**Opened here:**

- **`docs/` is the fifth corpus and the biggest by far.** The four scanned roots are source and the
  contract; `docs/handoff/*.md`, `docs/plans/`, `docs/specs/*.md`, `docs/audits/` and `CLAUDE.md` are
  hundreds of files of exactly the prose this rule was written for, and `HANDOFF.md` alone is 545 kB.
  The question is not whether the population is non-empty — it is what an honest by-decision map
  looks like when a handoff section legitimately names files that were deleted four sections later.
  Strikethrough may already carry most of it, which is measurable before anything is written.
- **The 347 class-shaped `{@code}` tokens that name one of our own non-test classes.** Phase 3 took
  the `*Test` subset because that is where `{@link}` is impossible. The rest is a different question
  with a bigger payoff: a `{@code}` naming a class that *is* reachable from the citing source set
  should have been a `{@link}`, and converting it hands the reference to the compiler permanently
  rather than to a scanner. The population is measurable per source set and the conversion is
  mechanical; the risk is that a wrong conversion is a build failure, which is the good direction.
- **Nine repairs in phase 2 were a tense, and nothing checks a tense.** The mock sweep was done by
  reading. The general shape — *prose in one stack asserting the present-tense behaviour of another*
  — is what §197–§204 built machinery for in the direction of data, and there is none in the
  direction of claims. Probably not a rule; worth stating as a known blind spot rather than
  re-derived next time.
- **`WORST_FIRST` has a twin one module over and nothing compares the two lists.** Its javadoc calls
  it *"a mirror of `PlanStatisticsService.WORST_FIRST`"* — a `private` field with the identical seven
  members in the identical order — and phase 3 pinned it against `StatusKind`, its *vocabulary*,
  rather than against the list it mirrors. The existing cross-check does reach a rank disagreement,
  but only through the counts, and only for kinds the fixture contains: its rows are `PASSED`,
  `FAILED` and `BLOCKED`, so swapping `RETEST` and `WIP` in one copy and not the other changes no
  number it asserts. Comparing the two lists directly is four lines and one more package-private
  field; the reason not to do it in §206 is that it is a different claim from the one the stale
  sentence made.

**Carried forward:** `tcms_report.generate` has no replay protection and the contract offers none
(§205, measured by `§205 PL-4`); `Problem.type` is required, sent on every refusal, read by nothing
on either stack (§205); `observeStatus` has one reader and fifteen create arms ignore it, eight by
decision (§204); `Location` on the contract's 201s is unlooked-at (§204); the two `api(routes)`
harnesses are still near-copies and the MCP one's header still says it *is* the CLI's (§203); two
fixture modules over one contract and nothing pairs them (§203); nothing counts how many tests mount
a boundary (§201); `app/queryClient.ts` and `test/queryClient.ts` both report and nothing pairs them
(§202); a hatch is a per-test declaration and nothing reads the 44 that say *"this test refuses a
write"* (§202); resume the browser queue at `A2 — plans`; **a third `packages/` live pass**, carrying
`§203 PL-1`, `§204 PL-2`, `§204 PL-3` and `§205 PL-4`; the snapshot has no reader (§193); a clone
cannot file the copy nowhere (§195); nothing pairs the two tsconfig lists (§196); the
undeclared-property question is off under `additionalProperties` (§198); `NEEDS_A_SAVE` has no
producer and never had a test (§200); a stub is `INCOMPLETE` only once approved and nothing tells its
author before then (§200); the read-only archive decision §159 did not take; serving the relabel
audit (§174); `CurrentUser.teams` has no reader (§175); a run has no edit endpoint (§177); §184's,
§185's, §188's, §189's and §190's items.
