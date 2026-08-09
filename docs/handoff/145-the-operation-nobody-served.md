# §145 — the second live pass, and the operation nobody served

**2026-08-09 · one phase · `backend/` only: no migration, no contract change, no frontend or
packages code.**

§144 fixed four defects that only a live pass could find, and verified them with the observers that
had missed them — a stubbed `fetch`, a jsdom, a MockMvc. It said so in its own last section and left
eight rows, `LP-1`…`LP-8`, as the things a real instance still had to be asked. This slice asks them.

**Seven passed. `LP-3` failed, and the cause is one line wider than the row: a contract operation
that has never been implemented, with three consumers and no observer.**

Evidence:
[`docs/verification/2026-08-09-packages-live-pass-round-2.md`](../verification/2026-08-09-packages-live-pass-round-2.md).

---

## What the seven confirm

Round 1 (§143) drove the read surface and a handful of writes. This one drives the edit surface
§144 rewrote, on a fresh company (`lp2` / `LP2`) with an `Agent` token holding the same 23
permissions, against an image rebuilt from `b1ad572`:

- **`LP-1`** — `case edit TC-1 --title x`: a command that had **never once worked** now moves
  `title` and the version metadata and nothing else. Description, objective, expectedOutcome,
  severity, both steps *with their ids*, tags and `suiteId` all survive the round-trip. That is
  `core/rewrite.ts` doing exactly what it was written to do, observed rather than stubbed.
- **`LP-2`** — `--unfile` clears `suiteId` and touches nothing else, so the explicit null survives
  the merge that preserves everything else.
- **`LP-4`** — the silent-data-loss row. A plan with `scopeText` and `outOfScopeText` authored
  first, then `plan edit TP-1 --milestone GA2`: the milestone changes and **both prose fields are
  still there**. Before §144 every plan edit either client had ever made emptied them, reachable
  from no flag and no tool argument.
- **`LP-5`** — the MCP edit arm, §143's F3, clean.
- **`LP-6`** — `case propose --file` with only a title is refused **client-side**, exit 1, naming
  `stepFormat`, `priority` and `automationStatus` **together**. Proved with a recording proxy in
  front of the backend rather than by reading the code: the only request that reached the server
  was the project lookup.
- **`LP-8`** — a stale `--file` write is a **409**, confirmed at the wire (`If-Match: "3"` → 409),
  not a silent overwrite.
