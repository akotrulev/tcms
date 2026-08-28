# Case authoring redesign — the case is a document, not a record

**Status:** proposed, nothing implemented · **Date:** 2026-08-28 · **Owner:** frontend, with two
backend follow-ons named at the end.

**Render:** [`assets/case-authoring-redesign.png`](assets/case-authoring-redesign.png) —
frame A is writing an existing case, frame B is what *New test case* becomes. The source that
produced it is [`assets/case-authoring-redesign.html`](assets/case-authoring-redesign.html); open it
in a browser to re-render or to change the direction before any of this is built.

---

## The problem this is answering

`CaseEditorPage.tsx` renders approximately one control per column of `test_case` and asks the author
to populate a schema. Four consequences, all visible on one screenshot of the live screen:

1. **The steps are the case, and they are the smallest thing on the screen.** The procedure is one
   panel, below the summary and below Conditions. Each step costs three controls in three
   modalities — an autocomplete, a three-mode segmented control, a textarea — so writing three
   steps means touching nine controls to produce what is three lines in any `.feature` file.
2. **Three prose boxes nobody can tell apart** — Objective, Description, Expected outcome — and
   case-level *Expected outcome* sits on the same screen as step-level *Expected result*, with
   helper text that apologises for the collision instead of resolving it.
3. **Empty vocabularies are presented as choices.** A project that has never configured
   classification is shown Test Type, Test Level and Feature as selects, plus the sentence
   *"Nothing to choose from yet — this vocabulary is empty."*
4. **The blank state teaches nothing.** *New test case* is an empty form. The one genuinely
   intelligent thing in the module — the step-suggestion corpus, which knows the project's existing
   wording and how many cases use each step — is invisible until you find and focus the right
   field.

A previous pass (§258) moved panels between columns and tightened spacing. It did not touch any of
the four. This document is the replacement, not another restyle.

## The thesis

**A test case is a document.** Its procedure is prose with structure, which is what Gherkin is, and
this instance already speaks Gherkin losslessly in both directions:

| Direction | Endpoint | Already wrapped as |
|---|---|---|
| text → steps | `POST /projects/{projectId}/cases/parse-gherkin` | the parse mutation in `api/queries/cases.ts` (`api.postText`) |
| steps → text | `GET /projects/{projectId}/cases/{caseId}/feature` | `useCaseFeature` (`api.getText`) |

`openapi.yaml` states that `parse ∘ render ∘ parse` is identity and that comments round-trip. Both
hooks exist and ship today; their only consumer is a **paste dialog**. The redesign's central move
is to promote that round-trip from an import affordance to the editing surface.

So: the procedure becomes the page, the schema retreats into a summary you click to change, and the
corpus arrives while you are writing rather than after you find the right box.

---

## Phases

Each phase is independently shippable and independently revertable. Phase 1 alone fixes findings 3
and 4; the document surface is phases 2–3.

### Phase 1 — stop asking for what the project has not got, and make the blank state teach

*No new surface. `CaseEditorPage.tsx`, `ClassificationPicker.tsx`, `useNewCaseDraft.ts`.*

- A dimension with an empty vocabulary **does not render a control.** One sentence and a link to
  Classification, once, for all of them together — not four dead selects and a line of copy that
  describes the system's state and leaves the reader stuck.
- *New test case* gains three starts, rendered above an otherwise empty document: **Write it**,
  **Start from a case** (naming the most recently edited one — `useCases` already sorts by
  `updatedAt`), and **Paste a `.feature`** (the existing `PasteGherkinDialog`, promoted out of a
  button).
- The step corpus (`useStepSuggestions`) gets a **standing panel** in the rail, ranked by use count,
  fetched with an empty fragment so it is populated before the first keystroke. Insert on click.
- The `Given / When / Then` scaffold renders as ghost text on an empty case, saying what each
  keyword is for.

**Risk:** low. No contract change, no data change. `ClassificationPicker.test.tsx` and
`CaseEditorPage.test.tsx` will need arms for the empty-vocabulary branch.

### Phase 2 — the Write lens

*New: `features/cases/CaseDocument.tsx` and `features/cases/gherkinText.ts`.*

A full-width editing surface that is the procedure. Title on the first line, one *Why this exists*
line, then the steps.

**Architecture, and the part that decides whether this works:**

- **While the Write lens holds focus, the TEXT is the source of truth** and `steps` are derived.
  On blur, on lens switch and on save, the text is parsed and committed to the form. The reverse —
  re-rendering the textarea from parsed state on every keystroke — is what makes this class of
  editor jump the caret, and it is not worth attempting.
