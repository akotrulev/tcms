<!-- orientation-cache v1 -->

# be-documenter — orientation cache (TCMS)

**Last verified:** 2026-08-03
**Baseline commit:** `93444b2`
**Fingerprint files:** `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml` (stack
version lane — shared with `be-java.md`, not re-derived here)

> This is a map for re-orienting fast on the *documentation* pass, not a source of truth. The
> KB itself (`docs/business-model/`) is the source of truth about what's been documented;
> `docs/agent-context/be-java.md` remains authoritative on stack/layout/seams for the code.

## Version lane

Not independently tracked here — defer entirely to `docs/agent-context/be-java.md`, which
`be-documenter` reads every run per `ep-agent-orientation-cache`.

## Layout — what be-documenter reads to survey this codebase

Entry-point surfaces, by module (`com.tcms.<module>`), as of `93444b2`:

| Module | Controllers | Notes |
|---|---|---|
| `auth` | AuthController, InvitationController, MeController | session, invitations, `/me` |
| `identity` | AdminController, ProjectMemberController, TeamController | roles, users, teams |
| `project` | ProjectController, ProjectSettingsController, ReviewController | projects, vocab, review settings, review workflow |
| `repository` | CaseController, SuiteController, ConditionController, SharedStepController, DashboardController | suite tree, cases, libraries, dashboard |
| `execution` | PlanController, RunController, EvidenceController, CaseHistoryController, SuiteHistoryController | plans, runs, evidence, version history |
| `documentation` | DocPageController, ArtifactController | spec pages, artifact register |
| `work` | WorkItemController | kanban board — **in-flight/uncommitted as of this run, deliberately not documented** |
| `provisioning` | ProvisionCompanyCommand, IssueInvitationCommand | CLI verbs, not HTTP |
| `tenancy` | (none — no controller; cross-cutting enforcement only) | |

No message consumers or scheduled jobs found beyond what `be-java.md` already documents.
`/reports` is designed (plan, contract) but has no controller anywhere — dead contract, per
`be-java.md`'s own "Known unknowns," not independently re-verified by a fresh grep this run
beyond confirming no `ReportingController` exists.

## Seams to reuse — the area map (confirmed with the requester 2026-08-03)

Six functional areas, confirmed before tracing began:

1. **Identity & Access** — `auth` + `identity` modules → `docs/business-model/areas/identity-access.md`
2. **Company & Project Administration** — `project.ProjectController`/`ProjectSettingsController` + `provisioning` → `areas/company-project-administration.md`
3. **Test Repository** — `repository` module → `areas/test-repository.md`
4. **Test Execution** — `execution` module → `areas/test-execution.md`
5. **Review & Collaboration** — `project.ReviewController` (spans case/suite/plan subjects) → `areas/review-collaboration.md`
6. **Documentation** — `documentation` module → `areas/documentation.md`

**Deliberately parked, not silently skipped:** `work` module (uncommitted in-flight code at
time of this run) and `/reports` (no implementation exists).

## Coverage ledger (this run)

| Area | Controllers traced | Depth |
|---|---|---|
| Identity & Access | AuthController, MeController, InvitationController, (AdminController/TeamController/ProjectMemberController routes enumerated, services read: AuthenticationService, ProjectMembershipService, RoleGrantService, InvitationService) | Full read of primary services; AdminController/TeamController bodies not line-read |
| Company & Project Admin | ProjectController (full), ProjectSettingsController (full), ProvisionCompanyCommand (full) | Full |
| Test Repository | CaseController (full), SuiteController (partial — header + create/rename), LifecycleLadder (full); ConditionController/SharedStepController headers only | Mixed — core authoring flow full, libraries shallow |
| Test Execution | RunController (full), PlanController (partial — header + list/detail), EvidenceController (partial — header + attach), RunLifecycle (full), PlanLifecycle (full) | Mixed — lifecycles full, CRUD detail shallow |
| Review & Collaboration | ReviewController (full), ReviewStanding (full) | Controller + standing full; ReviewService internals taken from `be-java.md`'s prior findings, not re-read |
| Documentation | DocPageController (full), ArtifactController (partial) | Mostly full |

Several second-order services (DashboardController, PlanStatisticsService, classification
reconciliation internals, ReviewService's own body) were **not** independently re-read this
session; their business rules in the KB are sourced from `be-java.md`'s prior findings and
marked `#inferred`/flagged as such rather than presented as freshly `#observed`. A deeper pass
re-reading those services directly would upgrade those statements.

## KB state

`docs/business-model/` — created this run (was empty on arrival). 6 areas, 4 entities, 3 flows,
2 decisions, system-overview, glossary, INDEX, active-context. Zero `#confirmed` entries (none
written, per the agent's own rule). See `docs/business-model/INDEX.md` for the authoritative
current listing — do not duplicate its content here; this file only tracks *what the survey
covered and at what depth*, for the next `be-documenter` run to pick up from.

## Conventions observed (about the codebase, useful for future documentation passes)

- Controller javadocs in this codebase are unusually rich and largely double as business
  rationale — read them first, they are the fastest route to `#inferred` statements with high
  confidence, but they are still this agent's own reading and must be tagged `#inferred`, never
  promoted to `#confirmed`.
- `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` is the authoritative source for the
  permission catalogue and the six roles — reference it directly rather than re-deriving or
  restating the whole matrix in the KB.
- Shared "ladder" classes (`LifecycleLadder`, `PlanLifecycle`, `RunLifecycle`) are pure, static,
  and are the fastest, most reliable route to an accurate entity state grid — read these
  directly rather than inferring transitions from controller behaviour alone.

## Known unknowns

- No cross-area conflict sweep (`interaction-map.md`) has been run — that's `business-analyst`'s
  job.
- `work` module and `/reports` are undocumented by design; re-run AREA mode against `work` once
  it lands and is committed.
- DashboardController, PlanStatisticsService, and ReviewService's own internals were not
  independently re-read this session (see coverage ledger) — a REFRESH pass that re-reads them
  directly would strengthen several `#inferred` statements to `#observed`.

## Session log

- 2026-08-03 · FULL · First documentation run. Confirmed 6-area split with requester, traced all
  six, wrote the full KB from scratch. See `docs/business-model/active-context.md` for the
  detailed session entry and the consolidated open-questions list.
