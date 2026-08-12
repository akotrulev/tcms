# §169 — the capability the schema designed, the plan approved, and nobody built

**Two phases, `backend/` + contract + both `schema.d.ts` copies + one migration (`V47`) +
`frontend/`: no `packages/` source.** §168's named next slice — the six `DocLink.external*` keys,
the only entries left in `ContractConventionTest.MEMBER_DIVERGENCE_BY_DECISION`, and the map itself
said what would remove them: *"an external target needs a writer, a second arm in the mirror
statement and a `targetKind` this SPA branch can reach, and on that day all six entries have to be
deleted for the build to pass."* All three, in that order.

**`MEMBER_DIVERGENCE_BY_DECISION` IS NOW EMPTY.** Every named object schema and the record serving
it agree member for member, in both directions. Ten entries have passed through that map and
**every one of them left by deletion** — §166's two `lastResult` keys, §167's `relatedDocuments`,
§168's `DocBlock.coverage`, and these six — which is the mechanism a both-ways pin exists for,
exercised four times in four sections.

## The finding is that the gate inverts when the target leaves the module

`BlockLinkService` has charged `doc:edit` since §93 and states the reason: *"a link is a property of
the documentation, so it costs what editing the documentation costs"*, with `case:edit` explicitly
refused because it *"would let a person with no documentation rights write into a page's
rendering"*.

**Every clause of that argument turns on the target being a doc block.** An external link renders in
exactly one place — the case's Traceability panel — and on no page at all, because `BlockLinks` reads
by `target_block_id` and an external row has none. So the act writes into a case's rendering and
nothing else, and `doc:edit` would be the wrong charge twice over: it would let somebody with
documentation rights and no case rights annotate a case they may not edit, and it would require a
documentation grant to record a fact about a case in a project that keeps **no documentation at
all** — which is most projects, since a `doc_space` exists but nothing forces a page into it.

So `ExternalLinkService` gates on **`case:edit`**, and the cost is written down rather than glossed:
`doc_link` now has two writers with two gates, so *"who may create a link"* has no single answer.
The answer it does have is the one that reads off the screen — **whoever may edit the thing the link
renders on** — which is checkable by looking, where one gate for the table would have required
knowing that a row's kind decides where it appears.

The test that holds it is `Gates#anEngineerWithNoDocumentationRightsMayStillLink`, and the persona
is not what makes it work: Test Engineer holds both keys. What makes it work is the **project** —
its fixtures seed no documentation space, no page and no block, and this endpoint neither reads nor
needs one.

## The source is a case and not a suite, because a suite has no reader

`LinkSourceType` offers `TEST_CASE` and `SUITE` and this endpoint takes the case alone.
`case_doc_link` mirrors `source_type = 'TEST_CASE'` rows only, so a suite's external link would be a
row nothing in the product could ever show — §156's shape, a writer whose value has no reader,
arriving in the slice that closes six of them. The fix on the day a suite grows the panel is one
path parameter. `aSuitesExternalLinkIsNotMirrored` pins it from the other end, and its failure mode
is worth knowing: the predicate is on **both** arms of the mirror statement, and dropping it from
the second inserts a row whose `case_id` is a suite id — a foreign-key violation that aborts the
whole refresh rather than mis-rendering, which is the direction to fail in.

## Idempotency is Java, and SQL is the reason

Re-asserting the same target answers 200 with the link already there — `BlockLinkService`'s rule,
because two people recording the same coverage is agreement and a second row doubles every count
drawn off this table. **What differs is how sameness is decided.**

Four of the six identifying columns are nullable, and `external_line_start = :start` is **UNKNOWN
rather than true when both sides are null**. So the query spelling of this check silently fails to
match a whole-file link against itself and writes a second row on the second click, while every
other test in the class stays green. `IS NOT DISTINCT FROM` says it correctly and JPQL cannot; a
native query would put one in the single file of this module that is not allowed to hold one. The
comparison is therefore `ExternalLinkService#sameTarget`, over the handful of links a case has, and
`aWholeFileLinkMatchesItself` is the test that would catch the regression.

**The line range is part of the identity.** Folding it out is the cheap way to write that method,
and it fails by answering the author's request with somebody else's link and a 200 on top.

## `V47`, and the one thing an external link cannot be

