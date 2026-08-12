# §168 — the marker whose obstacle had already moved

**2026-08-12 · one phase · `backend/` + contract + both `schema.d.ts` copies + two `frontend/` files:
no migration, no new table, no new column, no `packages/` source changed.**

`DocBlock.coverage` — plan §6i's inline coverage marker, *"what makes people maintain links"* — was
declared from M0 and served by nothing. It was the last of the four entries
`MEMBER_DIVERGENCE_BY_DECISION` opened with, and §167 named it as the next slice on the strength of
its own argument: it needs *two* modules `documentation` may not name.

**That argument had been false for two sections.** §166 mirrored each case's newest recorded result
onto `case_health.last_status_kind` — one column, in `repository`, the module `CatalogueFacts` has
been allowed to name since §93. The join the entry called impossible had already stopped being a
join. Nothing in this slice removed an obstacle; the obstacle left in §166 and nobody went back to
look.

## What that makes this slice, and the finding is not the marker

Three of the four entries were closed by **re-reading the argument**, not by removing what it named:

| Entry | Its stated obstacle | What had actually happened |
|---|---|---|
| `TestCase.lastResult` (§166) | "`execution` does not exist" | It was 119 files; §165 rewrote the argument, §166 built the mirror |
| `TestCase.relatedDocuments` (§167) | same | same rewrite, one section later, through a table |
| `DocBlock.coverage` (§168) | "two modules `documentation` may not name" | §166 had reduced it to one, two sections earlier |

The map is pinned in both directions, so a **closed** divergence cannot keep its argument — the build
goes red until the entry is deleted. It has no such pin on an argument whose *obstacle* has expired
while the gap remains, and that is the state all three sat in. `ContractConventionTest`'s header now
says so where the reader who is about to add an entry will meet it: **name the obstacle precisely
enough that a later slice can tell it has gone.** §160's `rawStorageKey` (arguing "there is no
adapter" for twenty-nine sections after `storage` built one) is the same shape a third time, and the
cost is identical — an argument that misnames its obstacle deters the repair long after the repair
became free.

## No table and no column, which is the third answer in three sections

§166 argued columns (one fact, one row). §167 argued a table (zero-or-more links, and a column holds
one value). This one needed **neither**: every input already exists, in tables this module already
reads, and the marker is `doc_link` rows filtered and counted against a column §166 wrote. The
progression is worth stating because the tempting reading of §166 and §167 is "a divergence closes
when you build a read model" — and here the read model was already built, by somebody else, for
another screen.

## The marker is assembled in `BlockLinks`, from rows it had already read

`BlockLinks` was "the ONE assembler of a page's links" and is now the assembler of the links **and**
the marker, because the marker is derived from the links it has already resolved. A `BlockCoverage`
component beside it would re-read `doc_link` for the same page in the same response, and the two
reads would have nothing making them agree about which links exist — which is precisely the defect
the tests `a link to a deleted case is in neither the bullets nor the marker` and `a link to a
sibling project's case is in neither` pin. **The marker and the bullets share their input**, so
"2 cases verify this" cannot appear above one visible bullet.

Three queries became four: links, cases, suites, then every linked case's newest result. The fourth
is skipped entirely when a page's links name no case — `CatalogueFacts` answers an empty id set
without a statement, since `IN ()` is a Postgres syntax error rather than a predicate matching
nothing.

`forPage` now returns a `PageLinks` record rather than a bare map, and the change is not cosmetic: a
caller able to fetch the links without the marker is a caller able to render a block whose marker
counts links the page did not show. Four call sites, one parameter each.

## Null, never zeroed — the original argument survives every rewrite

`coverage` is **null on a block nothing claims to verify**, and never a row of zeros. The reason has
outlived three separate obstacles: a zeroed marker does not read as "unknown", it reads as *"no test
case verifies this statement"* — the SPA renders those exact words — so a zero fallback would put a
specific and alarming claim on every paragraph of every specification in the product. `links` is
where the answerable half is stated: present, possibly empty, and an empty one truthfully means
nobody has claimed this block.

**`VERIFIES` only.** `CoverageMatrixReport`'s spine counts a statement as covered when it carries a
`VERIFIES` link, so counting `REFERENCES` here would put a number on a paragraph the matrix — reading
the same rows about the same statement — says nothing covers. A block linked only by `REFERENCES` or
`DERIVED_FROM` has bullets and no marker.

## Three counts for six kinds, and they always sum

`passed` is `StatusKind.PASSED`, `failed` is `FAILED`, and **`notRun` is everything else** —
`BLOCKED`, `SKIPPED`, `RETEST`, `UNTESTED`, `WIP`, and a case that has never been executed at all.
The contract offers three counts and the domain has six kinds; the three that are not a verdict
belong with the ones never asked for, per §46's rule that a roll-up which could go either way must
not go the way that makes the numbers look better than the truth. Counting a blocked case as
*failed* would be the opposite error — reporting a failure nobody observed.

`passed + failed + notRun == linkedCases`, always, because `notRun` is computed as the remainder
rather than counted independently. A marker whose parts do not sum is one a reader cannot subtract
with.

**The kind is compared by name, not parsed into the enum.** A project seeding a seventh kind this
build has no constant for would throw on read — an exception rendering a specification, over a
number — where an unrecognised name falls through to `notRun`, which is already what "no verdict
this reader can act on" means. §167's `DocLinkView` refuses to re-declare another module's value set
for the same reason, one layer down.

