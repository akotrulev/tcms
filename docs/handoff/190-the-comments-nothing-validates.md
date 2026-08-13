# §190 — the comments nothing validates, and the two that named a dropped column

**One phase, `backend/` only: one migration (`V49`), one new test class, one javadoc. No contract
change, no `schema.d.ts` regeneration, nothing in `frontend/src` or `packages/`.** §189's list,
taken from the first item an agent can do alone: §186's *"`V1`'s comment on `suite_condition` still
describes the tree §98 deleted — a new migration carrying nothing but `COMMENT ON`."*

## The item was one comment; the slice is the instrument that says whether it was the only one

§186 named a defect it had found by reading. This repository's standing rule is that **a
schema-wide invariant needs a computed check, not a careful reading** — the rule §108 paid the most
for and §156, §161, §162, §165 and §170 each re-earned. Writing the migration alone would have
closed the one instance somebody happened to notice while working next to it, which is the exact
shape those five slices were about.

So the deliverable is `SchemaCommentTest`, and the migration is what it reported.

**This schema's comments carry the *why*.** `V1` says so about itself, and most of its arguments —
why `case_classification.dimension_id` is denormalized, why SYSTEM-scope grants are a separate
table, what a `case_condition` snapshot is for — exist in no other artifact. They are also **the one
part of the DDL that nothing in this repository reads**: `hibernate.ddl-auto: validate` compares
columns and types, `SchemaConventionTest` compares `company_id`, policies and index leading edges,
`RepositoryTransactionTest` reads annotations. Every gate reads structure. A sentence is observed by
nobody, which is why a comment can describe a mechanism that has not existed for fifteen sections
and no run of anything gets slower, louder or redder.

## What it found: two, out of 195 comment statements

| Comment | Says | Since |
|---|---|---|
| `suite_condition` | "Inherited by every case beneath the suite. Resolution order: inherited (**walking `suite.path` upward**) then the case own conditions, deduped by `condition_id`" | `V33` (§98) dropped `suite.path` **and** `suite.parent_id` |
| `role_assignment` | "unless the user has an ACTIVE `company_membership` for **`project.company`**" — twice | `V1`; the column has always been `company_id` |

**The first is a stale claim and the second is a shorthand, and the check cannot tell them apart.**
That is the argument for repairing both rather than exempting the second: neither can a reader who
greps for `project.company` to find out what reads it, and an exemption for prose is the exemption
the next stale identifier hides behind (§162 — an ignore list naming the wrong mechanism reads as
coverage).

**The first is worse than a dead column name, in two ways the migration says at length.** "Every
case beneath the suite" is not how membership works: a suite is flat and has three arms (filed,
added, tagged), and inheritance reaches **exactly one of them** — a decision with an argument
behind it in `CaseConditions.ancestry` (*filing says where a case lives and the standing rules of
that place apply to it; a tag rule says a collection wants to see it, which is not a claim about how
the case is run*). A reader implementing the comment would widen the model by way of a typo. And the
comment never mentioned `case_condition_optout` — the one table that takes a row *out* of the
resolved list, and the table §187 gave its first writer.

**`V33` rewrote `suite`'s comment in the same migration that dropped the column, and did not look
one table over.** Nothing could have told it to.

## The claim had been quoted, which is how a stale comment becomes load-bearing

`CaseConditions` is *the one implementation of the §6f resolution order*, and its class header
opened by **quoting the comment as the statement of the rule**: `V1`'s comment on `suite_condition`
states it: "inherited (walking suite.path upward)…". Ten lines below, `ancestry`'s own javadoc says
the walk is gone and explains why the method survives its own name — **so the file both cited the
tree and denied it**, and the citation was at the top. §152's finding, one artifact over: a javadoc
is evidence nothing has ever checked, and the place a stale claim gets most convincing is the file
that supersedes it.

## Two rules, because there are two ways to name a thing

`SchemaCommentTest` reads every comment out of `pg_description` — the **live** text, so a comment a
later migration replaced is not in the corpus, because a superseded comment is not one anybody
reads.

- **Qualified** (`table.column`) resolves the right half against that table's live columns. Precise
  enough to say which table the reference is wrong about, and it is the form a stale column
  reference almost always takes. 21 references resolve; 3 did not, and they are the two comments
  above.
- **Bare** (any `snake_case` token) resolves against every name this database declares — relations,
  columns, constraints, triggers, types, and **functions in any namespace**, so `to_tsvector`,
  `jsonb_to_tsvector` and `current_setting` resolve as what they are instead of as three
  exemptions. This is the rule that catches a comment naming a dropped *table*, which the qualified
  rule structurally cannot. It reports **nothing** today, which is the expected result for a
  population rule: it is coverage for the next migration.

