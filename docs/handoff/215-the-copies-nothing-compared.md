# §215 — The copies nothing compared

**One phase of work**, `packages/` test-lane only — one new test file, one refactor — plus the two
documents the rule is about, each corrected where it claimed nothing checked it. **No migration, no
contract change, no permission moved, no production code in any stack, and not one byte of either
`schema` copy.** No `backend/` and no `frontend/` byte at all.

§214's own "what is NOT done" named it and called it what it is:

> **`CLAUDE.md`'s and `README.md`'s convenience copies of the matrix are still copies.** Both say in
> as many words that the spec wins and the copies are the bug, and nothing compares either of them
> to anything. That is the same population one corpus over, it is measurable with the parser this
> slice just wrote, and it is the nearest standing candidate.

Taken verbatim, with one of its two clauses overruled — see *Why not in `SeedDataTest`* below.

## What a copy is for, and what nothing was watching

`CLAUDE.md` and `README.md` each print §A3's matrix in full. Both are honest about their status:
*"this is a convenience copy … If they disagree the spec wins and the copies are the bug."* That
sentence settles the **precedence** question and answers the **detection** question not at all — it
tells a reader who has already noticed a disagreement which side to repair, and there is no reader
who notices. The copies exist precisely so that nobody has to open the spec.

`CLAUDE.md`'s own header goes further and names the failure mode:

> 55 permissions, all seeded by `V3__seed_permissions_and_roles.sql`; no migration has added one
> since — though `V51` **moved three grants between roles**, which is a different thing and **the
> one this table gets wrong most easily**.

Three migrations have moved grants under these two tables (`V40`, `V51`, `V52`), each edited both
copies by hand, and each got it right. A copy that survives three hand-edits is not a checked copy;
it is an unchecked one that has not been unlucky yet.

## The population is zero, and that is the finding stated plainly

Measured before the rule was written, the way §201, §204, §213 and §214 each had to be measured
before their clean run could be reported honestly. Parse §A3, parse both copies, compare every cell:

| | Keys | Cells disagreeing with §A3 |
|---|---|---|
| `CLAUDE.md` | 39 `PROJECT` + 9 `COMPANY` + 7 `SYSTEM` = 55 | **0** |
| `README.md` | 39 + 9 + 7 = 55 | **0** |

Both copies agree with the spec on every key and every cell, **qualifier text included**. So this is
a **guard** in §201's and §204's sense — a way two documents could come to disagree is removed,
rather than a disagreement repaired — and the run being green from the first commit is not a save.
Saying so is the point of measuring first; §214 had three cells and one documented fact, and a
slice that reports "clean" without having measured cannot tell those two situations apart.

## Why not in `SeedDataTest`, which already had the parser

§214 built the §A3 parse one stack over and observed, correctly, that pointing it at the copies
would be cheap. It would — and it would put the check in **the suite `ci.yml` does not run**, which
is the thing §214's *own* "what is NOT done" calls the largest item on its list:

> The comparison lives in the **backend** suite and `ci.yml` runs `-x test`. Between a bad spec edit
> and the 01:17 UTC nightly, both `packages/` rules answer confidently out of a wrong document.

A rule comparing two markdown files needs no database, no Testcontainers boot and no Spring context.
Putting it in the backend suite would buy a ~30s container start and a nightly-only observer in
exchange for a `readFileSync`. So the cheap place and the right place are not the same place here,
and the chain both documents describe is unchanged:

```
migration → SeedDataTest → spec → the rules in packages/
```

`SeedDataTest` still owns the only link that sees the seeded rows. This is a third rule hanging off
the spec in the job that runs on every push, beside `tierClaims.test.ts` (prose in four corpora) and
`agentKeys()` (the MCP surface). The window §214 opened stays open and stays §214's: nothing here
narrows it, because nothing here reads the database.

`CLAUDE.md` and `README.md` are **not** added to `corpus.ts`'s `CORPUS`, and that is a decision. That
list is the four *prose* corpora four rules sweep; this reads two named documents for one table each.
A rule that walked every markdown file looking for a permission matrix would find the handoff
narrative's quotations of one, which are records of what was true when they were written — the
argument `corpus.ts` already makes for keeping `docs/` out.

## What is compared, and the three things that are not

**Every cell, both ways, over all three scopes.** Not the six `PROJECT` columns `roleMatrix()`
answers from: `COMPANY` and `SYSTEM` are 16 of the 55 keys, and a copy is wrong about them exactly
as easily.

**The cell's text, not whether it is a grant.** A qualifier narrows a grant without removing it —
`quarantine only` is the one live instance, and `artifact:edit` read `plan scope only` in both copies
until `V52` de-qualified it (§210, the slice before last). A boolean comparison would have called
that cell agreement.

**The key set, both ways.** A copy that quietly drops a row is the way a matrix goes stale that
still reads, to somebody scanning it, like a matrix: a permission that is not printed is a permission
that does not exist.

What is deliberately **not** compared is everything about how a copy is laid out. §A3 groups by
subject area under `#### ` headings with abbreviated columns (`IA`/`CA`/`TL`/…); both copies group by
how widely a permission is held and spell the roles out **in the opposite order**; `CLAUDE.md` packs
several keys into one row with `·`. All three are the same claim in different shapes, the shape is
the copy's own business, and a rule that insisted on one would be a rule against having a
convenience copy at all.

