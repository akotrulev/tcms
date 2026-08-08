# §129 — Clone case, plan and run: the sentence that held twice out of three, and the two rules that cannot both be true

**Date:** 2026-08-08 · **Decision:** round 3 (§123) **#59**, the second half of step 8 · **Plan §6v**
· **No migration** — `V39` is still the head.

§128 closed step 8's first half (#57, the draft buffer). This is the other half, and the last step in
the round an agent can do alone: steps 1 and 2 — registering the shanks runner and rotating the JWT
key — both need a terminal a person is sitting at.

Scope is **clone only**. #59 dropped plan *templates* from M1–M2 outright: a template stores the §6q
selection **query** and needs a table and a new concept, while a clone reuses the version snapshot
and needs no new table. Which is exactly what happened — two phases, three endpoints, no DDL.

---

## The design, in one sentence, and the aggregate that refuses it

**A clone is a create.** The clone service reads the source, builds the request the create endpoint
*already* takes, and hands it over. It is `CaseCreateService`'s own argument about validation — *"a
create that validated its own way would be a second set of answers to is this title acceptable"* —
one level up: a clone that assembled its own rows would be a **third** definition of a legal case,
beside `CaseEdit` and the bulk path's reconstruction, and the one that diverges is whichever is
edited second. §128 refused a promote endpoint for the same reason one table over.

Six of §6v's clone rules then hold **by construction** rather than by being remembered:

| §6v rule | How it holds |
|---|---|
| Display key newly allocated | `DisplayKeys.allocate` is inside the create path; the clone cannot reach it |
| Review state resets to Draft | `TestCase.create` / `TestPlan.create` decide the status; no request field carries one |
| Its own version-1 snapshot | `CaseVersions.initial` / `PlanVersions.writeInitial` are the create path's |
| No shared history, no linkage | There is no column to write one into, and none was added |
| Results, evidence, timestamps never copied | Nothing reachable from a create request names one |
| `Idempotency-Key` honoured | It *is* the create's handling — a retried clone answers 200 with the case the first attempt made |

**It held for the case and for the run. It does not hold for the plan**, and the reason is worth
carrying: `PlanWriteRequest` has **six** fields where `PlanUpdateRequest` has **twelve**, on the
stated ground that *a plan is named before anybody has subdivided it*. A clone routed through
`POST /plans` therefore drops the tag arm, every §97 section, both scope-prose fields and both
criteria lists — most of what §97 says a plan detail consists of — and answers **201** with
something that reads as a faithful copy of a plan that never had any of it.

The obvious repair, **create-then-edit**, is worse than it looks: version 1 would describe a plan
with no sections and no criteria, and version 2 would land a second later, so every cloned plan is
born with a history saying somebody rewrote it. §6v rule 3 asks for *its own* version 1, not a v1
that never described anything real.

So `PlanCreateService.write` was extracted — the create's whole orchestration, taking a **validated
`PlanEdit`** instead of a request, plus the two serialized criteria columns. One key allocator, one
starting status, one version-1 writer, and the clone supplies an edit the narrow contract could not
have expressed. `TestPlan.create` stopped hard-coding `entryCriteria = null`; the null still comes
from `PlanEdit.validateCreate`, which is where that policy always belonged.

---

## The finding: two documented rules that cannot both hold

**§6v states, independently: "copy the automation status" and "`automationKey` is never copied."
They are jointly unsatisfiable.** `CaseEdit.validate` refuses `AUTOMATED` without a key, so a clone
that copied the status and dropped the key was refused by the product's own validator — **a clone of
an automated case was impossible**. Neither the plan nor this slice's own plan noticed; the endpoint's
first test run did.

Relaxing the validator is the tempting fix and it is wrong: an `AUTOMATED` case with no key is one
§117's matcher can never match, so it reports as automated for ever while no CI result ever lands on
it — a coverage number counting a test nothing runs.

**`AUTOMATED` becomes `TO_BE_AUTOMATED`**, which is not a compromise but the accurate state: the
clone genuinely is intended to be automated and genuinely has no automation yet, which is what §6c
makes the key optional for. Every other status copies unchanged — a blanket reset would tell a reader
that a `NOT_AUTOMATABLE` case, carrying a written reason it cannot be automated, is merely pending.

---

## The second finding: §6v asks for the one thing the storage model cannot do

§6v's table says attachments come across **"by reference, not byte copy."** `AttachmentStore` is a
handful of methods over an opaque key with **no reference count**, and it cannot acquire one without
learning what a row is — which is the property (§125) that keeps "may I reach these bytes" in one
place. So two `attachment` rows sharing one `storage_key` means deleting **either** destroys the
other's bytes: the survivor renders as a file that 404s, with nothing having failed.

