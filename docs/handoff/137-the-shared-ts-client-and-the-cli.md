# §137 — the shared TS client, and the deadline a `Request` constructor throws away

**2026-08-08 · three phases · no migration · no contract change · nothing a user of the SPA can
see.** The MCP WIP plan's **phases 4 and 5** — the shared TypeScript client generated from
`openapi.yaml`, the command core the surface spec §4 specifies, and the CLI's read commands.

It is the first slice in this repo that adds a **stack** rather than a feature. `packages/` is a
third build beside `backend/` and `frontend/`, with its own npm workspace root at
`packages/package.json` so the repository root still runs only `docker compose` — the invariant
`CLAUDE.md` records. `@tcms/cli` and, later, the MCP server resolve `@tcms/client` through the
workspace rather than through a `file:` dependency or a publish step between every change.

---

## 1. Why phase 4 was not allowed to ship alone

The plan's phase 4 is *"shared TS client from `openapi.yaml`"* and nothing else. Building only that
would have reproduced the exact shape this project has now found five times — §116's `api_token`,
§117's `ingestion_job` and `automation_key_mapping`, §121's `findQuarantined`, §132's three
attribution columns: **a seam written ahead of its consumer, which guesses.** §121's case is the
sharpest, because the seam it wrote was deleted rather than used: `CaseFlakeRepository.findQuarantined`
had a javadoc naming a caller that could never have called it, since `work` may not import
`repository`.

So phase 5 — the CLI's read commands — landed in the same slice, and the client has a consumer in
`packages/cli/src` before anything about it is settled. That decision paid immediately: three of
the client's shapes were wrong in ways only a caller could show, and the compiler said so (§4).

## 2. Generated, not hand-written — and what that replaces

`frontend/src/api/` spends **~450 lines of AST walking** in `contractOperations.test.ts` and
`contractParameters.test.ts` reconstructing what each hand-built template literal *meant*, in order
to compare it against the contract. It exists because a URL built by string interpolation can name
a path the contract does not declare and nothing else notices.

