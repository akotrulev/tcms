# §33. The parallel-execution rework — the three write-conflict files, the handoff, and a second stack (2026-07-31)

**Not a feature slice.** No endpoint, no migration, no contract change. This session answered a
question — *can the plan be reworked so more than one lane of work runs at once?* — and then
implemented the answer. The reasoning lives in
[`docs/plans/2026-07-31-parallel-execution-rework.md`](../plans/2026-07-31-parallel-execution-rework.md);
this section records what changed and what it cost.

## The headline: the constraint was never the source files

The obvious answer to "can we parallelise" is "split the big files". That turned out to be the
*cheap* half. The binding constraint is the **browser pass** — one Postgres, one backend, one
`localhost:5173` — which caps concurrent work at one lane no matter how the source is arranged.
That is now fixed too, and it is the change most likely to be underestimated.

The second finding is the one that cannot be fixed at all, and it is worth reading before planning
any parallel run: **§28, §29, §30 and §31 each opened by recording that the orientation pass changed
the slice before it started.** Four consecutive slices. That coupling is this project's discovery
mechanism, not an artifact of layout, so parallel lanes will re-derive premises from a
one-slice-stale handoff and some work will be redone. The plan's §6 says so plainly rather than
pretending otherwise.

## What changed

| Area | Change |
|---|---|
| `frontend/src/api/queries.ts` (702 L) | → `api/queries/` — one module per domain, `index.ts` barrel |
| `frontend/src/mocks/handlers.ts` (1302 L) | → `mocks/handlers/` — one module per endpoint family |
| `frontend/src/mocks/db.ts` (490 L) | → `mocks/db/` — store central, readers per domain |
| `docs/HANDOFF.md` (276 KB) | → `docs/handoff/NN-slug.md` + an index |
| `compose.yaml`, `.env.lane-b.example`, `vite.config.ts`, `package.json` | A second, independent local stack |
| `CLAUDE.md` | The parallel-work rules, the worktree exception, the new handoff convention |

**All 23 frontend call sites kept their import specifier.** The directories carry `index.ts`
barrels, so no page changed.

## Three things preserved on purpose, each of which the split could have broken

1. **`keys.ts` stays a single key factory.** An invalidation in `cases.ts` targets keys `suites.ts`
   owns. Two factories drift into invalidations that match nothing — and the symptom is a stale
   list with no error anywhere.
2. **The mock store does not split.** The suite tree reads the case list; work items read
   executions. A per-domain store would have to reassemble itself before answering anything.
3. **MSW handler order is load-bearing.** `index.ts` composes rather than concatenating, and the
   fault wildcard is exported alone from `injection.ts` so "this must stay first" has a home.

## The § number is the address, which is why nothing had to be rewritten

The handoff split preserved section numbers as filename prefixes. The plan, the audits and four
Java files cite sections as `§6 lesson 5` / `HANDOFF §14` / `docs/HANDOFF.md §13`, and every one
still resolves by listing the directory. Renaming the sections to pure slugs would have meant
touching all of them.

## Two rules that are now written down because they fail silently

- **Flyway numbering.** `spring.flyway.out-of-order` is unset → defaults to `false`, so a `V13`
  landing after a `V14` is **refused on any already-migrated database**. Testcontainers cannot see
  this (fresh container every suite) and `down -v` hides it. Pre-assign, renumber at integration.
  Do **not** set `out-of-order: true` — that gives every install a different schema order.
- **`strictPort: true` on vite.** The default walks to the next free port, which with two lanes
  means lane B silently landing on 6174 while `TCMS_BASE_URL` still says 6173 — so every invitation
  link opens lane A's tab against lane A's backend, and it reads as a backend defect.

## The one contract-level rework that was DECLINED

Batching `openapi.yaml` changes ahead of implementation would remove the last frontend/backend
chokepoint. It is declined for vertical slices on evidence: §28 found four contract divergences *by
reading during implementation*, §25 changed `TestCaseSummary.owner` mid-slice, §30 found the
`If-Match`-in-the-body divergence the same way. Freezing the contract first removes the loop that
produced all of those. It is adopted only for the M3/M4 port-and-adapter fan-outs, where the port
is the contract and is frozen before the adapters begin.

## Gate evidence — run, not just built

**Frontend** — `npm run build` clean, `npx oxlint` 0 warnings. Bundle **1,427,883 → 1,431,259 B
(+0.24%)**, measured by building both trees rather than reasoned about (§6 lesson 8); the delta is
the re-export boundary.

**Browser pass, mock mode.** Case list 27 with the tree reconciling 19+3+3+2. Delete TC-1 → 26,
Refunds 14→13, Payments 19→18, each moving by exactly one (the §32 double-count regression would
have shown here). Board renders its derived cards, exercising work items → executions → cases →
store, the deepest chain across the split. MOB as Viewer still disables Edit and Delete, so the
`permissionsOn` gate survived the move into `shared.ts`. Console free of errors and MUI warnings;
the only 4xx was the 404 for the case just deleted.

**Handoff split verified mechanically, not by reading** (§6 lesson 9): all **2,466** significant
lines of the original appear in the split files, **0 missing**.

**Lane B booted rather than described.** `tcms-b-postgres-1` healthy on 6432 with its own
`tcms-b_pgdata`; the initdb role script ran there too — `tcms_app` and `tcms_owner` both present,
neither superuser, neither `BYPASSRLS`. Vite served 5173 and 6173 simultaneously, and a third vite
on 6173 failed with *Port 6173 is already in use* rather than shifting. Torn down with `down -v`.

**Backend untouched** — no Java, no SQL, no `openapi.yaml` in this session.

## Deliberately not done

1. **No worktree was created.** The rule that a backend second lane needs one is written down
   (`CLAUDE.md`, Git workflow); using it is the next session's call.
2. **The `execution` module, project creation, `/versions`** — the actual M1 backlog is untouched.
   This session deliberately changed no product behaviour.
3. **Nothing was parallelised.** The reworks make parallel lanes *safe*; the plan's recommendation
   is still to keep M1/M1.5 slices serial and spend the concurrency at M3/M4.

## Next

Unchanged from §32, because this session added no product capability:

1. **A project-creation path** — still blocking twice over ("provisioned company" still means
   "provisioned company plus hand-written SQL").
2. **`GET …/cases/{caseId}/versions`** with the detail page's history panel — `useCaseVersions` is
   now seven slices old and still the oldest dead hook.
3. **The `execution` module** — unblocks `/history` and owns `evidence:delete`.
4. Frontend **Phase 4 (Vitest + RTL)** — `useCan` remains the best first target, and it is the one
   item on this list that is safe to run as a second lane today.
5. **Board drag-and-drop is still ungated** (`workitem:manage`).
