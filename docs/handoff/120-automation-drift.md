# §120. Automation drift — the column that waited from `V1` for a fact ingestion had to invent

**M3 slice 5.** `case_health.stale_automation` has been a column since `V1`, `HealthSignal.STALE_AUTOMATION`
a constant with a severity and a label since §47, and `lib/labels.ts` has worded it — *"Automation
stopped reporting"* — for as long as the SPA has had a dashboard. What it never had was a **writer**,
and the reason was not neglect. The signal's predicate is *"no **ingested** result in N days"*, and
until §117 there was no such thing as an ingested result: nothing in the product had ever written
`test_run.source = 'INGESTED'`. A computation over that would have selected the empty set in every
deployment and reported a confident zero, which is §95's `usage_count` and §100's exit criteria
exactly.

This slice gives it one, plus the mirror §6c names in the same sentence and the schema never had.

## The re-derivation, because three slices in a row named this

§117, §118 and §119 each closed by naming drift detection as next, and a "next" list repeated three
times is the shape §28, §29 and §46 all warn about — §27's three named next steps were every one of
them unsupportable. So the question was asked from the code rather than from the list, and both
halves of the orientation pass answered:

- **Does it have a consumer?** Yes, and it has had one for longer than it has had a producer. The
  dashboard's "Needs attention" panel, the case grid's health facet and `HealthChip` are all generic
  over the enum. `HealthSignal`'s own header predicted the shape: *"it starts working when the
  computation lands, with no change here."* That turned out to be true to the letter — the frontend
  half of this slice is two exhaustive records and one decision.
- **Is it still blocked?** No, and §117 is when it stopped being. That is the same finding §115
  recorded about the coverage matrix, whose blocker §93 had removed two slices earlier with nobody
  noticing, and it is now the third time: **re-read a plan's own "not covered" list against the code
  before treating it as current.**

Two decisions the plan had already settled and this slice did not re-open. **#30** fixed the
threshold at **14 days, instance-wide**, on 2026-08-02 — there was no number to choose. **#42** said
the v1 signal set is four and does not include this one, on the grounds that *"the remaining six wait
for real data to calibrate against"*; that list was **already superseded by what shipped** (§53 wrote
two signals that are not on it and `FAILING_REPEATEDLY` is a third), and the real data it was waiting
for is what ingestion produced. Recorded as a departure rather than treated as permission.

## What the two signals are

Five of the ten signals now have a writer, up from three. Both new ones read **one fact** — the
newest recorded execution whose run came through the ingestion door — on **opposite sides of one
cutoff**.

| Signal | Predicate |
|---|---|
| `STALE_AUTOMATION` (existing constant, first writer) | marked `AUTOMATED`, **has** an ingested result, newest one **older** than the cutoff |
| `UNDECLARED_AUTOMATION` (`V37`, new) | **not** marked `AUTOMATED`, newest ingested result **newer** than the cutoff |

**A case can therefore never raise both**, and that is a property of the statement rather than of the
schema — which is the whole reason the two are written together in one `INSERT` instead of by two
jobs that would have to agree. `theTwoAreMutuallyExclusive` asserts it over the whole fixture rather
than per case, because the way it breaks is a lost `automation_status` arm, not a bad row.

## The decisions worth knowing before touching this

**Staleness requires a *previous* ingested result, and that is not an optimisation.** "Stopped
reporting" presupposes started. §6c makes `automationKey` optional *precisely so* a case can be
marked `AUTOMATED` before the key is known — so the literal predicate, "marked `AUTOMATED` and no
ingested result in 14 days", fires an **ERROR-severity** signal on every case in a project the day
somebody begins declaring automation intent. Before anything is wrong, and loudest on the teams doing
the work. **The hole this leaves is stated rather than left to be found**: a case marked `AUTOMATED`
whose automation has never once reported raises nothing from this pair. With no results at all it is
already `NEVER_EXECUTED`; with only manual ones it is silent, and closing that needs a
date-automation-was-expected that no write path fills.

**The mirror is `<> 'AUTOMATED'`, though §6c says `MANUAL`.** Two of the other three states are
louder than the one it names. `TO_BE_AUTOMATED` means the automation backlog is counting work that is
already done — the backlog §6c says justifies headcount. `NOT_AUTOMATABLE` is the loudest: the case
was declared impossible to automate and something is automating it, and coverage is denominated
`AUTOMATED / (total − NOT_AUTOMATABLE)`, so a wrong one moves the **denominator** of the number this
entire signal family exists to make honest. Hence a column named for the meaning —
`undeclared_automation` — rather than for §6c's wording, which would have to be re-read the first
time it fired.

**Both signals are time-bounded, and the mirror's bound is the interesting one.** The obvious reading
is that undeclared automation needs no window: any ingested result ever, on a case that does not claim
automation, is a discovery. That produces a signal that **can never go false** — a case automated,
discovered, and then legitimately returned to manual when the automation was deleted stays flagged for
ever with nothing anybody can do about it. That is `HealthSignal`'s "no button for lying" rule
arriving from the other direction: a derived claim with no route back is exactly as dishonest as a
stored one. One fact, one cutoff, two sides; #34 made the same call for the stale-plan threshold and
gave the reason — *one figure to remember rather than two that drift*.

