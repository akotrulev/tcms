# §249 — Cross-project case search: the narrowing goes in the query, and the test goes on the total

Area D of `docs/plans/2026-08-18-unplanned-gaps.md` (master tasks 26–32), executed against the
stepped plan `docs/plans/2026-08-18-gap-d-cross-project-case-search.md` (22 tasks).
**No migration, no permission key, no module edge, no `packages/` change. Catalogue still 55, head
still `V68`.**

**Gates: RUN, and green.** Backend `3249 / 0 / 0`; frontend `1692 / 1692`, build clean, lint 0
warnings; packages build, lint and tests green. This is the first area in the batch to land gated,
because Stage 1 of batch 4 greened the tree first.

---

## 1. RLS scopes to the COMPANY, so the project narrowing here is the application's alone

State it first because everything else follows from it. The policy predicate on every company-owned
table is `company_id = current_setting('app.current_company')`. **Two projects in one company are
both RLS-visible to the same session.** The database will not refuse a row from a project the caller
holds no grant on — it has no idea such a concept exists.

Every other case read in this product is scoped to one project and gated by that project's grant.
This one returns rows from many, and the only thing standing between a caller and a colleague's
project is one line of Java-built SQL. ERD §0.1 names this persona for exactly this reason: *"a
third persona — a company member with no project grant — asserting they see nothing, since those
rows are in the caller's company and the cross-company assertions structurally cannot catch that
leak."*

`CaseSearchTest.rlsIsNotTheBoundary` asserts it from both ends: a superuser count proving seven
matching rows sit in one company, and the endpoint answering two.

## 2. The count is the assertion. A page-only test passes straight through the defect

**This is the sentence to carry.** Filter in Java after an unrestricted read and the page is
*exactly right* at `size=200`: five rows are dropped and the caller sees the two that are theirs.
What is wrong is the `total` — 7 — and the paging, both computed over rows the caller cannot reach.
A count is a name-free existence claim about the client's portfolio, which is what §6t and
`AdminReach.visibleProjects` refuse and what `OrgChartService` refuses *even as a count*.

Two arms, and they fail with **different symptoms**, which is what makes the diagnosis unambiguous
in six months:

| Arm | Under the defect |
|---|---|
| `theTotalIsNarrowedToo` | `total` is **7** instead of 2 |
| `thePageCannotHideTheCount` | the page is **EMPTY** beside a total promising rows |

The second works because `TC-1` — the row that sorts first of all seven — is in a project the caller
was not granted, so at `size=1` the database serves it, Java drops it, and the response is an empty
page. The fixture is arranged so every wrong answer is a **different number**: 2 correct, 7 the
company-wide leak, 8 counting the soft-deleted row, 3 a per-project loop that forgot one grant.

## 3. `repository` asks `project`, not `identity` — and the master plan said otherwise

The master plan's task 26 says *"`OrgChartService` and `AdminReach` already carry visible-project
narrowing. Reuse that narrowing"*. Read literally that means calling
`AdminDirectoryService.reachOf(userId).visibleProjects()` from `com.tcms.repository`. **Do not.**
`repository/package-info.java` forbids it in a sentence written for this exact temptation:

> *"this module still never asks `identity` whether a caller **may** do anything. […] A permission
> check appearing anywhere in this package is a defect regardless of what this list permits."*

`identity` **is** on `repository`'s `allowedDependencies` — which is precisely why the prohibition is
prose, and why following the master plan's wording would have shipped a violation that **ArchUnit
and Modulith both pass**. The correct handle is `ProjectService.readAllVisible`, which is the same
`PermissionEvaluator` reached through the module this one already depends on for every read. They
are not two implementations; they are two exposures of one. Recorded because the next person will
read the master plan too.

`readAllVisible` is now the cross-project gate for **two** modules (`work` and `repository`), which
is what makes it a seam rather than a helper.

## 4. `CaseSpecifications`' project bound is no longer a survivor mutation

One line moved from "documented as untestable" to "the entire boundary". The old comment argued —
correctly — that deleting `cb.equal(root.get("projectId"), …)` changes nothing observable, because
the suite scope is already the project's own live tree. That argument is **true of the project grid
and false of this endpoint**, where there is no suite scope at all. Both texts now stand at the site,
the old one struck through.

