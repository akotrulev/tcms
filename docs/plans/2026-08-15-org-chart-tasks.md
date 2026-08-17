# Org chart — the runnable task list

**Companion to** `docs/plans/2026-08-15-org-chart.md`. That file is the design; this one is the
execution order and the prompt to paste for each step.

**How to use this.** One task per session. Copy the prompt verbatim, paste it as your first message.
Every prompt is self-contained: it names the branch, the plan section the agent must read, the files
it may touch, the gate it must pass, and when to commit. **Run them in the status board's `Run`
order** — the `Deps` column is real, and CLAUDE.md's "work is sequential" rule means no two of these
run at once (T2 and T3 in particular share `backend/build/` and one Gradle daemon).

**The prompt sections below are in id order, not run order.** The board is the schedule; this half of
the file is the reference. Ids never renumber, because §11's verification rows and the plan's §10
quote them.

**Preamble — prepend to every prompt below.** It is written once here rather than repeated eleven
times; paste it above whichever task prompt you are running.

```
Repo: ~/RiderProjects/tcms
Branch: worktree-org-chart-plan (git worktree at .claude/worktrees/org-chart-plan)

Read CLAUDE.md FIRST — it is binding. Then read the orientation cache for the stack you
are touching (docs/agent-context/be-java.md or fe-react.md) before the handoff narrative
and before any raw DDL. Trust the cache's BODY over its Baseline commit header.

Then read docs/plans/2026-08-15-org-chart.md — the whole of §0-§4 for context, plus the
sections this task names.

Rules for this session:
- Work on the current branch. Do not create a new one.
- Other sessions may be live in the main checkout. Stage ONLY the files this task names
  (git add <paths>, never git add -A). Re-read a shared doc before editing it.
- Commit at the END of the phase, after the gate passes — not before, not once at the end
  of everything.
- Do NOT run browser passes or backend mutation checks (§65 — deferred and batched).
  Record the rows instead; T11 appends them.
- If you find a real problem with the task as specified, say so in a sentence or two, then
  deliver the task under a stated assumption. Do not silently rescope.
```

---

## Status board

**Rows are in RUN ORDER; the ids are the plan's and never renumber.** A prompt below says "Task T6",
the plan's §10 says T6, and the verification rows quote ids the same way — so the board sorts, it
does not renumber. Read the `Run` column top to bottom.

| Run | # | Task | Agent | Deps | State |
|:--:|---|---|---|---|---|
| 1 | **T6** | Extract `teamTree.ts` | `fe-react` | — | ☑ `3f100b1` |
| 2 | **T0** | Decision gate — answer Q1–Q3 **and Q5** | *(you)* | — | ☑ plan §14, all four recommendations taken |
| 3 | **T1** | Contract + both generated schema copies | `be-java` | T0 | ☑ `2b3546a` (with T0) |
| 4 | **T2** | Repository generalisation + §A1.1 helper | `be-java` | T1 | ☑ `ba5ed64` |
| 5 | **T3** | `OrgChartService` + controller | `be-java` | T2 | ☑ `1ecd0db` |
| 6 | **T4** | Backend tests | `qa-api-java` | T3 | ☑ `d5b9b89` |
| 7 | **T7** | `useOrgChart` + invalidations | `fe-react` | T1, T6 | ☑ `c98b49a` |
| 8 | **T8** | The screen | `fe-react` | T7 | ☑ `bd420eb` |
| — | **T5** | Query-plan review | `data-architect` | T2 | ☐ **not run** |
| — | **T9** | Disclosure-boundary security pass | `qa-security` | T4 | ☐ **not run** |
| — | **T10** | Manual test-case suite | `qa-test-case-designer` | T4, T8 | ☐ **not run** |
| last | **T11** | The record — handoff, caches, queues | *(last lane)* | T8 | ☑ `64f9814` — handoff §241 |

**Executed 2026-08-15/16 — and NOT on `worktree-org-chart-plan`.** All eight code lanes plus T11
landed **on `master`** (`3f100b1`…`64f9814`, in the `Run` order above); the branch every prompt's
preamble names is still at `5149e58`, the two plan commits alone. If you are reading a prompt below
and about to check out that worktree, don't — the work is on `master` and a second implementation
from that branch would re-add every file.

