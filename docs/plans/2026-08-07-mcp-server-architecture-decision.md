# MCP server — architecture decision

> **Superseded in part, 2026-08-08, by `docs/plans/2026-08-08-mcp-server-wip-plan.md`.** That
> document is the live one. Two things moved past this doc: the scope is no longer read-only, and
> the load-bearing choice is no longer the implementation language but the token permission profile.
> **This document remains the evidence base** — the MCP security spec, Anthropic's tool-design
> guidance, the FastMCP capability survey, and the cost analysis are here and are not repeated
> there. ~~Its stdio-first endorsement is reversed~~ → see the WIP plan §3.1.

**Status:** proposed, awaiting decision
**Scope:** settles *where the MCP server runs and what language it is written in*, and the
consequences that follow. The tool-level detail (schemas, pagination, error copy, the wave-1
surface) is deliberately **not** here — it belongs in the implementation plan that follows this
decision.
**Implements:** `docs/plans/2026-07-31-ai-integration-mcp-grounded-generation-and-coverage.md`
§6w.3, slice 3 of §6w.10.

---

## 0. The question

§6w.3 names the implementation as "Spring AI MCP / the Java MCP SDK, stdio + streamable HTTP" —
one line, written when the slice was a sketch. The proposal on the table is to build it on
**FastMCP** (`gofastmcp.com`) instead.

FastMCP is **Python** (with an official TypeScript counterpart from the same team). There is no
Java build. So this is not a library swap inside the existing Spring Boot process — choosing it
means the MCP server becomes **a separate process that reaches TCMS over the REST API**, which
contradicts CLAUDE.md's "One deployable: Spring Boot REST API + React SPA".

That is the decision. Everything below is the evidence for making it deliberately rather than by
import.

---

## 1. What the codebase already has

Four findings, each of which moves the answer. All were verified in the tree at `e95ef1a`.

| Fact | Where | Why it matters |
|---|---|---|
| `ApiTokenAuthenticationFilter` + `ApiTokenAuthenticationService` exist | `backend/.../auth/` | The REST API **already** authenticates `api_token` bearers through the same Spring Security chain that serves the SPA: `CompanyContextFilter` opens `CompanyContext`, `PermissionEvaluator` gates each call. An out-of-process MCP server needs to write **no** authentication code — it holds the user's token and calls the API. |
| `GET /me/permissions` exists | `openapi.yaml:185`, `MeController` | This is precisely the input §6w.3's per-session tool filtering needs: the caller's effective permission set, scoped by project, answering the empty set for an unreachable project so it can't be used to probe. A sidecar can compute its `tools/list` from one call at session start. |
| **`api_token` carries a `role_id`, not the owner's grants** | `V1__initial_schema.sql:333`, and its own `COMMENT` | §6w.3 says an agent "is simply a caller holding whatever its token's user holds." **That is not what the schema does.** A token holds *a role* — deliberately, per §6b: "A CI token holds Automation Service and nothing more … a leaked token can post results and add quarantine stubs, and cannot edit or delete a real case." This is a correction to the plan, not to the code, and it is a better property than the one the plan claimed. See §5. |
| `audit_event` has `actor_token_id` but **no `via` column** | `V1__initial_schema.sql:1288` | §6w.3 requires `via = MCP` on every tool call. The row can already name the token; it cannot yet name the channel. This is a real, small gap either way — see §6. |

The net effect: **the "sidecar has to rebuild auth" objection is void.** Auth, tenancy, permission
evaluation, and token binding are all already on the REST side of the wire.

---

## 2. What the MCP ecosystem says (research)

Three sources, all fetched 2026-08-07.

### 2.1 The MCP security spec

`modelcontextprotocol.io` → *Security Best Practices*. The relevant rules for a server we host:

- **Token passthrough is explicitly forbidden.** "MCP servers MUST NOT accept any tokens that were
  not explicitly issued for the MCP server." A server that takes whatever bearer the client sends
  and forwards it downstream is the named anti-pattern.
  **We are not exposed to this in either option** — the token is a TCMS `api_token`, issued by
  TCMS, validated by TCMS, and the "downstream API" is TCMS itself. There is no third party and no
  audience confusion. Worth recording, because it is the single most common way a hosted MCP server
  gets this wrong.
