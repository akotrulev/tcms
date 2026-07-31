# §24. Backend M1, slice 3 — the suite tree, and the `repository` module's first endpoint (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`, then `code-review`), **Mode B
— extend**. Closes §23's next-step item 1's first half: **`GET /projects/{projectId}/suites`**.

### The headline: the shared gate held from another module

§23 made `ProjectService.readVisible` the one implementation of "may this caller see this project".
This slice is the first endpoint **outside the `project` module** built on it, which is the case it
was extracted for — and it needed no new module edge to work. `repository` declares
`allowedDependencies = {"tenancy", "project"}`, hands a user id to `project`, and is handed back
either the project or nothing. It never asks `identity` anything.

Deleting the grant check from `visibleProject` now fails **eight tests across three endpoint
classes**. That number is the property, and it went up without anyone maintaining a list.

| Path | Purpose |
|---|---|
| `repository/package-info.java` | **The fifth module.** Why the name means *the test repository* and not "the persistence layer". |
| `repository/Suite · Condition · SuiteCondition` | Three entities. Second `ltree` column in the codebase, first `List<String>` over `jsonb`. |
| `repository/…Repository` ×3 | One purposeful query each, all `@Transactional(readOnly = true)` per §15. |
| `repository/SuiteView · SuiteConditionView · LifecycleStatus` | The contract's shapes. `companyId` **and** `projectId` withheld. |
| `repository/SuiteService` | Borrows the gate; three queries, grouped in memory. |
| `repository/SuiteController` | The endpoint. Separate controller — the path expresses scope, not ownership. |
| `repository/SuiteEndpointTest` | **New, 25 tests.** |

No migration and no `openapi.yaml` change: the contract was already right and was implemented as
written. The frontend is untouched, so no frontend gate — `schema.d.ts` already carried `Suite`.

### The defect this slice was most likely to ship, and why it would have looked fine

**`caseCount` is the suite's own live cases, not its subtree's.** The rollup is done client-side —
`SuiteTree.tsx` adds each node's count to its children's — so a subtree count here is counted again
at every ancestor and a two-level tree reports roughly double. Both numbers are plausible, the
endpoint answers 200, and nothing fails. The SPA's own fixture says it out loud (a parent folder
with children carries `caseCount: 0`) and that is where the semantics were read from rather than
guessed.

### Two exclusions that are decisions, not oversights

Both are recorded on `SuiteConditionView` and both are pinned by a test that says what a future
failure means:

1. **An inline condition attachment has no title, and `title` is `required`.** `suite_condition`
   stores either a `condition_id` or an `inline_body` and nothing else, and `CaseConditionInput`
   has no field a writer could send a title in — so the inline form is a state the API cannot
   describe. Inventing one (the first body line, "Inline condition") would be the response making
   a claim the data never made, which is exactly what §22's dangling `Your role:` and §23's
   fallback status colour were fixed by *not* doing. Excluded at the query; nothing in the product
   can create such a row today.
2. **`kind` (PRE/POST) has nowhere to go.** The column exists and the distinction is real, but
   `CaseConditionRef` carries no field for it. Adding one is a contract change with no consumer on
   the other side — `Suite.conditions` has **zero** readers in the SPA today.

A third case drops out quietly and is named rather than left to be discovered: an attachment whose
library condition has been **retired** resolves to nothing and is omitted, because serving it would
offer a withdrawn precondition as current.

### Mutation-verified — twelve mutations, two survivors and both instructive

| Mutation | Result |
|---|---|
| `caseCount` rolled up over the subtree | **Killed** |
| `deletedAt IS NULL` removed from the suite query | **Killed**, 2 tests |
| `deleted_at IS NULL` removed from the case count | **Killed**, 2 tests |
| Ordered by `position` instead of `path` | **Survived → fixture fixed → killed** (below) |
| Grant gate replaced by company-only visibility | **Killed**, 3 tests |
| Inline attachments no longer excluded | **Survived → test moved → killed** (below) |
| Retired library entries no longer excluded | **Killed** |
| Empty `conditions` become `null` instead of `[]` | **Killed**, 2 tests |
| Absent `caseCount` becomes `null` instead of `0` | **Killed**, 19 tests |
| `columnDefinition = "ltree"` removed | **Killed** — 25/25, at context startup |
| `project_id` removed from the case count | **Survived, structurally** |
| `company_id` removed from the case count | **Survived, structurally** |

