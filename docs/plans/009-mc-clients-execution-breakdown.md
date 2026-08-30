# Mc — Clients: execution breakdown

The task cut for **Mc — Clients**, phase 6 of
[009-execution-breakdown.md](009-execution-breakdown.md) and a module of
[009-project-management-expansion-modules.md](009-project-management-expansion-modules.md).
**This file is the authority for that phase** — it supersedes the four coarse tasks the program run cut for Mc.

From Mission Control run `96e92e58-45e5-4550-954a-f59af7537661`, planned 2026-08-30 against a stale worktree. That
board is abandoned; **implementation happens on `master`, in this checkout — no worktree, no `mc/…` branch.**

---

## Position: seventh. `M1 → M2 → M3 → M5 → M4 → Mc → M7 → M6 → M8 → M9`

This run was planned on the premise that **Mc lands next**. It does not. Confirmed 2026-08-30: the tracker spine goes
first. Three consequences, each written into the tasks below rather than left for a reader to infer:

| The run decided | Reversed to | Because |
|---|---|---|
| Mc takes **`V3`** (q3) | **M1 takes `V3`.** Mc takes the next free V at start time — projected **`V8`** | Five modules land migrations before Mc. The q3 reasoning — *"Mc lands first, so Mc takes V3"* — no longer holds, but its rule does: versions are assigned in **landing order**, read from the directory |
| Mc takes plan **`011`** (q2) | **M1 takes `011`.** Mc takes the next free NNN — projected **`016`** | Same reason. q2's *other* finding survives and was the real point: `010-foundation.md` **does** exist here, so `010` is M0's and is not free |
| M1's prompt must be rewritten to re-read the directory (q3) | **Not needed.** M1 keeps `V3` | Nothing to reverse. Plan 009's table is correct for M1 |

**Mc still depends only on M0, which is done.** Nothing in the module needs M1–M4; it sits seventh by choice, not by
dependency. Plan 009's *"Mc is deferrable and sits anywhere after M0 — build it the week a first real client
exists"* still stands. Moving it changes only the two projected numbers above, both of which every task re-reads
from disk anyway. Note that **M8 depends on Mc**, so Mc cannot slip past M8.

---

## Preconditions — verified 2026-08-30

| The run recorded | Actually, here |
|---|---|
| `docs/mockups/` absent — *blocked `t7` and `t8`* | **present, 51 files, git-tracked**, including `clients.html`, `client.html`, `client-new.html`, `clients-empty.html`, `projects.html`, `project-new.html`, `settings-general.html` |
| Both expansion specs absent | present and tracked |
| `docs/plans/010-*.md` absent | **`010-foundation.md` present** |
| M0 unverified | **done** — plan 009's status table reads `done 2026-08-30 @ 24f997bb` |

**q1 is closed.** The screens are built against the mockups, as the module charter always intended. Its option (b),
*derive the screens and mark them provisional*, and option (c), *land backend-only and defer the frontend*, are both
moot — recorded so a reader knows the fork existed.

Three findings from the run that **do** still hold, and shape the tasks:

1. **The permission tax for Mc is already paid.** `client:read/create/edit/delete` are seeded in
   `V2__pm_foundation.sql` and already present in all five documents `SeedDataTest` and `matrixCopies.test.ts`
   compare — `README.md`, `docs/specs/permission-matrix-summary.md`,
   `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, the `PermissionKey` enum in `openapi.yaml`, and
   `frontend/src/lib/permissions.ts`. **There is no permission-catalogue task in this module.** Mc must not add
   client keys or move those grants.
2. **`settings-general.html` has no implemented counterpart.** `frontend/src/features/settings/` holds
   Classification, Review, Tracker and Notification pages only; project creation lives in `ProjectCreateDialog.tsx`,
   not a page. q5 settles where the Client field goes.
3. **Naming hazard.** `clientId` is already taken in `openapi.yaml` for OIDC identity providers
   (`com.tcms.identity.IdentityProvider`). The CRM resource needs a name that does not collide in either generated
   schema or the CLI verb table.

---

## Why every phase here is sequential

The four lanes below name **ownership**, not concurrency. Every phase is `parallelSafe: false` deliberately:
CLAUDE.md's *Running work in parallel — don't* is a standing directive earned from three silent losses of committed
work in this repo, and plan 009 itself lists `keys.ts`, `labels.ts`, `permissions.ts`, `router.tsx`, `devFaults.ts`,
`contractBodies.ts` ×2, `docs/features.md` and the MCP surface doc as files every module touches.

| Lane | Owns |
|---|---|
| **Plan and documentation** (`docs`) | Owns docs/plans/0NN-clients.md from authoring through hand-off closure, plus docs/features.md and plan 009's status table. Sequential because the closure task edits the plan document the first task wrote. |
| **API contract and client tooling** (`contract`) | Owns openapi.yaml, both generated schema copies, both contractBodies.ts copies, the CLI and MCP verb tables and the MCP surface doc. A single atomic surface — plan 009 requires it as one commit. |
| **Migration and crm module** (`backend`) | Owns the Flyway migration, the new com.tcms.crm package, and the project-side integration. Sequential: entities need tables, the picker needs the entities, and the project tasks share Project.java and its DTOs. |
| **React screens and data layer** (`ui`) | Owns the fault switch, the clients query module, the client screens, and the client column/filter/field on existing project surfaces. Sequential: all screens consume the same query layer and share keys.ts, labels.ts, router.tsx and AppShell.tsx. |

The task shape follows plan 009's tax: **contract first as one commit** (`openapi.yaml` with `required` arrays
including on inline objects, both generated clients, `contractBodies.ts` byte-identical across both copies, CLI + MCP
verbs, the surface doc), then the migration, then the `crm` module, then project integration, then the frontend, then
doc closure. The fault switch is extended **before** the screens it proves.

No task here runs live tests, boots a stack or does manual verification — unit and component tests belong to their
implementing task, and the gates on `shanks` are a separate pass.

---

## Decisions

### Q1 — The mockups and both expansion specs are absent from this worktree. Where do the screens come from?

Answered 2026-08-30: *the designs are all there, just not tracked via git, they exist locally on the path i gave you* · **Closed by fact** — the mockups are present. Build against them.

The prompt calls the mockups "the source of truth for the screens", but there is no docs/mockups/ directory anywhere in the tree, and neither docs/specs/2026-08-30-project-management-expansion-scope.md (D1) nor docs/specs/2026-08-30-project-management-expansion-user-stories.md (C1-C3) exists. The only HTML in the repo is docs/brand/renders/, docs/specs/assets/renders/ and frontend/index.html. Nothing in .gitignore explains the omission, so the worktree is likely missing commits that exist elsewhere. The behaviour spec is partly recoverable from plan 009's Mc section and V2's comments; the screen layouts are not recoverable at all.

| Option | | Detail |
|---|---|---|
| `a` Sync the missing docs into the worktree before the UI phase starts |  | Fetch docs/mockups/ and both spec files from the branch or checkout that holds them, then build the screens against the stated source of truth. Costs a round trip and blocks phase 3 until it lands, but it is the only option that satisfies the acceptance criteria as written — particularly solo-overview.html versus project-overview.html, which is an explicit criterion and cannot be inferred. |
| `b` Derive the screens from the existing render kit and plan 009, and mark them provisional |  | Build clients/client/client-new from the patterns already proven in features/admin and features/settings, treating plan 009's Mc section as the behavioural spec. Keeps the module moving with no dependency on missing files, but the screens will almost certainly diverge from the mockups and the design audit in docs/audits/ will reopen them — rework at exactly the altitude that is most expensive to redo. |
| `c` Land backend and contract only; defer the frontend until the mockups arrive |  | Run phases 1, 2 and 4 now and hold phase 3. Preserves correctness and unblocks M8's dependency on the client endpoints, but violates the module's own charter — a module is supposed to be complete on arrival and usable by a person the day it merges, and a backend-only Mc is neither. |

### Q2 — Which plan number does Mc take — 010 or 011?

Answered 2026-08-30: `a` — *Take 011 and leave 010 to M0* · **Reversed** — plan `016` (projected), not `011`. See *Position* above.

Plan 009 records M0 as landed at 24f997bb with "plan 010", and V2__pm_foundation.sql is present on disk, so M0's code is here. But docs/plans/010-*.md does not exist in this worktree. The rule is "take the next free NNN, never renumber", and whether 010 is free depends on whether M0's doc exists elsewhere. Note that docs/plans/ already contains two files numbered 007, so this repo has collided before.

| Option | | Detail |
|---|---|---|
| `a` Take 011 and leave 010 to M0 | **chosen** | Trusts plan 009's own record that M0 owns 010. Leaves a visible gap in this tree until M0's doc syncs, but never produces a second collision like the duplicated 007 — and a gap is cheap to explain where a collision is not. |
| `b` Take 010, since no file holds it here |  | Follows the letter of "next free NNN" against the directory as it actually is. Risks a genuine duplicate the moment M0's document syncs in, which is the precise failure the 007 duplication already demonstrates in this repo. |
| `c` Resolve the missing commits first, then number against the reconciled directory |  | Ties this to q1, since both symptoms point at the same missing commits. Most correct and removes the guess entirely, but blocks the very first task on an external sync. |

### Q3 — Does Mc take V3, which plan 009 reserves for M1?

Answered 2026-08-30: `a` — *Take V3 now and rewrite M1's prompt to re-read the directory* · **Reversed** — M1 takes `V3`; Mc takes the next free V, projected `V8`. See *Position* above.

Only V1__initial_schema.sql and V2__pm_foundation.sql exist, so V3 is next free on disk. Plan 009's table reserves V3 for M1 and says Mc reserved no number on purpose, taking the next free at start time. Plan 009 also states that versions are assigned in landing order at integration, not from the table. The hazard is real and silent: out-of-order is unset in application.yml, so a lower-numbered migration arriving after a higher one is never applied, and Testcontainers builds each test database from scratch so no suite will ever show the collision.

| Option | | Detail |
|---|---|---|
| `a` Take V3 now and rewrite M1's prompt to re-read the directory | **chosen** | Matches plan 009's own rule that versions are assigned in landing order at integration. Mc lands first, so Mc takes V3 and M1 takes V4. Requires editing M1's prompt in the same closure commit, which the hand-off protocol already calls for under "rewrite dependent prompts". |
| `b` Leave V3 to M1 and take V4 for Mc |  | Honours the reservation table literally. But if Mc lands before M1, V4 is applied first and V3 arriving later is silently skipped on every existing instance — exactly the failure the reservation warning describes, and one no test in this repo can catch. |
| `c` Confirm M1 has not started, then take V3 under the parallel-work pre-assignment protocol |  | Reads docs/specs/parallel-work-rules.md and pre-assigns the version explicitly across both lanes before writing the file. Safest if M1 really is running beside Mc, but adds coordination overhead that is pure waste if M1 has not started — and CLAUDE.md's standing directive is that work is sequential anyway. |

### Q4 — What do the per-client rollups on the client page actually show, with M6/M7/M8 not yet built?

Answered 2026-08-30: `a` — *Project rollup now, plus an explicitly reserved hours-and-invoices slot* · **Stands.**

Mc's deliverable promises that "per-client rollups appear where they are relevant", but hours and invoices belong to M6/M7/M8, which land after Mc and depend on it. The closure step tells Mc to hand M8 "the client page's hours-and-invoices slot as it landed", implying the slot is Mc's to define. Plan 009 also forbids porting a screen the module has no endpoint for.

| Option | | Detail |
|---|---|---|
| `a` Project rollup now, plus an explicitly reserved hours-and-invoices slot | **chosen** | The client page shows the projects attached to the client and their status, and reserves a named, documented region for M8 to fill. Honest about what exists, gives M8 a defined seam to land into, and stays inside the endpoints Mc actually ships. |
| `b` Project rollup only, with no reserved slot |  | Simplest and adds nothing speculative. But M8 then has to design the seam itself and Mc's closure step cannot hand over the slot it is told to hand over, pushing an unresolved layout decision into a module that lands three steps later. |
| `c` Build the full rollup shape now against placeholder data |  | Gives M8 nothing to do but fill values. Ships UI backed by no endpoint, which plan 009 explicitly forbids, and hard-codes assumptions about hours and invoices before either module is designed. |

### Q5 — Where does the Client field on an existing project live, given settings-general.html has no implemented counterpart?

Answered 2026-08-30: `a` — *Create a minimal General settings page holding the Client field* · **Stands.**

frontend/src/features/settings/ holds Classification, Review, Tracker and Notification pages only — there is no General settings page, and each settings page is its own route rather than a tab under an index. Project creation lives in ProjectCreateDialog.tsx, not a page. So the Client field on project-new.html has an obvious home, but the one on settings-general.html does not. Task t8 currently assumes a GeneralSettingsPage would be created.

| Option | | Detail |
|---|---|---|
| `a` Create a minimal General settings page holding the Client field | **chosen** | Adds features/settings/GeneralSettingsPage.tsx as a fifth settings route, matching the mockup's shape and giving later modules a natural home for other project-level attributes. Grows Mc's scope by one screen, and its content is thin until something joins the Client field there. |
| `b` Put the Client field on the project detail or overview surface instead |  | Avoids a near-empty new page by attaching the field where the project is already displayed. Diverges from the mockup that q1 may yet produce, and risks placing an edit control on a read surface that has no other edit affordance. |
| `c` Attach and detach only from the client page, never from the project |  | Keeps all client-shaped UI inside the crm feature, which makes the empty case trivially true. But it inverts the natural task — a person editing a project expects to set its client there — and story C2's picker is explicitly gated on project:settings, which is a project-side permission and would have no project-side surface. |

---

## Tasks

Task ids are prefixed `mc` so they do not collide with the program breakdown's `t1`–`t46`. Nothing is started.

## Phase 1 — Plan and contract

**Goal.** A written module plan at the next free NNN with the next free V confirmed against the directory, then the whole client surface landed as one contract-first commit: openapi.yaml, both generated clients, contractBodies.ts x2, the CLI and MCP verbs, and the surface doc.

**Sequential.** One lane: the contract task is written from the plan document that precedes it, and plan 009 requires the contract, both generated copies and the CLI/MCP verbs to land as a single atomic commit — which cannot be split across concurrent workers.

### `mc1` · Write the Mc module plan document

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `be-planner` | `docs` | `ep-writing-plans`, `be-shared-delivery-plan`, `be-shared-codebase-survey` | — |

Produce docs/plans/0NN-clients.md with ep-writing-plans as a flat T1..Tn task list covering the crm module: client and client_contact, contacts that never become logins, the FK constraint on the project.client_id column M0 shipped without one, and the empty case as the acceptance criterion. Take the next free NNN and never renumber. Confirm M0 reads done in plan 009's status table before anything else. Re-read backend/src/main/resources/db/migration/ and record the actually-next-free V in the document — Mc reserved no number on purpose, and the table in plan 009 is a reservation, not a fact. Record the three M0 decisions Mc must honour: project.client_id is UUID NULL with no REFERENCES and deliberately unmapped, the four client permissions are already seeded with Finance holding client:read alone, and story C2's picker rides project:settings rather than client:read.

**Corrected 2026-08-30.** Two of this task's instructions are reversed by the confirmed module order. The plan number is **not `011`** — M1 takes `011`; Mc takes the next free `NNN` when it starts, projected `016`. The V number is **not `V3`** — M1 takes `V3`; Mc takes the next free V when it starts, projected `V8`. Both are still read from disk at authoring time, which is what the task already required.

**Acceptance criteria**

- [ ] docs/plans/0NN-clients.md exists at the next free NNN with a flat T1..Tn task list — **`011` is M1's; projected `016`**
- [ ] The document names the V number read from backend/src/main/resources/db/migration/ at authoring time, not from plan 009's table
- [ ] The document records that client:read/create/edit/delete are already seeded and must not be added or re-granted
- [ ] The document records that the client picker is served from the project-settings endpoint under project:settings
- [ ] The empty case is written as an acceptance criterion with each absence enumerated: no Client column, no Client filter, no Client field, no attach-a-client prompt, nothing marking a project as missing one
- [ ] M0's status is quoted from plan 009's status table as verified done

**Files**

- `docs/plans/016-clients.md`  *(projected — take the next free NNN at start time)*

### `mc2` · Contract first: openapi.yaml, both generated clients, contractBodies x2, CLI and MCP verbs

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `be-java` | `contract` | `be-shared-api-surface-plan`, `be-java-pattern-guide`, `tool-capability-contract` | `mc1` |

Land the entire client surface as one contract-first commit. Add the client and client-contact endpoints and schemas to docs/specs/openapi.yaml with a required array on every schema including inline nested objects, plus the project-settings client picker response and the clientId field on the project write and read shapes. Regenerate BOTH copies — frontend/src/api/schema.d.ts via npm run gen:api and packages/client/src/schema.ts via the same script under packages/client. Add the client fixtures to the marked byte-identical region of contractBodies.ts and make the identical edit in both copies so contractPairing.test.ts stays green. Add the CLI and MCP verbs for client list/get/create/edit/delete and contact management, and update docs/specs/2026-08-08-mcp-tool-and-command-surface.md. Beware the existing clientId field on the OIDC identity-provider schemas — pick CRM names that do not collide in the generated types or the verb table.

**Acceptance criteria**

- [ ] Every new schema in openapi.yaml has a required array, including on inline nested objects
- [ ] frontend/src/api/schema.d.ts and packages/client/src/schema.ts are both regenerated in the same commit and packages/client/src/test/schemaFreshness.test.ts passes
- [ ] The marked region of frontend/src/api/contractBodies.ts and packages/client/src/test/contractBodies.ts is byte-identical
- [ ] CLI verbs and MCP tools for clients and contacts exist and pass writeConformance.test.ts and guideCopies.test.ts in both packages
- [ ] docs/specs/2026-08-08-mcp-tool-and-command-surface.md lists the new verbs
- [ ] No new permission keys are introduced; the PermissionKey enum is unchanged
- [ ] No CRM field name collides with the existing OIDC clientId in the generated schemas

**Files**

- `docs/specs/openapi.yaml`
- `frontend/src/api/schema.d.ts`
- `packages/client/src/schema.ts`
- `frontend/src/api/contractBodies.ts`
- `packages/client/src/test/contractBodies.ts`
- `packages/cli/src/commands.ts`
- `packages/cli/src/reads.ts`
- `packages/cli/src/writes.ts`
- `packages/mcp/src/tools.ts`
- `packages/mcp/src/reads.ts`
- `packages/mcp/src/writes.ts`
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

---

## Phase 2 — Schema and crm module

**Goal.** The client and client_contact tables with company_isolation RLS, the FK from project.client_id, the new crm Spring Modulith module with client CRUD and contacts that never become logins, and the project-side clientId mapping plus the project:settings-gated picker.

**Sequential.** One backend lane, strictly ordered: the JPA entities cannot be mapped before the migration creates the tables, and the project picker cannot resolve clients before the Client entity and repository exist; the project tasks also share Project.java and the project DTOs.

### `mc3` · Flyway migration: client, client_contact, and the project.client_id FK

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `data-architect` | `backend` | `data-migrations`, `data-postgresql`, `data-schema-design` | `mc2` |

Re-read backend/src/main/resources/db/migration/ immediately before writing the file and take the actually-next-free V — only V1 and V2 exist now, but the reservation in plan 009 is not a fact and out-of-order is unset, so a lower-numbered migration arriving late is silently skipped and Testcontainers will never show the conflict. Create client and client_contact with company_id UUID NOT NULL leading every composite index and every previously-global uniqueness constraint, both with the standard company_isolation policy under FORCE ROW LEVEL SECURITY. Add the FK from the existing project.client_id column to client(id), keeping the column nullable — a project with no client is not incomplete. Contacts are records, never logins: no credential, no invitation, no link to app_user. Carry the why in comments as V1 and V2 do.

**Corrected 2026-08-30.** Not `V3`. Under the confirmed order Mc lands seventh, after M1, M2, M3, M5 and M4, each of which takes a migration first. Projected `V8` — but the directory, read immediately before writing the file, is the authority.

**Acceptance criteria**

- [ ] The migration file is numbered from a fresh read of the migration directory and the number is recorded in the plan document
- [ ] client and client_contact both carry company_id UUID NOT NULL and a company_isolation policy with FORCE ROW LEVEL SECURITY
- [ ] company_id leads every composite index and every uniqueness constraint on both tables
- [ ] project.client_id gains REFERENCES client(id) and remains nullable
- [ ] client_contact has no credential, invitation or app_user linkage
- [ ] SchemaConventionTest passes
- [ ] ExportTablesTest passes with no edit to ExportTables.java, since the standard policy makes both tables exported by construction

**Files**

- `backend/src/main/resources/db/migration/V8__crm_clients.sql`  *(projected — take the next free V at start time)*

### `mc4` · New crm module: client CRUD and contacts

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `be-java` | `backend` | `be-java-database-code`, `be-java-pattern-guide`, `be-java-unit-testing` | `mc3` |

Create com.tcms.crm following the shape of com.tcms.work — entity, enums, repository, service, controller, view/write DTOs, package-info. Client CRUD gated on the already-seeded client:read/create/edit/delete at COMPANY scope; check permissions, never role names. Every repository interface carries @Transactional(readOnly = true), or derived and @Query methods run with no company set and every RLS-protected read silently returns zero rows. crm is not a foundation module, so it may depend only on api, tenancy, identity, project and storage and must cross to anything else via a CompanyScopedEvent — do not propose a sixth foundation module. Contacts are managed under the client and never become logins. Cover the service logic with JUnit 5 + Mockito unit tests as part of this task.

**Acceptance criteria**

- [ ] com.tcms.crm exists with package-info.java and compiles under -Werror
- [ ] Every repository interface in the module carries @Transactional(readOnly = true)
- [ ] Authorization checks permission keys, never role names, and adds no new keys
- [ ] ArchitectureRulesTest passes: crm depends only on the five foundation modules and every entity field has both a reader and a writer
- [ ] Contact endpoints create no credential, invitation or app_user row
- [ ] Unit tests cover client CRUD and contact management including the company-isolation path
- [ ] The controllers match the shapes landed in openapi.yaml and the contract tests pass

**Files**

- `backend/src/main/java/com/tcms/crm/package-info.java`
- `backend/src/main/java/com/tcms/crm/Client.java`
- `backend/src/main/java/com/tcms/crm/ClientContact.java`
- `backend/src/main/java/com/tcms/crm/ClientRepository.java`
- `backend/src/main/java/com/tcms/crm/ClientContactRepository.java`
- `backend/src/main/java/com/tcms/crm/ClientService.java`
- `backend/src/main/java/com/tcms/crm/ClientController.java`
- `backend/src/main/java/com/tcms/crm/ClientView.java`
- `backend/src/main/java/com/tcms/crm/ClientWriteRequest.java`
- `backend/src/main/java/com/tcms/crm/ClientContactView.java`
- `backend/src/main/java/com/tcms/crm/ClientContactWriteRequest.java`
- `backend/src/test/java/com/tcms/crm/ClientServiceTest.java`
- `backend/src/test/java/com/tcms/crm/ClientEndpointTest.java`

### `mc5` · Project integration: map clientId and serve the picker from project settings

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `be-java` | `backend` | `be-java-database-code`, `be-java-pattern-guide`, `be-java-unit-testing` | `mc4` |

Map client_id on com.tcms.project.Project now that the FK and the client table exist — M0 left it unmapped precisely so this landed with the constraint. Expose the client on the project read shape and accept it on the project write path. Serve the client picker from the project-settings endpoint under project:settings, not client:read: Project Manager is PROJECT-scope so a company-wide client:read would be an inert row for them, and granting client:read to the Company Member baseline would break the one sentence that role is defined by. Reaching the crm module from project would invert the foundation rule, so resolve the picker through the allowed direction or a CompanyScopedEvent-fed projection, whichever the architecture tests permit. NULL stays a valid, non-deficient answer for a company running its own product.

**Acceptance criteria**

- [ ] Project.java maps client_id with both a reader and a writer, and ArchitectureRulesTest's entity sweep passes
- [ ] The project read and write shapes carry the client and match openapi.yaml
- [ ] The client picker is served from the project-settings endpoint and is authorized on project:settings
- [ ] No cross-module dependency from a foundation module into crm; ArchitectureRulesTest passes
- [ ] A project with no client persists and reads back as NULL with no validation error and no deficiency flag
- [ ] Unit tests cover attach, change and detach of a client on a project

**Files**

- `backend/src/main/java/com/tcms/project/Project.java`
- `backend/src/main/java/com/tcms/project/ProjectView.java`
- `backend/src/main/java/com/tcms/project/ProjectWriteRequest.java`
- `backend/src/main/java/com/tcms/project/ProjectService.java`
- `backend/src/main/java/com/tcms/project/ProjectSettingsController.java`
- `backend/src/main/java/com/tcms/project/ProjectSettingsService.java`
- `backend/src/test/java/com/tcms/project/ProjectClientTest.java`

---

## Phase 3 — Frontend

**Goal.** Client screens on the render kit, the Client column and filter on the projects list, the Client field on project creation and edit, and — the acceptance criterion — proven absence of all of it when the company has no clients.

**Sequential.** One ui lane: every task reads the same generated query hooks, and the three of them collectively touch the single keys factory, lib/labels.ts, router.tsx and AppShell.tsx, which plan 009 names as merge-expected shared files.

### `mc6` · Fault switch, clients query module, and labels

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `fe-react` | `ui` | `fe-react-data-fetching`, `fe-react-error-handling`, `fe-react-state-management` | `mc5` |

Build the fault switch before the feature it proves: extend src/lib/devFaults.ts so the client endpoint family can be scoped, hung, 503'd, expired and drifted, keeping both guards a literal import.meta.env.DEV so Rollup drops the module from a production build. Add src/api/queries/clients.ts behind the existing barrel with every read and write hook, an enabled gate, and the per-endpoint-class timeout budget. Extend the single keys factory in keys.ts — never add a second. Put any new client enums into lib/labels.ts as satisfies Record<Union, string> so a new member is a compile error until labeled. Add client fixtures to frontend/src/test/fixtures.ts so every exchange crossing the fetch double is checked against openapi.yaml, anchoring path-keyed mock arms so a sub-resource is not caught by a prefix.

**Acceptance criteria**

- [ ] devFaults.ts scopes faults to the client endpoint family and both guards remain a literal import.meta.env.DEV
- [ ] src/api/queries/clients.ts exports all client and contact hooks with enabled gates and a timeout budget, exported through the barrel
- [ ] keys.ts gains client keys and remains the only keys factory in the codebase
- [ ] New enums in lib/labels.ts use satisfies Record<Union, string>
- [ ] Client fixtures in frontend/src/test/fixtures.ts use anchored path-keyed mock arms
- [ ] npm run build, npm run lint at zero warnings, and npm run test:run all pass

**Files**

- `frontend/src/lib/devFaults.ts`
- `frontend/src/api/queries/clients.ts`
- `frontend/src/api/queries/keys.ts`
- `frontend/src/api/queries/index.ts`
- `frontend/src/lib/labels.ts`
- `frontend/src/test/fixtures.ts`

### `mc7` · Client screens: list, detail, create, and the empty state

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `fe-react` | `ui` | `fe-react-component-writer`, `fe-react-ui-rules`, `fe-react-component-testing`, `fe-react-routing` | `mc6` |

Port the clients list, client detail and client-new screens onto the render kit only — Tailwind utilities as the single authority, no sx, no CSS-in-JS, no second stylesheet, components from src/components/kit and src/components/ui. Use DataRegion for the loading/loaded/failed/empty/stale matrix and Identifier for anything monospace. The clients-empty state is the only trace of the module when a company has no clients, and it exists so the first one can be created. Add the routes to app/router.tsx as company-scoped, outside ProjectGuard, following the /admin/teams precedent, and a rail entry in AppShell.tsx. The client detail page carries the per-client project rollup plus a reserved slot for the hours and invoices M8 will fill. Component tests via Vitest + RTL, using createTestQueryClient only — never build your own QueryClient — and calling renderingIntoAnErrorBoundary / drivingAFailedRead / drivingAFailedWrite where a test trips those paths.

**Unblocked 2026-08-30.** `docs/mockups/` is present and git-tracked in this checkout — `clients.html`, `client.html`, `client-new.html` and `clients-empty.html` all exist. Build against them; they are the source of truth for what the screen shows.

**Acceptance criteria**

- [ ] Clients list, client detail and client-new screens render entirely from the render kit with no sx, no CSS-in-JS and no second stylesheet
- [ ] With zero clients the list renders the empty state and offers creation of the first client
- [ ] Routes are registered outside ProjectGuard and the rail entry appears only where the client permissions allow
- [ ] The client detail page shows the per-client project rollup and a clearly marked slot reserved for M8's hours and invoices
- [ ] Component tests use createTestQueryClient and declare error-boundary and failed-read/write intent explicitly
- [ ] npm run build, npm run lint at zero warnings, and npm run test:run all pass

**Files**

- `frontend/src/features/clients/ClientsPage.tsx`
- `frontend/src/features/clients/ClientsPage.test.tsx`
- `frontend/src/features/clients/ClientPage.tsx`
- `frontend/src/features/clients/ClientPage.test.tsx`
- `frontend/src/features/clients/ClientCreateDialog.tsx`
- `frontend/src/features/clients/ClientContactList.tsx`
- `frontend/src/app/router.tsx`
- `frontend/src/app/AppShell.tsx`

### `mc8` · Client column, filter and field on project surfaces — and their proven absence

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `fe-react` | `ui` | `fe-react-component-writer`, `fe-react-ui-rules`, `fe-react-component-testing`, `fe-react-data-grid` | `mc7` |

Add the Client column and filter to the projects list and the Client field to project creation and project edit, with filter state in the URL via useSearchParams as this codebase requires. Then test the absence, which is the module's real acceptance criterion: with no clients there is no Client column, no Client filter, no Client field, no attach-a-client prompt and nothing marking a project as missing one — a company building its own products is the owner's own case, and a project without a client is not incomplete. The picker reads the project-settings endpoint under project:settings, not client:read. Write the absence as explicit assertions, not as an untested implication of a conditional.

**Unblocked 2026-08-30.** `docs/mockups/settings-general.html` and `projects.html` are present and tracked.

**Acceptance criteria**

- [ ] With at least one client: the projects list shows a Client column and a Client filter whose state lives in the URL, and projects can be attached and detached from the create and edit surfaces
- [ ] With zero clients: tests assert the absence of the Client column, the Client filter, the Client field, any attach-a-client prompt, and any marker rendering a project as missing a client
- [ ] The client picker is fed by the project-settings endpoint and is visible to a PROJECT-scope Project Manager holding project:settings
- [ ] A Company Member holding only company:read sees no client surface anywhere
- [ ] All new UI is on the render kit with no sx and no CSS-in-JS
- [ ] npm run build, npm run lint at zero warnings, and npm run test:run all pass

**Files**

- `frontend/src/features/projects/ProjectsPage.tsx`
- `frontend/src/features/projects/ProjectsPage.test.tsx`
- `frontend/src/features/projects/ProjectCreateDialog.tsx`
- `frontend/src/features/settings/GeneralSettingsPage.tsx`
- `frontend/src/features/settings/GeneralSettingsPage.test.tsx`

---

## Phase 4 — Documentation closure

**Goal.** docs/features.md's replaced section superseded rather than deleted, plan 009's status table moved to done with the V actually taken, the built-and-unverified list written, and the M8 prompt rewritten against the endpoints as they landed.

**Sequential.** A single closure task; nothing to parallelise.

### `mc9` · Close the module docs and the hand-off protocol

| Agent | Lane | Skills | Depends on |
|---|---|---|---|
| `be-documenter` | `docs` | `be-shared-business-documentation`, `ep-verify-before-done`, `be-shared-plain-language-companion` | `mc8` |

Update docs/features.md by striking through the section Mc replaces and marking it superseded rather than deleting it, so the next reader does not think the old reasoning was never considered. Then close per the hand-off protocol: move Mc's row in plan 009's status table to done, correct the V number to the one actually taken if it differs from what the plan document assumed, write the built-and-unverified list honestly — the gates prove it compiles, not that it works — close the review items Mc owns, and rewrite the M8 prompt with the client endpoints and the client page's hours-and-invoices slot as they actually landed. Commit this document in its own commit, staging only this file.

**Acceptance criteria**

- [ ] docs/features.md's replaced section is struck through and marked superseded, not deleted
- [ ] Plan 009's Mc status row reads done with the commit and the V number actually taken
- [ ] A built-and-unverified list is written that distinguishes what the gates proved from what was never exercised
- [ ] The M8 prompt is rewritten naming the client endpoints as landed and the hours-and-invoices slot on the client page
- [ ] The plan document's own closure section is completed
- [ ] No path cited in any comment or doc is missing from disk, so citedFiles.test.ts stays green

**Files**

- `docs/features.md`
- `docs/plans/009-project-management-expansion-modules.md`
- `docs/plans/016-clients.md`

---

## Running a task from here

1. Confirm the branch: `git rev-parse --abbrev-ref HEAD`. Work continues on it — no new branch, no worktree.
2. Start from **Mc's prompt block** in plan 009 for the reading list, then execute the tasks above in order.
   By the time Mc runs, five modules will have rewritten that prompt — trust the prompt, not this file, on what
   endpoints and packages exist.
3. **One subagent at a time.** The lanes above are ownership, not permission to run concurrently.
4. Gates on `shanks`: `~/bin/tcms-remote sync`, then `backend ./gradlew build`, `frontend npm run test:run`,
   `packages npm run build`.
5. Commit at the end of each phase, after its gate passes, staging only that phase's files.
6. `mc9` is the ninth and closure task and carries plan 009's hand-off protocol — including **correcting the V and plan numbers**
   to the ones actually taken, and rewriting M8's prompt with the client endpoints and the hours-and-invoices slot as
   they landed.

