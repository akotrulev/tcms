# The test plan document — slice plan (§93)

**Date:** 2026-08-04
**Source:** `docs/Easy leave test plan.pdf`, handed over as *"an example of how a test plan should be
structured — I want the same in our system (ui+be)"*.
**Status:** built and unverified — both queues hold its rows and neither has been run (§65).

---

## 0. What the example actually is

The PDF is a five-section narrative document with a deeply nested scenario outline:

| Section | Content |
|---|---|
| Title | *Easy leave — Test plan* |
| **Business requirements** | a paragraph, then a bulleted list of the product's functionalities |
| **Test artefacts** | test environments (a URL), then accounts (email + team + **plaintext password**) |
| **Scope** | four bullets of prose |
| **Out of scope** | one bullet of prose |
| **Test scenarios** | headed areas (*Login page*, *Home page → Personal leave section*, *Teammates leave section*, *Language switch*), each an outline three levels deep: `1.` → `a.` → `i.` |

## 1. What TCMS has, and the gap

| PDF section | TCMS today | Verdict |
|---|---|---|
| Title | `test_plan.name` | present |
| Business requirements | `test_plan.description`, one flat TEXT | **no structure** |
| Test artefacts | — | **absent** |
| Scope / Out of scope | `plan_scope_item` is *suite and case ids* | **different meaning.** The stored scope is what will be *executed*; the PDF's scope is a sentence about what will be *tested*. Both are wanted; neither substitutes for the other. |
| Test scenarios | scope items point at real cases | **no narrative outline.** A plan cannot state a scenario before the case exists, which is what the PDF does on every line. |

So the deliverable is a **narrative document body on the test plan**, and the scenario outline is its
hard part.

## 2. Decisions taken with the user (2026-08-04)

- **D1 — the document is a `DocPage`.** Reuse `documentation`'s block model rather than a new
  `test_plan_section` table. Chosen over a dedicated table and over markdown columns.
- **D2 — a scenario node may optionally name a case or a suite.** Unlinked nodes read exactly like
  the PDF; a linked node gives the plan real coverage.
- **D3 — no secrets.** Test artefacts records environments and account *identifiers*, and a
  free "where the secret lives" note. The product never stores a password.

## 3. The architectural problem D1 creates, and its answer

`execution` may not depend on `documentation` — `ArchitectureRules.FOUNDATION_MODULES` permits a
cross-module dependency only toward `tenancy` / `identity` / `project` — so `test_plan.doc_page_id`
is not a column anybody may add. The link must therefore be held **on the documentation side**:

> **`doc_page.subject_type` / `subject_id`, opaque, no FK.**

This is §76's Review record arriving a second time, and for the same reason: *one* record type serves
several artifact kinds precisely because its subject is a triple nobody joins on. `subject_type`
starts as a one-value `CHECK` (`'TEST_PLAN'`), which is a place for the next kind rather than a
generalisation nobody asked for.

`documentation` may already read `test_plan` — **`PlanFacts` exists and does exactly this**, for the
artifact register's plan-scope check, under a header explaining that a module needing a sibling's
fact holds an id into it and reads it through exactly one file. The subject check is the same
question that file already answers.

## 4. The other half the contract already specified

`openapi.yaml` declares `DocBlock.coverage` and `TestCase.relatedDocuments`, and **both are
deliberately unserved**: `DocBlockView`'s header states that *"nothing can create a `doc_link`"* and
that a zeroed marker would put the words "Not tested" on every paragraph in the product on the
strength of a question nobody has asked. D2 is the question. Building the `doc_link` writer lights
up two read paths that have been waiting for it since the mock era.

`doc_link.source_type` already admits `'TEST_CASE'` and `'SUITE'`, and `target_kind = 'DOC_BLOCK'`
already targets `target_block_id`. **No `doc_link` DDL changes.** The direction is the one the
schema chose: the *case* is the source and the *scenario block* is the target — "this case verifies
this statement" — which is also why `TestCase.relatedDocuments` reads the way it does.

### The consequence for the outline: one bullet is one block

A `doc_link` targets a **block**. So a scenario node that can be linked must *be* a block, not a line
inside one. Hence one `list` block per bullet, and the outline's depth carried on the block.

`BlockContent` already has a `level`, used for heading depth, and both the contract and
`PageContent.validBlocks` currently **refuse** it on every other type. This slice widens that to
`list`, where it means **indent depth**. That is a real contract change and it is written down as
one; the alternative — encoding `1. / a. / i.` into the block's own text — makes the outline
unreadable to the server, unlinkable, and unformattable, and the PDF is three levels deep on most of
its pages.

## 5. Scope of the slice

