# Live pass — `packages/` against a real TCMS (§143)

**Run:** 2026-08-08 · **Instance:** `docker compose` at `localhost:8080`, schema **V43** (head),
image rebuilt from `26b42c8` so §138–§142 are actually present — the container that had been running
was two days old and predated `ai_proposal` and the rate limit.
**Company:** `livepass` "Live Pass Co" (`e4e91a53-b022-4787-a5d0-f6ce5098c873`), project `LP`
"Live Pass Product" (`afc4316b-2d3f-42cd-b7b9-99fb9e9c08ab`), admin `ops@livepass.test`.
**Credential:** an **`Agent`** token bound to `LP`, issued through `POST /api-tokens` by the
Company Admin — 23 effective permissions.

> **This is the gap §137 opened and §140/§142 widened**: nine read commands, sixteen write commands
> and 21 MCP tools, none of which had ever spoken to a real instance. Every test in `packages/`
> drives a stubbed `fetch`, and a stub that agrees with the contract cannot see the server
> disagreeing with its own `openapi.yaml` — the same shape of observer as the mock layer §107
> deleted. It is neither a browser lane nor a mutation, so §65's two queues do not own it.

**Four defects, none of which any gate could see.** Two are fixed in this slice; two are recorded
and not fixed, because both need a contract change and the second changes a shipped surface.

---

## What was driven

