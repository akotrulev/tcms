# §41. The second parallel run — three lanes again, and the one rule that had to change (2026-07-31)

**Not a feature slice.** §38, §39 and §40 are the product. This records what running them at the
same time cost, and it is the second data point against §37's predictions rather than the first.

| Lane | Slice | Location | Contract | Flyway |
|---|---|---|---|---|
| A | §38 the `execution` module | main checkout, `master` | **holder** | `V14` pre-assigned, **unused** |
| B | §39 role grants (`identity`) | worktree `../tcms-lane-b` | forbidden | `V15` pre-assigned → **renumbered `V13`** |
| C | §40 frontend Phase 5 review | worktree `../tcms-lane-c` | forbidden | none |

## The contract holder rule broke before the run started, and the fix is worth keeping

§37's reusable rule was: *parallelism was safe because exactly one lane could change the contract,
and it made only additive changes.* That rule assumes the lanes' contract needs are known to be
compatible. Here they were not — **lane B's entire slice needed two endpoints the contract did not
have** (`POST` / `DELETE /role-assignments`), while lane A was the natural holder because
`execution` is six paths and the biggest surface in the run.

Three options, and the one taken is the third:

1. Make B the holder and let A report divergences. Rejected: A had six paths to A's one pair, so it
   would have put the larger surface behind the smaller lane's queue.
2. Let both edit. Rejected — `schema.d.ts` is generated and two regenerations do not merge.
3. **Land B's endpoints serially, before the run, as their own commit** (`a8e4b50`), then start all
   three lanes against the frozen result.

Option 3 looks like the contract batching §33 §3 explicitly **declines** for vertical slices, and
the distinction is worth stating because the next run will face it again. §3 declines batching *a
lane group's whole surface* ahead of implementation, on the evidence that §25, §28 and §30 each
found real divergences by reading the contract *while* implementing. What happened here is
narrower: **one pair of endpoints, designed in full, for a lane that could not otherwise start.**
B then found a divergence in it anyway (below) — which is §3's point landing, not being refuted.

The rule that replaces §37's: **before assigning the holder, ask which lanes need a contract change
at all.** If more than one does, the extra ones are either serialised up front or moved out of the
run. Lane count is still not the property to check.

## The seam held again, and now for a module that did not exist

Backend file overlap between the three lanes: **zero**. `execution` was a new package, `identity`
an existing one, `repository` untouched by both. Test counts were exactly additive again — 630
baseline → 673 (A's 43) → 731 (B's 58) → 733 with integration's 2.

The finding worth keeping is §38's, not this one: `execution` may not import `repository`, and an
execution is meaningless without a case. The resolution was that `V1` already gives
`test_execution` two FKs into `repository` and the ERD draws them, so **a shared row is not a
shared class** — the whole crossing is one class issuing native SQL, reading the *pinned snapshot*
rather than the live case. The narrower coupling and the correct semantics turned out to be the
same choice. A lane that had been allowed to import across the boundary would have taken the wrong
one and it would have typechecked.

## Frontend overlap: three lanes, three files, all auto-merged

`api/queries/index.ts` (barrel), `api/types.ts`, `mocks/db/store.ts` — one appended line each. That
is rework 1 from §33 paying off for the second consecutive run, and it is now the boring part.

**§37's semantic-conflict warning was heeded and cost nothing.** All three lanes were told that
`useCan.ts` and `PermissionGate.tsx` are owned by whoever is *asserting* on them, not just editing
them. None of the three modified either; A and B both reported that their new call sites needed no
widening, and C found a `PermissionGate`-shaped defect in `BoardPage` and wrote it up without
touching the component. The near-miss did not recur.

## Integration had to fix three things the lanes correctly refused to

1. **A person-lookup whose null behaviour depended on the data.** `ExecutionPeople.resolve` returned
   `Map.of()` when there was nobody to resolve, and `Map.of().get(null)` throws by contract while
   the `HashMap` the non-empty branch builds answers null — so an unassigned, unexecuted execution
   was a 500 while the same code path worked on any run with one named executor. Lane A found it in
   its own first test run, then reported that `repository.CaseOwners` **has the identical shape** and
   declined to cross the module boundary to fix it. Both now answer a null-tolerant map, with a test
   per package pinning it; they cannot be one test, because both classes are package-private and
   `execution` may not import `repository`. `CaseVersionService`'s comment cited the old trap as the
   reason for its null check and has been corrected — a comment that is true only until someone
   fixes the thing it describes is a comment that will lie.
