# §200 — The case that could not be created from a plan row, and the rule that had to move twice

**One phase, across all three stacks: `backend/` (five files + five test files), `openapi.yaml` (two
operation descriptions), both `schema.d.ts` copies, one file in `packages/` and two in `frontend/`.
No migration, no new endpoint, no new statement and no new query.**

One phase for §196's, §197's, §198's and §199's reason, restated because it applies unchanged: the
rule and the repairs it demands are the same change, and a commit holding only one half is a commit
whose gates say nothing about the other. Here that is sharper than usual — the rule moves on the
*create* path and what would have broken is on the *bulk* path.

§199's largest opened item, and the arm the user chose from its three:

> **A case authored from a plan row cannot be created, and the fix is a product decision.**
> 1. **The server accepts a stepless `STRUCTURED` case on create.** The product already contemplates
>    one — `CaseCompleteness` raises `INCOMPLETE` for *"an approved case with no steps"*, which is a
>    state the create rule says cannot exist. Cheapest, and it weakens a rule the case editor relies
>    on the server for.

Arm 1, and the parenthesis about weakening the rule turned out to be the design rather than the cost.

## The rule is one rule, and it applies to one act

`CaseEdit.validate` refused two things: a `STRUCTURED` case with no steps, and a `GHERKIN` case with
no scenario. Those are **one rule with two spellings** — `StepFormat` makes steps and a scenario
alternatives, so a case states its procedure in exactly one of them — and `CaseCompleteness` has read
them as one question since §182. They are therefore deferred together and enforced together;
half-deferring would accept or refuse the same act depending on a format the author may not have
chosen deliberately (`§200 PR-4`).

What changed is not *whether* the rule holds but **which act it is about**:

| | |
|---|---|
| **`PATCH` — `REQUIRED`** | A body here is the whole editable set (§144), sent by an author with the step field on screen. An empty procedure is an answer, not an open question. It is also the funnel: a stub reaches its steps by being opened and saved, and this rule is what makes saving it mean writing it. |
| **`POST` — `DEFERRED`** | The plan's Test cases tab authors a case from a title (§97 D2) and renders no step field. The alternatives were refusing the save — what it did from §97 to §199 — or inventing a step, which is authored content with nobody's name on it. |
| **`CaseEdit.of` — `DEFERRED`** | Reconstruction of a stored row, and **not** a second decision. See below; this is the half the slice would have shipped broken. |

The seam is `CaseEdit.ProcedureRule`, a fourteenth parameter on `validate`. It joins three arguments
of a shape that already existed there — the vocabulary, the shared-step library and the declinable
set are all passed empty by `of` because *a reconstruction is not an authoring act*. It differs from
those three in one way that matters: a boolean rule has no empty spelling, so it had to be named.

## The half that would have shipped broken

`CaseEdit.of` re-validates a stored row before an authored bulk action may write its version. Its own
`@return` javadoc named the two states it refuses, and **both were about to become wrong**:

> empty when the stored row does not validate — a case with no steps in structured format, or one
> whose suite has since been deleted

The first is precisely what this slice makes legal. So with `of` left at `REQUIRED`, every case a
plan authored would come back from a bulk **Move to suite** as *skipped*, under `NEEDS_A_SAVE`:

> Open this case and save it once — it has content that must be completed first.

which the update path refuses **for the same missing steps**. A dead end reachable by an ordinary
click, produced by two rules that are each correct on their own, and reachable only after a change
that touches neither of them. It was **measured red rather than reasoned about** (`§200 PR-3`,
`aStubIsMovableLikeAnyOtherCase`): the guard was written, the arm flipped back to `REQUIRED`, the
test reddened with the stub under *skipped*, and the arm restored.

The second state turns out to be unreachable too, and always was: `SuiteWriteService.delete` refuses
a suite with live cases filed in it. So the whole `reconstructable` branch is now defensive rather
than reachable through any endpoint — §181's shape, kept for §181's reason. **And no test has ever
produced its message**, which is the other half of the observation and why it is recorded at the
constant rather than deleted: a message with no producer is removable, a message with no *test* is
one nobody would notice going wrong.

## The health signal gets a producer, and the gate is what makes this cheap

§182 wrote `case_health.incomplete` with two terms it could not reach, on §181's precedent, and its
list of things that would make them reachable ended: *"or a relaxation of that rule in a slice
thinking about something else"*. This is that slice, except it was not thinking about something else
— and **the predicate needed no change at all**, which is the whole return on having written it
early.

