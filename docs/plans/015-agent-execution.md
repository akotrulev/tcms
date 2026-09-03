# M5 — Agent execution Implementation Plan

**Date:** 2026-09-03 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A developer connects their own AI assistant from their profile, flags a task AI-eligible, and the assistant fetches it with its full working context, reports a PR back and moves it to In Review — and is refused Done, with the reason on the issue's history.

**Architecture:** No agent principal, no orchestrator, no runner, no forge adapter, no model credential (scope D25/D26). **An assistant *is* an `api_token` the caller owns carrying the `Agent` role** — the profile surface (`/me/assistants`) is a self-service view over `api_token` filtered to `owner_id = caller ∧ role_id = Agent`, needing no `token:issue`. The API token is the identity; every act already records `actor_user_id` + `actor_token_id`, so attribution reads "*<person>, via <assistant>*" with nothing new built for it. The "refuse Done" gate is the existing `author_may_approve_own_work` rule extended to issue transitions by a new per-project `review_enabled_issue` flag (D33, default `false`), enforced on `POST /issues/{key}/transitions` and mirrored into the caller-scoped `allowedTransitions` projection. `project_repository` is reference data (D16); `issue.pr_url` and `issue.agent_eligible` (D30) are two columns.

**Tech Stack:** Spring Boot 3.5.6 / Java 21 / Postgres 17 + RLS (backend), OpenAPI 3 contract with two generated clients, `@tcms/client`+`@tcms/cli`+`@tcms/mcp` (Node/TS), React 19 + Vite + Tailwind v4 + shadcn/ui + TanStack Query (frontend).

**T1 landed 2026-09-03 @ `26e2c9da`.** Two facts for downstream tasks: (a) making `agentEligible` **required** on `Issue`/`IssueSummary`/`BoardIssueCard` forced fixture updates — `packages/client/src/test/fixtures.ts`, `frontend/src/test/fixtures.ts` now set `agentEligible: false`, `responseCredentials.test.ts` has an `IssuedAssistant.secret` `BY_DECISION` entry, and `shapesContract.test.ts` has an `UNOFFERED['IssueInput.agentEligible']` line T4 must remove (Step 3b). (b) **`npm run build` is tsc-only** — to exercise `schemaFreshness.test.ts` / `contractPairing.test.ts` a contract task must also run `~/bin/tcms-remote packages 'npm run test:run --workspaces'` (or `-w @tcms/client`). Locate contract anchors by content, not line number — the file has drifted.

**T3 landed 2026-09-03.** Facts T4 and T5 need. (a) **`GET /issues` takes `agentEligible` as a
tri-state** — absent, `true`, `false` — because `false` is a filter somebody asks for; the MCP queue
verb combines `agentEligible=true` with `assigneeId=<owner>`. (b) **`agentEligible` is on
`IssueSummary` and `BoardIssueCard` as well as `Issue`**, so the work table and the board card mark
it without a second read; `prUrl` is on `Issue` only. (c) **A board drag into a Done column is
refused the same way** — `BoardMoveService.Outcome.CloseRefused` → 409 from
`POST /boards/{id}/cards/{key}/move`, carried through rather than mapped onto the workflow refusal,
so T5's board must render it as "needs a second person" and not as "the workflow forbids this".
(d) **The refusal detail names the next move**: *"…Move it to In Review instead, and ask somebody to
close it."* — T5 shows the server's sentence rather than composing its own. (e) The
`ProjectRepository` schema is served by **`com.tcms.project.ProjectRepoView`** (the entity is
`ProjectRepo`), and the caller-scoped narrowing landed in **four** assemblies, `work.BoardService`
included. (f) The seeded **`Task` issue type is STORY level** (V3 §592) — the TASK-level type is
`Sub-task`, which is what an assistant's task actually is; a fixture that hangs a "Task" off a story
is a 400.

