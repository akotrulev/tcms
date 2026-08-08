# Clone case / plan / run — slice plan

**Date:** 2026-08-08 · **Decision:** round 3 (§123) #59, the second half of step 8 · **Plan §6v**

§128 closed step 8's first half (#57, the draft buffer). This is the other half, and it is the last
step in the round that an agent can do alone — steps 1 and 2 (register the shanks runner, rotate the
JWT key) both need a terminal a person is sitting at.

**Scope is clone only.** #59 dropped plan *templates* from M1–M2 outright: a template stores the §6q
selection **query** and needs a table and a new concept, while a clone reuses the version snapshot
and needs no new table. This slice adds **no migration** — `V39` stays the head.

---

## 0. The one design decision, and everything that falls out of it

**A clone is a create, not a second write path.** The clone service reads the source, builds the
*existing* create request record, and calls the *existing* create service. It is the same argument
`CaseCreateService`'s header makes about validation ("a create that validated its own way would be a
second set of answers to *is this title acceptable*"), one level up: a clone that wrote rows itself
would be a third definition of a legal case, free to disagree with both.

Six of §6v's rules then hold **by construction** rather than by being remembered:

| §6v rule | How it holds |
|---|---|
| Display key newly allocated | `DisplayKeys.allocate` is inside the create path; a clone never touches it |
| Review state resets to Draft | `TestCase.create` / `TestPlan.create` decide the status; the request cannot carry one |
| Its own version-1 snapshot | `CaseVersions.initial` / `PlanVersions` are the create path's |
| No shared history, no linkage | There is no column to write one into, and none is added |
| Results / evidence / timestamps never copied | A cloned run creates fresh executions; nothing else is reachable from `RunWriteRequest` |
| Idempotency-Key honoured | The create services already read it; the clone endpoints pass it through |

What is left is what the clone must **deliberately strip or supply**, and that is the whole slice.

### The four strips

1. **`automationKey` is never copied** (§6c: unique per company). Copying it either violates the
   constraint or — if uniqueness were ever relaxed — makes an ingested CI result match two cases.
   §120's automation drift and §117's matcher both key on it, so a duplicate is not a tidy-up.
2. **Step ids are stripped.** The create path passes an **empty** adoptable-step-id set, so leaving
   them on is a 400 rather than a theft — the failure is loud, and stripping them is what makes the
   clone's steps *new rows*, which is what keeps the source's `step_result` history attached to the
   source.
3. **`changeComment` is null**, for `CaseEdit.of`'s recorded reason: it is hashed, so "cloned from
   TC-12" here would make the next ordinary save differ by the comment alone and write an
   empty-diff version.
4. **`overrideApprovalGate` is not inherited by a cloned run — and it *cannot* be.**
   `RunWriteRequest`'s own javadoc already says it "is not a property of the run — it is a statement
   about *this* request", and nothing on `test_run` stores it. So the clone re-evaluates §6g's
   approval gate from scratch: cloning last month's overridden run into this month's build refuses
   unless the cases are approved now or the caller overrides again, on the record. A clone that
   inherited it would be a laundered override, which is §6v rule 2's argument applied to the gate
   instead of to the review state.

### The one thing the caller supplies: the name

**The new title/name is required, not defaulted server-side.** Two rows called *Regression* and
*Copy of Regression* are two rows a person cannot tell apart in a list a week later, and a server
that invents `"Copy of " + title` also has to decide what happens at the length limit. The **SPA**
prefills `Copy of <name>` in the clone dialog, where a human can edit it before it becomes a row —
the same split as everywhere else in this product: a default a person sees and can change is not
the same object as a default the server writes.

---

## 1. What each entity copies

Read against the code, not against §6v's table — §6v was written before §98 and before §126.

### Case — `POST /projects/{projectId}/cases/{caseId}/clone`

Body `CaseCloneInput { title (required), suiteId (optional — defaults to the source's) }`.

Copied: `description`, `objective`, `expectedOutcome`, `stepFormat`, `gherkinSource`, `priority`,
`severity`, `automationStatus`, `notAutomatableReason`, `estimatedDurationSec`, steps (ids
stripped), classification value ids, condition references (pre + post), tags.

Not copied, each with its reason:

- **`automationKey`** — strip 1 above. `automationStatus` **is** copied, so a clone of an AUTOMATED
  case reads as automated with no key, which is exactly the state §6c makes representable
  (`automationKey` is optional precisely so intent can precede the automation) and exactly what
  §120's `undeclared_automation`/`stale_automation` pair is there to surface. The UI says so.
- **Version history, execution history, review state** — §6v rules 2 and 3, held by construction.
- **`ownerId`** — `CaseUpdateRequest` refuses it (no membership check exists yet), so the clone is
  authored by the caller. That is also the right answer: a clone is the cloner's work.
- **Condition opt-outs** — `CaseConditionOptout` **has no writer anywhere in `src/main`**, so no case
  in any deployment has one and there is nothing to copy. Recorded rather than designed around; it
  is another writerless shape, one field narrower than the ones §116/§117 closed.