The choice was a byte copy or nothing, and §6v's stated preference is the option that is wrong.
`AttachmentStore.copy` is the **fifth** method on that port — on the port rather than
`open()`-then-`put()` in the caller, because `put` takes a `byte[]` and the caller version forces
every byte of every file through heap for ever, foreclosing the one operation an S3 adapter makes
free. **`ReportStore` deliberately does not get it**: the two ports were kept as two types in §125 so
a wrong one would not compile, and the first thing that actually diverged was the method list.

`Attachment.copyForCase` **carries the source's checksum rather than recomputing it**. Recomputing
would hash the bytes *as they are now*, so a source blob that had silently rotted would hand its
clone a fresh attestation that the rot is correct — corruption laundered by exactly one clone.

---

## The third finding: one mapping written twice, and the half that was correct by accident

`PlanUpdateService` materialised a `PlanScope` into rows in five arms (three unsectioned, three per
section, and the empty-section placeholder). `PlanCreateService` materialised **two** — correct only
because `PlanWriteRequest` cannot express the other three. The clone reaches that code with a scope
that can, so the two spellings would have dropped tags and sections silently, with the plan saving
and reading back as though they had never been submitted.

`PlanScopeItem.rowsOf` is the one write-direction mapping now, inverse to `PlanScope.ofItems`. This
is §127's `BlockContent.of` finding, one aggregate over, and it arrived the same way: a second caller
appeared and the two copies stopped agreeing.

---

## Smaller things the code decided, against what a document said

- **A run has no assignee to copy.** §6v's table says a cloned run copies its "assignees"; the column
  is on `test_execution`, one per case, so a checklist can name two hundred different people.
  Choosing one to be "the run's" would invent a fact. Omitted means an unassigned checklist;
  supplying one assigns the whole checklist, exactly as on a create.
- **A run stores no scope at all.** §99's three arms are resolved once, at creation, into executions;
  the declaration is discarded. So a cloned run carries the **resolved** case set — and **cloning a
  suite-scoped run does not pick up the cases added since**. That is §6v's own clone-versus-template
  distinction, for once decided by the schema rather than by the design.
- **§6g's approval gate is re-evaluated on a cloned run, and no line mentions gating** — it happens
  because the clone is a create. A run that was legal last month is refused once its project turns
  gating on. **The override is never inherited**: it is a statement about a request, nothing stores
  it, and a clone that carried it forward would turn one documented 2am exception into a standing
  exemption no screen shows and no audit row explains. Today that is *unexpressible*, and the test
  exists to keep it so.
- **The name is required and the server invents no `Copy of …`** on any of the three. Two rows a
  person cannot tell apart in a list is the failure that default ships, and whoever writes the prefix
  also has to decide what happens at the length limit. The SPA prefills the suggestion in its dialog,
  where a human sees it before it becomes a row.
- **Provenance is a second audit row** — `case.cloned` / `plan.cloned` / `run.cloned` — beside the
  created one, carrying both **display keys**. The created row is what every existing audit reader
  already understands; `*.cloned` makes "where did this come from" a column filter rather than a
  parse. `CaseBulkService`'s argument for giving approval its own action name, one entity over.
- **A plan's criteria are copied as stored JSON and not re-validated.** Round-tripping them through
  `validateCriteria` would make a plan **permanently uncloneable** the day a metric is retired — a
  rule about *requests* refusing a copy of something already stored. `CaseEdit.of` makes the same
  concession for a retired shared step.
- **The plan clone's gate is `plan:create` alone**, though it writes criteria and prose that normally
  cost `plan:edit`. That is only defensible while the same roles hold both, so the test **derives it
  from the seeded catalogue** rather than restating `V3` — a future role separating them fails the
  build instead of quietly acquiring an edit.
- **Condition opt-outs are not copied because none exist.** `CaseConditionOptout` has no writer
  anywhere in `src/main`, so no case in any deployment carries a row. Recorded rather than designed
  around: when it gets a writer, a clone landing in the source's suite should carry the opt-outs
  whose `suite_condition` belongs to that suite, and one landing elsewhere none.
- **§6v rule 4's deep-vs-shallow suite question is superseded by §98** — a suite has been flat since
  the organization-model restatement, so there is no subfolder question to ask. The plan is annotated
  in place rather than left looking answered.
- **Cross-project clone is structurally absent, not refused.** The path is
  `/projects/{projectId}/…/{id}/clone`, so the destination is the source's project by construction.
  Doing it properly needs classification revalidated against the target's vocabularies (§6d) and is
  its own slice.