**"Ingested" is `test_run.source`, which §117 pinned to the door and never the caller.** An Automation
Service token opening a run through `POST /runs` writes `MANUAL`; only `IngestionService` writes
`INGESTED`. That is the definition this signal wants — drift is about whether the CI **pipeline** is
posting reports, not about whether a machine was involved. The alternative spelling ("the executor was
a token") is a second implementation of a question the column already answers, and it answers
differently for the person who kicks the pipeline off by hand.

**No `deleted_at` predicate on the run, and the reason is agreement rather than correctness.** A
deleted run's results still count as evidence CI reported. That is arguable on its own terms; what is
not arguable is that the two statements in `ExecutionFacts` must mean the same thing by "a result",
and `recomputeExecutionSignals` has counted every recorded execution since §53. Two neighbours
disagreeing would surface as a case that is simultaneously `NEVER_EXECUTED` and `STALE_AUTOMATION`,
which reads as a bug in whichever one the reader trusts less. If it changes, it changes for both, in
one edit.

**The computation is a second statement in `ExecutionFacts`, not a second file.** That class's header
states the property: it is the one place this module names an `execution` table, the mirror of
`execution.CaseFacts`, and a second class naming `test_execution` ends it *"for the most tempting
reason there is — that the new query is short."* Not `reporting.ExecutionSource` either, where
`FAILING_REPEATEDLY` lives: that reads `execution_fact`, and this needs `test_case.automation_status`,
which is not on the star schema and should not be (R7's argument about mutable case columns).

**The `ON CONFLICT` names three columns, and this is the first slice where that rule bites in both
directions.** `stale_automation`, `undeclared_automation`, `computed_at`. Until now the rule was
protecting a *hypothetical* future writer's columns; `reporting.ExecutionSource` writes
`failing_repeatedly` into these same rows on its own schedule, so a statement that set every column
would blank a **live** sibling's work every tick.

## The mutation that taught something, and the one that did not

Both new test classes went green first time — §114's recorded weakness, and the thing it named a
mutation row as the only route out of. Three were run inline as a stated §65 deviation.

**`DR-1`** — dropping `AND r.source = 'INGESTED'` — reddens **five** tests, more than the one written
for it, and the extra four are the right four: the fixture's ordinary manual cases all become drift
cases. The one that matters is `aPersonRunningItByHandProvesNothingAboutTheAutomation`, a case CI
abandoned 120 days ago that somebody still runs by hand every fortnight. It is not long-unexecuted and
its automation *is* stale, so an implementation reading "the newest result" for both signals — which
is the shape of the statement directly above it, and therefore the one anybody will copy — gets
exactly one of the two answers wrong.

**`DR-2` was attempted the wrong way first and that is the part worth keeping.** Deleting
`last_ci.at IS NOT NULL` outright reddened **all nineteen** tests in the class. Not because the
semantics are that load-bearing: because `last_ci.at < :cutoff` is NULL when there is no ingested
result, and a NULL boolean into a `NOT NULL` column aborts the whole recompute. A crash, in the safe
direction, saying nothing at all about the behaviour under test. The mutation that **measures** the
decision is the competing implementation — §6c read literally,
`(last_ci.at IS NULL OR last_ci.at < :cutoff)` — and that one reddens exactly one test, the one
written for it. **Mutate to the rival implementation, not to nothing**; the javadoc says so for
whoever re-measures.

## `CaseHealthAttributesTest` now exists

`HealthSignal`'s javadoc has said *"`CaseHealthAttributesTest` asserts every constant resolves"* since
§47, and nothing did. §100's finding in its purest form — **a comment asserting a fact about another
file is not a check** — and it went unnoticed for the reason it matters: `attribute()` is a *string*,
because Criteria takes a name, so a typo compiles, passes `-Werror`, passes `ddl-auto: validate` (the
column is fine, only the Java attribute name is wrong) and then throws at runtime on the **one** code
path that filters by that signal, while the chip, the facet count and the tile all keep rendering from
`CaseHealth.signals()`, which is a `switch` and cannot be wrong. One broken filter among ten working
ones. Database-free, reflection over the declared fields, two tests: every attribute resolves to a
boolean field, and no two signals share one.

## Two stale claims corrected on the way past

`HealthSignal`'s header said **"Nothing populates it yet, because neither module exists."** Three
computations have landed since. The prediction it made in the same breath held exactly, so the
paragraph is struck through and the count corrected rather than deleted.

The same header said `INCOMPLETE` is *"on the table and not in this enum … putting it on the wire
would invent a member the SPA has no label for."* **The contract went the other way and nobody
updated the header**: `openapi.yaml` lists `INCOMPLETE` and `lib/labels.ts` labels it. So the two
enums are **not** the same set — the wire is a superset by one member this server can never send.
Left as it is rather than tidied in either direction, because both tidyings are worse: deleting it
from the contract breaks a label the SPA already has, and adding a constant here invents a facet that
always tallies zero.

## The one frontend decision: the pair must not match

No new screen, no new endpoint, no new query hook. The dashboard panel, the case grid's facet and the
chip are generic over the enum and gained the signal with no edit. The whole frontend change is the
two exhaustive `Record<HealthSignal, …>`s that a new member makes a compile error until they are
filled — and one decision.

**`UNDECLARED_AUTOMATION` stays out of `SEVERE_SIGNALS` while `STALE_AUTOMATION` is in it.** They are
mirrors and read as a matched set, so giving them a matched appearance is the obvious tidy-up, and it
erases the only distinction a reader acts on. Stale automation means a result the team believes it is
getting is not arriving — a red test can stay invisible for as long as it lasts. Undeclared automation
means results are arriving that no coverage number counts: the percentage is understated, which is
wrong in the direction that costs nobody a missed defect. The server draws the same line
(`HealthSignal.Severity`, ERROR vs WARNING) and **it is not on the wire for a bare signal** — only a
`DashboardTile` carries severity — so `SEVERE_SIGNALS` is a second copy of a server-side decision with
nothing holding the two together. `HealthChip.test.tsx` is what holds them now, and it exists for
§118's reason: a rule whose violation is indistinguishable from a tidy-up needs a test that says so
out loud. The mutation confirms it reddens exactly one test.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **BUILD SUCCESSFUL — 1963 tests across 410 result classes, 0 failures, 0 skipped** (§119's baseline was 1953/408; +8 drift tests, +2 attribute tests, +2 classes) |
| `cd frontend && npm run build` | clean |
| `npm run lint` | 0 warnings |
| `npm run test:run` | **1021/1021 across 77 files** (measured; §119's baseline was 1018/76) |

Schema head is **`V37__case_health_undeclared_automation.sql`**; **`V38` is the next free number**.
Policy count is still **61** — `case_health` has been in `V1`'s `company_owned` array since M0, so a
column added to it inherits the policy and there is nothing to register.

## Verified how

**Seven browser rows (`L4-49`…`L4-55`) and ten mutation rows (`DR-1`…`DR-10`), three of the mutations
RUN and stated as a deviation** — the same shape §115 and §117 recorded, for the same reason (both
classes green first time), and not a new rule.

The browser rows sit in `L4` because they need a scheduler tick, and they need `A3`'s ingestion rows
before them: without an `INGESTED` run both signals are correctly and permanently false, which is
precisely the state the column sat in from `V1` to M3. **The clock is a problem in one direction
only** — `UNDECLARED_AUTOMATION` fires on a recent result and drives in a sitting, while
`STALE_AUTOMATION` needs one older than 14 days, which a company provisioned this morning cannot have.
The row names two honest routes (back-date in `psql`, or restart with `TCMS_STALE_AUTOMATION=PT1M`)
and rules out the third: **do not lower the shipped constant**, which verifies a number nobody runs.

One thing the pass structurally cannot show, named rather than left looking like an oversight: D3's
hole is an **absence**, so its row would be "open the dashboard and see no tile" — equally true of the
signal being broken. It is asserted in `automationThatNeverStartedHasNotStopped` and nowhere else.

## What is next

- **`lastUsedAt` needs a writer** (§118), still. Named for the third slice running, which by §28's
  rule is now a reason to check whether it is blocked rather than to repeat it: it is not — the filter
  resolves a token row on every request and the write is one `UPDATE` away.
- **Async ingestion** — `PENDING`/`PROCESSING`/`FAILED` and `raw_storage_key`, which §117 left with no
  writer on purpose and §119 named.
- **A stale-automation report.** §6c lists one, and `ReportComputations` is a bean registry, so it is
  a class and not a slice. Different question from the signal: a list to work through, rather than a
  flag on a case.
- **The board's quarantined-flaky generator and flake scoring** (§6l, §6c). §112's R7 already computes
  the inconsistency signal as a *report section* over `selectedCte`; persisting a per-case flake score
  is its own slice and its own decision about where the score lives. Note the trap R7 recorded: a
  section — or a scorer — written over `resolvedCte` is permanently empty and looks fine.
- **Five `case_health` signals still have no writer** — `OUTDATED_SPEC_CHANGED`, `STALE_APPROVAL`,
  `CONDITION_DRIFT`, `UNLINKED`, `ORPHANED` (five of ten, against five that now do). Each is now the same shape this slice was: a column,
  a constant, a label, a tile, and no producer. `UNLINKED` is the cheapest — `doc_link` has had a
  writer since §93, which is the blocker §115 found already removed.
- **`DR-6` and `DR-9` are recorded as rows that may redden nothing**, which would be the finding: the
  enum's declaration-order rule and both positive-duration checks in `CaseHealthProperties` appear to
  be held by comments alone.
