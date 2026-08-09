# Live pass round 2 — verifying §144 against a real TCMS (§145)

**Run:** 2026-08-09 · **Instance:** `docker compose` at `localhost:8080`, schema **V43** (head),
image rebuilt from `b1ad572` (§144) at the start and from the fix at the end.
**Company:** `lp2` "Live Pass Two" (`114df9bb-9dd1-4891-b49c-ca045708cfad`), project `LP2`
"Live Pass Two Product" (`c705f4d4-c99e-4362-ac07-a36633233732`), admin `ops@lp2.test`.
**Credential:** an **`Agent`** token bound to `LP2`, issued through `POST /api-tokens` — 23 effective
permissions, the same shape as round 1.

> **Why a second pass.** §144 fixed four defects that only a live pass could find, and verified them
> with the observers that had missed them: a stubbed `fetch`, a jsdom, a MockMvc. Rows `LP-1`…`LP-8`
> were written in that slice as the eight things a real instance still had to be asked. This is that
> run.

**Seven of the eight passed on the first attempt. `LP-3` failed, and it failed wider than the row** —
the cause is a contract operation that has never been implemented, with three consumers.

---

## The rows

| # | Result |
|---|---|
| LP-1 | ✅ `case edit TC-1 --title …` → 200; a re-read moved `title`, `currentVersionNo`, `lockVersion`, `updatedAt` and **nothing else** — description, objective, expectedOutcome, severity, both steps (ids preserved), tags and `suiteId` all intact |
| LP-2 | ✅ `case edit TC-1 --unfile` → 200; `suiteId`/`suiteName` null, every other field unchanged |
| LP-3 | ❌ → **F5 below**, fixed in this slice; ✅ after the fix (`--tag-rule nightly` alone: 200, name and description intact) |
| LP-4 | ✅ `plan edit TP-1 --milestone GA2` on a plan with prose → 200, `milestoneName` set, **`scopeText` and `outOfScopeText` still there.** The silent-data-loss row, closed |
| LP-5 | ✅ MCP `tcms_write_case` `action: edit` → 200 and a clean re-read. §143's F3, fixed live |
| LP-6 | ✅ `case propose --file` with `{"title": …}` alone → refused **client-side**, exit 1, naming `stepFormat`, `priority` and `automationStatus` **together**; the only request that reached the server was the project lookup (proved with a recording proxy, not by reading the code) |
| LP-7 | ✅ **at the API**: proposal filed 201 → `PENDING` → accept with the **token** 403 (§138's gate) → accept as the **person** 200, creating `TC-2` at `DRAFT` authored by the accepter. **The SPA half is not done** — see below |
| LP-8 | ✅ stale `--file` + `--lock-version` → **409**, exit 1, the reload message, no overwrite. Confirmed at the wire as `If-Match: "3"` → `409` |

**`LP-7` is a partial pass and is recorded as one.** The row says *"accepted in the SPA by a
person"*; the accept here was the person's **session** driven with `curl`. That exercises the same
gate, the same service and the same authorship, and it does not exercise the screen — which is what
`A4-11`/`A4-12` in the browser queue are for. They stay open.

---

## F5 — `GET /projects/{projectId}/suites/{suiteId}` is in the contract and served by nothing

**Severity: high.** **Product defect**, backend. Found by `LP-3`, and much larger than `LP-3`.

`tcms suite edit TS-1 --tag-rule nightly` answered:

```
The request failed (HTTP 405).
```

§144 made `suite edit` a read-modify-write, so the command now **reads the suite first** — and that
read is `GET /projects/{projectId}/suites/{suiteId}`, an operation the contract has declared since
M0 and **no controller has ever mapped**. `SuiteController` maps `GET` on the collection, `POST`,
`PATCH /{suiteId}`, `DELETE /{suiteId}` and `PUT /{suiteId}/status`. There is no `GET /{suiteId}`,
so Spring matched the path, found no method, and answered 405.

**Three consumers, all broken, none of them the SPA:**

| Consumer | Since | Symptom |
|---|---|---|
| `tcms suite edit` (every flag form) | §144 | 405 — including `--name`, which **worked in round 1** |
| MCP `tcms_list_suites` with a `suite` argument (the "open one" arm) | §140 | `Could not list the suites, or open one: The request failed (HTTP 405).` |
| MCP `tcms_write_suite` `action: edit` | §144 | same |

**Why it survived from M0 to now.** The SPA never called it. It builds every suite screen from the
collection — the list carries the whole `SuiteView`, so a detail fetch would be a second request for
data already in hand. An operation nobody consumes cannot fail, and the only consumers that ever
arrived were written from the contract by clients that had no way to know.

**§144 made `LP-3` worse before this pass made it better, and that is the honest reading.** In round
1 `suite edit --name X` worked and `--tag-rule R` alone was a 400. After §144 both were 405. The
read-modify-write is still right; it just leaned on an endpoint that was not there.

### The check that would have caught it was already in the repo, skipping it on a dead premise

`ContractParameterBindingTest` walks the live `RequestMappingHandlerMapping` and compares it to
`openapi.yaml`. Its `boundParametersOf` returns `null` when an operation has no handler, and the
class javadoc explains why that is skipped rather than reported:

> *An endpoint still served by MSW has no handler to compare against, so it is skipped rather than
> reported: the check must not turn "not built yet" into a build failure.*

That was correct when it was written. **§107 deleted `src/mocks/` on 2026-08-06**, and with it the
only state in which a declared operation could legitimately have no handler — but the skip stayed,
and it was covering one. This is the **ninth** time in this repo a note recording why something was
permitted has outlived the thing that permitted it, and the first where the note lived **inside the
check that would otherwise have reported the defect**.

### The fix

1. **`SuiteService.findOne`** — renders the project's suites and picks one out, which is `viewOf`'s
   shape and `viewOf`'s reason: a second definition of a suite's wire shape is a field that shows one
   value on the list and another on the detail beside it. `SuiteEndpointTest.Detail` asserts the two
   are equal as JSON rather than restating field expectations a third time.
2. **`SuiteController.detail`** — 404 for "no such suite", "soft-deleted", "another project's suite"
   and "a project you may not see", all four indistinguishable, per this controller's
   "404, never 403" rule. No 403 arm, and the contract declares none.
3. **`ContractParameterBindingTest.everyDeclaredOperationHasAHandler`** — every operation in the
   contract must be mapped. **No ignore list**, deliberately: "not built yet" is the finding.

Nothing changed in `openapi.yaml`, `packages/` or `frontend/`. The clients were already right.

### What the computed check found overall

127 operations across 92 paths. **One** was unserved (the above); **zero** server operations are
absent from the contract. Verified by deleting the new `@GetMapping` and re-running: the new test
fails naming the operation, and the parameter-binding test beside it stays green.

---

## Notes, not defects

- **A body Spring cannot read answers with the container's error JSON, not `Problem`.** A `POST
  …/cases` carrying `"stepFormat": "STEPS"` (not a value this API has) answered `400` with
  `{"timestamp","status","error","path"}` for a **session**, where the contract declares a `Problem`
  with an `errors` map. The *status* is right — that is §143's F1 fixed and holding, since the token
  path now answers 400 too — but the *shape* is the container's. Not pursued here: it is a different
  slice from F5 and needs a decision about `/error`, not a mapping.
- **`tcms case propose` needs `--verdict` spelled as an enum value** (`UNVERIFIED`), and prints
  `Verdict: UNVERIFIED (EXTERNAL)` — the derived `verdict_source` §138 refused to put on the wire as
  an input, rendered as an output. Working as designed.
- The suite's three-arm membership was observed live: adding a `smoke` tag rule to `TS-1` moved its
  `caseCount` from 0 to 1 with no case filed in it, because `TC-1` carries the `smoke` tag (§98).
