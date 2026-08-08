# Agent access to TCMS — WIP plan

**Status: WIP.** **[SETTLED]** items were decided in discussion 2026-08-07/08; **[OPEN]** ones were
not. Nothing is built. Do not start phase work until §8 is closed.

**Amended 2026-08-08** after a validation pass against the code: §2.6 (five hardcoded checks, not
four, and the `GET /roles` question answered), §2.7 (the writer's module boundary and its async
half), §2.8.3 (`SeedDataTest` derivations enumerated), §6.1 (idempotency audit done — two holes),
§1.3 (proposal ≠ draft buffer), §7 (phases 1/2/7 sharpened), §8.2 (candidate rule).

**Supersedes in part:** `docs/plans/2026-08-07-mcp-server-architecture-decision.md` — that document
holds the research (MCP security spec, Anthropic tool-design guidance, FastMCP survey) and the cost
analysis and remains the evidence base.

**Reshapes:** `docs/plans/2026-07-31-ai-integration-mcp-grounded-generation-and-coverage.md` §6w.
Four of its ten slices are dropped outright — see §5.

---

## 1. What we are building **[SETTLED]**

Agent access to the test repository for an **already-authenticated TCMS user**: create and update
test cases, add and update documentation, organise plans and runs, fetch executions and results.
Generation, drift checking and scheduling all run through this surface.

**Everything except** creating companies, creating projects, and granting permissions.

**One client library, two front ends, one credential:**

```
                    ┌── CLI  ──────────→ Claude Code · Cursor · Copilot CLI · CI · humans
openapi.yaml        │
  → gen:api         │
  → TS client ──────┤
                    │
                    └── MCP server ────→ Claude Desktop · hosted/remote agents · other MCP hosts

both authenticate with the same per-user api_token, carrying the Agent role
```

The MCP server is a thin layer over the same client, not a second implementation.

### 1.1 Why both **[SETTLED]**

**CLI** is the cheaper and better seam wherever the agent has a shell: zero marginal tools (Bash
already exists), output pipes through `jq` and never enters the context window — which matters
against MCP's 25K-token output cap on a repository with hundreds of cases — and it serves humans,
scripts and CI at the same build cost. It is also the most portable option: any agent with a shell,
not just Claude.

**MCP** is required where there is no shell. **Claude Desktop has no Bash tool**, and the Messages
API MCP connector cannot spawn a CLI — it reaches a remote HTTPS endpoint or nothing. That is the
whole justification, and it is sufficient.

### 1.2 Scope spans §6w slices 3 **and** 5 **[SETTLED]**

§6w slice 3 was wave-1 read-only. This is waves 1 and 2 plus documentation and plan writes. Budget
several slices.

### 1.3 Writes: direct for edits, proposals for new cases **[SETTLED 2026-08-08]**

**An edit to existing content writes straight through. A newly generated case lands as an
`ai_proposal` for a human to accept.**

This keeps §6w.6's guard exactly where it was written for — *"`propose_cases` is the interesting one
and `create_case` is not"*, because the proposals surface is what stops an agent minting unreviewed
coverage — without making a one-word correction cost a review round-trip.

**Consequences:**

- `ai_proposal` and its review surface are **back in scope**; §5 does not drop them. The accept path
  goes **through the existing create path**, per §6w.6, so an accepted proposal is indistinguishable
  from a case created any other way — same validation, same display-key allocation, same audit.
- The tool surface splits accordingly: `propose_cases` for generation, `update_case` and friends for
  edits. The boundary to get right is **what counts as "new"** — a proposal that edits a case is an
  edit; a create is a proposal.
- `case:create` stays in the `Agent` grant (§2.2) because the *accept* path needs it, and because a
  proposal that cannot become a case is not worth landing.
- **`ai_proposal` is not `test_case_draft`.** The §6j autosave buffer (in flight 2026-08-08) is a
  name-adjacent but different thing — a keystroke buffer for the case editor, one row per
  (case, user), cleared on save. Its own javadoc forbids a promote endpoint as *"a second write
  path into `test_case`"* — which independently confirms the accept-through-create decision above.
  The review surface gets its own table; it must not reuse the draft buffer.

