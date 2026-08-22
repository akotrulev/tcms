# §245 — a run's checklist can be handed out in one act

**Area B of `docs/plans/2026-08-18-unplanned-gaps.md`** (master tasks 7–14), executed against the
stepped plan `docs/plans/2026-08-18-gap-b-bulk-execution-assignment.md` (T1 → T14).

**No migration. No permission seeded or moved; the catalogue is still 55. The Flyway head is still
`V67`** — `V68` is reserved for area C and this area needed no DDL, because the one column it writes
has existed since `V1`.

> ⚠️ **BUILT, UNGATED AND UNVERIFIED.** No `./gradlew build`, no `./gradlew test`, no targeted test
> class, no `npm run test:run`, no `npm run build`, no `npm run lint` — locally or on `shanks` —
> was run against any part of this area, under the batch's standing no-tests directive. Every
> commit here is a state whose edits are complete and self-consistent; **not one is a state whose
> gate passed**, and the commit messages say so. The `§245 BA-*` and `§245 BA-M*` rows in both
> verification queues are also unrun, per §65. Any test count quoted near this area is §243's and
> is stale.

---

## What it does

`POST /api/v1/projects/{projectId}/runs/{runId}/executions/bulk` — preview-then-commit over a
selection of a run's executions, in two modes:

| `action` | Argument | Meaning |
|---|---|---|
| `ASSIGN` | `payload.assigneeId` (uuid, **nullable**) | Every selected row goes to that person. **`null` unassigns every selected row** — a value, not an omission. |
| `DISTRIBUTE` | `payload.assigneeIds` (1–50) | Round-robins the selection across those people, in the caller's order. |

Splitting a 300-case run across five testers was 300 requests, and the single-row endpoint's own
description had recorded the gap: an execution's assignee *"could be set once, for all two hundred
rows, or not at all."*

Front of it: a checkbox column and a bulk bar on the run detail, an `AssignExecutionsDialog` that
previews then commits, and — the debt §242 left for this area — an audit trail that **groups** a
bulk act instead of being flooded by it.

---

## The three things a later reader will get wrong

### 1. There is no `Idempotency-Key`, and that is not an omission

The master plan's task 8 said to *"carry the idempotency header the cases-bulk endpoint declares"*.
**`POST /projects/{projectId}/cases/bulk` declares no parameters at all**, and the mechanism this
repository calls idempotency is a unique `idempotency_key` column **on the row a create produced**
(`TestCase`, `Suite`, `ReportJob`, `AiProposal`): a replay answers 200 with *the resource that
intent created*. A bulk assign creates no resource, so there is no row to key and nothing to answer
a replay with.

**What replaces it is determinism, and it is designed for rather than hoped for:**