## The seam this leaves, recorded rather than discovered later

**This marker buckets by `StatusKind`; `CoverageMatrixReport`'s Verified column reads
`counts_as_pass`.** A project that adds "Passed with comment" — kind `PASSED`, `counts_as_pass =
false`, the split `StatusKind`'s own javadoc designs — sees it counted as a pass here and not there.
The disagreement is real and **unreachable from this module**: `case_health` carries the kind and not
the status id, by `V45`'s decision, and the status row lives behind `execution`. Closing it means a
second mirrored column, not a query. It is in the mutation queue as a "no test is possible" row.

## `required`, and the fixture that caught it

`links` and `coverage` joined `DocBlock`'s `required` set. Both keys have been emitted on every
response there has ever been — one rendering path, Jackson's default inclusion — so this records what
is already true, and it makes the distinction the marker rests on machine-checkable:
**present-and-null is an answer, absent is not.**

It cost one frontend fixture, exactly as §167's `caseBody()` did: `DocPageEditorPage.test.tsx` builds
two `DocBlock` literals, and `required` turned a missing key into a `tsc` error rather than a runtime
gap.

## The SPA rendered this component for 168 sections against a field nothing sent

`CoverageMarker` in `DocsPage.tsx` was complete — chip, colours, tooltip, a link into the filtered
case list — and had never once run against real data. One line changed: **null and zero are now one
branch.** The server sends `null` for an unverified block and never a zeroed object, so the
`linkedCases === 0` arm that carried the "Not tested" chip was unreachable while the `!coverage` arm
rendered an *empty margin* — hiding the marker in exactly the case plan §6i built it for, the
statement nobody has linked.

## Tests

`DocPageEndpointTest`, **+11 tests, +1 nested class (`Coverage`)**, and **one existing test revised in
place**: `coverageIsAbsentRatherThanZeroed` asserted the key was *absent*; it is now
`coverageIsNullRatherThanZeroed` and asserts present-and-null. Same claim, new spelling, id and
history intact — the treatment §166 and §167 gave their superseded queue rows.

- a verified block carries the counts and the keys of the cases verifying it
- the three counts always sum to `linkedCases`, whatever the kinds are
- a `BLOCKED` case is `notRun` — not passed, and not failed either
- a case that has never been executed is `notRun`, not missing from the marker
- a block linked only by `REFERENCES` has links and **no** marker
- one case linked to one block twice counts once
- a link to a deleted case is in neither the bullets nor the marker
- a link to a sibling project's case is in neither, though RLS would allow it
- `caseKeys` are in the same order as the links rendered beside them
- the write path serves the marker too, so a save does not blank it
- a suite link is a bullet and not a coverage claim

The fixtures write `test_case` and `case_health` directly rather than driving results through the
execution endpoints: `CaseHealthRecomputeTest` owns that projection and asserts it directly, and what
is under test here is what `documentation` does with the value once it is there — including the case
with **no `case_health` row at all**, which no amount of driving the real path could produce on a
case somebody had linked.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL (7m 15s), 2487 tests / 509 classes / 0 failures / 0 skipped.** §167 was 2476 / 508, so this is **+11 and one new class**. |
| `frontend`: `npm run build` · `npm run lint` · `npm run test:run` | clean (after the two-block fixture fix) · **0 warnings** · **1214 across 93 files** |
| `packages`: `npm run build` (in order) · lint · `test:run --workspaces` | clean · clean · **229** (66 + 85 + 78) |
| both `schema.d.ts` | regenerated, **byte-identical to each other** |

**One gate was run twice and the first result discarded**, recorded because a green build that does
not describe the tree is worse than no build: the first `./gradlew build` had already compiled when
`BlockLinks#coverage` was refactored (the case *key* is now carried off the rendered view rather
than looked up again in the resolved map, removing an invariant held across two loops). Its result
would have graded the pre-refactor code. It was killed and re-run from the final tree rather than
believed.

## Queues

**Mutations are RECORDED AND NOT RUN** — §65 in full. `backend-mutation-checks.md` gains `§168 CV-1`
…`CV-5` plus the `counts_as_pass` no-test-possible note; `frontend-browser-checks.md` gains
`§168 CV-1`…`CV-5` in lane **A1**, all five driving a component that has never run against real data.

## The next slice

**`MEMBER_DIVERGENCE_BY_DECISION` no longer holds a single module-boundary entry.** What remains is
the six `DocLink.external*` keys, which are one capability nothing builds — a writer, a second arm in
the mirror statement, and a `targetKind` the SPA can reach — and all six leave together on the day it
gets one.

Unchanged and still open: the browser queue (now carrying `§168 CV-1`…`CV-5`), `TeamRef` (three
types, §165), `OUTDATED_SPEC_CHANGED` / `UNLINKED` — now derivable from `case_doc_link` **and**
readable against this marker, so the case's chip and the statement's marker can finally be checked
against each other — the read-only-archive decision §159 deliberately did not take, and the
inline-object-schema population §165 named on its way out (`objectSchemas()` skips them; `lastResult`
was one instance and `coverage` is another, which is why this slice's contract change was paired by
*member* name and never by schema).