---

## 2. The load-bearing decision: a token-only **`Agent`** role **[SETTLED]**

### 2.1 The decision

**Seed a seventh role, `Agent` — PROJECT scope, carried by tokens, never assignable to a person.**
Its grant is **Test Engineer minus `library:manage`, minus `case:delete`, minus `evidence:delete`.**

The intersection that caps a token already exists and is the only narrowing point, so
`PermissionEvaluator` is **unchanged**:

```
effective = evaluate(ownerId, projectId) ∩ permissionsOf(Agent) ∩ [the token's project]
```

A Viewer's agent token still resolves to Viewer's set — Decision C's "a token can never exceed its
owner" does the per-user degradation with no branching to write. **Today both production users are
Company Admins, so the intersection constrains nothing and the role *is* the entire cap** — which is
the reason to define it deliberately rather than borrow one.

**The ceiling is data, not code**, and that is the point: it appears in the permission matrix, and
`SeedDataTest` derives invariants from the catalogue rather than asserting them about a constant. A
ceiling held in validation code has no such derivation available.

### 2.2 The grant, and why each line is where it is

**Kept** — everything §1 asks for: `case:read` · `case:create` · `case:edit` · `suite:manage` ·
`doc:read` · `doc:edit` · `plan:create` · `plan:edit` · `run:create` · `run:execute` ·
`result:submit` · `evidence:attach` · `case:comment` · `case:review` · `suite:review` ·
`plan:review` · `report:view` · `report:export` · `artifact:read` · `artifact:edit` (plan scope) ·
`defect:create` · `workitem:manage` · `project:read`.

**Dropped from Test Engineer** — three, each for its own reason:

| Dropped | Why |
|---|---|
| `library:manage` | §A3 gates it by an **impact modal, not by permission** — editing a shared condition or step propagates to every referencing case, and the control is *informed consent* via a confirmation listing them. **An API caller never sees that modal.** Removing the permission is the only fix that also holds for raw REST; rebuilding consent at the tool layer could not. |
| `case:delete` | `OWN` for Test Engineer, and `OWN` follows `api_token.owner_id` — so an agent would delete cases **the human authored**, not only ones it created. Not needed for add/update. |
| `evidence:delete` | Same `OWN` reasoning. |

**Never held, inherited from Test Engineer's own boundary** — and each is something an agent should
not do:

| Excluded | Why |
|---|---|
| `case:approve` · `suite:approve` · `plan:approve` | Author-cannot-approve-own-work already defaults on. An agent that generates cases must not approve them. |
| `run:close` · `result:amend` | A judgement about the *work*, not the artifact. |
| `doc:publish` | Publishing is the human's signal that a document is ready. |
| `project:settings` · `project:members` · `integration:manage` | Configuration and access-granting — the boundary §1 draws. |
| `suite:delete` · `plan:delete` · `artifact:delete` | Destructive, and not needed for add/update. |

### 2.3 Why a role, and why it may be a seventh one

The pattern is established twice already: **Automation Service** is *"Non-human. Attached to API
tokens, never people"* — a token-only capability set implemented as a role — and **Company Member**
(`V15`) is a role row deliberately outside the six, filtered from `GET /roles` and refused by
`POST /role-assignments`. `Agent` is the first with the second's exclusion, so §A2's
"six *assignable* roles" stays literally true.

Because `library:manage` is simply absent rather than gated, this holds on **raw REST as well as the
tool layer** — a boundary drawn in the tool list would not.

### 2.4 Automation Service stays **[SETTLED 2026-08-08]**

`case:read` · `project:read` · `case:create` (quarantine only) · `run:create` · `result:submit` ·
`evidence:attach` — a CI pipeline posting results, deliberately *"the smallest role in the
product."* `Agent` does not subsume it and CI must not hold `Agent`. Two token-carried roles,
different jobs.

**Asked and closed:** does the CLI/MCP make it redundant? No — that would change the *transport* CI
uses, not the reason the role is narrow. Its value is that a leaked CI token cannot edit or delete a
real case, and that is independent of how results arrive. Folding CI onto `Agent` would widen what a
CI credential can do, and result ingestion (§117) is built around it.

