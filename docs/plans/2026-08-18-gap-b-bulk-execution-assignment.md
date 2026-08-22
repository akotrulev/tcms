# Bulk Execution Assignment Implementation Plan

**Date:** 2026-08-22 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Assign many rows of a run's checklist to people in one act — including **spreading them
across a list of testers** — so that splitting a 300-case run across five people is one operation
instead of three hundred.

**Architecture:** One new `POST …/runs/{runId}/executions/bulk` operation in the `execution` module,
mirroring the cases-bulk envelope (`preview-then-commit`, lists of keys out, never a bare count). It
reuses every predicate the per-execution assignee endpoint already applies — `ExecutionTargets`,
`ExecutionPeople.isActiveMember`, `run:execute`, the closed-run freeze — and adds nothing to the
permission catalogue and nothing to the schema. It writes **one `audit_event` row per target sharing
one `bulk_operation_id`**, which makes it the first writer of that column in the product's history
and therefore the task group that pays down both debts §242 left behind.

**Tech Stack:** Spring Boot 3.5.6 / Java 21 / Spring Modulith · `openapi.yaml` as the contract,
generated into `frontend/src/api/schema.d.ts` **and** `packages/client/src/schema.ts` · React 19 /
TypeScript strict / MUI v7 / TanStack Query.

**Tasks** — in the order they are done:

| # | Task | Deliverable | Depends on |
|---|---|---|---|
| 1 | T1 — The contract | `POST …/executions/bulk` and its three schemas are in `openapi.yaml`; both generated copies are byte-identical | none |
| 2 | T2 — The failing backend test | `ExecutionBulkAssignTest` exists with 14 arms and fails to compile against the missing service | T1 |
| 3 | T3 — The request and result records | `ExecutionBulkAssignRequest` / `ExecutionBulkAssignResult` compile and match the contract's members | T2 |
| 4 | T4 — `bulk_operation_id`'s writer seam | `AuditEvent.record` has a bulk overload; `ExecutionAssignmentAudit` is the one speller of the `changes` document | T3 |
| 5 | T5 — The service, straight assign | `ASSIGN` and `UNASSIGN` arms of `ExecutionBulkAssignTest` are green | T4 |
| 6 | T6 — Distribute mode | The round-robin arms are green, including the determinism-is-idempotency one | T5 |
| 7 | T7 — The controller and the contract pairings | The endpoint answers 200/400/403/404/409; `./gradlew build` green | T6 |
| 8 | T8 — Delete the writerless exemption | `WRITERLESS_BY_DECISION` no longer names `AuditEvent.bulkOperationId` and the build is green | T7 |
| 9 | T9 — The write hook | `useBulkAssignExecutions` exists and both its arms are contract-conformant | T7 |
| 10 | T10 — The selection model | `RunDetailPage` selects rows and shows a bulk bar | T9 |
| 11 | T11 — The assignment dialog | `AssignExecutionsDialog` previews then commits, in both modes | T10 |
| 12 | T12 — Group the audit trail | `AuditLogPage` renders one collapsible row per `bulkOperationId`; frontend gates green | T11 |
| 13 | T13 — The deferred verification rows | Rows `§244 BA-*` and `§244 BA-M*` appended to both queues | T12 |
| 14 | T14 — The record | `docs/handoff/244-*.md`, one index row, both orientation caches; pushed | T13 |

---

## Area B of `docs/plans/2026-08-18-unplanned-gaps.md`

The master plan scoped this area as tasks 7–14 and required exactly this document before work
starts. **Read the master plan's Area B section first**, then this. Everything it named is here;
five of its instructions are corrected below, each against code that was read rather than assumed.

### Six deviations from the master plan, and why

1. **There is no `Idempotency-Key` to carry, because the cases-bulk endpoint does not declare one.**
   The master plan's Task 8 says *"Carry the idempotency header the cases-bulk endpoint declares. A
   retried bulk assign must not reassign."* `POST /projects/{projectId}/cases/bulk` declares **no
   parameters at all** (`openapi.yaml:1758-1782`) — its answer to a double-submit is `dryRun`, not a
   key. And the mechanism this repository calls idempotency is a **unique `idempotency_key` column on
   the row a create produced** (`TestCase`, `Suite`, `ReportJob`, `AiProposal`): a replay answers 200
   with *the resource that intent created*. A bulk assign creates no resource, so there is no row to
   key and nothing to answer with.

   **What replaces it is stronger and is designed for in T6: the operation is deterministic, so a
   replay is a no-op by construction.** The body states the desired state of every named row (the
   `PUT` reasoning the single-row endpoint already carries), distribute mode's mapping is a pure
   function of `(executionIds in run order, assigneeIds in caller order)`, and
   `ExecutionAssignmentService` already writes **no audit row for an assignment that changes
   nothing**. So a retry re-derives the same mapping, finds every row already in that state, reports
   them all as `unchanged`, and writes zero audit rows and zero `bulk_operation_id`s.
   `aRetryChangesNothingAndWritesNoSecondOperation` is the arm; `§244 BA-M6` is the mutation.

2. **One `audit_event` row per target, and that is a departure from every other bulk service —
   deliberately.** `CaseBulkService` writes ONE row whose `target_ids` array holds all 143 ids, which
   is what the audit reader's `Targets` cell renders as *"143 items"*. That shape **cannot state what
   distribute mode did**: one row has one `changes` document, and this act moves 300 rows to five
   different people. A truthful single row would need `changes` to be a map from execution id to
   assignee, which no reader can render and no filter can search. Per-target rows sharing a
   `bulk_operation_id` is exactly the shape that column was added for, and T12 is the reader that
   makes it legible. **This is why area B is the first writer and why the ArchUnit exemption dies
   here rather than in some later slice.**

3. **The action vocabulary is the existing one, not a new pair of verbs.** Each row records
   `execution.assigned` or `execution.unassigned` — the same two actions
   `ExecutionAssignmentService` already writes, with the same `{"from":…,"to":…}` document. A bulk
   act is not a different kind of fact about a row, and inventing `execution.bulk_assigned` would
   split every "who took this off Ana" query in two and leave `auditActionLabel`'s curated map with
   two entries meaning one thing. What marks it as bulk is the `bulk_operation_id`, which is the
   whole point of the column.

4. **The controller is `RunController`, not a new `ExecutionBulkController`.** The master plan names
   four new files including a controller. `RunController` is already
   `@RequestMapping("/api/v1/projects/{projectId}/runs")` and already owns
   `PUT …/executions/{executionId}/assignee`, whose refusal sentences (`assignForbiddenProblem`,
   `assignmentFrozenProblem`, `validationProblem`) this operation answers with **verbatim**. A second
   controller on the same path prefix would be a second copy of those five problem builders, which is
   the shape §72 extracted `ExecutionTargets` to end. The **service** is new and is its own file.

5. **The result reports rows, not bare keys, because the preview must name who each case went to.**
   `BulkResult` reports `changed` as a list of case keys, which is right for "set the status of these
   38". For distribute mode a list of keys answers *that* something moved and not *where*, and the
   whole reason a preview exists (plan §6f) is that a person is about to hand three hundred cases to
   five colleagues and needs to see the split before they confirm it. So `changed` carries
   `{executionId, caseKey, assigneeId, assigneeName}` and the dialog renders a per-person tally off
   it. `unchanged` and `skipped` keep the cases-bulk shape exactly.

6. **The CLI and the MCP server get nothing in this area, and that is a decision rather than an
   omission.** Neither surface has a selection — a bulk assign from `@tcms/cli` would mean a case-key
   list on the command line or a filter language that does not exist, and `writeConformance.test.ts`
   would then be pinning an arm nobody can drive. The gap is worth naming in the handoff (T14) so the
   next reader does not think it was forgotten: the natural shape there is `tcms run assign --run
   RUN-3 --to ana,ben --all-unassigned`, and it wants a filter argument first.

### The two inherited debts this area exists to discharge

**Both are recorded in `docs/handoff/242-the-audit-log-reader.md` under "What is owed", and both are
false claims the moment T5 lands rather than tidy-ups that can wait.**

| Debt | Where | Discharged by |
|---|---|---|
| `ArchitectureRulesTest.WRITERLESS_BY_DECISION` claims nothing writes `AuditEvent.bulkOperationId` | `backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java:1058` | **T8** — and the build *fails* until it is deleted, because the assertion is `containsExactlyInAnyOrderElementsOf` (§162: a stale exemption is a red build, in both directions) |
| `AuditLogPage` renders every audit row flat, so one bulk act floods the trail | `frontend/src/features/audit/AuditLogPage.tsx` | **T12** — not a contract change; `bulkOperationId` has been on the wire since §242 (`openapi.yaml:7716`) |

T8 is not optional and it is not deferrable: after T5 writes the column, `everyMappedColumnHasAWriter`
computes a writer (`isWrittenInJava` counts an assignment in a method that has a caller outside the
entity — the bulk overload of `AuditEvent.record`, called by the new service), the exemption stops
matching, and `./gradlew build` goes red on `ArchitectureRulesTest` until the entry is removed. **If
T7's gate is red on that rule, that is the plan working, not a defect.**

### Concurrency: this plan is written against the post-lane-1 contract

Area A (execution duration) is landing in a concurrent lane and adds an optional nullable
`durationMs` to `ExecutionResultInput`. That is the only overlap and it is in a schema this area does
not touch, but it has two consequences:

- **Every `openapi.yaml` instruction here is anchored by schema name and neighbouring text, never by
  line number.** Area A's edit moves every line after `ExecutionResultInput`. Where a line number is
  quoted it is marked *as of 2026-08-22* and is a navigation aid only.
- **T1 must be committed after area A's contract commit is in the tree**, and `npm run gen:api` must
  be run **once, by this lane, after that** — the parallel rules' escape hatch: *"Resolve the
  contract, then regenerate — never hand-merge the output."* If `git log` shows no area-A contract
  commit yet, either wait or accept that a later regeneration will produce the union; do not
  hand-edit either generated file.

**Fixture UUID prefix: `88`.** Pre-assigned for this area and verified unused on 2026-08-22 against
every `UUID.fromString("…` literal under `backend/src/test/java`, `frontend/src` and `packages/`.
Use it for every id this area's tests mint. Do not choose another — §19's collision has happened
five times and two lanes taking one prefix merge clean.

---

## The design, settled here rather than during implementation

### The endpoint

```
POST /api/v1/projects/{projectId}/runs/{runId}/executions/bulk
```

Tag `execution`. Chosen over the three alternatives for reasons worth keeping:

- **Not `PUT …/executions/assignees`.** The cases-bulk precedent is a `POST` to a `/bulk`
  sub-resource carrying an `action`, and a second spelling of "operate on a selection" is how one
  product starts reading like two.
- **Not a widened `PUT …/executions/{executionId}/assignee`.** That operation's body is one row's
  desired state and its `null` means unassign; a batch form would have to make `executionId` optional
  in the path, which is not a thing a path can be.
- **Not a project-scoped `/executions/bulk`.** Assignment is scheduling *within a run*, the closed-run
  freeze is a property of the run, and `ExecutionTargets` resolves three segments because all three
  are gates. A project-scoped selection would have to re-derive the run gate per row.

### The action vocabulary

`action` is an enum with **two** members. Unassigning is `ASSIGN` with a null `assigneeId`, exactly as
the single-row endpoint spells it — a third `UNASSIGN` action would be a second way to say one thing
and the server would have to keep them agreeing.

| `action` | `payload` member it requires | Meaning |
|---|---|---|
| `ASSIGN` | `assigneeId` (uuid, **nullable**) | Every selected row goes to this person. `null` clears every selected row. |
| `DISTRIBUTE` | `assigneeIds` (array, 1–50 uuids) | Round-robin the selection across these people. |

### Distribute mode, exactly

**This is the actual request behind the gap and it is in scope, so its semantics are pinned here and
asserted in T6 rather than discovered in a dialog.**

