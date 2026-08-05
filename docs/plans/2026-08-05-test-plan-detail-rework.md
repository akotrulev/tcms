# The test plan detail becomes one document — slice plan (§97)

**Date:** 2026-08-05
**Source:** review of §93 against `docs/Easy leave test plan.pdf`, and the four corrections that came
out of it. **Revised 2026-08-05 (second pass)** after the user restated what a test plan *is* — see
§1a. That restatement retires three of the five original decisions; they are struck through in place
rather than deleted, per the working agreement.
**Status:** phase 1 shipped (`eb548d2`, the contract's `PUT`/`DELETE`). Phases 2+ **re-planned, not
started**.

---

## 0. What §93 got wrong, in the user's words

§93 shipped the plan document as a Document tab holding one flat list of text fields, each saving on
blur, with an "Add a test case" bar under it that required a suite and created the case immediately.
Four corrections:

1. **The sections should be different sections on the detailed view** — not one undifferentiated
   list of blocks.
2. **Case creation must not require a suite.**
3. **Case creation must work the way adding a step works** — an empty row appears, it goes to the
   server only once it is filled in *and* the plan is saved, and nothing is created before that.
   A warning says there are unsaved changes.
4. **The document's "Test cases" subsection is dropped** — the plan already carries its cases.

## 1a. What a test plan IS — the user's restatement (2026-08-05)

> A test plan outlines what must be completed for a feature to be considered done. It consists of
> name, business requirements (link to a document and displaying the document itself), test
> artefacts (we have that as an entity, we need to link it to a plan), Scope — free text only, says
> what is to be tested, Out of scope — what needs to be skipped when testing, then a test case
> section. The test case section is split in sections by sub-feature, for example Create an object,
> then another section for Edit, Navigation, etc. In each sub-feature, user can either add a new
> test case (title only) OR select from already existing cases either by searching for them by id or
> name, or by adding an entire suite, or by feature tag.

**This is a set of six semantically-typed sections, not a document with headings.** Each has its own
mechanism and its own storage, and only one of them is prose in a `DocPage`. That is what retires D1.

## 1. Decisions

### Retired by §1a

- ~~**D1 — a section per tab, derived from the document's level-1 headings.** Not a fixed tab set.
  Whatever `heading` blocks at `level: 1` the document has become the tabs, in document order.~~
  → **superseded 2026-08-05.** The tab set is **fixed and typed**: Progress · Plan · Business
  requirements · Test artefacts · Scope · Out of scope · Test cases. Four of those seven cannot be
  blocks in a page — one is a linked page, one is the artifact register, one is a case tree — so a
  mechanism that derives tabs from headings can only produce the *prose* ones and would need every
  other tab hardcoded beside it. Deriving nothing is simpler than deriving one seventh.
  The **failure mode D1 was designed to remove is removed anyway**: with no `CASE_SECTION` text
  match, there is no magic heading whose renaming strands a control.
- ~~**D5 — "Test cases" leaves the template.**~~ → **superseded**: the *template* leaves. See D4′.

### Standing

- **D2 — no suite when adding a case.** Not a default, not a hidden picker: **none**. This is
  decision 4 of the authoring rework — a suite is a *resolvable collection*, membership is optional,
  and a case reaches a suite through its tags. `test_case.suite_id` went nullable in **§96**. ✅ met.
- **D3 — one Save for the entire plan.** The plan's own fields, the sections' membership, and the
  new case rows are one form with one Save button and one dirty state. **`PlanEditDialog` is
  replaced by it.** *Its cost drops sharply under D6* — see §2.

### New

- **D4′ — business requirements is a LINKED page, and the template is retired.** The plan links a
  document somebody already wrote and displays it inline, read-only, with an edit affordance that
  goes to the page. `POST /plans/{planId}/document` (create from the five-section template) is
  **retired**, because four of that template's five sections now have real homes and the fifth is
  the whole document. `PlanDocumentTemplate` is deleted, not emptied.
  **One page, not many** — `doc_page.subject_type`/`subject_id` holds one plan and a list would need
  a join table; named as not built.
- **D6 — Scope and Out of scope are columns on `test_plan`.** Free text, one per plan, saved with
  the plan under `plan:edit` on the plan's own lock version. **This is the decision that removes a
  third of D3's risk**: two of the three writes Save had to sequence collapse into the plan's own
  `PATCH`.
  **The word collides and the contract must say so.** `plan_scope_item` already means *which cases
  are in the plan*; this Scope is prose about what will be tested. Contract fields are `scopeText`
  and `outOfScopeText`, columns `scope_text` / `out_of_scope_text`, and both carry a description
  naming the other meaning. The UI labels stay "Scope" and "Out of scope", because that is what a
  test plan calls them and the tab strip disambiguates by position.
- **D7 — sub-feature sections are LABELS on scope items, expanded at add time.** `plan_scope_item`
  gains `section_name` and `ordinal`. Adding a suite or a tag to a section **resolves it once, at
  add time, into individual case rows** carrying that section's name.
  **The accepted cost, stated plainly:** the section does not track the suite or the tag, so a case
  added to that suite or given that tag *later* does not appear in the plan. A plan is a release
  checklist and a checklist that grows under the reader is arguably worse — but this is the
  trade being made, not a limitation nobody noticed.
  **The benefit that decided it:** no third copy of §96's resolution predicate. §96 writes
  filed ∪ added ∪ tagged twice already (`SuiteMembership` for the tree and the grid, `CaseFacts` for
  plan scope and suite history) because ERD §0.2 forbids the cross-module call, with
  `SuiteScopeAgreementTest` keeping them honest. A live-resolving plan section would be a **third**,
  in a third module, with a three-way agreement test.
- **D8 — "feature tag" means §96's `case_tag`,** not the Feature classification dimension. Same
  mechanism a suite's `tagRules` use, so "add by tag" on a section and "add by tag" on a suite are
  the same act with the same semantics — and D7 means the section keeps the *result* rather than
  the rule.
  **This forces a new endpoint.** §96 shipped both tag controls with an empty option list and named
  a "tags in use" endpoint as the obvious follow-up. It stops being optional here: a tag is now a
  *fill mechanism* for a section, and a picker that cannot tell you which tags exist makes it
  unusable. `GET /projects/{projectId}/tags` is in scope (§6.1).

## 2. What Save writes — and it is now two endpoints, not three

D6 and D7 both move work onto the plan's own row, which is the single biggest change from the first
draft of this plan.

| What Save writes | Endpoint | Permission | Optimistic lock |
|---|---|---|---|
| Plan fields, criteria, **scope text**, **out-of-scope text**, **sectioned scope items** | `PATCH /plans/{planId}` | `plan:edit` | `test_plan.lock_version` |
| New case rows | `POST /cases` ×N | `case:create` | — |

**The document is no longer written by this form at all.** Under D4′ business requirements is a
*linked, displayed, read-only* page; editing it is a trip to `/doc-pages/{pageId}`, which is where
`doc:edit` and `doc_page.lock_version` stay. That removes:

- the third write and its partial-failure arm,
- the second lock version and the two-409s-two-sentences trap,
- the doc page's third 409 (*"n links point at content you are removing"*),
- the whole "a tester with `doc:edit` only must not get a screen that does nothing" argument.

**What survives** is the ordered pair, and the ordering argument is §93's, unchanged and still right:

1. **Create every filled-in case row** (`POST /cases` ×N). A case is the durable half — a case that
   exists without being in scope is findable and re-addable; a scope entry pointing at a case that
   was never created cannot exist.
2. **`PATCH` the plan** — fields, criteria, both texts, and the sectioned scope items including the
   ids just created.

**Partial failure is still named per step**: if step 2 fails, the banner says the cases were created
and the plan was not, and names them, because they are now loose in the project and the reader needs
to know that rather than press Save again and create them twice.

**Permissions disable sections, not the Save** (§31), and the case for it is narrower now but not
gone: a caller with `plan:edit` and not `case:create` gets the Test cases tab's *new-row* affordance
disabled with the reason, and can still add existing cases, reorder sections and save.

## 3. The tabs

```
[ Progress ] [ Plan ] [ Business requirements ] [ Test artefacts ] [ Scope ] [ Out of scope ] [ Test cases ]
```

Fixed, typed, in this order. What is behind each:

- **Progress** — unchanged; today's statistics screen, its own query, unmounted while inactive, holds
  no draft. **It must keep working for a reader with no write permission at all** — the page is a
  report first, it is what gets pasted into a release thread, and turning it into a form must not put
  a disabled editor in front of somebody who came to read numbers.
- **Plan** — name, description, milestone, entry and exit criteria. `PlanEditDialog`'s contents, on
  the page.
- **Business requirements** — the linked `DocPage`, rendered read-only from its blocks, with
  "Open in documentation" and, when nothing is linked, **Link a page** (phase 1's `PUT`). Unlink is
  there and says what it does: it unlinks, it does not delete.
- **Test artefacts** — the plan-scope artifact register. **Backend already exists** (§4).
- **Scope** / **Out of scope** — one text field each (D6).
- **Test cases** — the sub-feature sections (§5).

**The tabs stay mounted, and this reverses a §93 decision.** `PlanStatisticsPage` currently unmounts
the inactive tab, arguing that a draft surviving a tab switch is a draft the author cannot see they
made. Under D3 that inverts: the form *is* the page, so unmounting a tab discards what was typed in
it. State lives above the tabs and **the dirty banner names which tabs are dirty**, which is what
makes an off-screen edit visible — the old argument was right about the risk and wrong about the
remedy. Progress is the exception and keeps its own lifecycle.

## 4. Test artefacts — already built, and this is the finding

`artifact` (`V1__initial_schema.sql:1021`) is a full entity: `scope IN ('PROJECT','PLAN')`, a
`plan_id`, four `kind`s, `status` with supersede-not-edit, `write_once` for sign-off records. It is
mapped — `com.tcms.documentation.Artifact` — and it is served:

```
GET  /projects/{projectId}/artifacts?scope=PLAN&planId={id}
POST /projects/{projectId}/artifacts
```

with project-scope artifacts returned **as well, flagged `inherited`** (plan §6n), so a tester
opening a cycle finds the environment guide without hunting for it.

**So "link test artefacts to a plan" needs no backend work whatsoever** — it needs a tab. The first
draft of this plan had test artefacts as *a prose heading in a document*, which would have re-typed
a register the product already has as free text, and §6n's own words for that outcome are "a
graveyard of outdated copies".

`FILE` and `GENERATED_REPORT` are declared but refused by `POST` (no storage port — open question
#28). The tab offers `DOC_PAGE_REF` and `EXTERNAL_URL`, which is what the server accepts, and does
not render an upload control for a thing that 400s.

## 5. The Test cases tab

A list of **named sections** in author order, each holding case rows.

**Section mechanics.** A section is a name and an ordinal, carried on its member rows (D7). Sections
are created, renamed, reordered and deleted on this tab; deleting one asks what happens to its cases
(remove from plan / move to unsectioned) rather than guessing. An **unsectioned** group holds items
with a null `section_name`, which is what every plan created before this slice has.

**Four ways to fill a section**, exactly as stated in §1a:

1. **A new case** — title only, no suite (D2). The step editor's shape, per correction 3, with
   `CaseEditorPage` as the reference implementation to follow rather than re-derive:
   - the list always ends in a **blank row**; typing in the last row appends the next
     (`NO_FOCUS` — the append happens while the author is typing in the row above, so
     focus-the-new-row would move the caret out of the sentence being written);
   - **trim, then drop rows that are entirely empty**, at submit — a whitespace-only title never
     reaches the API;
   - **nothing is created until Save**, which retires §93's `useCreateDraftCase` and the
     idempotency-key-per-row problem along with the bar it existed for.
2. **An existing case**, searched by key or title.
3. **A whole suite** — resolved once, at add time, into its member case rows (D7).
4. **A tag** — same, over `case_tag` (D8), needing `GET /projects/{projectId}/tags`.

3 and 4 are **add-time expansions and the UI must say so at the moment of the click**: "adds the 12
cases in this suite now" is a different promise from "keeps this plan in step with this suite", and
the reader cannot tell them apart afterwards. A count and a sentence, on the button.

**The blank row must not count.** `CaseSchema`'s "at least one step" refine had exactly this defect
in §94 and it is the same shape: any "the plan has cases" or "this section is empty" check must
count non-blank rows, or a permanently-present trailing blank satisfies it.

**Duplicates across sections are legal and must be visible.** A case can be added to Create and also
arrive via a suite expansion in Navigation. The plan's case count dedupes (it always has — a suite
and one of its cases were already two ways of saying one thing), so the sections' counts **do not
sum to the plan's total**, which is §96's lesson arriving one slice later: say it on the tooltip, or
a reader subtracts and reports a bug in a correct number.

## 5a. OPEN — the existing suite arm expands at READ time, and D7 expands at ADD time

**This needs a decision before phase 2.** `plan_scope_item.suite_id` exists today and the contract
defines it as live: *"A suite item means the folder and everything beneath it — the plan's
`caseCount` expands it."* D7's sections snapshot instead. So one screen would show two behaviours:

| | Today's suite item | D7's section |
|---|---|---|
| Storage | `suite_id` row | N `case_id` rows + `section_name` |
| A case added to the suite later | **appears** | does not |
| Can it be sectioned? | no — it is one row for many cases | yes |

Three ways out, none free:

- **(a) Keep both.** Suite items render as their own live group above the sections, labelled as
  live. Honest, and asks every reader to hold two models.
- **(b) Migrate suite items to expanded rows.** `V32` expands every existing `suite_id` item into
  case rows and the arm is retired from the contract. One model, and it **silently changes what
  existing plans contain** the next time a suite grows — which is the behaviour change nobody
  observes until a release checklist is short.
- **(c) Sections hold suite items too, live.** Reverses D7 for the suite arm only, keeps it for
  tags. Needs the read-time resolution D7 was chosen to avoid, so it costs the third copy.

## 6. Scope

### In

1. **Contract**: `scopeText` / `outOfScopeText` on `TestPlan` and `TestPlanUpdateInput`; section
   fields on the plan's scope items; `GET /projects/{projectId}/tags`; retire
   `POST /plans/{planId}/document`. Regenerate `schema.d.ts`.
   *(`PUT`/`DELETE` on the plan document — shipped, phase 1, `eb548d2`.)*
2. **Migration `V32`** — `test_plan.scope_text`, `test_plan.out_of_scope_text`,
   `plan_scope_item.section_name`, `plan_scope_item.ordinal`. No new table, so **no new RLS policy**:
   the count stays at 61. Plus whatever §5a's decision requires.
3. **Backend**: the plan `PATCH` accepts both texts and sectioned scope items; add-time expansion of
   a suite and of a tag into case rows; the tags-in-use query; `PlanDocumentTemplate` deleted with
   its `POST`; the link/unlink service and its three refusals (phase 1's contract).
4. **Frontend**: the plan detail becomes one form — state above the tabs, one Save, the ordered
   pair, per-step partial-failure reporting, the dirty banner naming dirty tabs,
   `useUnsavedWork` registration.
5. **Frontend**: the seven fixed tabs; the Plan tab replacing `PlanEditDialog`; the business
   requirements tab rendering the linked page read-only with link/unlink; the Test artefacts tab
   over the existing register; the two text tabs.
6. **Frontend**: the Test cases tab — named sections, four fill mechanisms, auto-append, trim,
   blank-row drop, deferred creation, the add-time sentence on the suite and tag buttons.
7. **Tests** — backend for link/unlink, both expansions, the tags query, and the section round-trip;
   Vitest/RTL for auto-append, trimming, blank-row drop, the dirty banner, and the ordered save with
   an injected failure at step 2.
8. Both verification queue rows, the handoff section, both orientation caches.

### Out, and named

- **No rewrite of existing documents.** There is no reset-to-template and this slice does not invent
  one; retiring the template affects what new plans get, not what old ones hold.
- **Business requirements is one page, not a list** (D4′).
- **The Feature classification dimension is not a fill mechanism** (D8 chose tags). It exists, it is
  seeded per project (`V18`), and using it here is a later call.
- **`DocBlock.coverage` stays absent** — still nothing here can price a link (§93 §3).
- **Export.** Printing the plan as the PDF is still §88's renderer's future job.
- **File uploads on the artifact tab** — the storage port does not exist (open question #28).

## 7. Phases

Each ends with its gate (`./gradlew build` from `backend/`; `npm run build` + `npm run lint` +
`npm run test:run` from `frontend/`) and its own commit. Browser passes and mutation checks are
**deferred and batched** per §65 — each phase appends its rows to `docs/verification/*.md` and runs
none of them.

| # | Phase | Gate |
|---|---|---|
| 1 | ✅ **shipped** (`eb548d2`) — contract: `PUT`/`DELETE` plan document | `npm run build` |
| 2 | Contract — scope texts, section fields, tags endpoint, retire the template `POST`; `gen:api` | `npm run build` |
| 3 | Migration `V32` + backend: scope texts, sectioned items, both add-time expansions, tags query, link/unlink service, template deleted | `./gradlew build` |
| 4 | Frontend — the form spine: state above tabs, one Save, ordered writes, partial-failure reporting, dirty banner, `useUnsavedWork` | both |
| 5 | Frontend — the seven tabs: Plan (replacing `PlanEditDialog`), business requirements, artefacts, the two texts | both |
| 6 | Frontend — the Test cases tab: sections, four fill mechanisms, auto-append, deferred creation | both |
| 7 | Handoff section, index row, both orientation caches, verification queue rows | both |

## 8. Traps

- **§5a is unresolved and phase 3 cannot be written without it.**
- **Two meanings of "scope" on one screen.** `plan_scope_item` is the case list; `scopeText` is
  prose. A contract field, a column and a UI label each have to survive a reader who knows only one.
- **Add-time expansion is invisible after the fact.** Once a suite is expanded, nothing records that
  it came from a suite. The sentence on the button is the only place the reader is told, so it is
  load-bearing rather than polish.
- **The blank row must not satisfy a non-empty check** — §94's refine defect, same shape.
- **Section counts do not sum to the plan's total** — §96's lesson, one slice later.
- **`PlanEditDialog` is deleted, not orphaned.** A dialog nobody opens that still compiles is the
  second implementation `ui-rules` §8 is about; `PlanLifecycle.test.tsx` and `PlanEdit.test.tsx` move
  with it. Same for `PlanDocumentTemplate` and its tests.
- **Progress must keep working for a reader with no write permission at all** (§3).
- **The tags endpoint is a new `api.*` call site**, so §70's contract-hygiene checks will generate
  against it — expect the frontend test count to move by more than the tests written by hand.
