# Active Context

## Session log (newest first)

- **2026-08-03 · `be-documenter` · FULL, first documentation run.** Initialized
  `docs/business-model/` from the backend implementation at `93444b2`. Confirmed 6-area split
  with the requester before tracing: Identity & Access, Company & Project Administration, Test
  Repository, Test Execution, Review & Collaboration, Documentation. Traced every controller in
  those six areas' modules (`auth`, `identity`, `project`, `repository`, `execution`,
  `documentation`) and their primary services, plus the shared lifecycle classes
  (`LifecycleLadder`, `PlanLifecycle`, `RunLifecycle`, `ReviewStanding`). Wrote 6 area files, 4
  entity state grids, 3 process flows, 2 decision tables, system overview, glossary, this file,
  and INDEX.md. The `work` module (kanban board, uncommitted in-flight code) and `/reports`
  (designed, no server implementation) were deliberately left undocumented per the requester's
  instruction. No `#confirmed` statements were written; every `#inferred` intent below is also
  an open question here.

## Open questions for the business (consolidated)

### Identity & Access
1. Is "logout" meant to stay client-side-only forever, or does the product need real
   server-side session revocation? `areas/identity-access.md`
2. Is there an intended self-service "forgot my password" flow? None was found in this trace.
   `areas/identity-access.md`
3. Is there a supported way to change your own password once already active (outside the
   invitation flow)? None was found. `areas/identity-access.md`

### Company & Project Administration
4. Is there ever a supported way to deprovision/merge companies, or is "suspend, never delete"
   the complete lifecycle? `areas/company-project-administration.md`
5. Should a Company Admin ever be able to rename/re-key a project after creation? No such path
   was found — confirm this is deliberate. `areas/company-project-administration.md`

### Test Repository
6. Should a Test Engineer be able to retire (deprecate) their own draft case without lead
   involvement, the way they can already delete it? Named in the code as a deliberate future
   step, not yet built. `areas/test-repository.md`
7. Is the current uniform 404 acceptable for a bulk action over a selection spanning
   partially-visible cases? Not independently traced. `areas/test-repository.md`

### Test Execution
8. Is the `/reports` module (execution facts reporting) still wanted as designed? Confirmed
   dead contract by the orientation cache, not independently re-verified this session.
   `areas/test-execution.md`
9. Should adding a case to a run, or starting a plan, ever require the case/plan to be
   APPROVED first? Named in the design as intentionally powerful/obstructive and left unbuilt.
   `areas/test-execution.md`
10. Does closing a plan need to check its runs are also closed, or vice versa? No cross-check
    was found between the two lifecycles. `flows/plan-run-execute-close.md`

### Review & Collaboration
11. Is a "my reviews" / review inbox a near-term priority? The endpoint shape today
    deliberately cannot answer "all reviews assigned to me" across artifact types.
    `areas/review-collaboration.md`
12. Should review events (assigned, commented, resolved, approved) trigger any notification
    channel? None exists today. `areas/review-collaboration.md`
13. Should the "every reviewer is also a commenter" invariant ever be allowed to break (e.g., a
    review-only, non-conversational role)? Currently enforced as a hard rule.
    `decisions/review-tier-gate.md`

### Documentation
14. Is the artifact register meant to eventually store the referenced files, or stay a
    pointer/register permanently? `areas/documentation.md`

### Cross-cutting
15. Is the override-downward permission rule (DEC-001, a project-specific grant replacing an
    inherited one even when it grants *less*) intuitive enough for administrators without a UI
    warning when it reduces someone's access? Frontend behaviour not traced.
    `decisions/permission-scope-resolution.md`

## Known gaps in this pass (not open questions — just not re-derived from code this session)

- Dashboard (`DashboardController`), plan statistics, and the internals of classification-value
  reconciliation were described using the orientation cache's prior findings rather than
  independently re-read line-by-line. Flagged `#inferred` with their source in each area file.
- No cross-area conflict sweep (`interaction-map.md`) has been produced — that's
  `business-analyst`'s pass, not this one.
- `work` module and `/reports` are entirely undocumented by design (see system-overview.md).