**T11 ran with T5, T9 and T10 outstanding**, which is exactly what the note below says not to do.
The handoff section records it rather than hiding it: §241 states *"T5, T9 and T10 were not run"* and
why (report-only lanes; T9 needs a provisioned instance over real HTTP). So the three are still
runnable, but their findings now need a **follow-up** record — an amendment to §241 or a section of
their own — because the handoff that would have carried them is already written and index rows are
never renumbered.

**T6 runs first, ahead of even the decision gate.** It is independent of T0–T5, it is the only task
that touches a shipped screen, and landing it alone means a regression in `/admin/teams` cannot be
confused with the new feature's fault. It also unblocks nothing else, so running it while Q1–Q3 are
still open costs nothing.

**The three dashed rows are off the critical path** and produce reports, not code — `T5` needs only
T2, `T9` needs only T4, `T10` needs T4 and T8. Slot each one in as soon as its deps land, or batch
all three before T11. **T11 is genuinely last**: if T9 or T10 has been run, its findings belong in
the handoff section T11 writes, so do not run T11 while either is outstanding.

**Nothing here runs concurrently** (CLAUDE.md, "work is sequential"). T2/T3/T4 in particular share
one `backend/build/` and one Gradle daemon, and T6/T7/T8 share `frontend/`.

---

## T0 — Decision gate *(you, not an agent)*

Answer §14's Q1, Q2, Q3 **and Q5**. Nothing below starts — except T6, which is independent — until
they are answered or explicitly deferred with an owner. Write the answers into §14 as a decision,
dated, so the next reader sees a decision rather than an open question.

- **Q1** — role *names* on a visible project: every reader of that project, or only `project:members`
  holders? *(plan recommends: every reader)*
- **Q2** — is `companyRoles` ("this person is a Company Admin") visible to every member?
  *(recommends: yes)*
- **Q3** — does SYSTEM scope / Instance Admin appear at all? *(recommends: no)*
- **Q5** — does the new path get an `operationId`? *(recommends: no, matching the identity family —
  none of its paths has one.)* §14 lists this as blocking T1 and the first version of this file
  omitted it; it is low-stakes and engineering's call if you would rather not decide it.

**Q4 is a defer-with-owners and blocks nothing** — it needs no answer before T1.

**Every prompt below assumes the recommended answers.** If you decide otherwise, say so in the
prompt — each one has a line for it.

---

## T1 — Contract

```
Task T1 from docs/plans/2026-08-15-org-chart.md. Read §5 in full (§5.1 for why this is a
new path rather than an extension of GET /users, §5.2 for the exact shapes).

Add to docs/specs/openapi.yaml:
- GET /api/v1/org-chart
- schemas OrgChart, OrgChartPerson, OrgChartProject, OrgChartAssignment

NO existing schema changes. OrgChart.teams reuses the EXISTING Team schema unchanged, and
User.teams stays string[] — widening a shipped shape for a screen that can have its own is
the breaking change §5.2 refuses.

Then regenerate BOTH copies of the generated types:
  cd frontend && npm run gen:api       # -> frontend/src/api/schema.d.ts
  cd packages/client && npm run gen:api  # -> packages/client/src/schema.ts
NOTE THE SECOND FILENAME: the packages copy is `src/schema.ts`, NOT `schema.d.ts` — the
first version of this file named a path that does not exist. Their contents are compared by
schemaFreshness.test.ts; run the generators rather than hand-copying either.

Also fix the false prose in openapi.yaml (plan §13 F1). It is in `CurrentUser.teams` and
reads "GET /teams is gated on company:teams, so the org chart is not readable to the person
in it" — found at :7182 on master at 2026-08-15, but LINE NUMBERS DRIFT, so grep for
"gated on `company:teams`" rather than trusting either number. It is gated on company:read
(AdminDirectoryService.java:249 via readsCompany(), TeamController.java:61-64). Verify that
in the source before you rewrite the sentence. Do NOT extend tierClaims.test.ts to catch this
phrasing — the plan flags that as its own slice. Do NOT delete the field: plan §13 F2 records
why it survives the repair.

Q1-Q3 and Q5 answered as: RECOMMENDED (Q1 every reader / Q2 yes / Q3 no / Q5 no operationId).
  ^ change this line if you decided otherwise.

Every new schema needs its `required` array, INCLUDING on inline nested objects — `required`
does not descend, and without it every generated property is optional and no consumer can
use it (CLAUDE.md working agreement).

Gate: `npm run build` in frontend/ AND `npm run build` in packages/ (the ordered script, not
--workspaces), plus schemaFreshness.test.ts green.
Files: docs/specs/openapi.yaml, frontend/src/api/schema.d.ts, packages/client/src/schema.ts
Commit when the gate passes.
```