2. **`RoleAssignment.user` was `required` and non-nullable, and cannot be.** A grant made to a team
   names no user. Lane B shipped against a cast and wrote down that it dies when the contract is
   corrected — exactly what lane B did last run with `EntityVersion.createdBy`. Corrected with the
   same `allOf` + `nullable: true` idiom; the cast is gone.
3. **Lane B's `V15` renumbered to `V13`** in landing order, per §33 §4. `V14` was pre-assigned to
   lane A and went unused, so it is free.

## The pattern that is no longer an oversight

Counting §37's finding 2: a path gets its success response written and its **refusals forgotten**.

- §29 — `POST …/cases` declared no 400/403.
- §37 — the versions path declared only a `200`.
- §38 — all six execution paths declared only their success; A added 404 to every one and 400/403/409
  to the `PATCH`.

Three runs, three times. The same is true of the nullable `$ref`: `EntityVersion.createdBy` (§37),
`TestExecutionSummary.assignee`/`.executedBy` (§38), `RoleAssignment.user` (§41). **Both are habits
of contract authoring, not accidents**, and both are cheap to check mechanically — a schema-wide
invariant wants a computed check rather than a careful reading (§8). Two candidate checks, neither
built: every path declares at least one 4xx, and every `$ref` to a nullable column is an `allOf`.
That is the next contract-hygiene slice and it is small.

## Gate evidence — the merged tree, not the lanes

Three separately-green trees are not evidence about the tree that results from combining them.
Every number below is from `master` after all three merges **and** the integration fixes.

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 1m 28s
           733 tests, 0 failures, 0 skipped   (630 + 43 + 58 + 2)
frontend:  npm run build   → ✓ built, entry chunk unchanged
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ Test Files 12 passed (12) · Tests 230 passed (230)   (203 + 22 + 5)
           find "* 2.*"    → nothing outside node_modules
```

## Was it worth it — the second answer

§37's honest read was that it worked because the lanes were *unusually* well separated. This run
was separated on purpose rather than by luck: one new module, one existing module, one frontend-only
lane with an explicit file-ownership list including files it could only *report* on. It worked
again, and the one thing that did not fit the model — two lanes needing the contract — was visible
**before** launch and cost one small serial commit.

The cost side is unchanged and should not be forgotten: two worktrees, two `npm install`s, two extra
Gradle daemons, and an integration pass that made a contract change and re-ran every gate. Docker
was held to two backend lanes deliberately (§33 §6: two is comfortable, four is not), which is why
lane C was frontend-only rather than the `documentation` module.

The `documentation` module was in fact the first candidate for lane C and was **rejected before
launch**, for a reason worth reusing: per-block coverage needs the latest execution results, which
is precisely the seam lane A was inventing that hour. A lane whose premise is another lane's
unfinished boundary is the one shape that cannot be made safe by file ownership.

## Not done

- **No live browser pass on any lane, again.** §38 argues its pass cannot usefully happen until
  `POST /runs` exists, because creating a run is what materialises the executions to record against.
  §39's grant dialog and §40's four fixes were driven in mock mode only.
- **`GET …/plans/{planId}/statistics` stays on MSW** — `bySuite` needs the live suite tree,
  `coveragePercent` a repository-wide denominator, and `trend` a `reporting` module that does not
  exist. The contract now says so in its own description.
- **`company:read` is unreachable at company scope for five of the six roles** — see §39. The roles
  spec §A3 grants it to every member and `V3` seeds it saying so, but four of those five roles are
  PROJECT-scope and the evaluator answers company-wide questions from SYSTEM + COMPANY grants only.
  **This is the most important open item in this run**, because it is a spec-vs-code disagreement
  rather than a bug: two repairs exist and choosing between them is a decision, not a fix.
- **Three frontend findings deferred by lane C** into files other lanes held: `refetchOnWindowFocus:
  false` in `app/queryClient.ts` (the one decision in that file with no written reason),
  `placeholderData: keepPreviousData` missing on `useCases`, and `useUnsavedWork` on the execution
  screen. C also checked §5's claim that the *board* needs `useUnsavedWork` and found it does not —
  its writes are immediate single-field mutations with no unsaved buffer.
- **`V14` is free**, having been pre-assigned and unused.
