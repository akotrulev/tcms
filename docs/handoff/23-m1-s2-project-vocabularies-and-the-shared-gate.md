# §23. Backend M1, slice 2 — the two project vocabularies, and the gate that is now shared (2026-07-31)

Run through the **`be-java`** skill (agent definition + `database-code`, then `code-review`), **Mode B
— extend**. Closes §22's next-step item 1: **`GET /projects/{id}/classification` and
`/projects/{id}/statuses`**.

### The headline: the second gate stopped being something each endpoint remembers

§22 wired both access gates into `GET /projects/{id}`. This slice's real deliverable is that the
gate became **one private method** — `ProjectService.visibleProject` behind the public
`readVisible(userId, projectId, read)` — that every project-scoped read is built on. A new endpoint
gets both gates by construction rather than by discipline.

The property is measured, not asserted: deleting the grant check from that one method now fails
**five tests across two endpoint classes**, not just the class whose author removed it. That is the
whole argument for a higher-order method over a copied two-line filter.

| Path | Purpose |
|---|---|
| `project/ClassificationDimension · ClassificationValue · CaseStatus` | The three entities. First `ltree` mapping in the codebase (below). |
| `project/…Repository` ×3 | One purposeful query each. All three `@Transactional(readOnly = true)` per §15. |
| `project/ClassificationDimensionView · ClassificationValueView · CaseStatusView` | The contract's shapes. `companyId` **and** `projectId` withheld. |
| `project/Cardinality · StatusKind` | The two closed enums, `@Enumerated(STRING)`. |
| `project/ProjectConfigurationService` | Borrows the gate; assembles dimensions + values in two queries. |
| `project/ProjectService` | `readVisible` extracted; `findVisible` now reads through it. |
| `project/ProjectController` | The two endpoints, on the existing controller — one contract tag, one gate. |
| `project/ProjectConfigurationEndpointTest` | **New, 24 tests.** |

No module edge was added: classification and statuses are ERD §3, i.e. the `project` module
already. No migration, no `openapi.yaml` change — the contract was already right and was implemented
as written.

### The defect this slice was most likely to ship, and why it would have looked fine

**`classification_dimension.project_id` is nullable, and NULL means company-level.** Test Type, Test
Level and Execution Phase are seeded that way; only Feature is per-project. A query filtering
`project_id = :projectId` therefore returns **one** dimension — the project's empty Feature tree —
answers 200, and leaves the case editor with a single empty picker. Nothing fails.

So the test asserts the four keys **by name**, not by count, and
`ClassificationDimensionRepository.findForProject` carries the reasoning inline. Mutating the
predicate to the equality form kills 5 tests.

There is a sharper corollary worth keeping: `project_id IS NULL` matches rows in **every** company,
so for exactly the rows the SPA needs most the project predicate scopes *nothing* — `company_id` is
the only thing between two clients' vocabularies. Both companies in the fixture are seeded by the
same V2 function, so every key and every value name collides and only ids can prove the scoping.

### `ltree` is the second extension type that would have failed at boot

`classification_value.path` is `ltree`, which reports as `Types#OTHER` while a `String` field maps
to `varchar` — the exact trap `AppUser.email` documents for `citext`. `columnDefinition = "ltree"`
is what satisfies Hibernate's fall back to comparing type *names*.

**Verified by removing it:** the application context fails to build at all
(`SchemaManagementException` from `AbstractSchemaValidator`), taking all 24 tests with it. Good
failure — at boot, not at the first query. `suite.path` is the next `ltree` column and will need the
same annotation.

### Mutation-verified — seven mutations, no survivors

| Mutation | Result |
|---|---|
| Dimension predicate reduced to `project_id = :projectId` | **Killed**, 5 tests |
| `deletedAt IS NULL` removed from values | **Killed** — a retired value came back as pickable |
| Values ordered by name instead of position | **Killed** — Accessibility first reads plausible |
| Grant gate removed from the shared `visibleProject` | **Killed, 5 tests across BOTH endpoint classes** |
| Statuses read company-wide instead of per project | **Killed**, 2 tests |
| Empty dimension given `null` instead of `[]` | **Killed** — `values` is `required`, so the SPA cannot null-check it |
| `columnDefinition = "ltree"` removed | **Killed** — 24/24, at context startup |

Unlike §22 there is no documented survivor. The seven were chosen to be the plausible wrong versions
of this slice, not arbitrary edits.

