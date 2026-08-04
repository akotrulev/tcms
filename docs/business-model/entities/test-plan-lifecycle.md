# ENT-002 — Test Plan Lifecycle

**Created:** 2026-08-03 · **Owned by:** `ba-entity-state-grid`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Area:** AREA-004 (Test Execution), consumed by AREA-005 (Review & Collaboration)

`PlanLifecycle.java:1-219`.

## States

`DRAFT` · `IN_REVIEW` · `APPROVED` · `ACTIVE` · `CLOSED`

## State × event grid

| Current \ Requested | DRAFT | IN_REVIEW | APPROVED | ACTIVE | CLOSED |
|---|---|---|---|---|---|
| **DRAFT** | Unchanged | Move (`plan:edit`) | Refused | Move (`plan:edit`) | Refused |
| **IN_REVIEW** | Move (`plan:approve` — unmaking) | Unchanged | Move (`plan:approve`) | Refused ("approve or return to draft first") | Refused |
| **APPROVED** | Move (`plan:approve` — unmaking) | Refused | Unchanged | Move (`plan:edit`) | Refused |
| **ACTIVE** | Refused ("reopen" is the only way back) | Refused | Refused | Unchanged | Move (`plan:approve`) |
| **CLOSED** | Refused ("reopen it first") | Refused | Refused | Move (`plan:approve` — reopen) | Unchanged |

`#observed`, `PlanLifecycle.java:141-178`.

## Rules

- **R1 — Review is optional, so `DRAFT → ACTIVE` is legal directly.** A plan never needs to pass
  through review/approval to start; that ladder is a detour a project opts into.
  `PlanLifecycle.java:21-28` `#observed`.
- **R2 — `APPROVED` is reachable only from `IN_REVIEW`** — approval always follows review when a
  team chooses to use it; there is no shortcut. `PlanLifecycle.java:33-36` `#observed`.
- **R3 — `CLOSED` is reachable only from `ACTIVE`.** Closing asserts "this release was executed
  and is over"; a plan that never started has nothing to close — the way out for an unused plan
  is deletion, not closing. `PlanLifecycle.java:37-44` `#observed`. Delete and close therefore
  partition every plan's possible endings.
- **R4 — Reopening a closed plan returns it to `ACTIVE`, never to `DRAFT`.** A release that
  needs more testing is still the release that was scoped; dropping to draft would falsely say
  the scope was never agreed. `PlanLifecycle.java:169-176` `#observed`.
- **R5 — Granting approval, ending the plan (closing), and taking either back all cost
  `plan:approve`; everything else costs only `plan:edit`.** The same "unmaking is an approval
  act" principle as the case/suite ladder (ENT-001 R2). `PlanLifecycle.java:46-62,122-131`
  `#observed`.
- **R6 — Repeating the current state is a no-op**, not an error or an audited event.
  `PlanLifecycle.java:88-96` `#observed`.

## Business intent

`#inferred`, stated directly in the code: the plan ladder exists to let review be genuinely
optional per project while still making the same three guarantees the case/suite ladder makes
when a project *does* turn review on — approval always follows real review, and nobody can
quietly rescind a sign-off using ordinary edit rights.
