# Staffing Implementation Plan — plan 009 module M7

**Date:** 2026-09-04 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Project Manager allocates a person to two projects at percentages, the over-allocation
flag appears at 101%, an absence zeroes that period's capacity on every screen that shows it, and
"who is free next month with skill X" is answerable from the directory.

**Architecture:** A new `com.tcms.staffing` Spring Modulith module over five tables — `person_profile`,
`skill`, `person_skill`, `allocation`, `absence` — behind `V10`. Capacity is **derived, never
stored**: `weekly_hours` spread across the working days of a range, each day reduced by that day's
absence fraction. Allocation is a percentage of that capacity over a date range, and over-allocation
is the sum of overlapping percentages **on a day**, not on a month. The module reads `time`'s
approved hours through one read-only native-SQL class (`TimeFacts`), the fifth instance of a pattern
this codebase already names, so utilisation is answerable without a second writer of anybody's
projection.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Spring Modulith · Flyway (`V10`) · Postgres 17 with
FORCE RLS · React 19 + TanStack Query + Tailwind v4 render kit · `@tcms/client` / `@tcms/cli` /
`@tcms/mcp`.

**Tasks** — in the order they are done:

| # | Task | Stack | Agent | Deliverable | Est. | Depends on |
|---|---|---|---|---|---|---|
| 1 | T1 — The staffing contract, both clients, and the CLI/MCP verbs | `ts` | `tooling-engineer` | **Landed 2026-09-04.** ~~17~~ **15** `staffing` operations and 17 schemas in `openapi.yaml`, both generated clients regenerated, a factory per resource in both `fixtures.ts` (~~`contractBodies.ts`~~ — those are the schema walker and hold no bodies), ~~three~~ **four** CLI verbs and three MCP tools, and the surface doc listing them | ~140k | none |
| 2 | T2 — `V10`: the five staffing tables | `be` | `data-architect` | **Landed 2026-09-04.** `V10__staffing.sql` applied on an empty database (Testcontainers) **and on a V9 one** (95 → 100 policies, seed functions untouched); the five tables exist with `FORCE` RLS and V1's predicate; `SchemaConventionTest` green at `100`. **The V the directory gave was `V10`, as predicted** — the first time in five that column was right | ~95k | T1 |
| 3 | T3 — The `staffing` module: capacity, over-allocation, absence and utilisation | `be` | `be-java` | Every operation T1 declared is served; one person allocated 50%+51% across two projects raises the over-allocation flag; an absence zeroes those days' capacity in the one function every caller uses; `./gradlew build` green | ~150k | T2 |
| 4 | T4 — The company screens: `/staffing` and `/directory` | `fe` | `fe-react` | `/staffing` renders the allocation timeline, the Over-100% panel and free capacity; `/directory` answers "free next month with skill X" through URL-held filters; both gated on `staffing:directory` | ~140k | T3 |
| 5 | T5 — The project's staffing, and the person page carrying profile, skills and absence | `fe` | `fe-react` | `/projects/:projectId/staffing` lists this project's allocations and allocates a person at a percentage for a range; `/directory/:userId` carries `member.html`'s Profile, Skills and Absence panels | ~135k | T4 |
| 6 | T6 — The two capacity figures M6 left absent, and the close-out | `fe` | `fe-react` | `/timesheets` shows four KPI tiles including `Period capacity`; My Work's This-period panel shows Capacity beside Logged with the meter under it; `docs/features.md`, the MCP surface doc and plan 009's status table are closed | ~110k | T5 |

---

The six blocks below are what is pasted into six fresh sessions, in order — **at the top,
because that is what a reader opening this file has come for.** Each is complete on its own:
what to read and in what order, the one deliverable, the traps that specific task will hit, the
gate command and what to commit. The reference material every block cites — the decisions, the
file structure and the task bodies with their code — is below them.

**They are living text.** A task that lands something different from what this plan predicted
rewrites the blocks below its own before it stops, in place, with no changelog appended.

## Prompts — one per task

### Prompt — T1 — The staffing contract, both clients, and the CLI/MCP verbs

```
You are `tooling-engineer` working in the tcms repo. Read CLAUDE.md first.

Read, in this order: docs/plans/017-staffing.md §T1 and its "Decisions this plan takes" · CLAUDE.md ·
docs/specs/openapi.yaml lines 8857-9260 (the /timesheets and /time blocks, as the pattern) ·
packages/client/src/test/fixtures.ts around `timesheetPeriod` (the factory shape, which is where the
bodies live — NOT contractBodies.ts) · packages/cli/src/reads.ts around `timesheet` ·
packages/mcp/src/reads.ts around `tcms_read_timesheet` ·
docs/specs/2026-07-30-tcms-roles-permissions-naming.md §A3's two Staffing tables.

**This task LANDED on 2026-09-04.** The block is kept because the next reader of this plan is
looking for what the contract is, and it is rewritten to what was actually done rather than to what
was predicted — §T1 carries the five corrections in full.

Deliver: ~~17~~ **15** `staffing`-tagged operations over twelve path items in docs/specs/openapi.yaml
and **17 schemas**, each carrying its `required` array; frontend/src/api/schema.d.ts and
packages/client/src/schema.ts regenerated from it; a factory per new resource in BOTH
`src/test/fixtures.ts` copies; ~~three~~ **four** CLI verbs and three MCP tools that call the new
operations; and docs/specs/2026-08-08-mcp-tool-and-command-surface.md listing all seven.

Traps this task will hit:
- `required` does NOT descend. Every inline nested object needs its own array, and a schema without
  one makes every generated property optional — which typechecks and is wrong everywhere.
- ~~The contractBodies shared region is compared BYTE-FOR-BYTE — paste, do not retype.~~ →
  **it needs no change at all, and 016 T1 recorded the same finding one plan earlier.** Both
  `contractBodies.ts` copies are the schema WALKER; the marked region is `violationsOf` and its
  helpers and holds no response bodies. The bodies are factories in `frontend/src/test/fixtures.ts`
  and `packages/client/src/test/fixtures.ts`, which are NOT byte-identical to each other — the
  frontend spells `aPersonCapacity(overrides): PersonCapacity` against a named type and the package
  spells `personCapacity(overrides): Schemas['PersonCapacity']`.
- There are still TWO generated clients. `npm run gen:api` runs in `frontend/` and again in
  `packages/client/`; `schemaFreshness.test.ts` compares the two and fails on a half-done pass.
- `staffing:directory`, `staffing:profile` and `staffing:absence` are COMPANY scope; `staffing:read`
  and `staffing:allocate` are PROJECT scope. Say so in each operation's description. Getting this
  backwards produces a permission row the evaluator can never reach (roles spec §43). It also
  decides the tool gates: `tcms_read_staffing` is `any` of the two READ keys because neither role
  that asks holds the other's.
- `declaredPermissionsOf` reads the gate out of the operation's **prose**, matching
  ``Requires `x:y` `` — so a write operation whose description does not spell it that way silently
  asserts nothing in both `writeConformance.test.ts` files.
- Nothing on this surface returns a rate or a cost. Say that in the MCP tool descriptions, as
  `tcms_read_timesheet` does.

When done: run `~/bin/tcms-remote sync` then `~/bin/tcms-remote packages npm run build`,
`~/bin/tcms-remote packages npm run test:run --workspaces` and
`~/bin/tcms-remote frontend npm run build`. Commit exactly the paths in §T1 step 11 as
`feat(staffing): the staffing contract, both clients, and the CLI/MCP verbs`. Write the record back
into §T1 of the plan, and rewrite the prompt blocks below yours if the operation names, paths or
schema shapes you landed differ from what they assume.
```

### Prompt — T2 — `V10`: the five staffing tables

```
You are `data-architect` working in the tcms repo. Read CLAUDE.md first, then load
`data-postgresql` and `data-migrations`.

Read, in this order: docs/plans/017-staffing.md §T2 and D-a through D-e · CLAUDE.md ·
backend/src/main/resources/db/migration/V9__time.sql IN FULL (the header, the per-table COMMENT ON,
and the RLS DO $$ block with its count assertion are the house style) ·
docs/specs/2026-07-30-tcms-erd.md §0.1 · docs/specs/tenancy-invariants.md.

Deliver: backend/src/main/resources/db/migration/V10__staffing.sql applied — on an empty database and
on the lab's already-migrated V9 one — creating person_profile, skill, person_skill, allocation and
absence, each with company_id UUID NOT NULL leading every composite index and each carrying a FORCE
RLS policy whose predicate is byte-identical to V1's; SchemaConventionTest green.

Traps this task will hit:
- `ls backend/src/main/resources/db/migration/` BEFORE writing a line. The plan says V10 and the
  directory is the authority — that column has been wrong five times in five weeks. `out-of-order`
  is unset, so a reused number is silently not applied and Testcontainers cannot show you.
- The RLS predicate must match V1's byte for byte. An added explicit WITH CHECK is a difference and
  SchemaConventionTest fails on it — a USING-only policy already reuses USING as the insert check.
- person_skill is a JOIN TABLE and still needs company_id. V1 shipped five without it and the
  omission was found by scripting the comparison, not by reading.
- Do NOT touch tcms_seed_project_defaults. CREATE OR REPLACE FUNCTION replaces the whole body, so
  reproducing it is the only way this file could break it.
- There is deliberately NO EXCLUDE USING gist on allocation — the reason is in the plan's D-n and in
  the migration's own comment. Do not add one.

When done: run `~/bin/tcms-remote sync` then
`~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest'`, then the
full `~/bin/tcms-remote backend ./gradlew build` — read docs/specs/shanks-runner-details.md first,
because BUILD FAILED with "Could not write XML test results" and zero assertion failures is an fd
limit, not a red suite. Commit the migration, SchemaConventionTest and the ERD as
`feat(staffing): V10 — person_profile, skill, person_skill, allocation, absence`. Write the V you
actually took back into §T2, this plan's header and plan 009's table, and rewrite the prompt blocks
below yours if it is not V10.
```

### Prompt — T3 — The `staffing` module

```
You are `be-java` working in the tcms repo. Read CLAUDE.md first, then load `be-java-database-code`
and `be-java-unit-testing`.

Read, in this order: docs/plans/017-staffing.md §T3 and D-a, D-c, D-e, D-f, D-h, D-i, D-j, D-k, D-l ·
CLAUDE.md · backend/src/main/java/com/tcms/time/ IN FULL — package-info.java, IssueFacts.java,
TimeAccess.java, TimesheetController.java, TimesheetService.java are the five patterns this task
copies · backend/src/main/java/com/tcms/project/ProjectService.java · GateResult.java ·
docs/specs/tenancy-invariants.md · docs/specs/2026-07-30-tcms-roles-permissions-naming.md §A3.

Deliver: every operation T1 declared served by com.tcms.staffing; allocating one person to two
projects at 50% and 51% over overlapping days raises overAllocated with peakPercent 101; an absence
reduces capacityHours in Capacity.of, which is the single function all four capacity surfaces read;
GET /staffing/people?skill=&freeFrom= answers "who is free next month with skill X" in one query;
./gradlew build green with ArchUnit and StaffingReadsNoTimeEntryTest passing.

Traps this task will hit:
- T1 landed FIFTEEN operations, not seventeen — seventeen is the schema count. The twelve path items
  and the exact shapes are in openapi.yaml, which is the authority; §T1's landed note lists the four
  places the plan's draft YAML and the shipped contract differ.
- POST /projects/{projectId}/allocations declares Idempotency-Key and a 200 replay; POST
  /staffing/skills and POST /staffing/people/{userId}/absences deliberately do NOT, because the
  dedupe scope is (company, project, key) and neither is addressed to a project. Do not invent a
  company-scope idempotency scope to close that — the shortfall is recorded on both agent surfaces
  with what it costs, and a duplicate absence row double-counts nothing (capacity is per DAY).
- EVERY repository interface carries @Transactional(readOnly = true) ON THE INTERFACE. Spring Data
  gives a transaction only to inherited CRUD methods, so a derived or @Query method otherwise runs
  with no company set and every RLS-protected read returns ZERO ROWS — fail-closed and silent.
- Company context comes from the session, never from a parameter, header or path segment.
- staffing may NOT import com.tcms.time. The crossing is TimeFacts, native read-only SQL, and it is
  the only file in the package allowed to name time_entry or timesheet_period. ArchUnit cannot see
  this — StaffingReadsNoTimeEntryTest reads the source text, and you write it.
- Over-allocation is a DAILY peak, not a monthly sum. 100% for 1-15 Sep and 100% for 16-30 Sep is
  NOT over-allocation. Write CapacityTest first; it is pure JUnit with no Spring and no database.
- Recording your OWN absence needs no staffing key (D-h) — `staffing:absence` covers doing it on
  somebody else's behalf, which is what the catalogue text says.
- Do not grant a COMPANY-scope key at project scope or the reverse. Use ProjectService.holds for
  staffing:read/allocate and the new readInCompany/holdsInCompany for the three COMPANY keys.
- Seed person_profile, skills, allocations and one half-day absence in DemoWorkspaceSeeder, or every
  capacity figure in the demo instance is an em dash and the module cannot be demonstrated.
- V10 LANDED AND IT IS V10 — no renumbering. Two things in it will bite the entity layer. FIRST:
  skill.name is CITEXT, which reports as Types#OTHER while a String maps to varchar, so
  `ddl-auto: validate` REFUSES TO START with "found [citext (Types#OTHER)], but expecting
  [varchar(255)]" unless the field says @Column(columnDefinition = "citext"). AppUser.email and
  CompanyEmailDomain.domain both carry it; a native query comparing to a parameter casts it, as
  AppUserRepository's `CAST(:email AS citext)` does. This is a startup failure, not a compile one,
  so it appears as every @SpringBootTest failing at once. SECOND: there is NO ix_person_profile_user
  — uq_person_profile UNIQUE (company_id, user_id) is itself that index, and the profile lookup
  seeks it.
