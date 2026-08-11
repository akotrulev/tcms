# §166 — the result every case said it never had, and the seam that stayed write-only

**2026-08-11 · one phase · `backend/` + contract + both `schema.d.ts` copies + one migration
(`V45`): no frontend or `packages/` source changed, one generated type changed.**

§165 named two product gaps it did not close and argued both at the right obstacle for the first
time. This is the larger of the two: **`TestCaseSummary.lastResult` was declared in `openapi.yaml`
from M0 and served by nothing**, so `CaseGrid`'s Last result column and the case detail's result
chip read **"Never run" for every case in the product** — executed ones included, and including a
case a tester had recorded a result on seconds earlier.

That is not an empty column. It is a confident falsehood on the busiest screen in the product, and
it has been one since `execution` shipped in §117.

## The obstacle was real and §165 named it; the repair is the shape it named

`repository` may not import `execution` (plan §3). The four expired arguments §165 struck said the
module "does not exist" — 119 files — and the one live argument, forty lines away in
`repository/package-info.java`, said the thing that was true: *"the same rule is what keeps
`TestCaseSummary.lastResult` off the wire today rather than reaching for `test_execution`
directly."*

So the value arrives the way `suite_run_summary` does — **a read model fed by an event** (§3 lesson
4). `ExecutionFacts` derives it on `execution.results_changed` (the existing coalesced outbox event,
already fanned out to this module's `HealthRecomputeOnResultsChanged`) and on the hourly schedule,
writes it into `case_health`, and the request path reads a table this module owns. **Nothing on a
request path names `test_execution`**, which is the property the rule was ever about.

## The load-bearing decision: three columns on `case_health`, not a `case_last_result` table

This is the one thing in the slice worth disagreeing with, so it is argued at length in `V45`'s
header and stated here in short.

A separate table would have agreed with `case_health` on **everything that identifies a row** —
same primary key, same population (every live case), same writer, same refresh trigger, same
company. What it would not have agreed with is the answer:

```
never_executed   = the newest recorded execution does not exist
long_unexecuted  = the newest recorded execution is older than the cutoff
last_executed_at = the newest recorded execution
```

**All three are one fact.** They are rendered inches apart on one `CaseGrid` row — the health chips
and the Last result cell — so two derivations of "has this case ever run" is two answers to one
question on one screen, each plausible, with nothing on the page saying which is right. That is
§52's `entries`/`runs`, §81's review cards and §98's suite arms again, and here it was avoidable for
free: **one lateral, one row, five columns**, and the agreement is structural rather than
maintained.

Two smaller consequences fall out of the same choice. The read path costs **no additional query** —
`CaseSummaries` already loads these rows for the health chips, in one bulk lookup for the whole
page, so a separate table would have made this a fifth. And `V45` is a three-column `ALTER` on a
table already inside `FORCE ROW LEVEL SECURITY`: no new table, no new policy, the count stays where
V37 left it.

**What it costs is a table name narrower than its contents.** `case_health` already carried two
non-signals (`computed_at`, `incomplete`); it now carries three more. The honest alternative was
*renaming the table*, not splitting it, and that is a bigger change than the gap it tidies.

## `max()` became a row, and that needed one predicate nobody would have missed

The lateral used to be `SELECT max(e.executed_at)`. It cannot carry the status and the release
beside the moment, so it orders and limits instead — and **that swap is not neutral**:

> **Postgres sorts NULLs FIRST under `ORDER BY … DESC`.** A row with a status and no `executed_at`
> would have won, and `max()` — which skips nulls — would never have chosen it.

Hence the explicit `executed_at IS NOT NULL`, which restores the old statement's semantics exactly
rather than approximately. **That state is unreachable today** (`TestExecution.record()` sets both
columns together), which is precisely why the predicate is written rather than reasoned about: no
test could ever have caught its absence, because no fixture may legitimately produce the row that
would expose it. The test helper says so at the parameter that would have built one.

`status_id IS NOT NULL` is now redundant **three** ways — `max()`'s old note, `executed_at`'s new
one, and the inner `JOIN case_status` the kind needs. It stays for the reason §53 recorded, plus
one: a predicate that survives only as a side effect of somebody else's fetch is one refactor away
from gone.

The tiebreak is `id DESC`, and it is the one thing here with **no test and no possible test** — see
the queue.

## The contract changed by one word, and it is `required`

The shape was already right: `lastResult` has been `type: object, nullable: true` with all three
members required since M0. The server has one rendering path and Jackson's default inclusion, so it
**always emits the key** — "always present, sometimes null", which is the sentence the `TestCase`
branch of this same schema already writes out for its own nullable scalars. Leaving it optional
would have meant a consumer still cannot tell *"this case has never run"* from *"this endpoint does
not serve the field"*, which is the exact distinction that was live and unanswerable until now.

Both `schema.d.ts` copies change by one character: `lastResult?:` → `lastResult:`. **The frontend
needed no source change and no fixture change** — `CaseGrid` and `CaseDetailPage` have rendered
`row.lastResult?.statusKind ? … : 'Never run'` correctly the whole time, and the one test fixture
that names the field already carried `lastResult: null`.

**The two `MEMBER_DIVERGENCE_BY_DECISION` entries are deleted, not edited.** That map is pinned in
both directions (§165), so a closed divergence keeping its argument fails the build — deleting them
*is* the build passing again, which is that property exercised for the first time on a divergence
somebody actually closed. Two remain, and neither is `lastResult`'s twin: `relatedDocuments` needs a
**table** rather than a column, because a list of links is not one.

## The seam stayed write-only, and one sentence in it had to be struck

`ExecutionFacts` opened with *"Two facts live on the other side of this seam, and **neither is ever
needed as a value**"*. `lastResult` needs the first one as a value, so the clause is struck — and
the property it was protecting is not. What crosses is still a statement, not a row: the value is
written into `case_health` on the tick and the request path reads it from there.

The header now names the edit that *would* end the property, because it is the tempting one: a
value-returning helper here — *"just fetch the last result for these forty case ids"* — would
satisfy every ArchUnit rule in this repository and give away the thing they protect. On the detail
endpoint it is a single row, which is exactly why somebody will propose it.

## Tests

`CaseHealthRecomputeTest`, **+6 tests, +1 nested class, +1 fixture case**. Three assertions reuse
the booleans' own fixtures (`NEVER`, `PLANNED`, `REVISITED`) — asserting the stored *value* over
them is what proves the five columns come out of one statement rather than merely agreeing today.

- **`REVISITED`'s two rows now carry opposite kinds.** The stale row is the **passing** one, so an
  implementation that takes the wrong row reports a green case rather than merely an old date. A
  fixture whose two candidates agreed could not tell "the newest wins" from "there was only ever one
  answer".
- **`theBooleanAndTheValueCannotDisagree` sweeps every live case in the company**, not the
  interesting three. `never_executed` must be exactly `last_executed_at IS NULL`, and the kind must
  be null exactly when the moment is. It cannot be a `CHECK` constraint: every pre-existing row
  violates it the instant `V45` runs, and **a migration cannot reach those rows to fix them** —
  Flyway runs as `tcms_owner`, which does not bypass `FORCE ROW LEVEL SECURITY`, so a backfill here
  reports a confident zero. The job owns the backfill because the job is the only thing that opens a
  company.
- **`aRemovedExecutionClearsTheResult`** is the only test that reaches the `ON CONFLICT` branch with
  a null. Updating those columns *only when non-null* is the tidy-looking edit that leaves a deleted
  run's verdict on a case forever — and it would leave `never_executed` correct, so the two would
  disagree on one grid row, which is the failure this whole design is arranged against.
- The fixture helper **asserts the status it asked for was actually written**. A kind the project has
  no status for makes the sub-select return null, which writes an *unrecorded* execution — and every
  assertion about that case would then measure a fixture bug while looking exactly like a derivation
  bug.

`CaseListEndpointTest.lastResultIsAbsent` → **`lastResultIsAnExplicitNull`**, two assertions where
there was one: the key being *present* is what lets a client tell "never run" from "not served", and
the value being *null* is what stops a fabricated `UNTESTED` result inventing an execution.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL (8m 12s), 2464 tests / 507 classes / 0 failures / 0 skipped.** §165 was 2458 / 506, so this is **+6 and one new class** — the six tests of `TheLastResult`, which is the class. `lastResultIsAbsent` was renamed rather than added, so it does not move the count. Counted off `build/test-results/test`, not off the console summary. |
| `frontend`: `npm run build` · `npm run lint` · `npm run test:run` | clean · **0 warnings** · **1214 across 93 files** (unchanged — no frontend source moved) |
| `packages`: `npm run build` (in order) · lint · `test:run --workspaces` | clean · clean · **229** (66 + 85 + 78) |
| both `schema.d.ts` | regenerated, **byte-identical to each other**, diff is `lastResult?:` → `lastResult:` and nothing else |

**Baseline verified green before any edit** (`./gradlew build`, exit 0), per the brownfield
orientation rule — a red build on arrival is a finding, not something to absorb silently.

## Queues

**Mutations are RECORDED AND NOT RUN.** §165 ran its four because they were checks over checks and
exercised no product behaviour; this slice ships a derivation, so §65 applies in full — see
`docs/verification/backend-mutation-checks.md` (`§166 LR-1`…`LR-5`).

The browser queue gains `§166 LR-1`/`LR-2`, and **`§165 RC-1` is revised in place rather than
deleted**: it told the pass to confirm the key was *absent* and added "if the column shows the real
result, say so, because the build should have failed first." The build did fail first — that is what
the `MEMBER_DIVERGENCE_BY_DECISION` deletion was — so the row now asks for the opposite reading with
its id and its history intact.

## The next slice

**`TestCase.relatedDocuments`** — the other gap §165 named, now the only one left, and the shape is
demonstrated rather than proposed. It does not get to reuse `case_health`'s row: a list of links is
not a column, so it wants its own table, which is the whole of the extra work over this slice.

Otherwise unchanged: the browser queue, `TeamRef` (three types, §165), the three remaining health
derivations (`OUTDATED_SPEC_CHANGED` and `UNLINKED` are both `documentation`'s cause and would land
naturally beside `relatedDocuments`), or the read-only-archive decision §159 deliberately did not
take.

**What this slice did not examine**: `CaseSort` says `lastResult` is `sortable: false` in
`CaseGrid.tsx` and gives the reason — it "would need `app_user` inside this module's ORDER BY", which
was `owner`'s reason and was never true of this field. It is now on a column of a table this module
owns, so sorting by it is a `CaseSort` entry and a contract enum value. Nobody has asked for it; the
argument against it has simply stopped being the one written down.
