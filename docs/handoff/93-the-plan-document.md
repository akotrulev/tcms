# §93 — The test plan document: the half a plan is mostly made of

**Date:** 2026-08-04 · **Plan:** `docs/plans/2026-08-04-test-plan-document.md`
**Status: BUILT AND UNVERIFIED.** Both queues hold this slice's rows and neither has been run (§65),
and — separately and more seriously — **the new endpoints have no tests of their own**. See §6.

## 0. The numbering

The commits landed as **§92** and the section is **§93**. §92 was already taken by *saved report
definitions*, which was committed before this slice started and which this session did not read the
index for until phase 4. The source comments and the plan file were corrected; the two commit
messages could not be, and say §92. Nothing else in the repo points at the wrong number.

## 1. What was asked, and what the example actually was

A test plan PDF was handed over as *"an example of how a test plan should be structured — I want the
same in our system (ui+be)"*. It is five headed narrative sections and a three-deep outline:
business requirements, test artefacts (environments and accounts — **with plaintext passwords**),
scope, out of scope, and a scenario outline.

TCMS stored **none of that**. `test_plan` holds the executable half — milestone, entry and exit
criteria, the suites and cases in scope — and `description` is one flat column. `plan_scope_item`
looks like the PDF's "Scope" and is a different thing entirely: it says what will *run*, where the
PDF says what the team undertakes to *test*.

Three decisions were taken with the user before any code:

- **The document is a `DocPage`**, reusing `documentation`'s block model. Chosen over a dedicated
  `test_plan_section` table and over markdown columns.
- **A bullet may name a case or suite.**
- **No secrets.** Test artefacts records identifiers and where the credential lives. The product
  stores no password.

A fourth arrived mid-slice: **the section is "Test cases", not "Test scenarios", and a bullet in it
can create one** — a title and a suite, filed as a `DRAFT`, written up later.

## 2. The link had to live on the documentation side

`execution` may not depend on `documentation` and `documentation` may not depend on `execution` —
plan §3, enforced by ArchUnit — so **neither side may hold a foreign key into the other**.
`test_plan.doc_page_id` is not a column anybody may add.

`V29` puts the reference on `doc_page` as an opaque `subject_type` / `subject_id` pair with no FK,
which is §76's Review subject arriving a second time for the same reason. `PlanFacts` — which has
read `test_plan` from this module since the artifact register — answers whether the plan is real.

Two things about `V29` are worth carrying forward:

- **The unique index is partial on `deleted_at IS NULL`.** A page's delete is soft, so a plain unique
  index would make a deleted document permanently block its plan from having another, surfacing as a
  duplicate-key 500 naming a row the user cannot see.
- **It creates no table**, so the `SchemaConventionTest` policy count stays **59** and `V30` is next.

## 3. `doc_link` got its first writer, four years after `V1` designed it

This is the finding of the slice, and it was found rather than planned: the contract already
specified `DocBlock.coverage` and `TestCase.relatedDocuments`, and `DocBlockView`'s own header said
*"nothing can create a `doc_link`"*. `DocPageWriteService`'s two `409`s — refusing to remove a block
or a page something links to — **could not fire on any real request**. All of that was waiting for a
writer that no slice had scheduled.

`BlockLinkService` is it. Consequences:

- **`DocBlock.links` is served** — which cases claim this statement, which this module knows exactly.
- **`coverage` is still absent and its reason has MOVED**, written into the field rather than
  deleted: the obstacle is no longer that nothing can create a link but that nothing here can
  **price** one. `passed`/`failed`/`notRun` are `test_execution` joined to `test_case`, two modules
  away, and the schema marks all five fields required so the two answerable ones cannot ship alone.
- **The two `409`s are now reachable**, and the mock counts real links alongside the fixture's
  `coverage.linkedCases` stand-in so the branch stays exercised on the pages built to exercise it.
- `LinkSourceType` is **narrower than the column** — `DEFECT` and `DOC_PAGE` are legal in `V1`'s
  `CHECK` and have no writer. The column stays wider deliberately; a `CHECK` tightened and loosened
  again is a constraint nobody trusts.

## 4. One bullet is one block, and that is why `level` grew a third arm

A `doc_link` targets a **block**. So a scenario a case can verify has to *be* a block rather than a
line inside one — which forces one bullet per block, which forces the outline's depth onto the block.

`level` was **required on `heading` and refused on everything else**. It is now required on
`heading`, **optional on `list`** (indent depth, default 1), refused on the rest. Three arms, not
two. The alternative was `1.` / `a.` / `i.` typed into the text, which cannot be indented,
renumbered, or linked.

## 5. Adding a case is three client requests, in an order that matters

`documentation` may not write `test_case` and `repository` may not assemble a document, so a
single-endpoint version needs a domain event (this product has zero) or a fourth module. The SPA does
it in three: **create the case, add the bullet, link them.**

The case is created **first** because it is the durable half. If step 2 or 3 fails, the case exists
and is findable by title; the reverse order leaves a bullet that *looks* like a test case and is not
one, which is precisely what this screen exists to prevent. The failure message says so, in place.

**`useCreateDraftCase` is not `useCreateCase`, and the difference is the idempotency key.**
`useCreateCase` holds one key per mounted editor — one intent, one case, retry-safe. This bar is the
opposite shape: one mount, ten cases typed in a row. Reusing that hook would send the same key every
time and the server would return the **first** case for every subsequent title, silently. A key per
submit, therefore; the accepted cost is that a retry of one failed add is unprotected, which is
visible in the bar where the duplicate would be visible in the list.

**Live trap: the "Test cases" section is found by heading text** (`PlanDocumentPanel.CASE_SECTION`).
There is no marker on a block saying cases go here and inventing one would mean a block type and a
migration. The failure mode is why it is tolerable: rename the heading and new cases go to the end of
the document with an info alert, rather than disappearing.

## 6. What is NOT done, and must not be read as done

- **No tests for any of it.** `PlanDocumentEndpointTest` does not exist; neither do link tests. The
  full suite is green and that says nothing whatever about this slice. This is the largest gap.
- **`TestCase.relatedDocuments` is still unserved.** It was in scope and was cut when the
  case-creation request arrived — it needs a fourth `*Facts` in the opposite direction. The links are
  real now, so the read has something to return the day somebody writes it.
- **The document versions as a page, not as a plan.** `test_plan_version` does not learn about it.
  That is the divergence the storage decision was taken with eyes open about.
- **Neither verification queue has been run.**

## 7. Files

`V29__doc_page_subject.sql` · `DocSubject` · `DocSubjectType` · `PlanDocumentService` ·
`PlanDocumentController` · `PlanDocumentTemplate` · `DocLink` · `DocLinkRepository` ·
`LinkSourceType` · `LinkRelationship` · `BlockLinks` · `BlockLinkService` · `BlockLinkView` ·
`BlockLinkInput` · `CatalogueFacts` · (edited) `DocPage` · `DocPageRepository` · `DocPageService` ·
`DocPageWriteService` · `DocPageController` · `DocPageSummaryView` · `DocPageView` · `DocBlockView` ·
`PageContent` · `PlanFacts`.

Frontend: `PlanDocumentPanel.tsx` · (edited) `PlanStatisticsPage.tsx` · `api/queries/documentation.ts`
· `api/queries/cases.ts` · `api/queries/keys.ts` · `api/types.ts` · `mocks/handlers/documentation.ts`.