- person_profile has NO row for most people and that is a real state, not a default of 40 — every
  hours figure for them is null and the screen reads an em dash (016 D-j's refusal, kept).
- The schema gate is `com.tcms.tenancy.SchemaConventionTest` (package `tenancy`, not `schema`), and
  it asserts a policy COUNT — now 100. A sixth staffing table would move it again.
- THE BACKEND BUILD IS ALREADY RED WHEN YOU START, at exactly four assertions, and closing them is
  your deliverable rather than a surprise: ContractConventionTest's two pairing checks,
  ContractEnumTest.everyNamedContractEnumNamesTheServerType and
  ContractParameterBindingTest.everyOperationIsServedByAHandler. T1 added 17 schemas and 15
  operations to openapi.yaml with no record and no handler behind them, and no gate between T1 and
  you could see it — T1's was `packages npm run build`, ci.yml compiles the backend without testing
  it, and tests.yml is nightly. Verified at fe305a88 by stashing T2's work. A FIFTH failure is a
  regression you introduced; these four are the job.

When done: run `~/bin/tcms-remote sync` then `~/bin/tcms-remote backend ./gradlew build` — read
docs/specs/shanks-runner-details.md first. Commit the paths in §T3 step 11 as
`feat(staffing): the staffing module, the capacity function, and utilisation`. Write the record back
into §T3, and rewrite the prompt blocks below yours if the response field names you landed differ
from T1's contract.
```

### Prompt — T4 — The company screens

```
You are `fe-react` working in the tcms repo. Read CLAUDE.md first, then load `fe-react-data-fetching`,
`fe-react-ui-rules` and `fe-react-component-testing`.

Read, in this order: docs/plans/017-staffing.md §T4 and D-e, D-f, D-g, D-i · CLAUDE.md ·
docs/mockups/staffing.html and docs/mockups/directory.html IN FULL — the mockups are the source of
truth for what these screens show · docs/agent-context/fe-react.md (your orientation cache) ·
frontend/src/features/time/TimesheetPage.tsx and ApprovalsPage.tsx as the most recent screens on the
kit · frontend/src/api/queries/time.ts · frontend/src/api/queries/keys.ts ·
frontend/src/components/kit/ · frontend/src/lib/devFaults.ts.

Deliver: /staffing renders staffing.html — the per-person allocation timeline over a range picker,
the Over-100% panel with its breakdown, the Free-capacity panel and the utilisation panel — and
/directory renders directory.html and answers "who is free next month with skill X" through filters
held in the URL; both gated on staffing:directory and refusing with copy that names the project
screen; build, lint and test:run green.

Traps this task will hit:
- Build the fault switch FIRST (devFaults.ts). `tcmsFaults.staffing('no-profile')` is the only way to
  reach the state where every hours figure is null, and that branch must read an em dash and "no
  profile recorded" — never 0h and never the failed state.
- ONE keys factory, frontend/src/api/queries/keys.ts. Never add a second. Filters go INSIDE the key
  array, and staffingRoot exists so writes do not hand-spell a prefix.
- T1 already landed the types and the fixtures — do NOT re-add either. frontend/src/api/types.ts
  exports AbsenceKind, Absence, AbsenceInput, PersonProfile, PersonProfileInput, Skill, SkillInput,
  PersonSkillsInput, AllocationSpan, Allocation, AllocationInput, AllocationPatch, PersonCapacity,
  StaffingCapacityView, SelfCapacity, ProjectStaffingPerson and ProjectStaffingView.
  frontend/src/test/fixtures.ts has aPersonProfile, aSkill, anAbsence, anAllocation, aPersonCapacity,
  aStaffingCapacityView, aSelfCapacity and aProjectStaffingView. The no-profile state is
  aPersonCapacity({ profile: null, capacityHours: null, absenceHours: null,
  allocatedCapacityHours: null, approvedHours: null }) — five nulls, because the server sends them
  together and a fixture carrying only the first describes a body it cannot send.
- The contract landed FIFTEEN operations, not seventeen. GET /staffing/capacity is the company view,
  GET /staffing/people is the directory, GET /staffing/people/{userId} is one person, and
  GET /staffing/skills is the vocabulary the skill filter is built from.
- Never build your own QueryClient in a test — only createTestQueryClient's has the caches. A render
  that trips an error boundary FAILS unless it calls renderingIntoAnErrorBoundary() and says why.
- Anchor path-keyed mock arms (/^\/staffing\/people\/[^/]+$/) so a sub-resource is not caught by a
  prefix.
- Filter state lives in the URL (useSearchParams), never in component state. That is what makes the
  directory's acceptance criterion true.
- staffing.html's data-role badge says "Project Manager" and the roles spec does not allow it — see
  the plan's correction 3. Gate on staffing:directory and put the PM's screen in T5.
- useCanInCompany() takes an `enabled` flag, NOT a permission key — the key goes to the Gate it
  returns. AppShell.tsx:762 is the existing call site.
- `npm run build` is the gate, NOT `tsc --noEmit`. Two failures in src/features/cases/CaseGrid.test.tsx
  predate this module; do not absorb them.

When done: run `~/bin/tcms-remote sync` then `~/bin/tcms-remote frontend npm run build`,
`npm run lint` and `npm run test:run`. Commit the paths in §T4 step 9 as
`feat(staffing): the company staffing view and the skills directory`. Update
docs/agent-context/fe-react.md, write the record back into §T4, and rewrite the prompts below yours
if the components or hooks you landed are named differently.
```

### Prompt — T5 — The project's staffing and the person page

```
You are `fe-react` working in the tcms repo. Read CLAUDE.md first, then load `fe-react-data-fetching`,
`fe-react-forms` and `fe-react-component-testing`.

Read, in this order: docs/plans/017-staffing.md §T5 and D-b, D-f, D-g, D-h · CLAUDE.md ·
docs/mockups/member.html and docs/mockups/directory.html · docs/agent-context/fe-react.md ·
frontend/src/features/staffing/ as T4 left it · frontend/src/features/admin/TeamsPage.tsx ·
frontend/src/auth/useCan.ts · frontend/src/api/queries/staffing.ts.

Deliver: /projects/:projectId/staffing lists this project's allocations, allocates a person at a
percentage for a date range through a dialog, edits and removes one, and shows each person's
company-wide total with the over-allocation flag and no other project's name; /directory/:userId
renders member.html's Profile, Skills and Absence panels and is reached by directory.html's Profile
button; build, lint and test:run green.

Traps this task will hit:
- THERE IS NO MEMBER PAGE IN THIS APP and you are not to build one. /admin/teams is
  features/admin/TeamsPage.tsx, a list; there is no /admin/teams/:userId route and no
  MemberPage.tsx. The three panels go on /directory/:userId (D-g). The rest of member.html —
  role toggles, the grants table, the assistants panel — is identity's and already exists elsewhere.
- useCanInCompany() takes an `enabled` flag, NOT a permission key. The key goes to the Gate it
  returns. See AppShell.tsx:762.
- The project screen must NOT name any other project (D-f). A Project Manager holds no company-wide
  key, and listing "Acme Loyalty 50%" for them discloses a project they cannot see through a
  project-scope grant. The total percentage and the flag are what they get.
- Recording your OWN absence needs no key; somebody else's needs staffing:absence (D-h). The Add
  control is enabled on your own member page for everybody.
- Writes are disabled-with-a-reason, never hidden (§31) — the audit entries are this product's only
  recorded exception and these are not them.
- A 409 from POST /projects/{id}/allocations is shown INLINE in the dialog naming the conflicting
  range, not raised as a notification — the conflict is about a field the person just filled in.
- Anchor the mock arms: /^\/projects\/[^/]+\/staffing$/ and /^\/projects\/[^/]+\/allocations$/ are
  different endpoints and a prefix match will serve one for the other.
- "6 years" on the profile panel is DERIVED from experienceYears (D-b). Do not add a years field to
  the edit form — the form takes a date.
- T4 LANDED THE WHOLE QUERY MODULE, writes included. Do not re-add any of it.
  frontend/src/api/queries/staffing.ts exports useStaffingCapacity, useMyCapacity, useStaffingPeople,
  useStaffingPerson, useSkills, useAbsences, useProjectStaffing, useCreateAllocation,
  useUpdateAllocation, useDeleteAllocation, useCreateAbsence, useDeleteAbsence, usePutProfile,
  usePutPersonSkills, useCreateSkill — all through the barrel. **Every mutation takes its id as a
  HOOK argument, not per call**: useCreateAllocation(projectId).mutate(input),
  useCreateAbsence(userId).mutate(input), useUpdateAllocation(projectId).mutate({allocationId,
  patch}). Each already sweeps both roots through the module's private invalidateStaffing.
- Two disabled buttons are YOURS to wire, and they are the only two:
  `Staffing.Allocate` on StaffingPage.tsx (leave it disabled — that screen names no project) and
  `Directory.Person.<id>.Profile` in PersonCard.tsx, which becomes a link to /directory/:userId.
  PersonCard also already renders the skills, the strapline and a one-person AllocationTimeline, so
  the person page reuses it rather than drawing a second card.
- Reusable, already built: features/staffing/capacityCopy.ts (capacityLabel, capacityDetail,
  utilisationLabel, freeCapacityLabel, overAllocationLabel, hasProfile, NO_PROFILE, NO_FIGURE,
  UTILISATION_BASIS) · AllocationTimeline.tsx (takes `window`, `people`, `testId`) ·
  DirectoryFilters.tsx, which ALSO exports SkillChips(skills, highlighted, onToggle, testId) ·
  lib/labels.ts::absenceKindLabels and absenceKindTones.
- `tcmsFaults.staffing('no-profile' | 'over-allocated')` exists. Its path test excludes
  /projects/{id}/staffing DELIBERATELY — that operation answers a ProjectStaffingView, which carries
  no profile, so a company-shaped body served there would be a drifted response rather than a fault.
  If T5 wants a fault for the project screen it is a new predicate, not a widened prefix.
- The directory's match count is a SECOND unfiltered read of GET /staffing/people, not a served
  total — see §T4's record. If the person page needs a company total, take it the same way.

When done: run `~/bin/tcms-remote sync` then `~/bin/tcms-remote frontend npm run build`,
`npm run lint` and `npm run test:run`. Commit the paths in §T5 step 5 as
`feat(staffing): the project's staffing, and the person page with profile, skills and absence`. Update
docs/agent-context/fe-react.md, write the record back into §T5, and rewrite T6's prompt below if
anything it assumes changed.
```

### Prompt — T6 — The two capacity figures, and the close-out

```
You are `fe-react` working in the tcms repo. Read CLAUDE.md first, then load `fe-react-ui-rules` and
`fe-react-component-testing`.

Read, in this order: docs/plans/017-staffing.md §T6 and D-i, D-m and "What this module was told"
correction 4 · CLAUDE.md · docs/mockups/timesheets.html and docs/mockups/my-work.html ·
frontend/src/features/time/TimesheetPage.tsx IN FULL, especially its header's "Three KPI tiles"
section and its `Kpis` component · frontend/src/features/board/MyWorkPage.tsx's header and its
`ThisPeriodPanel` · frontend/src/features/time/periods.ts IN FULL ·
docs/plans/016-time.md's "Built and unverified" · docs/plans/009-project-management-expansion-modules.md
"Prompt hand-off protocol".

Deliver: /timesheets shows FOUR KPI tiles — Logged · Period capacity · Approved · Status — in a
md:grid-cols-4; My Work's This-period panel shows Capacity beside Logged with the meter under it;
docs/features.md has a Staffing section; plan 009's status table reads done for M7 with the V
actually taken and the built-and-unverified list; the M9 prompt says whether utilisation landed.

Traps this task will hit:
- The panel is a PERIOD, not a week (016 D-a). Pass period.startsOn/endsOn from the LOADED payload;
  periods.ts is for navigation and labelling only and re-deriving bounds here breaks its own rule.
- The capacity read is its OWN DataRegion nested in the panel. One region over both would make a
  staffing outage blank the logged hours, which are a different module's and were fine.
- GET /staffing/capacity/me requires BOTH `from` and `to` — they are not optional on that operation.
  The response is SelfCapacity: from, to, capacityHours, absenceHours, allocatedPercent, hasProfile.
  `hasProfile` is what tells "no profile recorded" from "failed to load"; the fixture is
  aSelfCapacity() in frontend/src/test/fixtures.ts and T1 landed it.
- capacityLabel/utilisationLabel live in features/staffing/capacityCopy.ts (T4). Do not write a
  second copy of "what does an em dash mean here" in these two files.
- Rewrite the two file headers in the SUPERSEDED style — struck through with "→ superseded
  2026-09-04 (plan 017)" and the reason. Do NOT delete 016 D-j's reasoning; the next reader must see
  the absence was considered and why it ended.
- The close-out is step 5 and it is not optional: the next agent trusts the status table and its
  prompt and re-verifies nothing else in that file. No module depends on M7, so the ONLY dependent
  prompt to rewrite is M9's — and it changes because this prompt says to record whether utilisation
  landed. Also correct 009-execution-breakdown.md phase 7: t32's "No endpoint in this contract reads
  a time entry" is superseded, and the projected V9 is V10.
- Record explicitly whether the two capacity figures were completed or left absent again.

When done: run `~/bin/tcms-remote sync` then the full set —
`~/bin/tcms-remote frontend npm run build`, `npm run lint`, `npm run test:run`,
`~/bin/tcms-remote backend ./gradlew build`, `~/bin/tcms-remote packages npm run build`. Then TWO
commits, per the protocol: `feat(staffing): the period-capacity tile, My Work's meter, and the
features doc`, then `docs(plan): M7 done` staging only the two plan 009 files.
```


---

## What this module was told, and the four corrections it makes to what it was told

**1. The migration is `V10`, and the directory is what says so.** `ls
backend/src/main/resources/db/migration/` on 2026-09-04 returned `V1 … V9`, `V9__time.sql` being
M6's. Plan 009's table reserved `V9` for this module and was corrected to `V10` when M6 landed
ahead of it. That correction is now confirmed by the directory rather than by arithmetic. This is
the fifth time in five weeks that column has needed re-deriving; `out-of-order` is unset in
`application.yml`, so a reused number is **silently not applied**, and Testcontainers builds every
test database from empty and cannot show the conflict.

**2. Utilisation is IN, and `009-execution-breakdown.md` t32's acceptance criterion "No endpoint in
this contract reads a time entry" is superseded here.** That criterion was written on 2026-08-30,
when the confirmed order put M6 *after* M7 and review R11 had just carved utilisation out of this
module on the grounds that M6 might not exist yet. M6 landed on 2026-09-04 at `3471358c`, so the
condition the criterion guarded is gone. `PersonCapacity.approvedHours` reads `time_entry` through
`TimeFacts` (D-j), read-only.

**3. The mockup renders `staffing.html` under a Project Manager badge, and the roles spec does not
let it.** `staffing.html`'s `data-role="Project Manager"` and its heading *"Company view"* cannot
both be true: the company-wide capacity read is `staffing:directory`, which roles spec §A3 grants to
Instance Admin, Company Admin, Account Manager and Finance — and **not** to Project Manager, whose
staffing keys are the `PROJECT`-scope `staffing:read` and `staffing:allocate`. Granting a
COMPANY-scope key to a PROJECT-scope role produces the unreachable row §43 repaired for
`company:read`. Plan 009's precedence rule settles it: **on what a screen shows the mockup wins; on
permissions the spec wins.** So `staffing.html` is built exactly as drawn and gated on
`staffing:directory`, and the Project Manager's equivalent is T5's project screen — which story S1
asks for in its own first words, *"Given I open a project's staffing"*. See D-g.

**4. Capacity is absent for a person with no profile, and that is not the failure 016 D-j
avoided.** 016 removed the timesheet's fourth tile because *no figure existed that this module would
not be making up*. T6 restores it because a figure now exists. Where a person has no
`person_profile` row, the tile reads `—` and not `0h` and not `40h`: that is a real state (nobody has
recorded their contracted hours), and inventing 40 for it would be exactly the invention D-j
refused. `seed-demo-data` gives every demo person a profile (T3, step 9), so the demo instance shows
real numbers rather than a screen full of dashes.

---

## Decisions this plan takes

### D-a — Five tables, and capacity is derived on every read rather than stored

`person_profile` carries `weekly_hours`; `absence` carries days and a fraction of a day; `allocation`
carries a percentage and a date range. **Capacity in hours is computed from those three every time it
is asked** and is not a column anywhere.

A stored capacity is a cache with four invalidators — the profile changing, an absence being
recorded, an absence being deleted, and the calendar rolling forward — and the fourth cannot be
hooked. A capacity table that silently ages is the two-contradictory-percentages failure with a
schema behind it. The computation is a loop over the working days of a bounded range; the ranges this
product asks about are a fortnight and three months.

### D-b — Experience is a date, not a number of years

`directory.html` renders *"Test Lead · Stockholm · 6 years"*. The column is
`experience_since DATE`, and "6 years" is derived at read time. An integer `years_experience` is
correct on the day it is typed and wrong every day after, silently, and nothing in the product would
ever tell anybody.

### D-c — A working day is Monday to Friday, and there is no holiday calendar

Daily capacity is `weekly_hours / 5` on Mon–Fri and zero on Sat–Sun. There is **no public-holiday
table**, for 016 D-a's reason about the timesheet cadence: no mockup has a control that configures
one, and a table nothing writes is a migration for generality nobody asked for. A public holiday is
recorded as an `absence` of kind `HOLIDAY` — which is what `absence` is for, is per person, and is
already the thing that zeroes capacity everywhere.

**What this costs, stated rather than discovered:** a company-wide holiday is *n* absence rows, one
per person. That is the right trade while the product has no locale model — a shared calendar in a
system serving several client companies from one instance would have to be per company *and* per
country, and neither exists.

### D-d — An absence carries a fraction of a day, so a half day is not a second table

`member.html` shows `29 Aug · half day`. `absence.day_fraction NUMERIC(3,2) NOT NULL DEFAULT 1.00`,
`CHECK (day_fraction > 0 AND day_fraction <= 1)`. A half day is `0.50` and reads as *half day*; a
whole absence is `1.00`. Modelling it as a boolean `half_day` would answer the mockup and nothing
else; modelling it as start/end *times* would invent a working-hours-of-day model this product does
not have.

### D-e — Over-allocation is the sum of overlapping percentages **on a day**, not in a month

`staffing.html` draws monthly bars and totals a person at `130%` for October. That rendering is a
month's-eye view of a daily truth. A person at 100% for 1–10 September and 100% for 11–30 September
is **not** over-allocated, and a monthly sum would flag them at 200%.

So `StaffingService.overAllocation` walks the days of the queried range, sums the percentages of the
allocations covering each day, and reports the **peak** day and its total. The flag fires at
**101 and above**, which is the acceptance criterion in the prompt and in `009-execution-breakdown.md`
t34. A day with no allocation contributes nothing, and a day the person is fully absent is excluded
from the peak — being booked at 100% on a day you are on leave is not an over-allocation, it is an
allocation with no capacity behind it, and the two read differently on the screen.

### D-f — The over-allocation total is a number on the project screen and a breakdown only on the company one

`GET /projects/{projectId}/staffing` returns each allocated person's **total** allocated percentage
and the over-allocation flag, and **does not name the other projects**. A Project Manager holds
`staffing:read` on their own projects and no company-wide key; listing "Acme Loyalty 50% + Bergstrom
Portal 30%" for them would disclose, through a project-scope grant, the existence and staffing of
projects they cannot see. The number is what makes the flag actionable — *this person is already at
130%, do not add to them* — and the breakdown is the company view's, under `staffing:directory`.

`staffing.html`'s Over-100% panel shows the breakdown, because that screen is `staffing:directory`.

### D-g — Two scopes, two screens, and the rail entry is shown to everybody

| Screen | Route | Gate | Serves |
|---|---|---|---|
| Company staffing | `/staffing` | `staffing:directory` (COMPANY) | `staffing.html` — the timeline, Over-100%, free capacity, utilisation |
| Directory | `/directory` | `staffing:directory` (COMPANY) | `directory.html` — skills, availability, "free from" |
| This project's staffing | `/projects/:projectId/staffing` | `staffing:read` (PROJECT), writes on `staffing:allocate` | story S1's *"I open a project's staffing"* |
| One person | `/directory/:userId` | `staffing:directory`, **or the caller is that person** | `directory.html`'s `Profile` button, and `member.html`'s Profile / Skills / Absence panels |

**`member.html` shows those three panels on a member page this app does not have, and M7 does not
build one.** `/admin/teams` is `features/admin/TeamsPage.tsx`, a list; there is no
`/admin/teams/:userId` route and no `MemberPage.tsx` — verified on 2026-09-04. The rest of
`member.html` — company-role toggles, the project-grants table, the "what Test Engineer can do"
matrix, the assistants panel — is `identity`'s surface and already lives on `TeamsPage` and
`ProjectAccessPage`. Building a member page to hang three staffing panels off would have M7 porting
another module's screen.

So the three panels land on **`/directory/:userId`**, which is M7's own, is reached by the `Profile`
button `directory.html` already draws, and is where a reader looking for "when is this person free"
is already standing. If a member page is built later, the panels move to it and this route stays as
the directory's detail view.

**`teams.html` needs no change and gets none.** Its members table shows company roles, project
grants, assistants and last-seen — no skill column and no capacity figure — so the mockup this
module was told to walk for "where profiles and skills surface" answers that they surface on the
*person*, not in the list. `features/admin/TeamsPage.tsx` is untouched by every task here.

Both company entries go in the rail's **Company** group, ungated, per that group's own standing rule
(§31, disable-don't-hide): a hidden entry tells a lie about the *product*, and the two audit entries
are the sole recorded exception. A Project Manager therefore sees `Staffing` in the rail, opens it,
and is refused — and the refusal copy names the project screen, which is the one they can use.

### D-h — Your own absence needs no key; somebody else's needs `staffing:absence`

Straight off the catalogue text, which is precise about this: `staffing:absence` covers *"Record
absence on somebody else's behalf"*. Recording that you are away on Thursday is not an administrative
act, and a product that made it one would be a product where nobody records absence — which would
make every capacity figure in this module a lie by omission.

Reading somebody else's absences is `staffing:directory`; reading your own is always allowed.

### D-i — Utilisation is **approved hours ÷ allocated capacity**, and the screen says which

016 D-d: `approvedHours` and `pendingHours` are separate on the wire and **nothing in the product
sums them**. Utilisation denominates over **approved**, and the panel's own subtitle reads
`approved hours ÷ allocated capacity` — every other time figure in this product names its spelling
(`epic.html`'s *"approved hours only"*, `timesheets.html`'s *"everything in it, whatever anybody has
decided about it"*) and one that did not would be the first.

Approved rather than pending because Finance holds `staffing:directory` for exactly this figure
(roles spec §A3: *"utilisation is a money question as much as a staffing one"*), and hours nobody has
accepted are not yet a fact about the money.

`utilisationPercent` is **null**, not `0`, when allocated capacity for the window is zero. A person
nobody has allocated has no utilisation, and rendering `0%` would say they are idle when the true
statement is that nobody has planned their time.

### D-j — `staffing` reads `time` through one class, read-only — the fifth instance of a named pattern

`com.tcms.staffing.TimeFacts` is the only file in the module that names a `time`-owned table
(`time_entry`, `timesheet_period`). Native SQL, read-only, no JPA entity mapped over another module's
table. `StaffingReadsNoTimeEntryTest` asserts both halves — that nothing here writes them, and that
exactly one file names them — the way `TimeWritesNoIssueFactTest` does one module over.

The precedents: `work.BoardFacts` reads `tracker`, `execution.CaseFacts` reads `repository`,
`tracker.release.ReleaseFacts` reads `execution`, `time.IssueFacts` reads `tracker`. ArchUnit
permits a Java dependency only toward the five foundation modules and `time` is not one; SQL is not a
Java dependency, which is why the rule has to be a test over file text rather than a rule ArchUnit
can see.

### D-k — `project.PeopleDirectory` gains `membersOf(companyId)`

The directory lists **every company member**, matching `directory.html`'s *"3 of 11 people match"* —
not merely the people who happen to have a profile. No existing port answered that:
`PeopleDirectory.resolve` resolves ids you already have, and `identity.AdminDirectoryService.listUsers`
gates on admin reach, which an Account Manager holding only `staffing:directory` does not have.

So the port `project.PeopleDirectory` gains one method and `identity.IdentityPeopleDirectory`
implements it, exactly as `IdentityProjectAccess` does for `project`. The arrow stays one-way, and
`staffing` still never asks `identity` *may they*.

### D-l — `ProjectService.holdsInCompany` mirrors `ProjectService.holds`

`ProjectService` already exposes `holds(userId, projectId, permission)` for a PROJECT-scope question
and `createGranted(userId, permission, write)` for a COMPANY-scope **write**. There is no
company-scope **read** gate, so a leaf module asking "may this caller read the company directory"
would have to import `identity.PermissionEvaluator` — which `time` deliberately does not do, and
which would put a second answer to "may they" in a module whose package comment says it has none.

One public method, delegating to the `ProjectAccess.canDoInCompany` that `createGranted` already
calls, plus a `readInCompany` gate returning `GateResult`. Foundation-module addition, four lines of
behaviour, and it is the shape every subsequent COMPANY-scope module (M8's billing reads) will need.

### D-m — The two M6 figures are served by `GET /staffing/capacity/me`, not by widening `GET /timesheets/periods/current`

`TimesheetPeriod` belongs to `time`, and capacity is not a time figure — it is computed from
`person_profile`, `allocation` and `absence`, all of which are this module's. Putting
`capacityHours` on the timesheet payload would make `time` serve a number it cannot compute and
cannot own.

So both screens make **two reads**: their existing timesheet read, plus
`GET /staffing/capacity/me?from&to`. That is what a module boundary costs, paid where it is visible,
and the second read is its own `DataRegion` so a staffing outage empties the tile and leaves the
hours where they were.

**The window is the period, not the week** (016 D-a). `features/time/periods.ts` owns the fortnight
arithmetic and is a pure module with its own test; T6 passes `period.startsOn` / `period.endsOn`
from the loaded payload — which is that file's own rule about a loaded period being labelled from its
own payload — and never re-derives them.

### D-n — An allocation may not overlap itself, and the server refuses rather than merges

Two allocations of the *same person* to the *same project* over overlapping days is not a thing a
screen can render — `staffing.html` draws one bar per person per project — and merging them silently
would change a number somebody typed. `POST` and `PATCH` answer `409` with an `Invalid` outcome
naming the conflicting range. Two projects over the same days is the ordinary case and is exactly
what over-allocation exists to report.

---

## File structure

**Contract and clients (T1)**

| File | Responsibility |
|---|---|
| `docs/specs/openapi.yaml` | `staffing` tag; 17 operations; 17 schemas, every one with its `required` array |
| `frontend/src/api/schema.d.ts` | regenerated — `npm run gen:api` in `frontend/` |
| `packages/client/src/schema.ts` | regenerated — `npm run gen:api` in `packages/client/` |
| `frontend/src/api/types.ts` | one `export type` per schema, so a screen names a shape |
| `frontend/src/test/fixtures.ts` · `packages/client/src/test/fixtures.ts` | a factory per resource — **not** paired, each in its own idiom |
| ~~`frontend/src/api/contractBodies.ts`~~ · ~~`packages/client/src/test/contractBodies.ts`~~ | ~~the shared region gains the staffing bodies~~ → **untouched: they are the schema walker and hold no bodies** |
| `packages/cli/src/reads.ts` · `writes.ts` | `staffing directory`, `staffing project`, `staffing allocate`, `staffing absence` |
| `packages/mcp/src/reads.ts` · `writes.ts` | `tcms_read_staffing`, `tcms_write_allocation`, `tcms_write_absence` |
| `packages/cli/src/writeConformance.test.ts` · `packages/mcp/src/writeConformance.test.ts` · `packages/mcp/src/writes.test.ts` | the three registry checks a new verb may not pass |
| `frontend/src/features/help/sections.tsx` | the shipped guide, pinned both ways by `guideCopies.test.ts` |
| `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` | §3.9 and §6's staffing table — four verbs and three tools |

**Schema (T2)**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V10__staffing.sql` | the five tables, their indexes, five FORCE RLS policies |
| `docs/specs/2026-07-30-tcms-erd.md` | the staffing entities, in the existing style |

**Backend (T3)**

| File | Responsibility |
|---|---|
| `com/tcms/staffing/package-info.java` | the module declaration and the two rules it may not break |
| `com/tcms/staffing/PersonProfile.java` · `Skill.java` · `PersonSkill.java` · `Allocation.java` · `Absence.java` | entities |
| `…/PersonProfileRepository.java` · `SkillRepository.java` · `PersonSkillRepository.java` · `AllocationRepository.java` · `AbsenceRepository.java` | Spring Data, each `@Transactional(readOnly = true)` on the interface |
| `com/tcms/staffing/Capacity.java` | **the one capacity function** — working days, absence, allocation, over-allocation peak. Pure, no Spring, unit-tested with no database |
| `com/tcms/staffing/TimeFacts.java` | the only file naming a `time` table (D-j) |
| `com/tcms/staffing/StaffingService.java` | the company reads: directory, capacity, utilisation |
| `com/tcms/staffing/AllocationService.java` | the project reads and writes |
| `com/tcms/staffing/ProfileService.java` | profiles, skills, absences |
| `com/tcms/staffing/StaffingController.java` · `AllocationController.java` | thin; sealed-outcome switches |
| `…/AbsenceKind.java` and the eleven view/input records | the wire shapes T1 declared |
| `com/tcms/project/PeopleDirectory.java` · `ProjectService.java` | D-k's `membersOf`, D-l's `holdsInCompany` / `readInCompany` |
| `com/tcms/identity/IdentityPeopleDirectory.java` | implements `membersOf` |
| `com/tcms/DemoWorkspaceSeeder.java` | profiles, skills, allocations and one absence for the demo company |

**Frontend (T4–T6)**

| File | Responsibility |
|---|---|
| `frontend/src/api/queries/staffing.ts` | every staffing hook, behind the barrel |
| `frontend/src/api/queries/keys.ts` | the `staffing` sub-tree and `staffingRoot` — **the one factory** |
| `frontend/src/features/staffing/StaffingPage.tsx` · `AllocationTimeline.tsx` · `OverAllocatedPanel.tsx` · `FreeCapacityPanel.tsx` · `UtilisationPanel.tsx` | `/staffing` |
| `frontend/src/features/staffing/DirectoryPage.tsx` · `DirectoryFilters.tsx` · `PersonCard.tsx` | `/directory` |
| `frontend/src/features/staffing/ProjectStaffingPage.tsx` · `AllocationDialog.tsx` | `/projects/:projectId/staffing` |
| `frontend/src/features/staffing/PersonPage.tsx` · `ProfilePanel.tsx` · `SkillsPanel.tsx` · `AbsencePanel.tsx` | `/directory/:userId` — `member.html`'s three panels, on the page D-g says they land on |
| `frontend/src/lib/labels.ts` | `absenceKindLabels satisfies Record<AbsenceKind, string>` |
| `frontend/src/lib/devFaults.ts` | `tcmsFaults.staffing('no-profile' | 'over-allocated')` — the switch before the feature |
| `frontend/src/features/time/TimesheetPage.tsx` | the fourth tile (T6) |
| `frontend/src/features/board/MyWorkPage.tsx` | Capacity beside Logged, and the meter (T6) |

---

## T1 — The staffing contract, both clients, and the CLI/MCP verbs

**Deliverable:** ~~17~~ **15** `staffing`-tagged operations over twelve path items in
`docs/specs/openapi.yaml` and **17 schemas**, each carrying its `required` array;
`frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` regenerated from it; a factory per
new resource in both `src/test/fixtures.ts` copies (~~both `contractBodies.ts` copies carrying the
new bodies byte-identically~~ — see correction 2); ~~three~~ **four** CLI verbs and three MCP tools
that call the new operations; and `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` listing all
seven.
**Stack:** `ts`
**Agent:** `tooling-engineer`
**Est. context:** ~140k — 1 spec file modified (19,825 lines, read in the `/timesheets` region only),
2 generated files regenerated, 2 test-fixture files modified, 6 package source files modified, 1 doc
modified; 3 skills; 4 spec sections cited
**Depends on:** none

> **Landed 2026-09-04, with five corrections to what this block predicted.** They are written into
> the steps below rather than appended as a note.
>
> 1. **Fifteen operations, not seventeen.** Seventeen is the number of **schemas**, and step 3 below
>    listed fifteen operations when this plan was written — the deliverable line had counted the
>    wrong column. The twelve path items are `/staffing/people`, `…/{userId}`, `…/profile`,
>    `…/skills`, `…/absences`, `…/absences/{absenceId}`, `/staffing/skills`, `/staffing/capacity`,
>    `/staffing/capacity/me`, `/projects/{projectId}/staffing`, `/projects/{projectId}/allocations`
>    and `…/allocations/{allocationId}`.
> 2. **`contractBodies.ts` needed no change, and 016 T1 recorded the same thing one plan earlier.**
>    Both copies are the schema *walker* — the marked region is `violationsOf` and its helpers, and
>    it holds no response bodies at all. The bodies belong in the two `src/test/fixtures.ts` files,
>    which are **not** byte-identical to each other and are not paired: the frontend's spells
>    `aPersonCapacity(overrides): PersonCapacity` against a named type from `api/types.ts` and the
>    package's spells `personCapacity(overrides): Schemas['PersonCapacity']`. So the byte-for-byte
>    trap this block warned about does not apply to this task at all, and `contractPairing.test.ts`
>    is green because nothing in that region moved. Steps 5 and 6 are one step.
> 3. **Four CLI verbs, not three — `staffing project` is the fourth**, and the argument is
>    `time queue`'s exactly (016 T1's own second correction). `staffing allocate` is a PROJECT-scope
>    write held by a Project Manager, who holds **no** `staffing:directory` — so without a
>    project-scope read the verb that allocates would have had no read behind it on the only surface
>    that can call it, and nothing would have printed an allocation id anywhere. This block's own
>    step-7 comment already named the command (*"that is `tcms staffing project`"*) while its
>    deliverable line counted three. The registry now holds 68 commands.
> 4. **Five files outside the predicted list moved with the registries**, four of them found by an
>    existing check rather than by reading: `frontend/src/api/types.ts` (the staffing type exports
>    the SPA's fixtures are typed against), `frontend/src/features/help/sections.tsx` (pinned both
>    ways by `guideCopies.test.ts` in **both** packages, including its `N tools` prose — 34 → 37),
>    both `writeConformance.test.ts` (a write arm with no `INVOCATIONS` entry fails rather than
>    skips) and `mcp/src/writes.test.ts`'s tool count.
> 5. **Two company-scope creates carry no `Idempotency-Key`, and it is the parameter's own scope
>    that says so.** It deduplicates within `(company, project, key)`, and neither `POST
>    /staffing/skills` nor `POST …/absences` is addressed to a project. `POST
>    /projects/{projectId}/allocations` is, and carries it. The absence shortfall is recorded in the
>    `CREATE_WITHOUT_A_KEY` map on **both** agent surfaces with what a lost response actually costs:
>    a duplicate row over the same days, which double-counts nothing, because capacity is computed
>    per DAY from the absences covering it (D-a). T3 must not invent a company-scope idempotency
>    scope to close it.
>
> **Two frontend tests were already red on `master` before this task and still are** —
> `CaseGrid.test.tsx`'s two selection cases, verified by stashing this work and re-running them at
> `HEAD`. `ci.yml` runs only the frontend **smoke** set per push, which is how they are sitting
> there unobserved. Not this task's, and not repaired here.
>
> **And this task turned the BACKEND suite red, which T2 found and neither task's own gate could
> see.** `./gradlew build` fails four assertions — `ContractConventionTest`'s two pairing checks,
> `ContractEnumTest.everyNamedContractEnumNamesTheServerType` and
> `ContractParameterBindingTest.everyOperationIsServedByAHandler` — because 17 schemas and 15
> operations were added to `openapi.yaml` with no record and no handler behind them. **That is T3's
> to close and it is the expected state between these two tasks**, not a defect: this task is `ts`
> and its gate was `packages npm run build`, `ci.yml` runs the backend **compile only**, and
> `tests.yml` runs the full suite nightly on the default branch — so nothing between T1 and T3 was
> ever going to report it. T2 verified the failures are T1's by stashing its own work and re-running
> the three classes at `fe305a88`: identical four. **Until T3 lands, `./gradlew build` is red at
> exactly these four and a fifth failure is somebody's regression.**

