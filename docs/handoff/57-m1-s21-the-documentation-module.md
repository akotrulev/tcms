# §57. The `documentation` module — the biggest MSW-only surface, and the block id that must survive an edit

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the twelfth slice since §45.

`GET`/`POST /doc-pages`, `GET`/`PATCH`/`DELETE /doc-pages/{id}`, `PUT /doc-pages/{id}/status`, and
**`V18`** — the ninth Modulith module, and the first one whose central design decision is about
*not breaking something the product cannot yet create*.

## Why this one

Three sessions in a row recorded it. §54, §55 and §56 each ended with "`documentation` and
`reporting` are still MSW-only — `documentation` needs a **writer** to be honest", and §56 said so
in its opening: the reads had been in the contract since the mock era, serving a table nothing could
write to, so every provisioned company's documentation space could only ever render its empty state.

Two independent pointers said the module was next rather than merely available. `DisplayKeys` in
`tenancy` already reserves `DOC_PAGE`, and its javadoc names this module by name as the caller that
must not have to import `repository` to number a page. `TestCaseDetailView`'s javadoc records
`relatedDocuments` as deliberately omitted **because the `documentation` module does not exist**.
Both were written by earlier slices for this one.

## The decision the module is built around

`V1`'s comment on `doc_block.id` is unusually emphatic, and it is the whole design: the id is
immutable, generated at creation, never derived from content, because `doc_link` targets
`(page_id, block_id)`. Requirements *are* doc blocks — there is no `requirement` table (ERD,
resolved #18) — so a block id is what the traceability of the entire product hangs on.

The obvious implementation of "save the page" is to delete every block and insert the submitted
ones. **It passes every test anybody would think to write**: the page reads back exactly as
submitted, the text is right, the order is right, the types are right. It also re-issues every id,
so every case that verified a paragraph on that page now verifies nothing — and *nothing reports
it*, because `doc_link.target_block_id` is `ON DELETE CASCADE`, so the links are gone rather than
dangling. There is not even a stale row to find.

So identity is decided in one place, `PageContent`, from one rule: **a submitted block carrying an
id is the block with that id.** Not the block at that position, not the block with matching text —
both re-identify on edit, which is the same failure with extra steps.

The three cases, and the third is the one worth arguing about:

- **id present, on this page** → rewritten in place. Text, type and depth may all change; the id
  does not. Fixing a typo in a heading is the motivating case and it must cost nothing.
- **id absent** → new block, server issues the id. This is what makes "the client sent no id" and
  "this is new" the same fact rather than two that can disagree.
- **id present, NOT on this page** → **400**, never a silent create. Creating under the submitted
  id would mean a client that believes it edited block X has created block Y; issuing a fresh id
  instead is the same thing with the evidence removed.

A block *omitted* is deleted — and that path counts the `doc_link` rows targeting it first, refusing
with 409 **before anything is written**. A refusal raised after the rewrites have flushed is one
whose correctness depends on the rollback, and no reader should have to reason about that to know a
link is safe.

**The guard is built now, when nothing can create a link.** Deliberately: a rule added after the
data exists has to be reconciled with whatever grew without it. The test inserts a `doc_link` by
hand to drive the branch, and so did the live pass.

## The client is the only thing that can prevent it, and a framework quirk is how it breaks

The server refuses an id it does not recognise. It cannot refuse an id that was **never sent** —
from its side that is indistinguishable from a new block, which is exactly what the user did when
they added one. So the SPA is load-bearing here in a way it usually is not.

And the specific way it breaks is not a typo. `useFieldArray` puts **its own `id`** on every entry
of `fields`, a render key with no relation to the data, which *shadows* a data property called `id`.
Reading `field.id` looks completely right, submits a fresh uuid for every block on every save, and
passes any test that only checks the text came back. The form field is therefore named `blockId`,
which makes the mistake unavailable rather than merely discouraged, and
`DocPageEditorPage.test.tsx` asserts the **PATCH payload** rather than the rendering — the only
place it is visible. Dropping the id reddens three of its seven tests.

## Two permissions, so two endpoints

`doc:edit` writes content; `doc:publish` moves the publication state, and Test Engineer holds the
first and not the second (roles spec §A2). They are `PATCH .../doc-pages/{id}` and
`PUT .../doc-pages/{id}/status` rather than one endpoint branching on which fields the body carried.

A gate that depends on the payload is one the SPA cannot render a disabled control for, and one
whose 403 no client can predict — the two things §31 exists to prevent. `status` is therefore not on
`DocPageInput` at all, which is also §6g's rule for the third time: a state an ordinary create could
set is an approval its author granted themselves.

**Delete asks `doc:edit`, not a permission of its own** — the opposite of the
`suite:manage`/`suite:delete` split. That split exists because deleting a folder reaches *other
people's cases* through one action with no preview; a page has no subtree of anybody else's work
under it, and its own children are refused rather than cascaded. Copying the split anyway would put
a permission in the catalogue the roles spec does not have.

## `coverage` is absent, not zeroed — and the live pass found the one place that was not honoured

The inline coverage marker is sourced from `doc_link`, and nothing can create one. So `DocBlockView`
omits the field entirely, the same call `TestCaseDetailView` makes for `relatedDocuments` at the
other end of the same edge.

It matters more here than it usually would, and the live pass proved it: a zeroed marker does not
read as "unknown", it reads as **"no test case verifies this statement"** — which is what the SPA
renders it as, in the words "Not tested". Serializing it would put a specific and alarming claim on
every paragraph of every specification in the product on the strength of a question nobody asked.

The per-block marker already handled absence correctly. **The page-level summary chip did not**, and
it read `1 of 1 unlinked`, in amber, on a page whose only heading demonstrably *had* a `doc_link`.
Absent counted as zero, one layer above the decision. That is the §47/§49 shape again — a derived
display asserting more than the data supports — and the fix is one predicate.

## `V18`, and the seed row that had no writer

`doc_page.space_id` is `NOT NULL REFERENCES doc_space(id)` and **nothing had ever created a
`doc_space`**. `V2`'s `tcms_seed_project_defaults` seeds the Feature dimension, the six case
statuses and the Default configuration, and stops there — correct for as long as nothing wrote a
page, which is exactly how long it was invisible. This is the documentation module's `V5`: a missing
seed row found by the first code that needed it rather than by reading the schema, and worth naming
as a shape.

Two things the migration had to get right:

- **The function is reproduced in full**, because `CREATE OR REPLACE` replaces the whole body:
  anything omitted stops being seeded, silently, for every project created from then on.
- **The backfill sets a company context per company.** `doc_space` is under `FORCE ROW LEVEL
  SECURITY`, which covers the owner migrations run as, so a plain `INSERT … SELECT FROM project`
  would read nothing, write nothing and report success — V7's zero-rows-in-production shape. V15's
  loop, for V15's reason, including the reset to `''` at the end.

No `UNIQUE (project_id)` index: the ERD models a project as eventually having several spaces, and an
index saying "one" would have to be dropped by the migration that adds the second.
`DocSpaceRepository.requireForProject` throws a sentence naming `provision-company` instead — the
same contract `DisplayKeys` has with `key_sequence`.

## Two smaller decisions

**`DocBlockType` is the one lower-case enum in the product.** The contract has spelled block types
`heading · paragraph · …` since the mock era and the SPA branches on those strings. That is a real
asymmetry in the contract, and the wrong place to fix it is in the slice trying to make the endpoint
real — so the wire form is authoritative, and `DocBlockTypeConverter` stores the *same* lower-case
string rather than letting `@Enumerated(STRING)` write `HEADING` while the API serves `heading`. Two
spellings of one value in two layers is what somebody eventually writes a query against and gets
zero rows from.

**A retitle authors a version; publishing does not.** The opposite of `SuiteVersions`, and not an
inconsistency: a suite version is defined as *composition*, so a folder's name is outside it by
definition, while a page version is the page and its title is the first line a reader sees. A
publication is nobody authoring anything, and a history reading "v4 — published, v5 — unpublished"
is one whose numbers no longer count edits. That decision belongs to `audit_event`.

**No third `ApiProblem`.** `repository.ApiProblem`'s own header says the second copy should have
been extracted to a `shared` module — but `shared` would be a fourth foundation module, which
`ModuleStructureTest` treats as a decision rather than an import, and that is not this slice's to
take quietly. This module uses Spring's `ProblemDetail`, the call `work.WorkItemController` already
made.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL
           1067 tests, 0 failures, 0 skipped      (1021 + 46)
frontend:  npm run build   → ✓ built
           npm run lint    → oxlint, exit 0
           npm run test:run→ 25 files, 418 tests passed   (411 + 7)
live:      docker compose up --build
           V18 applied to an ALREADY-MIGRATED database: v17 → v18, backfilling 8 projects
           LIVE mode browser pass — see below
```

**Mutation-checked rather than watched to pass.** Delete-and-recreate in `PageContent.reconcile`
reddens six backend tests including all three identity ones; collapsing `doc:publish` into
`doc:edit` reddens exactly one, the one written for it. On the frontend, dropping the id from the
PATCH payload reddens three of seven.

**Live pass, driven as a real Company Admin, verified in psql rather than on screen:**

| Claim | Evidence |
|---|---|
| `V18` migrates in place | `Current version: 17` → `now at version v18`, `re-seeded 8 existing project(s)` |
| Every project has a space | 8 of 8 rows joined to a `Documentation` space |
| Key allocated from the counter | `DOC-1` on the first create |
| Block types stored in the wire spelling | `heading`, `paragraph` in `doc_block.block_type` |
| **An edited block keeps its id** | `Full refudns` → `Full refunds`, id `371efafb…` byte-identical |
| **The link survives the edit** | the `doc_link` row still present afterwards |
| Removing a linked block is refused | 409 rendered on the form, both blocks still in the database |
| The refusal writes nothing | page still v3, `lock_version` unchanged, no audit row |
| Publishing authors nothing | `status=PUBLISHED`, `current_version_no` still 3, still 3 version rows |
| Audit trail | `created`, `edited` ×2, `status_changed` — and none for the refused save |

**Three defects the live pass found, none of them visible to a gate:**

1. **An empty documentation space rendered a permanent loading skeleton** beside "No pages". A query
   that is not enabled is *pending*, which `DataRegion` correctly renders as loading; on a project
   with pages that lasts a frame because the effect redirects, and on a project with none it lasts
   forever. Invisible until this slice, because every mock fixture ships pages — nobody had ever
   seen a real empty space.
2. **The coverage chip claimed "1 of 1 unlinked"** on a page that had a link. See above.
3. **A delete that succeeded reported "Not found"**, with a global error notification, while the
   tree beside it correctly showed the page was gone. `removeQueries` on a key that still has a
   mounted observer does not forget the data — it makes that observer *refetch*, and the row is
   gone. Fixed at the cause, and the redirect is now declarative: "the page you are reading no
   longer exists" has three causes (you deleted it, a colleague did, you followed an old bookmark)
   and a callback on the delete fixes exactly one.

**Not driven live:** the `doc:publish` refusal for a Test Engineer (no second persona was signed in;
the backend test covers it, and the mock cannot show it either — see below), and the stale
`If-Match` 409.

## The mock is honest about one branch and cannot show another

The handlers implement the gate, the level rule, the unknown-id refusal, the `If-Match`
precondition and the link refusal — the last one keyed off the fixture's own
`coverage.linkedCases`, so the 409 branch is reachable in the mode the app is developed in. A
handler that always succeeded is how §55's three-state defect got as far as a live pass.

**What the mock cannot show is the Test Engineer split.** Its two projects are Test Lead (holds
both) and Viewer (holds neither), so `doc:edit` enabled beside `doc:publish` disabled — the shape
the two-endpoint decision exists for — renders in neither. Recorded rather than fixed: the Viewer
project was seeded that way deliberately in §31, and changing it to prove this would remove the
persona that proves everything else.

## Not done

- **`doc_link` still has no writer**, so no block has a `coverage` marker and no case has a
  `relatedDocuments` entry. **This is the named next slice** and it lights up both ends of the same
  edge at once — the marker here and the traceability panel the case detail has rendered since
  Phase 1. This module already refuses to break a link it cannot create, so the invariant is in
  place before the data is.
- **`reporting` is still MSW-only** — `/reports`, `/reports/generate`, plus `cases/bulk` and
  `shared-steps`. `documentation` is now off that list.
- **`doc_page_version` is written and never read.** No diff view; §6j's history accumulates from
  today so that the slice which builds one has real rows.
- **Page moves are not supported** — a differing `parentId` is a 400, as it is for a suite, and for
  the same reason: reparenting rewrites every descendant's `path`.
- **The third of `company:members` — membership display names — is still not possible** (§56).
  `CompanyMembership.rename()` still has no caller.
- **Teams still have no write endpoints** (§39, §45–§56).
- **The symmetric backend check — a declared operation no handler implements — is still not built**
  (§53–§56). It would now list a shorter surface than it would have this morning.
- **No fixture-id registry** (§54, §55, §56). This slice hand-checked `dc…` against every prefix in
  `src/test/java` before writing, which is the fourth session to do that by hand.
- **A case that moves between suites still takes its whole execution history with it** (§52).