1. **Target order is the run's own order**, which is the order `RunDetailPage` shows: by case display
   key sequence, tie-broken by `executionId` ascending so it is total even when two rows somehow
   share a key. The caller's array order is *not* used — a selection is a set, and two people
   selecting the same rows in different orders must get the same split.
2. **Assignee order is the caller's**, with duplicates collapsed keeping the first occurrence.
   Order matters here and must be preserved: it is what decides who gets the remainder, and a server
   that sorted it would silently overrule a coordinator who listed the senior tester first.
3. **The i-th target goes to `assignees[i % assignees.size()]`.** With 12 rows and 5 people:
   3/3/2/2/2, and the first two in the list get the extra one each.
4. **It states desired state over the whole selection** — a row already assigned to somebody else is
   reassigned, not left alone. The alternative ("fill only the gaps") was considered and rejected:
   it makes the result depend on invisible existing state, so the same act run twice produces
   different splits, and it is not expressible as a preview a person can trust. A coordinator who
   wants gap-filling filters the checklist to unassigned rows and selects those — which the run
   detail's filter chips already make possible, and which T10's caption says out loud.
5. **A row that is already on the person it lands on is `unchanged`**: no write, no audit row. This
   is what makes point 4 safe and (with 1–3) makes the whole operation idempotent.
6. **Fewer assignees than rows is the ordinary case; more assignees than rows is legal** — the tail
   of the list simply gets nothing, and the preview shows them with a count of zero so the person can
   see it.

### Preview-then-commit, and what refuses what

`dryRun` defaults to **true**, as it does for cases (`BulkOperation.dryRun`), and the SPA must preview
before committing. The split between *refuse the whole act* and *skip the row with a reason* follows
the cases-bulk rule verbatim — **knowable before any row is read ⇒ refuse; a property of the row ⇒
skip**:

| Situation | Answer |
|---|---|
| Caller lacks `run:execute` on the project | **403**, bodiless problem with the single-endpoint's sentence |
| Project, run or execution path unreachable | **404**, bodiless (§6t) |
| The run has ended | **409**, the assignment-frozen sentence — the same freeze the verdict is under |
| `assigneeId` / any `assigneeIds` member is not an ACTIVE member of this company | **400**, field error keyed to `payload.assigneeId` / `payload.assigneeIds` |
| `action` requires an argument the payload does not carry, or carries the other action's | **400**, field error |
| `executionIds` empty, over 500, or all duplicates of one another | **400**, field error on `executionIds` |
| An id in `executionIds` names no execution **in this run** | **200** with the id in `skipped`, reason given |
| A selected row is already in the requested state | **200**, in `unchanged` |

The last two are the ones a reader is most likely to get wrong. A selection is the output of a filter
somebody applied a minute ago, so a stale id is an ordinary event and refusing four hundred rows over
one of them is worse than the problem — that is `BulkOperation`'s own argument, quoted in the
schema below so the next reader finds it where they are working.

**One transaction for the whole act.** The partial-failure arm depends on it: a validation fault
raised halfway through leaves nothing written, including no audit rows and no half-used
`bulk_operation_id`.

### The cap is 500, and it is the same cap for the same reason

`caseIds` is capped at 500 because *"the whole operation is one transaction and one audit event, so
an unbounded selection is an unbounded lock"*. Here it is one transaction and up to **500 audit
events**, so the argument is stronger rather than weaker. A run larger than 500 cases is assigned in
two acts, which is two `bulk_operation_id`s and reads correctly in the trail.

### `bulkOperationId` on the response

`ExecutionBulkResult.bulkOperationId` is nullable and is:

- **null on a dry run** — nothing happened, so there is no operation to name;
- **null on a commit that changed nothing** — no audit row was written, so minting an id would
  produce a name for a group with no members;
- otherwise **the id every audit row of this act shares**, which is what lets a client (and a person
  reading the trail) join the two.

---

## File structure

**Create — `backend/src/main/java/com/tcms/execution/`**

- `ExecutionBulkAssignRequest.java` — the request record, `ExecutionBulkOperation`'s pair. Public,
  because `ContractConventionTest` compares it to the schema by name.
- `ExecutionBulkAssignPayload.java` — the argument, `ExecutionBulkPayload`'s pair.
- `ExecutionBulkAssignResult.java` — the response record and its two nested rows
  (`AssignmentChange`, `SkippedRow`), `ExecutionBulkResult`'s pair.
- `ExecutionBulkAssignService.java` — the act. Resolution, gate, validation, the two modes, the
  audit fan-out. One `@Transactional` method.
- `ExecutionAssignmentAudit.java` — package-private, static: the **one** speller of the
  `{"from":…,"to":…}` document and of the two action names, called by both writers of
  `assignee_id`.

**Modify — backend**

- `backend/src/main/java/com/tcms/tenancy/AuditEvent.java` — a `record` overload taking a
  `bulkOperationId`, and the field javadoc's writerless paragraph rewritten to past tense.
- `backend/src/main/java/com/tcms/execution/RunController.java` — one `@PostMapping`, mapping the
  new `Outcome` onto the problem builders that are already there.
- `backend/src/main/java/com/tcms/execution/ExecutionAssignmentService.java` — its inline audit
  writing replaced by `ExecutionAssignmentAudit`, so the two writers cannot drift.
- `backend/src/main/java/com/tcms/execution/TestExecutionRepository.java` — one query,
  `findAllInRun(projectId, runId, ids)`.
- `docs/specs/openapi.yaml` — one path, three schemas.

**Modify — backend tests**

- `backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java` — the
  `AuditEvent.bulkOperationId` entry deleted from `WRITERLESS_BY_DECISION`, plus the three prose
  counts that name it.
- `backend/src/test/java/com/tcms/contract/ContractConventionTest.java` — three `RESPONSE_RECORDS`
  entries.

**Create — backend tests**

- `backend/src/test/java/com/tcms/execution/ExecutionBulkAssignTest.java` — 14 arms, prefix `88`.

**Create — frontend**

- `frontend/src/features/runs/AssignExecutionsDialog.tsx` — preview-then-commit, both modes.
- `frontend/src/features/runs/AssignExecutionsDialog.test.tsx`
- `frontend/src/features/runs/executionSelection.ts` — the selection helpers (`toggle`, `allOnPage`,
  `tallyByAssignee`), so the page holds state and not arithmetic.

**Modify — frontend**

- `frontend/src/api/queries/runs.ts` — `useBulkAssignExecutions`.
- `frontend/src/api/queries/index.ts`, `frontend/src/api/types.ts` — the export and the three types.
- `frontend/src/features/runs/RunDetailPage.tsx` — checkbox column, selection state, bulk bar.
- `frontend/src/features/audit/AuditLogPage.tsx` — grouping by `bulkOperationId`.
- `frontend/src/features/audit/AuditLogPage.test.tsx` — the grouping arms.
- `frontend/src/test/fixtures.ts` — a bulk-assign mock arm and two grouped audit rows.
- `frontend/src/api/schema.d.ts` **and** `packages/client/src/schema.ts` — both regenerated.

**Modify — docs**

- `docs/verification/frontend-browser-checks.md`, `docs/verification/backend-mutation-checks.md`.
- `docs/handoff/244-bulk-execution-assignment.md` (new) + one row in `docs/HANDOFF.md`'s index.
- `docs/agent-context/be-java.md`, `docs/agent-context/fe-react.md` — in this area's own commits.
- `docs/features.md` — the gap's row.

---

## T1 — The contract

**Deliverable:** `POST /projects/{projectId}/runs/{runId}/executions/bulk` and its three schemas are
in `docs/specs/openapi.yaml`, and `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts`
are regenerated and byte-identical.
**Depends on:** none

**Files:**
- Modify: `docs/specs/openapi.yaml` — a new path after `…/executions/{executionId}/assignee` (that
  path starts at `:3813` as of 2026-08-22) and three schemas beside `ExecutionAssigneeInput`
- Regenerate: `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts`

- [ ] **Step 1: Confirm the contract is free**

```bash
cd ~/RiderProjects/tcms
git log --oneline -5 -- docs/specs/openapi.yaml
git status --short docs/specs/openapi.yaml
```

Expected: no uncommitted change to `openapi.yaml` from another lane. Area A's `durationMs` commit may
or may not be present; either is fine, an *uncommitted* edit is not — one contract holder at a time.

- [ ] **Step 2: Add the path**

Insert immediately **after** the `…/executions/{executionId}/assignee` path block ends (it ends with
its `'409': { $ref: '#/components/responses/Conflict' }`) and before
`/projects/{projectId}/runs/{runId}/executions/{executionId}/evidence`:

```yaml
  /projects/{projectId}/runs/{runId}/executions/bulk:
    parameters:
      - { $ref: '#/components/parameters/ProjectId' }
      - { $ref: '#/components/parameters/RunId' }
    post:
      tags: [execution]
      operationId: bulkAssignExecutions
      summary: Hand many rows of the checklist out at once, to one person or across several
      description: |
        Preview-then-commit over a selection of executions, the shape
        `POST /projects/{projectId}/cases/bulk` established for cases (plan §6f): always returns
        what changed, what was already compliant, and what was skipped with a reason, as LISTS
        rather than counts. A blanket success toast over a partial outcome is the worse half of
        the defect, because the caller believes all 300 went through.

        **Why this exists.** `PUT …/executions/{executionId}/assignee` says it in its own
        description: an execution's assignee *"could be set once, for all two hundred rows, or not
        at all"*. Splitting a 300-case run across five testers was 300 requests.

        **`DISTRIBUTE` is the request this endpoint was actually built for.** It round-robins the
        selection across a list of people, and the mapping is a pure function of the run's own row
        order and the caller's assignee order — so the same act sent twice produces the same split,
        and the replay finds every row already in that state. **That determinism is this
        operation's idempotency, and it is why there is no `Idempotency-Key` here.** The header
        elsewhere in this API answers a *create* by returning the resource that intent already
        created; nothing is created here, so there would be nothing to answer with. The cases-bulk
        endpoint declares no such header either, for the same reason.

        **Assignment states the desired state of every named row**, exactly as the single-row `PUT`
        does — a row already assigned to somebody else is reassigned rather than left alone. A
        caller who wants to fill only the gaps selects only the unassigned rows; the run detail's
        filter makes that a click, and a server-side "only if empty" rule would make the outcome
        depend on state the preview cannot show.

        **The permission is `run:execute`**, the same key the single-row endpoint takes and for the
        same reason: it is the one key in the catalogue that separates a person from a credential,
        so a leaked CI token cannot reorganise a team's workload. No new permission is seeded — the
        catalogue has taken none since `V3`, and a key granted to nobody is a 403 no administrator
        can grant their way out of (roles spec §A6).

        **A closed run refuses with 409**, the freeze the verdict and its evidence are already
        under: a finished checklist has no work left to hand out, and reopening the run is the way
        forward.

        **What refuses the whole act and what skips one row** follows the cases-bulk rule exactly.
        A bad assignee is knowable before a single row is read, so it is a **400** with the error
        on the field. An execution id that names nothing in this run is a property of that row, so
        it comes back in `skipped` and the other 299 proceed: a selection is a filter's output and
        may have gone stale, and refusing everything over one row is worse than the problem.

        **This operation writes one `audit_event` row per target, all sharing one
        `bulkOperationId`** — the first thing in the product to write that column. One row with 300
        `targetIds` would be the shape every other bulk service uses and it cannot state what
        `DISTRIBUTE` did, because one row carries one `changes` document and this act moves rows to
        several different people. `bulkOperationId` is returned so a client can join the two, and
        the audit trail groups on it.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ExecutionBulkOperation' }
      responses:
        '200':
          description: The result, or the preview when `dryRun` is true.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ExecutionBulkResult' }
        '400':
          # An assignee who is not an active member of this company, an argument the action does
          # not take, or a selection that is empty or over the cap. Field errors, so the dialog
          # lands each message on its own control.
          $ref: '#/components/responses/BadRequest'
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
```

