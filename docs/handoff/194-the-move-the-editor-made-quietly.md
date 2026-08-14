# §194 — the move the editor made quietly, and the control the save was already discarding

**One phase, `frontend/` only. No backend, no contract, no migration, nothing in `packages/` — and
no behaviour of the write itself changed.** `PATCH /cases/{id}` ends declines on a move exactly as
it did the day §192 shipped; what changed is that the screen driving it now says so, twice, and
stops offering a control the same request throws away.

§193's own closing item, taken as a decision: *"the single-case move says nothing either, and it is
not obvious that it should."*

## §193 offered three shapes and the answer was none of them

| Shape | §193's words | Verdict |
|---|---|---|
| The `PATCH` response grows the field the bulk result has | *"a client that re-reads the response can see the decline is gone"* | **Refused** — it already can, and the field would be a second spelling |
| The editor's suite picker warns before the save | *"could be wrong for a good reason… a dialog people learn to click through"* | **Taken, and not as a dialog** |
| Nothing — *"a single case is a screen the author is looking at"* | | **Refused, and the reason is the finding below** |

The response shape is refused on its own terms. `TestCaseDetailView` already answers the question:
`declinedPreconditions` and `declinedPostconditions` come back empty, and a `declinesEnded` beside
them would be the same fact under a second name — §184's shape. What made the field right on the
bulk result was never that a caller could not compute it; it was **the preview**, which §193 called
*"the one moment this can be told to the person while they can still decline"*. A field on a `PATCH`
response is told after the commit, about a decision the same section describes as unrecoverable.

And the third shape — that a single case is a screen its author is looking at, so nothing is owed —
was the one worth checking, because it is the cheap answer and it reads as reasonable. It is false,
and the reason it is false is not about disclosure at all.

## The finding: the panel is the folder the case is filed in NOW, and the picker does not move it

`inheritedPre` and `inheritedPost` are built once from the loaded case:

```tsx
const inheritedPre = [
  ...(existing?.preconditions ?? []).filter((ref) => ref.inherited),
  ...(existing?.declinedPreconditions ?? []),
];
```

`existing` is the case as the server resolved it, in the folder it is filed in. Move the **Suite**
picker in the Placement panel and nothing below it re-renders: the Conditions panel keeps showing
*From Refunds* rows, **with live switches on them**.

Those switches are inert, and not in a way the author can see. `CaseEdit.normalizeDeclines` bounds
the submitted set by what the **destination** imposes —

```java
return effective.stream().filter(retained::contains).sorted().toList();
```

— and since §98 a suite is flat, so `CaseConditions.ancestry` resolves to one folder and two folders
share no `suite_condition` id at all. The intersection is therefore **empty for every move**. A
decline switched on while the picker is moved is accepted by validation (it is checked against the
folder the client was looking at, which §192 made deliberate) and then dropped by the same request.

**That is not a missing disclosure, it is a control whose effect the save silently discards** — the
§152/§153 family, arrived at from the UI end. It is also why the third shape fails: the argument for
saying nothing is that the author knows what they are doing, and the screen was letting them do
something that did not happen.

## The preview is inline, not a dialog, and it is the panel that owns the rows

§193 named the hazard in the arm it did not take: a confirm on a move the author is making
deliberately, one case at a time, *"is the definition of a dialog people learn to click through"*.
So the warning is a **`warning` `Alert` inside the Conditions panel**, not an interstitial — it
appears when the picker moves, names each rule and its folder, withdraws when the picker goes back,
and is never in the way of the save.

It is rendered **once, above both panels**, inside the `declinedConditionIds` `Controller` that
already exists for the same reason: one array carries both kinds, so a per-panel alert would state
one event twice on a case declining a precondition and a postcondition, and each copy would name
half of it.

`warning` and not `info`, on §193's grounds transferred: a skip is something the operation will not
do, and this is something it will do that nobody asked for.

**The toggles grey out, and only the toggles.** A move invalidates the *inherited* group; the case's
own attachments are untouched, so they stay fully editable. Disabling the whole panel would be a
second claim, and a wrong one.

## What this panel deliberately does NOT do is show the destination's rules

It is the obvious next thought and the wire supports it: `Suite.preconditions` / `.postconditions`
carry the same `suite_condition` ids in the same `CaseConditionRef` shape (§186), on the suite list
this screen **already holds** for the picker. No new request, no new endpoint.

Refused, by this component's own header, which has said since §187 that re-deriving the inherited
order here *"would be the second implementation `CaseDetailPage` refuses to write for the same
list"*. Assembling "what this case will inherit at the destination" means deduping against the
case's own attachments, applying §6f's resolution order and dropping retired entries — the case
endpoint's resolution, rewritten in the client. And it would be built from the wrong bound: the
suite list omits attachments whose library entry was retired, which is the **declinable** bound and
explicitly not the **retention** one (`CaseConditions.Reaching`, §192). Two bounds that differ by
exactly the retired entries, and the client would have picked the one that is not the rule.

## The two halves are different instruments, and one test exists to say so

