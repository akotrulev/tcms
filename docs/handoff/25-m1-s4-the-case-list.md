# §25. Backend M1, slice 4 — the case list, and the screen that finally renders live (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**. Closes
§24's next-step item 1: **`GET /projects/{projectId}/cases`** — filtering, sorting, paging and
facets, the first `TestCase` entity, and the first endpoint whose response the SPA renders as a
*working screen* rather than as reference data.

### The headline: the SPA's biggest screen runs on the real API

`CaseListPage` needs three endpoints — the suite tree (§24), the classification vocabularies (§23)
and this one. With the third in place the screen renders live end to end: the tree with its counts,
the grid, the facet chips with their counts, URL-backed filters, and paging. That was the point of
sequencing these three slices in this order.

| Path | Purpose |
|---|---|
| `repository/TestCase` | **The first case entity.** Plain UUID FKs, no associations — see its header. |
| `repository/CaseTag · CaseTagId · CaseClassification · CaseClassificationId` | The two join tables, `@IdClass` per the `CompanyMembershipId` precedent. |
| `repository/CaseHealth · CaseHealthRepository` | The §6o read model. Mapped, queried, and **empty** — nothing computes it yet. |
| `repository/Priority · AutomationStatus · StepFormat · HealthSignal` | The contract's enums. `HealthSignal` carries its `CaseHealth` attribute name so filter, facet and row all resolve one source. |
| `repository/CaseQuery · CaseScope` | What was asked, and what that turned out to mean. Kept apart on purpose. |
| `repository/CaseSpecifications · CaseSort · CaseSearch` | One predicate, the `ORDER BY` allow-list, and the five queries that share them. |
| `repository/CaseService · CaseController` | The gate, the query budget, and the edge. |
| `repository/CaseOwners` | The one class that may touch `identity`. |
| `repository/*View` | `TestCaseSummaryView`, `UserRefView`, `FacetView`, `CasePageView`. |
| `repository/CaseListEndpointTest` | **New, 53 tests.** |

### The module now depends on `identity`, and the distinction is the point

`repository`'s package comment used to say "not `identity`", and the reason it gave was
authorization. That reason still holds and nothing changed it: the gate is still
`ProjectService.readVisible`, borrowed from `project`, and no class in the package asks what a
caller holds. What the case list added is a *different* question with a different failure mode —
**what is this person called** — which every grid showing a person needs, which §6u says must
resolve through the active company's membership, and which creates no cycle (`identity` depends on
`tenancy` and `project`, never on this module).

The `ProjectAccess` port-and-adapter shape was deliberately **not** repeated. That inversion exists
because `project` *cannot* depend on `identity`; this module can, so a port would mean making
`identity` depend on the test repository to hand back a display name — an arrow pointing the wrong
way to avoid one pointing the right way. `CaseOwners` is the only class permitted the edge, and the
package comment says so.

### One contract change: `TestCaseSummary.owner` is nullable

`test_case.owner_id` has always permitted null while the contract marked `owner` required. Resolved
toward the schema: `openapi.yaml` now carries `allOf` + `nullable: true`, types were regenerated,
and `CaseGrid` / `CaseDetailPage` render "Unassigned". The two alternatives were worse — hiding the
row loses a case from its own project's list, and inventing an owner puts a person on the wire whose
id nothing can resolve. `db.ts` passes `?? undefined` for the derived work items' assignee, which is
optional rather than nullable in the contract.

### Three decisions that are decisions, not omissions — each pinned by a test that names its own successor

1. **`releaseVersion` matches nothing.** The filter is defined over executions and `execution` does
   not exist, so zero cases have been executed in any release. Implemented as an explicit
   never-matching predicate rather than an ignored parameter: an ignored filter returns the entire
   project and labels it "executed in 4.3.0", which is worse than an error because it is
   indistinguishable from a filter that found everything.