The term fires only on an **APPROVED** case. That is what makes deferring the rule cheap rather than
merely permissive: a plan-authored stub is a `DRAFT`, and a draft missing its procedure is ordinary
work in progress — §6o's own position, and the reason the gate was there before there was anything to
gate. What the signal now catches is the case somebody **signed off** without finishing, which is the
state it was written for. Drop the gate (`§200 PR-5`) and every plan a team writes fills the
dashboard with faults.

## Two clients are now stricter than the server, and both on purpose

Neither is a defect and both had to say so, because each justified itself by *matching the server*:

- **`caseSchema.ts`** — the case editor's Zod rules. The editor renders a step field, so an author
  here has been asked the question. Relaxing it to match would make the one surface built for writing
  a case the surface that saves an empty one.
- **`packages/client/core/proposal.ts`** — `requireProposableCase`, whose whole argument is *"the
  same rules run when a person accepts it"*, now false for two of its six checks. Kept, because the
  deferral was granted to a different act: a person typing a title into a plan they are writing has
  said what the case is *for*; a model filing a proposal was asked for the case itself, and one with
  no procedure is an empty proposal whose acceptance gains the reviewer nothing.

## The check §144 never ran against the operation this slice changed

`TestCaseInput` is **one schema serving two operations**, and until now both enforced the same rules
— so `CaseUpdateEndpointTest.contractRequiredMatchesWhatTheServerEnforces`, which drops each field in
turn from an accepted `PATCH` and asserts `required` names exactly what the server then refuses, was
incidentally checking both. This slice ends that.

`required` is unchanged and still correct: neither deferred field was ever in it. But *"still
correct"* was a claim about the create path that nothing in the suite made, and a contract that
describes one verb while a caller uses the other is exactly §144's shape. So the same experiment now
runs on `POST` as well — `contractRequiredMatchesWhatCreateEnforces`. It is what would notice a later
slice making the create path stricter or looser again without saying so on the contract.

Both operation descriptions state the asymmetry, including the consequence a read-modify-write client
meets: **a stub read back and `PATCH`ed unchanged is refused until its procedure is filled in.**

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses. **All three stacks**, and
that is a decision rather than habit — the contract moved, so both generated `schema.d.ts` copies
moved with it.

| | |
|---|---|
| `backend`: `./gradlew build` | ✅ full, `-Werror` + every test, 7m21s |
| `packages`: `npm run build` | ✅ |
| `packages`: `npm run lint --workspaces` | ✅ 0 warnings, 0 errors ×3 |
| `packages`: `npm run test:run --workspaces` | ✅ **241** — 75 client / 85 cli / 81 mcp |
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings, 0 errors |
| `frontend`: `npm run test:run` | ✅ **1330 across 99 files** (unchanged — no frontend test moved) |

The one measured red: `§200 PR-3`, above.

## Verification

**Three browser rows and five mutation rows.**

`§199 PC-2` is **superseded rather than re-numbered** — ids are quoted from handoff sections, so the
row that expected a 400 now expects the 201, struck through with what it used to say. Three new rows
in lane `A2 — plans`: `§200 PS-1` drives the asymmetry from the editor, `PS-2` drives a stub to
`INCOMPLETE` by approving it unfinished, `PS-3` drives the bulk move that `PR-3` measured.

`§200 PR-1`…`PR-5` are the mutation rows, one per path the parameter reaches plus the health gate.
`PR-3` is recorded ✅ RUN; the rest are ⬜ NOT RUN, per §65.

## The next slice

§199's list, minus what this took, plus what this opened.

**Opened here:**

- **`NEEDS_A_SAVE` has no producer and never had a test.** Both states `CaseEdit.of` documented are
  now unreachable through any endpoint. Deleting the branch is one decision; giving it a test that
  forges the state in SQL is another; leaving a user-facing sentence nothing can produce and nothing
  observes is the third and is what ships today.
- **A stub is `INCOMPLETE` only once approved, and nothing tells its author before then.** The plan
  that authored it shows a case; the case list shows a `DRAFT`. Whether a plan should surface *"three
  of these have no steps yet"* is a product question this slice deliberately did not answer.

**Carried forward:** resume the browser queue at `A2 — plans`; a third `packages/` live pass; the
snapshot has no reader (§193); a clone cannot file the copy nowhere (§195); **`packages/` still mocks
its client with no comparison at all** (§196–§199); nothing pairs the two tsconfig lists (§196); **a
panel can render its error branch and no gate says so** (§198, still the largest instrument item);
the undeclared-property question is off under `additionalProperties` (§198); the read-only archive
decision §159 did not take; serving the relabel audit (§174); `CurrentUser.teams` has no reader
(§175); a run has no edit endpoint (§177); §184's, §185's, §188's, §189's and §190's items.