### One finding from the `code-review` phase, fixed rather than argued with

`findVisible` initially called the **public `@Transactional` `readVisible` on `this`** — a
self-invocation, which the skill's checklist rates a blocker. It was harmless here (the outer method
had already opened the transaction), but "harmless self-invocation" is a claim a reader has to verify
every time. Restructured so the gate is a **private unannotated method** both public entry points
call: no self-invocation, and still exactly one implementation of the gate. The M4 mutation was
re-run afterwards and still kills the same five tests, so the refactor kept the property it was
worth having.

### Gate evidence — run, not just built

`./gradlew build` clean with `-Werror`, **228 tests** (204 + 24). Frontend untouched, so no frontend
gate: `openapi.yaml` did not change and `schema.d.ts` already carried both shapes.

Then **driven live** against `docker compose up` + a provisioned `slice2` company, one project seeded
by `tcms_seed_project_defaults`, with a hand-built two-level Feature tree and a retired node:

| Assertion | Result |
|---|---|
| `/classification` dimension count vs psql | **4** — `test_type`, `test_level`, `execution_phase`, `feature`, in seed order |
| Locked / hierarchical / cardinality | company-level three locked + SINGLE/SINGLE/MULTI; `feature` unlocked, hierarchical, MULTI |
| The Feature tree | `Refunds` carries `parentId` = Payments and `path` = `Payments.Refunds` |
| Retired value | **absent** — 2 live of 3 rows, matching psql |
| `/statuses` vs psql | **6**, position order, colours matching the frontend design tokens |
| `Retest` | `isFinal=false`, `countsAsPass=false` — the two booleans are independent |
| `companyId` / `projectId` in any response | absent at every level |
| Unknown project id, both endpoints | **404, 0 bytes** |
| Anonymous, both endpoints | **401** |
| **The caller's only grant deleted, then re-requested** | `/projects` → `[]`, both endpoints → **404, 0 bytes**, for a project that genuinely exists in their own company with populated vocabularies. Persona 3, live. |
| Grant restored (re-running `provision-company`) | both endpoints → **200** |

No SPA screen was driven, and that is not an omission: the only consumers are `CaseListPage` and
`ExecutionPage`, which also need `/suites` and `/cases`. Verification is therefore at the HTTP layer
against psql ground truth — stated plainly rather than dressed up as a browser pass.

### Two things found and deliberately not fixed

1. **`case_status.color` is nullable in `V1` and `required` in `openapi.yaml`.** The seed sets all
   six and nothing else writes a status, so the invariant holds *by luck*. Pinned by a test
   (`everyStatusHasAColour`) and documented on `CaseStatusView.color` rather than papered over in the
   view — inventing a fallback colour would be the UI making a claim the data never made. The real
   close is `ALTER COLUMN color SET NOT NULL` plus a colour validator, and both belong with the
   status-editing endpoint that first makes a null reachable.
2. **`provision-company` prints `existed company membership (invited)` for a membership that is
   ACTIVE.** The label is a static description of the step
   (`ProvisionCompanyCommand.java:190`), so on a re-run it states a status that is no longer true.
   Cosmetic, pre-existing, operator-facing only — noted rather than scope-crept into this slice.

### Deliberately not done

- **No writes.** No vocabulary editor, no status editor; the entities carry no factory methods,
  because a factory with no caller is where the `company_id` rule gets forgotten. Add one with its
  endpoint.
- **No `controlled` on the wire.** Mapped, unrendered, no consumer.
- **Sibling ordering inside a Feature tree is unowned.** `position` is per dimension, and nothing
  allocates positions for tree nodes yet — the writer's problem when a writer exists.
- **No caching.** Same reasoning as the evaluator's own deferred cache: a wrongly-keyed cache here
  is a cross-company leak. Vocabularies are the most cacheable thing in the product and will be the
  most tempting; key on `(companyId, projectId)` or not at all.

### Next

1. **M1 slice 3 — `/projects/{id}/suites`**, then `/cases`. Both go through
   `ProjectService.readVisible`; the suite tree brings the second `ltree` column and the first
   endpoint whose response the SPA renders as a *screen* rather than as reference data, so live mode
   reaches the case list.
2. Frontend **Phase 4 (Vitest + RTL)** — unchanged, still unblocked.
3. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation,
   and the `case_status.color` migration alongside the status editor.