---

## T2 — Repository generalisation and the §A1.1 helper

```
Task T2 from docs/plans/2026-08-15-org-chart.md. Read §8.2 (two queries, not N×M) and §8.3
(the override this must not implement a fourth time). Load the be-java-database-code skill.

Three things, one commit:
1. Generalise RoleAssignmentRepository.findProjectRoleNames (:240) and
   findInheritedRoleNames (:270) to take an id LIST, and add `via` + `teamId` to what they
   return. They are already whole-company per user; an IN-list makes the whole matrix ~3
   queries instead of N×M.
2. Extract the §A1.1 display override into ONE helper in com.tcms.identity. It is currently
   implemented three times — PermissionEvaluator.projectTerm:189-193, ProjectService.java:586,
   and frontend roleAccess.ts (which admits it at :26-29). This must not become a fourth.
3. Point ProjectService at the same generalised queries.

THE RISK IS #3: Project.myRole is a caller-facing field on a shipped screen. The gate is that
every existing myRole assertion passes UNCHANGED — do not edit a test to make it pass. If one
fails, that is a real behaviour change and you should stop and report it rather than adapt the
assertion.

Repositories need @Transactional(readOnly = true) on the INTERFACE — see CLAUDE.md's tenancy
section for why (isolation is established in doBegin; without it RLS returns zero rows while
CompanyContext holds the right company). RepositoryTransactionTest derives its list from the
live context.

Gate: ./gradlew build in backend/ (needs Docker — Testcontainers). ~/bin/tcms-remote backend
./gradlew build is the Linux-shaped alternative.
Files: RoleAssignmentRepository.java, ProjectService.java, the new helper + its test
Commit when the gate passes.
```

---

## T3 — The service and controller

```
Task T3 from docs/plans/2026-08-15-org-chart.md. Read §4.4 (the redaction table — this is the
specification, row by row), §8.1 and §8.4.

Add OrgChartService + OrgChartController to com.tcms.identity.

- Gate on company:read (NOT company:members). Every member reads this chart; §84 recorded
  that inventing a key for it would be "a 403 no administrator could grant their way out of".
- Follow the module's existing convention: answer an EMPTY DOCUMENT when the gate fails, the
  way the four admin reads answer 200 [] (AdminController.java:44-53) — not 403, not 404.
- Project axis from AdminReach.visibleProjects. A project the caller cannot open contributes
  no column, NO CELL, and NO COUNT. A count is a name-free existence claim about the company's
  portfolio and §4.3 Option D rejects it.
- lastLoginAt stays null without company:members (UserView.java:21-25).
- companyRoles on the person; NO grant ids on this wire — revocability belongs to
  GET /role-assignments and stays there.
- Reuse AdminDirectoryService's peopleById() and listTeams(). Do not open a second
  authorization-resolution path beside the evaluator — that is the bug class plan §6b names.

Tenancy (CLAUDE.md): company_id on everything, RLS is the backstop, repository interfaces
carry @Transactional(readOnly = true).

Gate: ./gradlew build — Modulith and ArchUnit green, and no new record repeating an api shape
(noRecordOutsideApiRepeatsAnApiShape).
Files: new OrgChartService.java + OrgChartController.java in backend/src/main/java/com/tcms/identity/
Commit when the gate passes.
```