### 2.5 Why the tool list cannot be the boundary

The `tcms_…` bearer authenticates against `/api/*` through `ApiTokenAuthenticationFilter`. An agent
holding it can `curl` the REST API directly. **Omitting a tool controls what the agent reaches for,
not what it can do** — `/me/permissions` makes the same point about the SPA: *hidden is not
protected*. This applies doubly to the CLI, which has no tool list at all.

### 2.6 Making it non-assignable — what the code does today **[verified 2026-08-08]**

**There is no `assignable` flag on `role`.** Every exclusion of `Company Member` is a hardcoded UUID
equality check against `Role.COMPANY_MEMBER_ID` — in **five** places, not four, and the fifth is the
one that shows a single boolean cannot carry the whole rule:

| Where | What it does | With `Agent` it must |
|---|---|---|
| `RoleGrantService:286` | refuses to revoke it | refuse both → ask `assignable` |
| `RoleGrantService:356` | refuses to assign it | refuse both → ask `assignable` |
| `AdminDirectoryService:352` | filters it out of `GET /roles` | **keep returning `Agent`** — see below |
| `AdminDirectoryService:402` | filters its grants out of `GET /role-assignments` | unchanged (`Agent` grants to people cannot exist) |
| `ApiTokenService:253` | refuses it for a token | **keep refusing only `Company Member`** |

**Add `role.assignable BOOLEAN NOT NULL DEFAULT true`**, false for `Company Member` and `Agent`, and
the two `RoleGrantService` enforcement sites ask the row instead of a constant. The check somebody
forgets is the one that lets an administrator grant `Agent` to a person, and hardcoded lists are how
it gets forgotten.

**But `assignable` cannot drive the other three sites**, because the two non-assignable roles differ
exactly where those sites look: `Company Member` is non-assignable *and* non-issuable *and* never
listed; `Agent` is non-assignable **but issuable and must be listed** (the token form has to offer
it). So:

- **`ApiTokenService.validate` stays as it is** — its `COMPANY_MEMBER_ID` check is about the
  baseline, not about assignability, and `Agent` is issuable the day its seed row lands. Leave a
  comment on that check saying it must *not* be "cleaned up" to read `assignable`, or the cleanup
  breaks `Agent` token issue. Its error copy is already right: *"Choose the role this token carries.
  It is not your own role: a token holds one role and nothing else."*
- **The `GET /roles` question is answered. [verified 2026-08-08]** Both screens consume the same
  source: `IssueTokenDialog.tsx:85` and the assignment screens both call `useRoles()` →
  `GET /roles` → the server-side filter at `AdminDirectoryService:352`. So that filter must **not**
  become `WHERE assignable` — that would hide `Agent` from the token form. Instead: the filter keeps
  excluding only the baseline (`Company Member`, which no screen ever wants), the response gains an
  `assignable` field (`openapi.yaml` change, `required` array, `npm run gen:api`), and the
  *assignment* UI filters client-side. Precedent already in the file: `IssueTokenDialog.tsx:104`
  filters SYSTEM-scope client-side, with a comment explaining why it does not copy the neighbouring
  filter. `RoleGrantService` remains the enforcement, so client-side filtering here is affordance,
  not security.

### 2.6a Expiry and revocation **[SETTLED 2026-08-08]**

Both already exist and are enforced — `ApiToken.isUsable(now)` is
`revokedAt == null && (expiresAt == null || now.isBefore(expiresAt))`, checked after the company is
open, and an unknown digest, a revoked token, an expired one and a vanished row all collapse to the
same empty result so the caller learns nothing from which failure it was. `DELETE /api-tokens/{id}`
is idempotent and **the first revocation is the one that counts** — the trail must say when the
token stopped working, not when somebody clicked twice.

**The one change: `expiresAt` becomes required for `Agent` tokens, with a default pre-filled.** It is
nullable today for a deliberate reason — *"a credential that dies mid-release"* is the failure the CI
case avoids — but an agent token sits on a developer's laptop, where the opposite risk dominates and
an optional field is a field nobody sets. **This is the first per-role rule in the issue path**, so
it lands in `ApiTokenService.validate` beside the existing role checks, not in the form.

