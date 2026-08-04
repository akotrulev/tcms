# FLOW-003 — Plan a release, run it, execute, close

**Created:** 2026-08-03 · **Owned by:** `ba-process-flow-diagram`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Areas:** AREA-004 (Test Execution)
**Entities:** ENT-002 (plan lifecycle), ENT-003 (run lifecycle)

```mermaid
flowchart TD
    A([Test Engineer/Lead creates a plan]) --> B[DRAFT scope: cases + criteria]
    B --> C{Project uses review?}
    C -->|No, the default| D[Start directly — plan:edit]
    C -->|Yes| E[Submit for review — plan:edit]
    E --> F[IN_REVIEW]
    F --> G{Lead approves? — plan:approve}
    G -->|Yes| H[APPROVED]
    G -->|No, sent back| B
    H --> D
    D --> I[ACTIVE]
    I --> J([Create a run scoped to the plan])
    J --> K[Run: NOT_STARTED, one execution per case]
    K --> L{First result recorded — result:submit}
    L -->|Automatic, no extra gate| M[Run: IN_PROGRESS]
    M --> N[Testers record/amend results, attach evidence]
    N --> O{Lead ends the run — run:close}
    O -->|Results exist| P[Run: COMPLETED]
    O -->|No results at all| Q[Refused — must ABORT instead]
    O -->|Abort| R[Run: ABORTED]
    P --> S{Lead closes the plan — plan:approve}
    R --> S
    S --> T[Plan: CLOSED]
    T --> U{Needs more testing}
    U -->|Reopen run — run:close| M
    U -->|Reopen plan — plan:approve| I
```

`#observed` — nodes evidenced in `PlanLifecycle.java` (ENT-002), `RunLifecycle.java` and
`RunController.java` (ENT-003), `ExecutionService`/`RunLifecycle.java:19-30` (auto-start on
first result). The composition into one end-to-end flow is this document's synthesis.

## Notes

- A run's completion is deliberately never automatic, even when every case in scope has a
  result — "done" is a lead's judgement call (ENT-003 R1).
- A plan can be closed and reopened independently of its runs being closed and reopened; the
  two lifecycles are separate state machines that a team coordinates manually. Not independently
  verified whether the system cross-checks plan/run state consistency — flagged as an open
  question below.

## Open question

Does closing a plan need to check that its runs are also closed (or vice versa), or is that
left entirely to the team's own process? No cross-check between `PlanLifecycle` and
`RunLifecycle` was found in this trace. `#inferred`
