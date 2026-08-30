# M0 — Foundation Implementation Plan

**Date:** 2026-08-30 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the expansion's permission catalogue, its three new roles and the two schema
affordances every later module binds to — a per-project issue counter, an `'ISSUE'` link source and
a nullable `project.client_id` — with no screen changed and no role name hardcoded anywhere.

**Architecture:** One Flyway migration, `V2__pm_foundation.sql`, which is the only place any of it
is decided. The counter is a **new** table owned by the `project` foundation module
(`project_key_sequence`), seeded through the existing `tcms_seed_project_defaults` function so
every creation path gets it for free, and read through a `tenancy.DisplayKeys`-shaped allocator.
The catalogue grows from 56 keys to **89** and the role set from eight rows to eleven; five
documents and four test files transcribe that and are compared to it by tests, so they move in the
same commit as the seed.

**Tech stack:** Postgres 17 + Flyway (`V2`), Spring Boot 3.5.6 / Java 21, `openapi.yaml` + two
generated clients, Vite 8 / React 19 (no screen work — one `satisfies` map and one empty-state
sentence).

**Module:** M0 of `docs/plans/009-project-management-expansion-modules.md`. **Depends on:** nothing.
Status table checked 2026-08-30: every module reads `not started`, so nothing else is in progress.

**Tasks** — in the order they are done:

| # | Task | Deliverable | Depends on |
|---|---|---|---|
| 1 | T1 — `Role.name` gains three members | `openapi.yaml`'s `Role.name` enum lists nine names and both generated clients carry them; all four gates still green | none |
| 2 | T2 — The catalogue: `V2` and everything pinned to it | `SELECT count(*) FROM permission` is 89, `role` holds eleven rows including Project Manager / Account Manager / Finance, and `SeedDataTest`, `matrixCopies.test.ts` and `tierClaims.test.ts` are green against the seed | T1 |
| 3 | T3 — `ProjectKeys`, the per-project allocator | `com.tcms.project.ProjectKeys.allocate(projectId, n)` returns `ACME-1…ACME-n` for a project whose `project.key` is `ACME`, and throws a named exception when the counter row is missing | T2 |
| 4 | T4 — The documents M0 leaves wrong | The ERD's key-design section, `docs/features.md`, `LinkSourceType`'s javadoc and the Help empty state all describe nine roles, 89 keys and two counters | T3 |
| 5 | T5 — Gates on `shanks`, and the five acceptance checks | All four gates green on `shanks`; a company provisions, a new project gets its `project_key_sequence` row, and the three roles are grantable from the existing member screen | T4 |
| 6 | T6 — Close plan 009 | Plan 009's status table reads `done`, its `**Built and unverified:**` list exists, and the M1 / M7 / Mc prompts name `ProjectKeys` and the decisions below | T5 |

---

## Decisions this plan takes, and the ones it takes against the documents it was given

Recorded here rather than resolved silently, per plan 009's *"Every disagreement is recorded in the
module's own plan."*

### D-M0-1 — The counter is a new table; `key_sequence` is not touched

Plan 009 review R1 already reversed the scope document's *"`key_sequence` needs a primary-key
change"* (a primary key cannot contain a nullable column, so `(company_id, entity_type, project_id)`
with a nullable `project_id` never ran). This plan implements R1: `project_key_sequence
(company_id, project_id, next_value)`, PK `(company_id, project_id)`, **no prefix column** — the
prefix is `project.key`, which already exists with `uq_project_key` and `ck_project_key_format`.
`key_sequence`, `tenancy.DisplayKeys`, its five-member `EntityType` and its seven readers are
untouched.

### D-M0-2 — The row is seeded by `tcms_seed_project_defaults`, not by `ProjectCreateService`

Plan 009's `be` line says *"`project` gains the `project_key_sequence` row on create (in
`ProjectCreateService`)"*. **Deviated from**, deliberately: `tcms_seed_project_defaults(company,
project)` is called by **both** `ProjectCreateService:99` and `CompanyProvisioningService:328,337`,
is already idempotent, and already refuses a project it cannot see. Putting the `INSERT` there
covers every creation path — including `seed-demo-data` and anything M4's importer adds — with no
Java change and no second place to forget. The function is reproduced **in full** under
`CREATE OR REPLACE`, per the rule its own V18 header states: `CREATE OR REPLACE` replaces the whole
body, so anything omitted stops being seeded, silently, for every project created from then on.

### D-M0-3 — `project.client_id` is not mapped on the `Project` entity

The column ships nullable, with **no** `REFERENCES` (Mc adds the FK when `client` exists) and **no**
`@Column` on `com.tcms.project.Project`. `hibernate.ddl-auto: validate` is satisfied by an extra
database column with no entity field, and leaving it unmapped is what keeps it out of
`ArchitectureRulesTest.READERLESS_BY_DECISION` and `WRITERLESS_BY_DECISION` — both of which sweep
`@Entity` fields, in both directions, so an entry added now would have to be removed by Mc.

### D-M0-4 — The catalogue is 33 new keys, not "~35"

§7 of the scope document estimates *"~35 new permissions … roughly 91"*. Enumerated against the
stories and the module map, it is **33 new keys and 89 total**. Every key below separates two roles
from each other; none is seeded that no role holds without a neighbouring key, which is the argument
§A3 already used to refuse `suite:comment` and `plan:comment`.

### D-M0-5 — Test Lead gets `issue:*` and nothing else (owner's decision, 2026-08-30)

D3 reads *"Test Lead | unchanged + `issue:*`"* and is taken **literally**. Two consequences, both
of which a later module must plan around rather than discover:

1. **Test Lead minus Test Engineer grows from seven keys to ten** — the `V52` seven plus
   `issue:delete`, `issue:workflow` and `issue:import`. §A4's *"exactly seven things"*, `V1`'s grant
   comment, `SeedDataTest.testEngineerToTestLeadBoundary`, `roleMatrix.ts`'s `LEAD_ONLY` and
   `docs/features.md:36` all state the seven and all move together in T2/T4.
2. **A project staffed with a Test Lead and no Project Manager cannot configure a board (M2), cut a
   release (M3) or approve a timesheet (M6).** Only Company Admin can. This is what D3 says; it is
   flagged for M2, M3 and M6 in the hand-off rather than repaired here by widening a grant nobody
   decided to widen.

### D-M0-6 — Viewer holds `issue:comment` (owner's decision, 2026-08-30)

D3 lists only `issue:read`, `board:read` for Viewer. `issue:comment` is granted to it as well, by
§A3's own comment-tier argument: `case:comment` exists **because** Viewer holds it — *"built for the
case where the client reviews test coverage themselves without gaining any write access"* — and a
comment key held by nobody who lacks `issue:edit` would buy nothing. The ladder property §A3 states
(*every role holding any `*:review` also holds `case:comment`*) has no analogue here because there
is no issue-review tier; what carries over is the Viewer half.

### D-M0-7 — `staffing:*` is split across two scopes, because scope is a property of the holder

There is no `permission.scope` column: a key is answerable company-wide only from `SYSTEM` and
`COMPANY` grants (`PermissionEvaluator.evaluateCompanyWide`), and a `PROJECT`-scope role holding a
key that is only ever *asked* company-wide holds an **inert row** — the exact defect §43 repaired
for `company:read`, and the reason three cells are on `MATRIX_DIVERGENCE_BY_DECISION` today. So:

| Key | §A3 table | Because the question is |
|---|---|---|
| `staffing:read`, `staffing:allocate` | PROJECT | *"who is on **this project** and at what percentage"* (story S1) |
| `staffing:directory`, `staffing:profile`, `staffing:absence` | COMPANY | *"who has this skill and when are they free"*, and the over-allocation flag, which are company-wide views (story S2) |

The same test is applied to every other new key: `client:*`, `ratecard:*` and `billing:*` are held
by `COMPANY`-scope roles only and sit in `COMPANY` tables; `issue:*`, `board:*`, `release:*` and
`time:*` are asked per project and sit in `PROJECT` tables.

### D-M0-8 — Account Manager and Finance hold no `project:read` and no `report:view`

Both are `COMPANY`-scope, so either key would open **every project's contents, present and future**
— which is the boundary §A1 draws and the reason `Company Member` exists. A client page listing its
projects is served from the client aggregate under `client:read` (Mc's job); a Finance user pricing
approved hours reads `time_entry` under `billing:invoice` (M8's).

### D-M0-9 — The project form's client picker rides `project:settings`, not `client:read`

Story C2 is *"As a Project Manager I want to say which client a project is for"*, and Project
Manager is `PROJECT`-scope, so a company-wide `client:read` would be inert for them (D-M0-7). The
alternative — granting `client:read` to the `Company Member` baseline — would break the one sentence
that role is defined by (*"Exactly `company:read`, granted automatically to every member on
joining"*). So Mc serves the picker from the project-settings endpoint under `project:settings`, and
`Company Member` stays at exactly one key. Recorded here because Mc must honour it.

### D-M0-10 — Plan 009's *"`git diff --stat frontend/src` names only `schema.d.ts`"* cannot hold

**The "no screen changes" claim itself holds, and was re-verified before this plan was written.**
Every role-rendering site is data-driven: `features/admin/roleAccess.ts` branches on `role.scope`,
`GrantRoleDialog.tsx`, `RoleAssignmentTable.tsx`, `RoleReference.tsx` and `help/RoleMatrix.tsx`
render whatever `GET /roles` and `GET /role-assignments` return, and the only `role.name ===` in the
tree is `IssueTokenDialog.tsx:123,212` comparing to `'Agent'` for the MCP-config panel — a
credential-shape branch, unrelated to grants and correct as it stands. **No hardcoding to remove.**

What the criterion missed is that the catalogue is a *contract enum*:

- `frontend/src/lib/permissions.ts` closes `permissionAreas` with
  `as const satisfies Record<PermissionKey, Area>` and `permissionLabels` with
  `Record<PermissionKey, string>`. Thirty-three new members make that file a **compile error** until
  each is placed in an area and given a verb phrase. `SeedDataTest.areasMatchTheSpa` parses this
  file and compares it to `permission.area`, so it is not optional and not deferrable.
- `frontend/src/features/help/RoleMatrix.tsx:63` says *"the six built-in roles are seeded"* in its
  empty state. Copy, not logic — but a claim, and false at nine.

**Corrected criterion, which T5 checks:** `git diff --stat frontend/src` names exactly
`api/schema.d.ts`, `lib/permissions.ts` and `features/help/RoleMatrix.tsx`, and **no file under
`features/admin/`**. A hand-written file under `features/` other than that one line of copy is the
hardcoding plan 009 warns about.

---

## The catalogue — 33 new keys, and who holds each

Eleven `role` rows: the eight `V1` seeds plus **Project Manager** (`PROJECT`), **Account Manager**
(`COMPANY`) and **Finance** (`COMPANY`), all three `built_in = true`, `assignable = true`.

Abbreviations, as §A3 will print them: **IA** Instance Admin · **CA** Company Admin ·
**PM** Project Manager · **TL** Test Lead · **TE** Test Engineer · **AS** Automation Service ·
**V** Viewer · **AM** Account Manager · **FIN** Finance.

### `PROJECT`-scope keys — 22

| Key | Area | Description (seeded) | IA | CA | PM | TL | TE | AS | V |
|---|---|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `issue:read` | tracker | Read epics, stories and tasks | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `issue:create` | tracker | Create an issue at any level | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| `issue:edit` | tracker | Edit an issue, including its status and assignee | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| `issue:delete` | tracker | Delete an issue | ✓ | ✓ | ✓ | ✓ | — | — | — |
| `issue:comment` | tracker | Comment on an issue without any write access to it | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `issue:workflow` | tracker | Configure the project's issue types, statuses and transitions | ✓ | ✓ | ✓ | ✓ | — | — | — |
| `issue:import` | tracker | Import a project's issues from an external tracker | ✓ | ✓ | ✓ | ✓ | — | — | — |
| `board:read` | board | See the project's boards and backlog | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `board:rank` | board | Order the backlog | ✓ | ✓ | ✓ | — | — | — | — |
| `board:configure` | board | Create boards and define their columns, swimlanes and WIP limits | ✓ | ✓ | ✓ | — | — | — | — |
| `release:read` | releases | See releases and the roadmap | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `release:manage` | releases | Create, edit and delete releases and their scope | ✓ | ✓ | ✓ | — | — | — | — |
| `release:publish` | releases | Mark a release released | ✓ | ✓ | ✓ | — | — | — | — |
| `time:log` | time | Log your own hours | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| `time:read` | time | See other people's time entries | ✓ | ✓ | ✓ | — | — | — | — |
| `time:edit` | time | Amend a time entry somebody else recorded | ✓ | ✓ | ✓ | — | — | — | — |
| `time:approve` | time | Approve or reject a submitted timesheet period | ✓ | ✓ | ✓ | — | — | — | — |
| `staffing:read` | staffing | See this project's allocations and capacity | ✓ | ✓ | ✓ | — | — | — | — |
| `staffing:allocate` | staffing | Allocate a person to this project for a period | ✓ | ✓ | ✓ | — | — | — | — |

That table is nineteen rows; the remaining three `PROJECT`-scope entries are the existing
`project:*` keys Project Manager joins — no new key, only a new column. Project Manager's full
grant over the **existing** 56 is in the T2 SQL and in §A3's amended tables.

### `COMPANY`-scope keys — 14

| Key | Area | Description (seeded) | IA | CA | AM | FIN | Every member |
|---|---|---|:--:|:--:|:--:|:--:|:--:|
| `client:read` | clients | See clients, their contacts and their projects | ✓ | ✓ | ✓ | ✓ | — |
| `client:create` | clients | Record a new client organisation | ✓ | ✓ | ✓ | — | — |
| `client:edit` | clients | Edit a client and its contacts | ✓ | ✓ | ✓ | — | — |
| `client:delete` | clients | Delete a client | ✓ | ✓ | ✓ | — | — |
| `staffing:directory` | staffing | See the company-wide capacity view and the skills directory | ✓ | ✓ | ✓ | ✓ | — |
| `staffing:profile` | staffing | Maintain people's skills and staffing profiles | ✓ | ✓ | ✓ | — | — |
| `staffing:absence` | staffing | Record absence on somebody else's behalf | ✓ | ✓ | ✓ | — | — |
| `ratecard:read` | money | See rate cards and what an hour is charged at | ✓ | ✓ | — | ✓ | — |
| `ratecard:manage` | money | Create and edit rate cards | ✓ | ✓ | — | ✓ | — |
| `billing:read` | money | See budgets, invoices and margin | ✓ | ✓ | — | ✓ | — |
| `billing:budget` | money | Set and edit a project's budget | ✓ | ✓ | — | ✓ | — |
| `billing:expense` | money | Record an expense against a project | ✓ | ✓ | — | ✓ | — |
| `billing:invoice` | money | Draft and edit an invoice | ✓ | ✓ | — | ✓ | — |
| `billing:issue` | money | Issue an invoice, locking its lines | ✓ | ✓ | — | ✓ | — |

`Account Manager` and `Finance` also hold `company:read` — with a column in §A3's COMPANY tables, so
neither needs a `MATRIX_DIVERGENCE_BY_DECISION` entry. **`Project Manager` holds `company:read` too
and does need one**, for exactly the reason Test Lead, Test Engineer and Viewer already have one:
the row is a `PROJECT`-scope grant of a `COMPANY`-scope key, unreachable from
`evaluateCompanyWide`, inert rather than wrong, and carried by `Company Member` in practice.

**`Agent` needs no decision.** §A6a defines it as Test Engineer entire minus `company:read`, and
`SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse` derives that delta from the live catalogue
— so it picks up `issue:read`, `issue:create`, `issue:edit`, `issue:comment`, `board:read`,
`release:read` and `time:log` mechanically, which is what D3's *"Viewer / Automation Service / Agent
| + `issue:read`, `board:read`"* asks for and more, by the rule that already governs it.
`Automation Service` gains `issue:read` and `board:read` and nothing else.

### The seven new areas

`lib/permissions.ts` and `permission.area` group the catalogue for humans and must agree exactly:
`clients`, `tracker`, `board`, `releases`, `time`, `staffing`, `money`.

---

## File structure

**Created**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V2__pm_foundation.sql` | Every schema and seed change M0 makes. The only place any of it is decided. |
| `backend/src/main/java/com/tcms/project/ProjectKeys.java` | The per-project issue-key allocator. `tenancy.DisplayKeys` shaped, one `UPDATE … RETURNING`. |
| `backend/src/test/java/com/tcms/project/ProjectKeysTest.java` | Allocation, range reservation, the missing-row exception, and the RLS refusal. |
| `backend/src/test/java/com/tcms/tenancy/ProjectKeySequenceSchemaTest.java` | The counter row exists for every project after provisioning and after `POST /projects`. |