### In

1. `V29` — `doc_page.subject_type` / `subject_id` + the paired-nullability `CHECK` + a partial unique
   index (one live document per subject).
2. The plan-document endpoints: create-from-template, read, and edit through the existing
   `DocPageWriteService` path.
3. `PlanDocumentTemplate` — the PDF's skeleton as blocks, seeded on create.
4. `level` on `list` blocks — contract, validation, storage, rendering.
5. The `doc_link` writer and its delete, gated on `doc:edit`, with the source validated against the
   project through a new `CatalogueFacts` (reads `repository`, sibling to `PlanFacts`).
6. Frontend: a **Document** tab on the plan page — the outline rendered and edited in place.
7. **Adding a test case from the document** (added mid-slice at the user's request): a title and a
   suite create a `DRAFT` case, a bullet, and the link between them. The section is named
   **Test cases**, not "Test scenarios", because a bullet in it *is* a case.
8. The two verification queue rows (§65 standing policy), the handoff section, and both orientation
   caches.

### Out, and named

- **`DocBlock.coverage` stays absent.** Its five required fields include `passed` / `failed` /
  `notRun`, which need `test_execution` joined to `test_case` — two modules `documentation` may not
  name, and §81's rule puts a query naming three modules' tables in `work`, not here. `linkedCases`
  and `caseKeys` become answerable in this slice and the other three do not, and the schema marks all
  five required, so serving a partial object is not available either. The header's argument is
  updated rather than deleted: it is no longer *"nothing can create a link"* but *"nothing can price
  one"*.
- **No plan-document versioning of its own beyond `doc_page_version`.** The document versions as a
  page; `test_plan_version` does not learn about it. This is the divergence the storage decision was
  taken with eyes open about, and it is recorded here so the next reader does not think it was
  missed.
- **`TestCase.relatedDocuments` is still not served.** It was in scope and was cut when the
  case-creation request arrived: serving it needs a fourth `*Facts` seam in the opposite direction
  (`repository` reading `doc_link` + `doc_page` + `doc_block`), which is a slice of its own and not
  the deliverable the plan document is. The links are now real, so the read has something to return
  the day it is written — which it did not before.
- **Export.** §88's `HtmlReportRenderer` is the obvious future home for "print this plan as the
  PDF" and this slice does not build it.
- **No test coverage for the new endpoints.** `PlanDocumentEndpointTest` and the link tests are not
  written. The full suite is green, which means nothing about this slice's own behaviour, and the
  mutation queue's rows are what say so.

## 6. Phases

Each phase ends with its gate (`./gradlew build` from `backend/`, `npm run build` + `npm run lint`
from `frontend/`) and its own commit. Browser passes and mutation checks are **deferred and batched**
per §65 — each phase appends its rows to `docs/verification/*.md` and runs none of them.

| # | Phase | Gate |
|---|---|---|
| 1 | Contract first — `openapi.yaml`: subject columns' effect on `DocPage`, `PlanDocument` endpoints, `DocBlockInput.level` widened to `list`, `DocLink` write schemas. Regenerate `schema.d.ts`. | `npm run build` |
| 2 | `V29` + `DocPage` subject mapping + `PlanFacts` subject check + `PlanDocumentTemplate` + create/read endpoints | `./gradlew build` |
| 3 | `level` on `list` — `PageContent.validBlocks`, `BlockContent`, `DocBlockView`, and the edit path | `./gradlew build` |
| 4 | `doc_link` writer + `CatalogueFacts` + `TestCase.relatedDocuments` | `./gradlew build` |
| 5 | Frontend — mock handlers, `api/queries/`, the plan Document tab (view + edit), outline rendering | `npm run build` + `npm run lint` |
| 6 | Handoff section `docs/handoff/92-*.md`, index row, both orientation caches, verification queue rows | both |

## 7. Traps this slice must not walk into

- **`V29` writes an RLS predicate only if it creates a table — it does not.** `doc_page` is already
  policied; adding columns changes no policy and the `SchemaConventionTest` count stays **59**.
- **The unique index must be partial on `deleted_at IS NULL`**, or deleting a plan's document makes
  the plan permanently document-less.
- **`level` widening must not make it optional on `heading`.** The rule becomes *required on
  `heading`, optional on `list`, refused on the rest* — three arms, not two.
- **A `doc_link` source must be validated in the caller's project**, not merely in the company:
  `findById` is RLS-scoped to the company and would accept a sibling project's case. §64/§68/§69's
  family, and `PlanFacts`' own header states it.
- **The template is seeded once, on create.** Re-running it against an edited document would
  overwrite somebody's work; there is no "reset to template".
