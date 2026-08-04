# System Overview — TCMS

**Created:** 2026-08-03 · **Owned by:** `be-shared-business-documentation`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03

## What this system is, for whom

A self-hosted test case management system for an organisation that runs testing services (or
an internal QA function) for **multiple separate client companies from one deployment**. It is
not a test runner (it records results others produce), not a defect tracker (it links out to
one), and not public self-service SaaS (a company only exists because an operator provisioned
it). `#inferred`, matching the framing given at the start of this run and consistent with every
area traced — no self-service signup path exists anywhere in the code (AREA-001, AREA-002).

## The hierarchy

**Instance** (one deployment) → **Company** (a separate client organisation — the hard
isolation boundary) → **Project** (one whole client product or engagement, not a feature) →
suite tree / plans / runs / documentation, all scoped to the project. `#observed`, confirmed by
the `company_id`/RLS pattern referenced throughout every controller read this session and by
`docs/specs/2026-07-30-tcms-erd.md` §0.1 (authoritative, referenced not re-verified line by
line this session).

## The six functional areas

| Area | File | Covers |
|---|---|---|
| AREA-001 | [Identity & Access](areas/identity-access.md) | Sign-in, sessions, invitations, roles/permissions, teams |
| AREA-002 | [Company & Project Administration](areas/company-project-administration.md) | Provisioning, project creation, vocabularies, review policy settings |
| AREA-003 | [Test Repository](areas/test-repository.md) | Suites, test cases, bulk actions, shared libraries |
| AREA-004 | [Test Execution](areas/test-execution.md) | Plans, runs, results, evidence |
| AREA-005 | [Review & Collaboration](areas/review-collaboration.md) | Peer review, comments, reviewer assignment — across cases, suites, plans |
| AREA-006 | [Documentation](areas/documentation.md) | Project spec pages, artifact register |

**Parked, not documented this run:** the `work` module (kanban board / work items) — flagged by
the requester as in-flight/uncommitted; and `/reports` — designed but not implemented
(confirmed as dead contract by the orientation cache, not independently re-verified this
session).

## Two rules that cut across every area

1. **Two independent gates, always in this order: is this company yours, then do you hold a
   grant on this specific project.** Company membership alone shows nothing about any project
   (AREA-001). Every area's endpoints re-use the same visibility check rather than each
   inventing its own. `#observed`, repeated verbatim across every controller header read this
   session.
2. **An unreachable target is always 404, never 403, and a permission refusal is always 403,
   never disguised as a 404.** A caller can never learn whether something exists in another
   client's company, or infer anything about a colleague's grants, from the shape of a
   refusal. `#observed`, confirmed in every controller across all six areas.

## Cross-area journeys

- **Onboarding → staffed on a project** — FLOW-001, spans AREA-001 and AREA-002.
- **Author a case, get it reviewed, get it approved** — FLOW-002, spans AREA-003 and AREA-005;
  the identical shape applies to suites, and a close cousin applies to plans (ENT-002).
- **Plan a release, run it, execute, close** — FLOW-003, entirely within AREA-004, but a plan or
  a case can be under review (AREA-005) at any point along the way.

## Shared entities (state machines used by more than one area)

- ENT-001 — Test case / suite authoring lifecycle (AREA-003, driving AREA-005)
- ENT-002 — Test plan lifecycle (AREA-004, driving AREA-005)
- ENT-003 — Test run lifecycle (AREA-004 only)
- ENT-004 — Review record standing (AREA-005, driven by ENT-001/ENT-002)

## Shared decision points

- DEC-001 — Which permission a caller effectively holds on a project (feeds every area's write
  gates)
- DEC-002 — Which permission a review act needs, per subject type (AREA-005)

## Coverage note

This is a full-breadth, moderate-depth first pass across all six confirmed areas — every
controller and its primary service were read; some second- and third-order services (the
dashboard, condition/shared-step validation internals, plan statistics, classification
reconciliation) were traced at the depth the orientation cache already documents rather than
independently re-read line-by-line. Those are flagged `#inferred` with their source in the
relevant area file rather than presented as freshly `#observed`. See `active-context.md` for
the consolidated open-questions list.