---

## T4 — Backend tests

```
Task T4 from docs/plans/2026-08-15-org-chart.md. Read §4.4 and §8. Use the qa-api-java lane.

Write, in backend/src/test/java/com/tcms/identity/:
1. The §8.3 agreement test, BOTH directions — the extracted override helper and the
   evaluator must agree.
2. One arm per ROW of the §4.4 redaction table. Every row gets a named test.
3. The tenancy/RLS arm, covering BOTH spellings of an unset GUC — never-set reads NULL, but
   after set_config has run on a pooled session the reset value is '' (see CLAUDE.md; V4
   wraps the read in nullif(...,'') for exactly this). Both must yield zero rows.
4. A `via`-per-arm fixture: direct grant, through a team, inherited company-wide, and a
   Company Admin who has no cell at all.
5. Contract-shape tests.

THE LOAD-BEARING ONE: the agreement test must FAIL when a cell is served for a project the
viewer cannot open. Verify that by breaking it deliberately and watching it go red, then
revert — a test that cannot fail is not coverage (this repo has paid for that lesson
repeatedly; see §162 on ignore lists that name the wrong mechanism).

Pre-assign a fixture UUID prefix that no other lane is using, and say which one you took —
prefix collisions have happened five times here (§19…§62) and merge clean before failing at
runtime on project_pkey.

Gate: ./gradlew build.
Commit when the gate passes.
```

---

## T5 — Query-plan review *(independent of T3/T4; needs only T2)*

```
Task T5 from docs/plans/2026-08-15-org-chart.md §6 and §8.2. Use the data-architect agent with
the data-postgresql skill.

T2 introduced two IN-list queries over role_assignment. Review their plans against the
existing role_assignment_user_idx / role_assignment_team_idx on a realistically-sized company
(state the row counts you assumed or generated).

Deliverable is a WRITTEN FINDING appended to §6 of the plan file — either "an index is wanted,
here is the DDL and the EXPLAIN that justifies it" or "none is needed, here is the EXPLAIN
that shows why". Measurements, not opinion.

WRITE NO MIGRATION. If an index is wanted, the Flyway version is reserved at INTEGRATION in
landing order, not chosen now — out-of-order is unset, so a V53 landing after a V54 is refused
on any already-migrated database, and Testcontainers cannot see this because it migrates a
fresh container every time. Flyway head is currently V52; confirm that before you write.

Files: docs/plans/2026-08-15-org-chart.md (§6 only)
```

---

## T6 — Extract `teamTree.ts` *(RUN ORDER 1 — first of everything, including T0)*

```
Task T6 from docs/plans/2026-08-15-org-chart.md §9.3.

Extract the tree flattening from frontend/src/features/admin/TeamsPage.tsx into
frontend/src/features/admin/teamTree.ts, and have TeamsPage call it. It is the `useMemo`
inside the `TeamTree` component — :378-408 on master at 2026-08-15, under the block comment
at :365-377 — but LINE NUMBERS DRIFT, so find it by the `reachesARoot` helper rather than by
number.

BEHAVIOUR-PRESERVING, its own commit, nothing else in this session.

Preserve the orphan-and-cycle rule exactly: a team whose parent is missing, or two teams
pointing at each other, are surfaced AT THE ROOT rather than silently dropped. The existing
comment explains why — "a team the tree does not draw is a team nobody can fix, and the state
that most needs fixing is exactly the one that hides." Keep that reasoning with the code.
Write tests for the orphan and cycle cases specifically.

Gate: npm run build + npm run lint (0 warnings, it is a gate not advisory) + npm run test:run,
all in frontend/. EVERY existing TeamsPage test must pass UNCHANGED — if one needs editing,
the extraction changed behaviour and you should stop and report rather than adapt the test.

Use createTestQueryClient from src/test/ for any new test — never build your own QueryClient;
only that helper's has the caches the failure checks read, and renderFailures.test.tsx sweeps
for hand-built ones.

Commit when the gate passes.
```

