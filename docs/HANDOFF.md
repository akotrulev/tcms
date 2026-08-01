# TCMS — Session Handoff

**Date:** 2026-08-01 (last session: **the third and final parallel run** — §42 · §43 · §44, with §45
recording it **and the decision to stop running lanes**. Work is **sequential from here** unless the
user asks otherwise: the seams held all three runs, but wall clock is bounded by the longest lane and
every lane re-derives the same premises. The M3/M4 fan-outs in the parallel plan §5 remain the one
shape that would justify revisiting it.)
**State:** Planning and design complete **including multi-company tenancy**. **Backend M0 is COMPLETE**, and **M0.5 now has a usable login** (§18): invitation → activation → sign-in works end to end, `CompanyContext` finally has a production entry point, and the permission evaluator answers over HTTP. **The instance is reachable for the first time.** Frontend complete through **Phase 3** and aligned with the multi-company decisions (§9), and **the two halves have now met (§20)**: `VITE_API_MODE=live` points the SPA at the real backend through a dev proxy, `/accept-invitation` redeems a real CLI-issued link, and a real session survives a reload. **As of §25 the SPA's biggest screen renders live**: the case grid, its suite tree, its facet chips and its filters all run against the real API. **As of §26 that screen raises no errors at all** — its condition library is served, so nothing on it 404s. **As of §27 the case *detail* screen is live too, including §6f's suite-condition inheritance** — which makes the `repository` module's read surface complete. **As of §28 the product has its first WRITE**: editing a case is live end to end, `test_case_version` has a writer so §6j history actually accumulates, `audit_event` has its first row, and the write gate is a permission (`case:edit`) rather than the read gate every role holds. **As of §29 a case can be CREATED live**: `TC-n` is allocated from the company's `key_sequence` by the one non-racing statement, v1 is written with the case so a created case has no history gap, and the `Idempotency-Key` the SPA has sent since Phase 1 is finally read — a retry returns the case it already made. **As of §30 a provisioned company is finally usable without hand-written SQL**: suites can be created, renamed and deleted from the tree, both halves in one slice, so `useCreateSuite` — dead in the data module since Phase 1 — has its first consumer. **As of §31 the SPA finally asks what the caller may do**: `GET /me/permissions` — in the contract and implemented since the mock era, and never once called — now gates eleven write affordances, so an action the server will refuse renders disabled with a reason instead of inviting a 403. **As of §34 a company can create its own projects** — the blocker named in §31, §32 and §33 is closed, so "provisioned company" no longer means "provisioned company plus hand-written SQL". The gate is `project:create` at **COMPANY scope**, one level above the project, because there is no project grant for a project that does not exist yet. **As of §35 the oldest dead hook has a caller**: `useCaseVersions` was seven slices old with zero call sites, and the case detail now renders its authored history, gap and all. **As of §36 the frontend has a test runner and 203 tests** — Vitest + RTL, mutation-checked rather than watched to pass, and wired into CI at integration. **As of §38 the `execution` module exists at all** — plans, runs, executions and the recorded result are live, and the module crosses into `repository` through exactly one class that reads the *pinned snapshot* rather than the live case, so an execution shows what it ran. **As of §39 a company can staff its own projects**: `POST`/`DELETE /role-assignments` mean a colleague who signs in is no longer guaranteed an empty project list, which was the last blocker of the §34 shape. **As of §40 Phase 5 has finally been run** — the hostile review found four majors, all by driving the app and none by reading it, two of which had survived three previous browser passes. **As of §42 a run can be CREATED, which is what makes the product usable end to end**: `POST /plans` and `POST /runs` land, and creating a run resolves the plan's scope and writes one execution per case **pinned to that case's current version** — the first write of a `case_version_id`, and the thing that materialises the checklist a result is recorded against. It was driven live, the first live pass in three runs, and found two defects no gate could: a successful create navigating into an unimplemented route, and a `NOT_STARTED` run rendering "Still running" because until this slice **no run could reach that state**. **As of §43 `company:read` finally works at company scope** — a `Company Member` baseline granted on joining and backfilled, with the evaluator's scope model left alone. **As of §44 a sibling tab no longer lies about who is signed in**, focus revalidation is on with a written reason, and the case grid keeps its rows while you type. **As of §45 the same permission defect has been found twice in one day and turned into a computed check** (see the box below) — `V16` makes Automation Service reachable at all, which M3 ingestion depends on. **As of §46 plan statistics is live and the `execution` module is read-complete**: the endpoint §42's live pass navigated *into* and 404'd on now renders, so creating a plan lands on a working page. Two thirds of the blocker three sessions had recorded turned out to be already unblocked — coverage never needed a repository-wide denominator (§6p defines it over the plan's own scope) and `bySuite` had its seam since §24. `documentation`, `reporting` and `work` writes still run on MSW.
**Build status:** `backend` — `./gradlew build` clean, **843/843 tests green against Testcontainers Postgres 17**, migrating as a **non-superuser owner** (§19). `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 255/255**, and **driven in a real browser in LIVE mode again this run** (§46) — the plan statistics page against an already-migrated volume, including the two states that had never rendered before it (an unevaluated exit criterion, and a stale plan). **`docker compose up` serves provision → accept-in-the-browser → sign in against a non-superuser owner** (§18, §20), and its aggregate `/actuator/health` now reports **UP** — the `mail`-indicator defect §20 found is **fixed in §21**. CI runs both gates on GitHub Actions.
**Branch:** `master`. Per the git workflow, **work continues on the checked-out branch; do not branch per task** — and since §45 ended the parallel experiment, the concurrency-worktree exception should not be needed. The two worktrees §45 used were merged; prune anything left under `.claude/worktrees/`. Last commit: §46, plan statistics — the first sequential slice since the experiment ended, and it needed nothing the lanes provided. **Migrations still run to `V16` (§46 added none); `V17` is the next free number.**

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
> `find . -name "* 2.*" -not -path "./.git/*"` before trusting a build failure**, and prefer editing
> files in place over `rm -rf` + `cp -R` on a source directory.

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