Here the path and the method **are the type parameters** (`openapi-fetch` over
`openapi-typescript`'s output), so a path the contract does not declare, a method that path does not
have, a missing path parameter and a response field that was never on the wire are all compile
errors. Same guarantee, obtained from the generator rather than re-derived from the source.

**This is not an argument for rewriting the SPA's client.** That one fetches through TanStack Query
with per-endpoint budgets, a dev fault seam and a browser session; its checks cover call sites that
already exist and would still need covering. The two clients stay near-copies **on purpose**, and
`packages/client/src/errors.ts` says so at the top: what they genuinely share is the *wire format*,
which `openapi.yaml` owns and both read.

### 2.1 `schema.d.ts` has a twin now, and one test is the only thing watching it

`packages/client/src/schema.ts` is generated from the same contract by the same tool and is
**byte-identical** to `frontend/src/api/schema.d.ts`. `schemaFreshness.test.ts` reads `openapi.yaml`
itself for the path list and then compares the two files.

That comparison is the load-bearing half. Checking either generated file against the spec alone
cannot see the failure that matters — **one stack regenerated after a contract change and the other
not** — because each file is internally self-consistent. `npm run gen:api` is two commands now.

## 3. Two defects the tests found, both silent, both in the transport

Neither was a slip in reasoning; both are places where a library or a runtime does something other
than the obvious reading.

### 3.1 openapi-fetch does not pass `init` to `fetch` at all

It calls `fetch(new Request(...))` with **no second argument**, and copies any init member it does
not recognise onto the Request object as a property. The first transport read `init?.method`,
`init?.headers` and `init?.body` — the shape every `fetch` wrapper has — so **every request from the
generated client would have gone out as a GET with no headers and no body.** The header assertion in
`client.test.ts` is what caught it; nothing about the code looked wrong.

### 3.2 A signal read back off a `Request` does not reliably fire

Measured on Node 22.12: `new Request(url, { signal }).signal` **never dispatches its abort event and
never reports `aborted`**, while the same construction with any other init member present does. So a
deadline handed to a copy-constructed `Request` can simply stop existing, and the request hangs for
as long as the peer keeps it open — **the failure the budget exists to prevent, arriving through the
budget.**

It reads as the option not being applied rather than as the signal being lost, which is why the
first two attempts at a fix were aimed at the option, and why the third — "hold the signal in a
local, it must be getting collected" — was *also* wrong and was written into a comment before being
measured. The comment now states what was measured. The transport and the test stub both pass the
signal in the init and neither reads the derived one; `TS-1` in the mutation queue is the row that
pins it, and if that row does not redden, the workaround is unnecessary.

## 4. What the types caught while the CLI was being written

Three call sites, all in `doc show`, all of the kind a hand-written client discovers in production:

- a doc block's field is **`blockType`**, not `type`;
- its **`links` is optional** on the wire, so absence and an empty array are two different answers —
  "not asked for" against "asked for, and there are none";
- `text` is required, so `row.text ?? ''` was dead code implying otherwise.

## 5. Three findings from reading the contract against the design

### 5.1 The refusal copy cannot name a role, and no amount of care fixes it

Surface spec §4.5 asks a 403 to name *"the missing permission and the **lowest built-in role that
holds it**"*. Naming the role needs the role→permission catalogue, and **this credential cannot
obtain it**:

- `GET /roles` is the only endpoint carrying it;
- `AdminDirectoryService.listRoles` answers `roleCatalogue()` only when `reachOf(userId).readsCompany()`;
- `V40` §6 withholds `company:read` from `Agent` **deliberately** — *a token is not a member*.

So an agent token gets `[]` — not a 403. The data is not merely unreachable; its absence is
indistinguishable from an empty catalogue, so a client cannot even detect that it is missing.

Three ways to have it anyway, all refused for now: baking the catalogue into this package (a second
copy of roles spec §A3, and worse than the usual case because `@tcms/cli` is versioned independently
of the instance it is pointed at, so the copy is a claim about a server it has never seen —
`JsonReportRenderer`'s recorded argument, one client over); generating it at build time from
`V3`/`V40`/`V41` (derived staleness is staleness); or a contract change exposing role→permission
outside `company:read`, which is a decision about what a caller may learn about the instance's role
model and is not this slice's to make.

**So the copy says less than §4.5 permits, never more**, and that direction is the safe one: the
spec's own §8.4 test — the 404 branch names no role and no permission — holds a fortiori, and the
403 branch still gives the user the actionable half.

### 5.2 §4.4's example key does not exist

The spec gives `SU-7` for a suite. `V5` seeds exactly `TC- TS- TP- TR- DOC-`; there is no `SU-` and
never was. A client accepting it would resolve nothing and report "not found" for a key the user
believed in. `TS-5` in the mutation queue is that row.

### 5.3 Four of the six list endpoints cannot paginate, and only one key has a server-side lookup

`GET …/cases` is paged and short-circuits a key-shaped `q` to an exact lookup (§6q). **Suites,
plans, runs and doc-pages answer plain arrays** — no `page`, no `size`, no `q`.

Two consequences, opposite in sign:

- **Resolution is cheap.** `TS-7` costs one request and a client-side `find`, not a walk over pages.
- **§4.6 does not mean there what it means for cases.** *"Default page size 25 on every list tool"*
  is, for those four, a client-side truncation of a response that already arrived whole: it bounds
  the tokens the model sees and not the bytes the server sent.

A `q` or `key` filter on those four lists is the contract candidate that fixes both, and it would
give `tcms_list_*` something real to paginate.

## 6. What this slice deliberately did not do

- **No writes.** Phase 7 is CLI writes, and it is gated on phase 6 (`ai_proposal`) for the create
  arm, per surface spec §5. The core's write machinery — `Idempotency-Key`, `If-Match`, the
  concurrency types — is built and tested here because it is what the *core* is; nothing calls it
  yet.
- **No MCP server.** Phase 8.
- **No config file and no keychain.** The credential comes from `TCMS_TOKEN` and the instance from
  `TCMS_URL` or `--url`. Both of the other options are decisions about where a credential rests on
  disk, and an environment variable is the one form that is already how CI holds a token and how an
  MCP host injects one.
- **Nothing ran against a live instance.** See below — this is the sentence that matters most.

## 7. Gates, and what they do not cover

| Gate | State |
|---|---|
| `cd packages && npm run build --workspaces` | clean (both packages, `tsc` twice each: source+tests, then emit) |
| `cd packages && npm run lint --workspaces` | 0 warnings |
| `cd packages && npm run test:run --workspaces` | **82 passed** — 68 in `@tcms/client` across 4 files, 14 in `@tcms/cli` |
| `cd backend && ./gradlew build` | **not run — this slice changed no Java.** The head figure stands at §136's 2242. |
| `cd frontend && npm run build` / `lint` / `test:run` | **not run — this slice changed no frontend source.** `schema.d.ts` was not regenerated because `openapi.yaml` did not change. |

**`ci.yml` gained a third job** (`packages`), which — unlike the backend job — **does** run its
tests: the suite is ~0.5s of pure Node with no container to boot, so the trade `-x test` buys on
the backend does not exist. It is not in the `lab` deploy's `needs`: these packages are published,
not deployed, so a red package job must not stop a good backend from reaching the box where
unverified code is supposed to be observed.

> **⚠ NO COMMAND IN `@tcms/cli` HAS EVER BEEN RUN AGAINST A REAL TCMS.** Every test drives a
> stubbed `fetch`. The types enforce the contract and the stubs enforce the behaviour, and the one
> thing neither can observe is **the server disagreeing with its own `openapi.yaml`** — which is
> precisely the class of defect the mock layer hid for three slices before §107 deleted it (§52's
> `entries`/`runs`, §81's review cards, §98's suite arms). A stub that agrees with the contract is
> the same shape of observer as a mock that agreed with itself.
>
> This is not a §65 deferral: a CLI read is neither a browser lane nor a mutation, so no queue owns
> it. It is a **gap with no home**, stated here rather than left to be discovered. The cheapest
> closure is one live pass — `docker compose up`, `provision-company`, issue a token, run the nine
> read commands — and it needs a person at a terminal only because it needs a provisioned company.

## 8. Verification obligations

Nine rows added to `docs/verification/backend-mutation-checks.md` as **`TS-1` … `TS-9`** — the
queue's first rows that are not `./gradlew`. **`TS-4` is to be run first**: it is the only row in
the whole queue whose failure is a security finding rather than a defect (the 404 branch becoming
the 403 branch turns this surface into the enumeration oracle §6t exists to prevent). `TS-2` second,
because its failure is a silent overwrite of somebody else's edit.

`TS-9` records a **known gap that is now newly reachable**: nothing in either stack drives two
concurrent creates carrying one `Idempotency-Key`. §136's `IK-1` recorded the same hole from the
server side; this slice adds the first client that could actually produce the race.

Nothing goes in the frontend browser queue — neither a CLI nor a remote MCP server is a browser
lane, which the WIP plan's §9 states.

## 9. What is next

The plan's **phase 6** (`ai_proposal` and its review surface) is the next thing, and it is backend
work that must precede any generation tool. Phase 7 (CLI writes) is gated on it for the create arm
only; the edit arm is buildable today, and the core it needs is already here and tested.

The standing alternative is another re-derivation from the code (§28, §29, §46), which is what
found §132 and what §123 named to make round 4 cheap.