- the body states the desired state of every named row (the single-row `PUT`'s rule);
- the mapping is a pure function of `(executionIds in the RUN's order, assigneeIds in the CALLER's
  order)`;
- a row already in the requested state is reported `unchanged` and **writes no audit row** — a rule
  inherited from `ExecutionAssignmentService`.

So a replay re-derives the same mapping, finds every row already there, reports them all
`unchanged`, and writes zero rows and zero `bulkOperationId`s.
`ExecutionBulkAssignTest.aRetryChangesNothingAndWritesNoSecondOperation` is the arm that stands in
for the key, and `§245 BA-M6` is the mutation that would defeat it while every response assertion
stayed green. **If that arm ever fails, the contract's determinism claim is false and the endpoint
needs a different answer — not a patched expectation.**

### 2. One `audit_event` row per target, sharing one `bulkOperationId` — a deliberate departure

Every other bulk service in this codebase writes **one** row whose `target_ids` array holds all 143
ids, which the audit reader renders as *"143 items"*. That shape **cannot state what a distribute
did**: one row carries one `changes` document, and this act moves twelve rows to five different
people. A truthful single row would need `changes` to be a map from execution id to assignee, which
no reader can render and no filter can search.

So this area is **`audit_event.bulk_operation_id`'s first writer in the product's history**, through
a new eight-argument overload of `AuditEvent.record` — its own overload rather than an eighth
parameter on the seven-argument form, because that form has 42 call sites and none of them is
inside a bulk act.

**The older shape is still right where it applies**, and the field javadoc now says so rather than
being deleted: one act doing *one* thing to many rows is one row with a `target_ids` array.

### 3. The action vocabulary is the existing one

Each row records `execution.assigned` or `execution.unassigned` — the same two verbs
`ExecutionAssignmentService` already writes, with the same `{"from":…,"to":…}` document. Inventing
`execution.bulk_assigned` would split every *"who took this off Ana"* query in two and leave
`auditActionLabel`'s curated map with two entries meaning one thing. **What marks a row as bulk is
the `bulk_operation_id`**, which is the whole point of the column.

`ExecutionAssignmentAudit` is the one speller of both verbs and the document, and **both writers of
`test_execution.assignee_id` go through it** — extracted rather than copied, because the copy would
have been §72's shape again: two sites building the same JSON by concatenation under an instruction
to keep them in step. `theTrailReadsTheSameWhicheverDoorWasUsed` is the assertion.

---

## The distribute contract, pinned

1. **Target order is the RUN's own** — the pinned display-key sequence, tie-broken by execution id.
   The caller's array order is not used: a selection is a set, and two coordinators selecting the
   same rows must get the same split.
2. **Assignee order is the CALLER's**, duplicates collapsed keeping the first occurrence, never
   sorted. That order decides **who takes the remainder**, and a server that sorted it would
   silently overrule a coordinator who put the senior tester first, with nothing on any screen to
   show it had.
3. The i-th target goes to `assignees[i % assignees.size()]`. Twelve rows across five people is
   **3/3/2/2/2**, and the first two in the list hold the extra one.
4. It states **desired state over the whole selection** — a row already on somebody else is
   reassigned, not left alone. "Fill only the gaps" was considered and rejected: it makes the result
   depend on invisible existing state, so the same act run twice produces different splits, and it
   is not expressible as a preview anybody can trust. A coordinator who wants gap-filling filters
   the checklist to unassigned rows first, which the run detail's chips already make a click — and
   which the bulk bar's caption says out loud.
5. **More people than rows is legal.** The tail receives nothing, and the preview shows them with a
   count of **zero** rather than dropping them, because a person expecting work and getting none is
   the thing worth seeing. `tallyByAssignee` is seeded from the chosen list for exactly this; a
   `groupBy` over what changed silently drops that row.

## What refuses the act, and what skips one row

`BulkOperation`'s rule verbatim — **knowable before any row is read ⇒ refuse; a property of the row
⇒ skip:**

| Situation | Answer |
|---|---|
| No `run:execute` | **403**, `RunController.assignForbiddenProblem()` — the single-row endpoint's own sentence |
| Project or run unreachable | **404**, bodiless (§6t) |
| The run has ended | **409**, `assignmentFrozenProblem()` — the freeze the verdict and its evidence are under |
| An assignee is not an ACTIVE member of this company | **400** on `payload.assigneeId` / `payload.assigneeIds` |
| The action's argument is absent, or the other action's is present | **400** |
| `executionIds` empty or over 500 | **400** |
| An id names no execution **in this run** | **200**, in `skipped` with a reason |
| A row is already in the requested state | **200**, in `unchanged` |

The last two are the ones a reader is most likely to "simplify" into a 404. A selection is a
filter's output from a minute ago, so a stale id is an ordinary event and refusing four hundred rows
over one of them is worse than the problem. `§245 BA-M5` is that mutation.

**One transaction for the whole act**, which is what makes "a stranger in the list writes nothing"
true — including no half-used `bulkOperationId`.

**The cap is 500, for a stronger version of `caseIds`' reason**: it is one transaction *and* up to
500 audit rows. A larger run is assigned in two acts, which is two `bulkOperationId`s and reads
correctly in the trail.

---

## The two debts §242 left, both discharged

| Debt | Discharged by |
|---|---|
| `ArchitectureRulesTest.WRITERLESS_BY_DECISION` claimed nothing writes `AuditEvent.bulkOperationId` | The entry is deleted. The claim became **false** the moment the service landed, and `containsExactlyInAnyOrderElementsOf` fails in **both** directions (§162), so the build is red until it goes. |
| `AuditLogPage` rendered every row flat, so one bulk act floods the trail | `groupByBulkOperation` — **not a contract change**; `bulkOperationId` has been on the wire since §242. |

**The two halves were committed together on purpose** (`52d19a1` then `e4762fd`). Between them the
build is red on one rule by design — that is the plan working. Splitting them across a batch would
mean either a commit whose gate was not run, or an exemption deleted before its writer existed; and
under a no-gates directive **nobody would have observed the red**, which is the worse of the two.

**The column has now been in one map or the other since §152 and is in neither.** §242 gave it a
reader, §245 a writer, and it moved between the two maps three times without the column changing at
all. That is the pair's longest worked example and the plainest argument there is for running both
rules — it is written into `READERLESS_BY_DECISION`'s javadoc where the next reader will meet it.

### And the tally inside the tally-checking file was wrong again

`everyMappedColumnHasAWriter`'s javadoc said *"Four of the nine"*. **The map held eight**, and holds
seven now — so the sentence was stale in both halves, in the file whose entire subject is
hand-kept tallies. Recounted against the map rather than against the previous sentence, in two
places (the javadoc and the comment inside the method), and the staleness is recorded rather than
quietly fixed.

---

## The audit grouping, and its honest limitation

Rows arrive sorted `(occurredAt DESC, id DESC)` and **every writer stamps a transaction's rows from
one `Instant`**, so one act's rows are adjacent. `groupByBulkOperation` folds a **run** of
consecutive rows sharing a non-null id; a null id is always its own group of one, which is every row
written in every deployment before this section — so the ungrouped rendering did not move.

**A group straddling a page boundary renders as two groups, and the copy says *"n of one operation
shown here"* rather than claiming a total the page cannot see.** That is the limitation stated
rather than hidden: this screen is server-paged at 25–100 rows and a 300-row act spans several.
Grouping by scanning the whole page instead of by run would buy nothing (two acts cannot share an
id) and cost the property that makes this cheap.

**The pager's count stays `page.total`, the server's ROW count.** Deriving a count of *operations*
would make the pager disagree with itself on every page. There is a comment beside it saying so;
it is the second-most likely "improvement" after page-spanning groups.

---

## Six corrections to the plans, and what forced each

1. **No `Idempotency-Key`** — above. (Corrected by the stepped plan; recorded here because the
   master plan still says otherwise.)
2. **No new `ExecutionBulkController`.** The master plan's file list names one. `RunController`
   already maps `/api/v1/projects/{projectId}/runs` and already owns the three problem builders
   this operation answers with verbatim; a second controller on one prefix would be a second copy
   of them, which is the shape §72 extracted `ExecutionTargets` to end.
3. **`changed` carries whole rows, not case keys.** For a distribute, a list of keys says *that*
   something moved and not *where* — which is the entire question the preview exists to answer.
   `unchanged` and `skipped` keep the cases-bulk shape exactly.
4. **The caller who lacks `run:execute` is a Viewer, not a Test Engineer.** The stepped plan's
   fixture and its `BA-8` browser row both said Test Engineer. **Test Engineer holds
   `run:execute`** — roles spec §A3 and `V3` both grant it, because working a run by hand is that
   role's job — so a Test Engineer proves nothing here, and a *comment* claiming they lack it would
   additionally redden `tierClaims.test.ts`. Viewer is the assignable role holding `project:read`
   and none of the run keys.
5. **This is `§245`, not `§244`.** The stepped plan wrote `§244` throughout — for the handoff file,
   the verification ids and the cache entries. Area A took `§244` first (execution duration), and
   ids are never renumbered, so this area is `§245` and `§246` is area E's export. Every `BA-*` and
   `BA-M*` id carries `§245`.
6. **The mock arm does not go in `frontend/src/test/fixtures.ts`.** The stepped plan asked for a
   path-keyed arm there; that file holds **body factories only**, and the fetch doubles are
   `vi.spyOn(api, …)` per test file, wrapped once by `test/contractDoubles.ts` so every body is
   compared against `openapi.yaml` automatically. So `fixtures.ts` gained `aBulkAssignResult` and
   `anAssignmentChange`, and the anchored path arm lives in `AssignExecutionsDialog.test.tsx`.

---

## What is deliberately not built

**The CLI and the MCP server get nothing in this area, and that is a decision.** Neither surface has
a *selection* — a bulk assign from `@tcms/cli` would mean a case-key list on the command line or a
filter language that does not exist, and `writeConformance.test.ts` would then be pinning an arm
nobody can drive. The natural shape when it comes is
`tcms run assign --run RUN-3 --to ana,ben --all-unassigned`, and **it wants a filter argument
first**. Named here so the next reader does not read the absence as an oversight.

---

## Owed, and the gaps that have no observer

- **Everything.** The whole area is ungated: the first thing anybody does with it is run
  `./gradlew build`, the three npm suites and `npm run lint`, then work the `§245 BA-*` queue rows.
- **Two risks the author could not check without running anything**, both cheap to confirm and both
  named so they are not discovered as mysteries:
  - `ContractConventionTest.INLINE_RECORDS`' keys for the two nested response records were written
    as `ExecutionBulkResult.changed[]` / `.skipped[]`, following that map's own convention
    (`ExecutionResultInput.stepResults[]`). The stepped plan guessed `.changed.items`. **Read the
    failure message if it fails — it names the key it wants.**
  - `everyMappedColumnHasAWriter` decides "written" partly by a **name-text match**
    (`namedIn(writeText, null, field.getName())`), which has already produced one false negative in
    this repo — `IngestionService`'s JSON key `node.put("durationMs", …)` satisfied the sweep for
    `duration_ms`, a column that had no writer at all for a year (§244). The writer added here is a
    real assignment reachable from outside the entity, so `isWrittenInJava` should carry it; that
    is not the same as having watched it.
- **Three mutation rows are expected GREEN and recorded as gaps** — `BA-M7` (dropping
  `@Transactional`, which may have no observer because validation precedes every save), `BA-M10`
  (the closed-run check's position), `BA-M12` (the `inRunOrder` tiebreak, which no fixture exercises
  because every fixture case resolves its pin). Each row says what would close it.
- **`§242`'s owed index is still owed**: `(company_id, project_id, occurred_at DESC)` on
  `audit_event`. This area makes it matter more — a run assigned in one act writes up to 500 rows
  the trail then reads — and it still takes no Flyway version, because `V68`/`V69` are reserved.
- **`RunDetailPage.tsx` still has no component test in this repository.** The selection model, the
  bar's disable-when-frozen behaviour and the caption are covered only by the browser rows
  (`BA-1`, `BA-5`, `BA-8`). `AssignExecutionsDialog.test.tsx` covers the dialog.

## Fixture prefix

`ExecutionBulkAssignTest` takes **`88…`**, pre-assigned to this area and verified unused across
`backend/src/test/java`, `frontend/src` and `packages/` on 2026-08-22. The runs are instance fields
rather than id constants, because the fixture builds them through `POST /runs` — a fixture that
inserted `test_execution` rows to pin an id would be proving the query works over data the product
does not produce.
