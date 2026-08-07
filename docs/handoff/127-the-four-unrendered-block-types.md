# §127 — the four unrendered block types, and the snapshot that would have lied

**2026-08-08 · three phases · decision round 3's step 7 (#48) · no migration, `V39` still the head**

`DocBlockType` has had six values since M0. `BlockContent` modelled the two fields that four of them
share, `PageContent` branched on `HEADING` and `LIST`, and the SPA's two renderers branched on
`heading` alone — so **a documentation space could store a table and render it as a caption, and
store an image and render nothing.** The decision-round entry called this "build all four missing
renderers", which turned out to be an accurate description of a third of the work: two of the four
types had no *data* to render, and a third had a control that could read a field it could never
write.

---

## Phase 1 — the payloads, and the version history that would have stopped moving

`BlockContent` is `(text, level, rows, attachmentId)` now. No migration: the column is JSONB, and
`@JsonIgnoreProperties(ignoreUnknown = true)` — written into that record's javadoc on day one,
against exactly this change — is why every block written before the fields existed reads back with
them null, which is precisely what a heading, a paragraph, a list and a code block mean.

**The finding is `PageVersions`.** `recordEdit` writes a version row only when the snapshot's hash
moved, and the snapshot is built field by field from `SnapshotBlock`. So **a content field the
snapshot does not carry is a field somebody can rewrite for ever while the history says nothing
happened** — the page saves, reads back correct, and silently stops recording. Every other assertion
about tables passes against that version: the round trip is right, the cells are right, the reader
renders them. `TableBlocks.aCellEditIsAuthorship` is the only observer, and it is the row `DB-1`
names as the one worth running first.

**`@JsonInclude(NON_NULL)` on the two new components and deliberately not on the older two.** This
looks like an inconsistency and is the whole reason the annotations are there: without them every
stored `content_hash` in every deployment changes the day this ships, so the first save of each
untouched page writes a version whose diff is empty — the exact history-noise this class exists to
suppress, delivered once per page as a migration artefact. **Nothing inside the test suite can see
that**, because every hash in a run is computed by one build; the assertion is therefore that a
paragraph's *stored* snapshot has no `rows` key at all.

**`BlockContent.of(input)` is the one mapping.** `applyCreate` and `reconcile` each spelled the
constructor out, which is two copies of one mapping — and the drift it invites is silent in the
worst direction: a field wired into the edit path and forgotten on the create path gives a page
whose table renders on save and comes back empty the first time it is created.

### Three payload fields, one rule

`level` (heading, list), `rows` (table), `attachmentId` (image) are each **refused** on the types
that do not have them. That is `level`'s existing rule stated twice more, and it needs no new
argument: a field on the wrong type means the client believes it is sending something else, and
storing the type it named while dropping the field renders that belief with nothing reported.

Three decisions worth their own sentences:

- **The first row of a table is the header, always, and there is no flag.** A boolean would have two
  states that look identical in an editor and differ only in what a screen reader announces for
  every cell below it — so it gets set wrong and nothing reports it. A table in a specification
  whose columns are unnamed is one where a reader cannot tell what a cell means.
- **A ragged row is refused rather than padded, and row 0 decides the width.** Padding renders
  cleanly and is wrong in a way nothing can point at: an empty cell the author typed and a cell the
  server invented are indistinguishable, and in a specification they mean opposite things. Taking
  the *widest* row instead of the header lets a stray cell silently widen the table.
- **An `image`'s `text` is its alt text and blank is refused** — the one block type where empty text
  is not legal, sitting one `else if` away from the empty-paragraph rule that must stay legal. An
  image in a specification is never decorative.

**An image block can never be created with its page**, because a page has no attachments until it
exists. The refusal says *"create the page, attach the image, then add the block"* rather than
reporting a perfectly valid attachment id as unknown — `parentId`'s rule one field over, with the
create arm added so the message is *true*.

### The 409 with no foreign key behind it

`DELETE /doc-pages/{id}/attachments/{id}` now refuses while any block names the file. The reference
lives inside a JSONB document, so there is nothing for the database to enforce and the count is
explicit — and it runs **before the bytes**, which do not come back. `DB-13` is the mutation that
moves it one line down: the 409 still answers, and the file is already gone.

`content ->> 'attachmentId'` yields **text**, so the parameter binds as text. A `uuid` comparison is
a 500 on every attachment delete rather than a wrong answer, and the cast that "fixes" it also makes
a malformed stored value throw instead of simply not matching.

**The V2 search trigger needed no change**, which is worth pinning rather than assuming:
`jsonb_to_tsvector(…, '["string"]')` walks the whole document at any depth, so a table's cells became
searchable the moment they could be stored. Narrowing that trigger to `content ->> 'text'` reads as a
tidy-up and would make every table's contents silently unfindable.

---

## Phase 2 — one renderer, where there were two that disagreed

`DocsPage` and `PlanDocumentPanel` each carried a `BlockView` branching on `blockType`. **They did
not agree.** The plan panel indented list bullets and set a monospace face on `code`; the
documentation page did neither — so one stored outline read as an outline on one screen and as a run
of identical paragraphs on the other, with nothing failing anywhere. Neither was wrong about the
data. There were simply two answers to one question, which is the thing `lib/labels.ts` and
`DataRegion` exist to prevent one level down.

`components/DocBlockBody.tsx` is the single renderer. The callers keep only the chrome that genuinely
differs: the anchor id and the coverage margin on the documentation page, the link chips on the plan
panel. A table is a real `<table>` with `scope="col"` headers and `block.text` as a `<caption>` — and
the test asserts `getByRole('columnheader')` rather than the header's text, because a `<td>` holding
the same word renders identically and announces as an ordinary cell.