| Surface | Result |
|---|---|
| `provision-company`, invitation redemption, login | ✅ worked as documented, including the *undelivered invitation* warning |
| `POST /api-tokens` — the `Agent` expiry rule | ✅ refused without `expiresAt`, in §133's words |
| `GET /roles` | ✅ still **lists** `Agent` and `Automation Service` while both are `assignable: false` — §133/§134's four-axis distinction, live |
| CLI reads — `whoami`, `project/case/suite/plan/run/doc list`, `case/doc show` | ✅ all nine answer; `whoami` renders permissions and qualifiers correctly (§140's fix) |
| CLI exit codes | ✅ 0 on success, 1 on 403/404 — checked without a pipeline in the way |
| CLI writes — `suite create/edit/status`, `doc create`, `plan create/status`, `case propose` | ✅ except where noted below |
| `run create` with nothing in scope | ✅ refused with the field error, propagated intact |
| `suite status --status APPROVED` | ✅ 403 — the `Agent` ceiling holds; it has `suite:manage`, not `suite:approve` |
| `POST …/ai-proposals/{id}/accept` with the token | ✅ **403, in §138's words** — the gate no permission can express, working |
| MCP `initialize` over `--http` | ✅ per-session `instructions` carry *this* caller's name, project and permission count (§140) |
| MCP `tools/list` | ✅ 21 tools |
| MCP `tcms_orient`, `tcms_search_cases`, `tcms_list_suites`, `tcms_write_suite`, `tcms_write_case propose` | ✅ |
| MCP `tcms_write_case` **edit** | ❌ **F3** |

---

## F1 — Every 4xx an API token provokes was answered as **401** · FIXED

**Severity: high.** **Product defect**, backend. The headline finding.

An API-token caller that sent a body Spring could not read was told:

> The credential was not accepted. It may be revoked, expired, or for another instance.

The credential was fine. The *field* was wrong. Measured across five container-level errors, with a
session as the control:

| Provoked | API token | Session |
|---|---|---|
| unreadable body (unknown enum value) | **401** | 400 |
| malformed JSON | **401** | 400 |
| wrong `Content-Type` | **401** | 415 |
| wrong method | **401** | 405 |
| unmapped path | **401** | 404 |

**Cause.** All of these are resolved by `DefaultHandlerExceptionResolver`, which calls
`response.sendError`. In a servlet container that begins a second trip through the entire filter
chain — the **ERROR dispatch** — against `/error`, which sits behind `.anyRequest().authenticated()`
like every other path. `ApiTokenAuthenticationFilter` is a `OncePerRequestFilter`, whose
`shouldNotFilterErrorDispatch()` **defaults to `true`**, so the token was not re-authenticated on
that second trip, `/error` was denied, and `HttpStatusEntryPoint(UNAUTHORIZED)` overwrote the real
status. A session was unaffected: the resource server's own bearer filter re-authenticates there.

**Why 2302 tests missed it.** Every endpoint test in this suite drives **MockMvc, which performs no
ERROR dispatch.** It records the status the resolver set and stops. The suite is not wrong about the
handler; it has simply never observed the thing the container does afterwards. This is a second
instance of §58's lesson — a whole class of behaviour that the only observer structurally cannot
see — and unlike §58 the blind spot is the test framework rather than the operating system.

**Why it matters more here than it looks.** The callers that authenticate with a token are exactly
the ones built in §137–§142: the CLI, CI jobs, and the MCP server. `describeRefusal`'s 401 branch
tells them the credential is bad, and for the MCP surface the party acting on that is a **model**,
whose remedy — obtain a new token — cannot possibly work. It is §141's finding one layer down:
there the refusal did not say *when* to retry; here it says the wrong thing entirely.

**Fix.** `ApiTokenAuthenticationFilter.shouldNotFilterErrorDispatch()` returns `false`, with the
reason in the class header. New `ErrorDispatchStatusTest` — `@SpringBootTest(webEnvironment =
RANDOM_PORT)` + `TestRestTemplate`, because **this cannot be written with MockMvc** — pins all five
statuses plus the control that a genuinely bad credential is still 401. Verified failing without the
override (the three token assertions fail; both controls stay green) and passing with it, and
re-verified against the rebuilt image.

## F2 — `TestCaseInput` declares one required field and the server requires four · NOT FIXED

**Severity: high.** **Contract defect.** `docs/specs/openapi.yaml` §`TestCaseInput` says
`required: [title]`. `CaseEdit.validate` requires **`title`, `stepFormat`, `priority` and
`automationStatus`**, on *both* `POST …/cases` and `PATCH …/cases/{id}`:

```
POST /cases {"title":"Contract minimum"}
400 {"errors":{"stepFormat":[…],"priority":[…],"automationStatus":[…]}}
```

The server is deliberate — each check carries its reasoning — so **the contract is what is wrong**,
and it is wrong in the direction that generates broken clients: both copies of `schema.d.ts` type
three mandatory fields as optional, so a caller that omits them compiles, typechecks, and 400s.

**Its worst consequence is that every proposal a model files is dead on arrival.** `POST
…/ai-proposals` stores the payload **unvalidated** — by design; the contract says so — and the
create-path validation runs at *accept*. The contract's accept description anticipates a 400 there
and describes it as **drift**: *"a suite that has since been deleted, a retired shared step, a
vocabulary value that no longer exists"* — things that were valid when filed and stopped being
valid. What actually happens is that the payload was **never** valid, because the schema told the
agent those fields were optional. `tcms_write_case`'s own tool description repeats it:
*"title (required), description, objective, expectedOutcome, priority, severity, steps…"* — it does
not list `stepFormat` or `automationStatus` at all.

Driven end to end: a proposal filed through `tcms case propose` was accepted (201), sat as
`PENDING`, and the human reviewer's accept answered 400 on three fields **that are not on the
proposal and cannot be edited there**. The reviewer's only remaining action is Reject — for a case
whose content is fine.

**Fix not attempted here** because it is a contract change that must be regenerated into two
`schema.d.ts` copies and then compiled against both stacks, and because it is coupled to F3.

## F3 — `case edit` and `tcms_write_case` edit send deltas the server cannot accept · NOT FIXED

**Severity: high.** **Client defect**, `packages/`. Follows from F2 and is the sharper half.

`PATCH …/cases/{id}` requires the whole editable set — `CaseUpdateRequest`'s header states this
outright (*"requires the client to send the whole editable set. The SPA does exactly that"*) — and
both write clients §139/§140 built send only what the caller named:

```
tcms case edit TC-1 --title "Renamed by the CLI"
  stepFormat: Choose a step format.
  priority: Choose a priority.
  automationStatus: Choose an automation status.
```

**Every single-field case edit through the CLI or the MCP server fails**, and cannot ever have
worked. The stubs pass because they answer 200 to whatever body arrives. The SPA is unaffected,
which is why the server was never wrong about this — it has exactly one other client and that one
submits every field it renders.

The fix is client-side read-modify-write: read the case, merge the named flags onto it, send the
full set. That is **consistent with §139's rule** rather than in tension with it — §139 established
that a flag edit *"states its whole intent in its flags"*, so a read immediately before the write
is truthful; it is the `--file` form that must keep requiring `--lock-version`. Making the server's
PATCH genuinely partial is the alternative and `CaseUpdateRequest`'s header already declines it
(it needs `JsonNullable` or a presence map — a shape change on the contract).

## F4 — A validation rejection reached the model with no fields · FIXED

**Severity: medium.** **Client defect**, `packages/mcp`.

A 400's `detail` is *"Check the highlighted fields."* — copy written for a form. The fields are in
RFC-7807 `errors`, which `TcmsApiError.fieldErrors` carries and **only `@tcms/cli` was reading**
(`cli/src/main.ts:133`). `mcp/src/result.ts` never touched it, so the one caller that cannot see a
screen was the only one receiving that sentence alone — and its next move is to resend the same
body, which is the loop §141 spent a slice closing one status over.

**Fix.** `failed()` appends the per-field messages, keyed as on the input schema so they name the
tool's own arguments. Two tests, and verified live: the same `tcms_write_case` edit now returns the
sentence followed by `stepFormat:`, `priority:` and `automationStatus:` — which is also how F3 was
found from the model's side rather than the CLI's.

---

## Notes, not defects

- **`whoami` prints "Scope: no project (company-wide terms only)"** while listing 23 PROJECT-scope
  keys. Checked and correct: the token's *owner* is a Company Admin whose grant is COMPANY-scope, so
  those keys genuinely are company-wide terms. Worth knowing before reading it as a bug — but the
  token is bound to `LP` and `whoami` does not use that binding when asking for permissions, so the
  summary is broader than the credential's actual reach. Not pursued.
- **`tcms help <command>` prints the general help**, not that command's usage; `tcms <command>
  --help` prints the usage correctly. Cosmetic.
- The `V43` head applied **eight migrations onto an existing volume** without an out-of-order
  refusal, which is the numbering rule holding under the only condition Testcontainers cannot test.
