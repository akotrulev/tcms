# Reworking the plan so work can run in parallel

**Date:** 2026-07-31.
**Status:** implemented, except where a row below says otherwise.
**Question this answers:** *can the delivery plan be reworked so more than one lane of work runs
at once, safely?*

**Short answer: yes for three specific shapes of work, no for the vertical slice** — which is the
shape §22–§32 have all used. The reworks below raise the safe lane count from one to about three,
and the payoff lands at M3/M4 rather than on the next three slices. That timing is the honest part
of the answer and it is not a reason to skip the reworks: they are cheap, and two of them are
improvements on their own terms.

---

## 1. Why the vertical slice cannot be parallelised as-is

Read `2026-07-31-case-delete-and-the-own-qualifier.md`. One slice touches a migration, the
permission evaluator across three modules, a controller, `openapi.yaml`, `queries.ts`, a mock
handler, and a browser pass in both API modes. That breadth is deliberate — it is what makes each
commit a state that was actually verified — and it is exactly why two of them cannot run side by
side.

The chokepoints, each a single-writer resource:

| Resource | Why it serialises | Status |
|---|---|---|
| `docs/specs/openapi.yaml` + `src/api/schema.d.ts` | `schema.d.ts` is **generated**; two regenerations do not merge | **Still single-writer.** By design — see §3. |
| Flyway `V13…` | Version numbers are claimed, not allocated | **Rule below.** |
| `frontend/src/api/queries.ts` | One 702-line file every slice appends to | **Fixed** — now `api/queries/`. |
| `frontend/src/mocks/handlers.ts`, `db.ts` | 1,792 lines every backend slice also edits | **Fixed** — now directories. |
| `docs/HANDOFF.md` | 276 KB, one section appended per slice | **Fixed** — now `docs/handoff/NN-*.md`. |
| The live/browser pass | One Postgres, one backend, one `localhost:5173` | **Fixed** — lane B stack. |
| `backend/build/` + the Gradle daemon | Shared output dir in one checkout | **Unfixed.** Use a worktree — see §6. |

## 2. What was changed

### Rework 1 — the frontend write-conflict files became directories

`api/queries/`, `mocks/handlers/`, `mocks/db/`, each with an `index.ts` barrel, so all 23 call
sites keep the import specifier they had. Three properties were preserved deliberately:

- **`keys.ts` stays a single key factory.** An invalidation in `cases.ts` targets keys `suites.ts`
  owns. Two factories would drift into invalidations that quietly match nothing — the failure mode
  is a stale list, with no error anywhere.
- **The mock store does not split.** The suite tree reads the case list and work items read
  executions; a store carved per-domain would have to reassemble itself before answering.
- **Handler order is load-bearing**, so `index.ts` composes rather than concatenates, and the fault
  wildcard is exported alone from `injection.ts` so "this must stay first" has somewhere to live.

Cost, measured rather than assumed (§6 lesson 8): bundle 1,427,883 → 1,431,259 bytes, **+0.24%**.

### Rework 2 — the handoff became one file per section

`docs/handoff/NN-slug.md`, with the **§ number preserved as the filename prefix**. The plan, the
audits and four Java files all cite sections as `§6 lesson 5` / `HANDOFF §14`, and every one of
those still resolves by listing the directory — nothing had to be rewritten. `HANDOFF.md` keeps the
preamble and becomes the index.

**The convention this creates: a new slice adds a file and one index row. It never appends to
`HANDOFF.md`.**

### Rework 5 — a second stack, so the browser pass is not a singleton

`COMPOSE_PROJECT_NAME` + `.env.lane-b.example` (6432 / 9080 / 6173) + `npm run dev:lane-b`. The
project name is the load-bearing part: Compose prefixes **volume** names with it, so without it
lane B's Flyway migrates lane A's database.

`strictPort: true` came with it. Vite's default walks to the next free port, which with two lanes
means lane B silently landing on 6174 while `TCMS_BASE_URL` still says 6173 — every invitation link
then opens lane A's tab against lane A's backend, and it reads as a backend defect.

---

## 3. Rework 3 — contract batching, and why it is DECLINED for vertical slices

The available rework is: land all of a lane-group's endpoint shapes in one contract slice up front,
regenerate `schema.d.ts` once, then let implementers work against a frozen contract and never touch
`openapi.yaml`.

**This is declined for vertical slices, and the reason is evidence rather than taste.** §28 found
*four contract divergences by reading during implementation*. §25 changed `TestCaseSummary.owner` to
nullable mid-slice. §30 found the `If-Match`-in-the-body divergence the same way. Freezing the
contract ahead of implementation removes the loop that produced all of those.

**It is adopted for the port/adapter fan-outs in §5**, where the port genuinely is the contract and
is frozen before the adapters start.

