# §102 — Tags become reusable: the endpoint that had one consumer, and the locator that had none

**Date:** 2026-08-06 · **Lane:** frontend only · **Migrations:** none · **Contract:** unchanged

The ask was "make test case tags searchable so I can reuse them". No backend work was needed and
none was done: `GET /projects/{projectId}/tags` has existed since §97 phase 3b, and §97 phase 6 gave
it exactly one consumer — the plan's Test cases tab. The three controls where a person actually
*writes* a tag were still shipping `options={[]}`, which §96 recorded as a deliberate gap and §97
restated as a named follow-up. This slice closes it.

## What changed

| File | Change |
|---|---|
| `features/cases/CaseEditorPage.tsx` | `useProjectTags` + `options` on the Tags control, `filterSelectedOptions` |
| `features/cases/SuiteDialogs.tsx` | same on **Tag rules**; `SuiteFormDialog` gains a `projectId` prop, passed by both callers |
| `features/cases/BulkActionDialog.tsx` | same on **Add tags** — plus the locator fix below |

Nothing else moved. No contract change, no `gen:api`, no migration, no new query key: `keys.tags`
and `useProjectTags` were already there, already typed, already cached at `staleTime: 60_000`.

## The defect this slice found, which was not the one it went looking for

`BulkActionDialog`'s tags field carried its `data-testid` **inline on the `TextField`**, so MUI put
it on the root `div` and never on the input. The locator resolved to an element `userEvent.type`
cannot type into.

This is §96's slot trap in the form that hides longest. §96's version was visible — the chips
vanished, so an author typed a tag and saw nothing. This one was invisible, because **the two tests
covering this field never typed into it**: both drove the operation select, then read the POST body.
The field had shipped, been tested, and been unusable by any automated caller since the day it
landed. It surfaced only when a suggestion list gave the control something to filter, and the new
test's `findByRole('option')` timed out against a dropdown that could never open.

**The lesson is about the test, not the widget**: a test that asserts only the payload will pass
against a control nobody can reach. §96 paid for this once at the chip level and wrote it down; the
same defect at the *locator* level went unnoticed for two more slices because the assertion never
went through the DOM.

## The decisions worth not re-litigating

- **The list is DERIVED, never curated.** A tag is offered because a case carries it, so the picker
  cannot disagree with the grid and nobody has to populate it. The alternative — a maintained tag
  vocabulary — is precisely what left `shared_step` empty (authoring-rework decision 2), and the
  original `options={[]}` comment was right about that and wrong only about there being no endpoint.
- **`freeSolo` stays on all three, and a test on each one pins it.** The endpoint is a picker's list
  and never a validator's. A control that refused an unlisted tag would make the first use of any
  new tag impossible — the vocabulary would close itself, silently, the moment suggestions arrived.
  This is the one property of the slice that a well-meaning "tidy-up" is most likely to remove.
- **`filterSelectedOptions` on all three.** Re-picking a tag already on the case is a no-op the
  de-duplicating `Set` swallows without a word, which reads as a dead menu row.
- **The read is optional and stays ungated.** No `enabled`, no `isSuccess` gate, no spinner: a failed
  or slow `/tags` leaves an empty option list and the author types the tag exactly as before. The
  suggestion is an enrichment, and an enrichment that can break the field it enriches is not one.
- **The order is the server's.** Most-used first, then alphabetically. MUI's default `filterOptions`
  preserves the given order, so nothing client-side re-sorts it — alphabetical alone would put a
  project's one stray tag above the one on half its cases, in a picker whose first rows are the
  whole point.

## Verification

`docs/verification/frontend-browser-checks.md` rows **M2-49 … M2-53**, none run (§65 standing
policy). M2-52 is the one to drive hardest — the bulk field had never been typed into by anything.
The "Still owed" entry naming these three controls is struck through and points here.

No mutation rows: this slice writes nothing the server had not already been written by, and the
tag payloads it produces are the ones §96's rows already cover.

## Gate

`npm run build` ✅ · `npm run lint` ✅ (0 warnings) · `npm run test:run` — 6 tests added
(2 case editor, 1 suite dialog, 1 bulk dialog, plus 2 on the plan tab, below).

## The two tests that are not about tags

While reproducing a separate report — that adding a suite or tag to a plan's Test cases section did
not show up, and that removing a tag dropped what was added beside it — two tests were written
against `PlanCasesTab` and **both passed**, on the unsectioned group and inside a named section.
They are kept: the add path's *visibility* had genuinely never been asserted (the existing test reads
the saved payload, and the one that renders chips seeds them from the server), so a reference that
was recorded but never drawn would have passed the whole file.

The report itself turned out to be a different ask — showing the **cases** each reference currently
resolves to — which is not built and is **not** a client-side filter on `case.suiteId`: the suite arm
is filed ∪ added ∪ tagged since §98, so resolution has to come from the server or it undercounts
exactly the way §96's suite count did.