### 2.7 Attribution: telling a person's edit from their agent's **[SETTLED]**

§6w.3 requires `via = MCP` on every call's `audit_event`. Answering it turned up a live gap.

**`audit_event.actor_token_id` has no writer. [verified 2026-08-08]** The column is `V1` and is
mapped on `AuditEvent`, but `AuditEvent.record(...)` never sets it — so **every token-driven change
is currently audited as though the human made it in the UI.** Same shape as §116's `api_token` and
§117's `ingestion_job`: a `V1` column that never got a writer, and nothing failed because nothing
asked.

**The fix needs no call-site changes — but not quite the obvious import.** `record(...)` already
takes `companyId` from ambient scope rather than a parameter — *"`companyId` comes from
`CompanyContext`, never from a parameter"* — and `TokenContext` is the same shape of scope guard,
opened and closed by `ApiTokenAuthenticationFilter`. Two corrections to the obvious version of
"so `record(...)` reads it the same way" **[verified 2026-08-08]**:

1. **The module boundary refuses the import.** `AuditEvent` is `tenancy`; `TokenContext` is
   `identity`; and `tenancy` depends on *nothing* (ArchUnit-enforced — today `tenancy/*.java` has
   zero `identity` imports, and `identity` depends on `tenancy`, so the import is also a cycle).
   **Move `TokenContext` into `tenancy` beside `CompanyContext`** — its own javadoc already calls it
   *"`CompanyContext`'s shape, for the other half of 'who is asking'"*, and `PermissionEvaluator`
   can still read it there since `identity` already depends on `tenancy`. The alternative (a
   tenancy-owned ambient slot the filter populates) adds a second context for the same fact; the
   move is cleaner.
2. **The ambient read covers synchronous emissions only.** `TokenContext` is deliberately not
   `InheritableThreadLocal` — so an audit event recorded inside async work reads an *empty* context
   and writes null again. Ingestion (§117) is the case that matters, and it is the CI-token gap
   this fix cites as motivation: repair only the ambient read and the motivating example stays
   broken. The token id must ride the envelope the same way `companyId` does —
   `ApiToken`'s javadoc already pairs the gap as *"`ingestion_job.executed_by_token` and
   `audit_event.actor_token_id`"*, so the ingestion job row/event carries the token id and the
   async audit path reads it from there. Ambient scope for in-request emissions, envelope for
   async: both halves, or the fix is half a fix.

Three questions, three costs — do the first two:

| Question | What it needs | Size |
|---|---|---|
| **UI or token?** | the `actor_token_id` writer above | one method, no signature change |
| **Agent or CI?** | **nothing — `api_token.role_id` answers it.** An agent token carries `Agent`, a CI token carries `Automation Service`. One of the reasons §2.1 is a role. | free |
| **Show it in case history** | a token column on `test_case_version` (`created_by` is a bare user UUID today) and its siblings, plus the SPA | **deferred 2026-08-08** — a slice of its own, and cheap to add once the audit data exists |

`via = MCP` derives from the token's role — no channel column on `audit_event`, no `purpose` column,
and nothing a client can forge with a header.

**Two audit rules that constrain the shape.** `audit_event` is *"append-only, NEVER updated"* and is
emitted from domain events so scheduled jobs are audited identically to UI actions — so the token
must be readable at emission time, which the ambient-scope read gives. And `changes` *"must not
contain a credential, a token or a password — this table is readable by the client's own admins"*:
record the token **id**, never its prefix or name in `changes`.

### 2.8 Details that will bite

1. **Qualifiers, not just keys.** The intersection narrows `PermissionQualifier` via `narrowedBy` —
   a company-wide `ALL` capped by `OWN` is `OWN`. `Agent`'s seed must state the qualifier per key,
   and the tests must assert it rather than assume it. `case:edit` is the one to get right: `ALL`
   lets the agent edit anyone's case, which is what §1 asks for.
2. **`api_token.project_id` already gives the hard project scope**, refused *before* the intersection
   so the answer is "not this project" rather than "you hold case:read". Free, built, use it.