- [ ] **Step 3: Add the three schemas**

Put them immediately after `ExecutionAssigneeInput` in `components.schemas`, so the assignment
family reads together:

```yaml
    ExecutionBulkOperation:
      type: object
      required: [action, executionIds]
      description: |
        One action, one argument, over a selection of a run's executions. `BulkOperation`'s shape
        for cases, deliberately — the two are one family and a second spelling of "operate on a
        selection" is how one product starts reading like two.
      properties:
        action:
          type: string
          enum: [ASSIGN, DISTRIBUTE]
          description: |
            `ASSIGN` puts every selected row on one person — or on nobody, with a null
            `assigneeId`. There is no separate `UNASSIGN`: the single-row `PUT` already spells
            "nobody" as a null assignee, and two ways to say one thing is two implementations to
            keep agreeing.

            `DISTRIBUTE` round-robins the selection across `assigneeIds`.
        executionIds:
          type: array
          minItems: 1
          maxItems: 500
          description: |
            The selection, as execution ids. Duplicates are collapsed and the result reports each
            distinct id exactly once.

            Capped for `BulkOperation.caseIds`' reason and a stronger version of it: the whole act
            is one transaction, and here it also writes up to 500 audit rows. A run larger than
            this is assigned in two acts, which is two `bulkOperationId`s and reads correctly in
            the trail.

            An id naming no execution **in this run** comes back in `skipped`, not as a 404 — see
            the operation.
          items: { type: string, format: uuid }
        dryRun:
          type: boolean
          default: true
          description: |
            Preview first. The UI must show the preview before committing, and for `DISTRIBUTE`
            that is the whole point: the person about to hand 300 cases to five colleagues sees
            the split before they confirm it.
        payload:
          $ref: '#/components/schemas/ExecutionBulkPayload'

    ExecutionBulkPayload:
      type: object
      description: |
        The argument, typed. Both members are optional here and **exactly the one its action needs
        is required by the server**, which answers 400 naming the field — `BulkPayload`'s
        arrangement, and its reasoning applies unchanged: a `oneOf` discriminated on `action` would
        state the rule in the schema and cost every consumer a narrow to build a payload it
        constructs one field at a time, while the server has to validate the combination either
        way because it is the only side that knows the action.
      properties:
        assigneeId:
          type: string
          format: uuid
          nullable: true
          description: |
            Required by `ASSIGN`; **`null` unassigns every selected row**, which is a value here
            and not an omission — the single-row endpoint's rule, and what makes "take these off
            everybody's plate" expressible.

            Must be an **ACTIVE member of this company** — the predicate `POST /runs` and the
            single-row `PUT` both apply to this column, read from the same place rather than
            re-spelled. Note it is *company* membership and not the narrower "holds a grant on this
            project" that `GET /members` answers, so a picker built from that list can only offer
            people this operation accepts, never the reverse.

            Refused with a 400 for any other action.
        assigneeIds:
          type: array
          minItems: 1
          maxItems: 50
          items: { type: string, format: uuid }
          description: |
            Required by `DISTRIBUTE`, and refused for `ASSIGN`.

            **Order is the caller's and is load-bearing**: the i-th row of the run-ordered
            selection goes to `assigneeIds[i % assigneeIds.length]`, so this order decides who
            takes the remainder. Duplicates are collapsed keeping the first occurrence. A server
            that sorted this list would silently overrule a coordinator who put the senior tester
            first.

            Every member must be an ACTIVE member of this company, checked before anything is
            written; the 400 names this field and lists the ids that failed.

            More people than rows is legal — the tail simply receives nothing, and the preview
            shows them with a count of zero rather than dropping them, because a person expecting
            work and getting none is the thing worth seeing.

    ExecutionBulkResult:
      type: object
      required: [dryRun, total, changed, unchanged, skipped, bulkOperationId]
      description: |
        Every outcome as a LIST, never a count — `BulkResult`'s rule.

        `changed` carries whole rows rather than keys, which is where this diverges from the case
        result and why: a list of keys says *that* something moved and not *where*, and for
        `DISTRIBUTE` "where" is the entire question the preview exists to answer.
      properties:
        dryRun:
          type: boolean
          description: True when this is a preview. Nothing was written and no audit row exists.
        total:
          type: integer
          description: Size of the requested selection, after duplicates are collapsed.
        changed:
          type: array
          description: |
            The rows that moved — or would move, on a preview. Ordered as the run orders them, so
            the preview reads in the same order as the checklist behind the dialog.
          items:
            type: object
            required: [executionId, caseKey, assigneeId, assigneeName]
            properties:
              executionId: { type: string, format: uuid }
              caseKey: { type: string, example: TC-42 }
              assigneeId:
                type: string
                format: uuid
                nullable: true
                description: Where the row lands. Null when the act unassigns.
              assigneeName:
                type: string
                nullable: true
                description: |
                  Resolved at read time from the company's membership, never stored. Null when the
                  row is being unassigned, and null when the id names a membership that no longer
                  exists at all — the client owns that copy, which is why this is nullable rather
                  than a server-invented placeholder.
        unchanged:
          type: array
          description: |
            Case keys already in the requested state — compliant, not failed. **No audit row is
            written for these**, which is the same rule the single-row endpoint applies: two people
            handing one case to one tester are agreeing, not conflicting.
          items: { type: string, example: TC-43 }
        skipped:
          type: array
          description: Selected ids that could not be assigned, each with a displayable reason.
          items:
            type: object
            required: [executionId, reason]
            properties:
              executionId: { type: string, format: uuid }
              reason:
                type: string
                example: 'That execution is not part of this run.'
        bulkOperationId:
          type: string
          format: uuid
          nullable: true
          description: |
            The id every `audit_event` row this act wrote shares — the join between the result and
            the trail, and the first value ever written into `audit_event.bulk_operation_id`.

            **Null on a preview** (nothing happened) and **null on a commit that changed nothing**
            (no rows were written, so naming a group with no members would be a lie). Otherwise a
            fresh id per act.
```

- [ ] **Step 4: Regenerate both copies and prove they agree**

```bash
cd ~/RiderProjects/tcms/frontend && npm run gen:api
cd ../packages/client && npm run gen:api
diff ../../frontend/src/api/schema.d.ts src/schema.ts && echo IDENTICAL
```

Expected: `IDENTICAL`. A difference fails `schemaFreshness.test.ts` and
`contractPairing.test.ts`; fix it now rather than at T9.

- [ ] **Step 5: Commit the contract alone**

```bash
cd ~/RiderProjects/tcms
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts
git commit -m "contract: a run's checklist can be handed out in one act"
```

---

## T2 — The failing backend test

**Deliverable:** `ExecutionBulkAssignTest` exists with fourteen named arms and fails to compile
because `ExecutionBulkAssignService` does not exist.
**Depends on:** T1 (the contract the arms assert against)

**Files:**
- Create: `backend/src/test/java/com/tcms/execution/ExecutionBulkAssignTest.java`

- [ ] **Step 1: Write the class header and the fixture**

Extend `IntegrationTestBase` — a real Postgres 17 through Testcontainers, as every backend test in
this repository does. **Every id uses the `88…` prefix.**

```java
package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Handing a run's checklist out in one act — <strong>and the first writer of
 * {@code audit_event.bulk_operation_id} in this product's history.</strong>
 *
 * <p>Three things here are not obvious and each has an arm of its own.
 *
 * <ol>
 *   <li><strong>Determinism is this operation's idempotency.</strong> There is no
 *       {@code Idempotency-Key} — nothing is created, so there would be no resource to answer a
 *       replay with. Instead the split is a pure function of the run's row order and the caller's
 *       assignee order, and an assignment that changes nothing writes no audit row, so a replay is
 *       a no-op that can be asserted rather than assumed.</li>
 *   <li><strong>A bad assignee refuses the act; a stale execution id skips one row.</strong> That
 *       is {@code BulkOperation}'s rule — knowable before any row is read versus a property of the
 *       row — and getting it backwards is how a filtered selection of four hundred becomes
 *       unusable because one row went away.</li>
 *   <li><strong>One audit row per target, sharing one id.</strong> Every other bulk service in this
 *       codebase writes ONE row with a {@code target_ids} array. That shape cannot state what a
 *       distribute did, because one row has one {@code changes} document and this act moves rows to
 *       several people.</li>
 * </ol>
 *
 * <p>Ids use the {@code 88…} prefix, pre-assigned to this area and checked against every
 * {@code UUID.fromString} literal under {@code src/test/java} on 2026-08-22 — handoff §19's fixture
 * collision has happened five times and two lanes taking one prefix merge clean.
 */
@AutoConfigureMockMvc
@DisplayName("Bulk execution assignment — one act, and the trail that can still be read afterwards")
class ExecutionBulkAssignTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("88000000-0000-4000-8000-00000000000a");
    /** A second company. Its member drives the cross-tenant assignee arm. */
    private static final UUID RIVAL = UUID.fromString("88000000-0000-4000-8000-00000000000b");

    private static final UUID ATLAS = UUID.fromString("88001000-0000-4000-8000-000000000001");

    /** Test Lead on ATLAS: holds {@code run:execute}. */
    private static final UUID LEAD = UUID.fromString("88003000-0000-4000-8000-000000000001");
    /** Five testers, in the order a coordinator would type them. */
    private static final UUID ANA = UUID.fromString("88003000-0000-4000-8000-000000000002");
    private static final UUID BEN = UUID.fromString("88003000-0000-4000-8000-000000000003");
    private static final UUID CHO = UUID.fromString("88003000-0000-4000-8000-000000000004");
    private static final UUID DEV = UUID.fromString("88003000-0000-4000-8000-000000000005");
    private static final UUID EVE = UUID.fromString("88003000-0000-4000-8000-000000000006");
    /** An ACTIVE member of RIVAL and of nothing in ACME. */
    private static final UUID STRANGER = UUID.fromString("88003000-0000-4000-8000-000000000007");
    /** INVITED in ACME and never accepted — present, and not a member for this purpose. */
    private static final UUID INVITEE = UUID.fromString("88003000-0000-4000-8000-000000000008");
    /** Test Engineer on ATLAS, holding no {@code run:execute}. */
    private static final UUID VIEWER = UUID.fromString("88003000-0000-4000-8000-000000000009");

    private static final UUID RUN = UUID.fromString("88005000-0000-4000-8000-000000000001");
    /** A run in ATLAS that has ended, for the freeze arm. */
    private static final UUID CLOSED_RUN = UUID.fromString("88005000-0000-4000-8000-000000000002");
}
```

Build the twelve-case run through the product's own create path (`POST /runs` with a suite scope),
never by inserting `test_execution` rows: a fixture that inserts proves the query works over data
the product does not produce, which is the trap `AuditLogReadTest`'s header names.

- [ ] **Step 2: Write the fourteen arms**

Each is a `@Test` with a `@DisplayName`. Write the bodies as MockMvc exchanges against
`POST /api/v1/projects/{ATLAS}/runs/{RUN}/executions/bulk`, asserting on the response body **and**
on `audit_event` read back through `AuditEventRepository`, never on the response alone.