- **Sessions must never be used for authentication.** "MCP servers that implement authorization MUST
  verify all inbound requests." Every tool call re-authenticates; a session ID is not a credential.
  Both options satisfy this trivially — in-process because the filter chain runs per request,
  out-of-process because every tool is an HTTP call carrying the token.
- **Session IDs must be non-deterministic and bound to user-specific information** (`<user_id>:<session_id>`).
- **Scope minimization**: don't publish an omnibus scope catalogue, and never treat a claimed scope
  in a token as sufficient without server-side authorization logic. TCMS is already correct here —
  the token's role is resolved server-side, and there is no wildcard permission (§A2).
- **Local servers should prefer `stdio`**, precisely because it "limits access to just the MCP
  client." If an HTTP transport is used locally it must require an authorization token or a Unix
  domain socket. This supports the plan's stdio-first answer to open question 5.
- **Confused deputy / OAuth-proxy attacks** apply to servers that broker a *third-party* OAuth
  identity. TCMS brokers nothing — the token is ours. Not applicable, recorded so the next reader
  does not re-derive it.

### 2.2 Anthropic's tool-design guidance

`anthropic.com/engineering/writing-tools-for-agents`. The load-bearing points, because they decide
what the tool surface looks like *regardless* of which lane wins:

- **Do not wrap every API endpoint.** Build "a few thoughtful tools targeting specific high-impact
  workflows"; consolidate related operations rather than mirroring the REST surface.
- **Namespace related tools** under a common prefix.
- **Return high-signal information only**, and **resolve UUIDs to semantic names** — "significantly
  improves Claude's precision in retrieval tasks."
- **Pagination, filtering, and truncation with sensible defaults**, plus guidance steering the agent
  toward many small targeted searches over one broad one.
- **Errors must be actionable**, not opaque codes.
- **Evaluate with realistic multi-step tasks** — small description changes yield large behavioural
  differences.

Two of these bite TCMS directly. Every read model in this product is UUID-keyed, and the SPA
resolves those UUIDs through separate calls; an MCP tool returning raw `caseId` UUIDs would be
strictly worse than one returning display keys and names. And `GET /projects/{id}/cases` returns a
page shaped for a DataGrid, not for an agent's context window.

### 2.3 FastMCP's actual feature set

- **Authorization is callable-based and filters component visibility per request.** Failing checks
  mean components are "hidden from list responses and direct access returns not-found" — which is
  §6w.3's tool-filtering requirement, as a framework primitive rather than something we write.
  API: `AuthContext`, `AuthCheck`, `AuthMiddleware`, `require_scopes()`, `require_roles()`.
- **Token verification**: JWT (JWKS / static key / HMAC), RFC 7662 introspection for opaque tokens,
  and `StaticTokenVerifier` for tests. Tools read the caller via `get_access_token()` →
  `.claims`, `.scopes`, `.client_id`.
- **`FastMCP.from_openapi()`** generates a server from an OpenAPI spec, with `RouteMap` rules to
  include/exclude routes and `mcp_names` / `mcp_component_fn` to rename and rewrite descriptions.
- Middleware, tool transformation, tool search (for large catalogues), HTTP deployment, testing
  helpers.

**`from_openapi()` is a trap, and should be recorded as one.** TCMS has a 10,500-line generated
`schema.d.ts` behind ~120 REST paths. Auto-generating tools from `openapi.yaml` would produce
exactly the endpoint-mirroring surface Anthropic's guidance says not to build, and it would do it
in one line, which is what makes it tempting. **Wave-1 tools are hand-written**, whichever lane
wins. The generator may be useful for the *client* half — typed calls into TCMS — never for the
tool surface.

---

## 3. The three options

### Option A — Java MCP SDK, in-process (the plan as written)

Spring AI MCP inside the existing Spring Boot app.

**For:** one deployable, unchanged. `CompanyContext` and `PermissionEvaluator` are called directly
with no HTTP hop. Reuses the Testcontainers harness and the 1000+ integration tests, including
`RepositoryTransactionTest` and `AsyncCompanyContextTest`, which already know how to catch the
tenancy failure modes. Can expose tools the REST API does not — a `repository_health` roll-up that
would need four REST calls can be one query. `-Werror`, ArchUnit and Modulith keep it honest.