3. **`SeedDataTest` derives invariants from the catalogue — checked. [verified 2026-08-08]** The
   full list of derivations `Agent` touches:
   - `sixBuiltInRoles` pins the exact role list with `containsExactlyInAnyOrder`, and its rationale
     text says an addition *"needs a spec decision"* — it **will fail on the seed row**, which is
     the test working; phase 2 updates it deliberately, with the §A2-style wording `Company Member`
     got.
   - `everyReviewerIsAlsoACommenter` — `Agent` holds `case:review` and `case:comment` ✓.
   - `everyApproverIsAlsoAnEditor` — `Agent` holds no approve key ✓.
   - `automationServiceIsTheSmallestRole` — still holds: Automation Service's set is a strict
     subset of `Agent`'s (quarantine-only `case:create` narrows `Agent`'s unqualified one).
   - `noPermissionCarriesTwoDifferentRestrictiveQualifiers` — `Agent`'s `artifact:edit` must reuse
     Test Engineer's *exact* plan-scope qualifier spelling, not a new one.
   - `catalogueMatchesTheContract` — if the contract pins role names, the seed row is also an
     `openapi.yaml` change; check before phase 2, not during.

### 2.9 What endpoint gating does and does not do

Gating is real and resolves in one place: services ask through ports (`ProjectAccess`,
`IdentityProjectAccess`) whose contracts require resolution *"through `PermissionEvaluator` and
nothing else"*, with Modulith failing the build on the cycle that would let a module answer "may
they?" independently. **That is what makes the cap work everywhere, including raw REST — but the
gate enforces what the evaluator answers; it does not narrow it.** A Test Lead token passes a
`project:members` check because Test Lead holds it.

---

## 3. Credentials: two, in opposite directions **[SETTLED]**

| | Direction | Scope | Purpose |
|---|---|---|---|
| **TCMS agent token** | agent → TCMS | per **user**, per project | reading/writing the repository |
| ~~Company LLM key~~ | ~~TCMS → provider~~ | ~~per company~~ | ~~TCMS calling a model itself~~ — **dropped, §5** |

**The company-level LLM key pattern must not be copied for agent tokens.** That key is one outbound
secret with no identity. An agent token is inbound and must carry *a user*, because the entire cap is
`evaluate(ownerId) ∩ permissionsOf(role)`. A shared company token would break the
no-more-than-the-user's-permissions property and collapse `audit_event.actor_user_id` to one
synthetic actor — so "who changed this test case" stops having an answer.

---

## 4. Deployment **[SETTLED]**

**CLI:** published as `npx @tcms/cli`. Nothing to deploy. Do not reuse `tcms.jar` — that is an
operator tool with database access; this is a REST client that must not require a JRE.

**MCP server:** remote HTTPS. Local stdio cannot be reached by the Messages API connector
(*"Local STDIO servers cannot be connected directly"*), and Anthropic recommends HTTP as the
transport for remote servers. **This reverses §6w open question 5's stdio-first answer**, which was
chosen when the slice was read-only and locally driven.

**Auth: static bearer.** Claude Code takes `--header "Authorization: Bearer …"`; the connector takes
`authorization_token`; a hosted agent injects it as a vault `environment_variable` credential,
substituted at egress and never visible in the sandbox. OAuth 2.1 buys Claude Code's `/mcp` browser
sign-in and Directory eligibility — **a slice of its own, not this one.**

**Hosting is the smallest line item.** `deploy/shanks/Caddyfile` already terminates TLS, sets HSTS
and proxies `handle /api/*` → `127.0.0.1:8080`; this is one container and one `handle` block. Public
ingress is already sunk — that Caddyfile records that Tailscale Funnel made the site public and
*"every unauthenticated endpoint is now an internet-facing endpoint."*

**The costs that are not hosting:** rate limiting becomes required (an agent looping `search_cases`
is real load on the box running Postgres); unauthenticated `tools/list` must return nothing and a
revoked token must be indistinguishable from an unknown one; an availability commitment (ghcr image,
`TCMS_VERSION` pin, health check, `deploy.sh`, runbook); and the Messages API connector is **not
ZDR-eligible** — tool definitions *and* results are retained under standard policy, which is a
per-client disclosure question. None of that applies to the CLI.

