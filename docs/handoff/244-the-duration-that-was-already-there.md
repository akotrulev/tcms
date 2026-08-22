# §244 — The duration that was already there

**Area A of `docs/plans/2026-08-18-unplanned-gaps.md`** (old slice 3), tasks 1–6.
**No migration. No permission seeded or moved. The catalogue is still 55, the Flyway head is still
`V67`.**

> ## ⚠️ Read this before anything else: this area is unverified in TWO senses
>
> 1. **Its verification-queue rows sit unrun** — `§244 ED-1..ED-6` in
>    `docs/verification/frontend-browser-checks.md` and `§244 ED-M1..ED-M9` in
>    `docs/verification/backend-mutation-checks.md`. That is the ordinary §65 state.
> 2. **No automated gate was run against it at all.** Under a directive given mid-lane on
>    2026-08-22, this area was built with **no** `./gradlew build`, **no** `npm run test:run`, **no**
>    `npm run build` and **no** `npm run lint`. The only executions that happened were two targeted
>    backend classes run *before* that directive arrived:
>
>    | Command | Result |
>    |---|---|
>    | `./gradlew test --tests 'com.tcms.execution.ExecutionDurationTest'` (the TDD red) | **BUILD FAILED** — 4 tests, 3 failed, as intended |
>    | `./gradlew test --tests '…ExecutionDurationTest' --tests '…IngestionEndpointTest'` (after the fix) | **BUILD SUCCESSFUL** |
>
>    Nothing else ran. No frontend suite, no packages suite, no lint, no full backend build. **This
>    area is built and completely ungated**, and the user's post-batch pass on `shanks` is what will
>    gate it. Do not read the second row above as coverage of the slice — it covers two classes.

---

## What this was supposed to be, and what it turned out to be

The plan opened with *"Migration `V68`. Add `duration_seconds INTEGER` to `test_execution`."*

**The column was already there, and had been since the schema's first day (2026-07-30).**

| Where | What |
|---|---|
| `V1__initial_schema.sql:845` | `duration_ms BIGINT` on `test_execution` |
| `TestExecution.java:96` | `@Column(name = "duration_ms") private Long durationMs` |
| `TestExecutionSummaryView` · `TestExecutionView` · `CaseHistoryView` | served on all three read shapes |
| `openapi.yaml` | published as `durationMs` on `TestExecutionSummary` and `ExecutionHistoryEntry` |
| `ExecutionSource.java:206,236` | copied into `execution_fact`, **and in the seven-column `ON CONFLICT` set-list** |
| `RunSummaryReport.java:396` | `COALESCE(sum(f.duration_ms), 0)` — the report's "recorded duration" |

That much the plan's own revision had already caught, and it is why this area lost its migration and
dropped from half a session to a quarter.

**What re-reading the code found on top of that: nothing wrote the column. Nothing at all.**

The ingestion parsers do recover a duration — `ReportXml` and `CucumberReportParser` both fill
`IngestedResult.durationMs` — and `IngestionService#payload` puts it into the ingestion **job's**
payload JSON, a different table answering a different question. The column itself was never
assigned by any code path in the product. So five readers and one report answered `null` for every
execution ever recorded, and the run summary's headline "recorded duration" was structurally zero.

`IngestedResult`'s own javadoc said so, in a sentence that was half right and had gone unread for a
year:

> *"**Not written anywhere yet** — `test_execution` has no duration column and adding one is a
> schema decision with a reporting consumer behind it, not a side effect of a parser."*

The first clause was true. The second was false, and had been false when it was written. It is now
struck through in place with `→ superseded`, per the working agreement.

## The lesson, which is the reusable part

**"Does this column exist" and "does anything write it" are two different greps, and a plan that ran
only the first scoped a whole area as a migration.**