---

---

## Phase 3 — the three dialogs, and the field that must not be prefilled

~~**Phases 1 and 2 are backend only. The frontend (phase 3) is NOT BUILT.**~~ → **shipped
2026-08-08.** All three endpoints have a consumer in `src/` now: `CaseCloneDialog`,
`PlanCloneDialog` and `RunCloneDialog`, opened from a **Clone** button in each detail screen's
header, each gated on the key the endpoint charges (`case:create` · `plan:create` · `run:create`)
and **disabled rather than hidden** (§31). Every one navigates to the copy on success — the thing
the user now wants to do is edit it, and a toast on the source's screen leaves them hunting for a
key they have never seen.

**`lib/clone.ts` is the whole shared layer**: `copyOf(name, max)`, the `Copy of …` suggestion the
server refuses to invent. It is one function rather than three because the three limits differ (a
case title is 300, a plan and a run 200) and because the arithmetic that goes wrong is the same in
all three — the ellipsis is a character, so a truncation to exactly `max` before appending it lands
one over the limit the field itself mirrors.

**The finding is a field that must NOT be prefilled, and it inverts the dialog's own suggestion
rule.** A run clone's Release and Build are *overrides*: absent means keep the source's, and there
is **no way at all to say "clear it"**. So a prefilled field a user empties would be a control whose
most obvious use is silently discarded — `ui-rules` §3's worst form defect, arrived at by copying
the helpful behaviour from the field two rows above it. Left empty, with the source's value named in
the helper text (*"Leave empty to keep 4.3.0"*), the control means exactly what the wire means. The
name is prefilled and the overrides are not, on the same dialog, and that is the correct answer
rather than an inconsistency.

Three smaller decisions the phase made:

- **The automation note renders only for an `AUTOMATED` source.** The status is silently forced down
  to `TO_BE_AUTOMATED` because the key cannot be copied, so the note is what stops a user reading
  that as a defect — and a note that is always there is one nobody reads on the day it matters.
- **A run's clone is offered on a CLOSED run too**, unlike the lifecycle controls beside it that
  swap by status. Re-running last cycle against the new build is the commonest reason anybody opens
  a completed run at all, and the source's state says nothing about whether a copy is legal.
- **Configuration and assignee are not on the run dialog**, recorded in its header rather than left
  to be found: this SPA has **no configuration picker anywhere** (`RunCreateDialog` sends `null` and
  takes the project default), and a run has no assignee to copy. Omitting both sends the contract's
  own defaults — the source's configuration, an unassigned checklist.

`RunCloneDialog` reuses `RunCreateDialog`'s §6g refusal shape exactly: a 409 is the **only** thing
this endpoint answers with that status, so the client holds no copy of the gating rule, and the
*Clone it anyway* affordance is disabled-not-hidden for a caller without `case:approve`.

## What is built, and what is not

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | phase 1: **2156 tests / 459 classes, 0 failures**; phase 2: **2182 / 467**. Phase 3 touches no Java. |
| `cd frontend && npm run build` + `npm run lint` | clean, 0 errors, 0 warnings (phase 3) |
| `npm run test:run` | **1172/1172 across 90 files** — measured on a quiet run. 22 tests in 4 new files, so the pre-slice figure was **1150** and the 1144 the handoff carried was stale again (**eighth drift**; derived here, not measured). |
| `npm run gen:api` | run in phases 1 and 2; phase 3 changed no contract. |

> **⚠ §127's unreproduced red run REPRODUCED, and this time the names were captured.** Three of five
> full runs went red — 1, 11 and 13 failures — and **every failure was a 30–46 s timeout, never an
> assertion**, in `CaseEditorPage`, `DocPageEditorPage`, `PlanCases`, `RunCreateDialog` and
> `ReviewPanel`. All of them are `userEvent.type` tests typing sentences character by character,
> which is the cause `vitest.config.ts` already names and whose fix (use `paste`) is still not done.
> Every one passes in isolation, and the run that was green took **114 s** against the reds' 204 s
> and 373 s. So the standing claim is now specific: **this suite has a load-dependent timeout cliff
> in a known set of tests, not an unidentified flake.** Raising `testTimeout` a third time would be
> the third fix of a symptom.

**Verification, per §65:** 18 mutation rows `CL-1`…`CL-18` (phase 1), 12 more `CL-19`…`CL-30`
(phase 2), and **10 browser rows in phase 3** (`M2-76`…`M2-80`, `M3-79`…`M3-81`, `M4-59`…`M4-61`),
**none run**. Under the standing policy the whole slice is **built and unverified**.