The mirror `V46` built one section ago was written for the one kind that existed and said so at
three `NOT NULL`s. Four columns lose them, `ck_case_doc_link_target` takes their place — one column
list per kind, `ck_doc_link_target` seen in a mirror — and the six external columns are copied
across. Unlike the page title and the block excerpt beside them, **these copies cannot go stale**:
`doc_link` has no update path at all (every column is `updatable = false`, and `DocLink`'s header
says why — *"changing what a link points at is two acts"*), so a row is right or the link was
deleted.

**`stale` is the literal `false` on the external arm, and that is an answer rather than a default.**
The column means *the linked text changed after this case's content last changed* — a
`doc_page_version` against a `test_case_version` — and neither half exists for a target this product
does not store. False is honest precisely because of #26's decision: an `EXTERNAL_GIT` link is
**pinned to a commit**, so the lines it names cannot change under it. What *can* happen is the pin
falling behind the branch, and this column does not mean that; a marker reading "the spec changed,
re-read it" on an immutable target would train readers to ignore the marker where it *is* computed.
Answering "is the pin current" needs the product to read a git host, which is an integration and not
a column. The cost is stated in the migration and in the queue: a git link pinned a year ago looks
exactly as fresh as one pinned this morning.

`CaseDocLinkRepository`'s `ORDER BY pageKey, blockId` was total only while every row was a block
link. Both columns are null on the others, so the whole external half of a case's panel would have
been left in whatever order a plan produced — the instability that clause exists to prevent,
arriving through the clause itself.

## Validation is narrower than the constraint, in two places, on purpose

- **`repo` is required** and `ck_doc_link_target` does not ask for it. A path with no repository
  names a file in an unnamed project, which is a link nobody can follow. `LinkSourceType`'s
  precedent: what the application refuses is expressed in the code, what the schema permits is a
  statement about the design.
- **The sha is 7–40 hex.** Loose at the bottom because people paste abbreviated shas out of
  terminals; refusing a seven-character one would be this product deciding how somebody's git is
  configured. **SHA-256 object names (64 characters) are refused**, which is a known limit and one
  number to change on the day a repository using them turns up.
- **`http` and `https` only.** The value is rendered as an anchor's `href`, so the scheme is a
  security bound and not tidiness — `javascript:` executes in the reader's session and `data:`
  renders attacker-authored HTML on this product's origin. Refused at the write because that is the
  one place it can be refused once: four readers read this value back.
- A URL link carrying git coordinates, or the reverse, is **refused rather than ignored**. Accepting
  and dropping them is how a caller comes to believe a range was recorded.

## Phase 2 — the panel that had rendered two dead branches since M0

`DocLinksPanel` carried an external arm from the day it was written, and **no response could ever
populate it**. What it did with a URL link was render `externalRepo` — null — as the label, i.e. a
blank line; what it did with a git link was render the repository as the label and drop the file,
the range and the pin into a caption below, so the pin read as a footnote. It typechecked for
seventy sections. That is §168's `CoverageMarker` one panel over, and the second time in two slices
that the SPA's branch for a field nothing sent turned out to be wrong in a way only real data shows.

Now: one `DocLinkTarget` component, switching on **`targetKind` and never on which field is
populated**, rendering a git link as one identifier — `acme/checkout · src/Refund.java:40–58 at
4f9c2b1` — and a URL as an anchor with `target="_blank"` **and `rel="noopener"`**, which is the
other half of the scheme check: without it the opened page can navigate this tab through
`window.opener`, and the href is a value somebody typed into a form.

`ExternalLinkDialog` is the write. The kind is a **choice and never inferred** — a form that decided
from which inputs were filled would send a git link the moment somebody pasted a URL into the path
field. The relationship is **offered rather than defaulted**, because a form that always sent
`VERIFIES` would make the other two unreachable from this product, which is the shape this whole
slice exists to close. Remove appears on external links only: a block link is withdrawn on the
documentation page under `doc:edit`, and no endpoint withdraws one from the case end.

`linkRelationshipLabels` and `linkRelationshipHelp` join `lib/labels.ts` — the panel had been
lower-casing the wire value inline, which is the display rule living in the page that CLAUDE.md's
shared-layer rule is about, and the second screen would have done it slightly differently.

### The cache rule this slice had to discover, and a test found it

