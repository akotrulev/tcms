# §22. Backend M1, slice 1 — the project list and detail, and the second gate over HTTP (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`), **Mode B — extend**. Closes
§20's next-step item 1 *for its first slice*: **live mode now reaches past the login screen.**

### What this is, and what M1 still is

M1's contract is 30+ endpoints. This slice is **`GET /projects` + `GET /projects/{projectId}`** — the
two the SPA calls immediately after sign-in, and therefore the whole difference between "live mode
stops at login" and "live mode renders the product". Everything else in M1 (classification, statuses,
suites, cases, plans, runs, docs, artifacts, dashboard, reports) is untouched.

### The headline: both access gates are now observable over HTTP

`PermissionEvaluatorTest` has proved the second gate as a *permission* question since M0. This is the
first time it is proved as an *answer to a request* — a different claim, and the gap between them is
where the defect would have lived: the evaluator can be perfectly correct while the endpoint above it
lists every project in the company because nobody wired the filter in. **That failure would have
passed all 180 existing tests.**

| Path | Purpose |
|---|---|
| `project/ProjectController.java` | The two endpoints. Caller from the token, company from the context, neither ever a parameter. |
| `project/ProjectService.java` | The grant gate applied to every row the company filter admits; assembles the view. |
| `project/ProjectView.java` | The contract's `Project` shape. A DTO **so `companyId` cannot leave the building** — echoed back, it invites the next author to accept it on the way in. |
| `project/ProjectAccess.java` | **The port** (below). |
| `identity/IdentityProjectAccess.java` | The adapter. Delegates every authorization decision to `PermissionEvaluator`. |
| `project/ProjectRepository` · `identity/RoleAssignmentRepository` | Two grouped projections — case counts, project-scope role names. |
| `project/ProjectEndpointTest.java` | **New, 24 assertions.** |

### The decision that shaped the slice: a port, not a wider dependency

Listing projects needs two things `project` cannot compute — does the caller hold a grant, and what is
their role called. Both live in `identity`. The obvious move is to import `PermissionEvaluator`, and
**Spring Modulith failed the build on it, correctly**: `identity` already depends on `project`,
because the evaluator resolves a project through `ProjectRepository` to check it belongs to the active
company. Both modules' `package-info` files already said the edge is one-way.

So the dependency is **inverted rather than reversed**: `project` declares `ProjectAccess`, and
`identity` — which may already see `project` — implements it. Nothing was added to
`project`'s `allowedDependencies`. Both `package-info` files now say so explicitly, including the
instruction **not** to "fix" a future Modulith failure here by widening the declaration.

> **This is the good case.** The architecture test refused a change that looked obviously right, and
> the refusal produced a better design than the one being attempted. A module rule that never blocks
> anything is not being enforced.

### Two contract fields that needed a decision, and where the decision came from

| Field | Resolution |
|---|---|
| **`myRole`** | The contract already settled it: *"the caller's role on this project, or null when they hold no project grant."* So a **Company Admin gets `null`** — they reach every project through a grant carrying no project id, and labelling each project "Company Admin" would state a fact about the *company*. Where a caller holds two project-scope grants (directly and via a team), **"strongest" is derived from the role's permission count**, read from the same `role_permission` table authorization reads — never a hard-coded ranking of names, which is §6 lesson 10 in a new costume. |
| **`caseCount`** | Live cases, **excluding soft-deleted** (`test_case.deleted_at`), or the number contradicts the case list on the next screen. Read with a native `GROUP BY` **from the `project` module**, because a Java edge `project → repository` is forbidden and SQL is where the coupling can live — recorded as the deliberate trade it is, with §3 lesson 4's event-updated read model named as the long-run answer *once anything writes a case*. Nothing does yet, so a live count is both correct and cheaper than a counter that can drift. |

### Why visibility is N evaluations rather than one clever join

A single native query joining `role_assignment`, `team_membership` and `role_permission` would return
visible project ids in one round trip. It is the wrong shape: a **second authorization-resolution path
beside `PermissionEvaluator`** — the bug class §6b names, and the same reason §18 kept grant claims
out of the token. The cost is accepted with its ceiling stated: a company holds *a handful of whole
products* (§1), and the two things that would otherwise be N+1 *per project* are each one grouped
query, so what remains is N evaluations, not 3N queries. The evaluator's Caffeine cache improves this
for free when it lands, with no call site changed.

