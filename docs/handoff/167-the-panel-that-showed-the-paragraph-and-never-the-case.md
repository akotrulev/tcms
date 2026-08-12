# §167 — the panel that showed the paragraph and never the case

**2026-08-12 · one phase · `backend/` + contract + both `schema.d.ts` copies + one migration
(`V46`) + one frontend test fixture: no `packages/` source changed.**

§166 closed the larger of the two gaps §165 named and left one behind: **`TestCase.relatedDocuments`
was declared in `openapi.yaml` from M0 and served by nothing**, so a link made from a doc block to a
case — real, creatable since `POST …/blocks/{blockId}/links` shipped in §93 — rendered on the block
and **never on the case**. `CaseDetailPage` read `data.relatedDocuments ?? []` and the traceability
panel said *"Not linked to any specification. Nothing traces this case back to a requirement."*
about every case in the product, including ones linked from the doc page minutes earlier.

## §166's argument for a column does not transfer, and the reason is arithmetic

`V45`'s decision was three columns on `case_health` rather than a `case_last_result` table, because
a last result is one fact and belongs on the row that already answers "has this case ever run."
That argument does not carry over here: **a case has zero or more links, and a column holds one
value.** There is no arrangement in which a list of links is a column on a one-row-per-case table —
the alternative to a table is JSON in a column, which the database cannot index, join or count, and
which every later reader has to parse to answer "how many cases link to this block."

So `case_doc_link` is a new table (`V46`, policy count **64**), and what carries over from `V45` is
the part that mattered: the derived value and the health signals computed from it are written by
**one service in one transaction**, so the panel's per-link `stale` marker and the case's eventual
`OUTDATED_SPEC_CHANGED` chip — rendered on one screen, one panel apart — cannot disagree.

## The seam, mirrored a second time

`DocumentationFacts` is the one file in `repository` that may name a `documentation` table —
`documentation.CatalogueFacts`'s mirror image, and `ExecutionFacts`'s shape a second time.
`repository` and `documentation` may not import each other (plan §3), so each reads rows it holds
ids into through exactly one file, and a second class here naming `doc_link` would end that
property for the most tempting reason there is — that the new query is short.

