# TCMS

A self-hosted, Java test case management system serving **multiple client companies from one
instance**. One deployable: Spring Boot REST API + React SPA.

Not a test runner (it ingests results), not a defect tracker (it links out), not public multi-tenant
SaaS (companies are provisioned by an operator script).

**Hierarchy:** Instance → Company (the isolation boundary) → Project (a whole client product) →
suites. "Payments" is a Feature or a suite, never a project.

## Where things are

| | |
|---|---|
| Agent orientation, read first | `docs/agent-context/be-java.md`, `docs/agent-context/fe-react.md` |
| Session handoff (index + `docs/handoff/NN-slug.md`) | `docs/HANDOFF.md` |
| Build/run instructions and conventions | `CLAUDE.md` |
| Permissions, roles, grants — **authoritative** | `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` |
| REST contract — **authoritative** | `docs/specs/openapi.yaml` |
| Entity design; §0.1 is the `company_id` rule | `docs/specs/2026-07-30-tcms-erd.md` |
| Master design doc (§6a–§6v, milestones) | `docs/plans/2026-07-30-tcms-high-level-plan.md` |

Backend: `cd backend && ./gradlew build` (needs Docker — every test boots Postgres 17 via
Testcontainers). Frontend: `cd frontend && npm run build`. Local stack: `docker compose up --build`
from the repo root. Details and the traps in `CLAUDE.md`.

---

## Permission matrix

Roles are ordered least-permissioned first. Rows are grouped by how widely a permission is held — broadest at the top, narrowest at the bottom.
`✓` granted · `—` not granted · anything else is a qualifier that narrows the grant.

> **Authoritative source is `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3.** This
> table is a convenience copy. If they disagree, the spec wins and this table is the bug.
> 55 permissions, all seeded by `V3__seed_permissions_and_roles.sql`; no migration has added one
> since. There is no wildcard permission, and **code checks permissions, never role names**.

### `PROJECT` scope

| Permission | Viewer | Automation Service | Test Engineer | Test Lead | Company Admin | Instance Admin |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `case:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `project:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `case:comment` | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `report:view` | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `report:export` | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `doc:read` | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `artifact:read` | ✓ | — | ✓ | ✓ | ✓ | ✓ |
| `case:create` | — | quarantine only | ✓ | ✓ | ✓ | ✓ |
| `run:create` | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `result:submit` | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `evidence:attach` | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `suite:manage` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:edit` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:delete` | — | — | OWN | ✓ | ✓ | ✓ |
| `library:manage` | — | — | ✓ | ✓ | ✓ | ✓ |
| `case:review` | — | — | ✓ | ✓ | ✓ | ✓ |
| `suite:review` | — | — | ✓ | ✓ | ✓ | ✓ |
| `plan:review` | — | — | ✓ | ✓ | ✓ | ✓ |
| `plan:create` | — | — | ✓ | ✓ | ✓ | ✓ |
| `plan:edit` | — | — | ✓ | ✓ | ✓ | ✓ |
| `run:execute` | — | — | ✓ | ✓ | ✓ | ✓ |
| `evidence:delete` | — | — | OWN | ✓ | ✓ | ✓ |
| `defect:create` | — | — | ✓ | ✓ | ✓ | ✓ |
| `workitem:manage` | — | — | ✓ | ✓ | ✓ | ✓ |
| `doc:edit` | — | — | ✓ | ✓ | ✓ | ✓ |
| `artifact:edit` | — | — | plan scope only | ✓ | ✓ | ✓ |
| `suite:delete` | — | — | — | ✓ | ✓ | ✓ |
| `case:approve` | — | — | — | ✓ | ✓ | ✓ |
| `suite:approve` | — | — | — | ✓ | ✓ | ✓ |
| `plan:approve` | — | — | — | ✓ | ✓ | ✓ |
| `plan:delete` | — | — | — | ✓ | ✓ | ✓ |
| `run:close` | — | — | — | ✓ | ✓ | ✓ |
| `result:amend` | — | — | — | ✓ | ✓ | ✓ |
| `doc:publish` | — | — | — | ✓ | ✓ | ✓ |
| `artifact:delete` | — | — | — | ✓ | ✓ | ✓ |
| `project:audit` | — | — | — | ✓ | ✓ | ✓ |
| `project:settings` | — | — | — | ✓ | ✓ | ✓ |
| `project:members` | — | — | — | ✓ | ✓ | ✓ |
| `integration:manage` | — | — | — | ✓ | ✓ | ✓ |

### `COMPANY` scope

Answered from the SYSTEM + COMPANY terms only, so a PROJECT-scope role contributes nothing here.
`company:read` reaches every member through the non-assignable **Company Member** baseline role
(`V15`), not through the six.

| Permission | Every member | Company Admin | Instance Admin |
|---|:--:|:--:|:--:|
| `company:read` | ✓ | ✓ | ✓ |
| `company:settings` | — | ✓ | ✓ |
| `company:members` | — | ✓ | ✓ |
| `company:teams` | — | ✓ | ✓ |
| `company:vocabulary` | — | ✓ | ✓ |
| `company:idp` | — | ✓ | ✓ |
| `project:create` | — | ✓ | ✓ |
| `token:issue` | — | ✓ | ✓ |
| `audit:view` | — | ✓ | ✓ |

### `SYSTEM` scope

Held in `instance_role_assignment`, a separate instance-level table — a nullable `company_id` under
RLS would be permanently unreachable.

| Permission | Instance Admin |
|---|:--:|
| `company:create` | ✓ |
| `company:delete` | ✓ |
| `company:suspend` | ✓ |
| `user:manage` | ✓ |
| `role:manage` | ✓ |
| `system:settings` | ✓ |
| `instance:audit` | ✓ |

### Notes that change how the table reads

- **The six roles are the complete assignable set**, seeded and immutable — **no custom roles, not
  even clone-and-edit**. A client needing something between two tiers gets the higher one.
- **`Company Member` is a seventh row that is not one of the six**: exactly `company:read`, granted
  automatically on joining, filtered out of `GET /roles`, refused by `POST /role-assignments`.
- **An API token never confers its own permissions.** It *caps* its owner's:
  `evaluate(ownerId, projectId) ∩ permissionsOf(token.roleId) ∩ [the token's project]` — intersection,
  never union, so a token can exceed neither its role nor its owner, and demoting a person shrinks
  every token they issued.
- **`OWN` narrows through the intersection too** (`PermissionQualifier.narrowedBy`): a company-wide
  `ALL` capped by a role's `OWN` is `OWN`.
- **`case:comment` is one key covering cases, suites and plans.** `suite:comment` / `plan:comment`
  deliberately do not exist — they would be seeded to nobody, making a comment on a folder a 403 no
  administrator could grant their way out of. `evidence:read` was rejected for the same reason.
- **Resolving a comment thread is not commenting** — it costs the subject's `*:review`.
- **Author-cannot-approve-own-work defaults on.**
