# §195 — the exceptions the copy never gets, and the caption that promised them

**One phase, `frontend/` only. No backend, no contract, no migration, nothing in `packages/`.**
`POST /cases/{caseId}/clone` behaves exactly as it has since §129; what changed is that the dialog
driving it stops making a promise the control two fields above it had already broken.

§194's own closing item, taken as the audit it asked for: *"How many other panels are a snapshot of
a field the form can change?"*

## The audit, and what it covers

The question is computable in §156's sense — *which values are read from a loaded entity while a
form field feeds the same derivation* — and the population is the `useForm` lane. Seventeen files in
`frontend/src` call `useForm`; **seven of them initialise from a loaded entity** and are therefore
the only ones where the shape can occur at all:

| Form | Entity | Verdict |
|---|---|---|
| `CaseEditorPage` | `existing` | §194's `inheritedPre` / `inheritedPost`, **fixed there**. The three other suspects §194 named on its way out are all clean — see below |
| `CaseCloneDialog` | `testCase` | **THE FIND** |
| `SuiteDialogs` → `RenameSuiteDialog` | `suite` | Clean — `Rename ${suite.name}` is the only reading, and it identifies |
| `TeamDialog` | `team` | Clean, and the nearest miss — see below |
| `DocPageEditorPage` | `page` | Clean — `page.attachments` is beside a form that edits title and blocks, and is an input to neither |
| `PlanCloneDialog` · `RunCloneDialog` | `plan` · `run` | Clean by construction: a clone form edits the **copy**, and every snapshot on it is of the **source**, which the form cannot reach |

The other ten are creates or the two auth screens: they have no loaded entity, so there is nothing
for a picker to falsify.

**The line between the two verdicts is worth having, because most of the population sits on the safe
side of it and does so for a reason rather than by luck.** A snapshot that **identifies** the entity
is fine, however stale it goes — `Edit ${team.name}` above a Name field, `Rename ${suite.name}`,
`Edit ${existing.key}`, and `ConditionEditDialog`'s *"Saving changes “{condition.title}” everywhere
it is used"* all keep naming the row as the server last stored it, which is what the reader needs to
know **which** thing they are editing. A snapshot that **predicts what the save will do** is a defect
the moment a control on the same form is an input to that prediction. §194's inherited rows were the
second kind. So is the caption below.

**The three suspects §194 named are all clean, and the mechanism is the same in each**: the
classification vocabulary is `useClassification(projectId)` and the shared-step corpus is
`useStepSuggestions(projectId, fragment)` — both keyed on a **route param**, which no control on the
form can move — and the automation panel branches on `watch('automationStatus')`, which is the form
value and not the loaded one. §194 guessed they were probably fine and they are; the value of running
it was the eighth form, which §194's list did not name.

`TeamDialog` is the nearest miss and is included because it reads as the shape until you check it.
`subtreeOf(team.id, teams)` computes the parents a move must not offer, from **stored** parentage,
while the form edits `parentTeamId` — but editing a team's own parent does not change which teams sit
beneath it, so the one control that could touch the derivation is not an input to it. Correct, and
not obviously so.

## The find: the caption says the conditions come across, and the Folder picker decides half of it

`CaseCloneDialog` closes with

> The steps, classification, conditions, tags and attached files come across.

and carries a **Folder** picker three fields above it. `CaseCloneService` copies the source's
condition declines under a guard:

```java
List<UUID> clonedDeclines =
        java.util.Objects.equals(targetSuiteId, source.getSuiteId())
                ? caseConditions.declinedAtSuite(source.getId(), source.getSuiteId())
                : List.of();
```

So a copy filed anywhere but the source's own folder **carries none of them**, and the dialog said
nothing about it. This is §194's event at the other write path — a case that stops declining a rule
it used to decline — and until now exactly one of the two screens that can cause it mentioned it.