That is a fact about the mutation-check queue rather than only about this file: `§249 CS-M3` deletes
the predicate and expects **red on `CaseSearchTest`, green on `CaseListEndpointTest`**, and the pass
is asked to record which suites reddened.

## 5. THE PLAN'S OWN FIRST UNCERTAINTY WAS REAL, AND IT WOULD HAVE SHIPPED A NEARLY-EMPTY SEARCH

The plan proposed saying "no suite bound" as `liveSuiteIds = List.of()` with
`includeUnfiled = true`, and flagged, in its self-review, that it had not read
`CaseSpecifications.suiteScope` line by line. Read: that pair means `suite_id IS NULL` — **unfiled
cases only**, which is what `CaseService` builds it to mean for the grid's "unfiled" filter.

A cross-project search spelled that way returns the handful of cases filed nowhere and **silently
drops every filed case in the company**. Plausible, non-empty, wrong, and no arm in the plan's
drafted list would have caught it, because every case in a natural fixture is filed.

So `CaseScope` gains an explicit **`boundByTree`** — the plan's own prescribed fallback — `suiteScope`
returns `Optional`, and the compact constructor refuses a scope that is both unbounded and naming
suites. **The fixture files every case in a suite on purpose**, with one deliberately unfiled
(`TC-8`) so both halves are observed rather than only the convenient one.

**The consequence, which is Q4 answered:** the search applies no live-tree bound, so a case filed in
a **soft-deleted suite is findable here** where it is absent from that project's grid. Shipped
deliberately — a search is for finding a case you know exists, and a case hidden behind a deleted
folder is precisely the one somebody cannot find — and asserted by
`aCaseInARetiredFolderIsStillFindable` on a word of its own (`rebate`) so it cannot disturb the
`refund` arithmetic.

## 6. The query plan: measured, and NO INDEX IS OWED — which contradicts the plan's expectation

Master task 30. Method is `V67`'s: a throwaway PostgreSQL 17 container on `shanks`, all **68**
migrations applied in order as non-superuser `tcms_owner`, every plan taken as `tcms_app` with
`app.current_company` set — because the RLS predicate is part of the query and a plan taken as the
superuser loses the `One-Time Filter` node entirely.

**Instance:** 1,001 companies, 500,000 `test_case` rows (279 MB). Company under measurement: 200,000
cases across 10 projects, ~1 in 40 titles matching `refund`.

| Query | Node | Time |
|---|---|---|
| The page, 2 of 10 projects | `Bitmap Index Scan on test_case_lifecycle_idx` → heap filter | **72.8 ms** |
| The total, same predicate | same | **68.0 ms** |
| All ten projects (Company Admin) | `Index Scan using test_case_lifecycle_idx`, ×10 | **247.1 ms** |
| `TC-n` short-circuit | `Index Scan using test_case_key_seq_idx` | **0.064 ms** |

**No `Seq Scan` anywhere**, which is what task 30 actually asked. The authorization predicate leads
through `test_case_lifecycle_idx (company_id, project_id, lifecycle_status)` — ERD §0.1 point 3's
*leading, not appended*, working as designed.

**The candidate index was created and measured, not reasoned about.** The plan expected
`lower(title) LIKE '%…%'` to be the problem, since `test_case_title_trgm_idx` is on `title` and a
trigram GIN index does not serve an expression it was not built on. That much is true — but adding
`GIN (lower(title) gin_trgm_ops)` and its `description` twin changed **nothing**: the planner
ignores both, the plan is identical, `Rows Removed by Filter` is identically 34,639, and the timings
were 72.8 → 72.9 ms (page) and 68.0 → 66.6 ms (count). The reason is that the grant predicate has
already cut 500,000 rows to 40,000, and a GIN scan plus `BitmapAnd` costs more than filtering that.

> ⚠️ **The first baseline looked 25% slower and it was cache, not the index** — `read=7144` versus
> `hit=7268`. Both sides were re-run warm before concluding. A cold-versus-warm comparison would have
> "proved" an index worth taking.

So **plan Q5 is answered "neither"**: no expression index, and no move to `search_vector` (whose GIN
index, `test_case_search_idx`, already exists on the table if it is ever wanted). **No Flyway version
taken; head stays `V68`.**

