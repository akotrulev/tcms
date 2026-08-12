# M3 slice 5 — automation drift detection

**Date:** 2026-08-07 · **Milestone:** M3 (automation ingestion) · **Precedes:** async ingestion,
`lastUsedAt`'s writer

§117 named it, §118 named it, §119 named it: **drift detection** — plan §6c's stale-automation flag,
and the mirror, cases receiving results while marked `MANUAL`. Three slices in a row is normally a
reason to distrust a "next" list (§28, §29, §46 — *the orientation pass's second question is "is this
still blocked"*), so this plan opens by answering it from the code rather than from the list.

## Why this is unblocked, and since when

`case_health.stale_automation` is a `V1` column. `HealthSignal.STALE_AUTOMATION` is a `V1`-era enum
constant with a severity, a label and a `lib/labels.ts` string on the other side of the wire. What it
has never had is a **writer** — the same shape as §116's `api_token`, §117's `ingestion_job` and
`automation_key_mapping`, and §115's coverage matrix, whose blocker had been removed two slices
earlier and nobody noticed.

The blocker here was real and is now gone. The signal's predicate is *"no **ingested** result in N
days"*, and until §117 there was no such thing as an ingested result: nothing in the product ever
wrote `test_run.source = 'INGESTED'`. A signal computed over an empty set is a permanent zero, which
is exactly the confidently-wrong answer §95's `usage_count` and §100's exit criteria each shipped.
Since §117 the column has a writer (`RunCreateService.createIngested`, and only it), so the predicate
now discriminates.

Two decisions this does **not** re-open, because the plan already settled them:

- **Open question #30** — *health-signal thresholds* — resolved 2026-08-02: **14 days for stale
  automation**, 90 for long-unexecuted, **instance-wide**. There is no number to choose here.
- **Open question #42** — *which health signals ship in v1* — resolved as four, not including this
  one, on the grounds that *"the remaining six wait for real data to calibrate against"*. That list
  is **already superseded by what shipped**: §53 wrote `NEVER_EXECUTED` and `LONG_UNEXECUTED` and
  §112's lane wrote `FAILING_REPEATEDLY`, only one of which is on it. The reason it named — no real
  data — is the reason ingestion has now removed. Recorded as a departure rather than treated as
  permission.

## What this slice is

Three of nine health signals have writers. This slice takes it to **five**, and both are about the
same fact read from two sides: **when did CI last post a result for this case.**

| Signal | Predicate | Severity |
|---|---|---|
| `STALE_AUTOMATION` (existing constant, first writer) | marked `AUTOMATED`, has an ingested result, and the newest one is **older** than the cutoff | ERROR |
| `UNDECLARED_AUTOMATION` (**new**) | **not** marked `AUTOMATED`, and has an ingested result **newer** than the cutoff | WARNING |

## Decisions

### D1 — the computation goes in `repository.ExecutionFacts`, as a second statement

Not a second file. That class's own header states the property it exists to keep: it is *"the one
place in this module that names an `execution` table"*, the mirror of `execution.CaseFacts`, and a
second class naming `test_execution` ends it *"for the most tempting reason there is — that the new
query is short"*.

Not `reporting.ExecutionSource` either, where `FAILING_REPEATEDLY` lives. That signal reads
`execution_fact`; this one needs `test_case.automation_status`, which is `repository`'s own column and
is **not** on the star schema (R7 refused `case_key` for the same family of reason). Putting it there
would need a fourth column on the fact table to answer a question nothing groups by.

### D2 — "ingested" means `test_run.source = 'INGESTED'`, which is the door and not the caller

§117 pinned `TestRun.create`'s `source` to **the door the request came through, never the caller's
role**: an Automation Service token creating a run through `POST /runs` writes `MANUAL`, and only
`IngestionService` writes `INGESTED`. That is exactly the definition this signal needs. Drift is about
whether **the CI pipeline is posting reports**, not about whether a machine was involved somewhere.

The alternative spelling — "the executor was a token" — would be a second implementation of a
question `run_source` already answers, and it would answer differently for a person who kicked the
pipeline off by hand.

### D3 — `STALE_AUTOMATION` requires a **previous** ingested result

"Stopped reporting" presupposes started. §6c is explicit that `automationKey` is optional *because a
case can be marked `AUTOMATED` before the key is known*, so a predicate of "marked `AUTOMATED` and no
ingested result in 14 days" fires an **ERROR-severity** signal on every case the moment somebody
starts declaring automation intent — the whole project, on day one, before anything is wrong.

That is the shape of §95's usage count and §100's exit criteria: a computed claim that is
confidently wrong rather than cautiously silent.

**The hole this leaves is real and is stated rather than left to be found.** A case marked
`AUTOMATED` whose automation has *never* reported raises nothing from this pair. If it has no results
at all it is already `NEVER_EXECUTED`; if it has only manual ones, it is silent. Closing it needs
something this schema does not have — a date from which automation was expected — and inventing one
here would be a column no write path fills.

### D4 — the mirror signal's predicate is `automation_status <> 'AUTOMATED'`, not `= 'MANUAL'`

§6c words it as *"cases receiving results while marked `MANUAL`"*. Taken literally that misses the two
louder cases:

- **`TO_BE_AUTOMATED`** receiving ingested results means the automation backlog is counting work that
  is already done. That backlog is what §6c says justifies automation headcount.
- **`NOT_AUTOMATABLE`** receiving ingested results means the case was declared impossible to automate
  and something is automating it — and §6c denominates automation coverage as
  `AUTOMATED / (total − NOT_AUTOMATABLE)`, so a wrong `NOT_AUTOMATABLE` moves the **denominator** of
  the number this whole family exists to make honest.

The signal is therefore named for what it means — `UNDECLARED_AUTOMATION`, *automation exists and the
case does not say so* — rather than for the one status §6c happened to name.

### D5 — one cutoff, two sides of it, and the mirror is time-bounded too

The obvious reading is that undeclared automation needs no window: any ingested result ever, on a case
that does not claim automation, is a discovery. That produces a signal that **can never go false**.
A case automated in 2026, discovered, and then legitimately returned to manual when the automation was
deleted stays flagged for ever with nothing anybody can do about it — a derived signal with no path
back is the "mark as not stale" button (`HealthSignal`'s header) arriving from the other direction.

So both signals read **one** fact — `max(executed_at)` over recorded, ingested executions — against
**one** cutoff, on opposite sides. Undeclared fires while the automation is live, which is when the
discovery is actionable, and clears by the same route staleness does. One threshold, one number to
remember; open question #34 made the same choice deliberately ("*one figure to remember rather than
two that drift*").

### D6 — the fact does **not** exclude soft-deleted runs, and the reason is agreement

A deleted run's executions still count as evidence that CI reported. That is arguable on its own
terms, and it is not arguable that the two statements in this file must mean the same thing by "a
result": `recomputeExecutionSignals` (`NEVER_EXECUTED` / `LONG_UNEXECUTED`) has counted every recorded
execution since §53, deleted run or not. Two neighbouring statements disagreeing about what a result
is would surface as one case that is simultaneously `NEVER_EXECUTED` and `STALE_AUTOMATION`, which
reads as a bug in whichever one the reader distrusts.

If that changes it changes for both, in one edit, with one test.

### D7 — the `ON CONFLICT` names three columns and no others

`stale_automation`, `undeclared_automation`, `computed_at`. The rule is already written into
`recomputeExecutionSignals`' header — *a signal's owner writes its own column and no others; the row
is shared, the columns are not* — and this slice is the first time there are **three** writers of one
row rather than two, so it is also the first time the rule is load-bearing in both directions.

### D8 — thresholds stay instance-wide, as #30 resolved

`tcms.case-health.stale-automation`, defaulting to `P14D`, beside `long-unexecuted`.
`CaseHealthProperties` already carries the argument for typed config over an inlined constant and the
note that #30 would make these a fallback rather than a decision; both stand unchanged.

## Phase 1 — backend

1. **`V37__case_health_undeclared_automation.sql`** — the new boolean, `NOT NULL DEFAULT false`, plus
   the two partial indexes the pair needs. `stale_automation` never had one: `V1` indexed four of the
   nine signals, and a facet query over an unindexed boolean is the thing that gets noticed a year
   later. Both lead with `company_id` (`SchemaConventionTest`). **No new table, so the policy count
   stays 61**; `case_health` has been in `V1`'s `company_owned` array since M0.
2. `HealthSignal.UNDECLARED_AUTOMATION` — **appended last**. Declaration order is load-bearing
   (`CaseSearch.tallyHealth` selects one aggregate per constant and reads them back **by position**),
   and appending is the one edit that cannot disturb it.
3. `CaseHealth` — the mapped field and its switch arm.
4. `CaseHealthProperties.staleAutomation` + the positive-duration check + `application.yml`.
5. `ExecutionFacts.recomputeAutomationDrift(cutoff, now)`; `CaseHealthRecomputeService.recompute()`
   calls both statements under the one transaction it already opens.
6. `openapi.yaml` — one enum member and the prose that says what it means. Additive.
7. Tests, in `CaseHealthRecomputeTest` — its fixture already builds cases and runs, both hard-coded
   to `MANUAL`, and both need a parameter. **The discriminating fixture is the point**: a case whose
   only recent result is *manual* must not clear staleness, and a case whose only *ingested* result is
   old must not raise the mirror. Each rung tested alone (§117's ladder rule).

## Phase 2 — frontend

`npm run gen:api`, then the two records a new member makes a compile error until they are filled:
`healthLabels` and `healthHelp`. ~~`SEVERE_SIGNALS` is a **decision, not a default** — the new signal
stays out of it, per D4's severity argument, and the dashboard tile inherits its position from
`HealthSignal.Severity` on the server.~~ → **superseded 2026-08-12 (§179): `SEVERE_SIGNALS` is
deleted.** The severity is on the wire beside each raised signal, so D4's argument is recorded once, on
the enum, and the chip inherits its colour the same way the tile inherits its position. A new signal is
now **two** records rather than three, and its severity is one line in `HealthSignal` plus one in
`RaisedSignalTest.DECIDED` — which is where the decision has to be typed twice on purpose.

No new screen. The dashboard's "Needs attention" panel and the case grid's health facet are generic
over the enum and gain the tile and the chip with no edit — which is what `HealthSignal`'s header
predicted when it said the signals *"start working when the computation lands, with no change here"*.

## Out of scope, stated

- **A stale-automation report.** §6c lists one; §6e's report registry (`ReportComputations`) makes it
  a bean rather than a slice, and it is a different question (a list to work through) from a signal (a
  flag on a case).
- **Per-project thresholds** — #30 resolved against them.
- **The board's quarantined-flaky generator** (§6l) and **flake scoring**. §112's R7 already computes
  the inconsistency signal as a *report section*; persisting a per-case flake score is its own slice
  and its own decision about where the score lives.
- **Auto-quarantine.** §117 declined §6c's auto-created case stubs and the argument has not changed.
