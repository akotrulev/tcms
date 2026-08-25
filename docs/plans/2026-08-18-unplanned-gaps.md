# Unplanned Gaps — Task Plan

**Date:** 2026-08-18 · **Revised:** 2026-08-22 (twice — restructure, then progress) · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gaps recorded in `docs/features.md` § *Gaps Not Currently Planned*. **Seven of
the nine are implemented. Two remain: D and G.**

**Architecture:** Every closed area was additive — a new write field, a new endpoint, a new table, a
new operator command — and none changed the tenancy, permission or contract machinery. The
catalogue has stayed at **55 keys** through all seven. Exactly one area needed DDL and it has spent
it.

**Tech Stack:** Spring Boot 3.5.6 / Java 21 / Flyway / Postgres 17 · React 19 / TypeScript / MUI v7
/ TanStack Query · `openapi.yaml` as the contract, generated into `frontend/src/api/schema.d.ts` and
`packages/client/src/schema.ts`.

---

## Status

| Area | Gap | State | Section |
|---|---|---|---|
| — | Audit log reader | ✅ shipped | `docs/handoff/242` |
| — | Review-workflow notifications | ✅ shipped | `docs/handoff/243` |
| **A** | Execution duration | ✅ shipped | `docs/handoff/244` |
| **B** | Bulk execution assignment | ✅ shipped | `docs/handoff/245` |
| **E** | Company data export | ✅ shipped | `docs/handoff/246` |
| **C** | Scheduled runs | ✅ shipped — spent **`V68`** | `docs/handoff/247` |
| **F** | Keyboard-driven execution | ✅ shipped | `docs/handoff/248` |
| **D** | Cross-project case search | ⬜ **next** — stepped plan written, not implemented | → §249 |
| **G** | i18n groundwork | ⬜ open — not planned, deliberately | — |

### The state that matters more than the tally

**Five of the seven shipped areas have never been gated.** A, B, E, C and F are implemented,
committed and pushed to `origin/master`, and `./gradlew build` and the npm suites have not been run
over any of them — §247 was never even compiled. That is **~93 backend arms and 32 frontend arms
whose first execution is still ahead**, plus whatever the five authors' comment citations did to
`citedFiles.test.ts`.

This is not the §65 queue backlog, and conflating the two would stall the work. They are different
things with different owners:

| | What it is | Who runs it | Blocks D? |
|---|---|---|---|
| **The gates** | `./gradlew build`, the three npm suites, `lint` — on `shanks` | the session, mechanically | **Yes** |
| **The §65 queues** | 56 backend mutation rows + 36 browser rows, driven by hand | the user, in a sitting of its own | No — still deferred by standing policy |

**Gate the tree before D lands.** `./gradlew build` compiles and tests the whole backend, so there
is no gating D alone: D's own gate would necessarily be the first execution of A, B, C and E too, and
every red would arrive unattributed in the session least able to judge whose it is. D is also the one
area that can expose a **tenancy** defect — precisely the finding you do not want landing in a pile
of five other authors' failures. `prompts/batch-4-d-alone.md` stages this: gates first, then D.

**Every shipped area is therefore "built and unverified"** in both senses. Describe it that way.

## What the two 2026-08-22 revisions changed

