# Reworking the plan so work can run in parallel

**Date:** 2026-07-31 · **Amended 2026-08-02** with §0, the operative gate and the 4-agent cap.
**Status:** implemented, except where a row below says otherwise. **Start at §0.**
**Question this answers:** *can the delivery plan be reworked so more than one lane of work runs
at once, safely?*

**Short answer: yes for three specific shapes of work, no for the vertical slice** — which is the
shape §22–§32 have all used. The reworks below raise the safe lane count from one to about three,
and the payoff lands at M3/M4 rather than on the next three slices. That timing is the honest part
of the answer and it is not a reason to skip the reworks: they are cheap, and two of them are
improvements on their own terms.

---

## 0. The gate and the cap — read this before starting any run

> **Added 2026-08-02.** The rest of this document explains *why* each rule exists; this section is
> the operative form. It supersedes the blanket "work is sequential from here" that handoff §45 and
> `docs/HANDOFF.md` have carried since 2026-08-01 — see §0.4 for what did and did not survive.

### 0.1 The cap

**At most 4 agents at once. Fewer whenever any rule in §0.2 or §0.3 says so.**

Four is a ceiling, not a target. Every sub-cap below bites *before* it, and three of them bite at 2.
A run of two module-disjoint lanes is a normal parallel run; a run of four is only reachable for the
fan-out shape in §5, where four lanes write four files behind one frozen port.

### 0.2 The safety gate — parallel is safe only when EVERY row holds

| # | Condition | Why, and where it is explained |
|---|---|---|
| 1 | **The premise is frozen, not being discovered.** A port, an interface or a written spec exists before the lanes start. | §6. Lanes re-derive premises from a handoff one slice stale, and redo the work. This is the rule that disqualifies most vertical slices. |
| 2 | **Lanes are disjoint by Modulith module**, or are N implementations behind one port. | §5. ArchUnit machine-checks the boundary, so the seam is real rather than hoped for. |
| 3 | **Exactly one contract holder — or zero.** Every other lane is forbidden `openapi.yaml` and `npm run gen:api`. | §3. `schema.d.ts` is generated; two regenerations do not merge. |
| 4 | **Flyway versions pre-assigned**, and preferably **at most one lane writes DDL**. | §4. `out-of-order` is unset, so a `V13` landing after a `V14` is refused on any already-migrated database. Testcontainers cannot see this. |
| 5 | **Each backend-touching lane has its own git worktree.** | §6. One `backend/build/` and one Gradle daemon cannot serve two lanes. This is the one sanctioned exception to `CLAUDE.md`'s "do not branch per task". |
| 6 | **Fixture UUID prefix space pre-assigned per lane**, in writing, before any lane writes a test. | §19's collision has now fired **five times** (most recently §62, where `b0` was already the board tests' and `project_pkey` is global). The prefix space is hand-managed and has no check behind it — it is the single most reliable way a parallel run goes red for a reason that looks like a real defect. |
| 7 | **Each lane writes its own `docs/handoff/NN-*.md`**; the index row is merged last. | §2. This is why the handoff was split in the first place (§10). |
| 8 | **One cache holder per agent type.** `docs/agent-context/*.md` is refreshed serially *before* the fan-out, read-only during it, and updated by exactly one lane at integration. | §0.5. Two lanes of the same agent type write the same file, and the protocol's own justification assumes they cannot. |

If a row cannot be satisfied, the honest move is **fewer lanes**, not a waiver.

### 0.3 Sub-caps that bite before 4

| Resource | Cap | Reason |
|---|---:|---|
| Lanes running the **backend suite** | **2** | Each runs Testcontainers Postgres; §6 already records that "two lanes is comfortable on a laptop; four is not". |
| Lanes needing a **browser pass** | **2** | Only two stacks exist — the default and `.env.lane-b` (6432 / 9080 / 6173). A third needs a third set of ports invented first. |
| Lanes editing the **contract** | **1** | Gate row 3. |
| Lanes writing a **migration** | **1** preferred, 2 with pre-assigned versions | Gate row 4. |
| Lanes that are **pure functions over fixtures** (parsers, adapters, renderers) | **4** | No schema, no contract, no UI, no shared build state. This is the only shape that actually reaches the cap. |

**So the practical reading:** 4 agents is reachable for §5's fan-outs — four result parsers (M3), four
`IssueTracker` adapters (M4), three report renderers (§6e). For anything touching the backend it is
2. For a vertical slice it is 1.

### 0.4 What this supersedes, and what it does not

~~**Work is sequential from here unless the user asks otherwise** (§45, 2026-08-01).~~
→ **Superseded 2026-08-02**: parallel is allowed again, bounded by §0.1–§0.3. The user's instruction
was *"unless the user asks otherwise"*, and this is that.

**§45's finding is not superseded and is the reason the cap exists.** Three lanes were run and the
verdict was *"they take 2x the time and tokens"*: wall clock is bounded by the longest lane, and
every lane re-derives the same premises. That is a **cost** finding, not a correctness one — which is
exactly why the answer is a ceiling with sub-caps rather than a ban. Gate row 1 is §45's finding
turned into a precondition: freeze the premise and the re-derivation stops being paid for N times.

