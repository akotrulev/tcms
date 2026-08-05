# The test plan detail becomes one document — slice plan (§97)

**Date:** 2026-08-05
**Source:** review of §93 against `docs/Easy leave test plan.pdf`, and the four corrections that came
out of it.
**Status:** planned, not started. **UNBLOCKED as of 2026-08-05** — §96 shipped and
`test_case.suite_id` is nullable, which is the whole of the dependency (see §7).

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

## 1. Decisions taken with the user (2026-08-05)

- **D1 — a section per tab, derived from the document's level-1 headings.** Not a fixed tab set.
  Whatever `heading` blocks at `level: 1` the document has become the tabs, in document order; a
  renamed heading renames its tab and a new one appears as a tab. Nothing is hardcoded, which is what
  makes a document created from the project's Documentation section — rather than from the template —
  work with no special case. This retires §93's `CASE_SECTION` text match as the mechanism *and* as
  the failure mode: there is no longer a magic heading whose renaming strands anything.
- **D2 — no suite when adding a case.** Not a default, not a hidden picker: **none**. This is
  decision 4 of the authoring rework — a suite is a *resolvable collection*, membership is optional,
  and a case reaches a suite through its tags. `test_case.suite_id` goes nullable in **§96**, which is
  why this slice sits behind it (§7).
- **D3 — one Save for the entire plan.** The plan's own fields (name, description, milestone,
  entry and exit criteria, scope), the document's blocks, and the new case rows are one form with one
  Save button and one dirty state. **`PlanEditDialog` is replaced by it.**
- **D4 — the document stays a `DocPage`.** It remains an ordinary page in the project's documentation
  space, and the plan gains the ability to **link an existing page** as its document as well as to
  create one from the template. This is the one genuinely new capability in the slice.
- **D5 — "Test cases" leaves the template.** The section is dropped from `PlanDocumentTemplate`;
  the plan-level tab is where cases live.

## 2. The hard part is that one Save spans three permissions and two lock versions

This is the whole risk of the slice and everything else follows from it.

| What Save writes | Endpoint | Permission | Optimistic lock |
|---|---|---|---|
| Plan fields + scope | `PATCH /plans/{planId}` | `plan:edit` | `test_plan.lock_version` |
| Document blocks | `PATCH /doc-pages/{pageId}` | `doc:edit` | `doc_page.lock_version` |
| New case rows | `POST /cases` ×N | `case:create` | — |

**There is no transaction across these and there must not be one** — they are three modules, and the
document is deliberately `doc:edit` rather than `plan:edit` because it versions as documentation and
is searched as documentation (the contract says so at `/plans/{planId}/document`).

So Save is an **ordered, reported sequence**, not an atomic write:

1. **Create every filled-in case row.** First, for §93's reason, restated and still right: a case is
   the durable half. A case that exists without being in scope is findable and re-addable; a scope
   entry pointing at a case that was never created is not a thing that can exist.
2. **`PATCH` the plan** — fields, criteria, and `caseIds` including the ids just created.
3. **`PATCH` the document page.**

**Partial failure is named, per step, in place.** If step 3 fails the banner says the plan was saved
and the document was not, and the document stays dirty so the retry is one button press. A single
"couldn't save" over three writes, two of which landed, is the failure this design is choosing to
avoid rather than the one it is risking.

**Two 409s, two meanings.** A stale `test_plan.lock_version` and a stale `doc_page.lock_version` are
different sentences and must not share a message. The doc page has a third 409 nobody else has —
*"n links point at content you are removing"* — which §93 made reachable and which lands here now.

**Permissions disable sections, not the Save.** §31: a reader with `doc:edit` but not `plan:edit`
edits the document and sees the plan-fields tab disabled with the reason on it. Save writes the steps
the caller is allowed and reports the ones it skipped. A Save button that is simply disabled for
anyone missing any one of three permissions would make the common case — a tester with `doc:edit`
only — a screen that does nothing.

## 3. The tabs

```
[ Progress ] [ Plan ] [ Business requirements ] [ Test artefacts ] [ Scope ] [ Out of scope ] [ Test cases ]
                     └──── derived from the document's level-1 headings ─────────────────────┘  └─ plan-level
```