---

## 5. What this deletes from §6w **[SETTLED]**

Dropping the company LLM key removes four slices and everything attached to them:

| §6w slice | Fate |
|---|---|
| **4** — `ai_provider` + `company:ai`, encrypted key, egress consent, budget, *Test connection* | **Dropped.** No key to store. |
| **6** — server-side generation + critic, async job, budget enforcement | **Dropped.** The user's agent generates; tools land the result. |
| **6b** — boundary-gap analysis | **Dropped as a server-side job**; becomes a prompt the agent runs. |
| **7** — model-assisted criteria extraction | **Dropped as server-side**; BYO agent does it and writes back. |

**What survives, and is now the backend half of the work:**

- **Slice 1 — criteria extraction, deterministic.** `acceptance_criterion` + `source_hash`,
  `doc_link.target_kind = ACCEPTANCE_CRITERION`, re-hash on doc save, precise
  `outdated_spec_changed` + `orphaned_criterion`.
- **Slice 2 — three-axis coverage.** `criterion_coverage`, the claimed-vs-verified split,
  automation-link health.
- **Slice 3b — the method layer**, whose declarative `rules` half drives rung-0 checks *with no model
  configured*, which was always §6w.2a's point.

### 5.1 Drift: two different things share the name

- **Automation drift** (§6c, `case_health.stale_automation`) — **already shipped as §120**, fully
  deterministic. Nothing to fold.
- **Spec drift** (`outdated_spec_changed`) — also deterministic, and is slice 1 above. No model.

### 5.2 Scheduling moves out of TCMS, it does not move into MCP

The only reason §6w wanted a key was that *something* must call a model when no user is present. The
scheduler therefore moves to the **agent side** — a CI cron running `claude -p` against the CLI, or a
scheduled deployment against the MCP endpoint. It brings its own model and its own budget. **TCMS
never holds a model key and never calls a provider.**

---

## 6. Tool / command surface **[SETTLED 2026-08-08]**

> **Settled by [`docs/specs/2026-08-08-mcp-tool-and-command-surface.md`](../specs/2026-08-08-mcp-tool-and-command-surface.md)**
> — 21 MCP tools over 88 paths, ~45 CLI commands, one shared client and command core. The rule that
> decides the count is *a tool never spans two permissions on its write path*, because `tools/list`
> is permission-filtered. **Read that spec's §0 before §6.1 below**: the documentation-module read
> this section was blocked on produced five reversals to §6w, including `list_criteria` dropped
> outright (there is no `acceptance_criterion` table, and this product resolved ERD question #18 by
> deciding a requirement *is* a doc block).
>
> §6.1's audit stands and is now a **prerequisite** rather than a reminder: `Idempotency-Key` on
> `POST …/suites` and `POST …/doc-pages` gates two of the twenty-one tools. §6.2 is adopted
> unchanged.

Roughly 80–90 REST paths are in scope. **Do not mirror them.** Anthropic's guidance: a few
workflow-shaped tools, namespaced, high-signal fields, UUIDs resolved to display keys, pagination and
filtering defaults.

Limits to design against (MCP side): output warns at 10K tokens and is **capped at 25K**, with
oversized results persisted to disk and replaced by a file reference; a tool may raise its own
ceiling via `_meta["anthropic/maxResultSizeChars"]` up to 500,000 chars; **tool descriptions and
server instructions truncate at 2KB each**. Claude Code defers definitions behind tool search by
default; the Messages API connector needs `defer_loading` set explicitly.

### 6.1 Two things every write must carry

- **`Idempotency-Key`** — already specified on case create, for exactly this failure mode: *"the
  retry of a create that in fact succeeded cannot make a second."* Agents retry constantly. **The
  coverage audit is done. [verified 2026-08-08]** Carried today: cases, plans, runs, ingestions
  (required there), work-items, projects, users. Explicitly declined with recorded reasons:
  conditions and shared-steps — both behind `library:manage`, outside the `Agent` grant, so
  consistent. **Missing and inside the grant: `POST …/suites` and `POST …/doc-pages`** — both
  retry-prone creates an agent will double-fire. Add `Idempotency-Key` to both in `openapi.yaml`
  before the CLI write phase, and bake it into the proposal-create endpoint from day one.