**Still true, still binding:** everything in §1–§6. The reworks that made parallelism possible at all
(the file splits, the numbering rule, the second stack, the lane-selection rule) are unchanged.

### 0.5 The orientation cache — an asset, and a single-writer resource

`docs/agent-context/be-java.md` and `fe-react.md` are the per-agent orientation caches
(`ep-agent-orientation-cache`). **They are the best thing that has happened to the economics of a
parallel run and they need the same single-writer discipline as `openapi.yaml`.**

**The assumption they were built on does not survive fan-out.** The protocol justifies one file per
agent *type* with: *"Different agent types never write the same file, so there is no cross-agent
write contention."* True for one instance per type. Four lanes are not — two backend lanes are both
`be-java` and both write `be-java.md`.

Three distinct failures, in increasing order of nastiness:

1. **Guaranteed merge conflict.** The file stamps a `Baseline commit` and *prepends* a session-log
   line. Two lanes touch both structures, so every concurrent run conflicts in the same two places.
   Annoying, visible, cheap.
2. **Lost seams.** Phase 3's highest-value step is *"add the seams the run created"*. Last-writer-wins
   on a merge resolution silently drops the other lane's — and a cache that is confidently
   *incomplete* is worse than one that is missing, because the next run's gate will TRUST it.
3. **A poisoned baseline stamp.** This is the real one. Each lane stamps the `HEAD` *it* finished at.
   Whichever survives describes neither lane's actual state, so the next run's delta gate computes
   `git diff <wrong baseline>..HEAD`, under-reports what moved, and returns **TRUST for sections that
   are stale**. The failure is silent and it lands on the *following* session, not on the run that
   caused it.

Worktrees (gate row 5) do not fix this — they defer it. Each worktree has its own copy, so the lanes
do not contend *during* the run; they contend at merge, plus lane B spends the whole run reading a
map that is missing whatever lane A just built.

**The rule, which is `openapi.yaml`'s rule in a different costume:**

- **Refresh the cache serially, before the fan-out.** This is part of gate row 1 — the cache *is* a
  large share of "the frozen premise".
- **Read-only during the run.** Every lane reads it; no lane runs Phase 3 against it.
- **One lane per agent type runs the update-after at integration**, folding in the seams every lane
  created. If two backend lanes ran, one of them writes `be-java.md` and names the other's seams too.

**Why this is worth the ceremony rather than just disabling the cache:** §45 measured the cost of
parallelism as *"every lane re-derives the same premises"*. A shared, frozen, pre-warmed cache is the
direct antidote — it is the one mechanism that makes N lanes cost meaningfully less than N times one
lane. Serialising the *writes* keeps all of that and gives up nothing the lanes needed.

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
2. ~~**Keep the current cadence serial through the rest of M1 and M1.5**~~ → **done, and it held**:
   those slices ran serially and the orientation pass changed the slice before it started in most of
   them. The general form of this recommendation is now **§0.2 gate row 1** — serial is the default
   whenever the premise is still being discovered, which is a property of the work rather than of a
   milestone.
3. **Do not batch the contract for vertical slices** (§3).
4. **Plan the real parallel push for M3 and M4**, on the fan-outs in §5 — **still the recommendation,
   and still unstarted.** M3 is 0% and M4 ~12% (`docs/audits/2026-08-02-remaining-scope-assessment.md`),
   so both fan-outs are entirely ahead. These are the runs the 4-agent cap in §0.1 was set for.

## 8. Checklist before starting any parallel run

- [ ] **Lane count is within §0.1 (max 4) and every sub-cap in §0.3** — backend lanes ≤ 2, browser
      passes ≤ 2, contract lanes ≤ 1, migration lanes ≤ 1.
- [ ] **The premise is frozen** — a port, interface or spec exists — not still being discovered
      (§0.2 row 1). If it is not, run serially.
- [ ] One lane named as the **contract holder**; every other lane is forbidden `openapi.yaml` and
      `npm run gen:api`.
- [ ] Flyway versions **pre-assigned**, with renumber-at-integration understood (§4).
- [ ] Lanes chosen by **Modulith module**, not by "Next"-list order (§5).
- [ ] The second lane on a **git worktree** if it touches the backend (§6).
- [ ] Lane B's stack up via `.env.lane-b` if it needs a browser pass, or the pass is queued.
- [ ] **Fixture UUID prefix space assigned per lane, in writing** — the hand-managed namespace that
      has now collided five times (§0.2 row 6). Check the census first:
      `grep -rhoE 'UUID\.fromString\("[0-9a-f]{2}' backend/src/test/java | grep -oE '[0-9a-f]{2}$' | sort -u`
- [ ] Each lane writes **its own** `docs/handoff/NN-*.md`; the index row is merged last.
- [ ] **`docs/agent-context/*.md` refreshed serially BEFORE the fan-out**, read-only during it, and
      **one lane per agent type** named to run the update-after at integration (§0.5).