**Halves of a dotted name whose left side is not a table are skipped, and that is the division of
labour rather than a carve-out.** `documentation.links_changed` is the outbox event type §167 added;
`app.current_company` is the RLS GUC; `plan §6f` and `docs/specs` are documents. Asking *which
namespace* is a better answer than exempting the word, and it took `NOT_A_SCHEMA_IDENTIFIER` **from
three entries to one** — `pass_rate`, a metric name inside `test_plan.exit_criteria`'s JSON example,
the only token here with no namespace to ask about.

## THE MUTATION THAT WAS WRITTEN TO CONFIRM THE RULE FIRES AND PROVED THE OPPOSITE

`§190 SM-2` names a table that does not exist, at the end of a sentence: *"…through
`suite_parent_link`."* It was expected red. **It was green.**

The skip asked only whether the next character was a dot. A full stop is a dot, so a
sentence-final identifier was read as the left half of a qualified name and handed to a rule that
skips unknown left halves. **A sentence-final identifier is the most ordinary way a comment names a
table** — the bare rule would have shipped green, scanning 118 tokens, and blind to the population
it exists for. Found by mutating, not by reading, and one character of intent away from a check
that reports nothing forever.

## The second thing the pins caught: the corpus was empty on the first run

The catalog was read in `@BeforeAll`, and at that moment **this schema has no tables**: the Spring
context — and therefore Flyway — is created when the test instance is post-processed, which is
*after* the class-level callback. `pg_description` had 5286 rows, every one of them `pg_catalog`'s
own, and `public` had zero tables.

All four rules failed, **every one of them on its corpus pin rather than on its subject**. Without
the pins (`≥ 150` comments, `≥ 15` resolved references, `≥ 100` tokens scanned) the class would have
passed on the first run and every run after it, checking nothing — §154's shape, met on the day the
check was written rather than two years later. `§190 SM-3` is the row that reproduces it.

## What shipped

**Phase 1** (`48218a4`):

- **`V49__comments_that_name_live_columns.sql`** — two `COMMENT ON TABLE` statements and no
  structure. `suite_condition`'s says flat, says *filed* and names both other arms as excluded, and
  names `case_condition_optout` and why the decline is keyed on `suite_condition.id`;
  `role_assignment`'s is `V1`'s verbatim with two words changed, because `COMMENT ON` replaces the
  whole string.
- **`SchemaCommentTest`** — four rules: the two above, `everyExemptionStillExemptsSomething` in
  **both** directions (dead entries *and* entries shadowing a live name), and the corpus pin.
- **`CaseConditions`'s header** stops quoting the deleted sentence and quotes `V49` instead, saying
  in place that the order is written for a chain that has had one element since §98.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses:

| | |
|---|---|
| `./gradlew build` | ✅ **SUCCESSFUL in 7m22s** |
| tests | **2636 · 531 classes · 0 failures** (+4, one new class) |

`frontend/` and `packages/` were not rebuilt: neither was touched, and no generated `schema.d.ts`
moved because the contract did not.

## Verification

**Six mutation rows, `§190 SM-1`…`SM-6`** — `docs/verification/backend-mutation-checks.md`.
`SM-1`…`SM-3` are **RUN** (the mutation-verification of a check written in this slice, §175/§188's
precedent); `SM-4`…`SM-6` are **NOT RUN** (§65). `SM-5` is recorded as a **hole**: a dropped table
referenced in *dotted* form is invisible to both rules, and closing it would mean demanding that
every module and event name in every sentence be a table.

**No browser row**: nothing in `frontend/src` changed, and a table comment renders on no screen.

## The next slice

§189's list, minus what this took:

- **resume the browser queue at `A2 — plans`** — unchanged since §186, and still carrying the seven
  `M2` rows that need the suite-condition fixture `§186 A1-2` authors.
- **a third `packages/` live pass** — §189's two write arms and three read commands are all
  stub-observed, and `§189 CN-3` cannot be closed by a stub.
- §187's, untaken: **the inert decline is state no screen can show and no test can reach.**
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175); **a run has no edit endpoint at all** (§177);
  §184's *what the SPA computes from a contract enum the server also computes*; §185's *a rule that
  fails when a test reads a path outside `src/` that `build.gradle.kts` does not declare as an
  input*; §188's `= null` writer-rule gap and its `releaseSortKey` observation; §189's *a proposal
  cannot carry a decline*.

**And one this slice opened.** **The same question one artifact over: `V1` is 64 tables of prose and
nothing reads any of it.** This slice checked *identifiers*, which is the half a catalog can settle.
The other half — a comment whose **argument** expired — is what §160, §161 and §162 each found by
hand in a different corpus, and the only instrument this repository has for it is a person
re-deriving one claim at a time (§154's redo, item 2 of the open actions). `suite_condition`'s
comment failed on both halves at once and only the first was detectable. Whether the second half
has any computable form at all is the question; **"it does not, and here is why" is a legitimate
answer** and would be worth writing down once rather than re-asking every slice.