2. **`healthSignal` matches nothing and the health facet is empty.** Same shape: `case_health` is a
   read model nothing writes yet, so no case raises a signal. Real predicate, real query, honest
   zero — it starts working when the computation lands, with no change here.
3. **`lastResult` is absent rather than null.** Not marked required, and the SPA already renders a
   missing `lastResult` as "Never run", which is exactly true.

### Enums sort by meaning, and the mock is the one that is wrong

`priority` stored as text sorts CRITICAL, HIGH, **LOW, MEDIUM**. Every value is in a plausible
place, the column looks sorted, nothing fails. `CaseSort.byDeclaredOrder` builds a CASE expression
from the enum's own `values()`, so the order is the declaration order and a new member is ordered
correctly the moment it is declared. `lifecycleStatus` and `automationStatus` have the same problem
and the same fix. **The SPA's mock sorts all three alphabetically** (`localeCompare` in
`handlers.ts`) — recorded rather than matched.

### The defect running the app found, and the gates could not

**The project picker said "4 cases" over a list showing 3.** `GET /projects` counts live cases;
this endpoint excludes cases whose *suite* is soft-deleted (soft-deleting a folder does not touch
the cases under it, and a row whose folder is gone names a place the user cannot navigate to — which
is what `SuiteRepository` already documented as the intent). Two queries, two screens, both numbers
plausible, `./gradlew build` green and 306 tests passing.

Fixed in `ProjectRepository.countLiveCasesByProject` by joining to a live suite, and pinned by a
test that asserts the **equality of the two endpoints** rather than two expected numbers — expected
numbers drift apart silently; their agreement is the actual requirement. Same family as the two
contradictory percentages on the plan-statistics screen (§5), and the same reason the working
agreement says the gates prove it compiles, not that it works.

### Mutation-verified — twenty mutations, three survivors, two of them weak tests