**The ordering survivor was a fixture defect.** Every suite carried `position = 0`, so an
`ORDER BY position` query returned parents first by luck and the assertion could not fail. Fixed by
making sibling positions *descend* as the tree deepens (Payments 5, Refunds 3, Partial 1) — so the
wrong query genuinely returns a child before its parent. A fixture that cannot tell the right query
from the wrong one proves nothing.

**The inline survivor was §22's survivor in a new costume.** An inline row has a null `conditionId`,
the service resolves it with `library.get(null)`, and `HashMap` answers null — so the row dropped
out whether or not the query excluded it, and every endpoint assertion stayed green. `HashMap`
tolerating a null key was doing the predicate's job again. Fixed by asserting **at the repository**,
where the decision actually lives.

**The two tenancy survivors are structural and are documented as such** on
`SuiteRepository.countLiveCasesBySuite`. The result is keyed on `suite_id` — a UUID primary key —
so a case in another project or company hangs off a suite id that is never looked up, and no
fixture can make the difference observable. They stay for reasons that are not "in case the join
key changes": `company_id` leads `test_case_suite_idx` and is what makes the query index-covered,
and `project_id` bounds the aggregate instead of grouping the whole company and discarding most.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **253 tests** (228 + 25).

Then **driven live** against `docker compose up` + a provisioned `slice3` company, with a
three-level tree, a soft-deleted folder, a soft-deleted case and all three attachment shapes seeded
directly:

| Assertion | Result |
|---|---|
| Ordering, against positions 5/3/1 | **Payments → Refunds → Partial refunds** — a position ordering would have inverted it |
| `caseCount` vs psql ground truth | **0 / 2 / 1**; psql agrees, and the deleted case is excluded |
| The soft-deleted folder | **absent** |
| Live reference attachment | resolved with title and two-element body, `inline`/`inherited` both false |
| Inline attachment, retired-condition attachment | **both absent**, `conditions: []` |
| Another company's populated suite | absent from the response — a live cross-company check |
| `companyId` / `projectId` at any level | absent |
| Unknown project id | **404, 0 bytes** · anonymous → **401** |
| **The caller's only grant deleted, then re-requested** | `/projects` → `[]`, `/suites` → **404, 0 bytes**, for a populated project in their own company. Persona 3, live. |
| Grant restored (re-running `provision-company`) | **200** |

No SPA screen was driven, and that is not an omission: the only consumer is `CaseListPage`, which
also needs `/cases`. Verification is therefore at the HTTP layer against psql ground truth — stated
plainly rather than dressed up as a browser pass.

### One finding from the `code-review` phase

`ProjectEndpointTest`'s fixture comment claimed "the `repository` module does not exist yet", which
this slice made false. Corrected, and pointed at `SuiteEndpointTest`'s fixture as the stronger
arrangement to copy: it writes through the **app role** under RLS rather than as the superuser, so a
fixture that could not exist under policy cannot silently pass.

Writing that test also re-taught §15's lesson from the other side: `CompanyContext.open` must wrap
the transaction, not sit inside it. The transaction manager reads the context in `doBegin`, so a
context opened after the transaction started arrives too late and RLS answers **zero rows** —
fail-closed and silent, and it read like a broken query.

### Deliberately not done

- **No writes.** No suite create/rename/move. Reparenting rewrites every descendant's `path` in one
  statement, which is why `Suite` has no setters and why the move endpoint is a slice of its own.
- **`GET /suites/{suiteId}` and `/history`** — the same `Suite` shape and a `SuiteRunSummary`
  respectively. The second needs `execution`, which does not exist.
- **No `TestCase` entity.** The counts are native SQL against `test_case`, which is this module's
  own table. `/cases` is the next slice and will map it.
- **No caching.** Same reasoning as the evaluator's own deferred cache: a wrongly-keyed cache here
  is a cross-company leak.
- **`Suite.conditions` has no SPA consumer** — implemented because the contract marks it required
  and hard-coding `[]` is a lie the moment anything writes a suite condition.

### Next

1. **M1 slice 4 — `/projects/{id}/cases`**, the list the SPA's biggest screen reads. It brings the
   first `TestCase` entity, the first server-side filtering and paging, and it is what finally makes
   live mode render a *screen* rather than reference data.
2. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
3. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.
