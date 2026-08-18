# §241 — The chart is the reader's own

**`GET /api/v1/org-chart`, `/org-chart`, and the sentence in the contract that said the opposite.**
Executed from `docs/plans/2026-08-15-org-chart-tasks.md`, T6 → T0 → T1 → T2 → T3 → T4 → T7 → T8 →
T11 in that order. **No migration, no permission seeded or moved**, catalogue unchanged at 55.

~~T5 (query-plan review), T9 (disclosure-boundary security pass) and T10 (manual test-case suite) were
**not run**~~ → **superseded 2026-08-17: T5 has since run (`0b2191e`), and the sentence above is the
part of this section that most needs correcting, because T5 did not confirm what the plan expected —
it found the plan looking at the wrong table.** ~~T9 and T10 remain unrun~~ → **superseded
2026-08-18: T9 has since run, and it found nothing** — `docs/audits/2026-08-18-org-chart-disclosure-boundary.md`
is the evidence: 69 assertions over §4.4 row by row and 45 tamper probes, against the response body
of a two-company, four-project instance provisioned for the pass, every principal signing in as
itself. **The instrument was controlled rather than trusted** — replayed against the Company Admin's
document, which is byte-for-byte what a dropped `reach.maySee` would have sent that member, it goes
13 red. One check did *not*, and it is the §241 trap at the HTTP layer: "every cell's `projectId` is
a served column" stays green on a uniformly over-disclosed document, because the columns leaked too.
A consistency assertion cannot see uniform over-disclosure. **T10 remains unrun**, for the reason
below: it is a report-only lane off the critical path. Its absence is why §11's rows below are the
record and not a summary of findings.

**What T5 found, and what it cost.** Plan §5's assumption was that the two `role_assignment` indexes
`V1:288-289` already installed made the matrix queries cheap and no index was wanted. Half right: no
`role_assignment` index is wanted. But the **team arm** of every one of those queries reaches a
person through `team_membership.user_id`, and `team_membership` had exactly one index — its primary
key `(team_id, user_id)`. `user_id` trails it and `company_id` is in no index at all, so those arms
sequentially scanned the whole *instance's* `team_membership`, discarding other client companies'
rows to find one tenant's. **And the same predicate is in the authorization hot path**, which was not
in T5's brief and is why the finding outgrew the org chart: `findProjectScopedHeldPermissions` ran
3.980 ms / 696 buffers with `Rows Removed by Filter: 60,028`, `TeamMembershipRepository.findByUserId`
2.383 ms for two rows, and `ProjectService`'s `Project.myRole` — a shipped screen, nothing to do with
this slice — 9.006 ms to find one row. Plan §6.1 is the measured finding in full.

**So this section's opening claim of "no migration" is now false for the feature's *record*, though
still true of the slice it describes.** `V67__team_membership_answers_which_teams_a_person_is_in.sql`
landed the index on 2026-08-17 as its own slice, deliberately not folded back into this one. It took
the DDL in the **plain** form rather than §6.1's recommended `CREATE INDEX CONCURRENTLY` +
`flyway:no-transaction`, striking that half of §6.1 through: an `ACCESS EXCLUSIVE` lock costs its
duration, duration is a function of table size, and `team_membership` is read-hot but physically tiny
— so `CONCURRENTLY` would have bought the repo's first no-transaction migration, with an
`INVALID`-index recovery path and a non-rollback-able failed history, to avoid milliseconds inside a
deploy window. `V67`'s header carries the argument and names the six-migration precedent it declined
to half-reverse.

**The standing lesson is the shape, not the index.** This section shipped saying a report-only lane
was skipped because it was off the critical path. The lane was off the critical path and it still
found a defect on the authorization hot path that had been shipped for months. "Off the critical
path" describes what blocks the build, not what the lane can see.

---

## 1. The decision the whole slice hangs on, and it is one sentence

**The people axis is a company fact; the project axis is the reader's own.**

