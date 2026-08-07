# M3 slice 6 — flake detection and quarantine

**Date:** 2026-08-07 · **Milestone:** M3 · **Schema:** `V38` (next free after §120's `V37`)

## Why this slice, derived from the code rather than from a "next" list

§28/§29/§46's rule, applied. The plan's own milestone table says what M3 is:

> **M3 — Automation ingestion** | Result parsers, automation-key matching, API tokens, **quarantine
> flow** | 2–3 weeks

Three of those four have shipped — parsers and matching in §117, tokens in §116/§118. **The
quarantine flow is the one item left, and it has no schema at all.** Both orientation questions
answer:

- **Does it have a consumer?** Yes, and it has had one since the board shipped. §6l names four
  derived-item generators; `BoardFacts` implements three (`EXECUTION`, `CASE_MAINTENANCE`,
  `REVIEW`). The fourth is **"quarantined flaky test"**, and its card source is silently empty.
- **Is it still blocked?** No, and §117 is when it stopped being. Open question #24 recorded the
  blocker in as many words — *"the quarantined-flaky generator has no data until M3 ingestion
  exists. On-by-default here means on **when it ships**, not a card source that is silently empty
  and mistaken for working."* That is the **fourth** time a plan's own "not covered / blocked" note
  has outlived its blocker (§115's coverage matrix, §120's drift pair, and now this).

## What the plan already decided, and is not re-opened here

| Where | Decision |
|---|---|
| §6c (line 166) | *"Flaky test detection — result history per automation key, flake score, quarantine flag."* Three things: a history, a score, a flag. |
| §6l (line 1517) | **The scoping rule.** *"Flakiness — pass/fail alternation **within a single version and configuration**, which is the only scoping under which flakiness means anything. Alternation across versions is just change, and reporting it as flake is a false signal."* |
| #24 (2026-08-02) | Every generator is **on by default**, and the board must **cap** derived generation. |

## The design

### D1 — The score is derived; the quarantine is authored. One table, two column groups.

`case_health`'s table comment is categorical — *"Signals are DERIVED, never authored — no UI writes
here, so they self-heal … No 'mark as not-stale' button exists to lie with"* — so the quarantine
flag cannot go there, and neither can a score, which is not a boolean.

`case_flake` is a new table holding **both halves of one row**, and the separation is enforced by the
rule this codebase already runs on: **a writer names its own columns in `ON CONFLICT` and no
others.** §120 was the first slice where that rule bit between two live writers; this is the first
where it bites **inside one table** — the recompute never touches the four quarantine columns, and
the quarantine write never touches the seven derived ones.

Two tables would also work and are worse: one row per case in two places, two upserts, and a join on
every read of a screen that always wants both.

**Quarantine is authored, not a threshold.** A score crossing a line is a *fact*; quarantining is a
*decision* — "stop gating on this test" — and §6o's no-button-to-lie-with rule does not forbid
recording a decision, only recording a derived claim. It is also what makes the board card mean
something: a derived card would vanish the moment the test stopped flaking, and the debt it
represents would vanish with it while the test was still excluded.

### D2 — The score is computed in `reporting`, over `execution_fact`, beside `FAILING_REPEATEDLY`.

Not in `repository.ExecutionFacts` where §120's drift pair went. The deciding question is the one
§120 recorded: drift needs `test_case.automation_status`, which is not on the star schema and should
not be — so it had to read `test_execution`. **Flake needs nothing but executions**: `case_id`,
`configuration_id`, `case_version_id`, `executed_at`, and the outcome, all of which
`execution_fact` carries.

That makes it exactly the shape of `FAILING_REPEATEDLY`, which is already written from
`reporting.ExecutionSource`, and it buys the property that matters most here: **R7's inconsistency
callout reads the same rows.** The callout and the flake score answer nearly the same question, and
two implementations over two different tables would disagree on the day somebody changes what a
recorded execution is.

### D3 — A cell is `(case, configuration, case version)`, and it is R7's cell.

Straight from §6l line 1517 — and `AggregateExecutionReport.inconsistentCases` already groups by
exactly that triple for `within_one_cell`. Alternation is counted **inside** a cell, never across
one, so a case that passed at v2 and fails at v3 is a change and not a flake.

### D4 — Non-outcomes are removed from the sequence, not treated as a value.

A flake alternation is between `counts_as_pass` and `status_kind = 'FAILED'`. `BLOCKED`, `SKIPPED`,
`RETEST` and `UNTESTED` are **filtered out before the sequence is formed**, which is not the same as
ignoring them: a blocked run sitting between two passes would otherwise read as *two* alternations
(pass→blocked, blocked→pass) and score a perfectly stable test at 1.0. This is R7's
*"a pass and a BLOCK is not an inconsistency"* rule, applied to a sequence rather than to a set, and
it is the mutation this slice most needs.

### D5 — `flake_score` is NULL below the evidence floor, not 0.

Two executions that went pass→fail score 1.0 on any ratio definition, and that is noise, not a flake.
Below `minimumTransitions` adjacent pairs the score is **NULL — "not enough evidence"** — which is a
different claim from `0.0`, *"evidently stable"*. Collapsing the two is §95's `usage_count` and
§100's exit criteria: the confident zero. The counts behind the score (`executions`, `transitions`,
`alternations`, `cells`) are stored either way, so a reader can see *why* there is no score.