- **`If-Match` with `lockVersion`** — the contract is explicit that *"omitting it is
  last-write-wins."* A command that does not thread the version through will silently clobber a
  human's concurrent edit with no error. It belongs in the schemas from the first write, not
  retrofitted.

### 6.2 The session must know its own reach, and say so **[SETTLED]**

The agent should be able to tell the user *"I can't do that, and here is what would let me."*
Three places, because no single one covers it.

**1. Server instructions, at session start.** `GET /me/permissions` already answers this — effective
permissions for the caller in a project, and it is *the* place the SPA asks. The MCP server calls it
on connect and renders a short capability summary into its server instructions: who the token acts
as, its role, its project, and the operations that are unavailable with the role that holds each.

This is the right carrier because **Claude Code loads server instructions at session start even when
tool search defers the tool definitions** — so the summary is present before any tool is discovered.
It also has to be *short*: **instructions truncate at 2KB**, so this is a grouped summary
("approving, closing runs and publishing docs need Test Lead"), never a 55-row dump.

**2. A `whoami` tool**, always loaded (`_meta["anthropic/alwaysLoad"]: true`), for when the user asks
directly or the session has run long enough that the instructions are far away. Returns the same
summary, live.

**3. Refusal copy on the tools that do exist.** When a call is refused, the message names the missing
permission and the lowest built-in role that holds it.

#### The constraint that decides what may be said

**§6t's 404-not-403 rule.** The `Forbidden` response is explicit that 403 is *"reached only after the
read gate has passed"* — an unreachable project or case answers **404**, and
`GET /me/permissions` answers the **empty set** identically for an unreachable project and a
non-existent one, *"so this endpoint cannot be used to probe for projects in other companies."*

| Situation | What the agent may say |
|---|---|
| Past the read gate, write refused (403) | name the permission and the lowest role that holds it |
| Not past the read gate (404), or an empty permission answer | **nothing about roles** — "no such project, or you cannot see it" |

Getting this backwards turns the MCP server into the enumeration oracle §6t was written to prevent.

#### Why the tools stay hidden

§6w.3 filters `tools/list` by permission, reasoning that *"an agent shown a tool it cannot use will
call it, be refused, and retry — the same reasoning as §31's disable-don't-hide, arriving at the
opposite affordance because an agent reads a list, not a screen."* That decision stands: hiding
avoids retry loops and saves context. The three carriers above are what replace the SPA's *disabled
but visible* affordance — the agent knows what it cannot do without holding a tool it cannot call.

---

## 7. Phases (sketch)

1. **Two dead columns (§2.7, §8)** — writers for `audit_event.actor_token_id` and
   `api_token.lastUsedAt`. Independent of everything below and worth landing first: both are small,
   both sit on the authentication path, and both repair live gaps on *existing* CI tokens whether or
   not any of the rest happens. Two shape notes from §2.7's verification: the `actor_token_id`
   writer needs `TokenContext` moved to `tenancy` *and* the async/ingestion envelope half, and the
   `lastUsedAt` writer must be **coarse** — a naive version is one `UPDATE api_token` per
   authenticated request on the hot path, contending on a busy CI token's row. Write it only when
   stale by more than some interval.
2. **The `Agent` role (§2.1)** — seed row and grants, `role.assignable` behind the two
   `RoleGrantService` enforcement sites plus the `assignable` field on `GET /roles` (§2.6 — the
   directory filter and the token-issue check stay baseline-specific), and tests that pin the
   intersection for an agent token held by a Viewer, a Test Engineer and a Company Admin owner, plus
   the qualifier narrowing of §2.8.1 and the `SeedDataTest` updates enumerated in §2.8.3.
   **`PermissionEvaluator` unchanged.** Also update the permission matrix in the roles spec,
   `README.md` and `CLAUDE.md` — three copies, and the spec is authoritative — **including the
   prose**: "the six are the complete assignable set" appears outside the tables and needs the same
   §A2-style wording `Company Member` got.
