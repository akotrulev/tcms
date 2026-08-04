# ENT-004 — Review Record (Standing)

**Created:** 2026-08-03 · **Owned by:** `ba-entity-state-grid`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Area:** AREA-005 (Review & Collaboration); driven by ENT-001 and ENT-002

A review is opened and closed as a *side effect* of a subject's own authoring-lifecycle
transitions (ENT-001 for cases/suites, ENT-002 for plans) — it is not an independent state
machine a user drives directly. `ReviewStanding.java:1-35` `#observed`.

## Standings

`OUTSIDE_REVIEW` (draft, retired/deprecated, active, closed — anything not mid-review or
signed off) · `IN_REVIEW` (a review row is open; at most one open review per subject) ·
`APPROVED` (signed off by someone holding the approve key). `ReviewStanding.java:26-35`
`#observed`.

## How each subject type maps onto these three standings

| Case/suite `LifecycleStatus` | Standing |
|---|---|
| DRAFT | OUTSIDE_REVIEW |
| IN_REVIEW | IN_REVIEW |
| APPROVED | APPROVED |
| DEPRECATED | OUTSIDE_REVIEW |

`LifecycleLadder.java:219-225` `#observed`. Retiring an artifact *while* it is under review ends
the review the same way withdrawing it would — nobody judged the content, the question was
simply taken off the table.

| Plan `PlanStatus` | Standing |
|---|---|
| DRAFT | OUTSIDE_REVIEW |
| IN_REVIEW | IN_REVIEW |
| APPROVED | APPROVED |
| ACTIVE | OUTSIDE_REVIEW |
| CLOSED | OUTSIDE_REVIEW |

`PlanLifecycle.java:192-198` `#observed`. Starting an approved plan (`ACTIVE`) does not retract
its sign-off, and closing a release does not retract it either — execution is not a review
state.

## Rules

- **R1 — A review has no opinion on any state it doesn't recognize.** A move between two
  `OUTSIDE_REVIEW` states (e.g. `ACTIVE → CLOSED` on a plan) writes nothing to the review
  record at all. `ReviewStanding.java:21-23` `#observed`.
- **R2 — At most one open review per subject at a time.** `ReviewStanding.java:30-31`
  `#observed`.
- **R3 — Peer verdicts (LGTM/request changes) and threaded comments accumulate inside one open
  review**; see AREA-005 for the participation/comment/assignment mechanics layered on top of
  this standing.

## Business intent

`#inferred`: keeping "why a review ended" distinct (approved vs sent back vs simply withdrawn
because the artifact was retired) is treated as necessary for the review's own audit trail to
be honest — collapsing an approval and a rejection into the same shape would make the trail lie
about what actually happened. Stated directly in the code's own rationale.