Person rows are complete — every non-`REMOVED` member, their teams, their lead flags, their status.
That is not a new disclosure: `GET /users` and `GET /teams` already serve all of it to every member,
because plan §6k says org structure is not sensitive *within* a company and hiding it defeats the
purpose. The matrix **columns** are exactly the projects the caller holds `project:read` on.

So two colleagues open the same chart, see the same people, and see **different matrices**. That is
the design and not a bug, which is why §9.4's standing line is not optional (see §5 below).

The three open questions the plan blocked T1 on were answered as recommended and written into the
plan's §14 as a dated decision rather than left open — role *names* go to every reader of a visible
project (the one genuinely new disclosure), `companyRoles` is visible to every member, and SYSTEM
scope does not appear at all. Q5 (no `operationId`, matching the identity family) went the same way
and neither generator objected.

---

## 2. What the plan got wrong, twice, and both are stated at the site

The plan is 809 lines and mostly right. Two of its instructions could not be executed as written, and
in both cases the deviation is recorded in the code rather than only here.

### 2.1 §8.3's helper cannot live where the plan puts it

The plan says: extract §A1.1's display override into one helper in `com.tcms.identity` that **both**
`ProjectService.view` and `OrgChartService` call. That does not build. `project`'s module declaration
is `allowedDependencies = {"api", "tenancy"}`, and `ProjectAccess`'s own header says a Modulith
failure on that edge must be fixed **with a port and never a wider list**. `com.tcms.api` was the
other candidate and its charter refuses this explicitly: *"a helper two modules happen to share does
not qualify — that is a copy, or an event."*

So the override moved to the side that owns it, and the port asks for the **answer** instead of the
ingredients: `projectRoleNames(userId)` + `inheritedProjectRoleName(userId)` became one
`effectiveProjectRoleNames(userId, projectIds)`.

That collapse answers the objection the old two-method split was written around. Its javadoc said the
two must *not* be merged because the map is keyed by project and **only the caller knows which
projects are being rendered**, so an implementation folding in a per-project default would be
inventing entries for projects nobody asked about. Correct — and the new signature has the caller say
which projects it is rendering, so nothing is invented. The old paragraph survives struck through,
because it was right about its own shape rather than wrong.

Net effect is what the plan wanted: **three** implementations of §A1.1, not four.
`PermissionEvaluator.projectTerm` (permissions), `EffectiveRoleNames` (display, both screens) and
`roleAccess.ts` (client, which admits it in a comment). No new module edge.

### 2.2 §9.2's `DataGrid` would have made the matrix untestable

The plan says MUI X `DataGrid` with dynamic columns, and `CaseGrid` is the precedent. **The matrix is
a `Table`.** Two reasons, and the second decided it:

1. Every DataGrid feature this screen would use is absent. The whole document arrives in one
   response, so there is no server-side paging, sorting or filtering to mode-switch — which is what
   `CaseGrid` is actually built around. What would be left is a table with a scrollbar.
2. **DataGrid does not render in jsdom.** Its virtualization measures through a `ResizeObserver` that
   `src/test/setup.ts`'s shim never fires, so the scroller comes back 0×0 with no rows in it. This
   was measured rather than predicted: the DataGrid version was written first and **six arms failed**
   on a grid with no rows. The corroborating fact is that `CaseListPage` — this repo's only DataGrid
   screen — has **no component test at all**.

Paying that price here would have left the §9.4 standing line, the Company-Admin-has-no-cell rule and
the `via` labels assertable only through a browser pass §65 has deferred. The measured bundle delta
(§7) is the other half of the argument.

---

## 3. The finding that reshaped the test class: §8.3's agreement test does not observe the boundary

The plan calls the §8.3 agreement test *"the single most valuable test in this slice"*, and T4's
prompt says it must **fail when a cell is served for a project the viewer cannot open**.

Those are two different claims and the test as specified is the other one. §8.3 pins
`ProjectAccess.canView(**person**, project)` — the subject's. Replace `reach.maySee` with `true` in
`OrgChartService` and both §8.3 directions stay **green**, because a cell for a project the reader
cannot open still names somebody who legitimately holds a role there. Every column is served to
everyone, and the test built to catch exactly that says nothing.

