# §156 — the columns nothing writes, and the all-clear five checks never earned

**2026-08-09 · one phase · `backend/` plus one frontend sentence: no migration, no contract change,
no `packages/` source.**

§154 asked which mapped column nothing *reads*. This is the same question at the other end, and it
is the older of the two: **§116, §117, §132 and §151 each found one instance of it by hand**, one
slice at a time, and each wrote the *instance* down rather than the question. §116's own phrasing —
"three `V1` tables had never had a writer" — is this rule at table granularity, answered by a person
who happened to be looking at those tables.

`ArchitectureRulesTest.everyMappedColumnHasAWriter` is the fifth rule in §152/§153/§154/§155's
family and the deliverable. **The answer is fourteen columns**, and unlike the four before it this
one found something a user can see.

## The rule reads bytecode first, which is the exact mirror of why §154 reads text

§154 is the only rule in that file that reads production *source text*, and the reason is at the
rule: a column's reader is usually a query, and a query is a string. **A writer is an assignment,
and an assignment is what bytecode records** — so this rule's substance is `isWrittenInJava`, and
the SQL arm is the remainder rather than the point.

The remainder is still needed, because this codebase writes `case_health` through native
`INSERT … ON CONFLICT` and starts a run through JPQL `UPDATE`, and neither is a field access. Both
spellings are matched — the **column** name for native SQL, the **field** name for JPQL, because
`startIfNotStarted` names `r.startedAt` and `V1` names `started_at`. The `.sql` files are in the
corpus too, which §154 has no need of: a migration that *backfills* a column is a writer of it
(`V44` is the worked example) and one that only creates it is not, because `CREATE TABLE` is neither
verb.

**Only `INSERT` and `UPDATE` statements are in the corpus, which is the exact complement of §154's
`withoutWrites`.** There, an insert's column list is stripped because it is not a read; here it is
the only thing kept.

## The first draft over-matched, and in this direction that hides findings

§154's corpus joins a file's string literals and searches the join, which is right for a
word-boundary read. Here a statement runs to the next `;`, because that is where a statement ends —
and a literal with no terminator then swallows every later literal in its file. `DocPageEndpointTest`
has exactly one: `"UPDATE key_sequence SET next_value = 1"`. Everything after it in that file counted
as part of an `UPDATE`, and **`case_health.unlinked` read as written because an `@DisplayName`
containing the word "unlinked" was inside the swallowed region.**

Two of the fourteen were invisible on the first run. `writesInLiterals` is the bound — one literal
at a time — and the asymmetry is worth stating because it is the reason this bug mattered and §154's
equivalent does not: **§154's over-match costs a missed finding it will catch the next time the
corpus tightens; this one costs a finding that never appears at all.**

That is the fourth consecutive slice in which the check written to replace hand-greps had the defect
it was written to find. §152's javadoc stood in for its seam, §153's prefix match hid `Outbox.record`,
§154's `READERLESS_BY_DECISION` read its own argument as a reader — and this one read a test's
display name as a writer.

## It corrected §154 rather than only extending it