So: **one writer for `openapi.yaml` per parallel run.** The lane holding the contract also holds
`npm run gen:api`. A second lane needing a contract change stops and hands it over; it does not
edit in parallel and merge.

## 4. Rework 4 — the Flyway numbering rule

`spring.flyway.out-of-order` is **unset** in `application.yml`, so Flyway's default of `false`
applies. The consequence:

> If lane B lands `V14` first, lane A's `V13` is **refused on any already-migrated database**.

Testcontainers never sees this (every suite migrates a fresh container), and a `docker compose
down -v` hides it too. It appears on a developer's warm volume and on any deployed instance — the
worst place to discover it.

**The rule: pre-assign version numbers, and renumber at integration in landing order.** Renumbering
is cheap because a migration is a new file, never an edit. Numbering at authoring time and hoping
the merge order matches is what is forbidden.

Do **not** "fix" this by setting `out-of-order: true`. That makes migration order depend on when
each instance last migrated, which is a different schema per install.

## 5. Rework 6 — pick lanes by Modulith module, and the three real fan-out seams

Lanes are chosen by module boundary, not by the order of a "Next" list. The boundaries are already
machine-checked by ArchUnit, so the seam is real rather than aspirational, and `execution` does not
exist yet — an agent building it writes only new packages and events.

The plan already contains three natural fan-outs, all of the form *one interface, N implementations*,
which is the only structure that parallelises without coordination:

| Seam | Plan ref | Lanes | Note |
|---|---|---|---|
| **Result parsers** — JUnit XML · TestNG · Allure · Cucumber JSON | M3 | 4 | Best candidate in the plan: pure functions over fixture files, no schema, no contract, no UI. Gated on Outstanding #43 (the parser SPI), which must be answered before M3 freezes ingestion anyway. |
| **Four `IssueTracker` adapters** — Jira · GitHub · GitLab · Azure DevOps | M4, §7 | 4 | The plan's own justification is already a fan-out argument: *building four at once is what stops the port leaking Jira's vocabulary.* |
| **Three report renderers** — HTML · MD · PDF behind `ReportModel` | §6e | 3 | "One model, three renderers" — the abstraction is specified before the renderers exist. |

**In every case the port lands serially first.** Get the port wrong and you have four adapters to
redo, which is worse than having built them one at a time.

---

## 6. What no rework can remove

§28, §29, §30 and §31 each open with the same finding in different words: **the orientation pass
changed the slice before it started.** Four consecutive slices; §32 was the first where it did not.

That coupling is not a file-layout artifact. It is how this project discovers what to build next,
and it has caught most of the defects the handoff records. Module-disjoint lanes weaken it — lanes
with less to say to each other — but do not remove it.

**The concrete consequence: parallel lanes will re-derive premises from a handoff that is one slice
stale, and some of that work will be redone.** The §5 fan-outs are immune because their premise is
the port, frozen before they start. Vertical slices are not.

Two smaller residuals, both stated rather than solved:

- **One checkout, one `backend/build/`.** Two `./gradlew build` runs in the same tree contend on
  file locks, and one lane editing sources while the other compiles produces failures that look
  like real defects. A second lane touching the backend wants a **git worktree**. Note this
  conflicts with `CLAUDE.md`'s "work on the current branch, do not branch per task" — that rule is
  about not branching per *task*, and a concurrency worktree is a different thing, but the
  exception should be explicit before it is used.
- **Docker cost.** Each backend lane runs Testcontainers plus, if it holds a browser pass, a
  compose Postgres. Two lanes is comfortable on a laptop; four is not.

## 7. The recommendation this produces

1. **Now (done):** reworks 1, 2, 4, 5, 6 — the file splits, the numbering rule, the second stack,
   the lane-selection rule.
2. **Keep the current cadence serial through the rest of M1 and M1.5** — the project-creation path,
   `GET …/cases/{caseId}/versions`, and the `execution` module. These are the slices where the
   orientation pass is still finding something every time.
3. **Do not batch the contract for vertical slices** (§3).
4. **Plan the real parallel push for M3 and M4**, on the fan-outs in §5.

## 8. Checklist before starting any parallel run

- [ ] One lane named as the **contract holder**; every other lane is forbidden `openapi.yaml` and
      `npm run gen:api`.
- [ ] Flyway versions **pre-assigned**, with renumber-at-integration understood (§4).
- [ ] Lanes chosen by **Modulith module**, not by "Next"-list order (§5).
- [ ] The second lane on a **git worktree** if it touches the backend (§6).
- [ ] Lane B's stack up via `.env.lane-b` if it needs a browser pass, or the pass is queued.
- [ ] Each lane writes **its own** `docs/handoff/NN-*.md`; the index row is merged last.