So `Agreement` has **three** arms, not two: the two §8.3 directions, plus a reader-side sweep asking
the evaluator about the **caller** for every column served. That third one is what reddens.

This was verified by breaking it — five arms went red including the new one, the two §8.3 arms passed
throughout — and reverted. A second mutation was run the same way: turning §A1.1's replace into a
union reddens **twelve** arms across three classes, and the three in `ProjectEndpointTest` are the
evidence that T2's helper is genuinely shared across the module boundary rather than merely
extracted. Both are `§241 OC-1`/`OC-1b` and `OC-5` in the mutation queue, marked **RUN** rather than
`NOT RUN`, which is the only exception to §65 in this slice and is there because a test that cannot
fail is not coverage (§162).

---

## 4. The server

**Three queries for the whole matrix, not N×M.** The naive shape is one `PermissionEvaluator.evaluate`
per (person, project) pair; for 40 people and 8 projects that is 320 evaluations of several queries
each, and neither `reachOf` nor `membersOf` licenses it — both accept an N-shaped cost with a stated
ceiling, and N×M is not N.

Generalising `findProjectRoleNames` / `findInheritedRoleNames` to an id list **forced a shape change
worth knowing about**. The single-user form could ask `user_id = :userId OR team_id IN (…)` because
the person was already known; with a list, a team grant's row carries `user_id = NULL` and nothing on
it says *whose* cell it is. So each way a grant reaches somebody became its own `UNION ALL` arm
naming the person — `ra.user_id` directly, `tm.user_id` through the membership join — and **that same
join is what makes `via` and `teamId` answerable at all** rather than a second query. `UNION ALL`
never `UNION`: two grants become one string in the ranking, deliberately, because that is where the
rule for choosing is written.

`ProjectService` reads the same statements with a single-element list, so `Project.myRole` and the
chart's cells cannot be resolved by two queries that drift apart. The convenience overloads are
`default` methods, which Spring Data never proxies — one SQL statement behind both entry points.

The third query is COMPANY-scope role names, and it **does not filter on `role.assignable`**. Three
seeded rows are non-assignable and only `Company Member` is a consequence of membership, so the
baseline is dropped by **id**. Filtering on the flag is the one-line conversion `V40` §2 and `V41` §1
name as the "cleanup" that breaks four unrelated sites, and here it would additionally hide a future
non-assignable company role that is a real fact about a person. `§241 OC-8` records that the two
spellings are indistinguishable today.

`OrgChartService` is gated on `company:read` and answers an **empty document** rather than 403,
matching the four other identity reads: the contract declares only 200 on them and inventing a status
a generated client does not know about is the costlier divergence. It reuses `reachOf`, `peopleById`
and `listTeams` rather than re-implementing them — the last of those is why this chart and
`/admin/teams` cannot draw different trees or different project chips. `AdminDirectoryService.statusOf`
became package-private rather than being copied: two people can disagree about whether an invitee here
who is active elsewhere reads `INVITED`, and the answer must not depend on which screen asked.

**The boundary is enforced twice by construction** — once filtering the columns, and again because
`cells()` iterates the column ids, so a project absent from them cannot produce a cell even for
somebody who demonstrably holds a role there.

---

## 5. The screen

`/org-chart`, **beside `/admin/teams` and not beneath it**. Every affordance on that screen answers to
`company:teams`; this one has no gate at all, and §84 recorded that inventing a key for it would be a
403 no administrator could grant their way out of. A screen every member may open, filed under a
prefix that reads "administration", teaches the opposite of what is true — and the URL is the part of
a screen people quote to each other. `/teams/:teamId/board` is the existing precedent.

**§9.4's standing line renders unconditionally**, including for a Company Admin who sees everything.
Rendering it only when something is hidden would make its presence the existence claim §4.3 rejected
— the "+3 more" count wearing a different hat. The test for it drives a reader served every project
the company has, which is precisely the case a conditional implementation would pass.