- **Progress** — unchanged; today's statistics screen.
- **Plan** — new. Name, description, milestone, entry and exit criteria, suite scope. This is
  `PlanEditDialog`'s contents, on the page.
- **One tab per level-1 heading**, in document order. A tab renders the blocks from its heading up to
  the next level-1 heading: sub-headings, paragraphs and the bullet outline with its indent depth,
  each block still individually editable and still individually linkable (a `doc_link` targets a
  block, which is why one bullet is one block — §93 §4, unchanged).
- **Test cases** — the plan's cases: those in `caseIds`, plus the auto-appending row.

**Legacy documents keep their "Test cases" heading**, because there is no reset-to-template and
rewriting somebody's document on read is worse than a duplicate tab. Rule: **a derived level-1
heading whose text is "test cases" is folded into the plan-level Test cases tab**, its blocks
rendered above the case list. That is §93's text match surviving in exactly one place — as a
*merge* rather than as the mechanism — and its failure mode is now a tab too many rather than a
control that moved.

**The tabs stay mounted, and this reverses a §93 decision.** `PlanStatisticsPage` currently unmounts
the inactive tab with a comment arguing that a draft surviving a tab switch is a draft the author
cannot see they made. Under D3 that argument inverts: the form is the page, and unmounting a tab
would discard what was typed in it. So state lives above the tabs and **the dirty banner names which
tabs are dirty** — which is the thing that makes an off-screen edit visible, and is the reason the
old argument was right about the risk and wrong about the remedy. Progress keeps its own query and
stays unmounted while inactive; it holds no draft.

## 4. The case rows

The step editor's shape, per correction 3, and `CaseEditorPage` is the reference implementation to
follow rather than to re-derive:

- The list always ends in a **blank row**; typing in the last row appends the next one
  (`NO_FOCUS` — the append happens while the author is typing in the row above, so the default
  focus-the-new-row behaviour would move the caret out of the sentence being written).
- **Trim, then drop rows that are entirely empty**, at submit. A whitespace-only title never reaches
  the API.
- **Nothing is created until Save.** No idempotency-key-per-submit problem, because there is one
  submit — which retires §93's `useCreateDraftCase` distinction along with the bar it existed for.
- A row is a **title**. No suite (D2). Tags are the §96 mechanism and belong on the case editor;
  a row here offers the title and a link to write the case up properly.
- Existing cases in scope render as rows too — removable, which writes `caseIds`.

**The blank row must not count.** `CaseSchema`'s "at least one step" refine had exactly this defect in
§94 and it is the same shape here: any "the plan has cases" check must count non-blank rows, or a
permanently-present trailing blank satisfies it.

## 5. Linking an existing page (D4)

New on the contract:

```
PUT    /projects/{projectId}/plans/{planId}/document   { pageId }  → 200 DocPage
DELETE /projects/{projectId}/plans/{planId}/document              → 204
```

`PUT` sets `doc_page.subject_type/subject_id`; `DELETE` clears them and **keeps the page** — it
unlinks, it does not delete documentation. Both are `doc:edit`.

Three refusals, all of which the partial unique index already implies and none of which it explains:

- The page is already another plan's document → **409** naming that plan.
- The plan already has one → **409**. Relinking is `DELETE` then `PUT`, deliberately two steps: a
  silent reassignment is how a plan ends up documenting a different release than its reader thinks.
- The page belongs to a sibling project → **404**. RLS scopes to the *company*, so only an explicit
  project predicate refuses this — §64/§68/§69's family, and `PlanFacts` states it.

The empty state gains a second control beside "Start the document": **link an existing page**.

## 6. Scope

### In

1. Contract: `PUT`/`DELETE` on the plan document; regenerate `schema.d.ts`.
2. Backend: the link/unlink service and its three refusals; `PlanDocumentTemplate` drops its
   Test cases section.
3. Frontend: the plan detail becomes one form — state above the tabs, one Save, the dirty banner
   naming dirty tabs, `useUnsavedWork` registration, per-step partial-failure reporting.
4. Frontend: derived section tabs; block editing inside them.
5. Frontend: the Plan tab, replacing `PlanEditDialog`.
6. Frontend: the Test cases tab with auto-appending rows and deferred creation.
7. Tests — backend for the new endpoints, Vitest/RTL for auto-append, trimming, blank-row drop, the
   dirty banner, and the ordered save with an injected failure at step 3.