Nothing here is a server defect and nothing is a contract defect. `TestCase.declinedPreconditions`
has described this behaviour in as many words since §192 (*"`POST /cases/{caseId}/clone` has answered
the same question the same way since §129 — a clone landing outside the source's folder carries
none"*), `CaseCloneService`'s header prescribed it before it was built, and `§187 M2-22g` is a
browser row that drives it. **Four artifacts stated the rule and the one surface a person uses did
not** — which is the §165 shape (prose that is right while the screen is wrong) rather than the §152
one.

## The prediction is EXACT here, where the editor's is a bound

§194 pairs its alert with a snackbar that **measures**, and gives a reason worth re-reading: the
editor's alert concludes *"a move ends every decline"* from §98's flatness, so a widening of
inheritance would leave the warning over-stating while the measured snackbar stayed true.

That argument does not transfer, and the difference is the whole reason this slice ships one alert
and no counterpart:

- The editor's prediction is **derived** — from a schema property that a future slice may change.
- This one is **quoted** — from a single `Objects.equals` the server applies unconditionally. There
  is no inheritance rule for a migration to widen; either the target suite is the source's or it is
  not, and the client is comparing the same two ids the server compares.

The second half is that **a clone destroys nothing.** A move ends a decision; a clone simply starts
the copy without one, and the source keeps everything it had. There is no event afterwards that needs
a record, so the dialog is only ever the **last moment somebody can decide not to** — which is §193's
argument for the bulk preview, arriving at one alert instead of two disclosures.

It does inherit the editor's one bound, and it is written at the field: `declinedPreconditions` omits
a decline whose library entry has been **retired** (§192), so the list is what a *list* shows and can
under-count. Same bound, same reason, stated in both places rather than in neither.

## Named, not counted — and the count is not the disclosure

The alert lists the rules, from `title` and `inheritedFrom`, exactly as §194's does. *"The copy will
not carry 2 condition exceptions"* on its own leaves the reader unable to find out **which** two,
and §193 ends on precisely that open question about the bulk snackbar's bare count. Where a screen
can name them it names them.

It also says who keeps them — *"TC-7 keeps its exceptions"* — because a warning about exceptions
being lost, on a screen whose other warning is about an automation key being dropped, reads as damage
to the thing you are looking at. The source is untouched and the sentence has to say so.

## The caption lost one word and gained a clause

> The steps, classification, tags, attached files and the conditions the case attaches itself come
> across; the standing rules it inherits are its new folder's.

The unqualified "conditions" was the falsified claim. A case's **own** attachments are copied
whatever folder the copy lands in — `conditions.findForCase(source.getId())`, no guard — and the
rules it **inherits** are always the destination folder's, in every clone, including the ordinary
one that does not move. That second half is true with or without the alert, so it belongs in the
caption rather than in a warning that only fires on a move.

## What shipped

- **`CaseCloneDialog`** — `landingElsewhere` and `notCarried`; the `warning` alert under the Folder
  field, naming each rule and its folder; the corrected caption. The header carries the audit's
  dividing line, the exact-versus-bound argument, and the refusal of a measured counterpart.
- **`CaseCloneDialog.test.tsx`** — four tests in their own block, and the fixture gained the two
  decline lists (see below).

## The fixture that promised a shape it did not have

`sourceCase()` builds its `TestCase` through `as TestCase`, and the cast makes a **required** field
optional to `tsc`. `declinedPreconditions` and `declinedPostconditions` were simply absent; the day
something spread them the existing *"sends the chosen folder when one is picked"* test threw
`undefined is not iterable`. That is §38's family arriving at the fixture end — the type system was
satisfied on both sides of a value that did not exist — and it is the second time in two slices that
a `required` array on a schema turned into a test-only failure (§167 hit the same thing from the
opposite direction, where `required` *was* a `tsc` error because the fixture was not cast).

Nothing was added to the component to absorb it. The contract declares both fields required, so a
`?? []` would be the client compensating for a shape the server always sends — §148's finding, where
three consumers quietly worked around a contract that was itself wrong. The fixture is what was
wrong.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings |
| `frontend`: `npm run test:run` | **1298 across 97 files, 0 failures** (+4, no new file) |

`backend/` and `packages/` were **not** run and did not need to be: no file in either changed, the
contract did not move, and neither `schema.d.ts` copy was regenerated.

## Verification

**Two browser rows, `§195 M2-22o` and `§195 M2-22p`**, both **NOT RUN** (§65), in lane `M2` beside
§187's `M2-22g` — the clone row that has driven this rule from the server side since §129 and which
these two extend to the screen.

**No backend mutation rows, and that is not an omission** — no Java changed, and the behaviour these
rows describe is already pinned there by `§187`'s clone coverage.

**Built and unverified.**

## The next slice

§194's list, minus what this took, plus what this opened:

- **resume the browser queue at `A2 — plans`** — unchanged since §186, and now carrying sixteen `M2`
  rows including this slice's two.
- **a third `packages/` live pass** — §189's two write arms and three read commands are all
  stub-observed, and `§189 CN-3` cannot be closed by a stub.
- **the snapshot has no reader** — §193's larger item, still untouched. `EntityVersionView` has
  recorded it since §28 as *"an endpoint that does not exist yet"*.
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175); **a run has no edit endpoint at all** (§177);
  §184's *what the SPA computes from a contract enum the server also computes*; §185's *a rule that
  fails when a test reads a path outside `src/` that `build.gradle.kts` does not declare as an
  input*; §188's `= null` writer-rule gap and its `releaseSortKey` observation; §189's *a proposal
  cannot carry a decline*; §190's *whether "a comment whose argument expired" has any computable form
  at all*.

**And two this slice opened.**

**A clone cannot file the copy nowhere.** `TestCaseCloneInput.suiteId` absent means *"the source's
suite"*, so on this endpoint there is no spelling of **unfiled** at all — where the editor's own
picker has said since §96 that "not filed" is a real answer and not a field somebody forgot. A case
filed in `Payments` can be cloned into any folder except none. It is a contract change (a nullable
`suiteId` collides with absent-means-source, so it wants an explicit third spelling or a separate
flag), which is why it is named rather than taken, and it is small.

**The `as TestCase` fixture cast is a population, not an instance.** The cast defeats `required` on
every field the fixture omits, so any component that starts reading one of them fails at run time in
a test that was green the day before. That is mechanically findable — a fixture cast to a generated
type, versus the fields that type requires — and unlike this slice's audit it is a **check** rather
than a reading. `grep -rn "as TestCase\|as TestPlan\|as TestRun" src` is where it starts, and it is
**27 sites across 15 files** as of this commit — measured, not estimated, and none of the other 26
checked.