`OrgChartGaps` is pure presentation over the one document — no second query and no second
authorization path. That is not an optimization but the safety property: it computes from
`person.projects`, which are already clipped, so it can never name reach the reader was not shown.
Its third gap is worded *"no role on any project you can open"* for the same reason — *"no
assignments"* would be a claim about the company this reader's document cannot support. It also
excludes anyone holding a company role, or the panel reports the person with the most access in the
company as a vacancy.

`OrgChartTeams` calls T6's `flattenTeamTree`. The extraction landed first and alone, so a regression
in `/admin/teams` could not be confused with this feature's fault.

**Nine invalidations, and a rule so a tenth cannot be missed.** The chart is a derived view of
`teams`, `users`, `role-assignments` and `projects` at once, so any write touching those four can move
a cell. Nine named arms drive the mutations; a tenth arm derives the population from `admin.ts`'s own
source, because a list of nine is something somebody has to remember to extend. Both halves were
verified by deleting `useRevokeRole`'s invalidation.

---

## 6. The false claim in the contract's own prose (plan §13 F1)

`CurrentUser.teams` said: *"`GET /teams` is gated on `company:teams`, so the org chart is not readable
to the person in it."* It reads **`company:read`** — the `listTeams` assembler asks `readsCompany()`
— and has since the endpoint shipped. The org chart has always been readable to the person in it,
which is the opposite of what the sentence said, in the sentence most likely to be read by somebody
deciding this very question.

Struck through with the correction rather than deleted, and the field survives (§13 F2): it is still
the cheaper answer for the app shell, which needs one person's team names and would otherwise pay for
every team's leads, members and per-caller project chips.

**The instrument gap is the finding.** `tierClaims.test.ts` matches `Lead-only` / `Lead and up` and
`does not hold` / `not held by` / `withheld from`; *"gated on `<key>`"* is none of those forms.
Extending it is its own slice — a new rule over four corpora that would find more than one row — and
is **not** proposed here.

---

## 7. Gates, all on shanks

| | Before | After |
|---|---|---|
| backend `./gradlew build` | 3038 | **3082 / 0 / 0** (+44 — `OrgChartEndpointTest` 34, `EffectiveRoleNamesTest` 10) |
| frontend `test:run` | 1553 / 116 | **1582 / 119** (+29, +3 files) |
| packages | 336 | 336, both `schema` copies regenerated and identical |
| dist JS | 1,919,799 B / 96 chunks | **1,926,853 B / 99 chunks** (+7,054, **+0.37 %**) |

The `OrgChartPage` chunk is **5,347 B**. Small because MUI's `Table` already ships in the shared
chunk — which is the working agreement's own DataGrid lesson pointing the same way.

**T2 was committed with two known-red contract tests**, and that is worth reading rather than
glossing: T1 adds the path and four schemas to `openapi.yaml`, so between T1 and T3 the backend
suite correctly reports *declared and unserved* and *a schema paired with nothing*. T1's own gate is
the two TypeScript builds, which the plan specifies and which passed. Both closed at T3. **No
existing `myRole` assertion failed at any point**, which was T2's real gate and the risk it was split
out for.

---

## 8. Built and unverified

`§241 OC-1`…`OC-8` in the mutation queue and `L1-23`…`L1-26` + `A0-17`/`A0-18` in the browser queue.
`OC-1`, `OC-1b` and `OC-5` are marked **RUN** because they were run during T4 to prove the checks can
fail; everything else is `NOT RUN` per §65. Three of the eight are **expected green and recorded as
gaps rather than as passes** — `OC-3` (RLS independently scopes the table), `OC-7` (a re-copied
override is indistinguishable from the shared one *today*) and `OC-8` (both spellings drop the
baseline).

~~T9 would have been the row that matters most~~ → **superseded 2026-08-18: T9 ran and the boundary
held.** Its rule stands and is the reason it found anything worth writing down: **assert on the
response body, not the UI.** A field the server sends and the client merely declines to render is
invisible to any UI-level test, and `L1-23` says so in its own text for whoever drives it. T9 does
**not** discharge a single row of either queue — it is a different observer asking a different
question, and `OC-1` in particular is this boundary's *mutation* form and was deliberately not run.