```java
    @Test
    @DisplayName("assigning a selection puts every row on that person")
    void aStraightAssignMovesEveryRow() {
        // ASSIGN, assigneeId = ANA, 12 ids, dryRun false.
        // changed has 12 rows, each naming ANA and her display name; unchanged and skipped empty;
        // total 12; bulkOperationId non-null. Read the executions back and assert the COLUMN, not
        // just the response: a service that answered correctly and wrote nothing passes a
        // body-only assertion.
    }

    @Test
    @DisplayName("a preview writes nothing at all")
    void aDryRunChangesNothing() {
        // Same request with dryRun true (and again with dryRun ABSENT, since the contract defaults
        // it to true and an absent field defaulting to FALSE is the one mistake here that destroys
        // data). changed still lists the 12 rows it WOULD move; every execution still has its old
        // assignee; zero audit rows; bulkOperationId is null.
    }

    @Test
    @DisplayName("a null assignee takes every selected row off everybody")
    void assigningNobodyUnassigns() {
        // ASSIGN with assigneeId null over rows previously on ANA.
        // changed lists 12 rows with assigneeId null and assigneeName null; the audit rows carry
        // action "execution.unassigned" and {"from":"<ana>","to":null}.
    }

    @Test
    @DisplayName("rows already on that person come back as unchanged and write no audit row")
    void anAlreadyAssignedRowIsUnchanged() {
        // Assign 12 to ANA, then assign the same 12 to ANA again.
        // Second call: changed empty, unchanged holds all 12 case keys, bulkOperationId NULL.
        // Assert the audit row count did not move — this is the rule that makes the whole
        // operation idempotent, and it is inherited from ExecutionAssignmentService.
    }

    @Test
    @DisplayName("distributing twelve rows across five people splits them 3/3/2/2/2")
    void distributeRoundRobinsInRunOrder() {
        // DISTRIBUTE across [ANA, BEN, CHO, DEV, EVE] over all 12 rows.
        // Assert the exact mapping per case key, in the run's key order: row 0 -> ANA, 1 -> BEN,
        // ... 5 -> ANA, and so on. Counts 3/3/2/2/2, and the FIRST TWO in the caller's list hold
        // the remainder — that is the arm that pins "the caller's order decides the remainder".
    }

    @Test
    @DisplayName("the split does not depend on the order the caller listed the executions in")
    void theSelectionIsASetAndTheRunDecidesTheOrder() {
        // Same distribute with executionIds shuffled. Identical mapping.
        // The defect this catches is a service that round-robins over the request array: two
        // coordinators selecting the same rows would then produce different splits, and neither
        // could tell.
    }

    @Test
    @DisplayName("a repeat of the same distribute changes nothing and writes no second operation")
    void aRetryChangesNothingAndWritesNoSecondOperation() {
        // Send the identical distribute twice.
        // Second call: changed empty, unchanged 12, bulkOperationId null, audit row count
        // unchanged. THIS IS THE ARM THAT REPLACES AN IDEMPOTENCY KEY — if it fails, the
        // determinism argument in the contract is false and the endpoint needs a different answer,
        // not a patched test.
    }

    @Test
    @DisplayName("more people than rows leaves the tail with nothing, and says so")
    void moreAssigneesThanRowsIsLegal() {
        // DISTRIBUTE across five people over three rows: three changed rows on the first three,
        // and the act succeeds. The preview's per-person tally is the client's arithmetic, so
        // nothing is asserted about EVE here beyond the act not failing.
    }

    @Test
    @DisplayName("an assignee who is not an active member of this company refuses the whole act")
    void aStrangerIsAFieldErrorAndNothingIsWritten() {
        // DISTRIBUTE across [ANA, STRANGER]: 400, errors keyed "payload.assigneeIds", and
        // ***nothing is written*** — assert every execution still holds its previous assignee and
        // no audit row was added. One transaction is what makes this true.
        // Repeat with INVITEE, whose membership exists and is not ACTIVE: same answer. An
        // unaccepted invitation must not become a way to be handed a client's work.
    }

    @Test
    @DisplayName("an execution id from another run is skipped, and the rest of the act proceeds")
    void aForeignExecutionIdSkipsOneRow() {
        // 12 good ids plus one from CLOSED_RUN and one random UUID.
        // 200. changed 12, skipped 2 with a displayable reason naming each id. This is the
        // asymmetry with the arm above and the one most likely to be "simplified" into a 404.
    }

    @Test
    @DisplayName("a caller without run:execute is refused, and it is the same key the single row takes")
    void aCallerWithoutRunExecuteIsRefused() {
        // VIEWER: 403, and nothing written. Asserting the WRITE too, because a service that
        // answered 403 after applying is the shape a gate placed after the work produces.
    }

    @Test
    @DisplayName("a run that has ended refuses with 409")
    void aClosedRunIsFrozen() {
        // CLOSED_RUN: 409. The same freeze the verdict is under, and the message names reopening.
    }

    @Test
    @DisplayName("every audit row of one act shares one bulkOperationId, and different acts do not")
    void oneActIsOneBulkOperationId() {
        // Distribute, then assign, then read audit_event.
        // First act: 12 rows, all non-null bulkOperationId, all EQUAL.
        // Second act: 12 more rows sharing a DIFFERENT non-null id.
        // Assert the response's bulkOperationId equals the id on the rows — that join is what the
        // audit screen and every future digest read.
    }

    @Test
    @DisplayName("a bulk row records the same action and the same changes document as a single assign")
    void theTrailReadsTheSameWhicheverDoorWasUsed() {
        // Assign one row through PUT .../assignee and one through this endpoint, to the same
        // person from the same previous state. The two audit rows differ ONLY in
        // bulk_operation_id: same action name, same {"from":…,"to":…} document.
        // This is what ExecutionAssignmentAudit exists to guarantee, and the arm that fails if
        // somebody adds an "execution.bulk_assigned" verb later.
    }
```

- [ ] **Step 3: Run it and watch it fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.execution.ExecutionBulkAssignTest'
```

Expected: **FAIL to compile** — `ExecutionBulkAssignService` and the two records do not exist.
**Tests run on `shanks`, never locally** — standing directive, restated 2026-08-15.

---

## T3 — The request and result records

**Deliverable:** `ExecutionBulkAssignRequest`, `ExecutionBulkAssignPayload` and
`ExecutionBulkAssignResult` compile and carry exactly the members their schemas declare.
**Depends on:** T2

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/ExecutionBulkAssignRequest.java`
- Create: `backend/src/main/java/com/tcms/execution/ExecutionBulkAssignPayload.java`
- Create: `backend/src/main/java/com/tcms/execution/ExecutionBulkAssignResult.java`

**Public records, not package-private.** `ContractConventionTest` compares a record's components to
its paired schema's properties and needs to reach the class; `ExecutionAssigneeRequest` beside them
is public for the same reason.

- [ ] **Step 1: The request**

```java
package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code ExecutionBulkOperation} in {@code docs/specs/openapi.yaml} — one action, one argument, one
 * selection.
 *
 * @param action       {@code ASSIGN} or {@code DISTRIBUTE}. There is no {@code UNASSIGN}: the
 *                     single-row {@code PUT} already spells "nobody" as a null assignee, and a
 *                     second spelling is a second implementation to keep agreeing.
 * @param executionIds the selection. Duplicates are collapsed by the service and each distinct id
 *                     is reported exactly once. Capped at 500 — one transaction, and up to 500
 *                     audit rows.
 * @param dryRun       {@code null} means <strong>true</strong>, which is the contract's default and
 *                     the one defaulting mistake here that would destroy data: a client omitting
 *                     the field must get a preview, never a commit.
 * @param payload      the argument, whose required member depends on {@link #action}.
 */
public record ExecutionBulkAssignRequest(
        String action, List<UUID> executionIds, Boolean dryRun, ExecutionBulkAssignPayload payload) {

    /** Whether this is a preview. Absent means yes — see the {@code dryRun} note above. */
    boolean previewing() {
        return dryRun == null || dryRun;
    }
}
```

- [ ] **Step 2: The payload**

```java
package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code ExecutionBulkPayload} — both members optional here, exactly one required by the server
 * depending on the action.
 *
 * <p>A flat record rather than a sealed hierarchy per action, matching {@code BulkPayload}: the
 * server has to validate the combination either way because it is the only side that knows the
 * action, so a typed union would buy a compile-time check the client cannot use.
 *
 * @param assigneeId  required by {@code ASSIGN}; <strong>null is a value</strong> and unassigns.
 *                    Because null is meaningful, "the caller sent no argument at all" is a
 *                    different state and is why {@link #payload} may itself be null on the request.
 * @param assigneeIds required by {@code DISTRIBUTE}. <strong>Order is the caller's and decides who
 *                    takes the remainder</strong>; the service collapses duplicates keeping the
 *                    first occurrence and never sorts.
 */
public record ExecutionBulkAssignPayload(UUID assigneeId, List<UUID> assigneeIds) {
}
```

- [ ] **Step 3: The result**

```java
package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code ExecutionBulkResult} — every outcome as a list, never a count ({@code BulkResult}'s rule,
 * plan §6f).
 *
 * <p><strong>{@link #changed} carries whole rows where the case result carries keys</strong>, and
 * that divergence is the point: for a distribute, a list of keys says that something moved and not
 * where, which is the entire question the preview exists to answer.
 *
 * @param bulkOperationId the id every audit row of this act shares — null on a preview, and null on
 *                        a commit that changed nothing, because naming a group with no members is a
 *                        claim about rows that do not exist.
 */
public record ExecutionBulkAssignResult(
        boolean dryRun,
        int total,
        List<AssignmentChange> changed,
        List<String> unchanged,
        List<SkippedRow> skipped,
        UUID bulkOperationId) {

    /**
     * One row that moved, or would.
     *
     * @param assigneeName resolved at read time from this company's membership, never stored. Null
     *                     when the row is being unassigned and null when the id names a membership
     *                     that no longer exists at all — the client owns that copy, so the server
     *                     does not invent "Unknown".
     */
    public record AssignmentChange(
            UUID executionId, String caseKey, UUID assigneeId, String assigneeName) {
    }

    /** A selected id that could not be assigned, with a sentence a dialog can print. */
    public record SkippedRow(UUID executionId, String reason) {
    }
}
```

---

## T4 — `bulk_operation_id`'s writer seam

**Deliverable:** `AuditEvent.record` has an overload that fills `bulkOperationId`, and
`ExecutionAssignmentAudit` is the single speller of the assignment action names and `changes`
document, called by both writers of `assignee_id`.
**Depends on:** T3

**Files:**
- Modify: `backend/src/main/java/com/tcms/tenancy/AuditEvent.java`
- Create: `backend/src/main/java/com/tcms/execution/ExecutionAssignmentAudit.java`
- Modify: `backend/src/main/java/com/tcms/execution/ExecutionAssignmentService.java` (its inline
  `AuditEvent.record(...)` call)

- [ ] **Step 1: Add the overload**

In `AuditEvent`, beside the existing factory. The existing seven-argument form delegates, so there
is one assignment site per column and `companyId`/`actorTokenId` keep coming from the contexts
rather than from parameters:

```java
    /**
     * The same, for <strong>one target of a bulk act</strong> — every row of which carries the same
     * {@code bulkOperationId}.
     *
     * <p>Its own overload rather than an eighth parameter on the form above, because that form has
     * 42 call sites and none of them is inside a bulk act: adding a parameter would be 42 edits
     * passing {@code null} to say "not a bulk", which is noise at every site that is not this one.
     *
     * <p><strong>This is {@code bulk_operation_id}'s first writer</strong>, eighty-odd sections
     * after {@code V1} created the column. Until §244 every bulk service in this codebase recorded
     * ONE row per act with a {@code target_ids} array, which is why the column stood empty and why
     * it sat in {@code ArchitectureRulesTest.WRITERLESS_BY_DECISION}. That shape is still right for
     * an act that does one thing to many rows; it cannot state an act that does a <em>different</em>
     * thing to each row, because one row carries one {@code changes} document.
     *
     * @param bulkOperationId one id per act, shared by every row it writes. Never null here — a
     *                        caller with nothing to group calls the seven-argument form.
     */
    public static AuditEvent record(
            UUID projectId,
            UUID actorUserId,
            String action,
            String targetType,
            List<UUID> targetIds,
            String changes,
            Instant now,
            UUID bulkOperationId) {

        AuditEvent event = record(projectId, actorUserId, action, targetType, targetIds, changes, now);
        event.bulkOperationId = Objects.requireNonNull(bulkOperationId, "a bulk row needs its act's id");
        return event;
    }
```

Add `import java.util.Objects;` if it is not already there.

