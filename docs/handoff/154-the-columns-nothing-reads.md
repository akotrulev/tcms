# §154 — the columns nothing reads, and the check that read its own argument

**Date:** 2026-08-09 · **Branch:** `master` · **One phase** · **`backend/` only: no migration, no
contract change, no frontend or `packages/` source.**

The third question in §152's and §153's family, asked at the column: **which mapped column does
nothing read back?** §152 asked which repository methods have no caller, §153 which bean methods
only a test calls; this one is the tally §116, §117, §132 and §151 each kept by hand, one instance
per slice, and it is now `ArchitectureRulesTest.everyMappedColumnHasAReader`.

**The answer is thirteen.**

## Why this rule reads source text, which no other rule here does

A column's reader is usually not Java. It is JPQL in a `@Query` string, native SQL in a text block
inside a service, or a Spring Data method *name* from which the query is derived — and none of the
three is a field access. **Measured before it was written: over bytecode alone the population is
212 of 635 fields**, which is not a rule, it is a shrug.

So the rule builds a corpus out of `src/main/java` **and** `src/test/java` and counts four things as
a reader: the column name as a whole word in any file but the entity's own, the field name inside a
string literal, the capitalised field name in any name a repository declares, or a Java read.
`ConfigConventionTest` (§108) is the precedent — a check that reads the *text* rather than a
resolved value, and the same lesson, since every other check in the file observes what the compiler
produced while the defect lives in what somebody wrote.

Three things are stripped first and each was measured:

- **A mapping annotation is not a read.** Without stripping `@Column(name = …)`, every entity
  declaring its own `created_at` marks every *other* entity's `created_at` live.
- **A comment is not a read**, and this one is uncomfortable: **nineteen columns read as live only
  because some javadoc mentioned them.** In a repository whose comments carry the reasoning, prose
  standing in for code is §152's exact finding.
- **An `INSERT`'s column list is a write.** Seven columns read as live off the fixture rows that
  create them. A fixture inserting `added_by` is not a reader of `added_by`.

**And no exemption for `@Id`, `@Version` or `company_id`** — all three were carved out of an earlier
draft and all three turned out to be matched by the corpus anyway: an id by `findById`'s derived
name, `lock_version` by the queries that name it, `company_id` by the RLS predicate and the
Hibernate filter condition. A special case the general rule already covers is a special case that
will one day be wrong.

## The finding under the finding: the check read its own argument

`READERLESS_BY_DECISION` is §153's object at the column — an exact population with an argument for
each member, failing in **both** directions, so an argument cannot outlive the code it argues about.
Every key in it is the string `"Entity.field"`.

**Which means each argument for keeping a column was itself a string literal naming that column,
and the corpus read the argument as the reader.** Five of the thirteen members deleted themselves.
The rule ran green over a population it had silently halved, and the measured count went **7 → 13**
the moment the file was excluded from its own corpus.

That is the third time in three slices: §152 found a javadoc standing in for the seam it described,
§153 found a prefix match that read `OutboxDispatchTest` as a self-call of `Outbox` and hid the
finding, and this one is inside the check written to end the first two. **A check that quotes what
it is checking is a check that can pass by quotation.**

The first draft had §152's other lesson too: the repository-method corpus matched
`findBy*`/`countBy*`/`existsBy*`, so `ReportJob.requestedAt` was reported readerless while
`findFirstByStatusOrderByRequestedAtAsc` reads it — the whole scheduler reads it. A hand-written
pattern standing in for a population, inside the check written to replace hand-written patterns.

## The thirteen, and they are two different defects

**Written and readerless — §151's shape, and the expensive one.** A write per row recording
something no screen, report or query has ever asked for, free to be wrong for as long as nobody
looks. This is exactly how `release_sort_key` sorted a release below its own release candidate from
M0 to §151 with 2389 tests green.

| Column | What it costs |
|---|---|
| `IngestionJob.rawStorageKey` | **The upload cannot be retrieved.** §117 stores the raw CI file *and* the key that would find it again; the package-private getter has no caller in main or test. |
| `AppUser.locale` · `AppUser.timezone` | Constants `"en"` and `"UTC"`, written by `invite` and never read. `AppUser`'s own `@Version` javadoc reasons about "when someone edited their own timezone" — **an act this product cannot perform.** |
| `DocPage.spaceId` | `V1 NOT NULL`, written on every page. `DocSpace`'s header states the other half: the space "is not on the wire and has no endpoint". |
| `SuiteCase.addedAt` · `addedBy` | §98's *added* arm records who filed a case and when, on every add. `addedBy` is a caller-supplied actor id carried endpoint→service→database so that nothing can read who did it. |
| `CompanyMembership.invitedAt` · `invitedBy` | `SuiteCase`'s shape exactly, from `invite()` and `reinvite()`. `UserView` is the contract's `User` and carries neither, so the reader is a contract change. |
| `CaseHealth.incomplete` | **The reference entry** — its argument was already at the field, in the shape the rule asks of the other twelve. |

**Writerless as well — §116's cheaper one.** A column nobody fills is a column nobody is misled by,
and the whole cost is four javadocs describing a state the database has never been in:
`DocBlock.anchorSlug`, `ReportDefinition.scheduleCron`, `ReportDefinition.sharedWithTeamId`,
`AuditEvent.bulkOperationId` — whose "set when this event is one of many from a single bulk
operation" has never once been true, its field, mapping and getter being its only three mentions
anywhere.

**`ReportArtifact.generatedAt` is the near miss, and it is recorded at the field rather than
pinned**: no product code reads it, but a test selects the column to assert what a generate left
behind, which counts under §152's rule that a seam whose consumer is a test is a seam with a
consumer. Same for `AuditEvent.actorTokenId` — §132 spent a whole slice giving it a writer and
`IngestionEndpointTest`'s `SELECT actor_token_id FROM audit_event` is its only reader.

## What the rule deliberately cannot see, and the next slice

The corpus is text and has no idea which table a statement is about, so a native query naming
*another* table's `lock_version` marks this one read. That over-counting is on purpose and it runs
one way: a rule that reports a field something really reads earns an ignore-list entry and is then
ignored.

**What it costs is named where it is paid.** `test_run.lock_version` and `work_item.lock_version`
are `NOT NULL DEFAULT 0` columns, mapped as plain columns **with no `@Version`**, never incremented
and never read — while nineteen other entities carry `@Version` on the identically-named column,
`V1`'s header says the column exists "for optimistic locking on concurrently-edited entities", and
`RunController` itself cites ERD §5 that "a run is worked by several testers at once by design". Two
concurrent run edits are last-write-wins with no detection; `RunController.onWriteRace` already
exists and has never been reachable. **That is the recorded next slice** — it is a behaviour change
on a concurrently-edited aggregate and wants its own tests, so it was not taken here. `RL-7` in the
mutation queue is its check.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | see the handoff index — §154 adds **+1 test, no new class**: one rule in the existing `ArchitectureRulesTest`. It is the file's **first rule that reads source text rather than bytecode**, and the second (after §153's) to pin an exact population rather than assert an empty one. |
| `frontend/`, `packages/` | unchanged — not run |

Rows `RL-1`…`RL-7` in `docs/verification/backend-mutation-checks.md`; **`RL-1` and `RL-2` run**, both
red for the right reason and reverted. **No browser row — nothing renders differently**: one test
rule, and thirteen javadocs corrected at the fields they describe.