**Against:** no FastMCP ergonomics — tool schemas, per-request filtering, and transport lifecycle
are all hand-rolled. Distributing a **stdio** server means shipping a JAR and telling the user to
put `java -jar tcms.jar mcp` in their Claude Code config. And the safety property is a *discipline*:
nothing stops a future tool from calling a repository directly and skipping `PermissionEvaluator`
— exactly the class of defect `RepositoryTransactionTest` exists to catch, which is evidence it
happens.

### Option B — FastMCP Python sidecar

A separate Python process calling the TCMS REST API.

**For:** the flagship FastMCP, with the deepest auth, middleware, and deployment story. The safety
property becomes **structural rather than disciplinary** — the only door into TCMS is the REST API
and its filter chain, so an MCP bypass is not possible to write. Ships as `uvx tcms-mcp`, which is
a far better story for a Claude Code config than a JAR path. Per-request tool filtering is a
framework primitive fed by `GET /me/permissions`.

**Against:** a **third stack**. Today the repo has Gradle and npm; this adds Python, its own test
runner, its own lint gate, its own CI lane, its own dependency policy, and a third thing to keep in
step with `openapi.yaml` — with no existing type pipeline to reuse. Tools are capped at what REST
exposes. Breaks "one deployable."

### Option C — FastMCP TypeScript sidecar

Same architecture as B, in the language the frontend already uses.

**For:** everything in B's "for" column, plus **no third language**. The repo already runs npm, and
already generates typed API bindings from `openapi.yaml` via `npm run gen:api` — so the MCP
server's calls into TCMS are typed against the same contract the SPA uses, and a contract change
breaks its build the same way it breaks the SPA's. ~~Ships as `npx @tcms/mcp`.~~ → **superseded
2026-08-23**: it ships as a GHCR image for the hosted endpoint, and as a tarball the instance serves
at `/downloads` for the stdio shape — `docs/specs/client-distribution.md`.

**Against:** the TypeScript FastMCP is the counterpart, not the flagship — expect a thinner feature
set and less documentation than B. Still breaks "one deployable." Still capped at the REST surface.
Adds a third `package.json` concern to a repo whose npm lane currently means "the SPA".

---

## 4. Recommendation

**Option C — FastMCP TypeScript sidecar**, if FastMCP is wanted at all; **Option A** if "one
deployable" is the binding constraint.

Three reasons C over B:

1. **The contract pipeline already exists.** `openapi.yaml` → `npm run gen:api` → typed bindings is
   a working seam this repo already maintains and already has a rule for ("one contract holder").
   A Python sidecar would need a second, parallel generation story or hand-written request code
   that can silently drift from the spec — and drift-between-two-implementations-of-the-same-rules
   is the exact defect class that killed the mock layer (§52, §81, §98).
2. **No third stack.** Two build systems is already the documented shape of this repo. Three is a
   new standing cost on every gate, every CI change, and every onboarding pass.
3. **`npx` distribution.** *(The reasoning below still holds; the channel does not — see the
   strikethrough above. Nothing here depended on the registry being npmjs.org, only on the artifact
   being client-side and small, which the served tarball also is.)* stdio-first means the server is
   a process the *user's* agent spawns, and
   the `api_token` is a *user's* credential bound to one user and one project. The MCP server is
   therefore naturally a client-side artifact, not part of the deployed system — which is an
   argument for a sidecar generally, and for the one that ships over npm specifically.

And the honest case for A: it is the only option that keeps the deployment shape CLAUDE.md
describes, the only one that can expose a tool the REST API cannot, and the only one whose tests
are the tests we already have. If the answer to "is one deployable load-bearing?" is yes, A wins
outright and FastMCP is simply not available to us.

---

## 5. Consequence: correct §6w.3's authority model

This holds **in every option** and should land in the plan regardless of the lane chosen.

§6w.3 says an MCP caller "is simply a caller holding whatever its token's user holds." The schema
disagrees: `api_token.role_id` means a token carries **a role**, chosen at issue time, not the
issuing user's grants. The `COMMENT` states the intent plainly — a CI token holds Automation
Service "and nothing more," and "a leaked token can post results and add quarantine stubs, and
cannot edit or delete a real case."