- [ ] **Step 2: Rewrite the field's javadoc, in the same commit**

The paragraph at `bulkOperationId` currently says *"~~Set~~ — **it never is.**"* and names slice 4 as
its future writer. That claim is false the moment T5 lands, and a stale comment is the
`citedFiles`/`tierClaims` class of defect this repository spends tests preventing. Replace the two
paragraphs with:

```java
    /**
     * Set when this event is one of many from a single bulk operation.
     *
     * <p>~~<strong>It never is</strong> — every bulk service records ONE row per act rather than one
     * per target.~~ → <strong>superseded 2026-08-22 (§244).</strong> {@code
     * execution.ExecutionBulkAssignService} writes one row per target sharing one id, and it is the
     * first thing in the product to do so. The older shape is still right where it applies: one act
     * doing one thing to many rows is one row with a {@code target_ids} array, and the audit screen
     * renders it as "143 items". What forced this column open is an act that does a
     * <strong>different</strong> thing to each row — a distribute puts twelve cases on five people,
     * and one {@code changes} document cannot say that truthfully.
     *
     * <p>Read since §242 ({@code identity.AuditEventView}), written since §244. Both halves of the
     * pair are now live and its entries are gone from both maps in {@code ArchitectureRulesTest} —
     * which took three sections and is that rule's longest worked example.
     */
```

- [ ] **Step 3: Extract the assignment audit vocabulary**

```java
package com.tcms.execution;

import com.tcms.tenancy.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one speller of an assignment's audit row — <strong>both writers of
 * {@code test_execution.assignee_id} go through it.</strong>
 *
 * <p>Extracted in §244 rather than copied, because the copy would have been the shape §72 already
 * paid for once here: two sites building the same JSON document by concatenation, under an
 * instruction to keep them in step. A bulk act and a single assignment are the same fact about a
 * row and must read identically in the trail — {@code ExecutionBulkAssignTest
 * .theTrailReadsTheSameWhicheverDoorWasUsed} is the assertion, and it fails the day somebody adds
 * an {@code execution.bulk_assigned} verb.
 *
 * <p>The document names <strong>both ends</strong> deliberately: the question an auditor asks is
 * about a MOVE, and "who took this off Ana" is unanswerable from a row that only records where it
 * landed.
 */
final class ExecutionAssignmentAudit {

    private ExecutionAssignmentAudit() {
    }

    /** {@code execution.assigned}, or {@code execution.unassigned} when nobody now holds the row. */
    static String action(UUID to) {
        return to == null ? "execution.unassigned" : "execution.assigned";
    }

    /** {@code {"from":…,"to":…}}, with both ends spelled as a JSON null when absent. */
    static String changes(UUID from, UUID to) {
        return "{\"from\":" + id(from) + ",\"to\":" + id(to) + "}";
    }

    private static String id(UUID value) {
        return value == null ? "null" : AuditEvent.jsonString(value.toString());
    }

    /** One row of a bulk act. {@code bulkOperationId} is what makes it groupable in the trail. */
    static AuditEvent bulkRow(
            UUID projectId, UUID actorUserId, UUID executionId, UUID from, UUID to,
            Instant now, UUID bulkOperationId) {

        return AuditEvent.record(
                projectId, actorUserId, action(to), "TEST_EXECUTION", List.of(executionId),
                changes(from, to), now, bulkOperationId);
    }
}
```

- [ ] **Step 4: Route the single-row writer through it**

In `ExecutionAssignmentService.apply`, replace the inline `AuditEvent.record(...)` call — the one
that concatenates `"{\"from\":" + …` — with:

```java
        auditEvents.save(AuditEvent.record(
                execution.getProjectId(),
                userId,
                ExecutionAssignmentAudit.action(assigneeId),
                "TEST_EXECUTION",
                List.of(execution.getId()),
                ExecutionAssignmentAudit.changes(previous, assigneeId),
                now));
```

Keep the existing comment about recording both ends, moved to the helper if it reads better there;
do not delete the reasoning.

- [ ] **Step 5: Compile only — the suite is still red**

```bash
~/bin/tcms-remote backend ./gradlew compileJava compileTestJava
```

Expected: `compileTestJava` still fails on the missing service. That is T5.

---

## T5 — The service, straight assign

**Deliverable:** the `ASSIGN` arms of `ExecutionBulkAssignTest` are green — a straight assign, a dry
run, an unassign, an already-assigned no-op, the stranger refusal, the foreign-id skip, the 403 and
the 409.
**Depends on:** T4

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/ExecutionBulkAssignService.java`
- Modify: `backend/src/main/java/com/tcms/execution/TestExecutionRepository.java`

- [ ] **Step 1: Add the one query this needs**

```java
    /**
     * The named executions of one run, in the run's own order.
     *
     * <p>One query for a whole selection, never one per id: a bulk act names up to 500 rows and a
     * lookup per row is the N+1 that looks harmless because each query is fast — {@code
     * ExecutionPeople}'s own rule, one file over.
     *
     * <p>Ordered by {@code caseId} for {@link #findForRun}'s reason: the row order a database
     * happens to return is not an order, and this operation's split must not depend on it. The
     * caller re-orders by the pinned display-key sequence afterwards, exactly as {@code RunService}
     * does — but a total order here is what makes that re-order stable when two keys collide.
     */
    @Query("SELECT e FROM TestExecution e WHERE e.projectId = :projectId AND e.runId = :runId "
            + "AND e.id IN :ids ORDER BY e.caseId")
    List<TestExecution> findAllInRun(
            @Param("projectId") UUID projectId,
            @Param("runId") UUID runId,
            @Param("ids") Collection<UUID> ids);
```

The interface already carries `@Transactional(readOnly = true)` — **do not remove it**; a repository
without it returns zero rows under RLS, fail-closed and silent.

- [ ] **Step 2: Write the service — resolution, gate, validation, apply**

```java
package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handing many rows of a run's checklist out in one act.
 *
 * <h2>Why this is its own class and not a method on {@link ExecutionAssignmentService}</h2>
 *
 * <p>That class answers about ONE row and its {@code Outcome} is shaped for one: {@code Assigned}
 * carries a whole {@code TestExecutionView}, and {@code Invalid} names one field. A bulk act's
 * answer is three lists, its refusals are act-shaped rather than row-shaped, and it needs a
 * transaction that spans 500 writes. What is shared is shared rather than copied — {@link
 * ExecutionTargets} for the path, {@link ExecutionPeople#isActiveMember} for the predicate, {@link
 * ExecutionAssignmentAudit} for the trail — which is the arrangement §72 established here.
 *
 * <h2>The whole act is one transaction, and that is a requirement rather than a default</h2>
 *
 * <p>The partial-failure arm depends on it: a stranger in {@code assigneeIds} is found while
 * validating, and nothing may be written including no half-used {@code bulkOperationId}. A service
 * that saved as it went would leave a group whose members are the rows it got to before the fault.
 *
 * <h2>Determinism is this operation's idempotency</h2>
 *
 * <p>There is no {@code Idempotency-Key}. The mapping is a pure function of the run's row order and
 * the caller's assignee order, and a row already in the requested state is reported {@code
 * unchanged} and writes no audit row — so a replay is a no-op that can be asserted. The contract
 * states this where a client can read it; {@code aRetryChangesNothingAndWritesNoSecondOperation} is
 * what keeps it true.
 */
@Service
public class ExecutionBulkAssignService {

    /** The same key the single-row write takes — see {@link ExecutionAssignmentService}. */
    private static final String PERMISSION = "run:execute";

    private static final int MAX_SELECTION = 500;
    private static final int MAX_ASSIGNEES = 50;

    private final ProjectService projects;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;
    private final ExecutionPeople people;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    // constructor omitted here for brevity in the plan — inject exactly these six, in this order.

    /** What happened. Sealed, so {@code RunController}'s mapping is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project or run — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code run:execute} is not held. Bodiless 403. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages. Act-shaped faults only; a bad ROW is a skip, not a fault. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The run has ended, so its checklist is frozen. 409, and reopening is the way forward. */
        record RunClosed() implements Outcome {
        }

        /** 200 — the result, or the preview. */
        record Applied(ExecutionBulkAssignResult result) implements Outcome {
        }
    }

    @Transactional
    public Outcome assign(
            UUID userId, UUID projectId, UUID runId, ExecutionBulkAssignRequest request) {

        UUID companyId = CompanyContext.require();

        // The read gate first, so an unreachable target is a 404 before the permission is
        // consulted — ExecutionTargets' ordering and its reason: a 403 on a path naming a sibling
        // project's run would confirm that run exists.
        Optional<TestRun> found = projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> runs.findLiveInProject(project.getId(), runId));
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestRun run = found.get();

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, PERMISSION,
                project -> apply(companyId, userId, run, request));

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }
```

- [ ] **Step 3: Write `apply` — the order of the four checks is the design**

```java
    private Outcome apply(
            UUID companyId, UUID userId, TestRun run, ExecutionBulkAssignRequest request) {

        // Before validation, as on the verdict and the single assign: a field the caller could fix
        // is not worth reporting to somebody whose save cannot succeed at all until the run is
        // reopened.
        if (run.isClosed()) {
            return new Outcome.RunClosed();
        }

        Map<String, List<String>> errors = validate(companyId, request);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        // Duplicates collapsed, order irrelevant here — the repository returns run order.
        List<UUID> selection = new ArrayList<>(new LinkedHashSet<>(request.executionIds()));
        List<TestExecution> rows =
                executions.findAllInRun(run.getProjectId(), run.getId(), selection);

        // Anything the query did not return is not in this run: a stale selection, which is an
        // ordinary event and a SKIP rather than a 404 over the other 299 rows.
        List<ExecutionBulkAssignResult.SkippedRow> skipped = new ArrayList<>();
        var present = rows.stream().map(TestExecution::getId).collect(java.util.stream.Collectors.toSet());
        for (UUID id : selection) {
            if (!present.contains(id)) {
                skipped.add(new ExecutionBulkAssignResult.SkippedRow(
                        id, "That execution is not part of this run."));
            }
        }

        // The mapping. ORDERED BY THE RUN, never by the request array — two coordinators selecting
        // the same rows must get the same split, and a set has no order of its own.
        List<TestExecution> ordered = inRunOrder(rows);
        Map<UUID, UUID> destination = destinations(ordered, request);

        Instant now = clock.instant();
        UUID bulkOperationId = UUID.randomUUID();

        List<ExecutionBulkAssignResult.AssignmentChange> changed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        Map<UUID, String> names = people.namesOf(companyId, destination.values());

        for (TestExecution execution : ordered) {
            UUID to = destination.get(execution.getId());
            if (Objects.equals(execution.getAssigneeId(), to)) {
                // Already there. No write, no audit row — the single-row endpoint's rule, and what
                // makes a replay a no-op.
                unchanged.add(keyOf(execution));
                continue;
            }
            changed.add(new ExecutionBulkAssignResult.AssignmentChange(
                    execution.getId(), keyOf(execution), to, to == null ? null : names.get(to)));

            if (!request.previewing()) {
                UUID from = execution.getAssigneeId();
                execution.assignTo(to, now);
                executions.save(execution);
                auditEvents.save(ExecutionAssignmentAudit.bulkRow(
                        execution.getProjectId(), userId, execution.getId(), from, to, now,
                        bulkOperationId));
            }
        }

        return new Outcome.Applied(new ExecutionBulkAssignResult(
                request.previewing(),
                selection.size(),
                changed,
                unchanged,
                skipped,
                // Null on a preview, and null when nothing moved: an id naming a group with no
                // members is a claim about rows that do not exist.
                request.previewing() || changed.isEmpty() ? null : bulkOperationId));
    }