3. **Token issue** — `expiresAt` required with a default for `Agent` (§2.6a). Small, and it belongs
   with the role rather than after it.
4. **Shared TS client** from `openapi.yaml`.
5. **CLI**, read commands first.
6. **`ai_proposal` + the review surface** (§1.3) — the accept path goes through the existing create
   path, so this is backend work that must precede any generation tool.
7. **CLI writes** — edits direct, creates as proposals. Preceded by §6.1's contract additions:
   `Idempotency-Key` on `POST …/suites` and `POST …/doc-pages`.
8. **MCP server** over the same client, carrying §6.2's capability summary and `whoami`.
9. **Rate limiting**, REST side.
10. **Deploy** — image, compose, Caddy route, runbook.

Slices 1/2/3b of §6w (criteria, coverage, method rules) are independent backend work and can run
before or beside this.

---

## 8. Open questions

Four were closed on 2026-08-08 and are recorded in place: §1.3 (writes), §2.6a (expiry), §2.7
(history UI), §2.4 (Automation Service). What remains:

1. ~~**§6 — the command and tool list itself.** The only genuinely blocking one. Needs the
   documentation module read first…~~ → **CLOSED 2026-08-08**,
   [`docs/specs/2026-08-08-mcp-tool-and-command-surface.md`](../specs/2026-08-08-mcp-tool-and-command-surface.md).
   The documentation-module read was the whole content of it: `list_criteria` had no table and never
   will, because the requirement model this product chose is the doc block. **Question 2 below is
   settled at the tool layer as a side effect** — the create/edit arm boundary *is* the new-vs-edit
   line — leaving only its bulk-generation semantics open.
2. **Where the "new vs edit" line falls** (§1.3). A proposal that rewrites a case is an edit; a
   create is a proposal. Bulk generation against an existing suite is the case that decides it.
   **Candidate rule:** anything that names an existing case id and threads `If-Match` is an edit;
   anything that would allocate a display key is a create and therefore a proposal. Under that rule
   a bulk generation decomposes per item — its edits land, its creates queue — rather than the
   batch deciding as a whole.
3. **Per-token permission picker — a future refinement, not a replacement.** Let the issuer select a
   subset at token creation, stored on the token and applied as a further intersection term, with
   `Agent` as the ceiling. This is the MCP spec's progressive least-privilege model, and it means a
   read-and-propose agent need not carry `run:execute`. Costs when it happens: an
   `api_token_permission` join table (**with its own `company_id` per ERD §0.1**, like every join
   table), a third term in `PermissionEvaluator`, and a picker in the token-issue screen rendering
   the ceiling rather than the 55-key catalogue. Qualifiers keep coming from `role_id`, which is why
   the role stays even after the picker exists.

**Not a question — a second dead column. [verified 2026-08-08]** `api_token.lastUsedAt` has a field
and a getter on `ApiToken` and nothing sets it, the same shape as `actor_token_id` (§2.7) and the
same shape §116 and §117 kept finding. So the token list's "last used" column reads empty forever,
and *"revoke the ones nobody has used"* cannot be answered — which is the natural companion to
§2.6a's expiry policy. Both writers are cheap and both sit on the authentication path: fix them
together in phase 1.

---

## 9. Standing obligations

- **§65 — deferred verification.** Record rows in `docs/verification/backend-mutation-checks.md` for
  the agent-token path and every write, in executable form. Neither a CLI nor a remote MCP server
  is a browser lane, so nothing goes in the frontend queue. **Landing without those rows skips the
  check outright.**
- **Contract.** New or changed endpoints go in `openapi.yaml` first, then `npm run gen:api`, then
  both sides in one commit — including `required` arrays.
- **Orientation cache.** `docs/agent-context/be-java.md` updated in the slice's own commit.
- **`list_criteria` / `get_doc_page`** were specified in §6w against a documentation module that did
  not exist then. Slice-10 work has been building it since — **re-read the module first.**
- **Write the reversals into §6w**, struck through: stdio-first (§4 here), and the four dropped
  slices (§5 here). Per the working agreement, so the old reasoning doesn't read as unconsidered.
