# §51. The open-question decision round — nine settled, two of them by reading rather than deciding

**2026-08-01.** No code changed in this section. It records a decision round over `docs/plans/2026-07-30-tcms-high-level-plan.md` §8, taken because the next slice (M2 execution writes) is gated on some of them and because the list had begun to decay in the way §46 warned about.

## What the round actually found first

**Two of the nine were not open.** Before asking anything, the outstanding list was checked against the code and the contract rather than read:

| # | Claimed | Actually |
|---|---|---|
| 16 | Suite-level condition inheritance — build in M1.5 or defer? | **Built in §27.** The case detail renders §6f inheritance live. |
| 36 | Release version required or optional on a run? | **Optional.** `releaseVersion` is `nullable: true` on every run schema in `openapi.yaml`. |

This is §46's lesson holding a second time: *the orientation pass's second question is "is this still blocked".* Two of nine entries in a list of open questions were answered by artifacts already in the repo, and both would have been re-asked indefinitely. **A question list needs the same struck-through-with-reason discipline as a decision list**, or it silently becomes a list of things nobody has re-checked.

**And #7 is worse than stale — it is contradictory.** Concurrent execution appears in the plan's *Resolved* table ("yes, with live updates; optimistic locking on executions and step results; SSE shared with the kanban board") **and** as outstanding question #7. The handoff §6 note repeated the outstanding version, so three sessions could have inherited "undecided" from a document that decided it. **The contradiction was left standing by explicit decision**, with a box in §8 explaining that it is deliberate. Implementation follows the Resolved table.

## The decisions

| # | Decision | Why it was decided this way |
|---|---|---|
| 1 | **Full snapshot per edit** | Ratifies what §29 and §49 already built, so it costs no migration. Event-sourced diffs would have invalidated the version-pin read path `test_execution.case_version_id` depends on. |
| 8 | **Structured steps are the project default** | Agrees with the already-decided relational step model, where `step_result` carries a real FK to a stable step id. M1's polish budget goes to the structured editor. |
| 9 | **`.feature` files are IMPORTED into TCMS** | The automation repo stays the source of truth. Export drops out of M4; a parser stays in. |
| 15 | **Warn on unapproved cases, never block** | Read-side only. An unapproved case in a run is a workflow problem, not a data-integrity one, and a project setting nobody configures buys nothing. Run scope resolution stays approval-blind. |
| 17 | **`LATEST` default, `BEST` removed from the enum** | "Best result wins" is how a red suite gets reported green. `WORST` — the release-gate reading it would be weighed against — is already in the enum, so removing `BEST` forfeits nothing. |
| 28 | **Storage port, filesystem adapter now, S3 later** | Keeps the single-deployable story; the port makes the S3 adapter additive. |
| 43 | **A narrow result-parser SPI + Phase 3 outbound webhooks** | Nearly free designed into M3's ingestion contract, expensive retrofitted — which is why this one had a deadline attached. |

## The three consequences worth carrying into the slices that implement them

1. **#9 needs a re-import identity rule, and it is the whole risk.** Without a stable scenario→case mapping, the second import duplicates the entire suite. `automationKey` is the natural key and is already company-scoped (§6c), which is what makes import viable at all. **The rule is the feature; the parser is the easy half.**
2. **#9 pulls against #8, and that is accepted rather than unnoticed.** The editor default is structured while Gherkin arrives by import, so imported cases are second-class in the editor until the Gherkin view gets budget. An M4 decision, not an M1 one — recorded so it is not discovered as a surprise.
3. **#28 gives up the signed URL, and that was stated before it was chosen.** A filesystem adapter cannot issue one, so **evidence uploads proxy through the API** — the upload control needs a request-size limit rather than a browser-direct PUT — and the VM's disk becomes stateful and backup-critical, which the Hetzner runbook has to cover. Per-project quota (#28's other half) is still open and the filesystem adapter makes it *more* pressing, not less, because the bound is one disk.

## #17 is a contract change and has not been applied

The decision is recorded; the edit is not made. It touches four places and belongs to one lane per the contract-holder rule:

- `docs/specs/openapi.yaml` — `AggregationPolicy` becomes `LATEST · LATEST_PER_CONFIG · WORST · ALL`, then `npm run gen:api`
- `frontend/src/lib/labels.ts:82,92` — the label and the "flatters a flaky suite" description
- `backend/src/main/java/com/tcms/execution/AggregationPolicy.java`
- `backend/src/test/java/com/tcms/execution/PlanStatisticsEndpointTest.java` — ~10 assertions use `BEST` **as the deliberately-disagreeing policy**, which is the point of them. Most survive by switching to `WORST`, which still discriminates against `LATEST`; check each one rather than sed-ing them, because a test that no longer discriminates is worse than a deleted one.

## Still open, and deliberately

#2 license · #3 migration from an existing tool · **#4 Jira flavour** · **#5 data volume** · #11 PDF engine · #13/#22/#23 retention and change comments · #14/#21 review workflow details · #19/#26 docs-as-code and Git line-linking · #24/#25 board generators · #27 case-level `expectedOutcome` · #29 sign-off immutability · #30/#34/#42 health thresholds and signal set · #35 release version source · **#37–#41 the IdP set** · #44 tracker status mappings. Plus **per-project artifact quota**, split out of #28.

**#37 — "which IdP is actually yours" — is the one that gets more expensive with time**, because M4 otherwise builds and tests against a generic abstraction that no real provider has exercised. The others are genuinely cheap to leave.

**Two work items rather than questions**, from ERD §10, both flagged as needing a test each and neither having one: **key-range reservation** in a single statement for bulk import and CI quarantine auto-create (open point 5 — contention is now company-wide, so range allocation is mandatory rather than advisable), and the **display-key inference channel's** *not found*-never-*forbidden* rule (open point 7).