**Migration version:** **`V8`** — the directory held `V1…V7` on 2026-09-03 (`V7__import.sql` is M4's, landed 2026-09-03 `ba0031bb`). `ls backend/src/main/resources/db/migration/` again before writing T2; `out-of-order` is unset and Testcontainers cannot show a number collision.

**No new permission.** `Agent` already holds `issue:read/create/edit/comment`, `board:read`, `release:read`, `project:read`, `doc:read` (Test Engineer minus `company:read`, V2 §567). `/me/assistants` needs authentication only — an Agent token grants nothing its owner lacks (owner ∩ role intersection), so issuing one is not a privileged act. Managing `project_repository` costs the existing `project:settings`. `review_enabled_issue` is a **setting**, not a permission. So there is **no** roles-spec / `permission-matrix-summary.md` / `README.md` / `tierClaims.test.ts` / `matrixCopies.test.ts` churn in this module.

**Tasks** — in the order they are done:

| # | Task | Deliverable | Depends on |
|---|---|---|---|
| 1 | T1 — the agent-execution contract | `openapi.yaml` carries `/me/assistants`, `/projects/{id}/repositories`, `GET /issues/{key}/agent-context`, `agent_eligible`+`pr_url` on the issue schemas, `reviewEnabledIssue` on settings, and the narrowed `allowedTransitions` is documented; both generated clients regenerated; `contractPairing` green — all in one commit | none |
| 2 | T2 — `V8__agent.sql` | `project_repository` (RLS-forced reference data), `issue.pr_url`, `issue.agent_eligible` (default false), `project_settings.review_enabled_issue` (default false) all exist; `SchemaConventionTest` + the boot-context validate gate green; ERD updated | T1 |
| 3 | T3 — the backend | Assistant tokens issue/list/revoke from `/me/assistants`; `project_repository` CRUD; `agent_eligible`+`pr_url` written and read; `GET /issues/{key}/agent-context` returns the assembled context; a DONE-kind move is refused under the gate with the reason on history and dropped from `allowedTransitions`; a one-person company with the gate off still closes its own work | T2 |
| 4 | T4 — the issue tool surface | `@tcms/mcp` and `@tcms/cli` gain the verbs an assistant drives — list my AI-eligible queue, fetch a task with its context, attach a PR, request a transition — typed inputs, shared envelope, actionable refusals naming the allowed moves; the surface doc lists every new verb; packages build in order, lint 0, tests green | T3 |
| 5 | T5 — the screens + close-out | `profile-assistants`, `assistant-connect`, `settings-repositories`, `story-proposal` ported; the AI-eligible toggle+marker on the task page, work table and board card from one shared component; the "Require review to close issues" toggle on General settings; the help entry; `features.md` rewritten; the 009 status table closed | T4 |

---

## File structure

**Backend (T2, T3)**
- `backend/src/main/resources/db/migration/V8__agent.sql` — the one migration.
- `backend/src/main/java/com/tcms/identity/` — assistant self-service: `MeAssistantsController`, `AssistantService`, `AssistantView`, `AssistantInput`, `IssuedAssistantView`. Reuses `ApiTokenService` / `ApiTokenRepository` under the hood.
- `backend/src/main/java/com/tcms/project/` — `ProjectRepository*` **name is taken** (the JPA repo for `Project` is `com.tcms.project.ProjectRepository`), so the new entity is `ProjectRepo` / table `project_repository`: `ProjectRepo`, `ProjectRepoRepository`, `ProjectRepoView`, `ProjectRepoInput`, `ProjectRepoService`, `ProjectRepoController`. Plus `review_enabled_issue` threaded through `ProjectSettings`, `ProjectSettingsView`, `ProjectSettingsRequest`, `ProjectSettingsService`.
- `backend/src/main/java/com/tcms/tracker/` — `agent_eligible` + `pr_url` on `Issue`/`IssueInput`/`IssueUpdate`/`IssueView`/`IssueSummaryView`; the `review_enabled_issue` gate in `TransitionService`; the caller-scoped `allowedTransitions` narrowing in `IssueService`; `AgentContextController` + `AgentContextService` + `AgentTaskContextView` (reads `project` for repositories via a directory, never importing it beyond the five foundation modules — `project` is a foundation module, so a direct dependency is legal).

**Shared packages (T4)**
- `packages/client/src/core/shapes.ts` — extend `ISSUE_SHAPE` with the `attach-pr` arm and `agentEligible`.
- `packages/mcp/src/reads.ts` — `tcms_read_issue` gains a `queue`/`context` mode; `packages/mcp/src/writes.ts` — `tcms_write_issue` gains `attach-pr`.
- `packages/cli/src/reads.ts` / `writes.ts` — the same verbs on the CLI.
- `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` — the new arms recorded.

**Frontend (T5)**
- `frontend/src/features/account/AssistantsPage.tsx` (+ `.test.tsx`), `ConnectAssistantPage.tsx` (+ test) — `profile-assistants.html`, `assistant-connect.html`.
- `frontend/src/features/settings/RepositoriesSettingsPage.tsx` (+ test) — `settings-repositories.html`; `GeneralSettingsPage.tsx` gains the review-to-close toggle.
- `frontend/src/features/tracker/AiEligibleMarker.tsx` (+ test), `TaskPanels.tsx` (the four assistant panels), `ProposalReviewPage.tsx` (+ test) — `story-proposal.html`; the marker on `WorkTablePage` and the board card.
- `frontend/src/api/queries/account.ts` (new) + `keys.ts`; `frontend/src/api/queries/projectRepos.ts` (new); `agentEligible` into `frontend/src/lib/labels.ts` is **not** needed (it is a boolean, not an enum) — the marker copy lives in the component.

---

## Decisions this plan takes (mockup vs. behaviour)

Recorded per the module-prompt's precedence rule (*mockup wins on what a screen shows; plan+spec win on what the system does*):

1. **An assistant is an `Agent`-role `api_token`, self-issued from `/me/assistants` with no `token:issue`.** The admin `/api-tokens` surface is unchanged and untouched. The profile screen lists only the caller's own Agent tokens. (D26; `profile-assistants.html`, `assistant-connect.html`.)
2. **The "refuse Done" gate is `review_enabled_issue` + `author_may_approve_own_work`, a person-level rule the token inherits.** A transition whose target is a `DONE`-kind status is refused when **(a)** the project's `review_enabled_issue` is `true`, **(b)** `author_may_approve_own_work` is `false`, and **(c)** the caller is the issue's **assignee** (the party who did the work; a token acts as its owner, so an assistant working its owner's assigned task is refused exactly as its owner would be). There is **no** token-specific rule — with the gate off the same move succeeds, which is `task.html`'s own "on a project with the review gate off, that same move would have been allowed" and A6. This is *not* the full review module (no `review` row is opened for an issue, no reviewer assignment, no verdict) — that surface has no mockup in M5 and no task in the breakdown; it is the self-approval guard on the terminal transition, mirroring the existing `author_may_approve_own_work` semantics. **Recorded as a scope decision**, not a silent omission: D10 §3 named the full review machinery as "the gate", and this narrows it to the terminal-transition self-approval guard because that is what the Done-when criteria and the mockups require and all the breakdown allots.
3. **"Author" for the gate = the issue's `assignee_id`.** Unassigned ⇒ the guard does not bite (nobody is the author). This matches "closing an issue *it worked on*".
4. **The fetch payload is one composite endpoint `GET /issues/{key}/agent-context`**, justified by §3.1's grounding argument (an agent that must assemble context from six calls generates blind). It assembles the task's `Issue` (which already carries `release`, `parentKey/Title`, `epicKey/Title`, `allowedTransitions`), the **parent story's acceptance criteria**, the **epic's goal** (its description), the **linked spec excerpts** (from doc-links), and the **project's repositories** (`project_repository`). The MCP/CLI "fetch a task" verb is one call to it.
5. **`pr_url` is a field on `IssueUpdate` (PATCH)**, recorded with a distinct `issue.pr_url_set` audit action so "attached PR #482" appears on history. No new endpoint; `issue:edit` already gates PATCH. (`task.html` "attached PR #482".)
6. **`project_repository` primary** is the row with lowest `position`; the view computes `primary: boolean`. No stored flag, no partial unique index. (`settings-repositories.html` "primary" badge.)

---

### T1 — the agent-execution contract

**Deliverable:** `openapi.yaml` carries every M5 endpoint and schema below, each object with its `required` array (including inline nested objects); `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` are regenerated from it; `packages/client/src/test/contractPairing.test.ts` and both `schemaFreshness` checks pass. One commit.
**Depends on:** none

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Regenerate: `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts`
- Verify only (no edit expected — the shared region is schema-driven machinery, not per-endpoint fixtures): `frontend/src/api/contractBodies.ts`, `packages/client/src/test/contractBodies.ts`

- [ ] **Step 1: Add the assistant schemas** under `components.schemas` in `docs/specs/openapi.yaml`.

```yaml
    Assistant:
      type: object
      description: |
        A connected AI assistant — **an `api_token` the caller owns carrying the `Agent` role**
        (D26). There is no agent principal and no separate table; this is the self-service view
        over the caller's own Agent-role tokens that `profile-assistants.html` renders. A revoked
        assistant stays on the wire (its acts remain in each issue's history, attributed to the
        owner via it) — `revokedAt` is set rather than the row filtered out.
      required: [id, name, projectId, projectKey, lastUsedAt, revokedAt, createdAt]
      properties:
        id: { type: string, format: uuid }
        name: { type: string, example: claude-code, description: 'Appears in every history line: "<person>, via <name>".' }
        projectId:
          type: string
          format: uuid
          nullable: true
          description: The one project it is scoped to, or null for every project the owner can reach.
        projectKey:
          type: string
          nullable: true
          description: The scoped project's key, for display. Null when scoped to every project.
        lastUsedAt:
          type: string
          format: date-time
          nullable: true
          description: When it last did anything, coarse to the hour (`api_token.last_used_at`). Null = never used.
        revokedAt: { type: string, format: date-time, nullable: true }
        createdAt: { type: string, format: date-time }

    AssistantInput:
      type: object
      description: |
        Connecting an assistant. **No `role`, no `owner`, no `expiresAt`** — the role is `Agent`,
        the owner is the caller, and the expiry is set server-side (an Agent token must expire).
        Nothing here can grant it more than the caller holds.
      required: [name]
      properties:
        name: { type: string, minLength: 1, maxLength: 120 }
        projectId:
          type: string
          format: uuid
          nullable: true
          description: Scope it to one project, or null for every project the caller can reach.

    IssuedAssistant:
      type: object
      description: |
        The creation response, and **the only place the token secret ever appears**. Shown once,
        unrecoverable afterwards — a separate type from `Assistant` so no read path is one forgotten
        null away from serving it (`IssuedApiTokenView`'s reasoning).
      required: [assistant, secret]
      properties:
        assistant: { $ref: '#/components/schemas/Assistant' }
        secret:
          type: string
          description: The plaintext bearer token, shown once. Never logged, never in an audit row.
```

- [ ] **Step 2: Add the repository schemas.**

```yaml
    ProjectRepository:
      type: object
      description: |
        One repository a project spans, with how it is built — **reference data (D16)**. This
        product never holds a repository, clones one or runs a build; this is what a new joiner or
        somebody's assistant is told to check out. A project with none is not degraded.
      required: [id, name, url, defaultBranch, subdirectory, buildCommand, position, primary]
      properties:
        id: { type: string, format: uuid }
        name: { type: string, example: acme-storefront }
        url: { type: string, example: 'git@github.com:northwind/acme-storefront.git' }
        defaultBranch: { type: string, example: main }
        subdirectory: { type: string, example: /, description: 'The working subdirectory within the repo. `/` for the root.' }
        buildCommand: { type: string, example: 'npm ci && npm run build && npm test' }
        position: { type: integer, description: Order within the project, from 0. }
        primary: { type: boolean, description: 'True for the lowest-position row; computed, not stored.' }

    ProjectRepositoryInput:
      type: object
      required: [name, url, defaultBranch, subdirectory, buildCommand]
      properties:
        name: { type: string, minLength: 1, maxLength: 200 }
        url: { type: string, minLength: 1, maxLength: 500 }
        defaultBranch: { type: string, minLength: 1, maxLength: 200 }
        subdirectory: { type: string, minLength: 1, maxLength: 500, description: '`/` for the repository root.' }
        buildCommand: { type: string, minLength: 1, maxLength: 1000 }
```

- [ ] **Step 3: Add the agent-context schema.**

```yaml
    AgentTaskContext:
      type: object
      description: |
        Everything an assistant needs to work one task, assembled in one read (§3.1's grounding
        rule — an agent that assembles context from six calls generates blind). The `issue` already
        carries the target release, the parent/epic keys and the caller-scoped `allowedTransitions`;
        this adds the parent story's acceptance criteria, the epic goal, the linked spec excerpts and
        the project's repositories and build commands.
      required: [issue, specExcerpts, repositories]
      properties:
        issue: { $ref: '#/components/schemas/Issue' }
        story:
          allOf: [{ $ref: '#/components/schemas/AgentStoryContext' }]
          nullable: true
          description: The parent story, or null for a task with no story parent.
        epic:
          allOf: [{ $ref: '#/components/schemas/AgentEpicContext' }]
          nullable: true
        specExcerpts:
          type: array
          items: { $ref: '#/components/schemas/AgentSpecExcerpt' }
        repositories:
          type: array
          items: { $ref: '#/components/schemas/ProjectRepository' }

    AgentStoryContext:
      type: object
      required: [key, title, acceptanceCriteria]
      properties:
        key: { type: string, example: ACME-118 }
        title: { type: string }
        description: { type: string, nullable: true }
        acceptanceCriteria:
          type: array
          items:
            type: object
            required: [text, done]
            properties:
              text: { type: string }
              done: { type: boolean }

    AgentEpicContext:
      type: object
      required: [key, title]
      properties:
        key: { type: string, example: ACME-90 }
        title: { type: string }
        goal: { type: string, nullable: true, description: The epic's description — its goal. }

    AgentSpecExcerpt:
      type: object
      required: [title, excerpt]
      properties:
        pageId: { type: string, format: uuid, nullable: true }
        title: { type: string, example: 'Checkout specification §3.2' }
        excerpt: { type: string }
```

- [ ] **Step 4: Add `agentEligible` and `prUrl` to the issue schemas.** In `Issue` (line ~17110): add `agentEligible` to `required`, and these properties:

```yaml
        agentEligible:
          type: boolean
          description: |
            D30 — a hint that this issue is a good candidate for an LLM. **A boolean and a filter,
            not an assignment target** (D26 unchanged): the assistant's queue defaults to the
            owner's AI-eligible tasks. Shown as a marker on cards and lists.
        prUrl:
          type: string
          nullable: true
          description: |
            The pull request an assistant (or a person) opened for this task, reported back through
            `PATCH`. This product never opens the PR — the local agent does, with the developer's
            own git, and reports the URL (D25).
```

In `IssueSummary` (line ~17083) and `BoardIssueCard` (line ~17521): add `agentEligible: { type: boolean }` to `properties` and to `required` (the marker on the work table and the board). In `IssueInput` (line ~17186): add `agentEligible: { type: boolean, default: false, description: 'D30. Defaults to false.' }` (optional). In `IssueUpdate` (line ~17244): add `agentEligible: { type: boolean }` and `prUrl: { type: string, nullable: true, description: 'The PR an assistant opened, reported back. Recorded on history as issue.pr_url_set.' }` (both optional).

- [ ] **Step 5: Add `reviewEnabledIssue` to the settings schemas.** In `ProjectSettings` (line ~/ProjectSettings:/): add `reviewEnabledIssue` to `required` and:

```yaml
        reviewEnabledIssue:
          type: boolean
          description: |
            D33 — "Require review to close issues". **Off by default.** When true, and with
            `authorMayApproveOwnWork` false, an issue's **assignee** may not move it into a
            `DONE`-kind status — a second person must. When false the assignee closes their own
            work (A6), which is why the default is off: a one-person company must not dead-end.
            The status ladder is unaffected either way.
```

In `ProjectSettingsUpdate` (line 10580): add `reviewEnabledIssue: { type: boolean, default: false }` to `properties` (it collapses like the other opt-ins — absent means off).

- [ ] **Step 6: Add the paths.** Under `paths`:

```yaml
  /me/assistants:
    get:
      tags: [identity]
      summary: The caller's own connected assistants
      operationId: listMyAssistants
      description: |
        The caller's own `Agent`-role tokens (D26). **Needs authentication only** — an assistant
        grants nothing its owner lacks, so issuing or listing one is not privileged. Distinct from
        `GET /api-tokens`, which administers the whole company's credentials and costs `token:issue`.
      responses:
        '200':
          description: The caller's assistants, newest first.
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/Assistant' } }
        '401': { $ref: '#/components/responses/Unauthorized' }
    post:
      tags: [identity]
      summary: Connect an assistant
      operationId: connectAssistant
      description: |
        Issues an `Agent`-role token owned by the caller, scoped to one project or all, expiring
        server-side. Returns the secret **once**.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/AssistantInput' }
      responses:
        '201':
          description: Connected. The secret is in the body and nowhere else, ever again.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/IssuedAssistant' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
  /me/assistants/{assistantId}:
    parameters:
      - { name: assistantId, in: path, required: true, schema: { type: string, format: uuid } }
    delete:
      tags: [identity]
      summary: Revoke one of the caller's assistants
      operationId: revokeAssistant
      description: |
        Immediate, and it does not undo anything the assistant already did — those acts stay in each
        issue's history, attributed to the owner via it. Revoking one the caller does not own is a 404.
      responses:
        '204': { description: Revoked (idempotent). }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }

  /projects/{projectId}/repositories:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [project]
      summary: A project's repositories
      operationId: listProjectRepositories
      description: '`project:read` — reference data (D16). A project with none returns `[]`.'
      responses:
        '200':
          description: The repositories, by position.
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/ProjectRepository' } }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    post:
      tags: [project]
      summary: Add a repository
      operationId: addProjectRepository
      description: '`project:settings`. Appended at the next position.'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ProjectRepositoryInput' }
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ProjectRepository' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
  /projects/{projectId}/repositories/{repositoryId}:
    parameters:
      - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
      - { name: repositoryId, in: path, required: true, schema: { type: string, format: uuid } }
    patch:
      tags: [project]
      summary: Edit a repository
      operationId: updateProjectRepository
      description: '`project:settings`. A full replacement of the five values.'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ProjectRepositoryInput' }
      responses:
        '200':
          description: Updated
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ProjectRepository' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    delete:
      tags: [project]
      summary: Remove a repository
      operationId: deleteProjectRepository
      description: '`project:settings`. Nothing references a repository row, so a delete is clean.'
      responses:
        '204': { description: Removed (idempotent). }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /issues/{key}/agent-context:
    parameters:
      - { name: key, in: path, required: true, schema: { type: string } }
    get:
      tags: [tracker]
      summary: One task with everything needed to work it
      operationId: getAgentTaskContext
      description: |
        `issue:read`. The composite an assistant fetches to work a task — the issue (with its
        caller-scoped `allowedTransitions`), the parent story and its acceptance criteria, the epic
        goal, the linked spec excerpts, and the project's repositories and build commands. A token
        scoped to another project cannot reach it — the same scope that returns nothing from
        `GET /issues`.
      responses:
        '200':
          description: The task and its working context.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AgentTaskContext' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

- [ ] **Step 7: Add the `agentEligible` filter to `GET /issues`.** In the `/issues` `get.parameters` array (line ~8409), add:

```yaml
        - { name: agentEligible, in: query, schema: { type: boolean }, description: 'D30 — only AI-eligible issues. The assistant queue tool combines it with assigneeId=owner.' }
```

- [ ] **Step 8: Document the `allowedTransitions` narrowing.** Edit the `allowedTransitions` description in `Issue` (line ~17179) and `BoardIssueCard`/`BoardCard` to append:

```
            **Caller-scoped under the review-to-close gate:** when `reviewEnabledIssue` is on,
            `authorMayApproveOwnWork` is off and the caller is this issue's assignee, `DONE`-kind
            targets are absent here — the move exists in the workflow but not for this caller, and
            `POST …/transitions` refuses it with the reason (`task.html`: "needs a reviewer").
```

- [ ] **Step 9: Regenerate both clients and verify pairing.**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

Run: `~/bin/tcms-remote sync && ~/bin/tcms-remote packages 'npm run build'` (client typechecks the regenerated schema; `schemaFreshness.test.ts` and `contractPairing.test.ts` must be green). Expected: PASS. The `contractBodies.ts` shared region is the schema-walking validator — it reads `openapi` shapes generically and needs no per-endpoint edit; if `contractPairing` flags a divergence, the two copies drifted for an unrelated reason and both must be reconciled.

- [ ] **Step 10: Commit.**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts
git commit -m "feat(agent): the M5 contract — assistants, repositories, agent-context, agent_eligible, pr_url"
```

---

### T2 — `V8__agent.sql`

**Deliverable:** `project_repository` exists as an RLS-forced, company-isolated reference table; `issue.pr_url` (nullable) and `issue.agent_eligible` (NOT NULL DEFAULT false) exist; `project_settings.review_enabled_issue` (NOT NULL DEFAULT false) exists; a database built from `V1…V8` boots the Spring context (`ddl-auto: validate`) and `SchemaConventionTest` is green.
**Depends on:** T1 (the entities T3 writes bind to these columns; the migration lands first)

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__agent.sql`
- Modify: `backend/src/test/java/com/tcms/schema/` (only if `SchemaConventionTest` needs the new table acknowledged — it does not, `project_repository` is company-owned), `docs/specs/2026-07-30-tcms-erd.md`

- [ ] **Step 1: Confirm the version.** `ls backend/src/main/resources/db/migration/` — expect `V1…V7`. If a `V8` already exists, STOP: the number moved again; take the next free one and correct this plan's header and the 009 table before writing a line.

- [ ] **Step 2: Write the migration.** `project_repository` follows the `V7__import.sql` template exactly (company_id first, RLS FORCE, byte-identical isolation predicate, `tcms_seed_project_defaults` deliberately not touched — a project with no repositories is normal, so nothing is seeded).

```sql
-- =============================================================================
-- V8 — agent execution (plan 009 module M5, plan 015)
--
-- THE VERSION IS V8, AND THE DIRECTORY IS WHAT SAYS SO. The 009 table reserved
-- V8 for M5 on the assumption M4 took V7 — which it did (2026-09-03, plan 014),
-- so this is genuinely V8. `out-of-order` is unset in application.yml, so a
-- reused number is SILENTLY not applied and Testcontainers builds every test
-- database from empty and cannot show the conflict. Re-read on 2026-09-03:
-- V1..V7, V7 being M4's. This is V8.
--
-- One reference table (project_repository, D16), two columns on issue
-- (agent_eligible per D30, pr_url), and one on project_settings
-- (review_enabled_issue per D33). NO agent principal, NO orchestrator table, NO
-- runner, NO forge adapter, NO model-credential column (D25, D26): an assistant
-- is an api_token the caller owns carrying the Agent role, which V1 already has.
-- =============================================================================

-- 1. REPOSITORIES — reference data (D16). A project spans many; each has its own
--    build command. The server knows WHICH repositories; the local MCP config
--    knows WHERE they are checked out (D25's amendment). No local path here.
CREATE TABLE project_repository (
    id             UUID        PRIMARY KEY,
    company_id     UUID        NOT NULL REFERENCES company(id),
    project_id     UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name           TEXT        NOT NULL,
    url            TEXT        NOT NULL,
    default_branch TEXT        NOT NULL,
    subdirectory   TEXT        NOT NULL DEFAULT '/',
    build_command  TEXT        NOT NULL,
    position       INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     UUID,
    CONSTRAINT uq_project_repository_name UNIQUE (company_id, project_id, name)
);
COMMENT ON TABLE project_repository IS
    'Reference data (D16): the repositories a project spans and how each is built. This product '
    'never holds, clones or builds a repository — this is what a new joiner or an assistant is '
    'told to check out. A project with none is normal, so tcms_seed_project_defaults seeds none. '
    'THERE IS NO LOCAL PATH COLUMN: the checkout path is machine-specific and lives in the '
    'developer''s MCP config, not here (D25). THERE IS NO CREDENTIAL COLUMN: this product opens '
    'no PR and clones nothing.';
COMMENT ON COLUMN project_repository.position IS
    'Order within the project, from 0. The lowest-position row renders as "primary" '
    '(settings-repositories.html); primary is computed, not stored — a second boolean would be a '
    'second source of truth for the same fact.';
COMMENT ON COLUMN project_repository.build_command IS
    'The verify command for THIS repository (D16). One command per repository, never one per '
    'project: TCMS is itself three stacks with three gates and no root build, and a single '
    'wrapper would say "the build broke" rather than which stack.';

CREATE INDEX ix_project_repository_project
    ON project_repository (company_id, project_id, position);

-- 2. ISSUE — two columns. agent_eligible defaults false on every existing issue.
ALTER TABLE issue ADD COLUMN agent_eligible BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE issue ADD COLUMN pr_url TEXT;
COMMENT ON COLUMN issue.agent_eligible IS
    'D30 — a hint that this issue is a good candidate for an LLM, and the default filter of the '
    'assistant''s queue. A boolean and a filter, NOT an assignment target (D26): assignment stays '
    'person to person.';
COMMENT ON COLUMN issue.pr_url IS
    'The pull request an assistant (or a person) opened, reported back through PATCH. This product '
    'opens no PR — the local agent does, with the developer''s own git (D25). Recorded on history '
    'as issue.pr_url_set when it changes.';

-- 3. PROJECT_SETTINGS — the review-to-close gate. Off by default (D33) so a
--    one-person company can still close its own work (A6); a lead turns it on.
ALTER TABLE project_settings
    ADD COLUMN review_enabled_issue BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN project_settings.review_enabled_issue IS
    'D33 — "Require review to close issues". OFF BY DEFAULT. When true and '
    'author_may_approve_own_work is false, an issue''s assignee may not move it into a DONE-kind '
    'status; a second person must. Off by default because the opposite would dead-end a one-person '
    'company the moment its own assistant worked a task (A6).';

-- 4. ROW LEVEL SECURITY — one more forced policy, predicate byte-identical to V1
--    (SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares it,
--    and fails equally on an explicit WITH CHECK — a USING-only policy reuses
--    USING as the insert check, so read boundary IS write boundary).
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['project_repository'];
BEGIN
    FOREACH t IN ARRAY protected LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = t) THEN
            RAISE EXCEPTION 'the RLS list names a table that does not exist: %', t;
        END IF;
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)', t);
    END LOOP;

    IF array_length(protected, 1) <> 1 THEN
        RAISE EXCEPTION 'expected 1 new company-isolated table, found %', array_length(protected, 1);
    END IF;
END $$;

-- issue and project_settings already carry their V1 policies; a column added to a
-- protected table is protected by it. tcms_seed_project_defaults is NOT touched:
-- a project with no repositories is the normal, opt-in case (D16).

-- 5. NO explicit GRANT. This repo sets ALTER DEFAULT PRIVILEGES FOR ROLE
--    tcms_owner before Flyway runs (deploy/postgres/initdb/10-tcms-roles.sql and
--    the test tcms-roles.sql), so tcms_app gets DML on every new public table
--    automatically — V5/V6/V7 add tables and grant nothing. A comment states the
--    mechanism; there is no GRANT statement.
```

**Landed as `4326debe`** — `T2` corrections folded in: (a) the literal `GRANT` above was WRONG for this repo (default privileges do it; V5/V6/V7 grant nothing) — omitted, replaced by the comment shown. (b) `SchemaConventionTest` **did** need an edit after all — `everyIsolationPolicyCarriesTheSamePredicate` pins the total forced-policy count, so it moved `91 → 92` (`backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java`); any future company-owned table bumps it again. (c) **Trap for T3:** `SchemaCommentTest.everyQualifiedReferenceNamesALiveColumn` parses any `word.word` in a SQL comment as `table.column`, so an audit-action string like `issue.pr_url_set` in a *migration comment* fails (`issue` is a table, `pr_url_set` is not a column). The audit-action strings `issue.pr_url_set` / `issue.close_refused` / `issue.agent_eligibility_set` are fine **in Java**; never write one in a SQL comment.

- [ ] **Step 3: Update the ERD.** In `docs/specs/2026-07-30-tcms-erd.md`, add `project_repository` (company-owned, the `company_id` rule applies) with its columns and the "no path, no credential" note; add `issue.agent_eligible`, `issue.pr_url`, `project_settings.review_enabled_issue`.

- [ ] **Step 4: Run the schema gate on shanks.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest' --tests 'com.tcms.schema.*'"
```

Expected: PASS — the new table has `company_id` leading, a forced policy with the exact predicate, and boots under `validate`. (A `BUILD FAILED` with `Could not write XML test results` and zero assertion failures is the fd-limit problem, not a red suite — re-run with the `ulimit`.)

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/resources/db/migration/V8__agent.sql docs/specs/2026-07-30-tcms-erd.md
git commit -m "feat(agent): V8 — project_repository, issue.agent_eligible, issue.pr_url, review_enabled_issue"
```

---

### T3 — the backend

**Deliverable:** every backend Done-when criterion holds: (1) a scoped assistant requesting another project's tasks receives nothing from it (asserted as an absence); (2) an assistant's move of an assigned task into a DONE-kind status under the gate is refused with a reason, and the refusal is on the issue's history; (3) attribution reads "<person>, via <assistant>" on every action; (4) a one-person company with `review_enabled_issue` false still closes its own work; (5) `GET /issues/{key}/agent-context` returns the task with its story AC, epic goal, release (null until M3), spec excerpts and build command; (6) `/me/assistants` issues/lists/revokes the caller's Agent tokens; (7) `agent_eligible` and `pr_url` round-trip.
**Depends on:** T2

This is one `be` task. **Budget:** `CLAUDE.md` (~8k) + this section (~7k) + patterns to read (`ApiTokenService`/`Controller` ~7k, `IssueService` ~10k, `TransitionService` ~3k, `ProjectSettings*` ~5k, one RLS isolation test ~4k) + files written (~16k) + build read-back (~12k) ≈ **~72k**, under the 150k ceiling — one session. If it approaches the ceiling, split at the identity/tracker seam (assistants+repositories vs. the transition gate+agent-context), not by capability.

**Files** (create unless noted):
- `identity/MeAssistantsController.java`, `AssistantService.java`, `AssistantView.java`, `AssistantInput.java`, `IssuedAssistantView.java`
- `project/ProjectRepo.java`, `ProjectRepoRepository.java`, `ProjectRepoView.java`, `ProjectRepoInput.java`, `ProjectRepoService.java`, `ProjectRepoController.java`
- Modify `project/ProjectSettings.java`, `ProjectSettingsView.java`, `ProjectSettingsRequest.java`, `ProjectSettingsService.java` (thread `reviewEnabledIssue`)
- Modify `tracker/Issue.java`, `IssueInput.java`, `IssueUpdate.java`, `IssueView.java`, `IssueSummaryView.java`, `IssueService.java`, `IssueController.java` (`agentEligible`, `prUrl`, the `agentEligible` list filter, the caller-scoped `allowedTransitions`)
- Modify `tracker/TransitionService.java` (the `review_enabled_issue` gate + refusal audit)
- `tracker/agent/AgentContextController.java`, `AgentContextService.java`, `AgentTaskContextView.java`, `AgentStoryContextView.java`, `AgentEpicContextView.java`, `AgentSpecExcerptView.java`
- Tests: `tracker/AgentCloseRefusalTest.java`, `tracker/AgentContextScopeTest.java`, `identity/MeAssistantsTest.java`, `project/ProjectRepoTest.java` (each boots the real context — these are `qa`-flavoured integration tests, written here because the deliverable is behavioural; keep them in the same commit as the code they prove)

- [x] **Step 1 (assistants): write the failing scope test.** `identity/MeAssistantsTest.java` — a Test Engineer POSTs `/me/assistants {name:"claude-code", projectId: <acme>}`, gets a 201 with a secret; `GET /me/assistants` lists exactly it; a DELETE revokes it (204) and a second DELETE is still 204 (idempotent); a DELETE of another user's assistant is 404. Assert the issued token authenticates and its `role` resolves to `Agent`, scoped to Acme. Expected: FAIL (no controller).

- [x] **Step 2 (assistants): implement.** `AssistantService` delegates to the existing token machinery — it issues an `ApiTokenIssueRequest(name, Role.AGENT_ID, projectId, now.plus(90 days))` through `ApiTokenService` (which already validates the Agent-token-must-expire rule), and lists via `ApiTokenRepository` filtered to `owner_id = caller ∧ role_id = Role.AGENT_ID ∧ revoked_at IS NULL`-or-recently-revoked. `AssistantView` maps `ApiToken` → `{id, name, projectId, projectKey (resolved), lastUsedAt, revokedAt, createdAt}`. `MeAssistantsController` at `/api/v1/me/assistants`, caller = `jwt` owner, no permission check beyond authentication (the whole point — see the class comment mirroring `AssistantInput`'s contract note). Revoke calls `ApiTokenService.revoke` **only if the token's `owner_id` is the caller**, else 404.

```java
// AssistantService.connect — the role and expiry are not the caller's to choose.
IssuedApiTokenView issued = tokens.issue(
        callerId,
        new ApiTokenIssueRequest(input.name(), Role.AGENT_ID, input.projectId(),
                clock.instant().plus(Duration.ofDays(90))));
return new IssuedAssistantView(AssistantView.of(issued.token(), projectKeyOf(issued.token())),
        issued.secret());
```

Run the Step 1 test on shanks. Expected: PASS.

- [x] **Step 3 (repositories): failing test then implement.** `project/ProjectRepoTest.java` — `project:settings` holder POSTs two repos, `GET` lists them by position with the first marked `primary:true`; a PATCH replaces the five values; a DELETE removes one; a caller with only `project:read` can GET but a POST is 403; a repo name duplicate is a 400. Then implement `ProjectRepo` (entity, `@Filter` company filter like other entities), `ProjectRepoRepository extends` a `@Transactional(readOnly = true)` interface (the RLS rule — derived methods otherwise run with no company set), `ProjectRepoService` (permission-gated through `ProjectService.writeGranted`/`readGranted` with `project:settings`/`project:read`; assigns `position = max+1` on create; `ProjectRepoView.of` computes `primary = (position == min)`), `ProjectRepoController` at `/api/v1/projects/{projectId}/repositories`. Run on shanks. Expected: PASS.

- [x] **Step 4 (settings gate flag): thread `reviewEnabledIssue`.** Add the boolean to `ProjectSettings` (field + getter, `@Column(name="review_enabled_issue")`), `ProjectSettingsView` (record component, mapping), `ProjectSettingsRequest` (`Boolean reviewEnabledIssue`, collapsing to false like the other opt-ins), `ProjectSettingsService.update` (persist it). **Also add it to `ReviewSettings`** — a **plain field beside `authorMayApproveOwnWork`, NOT a member of `ReviewSubjectType` and NOT routed through `reviewEnabledFor`** (adding `ISSUE` to that enum would drag in the full review-module apparatus — a ladder, a migration, the reviewer flow — which decision 2 explicitly scopes out; `review_enabled_issue` is a self-approval guard on the terminal transition, like the gating flags `ReviewSettings`'s own header keeps out of `reviewEnabledFor`). Thread the new field through `ProjectSettings.toSettings()` and `ProjectSettings.defaults()` (default `false`) and every `ReviewSettings(...)` construction site the compiler flags. No new test file — extend `ProjectSettingsControllerTest`/`...ServiceTest` if present to assert the round-trip and the false-default.

- [x] **Step 5 (agent_eligible + pr_url on issues): thread the columns.** Add `agentEligible`/`prUrl` to `Issue` (fields, getters, and a `setAgentEligible`/`setPrUrl` used by the service), `IssueView` and `IssueSummaryView` (record components + `viewOf`/summary mapping), `IssueInput` (create reads `agentEligible`, default false), `IssueUpdate` (edit applies `agentEligible` and `prUrl` when present). In `IssueService`: on create, set `agent_eligible`; on update, when `prUrl` changes, record an `AuditEvent` with action `issue.pr_url_set` (so history shows "attached PR"); when `agentEligible` changes, action `issue.agent_eligibility_set`. Add the `agentEligible` filter to the `GET /issues` query in `IssueController`/`IssueService` (a `WHERE agent_eligible = :flag` term when the param is present).

- [x] **Step 6 (the refusal): write the failing close-refusal test.** `tracker/AgentCloseRefusalTest.java`:
  - Seed a project, turn `review_enabled_issue` on, `author_may_approve_own_work` off (the default). Assign a Task to Dana. Issue Dana an Agent token scoped to the project. Acting **through that token** (open `TokenContext` with Dana as owner), `POST /issues/{key}/transitions` targeting the `Done` status → expect **409** with a `detail` naming the reason ("closing an issue you are the assignee of requires a second person / review to close is on"). Assert an `AuditEvent` with action `issue.close_refused` exists for the issue (the refusal is on history).
  - Assert `GET /issues/{key}` for that caller has **no** `DONE`-kind status in `allowedTransitions`, but In Review/Blocked are present.
  - **A6:** with `review_enabled_issue` OFF, the same token moves the same task to Done → **200**, `doneAt` set. This is the one-person-company path.
  - A **second person** (a PM, not the assignee) moving it to Done under the gate → **200** (a second person may close).

  Expected: FAIL (gate not implemented).

- [x] **Step 7 (the refusal): implement in `TransitionService`.** Add a `DONE`-kind guard before the workflow allow-list check. The rule (decision 2 above): refuse when `settings.reviewEnabledIssue() && !settings.authorMayApproveOwnWork() && callerIsAssignee(issue, userId) && target.kind == DONE`. `callerIsAssignee` compares `issue.getAssigneeId()` to `userId` (the token's owner *is* the caller identity — `TokenContext` makes the caller the owner, so no token-specific branch is needed). Return a new `Outcome.CloseRefused(reason)` the controller maps to 409, and record the `issue.close_refused` audit event inside the same read (it is a refusal, so nothing else is written). Read settings via `ProjectSettingsService.effective(projectId)` → `ReviewSettings` (a `project`-module *value* read; `tracker` already depends on `project`, and `ReviewSettings` is the boundary-crossing value the entity resolves to — never the managed entity). Inject `ProjectSettingsService` into `TransitionService`.

```java
if (target.get().getKind() == IssueStatusKind.DONE) {
    ReviewSettings s = settings.effective(project.getId());   // projectId-only value read; no row ⇒ defaults
    boolean gated = s.reviewEnabledIssue() && !s.authorMayApproveOwnWork();
    boolean callerIsAuthor = userId.equals(issue.getAssigneeId());
    if (gated && callerIsAuthor) {
        auditEvents.save(AuditEvent.record(project.getId(), userId, "issue.close_refused",
                "issue", List.of(issue.getId()),
                "{\"key\":" + AuditEvent.jsonString(issue.getIssueKey())
                    + ",\"to\":" + AuditEvent.jsonString(target.get().getName())
                    + ",\"reason\":\"review to close is on and you are the assignee\"}",
                clock.instant()));
        return new Outcome.CloseRefused(
                "Closing an issue you are the assignee of requires a second person while "
                + "\"Require review to close issues\" is on. Move it to In Review instead.");
    }
}
```

Map `CloseRefused` to 409 in `TransitionController` (the sealed `Outcome` keeps the mapping exhaustive). Run the Step 6 test on shanks. Expected: PASS.

- [x] **Step 8 (the narrowed projection): narrow `allowedTransitions` for the caller.** In `IssueService.viewOf` (and the summary/board projections that carry `allowedTransitions`), thread the caller's `userId` and the project settings; when the same guard bites, drop `DONE`-kind statuses from the computed set. Thread `userId` through the read path — the controller already has it. Keep the workflow set as the base and subtract; **do not** replace it. Assert (extend Step 6's test) the board card and work-table row show the same narrowing. This is why the projection is caller-scoped: "so the caller learns what it may do."

- [x] **Step 9 (agent-context): failing scope test then implement.** `tracker/AgentContextScopeTest.java`:
  - `GET /issues/{taskKey}/agent-context` returns `{issue, story:{acceptanceCriteria:[…]}, epic:{goal:…}, specExcerpts:[…from doc-links…], repositories:[…]}`; `issue.release` is null (M3 not landed) and the field is present.
  - **The scope assertion (Done-when #1):** a token scoped to project A requesting a task in project B receives **nothing** — a 404/empty, asserted as an absence, because `TokenContext.projectId` narrows the permission intersection to the empty set for B (existing machinery; the test proves the composite honours it).

  Implement `AgentContextService.contextFor(userId, key)`: gate on `issue:read` via `ProjectService.readGranted`; load the `Issue` view (already carries the caller-scoped `allowedTransitions`, release, parent/epic keys); resolve the parent story (`GET`-equivalent read) for its `acceptanceCriteria`; resolve the epic for its description→`goal`; read the issue's doc-links (existing `DocLinkService`) into `AgentSpecExcerptView`; read the project's repositories (`ProjectRepoService`) into the list. `AgentContextController` at `/api/v1/issues/{key}/agent-context`. Run on shanks. Expected: PASS.

- [x] **Step 10 (attribution): assert "via <assistant>".** Add to `MeAssistantsTest` (or a small `identity/AttributionTest`): an act performed through an Agent token records an `audit_event` with both `actor_user_id` (the owner) and `actor_token_id` (the token), and `AuditEventView` resolves `actorUserName` + `actorTokenName` (the token's name). This is existing machinery (`AuditEvent.record` reads `TokenContext`); the test pins that the M5 acts (transition refusal, pr_url set, agent_eligible set) all carry it. Expected: PASS.

- [x] **Step 11: full backend gate on shanks.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew build"
```

Expected: PASS (compile with `-Werror`, all tests, ArchUnit — the new `tracker/agent` subpackage is still `tracker`, which already depends on `project`, so no foundation-module rule is touched).

- [x] **Step 12: Commit.**

```bash
git add backend/src/main/java/com/tcms/identity/ backend/src/main/java/com/tcms/project/ \
        backend/src/main/java/com/tcms/tracker/ backend/src/test/java/com/tcms/
git commit -m "feat(agent): assistant tokens, repositories, agent-context, and the refusal to close"
```

---

### T4 — the issue tool surface (MCP + CLI)

**Deliverable:** `@tcms/mcp` and `@tcms/cli` expose the verbs an assistant drives — list my AI-eligible queue, fetch a task with its full context, attach a PR, request a transition (with a refusal that names the allowed moves) — each with a JSON-schema input and the shared output envelope; `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` lists every new arm; `citedFiles.test.ts` green; `packages/` builds in order (client→cli→mcp), lints at 0, tests green; smoke-tested against a running backend.
**Depends on:** T3

**T4 landed 2026-09-03 @ `acffef5e`.** Four facts, three of which change what T5 does:

1. **`attach-pr` is NOT a catalogue action** — it is an arm on both adapters with a hand-written
   `prUrl` property, the way `release`, `cases`, `label` and `move-card` already are. Cataloguing it
   would pull `IssueUpdate` into `shapesContract.test.ts`'s accounted set, where every other property
   is already offered at its `IssueInput` site by the `update` action, so the accounting would need a
   dozen `UNOFFERED` lines declaring fields unoffered that this surface offers. The rule is written
   into `ISSUE_SHAPE`'s header: the catalogue holds an action only when it has a payload to describe.
   `agentEligible` *is* a catalogue field, which is what closed T1's placeholder.
2. **The CLI flag is `--pr-url`, not the plan's `--url`.** `--url` is a GLOBAL flag naming the
   instance, so `attach-pr --url https://…/pull/482` writes to `PATCH /northwind/…/pull/482/api/v1/…`.
3. **T4 already edited `frontend/src/features/help/sections.tsx`** — `guideCopies.test.ts` compares
   the guide to both registries from the *packages* job, so a new CLI command reddens `packages/`
   until the guide lists it. The three `tcms issue` lines are in. **T5 Step 7 is therefore only the
   "Connect an AI assistant" card**, not the command lines.
4. **`POST /issues` with `agentEligible: true` answers 500, and it is not fixed** — a backend defect
   found by T4's smoke and left to the backend's stack. The insert leaves `Issue.rank` null in the
   persistence context (V5's `issue_rank_default` trigger fills the column and nothing reads it
   back), so the update T3's agent-eligibility path flushes writes `rank=NULL` against a `NOT NULL`
   column. **It also breaks `seed-demo-data`**, which flags every second card, so a demo instance
   cannot be built until it is repaired. `PATCH {agentEligible}` is unaffected. T5's AI-eligible
   toggle is a PATCH and so works; `IssueNewPage` must not send `agentEligible: true` on create
   until this lands.

**Files:**
- Modify: `packages/client/src/core/shapes.ts` (`ISSUE_SHAPE`: `attach-pr` arm, `agentEligible`)
- Modify: `packages/mcp/src/reads.ts` (`tcms_read_issue` gains `queue` + `context` modes), `packages/mcp/src/writes.ts` (`tcms_write_issue` gains `attach-pr`)
- Modify: `packages/cli/src/reads.ts`, `packages/cli/src/writes.ts`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`
- Tests: extend `packages/mcp/src/reads.test.ts`, `writes.test.ts`, `packages/cli/src/*.test.ts`

Use the tooling skills: **`tool-capability-contract`** to write the arm contracts (typed inputs, one envelope, actionable errors), then **`tool-mcp-builder`** and **`tool-cli-builder`** to render them. The surface is the feature here, not the tax — §2's rule (a tool never spans two permissions on its write path) still governs: `attach-pr` is `issue:edit` (a PATCH), which the `update` arm already carries, so it is an arm of `tcms_write_issue`, not a new tool. The `queue`/`context` reads are `issue:read`, which `tcms_read_issue` already carries.

- [x] **Step 1: the context/queue reads.** In `tcms_read_issue`, add:
  - a `queue: boolean` input — lists the caller's AI-eligible tasks: `GET /issues?agentEligible=true&assigneeId=<owner>`. The owner is the token's own user; resolve it from `tcms_whoami`/session rather than taking it as input (a token cannot name another owner). Its description: "your AI-eligible queue — the tasks flagged for an assistant and assigned to you."
  - a `context: boolean` input (for one `issue`) — calls `GET /issues/{key}/agent-context` and returns the assembled `AgentTaskContext`. Its description names what it carries (story, AC, epic goal, release, spec excerpts, repository + build command) and that a scoped token gets nothing from another project.

- [x] **Step 2: the `attach-pr` write.** In `tcms_write_issue`, add `attach-pr` to the `action` enum, taking `issue` (a key) and `prUrl` (string). It maps to `PATCH /issues/{key}` with `{prUrl}`. Keep the existing refusal behaviour: a `move` refused by the server (409) returns an actionable error whose text names the allowed moves — the server's `detail` already lists them, and under the M5 gate a DONE refusal reads "needs a second person; move it to In Review instead."

- [x] **Step 3: the CLI verbs.** Mirror onto the CLI per its spelling rules in `ISSUE_SHAPE` (`cliSpelling`): `tcms issue queue`, `tcms issue context <KEY>`, `tcms issue attach-pr <KEY> --url <URL>`. Follow `tool-cli-builder`; keep `writeConformance.test.ts`/`guideCopies.test.ts` green (they compare CLI/MCP arms against the shape).

- [x] **Step 3b: clear the contract-catalogue placeholder T1 left.** T1 registered `IssueInput.agentEligible` as `UNOFFERED['IssueInput.agentEligible']` in `packages/client/src/test/shapesContract.test.ts` (a new contract property on a catalogued schema that the shape did not yet offer). When you add `agentEligible` to `ISSUE_SHAPE` in `core/shapes.ts`, **remove that `UNOFFERED` line** in the same commit — otherwise `shapesContract.test.ts` flips to "offered and unoffered" and reddens.

- [x] **Step 4: the surface doc.** In `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §3.4, record: `tcms_read_issue` gains `queue` and `context` modes (both `issue:read`), `tcms_write_issue` gains `attach-pr` (`issue:edit`); note the `agent-context` composite is the third read-composite and cite §3.1's grounding justification. Update the tool-count line only if a *tool* was added (none was — all arms), so the count is unchanged; say so.

- [x] **Step 5: build, lint, test, smoke on shanks.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages 'npm run build && npm run lint --workspaces && npm run test:run --workspaces'
```

Then smoke against a running backend (the module prompt requires it): with `TCMS_URL`/`TCMS_TOKEN` for an Agent token, run `node packages/cli/dist/main.js issue queue`, `... issue context ACME-124`, `... issue attach-pr ACME-124 --url https://…`, and a `move` to Done that the gate refuses — confirm the refusal names the allowed moves. Record the smoke transcript in the commit message body.

- [x] **Step 6: Commit.**

```bash
git add packages/client/src/core/shapes.ts packages/mcp/src/ packages/cli/src/ \
        docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "feat(agent): the issue tool surface — queue, context, attach-pr on MCP and CLI"
```

---

### T5 — the screens, the marker, and the close-out

**Deliverable:** `profile-assistants`, `assistant-connect`, `settings-repositories` and `story-proposal` are on the render kit and wired to TanStack Query; the AI-eligible toggle+marker renders on the task page, the work-table row and the board card from one shared component; the token secret shows once on creation and never again, and revoking one is reachable from the profile; the "Require review to close issues" toggle is on General settings; the help entry links to Connect-an-assistant; `frontend/npm run build && lint && test:run` green; `features.md` and the 009 status table closed.
**Depends on:** T4

**Files:**
- Create: `frontend/src/features/account/AssistantsPage.tsx` (+ `.test.tsx`), `ConnectAssistantPage.tsx` (+ test)
- Create: `frontend/src/features/settings/RepositoriesSettingsPage.tsx` (+ test); modify `GeneralSettingsPage.tsx`
- Create: `frontend/src/features/tracker/AiEligibleMarker.tsx` (+ test); modify `TaskPanels.tsx`, `WorkTablePage.tsx`, the board card component
- Create: `frontend/src/features/proposals/ProposalReviewPage.tsx` (+ test) — `story-proposal.html`
- Create: `frontend/src/api/queries/account.ts`, `frontend/src/api/queries/projectRepos.ts`; modify `frontend/src/api/queries/keys.ts`, `frontend/src/api/queries/issues.ts` (agentEligible mutation + prUrl), `frontend/src/api/queries/index.ts`
- Modify: routes (`account` profile sub-nav, project `settings` sub-nav → Repositories), `frontend/src/features/help/*` (the entry)
- Modify: `docs/features.md`

Use **`/fe-react`** (`fe-react-forms`, `fe-react-ui-rules`, `fe-react-component-testing`). Screens are a translation of the named mockups into the kit + TanStack Query — not a redesign. One `keys` factory (`keys.ts`); never a second. No `sx`, no CSS-in-JS.

- [ ] **Step 1: query hooks.** `account.ts` — `useMyAssistants()` (`GET /me/assistants`), `useConnectAssistant()` (`POST`, returns the secret — keep the plaintext in component state, never in the query cache, exactly as `AgentTokenPanel`/`useIssueApiToken` do), `useRevokeAssistant()` (`DELETE`). `projectRepos.ts` — `useProjectRepositories(projectId)`, `useAddRepository`, `useUpdateRepository`, `useDeleteRepository`. In `keys.ts` add `myAssistants` and `projectRepositories(projectId)` entries. In `issues.ts` add `useSetAgentEligible(key)` (PATCH `{agentEligible}`) and `useAttachPr(key)` (PATCH `{prUrl}`) with the right invalidations.

- [ ] **Step 2: `AiEligibleMarker`.** One shared component rendering the `ai-tag`/`AI-ELIGIBLE` chip (task page: `AI-ELIGIBLE`; list/card: a compact `AI` marker). Props: `{ eligible: boolean, size?: 'chip'|'tag' }`. Used by `TaskPanels`, `WorkTablePage` row, and the board card. Test: renders nothing when `eligible` is false, the marker when true.

- [ ] **Step 3: the task page assistant panels.** In `TaskPanels.tsx`, port `task.html`'s panels: "What the assistant received" (the agent-context dl — story, epic goal, release, linked spec, repository+build, **Moves allowed** from `allowedTransitions` with DONE struck through when absent), "Pull request" (from `issue.prUrl`), "What the assistant was refused" (render from the `issue.close_refused` history entry when present), the "Assistant" panel with the AI-eligible toggle (`useSetAgentEligible`) and a link to the profile, and "Where it can go" (the allowed set, DONE disabled with "needs a reviewer"). The refusal copy and the "on a project with the review gate off…" line come from the mockup verbatim.

- [ ] **Step 4: `AssistantsPage` + `ConnectAssistantPage`.** Port `profile-assistants.html` (connected list with scope/reach/last-acted/revoke; the "what mine did this week" feed from the audit trail filtered to the caller's tokens; the limits panel) and `assistant-connect.html` (name + scope form → create → show the snippet once, with the `claude mcp add tcms --transport http <origin>/mcp --header "Authorization: Bearer <secret>"` block built from the returned secret and `window.location.origin`; the "shown once" warning). Reachable from the profile sub-nav ("AI assistants"). Test (required by the breakdown): the **token-shown-once** path — after create the secret renders; navigating away and back does not.

- [ ] **Step 5: `RepositoriesSettingsPage` + the review-to-close toggle.** Port `settings-repositories.html` (list with primary badge on position 0, add/edit/remove dialogs, the empty state, the note that a project with none is not degraded) under the project Settings sub-nav. In `GeneralSettingsPage.tsx`, add the "Require review to close issues" toggle bound to `reviewEnabledIssue` (A6: off on a new project). Test: the toggle round-trips; the repo list renders primary on the first row.

- [ ] **Step 6: `ProposalReviewPage` — display only, A5 flagged.** **Confirmed at plan time:** the on-wire `/projects/{id}/ai-proposals` family is `repository`'s **test-case** proposals (`ReviewSubjectType` CASE/SUITE/PLAN/DOC); there is **no issue-proposal endpoint**, and D21 calls for `tracker`'s own `issue_proposal` (its own table, "a shared row is not a shared class"), which this module does **not** build — no migration column, no endpoint for it exists here. So port `story-proposal.html` as a **static/read-only** presentation of the shape (the accept/edit/reject layout, the "a proposal is not a task" note) with the arms **disabled and labelled "coming with the assistant-proposal write loop"**, and record in `features.md` and the close-out that **A5 (propose-then-accept) is not yet wired — it needs a `tracker` `issue_proposal` table and endpoint of its own** (a follow-on module or a later M5 slice). **Do not invent a fake accept and do not point it at the test-case proposals surface** (wrong subject, wrong module). If porting a fully non-functional screen is judged not worth it, skip the page entirely and record A5 as deferred in the close-out — the module deliverable (A1–A4, A6, A7) does not depend on it.

- [ ] **Step 7: the help entry.** Add the "Connect an AI assistant" card to the help sections linking to the connect flow (mirrors `help.html` line 17). **The three `tcms issue queue|context|attach-pr` lines are already there** — T4 added them, because `guideCopies.test.ts` reddens `packages/` over a command the guide does not list.

- [ ] **Step 8: frontend gate on shanks.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
```

Expected: PASS (`tsc -b` strict, oxlint 0, Vitest + RTL).

- [ ] **Step 9: `features.md` close-out.** Rewrite the agent-execution / assistant section of `docs/features.md` to describe what landed (self-service assistants, AI-eligible tasks, the agent-context fetch, the refuse-to-close gate, project repositories) — struck-through-and-superseded, not deleted, per the working agreement.

- [ ] **Step 10: Commit the frontend + features.**

```bash
git add frontend/src/features/account/ frontend/src/features/settings/ \
        frontend/src/features/tracker/ frontend/src/features/proposals/ \
        frontend/src/features/help/ frontend/src/api/queries/ docs/features.md
git commit -m "feat(agent): assistant profile, repositories, AI-eligible marker, and the close-out"
```

---

## Module close-out (after T5's gates are green)

Per the 009 **Prompt hand-off protocol**, in its own commit `docs(plan): M5 done`, staging only `docs/plans/009-project-management-expansion-modules.md`:

1. **Status table** — set M5's row to `done 2026-09-0N @ <sha>`, `V8` (matches the reservation — no strike-through needed).
2. **Built and unverified** — list what the gates proved compiles but nobody clicked: the token-shown-once flow end to end, and (if applicable) A5's propose-and-accept write loop. ~~the refuse-to-close on a real project with the gate on, the agent-context fetch through a real Agent token~~ → **both were smoked in T4** against a stack built from this tree, through a real `/me/assistants` token, on both adapters: the queue, the context read, `attach-pr`, the 409 with its sentence, and A6's same-move-succeeds with the gate off. The transcript is in `acffef5e`'s commit body.
2b. **The open backend defect** — `POST /issues` with `agentEligible: true` answers 500 (`rank=NULL` on the post-insert flush; see T4's landed note), which also breaks `seed-demo-data`. It is not M5's deliverable and it blocks building a demo instance, so the close-out records it as open rather than as done.
3. **The V actually taken** — `V8`, as reserved; note the directory was re-read and held `V1…V7`.
4. **Dependent prompts** — no module's *Depends on* names M5, so nothing downstream is rewritten. **If M3 has not landed** when M5 closes, leave a one-line note in the M3 prompt: *M5's `AgentTaskContext.issue.release` and the fetch payload's `release` are already on the wire and null; M3 must populate `issue.release_id` / `release_assigned_at` and the fact projection so the assistant's context carries the target release.* (M3 shows `done 2026-09-01 @ 55cb4bc0` in the table, so this note is likely unnecessary — verify the release *fact* is populated before skipping it.)
5. **Review table** — M5 hits no R-number that another module owns; if it takes an R15+, record it.

---

## Self-review

- **Spec coverage:** A1 (connect, scope, reach-shrinks, last-used/revoke) → T3 Steps 1–2 + T5 Step 4. A2 (mark AI-eligible, marker on cards/lists, queue default) → T3 Step 5 + T4 Step 1 + T5 Steps 2–3. A3 (whole picture + allowed moves) → T1 Step 3 + T3 Step 9 + T4 Step 1. A4 (comment/PR/move-to-review, refuse Done, "via <assistant>", build-failure comment) → T3 Steps 6–7,10 + T4 Step 2 + T5 Step 3 (the build-failure comment is an ordinary `issue:comment`, already built in M1). A5 (propose breakdown) → T5 Step 6, flagged if the write loop is a follow-on. A6 (one-person company closes own work) → T3 Step 6 (gate-off path) + T5 Step 5 toggle. A7 (repositories list) → T3 Step 3 + T5 Step 5. D16/D25/D26/D30/D33 → covered and cited. D21 → T5 Step 6 (display; write loop flagged).
- **Placeholder scan:** every code step carries real SQL/YAML/Java/TS; the one genuine unknown (whether issue proposals are on the wire) is called out as a runtime check with an explicit non-fake fallback, not a TODO.
- **Type consistency:** `agentEligible`/`prUrl`/`reviewEnabledIssue` spelled identically across contract, entity, view and hooks; `ProjectRepo` (not `ProjectRepository`, which is taken) used consistently.
- **Order check:** T1 (contract) → T2 (schema binds to nothing above it) → T3 (Java binds to T2's columns) → T4 (tools call T3's endpoints) → T5 (screens call T3/T4). Each `Depends on` points backward; the tree builds at every boundary.
- **Deliverable check:** every task states a result, not an activity.
