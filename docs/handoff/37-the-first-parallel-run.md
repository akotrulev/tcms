# §37. The first parallel run — three lanes, what merged clean, and what integration had to fix (2026-07-31)

**Not a feature slice.** §34, §35 and §36 are the product. This section records what running them
*at the same time* actually cost, because §33 predicted specific things about it and this is the
first evidence either way.

The run: three lanes, launched together, against the checklist in
[`docs/plans/2026-07-31-parallel-execution-rework.md`](../plans/2026-07-31-parallel-execution-rework.md) §8.

| Lane | Slice | Location | Contract | Flyway |
|---|---|---|---|---|
| A | §34 project creation (`project` + `identity`) | main checkout, `master` | **holder** | `V13` pre-assigned |
| B | §35 case versions (`repository`) | worktree `../tcms-lane-b` | forbidden | none |
| C | §36 Vitest + RTL (frontend only) | worktree `../tcms-lane-c` | forbidden | none |

## The seam rule worked, and it is the finding worth keeping

§33 §5 says to pick lanes by **Modulith module, not by "Next"-list order**, on the argument that
ArchUnit already machine-checks that boundary so the seam is real rather than hoped for.

**Backend file overlap between lanes A and B: zero.** Not "few" — none. `project`/`identity` and
`repository` are disjoint sets of files, and the two lanes each ran `./gradlew build` to green
without ever seeing each other's code. Test counts were exactly additive: 579 baseline → 607 (A's
28) → 630 (B's 23), with no test in either suite perturbed by the other.

The frontend overlapped on two files and **both auto-merged**: `api/queries/index.ts` (a barrel, so
two lanes append two export lines) and `mocks/db/store.ts`. That is rework 1 from §33 paying off
directly — those are the two files that were single 700–1300-line write-conflict monoliths a session
ago.

## What did NOT happen, and was expected to

§33 §6 predicted parallel lanes would "re-derive premises from a one-slice-stale handoff and some
work will be redone." **No lane redid another lane's work.** Lane A held the contract and made two
purely additive changes, so the regenerated `schema.d.ts` never shifted under B or C.

The near-miss worth recording: **lane A modified `useCan.ts` and `PermissionGate.tsx`** (adding the
company-scope call path) while **lane C was writing 22 tests against those exact two files**. That
is a semantic conflict no merge tool can see — textually disjoint edits, one lane changing behaviour
the other is asserting on. It survived only because A widened the surface rather than changing it.
Had A altered `useCan`'s existing answer, C's suite would have gone red at integration with no
conflict marker anywhere. **Lane assignment should treat "the file another lane is asserting on" as
ownership, not just "the file another lane is editing."**

## Integration had to fix three things the lanes correctly refused to

Each lane stopped at its boundary and reported rather than reaching across. All three were then
fixed here, which is the integrator's job and the reason the boundaries are worth enforcing.

1. **The contract lied about `EntityVersion.createdBy`.** Declared `required` and non-nullable;
   `test_case_version.created_by` is a nullable column with **no FK to `app_user`** (§28 finding 1),
   so both "nobody recorded" and "the id names no surviving account" are states a real row is in.
   Lane B shipped against a hand-widened alias and wrote down that it should be deleted when the
   contract was corrected. It now carries `allOf` + `nullable: true` — the idiom
   `TestCaseSummary.owner` already used for the same reason — and the alias is gone.
2. **The versions path declared only a `200`.** An unreachable project, a sibling-project case, a
   soft-deleted case and an absent one all answer 404. Same omission §29 found on the case `POST`;
   **that is twice, so it is a pattern rather than an oversight** — a new path gets its success
   response written and its refusals forgotten.
3. **The test suite was not in CI.** `ci.yml` reserved the step for `qa-cicd-typescript` and lane C
   respected that rather than crossing the boundary quietly. A suite nothing runs rots, so the
   pass/fail gate landed now; publishing and the flake lane still belong to that skill.

## Gate evidence — the merged tree, not the lanes

Every number below is from `master` after both merges **and** the three integration fixes, because
three separately-green trees are not evidence about the tree that results from combining them.

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 1m 27s
           630 tests, 0 failures, 0 skipped   (579 + 28 + 23)
frontend:  npm run build   → ✓ built, entry chunk 253.30 kB / 79.81 kB gzip (unchanged)
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ Test Files 10 passed (10) · Tests 203 passed (203)
           find "* 2.*"    → nothing outside node_modules
```

The `EBADENGINE` warning on `npm install` is **pre-existing**, not Phase 4's: it comes from
`@inquirer/*` via `msw` and was already in the lockfile at `af399a2`. Lane C's jsdom pin held —
`jsdom@28.1.0`, with vitest deduped onto the same copy.

## Was it worth it

Three slices landed in one session where the cadence has been one. Against that: two worktrees, two
`npm install`s, two Gradle daemons, and an integration pass that had to make a contract change and
re-run every gate. **The honest read is that it worked because the lanes were unusually well
separated** — one backend module each plus a frontend-only lane — and §33's recommendation to keep
vertical slices serial through M1 was *not* vindicated so much as narrowly dodged.

The reusable rule: parallelism was safe here because **exactly one lane could change the contract,
and it made only additive changes.** That is the property to check before the next run, not lane
count.

## Not done

- **No live browser pass on any lane.** §34's create dialog and §35's version panel are covered by
  integration tests over real Postgres and by mock-mode browser passes; neither was driven against
  `docker compose`. §34 names the two spots that want eyes (the key field's live upper-casing, and
  `PermissionGate`'s first company-scope call site).
- **`workItems.ts` falls back to `projects[0]`** for an unknown project id, so a newly created
  project's board renders another project's work items in mock mode. Pre-existing in the `work`
  domain; §34 made it reachable.
- **`CaseSchema`'s four cross-field refinements are untested** — it is module-private in
  `CaseEditorPage.tsx`. The follow-up is one word: `export const CaseSchema`.
- **`SuiteTree` silently drops an orphan suite** whose `parentId` is absent from the payload. Pinned
  as a characterisation test in §36, explicitly named as one; it flips to a real failure the day
  that list is paginated or filtered.