**The `default` arm is not dead code and must not be tidied into exhaustiveness.** `blockType` is a
generated union, so a seventh member is a compile error at every `case` — but the value arriving at
runtime comes from a server that may be a version ahead of the bundle, which is the mirror image of
`BlockContent`'s `ignoreUnknown`. A block whose type this build cannot name still has words in it. A
unit test drives it, because no deployment can.

### The image, and the decision it is not reversing

**`<img src={attachment.url}>` is the obvious spelling and it answers 401.** The session is a bearer
token this client sets per request, so any browser-initiated request to `/api/v1/…` carries no
credential — and it renders as a broken image with every gate green. `api/client.ts` has carried that
account since §89 for anchors; §126 phase 4 named the `<img>` half as un-fixed. This is the first fix.

`useAttachmentBlob` reads through `fetchArtifact` and **caches the `Blob`, never an object URL**: a
URL is a lifetime rather than a value, and a revoked one sitting in a shared query cache is a broken
image on the next mount that reads it. The component derives its URL in an effect and revokes that.

**An image block gets bytes where an attachment row does not, and that is not a reversal of §126
phase 4's no-thumbnail decision.** That decision was about fetching *every* file of a page into
memory on load, unbounded, to decorate a list. Here the picture **is** the content, the count is
bounded by blocks somebody wrote, and rendering nothing is a missing paragraph rather than a plainer
row. **`EvidencePanel` still has the un-fixed version** and was deliberately not swept.

`toHaveStyle({ paddingLeft: '48px' })` **silently passes nothing** under MUI v7 + jsdom: the emitted
value is `calc(6 * var(--mui-spacing))` and jsdom does not resolve the variable, so the matcher
compares against an empty string.

---

## Phase 3 — the editor could author three of six types, and one field was read-only

`table` and `image` had no control at all, so the only way to put either on a page was a
hand-written request. **The list case is different and worse**: §93 widened the contract so a bullet
carries an indent depth, the reader has drawn that indent ever since, and the Depth control was
`type === 'heading'` only — so `level` could be **read and never written**. Half a round trip,
invisible from either end alone. It is the same shape as the writerless tables M3 was re-derived
from, one field down.

- **`TableRowsEditor` is a grid, not a delimited textarea.** The textarea is a quarter of the code
  and it is §93's mistake one field over: a cell containing the delimiter splits silently, a column
  cannot be inserted, and a ragged row — which the server refuses by naming its index — is something
  the author can only find by counting characters. Rows are added and removed whole and columns
  across **every** row at once, which makes ragged *unconstructable* rather than merely refused. The
  header row's Remove is disabled, which is how the author learns the rule without a switch that
  could disagree with it.
- **`ImageFilePicker` is a picker over the page's existing attachments and deliberately not an
  upload.** The upload lives on the page's own panel, which owns the size limit, the empty-file
  refusal and the 413; a second one here would copy all three, and it would have to happen *before*
  Save while everything else on the form happens at Save.
- **Every type-specific field is derived from the type at submit**, never from what the control last
  held. Switching a table to a paragraph with cells still in form state is a 400 whose cause the
  author cannot see, on an ordinary act.
- **`TEXT_LABEL`/`TEXT_HINT` are `satisfies Record<blockType, …>`.** `text` is the Heading, the
  Bullet, the Caption or the Alt text depending on type, and a control labelled "Text" gives nobody
  a reason to fill in the one the server refuses when blank.

**A `data-testid` on a MUI select sits on the hidden `<input>`**, which carries
`pointer-events: none` — so `userEvent.click(getByTestId(…))` throws rather than opening the menu.
The repo already had the idiom (`ArtifactDialogs.test.tsx`); this file now has its own `choose()`.

---

## Gates

| Gate | Result |
|---|---|
| `cd backend && ./gradlew build` | **2104 tests across 447 result classes, 0 failures** (phase 1; §126's baseline was 2088/445). Phases 2–3 changed no backend file. |
| `cd frontend && npm run build` | clean, 0 errors |
| `npm run lint` | 0 warnings |
| `npm run test:run` | **1105/1105 across 84 files**, measured on a solo run (§126 phase 4's measured 1088/83) |

> **One full frontend run in four went red with 2 failures and was not reproduced.** It happened on
> a loaded machine — that run's test time was 780s against 373s on a quiet one — and three
> subsequent full runs plus three targeted runs of the changed surface are green. **The failing test
> names were not captured**, which is the honest limitation: an unidentified flake is a standing
> claim about this suite rather than a fact about one afternoon, and it is recorded in the
> orientation cache so the next red run has a precedent to check against.

**Built and unverified**, per §65: 9 browser rows (`M3-70`…`M3-78`) plus `F-11`, and 14 mutation
rows (`DB-1`…`DB-14`), **none run**. `DB-1` is the one to run first — it is the only mutation here
whose failure is invisible in every response.

## What this slice did not do

- **`EvidencePanel`'s `<img src>` and `<a href>` are still un-fixed.** The credentialed image path
  exists now and sweeping evidence with it is a separate slice; `M4-7` remains expected to fail live.
- **`STEP` is still not an attachment owner and no longer needs to be** — `V39` dropped it (§126).
- **No renderer decides anything about an unknown block type beyond showing its words.** That is the
  `default` arm, and widening it would be inventing a rendering for a thing this build cannot name.