```

`keyOf` resolves the pinned display key the same way `ExecutionService` already does for a run's
rows — **reuse that resolution, do not re-derive it**; if it is private there, widen it to
package-private rather than writing a second one.

`people.namesOf` is `ExecutionPeople`'s existing batch resolution (two queries for a whole run,
never two per row). If its current signature does not take a collection, add the collection form
beside `resolve` rather than calling `resolve` in the loop.

- [ ] **Step 4: Write `validate` — every fault collected, never short-circuited**

```java
    /**
     * The act-shaped faults, collected rather than short-circuited: a coordinator at the end of a
     * long selection should not be sent round the loop twice, which is why {@code
     * ExecutionService.validate} returns a map too.
     */
    private Map<String, List<String>> validate(UUID companyId, ExecutionBulkAssignRequest request) {
        Map<String, List<String>> errors = new java.util.LinkedHashMap<>();

        if (request == null || request.executionIds() == null || request.executionIds().isEmpty()) {
            errors.put("executionIds", List.of("Select at least one row of the checklist."));
        } else if (new LinkedHashSet<>(request.executionIds()).size() > MAX_SELECTION) {
            errors.put("executionIds",
                    List.of("Assign at most " + MAX_SELECTION + " rows at a time."));
        }
        if (request == null || request.action() == null) {
            errors.put("action", List.of("Choose whether to assign or to distribute."));
            return errors;
        }

        ExecutionBulkAssignPayload payload =
                request.payload() == null
                        ? new ExecutionBulkAssignPayload(null, null)
                        : request.payload();

        switch (request.action()) {
            case "ASSIGN" -> {
                if (payload.assigneeIds() != null) {
                    errors.put("payload.assigneeIds",
                            List.of("A list of people is only meaningful when distributing."));
                }
                // assigneeId null is UNASSIGN and is legal — the one place absence is a value.
                if (payload.assigneeId() != null
                        && !people.isActiveMember(companyId, payload.assigneeId())) {
                    errors.put("payload.assigneeId",
                            List.of("That person is not a member of this company."));
                }
            }
            case "DISTRIBUTE" -> {
                if (payload.assigneeId() != null) {
                    errors.put("payload.assigneeId",
                            List.of("Distributing takes a list of people, not one."));
                }
                List<UUID> assignees = payload.assigneeIds() == null
                        ? List.of()
                        : List.copyOf(new LinkedHashSet<>(payload.assigneeIds()));
                if (assignees.isEmpty()) {
                    errors.put("payload.assigneeIds", List.of("Choose at least one person."));
                } else if (assignees.size() > MAX_ASSIGNEES) {
                    errors.put("payload.assigneeIds",
                            List.of("Distribute across at most " + MAX_ASSIGNEES + " people."));
                } else {
                    // isActiveMember, NOT resolve: app_user is instance-level (ERD §0.1's
                    // exception list), so "the account exists" accepts a user id belonging to
                    // another client entirely and puts a stranger's name on this company's screens.
                    List<String> strangers = assignees.stream()
                            .filter(id -> !people.isActiveMember(companyId, id))
                            .map(UUID::toString)
                            .toList();
                    if (!strangers.isEmpty()) {
                        errors.put("payload.assigneeIds", List.of(
                                "These people are not members of this company: "
                                        + String.join(", ", strangers)));
                    }
                }
            }
            default -> errors.put("action", List.of("Unknown action."));
        }
        return errors;
    }
```

- [ ] **Step 5: `destinations` — the `ASSIGN` half only, for now**

```java
    /** Which person each row lands on. The {@code DISTRIBUTE} arm arrives in T6. */
    private Map<UUID, UUID> destinations(
            List<TestExecution> ordered, ExecutionBulkAssignRequest request) {

        Map<UUID, UUID> destination = new java.util.LinkedHashMap<>();
        UUID one = request.payload() == null ? null : request.payload().assigneeId();
        for (TestExecution execution : ordered) {
            destination.put(execution.getId(), one);
        }
        return destination;
    }
```

`inRunOrder` sorts by the pinned display-key sequence, tie-broken by `getId()` — the same order
`RunService` serves the run detail in, so the preview reads in the order the checklist behind the
dialog does.

- [ ] **Step 6: Run the assign arms**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.execution.ExecutionBulkAssignTest'
```

Expected: the four `DISTRIBUTE` arms fail (they land every row on `null`); everything else passes.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/execution/ backend/src/main/java/com/tcms/tenancy/AuditEvent.java \
  backend/src/test/java/com/tcms/execution/ExecutionBulkAssignTest.java
git commit -m "feat: a run's checklist can be assigned in one act"
```

---

## T6 — Distribute mode

**Deliverable:** the round-robin arms are green, including
`theSelectionIsASetAndTheRunDecidesTheOrder` and
`aRetryChangesNothingAndWritesNoSecondOperation`.
**Depends on:** T5

**Files:**
- Modify: `backend/src/main/java/com/tcms/execution/ExecutionBulkAssignService.java` —
  `destinations`

- [ ] **Step 1: Replace `destinations` with both arms**

```java
    /**
     * Which person each row lands on.
     *
     * <p><strong>Round-robin over the RUN's order, across the CALLER's list.</strong> Both halves
     * are deliberate and each closes a different defect:
     *
     * <ul>
     *   <li>Ordering the rows by the run rather than by the request array makes the split a
     *       property of the selection rather than of how it was typed — two coordinators selecting
     *       the same rows get the same answer, and neither could have detected otherwise.</li>
     *   <li>Preserving the caller's assignee order is what decides who takes the remainder. Sorting
     *       it would silently overrule a coordinator who listed the senior tester first, and no
     *       screen would show that it had happened.</li>
     * </ul>
     *
     * <p>Together they make the whole operation a pure function of its input, which is this
     * endpoint's answer to idempotency — see the class header.
     */
    private Map<UUID, UUID> destinations(
            List<TestExecution> ordered, ExecutionBulkAssignRequest request) {

        Map<UUID, UUID> destination = new java.util.LinkedHashMap<>();
        ExecutionBulkAssignPayload payload = request.payload();

        if ("DISTRIBUTE".equals(request.action())) {
            List<UUID> assignees = List.copyOf(new LinkedHashSet<>(payload.assigneeIds()));
            for (int index = 0; index < ordered.size(); index++) {
                destination.put(
                        ordered.get(index).getId(), assignees.get(index % assignees.size()));
            }
            return destination;
        }

        UUID one = payload == null ? null : payload.assigneeId();
        for (TestExecution execution : ordered) {
            destination.put(execution.getId(), one);
        }
        return destination;
    }
```

- [ ] **Step 2: Run the class to green**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.execution.ExecutionBulkAssignTest'
```

Expected: **PASS, 14 tests.**

If `aRetryChangesNothingAndWritesNoSecondOperation` is the one that fails, do not weaken it. It is
the assertion standing in for an idempotency key, and a failure means either `inRunOrder` is not
total (two rows with equal keys and no tiebreak) or the unchanged branch is writing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/tcms/execution/ExecutionBulkAssignService.java
git commit -m "feat: a bulk assignment can spread a run across a list of testers"
```

---

## T7 — The controller and the contract pairings

**Deliverable:** `POST …/executions/bulk` answers 200/400/403/404/409 through `RunController`, the
three new records are paired in `ContractConventionTest`, and `./gradlew build` passes except for
the exemption T8 removes.
**Depends on:** T6

**Files:**
- Modify: `backend/src/main/java/com/tcms/execution/RunController.java`
- Modify: `backend/src/test/java/com/tcms/contract/ContractConventionTest.java` —
  `RESPONSE_RECORDS`

- [ ] **Step 1: Add the mapping**

Beside the single-row `assign`, reusing its problem builders verbatim:

```java
    /**
     * Hands many rows of the checklist out at once.
     *
     * <p>{@code POST} to a {@code /bulk} sub-resource, mirroring {@code POST …/cases/bulk} rather
     * than inventing a second spelling of "operate on a selection". Preview-then-commit, so the
     * ordinary sequence is two requests and the first one writes nothing.
     *
     * <p>The four refusals are the single-row endpoint's, unchanged and answered with its own
     * sentences — one key, one freeze, one 404 for every way of being unreachable. What is new is
     * that a bad ROW does not refuse the act: it comes back in {@code skipped} with a reason, which
     * is {@code BulkOperation}'s rule and the reason a stale selection is survivable.
     */
    @PostMapping("/{runId}/executions/bulk")
    public ResponseEntity<Object> bulkAssign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @RequestBody ExecutionBulkAssignRequest request) {

        ExecutionBulkAssignService.Outcome outcome =
                bulkAssignments.assign(UUID.fromString(jwt.getSubject()), projectId, runId, request);

        return switch (outcome) {
            case ExecutionBulkAssignService.Outcome.Applied applied ->
                    ResponseEntity.ok(applied.result());
            case ExecutionBulkAssignService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case ExecutionBulkAssignService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(assignForbiddenProblem());
            case ExecutionBulkAssignService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ExecutionBulkAssignService.Outcome.RunClosed ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(assignmentFrozenProblem());
        };
    }
```

Add `ExecutionBulkAssignService bulkAssignments` as a seventh constructor parameter and field, in
the order the others follow.

- [ ] **Step 2: Pair the three records**

In `ContractConventionTest.RESPONSE_RECORDS`, keeping the map's alphabetical order:

```java
            Map.entry("ExecutionBulkOperation",
                    List.of(com.tcms.execution.ExecutionBulkAssignRequest.class)),
            Map.entry("ExecutionBulkPayload",
                    List.of(com.tcms.execution.ExecutionBulkAssignPayload.class)),
            Map.entry("ExecutionBulkResult",
                    List.of(com.tcms.execution.ExecutionBulkAssignResult.class)),
```

The record names deliberately do not match the schema names, so the name-matching pass would leave
all three compared to nothing in either direction — the hole §170 closed and §242 hit again with
`AuditEvent`. `ApiTokenInput → ApiTokenIssueRequest` is the precedent for an explicit entry.

The two nested records (`AssignmentChange`, `SkippedRow`) pair against the schemas' **inline**
objects. If `everyInlineObjectIsPairedOrExplained` reports them, add both to `INLINE_RECORDS` keyed
by their location (`ExecutionBulkResult.changed.items`,
`ExecutionBulkResult.skipped.items`) — read the failure message, it names the key it wants.

- [ ] **Step 3: Run the full backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: **one failure, and it is the plan working** —
`ArchitectureRulesTest.everyMappedColumnHasAWriter` now reports that
`AuditEvent.bulkOperationId` has a writer while the exemption still claims it does not
(`containsExactlyInAnyOrderElementsOf`, §162: stale exemptions fail in both directions). Everything
else must be green. **Do not commit here** — T8 is the same fix's other half and they belong in one
state.

---

## T8 — Delete the writerless exemption

**Deliverable:** `ArchitectureRulesTest.WRITERLESS_BY_DECISION` no longer names
`AuditEvent.bulkOperationId`, the three prose counts that reference it are corrected, and
`./gradlew build` is green.
**Depends on:** T7

**Files:**
- Modify: `backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java` — the entry at
  `:1058` (as of 2026-08-22), the `WRITERLESS_BY_DECISION` javadoc, the
  `READERLESS_BY_DECISION` javadoc's "writerless as well" list, and the count in
  `everyMappedColumnHasAWriter`'s own comment

- [ ] **Step 1: Delete the entry**

Remove the whole `Map.entry("AuditEvent.bulkOperationId", …)` block — the one whose text ends
*"slice 4 of the gaps plan is the one that changes that, and this entry is what it must delete"*.
That sentence is the instruction; follow it exactly rather than rewriting the entry.

- [ ] **Step 2: Correct the three prose claims that name it**

Leave a struck-through note rather than deleting the history, which is this file's own style and the
working agreement's rule about superseded decisions:

1. In `READERLESS_BY_DECISION`'s javadoc, the sentence *"**Writerless as well** — `anchorSlug`,
   `scheduleCron`, `sharedWithTeamId`, `bulkOperationId` …"* becomes:

```java
     * <del>{@code bulkOperationId}</del> — <strong>which left in §244</strong>, the first bulk act
     * in the product to write one row per target rather than one row per act. The column had been
     * in one map or the other since §152 and is now in neither, having moved between them without
     * the column changing at all: §242 gave it a reader, §244 a writer. That is this pair's
     * longest worked example and the plainest argument there is for running both rules.