**Modified**

| File | Change |
|---|---|
| `docs/specs/openapi.yaml` | `Role.name` +3 members (T1); `PermissionKey` +33 members (T2). |
| `frontend/src/api/schema.d.ts` · `packages/client/src/schema.ts` | Regenerated, both, in the same commit as each contract change. |
| `frontend/src/lib/permissions.ts` | 33 entries in `permissionAreas` and `permissionLabels`; 7 members in `Area` and `areaLabels`. |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | §A2 role table +3 rows; §A3 every PROJECT table gains a `PM` column, the COMPANY table gains `AM`/`FIN`, five new tables; §A4's "seven keys" superseded. |
| `docs/specs/permission-matrix-summary.md` · `README.md` | The same matrix, same commit, or `matrixCopies.test.ts` reddens. |
| `backend/src/test/java/com/tcms/schema/SeedDataTest.java` | `SPEC_COLUMNS` +3, `MATRIX_DIVERGENCE_BY_DECISION` +1, the catalogue count 56 → 89, `testEngineerToTestLeadBoundary` 7 → 10. |
| `packages/client/src/test/roleMatrix.ts` | `COLUMNS` +`PM`, `SPEC_ROLES` +`AM`/`FIN`, `CATALOGUE_SIZE` 56 → 89, `LEAD_ONLY` 7 → 10, the `held.TE`/`held.TL` floors. |
| `packages/client/src/test/matrixCopies.test.ts` | `COPY_COLUMNS` +3 spellings. |
| `packages/client/src/test/tierClaims.test.ts` | `KEY`'s namespace list +8, `NAMED` +3 role spellings. |
| `docs/specs/2026-07-30-tcms-erd.md` | §366 key-design amendment in the superseded style; `project_key_sequence` block; §41, §787, §809. |
| `docs/features.md` | Six roles → nine; "exactly seven things" → ten; "six immutable roles" → nine. |
| `backend/src/main/java/com/tcms/documentation/LinkSourceType.java` | Javadoc: the column now admits a third value the enum does not offer. |
| `frontend/src/features/help/RoleMatrix.tsx` | Empty-state copy: six → nine. |
| `docs/plans/009-project-management-expansion-modules.md` | T6 only — status table, built-and-unverified, dependent prompts. |

**Deliberately unmodified, each for a checked reason**