**`CLAUDE.md`'s `SYSTEM` scope is a sentence and is compared anyway.** Seven keys held by one role
is a list, and it is written as one: *"`SYSTEM` scope, Instance Admin only: `company:create` · …"*.
It is still a copy of the table §A3 prints. Excusing it would have left **seven of the 55 keys
unchecked in the copy an agent meets first** — the corpus this repository's own readers actually
read — so the sentence is *declared* (a `ProseRow`, with the roles it grants to) rather than
excused. The declaration fails loudly if the sentence stops matching, which is the same bargain
every by-decision map in this repository makes.

## The parser, and why `roleMatrix()` had to change shape

§214's Java parse keys cells by **column heading** so that one reader serves three tables of
different shapes. `roleMatrix.ts` did not: it matched a six-column header with a literal regex, took
cells by position, and threw away everything but grant/no-grant. All three are right for the question
it answers — *who holds what*, `PROJECT` scope, for a rule about prose — and all three are wrong for
a question about a **copy**.

So the parse is now `specRows()` — the whole `### A3.` … `### A4.` region, keyed by heading, cell
text kept — with `roleMatrix()` derived from it and unchanged in meaning (a `PROJECT` table is one
carrying all six role columns). Three things throw rather than answering thinly, because a parser
that silently reads nothing is indistinguishable from two documents that agree:

- **an unknown column heading** — a seventh tier compared on six columns reads as agreement;
- **a key in two tables** — one of the two is a stray, and merging them would let a copy agree with
  whichever half suited it;
- **a catalogue that is not 55 keys** — the size both copies claim in their own headers.

The existing guards (`TE ≥ 25`, and the Test Lead − Test Engineer delta being `V52`'s exact seven)
are untouched and now also serve as the check that the rewrite reads what the old one read.

## Files

| Path | What |
|---|---|
| `packages/client/src/test/matrixCopies.test.ts` | New. Both copies against §A3, cell by cell, both ways; `COPY_COLUMNS`, `ProseRow`, `matrixSection`, `copyMatrix`. |
| `packages/client/src/test/roleMatrix.ts` | `specRows()`, `specMatrix()`, `SPEC_ROLES`, `CATALOGUE_SIZE`, `NOT_GRANTED`; `roleMatrix()` derived from the new parse; header corrected where it said nothing compared the copies. |
| `CLAUDE.md` | The matrix section's *"nothing compares either … it has not been done"* struck through and corrected — including the half that was overruled, that the check would live in `SeedDataTest`. |
| `README.md` | The convenience-copy note now says the build enforces it. |

## Gates

Run on `shanks`. `backend/` and `frontend/` hold no changed byte, and neither suite reads either
`.md` — the two mentions in `SeedDataTest` and `ContractConventionTest` are prose in a failure
message, not an input.

| Gate | Result |
|---|---|
| `cd packages && npm run build` | clean |
| `npm run lint --workspaces` | 0 warnings, 0 errors |
| `npm run test:run --workspaces` | **323 passed** — 78 cli / **126 client** (+3) / 119 mcp |

**Measured red four ways**, because a rule over a population of zero is otherwise indistinguishable
from a rule that reads nothing: `run:close` flipped to `—` for Test Engineer in `README.md`
(1 cell reported); `instance:audit` deleted from `CLAUDE.md`'s `SYSTEM` sentence (1 key reported —
and it reddened the vacuity guard too, 54 against 55); `artifact:edit` given back `plan scope only`
in `CLAUDE.md` (the qualifier case, reported as a cell); and a seventh column headed `Bot` added to
§A3 itself (the parse refuses to run).

## Verification

Nothing here reaches a server or a screen. No browser row and no mutation row: the slice adds a test
and edits two markdown documents, and §65's queues are for behaviour a gate cannot observe. `§203
PL-1` and §212's `PL-7`/`PL-8` are carried forward unrun.

## What is NOT done

- **§214's window is unchanged and is still the largest thing on the list.** `SeedDataTest` is the
  only link that compares the spec to the seeded rows, it lives in the suite `ci.yml` does not run,
  and this slice adds a **third** `packages/` rule reading that same spec confidently between a bad
  edit and the 01:17 UTC nightly. The two options §214 stated are unchanged: a targeted
  `--tests '*SeedDataTest*'` job on the `tcms-lab` runner (~30s, and the first crack in the
  2026-08-02 `-x test` trade), or a `packages/`-side parse of the migration SQL, which is a small SQL
  interpreter and is not obviously honest.
- **Nothing still reads the migration.** Unchanged from §214, and one document further from it: the
  seed is now compared to *three* documents and to nothing that says what was intended.
- **The copies are compared on the matrix and on nothing else.** Both carry paragraphs of prose about
  the catalogue under their tables — the five bullets in `CLAUDE.md`, the notes section in
  `README.md` — and those are exactly the tier claims `tierClaims.test.ts` reads, in a corpus it does
  not sweep. Adding these two files to `CORPUS` is a different decision from this one (it would put
  the whole of `CLAUDE.md` and `README.md` under four rules, not one) and it is the nearest standing
  candidate that touches the same documents.
- **`docs/specs/openapi.yaml` prints no matrix and is not read here.** Its `PermissionKey` enum is
  compared to the seed by `SeedDataTest.catalogueMatchesTheContract` — the keys, never the grants,
  which is §214's observation and still true.
- **The three §212 items are untouched**, two of them decisions: the `{@link Type#member}` citations
  nothing resolves, artifacts having no MCP surface, `library:manage` being off it.
- **The tool count is still not pinned anywhere** (§213), unchanged.
