# §83 — the cross-project board: the parameter that was left off, and the mock that had been showing the wrong project since Phase 1

**Date:** 2026-08-04 · **Backend** 1505 → **1513** · **Frontend** 662 → **682** ·
**no migration — `V27` is still free** · policy count unchanged at **58**

§82 ended §6g and named three things left in the plan around it. This is the second of them, and
the one that needed no feature built first:

> **Personal — "My Work"** — everything assigned to me across all projects I can see, in one place.
> This is the view most people will keep open all day, and it must aggregate across projects; a
> per-project-only board fails the person working on three. *(§6l)*

§81 recorded the bound from the other end when it built the reviewer's inbox — *"the inbox is per
project; a person on three projects checks three boards"* — and said what the way out was: **the
endpoint is already instance-scoped (`/work-items?projectId=…`), which is the shape that eventually
drops the parameter.** That is the whole of this slice's path work. There is no new route on the
server, no new table, no new permission key, and no migration.

## The decision that shaped everything: the aggregate is the PERSONAL board, and the unfiltered one is refused

`projectId` is now optional. Absent, with `mine=true`, it answers §6l's My Work board. Absent, with
`mine=false`, it answers **400**.

That refusal is the substantive call. §6l describes three boards — personal, team, project — and
the cross-project one is *the personal one*. There is no board that is every project's work
unfiltered; a company-wide firehose is not a view anybody could work from, and nothing in the plan
asks for one. So the request that names neither a project nor a person is not a board.

**Refused rather than narrowed**, which is the part worth stating: forcing `mine` on silently would
answer with a list that is not what was asked for, and the client has **no way to tell that apart
from a company holding only its caller's work**. The problem detail names the two requests that do
work, the rule `unknownStatusProblem` set one method down. 400 and not 404 — nothing failed to
resolve, and the caller is entitled to both of the requests it names.

## The gate is the project SET, because there is no 404 left to bound it

Every project-scoped read in the product passes `ProjectService.readVisible`, whose whole job is
that a caller never holds a `projectId` it has not had gated. A board that names no project has
nothing to gate that way — and, crucially, **no 404 to answer**: the request named nothing that
could be missing, so a member holding no grants at all gets `200 []`.

