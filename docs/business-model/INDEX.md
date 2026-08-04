# Business Model — TCMS

Read this file first, every session. One line per artifact; read the ones your task touches.
Populated 2026-08-03 by `be-documenter` from the backend implementation at `93444b2` — a FULL,
first-pass documentation run covering 6 confirmed functional areas. `work` (kanban board) and
`/reports` are deliberately parked, not documented.

## Areas
- [Identity & Access](areas/identity-access.md) — AREA-001; sign-in, sessions, invitations, roles/permissions, teams · derived from code
- [Company & Project Administration](areas/company-project-administration.md) — AREA-002; provisioning, project creation, vocabularies, review policy settings · derived from code
- [Test Repository](areas/test-repository.md) — AREA-003; suite tree, test cases, bulk actions, shared condition/step libraries · derived from code
- [Test Execution](areas/test-execution.md) — AREA-004; plans, runs, results, evidence · derived from code
- [Review & Collaboration](areas/review-collaboration.md) — AREA-005; peer review, comments, reviewer assignment across cases/suites/plans · derived from code
- [Documentation](areas/documentation.md) — AREA-006; project spec pages, artifact register · derived from code

## Entities
- [Case/suite authoring lifecycle](entities/case-and-suite-lifecycle.md) — ENT-001; DRAFT→IN_REVIEW→APPROVED→DEPRECATED; owners: Test Repository, Review
- [Test plan lifecycle](entities/test-plan-lifecycle.md) — ENT-002; DRAFT→IN_REVIEW→APPROVED→ACTIVE→CLOSED; owner: Test Execution
- [Test run lifecycle](entities/test-run-lifecycle.md) — ENT-003; NOT_STARTED→IN_PROGRESS→COMPLETED/ABORTED; owner: Test Execution
- [Review record standing](entities/review-record.md) — ENT-004; OUTSIDE_REVIEW/IN_REVIEW/APPROVED, driven by ENT-001/ENT-002; owner: Review & Collaboration

## Flows
- [Onboarding and access](flows/onboarding-and-access.md) — FLOW-001; provision → invite → accept → get staffed
- [Author, review, approve a case](flows/author-review-approve-a-case.md) — FLOW-002; happy path + send-back/retire branches
- [Plan, run, execute, close](flows/plan-run-execute-close.md) — FLOW-003; happy path + abort/reopen branches

## Decisions
- [Permission scope resolution](decisions/permission-scope-resolution.md) — DEC-001; hit policy first-applicable-with-override; contributed by all six areas' write gates
- [Review tier gate](decisions/review-tier-gate.md) — DEC-002; hit policy first-applicable; contributed by Review & Collaboration

## Cross-cutting
- [Glossary](glossary.md) — canonical terms; "project" ≠ "feature," "company" not "tenant"
- [System overview](system-overview.md) — 6 areas documented, 2 deliberately parked (`work`, `/reports`)
- [Active context](active-context.md) — last touched 2026-08-03; 15 open questions across all areas

No `interaction-map.md` yet — no cross-area conflict sweep has been run against this model
(that is `business-analyst`'s job, not this documentation pass's).