### D6 — The window is bounded, for §120's reason exactly.

`tcms.case-flake.window`, default 90 days. An unbounded score is one that **can never go false**: a
test that flaked badly last spring and has been rock solid since stays flagged for ever with nothing
anybody can do about it — the same "derived claim with no route back" §120 rejected for
`UNDECLARED_AUTOMATION`, arriving from the other direction.

### D7 — No `source` predicate. Flakiness is a property of results, not of the door.

Deliberately unlike §120's drift pair, which filters `r.source = 'INGESTED'` because the signal is
*literally* about whether the CI pipeline is posting. A manual test that alternates pass and fail on
one configuration at one version is genuinely flaky. **R7's callout has no source predicate either**,
and D2's whole argument is that these two must not disagree.

### D8 — The gate is `case:edit`, and no permission is invented.

The catalogue (`docs/specs/2026-07-30-tcms-roles-permissions-naming.md`) is authoritative and has no
`case:quarantine`; adding one is a decision, and there is an existing gate that means the right
thing. `case:edit` also carries the §117 property for free: **Automation Service does not hold it**,
so a leaked CI token can post results and **cannot quarantine a test to stop its own red results
from gating.** That is the same argument §117 made for `automation_key_mapping`, and it is the
reason this endpoint is not gated on `result:submit`.

### D9 — No `lock_version`.

The quarantine toggle is a single-writer transition from one action, not two screens racing over a
form — `IngestionJob`'s argument, `report_job`'s shape rather than `test_case`'s.

## Phases

| # | What | Gate |
|---|---|---|
| 1 | `V38`, the `CaseFlake` entity + repository, the scoring statement in `ExecutionSource`, `CaseFlakeProperties`, wiring into `ExecutionFactRefreshService`, tests | `./gradlew build` |
| 2 | The authored half — `PUT /cases/{caseId}/quarantine`, the `case:edit` gate, the contract, the flake block on the case detail | `./gradlew build` |
| 3 | The board's fourth generator — `QUARANTINED_FLAKY` cards in `BoardFacts`/`BoardService`, under #24's cap | `./gradlew build` |
| 4 | Frontend — the flake panel, the quarantine action, the board card | `npm run build` + `lint` + `test:run` |

## Verification

§65 standing policy: browser rows to `docs/verification/frontend-browser-checks.md`, mutation rows to
`docs/verification/backend-mutation-checks.md`. **Written, not run.**

The mutation that matters most is **D4** — restore `BLOCKED` to the sequence and a stable test scores
1.0 — and D3 (drop the cell partition; a version bump reads as a flake). Both must redden a test
written for them and not merely crash, which is §120's recorded lesson: *mutate to the rival
implementation, not to nothing.*