That is a *stronger* property than the plan claimed, and it changes the wave-1 design in one
concrete way: **an MCP token is issued against a role, and the agent's reach is that role — not the
human's.** So:

- The audit line §6w.3 asks for becomes more precise, not less: "Ana's agent, holding the
  *Read-Only Agent* role on project X, did it."
- `mcp:connect` is a permission on a role, and there is a question the implementation plan must
  answer: **is there a new built-in role for MCP callers, or does `mcp:connect` get added to
  existing roles?** §A2 fixes the role set at six, so a new role is a decision, not an import — the
  same shape as §125's "a fifth foundation module is a decision."
- Per-session tool filtering asks `GET /me/permissions` **as the token**, and gets the token's
  role's permissions. Which is correct, and is what should be documented.

---

## 6. Consequence: `via = MCP` needs a column or a derivation

`audit_event` records `actor_user_id`, `actor_token_id`, `ip`, `user_agent`, `request_id` — but no
channel. §6w.3 requires `via = MCP`.

**It must be derived server-side, never taken from a request header**, or any caller can forge it.
Two options for the implementation plan:

- **Derive it from the token.** If MCP tokens are distinguished by their role (see §5), then
  `via` is a function of `actor_token_id` and needs no new column — the audit *query* joins to the
  role. Cheapest, and it stays true without a migration.
- **Add the column.** A `via TEXT` on `audit_event`, set by the filter that authenticated the
  request. More explicit, survives a role being reused for something else, costs a Flyway version.

Recommend deriving first; add the column only if the audit surface actually needs to filter on it.

---

## 7. Consequences of choosing a sidecar (B or C)

Each of these is a real cost and should be accepted knowingly, not discovered later.

| Consequence | Detail |
|---|---|
| **"One deployable" is amended** | CLAUDE.md's Architecture section says one deployable. It would become: one deployable **plus an optional client-side MCP server that is not part of the deployment**. That distinction is what makes it defensible — the sidecar is not something the operator installs on the VM. Write the amendment struck-through-and-superseded, per the working agreement. |
| **The tool surface is capped by REST** | Anything an agent should see must have an endpoint. Today's M1 read surface covers wave 1; `repository_health` and `coverage_summary` may not exist yet and would become REST work first. |
| **A second contract consumer** | `openapi.yaml`'s "one contract holder" rule now has two downstream generators. They can regenerate independently; they must not disagree. |
| **A second test lane** | Vitest against a mocked TCMS for the tool layer, plus at least one integration test against a real `docker compose up` — because the whole point is that the round-trip is the real one (the §109 lesson). |
| **Deploy/CI** | `.github/workflows/ci.yml` gains an npm lane for the package; `deploy.yml` does not, because the package is published, not deployed. |
| **Rate limiting** | §6w open question 6 answers "per token, generous." In a sidecar this lands on the **REST** side, where it protects the database regardless of caller — which is the right place for it anyway. |
| **Verification queue** | The slice records rows in `docs/verification/backend-mutation-checks.md` for the token/permission path, per §65. A stdio MCP server is not a browser lane. |

---

## 8. What this decision does *not* settle

Deliberately deferred to the implementation plan:

- The wave-1 tool list and each tool's input/output schema, including how UUIDs are resolved to
  display keys and how results are paginated and truncated.
- Whether `list_criteria` / `get_doc_page` survive contact with the `documentation` module as
  actually built — the plan specified them against a module that did not exist when it was written,
  and slice-10 work has been building it out since. **Re-read `docs/agent-context/be-java.md` and
  the documentation module before writing those two tools.**
- The `mcp:connect` role question from §5.
- Streamable HTTP (deferred behind stdio per open question 5).
- Wave-2 write tools and `propose_cases` — those are slice 5, gated on `ai_proposal`.

---

## 9. Decision record

| | |
|---|---|
| **Decision** | _pending_ |
| **Decided by** | |
| **Date** | |
| **Supersedes** | §6w.3's "Spring AI MCP / the Java MCP SDK" line, if B or C is chosen |