`READERLESS_BY_DECISION`'s javadoc sorts its thirteen members into "written and readerless" (§151's
expensive shape) and "writerless as well" (§116's cheap one) — **by hand, one slice after arguing
that hand-kept tallies are what this family exists to replace.** It put `IngestionJob.rawStorageKey`
in the expensive half:

> the blob key of the raw CI file §117 stores … **The bytes are written, the key that would find
> them again is written**, and no endpoint, service or test reads either.

Neither half is true. `IngestionJob.completed` is the one factory and takes no such parameter, so
the field is assigned nowhere — and the bytes are not stored either, which **the class header fifty
lines above it has said since §67**: *"there is no adapter … so that column stays null and says so."*
Two javadocs in one file disagreed about the same column for a slice, and the wrong one was written
by the slice about hand-kept tallies being wrong. `CaseHealth.incomplete` was misfiled the same way.
Both are corrected at the field and in both maps.

The conclusion §154 drew stands and is the only thing about it that was load-bearing: **there is no
retrievable copy of what CI uploaded.**

## The fourteen, and why the overlap is the point of running both rules

Six are in `READERLESS_BY_DECISION` as well, which is the harmless half — a column nobody fills and
nobody consults costs a javadoc describing a state the database has never been in. **The eight this
rule sees alone are the expensive half, and they are invisible to §154 by construction**: a column
with a reader passes that rule no matter what fills it. Something downstream is drawing a conclusion
from a value the product never produces.

| Column | What it is |
|---|---|
| `AuditEvent.bulkOperationId` · `DocBlock.anchorSlug` · `ReportDefinition.scheduleCron` · `ReportDefinition.sharedWithTeamId` | dead at both ends — §154's four, unchanged |
| `IngestionJob.rawStorageKey` · `CaseHealth.incomplete` | dead at both ends, and **misfiled by §154 as written** |
| `IngestionJob.error` | the detail of a `FAILED` job, and this lane never writes `FAILED` — argued at the class header since §117 and the one member whose argument predates the rule |
| `CompanyMembership.avatarUrl` | no upload exists; already stated on the contract side by `api.UserRef` ("null for everyone today") and now stated at the column |
| `Project.archivedAt` | **a mutator with no caller** |
| `CaseHealth.outdatedSpecChanged` · `staleApproval` · `conditionDrift` · `unlinked` · `orphaned` | **five of the ten health signals, rendered as chips and counted in facets, that no recompute writes** |

## `Project.archivedAt` — §153's shape, at a column, on a class §153 could not see

`Project.archive(now)` exists, is correct, and **has no caller**: `openapi.yaml` declares no archive
operation, so no request can reach it. §153's `everyPublicBeanMethodIsExercisedInProduction` is the
rule for exactly this and does not apply — `Project` is an entity, not a Spring bean.

The read half is finished and on the wire in the other direction: **`ProjectView.archived` is
`required` by the contract**, derived from this column, and `false` in every deployment there has
ever been. `ProjectWriteRequest`'s header already argues the adjacent half — why create and edit
must *not* accept `archived` ("a create that could set `archived` would let a project be born
retired") — so what is missing is one operation.

Kept rather than deleted, and the mutator with it: removing `archive` leaves `isArchived` a constant
while the contract still promises the field, which is the same state one layer down. The argument is
at the field.

## The finding a user can see: the dashboard's all-clear

`case_health`'s class header opened with **"Nothing writes this yet, and the endpoint is correct
anyway"**, and the argument under it was sound: the table is empty, so every signal reads "not
computed", and `computed_at` is null, so the SPA renders a third panel saying so rather than an
all-clear. `DashboardService`, `HealthSignal`, `CaseHealthRecomputeService`, `DashboardPage.tsx` and
`HealthPanel.test.tsx` each restate that premise in their own words. **The SPA's version is the
sharpest and the most wrong:**

> `healthComputedAt` … is `null` while `case_health` has no writer, **which is every deployment
> today**.

It has three writers. `ExecutionFacts` writes four signals (§53, §120), `ExecutionSource` writes
`failingRepeatedly`, all three stamp `computed_at` in the same statement, and
`CaseHealthScheduler` is `@ConditionalOnProperty(matchIfMissing = true)` on a one-hour fixed delay
with a two-minute initial delay. **So `healthComputedAt` is non-null within two minutes of any
deployment starting, and has been since §53.**

What that changes is what a `false` *means*. On an absent row every signal reads "not computed",
which is honest. On a row that exists, the five signals nothing derives are `NOT NULL` columns
holding their default, and the read path cannot tell that from a computation that ran and passed.
The panel's middle branch — the one its own comment called unreachable — is the branch every
deployment lands in, and it read:

> **Nothing needs attention.** Last checked 9 Aug 2026, 19:14.

That is a claim about the project made on the strength of five checks that had never run. §46's
`met: null` rendering as a failed gate is the same defect class, and §148's *"Not found — Not
Found"* is the same slice shape: a reassuring sentence whose premise expired somewhere else.

**The sentence is narrowed rather than the branch changed**, and the reason is a rule this repo
already has: the panel cannot know *which* signals the server derives, and a list of them in the SPA
is the second implementation of a server fact. What it can honestly say is what it knows —

> **No health signal was raised.** Last checked 9 Aug 2026, 19:14.

A statement about the checks instead of about the project. Naming the gap outright needs the server
to say which signals it computes, which is a contract change and a decision; **the five derivations
themselves are the real fix**, each owned by the module that can see its cause, and neither is this
slice.

Five documents that asserted the expired premise are struck through and corrected in this commit —
`CaseHealth`, `HealthSignal`, `DashboardService`, `CaseHealthRecomputeService`, `DashboardPage.tsx`
and its test's header. §145's lesson, seventh instance.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL — 2413 tests across 501 result classes, 0 failures.** §156 adds **+1 test, no new class**: one rule in `ArchitectureRulesTest`. |
| `cd frontend && npm run build` | clean, 0 errors |
| `npm run lint` | 0 warnings |
| `npm run test:run` | **1205 passed / 1205 across 92 files in 214 s** — +2 (`HealthPanel.test.tsx`, one of them the copy pin). **The §127 timeout cliff did not recur in this run.** |
| `packages/` | unchanged — not run |

Rows `WL-1`…`WL-7` in `docs/verification/backend-mutation-checks.md`, none run — the slice's own
red run measured the population (12, then 14 after the corpus fix), which is `WL-3`'s content
observed rather than driven. **One browser row block, `HP-1`…`HP-3`**, in lane 4 beside `L4-23`, and
it needs no fixture: the state it drives is what a freshly provisioned company is already in.

## What is next

Unchanged in kind from §155: the browser queue, `TeamRef`, another re-derivation, or the unrun
mutation rows. Named here on the way out, in the order of what this slice found:

1. **The five health derivations.** Each needs the module that owns its cause (`documentation` for
   `unlinked` and `outdatedSpecChanged`, `repository` for `staleApproval` and `orphaned`,
   `repository` again for `conditionDrift`). Any one of them is a slice; all five is a milestone.
2. **An archive operation for a project**, or the decision not to have one — `archived` is a
   required contract field with no way to become true.
3. **`IngestionJob.rawStorageKey`**, still: §67's port plus a read operation, and now known to have
   no writer either.
