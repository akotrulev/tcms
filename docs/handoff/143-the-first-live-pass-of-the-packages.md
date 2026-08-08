# §143 — the first live pass of `packages/`, and the 401 that was never about the credential

**2026-08-08 · `master` · one phase · `backend/`, `packages/mcp/`, `docs/` — no migration, no
contract change, no frontend code.**

The handoff named this slice on its way out of §142: the MCP plan has no phases left, and *"the live
pass is now the stronger candidate than either"* — three complete packages, **none of which had ever
spoken to a real TCMS.** Nine read commands, sixteen write commands, 21 MCP tools, and every test
behind them driving a stubbed `fetch`.

Its only recorded blocker was *"a person at a terminal is needed only because a company must be
provisioned"*. That turned out to be false in the cheap direction: `docker compose` was already up,
and `docker compose run --rm backend provision-company` is a tool call like any other. **The blocker
note outlived its blocker — the seventh instance this project has logged**, and this one had been
written the previous day.

The full evidence log, including everything that worked, is
[`docs/verification/2026-08-08-packages-live-pass.md`](../verification/2026-08-08-packages-live-pass.md).
**Four defects, none reachable by any gate.** Two are fixed here; two are recorded and deliberately
left, and they are named at the bottom as the next slice.

> **The container had to be rebuilt first, and that is worth writing down.** The running backend was
> two days old — it predated `ai_proposal` (§138) and the rate limit (§141). A live pass against it
> would have been a live pass against §137's world, reported as §142's. Eight migrations then applied
> to the existing volume without an out-of-order refusal, which is the Flyway numbering rule holding
> under the one condition Testcontainers structurally cannot test: a database that was already
> migrated.

---

## The finding: every 4xx an API token provoked came back as 401

Send a case with `"automationStatus": "NONE"` — not one of the four values — and the server answers:

```
HTTP/1.1 401
Content-Length: 0
```

and `@tcms/client` turns that into the sentence it is supposed to:

> The credential was not accepted. It may be revoked, expired, or for another instance.

The credential was fine. The **field** was wrong. It is not one endpoint and not one exception:

| Provoked | API token | Session |
|---|---|---|
| unreadable body (unknown enum) | **401** | 400 |
| malformed JSON | **401** | 400 |
| wrong `Content-Type` | **401** | 415 |
| wrong method | **401** | 405 |
| unmapped path | **401** | 404 |

**The mechanism is the ERROR dispatch.** All five are resolved by `DefaultHandlerExceptionResolver`,
which calls `response.sendError`. In a servlet container that starts a *second* trip through the
whole filter chain, against `/error` — which sits behind `.anyRequest().authenticated()` like
everything else. `ApiTokenAuthenticationFilter` is a `OncePerRequestFilter`, and
**`shouldNotFilterErrorDispatch()` defaults to `true`**, so on that second trip nothing
re-established the token's authentication, `/error` was denied, and
`HttpStatusEntryPoint(UNAUTHORIZED)` overwrote the status the resolver had already decided.

A session was untouched, because the resource server's own bearer filter re-authenticates there.
That asymmetry is what made the defect invisible in the direction anybody was looking: the SPA is
correct, so no browser pass could ever have found it, and the queue §65 defers is full of browser
passes.

### Why 2302 tests agreed with each other about a status the server does not produce

**Every endpoint test in this suite drives MockMvc, and MockMvc performs no ERROR dispatch.** It
records the status the resolver set and stops. The suite is not wrong about the handler — it has
simply never observed what the container does afterwards, and there is no assertion anywhere that
could have noticed, because the thing that overwrites the status happens after the last line MockMvc
executes.

This is §58's lesson a second time with a different observer. There it was macOS versus Linux — a
defect that *cannot* fail on the machine running the gate. Here it is MockMvc versus a servlet
container, and the blind spot belongs to the test framework rather than to the operating system,
which makes it worse: the nightly Linux lane added in §113 does not help, because it runs the same
MockMvc.