| Mutation | Result |
|---|---|
| Grant gate replaced by company-only visibility | **Killed** |
| `deletedAt IS NULL` removed from the case query | **Killed** |
| Live-suite bound dropped (deleted folders' cases return) | **Killed** |
| Priority sorted alphabetically instead of by declared order | **Killed** |
| Ordered by the text key instead of `keySeq` | **Killed** |
| LIKE metacharacters no longer escaped | **Killed** |
| `TC-n` short-circuit removed — searched as text | **Killed** |
| `description` dropped from free-text search | **Killed** |
| Classification descendants no longer included | **Killed** |
| Retired classification values become matchable | **Killed** |
| `releaseVersion` / `healthSignal` silently ignored | **Killed**, both |
| `total` computed from the page | **Killed** |
| Owner name read from the account instead of the membership | **Killed** |
| Zero-count facet values no longer dropped | **Killed** |
| Unowned case given an invented owner | **Killed** |
| Empty classification set widened to "no filter" | **Killed** |
| `keySeq` tie-breaker dropped | **Survived → fixture fixed → killed** |
| Facet query drops the request's enum filters | **Survived → test added → killed** |
| `project_id` predicate removed | **Survived, structurally** |

**The tie-breaker survivor was a fixture defect, and it is §24's ordering survivor one level up.**
Cases were inserted in key order, so Postgres returned a small table in physical order and a query
with no tie-breaker came back correctly *by luck*. Fixed by inserting rows that tie on priority in
the **opposite** order to their `keySeq` — TC-10 before TC-2, TC-5 before TC-3. A fixture that
cannot distinguish the right query from the wrong one proves nothing.

**The facet survivor was a genuinely missing test.** `suiteId` is resolved into the *scope* before
the predicate is built, so the one facet test that existed would still have passed had the facet
query dropped every filter carried on the *request*. `countsFollowAnEnumFilter` closes it.

**The `project_id` survivor is structural** and documented as such in `CaseSpecifications`: the
suite scope is already the project's own live tree and a suite id is a UUID primary key, so a case
in another project hangs off a suite that is never in the set. It stays because it is what makes the
query reachable through `test_case_lifecycle_idx`, and because three predicates stating the same
intent is what makes a future mismatch return nothing rather than something.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **307 tests** (253 + 53 + 1 on `ProjectEndpointTest`'s
neighbour). `npm run build` clean, `npm run lint` 0 warnings.

Then **driven live** against `docker compose up`, two provisioned companies, and a hostile dataset
seeded through the **app role** under RLS:

| Assertion | Result |
|---|---|
| Default order | **TC-1, TC-2, TC-10** — a text ordering would have inverted the last two |
| `sort=priority,asc` | **CRITICAL, MEDIUM, LOW** — alphabetical would have put LOW second |
| Soft-deleted case, live case in a soft-deleted suite | **both absent**; psql confirms 5 rows in the table and 3 served |
| `q=TC-1` | **TC-1 only**, never TC-10 |
| `q=refund` | title **and** description matches |
| `q=100%` | **one row** — unescaped it would have matched everything |
| `suiteId=Payments` (a parent) | its subtree, 3 rows |
| `featureId=Payments` (a parent) | **2 rows**, descendants included |
| `releaseVersion=4.3.0` · `healthSignal=NEVER_EXECUTED` | **0 rows**, not the whole project |
| `priority=NOPE` | **400**, not a dropped filter |
| Another client's project | **404, 0 bytes**; their case title appears nowhere in this client's list |
| **The caller's only grant deleted, then re-requested** | `/projects` → `[]`, `/cases` → **404, 0 bytes**. Persona 3, live |
| Grant restored | **200** |
| Picker `caseCount` vs list `total` | **3 and 3** — the defect above, after the fix |

And **in the browser**, in live mode: sign in → project picker → Test cases. Suite tree with counts,
facet chips carrying real counts (Draft 2 · Approved 1 · Manual 2 · Automated 1 · Critical 1),
"3 cases", the grid ordered TC-1 · TC-2 · TC-10, Owner reading "S. Admin" and **"Unassigned"**,
Health "—", Last result "Never run". Typing `100%` into the search box narrowed it to one row and
**re-computed every facet** to match.

### One finding the browser pass surfaced, and it is not this slice's

`CaseListPage` also fetches **`GET /projects/{projectId}/conditions?type=PRE`** for its bulk-action
dialog, and that endpoint **does not exist** — so the screen raises one "Something didn't load /
Not Found" notification on every load even though everything on it works. It was invisible before
this slice only because `/cases` 404'd first. The `Condition` entity and its repository already
exist (§24), so the endpoint is small; it is left out because it is a different endpoint from the
one this slice scoped.

*(The 503s visible in the network log during that pass were the backend restarting mid-check; the
retry policy recovered every one of them, which is `isTransient()` working as designed.)*

### Deliberately not done

- **No writes.** No create, edit, bulk or delete. Creating a case allocates `key`/`key_seq` from the
  company's `key_sequence` row and must write `test_case_version` v1 in the same transaction, which
  is a slice of its own.
- **`GET /cases/{caseId}`** — the full `TestCase` shape, with steps, conditions and versions.
- **`owner` is not sortable.** It would need `identity`'s table inside this module's `ORDER BY`.
  The SPA's grid offers the header and both modes fall back to `keySeq`; a real gap, recorded rather
  than papered over.
- **`search_vector` is unused.** `q` is a trigram `LIKE` over title and description — Criteria
  cannot express `@@` without a custom function, and a native query would drop the Hibernate company
  filter. `description` has no trigram index; the row set is already bounded to one project.
- **Facets count the filtered set, including each filter's own contribution**, matching the mock.
  Drop-own-filter faceting is more useful, is four more queries, and is a product decision with a UI
  consequence rather than a detail to slip in.

### Next

1. **`GET /projects/{projectId}/conditions`** — small, and it removes the one error notification on
   the case list screen.
2. **`GET /projects/{projectId}/cases/{caseId}`** — the case detail screen, the natural pair to this
   slice and the last read the repository module owes the SPA.
3. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
4. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.