---

## T7 — Query layer

```
Task T7 from docs/plans/2026-08-15-org-chart.md §9.5.

In frontend/src/api/queries/:
- useOrgChart() in admin.ts
- keys.orgChart = ['org-chart'] in keys.ts, beside keys.teams / keys.users / keys.roleAssignments
- the types in api/types.ts

THE DEFECT TO AVOID: add keys.orgChart to ALL NINE staffing-write invalidations —
useGrantRole, useRevokeRole, usePutTeamMember, useRemoveTeamMember, useCreateTeam,
useUpdateTeam, useDeleteTeam, useInviteUser, useRemoveUser. Each already invalidates two or
three keys (admin.ts:61,87,110-111,139-140,159-160,224,251-253,286-287,307-308). Missing one
leaves the chart stale in exactly the moment somebody just restaffed a project — which is the
moment they opened it.

Write a test asserting each of the nine invalidates orgChart. Nine arms, not one.

TanStack Query owns all server state; no server data in any client store (CLAUDE.md).

Gate: npm run build + npm run lint + npm run test:run in frontend/.
Commit when the gate passes.
```

---

## T8 — The screen

```
Task T8 from docs/plans/2026-08-15-org-chart.md. Read §9 in full, and §4.4 for what may be
displayed.

Build in frontend/src/features/org-chart/:
- OrgChartPage — one query, three regions, a DataRegion each so one failure leaves the others
  usable
- OrgChartMatrix — people × visible projects, MUI X DataGrid with dynamic columns
- OrgChartTeams — the team layer, indented, via teamTree.ts from T6 (do NOT re-implement it)
- OrgChartGaps — §6k's vacancies: people in no team, teams with no lead, people with no cell.
  Pure presentation over the document already served: NO second query, NO second
  authorization path.

Route /org-chart in app/router.tsx — beside /admin/teams, NOT beneath it (§9.1: the chart is
every member's, the admin screen is not). Rail entry under Company in AppShell.tsx.

§9.4 IS NOT OPTIONAL: the panel carries "Only projects you can open are shown here."
rendered UNCONDITIONALLY, for every reader including a Company Admin who sees everything.
Rendering it only when something is hidden makes its presence the existence claim §4.3
rejected. A reader missing six of eight projects otherwise gets a two-column grid that looks
complete.

Shared layer, not the page (CLAUDE.md): the `via` enum goes in lib/labels.ts with
`satisfies Record<AssignmentVia, string>` so a fourth contract member is a compile error until
labelled — DIRECT "Direct grant", TEAM "Through a team", INHERITED "Company-wide default".
Identifier for project keys and ids. lib/format.ts formatCount. DataRegion for the state
matrix. Do NOT use roleAccess.ts (§7.2) and do NOT reach for lib/permissions.ts (this screen
gates nothing).

MEASURE THE BUNDLE before and after and record the delta — the working agreement's own lesson
is that splitting DataGrid into its own chunk looked obviously right and made total JS ~40%
worse by defeating tree-shaking.

Gate: npm run build + npm run lint (0 warnings) + npm run test:run in frontend/.
Tests use createTestQueryClient and src/test/fixtures.ts — never a hand-built QueryClient.
Commit when the gate passes.
```

---

## T9 — Disclosure boundary *(the one that matters most)*

```
Task T9 from docs/plans/2026-08-15-org-chart.md §4.4 and §15. Use the qa-security agent.

Prove the disclosure boundary IS the boundary. Authorized security testing of this project's
own feature, driven over real HTTP against a locally provisioned instance.

As a member holding project:read on ONE project of several:
- confirm no OTHER project's id, name, key or count reaches the wire
- confirm lastLoginAt is null
- confirm no cross-company leakage of any kind
- BOLA/IDOR-shaped probing of GET /api/v1/org-chart

ASSERT ON THE RESPONSE BODY, NOT THE UI. Every cell marked "absent" in the §4.4 table must be
proven absent in the JSON. A field the server sends and the client merely declines to render
is the defect this task exists to catch, and it is invisible to a UI-level test.

Report findings; fix nothing.
```