**First revision — restructure.** Slices and phases became 55 numbered tasks; two areas were found
already shipped; and one premise was found false. The old plan opened area A with *"Migration `V68`.
Add `duration_seconds INTEGER` to `test_execution`."* **The column already existed** — `duration_ms
BIGINT` since `V1__initial_schema.sql:845`, mapped, served and summed into the run rollup. The real
gap was one field wide: `ExecutionResultRequest` carried no duration, so only ingestion could write
it. §244 shipped it with **no migration**, which freed `V68` for scheduled runs and voided the old
A→B ordering reason ("one migration wave" — there was no wave).

**Second revision — progress.** Five more areas landed. What the run confirmed, and what it did not:

- **The parallelism analysis held.** E was marked *"the one area that shares nothing with anyone"*;
  §246 records *"no contract change … no file under `frontend/` or `packages/` touched."* B ∥ E and
  C ∥ F both ran concurrently without a conflict.
- **The pre-assigned fixture prefixes were not all honoured, and it cost nothing** only because the
  namespace is large. C took `3d` rather than the assigned `e7`; F took `2c`, which was the spare.
  Pre-assignment still did its job — no collision — but assignment and use should be reconciled.
- **`V68` went to C exactly as re-assigned**, and is the plan's only migration.
- **Two things the stepped plans missed, both caught by an author reading the code rather than the
  plan.** `SchemaConventionTest`'s policy count is a **literal** (C moved `70` → `71` in the
  migration's own commit); and `everyMappedColumnHasAWriter`'s name-text match produced a **false
  negative** in §244, which C then avoided by leaning only on the genuine-Java-setter disjunct.
  Neither is visible without gates, which is the argument for running them.
- **One coverage gap nobody has filed.** C added five `run-schedule` contract operations and gave
  `@tcms/cli` and `@tcms/mcp` **no verbs for any of them**. `writeConformance.test.ts` checks the
  *command registry*, so a missing command is invisible to it. Not a red — a gap. It needs a row.
- **`§242 LG-5` is drivable at last.** The scheduled-runs poller is the product's first actorless
  writer, so the audit row with `actorUserId` null now has a producer. Superseded in place, id
  unchanged.
- **`CLAUDE.md`'s frontend test count (1582) is stale by at least 43.** Re-measure from a run.

## Old slice numbers → task groups

Handoff §242 and `docs/features.md` cite this plan by slice number. The mapping is stable:

| Old | New | Area | State |
|---|---|---|---|
| Slice 1 | — | Audit log reader | ✅ §242 |
| Slice 2 | — | Review-workflow notifications | ✅ §243 |
| Slice 3 | **A** — Tasks 1–6 | Execution duration | ✅ §244 |
| Slice 4 | **B** — Tasks 7–14 | Bulk execution assignment | ✅ §245 |
| Slice 5 | **C** — Tasks 15–25 | Scheduled runs | ✅ §247 |
| Slice 6 | **D** — Tasks 26–32 | Cross-project case search | ⬜ **next**, → §249 |
| Slice 7 | **E** — Tasks 33–40 | Company data export | ✅ §246 |
| Slice 8 | **F** — Tasks 41–46 | Keyboard-driven execution | ✅ §248 |
| Slice 9 | **G** — Tasks 47–55 | i18n groundwork | ⬜ open |

## Scope Check

`ep-writing-plans` requires a plan to cover **one** subsystem; this covers nine, because the request
was for the order and the sizing. It is a **master plan**, and its two-tier shape is the one this
repo has now proved seven times: the master plan scopes, and **each area's first act is to run
`ep-writing-plans` on itself alone** before touching code.

Six stepped plans exist and all six were followed to completion:

| Area | Stepped plan |
|---|---|
| B | `docs/plans/2026-08-18-gap-b-bulk-execution-assignment.md` |
| C | `docs/plans/2026-08-18-gap-c-scheduled-runs.md` |
| **D** | `docs/plans/2026-08-18-gap-d-cross-project-case-search.md` — **written, not yet executed** |
| E | `docs/plans/2026-08-18-gap-e-company-data-export.md` |
| F | `docs/plans/2026-08-18-gap-f-keyboard-driven-execution.md` |
| (2) | `docs/plans/2026-08-18-gap-2-review-notifications.md` |

**G has no stepped plan and must not get one yet** — it sweeps a surface D is about to move. Write it
after D lands, not before.

**D's plan carries three stale references.** It says `be-java.md`'s header names `§243` (now `§247`),
that `§243` is the section head (now `§248`), and it reasons about `V68` as unspent (C spent it).
Its *decisions* are unaffected — it takes no Flyway version either way — but do not copy its numbers.

## What is left, and in what order

| Area | Tasks | Size | Lane | Why here |
|---|---|---|---|---|
| **D** Cross-project case search | 7 | Full | BE-led | The riskiest read in the plan — it must narrow to the caller's project grants *inside* the query. Wants a green, quiet tree, which is why the gate pass precedes it. |
| **G** i18n groundwork | 9 | **Full+ — largest overall** | FE only | 132 components / ~41.7k lines / 51 `satisfies Record` maps. Last by construction: any earlier and D adds untranslated strings behind it. |

**Flyway.** Head is **`V68`**, spent by C. **Neither D nor G migrates.** D's Task 15 *measures* a
query plan and records an index as **owed** rather than adding DDL. `out-of-order` is unset — leave
gaps, never renumber.

## Parallelism — now moot

The parallelism matrix governed batches 1–3 and has done its job. **It no longer applies: two areas
remain and they cannot run together.**

**`D ∥ G` is not eligible.** G sweeps all 132 frontend components, and D adds a new one
(`CaseSearchPage.tsx`) plus a `router.tsx` edit. G conflicts with every frontend area by
construction, and D is a frontend area.

So the rest of this plan is **sequential**: gate the tree → D → G. **One subagent, so the standing
cap applies and no special permission is needed.** `docs/specs/parallel-work-rules.md` is back to
being the default rather than something to argue against.

**What the concluded matrix got right, kept for the next plan that needs one.** Eligibility was
decided on four shared resources only — `openapi.yaml`, Flyway, backend module, frontend files — and
the two calls it staked itself on both held: E ran beside everything and touched nothing, and the
A→B spine was genuinely unsplittable because both edit `ExecutionService.java`. The rule that proved
most load-bearing was the least obvious one: **the close is a conflict even between lanes whose code
is disjoint**, because every area ends by writing `docs/agent-context/*.md` and `docs/HANDOFF.md`.

### Resources consumed, so nothing is reused

- **Fixture UUID prefixes taken:** `0a` (§242), `66` (§244), `88` (§245), `7c` (§246), `3d` (§247),
  `2c` (§248). **D takes `4d`** — self-assigned in its own stepped plan and verified unused.
  **`5e` is in use in the tree and is not a spare.**
- **Sections taken:** §242–§248. **D is §249**, G is §250.
- **Queue ids:** D takes `CS-1..7` and `CS-M1..M9`, both unused in either queue.
- **Contract and router:** D holds `openapi.yaml` and `router.tsx` alone, and is the only lane
  running `npm run gen:api`.

## File structure

**Area D** — `openapi.yaml` · `backend/…/repository/{CaseSearchController,CaseSearchService,CaseSearchQuery,CaseSearchHitView}.java` (new) ·
`frontend/src/features/cases/CaseSearchPage.tsx` (new) · `frontend/src/app/router.tsx`

**Area G** — `frontend/src/i18n/` (new) · `frontend/src/app/Providers.tsx` ·
`frontend/src/lib/labels.ts` · a bare-literal check under `frontend/src/test/`

---

# Shipped areas — the record

Task-by-task steps for these lived here until they landed; the handoffs are now the record and are
not duplicated. What is kept is only what a later reader needs.

| Area | Section | Shipped | Kept because |
|---|---|---|---|
| **A** Execution duration | §244 | No migration; `durationMs` on `ExecutionResultInput`, the service write, the form control, both write clients | The plan called for a migration for **a column that had shipped in `V1`**. Reading the code rather than the plan is what caught it — and `docs/features.md`'s claim that no duration field existed was false when written. |
| **B** Bulk assignment | §245 | `POST /runs/{runId}/executions/bulk` with round-robin distribute; one `audit_event` per target sharing a `bulk_operation_id` | It was the product's **first `bulk_operation_id` writer**, which made the `ArchitectureRulesTest` writerless exemption false and required the shipped audit reader to group. Both debts came from §242 and both are now paid. |
| **E** Company export | §246 | `export-company` operator CLI command; one `CompanyContext`, one read-only transaction, blobs included, redaction list settled up front | The **only area touching no contract, no migration and no frontend file** — which is what made it eligible beside every other area, and it ran that way. |
| **C** Scheduled runs | §247 | **`V68`** `run_schedule`; per-company poller on `CaseHealthScheduler`'s shape; five contract operations; settings screen | Spent the plan's **only migration**. Found `SchemaConventionTest`'s policy count is a **literal** (`70`→`71`). Made **`§242 LG-5` drivable** as the first actorless writer. **Left `@tcms/cli` and `@tcms/mcp` with no verbs for its five operations** — an unfiled coverage gap. |
| **F** Keyboard execution | §248 | Key map in one module, the focus guard, the live region, the `?` overlay; `ExecutionPage.test.tsx` created | The focus guard **is** the feature — shortcuts firing while a text field has focus is the defect every implementation of this ships first. 32 arms, none yet executed. |

---

# Area D — Cross-project case search

> **Next.** Stepped plan written, not executed. Runs **alone**, after the gate pass. §249, prefix `4d`, no migration.


**Tasks 26–32. One session, backend-led.** **Run `ep-writing-plans` on this area first.**

**Goal:** Search cases across every project the caller can see, in one query. My Work, the boards and
the org chart all went cross-project; the repository did not.

**Shape:** `GET /cases/search` — company-scoped, returning `projectId` and `projectName` per hit.

**Why it is the riskiest read in this plan:** every other case read is scoped to one project and
gated by that project's grant. This one returns rows from *many* projects.

### Task 26: Read the two cross-project precedents before designing

**Files:** read `openapi.yaml:7071` (`/work-items`) and `backend/…/identity/OrgChartService.java`

- [ ] `/work-items` is the shipped company-scoped cross-project read. `OrgChartService` and
  `AdminReach` already carry visible-project narrowing. **Reuse that narrowing** — a second
  implementation of "which projects may this caller see" is the defect class this task exists to
  avoid.

### Task 27: Contract

- [ ] Path, query parameters, a paged response whose hit carries `projectId` and `projectName`.
  `required` arrays throughout. Regenerate both copies and `diff`.

### Task 28: Write the failing grant-narrowing test

**Files:** Create `backend/src/test/java/com/tcms/repository/CaseSearchTest.java`

- [ ] A person granted **one project of three** sees hits from one project only — and **assert on
  the total count, not just the page**. A count computed over rows the caller may not see leaks the
  existence of those rows even when the page hides them.
- [ ] An arm proving RLS is not what saves you here: RLS scopes to the **company**. Two projects in
  one company are both RLS-visible, so the narrowing is entirely the application's job.

### Task 29: Narrow inside the query, not after it

**Files:** Create `backend/…/repository/{CaseSearchController,CaseSearchService,CaseSearchQuery,CaseSearchHitView}.java`

- [ ] The project-grant predicate goes **in the SQL**. Filtering in Java after an unrestricted read
  means the count and the paging are computed over rows the caller may not see — which is the
  defect Task 28 asserts against.
- [ ] Stable sort with a secondary key, so paging cannot repeat or skip.

### Task 30: Check the query plan

- [ ] Confirm the search does not seq-scan, measured on a seeded `shanks` container. `V67` already
  fixed one seq scan on the authorization hot path — do not add another.
- [ ] **Record any index this needs as OWED; do not add DDL.** Head is `V68` and D takes no Flyway
  version — its own stepped plan says so, and an index added here would be the plan's second
  migration arriving without a number assigned to it. Write the finding into the handoff, with the
  measurement behind it, so whoever spends `V69` has the evidence rather than the hunch.

### Task 31: The search screen

**Files:** Create `frontend/src/features/cases/CaseSearchPage.tsx`; modify the router

### Task 32: Close the area

- [ ] All gates; caches; queue rows; handoff + index row; push.

---

---

# Area G — Internationalisation groundwork

> **Last, and alone.** Do **not** write its stepped plan until D has landed — it sweeps a surface D is about to move.


**Tasks 47–55. Full+ — the largest area overall. Frontend only. English stays the only locale.**
**Run `ep-writing-plans` on this area first.**

**Goal:** Route every user-facing string through a translation layer, so adding a locale later is
data rather than surgery.

**Measured scope, not estimated:** 132 non-test `.tsx` files, ~41,700 lines, and 51
`satisfies Record<…>` maps in `lib/labels.ts` alone.

**Why last:** it sweeps every screen. Run it earlier and areas A–F each add untranslated strings
behind it.

**Scope boundary:** ship the **mechanism and the English catalogue only. Do not translate
anything.** A second locale is a separate slice and probably a paid one.

### Task 47: Choose the library and pin the decision

- [ ] No i18n dependency is installed today (`frontend/package.json` has none). Choose one, and
  **measure the bundle change before believing it** — the working agreement records a split that
  looked obviously right and made total JS ~40% worse.

### Task 48: Add the provider, minding the nesting order

**Files:** Modify `frontend/src/app/Providers.tsx`

- [ ] The nesting order in that file is **load-bearing and documented in it** — the query client is
  constructed innermost because its global error handlers consume the notification and
  session-recovery providers. Read the file's own comment before inserting anything.

### Task 49: Write the bare-literal check first

**Files:** Create `frontend/src/test/bareStrings.test.ts`

- [ ] A test that fails on a bare string literal in JSX, so the sweep cannot silently regress. Write
  it **before** the sweep, with an explicit allow-list that starts large and shrinks — that is what
  makes tasks 51–53 measurable instead of open-ended.

### Task 50: Move `lib/labels.ts` without losing its compile-time guarantee

**Files:** Modify `frontend/src/lib/labels.ts`

- [ ] All 51 maps use `satisfies Record<Union, string>`, which makes a new enum member **a compile
  error** until it is labelled. **Preserve that property through the move.** Losing it trades a
  compile-time guarantee for a runtime blank, which is the single most expensive mistake available
  in this area.

### Task 51: Sweep the shared layer

**Files:** `frontend/src/lib/`, `frontend/src/components/`

- [ ] Shared first: `DataRegion`, `Identifier`, the error boundaries and `describeError.ts`. Every
  page depends on these, so doing them first shrinks the per-page diffs.
- [ ] `lib/describeError.ts` is the one place an error becomes user-facing copy — keep it the one
  place.

### Task 52: Sweep the features, area by area

**Files:** `frontend/src/features/**`

- [ ] One commit per feature directory, shrinking the Task 49 allow-list as you go. Do not do this
  in one commit — a 132-file diff is unreviewable.

### Task 53: Empty the allow-list

- [ ] The Task 49 allow-list reaches zero, or every remaining entry has a written reason beside it.

### Task 54: Guard the two things an i18n pass reliably breaks

- [ ] **Dates and numbers are `lib/format.ts`'s job.** Do not let a second formatting path in
  through the i18n library's own formatters.
- [ ] **`lib/planScope.ts:78` warns against `localeCompare` for case keys.** An i18n pass is exactly
  when someone "fixes" that sort. **Do not.** Read the comment before touching it.

### Task 55: Close the area

- [ ] `test:run`, `build`, `lint` at 0 warnings; the bare-literal check green with an empty
  allow-list; `fe-react.md` seam entry.
- [ ] **A written note in the handoff that no locale was added**, so the next reader does not go
  looking for the translations.
- [ ] Handoff + index row; push.

---

---

## Self-review

**Coverage.** All nine gaps have an area. **Seven are shipped** (§242–§248); **two remain** — D
(cross-project search, stepped plan written) and G (i18n, deliberately unplanned until D lands).

**Order.** Held. The relative order survived the whole run unchanged, and the two dependency claims
it staked itself on both proved real: B genuinely inherited §242's two debts, and F genuinely needed
A's and B's controls before it could bind them.

**Size.** A's re-estimate from half a session to a quarter was correct — it shipped with no
migration. G remains the largest and is still measured (132 files / 41.7k lines / 51 label maps)
rather than guessed.

**Flyway.** `V68` (area C) was the only migration, exactly as re-assigned after A's was deleted.
Head is `V68`; neither remaining area migrates.

**Parallelism.** Concluded. It governed batches 1–3, its two staked calls held, and it no longer
applies: `D ∥ G` is ineligible, so the remainder is sequential under the standing one-subagent cap.

**The open risk, stated plainly.** **Five shipped areas have never been gated** — ~93 backend arms
and 32 frontend arms whose first execution is still ahead, one area never compiled. Everything above
describing those areas as "shipped" means *landed and pushed*, not *verified*. The gate pass is the
next action in this plan, ahead of D, and until it runs the tally at the top is a statement about
commits rather than about working software.

**Carried into D, so none is forgotten:**
- Gate the tree first — D's plan assumes a tree that compiles (its Task 14 is *"Go green, and gate"*).
- D's plan has three stale section/version references; its decisions are unaffected.
- RLS scopes to the **company**, not the project — the grant narrowing is entirely D's job, inside
  the query, and its test must assert on the **total count**, not just the page.
- File a row for C's missing `cli`/`mcp` verbs; closing it is not D's job.
- Re-measure the frontend test count; `CLAUDE.md`'s 1582 is stale by at least 43.
