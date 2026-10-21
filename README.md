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
> table is a convenience copy. If they disagree, the spec wins and this table is the bug — and
> since §215 the build says so: `packages/client/src/test/matrixCopies.test.ts` compares all three
> scopes below against §A3 cell by cell, both ways, so a copy that goes stale reddens CI.
> 89 permissions — 55 seeded by `V3__seed_permissions_and_roles.sql`, `doc:review` by `V71`
> (2026-08-28), and 33 by `V2` (2026-08-30) for the project-management expansion: `issue:*`,
> `board:*`, `release:*`, `time:*`, `staffing:*`, `client:*`, `ratecard:*` and `billing:*`.
> There is no wildcard permission, and **code checks permissions, never role names**.

### `PROJECT` scope

| Permission | Viewer | Automation Service | Test Engineer | Project Manager | Test Lead | Company Admin | Instance Admin |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `case:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `project:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `issue:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `board:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `case:comment` | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `report:view` | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `report:export` | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `doc:read` | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `artifact:read` | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `issue:comment` | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `release:read` | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `case:create` | — | quarantine only | ✓ | — | ✓ | ✓ | ✓ |
| `run:create` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `result:submit` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `evidence:attach` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `suite:manage` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `case:edit` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `case:delete` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `library:manage` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `case:review` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `suite:review` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `plan:review` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `doc:review` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `plan:create` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `plan:edit` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `plan:delete` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `run:execute` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `evidence:delete` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `defect:create` | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `workitem:manage` | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `doc:edit` | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `artifact:edit` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `suite:delete` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `artifact:delete` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `run:close` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `result:amend` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `doc:publish` | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `issue:create` | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `issue:edit` | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `time:log` | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| `issue:delete` | — | — | — | ✓ | ✓ | ✓ | ✓ |
| `issue:workflow` | — | — | — | ✓ | ✓ | ✓ | ✓ |
| `issue:import` | — | — | — | ✓ | ✓ | ✓ | ✓ |
| `board:rank` | — | — | — | ✓ | — | ✓ | ✓ |
| `board:configure` | — | — | — | ✓ | — | ✓ | ✓ |
| `release:manage` | — | — | — | ✓ | — | ✓ | ✓ |
| `release:publish` | — | — | — | ✓ | — | ✓ | ✓ |
| `time:read` | — | — | — | ✓ | — | ✓ | ✓ |
| `time:edit` | — | — | — | ✓ | — | ✓ | ✓ |
| `time:approve` | — | — | — | ✓ | — | ✓ | ✓ |
| `staffing:read` | — | — | — | ✓ | — | ✓ | ✓ |
| `staffing:allocate` | — | — | — | ✓ | — | ✓ | ✓ |
| `case:approve` | — | — | — | — | ✓ | ✓ | ✓ |
| `suite:approve` | — | — | — | — | ✓ | ✓ | ✓ |
| `plan:approve` | — | — | — | — | ✓ | ✓ | ✓ |
| `project:audit` | — | — | — | ✓ | ✓ | ✓ | ✓ |
| `project:settings` | — | — | — | ✓ | ✓ | ✓ | ✓ |
| `project:members` | — | — | — | ✓ | ✓ | ✓ | ✓ |
| `integration:manage` | — | — | — | ✓ | ✓ | ✓ | ✓ |

`workitem:manage` is **superseded by `issue:*`** (2026-08-30, module M1): stored board cards are
issues now, so creating one costs `issue:create` and moving one `issue:edit`. The key is kept in
the catalogue and read by nothing.

**The ten rows with a `—` under Test Engineer and a `✓` under Test Lead are the whole tier line** —
~~seven since `V52` (2026-08-15)~~ → **ten since `V2` (2026-08-30)**: giving approvals, adding and
managing permission on the project, and managing its issues (`issue:delete`, `issue:workflow`,
`issue:import`). A Test Engineer creates, edits and deletes everything else in the project, whoever
authored it.

**Project Manager is not a rung on that ladder** — the column sits between Test Engineer and Test
Lead for readability, and the two roles are peers rather than tiers. A Project Manager runs the
delivery of the project (issues, board, releases, time, this project's staffing, and every
project-configuration key) and authors, approves and executes nothing in the test repository. `artifact:edit` was
~~`plan scope only`~~ until the same migration, which left **no grant in the catalogue carrying any
qualifier**.

### `COMPANY` scope

Answered from the SYSTEM + COMPANY terms only, so a PROJECT-scope role contributes nothing here.
`company:read` reaches every member through the non-assignable **Company Member** baseline role
(`V15`), not through the per-role grants. **Account Manager and Finance** are the two `COMPANY`-scope
roles `V2` added (2026-08-30); neither holds `project:read` or `report:view`, because at this scope
either key would open every project in the company, present and future.

