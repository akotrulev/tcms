# Agent-Facing Processes and the Specification Formatter

**Date:** 2026-09-07 · **Status:** ~~design, no code changes~~ → **built**, all five tasks landed 2026-09-07 (§7) · **Stacks touched:** contract → `be` → `ts` → `fe`

**The ask.** The MCP server and the CLI exist. What does not exist is a description of the *processes*
an agent is expected to run over them — a specification becomes a documentation page, test cases link
to the parts of that page they verify, a test plan is written for that page — and a *formatter*: hand
a model a specification in whatever shape a client wrote it, and get back the shape this product
stores, ready to write.

This document decides three things: **the format** a specification is translated into (§2), **the
process catalogue** and the carriers that put it in front of a model (§3), and **the surface gaps**
that stop the processes from completing today (§4–§5). §7 is the execution breakdown; the plan with
its per-task prompts is a separate artifact.

---

## 0. What the reading found, before any design

Seven facts about the shipped system. Three of them are gaps, and each gap is something a process
description would have to say "and here a person does it by hand", which is the failure this design
exists to remove.

1. **A requirement is a `doc_block`, and its id is permanent.** There is deliberately no
   `requirement` table (ERD question #18; `documentation/package-info.java` opens with it). A block
   keeps its id however much its text changes, links target `(page_id, block_id)`, and the coverage
   matrix is a query over `doc_link`. So "link a case to a specific part of a document" is already
   the product's native shape — a block, not a heading anchor, not a line number.
2. **A list item is its own block** (`DocBlockInput.level` on a `list` is indent depth), precisely so
   that *"a scenario a case can verify has to be its own block rather than a line inside one"*. A
   table's cells are data; its caption (`text`) is the statement coverage reads. Both are already
   the right granularity for a formatter to target.
3. **The authoring format has three carriers and no process has any.** `tcms_shape` (a tool that
   reads nothing and needs no project), `tcms://shape/{entity}` (a resource) and the enriched
   `inputSchema` on each write tool, all rendered from one catalogue in
   `packages/client/src/core/shapes.ts`. The sequence *page → cases → plan* is stated nowhere: the
   server `instructions` string carries one sentence ("ground yourself before you write").
4. **Nothing converts prose to blocks or blocks to prose.** A model writing a page today hand-builds
   `DocBlockInput[]` — `level` required on a heading and refused on a paragraph, `rows` on a table
   and nowhere else — and reads a page back as JSON. Every edit is a **rewrite**: a block omitted
   from `blocks` is deleted, and its links with it. The trap is documented on the tool and is still a
   trap, because the medium a model edits in (text) and the medium the tool takes (an id-carrying
   array) are different things.
5. **A proposal cannot carry the requirement it verifies — the gap that breaks traceability.**
   `AiProposalInput` is `testCase` + `verdict` + `grounding` (free-form, "displayed rather than
   interpreted"). A block link takes `sourceType: TEST_CASE, sourceId: <uuid>`, and the case has no
   id until a person accepts. So an agent that generates twelve cases *for* twelve requirements
   cannot say so in any field the product acts on. It either comes back after acceptance — an event
   it is not told about — or the reviewer links by hand, twelve times.
6. **A plan cannot adopt its specification from either surface.** `PUT /plans/{planId}/document`
   (`doc:edit`) exists and is reachable by nothing in `packages/`. And `tcms_write_plan` reaches six
   of the plan's twelve authorable fields: `scopeText`, `outOfScopeText`, `entryCriteria`,
   `exitCriteria`, `sections` and `tags` — the whole *written for a specification* half — have no
   argument. `writes.ts` records that every plan edit this server ever made emptied the first two.
7. **`POST …/cases/bulk` is excluded from MCP by decision** (surface spec §3.3): its gate is decided
   by its payload. That argument governs the shape of anything that writes many things at once from
   this surface, and §4 below is written under it.

---

## 1. The design in one paragraph

A specification is translated into **one Markdown file in a fixed subset** (§2) — headings for
structure, one testable statement per paragraph or list item, each statement carrying a stable
`{#label}` — and a converter in `@tcms/client`'s core turns that file into `DocBlockInput[]` and
back, with the block ids riding along as the anchors so an edit never silently voids a link. **Five
processes** (§3) are written as a catalogue beside the shapes, served by the same three carriers plus
MCP prompts. And three surface gaps close: a proposal carries `verifies` and the links are written
when a person accepts (§5), a page can be **adopted** by a plan, and the plan tool reaches every
plan field (§4).

---

## 2. The format — "TCMS spec markdown"

### 2.1 Why Markdown, and why a subset

A model writes Markdown natively and a person can review it in a diff; a JSON block array is neither.
But the page stores **plain text per block** — inline `**bold**` renders literally — and the block
types are six, so the format is the subset that maps onto them **one construct to one block type, no
construct without a mapping, and nothing silently dropped**. The converter refuses what it cannot
map, naming the line, for the same reason `DocBlockInput` refuses `rows` on a paragraph: a client
sending it believes it is sending something else.

~~A JSON bundle as the primary format~~ — considered and kept only for the multi-object import (§4.4).
For the page itself a bundle makes the model hand-build the array §0.4 names, which is the thing being
removed.

~~A server-side `POST …/doc-pages/parse-markdown`, twin of `parse-gherkin`~~ → **client core, in
`@tcms/client`** (`core/markdown.ts`). The consumers are the two adapters, which share the core by
design (surface spec §1); the mapping is ~150 lines of pure TypeScript with a round-trip property to
pin it; and it costs no backend session. If the SPA later wants paste-Markdown on the page editor,
the endpoint can be added then and this file becomes its reference implementation.

Two client specifications were run through the format by hand before the rules below were fixed;
they are in `assets/spec-formatter/` and §2.5 says what they changed.

### 2.2 The grammar

| Markdown | Block | Rule |
|---|---|---|
| `#` … `######` | `heading`, `level` 1–6 | Structure only. A heading is linkable but a case verifying a heading verifies nothing; the formatter never labels one. |
| Blank-line-separated text | `paragraph` | **One testable statement.** A paragraph joining two obligations with "and" is two paragraphs. |
| `- item` / `* item` / `1. item`, nested by two spaces | `list`, `level` = depth (1-based) | One block per item. Numbering is not stored; ordering is `position`. |
| ```` ``` ```` fence | `code` | The fence's info string is dropped; a block has no language field. Examples, payloads — never a requirement. |
| `Table: caption` line **immediately before** a GFM pipe table | `table`, `text` = caption, `rows` = cells | The caption is required, because it is the statement coverage reads. A table without one is refused, not captioned `""`. First row is the header, always; the `\|---\|` separator row is required by the grammar and dropped. ≤ 100 rows, ≤ 12 columns, ≤ 500 chars a cell — the contract's own bounds, checked before the request. **A table whose rows are rules is not a table** — see §2.4.4. |
| `![alt](attachment:<uuid>)` | `image`, `text` = alt, `attachmentId` | **Edit only.** A create cannot carry one (the page has no attachments until it exists), so on a create the formatter writes a paragraph `Figure: <alt>` and reports it. |
| `{#label}` at the end of a paragraph or list item | the block's **anchor** | See 2.3. |
| Anything else — blockquotes, HTML, `---`, inline markup | refused, naming the line | Inline emphasis is *allowed through* as literal text with a warning, because the page renders it as typed. |

### 2.3 Anchors — labels on the way in, ids on the way out

An anchor is `{#…}` at the end of a block's text. Two spellings, and the converter tells them apart
by shape:

- **A label** — `[a-z0-9][a-z0-9-]*`, unique in the file — marks a *new* block the author wants to
  refer to. The server assigns the id; the write's response carries `anchors: { label → blockId }`,
  which is what the next step (a proposal's `verifies`) is written against.
- **A block id** — a UUID — marks an *existing* block, and becomes `DocBlockInput.id`. This is what
  `tcms_open_doc_page {as: 'markdown'}` renders on every block, headings included, so a page read
  back and edited keeps every id it had.

**The edit rule that makes this safe:** on an `edit`, a block that currently has links and whose id
is absent from the new Markdown is a link about to be voided. The converter **refuses**, naming the
block and the cases claiming it, unless the call says `dropLinked: true` (CLI `--drop-linked`). This
is the first concrete answer to surface spec §3.3's open question about COMPLETE-SET edits: the
consent gate sits where the loss is computable, in the core, and not behind a generic prompt.

A label on an edit is legal and means "new block here". The converter tests for the UUID shape
first — 8-4-4-4-12 hex — and a label may not take that shape; the grammar for labels is otherwise
a subset of what a UUID matches, so the order of the two tests is the rule, not an implementation
detail.

### 2.4 The translation rules — what the formatter asks of the model

This is the text the process carrier serves (§3) and it is the *semantic* half the converter cannot
do. Each rule names the failure it prevents.

1. **One statement per block.** Coverage is per block; a block stating two things can be half-verified
   and read as whole.
2. **Rewrite every requirement into a testable statement — and only its wording.** The output is a
   *working* document, not a tidied copy, so each labelled block is rewritten to the standard in
   §2.4a: a named actor, an explicit condition, an observable outcome, one sentence. What may not
   change is the **obligation** (*shall/must* stays a requirement, *should* stays a flagged one,
   *may* stays a capability), the **facts** (every number, name, threshold and formula is the
   source's), and the **open questions** (an ambiguity is quoted and asked, never quietly decided).
   Where a rewrite is more than cosmetic — a pronoun resolved to a specific actor, two sentences
   merged or one split, a term normalised — it is listed in the report's *Reworded* section as
   before → after, so a reviewer can check that meaning survived.
### 2.4a The rewriting standard

Each labelled block, after rewriting, satisfies all of:

| Property | Means | Instead of |
|---|---|---|
| Named actor | "The system", "the liquidator", "an operator" — never "it" | "It will not sell into a closed market" |
| Explicit condition | The *when* is in the sentence, as a clause | "Trigger – EOD" on its own line |
| Observable outcome | Something a test can assert: a value, a state, a refusal with its message | "That's when it impacts the client's account" |
| One sentence, one obligation | A second obligation is a second block (rule 1) | A paragraph with three "and"s |
| The source's vocabulary | Its own terms, capitalised as it capitalises them; a term the source uses two ways stays two ways and is asked about | Silently merging "utilized margin" and "loaned amount" |
| The source's facts | Every number, threshold, name and formula verbatim | Rounding a figure, completing a formula |
| No hedging the source did not hedge | "about 151%" stays "about"; "a few hours" stays "a few hours" | Turning "a few hours" into "4 hours" |

Rationale, history and anecdote are rewritten too — for clarity, in unlabelled paragraphs — but
never promoted to requirements by the rewrite (rule 10).

3. **Label every requirement; label nothing else.** Rationale, background and definitions stay as
   unlabelled paragraphs — linkable, since every block is, but not offered as targets. A label is a
   promise that a case can be written against the block.
4. **Lift requirements out of tables.** A cell is data (≤ 500 chars, unlinkable). A rule that lives in
   a row becomes a list item; the table keeps the examples and its caption states what the rows
   exemplify.
5. **Acceptance criteria become list items**, one each, labelled — the shape a story's AC already has.
6. **Mirror the source's headings**; do not invent structure. A reader comparing the page to the
   original must find the same sections in the same order. Sections the source lacks and the plan
   needs — *Out of scope*, *Assumptions* — are added **at the end**, under those names, so the plan
   process (§3.3) can find them.
7. **Do not write into the page what belongs elsewhere.** Test scenarios go into cases; scope and exit
   criteria go onto the plan. The page is the specification, and the coverage matrix is what shows
   what tests it.
8. **Report what was not carried.** Images, cross-references to documents not in this project, and
   anything refused by 2.2 are listed in the report (§2.5), never silently dropped.
9. **A declarative is a requirement.** Client specifications rarely say *shall*; "interest rate is
   defined per currency" and "it sells one position per cycle" describe behaviour and are labelled.
   Present-tense description of the *product* is a requirement; description of the *world* ("a margin
   account lets a customer control more stock") is background.
10. **Separate the obligation from its reason.** A paragraph that states a rule and then argues for it
    ("It aims for 105%. We learned this the hard way: 5,461 emails…") is two blocks: the rule,
    labelled, and the reason, not. Coverage of the guard is then coverage of the rule, not of the
    anecdote, and a case is never linked to a sentence that begins "we learned".
11. **A negative requirement is a requirement.** "It does not close accounts" is a test. *What this
    does not do* sections are labelled like any other.
12. **A rule in a table row is lifted out; the table goes.** A bands table, a rules table, a controls
    table with a *Safeguard* column — each row is a requirement and a cell is unlinkable, so every
    row becomes a labelled list item (a second column of consequence becomes a nested item) and the
    table is not kept beside it. A table of *data* — rates, a worked example's positions — stays a
    table with its caption. The test is whether a case would want to link to a row.
13. **Measured figures are text.** "2.4 seconds", "5,461 emails", "about 151%" are observations from
    one environment, kept in the source's words and never turned into a threshold the source did not
    state.
14. **Never correct the source's facts.** Rewording is free; changing a number, a threshold or a
    formula is not. Reproduce the source's arithmetic from its own formula, and where it does not
    reproduce, the expected value in a case is the **source's**, the case objective says so, and the
    discrepancy goes in the report with both numbers. A formatter that fixes a specification has
    changed what the client signed off without telling them.
15. **A worked example is test data.** It stays on the page, unlabelled, and becomes the first cases,
    with the example's figures as expected results.
16. **Navigation, status and provenance are not specification.** A table of contents, a byline, a
    *Where it currently stands* section, a *Related pages* list, and keys from another tracker
    (`FI-5622`) are dropped from the page and named in the report; the tracker keys and the open
    items also go into the plan's `outOfScopeText`, which is where "not in this release" is stated.
17. **Boundaries are quoted, not resolved.** "150% and above" beside "120 – 150%" is an overlap the
    page carries as written and the report asks about. Choosing an edge is a product decision.

### 2.5 The input side — anything, and the report is half the answer

**The input has no grammar.** The two samples in
[`assets/spec-formatter/`](assets/spec-formatter/README.md) are a Confluence export pasted as text
(headings unmarked, a table of contents duplicated on top, every table flattened to one cell per
line, a status macro rendered as the words "Question Mark") and a plain-language narrative where
every one of twelve guards is a paragraph opening with the rule and continuing with why. Neither is
Markdown. The model's job is reconstruction under §2.4 — a table from its header's column count, a
requirement out of a paragraph of rationale — and the converter's job starts only once the output
grammar (§2.2) has been written. Flexibility lives entirely on the input side; the output has one
shape so that everything downstream (anchors, links, coverage, the round trip) can be exact.

**The output is a working document.** The page is *rewritten*, not reformatted: a reader who has
never seen the source should be able to write a test from any labelled block without opening the
original, and a reader who has should recognise every fact in it. The two example pages were first
produced close to the source's wording and then rewritten to §2.4a; the difference is what the
*Reworded* tables in their reports show.

**The formatter's answer is three things, always:** the page, the bundle, and **the report**. The
report has fixed sections so a reviewer can find the same thing in every ingestion —

| Section | What it names |
|---|---|
| Dropped | Everything in the source that is not on the page, by name (§2.4.16, §2.2 refusals) |
| Reconstructed | Tables rebuilt from flattened cells, and the column count they were rebuilt from |
| Lifted and split | Rows that became list items (§2.4.12), paragraphs split from their rationale (§2.4.10) |
| Reworded | Every rewrite that is more than cosmetic, before → after (§2.4.2) |
| Requirements | The labelled-block count, and the section of the page that holds none and why |
| Arithmetic | Every number the source's own formula does not reproduce, both values (§2.4.14) |
| Open questions | Ambiguities, overlaps, unconfirmed values, references to documents not yet ingested |

— and it is what `ingest-spec` (§3.2 P1, step 4) returns to the person, before anybody accepts
anything. The samples produced eleven open questions between them and one produced six arithmetic
discrepancies, none of which a reviewer would have found by reading the page alone. That is the
argument for the report being mandatory rather than a courtesy: the page is what gets linked to,
and the report is what stops a case being written against a number the specification got wrong.

---

## 3. The processes

A process is an ordered set of steps, each naming the MCP tool and the CLI spelling, the check
between steps, and the traps. They live in `packages/client/src/core/processes.ts` beside the shapes
— one catalogue, the same "computed, not hand-maintained" discipline `guideCopies.test.ts` enforces
for the tool list.

### 3.1 The carriers — three, plus prompts

| Carrier | What | Why it is needed when the others exist |
|---|---|---|
| `tcms_process [name]` | A tool that reads nothing, takes no project, has no gate — `tcms_shape`'s own argument, applied to sequences | The host that surfaces neither resources nor prompts still lists tools. |
| `tcms://process/{name}` | Resource, `text/markdown`, unfiltered by permission like the shapes | The long form. |
| **MCP prompts** — `prompts/list`, `prompts/get` with arguments | `ingest-spec` takes `spec` (the text), `title`, `parent?`; the others take a page key | The one carrier a **person** reaches: Claude Code lists them as slash commands, so "give the LLM some specification" is `/tcms:ingest-spec` and a paste. Advertised without `listChanged`, on the rule the `resources` capability already states. |
| `tcms process [name]` | CLI, beside `tcms format` | Same text, for the person scripting the CLI. |

The `tools` count moves from 44 to 45 and `guideCopies.test.ts` will demand the help guide say so.

### 3.2 The five

**P1 — `ingest-spec`: a specification becomes a page.**
1. `tcms_whoami`, `tcms_orient` — the page's parent, if any, and the vocabulary the cases will need.
2. Translate under §2.4; produce the Markdown.
3. `tcms_write_doc_page {action: 'create', title, parent?, markdown}` — CLI `tcms doc create --title T --from spec.md`.
4. Read the response's `anchors`; return the report (§2.5) with the label → id table.
5. **Stop.** Publishing (`publish`) is a separate act and a person's signal; the process does not take it.

**P2 — `cover-spec`: cases for a page's requirements.**
1. `tcms_open_doc_page {page}` — every block's `links` and `coverage`. A block with a `VERIFIES` link
   already has a claimant; the process proposes against it only when asked to extend, never to
   duplicate.
2. `tcms_shape case`; `tcms_orient` for the priorities, statuses and classification values.
3. For each labelled block: `tcms_write_case {action: 'propose', …, verifies: [{page, block}], tags: ['spec:<slug>']}`.
   The tag is what the plan process uses for membership (§3.3), because a proposal is not a case
   and cannot be put in a plan.
4. Report the proposals filed, by block. **Nothing here becomes a test case**; a person accepts,
   and the links are written at that moment (§5).

**P3 — `plan-spec`: a test plan written for a page.**
1. `tcms_open_doc_page {page}`; `tcms_list_plans` to see whether one already adopts it.
2. `tcms_write_plan {action: 'create', name, milestoneName?, scopeText, outOfScopeText, entryCriteria, exitCriteria, sections, tags: ['spec:<slug>']}` —
   `scopeText` from the page's purpose, `outOfScopeText` from its *Out of scope* section (§2.4.6),
   one `section` per top-level heading with the spec tag, criteria from the closed vocabularies
   `tcms_shape plan` lists. `cases` stays empty: membership resolves through the tag as proposals
   are accepted.
3. `tcms_write_doc_page {action: 'adopt', page, plan}` — the page becomes the plan's document
   (`subjectType: TEST_PLAN`).
4. `status` to `IN_REVIEW` only when told to; `APPROVED` is a person's.

**P4 — `revise-spec`: the round trip.**
1. `tcms_open_doc_page {page, as: 'markdown'}` — every block carries its id anchor.
2. Edit the text; keep the anchors; new statements get labels.
3. `tcms_write_doc_page {action: 'edit', page, markdown, lockVersion}`. A dropped linked block is
   refused (§2.3); the process says to put it back or to say `dropLinked` and report why.

**P5 — `report-spec`: coverage with the page as spine.**
1. `tcms_report {action: 'capabilities'}` — the type and format this deployment has.
2. `generate` the coverage matrix with the page as its spine; `poll`; return the URL. Read the page's
   `coverage` markers first for the in-conversation answer — they read the same column the report
   does and cannot disagree with it.

Every process opens with the same preamble the instructions already carry, and each names the
permission its steps cost, so a refusal maps to the sentence the instructions string gives for it.

---

## 4. The surface changes

All under surface spec §2 — no arm added to a tool spans a gate the tool's reachable roles do not all
hold — and all spelled the same way on both adapters.

### 4.1 `tcms_write_doc_page` — `markdown`, `anchors`, `adopt`, `dropLinked`

- `markdown` is accepted **instead of** `blocks` on `create` and `edit` — both present is a refusal.
- The response carries `anchors` (§2.3) on either input form.
- A new arm **`adopt`** (`doc:edit`, `kind: 'state'`): `{page, plan}` → `PUT /plans/{planId}/document`;
  `{page, plan: null}` → the `DELETE`. It lives here and not on `tcms_write_plan` because the contract
  says the gate is *"`doc:edit`, the same key as editing the page, because it is the same act seen from
  the other end"* — and because putting a `doc:edit` arm on a `plan:*` tool is exactly the two-gate
  span §2 refuses.
- `dropLinked` (boolean, default false) on `edit`.

CLI: `tcms doc create --title T [--parent DOC-1] --from spec.md`, `tcms doc edit DOC-7 --from spec.md [--drop-linked]`,
`tcms doc show DOC-7 --markdown`, `tcms doc adopt DOC-7 --plan TP-3 | --none`.

### 4.2 `tcms_open_doc_page` — `as`

`as: 'blocks' | 'markdown'`, default `blocks` so nothing existing changes. Not both in one response:
a page is bounded at 200K chars and doubling it is the budget §4.6 raised for exactly one copy.

### 4.3 `tcms_write_case propose` — `verifies`

`verifies: [{page, block, relationship?}]` — display key for the page, the block id, `VERIFIES` by
default. Stored on the proposal (§5); the CLI is `tcms case file … --verifies DOC-7:<blockId>`
(repeatable).

### 4.4 `tcms_write_plan` — the other six fields

`scopeText`, `outOfScopeText`, `entryCriteria[]`, `exitCriteria[]`, `sections[]`, `tags[]` on
`create` and `edit`, in the shape catalogue so `tcms_shape plan` and `tcms format plan` describe
them and `violationsOfShape` checks the criteria's closed vocabularies locally. `core/rewrite.ts`'s
scalar/list split covers the three new lists.

### 4.5 `tcms spec import bundle.json` — CLI only

The one composite write, and it is CLI-only for §0.7's reason: it spans `doc:edit`, `case:create`,
`plan:create` and `doc:edit` again, and a person at a terminal reads which step's 403 it was. The
bundle is `{ page: {title, parent?, markdown}, cases: [ <case shape> + verifies: [label] ], plan?: <plan shape> }`
with `verifies` written against the page's **labels**, resolved through `anchors` after step one.
Every create carries an `Idempotency-Key` derived from the bundle's content and the item's index
through the core's existing generator, so re-running a half-finished import replays rather than
duplicates. On MCP the same work is P1 + P2 + P3 as steps — which is the point of having them
written down.

---

## 5. The contract and backend change — `verifies` on a proposal

**The decision.** `AiProposalInput.verifies[]` (`{pageId, blockId, relationship}`) is stored with
the proposal, **shown to the reviewer** as the requirements this case claims to verify, and when the
proposal is accepted the links are written **through `BlockLinkService`** — the ordinary link path, so
the resulting `doc_link` rows are indistinguishable from ones a person made, exactly as the accepted
case is indistinguishable from one a person authored.

**Why not the alternatives.**
~~Let the agent create cases directly, since `Agent` holds `case:create`.~~ The product's rule is
structural and stated on the help page: nothing generated becomes a test case on its own. Not reopened.
~~Have the agent link after acceptance.~~ It is not told about acceptance, and a process whose last
step is "poll `tcms_list_proposals` for days" is not a process.
~~Put the intent into `grounding`.~~ It is displayed and never interpreted, by its own description;
reading a link target out of a free-form object is the second interpretation nobody can validate.

**Mechanism, under the module rule.** `ai-proposals` is `repository`; links are `documentation`;
neither may name the other. So:

- **Validation at accept, synchronously, in `repository`**, by native SQL over `doc_block` — the
  `PlanFacts` / `BoardFacts` pattern, fifth instance — so a target that no longer exists is a **400
  on `verifies[n]`**, the same field-error shape a deleted suite already produces there. The payload
  stays *stored unvalidated*, as the review boundary decided; it is checked at the one moment it
  becomes real.
- **Writing, asynchronously, in `documentation`**, on a `repository.proposal_accepted`
  `CompanyScopedEvent` carrying the case id and the validated targets; the handler calls
  `BlockLinkService`, which raises `documentation.links_changed`, which refreshes the case's
  `relatedDocuments` mirror — the path an external link already takes.
- The accept response is the case, unchanged; the links reach `TestCase.relatedDocuments` a
  projection later, which the contract already says of every link.

**A column, not a field inside `payload`.** `ai_proposal.payload` is *"the proposed case, in the shape
`POST /cases` takes — because accepting hands exactly this to the create path"*; a key that path does
not accept does not belong in it, and `grounding` already sits beside it as its own JSON column for the
same reason. So `verifies` is a third: nullable JSON, `updatable = false` like its siblings, in the
**next migration** — `V14` on 2026-09-07, with `V1`…`V13` in the directory. `CLAUDE.md`'s "exactly one
migration" is stale and must not be read as "put it in `V1`"; `ls` the directory before writing the
number, because `out-of-order` is unset and Testcontainers cannot show a collision.

**Reviewer's screen (`fe`).** `ProposalPage` renders the claimed requirements as page key + block
text, with a *no longer exists* state for a target the read cannot resolve, so the reviewer sees the
400 coming before pressing Accept.

---

## 6. Deliberately not built

- **No composite import tool on MCP** (§4.5's reason, and §3.1's: a composite whose output cannot be
  bounded needs the argument stated, and a five-write composite with partial-failure state is not a
  read).
- **No auto-publish, no auto-accept, no `APPROVED`.** Every process ends where a person's act begins.
- **No inline formatting.** The page stores text; a Markdown renderer for block text is a product
  decision this design does not take.
- **No label stored on the block.** Labels are a file-local convenience resolved to ids on write;
  storing them would be a second identity for a thing whose whole design is one permanent id.
- **No server-side parser** (§2.1) — available later, with this converter as its reference.

---

## 7. Execution breakdown

One stack, one agent, one session per task. Contract first, then backend, then the shared
packages, then the frontend with the close-out riding on it.

| # | Stack | Agent | Deliverable | Depends on | Budget | Landed |
|---|---|---|---|---|---|---|
| T1 | contract | `be-java` | `openapi.yaml`: `AiProposalInput.verifies`, `AiProposal.verifies`, the accept path's `400` on `verifies[n]` documented; both generated copies; `contractPairing` green | — | ~60k: the proposal and link regions of the contract, both regen outputs, fixture updates | `7e49c48c` |
| T2 | `be` | `be-java` | `V14` adds `ai_proposal.verifies`; `verifies` stored, served, validated at accept by native-SQL facts, written by `documentation` on the event; ERD row; `SchemaConventionTest` + ArchUnit green; a test that accepts a proposal and reads the link off the block | T1 | ~120k: `repository` proposal service + accept, `documentation.BlockLinkService`, the facts pattern, event plumbing, the migration, tests | `58f972b3` |
| T3a | `ts` | `tooling-engineer` | `@tcms/client` core: `core/markdown.ts` (both directions, anchors, the linked-block refusal, round-trip test), `core/processes.ts` (the five, rendered like `guideFor`), the plan shape's six fields; `npm run build` in order, lint 0, tests | T1 | ~110k: `shapes.ts` (1.5k lines), `rewrite.ts`, `proposal.ts`, the new files and their tests | `22f118b9` |
| T3b | `ts` | `tooling-engineer` | The adapters: `tcms_write_doc_page` markdown/anchors/adopt/dropLinked, `tcms_open_doc_page as`, `tcms_write_case verifies`, `tcms_write_plan` fields, `tcms_process`, prompts + `tcms://process/*`; CLI `doc create/edit --from`, `doc show --markdown`, `doc adopt`, `case file --verifies`, `plan` flags, `tcms process`, `tcms spec import`; surface spec §3/§6 rows | T3a | ~140k: `mcp/writes.ts`, `mcp/reads.ts`, `server.ts`, `cli/writes.ts`, `cli/reads.ts`, `commands.ts` and their conformance tests — the largest, and split from T3a so neither reads the other's files | `e784e811` |
| T4 | `fe` + docs | `fe-react` | `ProposalPage` shows the claimed requirements; help guide names the 45 tools and the five processes; `features.md` Documentation section; this document's §7 closed out | T3b | ~80k | this commit |

The capability is end-to-end after T3b; T4 is the reviewer's view of it and the words. Between T2
and T3b a proposal can carry `verifies` that no client sends — the backend returns the ordinary
shape, never a fake success, and the plan says so.

**Closed out 2026-09-07. What is built, and what is built and unverified.**

All five tasks landed as designed, with four departures worth carrying forward:

- **T2 found no `ai_proposal` row in the ERD to extend.** That document is the 2026-07-30 revision
  and predates the generation slice, so §4 gained a whole `### ai_proposal` subsection rather than a
  cell.
- **T3b's surface count is 45 tools and 94 CLI commands**, and the new surface is recorded in
  `2026-08-08-mcp-tool-and-command-surface.md` §3.0b and §6 — which outrank §3 and §4 here from now
  on. It also found `DocPageInput.title` missing from every whole-body page edit either adapter had
  ever made, a 400 older than this slice.
- **The processes have four carriers, not three.** MCP prompts are the one a *person* reaches, and
  the help guide leads with that rather than with `tcms_process`.
- **T4 reused `linkRelationshipLabels`** rather than adding a proposal-specific label map: the three
  values are the same three, and the review screen must not spell them differently from the page
  that will carry the link.

**Nothing in `packages/` has been exercised against a live instance.** Every gate there is a double
compared to `openapi.yaml`, so `tcms_process`, the prompts, `tcms spec import`, the Markdown
converter and the adapters' `verifies` arm are *built and unverified* in the sense `CLAUDE.md` means:
the shape of the contract is proven, a handler's answer is not. The backend half (T1–T2) is covered
by `AiProposalEndpointTest` against a real Postgres, and the reviewer's screen (T4) by RTL against a
double — so the one path with an end-to-end proof is *file → accept → link*, and only from the
server's side of the wire.

---

## 8. Open questions for the owner

1. **Is `tcms_process` a fifth carrier too many?** The prompts primitive is the one a person uses,
   the resource is the long form, and the tool exists for hosts with neither. Dropping the tool keeps
   the count at 44 and leaves a minimal host with only the instructions sentence.
2. **Should `should`-statements be labelled?** §2.4.2 labels them and flags them. The alternative —
   label only *shall/must* — produces cleaner coverage numbers and loses the statements most specs
   actually argue about.
3. **Where does the report live?** Today it is the model's answer and nothing else. The open
   questions are the part worth keeping: as remarks on the page's review (the product reviews
   documents since `ReviewSubjectType` gained `DOC`), or as a child page named *Open questions*
   under the specification. The first is closer to what they are; the second needs no new arm.
4. **Label syntax.** `{#label}` is Pandoc's and models know it; `<!-- id -->` survives more renderers.
   The converter can accept both; the guide should teach one.