**Attachments are byte-copied, and §6v's "by reference, not byte copy" is the one rule in that
table that is wrong for the storage model this product built.** `AttachmentStore` is four methods
over an opaque key with no reference count; two `attachment` rows sharing one `storage_key` means
deleting either one deletes the bytes the other names, silently, and `CaseAttachmentService`'s
delete has no way to know. So the choice is a byte copy or nothing, and "by reference" is neither.
The copy reads the source blob and `put`s it under a **fresh** `BlobUpload.key` — new UUID, new
owner — so the clone's files are the clone's. The cost is stated rather than hidden: cloning a case
with a 25 MB attachment moves 25 MB through the request.

### Plan — `POST /projects/{projectId}/plans/{planId}/clone`

Body `PlanCloneInput { name (required) }`.

Copied: `description`, `milestoneName`, `milestoneDate`, and the scope — `suiteIds` + `caseIds`
from `plan_scope_item`.

**Exit criteria are copied, and they are the one thing that does not go through the create path.**
`PlanWriteRequest` has no criteria field on purpose (criteria belong to `plan:edit`, a different
permission and a different slice), so the clone writes them through `PlanCriteria` inside the same
transaction. The gate stays `plan:create` alone, and a test derived from the **seeded catalogue**
asserts that every role holding `plan:create` also holds `plan:edit` — so the shortcut is pinned by
a computed check rather than by a sentence, and a future role that separates them fails the build
instead of silently gaining an edit it was not granted.

Not copied: runs, executions, results (nothing on `PlanWriteRequest` can name them); status (DRAFT
by construction); the key.

### Run — `POST /projects/{projectId}/runs/{runId}/clone`

Body `RunCloneInput { name (required), releaseVersion, buildNumber, configurationId, assigneeId }` —
the four overridable fields, because *"re-run this regression cycle against the new build"* is the
highest-value of the three (§6v placement) and a clone that could not change the build number would
make the caller edit the run immediately afterwards.

Copied when not overridden: `planId`, `configurationId`, `assigneeId`, `releaseVersion`,
`buildNumber`, and the scope — `caseIds`/`suiteIds`/`tags` **as the source stored them**.

Not copied: every result, every step outcome, every piece of evidence, every defect link,
`startedAt`/`closedAt`, `status` (a cloned run is OPEN with everything Untested), `source` (a clone
is `MANUAL` even when the source was `INGESTED` — a person pressed the button), and
`overrideApprovalGate` (strip 4).

**The scope arms are copied as the source *declared* them, not as they *resolved*.** A run scoped by
suite or by tag re-resolves at creation, so a clone picks up the cases added since — which is the
same distinction §6v draws between a template's query and a clone's frozen list, and it lands here
because `test_run`'s scope really is stored as the declaration. Stated because it is the one place
a reader will expect the frozen behaviour and get the live one.

---

## 2. Provenance

§6v: *"a `clonedFrom` provenance field recorded in the audit event"*.

The create path writes its own `case.created` / `plan.created` / `run.created` row. The clone
service writes a **second** row — `case.cloned` / `plan.cloned` / `run.cloned` — carrying
`{"clonedFrom":"<source key>","key":"<new key>"}`. Two rows for one act, deliberately: the created
row is what every existing audit reader already understands, and filtering the action column for
`*.cloned` is how "where did this come from" is answered — the same argument `CaseBulkService`
makes for giving approval its own action name instead of an argument.

The source's **key** (`TC-14`, `TP-3`, `TR-7`) and not only its id, because the key is what an audit
reader recognises a row by, and it is the one thing the id does not tell them.

---

## 3. What is deliberately out of scope, and why

- **Cross-project clone** (§6v rule 4). The endpoint is `/projects/{projectId}/…/{id}/clone` and the
  target suite is resolved in that project, so cross-project is *structurally absent* rather than
  refused. It needs classification revalidation against the target project's vocabularies (§6d) and
  a second `project:read` gate; that is its own slice.
- **Bulk clone** (§6v). It reuses §6f's bulk-selection mechanism — `BulkAction` — and belongs in
  that surface, not in a per-row endpoint.
- **~~Deep vs shallow on the suite tree~~ — SUPERSEDED by §98.** §6v rule 4's *"cloning a folder
  asks whether to include subfolders"* describes a tree that no longer exists: a suite has been
  **flat** since §98, a named collection of three arms rather than a folder with children. There is
  no subfolder question to ask. §6v is annotated in place rather than left to look answered.
- **Plan templates.** Dropped from M1–M2 by #59 itself.

---

## 4. Phases

| Phase | Scope | Gate |
|---|---|---|
| 1 | Backend — clone case, including the attachment byte copy. Contract + `CaseCloneService` + `CaseCloneController` (or the existing `CaseController`) + tests. | `./gradlew build` |
| 2 | Backend — clone plan and clone run, including the exit-criteria copy and the catalogue-derived gate check. | `./gradlew build` |
| 3 | Frontend — the three clone dialogs, `Copy of <name>` prefilled, the "automation key is not copied" note, and the navigation to the new row. | `npm run build` + `lint` + `test:run` |

Each phase commits after its gate. Both verification queues get their rows in the phase that
creates them — nothing is driven (§65).