**Files:**
- Modify: `docs/specs/openapi.yaml` (after the `/time/rollup/epics/{key}` block, currently line 9212)
- Modify: `frontend/src/api/schema.d.ts` (generated — `npm run gen:api`)
- Modify: `packages/client/src/schema.ts` (generated — `cd packages/client && npm run gen:api`)
- Modify: `frontend/src/api/types.ts` — the staffing type exports, so a screen and a fixture name a
  shape rather than reaching into `schema.d.ts`
- ~~Modify: `frontend/src/api/contractBodies.ts` (shared region, lines 124–457)~~
- ~~Modify: `packages/client/src/test/contractBodies.ts` (shared region, lines 67–400)~~ →
  **superseded: those two are the schema walker and hold no bodies.**
- Modify: `frontend/src/test/fixtures.ts`, `packages/client/src/test/fixtures.ts`
- Modify: `packages/cli/src/reads.ts`, `packages/cli/src/writes.ts`
  (`commands.ts` needs no change — it is `[...READS, ...WRITES]`)
- Modify: `packages/mcp/src/reads.ts`, `packages/mcp/src/writes.ts`
  (`tools.ts` needs no change, for the same reason)
- Modify: `packages/cli/src/writeConformance.test.ts`, `packages/mcp/src/writeConformance.test.ts`,
  `packages/mcp/src/writes.test.ts` — the three checks a registry change is not allowed to pass
- Modify: `frontend/src/features/help/sections.tsx` — the shipped guide, pinned both ways
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

- [x] **Step 1: Add the `staffing` tag**

`docs/specs/openapi.yaml`, after `- name: time` (currently line 58):

```yaml
  - name: staffing
    description: >
      Who is on a project and at what percentage, who in the company has which skill, and when
      anybody is free. Two scopes, deliberately: `staffing:read` / `staffing:allocate` are
      PROJECT-scope and answer "who is on THIS project"; `staffing:directory` /
      `staffing:profile` / `staffing:absence` are COMPANY-scope and answer the capacity and skills
      questions. Granting a COMPANY-scope key to a PROJECT-scope role produces a row the evaluator
      can never reach (roles spec §43), so the split is load-bearing rather than tidy.
```

- [x] **Step 2: Add the seventeen schemas**

Under `components.schemas`, alphabetically among the existing ones. **Every one carries `required`,
including the inline nested objects — `required` does not descend.**