`relatedDocuments` is served from a projection refreshed on an event, so **the ordinary
`onSuccess: invalidate` is wrong here**: an immediate refetch asks for a read model the event has
not reached yet, gets the list *without* the link that was just created, and erases it. A create
that succeeded reads as one that failed with a 201.

Both hooks therefore patch the cache from the server's own answer and **do not invalidate**. The
patch is not an optimistic guess — the create returns the id it minted and the columns it stored,
and the mirror's external arm copies exactly those and writes `stale: false`, so what is inserted is
the row the server described, a minute before the projection catches up with it. Reconciliation is
the next ordinary refetch.

**This was found by a test and not by reasoning.** The patch and the invalidate were both written
here first; `shows the new link straight away, without waiting for the projection` went red, with
the panel losing the link a millisecond after showing it.

## Tests

- **`ExternalLinkEndpointTest`** — new, 32 tests in five nested classes (`e1…` prefix, checked
  against every prefix in `src/test/java` first). Gates, what is written, idempotency, validation,
  removal.
- **`CaseHealthRecomputeTest.TheDocumentationLinks`** — +6: both kinds mirrored whole, both arms
  contributing to one case, never stale, a suite's link mirrored nowhere, a soft-deleted case's
  external link dropped. Its `mirrored()` helper read `page_id` through `UUID.fromString`
  unconditionally and would have thrown on the first external row.
- **`DocLinksPanel.test.tsx`** — new, 10 tests: the three kinds rendered by kind, the whole-file and
  single-line ranges, the anchor's `rel`, Remove on external links only, the disabled affordance for
  a Viewer, the payload carrying only the chosen kind's fields, and the two projection-lag rows.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL, 2525 tests / 515 classes / 0 failures.** §168 was 2487 / 509 — **+38 and one new file** (five nested classes). |
| `frontend`: `npm run build` · `npm run lint` · `npm run test:run` | clean · **0 warnings** · **1228 across 94 files** (§168: 1218 / 93) |
| `packages`: `npm run build` (in order) · lint · `test:run --workspaces` | clean · clean · **229** (66 + 85 + 78) — unchanged; only the generated `schema.ts` moved |
| both `schema.d.ts` | regenerated, **byte-identical to each other** |

## Queues

**Mutations are RECORDED AND NOT RUN** — §65 in full. `backend-mutation-checks.md` gains
`§169 XL-1`…`XL-6` (two of them guarding values nothing downstream can check, and `XL-6` whose
expected outcome is a *loudly aborted refresh* rather than a failed assertion);
`frontend-browser-checks.md` gains `§169 XL-1`…`XL-5` in lane **A1**.

## Two gaps found on the way, neither closed here

1. **`useLinkBlock` and `useUnlinkBlock` have no caller anywhere in `src/`.** The documentation page
   renders a block's links and offers no way to create or remove one, so the only way to make a
   `DOC_BLOCK` link in this product is the API. §168's browser row `CV-4` asked its question
   parenthetically — *"the doc page's link dialog, if it offers the choice"* — and the answer is that
   there is no dialog. Recorded in the browser queue where it will be met.
2. **`DocPageController`'s header called itself "the `documentation` module's only controller"** and
   had not been for four arrivals (#49's attachments, artifacts, plan documents, and now this).
   Corrected where it was found: *"the only X"* is the class of claim that is true when written,
   false the moment somebody adds a second, and checked by nothing — §152's finding again.

## The next slice

**There is no `MEMBER_DIVERGENCE_BY_DECISION` entry left to take.** What remains, unchanged since
§165 and §168:

- the **browser queue**, now carrying `§169 XL-1`…`XL-5`, and still never run since §110;
- **`TeamRef`** — three types, two of them a contract change before they are a Java one;
- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link` and now checkable against
  §168's marker from the other end;
- the **read-only-archive** decision §159 deliberately did not take;
- the **inline object-schema population** §165 named on its way out — shapes declared on a property
  rather than as a named schema, which `objectSchemas()` skips and no pairing rule sees. That
  population is larger than the named one, and **this slice did not add to it**: `ExternalLink` and
  `ExternalLinkInput` are both named schemas, paired in `RESPONSE_RECORDS`, which is what made the
  build fail until they were.
- **`useLinkBlock`'s missing screen**, above — a hook with no caller is the frontend spelling of the
  writerless column §156 spent a slice on.