| Permission | Every member | Account Manager | Finance | Company Admin | Instance Admin |
|---|:--:|:--:|:--:|:--:|:--:|
| `company:read` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `client:read` | — | ✓ | ✓ | ✓ | ✓ |
| `staffing:directory` | — | ✓ | ✓ | ✓ | ✓ |
| `client:create` | — | ✓ | — | ✓ | ✓ |
| `client:edit` | — | ✓ | — | ✓ | ✓ |
| `client:delete` | — | ✓ | — | ✓ | ✓ |
| `staffing:profile` | — | ✓ | — | ✓ | ✓ |
| `staffing:absence` | — | ✓ | — | ✓ | ✓ |
| `ratecard:read` | — | — | ✓ | ✓ | ✓ |
| `ratecard:manage` | — | — | ✓ | ✓ | ✓ |
| `billing:read` | — | — | ✓ | ✓ | ✓ |
| `billing:budget` | — | — | ✓ | ✓ | ✓ |
| `billing:expense` | — | — | ✓ | ✓ | ✓ |
| `billing:invoice` | — | — | ✓ | ✓ | ✓ |
| `billing:issue` | — | — | ✓ | ✓ | ✓ |
| `company:settings` | — | — | — | ✓ | ✓ |
| `company:members` | — | — | — | ✓ | ✓ |
| `company:teams` | — | — | — | ✓ | ✓ |
| `company:vocabulary` | — | — | — | ✓ | ✓ |
| `company:idp` | — | — | — | ✓ | ✓ |
| `project:create` | — | — | — | ✓ | ✓ |
| `token:issue` | — | — | — | ✓ | ✓ |
| `audit:view` | — | — | — | ✓ | ✓ |

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

- ~~**The six roles are the complete set**~~ → **nine since `V2` (2026-08-30)**, seeded and
  immutable — **no custom roles, not even clone-and-edit**. A client needing something between two
  tiers gets the higher one. ~~**Five of them are assignable to a person**~~ **Eight are**: `V41`
  made `Automation Service` non-assignable, since it is a capability set a CI *credential* carries
  and the spec has said "never people" since M0. `V2` added **Project Manager** (`PROJECT`),
  **Account Manager** and **Finance** (`COMPANY`), all three grantable.
- **`Company Member` is a row that is not one of the nine**: exactly `company:read`, granted
  automatically on joining, filtered out of `GET /roles`, refused by `POST /role-assignments`.
- **`Agent` is another row that is not one of the nine either** (`V40`): the ceiling an API token
  carries for agent access — **Test Engineer minus `library:manage`, `case:delete`,
  `evidence:delete`, `plan:delete` and `company:read`** (five since `V51`; the role gained nothing,
  Test Engineer gained `plan:delete`), enumerated in the roles spec §A6a. Like `Company Member`
  it is `assignable = false` and `POST /role-assignments` refuses it; **unlike** it, `GET /roles`
  returns it and a token may carry it, because the token-issue form is the only place it is ever
  chosen. An `Agent` token must carry an expiry. **`Automation Service` is the third `assignable =
  false` row (`V41`) and behaves like `Agent`, not like the baseline** — listed, issuable, and
  refused only as a grant.
- **Non-assignable is not "non-revocable", since `V41`.** `RoleGrantService` refuses a revoke for
  `Company Member` alone, because that is the only one whose removal breaks something the endpoint
  cannot repair. An `Automation Service` grant made before the rule existed stays listed and stays
  revocable — otherwise it would sit on the screen behind a Revoke button that 403s forever.
- **An API token never confers its own permissions.** It *caps* its owner's:
  `evaluate(ownerId, projectId) ∩ permissionsOf(token.roleId) ∩ [the token's project]` — intersection,
  never union, so a token can exceed neither its role nor its owner, and demoting a person shrinks
  every token they issued.
- **A qualifier narrows through the intersection too** (`PermissionQualifier.narrowedBy`): a
  company-wide `ALL` capped by a role's `OWN` is `OWN`. **No grant carries any qualifier** — `OWN`
  emptied by `V51` (2026-08-14), because CRUD belongs to every non-Viewer contributor no matter who
  created the row; ~~the qualifier still in play is `PLAN_SCOPE`~~ → **`PLAN_SCOPE` emptied by `V52`
  (2026-08-15)**, when `artifact:edit` — the only key that ever carried it — was de-qualified. So
  this describes a mechanism kept deliberately unpopulated, not a live narrowing, and every row of
  `role_permission.qualifier` is now `'ALL'`.
- **`case:comment` is one key covering cases, suites and plans.** `suite:comment` / `plan:comment`
  deliberately do not exist — they would be seeded to nobody, making a comment on a folder a 403 no
  administrator could grant their way out of. `evidence:read` was rejected for the same reason.
- **Resolving a comment thread is not commenting** — it costs the subject's `*:review`.
- **Author-cannot-approve-own-work defaults on.**

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md). You may use, modify and self-host TCMS for any
noncommercial purpose — personal study, hobby projects, research, or use by a charity, school or
public institution. Commercial use is not licensed: that includes selling it, offering it as a
hosted service, and using it inside a business. For any other use, ask the author.