```yaml
    AbsenceKind:
      type: string
      description: >
        Why somebody is away. `HOLIDAY` is how a public holiday is recorded, because there is no
        holiday calendar in this product (plan 017 D-c) and a per-person row is what actually
        zeroes capacity.
      enum: [LEAVE, SICK, HOLIDAY, OTHER]

    Absence:
      type: object
      required: [id, personId, startsOn, endsOn, kind, dayFraction, note]
      properties:
        id: { type: string, format: uuid }
        personId: { type: string, format: uuid }
        startsOn: { type: string, format: date }
        endsOn: { type: string, format: date }
        kind: { $ref: '#/components/schemas/AbsenceKind' }
        dayFraction:
          type: number
          format: double
          minimum: 0.25
          maximum: 1
          description: >
            The share of each day in the range that is absent. `1` is a whole day, `0.5` the
            `half day` member.html shows. Not a boolean, so a half day is not a second table
            (017 D-d).
        note: { type: string, nullable: true }

    AbsenceInput:
      type: object
      required: [startsOn, endsOn, kind]
      properties:
        startsOn: { type: string, format: date }
        endsOn: { type: string, format: date }
        kind: { $ref: '#/components/schemas/AbsenceKind' }
        dayFraction: { type: number, format: double, minimum: 0.25, maximum: 1, default: 1 }
        note: { type: string, nullable: true }

    PersonProfile:
      type: object
      required: [personId, weeklyHours, jobTitle, location, experienceSince, experienceYears]
      properties:
        personId: { type: string, format: uuid }
        weeklyHours:
          type: number
          format: double
          minimum: 0
          maximum: 80
          description: Contracted hours a week. Daily capacity is this over five (017 D-c).
        jobTitle: { type: string, nullable: true }
        location: { type: string, nullable: true }
        experienceSince:
          type: string
          format: date
          nullable: true
          description: >
            A date, not a count of years (017 D-b). directory.html renders "6 years"; a stored
            integer is correct on the day it is typed and wrong every day after.
        experienceYears:
          type: integer
          nullable: true
          description: Derived from `experienceSince` at read time. Null when that is null.

    PersonProfileInput:
      type: object
      required: [weeklyHours]
      properties:
        weeklyHours: { type: number, format: double, minimum: 0, maximum: 80 }
        jobTitle: { type: string, nullable: true }
        location: { type: string, nullable: true }
        experienceSince: { type: string, format: date, nullable: true }

    Skill:
      type: object
      required: [id, name]
      properties:
        id: { type: string, format: uuid }
        name: { type: string, minLength: 1, maxLength: 80 }

    SkillInput:
      type: object
      required: [name]
      properties:
        name: { type: string, minLength: 1, maxLength: 80 }

    PersonSkillsInput:
      type: object
      required: [skillIds]
      properties:
        skillIds:
          type: array
          items: { type: string, format: uuid }
          description: The whole set, replacing what is there. Not a delta — a chip list is a set.

    AllocationSpan:
      type: object
      description: One project's claim on one person, as the timeline draws it.
      required: [projectId, projectKey, projectName, percent, startsOn, endsOn]
      properties:
        projectId: { type: string, format: uuid }
        projectKey: { type: string }
        projectName: { type: string }
        percent: { type: integer, minimum: 1, maximum: 100 }
        startsOn: { type: string, format: date }
        endsOn: { type: string, format: date }

    Allocation:
      type: object
      required: [id, projectId, personId, person, percent, startsOn, endsOn, note]
      properties:
        id: { type: string, format: uuid }
        projectId: { type: string, format: uuid }
        personId: { type: string, format: uuid }
        person: { $ref: '#/components/schemas/UserRef' }
        percent: { type: integer, minimum: 1, maximum: 100 }
        startsOn: { type: string, format: date }
        endsOn: { type: string, format: date }
        note: { type: string, nullable: true }

    AllocationInput:
      type: object
      required: [personId, percent, startsOn, endsOn]
      properties:
        personId: { type: string, format: uuid }
        percent: { type: integer, minimum: 1, maximum: 100 }
        startsOn: { type: string, format: date }
        endsOn: { type: string, format: date }
        note: { type: string, nullable: true }

    AllocationPatch:
      type: object
      required: []
      properties:
        percent: { type: integer, minimum: 1, maximum: 100 }
        startsOn: { type: string, format: date }
        endsOn: { type: string, format: date }
        note: { type: string, nullable: true }

    PersonCapacity:
      type: object
      description: >
        One person over one window. `capacityHours` is already net of absence — there is no screen
        that should subtract it a second time, and one that did would contradict this one.
      required:
        [person, profile, skills, capacityHours, absenceHours, allocatedPercent,
         allocatedCapacityHours, freePercent, peakPercent, peakOn, overAllocated,
         approvedHours, utilisationPercent, freeFrom, allocations, absences]
      properties:
        person: { $ref: '#/components/schemas/UserRef' }
        profile:
          allOf: [{ $ref: '#/components/schemas/PersonProfile' }]
          nullable: true
          description: >
            Null when nobody has recorded this person's contracted hours. Every hours figure below
            is then null too — absent, not zero, and not a default of 40 (017, correction 4).
        skills:
          type: array
          items: { $ref: '#/components/schemas/Skill' }
        capacityHours: { type: number, format: double, nullable: true }
        absenceHours: { type: number, format: double, nullable: true }
        allocatedPercent:
          type: integer
          description: The mean daily allocated percentage across the window, which is what the row label shows.
        allocatedCapacityHours: { type: number, format: double, nullable: true }
        freePercent: { type: integer, description: '100 minus allocatedPercent, floored at 0.' }
        peakPercent:
          type: integer
          description: >
            The highest single day's total in the window (017 D-e). This is what the flag is read
            off — a month's sum would flag two consecutive half-month allocations at 200%.
        peakOn: { type: string, format: date, nullable: true }
        overAllocated: { type: boolean, description: '`peakPercent` is 101 or more.' }
        approvedHours:
          type: number
          format: double
          nullable: true
          description: >
            APPROVED time only (016 D-d). `approvedHours` and `pendingHours` are separate on the
            wire and nothing in this product sums them.
        utilisationPercent:
          type: integer
          nullable: true
          description: >
            `approvedHours ÷ allocatedCapacityHours`, as a percentage (017 D-i). **Null, not zero,
            when allocated capacity is zero** — a person nobody has allocated has no utilisation,
            and 0% would say they are idle when the true statement is that nobody planned their
            time.
        freeFrom:
          type: string
          format: date
          nullable: true
          description: >
            The first day in the window on which this person is under 100% allocated, which is what
            directory.html's "free from 1 Oct" reads. Null when they are full for all of it.
        allocations:
          type: array
          items: { $ref: '#/components/schemas/AllocationSpan' }
        absences:
          type: array
          items: { $ref: '#/components/schemas/Absence' }

    StaffingCapacityView:
      type: object
      required: [from, to, people, overAllocated]
      properties:
        from: { type: string, format: date }
        to: { type: string, format: date }
        people:
          type: array
          items: { $ref: '#/components/schemas/PersonCapacity' }
        overAllocated:
          type: array
          items: { $ref: '#/components/schemas/PersonCapacity' }
          description: >
            The subset with `overAllocated` true, so the panel does not re-filter and cannot
            disagree with the row badge above it.

    SelfCapacity:
      type: object
      description: >
        The caller's own capacity over a window — the one staffing read with no staffing
        permission on it, because it is about the caller (017 D-m). Serves the timesheet's fourth
        KPI tile and My Work's This-period meter.
      required: [from, to, capacityHours, absenceHours, allocatedPercent, hasProfile]
      properties:
        from: { type: string, format: date }
        to: { type: string, format: date }
        capacityHours:
          type: number
          format: double
          nullable: true
          description: Net of absence. Null when the caller has no profile — the tile then reads em dash.
        absenceHours: { type: number, format: double, nullable: true }
        allocatedPercent: { type: integer }
        hasProfile:
          type: boolean
          description: >
            Explicit rather than inferred from a null, so a screen can say *no profile recorded*
            instead of *failed to load*. The two look identical without it.

    ProjectStaffingPerson:
      type: object
      description: >
        A person on THIS project. Carries their company-wide total percentage and the flag, and
        **names no other project** (017 D-f) — the breakdown is the company view's, under
        `staffing:directory`.
      required: [person, percentOnThisProject, totalPercent, overAllocated, capacityHours]
      properties:
        person: { $ref: '#/components/schemas/UserRef' }
        percentOnThisProject: { type: integer }
        totalPercent: { type: integer }
        overAllocated: { type: boolean }
        capacityHours: { type: number, format: double, nullable: true }

    ProjectStaffingView:
      type: object
      required: [from, to, allocations, people]
      properties:
        from: { type: string, format: date }
        to: { type: string, format: date }
        allocations:
          type: array
          items: { $ref: '#/components/schemas/Allocation' }
        people:
          type: array
          items: { $ref: '#/components/schemas/ProjectStaffingPerson' }
```

- [x] **Step 3: Add the FIFTEEN operations** (seventeen was the schema count — correction 1)

After the `/time/rollup/epics/{key}` block. `403` bodies use the existing `Problem` response refs —
copy the `responses:` blocks from `/timesheets/approvals` rather than inventing new ones.

**Four departures from the YAML below, all made while writing it and all in the file that shipped.**
The block is left as drafted rather than re-pasted, because the contract itself is the authority and
a plan that copies it twice has two of them:

1. **Every operation declares `401`** (`$ref: Unauthorized`), which the draft omitted and every other
   operation in this document carries.
2. **Every `404` is `$ref: NotFound`**, not an inline `description:`. That response is bodiless *by
   decision* (§148, §6t) and `ErrorBodyPolicyTest` compares the declaration against the source of
   every handler — an inline one would have been a second spelling of a settled rule.
3. **Path-level `parameters:` blocks** carry `userId`, `projectId` and `allocationId` once per path
   item rather than once per operation, which is what `/timesheets/periods/{periodId}` does and what
   `declaredHeadersOf` merges.
4. **`POST /projects/{projectId}/allocations` declares `Idempotency-Key` and a `200` replay**; the
   two company-scope creates deliberately do not. See correction 5 — the parameter's dedupe scope is
   `(company, project, key)`, and only the allocation create is addressed to a project.

```yaml
  /staffing/people:
    get:
      tags: [staffing]
      operationId: listStaffingPeople
      summary: The skills directory — who has which skills, and when they are free
      description: >
        Every present company member, whether or not they have a profile. `skill` may repeat and is
        ANDed: a person must hold all of them, which is what "who knows Playwright and Java"
        means. `freeFrom` keeps only people with a day at under 100% on or after it.
        Requires `staffing:directory` (COMPANY scope) — held by Instance Admin, Company Admin,
        Account Manager and Finance.
      parameters:
        - { name: from, in: query, schema: { type: string, format: date } }
        - { name: to, in: query, schema: { type: string, format: date } }
        - { name: skill, in: query, schema: { type: array, items: { type: string, format: uuid } }, style: form, explode: true }
        - { name: freeFrom, in: query, schema: { type: string, format: date } }
      responses:
        '200':
          description: The people who match, in name order.
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/PersonCapacity' } }
        '403': { $ref: '#/components/responses/Forbidden' }

  /staffing/people/{userId}:
    get:
      tags: [staffing]
      operationId: getStaffingPerson
      summary: One person's profile, skills, allocations, absences and capacity
      description: >
        Requires `staffing:directory`, **or** that the caller is this person. Somebody may always
        read their own staffing.
      parameters:
        - { name: userId, in: path, required: true, schema: { type: string, format: uuid } }
        - { name: from, in: query, schema: { type: string, format: date } }
        - { name: to, in: query, schema: { type: string, format: date } }
      responses:
        '200':
          description: The person.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/PersonCapacity' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such person in this company. }

  /staffing/people/{userId}/profile:
    put:
      tags: [staffing]
      operationId: putPersonProfile
      summary: Create or replace somebody's staffing profile
      description: Requires `staffing:profile` (COMPANY scope). Creates the row if it is not there.
      parameters:
        - { name: userId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/PersonProfileInput' }
      responses:
        '200':
          description: The profile as stored.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/PersonProfile' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such person in this company. }

  /staffing/people/{userId}/skills:
    put:
      tags: [staffing]
      operationId: putPersonSkills
      summary: Replace somebody's whole skill set
      description: Requires `staffing:profile`. The payload is the set, not a delta.
      parameters:
        - { name: userId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/PersonSkillsInput' }
      responses:
        '200':
          description: The skills they now hold, in name order.
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/Skill' } }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such person, or a skill id that is not this company's. }

  /staffing/people/{userId}/absences:
    get:
      tags: [staffing]
      operationId: listAbsences
      summary: Somebody's absences in a window
      description: The caller's own always; anybody else's needs `staffing:directory`.
      parameters:
        - { name: userId, in: path, required: true, schema: { type: string, format: uuid } }
        - { name: from, in: query, schema: { type: string, format: date } }
        - { name: to, in: query, schema: { type: string, format: date } }
      responses:
        '200':
          description: The absences overlapping the window, earliest first.
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/Absence' } }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such person in this company. }
    post:
      tags: [staffing]
      operationId: createAbsence
      summary: Record an absence
      description: >
        The caller's own needs no staffing permission (017 D-h) — `staffing:absence` covers
        recording it *on somebody else's behalf*, which is what the catalogue says and what the
        gate here asks.
      parameters:
        - { name: userId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/AbsenceInput' }
      responses:
        '201':
          description: The absence as recorded.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Absence' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such person in this company. }

  /staffing/people/{userId}/absences/{absenceId}:
    delete:
      tags: [staffing]
      operationId: deleteAbsence
      summary: Remove an absence
      description: The caller's own always; anybody else's needs `staffing:absence`.
      parameters:
        - { name: userId, in: path, required: true, schema: { type: string, format: uuid } }
        - { name: absenceId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '204': { description: Removed. }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such absence for this person. }

  /staffing/skills:
    get:
      tags: [staffing]
      operationId: listSkills
      summary: The company's skill vocabulary
      description: Requires `staffing:directory`. Name order.
      responses:
        '200':
          description: Every skill this company has recorded.
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/Skill' } }
        '403': { $ref: '#/components/responses/Forbidden' }
    post:
      tags: [staffing]
      operationId: createSkill
      summary: Add a skill to the vocabulary
      description: >
        Requires `staffing:profile`. Names are unique per company, case-insensitively — `citext`,
        as every other name-like column in this schema is.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/SkillInput' }
      responses:
        '201':
          description: The skill.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Skill' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '409': { $ref: '#/components/responses/Conflict' }

  /staffing/capacity:
    get:
      tags: [staffing]
      operationId: getStaffingCapacity
      summary: The company capacity view — allocation, over-allocation, free capacity, utilisation
      description: >
        Requires `staffing:directory`. `from`/`to` default to the first of this month and three
        months on, which is what staffing.html's "Next 3 months" range shows.
      parameters:
        - { name: from, in: query, schema: { type: string, format: date } }
        - { name: to, in: query, schema: { type: string, format: date } }
      responses:
        '200':
          description: Everybody, plus the over-allocated subset.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/StaffingCapacityView' }
        '403': { $ref: '#/components/responses/Forbidden' }

  /staffing/capacity/me:
    get:
      tags: [staffing]
      operationId: getMyCapacity
      summary: The caller's own capacity over a window
      description: >
        No staffing permission — it is the caller's own figure (017 D-m). Serves the timesheet's
        `Period capacity` tile and My Work's This-period meter, both of which pass the **period's**
        bounds and not a week's (016 D-a).
      parameters:
        - { name: from, in: query, required: true, schema: { type: string, format: date } }
        - { name: to, in: query, required: true, schema: { type: string, format: date } }
      responses:
        '200':
          description: The caller's capacity, net of absence.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/SelfCapacity' }
        '400': { $ref: '#/components/responses/BadRequest' }

  /projects/{projectId}/staffing:
    get:
      tags: [staffing]
      operationId: getProjectStaffing
      summary: Who is on this project and at what percentage
      description: >
        Requires `staffing:read` on **this project** (PROJECT scope) — story S1's "I open a
        project's staffing". Each person carries their company-wide total and the over-allocation
        flag and **no other project's name** (017 D-f).
      parameters:
        - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
        - { name: from, in: query, schema: { type: string, format: date } }
        - { name: to, in: query, schema: { type: string, format: date } }
      responses:
        '200':
          description: This project's allocations and the people behind them.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ProjectStaffingView' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such project, or not visible to this caller. }

  /projects/{projectId}/allocations:
    post:
      tags: [staffing]
      operationId: createAllocation
      summary: Allocate a person to this project at a percentage for a range
      description: >
        Requires `staffing:allocate` on this project. Two allocations of the same person to the
        same project over overlapping days are refused with `409` rather than merged (017 D-n);
        the same person on two *different* projects is the ordinary case and is what
        over-allocation reports.
      parameters:
        - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/AllocationInput' }
      responses:
        '201':
          description: The allocation.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Allocation' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such project, or the person is not a member of this company. }
        '409': { $ref: '#/components/responses/Conflict' }

  /projects/{projectId}/allocations/{allocationId}:
    patch:
      tags: [staffing]
      operationId: updateAllocation
      summary: Change an allocation's percentage or range
      parameters:
        - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
        - { name: allocationId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/AllocationPatch' }
      responses:
        '200':
          description: The allocation as stored.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Allocation' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such allocation on this project. }
        '409': { $ref: '#/components/responses/Conflict' }
    delete:
      tags: [staffing]
      operationId: deleteAllocation
      summary: Remove an allocation
      parameters:
        - { name: projectId, in: path, required: true, schema: { type: string, format: uuid } }
        - { name: allocationId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '204': { description: Removed. }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { description: No such allocation on this project. }
```

- [x] **Step 4: Regenerate both clients**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

Expected: both files change; nothing else in `frontend/src` or `packages/*/src` does.
`packages/client/src/schemaFreshness.test.ts` is what fails if only one was regenerated.

- [x] **Step 5: Add a factory per new resource to BOTH `fixtures.ts` files**

~~Inside the marked region of `frontend/src/api/contractBodies.ts` … edit one, edit both, and paste
rather than retype.~~ → **superseded, see correction 2.** Those two files are the schema walker and
hold no bodies; the region is unchanged by this task. What the doubles serve comes from the two
`src/test/fixtures.ts` files, which are **not** paired and are written in each file's own idiom.

`frontend/src/test/fixtures.ts` gains `aPersonProfile`, `aSkill`, `anAbsence`, `anAllocation`,
`aPersonCapacity`, `aStaffingCapacityView`, `aSelfCapacity` and `aProjectStaffingView`, each typed to
the contract and never cast. `packages/client/src/test/fixtures.ts` gains the same eight as
`personProfile`, `skill`, `absence`, `allocation`, `personCapacity`, `staffingCapacityView`,
`selfCapacity` and `projectStaffingView`, plus three `IDS` entries (`allocation`, `absence`, `skill`).

Two defaults are decisions rather than values, and both are written into the factories' own comments:

- **`weeklyHours: 40` is the one non-inert default in either file.** A profile exists to record
  contracted hours and nobody is contracted for none. The absence that matters is the *missing
  profile*, which is `profile: null` with four hours figures nulled beside it — a different state,
  and the one that must never render as `0h` or as an invented 40 (correction 4 above).
- **`utilisationPercent: null` is the default, not a scenario.** Allocated capacity is zero in the
  inert body, and D-i is explicit that `0%` claims the person is idle where the true statement is
  that nobody has planned their time.

- [x] **Step 6: The staffing type exports**

`frontend/src/api/types.ts`, in the block after `time`'s: one `export type X = S['X']` per schema, so
a screen and a fixture name a shape rather than reaching into the generated file. Three carry the
doc comment the rest do not need — `AbsenceKind` (why `HOLIDAY` is a member and not a calendar),
`PersonProfile` (a date, not a count of years) and `PersonCapacity` (which nulls mean what).

- [x] **Step 7: Four CLI verbs**

`packages/cli/src/reads.ts` — `staffing directory` (`staffing:directory`, COMPANY) and
`staffing project` (`staffing:read`, PROJECT). `packages/cli/src/writes.ts` — `staffing allocate`
(`create`, `staffing:allocate`) and `staffing absence` (`create`, `staffing:absence`).
`commands.ts` needs no edit: `COMMANDS` is `[...READS, ...WRITES]`.

```ts
/**
 * `tcms staffing directory --skill Playwright --free-from 2026-10-01` — the company view.
 *
 * **It costs `staffing:directory`, which is a COMPANY-scope key** … A PM asking for this gets a 403,
 * and the honest answer to their actual question is the next command down: theirs is *who is on my
 * project*, and that is `tcms staffing project`.
 */
```

Four things in there are decisions rather than spellings, and each is in the code's own comment:
`--skill` takes NAMES, repeats and is ANDed, resolved against `GET /staffing/skills` first (an
unresolved name filtered server-side answers *nobody matches* for a typo, which is indistinguishable
from a real answer); `--person` on the writes takes a name or an email against `…/members`, with a
company member who has no grant here still allocatable by id; `staffing absence` with no `--person`
records the caller's own and needs no staffing key (D-h), reading `/me` for the id rather than
guessing one; and an hours column prints an em dash where there is no profile, never `0h`.

`memberIdOf` is exported from `reads.ts` for `writes.ts` — `assigneeIds`' lookup for one person,
exported rather than copied so the two do not become two answers to *who may this project name*.

- [x] **Step 8: Three MCP tools**

`packages/mcp/src/reads.ts` — `tcms_read_staffing`; `writes.ts` — `tcms_write_allocation`
(`allocate` · `change` · `remove`) and `tcms_write_absence` (`record` · `remove`). `tools.ts` needs
no edit, for `commands.ts`' reason.

`tcms_read_staffing` is the surface's **second `any`-gated read**, and the gate is a SCOPE split
rather than an owner/approver one: `staffing:directory` is COMPANY and `staffing:read` is PROJECT,
and neither role that asks holds the other's key, so gating it `all` would hide the whole noun from
both. It carries a `narrow(held)` sentence naming the half this credential reaches.

Each description says the four things a model relaying half of them would get wrong — a null hours
figure is *unknown*, not 0 and not 40; `utilisationPercent` is null, not zero, when nobody has
allocated them; over-allocation is the highest single DAY and not a month's sum; and an absence is
not a note on a calendar but the thing that removes those hours from every capacity figure there is.
All three end with `tcms_read_timesheet`'s sentence: **nothing on this surface returns a rate or a
cost.** Measured at 1140, 1010 and 1039 bytes against the host's 2KB ceiling.

**`staffing:profile` gets no tool**, and it is a decision rather than a gap: both its writes take a
user UUID, and §3.3 of the surface doc puts the company directory outside this surface — so nothing
here can obtain one. It is the `tcms_review_verdict` assignees exclusion, one module over.

- [x] **Step 9: The three registry checks, and the shipped guide**

None of these was in this block's file list and all four fail loudly rather than silently:

- `packages/cli/src/writeConformance.test.ts` — an `INVOCATIONS` entry per new write command, plus a
  `CREATE_WITHOUT_A_KEY` entry for `staffing absence` (correction 5). It is the first CREATE in that
  map rather than an edit with nothing to precondition on.
- `packages/mcp/src/writeConformance.test.ts` — five new arms, five entries, plus
  `tcms_write_allocation.change` (no `If-Match`; `Allocation` carries no `lockVersion` on the wire)
  and `tcms_write_absence.record` in the by-decision map.
- `packages/mcp/src/writes.test.ts` — `expect(TOOLS).toHaveLength(34)` → `37`, with the reason
  written beside it in the running notation that file uses.
- `frontend/src/features/help/sections.tsx` — the two terminal blocks, the Reading/Writing lists and
  **the `N tools` prose**, all three pinned by `guideCopies.test.ts` in both packages, in both
  directions.

- [x] **Step 10: Gate**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run lint --workspaces
~/bin/tcms-remote packages npm run test:run --workspaces
~/bin/tcms-remote frontend npm run build
```

Expected: all green. `contractPairing.test.ts`, `schemaFreshness.test.ts`, `citedFiles.test.ts` and
`matrixCopies.test.ts` are the four most likely to catch a slip here.

**Run 2026-09-04, on `shanks`:** packages build clean; `test:run --workspaces` **535 tests green**
(client 138, cli 168, mcp 229); `lint --workspaces` 0 warnings across all three; frontend `build`
and `lint` clean. The frontend's **full** `test:run` is 2703 green and **2 red**, both in
`CaseGrid.test.tsx`'s selection block and both **pre-existing on `master`** — verified by stashing
this work and re-running that file at `HEAD`. See the landed note above.

- [x] **Step 11: Commit**

```bash
git add docs/specs/openapi.yaml docs/specs/2026-08-08-mcp-tool-and-command-surface.md \
        docs/plans/017-staffing.md \
        frontend/src/api/schema.d.ts frontend/src/api/types.ts frontend/src/test/fixtures.ts \
        frontend/src/features/help/sections.tsx \
        packages/client/src/schema.ts packages/client/src/test/fixtures.ts \
        packages/cli/src/reads.ts packages/cli/src/writes.ts \
        packages/cli/src/writeConformance.test.ts \
        packages/mcp/src/reads.ts packages/mcp/src/writes.ts \
        packages/mcp/src/writeConformance.test.ts packages/mcp/src/writes.test.ts
git commit -m "feat(staffing): the staffing contract, both clients, and the CLI/MCP verbs"
```

~~`frontend/src/api/contractBodies.ts`, `packages/client/src/test/contractBodies.ts`,
`packages/cli/src/commands.ts`, `packages/mcp/src/tools.ts`~~ — none of the four changed, for the
reasons in corrections 2 and the file list.

**Out of scope:** nothing is served yet. Every operation here 404s until T3, because no controller
exists — which is the honest not-implemented state, not a stub returning an empty array.

---

## T2 — `V10`: the five staffing tables

**Deliverable:** `backend/src/main/resources/db/migration/V10__staffing.sql` applied — on an empty
database and on the lab's already-migrated `V9` one — creating `person_profile`, `skill`,
`person_skill`, `allocation` and `absence`, each with `company_id UUID NOT NULL` leading every
composite index and each carrying a FORCE RLS policy whose predicate is byte-identical to V1's;
`SchemaConventionTest` green.
**Stack:** `be`
**Agent:** `data-architect`
**Est. context:** ~95k — 1 migration created (~230 lines), 1 test modified, 1 spec modified; V9 read
as the pattern (210 lines); 3 skills; 3 spec sections cited
**Depends on:** T1 (the wire shapes the columns have to serve)

**LANDED 2026-09-04 — `V10__staffing.sql`, and the version the directory gave was `V10`.** `ls` on
the day read `V1 … V9`, so for the first time in five weeks that column needed no correction.
Nothing below this note was rewritten for a version change, because there was none. Three things
this task landed differently from the draft SQL below, all of them recorded here rather than left
for a reader to diff:

1. **There is no `ix_person_profile_user`.** The draft listed one on `(company_id, user_id)` beside
   `CONSTRAINT uq_person_profile UNIQUE (company_id, user_id)` — and a `UNIQUE CONSTRAINT` *is* a
   btree index, on exactly those columns in exactly that order. The second one would double the write
   cost of every profile edit and be chosen by the planner never. Every lookup the module makes seeks
   the constraint's index. Dropped, with the reason written into the migration where the next reader
   will look for the missing line.
2. **The gate is `com.tcms.tenancy.SchemaConventionTest`, not `com.tcms.schema.…`** — this plan's own
   prompt block named a package that does not exist, and the corrected FQN is in the block above.
   The policy-count assertion moved `95 → 100`.
3. **`skill.name CITEXT` costs T3 a `columnDefinition`, and that is a startup failure rather than a
   compile one.** `citext` reports as `Types#OTHER` while a `String` maps to `varchar`, so
   `ddl-auto: validate` refuses to boot with *"found [citext (Types#OTHER)], but expecting
   [varchar(255)]"* unless the entity says `@Column(columnDefinition = "citext")` — `AppUser.email`
   and `CompanyEmailDomain.domain` are the two precedents, and T3's prompt block now carries the
   trap. The draft's justification for the type ("citext, like every other name in V1") was also
   wrong on the facts: V1 uses `citext` for two email columns only and `TEXT` + a unique index on
   `lower(name)` for `team`, `board` and `issue_label`. The type is still the right one here, for the
   reason the migration now gives — this vocabulary is looked up **by name on the write path** — but
   it is a departure from the name precedent rather than an instance of it.

**The check Testcontainers structurally cannot do was run.** A throwaway Postgres 17 with the real
two-role split, migrated `V1 … V9` only — 108 tables, 95 policies, which is the lab's shape — then
`V10` applied on top of it: all five tables `ENABLE`d and `FORCE`d with one policy each, 100 policies
total, and `tcms_seed_company_defaults` / `tcms_seed_project_defaults` both still present. That last
line is the point of §7 below: the only way this file could have broken the seed functions was by
restating them, and it does not.

**`./gradlew build` is RED at the end of this task and the four failures are T1's, not this one's.**
`3901 tests completed, 4 failed`: `ContractConventionTest`'s two schema/record pairing checks,
`ContractEnumTest.everyNamedContractEnumNamesTheServerType` and
`ContractParameterBindingTest.everyOperationIsServedByAHandler` — T1 put 17 schemas and 15 operations
into `openapi.yaml` with nothing serving them, and **T3 is what closes all four**. Verified rather
than assumed: this task's five files were stashed and the three classes re-run at `fe305a88`, giving
the identical four. Not the fd-limit shape either — `docs/specs/shanks-runner-details.md`'s failure
prints hundreds of `Could not write XML test results` lines with *zero* assertion failures, and this
run printed none of those and four assertions. Everything this task owns is green:
`SchemaConventionTest` all six, and the whole tenancy, RLS and migration surface with it.


**Files:**
- Create: `backend/src/main/resources/db/migration/V10__staffing.sql`
- Modify: `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java` (the policy-count assertion, `95` → `100`) — the package is `tenancy`, not `schema`
- Modify: `docs/specs/2026-07-30-tcms-erd.md`

- [ ] **Step 1: Re-read the migration directory. Do not skip this.**

```bash
ls backend/src/main/resources/db/migration/
```

Expected on 2026-09-04: `V1__initial_schema.sql … V9__time.sql`, so this file is **`V10`**. If the
listing shows anything higher, take the next free number and correct plan 009's table and this
plan's header in the same commit. That column has been wrong five times in five weeks;
`out-of-order` is unset, so a reused number is silently not applied and Testcontainers cannot show
you the conflict.

- [ ] **Step 2: Write the migration**

`backend/src/main/resources/db/migration/V10__staffing.sql`. Read `V9__time.sql` first — the header,
the per-table `COMMENT ON`, and the RLS `DO $$` block with its explicit count assertion are the house
style, and the count assertion is load-bearing rather than decorative.

```sql
-- =============================================================================
-- V10 — staffing (plan 009 module M7, plan 017)
--
-- THE VERSION IS V10, AND THE DIRECTORY IS WHAT SAYS SO. 009's table reserved V9
-- for this module, then corrected itself to V10 when M6 landed first. `ls` on
-- 2026-09-04: V1..V9, V9__time.sql being M6's. Fifth correction to that column
-- in five weeks. `out-of-order` is unset in application.yml, so a reused number
-- is SILENTLY not applied, and Testcontainers builds every test database from
-- empty and cannot show the conflict.
--
-- Five tables. NO capacity column anywhere: capacity is derived from
-- weekly_hours, absence and the calendar on every read (017 D-a). A stored
-- capacity is a cache with four invalidators and the fourth — the calendar
-- rolling forward — cannot be hooked.
--
-- NO rate, NO cost. That is M8, and it is the sharpest boundary in the
-- expansion (scope §7). An allocation is a percentage of somebody's time, and
-- what that time is worth is a question this module must not be able to answer.
-- =============================================================================

-- 1. PERSON_PROFILE — contracted hours, and the three facts directory.html shows.
--
-- weekly_hours is the ONLY input to capacity. Daily capacity is this over five,
-- Monday to Friday (017 D-c): there is no public-holiday calendar, because no
-- mockup configures one and a company-wide holiday in a product serving several
-- client companies from one instance would have to be per company AND per
-- country. A public holiday is an `absence` of kind HOLIDAY, which is per person
-- and already zeroes capacity everywhere.
--
-- experience_since is a DATE and not a count of years (017 D-b). directory.html
-- renders "6 years"; a stored integer is right the day it is typed and wrong
-- every day after, silently, and nothing here would ever tell anybody.
--
-- A person with NO ROW HERE has no capacity figure at all — not zero, and not a
-- default of 40. Every hours figure the API serves for them is null and the
-- screens read em dash. That is a real state (nobody has recorded their
-- contracted hours) and inventing a number for it is the failure 016 D-j
-- refused to commit.
CREATE TABLE person_profile (
    id               UUID          PRIMARY KEY,
    company_id       UUID          NOT NULL REFERENCES company(id),
    user_id          UUID          NOT NULL REFERENCES app_user(id),
    weekly_hours     NUMERIC(5,2)  NOT NULL DEFAULT 40,
    job_title        TEXT,
    location         TEXT,
    experience_since DATE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_person_profile UNIQUE (company_id, user_id),
    CONSTRAINT ck_person_profile_hours CHECK (weekly_hours >= 0 AND weekly_hours <= 80)
);
COMMENT ON TABLE person_profile IS
    'Contracted hours and the three facts directory.html shows about a person. Company-owned '
    'because one account may belong to several companies (identity package comment) and be '
    'contracted differently in each — 40h at one client, 20h at another.';
COMMENT ON COLUMN person_profile.experience_since IS
    '017 D-b — a DATE, so directory.html''s "6 years" is derived and cannot rot. A stored integer '
    'is correct on the day it is typed and wrong every day after, with nothing to notice.';
CREATE INDEX ix_person_profile_user ON person_profile (company_id, user_id);

-- 2. SKILL — the company's vocabulary. citext, like every other name in V1.
CREATE TABLE skill (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    name       CITEXT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_skill_name UNIQUE (company_id, name),
    CONSTRAINT ck_skill_name CHECK (length(trim(name)) > 0)
);
COMMENT ON TABLE skill IS
    'A company''s skill vocabulary. CITEXT so "Playwright" and "playwright" are one skill — a '
    'directory whose filter misses half the people who have the skill is worse than no filter.';

-- 3. PERSON_SKILL — the join, and it carries company_id like every other join
--    table in this schema.
--
-- V1 shipped five join tables WITHOUT company_id and the omission was found by
-- scripting the comparison, not by reading (working agreement). A join table is
-- the easiest place in a schema to forget the isolation column and the hardest
-- place to notice it is missing, because both its parents are isolated and the
-- rows look right in every test that uses one company.
--
-- NO proficiency level. directory.html draws chips with no level on them, and a
-- level nothing renders is a column that would be filled in inconsistently by
-- three people and then used to filter.
CREATE TABLE person_skill (
    company_id UUID        NOT NULL REFERENCES company(id),
    user_id    UUID        NOT NULL REFERENCES app_user(id),
    skill_id   UUID        NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, user_id, skill_id)
);
CREATE INDEX ix_person_skill_skill ON person_skill (company_id, skill_id, user_id);
COMMENT ON INDEX ix_person_skill_skill IS
    'The directory''s "who has this skill" filter reads this one. skill_id leads because that is '
    'the question; the (company_id, user_id, skill_id) PK serves "what does this person know".';

-- 4. ALLOCATION — a percentage of a person's time, on a project, over a range.
--
-- project_id is a real FK: `project` is a foundation module. person_id
-- references app_user, which is instance-level and has no company_id of its own
-- — the company boundary on this row is its OWN company_id, which is what the
-- RLS policy below reads, exactly as time_entry does.
--
-- percent is a whole number 1..100 and is a percentage OF THAT PERSON'S OWN
-- CAPACITY, not of a notional 40-hour week. Somebody contracted 20 hours who is
-- allocated 50% is allocated 10 hours, and the same 50% on a full-timer is 20.
--
-- NO EXCLUSION CONSTRAINT on (person, project, range), deliberately, and this is
-- the one place a reader will expect one. An EXCLUDE USING gist would need
-- btree_gist for the two UUID equality terms, and the failure it produces is a
-- Postgres constraint-violation string that the API would have to pattern-match
-- to turn into 017 D-n's 409 naming the conflicting range. The overlap check
-- lives in AllocationService, over a query this index serves, and
-- AllocationOverlapTest asserts it against a real Postgres.
CREATE TABLE allocation (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    project_id   UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    person_id    UUID        NOT NULL REFERENCES app_user(id),
    percent      SMALLINT    NOT NULL,
    starts_on    DATE        NOT NULL,
    ends_on      DATE        NOT NULL,
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_allocation_percent CHECK (percent >= 1 AND percent <= 100),
    CONSTRAINT ck_allocation_range   CHECK (ends_on >= starts_on)
);
COMMENT ON TABLE allocation IS
    'A percentage of one person''s capacity on one project over a date range. The percentage is of '
    'THEIR capacity, not of a notional 40h week: 50% of a 20h contract is 10 hours.';
COMMENT ON COLUMN allocation.percent IS
    'Whole percent, 1..100. Over-allocation is the sum of overlapping percentages ON A DAY '
    '(017 D-e) — a monthly sum would flag two consecutive half-month allocations at 200%.';
CREATE INDEX ix_allocation_person  ON allocation (company_id, person_id, starts_on, ends_on);
CREATE INDEX ix_allocation_project ON allocation (company_id, project_id, starts_on, ends_on);

-- 5. ABSENCE — days somebody is away, and the fraction of each one.
--
-- day_fraction rather than a half_day boolean (017 D-d): member.html shows
-- "29 Aug · half day", and a boolean answers that mockup and nothing else, while
-- start/end TIMES would invent a working-hours-of-day model this product has no
-- other use for.
--
-- An absence is per person and needs NO project. It zeroes capacity everywhere
-- capacity is shown — staffing, the directory, the timesheet's KPI, My Work's
-- meter — because all four read the one function that subtracts it (017 D-a).
-- That is the acceptance criterion of story S1's third bullet, and the reason it
-- can be met is that no screen computes capacity for itself.
CREATE TABLE absence (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    person_id    UUID          NOT NULL REFERENCES app_user(id),
    starts_on    DATE          NOT NULL,
    ends_on      DATE          NOT NULL,
    kind         TEXT          NOT NULL,
    day_fraction NUMERIC(3,2)  NOT NULL DEFAULT 1.00,
    note         TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_absence_range CHECK (ends_on >= starts_on),
    CONSTRAINT ck_absence_kind  CHECK (kind IN ('LEAVE','SICK','HOLIDAY','OTHER')),
    CONSTRAINT ck_absence_fraction
        CHECK (day_fraction > 0 AND day_fraction <= 1)
);
COMMENT ON TABLE absence IS
    'Days somebody is away. HOLIDAY is how a public holiday is recorded — there is no holiday '
    'calendar (017 D-c), and a per-person row is what actually zeroes capacity.';
COMMENT ON COLUMN absence.day_fraction IS
    '017 D-d — the share of each day in the range that is absent. 1.00 whole, 0.50 the half day '
    'member.html shows. Not a boolean, so a half day is not a second table.';
CREATE INDEX ix_absence_person ON absence (company_id, person_id, starts_on, ends_on);

-- 6. ROW LEVEL SECURITY — five more forced policies, predicate byte-identical to
--    V1's (SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate
--    compares the text and fails equally on an explicit WITH CHECK — a
--    USING-only policy reuses USING as the insert check, so the read boundary IS
--    the write boundary).
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to the table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an owner-run
-- query reads every company's rows (docs/specs/tenancy-invariants.md).
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['person_profile','skill','person_skill','allocation','absence'];
BEGIN
    FOREACH t IN ARRAY protected LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = t) THEN
            RAISE EXCEPTION 'the RLS list names a table that does not exist: %', t;
        END IF;
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)', t);
    END LOOP;

    IF array_length(protected, 1) <> 5 THEN
        RAISE EXCEPTION 'expected 5 new company-isolated tables, found %', array_length(protected, 1);
    END IF;
END $$;

-- 7. tcms_seed_project_defaults IS DELIBERATELY NOT TOUCHED.
--
-- Nothing here is a per-project default row. A new project starts with no
-- allocations, which is the correct empty state — staffing.html's own empty
-- reading is "nobody is on this yet", not a row of zeroes. The function is not
-- restated "to be safe": CREATE OR REPLACE FUNCTION replaces the whole body
-- (V3 §5, V8 §5, V9 §6), so reproducing it is the only way this file could
-- break it, and a block omitted from the reproduction silently stops seeding.
--
-- The app role needs no explicit GRANT: ALTER DEFAULT PRIVILEGES FOR ROLE
-- tcms_owner covers every new table in schema public
-- (deploy/postgres/initdb/10-tcms-roles.sql).
```

- [ ] **Step 3: Run the schema convention gate**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest'
```

Expected: PASS. If it fails on a policy predicate, the failure text prints both strings — they must
match V1's **byte for byte**, and an added `WITH CHECK` counts as a difference.

- [ ] **Step 4: Prove it applies to an already-migrated database, not only an empty one**

Testcontainers always builds from empty, so it cannot show you a migration that only works on a fresh
database. This is the check it cannot do:

```bash
docker compose up -d db          # a database still at V9
docker compose run --rm backend  # Flyway migrates V9 -> V10, then ddl-auto: validate runs
```

Expected: `Successfully applied 1 migration`, then a clean start. `validate` at startup is the gate
that catches entity drift, and it is the reason a booting context is itself a test.

- [ ] **Step 5: Update the ERD**

`docs/specs/2026-07-30-tcms-erd.md` — the five entities in the existing style, and one line under
§0.1's `company_id` rule noting that `person_skill` carries it like every other join table, for the
reason V1's five omissions gave.

- [ ] **Step 6: Full backend gate and commit**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Read `docs/specs/shanks-runner-details.md` first: `BUILD FAILED` with `Could not write XML test
results` and **zero** assertion failures is an fd limit, not a red suite — `ulimit -n 65535;
./gradlew build`.

```bash
git add backend/src/main/resources/db/migration/V10__staffing.sql \
        backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java \
        docs/specs/2026-07-30-tcms-erd.md