- `com.tcms.tenancy.DisplayKeys` and its `EntityType` — D-M0-1.
- `com.tcms.project.Project` — D-M0-3.
- `com.tcms.provisioning.ExportCompanyService` and `ExportTables` — `ExportTables` derives the
  walked set from the tables carrying a `company_isolation` policy (*"Derived from the catalogue,
  never listed in Java"*), so `project_key_sequence` is exported with **no code change**. It is
  **not** added to `SENTINEL_TABLES`, which is a per-company liveness probe: a company with no
  projects legitimately has zero rows, and a sentinel that can be legitimately empty is a false
  alarm waiting to happen. Plan 009's *"`ExportCompanyService` exports the new table beside
  `key_sequence`"* is satisfied by construction.
- `deploy/postgres/initdb/10-tcms-roles.sql` — `ALTER DEFAULT PRIVILEGES` already covers every table
  a future migration adds.
- Everything under `frontend/src/features/admin/` — D-M0-10.

---

### T1 — `Role.name` gains three members

**Deliverable:** `openapi.yaml`'s `Role.name` enum lists nine names, `frontend/src/api/schema.d.ts`
and `packages/client/src/schema.ts` carry them, and `frontend npm run build`, `frontend npm run
lint` and `packages npm run build` are green.
**Depends on:** none

Its own commit, and nothing else rides in it. Safe on its own because nothing pins `Role.name` to
the seeded rows — the enum is a superset until T2 seeds the rows, and `GET /roles` keeps returning
six assignable names in the meantime. `ContractEnumTest` has no entry for `Role`, and there is no
server-side `RoleName` enum to widen.

**Files:**
- Modify: `docs/specs/openapi.yaml:15355-15364` (the `Role.name` enum) and its description block at `:15356`
- Modify (generated): `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts`

- [ ] **Step 1: Read the block you are about to edit**

```bash
sed -n '15345,15372p' docs/specs/openapi.yaml
```

Expected: the `Role:` schema, its description ending *"…never appears here at all."*, and the
`name:` enum of seven values.

- [ ] **Step 2: Widen the enum**

Replace the `name:` block at `docs/specs/openapi.yaml:15368-15377` with:

```yaml
        name:
          type: string
          enum:
            - Instance Admin
            - Company Admin
            - Project Manager
            - Account Manager
            - Finance
            - Test Lead
            - Test Engineer
            - Automation Service
            - Viewer
            - Agent
```

The order is §A2's, most authority first: the two administrator tiers, then the three roles D3
adds, then the four `V1` project roles, then the credential ceiling. `Company Member` stays absent —
it is filtered out server-side and never on the wire.

- [ ] **Step 3: Correct the description that counts them**

In the same schema's `description`, replace the paragraph beginning `**Seven names, and only five`
with:

```
        **Ten names, and only eight of them may be granted to a person.** Two are
        capability ceilings a *credential* carries: `Agent` (roles spec §A6a), and
        `Automation Service` (§A6), which has been documented as "attached to API
        tokens, never people" since M0 and was made non-assignable by `V41`. Both are
        listed here because the token-issue form is the only place either is ever
        chosen, and both are refused by `POST /role-assignments`. Filter this list by
        `assignable` when rendering a grant picker — see that field. The eleventh
        seeded row, the `Company Member` membership baseline, is filtered out
        server-side and never appears here at all.

        ~~Seven names, and only five of them may be granted to a person.~~ →
        **superseded 2026-08-30 (`V2`, plan 010): D3 of the project-management
        expansion adds Project Manager at `PROJECT` scope and Account Manager and
        Finance at `COMPANY` scope.** They are seeded, immutable and assignable like
        the rest; nothing in the code checks a role name, so the three arrive
        grantable through the screens that already exist.
```

- [ ] **Step 4: Regenerate both clients**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

- [ ] **Step 5: Confirm both moved, and that nothing else did**

```bash
cd ~/RiderProjects/tcms
git diff --stat frontend/src packages/client/src
grep -c 'Project Manager' frontend/src/api/schema.d.ts packages/client/src/schema.ts
```

Expected: exactly two files changed — `frontend/src/api/schema.d.ts` and
`packages/client/src/schema.ts` — and `1` from each `grep -c`.

- [ ] **Step 6: Typecheck and lint**

```bash
cd frontend && npm run build && npm run lint
cd ../packages && npm run build && npm run lint --workspaces
```

Expected: all green. `RoleName = Role['name']` is a widening union and is only ever rendered, so
nothing narrows against it.

- [ ] **Step 7: Commit**

```bash
cd ~/RiderProjects/tcms
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts
git commit -m "feat(contract): the role enum learns the three names D3 adds"
```

---

### T2 — The catalogue: `V2` and everything pinned to it

**Deliverable:** `SELECT count(*) FROM permission` is 89, `role` holds eleven rows including Project
Manager, Account Manager and Finance, `project_key_sequence` exists with a row per project,
`doc_link.source_type` admits `'ISSUE'`, `project.client_id` exists and is nullable — and
`SeedDataTest`, `matrixCopies.test.ts` and `tierClaims.test.ts` are green against all of it.
**Depends on:** T1 (`Role.name` already carries the three names, so nothing on the wire is a
surprise when the rows appear)

**One task and one commit, and the reason is the test harness, not preference.** Five checks compare
these files to each other, pairwise, in both directions:

| Check | Compares |
|---|---|
| `SeedDataTest.catalogueMatchesTheContract` | seeded `permission` rows ↔ `openapi.yaml` `PermissionKey`, both ways |
| `SeedDataTest.areasMatchTheSpa` | seeded `permission.area` ↔ `frontend/src/lib/permissions.ts` |
| `SeedDataTest.theSeededMatrixIsTheSpecsMatrix` | seeded `role_permission` ↔ roles spec §A3, cell by cell |
| `matrixCopies.test.ts` | §A3 ↔ `permission-matrix-summary.md` **and** ↔ `README.md` |
| `tierClaims.test.ts` | every present-tense prose claim ↔ §A3 |

Split them across commits and each intermediate commit is red — which the executing skill forbids
and which is worse advice than a large task. Plan 009's *"the three roles docs change in one commit
or `matrixCopies.test.ts` reddens"* is the same observation, one document short.

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__pm_foundation.sql`
- Modify: `docs/specs/openapi.yaml` (`PermissionKey`)
- Modify (generated): `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts`
- Modify: `frontend/src/lib/permissions.ts`
- Modify: `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` (§A2, §A3, §A4)
- Modify: `docs/specs/permission-matrix-summary.md`, `README.md`
- Modify: `backend/src/test/java/com/tcms/schema/SeedDataTest.java`
- Modify: `packages/client/src/test/roleMatrix.ts`, `matrixCopies.test.ts`, `tierClaims.test.ts`

- [ ] **Step 1: Confirm the migration version against the directory, not against plan 009**

```bash
ls backend/src/main/resources/db/migration/
```

Expected: `V1__initial_schema.sql` and nothing else. The next free version is therefore `V2`, which
is what plan 009's table reserved. **If anything else is listed, take the next free number and say
so in T6** — `out-of-order` is unset in `application.yml`, and Testcontainers builds each test
database from scratch so it will never show you the conflict.

- [ ] **Step 2: Do not probe for the `doc_link` CHECK's name — look it up in the migration**

`V1:1869` writes the constraint inline on the column, so **Postgres generates the name** and this
file must not hard-code a guess at it. Spinning a database up to read it is the wrong repair twice
over: it is a local stack this plan has no business starting or stopping, and it would pin the
migration to whatever one instance happened to be named. Step 6 drops the constraint by lookup
instead, which is correct on every instance and needs nothing running.

For reference while writing Step 6, the definition is at:

```bash
sed -n '1866,1872p' backend/src/main/resources/db/migration/V1__initial_schema.sql
```

Expected: `source_type TEXT NOT NULL CHECK (source_type IN ('TEST_CASE','SUITE','DEFECT','DOC_PAGE'))`
— an inline column constraint with no `CONSTRAINT <name>` clause, which is the whole reason Step 6
looks the name up.

- [ ] **Step 3: Write the migration — part 1, the counter**

Create `backend/src/main/resources/db/migration/V2__pm_foundation.sql` beginning:

```sql
-- =============================================================================
-- V2 — the project-management expansion's foundation (plan 010, module M0 of 009)
--
-- Four schema changes and one catalogue. Nothing here is user-visible: no
-- endpoint, no screen and no new module. What it buys is that the eight modules
-- after it each add their own tables against a permission catalogue and a key
-- counter that already exist, instead of eight edits to three documents a test
-- pins byte-for-byte.
--
-- V1 ONLY EVER RUNS ON AN EMPTY DATABASE (its own header says so). This file does
-- not: it runs against a live instance, so every statement below is written to be
-- safe on one — the backfill is ON CONFLICT DO NOTHING, the CHECK is dropped and
-- recreated rather than altered, and the new column is nullable with no default.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. PER-PROJECT ISSUE KEYS — a NEW table, not a primary-key change
-- -----------------------------------------------------------------------------
--
-- D5 of the expansion scope says issue keys are per project — ACME-1, not TC-1 —
-- and the scope document proposed widening key_sequence's primary key to
-- (company_id, entity_type, project_id) with project_id nullable for the five
-- existing company-scoped types. THAT STATEMENT CANNOT RUN: a primary key cannot
-- contain a nullable column. Plan 009 review R1 reversed it and this is the
-- replacement.
--
-- key_sequence is NOT TOUCHED. Its five entity types, its allocator
-- (tenancy/DisplayKeys.java), its seed function and its seven readers
-- (CompanyProvisioningService, ExportCompanyService, SeedDemoDataCommand,
-- ProjectEdit, RunEdit, PlanCreateService) keep the company-scoped numbering that
-- §6t argued for, and which nothing about issues makes wrong.
--
-- NO PREFIX COLUMN, unlike key_sequence. The prefix IS project.key, which already
-- exists with uq_project_key and ck_project_key_format '^[A-Z0-9]{2,10}$' — so a
-- prefix column here would be a second copy of a string that is immutable by
-- contract, and the two copies would be edited months apart. That is the same
-- reasoning DisplayKeys' header gives for taking the prefix FROM THE ROW rather
-- than from a Java constant; here the row is one table over.
--
-- The allocation statement is key_sequence's, one row per project instead of one
-- per company — which also relieves the contention §6t accepted: issue creation
-- in one project no longer serialises against every other project in the company.
--
--     UPDATE project_key_sequence s SET next_value = s.next_value + :n
--       FROM project p
--      WHERE s.company_id = :c AND s.project_id = :p AND p.id = s.project_id
--     RETURNING p.key, s.next_value - :n;
--
-- NOT MAX(key_seq)+1, which races under concurrent creation and surfaces as a
-- unique-constraint violation under load and never in a test.
CREATE TABLE project_key_sequence (
    company_id UUID   NOT NULL REFERENCES company(id),
    project_id UUID   NOT NULL REFERENCES project(id),
    next_value BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (company_id, project_id)
);
COMMENT ON TABLE project_key_sequence IS
    'The per-project issue-key counter (expansion scope D5). One row per project, created by '
    'tcms_seed_project_defaults so every creation path gets it — POST /projects, provision-company '
    'and seed-demo-data all call that function. Allocate with the UPDATE ... RETURNING in this '
    'migration''s header, joined to project for the prefix; NOT MAX(n)+1, which races. Gap-free '
    'numbering is NOT guaranteed and is not required: the contract is uniqueness and immutability. '
    'Distinct from key_sequence, which stays company-scoped for the five test-asset types and is '
    'not touched by this migration — see the header for why the proposed primary-key change to '
    'that table could never have run.';
COMMENT ON COLUMN project_key_sequence.next_value IS
    'The next number to issue. Starts at 1 so the first allocation is ACME-1 and not ACME-0 — '
    'issue keys are user-facing and 1-based, and there is no zero-padding anywhere.';
```

- [ ] **Step 4: Write the migration — part 2, the backfill, and the RLS trap it walks around**

Append:

```sql
-- The backfill, and the reason it is a loop rather than one INSERT ... SELECT.
--
-- Flyway connects as tcms_owner, which is NOT a superuser and holds NO BYPASSRLS,
-- and `project` has carried FORCE ROW LEVEL SECURITY since V1 — FORCE meaning the
-- policy applies to the table's owner too. With app.current_company unset, a plain
--
--     INSERT INTO project_key_sequence (company_id, project_id)
--     SELECT company_id, id FROM project;
--
-- reads ZERO ROWS and inserts nothing, silently, and this migration reports
-- success. That is the first of the two silent RLS traps in
-- docs/specs/tenancy-invariants.md, met head-on. `company` is on the ERD §0.1
-- exception list and carries no policy, so it can be walked; setting the GUC per
-- company then makes that company's projects visible through the ordinary policy.
--
-- The alternative — ALTER TABLE project NO FORCE ROW LEVEL SECURITY around the
-- backfill — was rejected: it relaxes the isolation layer inside a transaction
-- that also seeds a permission catalogue, and a migration that can turn the policy
-- off is a migration that can be edited into leaving it off.
--
-- set_config's third argument is `true` — transaction-local. Flyway runs a
-- migration in one transaction, so the GUC is gone when this file ends.
DO $$
DECLARE
    c UUID;
BEGIN
    FOR c IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', c::text, true);
        INSERT INTO project_key_sequence (company_id, project_id, next_value)
        SELECT company_id, id, 1 FROM project
        ON CONFLICT (company_id, project_id) DO NOTHING;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;


-- Isolation, with the SAME predicate every other company-owned table carries.
-- SchemaConventionTest asserts all three properties — ENABLED, FORCED, and the
-- predicate byte-identical to the other 74 — so a widened policy here fails the
-- build rather than leaking a counter across clients.
--
-- Enabled AFTER the backfill above, deliberately: with the policy live, the loop
-- would also have to satisfy it on the INSERT side, and an INSERT policy that
-- refuses silently is the second of the two traps.
ALTER TABLE project_key_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_key_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY company_isolation ON project_key_sequence USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
```

- [ ] **Step 5: Write the migration — part 3, the seed function, reproduced in full**

Append the function below. **Reproduce every block `V1:3642-3714` has** — `CREATE OR REPLACE`
replaces the whole body, so anything omitted here stops being seeded, silently, for every project
created from now on. That is V18's rule, restated in V1's own comment above the function.

```sql
-- -----------------------------------------------------------------------------
-- 2. EVERY NEW PROJECT GETS ITS COUNTER
-- -----------------------------------------------------------------------------
--
-- Seeded here rather than in ProjectCreateService because this function is called
-- by BOTH creation paths — ProjectCreateService and CompanyProvisioningService
-- (twice) — and by seed-demo-data through them. One INSERT here covers every path
-- that exists and every path a later module adds; a Java-side insert covers the
-- one its author remembered.
--
-- Reproduced IN FULL rather than patched, for V18's reason, restated in V1's own
-- header above this function: CREATE OR REPLACE replaces the whole body. Only the
-- final block differs from V1's.
CREATE OR REPLACE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_spec RECORD;
BEGIN
    PERFORM tcms_assert_company_context(p_company_id);

    IF NOT EXISTS (SELECT 1 FROM project WHERE id = p_project_id) THEN
        RAISE EXCEPTION 'project % is not visible in company %', p_project_id, p_company_id
            USING ERRCODE = '23503';
    END IF;

    -- Feature is the one PROJECT-scoped dimension, and the only hierarchical and
    -- unlocked one: two client products have different feature trees, and the
    -- tree is meant to be edited (plan §6d). Seeded empty — inventing a client's
    -- feature taxonomy for them is how a controlled vocabulary gets worked around.
    INSERT INTO classification_dimension
        (id, company_id, project_id, key, name, cardinality, controlled, hierarchical, locked, position)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'feature', 'Feature',
           'MULTI', true, true, false, 3
     WHERE NOT EXISTS (
        SELECT 1 FROM classification_dimension
         WHERE company_id = p_company_id AND project_id = p_project_id AND key = 'feature');

    -- The seven default execution statuses. `kind` is what lets reporting aggregate
    -- a project's custom statuses without knowing their names, and counts_as_pass
    -- is separate from kind because a project may add a "Passed with comment".
    -- Colours match the frontend design tokens deliberately: status colour is
    -- load-bearing data in a TCMS and is NOT brandable (§6u).
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('Passed',   'PASSED',   '#2e7d5b', true,  true,  0),
            ('Failed',   'FAILED',   '#c0392b', true,  false, 1),
            ('Blocked',  'BLOCKED',  '#b7791f', true,  false, 2),
            ('Skipped',  'SKIPPED',  '#6b7280', true,  false, 3),
            ('Retest',   'RETEST',   '#7c5cbf', false, false, 4),
            ('Untested', 'UNTESTED', '#9ca3af', false, false, 5),
            ('WIP',      'WIP',      '#2563eb', false, false, 6)
        ) AS s(name, kind, color, is_final, counts_as_pass, position)
    LOOP
        INSERT INTO case_status
            (id, company_id, project_id, name, kind, color, is_final, counts_as_pass, position)
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.kind,
               v_spec.color, v_spec.is_final, v_spec.counts_as_pass, v_spec.position
         WHERE NOT EXISTS (
            SELECT 1 FROM case_status
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- Every project gets a Default configuration so test_run.configuration_id can
    -- stay NOT NULL all the way through the rollup read models — suite_run_summary
    -- and plan_snapshot key on it, and a nullable PK column is not expressible.
    INSERT INTO configuration (id, company_id, project_id, name, attributes, is_default)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'Default', '{}'::jsonb, true
     WHERE NOT EXISTS (
        SELECT 1 FROM configuration WHERE project_id = p_project_id AND is_default);

    -- V18. One documentation space, for the same structural reason as the
    -- configuration above: doc_page.space_id is NOT NULL, so without this row the
    -- project cannot hold a page at all.
    --
    -- It is NOT the "inventing the client's structure" mistake that keeps a
    -- Default *suite* from being seeded here. A suite is a decision about how
    -- somebody's tests are organised and appears in their tree; the space is a
    -- container the API does not expose, has no name anybody reads, and holds
    -- every page whatever its shape. The tree the client actually organises is
    -- doc_page.parent_id, and this seeds none of that.
    INSERT INTO doc_space (id, company_id, project_id, name)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'Documentation'
     WHERE NOT EXISTS (
        SELECT 1 FROM doc_space WHERE project_id = p_project_id);

    -- V2. The per-project issue-key counter. Structural, like the configuration
    -- and the doc space above: without this row ProjectKeys.allocate matches
    -- nothing and the first issue in the project cannot be numbered. Its absence
    -- is an EMPTY RESULT rather than an error — an UPDATE ... RETURNING that
    -- matches no row is not a failure — which is exactly why the row is created
    -- here, once, rather than repaired by an INSERT ... ON CONFLICT on the hot
    -- path of every issue creation forever.
    INSERT INTO project_key_sequence (company_id, project_id, next_value)
    VALUES (p_company_id, p_project_id, 1)
    ON CONFLICT (company_id, project_id) DO NOTHING;
END $$;
```

- [ ] **Step 6: Write the migration — part 4, `doc_link` and `project.client_id`**

Append:

```sql
-- -----------------------------------------------------------------------------
-- 3. AN ISSUE IS A LINK SOURCE
-- -----------------------------------------------------------------------------
--
-- doc_link is the link authority and its source_type CHECK is the enumeration of
-- what may assert a link (expansion scope §3 — "the authority table enumerates its
-- source kinds; the source module is not imported"). An issue verifying a
-- statement in the documentation is the epic-to-spec edge M1 needs, and adding the
-- value here is what lets `tracker` write one without `documentation` learning
-- that `tracker` exists.
--
-- Dropped and recreated rather than altered: a CHECK constraint has no ALTER form
-- that widens it. Postgres validates the new constraint against existing rows on
-- ADD, which is correct and cheap — the widening cannot fail.
--
-- DROPPED BY LOOKUP, NOT BY NAME. V1 writes the constraint inline on the column
-- with no CONSTRAINT clause, so Postgres generated its name; hard-coding a guess
-- at it here would be a migration that works on the instance somebody checked and
-- fails on the next. The new one IS named, so the next migration to touch it can
-- say so.
DO $$
DECLARE
    v_name TEXT;
BEGIN
    SELECT conname INTO v_name
      FROM pg_constraint
     WHERE conrelid = 'doc_link'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%source_type%';

    IF v_name IS NULL THEN
        RAISE EXCEPTION 'doc_link has no CHECK constraint mentioning source_type — V1''s column '
                        'definition changed, and adding ISSUE blind would widen nothing';
    END IF;

    EXECUTE format('ALTER TABLE doc_link DROP CONSTRAINT %I', v_name);
END $$;

ALTER TABLE doc_link ADD CONSTRAINT ck_doc_link_source_type
    CHECK (source_type IN ('TEST_CASE','SUITE','DEFECT','DOC_PAGE','ISSUE'));
COMMENT ON CONSTRAINT ck_doc_link_source_type ON doc_link IS
    'What may assert a link (expansion scope §3 — the authority table enumerates its source kinds '
    'and does not import the source module). Wider than com.tcms.documentation.LinkSourceType on '
    'purpose: DEFECT, DOC_PAGE and, since V2, ISSUE are admitted by the column and offered by no '
    'writer, and narrowing the column to match would have to be undone by the slice that builds '
    'one. Named here, unlike V1''s generated name, so the next migration can drop it by name.';

-- com.tcms.documentation.LinkSourceType is NOT widened, and that is the design
-- rather than an omission — see the constraint comment above and the enum's own
-- header. M1 adds the member in the slice that first writes such a row.


-- -----------------------------------------------------------------------------
-- 4. A PROJECT MAY BELONG TO A CLIENT
-- -----------------------------------------------------------------------------
--
-- D1: a Client is an ATTRIBUTE of a Project, not a level of the hierarchy. This is
-- that attribute, and it is the whole of it — one nullable column on one existing
-- table.
--
-- THE COLUMN ONLY. No REFERENCES, because the `client` table belongs to module Mc
-- and does not exist yet; Mc adds the foreign key when it does. An earlier draft of
-- plan 009 wrote `REFERENCES client(id)` in the same sentence as "the FK is added
-- by Mc" (review R3).
--
-- Nullable and unread until Mc. NOT mapped on com.tcms.project.Project either:
-- ArchitectureRulesTest sweeps @Entity fields for columns with no reader and for
-- columns with no writer, in both directions, so a mapping added now would need an
-- entry in one of those maps that Mc would then have to remove. An unmapped column
-- is invisible to both rules and satisfies hibernate.ddl-auto: validate, which
-- checks that mapped entities match the schema and not the reverse.
--
-- "None (internal)" is a real answer and not a gap (story C2): a company running
-- its own product has no client, and NULL says so without a sentinel row.
ALTER TABLE project ADD COLUMN client_id UUID;
COMMENT ON COLUMN project.client_id IS
    'The client this project is for, or NULL for internal work — D1, where a Client is an '
    'attribute of a Project rather than a level of the hierarchy. Ships in V2 with NO foreign key '
    'and NO reader: the client table belongs to module Mc, which adds the constraint and the entity '
    'mapping together. A project with no client is not incomplete; nothing renders it as missing.';
```

- [ ] **Step 7: Write the migration — part 5, the 33 permissions**

Append. The `area` values must match `frontend/src/lib/permissions.ts` exactly, which Step 9
writes — `SeedDataTest.areasMatchTheSpa` compares them.

```sql
-- -----------------------------------------------------------------------------
-- 5. THE CATALOGUE — 56 KEYS BECOME 89
-- -----------------------------------------------------------------------------
--
-- SOURCE OF TRUTH: docs/specs/2026-07-30-tcms-roles-permissions-naming.md Part A,
-- as amended by D3 and D28 of the project-management expansion scope. This is its
-- transcription; openapi.yaml's PermissionKey is the same catalogue expressed for
-- the frontend, and lib/permissions.ts groups it for humans. All four agree or
-- SeedDataTest fails.
--
-- STILL NO WILDCARD. Instance Admin's grant is derived at the end of this file by
-- joining the catalogue, exactly as V1 did — a '*' sentinel cannot distinguish
-- "holds every permission" from "holds a permission that happens to be present".
--
-- Thirty-three, not the "~35" §7 estimated. The difference is not rounding: each
-- key below separates two roles from each other, and the ones that did not were
-- dropped rather than seeded. issue:comment is the sharpest case — it is here
-- BECAUSE Viewer holds it, which is the same argument §A3 used to refuse
-- suite:comment and plan:comment, two keys that would have been granted to nobody.
INSERT INTO permission (key, area, description) VALUES
    -- tracker (PROJECT) — M1
    ('issue:read',         'tracker',   'Read epics, stories and tasks'),
    ('issue:create',       'tracker',   'Create an issue at any level'),
    ('issue:edit',         'tracker',   'Edit an issue, including its status and assignee'),
    ('issue:delete',       'tracker',   'Delete an issue'),
    ('issue:comment',      'tracker',   'Comment on an issue without any write access to it'),
    ('issue:workflow',     'tracker',   'Configure the project''s issue types, statuses and transitions'),
    ('issue:import',       'tracker',   'Import a project''s issues from an external tracker'),
    -- board & backlog (PROJECT) — M2
    ('board:read',         'board',     'See the project''s boards and backlog'),
    ('board:rank',         'board',     'Order the backlog'),
    ('board:configure',    'board',     'Create boards and define their columns, swimlanes and WIP limits'),
    -- releases (PROJECT) — M3
    ('release:read',       'releases',  'See releases and the roadmap'),
    ('release:manage',     'releases',  'Create, edit and delete releases and their scope'),
    ('release:publish',    'releases',  'Mark a release released'),
    -- time (PROJECT) — M6
    ('time:log',           'time',      'Log your own hours'),
    ('time:read',          'time',      'See other people''s time entries'),
    ('time:edit',          'time',      'Amend a time entry somebody else recorded'),
    ('time:approve',       'time',      'Approve or reject a submitted timesheet period'),
    -- staffing — M7. Two scopes, deliberately: read and allocate are asked about
    -- ONE PROJECT and are held by a PROJECT-scope role, while the directory, the
    -- profiles and the absences are company-wide views held by COMPANY-scope
    -- roles. A key asked only company-wide but granted to a PROJECT-scope role is
    -- an INERT ROW — the defect §43 repaired for company:read, still visible as
    -- three entries in SeedDataTest.MATRIX_DIVERGENCE_BY_DECISION.
    ('staffing:read',      'staffing',  'See this project''s allocations and capacity'),
    ('staffing:allocate',  'staffing',  'Allocate a person to this project for a period'),
    ('staffing:directory', 'staffing',  'See the company-wide capacity view and the skills directory'),
    ('staffing:profile',   'staffing',  'Maintain people''s skills and staffing profiles'),
    ('staffing:absence',   'staffing',  'Record absence on somebody else''s behalf'),
    -- clients (COMPANY) — Mc
    ('client:read',        'clients',   'See clients, their contacts and their projects'),
    ('client:create',      'clients',   'Record a new client organisation'),
    ('client:edit',        'clients',   'Edit a client and its contacts'),
    ('client:delete',      'clients',   'Delete a client'),
    -- money (COMPANY) — M8. THE SHARPEST NEW BOUNDARY IN THE PRODUCT: these seven
    -- keys are held by Finance, Company Admin and Instance Admin and by nobody
    -- else. A Test Engineer with time:log logs hours and cannot see what they cost
    -- (story T1), and an Account Manager has no Billing entry in their sidebar at
    -- all (story F2, story M2). That needs a test asserting the NEGATIVE, which is
    -- SeedDataTest.moneyIsFinanceAndAdminsOnly.
    ('ratecard:read',      'money',     'See rate cards and what an hour is charged at'),
    ('ratecard:manage',    'money',     'Create and edit rate cards'),
    ('billing:read',       'money',     'See budgets, invoices and margin'),
    ('billing:budget',     'money',     'Set and edit a project''s budget'),
    ('billing:expense',    'money',     'Record an expense against a project'),
    ('billing:invoice',    'money',     'Draft and edit an invoice'),
    ('billing:issue',      'money',     'Issue an invoice, locking its lines');
```

- [ ] **Step 8: Write the migration — part 6, the three roles and every grant**

Append:

```sql
-- -----------------------------------------------------------------------------
-- 6. THREE MORE BUILT-IN ROLES — nine assignable, eleven rows
-- -----------------------------------------------------------------------------
--
-- D3 amends "the six are the complete role set" rather than reversing it: still
-- seeded, still immutable, still NO CUSTOM ROLES, not even clone-and-edit. This
-- is the third time that sentence has been superseded (V15's Company Member,
-- V40's Agent, and now these three), which is why it is written as an amendment
-- in the roles spec instead of being reworded again.
--
-- role has no company_id — it is on the ERD §0.1 exception list — and uq_role_name
-- is global, so these are three plain rows.
--
-- Fixed ids, continuing V1's series, for V1's reason: role_permission references
-- them and anything naming a role can do so without a lookup subquery.
INSERT INTO role (id, scope, name, description, built_in, assignable) VALUES
    ('10000000-0000-4000-8000-000000000009', 'PROJECT', 'Project Manager',
     'Runs the delivery of one project: epics, stories and tasks, the board and the backlog, '
     'releases and the roadmap, staffing this project and approving its timesheets. Reads the test '
     'repository and comments on it but does not author or approve test cases — that is the Test '
     'Lead''s line, and the two roles are granted together on a project that needs both.',
     true, true),
    ('10000000-0000-4000-8000-000000000010', 'COMPANY', 'Account Manager',
     'Owns the client relationship: client organisations, their contacts, and who is free to staff '
     'them. Sees no money at all — no rate card, no budget, no invoice, no margin — and Billing is '
     'absent from their sidebar rather than refused inside it.',
     true, true),
    ('10000000-0000-4000-8000-000000000011', 'COMPANY', 'Finance',
     'Owns the money: rate cards, budgets, expenses, invoices and margin. Reads clients so an '
     'invoice can name one, and writes nothing about them. Holds no project grant, so a '
     'COMPANY-scope role does not become a way to read every project''s test content.',
     true, true);


-- Project Manager — PROJECT scope. The delivery half of a project, whole:
-- issue:*, board:*, release:*, time:* and this project's staffing, plus the four
-- project-configuration keys and the project audit trail.
--
-- What it deliberately does NOT hold: any *:approve, and any test-authoring or
-- execution key. D3's line is "no test-case approval", and the rest follows from
-- it — a role that could create and edit cases but not approve them would be Test
-- Engineer with a different name, and D3's own note says there is no developer
-- role for exactly that reason. case:read and case:comment are held, because a PM
-- who cannot read the coverage they are managing is managing a number.
--
-- company:read is granted for V1's reason (they are a member, and every check is a
-- real permission check) and is the INERT ROW §43 documented: a PROJECT-scope
-- grant of a COMPANY-scope key is unreachable from evaluateCompanyWide, and what
-- actually carries the permission company-wide is V15's Company Member baseline.
-- SeedDataTest.MATRIX_DIVERGENCE_BY_DECISION pins it as deliberate, alongside the
-- identical rows for Test Lead, Test Engineer and Viewer.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000009', key
  FROM (VALUES
    ('case:read'),
    ('case:comment'),
    ('defect:create'),('workitem:manage'),
    ('report:view'),('report:export'),
    ('doc:read'),('doc:edit'),('doc:publish'),
    ('artifact:read'),
    ('project:read'),('project:audit'),('project:settings'),('project:members'),
    ('integration:manage'),
    ('company:read'),
    ('issue:read'),('issue:create'),('issue:edit'),('issue:delete'),('issue:comment'),
    ('issue:workflow'),('issue:import'),
    ('board:read'),('board:rank'),('board:configure'),
    ('release:read'),('release:manage'),('release:publish'),
    ('time:log'),('time:read'),('time:edit'),('time:approve'),
    ('staffing:read'),('staffing:allocate')
  ) AS granted(key);

-- Account Manager — COMPANY scope, and the narrowest company role in the product.
-- Clients and their contacts, the skills directory and absences, company:read.
--
-- NO project:read and NO report:view, and both omissions are load-bearing: this
-- role is COMPANY-scope, so either key would confer it over EVERY project in the
-- company, present and future, which is the boundary §A1 draws and the reason
-- Company Member exists at all. The client page lists its projects from the client
-- aggregate under client:read, which is Mc's job to build.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000010', key
  FROM (VALUES
    ('company:read'),
    ('client:read'),('client:create'),('client:edit'),('client:delete'),
    ('staffing:directory'),('staffing:profile'),('staffing:absence')
  ) AS granted(key);

-- Finance — COMPANY scope. Reads clients, writes money.
--
-- Holds client:read and NOT client:edit: an invoice has to name a client, and a
-- Finance user editing the client record is the Account Manager's job under a
-- different login. staffing:directory because utilisation is a money question as
-- much as a staffing one. Same two omissions as Account Manager, same reason.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000011', key
  FROM (VALUES
    ('company:read'),
    ('client:read'),
    ('staffing:directory'),
    ('ratecard:read'),('ratecard:manage'),
    ('billing:read'),('billing:budget'),('billing:expense'),('billing:invoice'),
    ('billing:issue')
  ) AS granted(key);


-- Company Admin — holds everything below SYSTEM scope, which is what makes it
-- Company Admin. The client boundary is what it must not cross, so it gains all 33
-- new keys and no SYSTEM key.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000002', key FROM permission
 WHERE key IN (
    'issue:read','issue:create','issue:edit','issue:delete','issue:comment',
    'issue:workflow','issue:import',
    'board:read','board:rank','board:configure',
    'release:read','release:manage','release:publish',
    'time:log','time:read','time:edit','time:approve',
    'staffing:read','staffing:allocate','staffing:directory','staffing:profile',
    'staffing:absence',
    'client:read','client:create','client:edit','client:delete',
    'ratecard:read','ratecard:manage',
    'billing:read','billing:budget','billing:expense','billing:invoice','billing:issue');

-- Test Lead — "unchanged + issue:*" (D3), taken literally, which is a decision the
-- owner took on 2026-08-30 and not an oversight of this migration.
--
-- THE CONSEQUENCE, STATED HERE BECAUSE IT MOVES A NUMBER THIS FILE USED TO ASSERT:
-- Test Lead minus Test Engineer WAS exactly seven keys — "giving approvals, and
-- adding and managing permission on the project" — and is now TEN. The three that
-- joined are issue:delete, issue:workflow and issue:import, and they belong to the
-- second half of that sentence rather than a third category: deleting an issue and
-- configuring the project's workflow are both "managing the project". §A4, this
-- file's own grant comment above Company Admin, roleMatrix.ts's LEAD_ONLY and
-- SeedDataTest.testEngineerToTestLeadBoundary all carried the seven and all move
-- together with this INSERT.
--
-- THE SECOND CONSEQUENCE, which no test can catch: a project staffed with a Test
-- Lead and no Project Manager cannot configure a board, cut a release or approve a
-- timesheet — only Company Admin can. That is what D3 says. It is flagged for M2,
-- M3 and M6 in plan 009's prompts rather than repaired here by widening a grant
-- nobody decided to widen.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000003', key
  FROM (VALUES
    ('issue:read'),('issue:create'),('issue:edit'),('issue:delete'),('issue:comment'),
    ('issue:workflow'),('issue:import'),
    ('board:read'),
    ('release:read'),
    ('time:log')
  ) AS granted(key);

-- Test Engineer — issue:create and issue:edit per D28 (D3 gave edit only; a
-- developer who cannot create an issue cannot file a bug or break a story down,
-- and D3 says a developer IS a Test Engineer), plus time:log, plus the reads and
-- the comment key the tier below it holds.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000004', key
  FROM (VALUES
    ('issue:read'),('issue:create'),('issue:edit'),('issue:comment'),
    ('board:read'),
    ('release:read'),
    ('time:log')
  ) AS granted(key);

-- Automation Service — issue:read and board:read, and nothing else (D3). Still the
-- smallest grant in the product, because a leaked CI token is the realistic breach.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000005', key
  FROM (VALUES
    ('issue:read'),('board:read')
  ) AS granted(key);

-- Viewer — issue:read and board:read per D3, plus release:read and issue:comment.
--
-- issue:comment is an EXTENSION of D3's list and the reason is §A3's own: the
-- comment tier exists because a Viewer holds it — "built for the case where the
-- client reviews test coverage themselves without gaining any write access" — and
-- a comment key that no role holds without also holding issue:edit would buy
-- nothing, which is the argument §A3 used to refuse suite:comment and
-- plan:comment. Taken as a decision on 2026-08-30, recorded in plan 010 D-M0-6.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000006', key
  FROM (VALUES
    ('issue:read'),('issue:comment'),('board:read'),('release:read')
  ) AS granted(key);

-- Agent — §A6a: Test Engineer entire, minus company:read. Derived here the same
-- way SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse derives the delta,
-- rather than restated, so the two cannot disagree.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000008', rp.permission_key
  FROM role_permission rp
 WHERE rp.role_id = '10000000-0000-4000-8000-000000000004'
   AND rp.permission_key <> 'company:read'
   AND NOT EXISTS (
       SELECT 1 FROM role_permission held
        WHERE held.role_id = '10000000-0000-4000-8000-000000000008'
          AND held.permission_key = rp.permission_key);

-- Instance Admin — DERIVED from the catalogue, never enumerated, exactly as V1
-- does it and for the same reason: if a key is added and Instance Admin is
-- forgotten, SeedDataTest fails on the derived count rather than a screen quietly
-- claiming full coverage. Last, so it picks up all 33 keys above.
INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000001', p.key
  FROM permission p
 WHERE NOT EXISTS (
       SELECT 1 FROM role_permission held
        WHERE held.role_id = '10000000-0000-4000-8000-000000000001'
          AND held.permission_key = p.key);
```

- [ ] **Step 9: Widen `PermissionKey` in the contract, and regenerate both clients**

In `docs/specs/openapi.yaml`, replace the `PermissionKey` description's *"**56 since `V71`…"*
paragraph with:

```
        **89 since `V2` of the project-management expansion (2026-08-30, plan 010).**
        `V3` seeded 55, `V71` added `doc:review`, and `V2` adds 33 across eight new
        namespaces — `issue:*`, `board:*`, `release:*`, `time:*`, `staffing:*`,
        `client:*`, `ratecard:*` and `billing:*`. They are seeded before the
        features that read them, once, rather than a namespace per module: the
        catalogue is transcribed into four documents that a test pins byte-for-byte,
        and eight rounds of that is eight chances for one of them to drift.
        `SeedDataTest.catalogueMatchesTheContract` compares this list to the seeded
        rows in both directions; nothing else does.
```

Then append the 33 members to the enum, grouped and commented by namespace, in the order Step 7's
`INSERT` uses. Then:

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

- [ ] **Step 10: Place all 33 in `frontend/src/lib/permissions.ts`**

Add the seven areas to the `Area` union, after `'work'` and before `'reporting'` for the five
project areas, and after `'project'` for the two company ones:

```ts
type Area =
  | 'repository'
  | 'review'
  | 'execution'
  | 'work'
  | 'tracker'
  | 'board'
  | 'releases'
  | 'time'
  | 'staffing'
  | 'reporting'
  | 'documentation'
  | 'artifacts'
  | 'project'
  | 'clients'
  | 'money'
  | 'company'
  | 'system';
```

In `permissionAreas`, after the `'workitem:manage': 'work',` block:

```ts
  'issue:read': 'tracker',
  'issue:create': 'tracker',
  'issue:edit': 'tracker',
  'issue:delete': 'tracker',
  'issue:comment': 'tracker',
  'issue:workflow': 'tracker',
  'issue:import': 'tracker',

  'board:read': 'board',
  'board:rank': 'board',
  'board:configure': 'board',

  'release:read': 'releases',
  'release:manage': 'releases',
  'release:publish': 'releases',

  'time:log': 'time',
  'time:read': 'time',
  'time:edit': 'time',
  'time:approve': 'time',

  'staffing:read': 'staffing',
  'staffing:allocate': 'staffing',
  'staffing:directory': 'staffing',
  'staffing:profile': 'staffing',
  'staffing:absence': 'staffing',
```

and after the `'integration:manage': 'project',` block:

```ts
  'client:read': 'clients',
  'client:create': 'clients',
  'client:edit': 'clients',
  'client:delete': 'clients',

  'ratecard:read': 'money',
  'ratecard:manage': 'money',
  'billing:read': 'money',
  'billing:budget': 'money',
  'billing:expense': 'money',
  'billing:invoice': 'money',
  'billing:issue': 'money',
```

In `permissionLabels`, the same 33 keys in the same positions, each as the **verb phrase that
completes** *"You don't have permission to ___ in this project."* — phrased from the user's side of
the screen, never from the model's:

```ts
  'issue:read': 'view issues',
  'issue:create': 'create issues',
  'issue:edit': 'edit issues',
  'issue:delete': 'delete issues',
  'issue:comment': 'comment on issues',
  'issue:workflow': 'change the issue workflow',
  'issue:import': 'import issues from another tracker',

  'board:read': 'view the board and backlog',
  'board:rank': 'order the backlog',
  'board:configure': 'configure boards',

  'release:read': 'view releases and the roadmap',
  'release:manage': 'create and edit releases',
  'release:publish': 'mark a release released',

  'time:log': 'log your hours',
  'time:read': "view other people's time",
  'time:edit': "amend somebody else's time entry",
  'time:approve': 'approve timesheets',

  'staffing:read': "view this project's staffing",
  'staffing:allocate': 'allocate people to this project',
  'staffing:directory': 'view the company staffing directory',
  'staffing:profile': 'maintain staffing profiles and skills',
  'staffing:absence': "record somebody else's absence",
```

```ts
  'client:read': 'view clients',
  'client:create': 'create clients',
  'client:edit': 'edit clients and their contacts',
  'client:delete': 'delete clients',

  'ratecard:read': 'view rate cards',
  'ratecard:manage': 'create and edit rate cards',
  'billing:read': 'view budgets and invoices',
  'billing:budget': 'set project budgets',
  'billing:expense': 'record expenses',
  'billing:invoice': 'draft invoices',
  'billing:issue': 'issue an invoice',
```

And the seven `areaLabels` members:

```ts
  tracker: 'Issues',
  board: 'Board & backlog',
  releases: 'Releases',
  time: 'Time',
  staffing: 'Staffing',
  clients: 'Clients',
  money: 'Rates & billing',
```

- [ ] **Step 11: Verify the SPA half compiles before touching any document**

```bash
cd frontend && npm run build && npm run lint
```

Expected: green. If `permissions.ts` reports a missing key, the contract and this file disagree —
which is the whole point of the `satisfies` clause, and the fix is whichever of the two is short.

- [ ] **Step 12: Amend roles spec §A2 — the role tables**

In `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, add three rows to the §A2 role table
(after `Company Admin`, in authority order) and supersede the count sentence:

```markdown
| **Project Manager** | PROJECT | yes | Runs the delivery of one project — epics, stories and tasks, the board and the backlog, releases and the roadmap, staffing it and approving its timesheets. Reads and comments on the test repository; **authors and approves nothing in it**. Added 2026-08-30 by `V2` (expansion D3). |
| **Account Manager** | COMPANY | yes | Owns the client relationship: clients, their contacts, and who is free to staff them. **Sees no money at all** — Billing is absent from their sidebar rather than refused inside it. Added 2026-08-30 by `V2`. |
| **Finance** | COMPANY | yes | Owns the money: rate cards, budgets, expenses, invoices, margin. Reads clients so an invoice can name one; writes nothing about them. **Holds no project grant**, so a COMPANY-scope role does not become a way to read every project's test content. Added 2026-08-30 by `V2`. |
```

And, immediately under the table, extend the superseded chain rather than rewording it:

```markdown
~~**superseded 2026-08-08 by `V41`: these six are the complete *role* set, and five of them are
assignable.**~~ → **superseded again 2026-08-30 by `V2` (expansion D3): there are nine, and eight of
them are assignable.** Project Manager, Account Manager and Finance are seeded, immutable and
grantable; `Automation Service` remains the one of the nine that is not. Still **no custom roles at
all**, not even clone-and-edit — the boundary this product draws is where the built-in tiers sit,
and D3's own note records the role that was considered and refused: there is no Developer role,
because the difference between a developer and a Test Engineer is which screens they open and not
what they may do. The count in this sentence has now moved three times (`V15`, `V40`, `V2`) and the
invariant under it has not: **no role outside the catalogue exists.**
```

- [ ] **Step 13: Amend roles spec §A3 — the column headings**

Change the legend line to:

```markdown
**IA** Instance Admin · **CA** Company Admin · **PM** Project Manager · **TL** Test Lead ·
**TE** Test Engineer · **AS** Automation Service · **V** Viewer — and, in the `COMPANY` tables,
**AM** Account Manager · **FIN** Finance
```

Then add a `PM` column to **every** `PROJECT` table — Repository, Review, Execution, Defects & work
items, Reporting, Documentation, Artifacts, Project — between `CA` and `TL`, so each header reads:

```markdown
| Permission | IA | CA | PM | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
```

PM's cell for each existing key, from Step 8's `INSERT`:

| Table | `✓` for PM | `—` for PM |
|---|---|---|
| Repository | `case:read` | `suite:manage`, `suite:delete`, `case:create`, `case:edit`, `case:delete`, `library:manage` |
| Review | `case:comment` | `case:review`·`suite:review`·`plan:review`·`doc:review`, `case:approve`·`suite:approve`·`plan:approve` |
| Execution | *(none)* | all ten |
| Defects & work items | `defect:create`, `workitem:manage` | — |
| Reporting | `report:view`, `report:export` | — |
| Documentation | `doc:read`, `doc:edit`, `doc:publish` | — |
| Artifacts | `artifact:read` | `artifact:edit`, `artifact:delete` |
| Project | all five | — |

The `COMPANY` table's header becomes:

```markdown
| Permission | IA | CA | AM | FIN | Every member | Covers |
|---|:--:|:--:|:--:|:--:|:--:|---|
```

with `company:read` `✓` for AM and FIN and every other row `—` for both. The `SYSTEM` table is
unchanged.

**Do not add a `PM` column to the `COMPANY` table.** PM holds `company:read` in the seed and that
row is inert — the same fact §43 recorded for Test Lead, Test Engineer and Viewer, which the
paragraph under that table already explains and which Step 16 pins as
`MATRIX_DIVERGENCE_BY_DECISION`.

- [ ] **Step 14: Add the five new §A3 tables**

After `#### Defects & work items`, insert:

````markdown
#### Tracker

| Permission | IA | CA | PM | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `issue:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `issue:create` | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| `issue:edit` | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| `issue:delete` | ✓ | ✓ | ✓ | ✓ | — | — | — |
| `issue:comment` | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `issue:workflow` *(types, statuses, transitions)* | ✓ | ✓ | ✓ | ✓ | — | — | — |
| `issue:import` *(from an external tracker)* | ✓ | ✓ | ✓ | ✓ | — | — | — |

**Test Engineer holds `issue:create`, not only `issue:edit` — D28.** D3 gave it edit alone, and D3
also says a developer *is* a Test Engineer while D10 says the assignee breaks a Story into Tasks. A
developer who cannot create an issue cannot file a bug or do that breakdown.

**`issue:comment` is the `case:comment` row, one table over**, and it is here for that row's reason:
a Viewer — often the client — may say something about the work without gaining any write access to
it. A comment key held by nobody who lacks `issue:edit` would buy nothing, which is the argument
this section already used to refuse `suite:comment` and `plan:comment`.

**Test Lead holds `issue:*` and no other new key** (D3, taken literally on 2026-08-30). See §A4: the
Lead-minus-Engineer delta is ten keys now, not seven, and `issue:delete`, `issue:workflow` and
`issue:import` are the three that joined.

#### Board & backlog

| Permission | IA | CA | PM | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `board:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `board:rank` *(order the backlog)* | ✓ | ✓ | ✓ | — | — | — | — |
| `board:configure` *(columns, swimlanes, WIP limits)* | ✓ | ✓ | ✓ | — | — | — | — |

**There is no `board:move`.** Dragging a card between columns changes the issue's status and costs
`issue:edit`, which is the key that already governs that field. A separate key would be granted to
exactly the roles holding `issue:edit` and would make the board and the issue screen disagree about
who may do the same thing. **Ordering the backlog is different** and does earn its own key:
prioritisation is a decision about what the team does next, and story B4 asks for it as a Project
Manager.

#### Releases

| Permission | IA | CA | PM | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `release:read` *(and the roadmap)* | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `release:manage` | ✓ | ✓ | ✓ | — | — | — | — |
| `release:publish` | ✓ | ✓ | ✓ | — | — | — | — |

`release:publish` is separate from `release:manage` for the reason `doc:publish` is separate from
`doc:edit`: publishing moves a state, and it is its own gate and its own audit row.

#### Time

| Permission | IA | CA | PM | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `time:log` *(your own hours)* | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| `time:read` *(other people's)* | ✓ | ✓ | ✓ | — | — | — | — |
| `time:edit` *(amend somebody else's entry)* | ✓ | ✓ | ✓ | — | — | — | — |
| `time:approve` | ✓ | ✓ | ✓ | — | — | — | — |

**Logging time and knowing what it costs are different questions and different keys.** A Test
Engineer holds `time:log` and no `ratecard:read`, so they record their day and never see its price
(story T1). That negative is asserted by a test rather than read off this table.

#### Staffing

| Permission | IA | CA | PM | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `staffing:read` *(this project's allocations)* | ✓ | ✓ | ✓ | — | — | — | — |
| `staffing:allocate` | ✓ | ✓ | ✓ | — | — | — | — |

The company-wide half of staffing — the directory, profiles and absences — is under
**Staffing — `COMPANY` scope** below. The split is not cosmetic: a key that is only ever *asked*
company-wide, granted to a `PROJECT`-scope role, is an unreachable row, which is the defect §43
repaired for `company:read`.
````

After the `#### Company — COMPANY scope` table, insert three more `COMPANY` tables — **Clients**,
**Staffing**, **Money** — each with the header
`| Permission | IA | CA | AM | FIN | Every member | Covers |` and the cells from the
`COMPANY`-scope table in this plan's catalogue section, with a `Covers` sentence per row. Add under
Money:

```markdown
**The sharpest boundary in the catalogue.** These seven keys are held by Finance, Company Admin and
Instance Admin and by **nobody else** — not by an Account Manager, who owns the client relationship
and never sees its price, and not by a Project Manager, whose budget view is a percentage on the
project overview rather than a number in a currency. That needs a test asserting the negative, not a
careful reading of this table, and `SeedDataTest.moneyIsFinanceAndAdminsOnly` is it.
```

- [ ] **Step 15: Supersede §A4's "exactly seven"**

In §A4, strike the seven-key statement and write the ten, in the repository's superseded style:

```markdown
~~Test Lead minus Test Engineer is exactly seven keys.~~ → **superseded 2026-08-30 (`V2`, plan 010):
it is ten.** D3 of the project-management expansion gives Test Lead `issue:*` and Test Engineer
`issue:create`/`issue:edit`/`issue:comment`, so `issue:delete`, `issue:workflow` and `issue:import`
join the delta. **The line itself did not move** — it is still approvals and project configuration:

    case:approve · suite:approve · plan:approve            giving approvals
    project:members · project:settings · project:audit     managing the project
    integration:manage                                     and its integrations
    issue:delete · issue:workflow · issue:import           and its issues

Deleting an issue and configuring the project's workflow are the second clause, not a third
category. The seven were not wrong; there are three more of the same kind.
```

- [ ] **Step 16: Move the four test files that carry catalogue constants**

`backend/src/test/java/com/tcms/schema/SeedDataTest.java`:

```java
    private static final String PROJECT_MANAGER = "Project Manager";
    private static final String ACCOUNT_MANAGER = "Account Manager";
    private static final String FINANCE = "Finance";

    private static final Map<String, String> SPEC_COLUMNS = Map.ofEntries(
            Map.entry("IA", INSTANCE_ADMIN),
            Map.entry("CA", COMPANY_ADMIN),
            Map.entry("PM", PROJECT_MANAGER),
            Map.entry("TL", TEST_LEAD),
            Map.entry("TE", TEST_ENGINEER),
            Map.entry("AS", AUTOMATION_SERVICE),
            Map.entry("V", VIEWER),
            Map.entry("AM", ACCOUNT_MANAGER),
            Map.entry("FIN", FINANCE),
            Map.entry("Every member", COMPANY_MEMBER));

    private static final Map<String, String> MATRIX_DIVERGENCE_BY_DECISION = Map.of(
            TEST_LEAD + " / company:read", COMPANY_READ_IS_SEEDED_PER_ROLE,
            TEST_ENGINEER + " / company:read", COMPANY_READ_IS_SEEDED_PER_ROLE,
            VIEWER + " / company:read", COMPANY_READ_IS_SEEDED_PER_ROLE,
            PROJECT_MANAGER + " / company:read", COMPANY_READ_IS_SEEDED_PER_ROLE);
```

Change every `56` catalogue assertion to `89` (`:180`, `:1202-1205`), and rewrite
`testEngineerToTestLeadBoundary`'s expected set to the ten of Step 15. Add one test:

```java
    @Test
    @DisplayName("money is Finance and the two admins, and nobody else — the negative, asserted")
    void moneyIsFinanceAndAdminsOnly() throws SQLException {
        // Read off the catalogue's own namespaces rather than a literal list, so a tenth money key
        // added later is covered by this rule on the day it is seeded rather than on the day
        // somebody remembers it. §7 of the expansion scope asks for exactly this: "That needs a
        // test asserting the negative, not a careful reading of the matrix."
        List<String> leaks = queryStrings("""
                SELECT r.name || ' / ' || rp.permission_key
                  FROM role_permission rp
                  JOIN role r ON r.id = rp.role_id
                 WHERE (rp.permission_key LIKE 'billing:%' OR rp.permission_key LIKE 'ratecard:%')
                   AND r.name NOT IN ('Instance Admin', 'Company Admin', 'Finance')
                 ORDER BY 1
                """);

        assertThat(leaks)
                .as("a role outside Finance and the two admin tiers holds a money key. An Account "
                        + "Manager who can see what an hour bills at, or a Test Engineer who can "
                        + "see what theirs costs, is the boundary expansion scope §7 names as the "
                        + "sharpest one in the catalogue")
                .isEmpty();
    }
```

`packages/client/src/test/roleMatrix.ts`:

```ts
const COLUMNS = ['IA', 'CA', 'PM', 'TL', 'TE', 'AS', 'V'] as const;

export const SPEC_ROLES = [...COLUMNS, 'AM', 'FIN', 'Every member'] as const;

export const CATALOGUE_SIZE = 89;

const LEAD_ONLY = [
  'case:approve', 'suite:approve', 'plan:approve',
  'project:audit', 'project:settings', 'project:members', 'integration:manage',
  'issue:delete', 'issue:workflow', 'issue:import',
];
```

Raise the vacuity floors in `roleMatrix()` — `held.TE.size < 30 || held.TL.size < 38 ||
held.V.size < 8` — and add `PM` to the sentence that lists what a `PROJECT` table must carry.
`KEY` (`/`([a-z]+:[a-z]+)`/g`) already matches every new key and needs no change.

`packages/client/src/test/matrixCopies.test.ts`:

```ts
const COPY_COLUMNS: Record<string, SpecRole> = {
  'Instance Admin': 'IA',
  'Company Admin': 'CA',
  'Project Manager': 'PM',
  'Test Lead': 'TL',
  'Test Engineer': 'TE',
  'Automation Service': 'AS',
  Viewer: 'V',
  'Account Manager': 'AM',
  Finance: 'FIN',
  'Every member': 'Every member',
};
```

`packages/client/src/test/tierClaims.test.ts` — the namespace list, or every claim written about a
new key is silently unchecked:

```ts
const KEY = new RegExp(
  '\\b(?:suite|case|library|plan|run|result|evidence|doc|artifact|project|defect|workitem'
  + '|report|integration|company|token|audit|user|role|system|instance'
  + '|issue|board|release|time|staffing|client|ratecard|billing):[a-z]+\\b',
  'g',
);
```

and three spellings in `NAMED`, longest first so `Project Manager` wins over `manager`:

```ts
  { role: 'PM', spellings: /Project Managers?\b|\bPMs?\b/ },
  { role: 'AM', spellings: /Account Managers?\b/ },
  { role: 'FIN', spellings: /Finance\b/ },
```

- [ ] **Step 17: Rewrite both matrix copies**

`docs/specs/permission-matrix-summary.md` and `README.md` print the same matrix with role names
spelled out. Both need the `Project Manager` column in the `PROJECT` table and the `Account
Manager` / `Finance` columns in the `COMPANY` one, and both need the five new `PROJECT` tables and
three new `COMPANY` tables. `matrixCopies.test.ts` compares **every cell of every key** in both
directions, so a missing row fails with the key named.

The mechanical way to get this right, rather than by transcription:

```bash
cd packages && npm run test:run --workspace @tcms/client -- matrixCopies
```

Expected on the first run: a list of `key / role: §A3 says X and <copy> says (no column)` lines.
Work the list to empty. `(no column)` on one side means the key sits under a different scope in the
two documents, which is a bigger disagreement than a cell.

- [ ] **Step 18: Run the packages gate**

```bash
cd packages && npm run build && npm run lint --workspaces && npm run test:run --workspaces
```

Expected: green, including `matrixCopies.test.ts`, `tierClaims.test.ts`, `contractPairing.test.ts`
and `citedFiles.test.ts`.

- [ ] **Step 19: Run the backend gate**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
```

Expected: green — in particular `SeedDataTest` (all of it), `SchemaConventionTest`,
`ArchitectureRulesTest`, `ExportTablesTest`, `ContractEnumTest` and every test that boots the real
context (`hibernate.ddl-auto: validate` catches entity drift at startup, which is how an accidental
`client_id` mapping would surface).

Read `docs/specs/shanks-runner-details.md` first: `BUILD FAILED` with `Could not write XML test
results` and **zero** assertion failures is an fd-limit problem, not a red suite — rerun with
`ulimit -n 65535; ./gradlew build`.

- [ ] **Step 20: Commit**

```bash
cd ~/RiderProjects/tcms
git add backend/src/main/resources/db/migration/V2__pm_foundation.sql \
        backend/src/test/java/com/tcms/schema/SeedDataTest.java \
        docs/specs/openapi.yaml \
        docs/specs/2026-07-30-tcms-roles-permissions-naming.md \
        docs/specs/permission-matrix-summary.md \
        README.md \
        frontend/src/api/schema.d.ts frontend/src/lib/permissions.ts \
        packages/client/src/schema.ts \
        packages/client/src/test/roleMatrix.ts \
        packages/client/src/test/matrixCopies.test.ts \
        packages/client/src/test/tierClaims.test.ts
git commit -m "feat(identity): the catalogue reaches 89 keys and nine grantable roles"
```

---

### T3 — `ProjectKeys`, the per-project allocator

**Deliverable:** `com.tcms.project.ProjectKeys.allocate(projectId, n)` returns an `Allocation` whose
`keyAt(0)` is `ACME-1` for a project keyed `ACME` with a fresh counter, reserves `n` consecutive
numbers in one statement, and throws a named `IllegalStateException` when the counter row is
missing.
**Depends on:** T2 (`project_key_sequence` exists and is seeded)

`tenancy.DisplayKeys` shaped, deliberately: the same statement, the same range API, the same
exception for the empty result. It lives in `project` rather than `tenancy` because the prefix is
`project.key` and the counter is per project — and `project` is one of the five foundation modules,
so `tracker` importing it in M1 is legal where importing a `tracker`-owned allocator from `time` or
`billing` would not be.

**Files:**
- Create: `backend/src/main/java/com/tcms/project/ProjectKeys.java`
- Create: `backend/src/test/java/com/tcms/project/ProjectKeysTest.java`
- Create: `backend/src/test/java/com/tcms/tenancy/ProjectKeySequenceSchemaTest.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/tcms/project/ProjectKeysTest.java`:

```java
package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.CompanyContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The per-project issue-key allocator, against a real Postgres with RLS in force.
 *
 * <p>There is no unit-test lane for this: the whole behaviour is one {@code UPDATE … RETURNING}
 * whose correctness is the row lock it takes and the policy it is evaluated under, and a mocked
 * {@code EntityManager} would assert the string rather than the effect.
 */
@DisplayName("ProjectKeys — per-project issue numbering (expansion D5)")
class ProjectKeysTest extends IntegrationTestBase {

    @Autowired
    private ProjectKeys projectKeys;

    @Test
    @DisplayName("the first allocation is PREFIX-1, and the prefix comes from project.key")
    void firstAllocationIsOne() {
        UUID company = givenCompany();
        UUID project = givenProject(company, "ACME");

        try (var scope = CompanyContext.open(company)) {
            ProjectKeys.Allocation allocation = projectKeys.allocate(project, 1);

            assertThat(allocation.prefix()).isEqualTo("ACME");
            assertThat(allocation.firstValue()).isEqualTo(1L);
            assertThat(allocation.keyAt(0)).isEqualTo("ACME-1");
        }
    }

    @Test
    @DisplayName("a range is reserved in one statement, and the next caller starts after it")
    void rangeIsReservedWhole() {
        UUID company = givenCompany();
        UUID project = givenProject(company, "OLB");

        try (var scope = CompanyContext.open(company)) {
            ProjectKeys.Allocation bulk = projectKeys.allocate(project, 500);
            ProjectKeys.Allocation next = projectKeys.allocate(project, 1);

            assertThat(bulk.keyAt(0)).isEqualTo("OLB-1");
            assertThat(bulk.keyAt(499)).isEqualTo("OLB-500");
            assertThat(next.keyAt(0)).isEqualTo("OLB-501");
        }
    }

    @Test
    @DisplayName("two projects in one company number independently")
    void projectsDoNotShareACounter() {
        UUID company = givenCompany();
        UUID first = givenProject(company, "AAA");
        UUID second = givenProject(company, "BBB");

        try (var scope = CompanyContext.open(company)) {
            assertThat(projectKeys.allocate(first, 3).keyAt(2)).isEqualTo("AAA-3");
            assertThat(projectKeys.allocate(second, 1).keyAt(0)).isEqualTo("BBB-1");
        }
    }

    @Test
    @DisplayName("a missing counter row throws, and names the repair")
    void missingCounterThrows() {
        UUID company = givenCompany();
        UUID project = givenProject(company, "GAP");
        deleteCounterRow(company, project);

        try (var scope = CompanyContext.open(company)) {
            assertThatThrownBy(() -> projectKeys.allocate(project, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no issue key counter")
                    .hasMessageContaining("tcms_seed_project_defaults");
        }
    }

    @Test
    @DisplayName("another company's project is invisible, so allocation refuses rather than leaks")
    void anotherCompanysProjectIsUnreachable() {
        UUID owner = givenCompany();
        UUID theirs = givenProject(owner, "THEIRS");
        UUID intruder = givenCompany();

        try (var scope = CompanyContext.open(intruder)) {
            assertThatThrownBy(() -> projectKeys.allocate(theirs, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no issue key counter");
        }
    }
}
```

**Do not build your own fixtures if the base class already has them.** Before writing
`givenCompany`, `givenProject` and `deleteCounterRow`, check `IntegrationTestBase` and the helpers
`ProjectCreateEndpointTest` uses:

```bash
grep -n 'protected UUID given\|protected .* company(\|insertProject' \
  backend/src/test/java/com/tcms/IntegrationTestBase.java
sed -n '1,80p' backend/src/test/java/com/tcms/project/ProjectCreateEndpointTest.java
```

Reuse what is there; add only the helpers that are genuinely missing, in this test class.

- [ ] **Step 2: Run it and watch it fail for the right reason**

```bash
cd backend && ./gradlew test --tests 'com.tcms.project.ProjectKeysTest'
```

Expected: compilation failure — `ProjectKeys` does not exist. Not a green run, and not a failure
about Docker: the backend suite boots a real Postgres 17 via Testcontainers and there is no H2 lane.

- [ ] **Step 3: Write the allocator**

`backend/src/main/java/com/tcms/project/ProjectKeys.java`:

```java
package com.tcms.project;

import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates a project's issue keys — {@code ACME-1}, {@code OLB-42} (expansion scope D5).
 *
 * <h2>Why this is a second allocator and not a widened first one</h2>
 *
 * <p>{@link com.tcms.tenancy.DisplayKeys} numbers five entity types <em>per company</em>, which
 * §6t argued for and which nothing about issues makes wrong: {@code TC-1} is unambiguous everywhere
 * inside a client, and moving a case between projects is a non-event because a collision is
 * impossible. Issue keys are the opposite trade by D5 — {@code ACME-12} names its project out loud,
 * which is the whole point of a key somebody types into a commit message.
 *
 * <p>The scope document proposed widening {@code key_sequence}'s primary key to
 * {@code (company_id, entity_type, project_id)} with {@code project_id} nullable for the five
 * existing types. <strong>That statement cannot run</strong> — a primary key cannot contain a
 * nullable column — and plan 009 review R1 replaced it with a new table. So {@code key_sequence},
 * its allocator, its five-member enum and its seven readers are untouched, and there is no
 * primary-key change on a live table anywhere in this expansion.
 *
 * <h2>Why it lives in {@code project} and not in {@code tenancy}</h2>
 *
 * <p>The prefix is {@code project.key}, and the counter is one row per project — so the table this
 * reads is joined to {@code project} in the same statement. {@code project} is one of the five
 * foundation modules ({@code ArchitectureRules.FOUNDATION_MODULES}), which is what makes
 * {@code tracker} importing this legal. {@code tenancy} would work too and would be worse: it would
 * have to learn what a project key is, which is a {@code project} concept.
 *
 * <h2>One statement, and the two wrong versions of it</h2>
 *
 * <pre>{@code
 * UPDATE project_key_sequence s SET next_value = s.next_value + :n
 *   FROM project p
 *  WHERE s.company_id = :c AND s.project_id = :p AND p.id = s.project_id
 * RETURNING p.key, s.next_value - :n;
 * }</pre>
 *
 * <ul>
 *   <li><strong>Not {@code MAX(key) + 1}.</strong> Two concurrent creations read the same maximum
 *       and issue the same key; the failure surfaces as a unique-constraint violation under load
 *       and never in a test. The row lock the {@code UPDATE} takes is what serialises them.</li>
 *   <li><strong>Not read-then-write.</strong> The same race with more code.</li>
 * </ul>
 *
 * <p>Gaps are possible and accepted: a transaction that allocates and rolls back does not return
 * its numbers. The contract is uniqueness and immutability, not gaplessness.
 *
 * <h2>Zero rows is an exception, and that is the whole reason it is one</h2>
 *
 * <p>An {@code UPDATE … RETURNING} matching nothing is an <em>empty result</em>, not an error, so
 * the failure mode without this check is a project whose issues silently cannot be numbered. Two
 * things produce it and the exception names both: a project created before {@code V2} that the
 * backfill missed, and a caller whose {@link CompanyContext} is another company's — under
 * {@code FORCE ROW LEVEL SECURITY} the policy matches no row, which is indistinguishable here from
 * the row being absent and is the correct answer to both.
 */
@Component
public class ProjectKeys {

    /**
     * A reserved block of numbers, and the keys they spell.
     *
     * @param prefix     the project's own {@code key} — {@code ACME}
     * @param firstValue the first reserved number, inclusive
     * @param count      how many were reserved
     */
    public record Allocation(String prefix, long firstValue, int count) {

        /** The sequence number at {@code index} — the only column that may order an issue key. */
        public long seqAt(int index) {
            if (index < 0 || index >= count) {
                throw new IndexOutOfBoundsException(
                        "Reserved " + count + " keys from " + firstValue + ", asked for index " + index);
            }
            return firstValue + index;
        }

        /** The display key at {@code index} — {@code ACME-1}. Never zero-padded. */
        public String keyAt(int index) {
            return prefix + "-" + seqAt(index);
        }
    }

    private static final String ALLOCATE = """
            UPDATE project_key_sequence s SET next_value = s.next_value + :count
              FROM project p
             WHERE s.company_id = CAST(:companyId AS uuid)
               AND s.project_id = CAST(:projectId AS uuid)
               AND p.id = s.project_id
            RETURNING p.key, s.next_value - :count
            """;

    private final EntityManager entityManager;

    public ProjectKeys(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Reserves {@code count} consecutive issue numbers for {@code projectId} in the active company.
     *
     * <p>Joins the caller's transaction rather than starting its own, which is required rather than
     * incidental: the row lock must be held until the issues that use the numbers commit, and a
     * separate transaction would release it early and would hand out numbers that survive a
     * caller's rollback while the issue that needed them does not.
     *
     * @throws IllegalStateException if the project has no counter row, or is not visible in the
     *                               active company — the two are one answer here, deliberately
     */
    @Transactional
    public Allocation allocate(UUID projectId, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Allocate at least one key, not " + count);
        }
        UUID companyId = CompanyContext.require();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(ALLOCATE)
                .setParameter("count", count)
                .setParameter("companyId", companyId.toString())
                .setParameter("projectId", projectId.toString())
                .getResultList();

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Project " + projectId + " has no issue key counter in company " + companyId
                            + ". Either the project is not visible in this company, or it predates "
                            + "V2 and the backfill missed it — tcms_seed_project_defaults creates "
                            + "the row and is idempotent.");
        }
        Object[] row = rows.getFirst();
        return new Allocation((String) row[0], ((Number) row[1]).longValue(), count);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend && ./gradlew test --tests 'com.tcms.project.ProjectKeysTest'
```

Expected: five tests, all PASS.

- [ ] **Step 5: Write the schema-level test — the row exists on both creation paths**

`backend/src/test/java/com/tcms/tenancy/ProjectKeySequenceSchemaTest.java`:

```java
package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every project has a counter, on every path that makes one.
 *
 * <p>{@code tcms_seed_project_defaults} is called by {@code ProjectCreateService} and by
 * {@code CompanyProvisioningService} (twice — the company's first project and any extra). Seeding
 * the row there rather than in Java is what makes one statement cover all three, and this is the
 * rule that says so: a fourth caller added later gets the row for free and this test proves it,
 * where a Java-side insert would need the fourth caller's author to remember.
 *
 * <p>The comparison is over the whole table in both directions rather than a spot check, per the
 * working agreement: reading V1 top to bottom missed five join tables without {@code company_id},
 * and scripting the comparison found them.
 */
@DisplayName("project_key_sequence — one row per project, on every creation path")
class ProjectKeySequenceSchemaTest extends IntegrationTestBase {

    @Test
    @DisplayName("a freshly provisioned company's project has its counter")
    void provisioningSeedsTheCounter() {
        // …provision a company through CompanyProvisioningService, then assert the row.
        // Use whatever ProvisioningSmokeTest/CompanyProvisioningServiceTest already does to
        // provision — do not re-implement it here.
    }

    @Test
    @DisplayName("every project in the database has exactly one counter row, both ways")
    void noProjectIsWithoutOneAndNoCounterIsOrphaned() {
        // SELECT p.id FROM project p LEFT JOIN project_key_sequence s
        //   ON s.company_id = p.company_id AND s.project_id = p.id WHERE s.project_id IS NULL
        // and the mirror, under a company context per company. Both empty.
    }
}
```

**Before writing the bodies**, read `backend/src/test/java/com/tcms/provisioning/` for the
provisioning fixture already in use and reuse it. Fill both bodies with real assertions — an empty
test body is a plan failure, not a placeholder to leave.

- [ ] **Step 6: Run the whole affected package**

```bash
cd backend && ./gradlew test --tests 'com.tcms.project.*' --tests 'com.tcms.tenancy.*' \
                            --tests 'com.tcms.provisioning.*'
```

Expected: green, including `SchemaConventionTest` and `ExportTablesTest`.

- [ ] **Step 7: Commit**

```bash
cd ~/RiderProjects/tcms
git add backend/src/main/java/com/tcms/project/ProjectKeys.java \
        backend/src/test/java/com/tcms/project/ProjectKeysTest.java \
        backend/src/test/java/com/tcms/tenancy/ProjectKeySequenceSchemaTest.java
git commit -m "feat(project): issue keys are numbered per project, not per company"
```

---

### T4 — The documents M0 leaves wrong

**Deliverable:** the ERD's key-design section, `docs/features.md`, `LinkSourceType`'s javadoc and
the Help page's empty state all describe nine roles, 89 keys and two counters — and nothing else in
the repository still claims six roles or 56 keys.
**Depends on:** T3

**Files:**
- Modify: `docs/specs/2026-07-30-tcms-erd.md` (§41, §354-386, §787, §809)
- Modify: `docs/features.md:30-31,36,397`
- Modify: `backend/src/main/java/com/tcms/documentation/LinkSourceType.java`
- Modify: `frontend/src/features/help/RoleMatrix.tsx:63`

- [ ] **Step 1: Amend the ERD's key design, in the superseded style**

Under `### key_sequence — display-key allocation, **per company**` in
`docs/specs/2026-07-30-tcms-erd.md`, after the existing `key_sequence` column table, insert:

````markdown
#### Issue keys are per project — `project_key_sequence` (2026-08-30, `V2`)

D5 of the project-management expansion makes issue keys **project-prefixed** — `ACME-12`, not
`TC-12` — which is the opposite trade from the five types above, and deliberately: a key somebody
types into a commit message or a PR title has to name its project out loud, while a test case's key
is read inside one client and never leaves it.

~~`key_sequence`'s primary key becomes `(company_id, entity_type, project_id)`, with `project_id`
nullable for the five existing company-scoped types, and its `entity_type` CHECK gains `'ISSUE'`.~~
→ **superseded 2026-08-30 (plan 009 review R1, implemented by plan 010): that statement could never
have run.** A primary key cannot contain a nullable column, so `project_id UUID NULL` in a PK is
rejected by Postgres — the design was wrong in a way no amount of care in the migration would have
survived. **`key_sequence` is not touched at all**, keeping its primary key, its five entity types,
its `prefix` column, its allocator (`tenancy/DisplayKeys.java`) and its seven readers exactly as §6t
left them.

| `project_key_sequence` | Type | Notes |
|---|---|---|
| `company_id`, `project_id` | UUID | Composite PK, `company_id` leading |
| `next_value` | BIGINT | |

**No `prefix` column**, unlike `key_sequence`. The prefix *is* `project.key`, which already exists
with `uq_project_key` and `ck_project_key_format`; a column here would be a second copy of a string
that is immutable by contract, and the two copies would be edited months apart. The allocator joins
`project` in the same statement and takes it from there — the same rule `DisplayKeys` follows when
it takes the prefix from the row rather than from a Java constant.

**One row per `(company, project)`**, seeded by `tcms_seed_project_defaults` so every creation path
gets it, and backfilled by `V2` for every project that already existed. The allocation statement is
`key_sequence`'s, one row narrower:

```sql
UPDATE project_key_sequence s SET next_value = s.next_value + :n
  FROM project p
 WHERE s.company_id = :c AND s.project_id = :p AND p.id = s.project_id
RETURNING p.key, s.next_value - :n;
```

**This relieves a contention cost §6t accepted rather than adding one.** All case creation in a
company still serialises on one `key_sequence` row; issue creation serialises per project, so two
projects in one client never wait on each other. Range allocation is still mandatory for bulk
import — M4 moves a whole Jira project in one pass.
````

Then correct the three cross-references: §41's index list gains `project_key_sequence`, §787's
contention paragraph gains the sentence above, and §809's change-log row is struck through with the
same reason.

- [ ] **Step 2: Correct `docs/features.md`**

Line 30-31:

```markdown
- Nine built-in roles: **Viewer**, **Automation Service**, **Test Engineer**, **Test Lead** and
  **Project Manager** on a project; **Account Manager**, **Finance** and **Company Admin** across a
  company; **Instance Admin** across the instance
```

Line 36:

```markdown
- Test Lead adds exactly ten things over Test Engineer: the three approvals, permission to manage
  the project and its integrations, the project audit trail, and deleting issues, configuring the
  issue workflow and importing from another tracker
```

Line 397:

```markdown
- Custom roles / clone-and-edit — deliberately rejected; nine immutable roles only
```

- [ ] **Step 3: Correct `LinkSourceType`'s javadoc**

In `backend/src/main/java/com/tcms/documentation/LinkSourceType.java`, replace the sentence
*"`V1`'s `CHECK` also admits `DEFECT` and `DOC_PAGE`"* with:

```java
 * <p>{@code V1}'s {@code CHECK} also admits {@code DEFECT} and {@code DOC_PAGE}, and {@code V2}
 * added {@code ISSUE}. None of the three is offered here and none is on the contract's enum,
 * because none has a writer yet: a defect is an external record this product links out to rather
 * than stores, a page-to-page link is a different feature that would need its own target semantics,
 * and the issue-to-specification edge arrives with module M1's tracker.
```

- [ ] **Step 4: Correct the Help page's empty state**

`frontend/src/features/help/RoleMatrix.tsx:63`:

```tsx
        body: 'This instance returned no roles. That is unexpected — the nine built-in roles are seeded.',
```

- [ ] **Step 5: Sweep for anything else still claiming six or 56**

```bash
cd ~/RiderProjects/tcms
grep -rn 'six built-in\|six immutable\|the six are\|55 keys\|56 keys\|exactly seven' \
  --include='*.md' --include='*.ts' --include='*.tsx' --include='*.java' --include='*.yaml' \
  docs README.md frontend/src packages/*/src backend/src | grep -v node_modules
```

Work the list. **Past tense and strikethrough are not claims** — `~~six immutable roles~~` and
*"was, until `V2`, seven"* are correct and stay. A present-tense sentence is a claim and moves.

- [ ] **Step 6: Run the packages gate, which is what reads this prose**

```bash
cd packages && npm run test:run --workspaces
```

Expected: green — `tierClaims.test.ts` reads `backend/`, `frontend/src/`, `packages/` and
`docs/specs/*.yaml`, and `citedFiles.test.ts` fails if any path named in a comment is not on disk.

- [ ] **Step 7: Commit**

```bash
cd ~/RiderProjects/tcms
git add docs/specs/2026-07-30-tcms-erd.md docs/features.md \
        backend/src/main/java/com/tcms/documentation/LinkSourceType.java \
        frontend/src/features/help/RoleMatrix.tsx
git commit -m "docs(roles): nine roles, 89 keys, and two counters instead of one"
```

---

### T5 — Gates on `shanks`, and the five acceptance checks

**Deliverable:** `./gradlew build`, `npm run build && npm run lint && npm run test:run` in
`frontend/`, and `npm run build` in `packages/` are all green **on `shanks`**; and the five
acceptance checks below are demonstrated with their output pasted into the task report.
**Depends on:** T4

**Nothing in this task starts, stops, rebuilds or re-seeds a stack on the owner's machine.** The
suites run on `shanks` through `~/bin/tcms-remote`, and the one check that genuinely needs a running
instance uses the lab the push delivers to. If a check cannot be made that way, it is reported as
not done — a local `docker compose up --build` is not the fallback.

- [ ] **Step 1: Read the runner's own document first**

```bash
sed -n '1,60p' docs/specs/shanks-runner-details.md
```

The one that bites: `BUILD FAILED` with `Could not write XML test results` and **zero** assertion
failures is an fd-limit problem, not a red suite. `ulimit -n 65535; ./gradlew build`.

- [ ] **Step 2: Sync the working tree, uncommitted changes included**

```bash
~/bin/tcms-remote sync
```

- [ ] **Step 3: The backend gate — the only machine-run of the backend suite before master**

```bash
~/bin/tcms-remote backend ./gradlew build
```

- [ ] **Step 4: The frontend gate**

```bash
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

`npm run build` is `tsc -b + vite build`, not `tsc --noEmit` — the latter has reported 0 errors while
the build failed with nine.

- [ ] **Step 5: The packages gate**

```bash
~/bin/tcms-remote packages npm run build
```

- [ ] **Step 6: Acceptance check 1 — `SchemaConventionTest`, `matrixCopies` and `tierClaims`**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest' \
                                          --tests 'com.tcms.schema.SeedDataTest'
~/bin/tcms-remote packages npm run test:run --workspace @tcms/client
```

Expected: green. Paste the three summary lines into the report.

- [ ] **Step 7: Acceptance check 2 — a company still provisions, and check 3 — every project gets its counter**

**Do not run a local stack for this, and do not `docker compose down -v` anything.** The standing
directive is that gates run on `shanks`; `down -v` drops `pgdata` and `tcmsblobs`, which is
destructive to whatever the owner has running and seeded, and the two claims are already provable
where the suite runs:

- **A company still provisions** is what `com.tcms.provisioning.CompanyProvisioningServiceTest`
  asserts, against a Postgres 17 that Testcontainers builds **from empty** — `V1` then `V2` — for
  every run. That is the same shape a fresh `docker compose up` would produce, minus the teardown.
- **A new project gets its `project_key_sequence` row** is T3's
  `ProjectKeySequenceSchemaTest`, which asserts it on both creation paths and sweeps the whole
  table in both directions.

```bash
~/bin/tcms-remote backend ./gradlew test \
  --tests 'com.tcms.provisioning.*' \
  --tests 'com.tcms.tenancy.ProjectKeySequenceSchemaTest' \
  --tests 'com.tcms.project.ProjectKeysTest'
```

Expected: green. Paste the summary line into the report.

**If a live database is nonetheless inspected by hand, read it as the `postgres` superuser, not as
`tcms_owner`.** `tcms_owner` is `NOBYPASSRLS` and `project_key_sequence` is `FORCE ROW LEVEL
SECURITY`, so a count of `0` from that role means the policy is doing its job, not that the backfill
failed. That distinction has cost time here before.

- [ ] **Step 8: Acceptance check 4 — the three roles are grantable from the existing member screen**

Do this against the **lab**, which `ci.yml` continuously delivers to on a push to master — so Step
11's push comes first and this step follows it. Get the address from the runner's document rather
than inventing one:

```bash
grep -n -i 'lab\|url\|http' docs/specs/shanks-runner-details.md | head -20
```

Sign in there and, in the browser:

1. Open **Teams → a member**. Confirm **Account Manager** and **Finance** appear under company roles
   and **Project Manager** under project roles, matching `docs/mockups/member.html:28-58`.
2. Grant **Project Manager** on a project. Confirm the row appears in the assignment table with the
   role name and no console error.
3. Grant **Finance** at company scope. Confirm it lands.
4. Open **Help → the role matrix**. Confirm nine columns and the new areas — Issues, Board &
   backlog, Releases, Time, Staffing, Clients, Rates & billing — each with its keys.

**This is a click-through, not a test**, and its result is a sentence in the report either way. The
gates prove it compiles: a no-op theme toggle, two contradictory percentages on one screen and a
provider-ordering bug that rendered nothing but the error page all typechecked and linted clean.

**If the lab is unreachable, say so and stop** — do not fall back to `docker compose up` on the
owner's machine to get a screen to click. Report the check as not done, and what stood in the way.

- [ ] **Step 9: Acceptance check 5 — the frontend diff is the three files and no more**

```bash
cd ~/RiderProjects/tcms
git diff --stat master@{u} -- frontend/src
```

Expected exactly:

```
 frontend/src/api/schema.d.ts
 frontend/src/features/help/RoleMatrix.tsx
 frontend/src/lib/permissions.ts
```

**Nothing under `frontend/src/features/admin/`.** A file there in this diff is the role-name
hardcoding plan 009 warns about, and removing it is the finding, not the diff. See D-M0-10 for why
the criterion is three files rather than one.

- [ ] **Step 10: Push the lane — before Step 8's click-through**

```bash
git push
```

`ci.yml` runs backend **compile only**, the frontend smoke suite and the full packages suite, then
continuously delivers to the lab — which is what puts `V2` and the three roles on the instance
Step 8 clicks through. The backend suite gates nothing before master, which is why Step 3 ran it on
`shanks` first.

**Steps 8 and 10 are therefore done in the order 10 → 8.** They are numbered this way round because
Step 8 is an acceptance check and Step 10 is the lane's close; the dependency is stated here rather
than hidden in the numbering.

---

### T6 — Close plan 009

**Deliverable:** plan 009's status table reads `done 2026-08-30 @ <sha>` for M0, its M0 section
carries a `**Built and unverified:**` list and the `V` actually taken, and the M1, M7 and Mc prompt
blocks name `ProjectKeys` and every decision above that they now depend on.
**Depends on:** T5

Its own commit, staging only that one file.

**Files:**
- Modify: `docs/plans/009-project-management-expansion-modules.md`

- [ ] **Step 1: Status table**

Set M0's row to `done 2026-08-30 @ <short sha of T5's last commit>`. Leave every other row alone —
the status table is the gate the next agent trusts, and nobody but the finishing agent writes a row.

- [ ] **Step 2: `**Built and unverified:**` under the M0 section**

```markdown
**Built and unverified:** the 33 new permission rows exist and are granted, and **no code reads any
of them** — the first evaluation site for `issue:*` arrives with M1, for `board:*` with M2, and for
`billing:*` with M8. A grant that is never evaluated is a grant nobody can tell is inverted, which
is the same standing this repository already gives `doc:read` and `artifact:read` (§A3, flow audit
C8). `ProjectKeys` is tested against a real Postgres but has **no production caller** until M1's
issue creation; `doc_link.source_type = 'ISSUE'` is accepted by the CHECK and written by nothing;
`project.client_id` exists, is nullable, is unmapped and is read by nothing until Mc.
```

- [ ] **Step 3: The `V` actually taken**

If T2 Step 1 found `V2` free, the table is already right and this step is a no-op — say so in the
commit message. If it took a different number, correct the table **and** M0's `**db**` line, and
*strike through* the reserved number rather than deleting it.

- [ ] **Step 4: Rewrite the M1 prompt**

M1 depends on M0. Edit its prompt block **in place**, adding:

```text
M0 landed on 2026-08-30. What it gives you, and what it costs you:

- The issue-key allocator is `com.tcms.project.ProjectKeys` in the `project` foundation module —
  `allocate(UUID projectId, int count)` returning `Allocation(prefix, firstValue, count)` with
  `keyAt(i)` spelling `ACME-1`. Importing it from `tracker` is legal; `project` is one of the five
  foundation modules. Do NOT reach for `tenancy.DisplayKeys` — that one is per company, is unchanged,
  and its `EntityType` enum still has five members with no `ISSUE`.
- The counter row is created by `tcms_seed_project_defaults`, not by any Java code. If you add a
  fourth project-creation path, call that function and the row is free.
- `doc_link.source_type` accepts `'ISSUE'`. `com.tcms.documentation.LinkSourceType` deliberately
  does NOT — the column is wider than the enum by design, and adding the member is M1's, in the
  slice that first writes such a row.
- Test Engineer holds `issue:read`, `issue:create`, `issue:edit`, `issue:comment`, `board:read`,
  `release:read`, `time:log`. Test Lead holds `issue:*` and, per D3 taken literally, NOTHING from
  `board:*`, `release:*` or `time:*` beyond the reads and `time:log`. Viewer holds `issue:read`,
  `issue:comment`, `board:read`, `release:read`. Agent is Test Engineer minus `company:read`,
  derived — you do not seed it.
- There is no `board:move`: dragging a card changes the issue's status and costs `issue:edit`.
- `SeedDataTest`'s `SPEC_COLUMNS` now has ten entries and `roleMatrix.ts`'s `COLUMNS` has seven.
  A §A3 table you add must carry all seven `PROJECT` columns or `roleMatrix()` will not read it as a
  project table, and a heading it does not know is a hard failure rather than a skipped column.
- The catalogue is 89 keys. If M1 needs a key M0 did not seed, that is a `V3` row plus the same five
  documents in one commit — the tax does not get cheaper for being small.
```

- [ ] **Step 5: Rewrite the M7 prompt**

M7 (Staffing) depends on M0 per review R11. Add:

```text
M0 landed on 2026-08-30. Staffing's keys are split across two scopes and the split is load-bearing:

- `staffing:read` and `staffing:allocate` are PROJECT-scope, held by Project Manager, and answer
  "who is on THIS project". They sit in §A3's `#### Staffing` PROJECT table.
- `staffing:directory`, `staffing:profile` and `staffing:absence` are COMPANY-scope, held by Account
  Manager (and `staffing:directory` by Finance too), and answer the company-wide capacity and skills
  questions story S2 asks for. They sit in §A3's `#### Staffing — COMPANY scope` table.

Do not grant a COMPANY-scope key to a PROJECT-scope role: `PermissionEvaluator.evaluateCompanyWide`
reads the SYSTEM and COMPANY terms only, so the row would be unreachable — the defect §43 repaired
for `company:read`, still visible as four entries in `SeedDataTest.MATRIX_DIVERGENCE_BY_DECISION`.

Account Manager and Finance hold NO `project:read` and NO `report:view`, deliberately (plan 010
D-M0-8): both are COMPANY-scope, so either key would open every project in the company. If M7 needs
an Account Manager to see something inside a project, serve it from the staffing aggregate under
`staffing:directory` rather than widening their grant.
```

- [ ] **Step 6: Rewrite the Mc prompt**

Mc (Clients) depends on M0. Add:

```text
M0 landed on 2026-08-30. Three things it decided that Mc must honour:

- `project.client_id` exists, is `UUID NULL`, has **no** `REFERENCES` and is **not mapped** on
  `com.tcms.project.Project`. Mc adds the FK to `client(id)` and the entity mapping together, in one
  migration. It is unmapped on purpose: `ArchitectureRulesTest` sweeps `@Entity` fields for readerless
  and writerless columns in both directions, so a mapping added by M0 would be an entry Mc then had
  to remove.
- `client:read`, `client:create`, `client:edit` and `client:delete` are seeded, COMPANY-scope, held by
  Instance Admin, Company Admin and Account Manager, with Finance holding `client:read` alone.
- **Story C2's client picker rides `project:settings`, not `client:read`** (plan 010 D-M0-9). Project
  Manager is PROJECT-scope, so a company-wide `client:read` would be inert for them, and granting
  `client:read` to the `Company Member` baseline would break the one sentence that role is defined by
  — "exactly `company:read` and nothing else". Serve the picker from the project-settings endpoint.
```

- [ ] **Step 7: Commit**

```bash
cd ~/RiderProjects/tcms
git add docs/plans/009-project-management-expansion-modules.md
git commit -m "docs(plan): M0 done"
git push
```

---

## Self-review

**Spec coverage** — M0's `**db**` line: `project_key_sequence` (T2 Step 3, T3), `doc_link` CHECK
(T2 Step 6), `project.client_id` (T2 Step 6), permission rows and the three role grants (T2 Steps
7-8). Its `**be**` line: the seed (T2 Step 5, D-M0-2 records the deviation), the allocator (T3), the
export (no change, with the reason). Its `**contract**` line: T1. Its `**docs**` line: T2 Steps
12-15 and T4 Step 1. Its `**Done when**` line: T5 Steps 6-10, with D-M0-10 correcting the last
criterion. §3, §7, D3 and D28 of the scope document: the catalogue section and T2 Steps 7-8. Stories
F1 and F2: F1 is M1's (existing `work_item` rows migrate there); F2's three criteria are T5 Step 9's
click-through and `moneyIsFinanceAndAdminsOnly`. `teams.html` / `member.html`: T5 Step 9.

**Placeholders** — T3 Step 5's two test bodies are the only prose-only steps, and they are marked as
bodies to fill with a named instruction to reuse the existing provisioning fixture rather than
invent one; the step says explicitly that leaving them empty is a failure.

**Order** — T1 is safe alone because nothing pins `Role.name` to the seed. T2 is one commit because
five checks compare its files pairwise in both directions and any split is red at the boundary; the
task block states that rather than assuming it. T3 needs T2's table. T4 needs T3's class to exist
before naming it in a hand-off. T5 needs everything. T6 needs T5's sha.

**Types** — `ProjectKeys.Allocation(prefix, firstValue, count)`, `seqAt(int)`, `keyAt(int)`,
`allocate(UUID, int)` are the same names in T3, T5 and T6. `project_key_sequence
(company_id, project_id, next_value)` is the same three columns in T2, T3, T4 and T6.

**Deliverables** — all six state a fact about the world after the task.