| | Reads | Can be wrong when |
|---|---|---|
| The alert, before the save | §98's flatness — *a move ends every decline* | inheritance widens |
| The snackbar, after it | the saved case's two declined lists | never; it is the writer's answer |

The prediction is bounded by something written down elsewhere: `CaseConditions.ancestry` says a
widening of inheritance *"is a decision with a slice behind it, not a side effect of flattening"*,
and this is one of the places such a slice has to visit. The snackbar deliberately does **not**
share the assumption — it subtracts what the response still carries from what the form submitted —
so under a widening the record of what happened stays true while the warning becomes the thing to
fix. That is the right way round for the two to fail, and it is asserted rather than asserted-about:
*"reports NOTHING when the response kept the decline"* hands the mock a server that kept the row and
watches the alert warn while the snackbar reports nothing.

The subtraction is **gated on the folder having changed**, which is the one place the measured half
takes the predicted half's input. The gate decides whether the question is asked; the response still
answers it. Without it, a response served by anything less than the whole contract shape reads as
*"the server dropped every decline"* on an ordinary title fix.

**The hole, stated rather than left to be found:** both sides of that subtraction are what a *list*
shows, and a decline whose library entry was retired is on no list (§187, §192 `M2-22i`). Such a row
ends with the move like any other and is counted by neither term. It is §193's `LD-4` arriving on
the other path, and closing it needs the server to say what it ended — which is exactly the field
the bulk result now has and this endpoint does not.

## `plural` moved on its second consumer

It was private in `lib/flake.ts`, where it was written and where the sibilant rule that stops it
printing *"4 switchs"* was learned. A second consumer is the moment `lib/format.ts`'s own header
applies — one canonical format per data type, defined once — so it moved rather than being copied,
and `flake.ts` imports it. `CLAUDE.md`'s rule for `labels.ts` and `format.ts`, and `muiTestIds.ts`
is the precedent: that file exists because there were already two copies of it.

## What shipped

- **`ConditionsPanel`** — `leavingFolder`, greying the decline switch alone and re-wording its
  tooltip to name the folder being left; the prop's comment carries why it is a defect and not a
  nicety, and the header carries the destination-rules refusal.
- **`CaseEditorPage`** — `movingFolder`; the naming alert above both panels; the composed snackbar
  arm, measured off the response.
- **`lib/format.ts` / `lib/flake.ts`** — `plural`, moved and imported.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings |
| `frontend`: `npm run test:run` | **1294 across 97 files, 0 failures** (+8, no new file) |

`backend/` and `packages/` were **not** run and did not need to be: no file in either changed, the
contract did not move, and neither `schema.d.ts` copy was regenerated.

## Verification

**Three browser rows, `§194 M2-22l`…`M2-22n`**, all **NOT RUN** (§65), in lane `M2` beside §187's,
§192's and §193's decline rows, where the fixture already exists. `M2-22m` is the one that matters:
it drives the switch the save was discarding.

**No backend mutation rows, and that is not an omission** — no Java changed. The rules this slice
depends on are already pinned there by `§192 DF-3` and `DF-6` (the retention bound on this exact
endpoint) and by `§193 LD-1`…`LD-4`.

**Built and unverified.**

## The next slice

§193's list, minus what this took:

- **resume the browser queue at `A2 — plans`** — unchanged since §186, and now carrying fourteen
  `M2` rows including this slice's three. `§192 M2-22f`, `§193 M2-22j` and `§194 M2-22m` are the
  three the live pass would have caught the last three slices' subjects with.
- **a third `packages/` live pass** — §189's two write arms and three read commands are all
  stub-observed, and `§189 CN-3` cannot be closed by a stub.
- **the snapshot has no reader** — §193's larger item, untouched. `EntityVersionView` has recorded
  it since §28 as *"an endpoint that does not exist yet"*, and it is now also the reason a case's
  own history cannot answer what both write paths now answer at the moment of the write.
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175); **a run has no edit endpoint at all** (§177);
  §184's *what the SPA computes from a contract enum the server also computes*; §185's *a rule that
  fails when a test reads a path outside `src/` that `build.gradle.kts` does not declare as an
  input*; §188's `= null` writer-rule gap and its `releaseSortKey` observation; §189's *a proposal
  cannot carry a decline*; §190's *whether "a comment whose argument expired" has any computable
  form at all*.

**And one this slice opened.**

**How many other panels are a snapshot of a field the form can change?** `inheritedPre` was stale
for a year because it is derived from the loaded entity while the control that decides it is bound
to the form — and nothing in the build can see that, because both halves typecheck and both are
individually correct. The editor alone has several derived-from-`existing` values sitting beside
fields that edit their inputs (the classification vocabulary against `suiteId`, the automation
panel against `automationStatus`, the shared-step corpus against `projectId`), and the question is
whether any of the others are also rendering a claim the picker above them has already falsified.
It is a computable question in the same sense §156's was — *which values are read from `existing`
while a form field feeds the same derivation* — and unlike this slice's instance, most of them are
probably fine. Which is exactly what the five checks in §156 said before they were written.
