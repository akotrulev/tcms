# The test plan detail becomes one document — slice plan (§97)

**Date:** 2026-08-05
**Source:** review of §93 against `docs/Easy leave test plan.pdf`, and the four corrections that came
out of it. **Revised 2026-08-05 (second pass)** after the user restated what a test plan *is* — see
§1a. That restatement retires three of the five original decisions; they are struck through in place
rather than deleted, per the working agreement.
**Status:** phases 1–2 shipped (the contract, both halves). **Phase 3 split in three** — 3a (M7 + M9
+ `V32`) shipped; 3b (scope prose, sections, the tag arm, the tags endpoint) and 3c (the plan
document's link/unlink, the template deleted) next. Phases 4–7 not started.

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
- ~~**D7 — sub-feature sections are LABELS on scope items, expanded at add time.** Adding a suite or
  a tag to a section resolves it once, at add time, into individual case rows. The benefit that
  decided it: no third copy of §96's resolution predicate, which a live-resolving section would need
  in a third module with a three-way agreement test.~~
  → **superseded 2026-08-05 by the TestNG validation.** See D7′.
- **D7′ — sub-feature sections are LIVE SELECTORS, resolved at read time.** `plan_scope_item` gains
  `section_name`, `ordinal` and a **`tag`** arm. A section holds *references* — case ids, suite ids,
  tags — and its membership is a query, exactly as a suite's is.
  **What decided it:** TestNG's `<test>` block is this exact level — a named subdivision carrying
  its own `<classes>` list and its own `<groups>` filter — and it re-resolves on every run. The user
  validated the whole model against TestNG and this is the one place the two disagreed. The analogy
  won.
  **D7's justification does not survive contact with the code, which is what makes the reversal
  affordable.** D7 feared a *third* copy of the resolution predicate in a *third* module. But
  `CaseFacts` is already in `com.tcms.execution` and **already owns `casesInPlanScope`** — the
  plan's scope expansion, `inSubtreeOf` and all. Live sections extend a method that exists, in the
  module that already holds the seam. The count of copies stays at **two**, and
  `SuiteScopeAgreementTest` keeps them honest exactly as it already does.
  **The accepted cost, stated plainly:** a plan's contents can change between approval and
  execution — tag a case `checkout` and it joins every section claiming that tag. That is §82's
  gating concern, and it is **narrower than it first looks**: `requireApprovedCaseForRun` resolves
  at *run-create* time over the resolved set (§82's own decision), so an unapproved case arriving by
  tag is still refused at the moment it would run. What goes stale is the **plan's own approval**,
  not the case gate — and that is the thing to say on screen and in the handoff.
- **D8 — "feature tag" means §96's `case_tag`,** not the Feature classification dimension. Same
  mechanism a suite's `tagRules` use, so "add by tag" on a section and "add by tag" on a suite are
  the same act with the same semantics — and under D7′ the section keeps the **rule**, not a
  snapshot of its result, which is what makes the two genuinely identical.
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

**Section mechanics.** A section is a name and an ordinal carried on its member rows (D7′), and its
members are **references, not cases**: case ids, suite ids and tags. Its contents are a query.
Sections are created, renamed, reordered and deleted on this tab; deleting one asks what happens to
its references (remove from plan / move to unsectioned) rather than guessing. An **unsectioned**
group holds items with a null `section_name`, which is what every plan created before this slice
has.

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
3. **A whole suite** — stored as a suite reference and resolved on every read (D7′).
4. **A tag** — stored as a tag reference, over `case_tag` (D8), needing
   `GET /projects/{projectId}/tags`.

3 and 4 are **live, and the UI must say so wherever the section's count appears**: this plan grows
when the suite grows and when somebody tags a case. Under D7 the sentence had to be on the *button*,
at the moment of the click, because nothing afterwards recorded where a row came from. Under D7′ the
reference is still there to be shown, so the section lists what defines it — "Smoke suite · tag
`checkout` · 3 cases added directly" — and the count is derived beside it.

**The blank row must not count.** `CaseSchema`'s "at least one step" refine had exactly this defect
in §94 and it is the same shape: any "the plan has cases" or "this section is empty" check must
count non-blank rows, or a permanently-present trailing blank satisfies it.

**Duplicates across sections are legal and must be visible.** A case can be added to Create and also
arrive through a suite reference in Navigation. The plan's case count dedupes (it always has — a suite
and one of its cases were already two ways of saying one thing), so the sections' counts **do not
sum to the plan's total**, which is §96's lesson arriving one slice later: say it on the tooltip, or
a reader subtracts and reports a bug in a correct number.

## 5a. DISSOLVED — the read-time/add-time mismatch no longer exists

This section asked how to reconcile `plan_scope_item.suite_id`, which the contract defines as
**live** (*"the folder and everything beneath it — the plan's `caseCount` expands it"*), with D7's
add-time snapshots. It was resolved on 2026-08-05 as *(a) keep both, and label the live one*.

**D7′ dissolves the question rather than answering it.** Sections are live too, so there is one
behaviour on the screen and not two: every arm — the legacy suite item, a section's suite reference,
a section's tag, an explicit case — resolves at read time. Nothing needs a "grows as the suite
grows" label because everything does, and the two-group rendering (a) called for is not built.

That is the second-order benefit of following TestNG here, and it is worth naming: the awkwardness
§5a existed to manage was **created by D7** and disappeared with it.

## 6. Scope

### In

1. **Contract**: `scopeText` / `outOfScopeText` on `TestPlan` and `TestPlanUpdateInput`; section
   fields on the plan's scope items; `GET /projects/{projectId}/tags`; retire
   `POST /plans/{planId}/document`. Regenerate `schema.d.ts`.
   *(`PUT`/`DELETE` on the plan document — shipped, phase 1, `eb548d2`.)*
2. **Migration `V32`** — `test_plan.scope_text`, `test_plan.out_of_scope_text`,
   `plan_scope_item.section_name`, `plan_scope_item.ordinal`, and **`plan_scope_item.tag`**, which
   requires widening `ck_plan_scope_target` from a two-way XOR (`(suite_id IS NULL) <> (case_id IS
   NULL)`) to a **three-way exactly-one** over suite / case / tag. That constraint is the one thing
   in this migration that can silently admit a nonsense row, so it gets its own assertion rather
   than being trusted to review.
   **Plus M7's lifecycle reduction** (`2026-08-05-organization-model-restatement.md`): the
   `test_plan.status` CHECK drops `ACTIVE` and `CLOSED`, existing rows in either state become
   `APPROVED`, and `closed_at` goes. **Lossy and not reversible** — the header must say that the
   audit log's `plan.started`/`.reopened`/`.closed` events are where that history now lives.
   **Plus M9**: `WIP` joins the seeded `case_status` set.
   No new table, so **no new RLS policy**: the count stays at 61.
3. **Backend**: `PlanLifecycle` and `PlanStatusService` reduced to `DRAFT → IN_REVIEW → APPROVED`,
   with §82's `requireApprovedPlanToStart` and `PlanDeleteService`'s close/delete partition retired
   and their reasoning recorded rather than deleted (M7) — **shipped in 3a, and it took more than
   the plan listed**: the reduction also removes `PlanStatusInput.overrideApprovalGate`, the
   `project_settings.require_approved_plan_to_start` column and its switch on the settings screen
   (a toggle whose rule no longer exists saves and does nothing), the closed-plan freeze on
   `PATCH /plans/{planId}`, and three audit verbs. The contract's `status` enum had to narrow in
   the same commit, which the plan's contract list did not name; the plan `PATCH` accepts both texts and
   sectioned scope items; **`CaseFacts.casesInPlanScope`
   extended with the tag arm and grouped by section** — the existing method, not a new one; the
   tags-in-use query; `PlanDocumentTemplate` deleted with its `POST`; the link/unlink service and
   its three refusals (phase 1's contract).
4. **Frontend**: the plan detail becomes one form — state above the tabs, one Save, the ordered
   pair, per-step partial-failure reporting, the dirty banner naming dirty tabs,
   `useUnsavedWork` registration.
5. **Frontend**: the seven fixed tabs; the Plan tab replacing `PlanEditDialog`; the business
   requirements tab rendering the linked page read-only with link/unlink; the Test artefacts tab
   over the existing register; the two text tabs.
6. **Frontend**: the Test cases tab — named sections, four fill mechanisms, auto-append, trim,
   blank-row drop, deferred creation, and each section showing **what defines it** beside its count.
7. **Tests** — backend for link/unlink, the extended plan-scope resolution over all three arms, the
   widened CHECK constraint, the tags query, and the section round-trip;
   Vitest/RTL for auto-append, trimming, blank-row drop, the dirty banner, and the ordered save with
   an injected failure at step 2.
8. Both verification queue rows, the handoff section, both orientation caches.

### Out, and named

- **No rewrite of existing documents.** There is no reset-to-template and this slice does not invent
  one; retiring the template affects what new plans get, not what old ones hold.
- **Business requirements is one page, not a list** (D4′).
- **The Feature classification dimension is not a fill mechanism** (D8 chose tags). It exists, it is
  seeded per project (`V18`), and using it here is a later call.
- **An epic identifier.** M8: the linked business-requirements page *is* the epic's specification, so
  there is no `epicKey` on the plan. A plan whose epic lives only in a tracker cannot say so, which
  is the named cost.
- **An exclude arm** on tags anywhere (M6), and it is a known gap rather than an oversight.
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
| 2 | ✅ **shipped** — contract: scope texts, `PlanSection`, the tags endpoint, the template `POST` retired; `gen:api`; the mock moved with it (§88's rule) and `planScopeCaseIds` now unions every arm | `npm run build` + lint + `test:run` |
| 3a | ✅ **shipped** — `V32` in full (scope texts, `section_name`/`ordinal`/`tag`, the widened CHECK, M7's status reduction and `closed_at` drop, M9's `WIP` seed) **plus the whole M7 backend reduction**: three plan states, §82's plan gate and its setting and request field retired, the closed-plan freeze gone, `PlanDeleteService`'s partition gone. Contract and frontend moved with the enum in the same commit, because a status the client still offers is a 400 nobody can act on | `./gradlew build` + both frontend gates |
| 3b | Backend: scope texts and sectioned items on the plan `PATCH`, `CaseFacts` extended with the tag arm, the tags-in-use query and its endpoint, `PlanSection.caseCount`, the widened-CHECK assertion | `./gradlew build` |
| 3c | Backend: the plan document's link/unlink service and its three refusals, `PlanDocumentTemplate` deleted with its `POST` | `./gradlew build` |
| 4 | Frontend — the form spine: state above tabs, one Save, ordered writes, partial-failure reporting, dirty banner, `useUnsavedWork` | both |
| 5 | Frontend — the seven tabs: Plan (replacing `PlanEditDialog`), business requirements, artefacts, the two texts | both |
| 6 | Frontend — the Test cases tab: sections, four fill mechanisms, auto-append, deferred creation | both |
| 7 | Handoff section, index row, both orientation caches, verification queue rows | both |

## 7a. Why M7 lands HERE and not in its own slice

The plan lifecycle reduction is not plan-detail work, and the instinct is to give it a slice. It
lands inside §97 for one reason: **§97's Plan tab renders the lifecycle**, phase 3 is already writing
`V32`, and the frontend phases have not started. Building the tab against five states and then
cutting to three is the same screen written twice. M9's seed change rides along because it is one
row in the same migration.

## 7b. What this slice is NOT, after the organization-model restatement

The user's model restatement (2026-08-05) produced two further slices, and neither belongs here:

- **§98 — suites become flat.** `parent_id` and `path LTREE` go. §97 is unaffected **because §96's
  seam absorbs it**: this slice asks `SuiteMembership` to resolve a suite's members and never asks
  how, so flattening changes the seam's body and not its signature.
- **§99 — a run is scoped by suites, cases and tags directly.**

Both are in `docs/plans/2026-08-05-organization-model-restatement.md`. The filing arm
(`test_case.suite_id`) **stays** — §96 as shipped is unchanged by any of this.

## 8. Traps

- ~~**§5a is unresolved and phase 3 cannot be written without it.**~~ → **dissolved** by D7′: with
  sections live, there is one behaviour on the screen rather than two, and the two-group rendering
  is not built.
- **Two meanings of "scope" on one screen.** `plan_scope_item` is the case list; `scopeText` is
  prose. A contract field, a column and a UI label each have to survive a reader who knows only one.
- **`casesInPlanScope` is the method that grows, and §96 already found four silent joins in it.**
  §96's whole finding was that a nullable FK drops rows in four places and every one is silent —
  `casesInPlanScope` was one of them. Adding a tag arm to it is editing the exact method that class
  of defect lives in. **Grep for `JOIN suite` before adding one** — §96's standing rule, and this is
  the slice it was written for.
- **A plan can now change between approval and execution** (D7′). Say so on the screen; the case
  gate still fires at run-create time, but the plan's own approval goes stale silently.
- **The blank row must not satisfy a non-empty check** — §94's refine defect, same shape.
- **Section counts do not sum to the plan's total** — §96's lesson, one slice later.
- **`PlanEditDialog` is deleted, not orphaned.** A dialog nobody opens that still compiles is the
  second implementation `ui-rules` §8 is about; `PlanLifecycle.test.tsx` and `PlanEdit.test.tsx` move
  with it. Same for `PlanDocumentTemplate` and its tests.
- **Progress must keep working for a reader with no write permission at all** (§3).
- **Phases 2–4 are a window in which no plan can be given a document.** The template `POST` is
  retired in phase 2 and the *Link a page* control lands in phase 5, so in between the empty state
  is a sentence and a link into the documentation section. Deliberate — the alternative is a
  contract phase that ships an affordance it cannot render — and recorded here and in browser
  row 88 rather than left for the next reader to find as a regression.
- **M7 deletes states that four slices' worth of reasoning was written about.** `PlanLifecycle`'s
  javadoc, `PlanStatusService`'s audit mapping, `PlanDeleteService`'s partition and §82's edge-vs-
  target finding all argue about `ACTIVE`/`CLOSED`. Strike them through with the reversal, per the
  working agreement — a reader who finds the argument gone assumes it was never made.
- **The tags endpoint is a new `api.*` call site**, so §70's contract-hygiene checks will generate
  against it — expect the frontend test count to move by more than the tests written by hand.