```

2. In `everyMappedColumnHasAWriter`'s comment, the tally *"Four of these nine are in
   `READERLESS_BY_DECISION` too"* is now **three of eight**. Recount against the map rather than
   trusting this instruction — a hand-kept tally being wrong inside the check written to end
   hand-kept tallies is the joke this file keeps telling, and it has landed twice already.

3. In `WRITERLESS_BY_DECISION`'s javadoc, add the exit to the running list of members that have
   left, naming §244 and the direction (acquired a writer).

- [ ] **Step 3: Run the rule, then the gate**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest'
~/bin/tcms-remote backend ./gradlew build
```

Expected: **BUILD SUCCESSFUL**, with the suite up by 14.

- [ ] **Step 4: Commit the backend**

```bash
git add backend/src/main/java/com/tcms backend/src/test/java/com/tcms
git commit -m "feat: bulk assignment writes bulk_operation_id, and the exemption goes with it"
```

---

## T9 — The write hook

**Deliverable:** `useBulkAssignExecutions(projectId, runId)` exists, sends the contract's body, and
invalidates the run.
**Depends on:** T7 (the contract is generated; the backend need not be running)

**Files:**
- Modify: `frontend/src/api/queries/runs.ts`
- Modify: `frontend/src/api/queries/index.ts`, `frontend/src/api/types.ts`
- Modify: `frontend/src/test/fixtures.ts`

- [ ] **Step 1: Export the three types**

In `types.ts`, beside the other execution types:

```ts
export type ExecutionBulkOperation = S['ExecutionBulkOperation'];
export type ExecutionBulkResult = S['ExecutionBulkResult'];
/** One row of a preview — what moves, and where to. Named so the dialog's tally can be typed. */
export type ExecutionAssignmentChange = ExecutionBulkResult['changed'][number];
```

- [ ] **Step 2: The hook**

```ts
/**
 * Assigns many rows of a run's checklist in one act — one person, or spread across several.
 *
 * **Two calls per act, and the first one writes nothing.** `dryRun` defaults to true on the server,
 * so the dialog previews, shows the split, and only then commits. Sending the commit without the
 * preview is possible and is exactly what the preview exists to prevent (plan §6f).
 *
 * **No `If-Match` and no `Idempotency-Key`.** The first for `useAssignExecution`'s reason — a
 * reassignment refused over a verdict somebody recorded in between is a conflict about a field
 * neither call touches. The second because there is nothing to key: the operation creates no
 * resource, and its split is a pure function of the run's row order and the assignee order, so a
 * retry lands every row where it already is and reports them all as `unchanged`.
 *
 * The run is invalidated rather than patched: `TestRunDetail` carries every row's assignee, the
 * response carries changes rather than executions, and up to 500 rows moved — a hand-merged cache
 * here is the stale-claim class §42 found on the other end of this screen.
 */
export function useBulkAssignExecutions(projectId: string, runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ExecutionBulkOperation) =>
      api.post<ExecutionBulkResult>(
        `${base(projectId)}/runs/${runId}/executions/bulk`,
        body,
      ),
    // The dialog lands 400s on their own controls and renders the 409 in place, the same call
    // `useAssignExecution` makes about its own refusals.
    meta: { handled: true },
    onSuccess: (result) => {
      // A preview changed nothing, so invalidating would refetch the run for no reason and flash
      // the grid under an open dialog.
      if (result.dryRun) return;
      void client.invalidateQueries({ queryKey: keys.run(projectId, runId) });
    },
  });
}
```

Export it from `queries/index.ts` beside `useAssignExecution`.

- [ ] **Step 3: The mock arm**

In `src/test/fixtures.ts`, add a **path-keyed** arm anchored so the sub-resource is not caught by a
prefix — `/^\/projects\/[^/]+\/runs\/[^/]+\/executions\/bulk$/`. Serve a body that satisfies
`ExecutionBulkResult`'s `required` array, including `bulkOperationId: null` on the preview. Every
exchange crossing the `fetch` double is compared against `openapi.yaml`, so a body missing a
required member fails the contract check rather than the test's own assertion.

---

## T10 — The selection model

**Deliverable:** `RunDetailPage` selects execution rows with a leading checkbox column and shows a
bulk action bar naming the count.
**Depends on:** T9

**Files:**
- Create: `frontend/src/features/runs/executionSelection.ts`
- Modify: `frontend/src/features/runs/RunDetailPage.tsx`

**This screen is an MUI `Table`, not a `DataGrid` — keep it that way.** §241/§242 measured the
reason: `DataGrid` does not render in jsdom at all (its virtualization measures through a
`ResizeObserver` the test shim never fires, so the scroller is 0×0 with no rows), and
`CaseListPage` is the repo's only DataGrid screen and has no component test at all. The checkbox
column here is a `TableCell` with a `Checkbox`, and that is what makes T11's arms possible.

- [ ] **Step 1: The helpers, in their own module**

```ts
/**
 * Selection arithmetic for a run's checklist, out of the page.
 *
 * The page owns the state; this owns what the state means. `tallyByAssignee` in particular is
 * display logic over a server result and belongs beside `lib/results.ts`' family rather than in a
 * dialog body — a second implementation of "how many did Ana get" is how two views of one act come
 * to disagree.
 */
export function toggleSelected(selection: string[], id: string): string[] {
  return selection.includes(id) ? selection.filter((x) => x !== id) : [...selection, id];
}

/** Every row currently in view — the filter chips decide what that is, not this. */
export function selectAll(rows: { id: string }[]): string[] {
  return rows.map((row) => row.id);
}

/** Per-person counts for a preview, in the order the people were listed. */
export function tallyByAssignee(
  changed: ExecutionAssignmentChange[],
  order: string[],
): { assigneeId: string; assigneeName: string | null; count: number }[] {
  // Seeded from `order` so a person who receives NOTHING still appears with 0 — the case a
  // coordinator most needs to see and the one a groupBy over `changed` silently drops.
  ...
}
```

- [ ] **Step 2: Selection state on the page**

```tsx
  const [selection, setSelection] = useState<string[]>([]);
  const [assignOpen, setAssignOpen] = useState(false);
```

Component state, not the URL — `CaseListPage`'s precedent. Filter state lives in the URL because it
is a *view* somebody pastes into a ticket; a selection is a gesture in progress and a URL carrying
three hundred ids is not a link.

**Clear the selection when the filter chip changes.** The chips swap which rows are in view, and a
selection holding ids that are no longer on screen is a bulk act over rows the person cannot see.

- [ ] **Step 3: The checkbox column**

A leading `TableCell` in the head with an indeterminate select-all over `sorted`, and one per row.
Row-click selection is *not* added: the row's click targets are two links already, and
`disableRowSelectionOnClick` is the equivalent decision the case grid makes.

- [ ] **Step 4: The bulk bar**

Above the table, rendered only when `selection.length > 0`, mirroring `CaseListPage`'s bar:
the count, an **Assign…** button gated on `run:execute` through `PermissionGate` (disabled with a
reason, never hidden — §31), a **Clear selection** button, and the caption. The caption differs from
the case grid's on purpose and must say what is true here:

```tsx
  <Typography variant="caption" sx={{ color: 'text.secondary', ml: 'auto' }}>
    Selection covers the rows this filter shows.
  </Typography>
```

The run detail is not paged — it renders the whole checklist filtered by the chip — so "the rows on
this page" would be wrong, and "every row in the run" would be worse: **Not run** is exactly the
filter a coordinator uses before distributing, and telling them the act covers rows the chip hid is
the misstatement this caption exists to prevent.

Disable the bar entirely when the run is closed (`isRunClosed(run.status)`), with the tooltip saying
the checklist is frozen and reopening is the way forward — the server's 409, said before it happens.

---

## T11 — The assignment dialog

**Deliverable:** `AssignExecutionsDialog` previews then commits, in both modes, and lands field
errors on their controls.
**Depends on:** T10

**Files:**
- Create: `frontend/src/features/runs/AssignExecutionsDialog.tsx`
- Create: `frontend/src/features/runs/AssignExecutionsDialog.test.tsx`
- Modify: `frontend/src/features/runs/RunDetailPage.tsx` (mount it)

`BulkActionDialog.tsx` is the model — read it before writing this, and copy its **structure**, not
its content: mode state, an argument, a `useEffect` that clears the preview whenever the argument
changes, `run(dryRun)`, and `applyServerError` landing a 400's `fieldErrors` on controls.

- [ ] **Step 1: The shape**

- A two-option toggle: **Give them all to one person** / **Spread them across several**.
- One-person mode: a single `Select` over `useProjectMembers(projectId)`, with an explicit
  **Nobody (unassign)** option — null is a value here and the picker must be able to say it.
- Spread mode: a multi-select. **Render the chosen people as an ordered list with the order
  visible**, because the order decides who takes the remainder and a control that hides that is a
  control that lies about what the button will do. A short caption says so.
- **Preview changes** → `run(true)`; **Assign N rows** → `run(false)`, disabled until a preview
  exists and lists at least one change.

- [ ] **Step 2: The preview**

Two readings, both from one result:

- the per-person tally from `tallyByAssignee` (T10), **including anybody who receives nothing**;
- the per-row list, `caseKey → assigneeName`, in the order the server returned (the run's order).

And the two honest counts beside them: `unchanged.length` as *"already assigned to that person"*,
`skipped.length` with each reason. A blanket "Assign 300" button over a preview showing 297 changed,
2 unchanged and 1 skipped is exactly the reporting `BulkResult` exists to force.

- [ ] **Step 3: Commit and report**

On success, close and hand the result to the page, which raises the snackbar `CaseListPage`'s
`onCommitted` raises — per outcome, never a blanket success:

```
47 assigned · 2 already assigned · 1 skipped
```

`variant: 'warning'` when anything was skipped, `'success'` otherwise. Clear the selection.

- [ ] **Step 4: The test arms**

In `AssignExecutionsDialog.test.tsx`, against `createTestQueryClient` — **never a hand-built
`QueryClient`**, only that one has the caches:

- previewing sends `dryRun: true` and writes nothing (assert the request body through the fixture);
- committing sends `dryRun: false` and only after a preview;
- switching mode or changing the people clears a stale preview (the defect: committing a preview
  that described a different act);
- the spread preview shows a chosen person who received **zero** rows;
- a 400 keyed `payload.assigneeIds` lands under the people control, not in the corner;
- a 409 renders in the dialog saying the run has ended.

A test whose render trips an error boundary fails unless it calls `renderingIntoAnErrorBoundary()`
and says why; a failed write needs `drivingAFailedWrite()`. `renderFailures.test.tsx` sweeps for it.

- [ ] **Step 5: Frontend gates**

```bash
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
```

Expected: green, `0 warnings`. **Verify with `npm run build`, not `tsc --noEmit`** — the latter has
reported 0 errors while the build failed with nine.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/runs frontend/src/api frontend/src/test/fixtures.ts
git commit -m "feat: a run's checklist can be handed out from the run detail"
```

---

## T12 — Group the audit trail

**Deliverable:** `AuditLogPage` renders one collapsible summary row per run of rows sharing a
`bulkOperationId`, a row with a null one stays a plain row, and both are covered.
**Depends on:** T11

**Files:**
- Modify: `frontend/src/features/audit/AuditLogPage.tsx`
- Modify: `frontend/src/features/audit/AuditLogPage.test.tsx`
- Modify: `frontend/src/test/fixtures.ts` (two grouped rows)

**Not a contract change.** `bulkOperationId` has been served since §242 (`openapi.yaml:7716`,
`AuditEventView`). Handoff §242 names this as area B's job in as many words: *"the reader must group
them or a 300-case assignment floods the trail."*

- [ ] **Step 1: Group by run, not by scan**

The rows arrive sorted `(occurredAt DESC, id DESC)` and **every row of one act is stamped from one
`Instant`**, so a group's rows are contiguous within the page. Fold consecutive rows sharing a
non-null `bulkOperationId` into one group; a null id is always its own group of one.

```ts
/**
 * Consecutive rows of one bulk act, folded into one group.
 *
 * **A run rather than a scan, and that is load-bearing.** Every writer stamps a transaction's rows
 * from one `Instant` and the sort is `(occurredAt DESC, id DESC)`, so one act's rows are adjacent.
 * Grouping by scanning the whole page would additionally merge two acts that happen to share an id
 * across a boundary — which cannot happen — while costing the property that makes this cheap.
 *
 * **A group that straddles a page boundary renders as two groups, and that is honest.** The page is
 * server-paged and this screen sees 25–100 rows; a 300-row act spans several. Claiming a total the
 * page cannot see would be the false statement about the past this screen exists not to make. The
 * summary therefore counts *what is here*, in those words.
 */
export function groupByBulkOperation(rows: AuditEvent[]): AuditGroup[] { … }
```

- [ ] **Step 2: Render the summary row**

One `TableRow` per group, with a disclosure control when `rows.length > 1`:

- **When** — the group's instant, formatted exactly as a plain row's.
- **Who** — the `Actor` component unchanged; every row of one act has one actor.
- **Act** — `auditActionLabel(action)`, plus the count in words: *"Execution assigned — 47 of one
  operation shown here"*. A distribute writes both `execution.assigned` and (where somebody was
  unassigned) `execution.unassigned` in one act, so where a group's rows do not share an action the
  label says *"47 assignment changes"* rather than picking one and being wrong.
- **On** — the target type, unchanged.
- **Targets** — the summed target count across the group, through the existing `Targets` reading
  ("47 items").

Expanding renders the group's rows beneath it, indented, each in the existing per-row rendering. Use
`data-testid="Audit.Group"` for the summary and keep `Audit.Row` on the individual rows so the twelve
existing arms keep passing unchanged.

- [ ] **Step 3: The pager's total is still rows**

Do not derive a count of *operations* for `TablePagination`. `page.total` is the server's row count
and the server pages rows; a client-side count of groups would make the pager disagree with itself
on every page. Say so in a comment beside the pager — this is the second-most likely "improvement"
somebody will make here after page-spanning groups.

- [ ] **Step 4: The arms**

Extend `AuditLogPage.test.tsx`:

- a page of 3 rows sharing one `bulkOperationId` renders **one** `Audit.Group` and no bare rows
  until it is expanded;
- expanding shows all three, each with its own target;
- a row with `bulkOperationId: null` renders as a plain row and is never folded into a neighbouring
  group;
- two different `bulkOperationId`s adjacent in the page are two groups;
- a group whose rows carry two different actions renders the neutral label rather than one of them.

- [ ] **Step 5: Gates and commit**

```bash
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run test:run --workspaces
```

The `packages` run is not optional even though this area writes no package code:
`client/src/test/citedFiles.test.ts` sweeps **all four corpora**, so a path named in a comment added
anywhere in this area — including in `backend/` and in the contract — reddens the packages job.

```bash
git add frontend/src/features/audit frontend/src/test/fixtures.ts
git commit -m "feat: the audit trail groups a bulk act instead of flooding"
```

---

## T13 — The deferred verification rows

**Deliverable:** rows `§244 BA-1…` and `§244 BA-M1…` appended to
`docs/verification/frontend-browser-checks.md` and `docs/verification/backend-mutation-checks.md`.
**Depends on:** T12

**Browser passes and backend mutation checks are DEFERRED AND BATCHED (§65, standing policy).**
Write the rows. **Run nothing.** Landing without the rows skips the check outright, which is what
batching exists to avoid, and a slice in an unrun queue is *built and unverified* and must be
described that way.

- [ ] **Step 1: Mint the id prefix**

```bash
grep -o '§[0-9]* [A-Z][A-Z]-' docs/verification/*.md | sort -u
```

Pick a two-letter prefix this returns nothing for; `BA` is the intended one — confirm before using
it, and record in the header that you checked. Ids are quoted from handoff sections and are **never
renumbered**.

- [ ] **Step 2: The browser rows**

Into the live pass's lane whose *state* they need — the run/execution lane, after whichever lane
creates a run with a checklist. Each row names the fixture the pass's own charter authors. Cover at
least:

| Row | Drive | Expect |
|---|---|---|
| `§244 BA-1` | Select rows on a run detail and read the bar | The count, an enabled **Assign…** for a Test Lead, and the caption naming *the rows this filter shows*. Switch the filter chip: **the selection clears.** A selection surviving a filter change is a bulk act over rows the person cannot see. |
| `§244 BA-2` | Spread ~12 rows across 5 people, read the preview, commit | The per-person tally is 3/3/2/2/2 with the **first two** in the list holding the extra one, the per-row list names each case's destination, and the snackbar reports per outcome. |
| `§244 BA-3` | Send the same distribute a second time | Every row comes back as **already assigned**, the commit button reports 0 changes, and the audit trail gains **no second group**. This is the row that stands in for an idempotency key. |
| `§244 BA-4` | Include somebody in the picker, then have an administrator offboard them, then commit | A 400 landing **on the people control**, and nothing assigned — not a partial act. |
| `§244 BA-5` | End the run, then select rows | The bar is disabled with the freeze explained. Force the request (devtools) and the dialog shows the 409 in place. |
| `§244 BA-6` | Open the project audit trail after `BA-2` | **One** collapsible row reading the operation and its count, not 12 lines. Expand it: 12 rows, each naming its case and its destination. |
| `§244 BA-7` | Page through a trail where a bulk act straddles a page boundary | Two groups, one per page, each counting only what is on its page — and **no row appears twice or goes missing**, which is §242's `(occurredAt DESC, id DESC)` doing its job under the new rendering. |
| `§244 BA-8` | As a Test Engineer holding no `run:execute` | The **Assign…** control is present and **disabled with a reason** — §31, and the opposite of the audit rail's deliberate exception. |

- [ ] **Step 3: The mutation rows**

At least: mint one `bulkOperationId` **per row** instead of per act (red —
`oneActIsOneBulkOperationId`); round-robin over the request array instead of run order (red —
`theSelectionIsASetAndTheRunDecidesTheOrder`); sort `assigneeIds` before distributing (red — the
remainder arm); treat an unknown execution id as a 404 instead of a skip (red — the skip arm); write
the audit row for an unchanged assignment (red — the retry arm, and this is the one that would make
the trail unreadable while every other assertion stayed green); default `dryRun` to false when
absent (red — `aDryRunChangesNothing`, and mark it ⚠️ as the mutation with the worst consequence in
this area); drop the transaction from `assign` (red — the stranger arm's "nothing was written" half).

Mark honestly anything expected **green** as a gap, with what would close it. `§244 BA-M*`.

- [ ] **Step 4: Commit**

```bash
git add docs/verification
git commit -m "docs: deferred verification rows for bulk execution assignment"
```

---

## T14 — The record

**Deliverable:** `docs/handoff/244-bulk-execution-assignment.md` exists, `docs/HANDOFF.md` has one
new index row, both orientation caches carry the new seams, and everything is pushed.
**Depends on:** T13

**Files:**
- Create: `docs/handoff/244-bulk-execution-assignment.md`
- Modify: `docs/HANDOFF.md` — **one index row, never an appended section**
- Modify: `docs/agent-context/be-java.md`, `docs/agent-context/fe-react.md`
- Modify: `docs/features.md` — the gap's row

- [ ] **Step 1: Write the handoff section**

Follow §242's shape. What it must carry, because a later reader cannot reconstruct any of it:

- the measured gate counts (backend tests, frontend tests, packages) — **measured, not predicted**;
- **the two debts discharged**, with the sentence that they were false claims rather than tidy-ups,
  and that the build was red between T7 and T8 by design;
- **why there is no `Idempotency-Key`** and what replaced it — the correction to the master plan;
- **why one audit row per target** rather than one row with 300 `targetIds`, and that the older
  shape is still right where it applies;
- **the distribute contract**: run order, caller's assignee order, states desired state over the
  whole selection, remainder to the head of the list;
- the audit grouping's **page-boundary limitation**, stated as a known and honest one;
- what is deliberately **not** built: the CLI/MCP arm and the filter argument it wants first.

- [ ] **Step 2: Update both orientation caches, in this area's own commit**

`be-java.md`: the seam list gains `ExecutionBulkAssignService` (what it extracted, why, and the
defect it prevents), `ExecutionAssignmentAudit` (one speller, two writers), the `AuditEvent.record`
overload, and the note that `WRITERLESS_BY_DECISION` lost `AuditEvent.bulkOperationId` — the third
and final move of the pair §242 half-resolved.

`fe-react.md`: the run detail's selection model, `executionSelection.ts` as the display-logic
module, the dialog's preview-then-commit shape, and the audit grouping.

**One writer for these files.** If another lane is closing at the same time, stagger it — the close
is a conflict even between areas whose code is disjoint.

- [ ] **Step 3: Push**

```bash
git add docs/handoff docs/HANDOFF.md docs/agent-context docs/features.md
git commit -m "docs: §244 — bulk execution assignment, and two debts §242 left"
git push
```

**Know what the push buys.** `ci.yml` triggers on push to `master`; the nightly runs on the default
branch. Pushing gets the commits off one machine; it does not observe a branch that is not merged.
The gates above are what make this state pushable.

---

## Self-review

**1. Master-plan coverage.** Task 7 → the design section plus T1. Task 8 → T1 (with the idempotency
correction argued). Task 9 → T2. Task 10 → T5/T6/T7. Task 11 → T4/T5. Task 12 → T8. Task 13 → T12.
Task 14 → T10/T11/T13/T14. Distribute mode, named as in-scope, is T6 and is pinned by four arms.
Both §242 debts have their own tasks and their own acceptance.

**2. Placeholders.** The one place this plan deliberately describes rather than shows is T5's
constructor body and `keyOf`/`inRunOrder`, which must reuse `ExecutionService`'s existing key
resolution — showing a second implementation would be the defect the instruction is warning against.
T10 and T11's TSX is specified by structure, control, testid and copy rather than as a whole
component, because both mirror an existing file named for the implementer to read.

**3. Type consistency.** `ExecutionBulkAssignRequest.previewing()` is used in T5's `apply` and in the
result's `dryRun`. `ExecutionBulkAssignResult.AssignmentChange` is the same record the frontend's
`ExecutionAssignmentChange` aliases. `ExecutionAssignmentAudit.action/changes/bulkRow` is used by
both writers. `people.namesOf` and `people.isActiveMember` are named identically in T5's two
methods; T5 step 3 says what to do if `namesOf`'s collection form does not exist yet.

**4. Order.** Every task's inputs come from above it. T7's gate is *expected red on one rule* and
T8 is the immediate next task, which is the one place the "green at every boundary" rule is
knowingly deferred by one task — stated at both ends rather than hidden, because splitting them
would mean either a commit whose gate was not run or an exemption deleted before its writer exists.

**5. Deliverables.** Every task states a result. The two most checkable by somebody who did not do
the work are T8 (`grep bulkOperationId ArchitectureRulesTest.java` returns only prose) and T12 (one
group row where twelve rows used to be).

---

## Open question for the implementer

**One, and it is a copy decision rather than a behaviour one.** T12's group summary must name the act
in the singular when a group's rows share an action and neutrally when they do not. A distribute that
moves some rows *off* somebody writes both `execution.assigned` and `execution.unassigned` in one
act, so the neutral case is reachable in ordinary use rather than hypothetical. The wording proposed
above — *"47 assignment changes"* — is a first draft and should be settled against
`auditActionLabel`'s existing voice when the map is in front of you. It changes no data and no test
beyond the one arm that asserts it.
