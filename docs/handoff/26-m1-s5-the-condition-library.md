# §26. Backend M1, slice 5 — the condition library, and the last error on the case list (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**. Closes
§25's next-step item 1: **`GET /projects/{projectId}/conditions`** — the reusable pre/post condition
library (plan §6f, §6h), the smallest slice on the list and the one that removes the error
notification §25's browser pass found.

### The headline: the biggest screen in the product now loads clean

`CaseListPage` fetches this endpoint for its bulk-action dialog on **every** load, so until now the
screen raised one "Something didn't load / Not Found" notification even though everything visible on
it worked. It was invisible before §25 only because `/cases` 404'd first — which is the useful half
of the observation: **an error notification the user learns to ignore is one that no longer reports
anything.** The screen now loads with an empty notification stack and a clean console.

| Path | Purpose |
|---|---|
| `repository/ConditionType` | `PRE`/`POST`/`BOTH`, and `matching()` — where BOTH is folded in. |
| `repository/ConditionQuery` | The two parameters, normalized once at the edge; owns the `LIKE` escaping. |
| `repository/ConditionView` | The contract's `Condition`. Distinct from `SuiteConditionView` — see its header. |
| `repository/ConditionService · ConditionController` | The borrowed gate and the edge. |
| `repository/Condition` | **`type` and `usageCount` are now mapped** — the readers they were waiting for. |
| `repository/ConditionRepository` | `findLibrary` beside the existing `findLiveByIds`. |
| `db/migration/V9__condition_project_index.sql` | The index V1 forgot. |
| `repository/ConditionLibraryEndpointTest` | **New, 30 tests.** |

### `type=PRE` includes `BOTH`, and that is the decision worth reading

An exact match answers 200 with a plausible list and silently withholds exactly one row: the entry
an author declared reusable in **either** position. Under exact matching a `BOTH` entry appears in
no picker at all — not the precondition one, not the postcondition one — so **marking a condition
usable everywhere would be the one way to make it unreachable everywhere.** The filter asks "what
may I attach *as* a precondition", not "what is filed under the literal string PRE".

`type=BOTH` resolves to `BOTH` alone, on the same reading: it asks for the entries usable in either
position, which is exactly the ones declared that way.

**The SPA's mock matched the type exactly**, and here the mock was wrong rather than merely
different — so it was fixed rather than recorded (`handlers.ts`), and a `BOTH` fixture row was added
so the fold-in is reachable in mock mode at all. With only PRE and POST rows the two readings return
identical lists and the difference is invisible until live mode.

### V1 forgot an index on `condition`, and this is the first query to notice

`condition` came out of V1 with two GIN indexes and **no b-tree at all**, so
`company_id = ? AND project_id = ? AND deleted_at IS NULL` — the shape of every read here, and the
shape RLS adds to any read that forgets — had nothing to seek on. Every sibling table already
carries it: `suite_project_idx`, `test_case_suite_idx`, `test_case_lifecycle_idx`. `V9` adds
`condition_project_idx` mirroring `suite_project_idx` exactly — `company_id` leading per ERD §0.1
point 3, partial on `deleted_at IS NULL` because retired entries accumulate permanently. Verified
with `EXPLAIN` against the running compose stack: `Index Scan using condition_project_idx`.

### Three decisions that are decisions, not omissions

1. **`usageCount` is zero on every row, and is served as zero.** The column is `NOT NULL DEFAULT 0`
   and the writers that would maintain it do not exist, so the stored value is the honest answer —
   §25's `case_health` shape exactly. It starts ranking the moment something counts, with no change
   here. **The SPA now omits the "used by N" annotation at zero** rather than rendering "used by 0"
   on every row: same call as §22's dangling "Your role:" and §23's invented status colour, and it
   also stops the order reading as a ranking when it is currently just the title.
2. **`q` is a `LIKE` over the title, not `@@` over `search_vector`.** The vector exists and is
   populated (V2, title A + body B) and is the right §6h corpus; HQL cannot express `@@` without a
   custom function and a native query would drop the Hibernate company filter — the same constraint
   and the same choice as the case list's free text. Consequences, both pinned by tests: the search
   is **title-only** (matching the mock) and is a substring rather than a stemmed match.
3. **Retired entries drop out.** Retiring is a soft delete because attachments survive it; serving
   one would offer a withdrawn precondition for attachment to a *new* case, which is the one place
   the withdrawal was supposed to bite.

### Mutation-verified — eleven mutations, one structural survivor, and **two fixture defects**

