# §235 — The vector that had no reader

**C3 of `docs/audits/2026-08-15-flow-review-and-improvements.md`**, slice 3 of the C run, in the
audit's §3.6 order. Two phases: the contract and the backend (`fbc9c3a`), then the search box
(`2deae11`). **No migration, no permission added or moved, no new key** — every column this slice
reads has been in `V1` since M0 and every trigger that fills it has been in `V2` just as long.

The decision on record was:

> Ship docs search: a search endpoint over the existing tsvector + a search box in the documentation
> space. Wider (cases/plans) search is not part of this decision.

Both halves shipped. The wider search was not started and is not begun by accident either — see
*What was deliberately left where it is* below.

---

## 1. What was actually wrong

Four tables carry a `search_vector`. All four are GIN-indexed. `V2` maintains all four, and the
`doc_page` one is the most expensive of them: it could not be a generated column, because a page's
text lives in `doc_block` rows and a generation expression may only read its own row, so it is
maintained by **two** triggers pushing from both sides through one shared recompute function whose
own `COMMENT ON` explains why the recompute is shared.

Nothing queried any of it.

That is not merely unused machinery. `PlanDocumentPanel.tsx:37` justifies its entire design —
why the in-place editor was deleted, why prose is edited in the documentation space and not on the
plan — on the document being *"found by everybody who searches documentation"*, and its empty state
at `:115` tells the reader to write the page in the documentation section first so it will keep
*"its place in search"*. The product told people to rely on a capability it did not have. Three
repositories, meanwhile, carried comments explaining that they deliberately did **not** use their
vector and that the reason would expire *"when the `@@` path lands"*.

This is the slice where it lands, for documentation, and the three comments are answered rather
than left to be read as still-pending.

---

## 2. The decision of the slice: native SQL and the tenancy invariant

`CaseSpecifications#keyOrText` refuses the `@@` operator and gives two reasons. The first is the one
that had to be settled before a line was written, because tenancy is the invariant everything else
rests on:

> Criteria cannot express `@@` without registering a custom function, so using it here would mean a
> native query — which drops the Hibernate company filter and leaves RLS as the only boundary, the
> single-point-of-defence trade `ProjectRepository`'s header refuses.

**Read `ProjectRepository`'s header and it refuses no such thing.** It *writes* a native query, and
it says why that is safe:

> A Hibernate filter does not apply to a native query, so `company_id = :companyId` is explicit — RLS
> is the guarantee, the predicate is the intent, and the two agreeing is what makes a mismatch return
> nothing rather than something.

What it refuses is leaving RLS as the *sole* boundary, and the way it refuses it is an explicit
predicate — not abstention from native SQL. `DocBlockRepository` does the same thing twice, in this
very module, with the same sentence at both sites. `backend/src/main` carries seventeen native
queries; spelling the predicate out is the established answer among them, not an exception being
made here.

So `DocPageRepository#search` is native and carries three application-layer predicates —
`company_id` bound from `CompanyContext.require()` and never from request input, the path's
`project_id`, and `deleted_at IS NULL` — over RLS underneath. That is **more** boundary than the
filtered Criteria query the case list uses, not less. `Searching.theCompanyBoundIsEnforced` is the
test of the nest: two identically-titled pages in two companies, which is the only fixture where a
dropped predicate is visible, because without it every other assertion in the file passes just as
well on a query that returns the whole instance.

A Hibernate `FunctionContributor` would have kept this in Criteria and was rejected on its own
terms: a dialect-wide seam registered to serve one fixed-shape query with no dynamic predicates buys
nothing that Criteria is *for*.

**All three stale comments are corrected in place**, struck through rather than deleted, because the
misreading is the thing worth keeping visible — it is what would send the next author around the
tsvector for a fourth time.

## 3. The reason that *does* survive, and why it was not inherited by accident

The case list's second objection is that full-text matching is by lexeme, so a half-typed word finds
nothing. That is still true and it is still disqualifying — **for a type-ahead**. It is measured and
pinned here rather than asserted: `Searching.lexemesNotSubstrings` drives `refund`, `refunded` and
`refun` against one page and asserts the first two hit and the third finds nothing.

The stemmer runs on *both* sides, which is worth knowing before writing copy about it: `refundable`
finds "refunds" too. What fails is the **prefix** — and a prefix is exactly what a box fired on every
keystroke is full of.

Docs search is the opposite instrument on every count: the corpus is prose, the interaction is
type-then-submit, and stemming is what a reader looking for a concept wants. It is also the only
indexed path available — block text lives in JSONB with no trigram index, so the `LIKE` alternative
is an unindexed scan over precisely the content that makes the feature worth having.

**So the SPA's box submits rather than filtering as you type**, and that is the feature rather than
a shortcut: results-per-keystroke would render "No pages match" for every keystroke until the moment
a word completed, which reads as a broken search rather than as an unfinished query. It also
sidesteps §186's debounce-and-detached-node trap, which is the most expensive flake this repo has
paid for.

## 4. Only ids come back

The native query returns `[pageId, snippet]` and nothing else. The entities are loaded through a
second, JPQL read and assembled by `DocPageSummaryView.of` — the same code the page tree renders.

Three things follow, and the first is the reason:

- **A search row and the sidebar row beside it cannot disagree** about a title, a key or a status.
  Selecting the columns in the native query would have been a second assembly of one view, on one
  screen, side by side.
- The entity half of the read is back **under the Hibernate filter**, so only the ranking is outside
  it.