`Rows Removed by Filter` is the permitted shape: 34,639 rows removed, all inside the caller's own
company **and** granted projects. The disallowed shape — filtering away other companies' rows —
does not appear.

## 7. Two more plan corrections

- **`CaseService:141` is not the only `CaseScope` construction site.** The plan says it is;
  `DashboardService:124` is the second, and both had to move to the collection form.
- **The new schemas needed `ContractConventionTest` pairings**, which the plan's watch-list did not
  name. `CaseSearchHit` → `CaseSearchHitView` and `GET /cases/search (200)` →
  `CaseSearchPageView`; the wire views are `public`, matching `CasePageView`, rather than going
  through the package-private string map.
- **The contract path went at the end of the `repository` block**, not "after `/work-items`" as the
  plan said — `/work-items` is in the `work` block, so the plan's two instructions contradicted each
  other and the banner convention is the one that decides.
- **Regenerating `schema.d.ts` picked up §247's pending change.** That area edited `openapi.yaml`
  (removing `Idempotency-Key` from `POST /run-schedules`) and never regenerated either copy, so both
  were stale-but-consistent and `contractPairing` passed. **`schemaFreshness` did not catch it
  either**: it asserts every PATH and OPERATION is typed, and a removed PARAMETER leaves both in
  place. An instrument gap, recorded rather than fixed here.

## 8. The screen, and the empty state that is two states

`/search`, beside `/my-work` and never under `/projects/:projectId` — `MyWorkPage`'s argument, and
stronger here because not knowing which project holds the case is the entire premise.

**"No case matches" and "you can see no projects" are the same HTTP response** — `200` with
`total: 0`, because the server refuses to make them distinguishable (§6t). They are not the same
sentence. Rendering the first at a member holding no grants makes a permissions problem look like a
product with no data in it, and they wait for data instead of asking for a grant. `useProjects`
answers which emptiness it is and is used for nothing else — never to filter, never to narrow.

`isSearchable` is the same predicate as the hook's `enabled` and as the server's 400, so an empty box
asks for nothing rather than being refused (`ui-rules` §4). A case key is exempt from the
two-character minimum on **both** sides, because `TC-4` is an identifier and not a substring. The
pager is driven by the server's `total`; a client-side count would discard the one number this area
exists to make trustworthy. `keys.caseSearch` is deliberately **not** under `['projects', …]`.

## 9. What has no observer

- **`§249 CS-M6`** — removing `CaseScope`'s emptiness guard is **expected green**. It guards a future
  edit; `CS-M5` proves the state is unreachable today.
- **`§249 CS-M7`** — nothing asserts the project fields came from the **gated** set rather than a
  fresh lookup. The gap worth closing, because a *name* is a disclosure where an id is not. It is
  green today by construction, and the row says what would close it.
- **`§249 CS-M9`** may come back green if seven rows are too few for Postgres to reorder. The row
  says so: if it does, the fixture is the finding.
- **The three omitted filters** (`featureId`, `testTypeId`, `testLevelId`) are viable —
  `classification_value` is company-scoped with no `project_id` — and need subtree expansion outside
  `CaseService`'s per-project walk. Plan Q1, out of v1 and recorded.
- **No `owner` on a hit** (Q2) and **no `@tcms/mcp` verb** (Q3): both deliberate scope cuts. The MCP
  one is the obvious next consumer and there is no read-side conformance harness the way
  `writeConformance.test.ts` exists for writes.

## 10. Fixture prefix, and the slug

`CaseSearchTest` takes **`4d…`**, pre-assigned and verified unused across `backend/src`,
`frontend/src` and `packages/*/src`. Its company slugs are **`csearch-acme` / `csearch-umbra`**, and
that mattered: `company` is not under RLS and `uq_company_slug` spans the instance, so a slug is a
**second global namespace the UUID prefix assignment does not cover**. §247 lost 32 unrelated tests
to that exact collision earlier in this batch, and this class was written after the repair.

## 11. Built, and verified how far

The automated gates are **run and green**. Both verification queues are **written and unrun** —
`§249 CS-1…7` in the browser queue (lane `A1`, naming the three-project fixture no lane creates) and
`§249 CS-M1…M9` in the mutation queue, two of them expected green and recorded as gaps. So this
slice is **built and gated, and unverified in the §65 sense**, and must be described that way.