git commit -m "feat(staffing): V10 — person_profile, skill, person_skill, allocation, absence"
```

**Out of scope:** no entity maps these tables yet, so `ddl-auto: validate` has nothing to check
against them until T3. That is the honest state of a schema-first task and not a gap.

---

## T3 — The `staffing` module: capacity, over-allocation, absence and utilisation

**Deliverable:** Every operation T1 declared is served by `com.tcms.staffing`; allocating one person
to two projects at 50% and 51% over overlapping days raises `overAllocated` with `peakPercent` 101;
an absence reduces `capacityHours` in `Capacity.of`, which is the single function all four capacity
surfaces read; `GET /staffing/people?skill=…&freeFrom=…` answers "who is free next month with skill
X" in one query; `./gradlew build` green with ArchUnit and `StaffingReadsNoTimeEntryTest` passing.
**Stack:** `be`
**Agent:** `be-java`
**Est. context:** ~150k — 26 source files created, 4 modified (2 in `project`, 1 in `identity`, 1
seeder), 6 test files created; V9's `time` module read as the pattern; 4 skills; 5 spec sections
cited. **At the top of the band and deliberately one task:** the capacity function, the services and
the controllers are one deliverable — a split leaving `Capacity` without a caller would be a task
whose acceptance criteria nothing can check.
**Depends on:** T2 (the tables), T1 (the wire shapes)

> **Landed 2026-09-04, with seven corrections to what this block predicted.** The four failures the
> prompt named as "the job" are closed and `./gradlew build` is green. **38 source files under
> `com/tcms/staffing`**, not the 26 this block estimated — the difference is one record per schema
> (16 of them) plus `CapacityReader` and `StaffingWindow`, which the block folded into the services.
>
> 1. **`allocation.percent` is `SMALLINT`, so the entity field is `short` and not `int`** — and this
>    is the trap list's `citext` warning with a different column. `ddl-auto: validate` refuses to
>    start with *"found [int2 (Types#SMALLINT)], but expecting [integer (Types#INTEGER)]"*, which
>    appears as **every `@SpringBootTest` in the suite failing at once** (3317 of 3934 on the first
>    full run) with the real message buried in a `SchemaManagementException` three causes down. The
>    wire carries an integer and `Allocation`'s accessors widen, so the narrowing lives in the one
>    place the column is described. A `columnDefinition = "smallint"` would have been the tempting
>    fix and is a guess about whether Hibernate's name fallback matches Postgres' `int2`.
> 2. **`project.PeopleDirectory` stopped being a functional interface**, and two existing tests were
>    passing it as a lambda: `execution.PeopleResolutionNullKeyTest` and
>    `repository.CaseOwnersNullKeyTest`. Both now use a named `EmptyDirectory` stub. **Neither method
>    was given a `default` body** — a default `membersOf` returning `List.of()` would let an
>    implementation silently answer *"nobody is in this company"*, which is the shape of every
>    fail-open defect the tenancy layer exists to prevent.
> 3. **There was a FIFTH architecture failure and it is a decision rather than a regression.**
>    `staffing.SkillView(UUID id, String name)` has `api.TeamRef`'s exact component set, and
>    `ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape` reported it. Its message offers
>    three resolutions and **none of them is available here**: importing `TeamRef` would make a
>    team's wire shape something this module can change, and "give it the field names of the concept
>    it is" is refused by the *other* rule — `openapi.yaml` declares `Skill` and `TeamRef` as two
>    schemas and fixes both members to `id` and `name`, so a rename fails
>    `ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers`. The two rules would each
>    demand the repair the other refuses. So a fourth answer was added in the house shape: a
>    `SHAPE_TWINS_BY_DECISION` map, pinned in **both** directions, with the argument at the entry and
>    a matching paragraph on `api.TeamRef` — whose javadoc had claimed since §175 that a copy "under
>    any name, in any module, fails the build". The bar for a second entry is written down: the two
>    must be different *schemas* in the contract, and a **third** twin means a minimal named
>    reference is a shape the contract should compose rather than re-declare.
> 4. **Two pre-existing records became homonyms of the new `Allocation` schema** —
>    `project.ProjectKeys.Allocation` and `tenancy.DisplayKeys.Allocation`, both
>    `(String prefix, long firstValue, int count)`, both *a reserved block of display-key numbers*.
>    Neither reaches the wire and neither is renameable without making it wrong about what it does
>    (reserving a block of numbers **is** an allocation), so both are `HOMONYMS` entries. This is the
>    map's second firing since §175 emptied it, and it cost two sentences exactly as that entry's own
>    paragraph predicted.
> 5. **`ModuleStructureTest`'s module list and `ArchitectureRulesTest` both had to be edited**, which
>    the prompt's "a fifth failure is a regression" line did not anticipate: the detected-module list
>    is asserted `containsExactly`, so a new package under `com.tcms` reddens it by construction.
>    That is the rule working — it exists so that whoever adds a module reads the
>    `allowedDependencies` rule they are now subject to.
> 6. **`CapacityReader.NOMINAL_WEEK` is the one place this module names 40 hours, and nothing derived
>    from it reaches the wire.** An unprofiled person still has real allocations, and passing zero
>    weekly hours would make every day's capacity zero — which `Capacity` correctly excludes from the
>    peak (being booked on a day you are away is not over-allocation), so somebody unprofiled and
>    allocated at 150% would have read as *not* over-allocated. That is a wrong answer where a null
>    hours figure is the right one. So the percentages are computed against a nominal week and every
>    hours figure for such a person is served as null, which is 016 D-j's refusal kept where it
>    matters: on the wire. `OverAllocationTest.unprofiledPeopleStillRaiseTheFlag` pins both halves.
> 7. **`POST /projects/{projectId}/allocations` honours no `Idempotency-Key`, and the shortfall is
>    recorded at the method rather than closed.** `V10` gave `allocation` no
>    `created_idempotency_key` column, so the contract's `200` replay arm is declared and
>    unimplemented — `time.TimeEntryController`'s precedent, stated the same way. What limits the
>    damage is D-n: a retry with the same range is refused as an overlap, so the duplicate a lost
>    response can produce is only one whose dates differ from the first. The fix is a column and a
>    lookup.
>
> **Three things this block asked for that landed differently and are worth knowing before T4:**
>
> - **`StaffingWindow` clamps a requested range to 366 days** rather than refusing one. `Capacity`
>   walks a day at a time per person, so the cost of a read is *days × people*; only
>   `GET /staffing/capacity/me` declares a `400`, so the other three have no honest way to refuse an
>   enormous range and inventing one would be a response the generated types say cannot happen.
> - **`totalPercent` on the project screen is the company-wide MEAN daily percentage**, matching
>   `PersonCapacity.allocatedPercent`, while `overAllocated` is read off the **peak**. Two statistics
>   on one row, deliberately: a mean is what a row label can carry and a peak is the only thing a
>   flag can honestly be read from.
> - **`utilisationPercent` is `0`, not null, for somebody who is allocated and has no approved
>   hours.** Null is reserved for the person nobody has allocated — the denominator not existing, not
>   the numerator being zero (D-i). `AbsenceZeroesCapacityTest.allocatedCapacityIsNetOfAbsence` pins
>   it.
>
> **The prompt blocks below need no rewriting.** Every response field name T4, T5 and T6 quote is the
> contract's and unchanged; the endpoints they name are the ones served.

**Files:**
- Create: `backend/src/main/java/com/tcms/staffing/` — `package-info.java`, `PersonProfile.java`,
  `Skill.java`, `PersonSkill.java`, `PersonSkillId.java`, `Allocation.java`, `Absence.java`,
  `AbsenceKind.java`, the five repositories, `Capacity.java`, `TimeFacts.java`,
  `StaffingService.java`, `AllocationService.java`, `ProfileService.java`,
  `StaffingController.java`, `AllocationController.java`, and the view/input records
- Modify: `backend/src/main/java/com/tcms/project/PeopleDirectory.java` (D-k),
  `backend/src/main/java/com/tcms/project/ProjectService.java` (D-l),
  `backend/src/main/java/com/tcms/identity/IdentityPeopleDirectory.java` (D-k),
  `backend/src/main/java/com/tcms/DemoWorkspaceSeeder.java`
- Test: `backend/src/test/java/com/tcms/staffing/CapacityTest.java`,
  `OverAllocationTest.java`, `AbsenceZeroesCapacityTest.java`, `AllocationOverlapTest.java`,
  `DirectoryQueryTest.java`, `StaffingReadsNoTimeEntryTest.java`

- [x] **Step 1: Write the failing capacity test first**

`backend/src/test/java/com/tcms/staffing/CapacityTest.java`. Pure JUnit 5 + AssertJ, **no Spring
context and no database** — `Capacity` is a function of three lists and a range, and the whole point
of extracting it is that the arithmetic every screen depends on is testable without a container.

```java
package com.tcms.staffing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CapacityTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 11);

    @Test
    @DisplayName("a 40-hour week over five working days is 40 hours")
    void fullWeek() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI, List.of(), List.of());

        assertThat(window.capacityHours()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("a weekend contributes nothing, so Mon-Sun is still 40")
    void weekendIsNotCapacity() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, MON.plusDays(6), List.of(), List.of());

        assertThat(window.capacityHours()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("a whole day of absence removes that day's eight hours")
    void absenceZeroesADay() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(), List.of(new Capacity.AbsentSpan(MON, MON, new BigDecimal("1.00"))));

        assertThat(window.capacityHours()).isEqualByComparingTo("32.00");
        assertThat(window.absenceHours()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("a half day removes half of it")
    void halfDayAbsence() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(), List.of(new Capacity.AbsentSpan(MON, MON, new BigDecimal("0.50"))));

        assertThat(window.capacityHours()).isEqualByComparingTo("36.00");
    }

    @Test
    @DisplayName("two overlapping allocations totalling 101 are the peak, and the flag")
    void overAllocationIsTheDailyPeak() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(
                        new Capacity.AllocatedSpan(MON, FRI, 50),
                        new Capacity.AllocatedSpan(MON, FRI, 51)),
                List.of());

        assertThat(window.peakPercent()).isEqualTo(101);
        assertThat(window.overAllocated()).isTrue();
        assertThat(window.peakOn()).isEqualTo(MON);
    }

    @Test
    @DisplayName("consecutive allocations are NOT summed — 100% then 100% is a peak of 100")
    void consecutiveIsNotOverAllocated() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(
                        new Capacity.AllocatedSpan(MON, MON.plusDays(1), 100),
                        new Capacity.AllocatedSpan(MON.plusDays(2), FRI, 100)),
                List.of());

        assertThat(window.peakPercent()).isEqualTo(100);
        assertThat(window.overAllocated()).isFalse();
    }

    @Test
    @DisplayName("a fully absent day is excluded from the peak — booked with no capacity behind it is not over-allocation")
    void fullyAbsentDayIsNotAPeak() {
        Capacity.Window window = Capacity.of(
                new BigDecimal("40"), MON, MON,
                List.of(new Capacity.AllocatedSpan(MON, MON, 120)),
                List.of(new Capacity.AbsentSpan(MON, MON, new BigDecimal("1.00"))));

        assertThat(window.overAllocated()).isFalse();
        assertThat(window.peakPercent()).isZero();
    }

    @Test
    @DisplayName("freeFrom is the first day under 100%, and null when there is none")
    void freeFrom() {
        Capacity.Window full = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(new Capacity.AllocatedSpan(MON, FRI, 100)), List.of());
        assertThat(full.freeFrom()).isNull();

        Capacity.Window partial = Capacity.of(
                new BigDecimal("40"), MON, FRI,
                List.of(new Capacity.AllocatedSpan(MON, MON.plusDays(1), 100)), List.of());
        assertThat(partial.freeFrom()).isEqualTo(MON.plusDays(2));
    }
}
```

- [x] **Step 2: Run it, and watch it fail for the right reason**

```bash
cd backend && ./gradlew test --tests 'com.tcms.staffing.CapacityTest'
```

Expected: FAIL, `cannot find symbol: class Capacity`. A failure naming anything else means the test
file is wrong, not the absent code.

- [x] **Step 3: Write `Capacity`**

`backend/src/main/java/com/tcms/staffing/Capacity.java`. **Pure — no Spring, no repository, no
`LocalDate.now()`.** Every date arrives as a parameter, which is the fifth of the untestable seams
`be-java-unit-testing` names.

```java
package com.tcms.staffing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * <strong>The one place capacity is computed, and every surface that shows it reads this.</strong>
 *
 * <p>That is not tidiness — it is how story S1's third acceptance criterion is met at all. "An
 * absence zeroes capacity <em>everywhere</em>" is a claim about four screens (the company staffing
 * view, the directory, the timesheet's KPI and My Work's meter), and the only way to make it true
 * rather than to check it four times is for none of them to compute anything. They ask for hours;
 * this decides what hours are.
 *
 * <h2>Derived on every read, never stored (017 D-a)</h2>
 *
 * <p>A stored capacity is a cache with four invalidators — the profile changing, an absence
 * recorded, an absence deleted, and the calendar rolling forward — and the last cannot be hooked by
 * anything. A capacity table that silently ages is the two-contradictory-percentages failure with a
 * schema behind it.
 *
 * <h2>A working day is Monday to Friday, and there is no holiday calendar (017 D-c)</h2>
 *
 * <p>A public holiday is an {@link AbsentSpan} of kind {@code HOLIDAY}. That is per person, which
 * is what a product serving several client companies from one instance needs, and it is already the
 * thing that zeroes capacity everywhere.
 *
 * <h2>Over-allocation is a daily peak, not a monthly sum (017 D-e)</h2>
 *
 * <p>{@code staffing.html} draws monthly bars and totals somebody at 130% for October; that is a
 * month's-eye view of a daily truth. 100% for the first half of a month and 100% for the second is
 * not over-allocation, and a monthly sum would report it as 200%. So the walk is per day and the
 * flag is read off the highest single day.
 *
 * <p>A day the person is <em>fully</em> absent is excluded from the peak. Being booked at 100% on a
 * day you are on leave is not an over-allocation — it is an allocation with no capacity behind it,
 * and the screen says a different thing about each.
 */
final class Capacity {

    /** One allocation's claim, flattened to what the walk needs. */
    record AllocatedSpan(LocalDate startsOn, LocalDate endsOn, int percent) {
    }

    /** One absence, flattened. {@code fraction} is the share of each day that is away. */
    record AbsentSpan(LocalDate startsOn, LocalDate endsOn, BigDecimal fraction) {
    }

    /**
     * Everything the four surfaces ask about one person over one window.
     *
     * @param peakOn      the day {@code peakPercent} was reached, or null when nothing is allocated
     * @param freeFrom    the first day under 100%, or null when they are full for the whole window
     */
    record Window(
            BigDecimal capacityHours,
            BigDecimal absenceHours,
            int allocatedPercent,
            BigDecimal allocatedCapacityHours,
            int peakPercent,
            LocalDate peakOn,
            boolean overAllocated,
            LocalDate freeFrom) {
    }

    private static final BigDecimal WORKING_DAYS_A_WEEK = new BigDecimal("5");

    static Window of(
            BigDecimal weeklyHours,
            LocalDate from,
            LocalDate to,
            List<AllocatedSpan> allocations,
            List<AbsentSpan> absences) {

        BigDecimal daily = weeklyHours.divide(WORKING_DAYS_A_WEEK, 4, RoundingMode.HALF_UP);

        BigDecimal capacity = BigDecimal.ZERO;
        BigDecimal lost = BigDecimal.ZERO;
        BigDecimal allocatedCapacity = BigDecimal.ZERO;
        long percentDaySum = 0;
        long workingDays = 0;
        int peak = 0;
        LocalDate peakOn = null;
        LocalDate freeFrom = null;

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!isWorkingDay(day)) {
                continue;
            }
            workingDays++;

            BigDecimal absentShare = absentShareOn(day, absences);
            BigDecimal present = BigDecimal.ONE.subtract(absentShare).max(BigDecimal.ZERO);
            BigDecimal dayCapacity = daily.multiply(present);

            capacity = capacity.add(dayCapacity);
            lost = lost.add(daily.subtract(dayCapacity));

