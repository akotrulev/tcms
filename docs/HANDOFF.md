# TCMS — Session Handoff

**Date:** 2026-08-01 (latest section: **§55, inviting a colleague** — `POST /users`, so a company can
finally add a person to itself. Nine sessions named this and §54 sharpened it: the product could show
you who was on a project and let you staff one, and the only way to get a *person* in was an operator
with a psql prompt — §54's own live fixture needed three `INSERT`s. **No migration**; the tokens, the
mailer and the accept flow have been there since §18, and `InvitationService`'s javadoc already named
*"a future invite-a-colleague endpoint"*. So the slice is decisions, not plumbing: the response
**cannot say whether the account already existed** (`app_user` is instance-level with a unique email,
so a differing answer enumerates the instance one guess at a time), a `REMOVED` membership must be
reset or the product sends **a link it then rejects**, and the input carries **no `roleId`** because
inviting and granting are two acts with two gates. The mutation that proves the disclosure rule is
the non-obvious one — seeding the membership from `canonicalName` passes every fresh-account test,
because for a fresh account the two strings are equal, and fails only against a **reused** account.
**The live pass found the defect no gate could**: `emailed: false` has *three* causes and the body
expressed one, so on an install **with** a relay a second click told the administrator their mail was
broken — `issued` is now on the wire and the three-state test exists because the two-state one passed.
Found by clicking Send twice. Before that: **§54, the board's three gaps** — `GET /projects/{projectId}/members`, `Idempotency-Key` on `POST /work-items` with `V17` behind it, and `BLOCKED` as a real fifth column. The members endpoint is not plumbing: the server already validated `assigneeId` against *active member ∧ project grant*, so the picker had to offer **that same set** rather than one that agrees with it — the predicate now lives in one place and a test drives both sides of it. It cannot be a query over `role_assignment`: that finds only direct grants, so it silently omits everybody staffed through a **team** and every **Company Admin**, and answers 200 with a plausible list. `BLOCKED` needed the value, the label *and* the column together, because `satisfies readonly WorkItemStatus[]` rejects a non-status and **accepts a subset** — only one direction was ever checked. The live pass found **no defect**, the first slice in five where it did not; it did prove the whole predicate against a four-person fixture and `V17` migrating an already-at-v16 database in place. Along the way: three mock defects of the "mock agrees with itself" class, §19's fixture-collision defect a **third** time (the prefix *space* is hand-managed and `c0` was taken), and a **pre-existing suite-wide flake** — 1–6 random failures per full run, all reading "Test timed out in 5000ms" rather than an assertion, fixed by raising `testTimeout`; the first attempt raised RTL's `asyncUtilTimeout` to the same value and made it worse. Before that: **§53, the `work` module and the `case_health` writer** — the kanban board runs on a real server, `case_health` has its first writer after six slices of being read and never written, and §52's named next rung — *every URL the SPA calls resolves to a declared operation with that method* — is built and immediately caught the defect this slice opened with: `POST`/`PATCH /work-items` had been called since Phase 1 and **declared nowhere**. The live pass found the one thing no gate could — both derived card types rendered with **no reason on them**, because the server sent `description` and the card never rendered it. Before that: **§52, the suite execution timeline** — and it landed alongside a concurrent strand's **§51, the open-question decision round** — no code, nine of plan §8's outstanding questions settled, **two of which turned out to be answered already by the code and the contract**. ~~The next slice is **M2 execution writes**: recording a result is the last hop still running on MSW.~~ → **superseded in §52**: recording a result has been live since §38, and §52's live pass drove `PATCH /runs/{runId}/executions/{executionId}` eleven times against the real server to build its fixture. ~~The MSW-only surfaces are `documentation`, `reporting` and `work`~~ → **superseded in §53**: `work` went live in that slice, so the MSW-only surfaces are now `documentation` and `reporting` (`/doc-pages`, `/artifacts`, `/reports`, plus `cases/bulk`). This is §46's "a 'Not done' note decays" a second time, inside a day. Before that: **§50, the query-parameter checks at both ends**. Before that: **§49, the case execution timeline**. Before that: **§48, the contract-hygiene computed checks**. Before that: **§47, the repository dashboard**. Before that: **the third and final parallel run** — §42 · §43 · §44, with §45
recording it **and the decision to stop running lanes**. Work is **sequential from here** unless the
user asks otherwise: the seams held all three runs, but wall clock is bounded by the longest lane and
every lane re-derives the same premises. The M3/M4 fan-outs in the parallel plan §5 remain the one
shape that would justify revisiting it.)
**State:** Planning and design complete **including multi-company tenancy**. **Backend M0 is COMPLETE**, and **M0.5 now has a usable login** (§18): invitation → activation → sign-in works end to end, `CompanyContext` finally has a production entry point, and the permission evaluator answers over HTTP. **The instance is reachable for the first time.** Frontend complete through **Phase 3** and aligned with the multi-company decisions (§9), and **the two halves have now met (§20)**: `VITE_API_MODE=live` points the SPA at the real backend through a dev proxy, `/accept-invitation` redeems a real CLI-issued link, and a real session survives a reload. **As of §25 the SPA's biggest screen renders live**: the case grid, its suite tree, its facet chips and its filters all run against the real API. **As of §26 that screen raises no errors at all** — its condition library is served, so nothing on it 404s. **As of §27 the case *detail* screen is live too, including §6f's suite-condition inheritance** — which makes the `repository` module's read surface complete. **As of §28 the product has its first WRITE**: editing a case is live end to end, `test_case_version` has a writer so §6j history actually accumulates, `audit_event` has its first row, and the write gate is a permission (`case:edit`) rather than the read gate every role holds. **As of §29 a case can be CREATED live**: `TC-n` is allocated from the company's `key_sequence` by the one non-racing statement, v1 is written with the case so a created case has no history gap, and the `Idempotency-Key` the SPA has sent since Phase 1 is finally read — a retry returns the case it already made. **As of §30 a provisioned company is finally usable without hand-written SQL**: suites can be created, renamed and deleted from the tree, both halves in one slice, so `useCreateSuite` — dead in the data module since Phase 1 — has its first consumer. **As of §31 the SPA finally asks what the caller may do**: `GET /me/permissions` — in the contract and implemented since the mock era, and never once called — now gates eleven write affordances, so an action the server will refuse renders disabled with a reason instead of inviting a 403. **As of §34 a company can create its own projects** — the blocker named in §31, §32 and §33 is closed, so "provisioned company" no longer means "provisioned company plus hand-written SQL". The gate is `project:create` at **COMPANY scope**, one level above the project, because there is no project grant for a project that does not exist yet. **As of §35 the oldest dead hook has a caller**: `useCaseVersions` was seven slices old with zero call sites, and the case detail now renders its authored history, gap and all. **As of §36 the frontend has a test runner and 203 tests** — Vitest + RTL, mutation-checked rather than watched to pass, and wired into CI at integration. **As of §38 the `execution` module exists at all** — plans, runs, executions and the recorded result are live, and the module crosses into `repository` through exactly one class that reads the *pinned snapshot* rather than the live case, so an execution shows what it ran. **As of §39 a company can staff its own projects**: `POST`/`DELETE /role-assignments` mean a colleague who signs in is no longer guaranteed an empty project list, which was the last blocker of the §34 shape. **As of §40 Phase 5 has finally been run** — the hostile review found four majors, all by driving the app and none by reading it, two of which had survived three previous browser passes. **As of §42 a run can be CREATED, which is what makes the product usable end to end**: `POST /plans` and `POST /runs` land, and creating a run resolves the plan's scope and writes one execution per case **pinned to that case's current version** — the first write of a `case_version_id`, and the thing that materialises the checklist a result is recorded against. It was driven live, the first live pass in three runs, and found two defects no gate could: a successful create navigating into an unimplemented route, and a `NOT_STARTED` run rendering "Still running" because until this slice **no run could reach that state**. **As of §43 `company:read` finally works at company scope** — a `Company Member` baseline granted on joining and backfilled, with the evaluator's scope model left alone. **As of §44 a sibling tab no longer lies about who is signed in**, focus revalidation is on with a written reason, and the case grid keeps its rows while you type. **As of §45 the same permission defect has been found twice in one day and turned into a computed check** (see the box below) — `V16` makes Automation Service reachable at all, which M3 ingestion depends on. **As of §46 plan statistics is live and the `execution` module is read-complete**: the endpoint §42's live pass navigated *into* and 404'd on now renders, so creating a plan lands on a working page. Two thirds of the blocker three sessions had recorded turned out to be already unblocked — coverage never needed a repository-wide denominator (§6p defines it over the plan's own scope) and `bySuite` had its seam since §24. **As of §47 the project's landing page is live and the `repository` module is read-complete**: the dashboard's tiles, breakdowns, coverage and activity chart all run against the real API, and building it found that the by-owner tiles emitted an `ownerId` the case list never bound — a link that silently opened *everyone's* cases under a heading naming one person. `documentation`, `reporting` and `work` writes still run on MSW. **As of §48 the contract is checked by machine rather than by reading** — the debt §42–§47 each recorded and none paid. Four computed checks over `openapi.yaml` found that **24 of 49 operations declared no 4xx at all** (so the generated types said a 401 could not happen on half the API) and **11 schemas declared no `required`**, flipping 29 properties from optional to required once repaired — including `PageMeta` and the case list's own `items`/`facets`. The fourth check is §47's own defect made mechanical, and its mutation left 26 of the other 27 dashboard tests green, which is the proof it was not already covered. **As of §49 the case execution timeline is live, which is the screen the version pin exists for**: the "Execution history" button the case detail has rendered since Phase 1 led to a route that 404'd, and now shows "green, green, *edited to v2*, red, red" — §6p's causation shape, driven live. Building it found **the same defect class §47 shipped and §48 made mechanical, in a place §48's check does not reach**: the SPA has always sent `?scope=CURRENT_VERSION`, the contract never declared it, and Spring silently ignores an unbound parameter — so the "Current version" chip would have answered with every version's results under a heading saying otherwise. It also found that `VersionMarker.author` was typed non-null while the column has no foreign key, and the tightened type failed the build on an **unguarded dereference that would have crashed the page**. §6p's "deleted runs must not orphan history" needed a new required field (`runDeleted`) to be expressible at all. **As of §50 the defect class §47 and §49 each named on the way out is machine-checked at both ends, and building it found the direction neither had described**: the two known cases were *SPA sends a key the contract does not declare*, and the symmetric one — *the contract declares a parameter no handler binds* — is worse, because there the reviewed spec, the generated types and autocomplete all agree with the caller while the server ignores it. Three live instances, one of them on **§49's own endpoint** (`releaseVersion` and `configurationId`, deleted rather than implemented since nothing sends them) and two created by a filter block written on the **path item**, which every operation under it inherits — so `POST /cases` declared the case list's whole fourteen-parameter query. The SPA-side check drives the **TypeScript compiler** rather than reading text, because the case list spreads `...filters` and those fourteen keys are a *type*. **No runtime code changed in this slice**; all three defects were latent in the contract and the generated types.
**As of §52 the suite execution timeline is live and the page finally has a way in** — nothing had linked to `/suites/{id}/history` since Phase 1. The orientation pass found the defect before any code: the SPA declared its own `{ runs: … }` response type at the call site while the contract said `entries`, and the MSW handler was written from the same hand-made shape, so **mock mode agreed with itself and live mode would have rendered nothing**. The lesson is that `api.get<T>` is an *assertion*, not a comparison — only a **generated** type puts the contract on the other side of it — and `apiResponseTypes.test.ts` now refuses an inline literal at all 45 `api.*` call sites. Driving it found two more, both shared rather than local: **every table in the app was clipped instead of scrollable** (`Panel`'s `overflow: hidden`, so all eight of them), and **signing out and in as somebody else in the same tab kept the previous user's data** — §44 built that protection and it ran for every tab except the one the user was looking at, because `BroadcastChannel` does not deliver to its sender.
**As of §55 a provisioned company can add its own people**, which closes the last leg of the staffing loop (§34 projects → §55 people → §39 grants → §54 the picker). `company:members` is defined as "invite, remove, set membership display names"; this is the first third, and **removing a person is still not possible over HTTP** — `CompanyMembership.remove()` and `rename()` have no caller, which is §28's dead-code question with one more instance.
**Build status:** `backend` — `./gradlew build` clean, **1002/1002 tests green against Testcontainers Postgres 17**, migrating as a **non-superuser owner** (§19). `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 401/401** — and that number is *stable* since §54 fixed a suite-wide flake (`testTimeout`, not `asyncUtilTimeout` — the first attempt made it worse). **Last driven in a real browser in LIVE mode in §55**, which found the three-state defect above by clicking Send twice; it also proved the `citext` match, the untouched outstanding invitation and the single audit row against a real database. (§54's live pass was the one slice in five that found nothing.) (§53's live pass found the one no gate could see:  both derived card types rendered **without the reason the server was already sending**, because `WorkCard` never rendered `description` while the backend test asserting its contents passed.) (§52's live pass caught two on the sibling screen: every table in the app was clipped rather than scrollable, and a sign-out/sign-in in one tab kept the previous user's data.) (§49's live pass caught the copy defect on the sibling screen: every case timeline ended with "Edited to v1", a change nobody made, on the screen whose whole job is answering "what changed?".) (§47's live pass caught the sibling: MUI turns a unitless `minHeight: 1` into `100%`, so every zero-activity day drew a full-height bar.) **`docker compose up` serves provision → accept-in-the-browser → sign in against a non-superuser owner** (§18, §20), and its aggregate `/actuator/health` now reports **UP** — the `mail`-indicator defect §20 found is **fixed in §21**. CI runs both gates on GitHub Actions.
**Branch:** `master`. Per the git workflow, **work continues on the checked-out branch; do not branch per task** — and since §45 ended the parallel experiment, the concurrency-worktree exception should not be needed. The two worktrees §45 used were merged; prune anything left under `.claude/worktrees/`. Last commit: §55, the handoff. **Migrations still run to `V17`** — §55 added no DDL, because every table the invitation flow needs has existed since `V1`/`V6`; **`V18` is the next free number**. `V17` was verified applying to an **already-migrated** database (v16 → v17) in §54's live pass, which is the one thing Testcontainers structurally cannot check.

> **✅ The `company:read` gap is closed — §43** — by the baseline COMPANY-scope grant, a seventh
> built-in role (`Company Member`) that is deliberately **invisible on the wire**. The evaluator's
> scope model is unchanged; the rejected "held anywhere in this company" alternative is recorded in
> the roles spec §A3 with its reasoning.
>
> **⚠ It happened a second time the same day, and that is the lesson — §45.** Automation Service
> held its five §A6 capabilities and no `project:read`, and every project-scoped path asks
> `ProjectAccess.canView` — which *is* `project:read` — **before** asking the permission. So the
> role 404'd on everything and was inert over HTTP; `canView`'s own javadoc justified the method on
> a claim about the six roles that was **false when written**. Repaired by `V16`. **Both defects
> were invisible to every reading of `V3`, because the defect is an absent row.** The rule is now a
> computed check over the catalogue (`SeedDataTest`), and the repair for this family is always
> *grant the missing permission*, never *weaken the gate*.

> **⚠ A file-sync daemon on this machine creates `<name> 2.<ext>` conflict copies, and they BREAK
> THE BUILD — in TWO ways. The second one is a HANG, not an error (§45).** 105 `* 2.xml` copies in
> `backend/build/test-results/` are hashed by Gradle as task inputs and stall `:test` for 20 minutes
> with no output at all; `find build -name "* 2.*" -delete` and the same build took 1m49s. The
> original mode is a compile error: 14 were sitting in the tree at the start of §30 — `ProjectController 2.java` declares
> `public class ProjectController`, which is a hard `compileJava` error, so the backend did not
> compile on arrival. They are gitignored inside `build/` and untracked in `src/`, so **`git status`
> does not always show them and a clean `git status` does not mean a clean build.** They reappeared
> mid-session the moment a script bulk-deleted and recreated a directory. **Check
> `find . -name "* 2*" -not -path "./.git/*"` before trusting a build failure**, and prefer editing
> files in place over `rm -rf` + `cp -R` on a source directory.
>
> **⚠ The pattern above lost its trailing dot in §47, and that is the fix.** The command as
> originally written — `-name "* 2.*"` — **cannot match a conflict-copy DIRECTORY**, because
> directories have no extension. §47 hit the 10-minute hang with that check reporting zero matches,
> while `build/classes 2`, `build/generated 2`, `build/reports 2` and `build/resources 2` sat there;
> `jstack` on the daemon showed it parked in `DirectorySnapshotter`. `rm -rf build` fixed it.
> **Also:** two concurrent `./gradlew` invocations block on the build lock and emit *no output at
> all* while waiting, which is indistinguishable from this hang — check for a second gradle process
> before blaming the sync daemon.

> **✅ The silently-ignored query parameter is machine-checked at both ends — §50.** It had shipped
> twice (§47's dashboard tile opened everyone's cases under a heading naming one person; §49's
> "Current version" chip would have returned every version's results). Both sections ended by
> naming the same missing check and it is now built — **and it found the direction neither had
> thought of.** The two known cases were *SPA sends, contract does not declare*; the symmetric and
> worse case is **contract declares, handler does not bind**, because there every artifact agrees:
> the parameter is in the reviewed spec, generated into `schema.d.ts`, and offered by autocomplete.
> Three live instances, one of them on §49's own endpoint. `contractParameters.test.ts` drives the
> **TypeScript compiler**, not a regex — the case list spreads `...filters`, so its fourteen keys
> are a *type*, and a field added to `CaseFilters` is how the next one arrives.
>
> **A path item must declare no query parameters at all (§50).** They are inherited by every
> operation under it, so a filter block written once for the list also lands on the POST beside it.
> The rule is strict rather than "…that every operation binds" on purpose: the six benign cases are
> benign only because they have one operation each, and that accident is invisible where the
> parameter is written.
>
> **⚠ A "Not done" note decays, and §46 found one that was two-thirds wrong.** The statistics
> endpoint was recorded as blocked on a `reporting` module in the contract, in §38 and again in §45.
> By the time anyone checked, `bySuite` had had its seam since §24 and `coveragePercent` had **never**
> needed the repository-wide denominator it was credited with — §6p defines coverage over the plan's
> own scope. Three sessions inherited the claim without re-testing it. **The orientation pass's first
> question is "which of these has a consumer" (§28); its second is now "is this still blocked".**

> **⚠ Re-derive the next slice from the code, not from the last session's "Next" list — §28, and
> again in §29.** §27's three named next steps were all unsupportable; §28's own list then led with
> `/versions` again, and `useCaseVersions` **still has no consumer**. Twice in a row, the same way —
> so it is a property of writing that list at the end of a slice, not a mistake. The orientation
> pass is not optional, and its first question is *which of these has a consumer*. One grep each.

> **✅ The suite's superuser owner is gone — §19.** `IntegrationTestBase` now creates a **non-superuser
> `tcms_owner`** before Flyway, mirroring `deploy/postgres/initdb/10-tcms-roles.sql`, and `PrivilegeSplitTest`
> pins every property of the split — including that **`FORCE ROW LEVEL SECURITY` covers the owner**, the
> assertion the old harness structurally could not make and the one V7's HIGH defect turned on. The
> container's superuser is now used only for ground truth in assertions.


> **✅ The §10 concerns from the previous handoff are resolved.** The two uncommitted doc files are committed (`d08590b`) and `§6v` exists — the dangling reference is gone. §10 is retained as the record of that strand's decisions, not as an outstanding warning.
>
> **⚠ The DDL is no longer unexecuted — and running it found two defects.** See §11.
>
> **⚠ Every repository interface now needs `@Transactional`, and a test enforces it.** Without it a
> repository's *query methods* run outside any transaction, so `app.current_company` is never set and
> every RLS-protected read returns **zero rows**. Fail-closed, but it silently emptied the permission
> evaluator. See §15.

---

## How this handoff is organised

**Every section is its own file under [`docs/handoff/`](handoff/), numbered by its § number.**
A reference anywhere in this repo — `§6 lesson 5`, `handoff §31`, `see §19` — resolves by the
filename prefix. This file is the index and the current-state summary above; it holds no section
bodies.

**Writing a new section: add a file, do not append here.** `docs/handoff/NN-slug.md`, then one row
in the table below. That is the whole convention, and it exists so two sessions finishing slices
at the same time do not both edit one 276 KB file — which is exactly what §10 records going wrong.
See [`docs/plans/2026-07-31-parallel-execution-rework.md`](plans/2026-07-31-parallel-execution-rework.md).

### Standing sections — read these first

| § | Topic |
|---|---|
| [§0](handoff/00-plugin-skills-are-mandatory.md) | IMPORTANT — Use the installed plugin skills |
| [§1](handoff/01-what-this-project-is.md) | What this project is |
| [§2](handoff/02-artifacts-produced.md) | Artifacts produced |
| [§3](handoff/03-decisions-locked-in.md) | Decisions locked in (do not re-litigate without reason) |
| [§4](handoff/04-frontend-current-state.md) | Frontend — current state |
| [§5](handoff/05-not-done-next-session-starts-here.md) | NOT done — next session starts here |
| [§6](handoff/06-known-risks-and-lessons.md) | Known risks and open items |
| [§7](handoff/07-how-to-run.md) | How to run |
| [§8](handoff/08-working-agreement.md) | Working agreement for this project |
| [§9](handoff/09-frontend-multi-company-alignment.md) | Multi-company alignment of the frontend — DONE 2026-07-30 |
| [§10](handoff/10-the-second-concurrent-work-strand.md) | A SECOND, CONCURRENT WORK STRAND — read before editing the plan |

### The slice log — newest last

| § | Slice |
|---|---|
| [§11](handoff/11-m0-first-build.md) | Backend M0 — first build |
| [§12](handoff/12-m0-v2-and-v3.md) | Backend M0 — V2 and V3 |
| [§13](handoff/13-m05-identity-and-permission-evaluator.md) | Backend M0.5 — the identity module and the permission evaluator |
| [§14](handoff/14-m0-async-context-and-the-reset-guc-defect.md) | Backend M0 — the async-context harness, and the reset-GUC defect |
| [§15](handoff/15-m0-provision-company.md) | Backend M0 — `provision-company`, and the two defects writing it found |
| [§16](handoff/16-m0-archunit-and-modulith.md) | Backend M0 — ArchUnit + Modulith verification |
| [§17](handoff/17-m0-compose-ci-and-the-privilege-split.md) | Backend M0 — Docker Compose, CI, and the deployment privilege split |
| [§18](handoff/18-m05-invitation-activation-and-login.md) | Backend M0.5 — invitation, activation and local login |
| [§19](handoff/19-harness-stopped-migrating-as-superuser.md) | Backend — the test harness stopped migrating as a superuser |
| [§20](handoff/20-frontend-live-api-mode.md) | Frontend — the two halves meet: live API mode, real login, accept-invitation |
| [§21](handoff/21-health-stops-reporting-down.md) | Backend — the default install stops reporting itself DOWN |
| [§22](handoff/22-m1-s1-project-list-and-detail.md) | Backend M1, slice 1 — the project list and detail, and the second gate over HTTP |
| [§23](handoff/23-m1-s2-project-vocabularies-and-the-shared-gate.md) | Backend M1, slice 2 — the two project vocabularies, and the gate that is now shared |
| [§24](handoff/24-m1-s3-the-suite-tree.md) | Backend M1, slice 3 — the suite tree, and the `repository` module's first endpoint |
| [§25](handoff/25-m1-s4-the-case-list.md) | Backend M1, slice 4 — the case list, and the screen that finally renders live |
| [§26](handoff/26-m1-s5-the-condition-library.md) | Backend M1, slice 5 — the condition library, and the last error on the case list |
| [§27](handoff/27-m1-s6-the-case-detail.md) | Backend M1, slice 6 — the case detail, and the SPA's order defect the mock could not show |
| [§28](handoff/28-m1-s7-the-first-write.md) | Backend M1, slice 7 — the first write, and why §27's own next-step list was wrong |
| [§29](handoff/29-m1-s8-case-create-and-the-key-allocator.md) | Backend M1, slice 8 — case create, the key allocator, and v1 |
| [§30](handoff/30-m1-s9-suite-writes.md) | Backend M1, slice 9 — suite writes, and the first slice that shipped both halves |
| [§31](handoff/31-frontend-effective-permissions-disable-dont-hide.md) | Frontend — the caller's effective permissions, and disable-don't-hide everywhere at once |
| [§32](handoff/32-m1-s10-case-delete-and-the-own-qualifier.md) | Backend M1, slice 10 + frontend — case delete, and the `OWN` qualifier it forced |
| [§33](handoff/33-parallel-execution-rework.md) | The parallel-execution rework — the three write-conflict files, the handoff, and a second stack |
| [§34](handoff/34-m1-s11-project-creation.md) | Backend M1, slice 11 + frontend — project creation, and the gate one level above the project |
| [§35](handoff/35-m1-s12-case-versions.md) | Backend M1, slice 12 + frontend — case version history, and the oldest dead hook's first caller |
| [§36](handoff/36-frontend-phase-4-vitest-rtl.md) | Frontend Phase 4 — Vitest + RTL, and 203 tests that can go red |
| [§37](handoff/37-the-first-parallel-run.md) | The first parallel run — three lanes, what merged clean, and what integration had to fix |
| [§38](handoff/38-m2-s1-the-execution-module.md) | Backend M2, slice 1 + frontend — the `execution` module, and the seam it could not cross |
| [§39](handoff/39-m1-s13-role-grants.md) | Backend M1, slice 13 + frontend — role grants, and the `company:read` gap they exposed |
| [§40](handoff/40-frontend-phase-5-review.md) | Frontend Phase 5 — the hostile review, and four defects only driving it found |
| [§41](handoff/41-the-second-parallel-run.md) | The second parallel run — the holder rule that broke before launch, and the contract habit that is now three-for-three |
| [§42](handoff/42-m2-s2-plan-and-run-creation.md) | Backend M2, slice 2 + frontend — plan and run creation, and the two defects only the live pass found |
| [§43](handoff/43-the-company-read-scope-repair.md) | Backend — the `company:read` scope repair, and the seventh role that stays off the wire |
| [§44](handoff/44-frontend-resilience-residuals.md) | Frontend — the resilience residuals, and the sibling tab that lied about who was signed in |
| [§45](handoff/45-the-third-parallel-run-and-the-end-of-the-experiment.md) | The third parallel run — the same permission defect twice, and the decision to stop running lanes |
| [§46](handoff/46-m2-s3-plan-statistics.md) | Backend M2, slice 3 + frontend — plan statistics, and the panel that hid an environment |
| [§47](handoff/47-m1-s14-the-dashboard.md) | Backend M1, slice 14 + frontend — the repository dashboard, and the tile that linked to the wrong list |
| [§48](handoff/48-contract-hygiene-computed-checks.md) | The contract-hygiene computed checks — the debt five sessions recorded and none paid |
| [§49](handoff/49-m1-s15-the-case-timeline.md) | Backend M1, slice 15 + frontend — the case timeline, and the parameter nobody had declared |
| [§50](handoff/50-contract-parameter-binding-checks.md) | The parameter checks at both ends — the rung §47 and §49 each named on the way out |
| [§51](handoff/51-open-question-decisions.md) | The open-question decision round — nine settled, two of them by reading rather than deciding |
| [§52](handoff/52-m1-s16-the-suite-timeline.md) | Backend M1, slice 16 + frontend — the suite timeline, and the field the two sides spelled differently |
| [§53](handoff/53-m1-s17-the-work-module.md) | The `work` module + the `case_health` writer — the board runs on a server, and the check that would have caught it |
| [§54](handoff/54-m1-s18-project-members-and-the-boards-three-gaps.md) | The board's three gaps — project members, `V17` idempotency, the fifth column, and the flake that predated the slice |
| [§55](handoff/55-m1-s19-inviting-a-colleague.md) | Inviting a colleague — the write nine sessions named, and the field the live pass added |
