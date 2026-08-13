# §189 — the two ids neither machine surface could name

**Two phases, `packages/` only: no migration, no contract change, nothing in `backend/` or
`frontend/src`.** §188's list, taken from the top: *"the CLI and MCP cannot decline a condition
either, and cannot change a suite's conditions"* — the oldest item on it, standing since §187.

## What was missing

Two slices built the server halves and neither built the machine surface for them:

- **§186** gave a suite its `preconditions` and `postconditions` — the writer `suite_condition`
  shipped without in `V1` — and the SPA got a dialog.
- **§187** gave a case the **decline**, `case_condition_optout`'s first writer, and the SPA got a
  toggle.

Both ended with the same shape recorded in `core/rewrite.ts`: *"what a CLI cannot yet do is change
them; that wants a flag, not a projection."* This is the flag. Neither the CLI nor the MCP server
could decline an inherited condition, and neither could change what a suite imposes — so **§6f's
opt-out was reachable from exactly one screen and nowhere else**, which is the shape §172 found for
`useLinkBlock` and §187 itself found for the endpoint it was building against.

## THE FINDING IS THAT THE WRITE WAS THE CHEAP HALF

The flags are twelve lines. What the slice actually cost is the **reads**, and the reason is one
sentence: **a write whose argument cannot be learned from the surface that offers it is a write
nobody can make.**

`--decline` takes a `suite_condition` **attachment** id. `--precondition` takes a `condition`
**library** id. Both are UUIDs. Neither had ever been printed by any command on this surface: the
CLI had no condition read at all, `case show` printed six fields and none of the four condition
arrays, and there was no `suite show`. A person at a terminal could reach either id only by
piping `--json` through `jq` — which is to say, by reading the contract first and the command's
output second. So the slice ships three reads for two writes:

| Command | Serves | Why it exists |
|---|---|---|
| `tcms library list` | `GET …/conditions` + `GET …/shared-steps` | the library ids `--precondition` takes. `tcms_list_library`'s twin, and read-only for the same reason: writing either library costs `library:manage`, which this surface deliberately does not hold, so `library create` could only ever 403 |
| `tcms suite show` | `GET …/suites/{id}` | the **only** place the two ids are printed side by side — `CONDITION` (library) then `ATTACHMENT` |
| `tcms case show`, extended | the four condition arrays it already fetched | the attachment ids `--decline` takes, with declined entries **beside** the live ones |

The MCP surface needed none of them: `tcms_list_library` has served the library ids since §140 and
`tcms_open_case` returns the detail verbatim. **That asymmetry is the surface spec's §1 working as
designed** — the CLI is wide because `--help` is paged and a person reads one command at a time,
the tool surface is narrow because definitions cost context — and it is the first slice where the
cost of the wide side landed as *new commands rather than new flags*.

## The two ids, and why nothing in `packages/` can tell them apart

"Log in" attached at *Billing* and again at *Billing › Refunds* is **two attachments of one library
entry**, and declining the first must leave the second in force. So the decline is keyed on the
attachment (`suite_condition.id`) and the suite's own list is keyed on the entry
(`condition.id`) — one column apart in `suite show`'s table, both UUIDs, and **interchangeable to
every type in this repository**.