### Mutation-verified — five mutations, and the two survivors were the interesting part

| Mutation | Result |
|---|---|
| Grant gate removed from `listVisible` | **Killed**, 3 tests |
| `deleted_at IS NULL` removed from the count | **Killed**, 3 tests |
| `company_id = :companyId` removed from the count | **Survived, benignly.** RLS independently scopes `test_case`. The same documented survivor `RoleAssignmentRepository` already carries — defence in depth working, not dead code, and load-bearing the moment a deployment takes V1 up on its offer to disable RLS. |
| `project_id IS NOT NULL` removed from `findProjectRoleNames` | **Survived for a bad reason → gap closed.** The company-scope row comes back with a null project id, lands in the result map under a **null key** no project id matches, and vanishes. `HashMap` tolerating a null key was doing the predicate's job, so the test could not tell *excluded by the query* from *silently dropped by the consumer*. Assertion moved to where the predicate lives; the mutation now dies. |
| Strongest-wins comparator inverted | **Killed** — by a case that had **no coverage at all** until this run. |

The strongest-wins fixture needed a **second team**: adding a Test Lead grant to the existing QA team
would also have reached `TEAM_MEMBER` and quietly broken "a team grant reveals only the team's
project". Two fixtures sharing a team is how one test's setup becomes another test's failure.

### Gate evidence — run, not just built

`./gradlew build` clean, **204 tests** (180 + 24). `npm run build` + `npm run lint` clean, 0 warnings,
**entry chunk unchanged at 124 kB gzip**.

Then **driven in a real browser in live mode** against `docker compose up` + a provisioned `westwind`,
with two projects and 6 cases (2 soft-deleted) seeded directly so the list had something real to say:

| Assertion | Result |
|---|---|
| `GET /projects` ordering | by name — Mobile App before Online Banking |
| `caseCount` vs psql ground truth | **4** for OLB (6 rows, 2 soft-deleted) and **0** for MOB |
| `companyId` in any response | absent |
| Unknown project id | **404, 0 bytes** |
| The SPA after sign-in | **the project picker rendered both projects from Postgres**, with the membership display name in the rail |
| Reload | session survived — `SessionGate` + restore |
| A project-scope grant added live | `Your role: Test Lead` appears on OLB and **not** on MOB, same user, same page — the company-scope grant correctly contributes no label |

### One defect the browser found and the gates could not — §6 lesson 5, again

**`Your role:` rendered as a dangling label with nothing after it.** `myRole` is correctly `null` for a
Company Admin, and `ProjectsPage`/`DashboardPage` printed the caption unconditionally. **Invisible in
mock mode**, where every fixture project carries a role — and visible on the very first real Company
Admin session, which is exactly the shape of defect live mode exists to surface.

Fixed by **omitting the line, not filling it**: the API deliberately does not say what their company
role is at this endpoint, and inventing "Company Admin" would be the UI making a claim the response
never made. Both branches then verified live on one page.

*Said out loud per `CLAUDE.md`: the two-line null guard did not re-enter the `fe-react` phase pipeline.
It introduces no pattern a skill owns — it is a conditional render in existing markup.*

### Deliberately not done

- **The rest of M1.** `/projects/{id}/classification` and `/statuses` are the natural next slice: both
  vocabularies are already seeded per-company by `provision-company`, so they are mostly projection.
- **No pagination on `/projects`.** The contract declares a bare array and a company holds a handful of
  products. It becomes wrong if that assumption does, and the contract would have to change first.
- **No project create/rename/archive.** `project:create` exists in the catalogue and the entity already
  has `rename`/`describe`/`archive`; no endpoint calls them. Reads first, so the visibility model is
  settled before anything writes through it.
- **`ProjectView` is assembled per request, uncached.** Same reasoning as the evaluator's own deferred
  cache: a wrongly-keyed cache here is a cross-company leak.
- **No `/me/permissions` consumer on the frontend** (still §20). `lib/permissions.ts` reads the fixture
  role catalogue, which stays honest only in mock mode.

### Next

1. **M1 slice 2** — `/projects/{id}/classification` + `/statuses`, then the suite tree. Each new
   endpoint now inherits the gate: read through `ProjectService`-shaped visibility, or re-answer "may
   they?" and diverge from the evaluator.
2. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
3. The evaluator's cache, the `pv` cache, the `OWN` qualifier, an RLS-disabled lane for §13's
   surviving mutation.