- **Parse locally, not over the network, while typing.** A round trip per keystroke against
  `parse-gherkin` is not viable at a 250 ms debounce and the corpus endpoint is already on that
  budget. `gherkinText.ts` holds a line-level parser sufficient for *keyword, sentence, docstring,
  table* — enough to colour and number, not enough to be authoritative. **The server's parse is
  what commits**, on blur and on save, so the client parser can never disagree with what is stored.
- **Syntax colour is a mirrored `<pre>` behind a transparent `<textarea>`**, sharing font metrics.
  This is the fiddly part and the reason phase 2 is not an afternoon: the two must agree on
  wrapping, padding and line-height exactly, or the colour drifts off the text.
- The corpus completes **under the caret**, keyed on the current line's fragment, reusing
  `useStepSuggestions` — with the use count shown, so an author can see they are about to write the
  project's twenty-first wording of a step it already has.

**Kept, not replaced:** the existing row editor stays as a **Fields** lens over the same document.
See *Open problems* — this is the weakest part of the design.

### Phase 3 — metadata as tags

Priority, execution phase, placement and automation become `@tag` syntax at the top of the
document, autocompleted from the project's own vocabulary, with the rail demoted to a read-only
summary that opens a picker on click.

**This phase is gated on the open problem below being solved, not merely noted.** It is the one part
of the render that is drawn past rather than designed.

---

## Decisions, and what they cost

**`Then` is the expected result — but the field stays.** Per-step `expectedResult` is a TCMS
invention Gherkin does not have, and it is why *Expected outcome* and *Expected result* collide.
In the document, a `Then` (or an `And` after a `Then`) IS the assertion, so the Write lens does not
offer a separate box for it.

> **Correction to what was said verbally: the field does not go.** `expectedResult` is read by
> `ExecutionPage.tsx:1191` — where a tester running the case sees *"Expected: …"* beside the step —
> and by `CaseDetailPage.tsx:1065` and `ProposalPage.tsx:347`. Deleting it would blank a column on
> the screen the product exists to serve. It becomes **derived and legacy**: rendered wherever it
> has a value, never authored on a new case, and the Fields lens keeps its input for cases that
> already carry one. Whether existing values are migrated into their step's sentence is a backend
> question and is deliberately not proposed here.

**Description dies; Objective survives.** The title is the first line. *Why this exists* is the
objective, kept because it is the only one of the three that says something the procedure cannot.
Description becomes a Gherkin comment block, which the contract already says round-trips.

**Empty vocabularies render nothing.** A control the project cannot use is not a choice.

**The icon rail collapses on this route.** The document wants the width. This is the one piece of
app-shell behaviour the redesign changes, and it should be scoped to the editor route rather than
made global.

---

## Open problems — unsolved, not oversights

1. **Two editors is two editors.** The Write/Fields toggle keeps the row editor for authors who do
   not know Gherkin. That is honest about the audience and dishonest about the cost: §255 already
   paid for a toggle between two representations of one thing, and the rule it left behind is that
   such a toggle must not be able to destroy the thing. The lenses are safe here — both commit
   through the same parse — but they are two surfaces to build, test and keep in agreement forever.
   **Alternatives not yet costed:** ship Write only and let the Fields lens go; or ship Fields only
   and treat the document as an import/export view.

2. **`@suite:refunds` and `@priority:medium` are not tags.** `case_tag` is free text; suite is a
   foreign key and priority is an enum. Rendering them as tag syntax means inventing a namespaced
   convention the backend does not know, parsing it on the client, and validating it against three
   different vocabularies — and deciding what happens when someone types `@priority:urgnet`. The
   render draws this working. It is not designed. **Phase 3 does not start until it is.**

3. **Nothing here has been driven in a browser.** The render is HTML written to communicate a
   direction; it is not the product and it is not evidence that any of this is workable at real
   content lengths, at 1280px, or with a fifty-step case.

## What the backend would adopt later

Neither is required for phases 1–2, and neither is proposed as work yet.

- **Parse on the wire is already enough**, but a `parse` that accepts a *fragment* (one line, no
  scenario header) would let the client stop carrying its own line parser for colouring.
- **A tag namespace**, if phase 3 survives its open problem — `@ns:value` reserved and validated
  server-side, so the client is not the only thing standing between a typo and a silently
  unclassified case.