The near miss is worth stating precisely. The plan's revision *did* re-read the code, *did* find the
column, and *did* delete the migration — a genuinely good catch that saved a `V68`. It then wrote
"written by ingestion since V1" from the fact that a parser produced the value, without following it
to an assignment. One more grep — `grep -rn 'setDurationMs\|durationMs,' src/main/java` — would have
shown the value going into a JSON node and stopping there.

**The instrument this repo already has did not fire, and the reason matters.**
`ArchitectureRulesTest` carries `WRITERLESS_BY_DECISION` and `READERLESS_BY_DECISION` maps —
§242 records `bulkOperationId` sitting in one of them — and **`duration_ms` was in neither**. Those
maps are exemption lists: they cover the columns somebody thought to list. **An exemption map is not
a sweep**, and the working agreement's own rule applies to it verbatim — *a schema-wide invariant
needs a computed check, not a careful reading*. A computed writerless-column check over the schema,
rather than over the exemption list, is what would have found this. It is not built; recording it
here is the honest half.

## What was built

### The contract

`ExecutionResultInput.durationMs` — optional, nullable, `integer`/`int64`, `minimum: 0`.
`required` stays `[statusId]`; adding the duration to it would break `tcms_record_result` and
`result record`, both of which write this operation.

**`minimum: 0` in the contract enforces nothing at runtime.** Nothing in this repository reads an
OpenAPI numeric bound. The check in `ExecutionService.validate` is the check; the contract line is
documentation for the clients. `ED-M4` records that.

Both generated copies regenerated and confirmed byte-identical (`frontend/src/api/schema.d.ts`,
`packages/client/src/schema.ts`).

### The write is total state, and it travels through `record`

`ExecutionResultRequest` gains `Long durationMs`, applied inside `TestExecution.record` beside the
verdict rather than through a setter of its own. Three consequences, each with a mutation row:

- **An omitted duration CLEARS it** — the same rule `comment` has carried since Phase 1, because
  this body states the desired state rather than patching it. A null-guard makes the field
  un-clearable and diverges from the field sitting next to it on the same form (`ED-M1`).
- **Zero is a duration**, not an absence. A truthiness guard turns "it was instant" into "not timed"
  (`ED-M2`), and a run of sub-second automated checks recorded by hand is exactly where that loses
  real data. Every layer between the tester and the column has a falsy check in it, which is why the
  zero arm exists in three suites (`ExecutionDurationTest`, `format.test.ts`, both packages'
  `writes.test.ts`).
- A setter would be a **second door into the verdict's state** that a caller could use without
  recording a verdict.

Validation is a **collected** fault: the `durationMs` check goes after the `stepResults` block and
before `return errors`, because the caller is a tester at the end of a test and one message per
round trip is three round trips (`ED-M3`).

### The two screens that must send a field they do not show

The frontend half's real finding. `PATCH …/executions/{id}` is total state, so **every** call site
must send **every** field — including the ones its own screen has no control for:

| Site | Has a duration control? | Sends `durationMs` from |
|---|---|---|
| `ExecutionPage` detail form (Focus / Split) | **yes** | the box |
| `ExecutionGrid.mark` (row verdict) | no | `client.fetchQuery(executionQueryOptions(…))` |
| `BlockedFromGrid` (grid-mode Blocked dialog) | no | `useExecution`'s detail |

Omitting it in either of the last two would **clear a duration the tester entered in Focus mode,
from a screen that never displayed it to them**. Both already sent `comment` back for exactly this
reason, with the argument written in their own comments — **the field was added by re-reading those
comments, not by a failing test**, and there is still no automated observer for either
(`ED-M9`, and `§244 ED-5` in the browser queue).

### The unit boundary, once

`lib/format.ts` gains `parseDurationSeconds` and `durationSecondsField`. Seconds on screen because a
tester does not think in milliseconds; milliseconds on the wire because that is what every report
dialect reports.

`parseDurationSeconds` returns **three** things and the third is the design:

| Input | Answer | Meaning |
|---|---|---|
| `''` / whitespace | `null` | "I did not time this" — saved, and clears a stored value |
| `'0'` | `0` | "it was instant" — a different statement |
| `'4.5'` | `4500` | the value |
| `'abc'`, `'-1'` | `undefined` | unreadable — the caller **disables the verdict buttons** |

A two-state parse folding the last row into `null` would make garbage in the box a **silent clear**:
the tester types nonsense, presses Passed, and a number they recorded earlier disappears. Refusing is
the better behaviour, and the helper text says out loud that an empty box clears.

**The CLI names its flag `--duration-ms` rather than converting from seconds**, so the conversion has
one implementation and not three. `--duration-ms ''` clears, the two-flags-in-one shape every
nullable field on that CLI uses. MCP takes `durationMs` with a new `nullableNum` beside
`nullableStr`, so an explicit `null` survives `present()`.

## The one deliberate scope widening

**`IngestionService#write` now passes `result.durationMs()` to `record`.** One line, strictly
additive — a column that was always null gets a value — and it is outside the file list Area A's
plan names.

It was taken because the alternative was indefensible: this area *found* a writerless column and
would have *left it* writerless for its natural writer, with `result.durationMs()` sitting one line
away from the call. It also makes the plan's own stated premise ("ingestion writes this") true for
the first time. `IngestionEndpointTest.theReportedDurationIsRecorded` covers it (`time="0.1"` →
100 ms), and `ED-M5` is the mutation that restores the state the product actually shipped in.

## Deviations from the plan, in full

| Plan said | What shipped | Why |
|---|---|---|
| "`test_execution.duration_ms` has been written by ingestion since V1" | It had **no writer at all** | Followed `IngestedResult.durationMs` to its use: a JSON node in the job payload, never the column |
| Add a setter and call `execution.setDurationMs(...)` in `apply` | Carried through `TestExecution.record(…, durationMs, now)` | The duration is part of the verdict; a setter is a second door into that state, and `record`'s own header already argues why every field of a recording moves together |
| Fourth test arm: "an ingested duration is not disturbed by an amendment that omits it" | Replaced by `zeroIsADurationAndNotAnAbsence` | There was no ingested duration to survive. The substitution pins the defect the finding actually exposes, and the class header records the swap |
| Area A touches ingestion nowhere | `IngestionService` + `IngestionEndpointTest` | See above |
| Gates run per phase on `shanks` | **No gates run** | The no-gates directive of 2026-08-22, mid-lane |

## What has no observer

Recorded rather than closed, because a silent gap is what §65 exists to prevent:

- **Nothing asserts a duration reaches `RunSummaryReport`'s sum** (`ED-M6`). The whole question this
  area exists to answer — *how long did this cycle take last time* — runs through
  `test_execution` → `ExecutionSource` → `execution_fact` → the report, and no test walks it. The
  reporting fixtures predate any writer, so they were all summing zero and passing.
- **Nothing asserts `duration_ms` stays in `ExecutionSource`'s `ON CONFLICT` set-list** (`ED-M7`), so
  an amended duration freezing at its first value is invisible. Unobservable until now for the same
  reason.
- **`ExecutionPage.tsx` has no component test** in this repository, and none was invented. Nothing in
  `frontend/src` drives `useSubmitResult` at all. `format.test.ts` covers the parse — the half
  testable without a harness (`ED-M8`).
- **No computed writerless-column check exists.** See the lesson above.

## Fixture prefix

`ExecutionDurationTest` takes **`66…`**, verified unused across `src/test/java`, `frontend/src` and
`packages/` on 2026-08-22.

## Commits

| Commit | Subject |
|---|---|
| `96f9764` | contract: a recorded result may carry its duration |
| `505e51b` | feat: a hand-recorded result may carry its duration |
| `b7680ef` | feat: recording a result takes a duration |
| *(this one)* | docs: execution duration handoff and seams |