**The refresh is DELETE-then-INSERT for the whole company, not `ON CONFLICT`.** An upsert can add
and update a row and has no way to remove one, and removal is the ordinary case here: unlinking is a
hard delete, removing a block cascades, deleting a page hides everything on it. A mirror that could
only grow would keep asserting claims somebody explicitly withdrew — the same defect
`BlockLinkService` refuses in `doc_link` itself ("a withdrawn claim that is still in the table still
reads as one to every count over it"), arriving one table later. Both statements run in the caller's
single transaction, so no reader ever sees the gap between them.

**The refresh is now the fifth statement pair in `CaseHealthRecomputeService.recompute()`**, same
transaction as the four health signals. It has to be: the two documentation-derived signals
(`case_doc_link.stale`, and the not-yet-built `OUTDATED_SPEC_CHANGED`) are computed from the rows
this statement writes, and a mirror refreshed by one job and read by another is exactly how the
panel and the chip beside it start disagreeing.

## The first outbox event outside `execution`'s vocabulary, and two producers

`OutboxEvents.DOC_LINKS_CHANGED` (`documentation.links_changed`) is coalesced, for
`RESULTS_CHANGED`'s reason — its handler is a whole-company recompute that takes no arguments, so a
page edit that rewrites nine blocks must not be nine projections. One handler,
`HealthRecomputeOnLinksChanged`, which calls the full recompute rather than the mirror alone: calling
only the refresh is the edit to refuse, because it is precisely how the panel's `stale` marker and
the case's chip start disagreeing.

**Two producers, and the second is easy to miss**: `BlockLinkService` fires the event on link
create/delete, which is expected. `DocPageWriteService.edit` fires it too, because `case_doc_link`
copies a page's title and a block's text — editing the page changes what a case's traceability panel
says exactly as creating a link does. The other three write paths on that class are deliberately
*not* producers: `#delete` answers `409 WouldBreak` when anything links to the page's blocks, so a
page that reaches `softDelete` has no links left to remove from the mirror; `#create` makes a page
nothing links to yet; `#setStatus` moves a column the mirror does not carry.

## `stale` is a content-version comparison on both sides, never `updated_at`

The newest `doc_page_version` against the newest `test_case_version`, falling back to each row's
`created_at` when it has no version yet. `ApprovalFacts` already refused the timestamp spelling for
`STALE_APPROVAL`, and the reason applies here twice over: a save whose content hash is unchanged
writes no version and still moves `updated_at`, and on `test_case` that column is *also* moved by
`assignOwner`, `moveToSuite` and `moveToStatus` — none of which read the case's content. Under the
timestamp spelling, giving a case an owner would forgive every spec change it had ever accumulated.

The comparison is strictly `>`. A page version and a case version written in the same microsecond
are not evidence that anybody read the new text.

**What it does not do: block-level precision.** The contract says "when a doc block changes," and
this compares *page* versions, so editing an unrelated paragraph on the same page marks every link
into it stale too. That is the finest granularity the schema supports — `doc_block` carries no
timestamp and no content hash, and `doc_page_version` snapshots a whole page — so closing it means a
new column on `doc_block`, a writer for it in `documentation`, and its own slice. It errs toward
telling a reader to re-read a spec they did not need to; the opposite error is a case that silently
keeps its coverage claim after the statement it verifies was rewritten.

## No cross-module FK, and no enum re-declared

`page_id` and `block_id` name rows in `doc_page` and `doc_block` with no foreign key — cross-module
FKs are allowed only toward `tenancy`/`identity`/`project`/`storage` (plan §3), and `documentation`
is not on that list. `doc_link.source_id` holds a case id with no FK for the mirror-image reason,
stated in `DocLink`'s own header. `relationship` and `targetKind` are `String` on both `CaseDocLink`
and `DocLinkView`, never an enum: the value sets are `documentation`'s (`LinkRelationship`,
`doc_link`'s `target_kind` CHECK), and re-declaring either here would put a second copy of another
module's value set in a module forbidden to import the first — a value added there would arrive as
an `IllegalArgumentException` on read rather than a compile error.

## The excerpt bound is a second spelling of a rule that already exists

`DocumentationFacts.EXCERPT_LIMIT` (160, collapse whitespace, ellipsis on cut) is the same three
things `reporting.CoverageMatrixReport.STATEMENT_LIMIT` does to the same column, so a reader
comparing the coverage matrix against a case detail meets one excerpt rather than two. It is a copy
because it cannot be anything else — `repository` may not import `reporting`, and a shared constant
would have to live in `api`, which §125 makes a decision rather than an import. **Nothing checks
that the two agree**; the day they disagree the symptom is cosmetic. The one place they differ
deliberately: a wordless block is `NULL` here and `"(empty block at position N)"` there — a blank
cell reads as a rendering fault, an absent panel line reads as nothing, which is what it is.

## The contract changed by `required`, and this time a frontend fixture needed an edit

Same shape as §166: the schema was already `type: object, nullable: true` since M0 with all three
members required, so the server's one rendering path and Jackson's default inclusion always emit the
key. `required` on `TestCase.relatedDocuments` is the one-word change. **Unlike §166, this one
touched a frontend file**: `CaseDetailPage.test.tsx`'s `caseBody()` fixture builds a full `TestCase`
by literal, and `required` turns a missing key into a `tsc` compile error on that fixture rather than
a runtime gap — the fixture gained `relatedDocuments: []`. `CaseDetailPage` and `CaseGrid` needed no
other change: they have rendered `data.relatedDocuments ?? []` correctly the whole time.

## `MEMBER_DIVERGENCE_BY_DECISION`: one entry left by deletion, six arrived

**`TestCase.relatedDocuments` left the map by deletion, not edit** — third time that both-ways pin
has fired on a divergence somebody actually closed (the two `lastResult` entries were the first,
§166). Deleting it *is* the build passing again.

**Six entries arrived with it**: `DocLink.external{Repo,Path,LineStart,LineEnd,CommitSha,Url}`,
CONTRACT-ONLY, paired together. They are visible for the first time only because this slice pulled
`DocLink` into `ContractConventionTest.RESPONSE_RECORDS` by name (`DocLinkView` now serves it) —
before that, `DocLink` was an unpaired schema and the pairing rule had nothing to compare it
against. They are a different kind of divergence than the one that closed: `V1`'s
`ck_doc_link_target` designs two target kinds (`EXTERNAL_GIT`, `EXTERNAL_URL`) that nothing writes,
and `documentation.DocLink` leaves those columns unmapped rather than mapped-and-always-null for the
reason its own header gives ("a mapped null suggests a value somebody could set"). `DocLinkView`
makes the same call one layer up. Nothing about a module boundary blocks them — they record a
designed-but-unbuilt capability, and all six leave together the day an external target gets a
writer.

**`DocBlock.coverage` is the one entry left of §165's original four** — the control, still blocked
by needing two modules `documentation` may not name.

## Tests

`CaseHealthRecomputeTest`, **+12 tests, +1 nested class (`TheDocumentationLinks`)**:

- a link carries the page, the block and the relationship
- every link of a case is mirrored, not merely one
- a long statement is cut at the bound, marked, and put on one line
- a block with no words has no excerpt, rather than an empty one
- a spec edited after the case was authored is stale
- a case with no version at all falls back to when it was created
- a withdrawn link disappears on the next refresh
- a soft-deleted case keeps no links
- a link into a soft-deleted page is not shown
- a page of a sibling project is not shown
- refreshing one company does not delete another's mirror
- a second refresh changes nothing

The "sibling project" test is `DocumentationFacts`' `p.project_id = c.project_id` predicate exercised
directly: RLS bounds the statement to the company, and a sibling project's page is still this
company's, so RLS alone would let it through. The write path already refuses to create such a link,
so this predicate can only ever drop a row a defect produced.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL (6m 58s), 2476 tests / 508 classes / 0 failures / 0 skipped.** §166 was 2464 / 507, so this is **+12 and one new class**. |
| `frontend`: `npm run build` · `npm run lint` · `npm run test:run` | clean (after the `caseBody()` fixture fix) · **0 warnings** · **1214 across 93 files** (unchanged count — a fixture fix, not a new test) |
| `packages`: `npm run build` (in order) · lint · `test:run --workspaces` | clean · clean · **229** (66 + 85 + 78) |
| both `schema.d.ts` | regenerated, **byte-identical to each other** |

**This session picked up mid-slice**: the previous session was interrupted mid-tool-call after
fixing a `CaseHealthRecomputeTest` assertion (`hasSize(160)` → "at most 160", to handle a
trim-before-ellipsis edge case) but before confirming the fix. This session reran the test class
(green, all 12 `TheDocumentationLinks` tests included), then ran the full gate suite from there.

## Queues

**Mutations are RECORDED AND NOT RUN** — §65 in full, same as §166: this slice ships a derivation,
not a check over a check. `docs/verification/backend-mutation-checks.md` gains `§167 DL-1`…`DL-5`.

The browser queue gains `§167 DL-1`…`DL-4`, and **`§165 RC-2` is revised in place rather than
deleted** — same treatment §166 gave `§165 RC-1`: it told the pass to confirm the panel was *empty*,
and now asks for the opposite reading with its id and history intact.

## The next slice

**`DocBlock.coverage`** is the one entry left in `MEMBER_DIVERGENCE_BY_DECISION` that a read model
does not close on its own — it needs two modules `documentation` may not name, and it is the control
§165 already identified: the one argument that was *revised* when its constraint moved rather than
left to expire.

Otherwise unchanged: the browser queue (now carrying `§167 DL-1`…`DL-4` too), `TeamRef` (three
types, §165), `OUTDATED_SPEC_CHANGED`/`UNLINKED` (`documentation`'s cause, and now derivable from
`case_doc_link` rather than from `doc_link` a second time), the read-only-archive decision §159
deliberately did not take, and the inline-object-schema population §165 named on its way out
(`objectSchemas()` deliberately skips them; `lastResult` is one instance, and `DocLink` was another
until this slice pulled it into `RESPONSE_RECORDS` by name).
