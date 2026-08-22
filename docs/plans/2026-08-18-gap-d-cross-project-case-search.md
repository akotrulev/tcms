# Cross-Project Case Search Implementation Plan

**Date:** 2026-08-22 · **Written by:** `ep-writing-plans` · **Area D of `docs/plans/2026-08-18-unplanned-gaps.md`**

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Find a test case across every project the caller can see, in one query — so that somebody
handed `TC-412`, or looking for "partial refund", does not have to know which of a company's eleven
projects it lives in. My Work (§83), the team board (§84) and the org chart (§241) all went
cross-project; the repository did not.

**Shape:** `GET /api/v1/cases/search` — company-scoped, paged, each hit naming its own project.

**Architecture:** One new controller and one new service in `repository`, reusing the shipped
`CaseSearch` Criteria component and `CaseSpecifications` predicate builder unchanged in kind. The
project-grant narrowing is **borrowed, not written**: `ProjectService.readAllVisible`
(`backend/src/main/java/com/tcms/project/ProjectService.java:225`) hands back the gated project list,
and that list becomes a `project_id IN (…)` predicate **inside** the same `WHERE` the page, the total
and any future facet share. No migration, no new permission key, no new module edge.

**Tech Stack:** Spring Boot 3.5.6 / Java 21 / Spring Modulith / JPA Criteria · `openapi.yaml` as the
contract, generated into both `schema.d.ts` copies · React 19 / TypeScript / MUI v7 / TanStack Query.

---

## Why this is the riskiest read in the master plan, stated before anything else

Every other case read in this product is scoped to **one** project and gated by **that project's**
grant. `CaseService.list` calls `ProjectService.readVisible`, gets back one `Project` or nothing, and
`CaseSpecifications` adds `project_id = <that one>`. This endpoint returns rows from *many* projects,
and three facts about the isolation model combine into one specific way to get it catastrophically
wrong:

1. **RLS scopes to the COMPANY, not the project.** `docs/specs/tenancy-invariants.md` and ERD §0.1
   are both explicit: the policy predicate is `company_id = current_setting('app.current_company')`.
   Two projects in one company are **both RLS-visible to the same session**. The database will not
   refuse a row from a project the caller holds no grant on — it has no idea such a concept exists.
   **RLS will not save you here.** ERD §0.1's own closing paragraph names this persona for exactly
   this reason: *"a third persona — a company member with no project grant — asserting they see
   nothing, since those rows are in the caller's company and the cross-company assertions
   structurally cannot catch that leak."*

2. **The narrowing is therefore entirely the application's job**, and it is the only boundary. On the
   project-scoped path the same predicate is a *documented survivor mutation* —
   `CaseSpecifications.java:60-74` says so in as many words: deleting `project_id = …` there changes
   nothing observable, because the suite scope is already the project's own live tree. **On this
   endpoint that predicate stops being a survivor and becomes the whole feature.** Task 12 rewrites
   that comment, and Task 14 records the flip as a mutation row.