---

## T10 — Manual test cases

```
Task T10 from docs/plans/2026-08-15-org-chart.md §4.4 and §5.2. Use the qa-test-case-designer
agent.

Derive a manual test-case suite for the org chart. Cover each VIEWER TIER (plain member,
Test Engineer, Test Lead, Company Admin) crossed with each `via` arm (direct grant, through a
team, inherited company-wide), plus the §9.4 standing line and the OrgChartGaps regions.

The interesting cases are the negative ones: two colleagues opening the same chart and
correctly seeing DIFFERENT matrices is the design, not a bug, and the suite must say so
explicitly or someone will file it.

Shared Setup, always a Cleanup. Save under docs/ per the agent's own convention.
```

---

## T11 — The record

```
Task T11 from docs/plans/2026-08-15-org-chart.md §11.

Close the slice out per CLAUDE.md:
1. Write docs/handoff/NN-<slug>.md — take NN from the current highest section number, and
   FINISHING A SLICE MEANS ADDING A FILE AND ONE INDEX ROW, never appending to HANDOFF.md.
2. Add the one index row to docs/HANDOFF.md.
3. Update BOTH orientation caches — docs/agent-context/be-java.md and fe-react.md — in this
   commit. Record what the code IS, not what the slice did.
4. Append §11's verification rows to docs/verification/frontend-browser-checks.md and
   backend-mutation-checks.md.

THE ROW IDS IN §11 ARE PROVISIONAL. Ids in those files are quoted from handoff sections and
are NEVER renumbered, so take the real ids from the current high-water mark at append time.
Measured on master at 2026-08-15: L1 tops out at L1-22, A0 at A0-16 — so §11's rows would be
L1-23..L1-26 and A0-17/A0-18 if nothing else lands first. RE-MEASURE ANYWAY: two other
worktrees (worktree-notifications, worktree-m6-multi-company) are live and either may append
to these lanes before this slice merges. Persona rows go in the L1 permission lane; the
"does it render at all" rows go in A0.

Also run T9 and T10 BEFORE this task if they are going to be run at all — this section is
where their findings are recorded, and a handoff written ahead of them cannot mention them.

Record the rows; RUN NOTHING (§65 — deferred and batched, and "deferred means ALL of them").
A slice in an unrun queue is BUILT AND UNVERIFIED and must be described that way, not as done.
```

---

## Notes

- **Gates that need Docker:** T2, T3, T4 (`./gradlew build` boots real Postgres via
  Testcontainers). `~/bin/tcms-remote backend ./gradlew build` runs them on `shanks` instead —
  same Linux shape as `tests.yml`, so it catches the microsecond-vs-nanosecond class of defect that
  cannot fail on macOS.
- **CI runs no backend tests.** `ci.yml` is `./gradlew build -x test`. Between your push and 01:17
  UTC nothing downstream runs a backend test, so the local gate is the only one.
- **Pushing this branch gates nothing.** `ci.yml` triggers on push to `master` and on PRs targeting
  it; `tests.yml` is nightly on the default branch. A pushed worktree branch is *backed up*, not
  *observed*.
- **Three worktrees are live** (verified 2026-08-15): `org-chart-plan` (this one, at `5149e58` — the
  two plan commits and nothing executed yet), plus `notifications` and `m6-multi-company`. That is
  why every prompt says `git add <paths>` and never `git add -A`, and why T11 re-measures the
  verification lanes rather than trusting the numbers written here.
- **Flyway head is `V52`** and this slice adds no migration (plan §6). If T5 recommends an index, its
  version is reserved at integration in landing order — `out-of-order` is unset, and Testcontainers
  cannot see the failure because it migrates a fresh container every time.
- **Citations in these prompts carry line numbers that drift.** Four had already moved between the
  plan being written and this pass (the `openapi.yaml` prose, the `TeamsPage` block, and the
  `packages/client` schema filename, which was wrong rather than stale). Grep for the named symbol;
  treat a number as a hint.
