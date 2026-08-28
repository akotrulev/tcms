# §256 — One format, and the keyword nobody selects

**2026-08-28. Frontend only. Phase 1 of 2.**
✅ **Gated green on `shanks`** — `1847 / 1847` across 133 files (was `1833 / 132`), `npm run build`
clean, `npm run lint` 0 warnings.

## Where this came from

A user question, and it was the right one: *"I don't see a difference between the Gherkin and
structured steps other than the optional keyword."* There wasn't one. §255 had made both lenses the
same `test_step` rows with the same fields, the same detail rendering, the same `.feature` export
and the same completeness arithmetic. What survived was two validation rules — every step needs a
keyword, and the first may not be `AND`/`BUT` — plus a chip on the grid.

Pressed on why the switch existed at all, the honest answer turned out to be: for a guarantee the
editor was simultaneously undermining. `BLANK_STEP` was minted with `keyword: ''` at three call
sites, and the Gherkin lens then refused it. **The form manufactured the error state and then
complained about it.**

## The rule to carry

> **A control whose only job is to decide whether a field is required is not a control — it is a
> defect generator. Supply the answer as the row is made, and the control decides nothing.**

That is the whole slice. Once every appended row arrives carrying a keyword, R1 is unreachable by
typing, and the toggle's permissive side existed only to produce rows the form refused.

## What changed

**Every case is `GHERKIN`.** `NEW_CASE_DEFAULTS.stepFormat` is `GHERKIN`, the editor pins the field
rather than carrying the case's stored value, and the `ToggleButtonGroup` is gone.

**`caseSchema.ts::nextKeyword`** — `GIVEN` when there are no live rows, `AND` otherwise. Wired into
**all four** paths that can mint a row: the trailing blank in `defaultValues`, the append on typing
into the last row, the append after a step-suggestion pick, and `ensureTrailingBlank`. A missed call
site is invisible — it produces an ordinary-looking row that refuses to save — which is why the
browser rows drive the paths rather than the function.

**`AND`, not a guess at `WHEN`/`THEN`.** Those are claims about what a step *means*, and this fires
before the step has been written. `prefilledKeywords` may guess because it reads a finished case.

**`prefilledKeywords` was repurposed, not deleted.** It was the toggle's proposal; it is now the
*load* path's — `defaultValues` for a pre-§256 case, `fromCasePayload` for a pre-§256 draft. Still
fills only, still idempotent.

**The draft restore was a real failing test, not a hypothetical.** Restoring a pre-§256 draft
verbatim put the form into the state R1 refuses, so Save did nothing, **the buffer was never
cleared**, and nothing on screen said why — the author's own restored work, unsavable. Caught by
`CaseEditorPage.test.tsx:1012` on the first shanks run. `fromCasePayload` now fills and pins.

**R1/R2 are unconditional**, kept for the two states the controls cannot produce: a draft written
before §256, and a select the author deliberately cleared.

**`StepKeywordCell`** is always mounted, has no `None`, and has no per-row `revealed` state.
`CaseEditor.Steps.AddKeyword` and `CaseEditor.StepFormat.*` are gone **as locators**, not merely as
controls — anything driving this screen must stop looking for them.

## What is deliberately NOT done

**Phase 2 — the contract and the column — is not built.** `stepFormat` is still `required` on
`TestCaseSummary`, `TestCaseInput` and the version snapshot; `CaseEdit.validate` still branches on
it; `test_case.step_format` is still a column. It cannot go until `migrate-gherkin-source` reports
zero on every instance, and removing a required contract field breaks `@tcms/cli` and `@tcms/mcp`.
The SPA sending `GHERKIN` unconditionally is compatible with the server exactly as it stands.

**`CaseDetailPage.tsx` was not touched**, so it still renders a `Step format` chip that now reads
*Gherkin* on every case — a label with no information in it. Left because **a concurrent lane held
uncommitted edits in that file** (the design-audit remediation pass), and interleaving another
author's in-flight diff is the hazard §37/§41/§45 were. It is the first thing phase 2 deletes.

## The UX finding this came out of is ONE-SIXTH fixed

The user's wider point — that the case screen and flow were never designed — was also correct, and
the audits confirm it by omission: `docs/audits/002-design-audit-r3-journeys.md` audited the run and
execution journeys, called the execution screen the best in the product, and **never opened the case
editor**. Still open, in the order judged worth doing:

1. **Enter does not advance to the next step.** There is no `onKeyDown` anywhere in
   `CaseEditorPage.tsx`; the append deliberately does not focus (`NO_FOCUS`, correct, since it
   fires mid-sentence) and nothing replaces it. For a text-entry activity this is the largest
   remaining cost — every step ends with a mouse trip.
2. **The Gherkin lens is still a row grid, when both halves of a text editor already exist on the
   server.** `POST …/cases/parse-gherkin` and `GET …/cases/{caseId}/feature`, which `openapi.yaml`
   states round-trip as identity on the rows. Typing a scenario as text is behind a button, in a
   modal, one-way.
3. **Four always-visible icon buttons per row** (drag, up, down, remove) against three text fields.
   A ten-step case is forty controls and forty buttons.
4. **`Expected outcome` (case) and `Expected result` (step)** collide on one screen; the helper text
   apologises for it rather than resolving it.
5. **Four multiline boxes before the first step**, with no stated difference between Objective and
   Description, and guidance on only the Title.

## Verification

Five browser rows appended — **`§256 GK-1`…`GK-5`, written and not run** (§65). `§255 US-2` is
**struck as superseded**, not renumbered: it presses a toggle that no longer exists, and `GK-1`
carries the half that still matters. **No mutation rows, and that is not an omission** — the slice
changes what the client sends, not what the server does with it.