| Mutation | Result |
|---|---|
| `PRE` matches `PRE` only (exact type match) | **Killed** |
| `deletedAt IS NULL` removed from `findLibrary` | **Killed** |
| `projectId` predicate removed | **Killed** |
| Gate replaced by unconditional visibility | **Killed** |
| `type` filter silently ignored | **Killed** |
| Blank `q` not normalized to null | **Killed** |
| `usageCount` served as a constant 0 | **Killed** |
| `@Enumerated(STRING)` → JPA default (ORDINAL) | **Killed at boot** — `ddl-auto: validate` |
| `ORDER BY usageCount` dropped | **Survived → fixture fixed → killed** |
| `LIKE` metacharacters no longer escaped | **Survived → fixture fixed → killed** |
| `id` tie-breaker dropped | **Survived, structurally** |

**Both survivors were the same defect, and it is §25's tie-breaker survivor twice over: a fixture
that cannot distinguish the right query from the wrong one proves nothing.**

- *Ordering.* "Log in" was seeded at 412 and "Log out" at 203 — which orders them the **same way the
  title does**, so dropping `usageCount` from the `ORDER BY` changed nothing. Inverted, so the two
  rankings now disagree.
- *Escaping.* The library carried one title with a `%` and one with a `_`, and that is not enough:
  unescaped, `%100%%` still matches only the "100" row and `%user_account%` still matches only the
  underscored one. **Each metacharacter needs a near-miss beside it** — "Refund 1000 EUR in full"
  and "Seed a user account row" — so the escaped and unescaped readings return different answers.
  Both were added and both mutations then died.

The `id` tie-breaker survivor is structural and documented in `ConditionRepository`: the remaining
tie needs two entries with the same usage count *and* the same title in one project, and Postgres
returns a small table in physical order, so no fixture can make the unstable query answer
differently on two consecutive reads.

### One thing the test harness got wrong, worth keeping

**`MockMvc` does not percent-decode a query string appended to the URL template.** `?q=100%25`
arrived at the controller as the six characters `100%25`, matched nothing, and the escaping test
failed for a reason that had nothing to do with escaping. Every parameter is now bound with
`.param(name, value)` — which is also what a real client produces after the container has decoded
the request, so the value under test is the value the endpoint will actually see.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **337 tests** (307 + 30). `npm run build` clean,
`npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, two provisioned companies, and a library seeded
through the **app role** under RLS:

| Assertion | Result |
|---|---|
| No filter | 7 live rows; the retired entry absent, `Log out` (412) first |
| `type=PRE` | 6 rows **including the BOTH row**, `Log out` absent |
| `type=POST` | `Log out` and the BOTH row, nothing else |
| `type=BOTH` | the BOTH row alone |
| `q=100%` | **one row** — unescaped it would also have returned "Refund 1000 EUR in full" |
| `q=user_account` | **one row** — unescaped it would also have returned "user account" |
| `q=` and `q=  ` | the whole library, not an empty search |
| `q=account menu` (body text) | **0 rows** — the title-only decision, visible |
| `type=NOPE` | **400**, not a dropped filter |
| Another client's project | **404, 0 bytes**; their condition title appears nowhere in this client's library |
| `EXPLAIN` on the library query | `Index Scan using condition_project_idx` |

And **in the browser**, in live mode: the case list loads with **no notification and no 404 in the
console**, and the bulk dialog's precondition picker lists the library most-used first, with the
retired entry absent, the POST-only entry absent, **the BOTH entry present**, and the zero-count row
rendering with no "used by" annotation while the others keep theirs.

*(One pre-existing console error remains and is not this slice's: MUI X `DataGridRaw` warns "Cannot
update a component while rendering a different component" under React 19. It predates this work and
is visible on any case-list load.)*

### Deliberately not done

- **No writes.** No condition authoring, editing or retirement — which is also what would make
  `usage_count` and the inline-attachment shape (§24) answerable rather than recorded.
- **`GET /projects/{projectId}/shared-steps`** — the sibling library, same shape, same table
  structure. Left out because it is a different endpoint with no consumer raising an error today.
- **No relevance ranking.** §6h specifies relevance blended with `usageCount`; this is
  `usageCount` alone, because relevance needs the `@@` path above.
- **Drop-own-filter faceting, `search_vector`, and the `kind` field on `CaseConditionRef`** — all
  unchanged from §24/§25 and all still recorded there.

### Next

1. **`GET /projects/{projectId}/cases/{caseId}`** — the case detail screen, the last read the
   `repository` module owes the SPA, and the natural pair to §25.
2. **`GET /projects/{projectId}/shared-steps`** — small, and it finishes the two reusable libraries.
3. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
4. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.