That removes the thing that was implicitly bounding the board, so the bound has to become explicit:
**`ProjectService.readAllVisible`**, `readVisible`'s plural, running the read against every project
in the active company this caller may see. `visibleProjects` is now one private method shared with
`listVisible` rather than the same two lines written twice — the day a third condition joins the
visibility rule, a second copy is the one that keeps answering the old question (§58's E1).

**The failure this prevents is a real row in the fixture, not a hypothetical.** Work can be assigned
to somebody who cannot open the project it is in — that is this class's defect 4, which
`BoardEndpointTest` has caught since §53 from the *write* side. A personal board built from "cards
assigned to me" instead of "projects I can see" puts it on their board, rendering the case key, the
title and the project name of a client nobody granted them, and **the assignee is the only person
who ever sees it**. So the fixture now holds three Acme projects, each with an outstanding execution
assigned to `LEAD`, and `LEAD` is granted on two of them.

**With one reachable project, a board that quietly still reads a single project passes every
assertion in the class.** That is why the second *granted* project had to be added too, and not only
the ungranted one.

## One query per fact over a set, never one per project

The four sources — stored tasks and the three generators — now take a `Collection<UUID>` and
predicate on `project_id IN (:projectIds)`. The project board is the **one-element case**, and there
is deliberately no per-project overload beside it: two spellings of each query is two things to keep
in step, and the one that drifts is whichever board does not exercise it (§58's E1 again).

The personal board of somebody on six projects is therefore **4 statements, not 24** — the
difference between a view people keep open all day and one they stop opening.

**The collection must never be empty**, because `IN ()` is a syntax error rather than a predicate
matching nothing — the same guard `ProjectService.listVisible` states at its own grouped query, and
here it is the *ordinary* path rather than an edge: a company member holding no project grants is
exactly who opens a personal board first. `assemble` answers `List.of()` before any query runs.

**Each card takes its project from the ROW, not from the request.** `projectId` and `projectKey`
have been on `WorkItemView` since §53 and on every `BoardFacts` record since §81 — with one project
in the answer they were a display nicety, and this is the first reader for which they are the only
thing that says where a card came from.

## The claim the comments made that the code did not hold

The first draft of `personalBoard`'s header said the bound on this list is one person's workload
rather than the company's, and the controller justified the 400 the same way. **`present` filters
after `assemble`**, so the personal board already performs the company-sized read: a caller visible
on every project loads every live task, every outstanding execution, every flagged case and every
open review request in the company — and resolves all their assignees — before keeping their own.

**The 400 bounds the answer, not the work.** Both comments now say so. The filter was left where it
is on purpose, because pushing it into the four sources is precisely what `present`'s own header
refuses by name (*"four predicates to keep in step, and the one that gets forgotten silently shows
somebody else's work on a personal board — a leak of attention rather than of data, which is why
nothing would ever fail"*) and it would change the project board too.

So §6l's *"cap derived generation with sensible limits"* — which open question #24 promoted from
prudence to a requirement — **does have somewhere to land after all, and it is the generators rather
than the response.** Measure before building it: the four statements are index-covered, and the
first company to feel this will be one with a large `case_health`.

## The frontend: one screen, and two repairs it could not have been written without

**`/my-work`, outside `/projects/:projectId/*`** — the one feature route with no project segment.
`router.tsx` already recorded that the segment is a real navigation boundary rather than a key
disambiguator, which is the reason: any project in this URL would be an arbitrary one, naming a
project in the breadcrumb that most of the cards are not from and making the rail's switcher appear
to change a board it cannot change. `/admin/teams` is the precedent. Not `/board`, because
`/projects/:projectId/board` exists and one name for two scopes is the IA defect the rail's own
Company/Project split was made to avoid.

The screen **reuses `Board`** rather than growing a second one; `Board` now takes no project at all,
because both things it used the project for turned out not to need it.

### 1. `linkFor` took the board's project, not the card's — and §81's four link tests pass against it

§81 keyed `linkFor` on `sourceType`, fixing *which screen* a card opens, and left the project as the
route's. On a board that is one project's those are the same value. On My Work they are not: a
review of a case in one project, opened from the aggregate, was sent to
`/projects/{the other project}/cases/{a case it does not contain}` — **a 404 on the one affordance
the card exists to offer**. `WorkItem.projectId` has been required since §53, so the fix has no
fallback arm and nothing to disagree with.

**The second repair this one function has needed in three slices**, both invisible until a new kind
of card or a new kind of board arrived. Nothing was wrong with either test suite.

### 2. THE MOCK HAS BEEN STAMPING CARDS WITH THE WRONG PROJECT SINCE PHASE 1

`db.runs`, `db.cases` and `db.plans` carry no `projectId` — the contract puts the project in the
path, so the wire shapes have no such field — and the mock's execution and maintenance generators
iterated **the whole store**, stamping every card with *whichever project was asked for*. The second
project's board has therefore been showing the first project's executions and flagged cases under
its own key for the product's entire mock life, every one of them linking into a project that does
not contain them.

§81's finding one slice later and in the same file: a mock module that holds *logic* agrees with
itself, and the thing that finally disagreed with it was the aggregate, where the same card appears
twice **with the same derived id** — a duplicate React key. `mocks/db/workItems.test.ts` exists
because of §81 and gained seven tests here; it is still the only test that asserts the mock's own
rules.

Two smaller mock defects went with it: `GET /work-items` fell back to `projects[0]` for an unknown
`projectId` where the server 404s (a stale bookmark rendered another project's board under the id in
the URL), and `useCreateWorkItem` invalidated only the board on screen, so a task created with the
"Everyone" chip selected left the `mine=true` cache holding a board without it.

## Gates

`./gradlew build` — **1513 tests, 0 failures, 0 skipped** (1505 → 1513: the eight in
`BoardEndpointTest.MyWork`). `npm run build` clean, `npm run lint` 0 warnings, `npm run test:run`
**682/682** (662 → 682: 12 `MyWorkPage.test.tsx`, 7 `mocks/db/workItems.test.ts`, and one §70
contract-hygiene case generated unattended for the new `api.get` call site).

> This machine still has no JDK 21 on `PATH` (only 17). The Temurin 21 unpacked into a previous
> session's scratchpad was reused via `-Porg.gradle.java.installations.paths`; nothing about the
> repo was changed to accommodate it. `./gradlew build` takes ~15 minutes here.

## Not browser-verified

The seventeenth slice in the deferred queue; **19 rows** appended to
`docs/verification/frontend-browser-checks.md`, three of them live-only (per-project grants, the
`200 []` for a member holding none, and the raw 400). The one that proves the stale-bookmark 404 is
the only row no component can reach on its own, because nothing asks for a project it cannot see.

**Zero mutations run inline** — the policy, followed on both halves. **Seven backend rows and five
frontend rows** are queued in `docs/verification/backend-mutation-checks.md`. Two to run first:

- **`visibleProjects` with the `access.canView` filter dropped** — the security row. It must redden
  **exactly 1**, and that test is the ungranted project's execution, assigned to the caller, in a
  live run, in their own company. Everything except the grant.
- **`linkFor` reading the route's project again** — it must redden **exactly 1**, and *that "only
  one" is the finding rather than a shortfall*: it is what §81's four passing link tests were
  already telling us and nobody could hear.

## What this leaves for the next slice, named rather than implied

- **The two remaining board generators**, unchanged since §81 and still a feature apiece rather than
  a generator apiece — `DOC_UPDATE` needs §6i's stale-case flagging, which nothing computes, and
  `DEFECT` needs the local defect record, which nothing writes.
- **§6l's team board**, now the only one of its three boards with no endpoint at all.
- **Per-project generator toggles**, and §6l's cap — which this slice has now located, on the
  generators rather than on the response. Measure first.
- **`/reports` and M2.5**, still the only dead contract left, and still a milestone rather than a
  slice.

Still latent, unchanged: **`@Version` on `TestRun` and `work.WorkItem`** (§74),
**`project_settings.lock_version` with nothing sending an `If-Match`** (§77), §79's residual — the
closed-review path on the comment resolution endpoint has no test of its own — and §82's two:
**`TestRunInput`'s hand-written shape** and **the `DEPRECATED` case that no gating test covers**.