8. Both verification queue rows, the handoff section, both orientation caches.

### Out, and named

- **No new migration.** `V29`'s subject columns already carry the link; `PUT` writes the columns that
  exist. Flyway head stays where §96 leaves it and the RLS policy count is untouched by this slice.
- **No rewrite of existing documents.** The template change affects documents created after it. There
  is no reset-to-template and this slice does not invent one.
- **`DocBlock.coverage` stays absent** — still nothing here can price a link (§93 §3).
- **Export.** Printing the plan as the PDF is still §88's renderer's future job.

## 7. Sequencing — this slice sits behind §96

**D2 is §96's decision 4.** Until `test_case.suite_id` is nullable, a case cannot be created without
a suite, and phase 4 below cannot be built as specified — only faked with a default suite, which is
the thing the user explicitly rejected.

~~Current position: §95 is phase 1 of 4, in flight. §96 is planned and not started.~~ →
**Both have shipped** (2026-08-05). `test_case.suite_id` is nullable, `TestCaseInput.suiteId` is
optional, and the case grid can reach an unfiled case, so **phase 4 below can be built as
specified** rather than faked with a default suite. Option **A** was taken and needs no further
scheduling call.

Two ways to run it, and it is a scheduling call rather than a technical one:

- **A — after §96.** §95 → §96 → §97. Everything lands as written, once.
- **B — split.** Phases 1–3 and 5 (the form, the tabs, the Plan tab, linking a page) do not touch
  suites and can land before §96; phase 4 (the case rows) follows it. Gets the visible half of the
  correction out sooner, at the cost of a slice that is half-shipped across two others.

**Recommended: A.** The correction that motivated all of this is "don't make me pick a suite", and B
delivers everything except that.

## 8. Phases

Each ends with its gate (`./gradlew build` from `backend/`; `npm run build` + `npm run lint` +
`npm run test:run` from `frontend/`) and its own commit. Browser passes and mutation checks are
**deferred and batched** per §65 — each phase appends its rows to `docs/verification/*.md` and runs
none of them.

| # | Phase | Gate |
|---|---|---|
| 1 | Contract — `PUT`/`DELETE` plan document, regenerate `schema.d.ts` | `npm run build` |
| 2 | Backend — link/unlink service, the three refusals, template drops Test cases, endpoint tests | `./gradlew build` |
| 3 | Frontend — the form spine: state above tabs, one Save, ordered writes, partial-failure reporting, dirty banner, `useUnsavedWork` | both |
| 4 | Frontend — derived section tabs + block editing; the Plan tab replaces `PlanEditDialog` | both |
| 5 | Frontend — the Test cases tab: auto-append, trim, deferred creation, no suite **(needs §96)** | both |
| 6 | Handoff section, index row, both orientation caches, verification queue rows | both |

## 9. Traps

- **Every existing block goes back with its `id`.** A block sent without one is deleted and recreated,
  and every `doc_link` pointing at it is destroyed silently. `DocPageEditorPage`'s header is the
  written-down version of this and its `blockId`-not-`id` naming exists so the `useFieldArray`
  shadowing mistake is unavailable rather than merely discouraged. This form must use the same name.
- **Two lock versions, two 409s, two sentences.** Sharing a message makes a stale plan and a stale
  document indistinguishable.
- **Save must report per step.** Steps 1 and 2 landing while 3 fails is the ordinary failure, not the
  exotic one.
- **The blank row must not satisfy a non-empty check** — §94's refine defect, same shape.
- **A tab derived from a heading can be renamed while dirty.** The tab list is derived from form
  state, not from the server's copy, or the tab the author is typing in disappears mid-edit.
- **`PlanEditDialog` is deleted, not orphaned.** A dialog nobody opens that still compiles is the
  second implementation `ui-rules` §8 is about; `PlanLifecycle.test.tsx` and `PlanEdit.test.tsx`
  move with it.
- **Progress must keep working for a reader with no write permission at all.** The page is a report
  first — it is what gets pasted into a release thread — and turning it into a form must not put a
  disabled editor in front of someone who came to read numbers.