Nothing here can refuse a mix-up. The server can: it validates each declined id against the
attachments that currently reach the case, and `CaseConditionInput.conditionId` must name a live
library entry. Both refusals are field errors, which is the right place for them — a client-side
check would be a second copy of a rule the server owns (`requireStatus`'s argument, one noun over).
What this slice can do is **print them under names that say which is which**, and say so in both
tool descriptions. `§189 CN-1` and `CN-3` are the mutation rows that pin it, and `CN-3` is recorded
as a **hole**: the stub accepts either, so wiring a decline into `preconditions` would *attach* what
the caller meant to refuse and every test in `packages/` would stay green.

## The exclusion that was predicted to fall, and did not

`core.test.ts` has said since §187 that `declinedConditionIds` is *"excluded on the weaker half of
the conditions' argument and the exclusion is therefore the one to revisit first"* — because unlike
`preconditions`, its shape **does** line up with what the read serves, so "it does not type" was
unavailable and the whole argument was "absent leaves it alone". §188 quoted that sentence when it
named this slice. The revisit happened and **the exclusion stands**, in both functions:

> A **projection** says what an unchanged save sends, and for a list that is nothing. A **flag** is
> a delta and says what the caller asked for.

They were only ever the same question if `caseInputFrom` is read as *"the resource"* — which is
what `suiteInputFrom` calls a base body, and what `ownerId`/`automationTags` (served by the `GET`,
refused by the `PATCH`) prove it is not. Echoing the declines back would state, on every
`--title` edit, the complete set of an author's opt-outs as though the caller had named it. Harmless
today — `CaseConditions.reconcileOptouts` bounds its deletions by `declinable`, so a decline the
client was never shown survives either way — and a claim nobody made. `§189 CN-5` is the row that
reverses it.

The recording matters as much as the decision: a predicted reversal that does not happen is exactly
the kind of thing that gets quietly re-predicted three slices later.

## Two fixtures that described a wire the server left

Both found by writing assertions that needed them, not by looking:

- **`mcp/src/writes.test.ts`'s `SUITE_ROW` said `conditions: []`** — the single undifferentiated
  array §186 split into two and removed from the wire **three sections ago** — in the file whose
  own header warns that a stub answers 200 to whatever arrives and whose subject is round-tripping
  a resource faithfully.
- **Both case-detail fixtures omitted `declinedPreconditions` / `declinedPostconditions`**, which
  the contract requires of a `TestCase`. `main.test.ts`'s case-show stub carried six fields.

Neither could fail before this slice, because nothing read them. That is the general form: **a
fixture is only as honest as the widest reader of it**, and the reader arrives slices later.

## What shipped

**Phase 1 — `packages/client` + `packages/cli`** (`88b13ef`):

- `case edit --decline ID…`, `suite create|edit --precondition ID… --postcondition ID…`. Complete
  sets on `--tag`'s rule: absent leaves the list alone, `--flag ''` empties it.
- `library list [--q] [--type]` and `suite show TS-7`; the four condition arrays on `case show`.
- `tagsFrom` → **`completeSet`**: four callers now, two of which are not tags.
- `rewrite.ts` and `core.test.ts` carry the decision above at both functions.

**Phase 2 — `packages/mcp`** (`91e50c5`):

- `tcms_write_case` gains `declinedConditions`; `tcms_write_suite` gains `preconditions` /
  `postconditions` on **both** arms — the create too, because a suite's conditions reach the cases
  filed in it and attaching them in a second call is a window in which those cases run without a
  setup step their author intended.
- The suite arm takes **bare ids** and builds the `CaseConditionInput` wrapper itself: the object
  has exactly one field a caller may fill (`inlineBody` exists to be refused, position is the
  array's order), so asking a model for the shape buys a shape to get wrong.
- `[]` is the clearing form here rather than `''` — JSON can type an empty array and a shell cannot.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), which is the Linux shape `tests.yml` uses:

| | |
|---|---|
| `packages` build | ✅ in order — client → cli → mcp |
| `npm run lint --workspaces` | ✅ 0 warnings, 0 errors ×3 |
| `npm run test:run --workspaces` | ✅ **241** (229 → 239 in phase 1, → 241 in phase 2) |

`backend/` and `frontend/` were not rebuilt: neither was touched, and no generated `schema.d.ts`
moved because the contract did not.

## Verification

**Seven mutation rows, `§189 CN-1`…`CN-7`, none run** (§65) — `docs/verification/backend-mutation-checks.md`.
Two are recorded holes (`CN-3`, `CN-6`) and one is the reversal of this slice's own decision
(`CN-5`). **No browser row**: nothing in `frontend/src` changed.

**One live-pass item, and it is the important one.** `CN-3` cannot be closed by a stub. What settles
it is a real `tcms_write_case action: edit` carrying `declinedConditions`, and a real
`tcms suite edit --precondition`, against an instance where a case actually inherits a suite
condition — the same fixture the browser queue's `§186 A1-2` authors. It belongs to the next
`packages/` live pass. **Built and unverified**, in the §65 sense: these two arms have existed for
one commit and nothing has ever sent one at a server.

## The next slice

§188's list, minus what this took:

- **resume the browser queue at `A2 — plans`** — unchanged since §186, and the queue now carries
  seven rows in `M2` needing the suite-condition fixture `§186 A1-2` authors. It is also where this
  slice's live-pass item would find its state, which makes it two claims on one fixture.
- **a third `packages/` live pass**, which §143/§145's precedent says finds things no stub can:
  this slice added two write arms and three read commands and every one of them is stub-observed.
- §186's, untaken: **`V1`'s comment on `suite_condition` still describes the tree §98 deleted** — a
  new migration carrying nothing but `COMMENT ON`.
- §187's, untaken: **the inert decline is state no screen can show and no test can reach.**
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175); **a run has no edit endpoint at all** (§177);
  §184's *what the SPA computes from a contract enum the server also computes*; §185's *a rule that
  fails when a test reads a path outside `src/` that `build.gradle.kts` does not declare as an
  input*; §188's `= null` writer-rule gap and its `releaseSortKey` observation (one slice, three
  recorded absences).

**And one this slice opened.** **`case propose` and `tcms_write_case propose` still cannot carry a
decline** — `TestCaseInput` takes `declinedConditionIds` and `requireProposableCase` says nothing
about it, so a generated case may name conditions it declines that the accept will validate against
a suite the proposal does not yet sit in. Not a defect today (a proposal is stored unvalidated and
checked at accept, which is where the declinable set finally exists); it is the question of whether
a decline is a property of a *proposed* case at all, and it wants deciding rather than defaulting.