            int allocatedToday = allocatedPercentOn(day, allocations);
            percentDaySum += allocatedToday;
            allocatedCapacity = allocatedCapacity.add(
                    dayCapacity.multiply(BigDecimal.valueOf(allocatedToday))
                            .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));

            // A day with no capacity behind it cannot be a peak — see the class comment.
            if (dayCapacity.signum() > 0 && allocatedToday > peak) {
                peak = allocatedToday;
                peakOn = day;
            }
            if (freeFrom == null && allocatedToday < 100) {
                freeFrom = day;
            }
        }

        int meanPercent = workingDays == 0 ? 0 : Math.toIntExact(percentDaySum / workingDays);

        return new Window(
                capacity.setScale(2, RoundingMode.HALF_UP),
                lost.setScale(2, RoundingMode.HALF_UP),
                meanPercent,
                allocatedCapacity.setScale(2, RoundingMode.HALF_UP),
                peak,
                peakOn,
                peak > 100,
                freeFrom);
    }

    private static boolean isWorkingDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /**
     * Overlapping absences add up and are capped at one whole day.
     *
     * <p>Two half-day rows on the same date is a person who recorded a morning and an afternoon
     * separately, and it means a whole day. Three of them still means a whole day, which is what
     * the cap is for — capacity may not go negative and take an hours total with it.
     */
    private static BigDecimal absentShareOn(LocalDate day, List<AbsentSpan> absences) {
        BigDecimal share = BigDecimal.ZERO;
        for (AbsentSpan absence : absences) {
            if (!day.isBefore(absence.startsOn()) && !day.isAfter(absence.endsOn())) {
                share = share.add(absence.fraction());
            }
        }
        return share.min(BigDecimal.ONE);
    }

    private static int allocatedPercentOn(LocalDate day, List<AllocatedSpan> allocations) {
        int total = 0;
        for (AllocatedSpan allocation : allocations) {
            if (!day.isBefore(allocation.startsOn()) && !day.isAfter(allocation.endsOn())) {
                total += allocation.percent();
            }
        }
        return total;
    }

    private Capacity() {
    }
}
```

- [x] **Step 4: Run the capacity test to green**

```bash
cd backend && ./gradlew test --tests 'com.tcms.staffing.CapacityTest'
```

Expected: 8 tests, PASS.

- [x] **Step 5: The module declaration**

`backend/src/main/java/com/tcms/staffing/package-info.java`. Read `time/package-info.java` first —
the two-rules-stated-first shape is the house pattern and the ArchUnit annotation is what enforces
the allowed list.

```java
/**
 * Staffing — who is on a project and at what percentage, who has which skill, and when anybody is
 * free (009 module M7, plan 017).
 *
 * <h2>What this module may not do, stated first because it is the failure the scope names</h2>
 *
 * <p>It <strong>reads {@code time}'s tables and never writes them</strong> (017 D-j). The whole
 * crossing lives in {@link com.tcms.staffing.TimeFacts}, which issues read-only SQL and returns
 * records declared here; <strong>nothing else in this package may name a foreign table.</strong>
 * Fifth instance of a pattern with a name in this codebase: {@code work.BoardFacts} reads
 * {@code tracker}, {@code execution.CaseFacts} reads {@code repository},
 * {@code tracker.release.ReleaseFacts} reads {@code execution}, {@code time.IssueFacts} reads
 * {@code tracker}. {@code StaffingReadsNoTimeEntryTest} asserts both halves.
 *
 * <p>ArchUnit cannot see this rule: SQL is not a Java dependency, so the guard has to be a test over
 * file text. That is why it exists as a test and not as an allowed-dependency entry.
 *
 * <h2>The other thing it may not do</h2>
 *
 * <p><strong>No rate, no cost, no invoice value.</strong> An allocation is a percentage of
 * somebody's time; what that time is worth is M8's, and scope §7 calls it the sharpest boundary in
 * the expansion. {@code time.TimeMoneyBoundaryTest} was written before M8 existed so M8 would
 * inherit it, and the same argument applies here — an Account Manager sees the client, the hours and
 * the utilisation, and never a rate.
 *
 * <h2>Capacity is derived, and there is exactly one function that derives it</h2>
 *
 * <p>{@link com.tcms.staffing.Capacity} — see its own comment. Four surfaces show capacity and none
 * of them computes it, which is the only way "an absence zeroes capacity everywhere" is a fact
 * rather than a claim checked four times.
 *
 * <h2>Two scopes, and the split is load-bearing</h2>
 *
 * <p>{@code staffing:read} and {@code staffing:allocate} are PROJECT-scope and go through
 * {@link com.tcms.project.ProjectService}'s project gates. {@code staffing:directory},
 * {@code staffing:profile} and {@code staffing:absence} are COMPANY-scope and go through
 * {@link com.tcms.project.ProjectService#readInCompany}. Asking a COMPANY-scope key at project
 * scope is the "held anywhere in this company" shortcut the roles spec records rejecting; asking a
 * PROJECT-scope key company-wide is the unreachable row §43 repaired for {@code company:read}.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>The five foundation modules. {@code identity} is asked <em>what is this person called</em> and
 * <em>who is in this company</em>, never <em>may they</em> — the permission question goes through
 * {@code project.ProjectService}, which is the only place in this module that decides anything
 * about access, exactly as in {@code time}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project", "storage"})
package com.tcms.staffing;
```

- [x] **Step 6: The entities and repositories**

Five entities mapping T2's tables, plain UUID foreign keys and no associations (the `identity`
package comment's rule, followed by `project` and `time`). Five Spring Data interfaces, **each
carrying `@Transactional(readOnly = true) on the interface`** — CLAUDE.md's rule, and the failure it
prevents is silent: a derived method with no transaction runs with no company set and every
RLS-protected read returns zero rows.

```java
@Repository
@Transactional(readOnly = true)
public interface AllocationRepository extends JpaRepository<Allocation, UUID> {

    List<Allocation> findByCompanyIdAndPersonIdInAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
            UUID companyId, Collection<UUID> personIds, LocalDate to, LocalDate from);

    List<Allocation> findByCompanyIdAndProjectIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
            UUID companyId, UUID projectId, LocalDate to, LocalDate from);

    /*
      The overlap check behind 017 D-n's 409. Note the reversed bounds: two ranges overlap when
      each starts on or before the other ends, which reads backwards and is the standard form.
      `excludeId` is the allocation being PATCHed, so an edit does not conflict with itself.
    */
    @Query("""
            select a from Allocation a
             where a.companyId = :companyId
               and a.projectId = :projectId
               and a.personId  = :personId
               and a.id       <> :excludeId
               and a.startsOn <= :endsOn
               and a.endsOn   >= :startsOn
            """)
    List<Allocation> overlapping(
            @Param("companyId") UUID companyId,
            @Param("projectId") UUID projectId,
            @Param("personId") UUID personId,
            @Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn,
            @Param("excludeId") UUID excludeId);
}
```

- [x] **Step 7: `TimeFacts` — the only file naming a `time` table**

```java
package com.tcms.staffing;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The only file in this module that names a {@code time}-owned table.</strong>
 *
 * <p>Read-only, and that is the module's rule rather than a property of this class — see the
 * package comment and {@code StaffingReadsNoTimeEntryTest}, which asserts both that nothing here
 * writes those tables and that exactly one file names them.
 *
 * <p><strong>APPROVED hours only, and utilisation therefore denominates over approved</strong>
 * (017 D-i, 016 D-d). {@code approvedHours} and {@code pendingHours} are separate on the wire and
 * nothing in this product sums them; hours nobody has accepted are not yet a fact about the money,
 * and Finance holds {@code staffing:directory} for precisely the money reading of this number.
 *
 * <p>RLS covers every statement here: it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}.
 */
@Component
class TimeFacts {

    private static final String APPROVED_BY_PERSON = """
            SELECT e.person_id, COALESCE(SUM(e.hours), 0)
              FROM time_entry e
              JOIN timesheet_period p ON p.id = e.period_id
             WHERE e.person_id IN (:personIds)
               AND e.spent_on BETWEEN :from AND :to
               AND p.state = 'APPROVED'
             GROUP BY e.person_id
            """;

    private final EntityManager entities;

    TimeFacts(EntityManager entities) {
        this.entities = entities;
    }

    /** Approved hours per person over the window. A person with none is absent from the map. */
    @SuppressWarnings("unchecked")
    Map<UUID, BigDecimal> approvedHoursBy(
            Collection<UUID> personIds, LocalDate from, LocalDate to) {

        if (personIds.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
            // error, and a company whose every member is unprofiled is an ordinary state.
            return Map.of();
        }
        Map<UUID, BigDecimal> byPerson = new HashMap<>();
        for (Object[] row : (java.util.List<Object[]>) entities
                .createNativeQuery(APPROVED_BY_PERSON)
                .setParameter("personIds", personIds)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()) {
            byPerson.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return byPerson;
    }
}
```

- [x] **Step 8: The two foundation additions (D-k, D-l)**

`com/tcms/project/PeopleDirectory.java` — one method on the port:

```java
    /**
     * Every present member of this company, in display-name order.
     *
     * <p>Added for {@code staffing}'s directory, which lists <em>everybody</em> — `directory.html`'s
     * "3 of 11 people match" counts members, not people who happen to have a staffing profile. No
     * existing port answered it: {@link #resolve} resolves ids the caller already has, and
     * {@code identity.AdminDirectoryService.listUsers} gates on admin reach, which an Account
     * Manager holding only {@code staffing:directory} does not have.
     *
     * <p><strong>This is a naming read, not an access read.</strong> Whoever calls it has already
     * been gated; this answers "who is here", never "may they".
     */
    List<UserRef> membersOf(UUID companyId);
```

`com/tcms/project/ProjectService.java` — the COMPANY-scope read gate:

```java
    /**
     * Does this caller hold a COMPANY-scope key? The companion to {@link #holds}, which asks the
     * PROJECT-scope question.
     *
     * <p>The two are not interchangeable and the difference is not a detail — see
     * {@link RequiredPermission}. This one resolves the SYSTEM and COMPANY terms only, which is
     * what makes a company-scope key mean the company rather than "somewhere".
     */
    public boolean holdsInCompany(UUID userId, String permission) {
        return access.canDoInCompany(userId, permission);
    }

    /**
     * A read gated on a COMPANY-scope key — {@link #createGranted}'s read-only sibling.
     *
     * <p>{@code createGranted} was the only company-scope gate here and it is a write, so a leaf
     * module needing a company-scope <em>read</em> had the choice of misusing it or importing
     * {@code identity.PermissionEvaluator} — which would put a second answer to "may they" in a
     * module whose package comment says it has none.
     *
     * <p>There is no {@link GateResult.Unreachable} outcome: a company-wide question has no target
     * to be invisible, so the only two answers are 403 and the value. That is
     * {@code identity.AuditLogService}'s reasoning for the company trail, one module over.
     */
    @Transactional(readOnly = true)
    public <T> GateResult<T> readInCompany(UUID userId, String permission, Supplier<T> read) {
        if (!access.canDoInCompany(userId, permission)) {
            return new GateResult.Denied<>();
        }
        return new GateResult.Allowed<>(read.get());
    }
```

`com/tcms/identity/IdentityPeopleDirectory.java` implements `membersOf` over
`CompanyMembershipRepository.findByCompanyId`, filtering `status != REMOVED` — the same filter
`OrgChartService.chartFor` applies, and for the same reason.

- [x] **Step 9: The three services, the two controllers, and the demo seed**

`StaffingService` (company reads — directory, capacity, utilisation), `AllocationService` (project
reads and writes, with D-n's overlap refusal), `ProfileService` (profiles, skills, absences with
D-h's self-or-key gate). Controllers thin, with exhaustive `switch` over `GateResult` — copy
`time/TimesheetController.java`'s `read` / `answer` helpers rather than re-deriving them.

`DemoWorkspaceSeeder` gains, for each demo person: a `person_profile` (weekly hours 40, a job title,
a city, an `experience_since`), two or three skills from a shared vocabulary, one or two allocations,
and — for exactly one person — a half-day absence. **Without this every capacity figure in the demo
reads as an em dash**, which is correct behaviour and a useless demonstration.

- [x] **Step 10: The four behaviour tests, against a real Postgres**

`OverAllocationTest` (50 + 51 across two projects ⇒ flag, peak 101), `AbsenceZeroesCapacityTest`
(the same absence reduces the company view, the person read and `/staffing/capacity/me` — three
assertions, one absence, because "everywhere" is the claim), `AllocationOverlapTest` (409 with the
conflicting range named; a PATCH of an allocation does not conflict with itself),
`DirectoryQueryTest` ("free next month with skill X" returns the right people and excludes the
skill-less and the fully-booked).

`StaffingReadsNoTimeEntryTest` reads the package's source text:

```java
    @Test
    @DisplayName("exactly one file in com.tcms.staffing names a time-owned table, and it never writes one")
    void oneCrossing() throws IOException {
        List<Path> naming = sourcesUnder("backend/src/main/java/com/tcms/staffing").stream()
                .filter(path -> mentions(path, "time_entry") || mentions(path, "timesheet_period"))
                .toList();

        assertThat(naming).extracting(Path::getFileName).extracting(Object::toString)
                .containsExactly("TimeFacts.java");
        assertThat(readString(naming.get(0)).toUpperCase(Locale.ROOT))
                .doesNotContain("INSERT INTO", "UPDATE ", "DELETE FROM");
    }
```

- [x] **Step 11: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
```

Expected: green, including `ArchitectureRulesTest` — a new module with an
`@ApplicationModule(allowedDependencies = …)` annotation is checked by it, and an import of
`com.tcms.time` from here is what it fails on.

```bash
git add backend/src/main/java/com/tcms/staffing backend/src/test/java/com/tcms/staffing \
        backend/src/main/java/com/tcms/project/PeopleDirectory.java \
        backend/src/main/java/com/tcms/project/ProjectService.java \
        backend/src/main/java/com/tcms/identity/IdentityPeopleDirectory.java \
        backend/src/main/java/com/tcms/DemoWorkspaceSeeder.java
git commit -m "feat(staffing): the staffing module, the capacity function, and utilisation"
```

**Out of scope:** no screen reads any of this until T4. The capability "a PM allocates a person" is
served by HTTP and reachable only through the CLI verb T1 built — which is what a stack-cut backend
task looks like, and it is why plan 009 states the cost rather than pretending the capability landed.

---

## T4 — The company screens: `/staffing` and `/directory`

**Deliverable:** `/staffing` renders `staffing.html` — the per-person allocation timeline over a
range picker, the Over-100% panel with its breakdown, the Free-capacity panel and the utilisation
panel — and `/directory` renders `directory.html` and answers "who is free next month with skill X"
through filters held in the URL; both are gated on `staffing:directory` and refuse with copy that
names the project screen; `npm run build`, `npm run lint` and `npm run test:run` green.
**Stack:** `fe`
**Agent:** `fe-react`
**Est. context:** ~140k — 9 components created, 2 files modified (`keys.ts`, `router.tsx`,
`AppShell.tsx`), 1 query module created, 4 test files created; the render kit and `DataRegion` read
as patterns; 3 skills; 2 mockups walked
**Depends on:** T3 (the endpoints)

**Files:**
- Create: `frontend/src/api/queries/staffing.ts`
- Create: `frontend/src/features/staffing/StaffingPage.tsx`, `AllocationTimeline.tsx`,
  `OverAllocatedPanel.tsx`, `FreeCapacityPanel.tsx`, `UtilisationPanel.tsx`, `DirectoryPage.tsx`,
  `DirectoryFilters.tsx`, `PersonCard.tsx`, `capacityCopy.ts`
- Create: `frontend/src/features/staffing/StaffingPage.test.tsx`, `DirectoryPage.test.tsx`,
  `AllocationTimeline.test.tsx`, `capacityCopy.test.ts`
- Modify: `frontend/src/api/queries/keys.ts`, `frontend/src/api/queries/index.ts`,
  `frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx`, `frontend/src/lib/labels.ts`,
  `frontend/src/lib/devFaults.ts`

- [ ] **Step 1: Build the fault switch first**

`frontend/src/lib/devFaults.ts` — *build the switch before the feature it proves*. Both guards stay
a literal `import.meta.env.DEV` so Rollup drops the module from a production build; routing that
check through an imported constant ships it.

```ts
/**
 * `tcmsFaults.staffing('no-profile')` — every person comes back with `profile: null` and every
 * hours figure null. This is the state no fixture will produce by accident and the one every
 * capacity surface has a second branch for (plan 017, correction 4): the tile must read an em dash
 * and say *no profile recorded*, never `0h` and never `failed to load`.
 *
 * `tcmsFaults.staffing('over-allocated')` — every person comes back at `peakPercent: 130`. Proves
 * the flag, the danger tone and the Over-100% panel without needing three real allocations.
 */
staffing(mode: 'no-profile' | 'over-allocated'): void;
```

- [ ] **Step 2: The keys, in the one factory**

`frontend/src/api/queries/keys.ts` — **never a second factory.** Filters go *inside* the array, and
`staffingRoot` exists so a write can invalidate the subtree without a hand-spelled literal (§250).

```ts
  staffing: {
    capacity: (from: string, to: string) => ['staffing', 'capacity', from, to] as const,
    myCapacity: (from: string, to: string) => ['staffing', 'capacity', 'me', from, to] as const,
    people: (filters: DirectoryFilters) => ['staffing', 'people', filters] as const,
    person: (userId: string, from: string, to: string) =>
      ['staffing', 'people', userId, from, to] as const,
    skills: ['staffing', 'skills'] as const,
    absences: (userId: string) => ['staffing', 'absences', userId] as const,
    projectStaffing: (projectId: string, from: string, to: string) =>
      ['projects', projectId, 'staffing', from, to] as const,
  },

  /*
    One root for every staffing write to sweep, for `timeRoot`'s reason. An allocation written on
    one project changes that project's screen, the company capacity view, the directory, the
    allocated person's own capacity tile on two other screens, and the over-allocation panel.
    Enumerating those at each call site is five chances to miss the one that is not on screen.

    `projectStaffing` sits under `['projects', p, …]` deliberately and is therefore ALSO swept by a
    project-level invalidation — that is correct, and it is why the write sweeps both roots.
  */
  staffingRoot: ['staffing'] as const,
```

- [ ] **Step 3: The query module**

`frontend/src/api/queries/staffing.ts`, exported through the barrel. Timeout budget per endpoint
class, as every other module here does.

```ts
/**
 * Staffing: the company capacity view, the skills directory, one project's allocations, and the
 * caller's own capacity.
 *
 * `useMyCapacity` is the one read here with no staffing permission on it (017 D-m) — it is the
 * caller's own figure, and it is what the timesheet's fourth KPI tile and My Work's meter are
 * denominated over. It takes the **period's** bounds, not a week's (016 D-a): the fortnight is
 * semi-monthly and nothing configures it, so a week would be a range the product does not have.
 */
export function useMyCapacity(from: string, to: string): UseQueryResult<SelfCapacity> {
  return useQuery({
    queryKey: keys.staffing.myCapacity(from, to),
    queryFn: ({ signal }) =>
      api.get<SelfCapacity>(`/staffing/capacity/me${qs({ from, to })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: from !== '' && to !== '',
  });
}
```

…plus `useStaffingCapacity`, `useStaffingPeople`, `useStaffingPerson`, `useSkills`,
`useProjectStaffing`, `useCreateAllocation`, `useUpdateAllocation`, `useDeleteAllocation`,
`useCreateAbsence`, `useDeleteAbsence`, `usePutProfile`, `usePutPersonSkills` — every write
invalidating `keys.staffingRoot` **and** `keys.staffing.projectStaffing`'s project prefix.

- [ ] **Step 4: `capacityCopy.ts` — the shared layer, not the page**

New display logic goes in the shared layer. This is where "what does an em dash mean here" is
decided **once**, so the timesheet tile in T6 and the directory card in this task cannot disagree.

```ts
/**
 * How a capacity figure reads, in one place, for the five surfaces that show one.
 *
 * The three states are genuinely different and look identical if this is written per screen:
 *   - a number — there is a profile and hours to report
 *   - `—` with *no profile recorded* — nobody has recorded this person's contracted hours. A real
 *     state, and the one 016 D-j refused to paper over with a default of 40
 *   - the region's own failed state — the read did not answer, which `DataRegion` renders and this
 *     file must never imitate
 */
export function capacityLabel(hours: number | null, hasProfile: boolean): string {
  if (!hasProfile) return '—';
  return hours === null ? '—' : `${hours.toFixed(hours % 1 === 0 ? 0 : 1)}h`;
}

/**
 * `utilisationPercent` is null when allocated capacity is zero (017 D-i), and the copy has to say
 * which of the two nulls it is: nobody planned their time, versus no time approved yet.
 */
export function utilisationLabel(percent: number | null): string {
  return percent === null ? 'not allocated' : `${percent}%`;
}