3. **The leak is bigger than the page.** If the narrowing runs in Java *after* an unrestricted read,
   the total and the paging are computed over rows the caller may not see. The rows themselves are
   hidden, and the caller still learns **how many cases exist in projects they were never granted** —
   a name-free existence claim about the client's portfolio, which is exactly what §6t and
   `AdminReach.visibleProjects` refuse (*"an id the caller already holds is not a disclosure, a name
   is"*, and `OrgChartService.java:34-40`: *"A project the caller cannot open contributes no column,
   no cell and **no count**"*). Worse, the page under such a defect is *short or empty* while the
   pager promises rows, which reads as a paging bug and gets "fixed" by widening the read.

**The consequence for the test suite is the single most important sentence in this document:**

> A test that asserts only on the contents of the page **passes against the defect**. With
> `size=200` and a small fixture, a Java-side post-filter produces exactly the right rows. The arm
> that reddens asserts the **total**, and a second arm asserts it with a page size small enough that
> the page cannot witness the difference at all.

Task 8 is that test, and it is written before any of the production code.

---

## Deviations from the master plan, and why

The master plan's Area D is Tasks 26–32 (`docs/plans/2026-08-18-unplanned-gaps.md:724-786`). Six
things below differ from it. Each was decided against something on disk, and each is cheap to
reverse if the reviewer disagrees.

### 1. The narrowing is borrowed from `project`, not from `identity` — and this is a correction

The master plan's Task 26 says *"`OrgChartService` and `AdminReach` already carry visible-project
narrowing. **Reuse that narrowing**"*. Read literally that means calling
`AdminDirectoryService.reachOf(userId).visibleProjects()` from `com.tcms.repository`.
**Do not do that.** `repository/package-info.java` forbids it in a sentence written for this exact
temptation:

> *"this module still never asks `identity` whether a caller **may** do anything. It hands a user id
> to `project` and is given back either the project or nothing. […] **A permission check appearing
> anywhere in this package is a defect regardless of what this list permits.**"*

`identity` *is* on `repository`'s `allowedDependencies` — so ArchUnit and Modulith would both stay
green, which is precisely why the prohibition is written in prose and why following the master plan's
wording here would ship a violation nothing fails on.

**The correct reuse is the same narrowing reached through the permitted gate.** They are not two
implementations — they are two exposures of one:

| | `identity`'s exposure | `project`'s exposure |
|---|---|---|
| Entry point | `AdminDirectoryService.reachOf` — `identity/AdminDirectoryService.java:129` | `ProjectService.readAllVisible` — `project/ProjectService.java:225` |
| The loop | `for (Project p : projects.findAllByOrderByNameAsc()) if (permissions.evaluate(userId, p.getId()).has(READ_PROJECT)) visible.add(p.getId())` — `:141-149` | `projects.findAllByOrderByNameAsc().stream().filter(p -> access.canView(userId, p.getId()))` — private `visibleProjects`, `:562-566` |
| The question asked | `project:read` (`READ_PROJECT`) | `project:read` — `ProjectAccess.canView`'s javadoc, `project/ProjectAccess.java:49`: *"`canView` is this question asked with `project:read`"* |
| Who may call it | anything that may depend on `identity` | anything that may depend on `project` |
| Shipped cross-project callers | the org chart's columns (`OrgChartService.java:121-127`) | **`/work-items`** — `work/BoardService.java:164` (personal board) and `:230` (team board) |

Both funnel into `PermissionEvaluator`. `ProjectService`'s own comment on the private plural form
(`:555-561`) makes the same anti-duplication argument this task exists to honour: *"the two lines it
consists of **are** the visibility rule: the day a third condition joins it, a second copy is the one
that keeps answering the old question."* Calling `readAllVisible` means this endpoint inherits that
third condition for free on the day it lands.

**So: `repository` asks `project`.** No new dependency edge — `repository`'s declaration is already
`allowedDependencies = {"api", "tenancy", "project", "identity", "storage"}`, and every read in the
module already goes through `ProjectService`. Nothing about ArchUnit or `ModuleStructureTest` moves.

### 2. `/work-items` is mirrored for the HIT, and deliberately not for the ENVELOPE

`openapi.yaml:7071` is the shipped cross-project read and Task 5 mirrors three things from it: the
gate (caller's own visibility, never the subject's), the per-hit `projectId` + `projectKey`, and the
400-rather-than-guess refusal of an unbounded request.

What it must **not** mirror is the response envelope. `/work-items` returns a **bare array** with no
paging, and `BoardService.personalBoard`'s header says why that was acceptable: *"the ANSWER is
person-sized"* — a personal board is bounded by what is assigned to one person. **A case search is
bounded by nothing of the kind**; a company with 40,000 cases can match thousands. So the envelope is
the *other* shipped precedent, the one `/audit-events` and `GET /projects/{projectId}/cases` share:
`allOf: [PageMeta, {items}]`. `PageMeta` is at `openapi.yaml:7617` and its comment is the reason
Task 8 exists — *"`total: 0` is what tells a grid to render 'no results' rather than to keep its
spinner up"*.

### 3. The class names avoid a collision the master plan could not have seen

The master plan names `CaseSearchController`, `CaseSearchService`, `CaseSearchQuery`,
`CaseSearchHitView`. **`com.tcms.repository.CaseSearch` already exists** — it is the Criteria
component that runs the page, the total and the facets, and it is the thing this slice reuses.
`CaseSearchService` sitting beside `CaseSearch` and *not* being its service is the sort of pair that
gets consolidated by a well-meaning later reader.

Shipped names in this plan:

| Master plan | This plan | Why |
|---|---|---|
| `CaseSearchController` | `CrossProjectSearchController` | says what it is: the search that is not about a project |
| `CaseSearchService` | `CrossProjectSearchService` | avoids reading as `CaseSearch`'s service |
| `CaseSearchQuery` | `CrossProjectSearchQuery` | pairs with the service |
| `CaseSearchHitView` | `CaseSearchHitView` | **kept** — no collision, and it is the wire shape's name |
| — | `CaseSearchTest` | **kept** — the master plan's test file name, unchanged |

### 4. The filter set is deliberately narrow, and one exclusion is a real gap

`GET /projects/{projectId}/cases` takes eleven filters. This endpoint takes six, and the exclusions
are decisions rather than omissions:

| Filter | Here? | Why |
|---|---|---|
| `q` | ✅ | the point of the endpoint. Same semantics as the project grid, including the `TC-n` short-circuit — and see the note below about what that short-circuit *becomes* here |
| `ownerId` | ✅ | "everything Maria owns, everywhere" is the second-most-asked cross-project question and needs no per-project resolution |
| `tag` | ✅ | `case_tag` is company-scoped and matched as free text — no vocabulary to resolve |
| `lifecycleStatus`, `automationStatus`, `priority` | ✅ | enum columns on `test_case`; no resolution of any kind |
| `projectId` (repeatable) | ✅ | **narrows within the visible set, never widens it.** Intersected after the grant list, exactly as `?id=` is intersected in the project grid. An id naming an ungranted or non-existent project matches nothing — no 400, no 404, no leak |
| `page`, `size`, `sort` | ✅ | `Page`/`Size` parameter refs, `size` clamped to 1..200 |
| `suiteId`, `unfiled` | ❌ | a suite is one project's folder. Across projects the parameter has no referent, and `CaseScope.liveSuiteIds` is resolved per project by `CaseService` |
| `featureId`, `testTypeId`, `testLevelId` | ❌ **for now** | ⚠️ **This one is a gap, not an impossibility.** `classification_value` carries `company_id` and **no `project_id`** (`V1__initial_schema.sql:382-392`), so a Feature genuinely is company-wide and this filter *would* work cross-project. What it needs is subtree expansion, which today is `CaseService`'s in-memory walk over one project's vocabulary. Deferred to keep this slice one session; recorded as open question **Q1** |
| `healthSignal` | ❌ | an `EXISTS` over `case_health`. Available, but it is a dashboard drill-down and a dashboard is per project |
| `releaseVersion` | ❌ | `CaseSpecifications.release` matches nothing today on any endpoint |
| `id` (by-ids) | ❌ | B8's read exists to resolve a *known* set within a project; a cross-project by-ids read is a different feature |

**The `TC-n` short-circuit is worth more here than it is on the project grid.** `key_seq` is
allocated from a **company** counter (`openapi.yaml`'s create description: *"sequential per company
and therefore shared across its projects — §6t"*), so `TC-412` names exactly one case in the whole
company and the caller does not know which project it is in — which is the situation this endpoint
exists for. It is also why the sort is already stable; see deviation 6.

### 5. An unbounded request is refused with a 400, on `/work-items`' argument

At least one of `q`, `ownerId` or `tag` must be present. Without one, the request is *"every case in
every project I can see"*, which is a company-sized response nobody asked for and no screen can work
from. `/work-items` makes the identical refusal for the identical reason
(`openapi.yaml:7134-7143`: *"there is no board that is every project's work unfiltered, and silently
forcing this on would answer a request nobody made"*), and answers **400**, not 404 — nothing failed
to resolve, and the caller may make any of the requests the message names.

`q` is additionally trimmed and must be ≥ 2 characters *when present*; a 1-character `q` is refused
rather than clamped, because `LIKE '%a%'` over a company is the unbounded read wearing a filter.

### 6. Two things Task 29 asks for are already true, and the plan proves rather than adds them

- **"Stable sort with a secondary key, so paging cannot repeat or skip."** `CaseSort.orderBy`
  (`repository/CaseSort.java:86-99`) **already** appends `cb.asc(root.get("keySeq"))` to every order,
  and its header states the reason in the same words. Because `key_seq` comes from one company
  counter, that trailing key is a **total order across projects**, not merely within one. So no new
  sort code is written — but Task 8 asserts it, because `test_case_key_seq_idx` is
  `CREATE INDEX … (company_id, key_seq)` and **not UNIQUE** (`V1__initial_schema.sql:549`): the
  allocation guarantees the total order, the schema does not enforce it, and an unasserted guarantee
  is a comment.
- **The `IN ()` hazard.** An empty visible-project list must never reach SQL — `IN ()` is a syntax
  error, and this is the third time this codebase has met it (`OrgChartService.java:132-136`,
  `BoardService.forAssignees`). Task 10 answers `total: 0, items: []` **before** a query is built,
  and Task 11 makes the state unrepresentable by throwing in `CaseScope`'s compact constructor.

---

## What already exists — do not rebuild it

| Piece | Where | What it does |
|---|---|---|
| `ProjectService.readAllVisible` | `project/ProjectService.java:225` | **THE narrowing.** `<T> T readAllVisible(UUID userId, Function<List<Project>, T> read)` — runs `read` against every project in the open company this caller holds `project:read` on. `@Transactional(readOnly = true)`. Never 404s; an empty list is the honest answer for a grantless member |
| `ProjectAccess.canView` | `project/ProjectAccess.java:44` | `project:read`, answered by `PermissionEvaluator` — the one implementation of "may they see it" |
| `CaseSearch` | `repository/CaseSearch.java` | `page(request, scope)` and `total(request, scope)`, both Criteria over `EntityManager`, both `@Component`, both package-private methods. **The Hibernate company filter applies to Criteria** — its header says a native query would keep RLS and lose the filter |
| `CaseSpecifications.where` | `repository/CaseSpecifications.java:51` | the one predicate the page and the total share. Line 74 is the project bound |
| `CaseQuery` | `repository/CaseQuery.java` | normalization at the edge: blank→null, `size` clamped 1..200, `page` ≥ 0, `keySeq()`'s `TC-n` parse, `hasFreeText()` |
| `CaseScope` | `repository/CaseScope.java` | the resolved half — the gated project, live suites, expanded classification ids |
| `CaseSort.orderBy` | `repository/CaseSort.java:86` | ORDER BY, always ending `keySeq ASC` |
| `CaseOwners` | `repository/CaseOwners.java` | the **only** class in this module permitted to touch `identity` — §6u display names for owners |
| `PageMeta` | `openapi.yaml:7617` | `{page, size, total}`, all required, on every list response including an empty one |
| `Identifier`, `DataRegion` | `frontend/src/components/` | monospace ids; the loading/loaded/failed/empty/stale matrix |
| `MyWorkPage` | `frontend/src/features/board/MyWorkPage.tsx` | the shipped precedent for a **top-level, projectless** screen and its route argument |

---

## File structure

**Create — `backend/src/main/java/com/tcms/repository/`**

- `CrossProjectSearchController.java` — `@RequestMapping("/api/v1/cases")`, one `@GetMapping("/search")`.
- `CrossProjectSearchService.java` — the gate, the narrowing, the empty short-circuit, the assembly.
- `CrossProjectSearchQuery.java` — the request record; normalizes once, and owns the "at least one
  narrowing predicate" rule.
- `CaseSearchHitView.java` — the wire shape of one hit.
- `CaseSearchPageView.java` — `(page, size, total, items)`.

**Create — tests**

- `backend/src/test/java/com/tcms/repository/CaseSearchTest.java` — the whole area's pin. Fixture
  UUID prefix **`4d`**.

**Modify — backend**

- `repository/CaseScope.java` — `UUID projectId` → `Collection<UUID> projectIds`, non-empty enforced.
- `repository/CaseSpecifications.java:74` — `equal` → `in`, and the comment above it rewritten.
- `repository/CaseService.java:141` — passes `List.of(project.getId())`.

**Modify — contract and generated copies**

- `docs/specs/openapi.yaml` — one path, one parameter block, two schemas. **The fragment is in Task 5
  of this document.**
- `frontend/src/api/schema.d.ts` **and** `packages/client/src/schema.ts` — both regenerated, `diff`ed.

**Create / modify — frontend**

- Create `frontend/src/features/cases/CaseSearchPage.tsx`.
- Modify `frontend/src/app/router.tsx` — one lazy import, one `/search` route beside `/my-work`.
- Modify `frontend/src/app/AppShell.tsx` — one nav entry beside My work.
- Modify `frontend/src/api/queries/keys.ts` — one key.
- Modify `frontend/src/api/queries/cases.ts` — `useCaseSearch`.
- Create `frontend/src/features/cases/CaseSearchPage.test.tsx`.

**Modify — the record**

- `docs/handoff/NNN-<slug>.md` (new) + one index row in `docs/HANDOFF.md`.
- `docs/agent-context/be-java.md`, `docs/agent-context/fe-react.md` — seam entries, **in this slice's
  own commits**.
- `docs/verification/frontend-browser-checks.md`, `docs/verification/backend-mutation-checks.md`.

**Explicitly NOT touched**

- **No migration.** `V68` is pre-assigned to Area C and this plan takes no version. Task 13 measures
  and *records* an index rather than adding one — §242's precedent, which recorded an owed
  `(company_id, project_id, occurred_at DESC)` index and took no number for the same reason.
- **No `packages/`.** `@tcms/cli` and `@tcms/mcp` gain no command. This is a deliberate scope cut and
  is open question **Q3** — an MCP `search_cases` tool is the obvious next consumer and the write
  conformance harness has no read-side twin to pay for.
- **No permission key.** See Task 4.

---

## Phase 0 — orient

### Task 1: Read the precedents before designing anything

**Files:** read only.

- [ ] **Step 1: the two cross-project reads that shipped**

```bash
sed -n '7069,7175p' docs/specs/openapi.yaml            # /work-items — the gate and the 400
sed -n '145,240p'  backend/src/main/java/com/tcms/work/BoardService.java
sed -n '195,232p;545,570p' backend/src/main/java/com/tcms/project/ProjectService.java
sed -n '100,170p'  backend/src/main/java/com/tcms/identity/OrgChartService.java
```

What to take from each: from `/work-items`, the refusal to guess and the per-hit project identity;
from `BoardService.teamBoard`, the sentence *"the bound is the CALLER's visibility, and never the
team's"* — this endpoint's bound is the caller's and never the company's; from `ProjectService`, the
two exposures of one gate; from `OrgChartService`, that a project the caller cannot open contributes
**no count**.

- [ ] **Step 2: the module rule that decides which gate you may call**

```bash
sed -n '1,70p' backend/src/main/java/com/tcms/repository/package-info.java
```

Confirm for yourself that `identity` is on the allowed list **and** that asking it a permission
question is prohibited anyway. This is the deviation-1 argument and it is the one thing in this plan
that no automated check will catch you on.

- [ ] **Step 3: the pieces you are reusing**

```bash
sed -n '55,105p'  backend/src/main/java/com/tcms/repository/CaseSearch.java
sed -n '45,110p'  backend/src/main/java/com/tcms/repository/CaseSpecifications.java
cat               backend/src/main/java/com/tcms/repository/CaseScope.java
sed -n '68,100p'  backend/src/main/java/com/tcms/repository/CaseSort.java
```

- [ ] **Step 4: confirm the orientation cache has not moved under you**

```bash
head -20 docs/agent-context/be-java.md
git log --oneline -8
```

`be-java.md`'s `Last verified:` header names §243. Four lanes were running in this tree on
2026-08-22; **re-grep every line number this document cites** before trusting it. The file:line
references here were taken at `cbc6437`.

**Acceptance:** you can state, in one sentence and without looking, why `AdminReach.visibleProjects`
is the wrong handle for this module and `readAllVisible` is the right one.

---

## Phase 1 — the contract

**One commit.** The path, the parameters and the two schemas exist on both sides of the wire before
anything serves them. Nothing is implemented yet; the commit message says so.

### Task 2: Decide the four things the contract has to pin, and write them down

- [ ] **Step 1: the response envelope** — `allOf: [PageMeta, {items}]`, not a bare array. Deviation 2.
- [ ] **Step 2: the refusal** — 400 when no narrowing predicate is present. Deviation 5.
- [ ] **Step 3: the empty answer** — `200` with `total: 0` for a member holding no grants. **Never a
      403**, and never a 404: the request names nothing that could fail to resolve, and a 403 would
      make "you hold no grants" distinguishable from "there is nothing matching", which is a fact
      about the company's portfolio (§6t).
- [ ] **Step 4: what a hit carries** — and, more importantly, what it does not. See Task 3.

### Task 3: Design `CaseSearchHit`, and justify every field against a disclosure

**Not `TestCaseSummary`.** That schema has eighteen required fields and its assembly
(`CaseSummaries`) runs four extra queries per page for tags, classifications, health and owners, plus
the §6u membership resolution. Reused here, those become per-page queries **across projects** for
data the search result does not need, and two of its fields (`suiteId`, `suiteName`) are
project-local by construction. A search hit is a *waypoint* — enough to recognise the case and
navigate to it — not a grid row.

| Field | Required | Justification |
|---|---|---|
| `id` | ✅ | the link target |
| `key`, `keySeq` | ✅ | `TC-412`. `keySeq` because text keys do not order — `TestCaseSummary`'s own note |
| `title` | ✅ | the thing being searched |
| `projectId` | ✅ | the SPA needs it to build `/projects/{projectId}/cases/{id}` |
| `projectKey` | ✅ | `/work-items`' `WorkItem.projectKey` (`openapi.yaml:13380`), same reason: an `Identifier` chip |
| `projectName` | ✅ | the master plan's stated requirement, and the grouping header. **A name is a disclosure** (`AdminReach`'s rule) — permitted here because the project list is exactly `readAllVisible`'s output, which is `project:read`, which is what `GET /projects` already serves in full |
| `lifecycleStatus`, `automationStatus`, `priority` | ✅ | already columns on the row; no extra query; they are the filters, so a result that does not show them cannot be checked by eye |
| `updatedAt` | ✅ | "is this the live one" — the question a person asks of a name they half-remember |
| `owner` | ❌ | would drag `CaseOwners` and the §6u membership resolution across N projects. Open question **Q2** |
| `tags`, `features`, `healthSignals`, `lastResult` | ❌ | four more per-page queries for a waypoint |
| `suiteId`, `suiteName` | ❌ | project-local |

### Task 4: Confirm no permission key is invented

- [ ] **Step 1: state the gate.** `project:read`, per project, via `readAllVisible`. Nothing else.
- [ ] **Step 2: state why no new key.** `BoardService.teamBoard`'s header is the precedent and the
      argument is verbatim applicable: *"A key invented for this would be seeded to nobody and
      therefore a 403 no administrator could grant their way out of"* — §67's `evidence:read`, §79's
      `suite:comment`, §80's assignment gate. Every row this endpoint can return is one the caller
      could already read on that project's case grid.
- [ ] **Step 3: confirm the catalogue does not move.**

```bash
grep -c "INSERT INTO permission" backend/src/main/resources/db/migration/*.sql | tail -3
```

The catalogue stays at **55**. If your slice changes that number, you have taken a wrong turn.

### Task 5: The contract fragment

> ⚠️ **This plan does not edit `openapi.yaml`** — it was written while another lane held the
> contract. The fragment below is the change, verbatim, for the implementer to apply.

- [ ] **Step 1: add the path.** Insert after the `/work-items` block (`openapi.yaml:7069`), keeping
      the `# ---- repository ----` banner convention of the file.

```yaml
  # -------------------------------------------------- cross-project search ----
  /cases/search:
    get:
      tags: [repository]
      summary: Find cases across every project you can see
      description: |
        **Company-scoped.** Every other case read in this API is scoped to one project and gated by
        that project's grant; this one returns rows from many. The bound is the **caller's own**
        project visibility (`project:read`, per project) — never the company's project list. Two
        colleagues searching the same word legitimately receive different results and different
        totals; that is the design, and it is the same rule `/work-items`' team board states.

        **A project you hold no grant on contributes no hit and no count.** The `total` is computed
        over the same narrowed set as the page, so it can never report the size of a result set you
        cannot page to. A count is a name-free existence claim about the client's portfolio and §6t
        does not permit one.

        **A member with no project grants at all gets `200` with `total: 0`** — not a 403. There is
        nothing here that could fail to resolve, and a distinguishable refusal would answer the
        question the empty answer exists to withhold.

        **At least one of `q`, `ownerId` or `tag` is required** — see the 400. There is no search
        that is every case in every project, for `/work-items`' reason: it is a company-sized
        response no screen can work from.

        A `q` matching the case-key pattern (`TC-412`) short-circuits to an exact lookup, as on the
        project grid. Keys are allocated from a **company** counter and are therefore unique across
        a company's projects (§6t) — which is exactly the situation this endpoint exists for: you
        were handed a key and do not know which project it belongs to.
      parameters:
        - { $ref: '#/components/parameters/Page' }
        - { $ref: '#/components/parameters/Size' }
        - name: q
          in: query
          description: |
            Free text over title and description, or a case key (`TC-412`), which short-circuits to
            an exact lookup. **At least two characters** after trimming; one character is refused
            rather than clamped, because a single-character substring over a whole company is the
            unbounded read wearing a filter.
          schema: { type: string, minLength: 2 }
        - name: projectId
          in: query
          description: |
            **Narrows within what you can already see — it never widens it.** Repeat to name
            several. The grant list is applied first and this is intersected with it, so an id
            naming a project you hold no grant on, or one that does not exist, simply matches
            nothing: no 400, no 404, and no way to tell those two apart (§6t).
          schema:
            type: array
            items: { type: string, format: uuid }
        - name: ownerId
          in: query
          description: Cases owned by one person, across every project you can see.
          schema: { type: string, format: uuid }
        - name: tag
          in: query
          description: |
            Cases carrying **every** tag listed — AND, not OR, exactly as on the project grid.
          schema:
            type: array
            items: { type: string }
        - name: lifecycleStatus
          in: query
          schema:
            type: array
            items: { $ref: '#/components/schemas/LifecycleStatus' }
        - name: automationStatus
          in: query
          schema:
            type: array
            items: { $ref: '#/components/schemas/AutomationStatus' }
        - name: priority
          in: query
          schema:
            type: array
            items: { $ref: '#/components/schemas/Priority' }
        - name: sort
          in: query
          description: |
            `field,dir`. Every ordering ends in the case key, which is allocated per company and is
            therefore a total order across projects — so paging cannot repeat or skip a row.
          schema: { type: string, example: 'keySeq,asc' }
      responses:
        '200':
          description: |
            The page of hits, and the total **over the projects you can see**. An empty `items` with
            `total: 0` is a reachable search with nothing in it — including for a member who holds
            no project grants at all.
          content:
            application/json:
              schema:
                allOf:
                  - $ref: '#/components/schemas/PageMeta'
                  - type: object
                    # `required` does not descend through allOf — the PageMeta branch declares its
                    # own and this one needs the same.
                    required: [items]
                    properties:
                      items:
                        type: array
                        items: { $ref: '#/components/schemas/CaseSearchHit' }
        '400':
          # A refusal to GUESS, not a validation failure: no q, no ownerId and no tag is the request
          # "every case in every project I can see", which is company-sized. Not a 404 — nothing
          # failed to resolve, and the caller may make any of the requests the message names.
          $ref: '#/components/responses/BadRequest'
        '401': { $ref: '#/components/responses/Unauthorized' }
```

- [ ] **Step 2: add the hit schema**, beside `TestCaseSummary` (`openapi.yaml:8666`).

```yaml
    CaseSearchHit:
      description: |
        One cross-project search result — a **waypoint**, not a grid row.

        Deliberately *not* `TestCaseSummary`: that shape carries the owner, the tags, the
        classifications, the health signals and the last result, each of which costs a per-page
        query, and two of its fields (`suiteId`, `suiteName`) name a folder that only exists inside
        one project. A hit carries what is needed to recognise a case and navigate to it, and the
        project it belongs to — because the caller, by construction, does not know.
      type: object
      required:
        [id, key, keySeq, title, projectId, projectKey, projectName,
         lifecycleStatus, automationStatus, priority, updatedAt]
      properties:
        id: { type: string, format: uuid }
        key: { type: string, example: TC-412 }
        keySeq: { type: integer, description: Sort by this — text keys do not order correctly. }
        title: { type: string }
        # The project a hit belongs to. Every one of these three is already served in full by
        # GET /projects for exactly this set of projects — the set is `project:read`, which is the
        # gate this endpoint applies — so naming them here discloses nothing new. The NAME is the
        # one that would be a disclosure if the narrowing were ever widened; see AdminReach.
        projectId: { type: string, format: uuid }
        projectKey: { type: string }
        projectName: { type: string }
        lifecycleStatus: { $ref: '#/components/schemas/LifecycleStatus' }
        automationStatus: { $ref: '#/components/schemas/AutomationStatus' }
        priority: { $ref: '#/components/schemas/Priority' }
        updatedAt: { type: string, format: date-time }
```

- [ ] **Step 3: check the `required` arrays.** The working agreement's rule — `required` does not
      descend, including into the inline `allOf` branch. Both are declared above; verify after
      pasting, because this is the failure that makes every generated property optional.

### Task 6: Regenerate both schema copies and diff them

- [ ] **Step 1: regenerate**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

- [ ] **Step 2: prove they are byte-identical** — `schemaFreshness.test.ts` fails otherwise.

```bash
cd ~/RiderProjects/tcms
diff frontend/src/api/schema.d.ts packages/client/src/schema.ts && echo IDENTICAL
grep -n "CaseSearchHit" frontend/src/api/schema.d.ts | head -3
```

- [ ] **Step 3: gate**

```bash
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote frontend npm run build
```

- [ ] **Step 4: commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts
git commit -m "feat(contract): GET /cases/search, the first case read that is not about a project

Company-scoped, paged, each hit naming its own project. The bound is the
CALLER's project visibility and never the company's — /work-items' team-board
rule, one resource over. Nothing serves this yet.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2 — the failing test

**One commit, and it is red.** Written before the production code, per `ep-test-driven-development`.
This is the phase the whole area exists for.

### Task 7: Build the fixture — UUID prefix `4d`

**Files:** Create `backend/src/test/java/com/tcms/repository/CaseSearchTest.java`.

The fixture is modelled on `CaseListEndpointTest`'s (`repository/CaseListEndpointTest.java:60-110`)
and is deliberately hostile in one *new* direction: where that class needed one ungranted sibling
project to prove a project-scoped read stayed in its project, this one needs **more matching rows
outside the grant than inside it**, so that a count computed over the wrong set is a different number
rather than the same one.

- [ ] **Step 1: pin the ids.** Prefix `4d`, pre-assigned and verified unused on 2026-08-22. Do not
      choose another — the prefixes are hand-managed and have collided five times
      (`docs/specs/parallel-work-rules.md`).

```bash
grep -rn "4d000000-0000-4000" backend/src/test | head   # expect: no output
```

```java
    private static final UUID ACME  = UUID.fromString("4d000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("4d000000-0000-4000-8000-00000000000b");

    /** GRANTED to SEEKER. Holds exactly TWO cases matching "refund". */
    private static final UUID GRANTED   = UUID.fromString("4d001000-0000-4000-8000-000000000001");
    /** Same company, NOT granted. Holds FOUR matching "refund" — deliberately more than GRANTED. */
    private static final UUID UNGRANTED = UUID.fromString("4d001000-0000-4000-8000-000000000002");
    /** Same company, NOT granted, and holds ONE matching case whose key sorts FIRST of all seven. */
    private static final UUID SHADOW    = UUID.fromString("4d001000-0000-4000-8000-000000000003");
    /** The other client, with an identically-titled case and an identically-shaped key. */
    private static final UUID UMBRA_ONE = UUID.fromString("4d002000-0000-4000-8000-000000000001");

    /** Test Lead on GRANTED alone. The persona every assertion is written from. */
    private static final UUID SEEKER   = UUID.fromString("4d003000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding NOTHING. ERD §0.1's third persona. */
    private static final UUID NO_GRANT = UUID.fromString("4d003000-0000-4000-8000-000000000002");
    /** Company Admin — reaches all three Acme projects through a COMPANY-scope grant. */
    private static final UUID ADMIN    = UUID.fromString("4d003000-0000-4000-8000-000000000003");
    /** Test Engineer in Umbra only. Must never see an Acme row, nor an Acme count. */
    private static final UUID OUTSIDER = UUID.fromString("4d003000-0000-4000-8000-000000000004");
```

- [ ] **Step 2: the case rows.** Seven Acme cases matching `refund`, allocated from **one company
      counter** so the keys interleave across projects — this is what makes the ordering assertion
      meaningful:

| `key` | project | title | why it is there |
|---|---|---|---|
| `TC-1` | **SHADOW** (ungranted) | `Refund the whole order` | **sorts first of all seven.** Under a Java-side post-filter with `size=1`, the page comes back **empty** while `total` says 7 |
| `TC-2` | GRANTED | `Partial refund of a shipped order` | hit |
| `TC-3` | UNGRANTED | `Refund a cancelled order` | must not appear, must not be counted |
| `TC-4` | UNGRANTED | `Refund to store credit` | " |
| `TC-5` | GRANTED | `Refund reversal` | hit |
| `TC-6` | UNGRANTED | `Refund audit trail` | " |
| `TC-7` | UNGRANTED | `Bulk refund` | " |

Plus, in Acme: one **soft-deleted** case in GRANTED titled `Refund (deleted)`, and one case in
GRANTED titled `Shipping` that matches nothing. In Umbra: `TC-1` titled `Refund the whole order` —
the same title and the same key in another company.

**Two granted, five ungranted, one deleted.** Every wrong answer is a *different number*: 2 is
correct, 7 is the whole-company leak, 8 counts the soft-deleted row, 3 is a per-project loop that
forgot the grant on one arm.

- [ ] **Step 3: seed it** using `CaseListEndpointTest`'s helper shape — `tx.executeWithoutResult`
      for the instance-level rows (`company`, `activeUser`), then `inCompany(ACME, …)` for projects,
      memberships, grants and cases. Grant `Role.TEST_LEAD_ID` to `SEEKER` on `GRANTED` only, and
      `Role.COMPANY_ADMIN_ID` to `ADMIN` at company scope.

```bash
grep -n "private void company(\|private void activeUser(\|private void member(\|grantOnProject(\|grantOnCompany(" \
  backend/src/test/java/com/tcms/repository/CaseListEndpointTest.java
```

Copy those helpers rather than inventing new ones, and **do not** move them to `IntegrationTestBase`
— company and user ids unique per class is what keeps the suite green in aggregate
(`CaseListEndpointTest`'s header: *"Sharing them with another test class is how handoff §19's first
defect happened — green alone, red in the full suite."*).

### Task 8: Write the arms — the count first

- [ ] **Step 1: the class header.** State the defect the class exists to catch, because it is not
      obvious from the assertions:

```java
/**
 * {@code GET /cases/search} — the one case read that spans projects.
 *
 * <h2>The defect this class exists to catch, and why the obvious test misses it</h2>
 *
 * <p><strong>RLS does not help here.</strong> The policy predicate is the company, and every project
 * in this fixture's Acme is in the caller's company — so the database serves all seven matching rows
 * to the same session, and the narrowing to the two the caller was granted is entirely the
 * application's. That is the opposite of every other case read in this product, where the project
 * bound is a documented survivor mutation ({@code CaseSpecifications}:60).
 *
 * <p><strong>A test written about the PAGE passes straight through the defect.</strong> Filter in
 * Java after an unrestricted read and the page is exactly right at {@code size=200}: five rows are
 * dropped and the caller sees the two that are theirs. What is wrong is the {@code total} — 7, a
 * name-free existence claim about four projects' worth of the client's portfolio — and the paging,
 * which is computed over rows the caller cannot reach. So the first assertion in this class is on
 * the count, and the second is on a page too small to witness the difference.
 *
 * <p>The fixture is arranged so that every wrong answer is a DIFFERENT NUMBER: 2 is correct, 7 is
 * the company-wide leak, 8 counts the soft-deleted row, 3 is a per-project loop that forgot the
 * grant on one arm. And {@code TC-1} — the row that sorts FIRST of all seven — is in a project the
 * caller was not granted, so a post-filter at {@code size=1} returns an EMPTY page beside a total
 * of 7.
 */
```

- [ ] **Step 2: the arm that matters.**

```java
    @Test
    @DisplayName("the total counts only the granted projects, not the company's matching rows")
    void theTotalIsNarrowedToo() throws Exception {
        JsonNode body = bodyAs(SEEKER, ACME, "q=refund&size=200");

        assertThat(body.get("total").asLong())
                .as("""
                        Seven cases in Acme match 'refund' and the caller holds a grant on the two \
                        in GRANTED. A total of 7 means the count was computed before the narrowing \
                        — the page hides those rows and the number discloses that they exist, \
                        which is the leak §6t and AdminReach.visibleProjects both refuse. A total \
                        of 8 additionally counted the soft-deleted row.""")
                .isEqualTo(2L);

        assertThat(keysOf(body))
                .as("and the page agrees with the count it was served beside")
                .containsExactly("TC-2", "TC-5");
    }
```

- [ ] **Step 3: the arm a page-only assertion cannot fake.**

```java
    @Test
    @DisplayName("a page too small to witness the filter still reports the narrowed total")
    void thePageCannotHideTheCount() throws Exception {
        JsonNode body = bodyAs(SEEKER, ACME, "q=refund&size=1&sort=keySeq,asc");

        assertThat(body.get("total").asLong())
                .as("this is the same claim as theTotalIsNarrowedToo, made where the page carries "
                        + "no evidence either way — one row proves nothing about six others")
                .isEqualTo(2L);

        assertThat(keysOf(body))
                .as("""
                        TC-1 sorts first of all seven matches and lives in SHADOW, which this \
                        caller was NOT granted. If the narrowing runs in Java after the read, the \
                        database serves TC-1 as the single row of page 0, Java drops it, and the \
                        response is an EMPTY page beside a total promising rows. Narrowed inside \
                        the query, page 0 is TC-2.""")
                .containsExactly("TC-2");
    }
```

- [ ] **Step 4: the arm that names RLS explicitly**, so the next reader does not add one.

```java
    @Test
    @DisplayName("RLS is not what saves this endpoint — the ungranted rows are in the caller's own company")
    void rlsIsNotTheBoundary() throws Exception {
        // Ground truth, taken as the container superuser: the rows the caller must not receive are
        // in the caller's company and are therefore RLS-VISIBLE to the caller's own session. If
        // this assertion ever fails, the fixture stopped testing what the class claims to test.
        try (Connection connection = superuserConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM test_case WHERE company_id = ? AND deleted_at IS NULL "
                             + "AND lower(title) LIKE '%refund%'")) {
            statement.setObject(1, ACME);
            var rows = statement.executeQuery();
            rows.next();
            assertThat(rows.getLong(1))
                    .as("seven matching rows in ONE company — five of them in projects the caller "
                            + "holds no grant on. RLS refuses none of them.")
                    .isEqualTo(7L);
        }

        assertThat(bodyAs(SEEKER, ACME, "q=refund&size=200").get("total").asLong())
                .as("and the application, not the database, is what reduces seven to two")
                .isEqualTo(2L);
    }
```

- [ ] **Step 5: the remaining arms.** One `@Test` each, all against the same fixture:

| Arm | Drive | Expect |
|---|---|---|
| `theGrantlessMemberSeesNothingAndIsNotRefused` | `NO_GRANT`, `q=refund` | **200**, `total: 0`, `items: []`. Not 403, not 404. ERD §0.1's third persona |
| `aCompanyAdminSeesEveryProject` | `ADMIN`, `q=refund` | `total: 7`, keys `TC-1`…`TC-7`. Proves the narrowing is a *grant* question and not a hard-coded single project — and that the COMPANY-scope grant reaches projects with no per-project row |
| `anotherClientIsInvisible` | `OUTSIDER`, `q=refund` in `UMBRA` | `total: 1`, the Umbra `TC-1` only. The identical title and identical key in Acme prove only ids can be doing the scoping |
| `theProjectFilterNarrowsAndNeverWidens` | `SEEKER`, `q=refund&projectId=<UNGRANTED>` | `total: 0`. Naming an ungranted project matches nothing — no 400, no 404, no way to distinguish it from a project that does not exist |
| `theProjectFilterIntersects` | `ADMIN`, `q=refund&projectId=<GRANTED>` | `total: 2` — the parameter narrows within what the caller can see |
| `aKeyShortCircuitsAcrossProjects` | `ADMIN`, `q=TC-4` | exactly one hit, `TC-4`, whose `projectId` is `UNGRANTED`. And `SEEKER` with `q=TC-4` gets `total: 0` — **the key lookup is gated too**, which is the arm somebody will forget because it looks like an id lookup rather than a search |
| `theSoftDeletedCaseIsGone` | `SEEKER`, `q=refund` | no `Refund (deleted)` in items and `total` still 2 |
| `everyHitNamesItsProject` | `ADMIN`, `q=refund` | every item has non-null `projectId`, `projectKey`, `projectName`, and the three agree with `GET /projects` for that id |
| `pagingIsStableAcrossProjects` | `ADMIN`, `size=3` pages 0,1,2 | the nine keys across the three pages are distinct and their union is all seven, none repeated, none missing. The tie-break is `keySeq`, which is company-allocated and therefore total *across* projects — `test_case_key_seq_idx` is **not UNIQUE**, so this is asserted rather than assumed |
| `anUnboundedSearchIsRefused` | `SEEKER`, `size=200` with no `q`, `ownerId` or `tag` | **400**, and the body names the three parameters |
| `aOneCharacterQueryIsRefused` | `SEEKER`, `q=r` | **400** |
| `theOwnerFilterIsCrossProject` | `ADMIN`, `ownerId=<someone owning one case in each of two projects>` | `total: 2`, two different `projectId`s |

- [ ] **Step 6: run it, and confirm it fails for the right reason**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.repository.CaseSearchTest'
```

Expected: **every arm fails with 404**, because the endpoint does not exist. A failure of any other
shape at this point means the fixture is wrong, not the product.

- [ ] **Step 7: commit the red test**

```bash
git add backend/src/test/java/com/tcms/repository/CaseSearchTest.java
git commit -m "test: the cross-project search's grant narrowing, asserted on the TOTAL

RLS scopes to the company, so five of the seven matching rows are in the
caller's own company and the database refuses none of them. A test written
about the page passes straight through a Java-side post-filter; these assert
the count, and assert it again at a page size that carries no evidence.

Red: the endpoint does not exist yet.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Phase 3 — the narrowing, inside the query

**One commit.** The predicate widens, the service and controller land, and Phase 2 goes green.

### Task 9: Widen `CaseScope` from one project to a set — and make emptiness unrepresentable

**Files:** Modify `backend/src/main/java/com/tcms/repository/CaseScope.java`.

- [ ] **Step 1: the field.**

```java
 * @param projectIds   the gated projects — <strong>one</strong> for every project-scoped read, and
 *                     the caller's whole visible set for the cross-project search. Present as a
 *                     predicate rather than trusted from the path or from the session: the caller
 *                     passes exactly the ids it was <em>handed back</em> by
 *                     {@link com.tcms.project.ProjectService#readVisible} or
 *                     {@code readAllVisible}, never ones it received.
 *
 *                     <p><strong>Never empty, and that is enforced rather than documented.</strong>
 *                     An empty collection bound into a SQL {@code IN ()} is a syntax error, and the
 *                     repair somebody reaches for under time pressure — dropping the predicate when
 *                     the set is empty — turns "this caller may see nothing" into "this caller may
 *                     see everything". The caller answers an empty page before building a scope;
 *                     this constructor is what makes forgetting to fail loudly instead of quietly.
```

```java
record CaseScope(
        Collection<UUID> projectIds,
        Collection<UUID> memberCaseIds,
        ...) {

    CaseScope {
        projectIds = List.copyOf(projectIds);
        if (projectIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "a case scope with no projects would be an IN () — answer the empty page above");
        }
        ...
    }
}
```

- [ ] **Step 2: the one existing construction site.**

```bash
grep -n "new CaseScope(" backend/src/main/java/com/tcms/repository/*.java
```

`CaseService.java:141` is the only one. Change `project.getId()` to `List.of(project.getId())`.

### Task 10: The predicate — `IN`, not `=`, and rewrite the comment above it

**Files:** Modify `backend/src/main/java/com/tcms/repository/CaseSpecifications.java`.

The comment at lines 60-74 currently argues that this predicate is a **documented survivor mutation**
— that deleting it changes nothing, because the suite scope already bounds the query to one project's
tree. **That argument is true only of the project-scoped read.** On the cross-project search there is
no suite scope narrowing anything to one project, and this line is the entire boundary.

- [ ] **Step 1: replace the comment.** Keep the old text struck through — the working agreement
      requires superseded reasoning to survive, so the next reader does not think it was never
      considered.

```java
        // ~~Removing this line is a mutation that SURVIVES, and it survives structurally rather
        // than for want of a test.~~ → TRUE OF THE PROJECT-SCOPED READ ONLY, AND FALSE SINCE THE
        // CROSS-PROJECT SEARCH.
        //
        // On GET /projects/{id}/cases the suite scope below is the project's own live tree, so a
        // case in another project hangs off a suite never in the set and no fixture could make the
        // difference observable. On GET /cases/search there IS no suite scope — the projects are
        // many and their trees are not enumerated — so THIS LINE IS THE WHOLE PROJECT BOUNDARY, and
        // RLS is not behind it: the policy predicate is the COMPANY, and every project the caller
        // is not granted is in the caller's own company and therefore RLS-visible to this very
        // session.
        //
        // The ids are the ones ProjectService handed back (readVisible / readAllVisible), never
        // ones the request supplied. Because the predicate is here rather than in Java after the
        // read, the total and the paging are computed over exactly the rows the caller may see —
        // which is what CaseSearchTest.theTotalIsNarrowedToo and .thePageCannotHideTheCount assert,
        // and what a page-only assertion cannot.
        //
        // It also still does what it always did: it is what makes the query reachable through
        // test_case_lifecycle_idx (company_id, project_id, lifecycle_status).
        all.add(root.get("projectId").in(scope.projectIds()));
```

- [ ] **Step 2: confirm nothing else reads `scope.projectId()`**

```bash
grep -rn "projectId()" backend/src/main/java/com/tcms/repository/
```

### Task 11: `CrossProjectSearchQuery` — normalize once, and own the refusal

**Files:** Create `backend/src/main/java/com/tcms/repository/CrossProjectSearchQuery.java`.

- [ ] **Step 1: the record.** `CaseQuery`'s shape — Spring binds the constructor parameters by name,
      and the compact constructor is the only place a blank string is decided about.

```java
/**
 * One cross-project search request, parsed — {@link CaseQuery}'s sibling for the read that names no
 * project.
 *
 * <p>A separate record rather than a wider {@link CaseQuery}, for the reason {@link CaseScope}'s
 * header gives about itself: a record whose field names have started lying is worse than two
 * records. {@code CaseQuery} carries {@code suiteId}, {@code unfiled} and {@code releaseVersion},
 * none of which has a referent across projects, and the "at least one narrowing predicate" rule
 * below is true here and false there.
 *
 * @param projectIds narrows WITHIN the caller's visible set and never widens it — the service
 *                   intersects, and this record makes no claim about visibility at all
 */
public record CrossProjectSearchQuery(
        String q,
        List<UUID> projectId,
        UUID ownerId,
        List<String> tag,
        List<LifecycleStatus> lifecycleStatus,
        List<AutomationStatus> automationStatus,
        List<Priority> priority,
        int page,
        int size,
        String sort) {

    /** The contract's stated minimum. One character over a whole company is not a filter. */
    static final int MIN_QUERY = 2;

    public CrossProjectSearchQuery {
        q = blankToNull(q);
        projectId = distinctNonNull(projectId);
        tag = trimmedDistinct(tag);
        lifecycleStatus = List.copyOf(lifecycleStatus == null ? List.of() : lifecycleStatus);
        automationStatus = List.copyOf(automationStatus == null ? List.of() : automationStatus);
        priority = List.copyOf(priority == null ? List.of() : priority);
        page = Math.max(0, page);
        size = Math.clamp(size <= 0 ? CaseQuery.DEFAULT_SIZE : size, 1, CaseQuery.MAX_SIZE);
    }

    /**
     * Whether this request narrows by anything at all.
     *
     * <p>False is a <strong>400</strong>, not an unfiltered search. The enum filters do not count:
     * "every APPROVED case in every project I can see" is still company-sized, and the three that
     * do count are the three that select a small set. {@code /work-items} makes the same refusal in
     * the same words — there is no board that is every project's work unfiltered.
     */
    boolean isBounded() {
        return q != null || ownerId != null || !tag.isEmpty();
    }

    /** True when {@code q} is present and too short to be a filter over a whole company. */
    boolean queryTooShort() {
        return q != null && q.length() < MIN_QUERY && CaseQuery.keySeqOf(q) == null;
    }
}
```

- [ ] **Step 2: reuse the key parse rather than copying it.** `CaseQuery.keySeq()` is an instance
      method over `q`. Extract the static half — `static Long keySeqOf(String q)` — and have the
      instance method call it, so `TC-n` is parsed in exactly one place. **Do not copy the regex.**

```bash
grep -n "CASE_KEY\|keySeq()" backend/src/main/java/com/tcms/repository/CaseQuery.java
```

- [ ] **Step 3: build the `CaseQuery` this maps onto.** The cross-project service still runs
      `CaseSearch.page/total`, which take a `CaseQuery`. Add one package-private factory so the
      mapping lives in one place and cannot drift:

```java
    /** The {@link CaseQuery} this request means — the fields with a cross-project referent. */
    CaseQuery asCaseQuery() {
        return new CaseQuery(
                q, List.of(), null, null, null, null, ownerId,
                lifecycleStatus, automationStatus, priority, List.of(),
                tag, false, null, page, size, sort);
    }
```

### Task 12: `CrossProjectSearchService` — the gate, the short-circuit, the assembly

**Files:** Create `backend/src/main/java/com/tcms/repository/CrossProjectSearchService.java`.

- [ ] **Step 1: the class header.** This is where the area's argument lives.

```java
/**
 * Cases across every project the caller can see — the one read in this module that names no project.
 *
 * <h2>The gate is borrowed, and from {@code project}, not {@code identity}</h2>
 *
 * <p>{@link ProjectService#readAllVisible} hands back exactly the projects this caller holds
 * {@code project:read} on, computed by the one implementation of that question
 * ({@code ProjectAccess.canView} → {@code PermissionEvaluator}). {@code AdminReach.visibleProjects}
 * is the same set reached through {@code identity}, and this module may not ask it: its package
 * comment permits the {@code identity} edge for display names and forbids a permission question
 * through it in as many words — <em>"a permission check appearing anywhere in this package is a
 * defect regardless of what this list permits"</em>. The two are not two implementations; they are
 * two exposures of one, and this is the exposure {@code repository} is allowed to reach.
 *
 * <h2>The narrowing is a predicate, not a filter — and that distinction is the whole feature</h2>
 *
 * <p>The gated ids go into {@link CaseScope} and become {@code project_id IN (…)} inside the same
 * {@code WHERE} that {@link CaseSearch#page} and {@link CaseSearch#total} share. Filtering in Java
 * after an unrestricted read would produce the right PAGE and the wrong TOTAL, and the wrong total
 * is a name-free existence claim about projects the caller was never granted — the disclosure §6t
 * and {@code AdminReach} both refuse, and the one {@code OrgChartService} refuses even as a count.
 *
 * <p><strong>RLS is not behind this.</strong> The policy predicate is the company; every project in
 * this company is visible to this session. There is no second boundary to be wrong under.
 *
 * <h2>Empty is answered before a query is built</h2>
 *
 * <p>A member holding no grants gets {@code total: 0} and no SQL, because an empty collection bound
 * into {@code IN ()} is a syntax error — the third time this codebase has met it
 * ({@code OrgChartService}:132, {@code BoardService.forAssignees}). {@link CaseScope} additionally
 * refuses to be constructed empty, so the short-circuit cannot be deleted quietly.
 */
```

- [ ] **Step 2: the method.**

```java
    @Transactional(readOnly = true)
    public CaseSearchPageView search(UUID userId, CrossProjectSearchQuery request) {
        return projects.readAllVisible(userId, visible -> {
            // The request may narrow within the visible set; it may never widen it. The
            // intersection is here, above the scope, so nothing downstream ever holds an id that
            // did not come back from the gate.
            List<Project> gated = request.projectId().isEmpty()
                    ? visible
                    : visible.stream()
                            .filter(project -> request.projectId().contains(project.getId()))
                            .toList();

            if (gated.isEmpty()) {
                // Reachable and empty. Also the IN () guard — see the header.
                return new CaseSearchPageView(request.page(), request.size(), 0L, List.of());
            }

            CaseScope scope = new CaseScope(
                    gated.stream().map(Project::getId).toList(),
                    null, List.of(), true, null, null, null);
            CaseQuery query = request.asCaseQuery();

            Map<UUID, Project> byId = gated.stream()
                    .collect(Collectors.toMap(Project::getId, project -> project));

            List<CaseSearchHitView> items = search.page(query, scope).stream()
                    .map(row -> hit(row, byId.get(row.getProjectId())))
                    .toList();

            return new CaseSearchPageView(
                    request.page(), request.size(), search.total(query, scope), items);
        });
    }
```

- [ ] **Step 3: the two things `CaseScope` is given that need arguing in a comment.**
      `liveSuiteIds = List.of()` with `includeUnfiled = true` means *no suite bound at all*.
      Confirm that `CaseSpecifications.suiteScope` produces "no predicate" rather than "matches
      nothing" for that pair before relying on it:

```bash
sed -n '/private static Predicate suiteScope/,/^    }/p' \
  backend/src/main/java/com/tcms/repository/CaseSpecifications.java
```

⚠️ **If it does not**, the cross-project search must pass a fourth state rather than reusing the
"unbounded" one — and the honest fix is an explicit `boolean boundByTree` on `CaseScope` rather than
a pair of values that happens to mean it. **This is the one place this plan could be wrong about a
shipped file; check it before writing the service, not after.**

Note the consequence either way, and state it in the header: the cross-project search **does not
apply the live-tree bound**, so a case filed in a soft-deleted suite *is* findable here where it is
not on that project's grid. That is defensible — a search is for finding a case you know exists, and
a case hidden by a folder's deletion is exactly the case somebody cannot find — but it is a
behavioural difference between two reads of the same table and it must be written down rather than
discovered. Recorded as open question **Q4**.

- [ ] **Step 4: `hit`.** `projectKey` and `projectName` come from the gated `Project` objects already
      in hand — **never a second query, and never a lookup that could return a project not in the
      gated set.** `byId.get(...)` returning null is unreachable by construction (the rows came from
      an `IN` over those ids); assert it rather than defaulting.

### Task 13: The controller

**Files:** Create `backend/src/main/java/com/tcms/repository/CrossProjectSearchController.java`.

- [ ] **Step 1:** `@RestController @RequestMapping("/api/v1/cases")`, one `@GetMapping("/search")`.
      Bind `CrossProjectSearchQuery` as a `@ModelAttribute`-style record — check how `CaseController`
      binds `CaseQuery`, and copy it:

```bash
sed -n '125,182p' backend/src/main/java/com/tcms/repository/CaseController.java
```

- [ ] **Step 2: the 400.** Raised in the controller, before the service, so no gate runs for a
      request that will be refused:

```java
        if (!request.isBounded()) {
            throw new BadRequestException(
                    "Name something to search for: q, ownerId or tag. "
                            + "There is no search that is every case in every project.");
        }
        if (request.queryTooShort()) {
            throw new BadRequestException("q must be at least 2 characters.");
        }
```

Use whatever this codebase's `ProblemEnvelope` path actually is:

```bash
grep -rn "BadRequest\|ResponseStatusException(HttpStatus.BAD_REQUEST" \
  backend/src/main/java/com/tcms/work/WorkItemController.java
```

`/work-items`' own two 400s are the precedent — copy the mechanism, not a new one.

- [ ] **Step 3: the caller's id.** However `CaseController` obtains the authenticated user id, do the
      same. **Never from a request parameter** — that is the one rule `CompanyContext` and this whole
      model rest on.

### Task 14: Go green, and gate

- [ ] **Step 1: the target test**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.repository.CaseSearchTest'
```

Expected: all arms pass. If `theTotalIsNarrowedToo` reports **7**, the narrowing is in Java. If it
reports **8**, `deletedAt IS NULL` was lost. If `thePageCannotHideTheCount` returns an empty page,
the narrowing is in Java *and* the previous arm was passing for the wrong reason.

- [ ] **Step 2: the full gate** — RLS, the project grid, the dashboard and `SchemaConventionTest` all
      exercise `CaseScope`, so a widening this small has a wide blast radius.

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected `BUILD SUCCESSFUL`. Watch three:

- `CaseListEndpointTest` — the project-scoped read through the widened scope.
- `DashboardEndpointTest` — `CaseQuery.unfiltered()` shares `CaseSpecifications`.
- `ArchitectureRulesTest` — `everyMappedColumnHasAReader` reads **source text**, and the new files
  add no column; but `everyBeanMethodHasACaller` will fail if `asCaptured` helpers are left unused.

- [ ] **Step 3: commit**

```bash
git add backend/src/main/java/com/tcms/repository/CrossProjectSearchController.java \
        backend/src/main/java/com/tcms/repository/CrossProjectSearchService.java \
        backend/src/main/java/com/tcms/repository/CrossProjectSearchQuery.java \
        backend/src/main/java/com/tcms/repository/CaseSearchHitView.java \
        backend/src/main/java/com/tcms/repository/CaseSearchPageView.java \
        backend/src/main/java/com/tcms/repository/CaseScope.java \
        backend/src/main/java/com/tcms/repository/CaseSpecifications.java \
        backend/src/main/java/com/tcms/repository/CaseService.java
git commit -m "feat: cases are findable across every project the caller can see

The grant narrowing is project_id IN (…) INSIDE the shared predicate, so the
total and the paging are computed over exactly the rows the caller may reach.
Filtering in Java would serve the right page beside a count of the client's
whole portfolio.

The gate is ProjectService.readAllVisible — /work-items' gate, not a second
implementation, and reached through `project` because `repository` may not ask
`identity` whether a caller may do anything.

CaseSpecifications' project bound stops being a documented survivor here and
becomes the whole boundary; its comment says so, and CaseScope now refuses to
be constructed empty.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Phase 4 — the query plan

**No commit of its own** unless something is found; this phase produces a measurement and a handoff
paragraph. Master plan Task 30.

### Task 15: Measure, and do not add DDL

- [ ] **Step 1: know what you are asking.** Two predicates matter and they behave differently:

| Predicate | Index available | Expectation |
|---|---|---|
| `company_id = ? AND project_id IN (…)` | `test_case_lifecycle_idx (company_id, project_id, lifecycle_status)` | **usable** — `company_id` leads and `project_id` is second, so an `IN` list becomes a bitmap over index scans. This is exactly what ERD §0.1 point 3 means by *leading, not appended* |
| `lower(title) LIKE '%…%'` | `test_case_title_trgm_idx (title gin_trgm_ops)` | ⚠️ **probably NOT usable.** The index is on `title`; the predicate is on `lower(title)`. A trigram GIN index does not serve an expression it was not built on. `CaseSpecifications.keyOrText`'s own header already concedes *"`description` has no such index and is scanned"* — the finding here may be that `title` is scanned too |

The project-scoped grid has lived with this because *"the row set is already bounded to one
project"*. **This endpoint removes that bound.** That is the whole reason this task exists and is not
a formality.

- [ ] **Step 2: take the plan the way `V67` took its.** As `tcms_app`, with `app.current_company`
      set — the RLS predicate is part of the query, and a plan taken as the superuser loses the
      One-Time Filter node entirely. `V67`'s header records falling into that trap deliberately, to
      show the difference. Follow `docs/plans/2026-08-15-org-chart.md` §6.1's method.

```bash
~/bin/tcms-remote shell
# on shanks, against a throwaway container with all migrations applied and a
# seeded instance in the shape V67 used: 1,000 companies, one company under
# measurement with ~10 projects and enough cases to be worth measuring.
```

```sql
SET ROLE tcms_app;
SET app.current_company = '…';
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM test_case
 WHERE company_id = '…'
   AND project_id IN ('…','…')
   AND deleted_at IS NULL
   AND (lower(title) LIKE '%refund%' OR lower(description) LIKE '%refund%')
 ORDER BY key_seq
 LIMIT 50;

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM test_case
 WHERE company_id = '…' AND project_id IN ('…','…') AND deleted_at IS NULL
   AND (lower(title) LIKE '%refund%' OR lower(description) LIKE '%refund%');
```

- [ ] **Step 3: record the numbers in the handoff**, in `V67`'s format — node, rows removed by
      filter, buffers, before and after any candidate index. **Rows Removed by Filter counting other
      companies' rows is the shape that is not allowed**; rows removed within the caller's own
      company is ordinary.

- [ ] **Step 4: take no Flyway version.** `V68` is pre-assigned to Area C
      (`docs/plans/2026-08-18-unplanned-gaps.md`: *"`V68` (area C, `run_schedule`) is the only
      migration in this plan"*), and `out-of-order` is unset. If an index is warranted, the candidate
      is:

```sql
-- CANDIDATE, NOT TAKEN IN THIS SLICE
CREATE INDEX test_case_title_lower_trgm_idx ON test_case USING GIN (lower(title) gin_trgm_ops);
```

Record it as **owed**, with the measurement behind it, exactly as §242 recorded its owed
`(company_id, project_id, occurred_at DESC)` index and took no number. **Do not add DDL silently**,
and do not renumber if Area C is skipped — leave the gap.

- [ ] **Step 5: if the measurement says the search is unshippable at scale**, stop and say so rather
      than shipping a screen that seq-scans a company on every keystroke. The honest alternatives, in
      order: debounce harder on the client and page smaller; drop `description` from the free-text
      predicate; or move to `search_vector @@ websearch_to_tsquery` and accept that prefixes stop
      matching (`CaseSpecifications.keyOrText`'s surviving argument, and `DocPageRepository#search`'s
      shipped precedent at `documentation/DocPageRepository.java:167`). **That last one is a product
      decision, not an optimization**, because it changes what the box finds.

---

## Phase 5 — the screen

**One commit.**

### Task 16: The route, and why it is top-level

**Files:** Modify `frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx`.

- [ ] **Step 1: read the precedent.**

```bash
sed -n '205,260p' frontend/src/app/router.tsx
sed -n '1,50p'   frontend/src/features/board/MyWorkPage.tsx
```

- [ ] **Step 2: `/search`, beside `/my-work` and never under `/projects/:projectId`.**
      `MyWorkPage`'s header makes the argument and it transfers exactly: the project segment is a
      real navigation boundary, every screen under it reads its project for breadcrumbs, the rail and
      permission scoping — **and this screen has no project to read.** Nesting it would put an
      arbitrary project in the URL of a list that spans all of them, and switching projects in the
      rail would appear to change a result set that cannot change.

- [ ] **Step 3: one nav entry** in `AppShell.tsx` beside the `/my-work` entry at `:114`. Not gated:
      every company member may open it, and a member with no grants gets an honest empty result — the
      same call `/my-work` already makes.

### Task 17: The query hook and its key

**Files:** Modify `frontend/src/api/queries/keys.ts`, `frontend/src/api/queries/cases.ts`.

- [ ] **Step 1: the key.** **Not** under `['projects', …]` — this list belongs to no project, and
      putting it there would mean an invalidation of one project's caches drops rows from a list
      spanning all of them, which is the exact hazard `keys.ts:260` already documents.

```ts
  /**
   * The cross-project case search — deliberately NOT under `['projects', …]`.
   *
   * This list belongs to no project, so an invalidation targeting one project's subtree must not
   * reach it; the comment at `project` in this file names that hazard from the other side. It
   * carries the whole filter object for `cases`' reason: a stale narrower result must not be shown
   * under a wider filter while the new request is in flight.
   */
  caseSearch: (filters: CaseSearchFilters) => ['case-search', filters] as const,
```

- [ ] **Step 2: `useCaseSearch`.** Follow `useCases` (`cases.ts:38`): `enabled` gated on the bounded
      predicate being present — **the hook must not fire a request the server will 400** (`ui-rules`
      §4's render-then-reject), `placeholderData: keepPreviousData` for paging, and this module's
      per-endpoint timeout budget.

### Task 18: `CaseSearchPage`

**Files:** Create `frontend/src/features/cases/CaseSearchPage.tsx` and its test.

- [ ] **Step 1: filter state in the URL** (`useSearchParams`), per the architecture rule. A search
      result must be linkable — this is the one screen whose whole output somebody pastes to a
      colleague.
- [ ] **Step 2: `DataRegion`** for loading / loaded / failed / empty / stale. **The empty state must
      distinguish two things**, and this is the row that matters on this screen: *"no case matches"*
      versus *"you can see no projects"*. They are the same HTTP response and they are different
      sentences, and the second must say what to do about it (ask an administrator for a project
      grant). Getting this wrong makes a permissions problem look like a product with no data in it.
- [ ] **Step 3: the project is part of every row**, not a column somebody can hide: `Identifier` for
      the project key and the case key, the project name beside it. The reader does not know which
      project a hit is from — that is the premise of the screen.
- [ ] **Step 4: `lib/labels.ts` for every enum.** No inline status text; the `satisfies Record<…>`
      maps already exist for all three enums this screen renders.
- [ ] **Step 5: server paging**, driven by `total`. Never a client-side slice — the whole point of
      Phase 2 is that `total` is a trustworthy number, and a client that re-derives it throws that
      away.
- [ ] **Step 6: the test.** `createTestQueryClient` only; a failed-read arm must call
      `drivingAFailedRead()`; contract conformance through `src/test/fixtures.ts` with a path-keyed
      arm anchored so `/cases/search` is not caught by a `/cases` prefix:

```ts
  /^\/cases\/search(\?|$)/
```

- [ ] **Step 7: gates**

```bash
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

`npm run build`, never `tsc --noEmit`. `lint` at **0 warnings** is a gate.

- [ ] **Step 8: commit** the frontend files only (`git add <paths>`, never `git add -A` — other lanes
      are committing in this tree).

---

## Phase 6 — the record

### Task 19: The handoff section

**Files:** Create `docs/handoff/NNN-<slug>.md`, add **one** index row to `docs/HANDOFF.md`.

- [ ] **Step 1: take the next free number.** `§243` is the head as of `cbc6437`; four lanes were
      running, so **check before claiming one**:

```bash
ls docs/handoff | sort -n | tail -5
grep -n "handoff/24" docs/HANDOFF.md | tail -5
```

- [ ] **Step 2: write it.** The five things that must be in it, because they are the things a later
      reader cannot re-derive:

1. **RLS scopes to the company, so the project narrowing on this endpoint is the application's
   alone.** State it as the first sentence.
2. **The count is the assertion.** A page-only test passes against a Java-side post-filter; name
   `theTotalIsNarrowedToo` and `thePageCannotHideTheCount` and say what each would report under the
   defect (7, and an empty page).
3. **`repository` asks `project`, not `identity`, for "may they"** — and the master plan said
   otherwise. Record the correction and the sentence in `repository/package-info.java` that decides
   it, because the next person will read the master plan too.
4. **`CaseSpecifications`' project bound is no longer a survivor mutation.** One line moved from
   "documented as untestable" to "the entire boundary". That is a fact about the mutation-check
   queue, not just about this file.
5. **The query plan numbers**, and the index recorded as **owed with no Flyway version taken**.

Plus: catalogue still **55**, head still **`V67`**, gates and the test count.

### Task 20: Both orientation caches

**Files:** `docs/agent-context/be-java.md`, `docs/agent-context/fe-react.md`.

> ⚠️ **One writer at a time.** The master plan's parallel section is explicit: *"Every area closes by
> editing `be-java.md` or `fe-react.md`, so **the close is a conflict even between two areas whose
> code is disjoint.** Stagger the closes."* If another lane is mid-close, wait.

- [ ] Update the `Last verified:` header and add a section, in the shape §243's takes: the rule to
      carry first, in capitals. The rule for this slice is:

> **WHEN A READ SPANS PROJECTS, THE GRANT NARROWING GOES IN THE QUERY AND THE TEST GOES ON THE
> TOTAL.** RLS is the company and only the company; two projects in one company are both visible to
> the same session. A narrowing applied in Java serves the correct page beside a count of the whole
> company's matching rows, and a page-only assertion cannot tell the two apart.

- [ ] Add the seam row: `readAllVisible` is now the cross-project gate for **two** modules (`work`
      and `repository`), which is what makes it a seam rather than a helper.
- [ ] Commit the cache edits **in this slice's own commit**, per CLAUDE.md.

### Task 21: The two deferred queues

Both are **written, none run** — §65, standing policy. Landing without these rows skips the check
outright.

- [ ] **Step 1: `docs/verification/frontend-browser-checks.md`.** Append a `§NNN amendment` block in
      the file's existing shape. Ids are stable and quoted from handoff sections — never renumber.
      **Name the fixture the lane must author**: a company with **three** projects, the signed-in
      person granted **one**, and cases matching one word in all three. No existing lane creates
      that, and without it the only row that matters cannot be driven.

| Row | Drive | Expect |
|---|---|---|
| `§NNN CS-1` | As a person granted **one of three** projects, search a word that matches cases in all three | Hits from the granted project only — **and read the result count.** It must be the number of granted matches, not the company's. This is the browser half of `theTotalIsNarrowedToo` and the one row on this screen that is a security check rather than a UI check |
| `§NNN CS-2` | Same search, page size 1, walk to the last page | No row repeats, none is missing, and the last page is not empty. The tie-break is the company-allocated case key |
| `§NNN CS-3` | As a member with **no project grants**, search anything | A sentence saying you can see no projects and who to ask — **not** "no results". The two are the same HTTP response and must not be the same screen |
| `§NNN CS-4` | Search `TC-<n>` for a case in a project you are **not** granted | Empty, with the ordinary "no results" copy. Not a 404, not an error, and no hint that the key exists |
| `§NNN CS-5` | Clear the search box | The list empties and **no request is made** — the server would 400 an unbounded search and the screen must not ask it to |
| `§NNN CS-6` | Copy the URL mid-search into a new tab | The same results. Filter state is in the URL; a search nobody can share is half a feature |
| `§NNN CS-7` | Open a hit, then go back | The result set and the page are still there |

- [ ] **Step 2: `docs/verification/backend-mutation-checks.md`.** Append rows in the file's shape:

| # | Mutate | Expect |
|---|---|---|
| `§NNN CS-M1` | In `CrossProjectSearchService`, drop the `CaseScope` narrowing and filter the page in Java with `.filter(row -> visibleIds.contains(row.getProjectId()))` | **Red** — `CaseSearchTest.theTotalIsNarrowedToo` (7 ≠ 2) and `.thePageCannotHideTheCount` (empty page). ⚠️ **Verify by doing it**: this is the defect the whole area is about, and the plan claims two arms catch it. If only one does, say which |
| `§NNN CS-M2` | In `CaseSpecifications`, change `root.get("projectId").in(scope.projectIds())` back to `equal(…, scope.projectIds().iterator().next())` | **Red** — every cross-project arm collapses to one project. Also confirms the project-scoped grid is unaffected, which is the regression this widening could have caused |
| `§NNN CS-M3` | Delete the project predicate from `CaseSpecifications` entirely | **Red on `CaseSearchTest`, and — check — GREEN on `CaseListEndpointTest`.** ⚠️ **This row is the point.** That predicate has been a documented survivor mutation on the project-scoped read since it was written; this slice is what makes it killable. Record which suites reddened, because the answer is the evidence that the comment above the line is now true |
| `§NNN CS-M4` | In `CrossProjectSearchService`, return `visible` instead of the intersection when `request.projectId()` is non-empty | **Red** — `theProjectFilterNarrowsAndNeverWidens`. The parameter would become a no-op rather than a leak, but a filter silently ignored returns everything under a heading claiming it is narrowed |
| `§NNN CS-M5` | Remove the `gated.isEmpty()` short-circuit | **Red** — `theGrantlessMemberSeesNothingAndIsNotRefused`, with a SQL syntax error rather than an assertion failure (`IN ()`). Confirm it is the exception and not a silent full read: **if it is a full read, the `CaseScope` guard was removed too and that is a separate finding** |
| `§NNN CS-M6` | Remove the `IllegalArgumentException` from `CaseScope`'s compact constructor | **Expected GREEN**, and recorded as such. It is a guard against a future edit, not against a current defect — CS-M5 is what proves the state is unreachable today |
| `§NNN CS-M7` | In `CrossProjectSearchService`, resolve `projectName` with a fresh `projectRepository.findById` instead of the gated map | **Expected GREEN** ⚠️ — a gap. Nothing asserts the name comes from the *gated* set, and the mutation is the shape that would later serve an ungranted project's name if the gate and the lookup ever diverged. One arm closes it: assert the hit's `projectName` for a row whose project is gated, after seeding a same-company project with a colliding name |
| `§NNN CS-M8` | Change `isBounded()` to `return true` | **Red** — `anUnboundedSearchIsRefused`. The endpoint becomes "every case in every project you can see", which is the response `/work-items` refuses to serve for the same reason |
| `§NNN CS-M9` | Remove the trailing `cb.asc(root.get("keySeq"))` from `CaseSort.orderBy` | **Red** — `pagingIsStableAcrossProjects`, **if the fixture is large enough to page**. ⚠️ Postgres may return a stable order anyway on a small heap; if this comes back green, the fixture is too small and the row is the finding |

### Task 22: Close

- [ ] **Step 1: every gate, on shanks.** Not locally — standing directive.

```bash
~/bin/tcms-remote backend  ./gradlew build
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run lint --workspaces
~/bin/tcms-remote packages npm run test:run --workspaces
```

`packages` is gated even though no source there changed: `citedFiles.test.ts` reads all four corpora,
so a path named in a new backend comment that does not exist on disk reddens the **packages** job.

- [ ] **Step 2: commit the record**, then **push**.

```bash
git add docs/handoff/NNN-<slug>.md docs/HANDOFF.md \
        docs/agent-context/be-java.md docs/agent-context/fe-react.md \
        docs/verification/frontend-browser-checks.md \
        docs/verification/backend-mutation-checks.md
git commit -m "docs: cross-project search — handoff, seams and both verification queues"
git push
```

- [ ] **Step 3: describe it honestly.** Both queues are unrun, so this slice is **built and
      unverified**, and must be described that way rather than as done.

---

## Open questions for the implementer

| # | Question | Where it bites | Default if unanswered |
|---|---|---|---|
| **Q1** | Should `featureId` / `testTypeId` / `testLevelId` be searchable cross-project? `classification_value` is company-scoped (`V1:382-392`) so it is *viable*; what it needs is subtree expansion outside `CaseService`'s per-project walk | contract — adding it later is additive and cheap | **Out of v1.** Recorded, not forgotten |
| **Q2** | Should a hit carry `owner`? It is the third thing people scan for after key and title | `CaseSearchHitView` + a `CaseOwners` call across N projects | **No.** Adding it means the §6u membership resolution over projects the caller may see — real work, and a second per-page query |
| **Q3** | Should `@tcms/mcp` gain a `search_cases` tool? It is the single most obviously useful read an agent could make of this product | `packages/mcp` — and there is no read-side conformance harness the way `writeConformance.test.ts` exists for writes | **Out of this area.** Its own slice |
| **Q4** | The cross-project search does **not** apply the live-tree bound, so a case filed in a soft-deleted suite is findable here and not on that project's grid. Correct, or a divergence to close? | `CrossProjectSearchService`'s `CaseScope` construction — see Task 12 Step 3 | **Ship it and write it down.** A search is for finding a case you know exists; the case hidden behind a deleted folder is precisely the one somebody cannot find |
| **Q5** | If Task 15 finds `lower(title) LIKE '%…%'` seq-scanning a whole company, is the answer an expression index (`V69`, owed) or moving to `search_vector` (which stops matching prefixes)? | a product decision, per `CaseSpecifications.keyOrText`'s surviving argument | **The index.** Prefix matching is what makes a type-ahead a type-ahead |
| **Q6** | Should `/cases/search` be reachable by an **API token** as well as a session? Every gate here is `project:read`, which tokens can hold | nothing in the plan forbids it; nothing asserts it either | **Whatever the shared security config already does** — do not add a special case, and add an arm asserting whichever it is |

---

## Self-review

**What this plan is confident about**

- The narrowing helper. `ProjectService.readAllVisible:225` is the shipped cross-project gate, it is
  already called twice from `work`, and it and `AdminReach.visibleProjects` funnel into one
  `PermissionEvaluator`. There is no second implementation and this plan writes none.
- The test shape. The count assertion and the small-page assertion are two witnesses to one defect
  and they fail with *different symptoms* — a wrong number and an empty page — which is what makes
  the diagnosis unambiguous when one of them goes red in six months.
- The envelope. `PageMeta` + `items` is what two shipped list endpoints already serve, and
  `/work-items`' bare array is explicitly argued from a bound this endpoint does not have.
- No migration, no permission key, no module edge, no `packages/` change. The blast radius is three
  modified backend files and their tests.

**What this plan is NOT confident about, stated rather than hidden**

1. **`CaseScope`'s "no suite bound" state may not exist** (Task 12 Step 3). The plan passes
   `liveSuiteIds = List.of(), includeUnfiled = true` and asserts, from `CaseScope`'s javadoc, that
   this means *no predicate*. `CaseSpecifications.suiteScope` was not read line-by-line while writing
   this. **Check it before writing the service.** If it means "matches nothing", the fix is an
   explicit fourth field, not a clever pair of values.
2. **The query plan may be bad enough to change the design.** `lower(title) LIKE '%…%'` over a
   company rather than a project is the one thing here that could turn out unshippable, and Phase 4
   is placed before the screen for exactly that reason. This plan does not assume the measurement
   comes out well.
3. **`CS-M7` is a known gap on purpose.** Nothing asserts that `projectName` came from the *gated*
   set rather than from a fresh lookup. It is green today by construction and the row says how to
   close it.
4. **The Company Admin arm is the weakest of the strong ones.** `aCompanyAdminSeesEveryProject`
   asserts `total: 7` — the same number the defect produces for `SEEKER`. It is testing that the
   narrowing *widens correctly* for someone who holds everything, which is a different property, and
   it is deliberately placed after the two arms that would already be red.
5. **This plan could not regenerate the schema copies** — another lane held `openapi.yaml` when it
   was written. Task 6 must be executed and `diff`ed for real; the fragment in Task 5 has never been
   parsed by a generator.

**The one sentence to carry out of here**

> The database will not stop this endpoint from serving another project's rows, because those rows
> are in the same company. The predicate in the query is the only thing that will, and the number
> that proves it is the total.