- A page the ranking named and the entity read did not return is **dropped**, not served empty. The
  two can only disagree if a page was deleted between them, and a hit that has stopped existing is
  not a hit.

The ordering is re-imposed in Java, because `IN` has none and re-stating `ts_rank_cd` in a language
that cannot express it would be a second, silently different answer.

## 5. The snippet, and the two decisions in it

`ts_headline` is given the same text the vector was built from — the page's block strings
reassembled through a lateral `jsonb_path_query` — so **the fragment always contains the match that
produced it**. Without a snippet, a result list is the page tree with extra steps; *where* a word
appears is what tells a reader which of four specifications mentioning "refund" is the one they
meant.

**It is plain text with no highlight markup.** Emitting `<mark>` would make this endpoint responsible
for escaping page content the product otherwise stores and renders as text, so one unescaped `<` in
somebody's specification becomes markup a client is being invited to trust. The fragment's location
carries most of the value; the emphasis is a rendering concern and belongs to whoever renders it.

**It is null for a page with no blocks** — a title-only match, which is every page created and not
yet written into — and null is *served*, not omitted: `required: [snippet]` with `nullable: true`,
the distinction `DocBlock.coverage` exists to make. A client showing a title with nothing under it
is showing the true answer rather than a field it failed to read.

## 6. One finding the plan did not contain

`V2` builds the vector with `jsonb_to_tsvector(…, '["string"]')`, which extracts **every** string in
a block's JSON — so a block's `blockType` is in the corpus beside its prose, and **searching `table`
or `image` matches every page containing one**. Table cells are in there too.

This is inherited, not introduced: it has been the vector's content since M0 and nothing could
observe it while nothing read the column. It is recorded on the contract rather than fixed, because
narrowing the corpus is a trigger change and a migration, and this slice's decision was to *read*
the vector. The snippet is what makes the behaviour visible rather than mysterious, which is the
argument for keeping the two corpora identical in the meantime — a snippet drawn from a narrower
text than the one that matched would sometimes contain no match at all.

## 7. A red this slice did not cause and did not leave

`frontend/src/features/reports/ReportViewPage.test.tsx` was **failing on master before this slice
touched anything** — seven tests, verified pre-existing by stashing §235 entirely and re-running the
file at phase 1.

§234 gave that page a "Save to register" control, which brought a permission read and a plan read
with it. Both landed on the double's catch-all arm and were answered with a **project**, so
`GET /me/permissions` served six properties the contract does not declare and neither of the two it
requires. Two anchored arms above the fall-through fix it.

Worth naming as a shape rather than as an incident: **a fall-through arm in a test double silently
adopts every read a page gains afterwards.** The contract-doubles rule (§197–§199) is what turned it
into a failure instead of a page rendering a project's fields as somebody's permissions — but it
turned it into a failure in *§234's* file, one slice after the change, which is why it reached
master. `CLAUDE.md`'s note that the frontend suite is a gate is only as good as the gate being run
on the whole suite.

---

## 8. What was deliberately left where it is

| Not done | Why |
|---|---|
| **Cases, conditions and shared steps on the `@@` path** | Out of the decision's scope, and — separately — right. Two of the three are type-aheads, where stemming costs every prefix a user is midway through typing. Their comments now say the path landed and that they stayed, rather than promising a move. |
| **Narrowing the vector's corpus** (§6) | A `V2` trigger change and a migration. Recorded on the contract with its consequence. |
| **Highlighting** | Would put escaping of stored content on the server. §5. |
| **Paging** | Capped at 50 with no cursor: every paging shape is a contract for walking a relevance ranking that changes under the walker on every edit. A reader who needs the fifty-first hit needs different words. |
| **An MCP or CLI surface** | The decision names an endpoint and a box. C10 gave the register tools one slice ago; a `tcms_search_docs` is a decision, not a follow-on. |

## 9. Gates

Both run on `shanks` per `CLAUDE.md`; `pgrep -af gradle-wrapper.jar` checked before each sync.

| Phase | Gate | Result |
|---|---|---|
| 1 | `./gradlew build` | **BUILD SUCCESSFUL** in 8m 1s — **3026 tests / 0 failures / 0 skipped**, +10 (`DocPageEndpointTest$Searching`) |
| 1 | `packages` build + lint + tests | green — **336** (78 cli / 130 client / 128 mcp), both `schema` copies regenerated |
| 2 | `frontend` build + lint + `test:run` | build clean, **0 warnings and 0 errors**, **1529 tests / 113 files** — +7 new, +7 recovered from §234's red |

The SQL was additionally probed against a throwaway **Postgres 17** before it was written into a
repository: `StartSel=""`, the `jsonb_path_query` lateral, and the empty-tsquery behaviour were all
confirmed rather than assumed. Two of the three did not behave as first written.

## 10. Queue

**Deferred per §65 — the rows are written and none is run.**

- `docs/verification/frontend-browser-checks.md` — `§235 DS-1` … `§235 DS-6`, lane **A1**.
- `docs/verification/backend-mutation-checks.md` — `§235 PM-1` … `§235 PM-3`.

## 11. What is next

**Six of eleven C items are done.** In the audit's §3.6 order the remainder is: **C5 + C9** (the
run-detail pair — minimal defect-link write, step-level evidence in the runner), then **C2**
(quarantine consumers), then **C1** (suite version advancement), then **C4** (retire the shared-step
library).