/** The denominator, said out loud. Every other time figure in this product names its spelling. */
export const UTILISATION_BASIS = 'approved hours ÷ allocated capacity';
```

- [ ] **Step 5: `/staffing`**

`StaffingPage.tsx` on the render kit — Tailwind utilities only, no `sx`, no second stylesheet. The
mockup's structure, translated:

- `phead` → the kit's page header. Subtitle `Company view · September – November 2026` from the
  served `from`/`to`, formatted by `lib/format.ts`.
- The range picker (`Next 3 months`) and every filter live in the **URL** via `useSearchParams` —
  filter state is never component state in this app.
- `1 over-allocated` chip in danger tone, read off `view.overAllocated.length` and **not** re-derived
  by filtering `view.people`, so the chip and the row badges cannot disagree.
- `AllocationTimeline` — one row per person, a track per allocation span positioned by date
  proportion, `unsched` styling for free stretches, `text-ghost` for absent ones. Bars carry
  `aria-label` naming the project and percentage; the timeline is a `<ul>` of `<li>`, not a grid of
  divs, so it is readable without sight.
- `OverAllocatedPanel` — the breakdown, and the mockup's line *"Flagged, not blocked. The product
  does not know which of the three matters most — you do."* is kept verbatim: it is the product
  saying what it will not do, and D-e is why.
- `FreeCapacityPanel` and its `Who has which skills →` link to `/directory`.
- `UtilisationPanel` — **new, and it is the one addition to the mockup this task makes.** Utilisation
  is on no mockup (the scope's P6 says only "utilisation becomes answerable"), and it is what M6
  landing made possible. It is a panel of person + percentage with `UTILISATION_BASIS` as the
  subtitle, in the same `g2` grid row, which becomes `g3`. Recorded here rather than resolved
  silently.
- Wrapped in `DataRegion` for the loading/loaded/failed/empty/stale matrix. The empty case — no
  allocations at all in the company — reads *"Nobody is allocated in this range"* with the range
  named, not a blank timeline.

The 403: `useCanInCompany()` from `frontend/src/auth/useCan.ts` returns a `Gate` — it takes an
`enabled` flag, **not a permission key**, and the key goes to the gate it returns
(`companyGate.can('staffing:directory')`), which is how `AppShell.tsx:762` already uses it. The
refusal copy is *"Staffing across the company is for Account Managers and Finance. To see who
is on a project you run, open that project's Staffing."* — which is the only refusal in this product
that can name the screen the reader actually wants.

- [ ] **Step 6: `/directory`**

`DirectoryPage.tsx` + `DirectoryFilters.tsx` + `PersonCard.tsx`. The filters — skill chips
(multi-select, ANDed) and `Free from:` — are **held in the URL**, which is what makes the acceptance
criterion "answerable through URL-held filters" true and makes the answer shareable. The match count
(`3 of 11 people match`) is served, not counted client-side.

- [ ] **Step 7: The rail and the routes**

`AppShell.tsx` — `Staffing` and `Directory` in the **Company** group, after `Org chart`, ungated per
that group's standing rule. `router.tsx` — `/staffing` and `/directory`, lazy, each behind the route
error boundary tier.

- [ ] **Step 8: Tests**

RTL, using `createTestQueryClient` — **never a hand-built `QueryClient`**, only that one has the
caches. Any test whose render trips an error boundary must call `renderingIntoAnErrorBoundary()` and
say why.

Cover: the flag appears at 101 and not at 100; an absent person's card reads zero capacity for those
days; a person with `profile: null` reads `—` and *no profile recorded*, not `0h`; the skill filter
round-trips through the URL; the 403 renders the refusal naming the project screen.

- [ ] **Step 9: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

`npm run build` is `tsc -b + vite build` and is the gate — **not** `tsc --noEmit`, which has reported
0 errors while the build failed with nine. Two failures in `src/features/cases/CaseGrid.test.tsx`
predate this module (016's close-out records them, verified failing at `90667f1e`); they are not
this task's and are not to be absorbed here.

```bash
git add frontend/src/features/staffing frontend/src/api/queries/staffing.ts \
        frontend/src/api/queries/keys.ts frontend/src/api/queries/index.ts \
        frontend/src/app/router.tsx frontend/src/app/AppShell.tsx \
        frontend/src/lib/labels.ts frontend/src/lib/devFaults.ts
git commit -m "feat(staffing): the company staffing view and the skills directory"
```

**Out of scope:** allocating anybody, and the person page. Both screens here are reads; the
`+ Allocate` button on `staffing.html` is rendered **disabled** with the title *"Allocate from the
project you are staffing"* until T5 ships the dialog, because the write is `staffing:allocate` on a
project and this screen names no project. `directory.html`'s per-person `Profile` button is likewise
rendered **disabled** here, because `/directory/:userId` is T5's — a button linking to a route that
does not exist yet is the fake success plan 009's task-cutting rule forbids.

**Record — landed 2026-09-04 (`ecc71e7d`).** `npm run build`, `npm run lint` and
`npm run test:run` green on shanks; 38 new tests in 4 files, 2777 passing of 2779. The two failures
are `src/features/cases/CaseGrid.test.tsx`'s selection pair, which 016's close-out already recorded
as pre-existing and which this task did not touch. `packages npm run test:run --workspaces` also run,
because `citedFiles.test.ts` reads comments in `frontend/src` and a stale citation here reddens that
job: green, 535 tests.

Landed as planned, plus three things the plan did not predict:

1. **`api/types.ts` gained `DirectoryFilters`**, which the plan's `keys.staffing.people(filters)`
   needed and did not name a home for. It sits beside `BoardFilters` / `BacklogFilters` for their
   reason: `useStaffingPeople` spreads it into `qs`, so `contractParameters.test.ts` reads the
   *type* and a member the contract does not declare is silently ignored by Spring rather than
   rejected. Four members, matching `GET /staffing/people` exactly.

2. **The match count is NOT served, and the denominator is a second read.** The plan said
   *"the match count (`3 of 11 people match`) is served, not counted client-side"*. It is not:
   `GET /staffing/people` answers a bare `PersonCapacity[]` with no total. `DirectoryPage` therefore
   calls the same hook a second time with the skill and date filters dropped — one extra key,
   `{from,to}` alone, fetched once and reused across every filter combination, and *identical* to
   the main read (so one request) when nothing is applied. Counting the filtered array would have
   printed "3 of 3", which is not a denominator; a company size taken from a members list would have
   been a second answer to a question this endpoint already answers.

3. **`lib/format.ts::pluralNoun` learned one irregular.** Every count on these two screens is of
   *people*, and the suffix inflector rendered `2 persons` — including inside `EmptyState`, whose
   `noun` prop is documented as singular and inflected there, so the plural could not be passed in.
   An `IRREGULAR_PLURALS` map with one entry, and that file's *"pass it already-plural"* sentence is
   struck through with the reason rather than deleted.

Decisions taken inside the task:

- **The over-allocation tone is `failed`, not the mockup's `b-blocked`.** The kit's `blocked` means
  *something outside stopped this*, and the panel's own kept sentence reads **"Flagged, not
  blocked"** — that tone would be the one word on the screen contradicting the one beside it.
  `failed` is the kit's red and is what `--color-danger` resolves to, which is the hue the mockup
  draws.
- **`AllocationTimeline` imports `monthsIn` / `DateWindow` from `features/releases/timelineScale.ts`
  and writes its own span arithmetic.** The month scale is genuinely one function; the span is not —
  a release has a target and may have no start, so that module's three shapes include a point marker
  and a null, where an allocation and an absence always carry both dates and the only question is
  how much of the window they cover. `spanBetween` is private to the timeline file and its window's
  `to` is **inclusive**, so a full-window bar reaches 100%.
- **The `Adjust` button on the Over-100% panel links to `/directory`, not to a dialog.** An
  allocation is written on a project and this screen names none — a dialog here would have to ask
  which of the three the reader meant and then refuse them on two of the answers.
- **`+ Allocate` and each card's `Profile` render disabled with a reason**, per §31 and the task's
  own out-of-scope note. Both are T5's to wire.
- The **utilisation panel is the one addition to the mockup**, in a third column of what the plan
  called the `g2` row. Its subtitle is `UTILISATION_BASIS` verbatim.

**Built and unverified:** every screen here was proved by RTL against doubles and by the contract
checks; neither has been opened against a running instance, because `V10`'s tables carry no seeded
rows — `seed-demo-data` writes no `person_profile`, `allocation` or `absence`, so a real session
would show the empty state on both. `tcmsFaults.staffing('no-profile')` and
`staffing('over-allocated')` are how they are looked at until T5 can write a row.

---

## T5 — The project's staffing, and the person page carrying profile, skills and absence

**Deliverable:** `/projects/:projectId/staffing` lists this project's allocations, allocates a person
at a percentage for a date range through a dialog, edits and removes one, and shows each person's
company-wide total with the over-allocation flag and no other project's name; `/directory/:userId`
renders `member.html`'s Profile, Skills and Absence panels and is reached by `directory.html`'s
`Profile` button; `npm run build`, `npm run lint` and `npm run test:run` green.
**Stack:** `fe`
**Agent:** `fe-react`
**Est. context:** ~135k — 6 components created, 4 modified, 4 test files created; T4's query module
and `AllocationDialog`'s form patterns read; 3 skills
**Depends on:** T4 (the query module and `capacityCopy`)

**Files:**
- Create: `frontend/src/features/staffing/ProjectStaffingPage.tsx`, `AllocationDialog.tsx`,
  `AllocationRow.tsx`
- Create: `frontend/src/features/staffing/PersonPage.tsx`, `ProfilePanel.tsx`, `SkillsPanel.tsx`,
  `AbsencePanel.tsx`
- Create: the four matching `.test.tsx`
- Modify: `frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx` (the project group),
  `frontend/src/features/staffing/DirectoryPage.tsx` (the `Profile` button becomes a link),
  `frontend/src/features/staffing/StaffingPage.tsx` (enable `+ Allocate` as a link to the project
  picker)

- [ ] **Step 1: `/projects/:projectId/staffing`**

The screen story S1 opens with. A table of allocations — person, percentage, range, note — plus a
per-person summary row carrying `totalPercent` and the flag. **No other project is named** (D-f):
the badge reads `130% allocated` and the tooltip *"across every project, including ones you cannot
see"*, which is honest about the number and discloses nothing.

Gate: `useCan(projectId, 'staffing:read')` renders it, `staffing:allocate` enables the writes —
disabled with a reason, never hidden (§31). Rail: `Staffing` in the project group, after `Releases`.

- [ ] **Step 2: `AllocationDialog`**

Person picker (project members, from the existing membership read), a percentage input `1–100`, a
date range, an optional note. On `409` the dialog shows the conflicting range inline — *"Already
allocated 1–15 Sep at 60%"* — rather than raising a notification, because the conflict is about the
field the person just filled in.

- [ ] **Step 3: `/directory/:userId` and its three panels**

`PersonPage.tsx` — the detail view D-g places these on, because **there is no member page in this
app**: `/admin/teams` is `features/admin/TeamsPage.tsx`, a list, and no `/admin/teams/:userId` route
or `MemberPage.tsx` exists. Do not create one; the rest of `member.html` is `identity`'s surface and
already lives on `TeamsPage` and `ProjectAccessPage`.

The page reads `useStaffingPerson(userId, from, to)` and carries:

- `ProfilePanel` — weekly hours, job title, location, "since" date rendered as
  `Test Lead · Stockholm · 6 years` exactly as `directory.html` writes it, the years derived from
  `experienceYears` and never stored (D-b). Edit behind `staffing:profile`.
- `SkillsPanel` — chips, adding from the company vocabulary or creating a new skill, behind
  `staffing:profile`.
- `AbsencePanel` — the mockup's `29 Aug · half day` row plus its line *"Capacity for those days is
  zero everywhere — staffing, utilisation and any plan built on them"*, kept verbatim because D-a is
  what makes it literally true rather than aspirational.

`AbsencePanel` is the one place D-h shows: on your **own** person page the Add control is enabled for
everybody, and on somebody else's it needs `staffing:absence`. The page itself opens for
`staffing:directory` **or** for the person themself, which is what the contract's gate on
`GET /staffing/people/{userId}` already says.

- [ ] **Step 4: Tests**

Allocating 50% then 51% on a second project shows the flag (mock arms anchored —
`/^\/projects\/[^/]+\/staffing$/`, so the sub-resource is not caught by a prefix); the 409 renders
inline; the absence panel's Add is enabled on self and disabled on another person without the key;
the person page renders for the person themself with no `staffing:directory`.

- [ ] **Step 5: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

```bash
git add frontend/src/features/staffing frontend/src/app/router.tsx frontend/src/app/AppShell.tsx
git commit -m "feat(staffing): the project's staffing, and the person page with profile, skills and absence"
```

**Out of scope:** the two capacity figures on `/timesheets` and My Work. They are T6's, because they
are M6's screens and the close-out that records whether they landed rides with them.

---

## T6 — The two capacity figures M6 left absent, and the close-out

**Deliverable:** `/timesheets` shows **four** KPI tiles — Logged · Period capacity · Approved ·
Status — in a `md:grid-cols-4`; My Work's This-period panel shows Capacity beside Logged with the
meter under it; `docs/features.md` has a Staffing section; plan 009's status table reads
`done` for M7 with the V actually taken and the built-and-unverified list; the M9 prompt says
whether utilisation landed.
**Stack:** `fe` (the docs close-out rides with it, per plan 009's task-cutting rule 6)
**Agent:** `fe-react`
**Est. context:** ~110k — 2 components modified, 2 test files modified, 4 docs modified; 016's two
file headers and `periods.ts` read; 2 skills; 3 plan sections cited
**Depends on:** T5 (nothing in the module is left to change after it)

**Files:**
- Modify: `frontend/src/features/time/TimesheetPage.tsx`, `TimesheetPage.test.tsx`
- Modify: `frontend/src/features/board/MyWorkPage.tsx`, and its test
- Modify: `docs/features.md`, `docs/plans/009-project-management-expansion-modules.md`,
  `docs/plans/009-execution-breakdown.md`, `docs/agent-context/fe-react.md`

- [ ] **Step 1: The fourth KPI tile**

`TimesheetPage.tsx`. The grid goes `md:grid-cols-3` → `md:grid-cols-4`; the tile sits **second**,
where `timesheets.html` puts it. It reads `useMyCapacity(period.startsOn, period.endsOn)` — **the
period's own bounds, off the loaded payload**, which is `periods.ts`' own rule and is why nothing
here re-derives a fortnight.

```tsx
      <KpiTile
        testId={`${TEST_ID}.Kpi.Capacity`}
        label="Period capacity"
        value={capacityLabel(capacity.data?.capacityHours ?? null, capacity.data?.hasProfile ?? false)}
        detail={
          capacity.data?.hasProfile === false
            ? 'no staffing profile recorded'
            : 'contracted hours over this period, net of absence'
        }
      />
```

Rewrite the file header's `<h2>Three KPI tiles…</h2>` section in the superseded style — struck
through with `→ superseded 2026-09-04 (plan 017)` and the reason: the figure D-j refused to invent
now exists, computed by `staffing.Capacity` from `person_profile`, `allocation` and `absence`. **Do
not delete the old reasoning**; a later reader must be able to see that the absence was considered
and why it ended.

- [ ] **Step 2: My Work's Capacity and the meter**

`MyWorkPage.tsx`'s `ThisPeriodPanel` — Capacity beside Logged in a `g2` grid, and the meter under
them at `logged / capacity`, clamped at 100% with an over-tone above it. The meter is what needed
the denominator, and it arrives with it and not before.

**The panel stays a PERIOD, not a week** — the header's first bullet is unchanged and still correct.
Amend only the second bullet, in the superseded style.

The capacity read is its **own** `DataRegion` nested inside the panel, so a staffing outage empties
the tile and the meter and leaves the logged hours where they are. A single region over both would
make one module's failure blank the other's number.

- [ ] **Step 3: Tests**

Four tiles render; the capacity tile reads `—` and *no staffing profile recorded* when `hasProfile`
is false; the meter is absent when capacity is null and present when it is not; a failed capacity
read leaves `Logged` rendered (`drivingAFailedRead()`, with the reason).

- [ ] **Step 4: `docs/features.md`**

A **Staffing** section: profiles and skills, allocation at a percentage over a range, the
over-allocation flag and what it deliberately does not do (flag, never block), absence and the one
sentence that it zeroes capacity everywhere, the directory's "free from" question, and utilisation
with its denominator named. Written for a reader deciding what the product does.

- [ ] **Step 5: Close plan 009 per the hand-off protocol**

1. **Status table** — M7's row to `done 2026-09-04 @ <short sha>`.
2. **The migration actually taken** — `V10`; strike through `V9` in the M6·M7·M8 table's M7 column
   and in the modules table, do not delete it.
3. **Built and unverified** — the honest list. Everything the gates proved compiles and nobody
   clicked through, plus specifically: whether the lab was seeded and a real allocation made;
   whether `GET /staffing/capacity` has served a company of eleven people over three months against
   a real database rather than a service-level test; and that `TimeFacts`' approved-hours join has
   one caller and one test.
4. **Rewrite the dependent prompts.** No module's *Depends on* names M7, so **only the M9 prompt
   changes** — and it changes because the M7 prompt's own closing instruction says so: record in it
   that **utilisation landed** (approved hours ÷ allocated capacity, on `/staffing`, D-i), so M9's
   analytics does not build a second one, and say which figures it can read.
5. **Record the two capacity figures** — completed, both, with the caveat that each reads `—` for a
   person with no `person_profile` row, which is a real state and not the absence 016 D-j left.
6. Also correct `009-execution-breakdown.md` phase 7: t32's *"No endpoint in this contract reads a
   time entry"* is superseded (M6 landed first), and the projected `V9` is `V10`.

- [ ] **Step 6: Gate and commit — two commits, per the protocol**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote packages npm run build
```

```bash
git add frontend/src/features/time frontend/src/features/board docs/features.md
git commit -m "feat(staffing): the period-capacity tile, My Work's meter, and the features doc"

git add docs/plans/009-project-management-expansion-modules.md docs/plans/009-execution-breakdown.md
git commit -m "docs(plan): M7 done"
```

---

## Definition of done

The module is done when all six are true, and each is checked by doing it rather than by reading the
code:

1. **A PM allocates a person to two projects at percentages.** Two `POST /projects/{id}/allocations`
   on two different projects, same person, overlapping ranges. Both accepted.
2. **The over-allocation flag appears at 101%.** 50 + 51 ⇒ `overAllocated: true`, `peakPercent: 101`,
   the danger badge on the row and the person in the Over-100% panel. 50 + 50 ⇒ no flag.
3. **An absence zeroes that period's capacity on every screen that shows it.** One absence, then
   four screens: `/staffing`, `/directory`, `/timesheets`' capacity tile and My Work's meter. All
   four move, because all four read `Capacity.of`.
4. **"Who is free next month with skill X" is answerable from the directory.** A skill filter and a
   `freeFrom` date, both in the URL, returning the people who hold the skill and have a day under
   100% on or after that date.
5. **The gates are green on `shanks`** — `./gradlew build`, `npm run build && lint && test:run`,
   `packages npm run build` — with the two pre-existing `CaseGrid.test.tsx` failures named and not
   absorbed.
6. **Plan 009 is closed** per the hand-off protocol, including the M9 prompt saying utilisation
   landed and this plan's record of the two capacity figures.