- **`LP-7`** — the end-to-end proposal path, which had never completed: filed 201 → `PENDING` →
  accept with the **token** 403 (§138's gate, refusing a credential its own proposal) → accept as
  the **person** 200, creating `TC-2` at `DRAFT` authored by the accepter. **Recorded as a partial
  pass**: the row says *in the SPA*, and the accept here was the person's session driven with
  `curl`. Same gate, same service, same authorship; not the screen. `A4-11`/`A4-12` stay open.

## `LP-3` — `GET …/suites/{suiteId}` is in the contract and served by nothing

`tcms suite edit TS-1 --tag-rule nightly` answered **`HTTP 405`**, and so did `--name`, which had
worked in round 1.

§144 made `suite edit` a read-modify-write, so the command reads the suite before it writes it. That
read is `GET /projects/{projectId}/suites/{suiteId}` — declared in `openapi.yaml` since M0, present
in both copies of `schema.d.ts`, and **mapped by no controller**. `SuiteController` has the
collection `GET`, `POST`, `PATCH /{suiteId}`, `DELETE /{suiteId}` and `PUT /{suiteId}/status`. Spring
matched the path, found no method for it, and answered 405.

Three consumers, none of them the SPA:

| Consumer | Broken since | Symptom |
|---|---|---|
| `tcms suite edit`, every flag form | §144 | 405 |
| MCP `tcms_list_suites` with a `suite` argument — the "open one" arm | §140 | 405 |
| MCP `tcms_write_suite` `action: edit` | §144 | 405 |

**Why it survived from M0.** The SPA never called it. The collection response carries the whole
`SuiteView`, so a detail fetch would re-request data already in hand, and every suite screen is built
from the list. An operation with no consumer cannot fail — and the only consumers that ever arrived
were written from the contract, by clients with no way to know the contract was ahead of the server.

**§144 made this worse before this slice made it better, which is the honest reading.** In round 1
`suite edit --name X` worked and `--tag-rule R` alone was a 400 on `name`. After §144 both were 405.
The read-modify-write is still right; it leaned on an endpoint that was not there.

## The finding: the check was already here, skipping it on a premise that had been deleted

`ContractParameterBindingTest` (§49) walks the live `RequestMappingHandlerMapping` and compares it to
the contract — the right observer, on the right pair of artifacts, running in every build. Its
`boundParametersOf` returns `null` when an operation has **no handler at all**, and the class javadoc
says why that is skipped rather than reported:

> *An endpoint still served by MSW has no handler to compare against, so it is skipped rather than
> reported: the check must not turn "not built yet" into a build failure, or the next unbuilt
> endpoint's author will add it to an ignore list and the real finding will arrive inside one.*

Every word of that was correct when it was written. The SPA was built against MSW ahead of the API,
so "no handler" genuinely meant "not yet", and failing the build on it would have been noise.

**§107 deleted `src/mocks/` on 2026-08-06** — and with it the only state in which a declared
operation could legitimately have no handler. The skip stayed. It was covering one.

This is the **ninth** time in this repo a note recording *why something was permitted* has outlived
the thing that permitted it (§115, §121, the nightly Linux lane, §142's `ci.yml` note, §143's
"needs a person at a terminal"…). It is the first where the note lived **inside the check that would
otherwise have reported the defect**, which is why it cost more than the others: the artifact that
existed to notice was the artifact agreeing not to.

## The fix

1. **`SuiteService.findOne`** renders the project's suites and picks one out — `viewOf`'s shape and
   `viewOf`'s reason. A detail endpoint assembling its own `SuiteView` is a second definition of a
   suite's wire shape, and the field that diverges shows one value on the list and another on the
   detail beside it. `SuiteEndpointTest.Detail.detailAgreesWithTheList` therefore compares the two
   **documents**, not a list of fields: a second definition is only wrong later.
2. **`SuiteController.detail`** — 404 for "no such suite", "soft-deleted", "a sibling project's
   suite" and "a project you may not see", all four indistinguishable, per this controller's
   "404, never 403" rule. No 403 arm, and the contract declares none: the gate is the project read
   gate the list already passes, and a caller who may list the suites may open one.
3. **`ContractParameterBindingTest.everyDeclaredOperationHasAHandler`** — every operation the
   contract declares must be mapped. **No ignore list, deliberately**: "not built yet" is the
   finding, and the failure message names the two honest repairs (implement it, or delete it from
   the contract and re-add it when a consumer exists).

**Verified by mutation, not by passing**: deleting the new `@GetMapping` fails the new test naming
the operation, while `everyDeclaredQueryParameterIsBound` beside it stays green — which is the whole
account of how this shipped. Rows `SD-1`…`SD-4` in the mutation queue; `SD-1` is the one run.

**The computed check's wider answer**: 127 operations across 92 paths, **one** unserved, and **zero**
server operations missing from the contract. The contract and the server now agree operation for
operation, and a build fails if they stop.

## What did not change, and why that is the point

`openapi.yaml`, `packages/` and `frontend/` are untouched. The clients were right; the CLI and the
MCP server had both been calling the operation the contract promised. **There was nothing to fix on
the side that failed** — which is the argument for the check, since every artifact a client can read
said the call was valid.

## Gates

`./gradlew build` **SUCCESSFUL, 2314 tests, 0 failures** (2308 at §144; +5 in `SuiteEndpointTest`,
+1 in `ContractParameterBindingTest`). `packages`: `oxlint` 0, **229 tests**. No frontend change.

Live, after rebuilding the image from the fix: `LP-3` passes (`--tag-rule nightly` alone → 200, name
and description intact), and both MCP arms answer — `tcms_list_suites` opens `TS-1`, and
`tcms_write_suite` edits it. The suite's three-arm membership was visible in the same call: adding a
`smoke` tag rule moved `caseCount` from 0 to 1 with no case filed in it (§98).

## What is still unverified

- **`A4-11` / `A4-12`** — the person's half of `LP-7`, in the SPA. Unchanged by this slice.
- **`CR-1`…`CR-3`** (§144) and **`SD-2`…`SD-4`** (this slice) — mutation rows, not run.
- Everything else §65 defers.