So `ErrorDispatchStatusTest` is `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a real Tomcat
and `TestRestTemplate` — **the assertions in it cannot be written any other way**, and the class
header says so, because the obvious "simplification" to MockMvc would leave five green tests
asserting nothing. It pins all five statuses, the session control, and the branch a careless fix
breaks: an unknown `tcms_`-prefixed secret must still be 401. Verified by reverting the override —
the three token assertions fail, both controls stay green.

### What it cost, and why it is §141's finding one layer down

The callers that authenticate with a token are precisely the ones §137–§142 built: the CLI, CI jobs,
and the MCP server. §141 spent a slice on the principle that **a refusal which does not say what to
do next produces the loop the refusal exists to prevent** — the caller is a model, and an
unexplained failure is the one it retries. This is the same defect having got worse: the refusal was
not unexplained, it was *explained wrongly*, and the remedy it names — obtain a new credential —
cannot work no matter how many times it is followed.

**Fix:** four lines and a header. `shouldNotFilterErrorDispatch()` returns `false`. The cost is one
extra digest lookup on a request that has already failed.

## The second fix: a validation rejection reached the model with no fields in it

A 400's `detail` is `"Check the highlighted fields."` — copy written for a form. The fields
themselves are the RFC-7807 `errors` map, which `TcmsApiError.fieldErrors` has carried since §137
and which **only `@tcms/cli` was reading** (`cli/src/main.ts:133`). `mcp/src/result.ts` never touched
it.

So the one caller in this project that **cannot see a screen** was the only one being handed a
sentence about looking at one, and what a model does next with an unactionable refusal is resend the
same body. `failed()` now appends the per-field messages, keyed as on the input schema so they name
the tool's own arguments.

It is also how the third defect below was found from the model's side rather than the CLI's — which
is the argument for the fix in one line: the field names are the finding.

## Two defects recorded and NOT fixed, and they are one slice

Both need a contract change, and the second changes a surface that has shipped.

**`TestCaseInput` says `required: [title]`; the server requires four.** `CaseEdit.validate` demands
`title`, `stepFormat`, `priority` and `automationStatus` on create *and* on edit, deliberately and
with its reasons written down — so the **contract** is the thing that is wrong, in the direction that
generates broken clients. Both copies of `schema.d.ts` type three mandatory fields as optional.

Its sharpest consequence: **every proposal a model files is dead on arrival.** `POST …/ai-proposals`
stores the payload unvalidated by design, and the create-path validation runs at *accept*. The
contract's accept description anticipates that 400 and calls it **drift** — *"a suite that has since
been deleted, a retired shared step, a vocabulary value that no longer exists"*, things that were
valid when filed. What actually happens is that the payload was **never** valid, because the schema
told the agent those fields were optional and `tcms_write_case`'s tool description repeats the
claim. Driven end to end: filed 201, sat `PENDING`, and the human reviewer's accept answered 400 on
three fields that are not on the proposal and cannot be edited there. The reviewer's only move is
Reject, for a case whose content is fine. **§138's review boundary works; what waits behind it
cannot be accepted.**

**`case edit` and `tcms_write_case`'s edit arm send deltas the server cannot take.** `PATCH
…/cases/{id}` requires the whole editable set — `CaseUpdateRequest`'s own header says so, and names
the SPA as the client that does it — while both write clients send only what the caller named. So
`tcms case edit TC-1 --title x` answers with three field errors about fields the user did not
mention, and **has never worked once.** The stubs pass because they answer 200 to whatever arrives.

The fix is client-side read-modify-write, and it is **consistent with §139's rule** rather than in
tension with it: §139 established that a flag edit *"states its whole intent in its flags"*, so a
read immediately before the write is truthful — it is the `--file` form that must keep requiring
`--lock-version`. Making the server's PATCH genuinely partial is the alternative, and
`CaseUpdateRequest` already declines it in writing (it needs `JsonNullable` or a presence map).

---

## What the pass confirms, which is most of it

Worth stating plainly, because a findings list reads as though everything is broken. Provisioning,
invitation redemption and login work as documented, including the undelivered-invitation warning.
`GET /roles` still **lists** both non-assignable ceilings, which is §133/§134's four-axis
distinction surviving contact with a real database. All nine reads answer, `whoami` renders
qualifiers correctly, and exit codes are right. The `Agent` ceiling holds — `suite:manage` yes,
`suite:approve` 403. **§138's gate refuses a credential's own accept, in its own words.** The MCP
server's per-session `instructions` carry this caller's name, project and permission count, which is
§140's finding working in the deployment shape it was written for.

## The method note

The gap this closed was named in §137 and restated in every handoff since, and it stayed open
through five slices while being described as *"neither a browser lane nor a mutation, so §65's two
queues do not own it"*. That description was accurate and it was also the reason nobody ran it: work
with no queue to belong to has no row to leave undone. **Four defects in one afternoon, three of
them high**, is what the un-queued observer was worth — and two of them are in the *client* code the
stubs were written to test.
