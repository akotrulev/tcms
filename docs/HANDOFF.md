# TCMS — Session Handoff

**Date:** 2026-08-07 · **Branch:** `master` · **Latest slice:** [§120 — automation
drift](handoff/120-automation-drift.md)

This file is an **index**. Every section is its own file under [`docs/handoff/`](handoff/), numbered
by its § number, and the slice log below is where the narrative lives — one row per slice, each row
naming what the slice found. **This header holds only what is true right now**; anything that reads
like a story belongs in the section file, not here. The long "before that" chain this file used to
carry was removed on 2026-08-06 because it duplicated the section files and had gone stale in three
places at once (it still described MSW as live, `V21` as the migration head, and §74 as the last
commit).

## Current state

**Milestones.** M0, M0.5 and M1 are complete. M2 is complete through the rollups (§101). **M2.5's
reporting lane is COMPLETE** — R8, version divergence (§114), was the last slice in the plan, and
R1–R8 plus F1–F2 have all shipped. What that plan deliberately does not cover is listed in its §8,
and **§115 took one item off that list**: the coverage matrix, which was blocked only on `doc_link`
having a writer — a blocker §93 had already removed. The rest of §8 stands, and every remaining entry
is blocked on infrastructure or on a deferred decision rather than on a writer (PDF and Mermaid's
SVG pre-render both need a headless renderer; evidence-bundle export needs the `shared` module;
scheduled reports need a notification channel). The organization-model restatement (§97, §98, §99) is complete with nothing
outstanding. **The next slice is re-derived from the code, not from this plan's tail** (§28, §29,
§46) — and that re-derivation landed **M3** at §116: three `V1` tables had never had a writer
(`api_token`, `ingestion_job`, `automation_key_mapping`), and §116 gave the first of them one,
because the other two have no caller without a credential. ~~**`ingestion_job` and
`automation_key_mapping` still have none**~~ → **§117 gave both one**, so `V1`'s writerless tables
are down to **zero**. M3 slice 2 needed **no migration**: every table, column and `CHECK` it writes
to has been there since M0. **§118 is M3's third slice** and the first frontend one since §115: the
token screen both §116 and §117 named on their way out, which gives `/api-tokens` its first consumer
in `src/` and makes obtaining a CI credential something the product can do rather than something
`curl` can. It carried no contract change either. **§119 is M3's fourth slice and closes the loop**:
`GET /ingestions` and `GET /ingestions/{jobId}` — the second being the resource `POST /ingestions`'
`Location` header has named since it shipped, at which there had never been a `GET` — plus the
history screen and the unmatched-test binder, which give `/automation-mappings` its first consumer
and make §117's refusal of §6c's auto-created case stubs defensible for somebody who cannot read
JSON. Its contract change is additive (one operation, one path, one schema). **§120 is M3's fifth
slice and the first that ships no screen**: §6c's automation drift, which is the pair
`case_health.stale_automation` has waited for since `V1` — its predicate is *"no **ingested** result
in N days"* and until §117 nothing in the product could produce one. Five of the ten health signals
have a writer now, up from three, and `V37` adds the mirror the schema never had
(`undeclared_automation`, a case taking CI results while its own `automationStatus` says it has no
automation). **The frontend half is two exhaustive records and one decision** — the dashboard tile,
the grid facet and the chip are generic over the enum, exactly as `HealthSignal`'s header predicted
in M0.

**Gates, as of §120** — both run locally, and only locally. Both halves were re-run for §120:

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **BUILD SUCCESSFUL — 1963 tests across 410 result classes, 0 failures, 0 skipped** |
| `cd frontend && npm run build` | clean |
| `npm run lint` | 0 warnings |
| `npm run test:run` | **1021/1021 across 77 files** (measured, over §119's measured 1018/76. This figure was stale four times running before §119 started reading it off a run — keep doing that) |

**CI runs no backend tests** — `.github/workflows/ci.yml` has run `./gradlew build -x test` since
§59, so `-Werror` and the Modulith wiring are checked and nothing else is. `./gradlew build` on your
own machine is the only thing that runs the integration suite. See §58 for what a macOS-only gate
structurally cannot observe.

> **A nightly Linux lane is written but NOT LIVE (§113).** `.github/workflows/tests.yml` runs the
> full `./gradlew build` on `[self-hosted, tcms-lab]` — the homelab box — which is a *differently
> shaped* gate, not just a free one. **It is inert until that runner is registered**
> (`deploy/shanks/install-runner.sh`), so the paragraph above still holds in full. Do not read the
> file's existence as coverage.

**Schema.** Flyway head is **`V37__case_health_undeclared_automation.sql`** (§120); **`V38` is the
next free number**. Neither §117 nor §119 carried a migration; `V37` adds one **column** and two
partial indexes to a table that has been policed since M0, so the count is still
61 tables under `FORCE ROW LEVEL SECURITY` — `V36`'s `api_token_lookup` is the **twelfth**
instance-level table and the ERD §0.1 exception list's newest entry, decided rather than assumed. Flyway owns all DDL
(`hibernate.ddl-auto: validate`).

**There is no mock API** (§107). `src/mocks/` — ~9,300 lines of MSW handlers and fixtures — and
`VITE_API_MODE` were deleted, so `npm run dev` proxies `/api` to `localhost:8080` and **requires
`docker compose up` at the repo root plus a provisioned company**. The fault switch (`tcmsFaults`)
now sits in front of `fetch` and faults the real backend.

## Open actions — do these

1. **Rotate the production JWT signing key (§108).** `${TCMS_JWT_SECRET:?…}` is shell syntax Spring
   does not implement, so it was read as a *default* and the production instance signed every session
   token with a 59-character string that is in this repository. The code is fixed and
   `ConfigConventionTest` reads the shipped text, but **the key itself is still public** — the
   operator must rotate it, and `TCMS_JWT_SECRET` must exist in `/etc/tcms/tcms.env` before the next
   deploy or the app will refuse to start.
2. **Run the verification queues.** See below.
3. **Write §110's section file.** The browser pass is part-driven (commits `9cccb2a`, `ccf9eea`: the
   `F` lane 6 of 9 rows, no product defect, three harness findings) and has no
   `docs/handoff/110-*.md` yet — which is why the slice log jumps 109 → 111.

## Verification — deferred, batched, and still unrun

> **⚠ Browser passes and backend mutation checks are DEFERRED AND BATCHED — §65, standing policy,**
> set 2026-08-02 at the user's direction: ship the remaining functionality while requirements are
> still moving, then verify in one concentrated pass. Two queues:
> [`docs/verification/frontend-browser-checks.md`](verification/frontend-browser-checks.md) and
> [`docs/verification/backend-mutation-checks.md`](verification/backend-mutation-checks.md).
>
> **A slice must still state the mutation it would have run and the flow it would have driven**,
> appended to the right queue in a form somebody else can execute. A slice that lands without adding
> its rows has skipped the check outright, which is the thing batching exists to avoid.
>
> **Until a queue is run, the slices in it are *built and unverified* — say that, rather than
> calling them done.** Every slice from §65 onward is in that state except where a row says
> otherwise. The browser queue is **one live pass of thirteen ordered lanes since §109**; row ids are
> quoted from handoff sections and are never renumbered.
>
> **The risk is the reversal itself.** The live pass has found a defect **no gate could see** in most
> slices since §42 — §62's unselectable option, §57's permanent skeleton, §53's reasonless card,
> §52's clipped tables, §64's contradictory messages. Deferring does not make those less likely; it
> makes them arrive later and all at once.

## Standing traps

> **⚠ A file-sync daemon on this machine creates `<name> 2.<ext>` conflict copies, and they break the
> build in two ways — the second is a HANG, not an error (§45, §47).** 105 `* 2.xml` copies under
> `backend/build/test-results/` are hashed as task inputs and stall `:test` for 20 minutes with no
> output. The other mode is a compile error: `ProjectController 2.java` declares
> `public class ProjectController`. They are gitignored inside `build/` and untracked in `src/`, so
> **a clean `git status` does not mean a clean build.** Check
> `find . -name "* 2*" -not -path "./.git/*"` — **without the trailing dot**, which §47 lost and
> which cannot match a conflict-copy *directory*, since directories have no extension.
>
> **⚠ Two concurrent `./gradlew` invocations look identical to that hang** — they block on the build
> lock and emit no output at all. **A subagent running the gate is a second lane** (§86, §98): each
> `--stop` orphans the other's test worker, which keeps holding `output.bin`, so every retry dies on
> a lock the previous retry left and the true cause never appears in the error. A catastrophic
> failure count is a signal to check the harness before reading an assertion.
>
> **⚠ Re-derive the next slice from the code, not from the last session's "Next" list (§28, §29, and
> §46).** §27's three named next steps were all unsupportable; §46 found a blocker that had been
> two-thirds wrong for three sessions. The orientation pass's first question is *"which of these has
> a consumer"*, and its second is *"is this still blocked"*.

Everything else that used to live here — the resolved warnings, the lessons, the per-slice accounts —
is in the section files. Start with **[§5, NOT done — next session starts
here](handoff/05-not-done-next-session-starts-here.md)** and
**[§6, known risks and open items](handoff/06-known-risks-and-lessons.md)**.

---


## How this handoff is organised

**Every section is its own file under [`docs/handoff/`](handoff/), numbered by its § number.**
A reference anywhere in this repo — `§6 lesson 5`, `handoff §31`, `see §19` — resolves by the
filename prefix. This file is the index and the current-state summary above; it holds no section
bodies.

**Writing a new section: add a file, do not append here.** `docs/handoff/NN-slug.md`, then one row
in the table below. That is the whole convention, and it exists so two sessions finishing slices
at the same time do not both edit one enormous file — which is exactly what §10 records going wrong.
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

**§110 has no row because it has no file yet** — the browser pass is part-driven and unwritten, see
Open actions above. The gap is deliberate; do not renumber around it.

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
| [§56](handoff/56-m1-s20-removing-a-person.md) | Removing a person — the half §55 named on its way out, and the token that outlived the status |
| [§57](handoff/57-m1-s21-the-documentation-module.md) | The `documentation` module — the biggest MSW-only surface, and the block id that must survive an edit |
| [§58](handoff/58-the-first-real-deployment.md) | The first real deployment — the box exists, and four things the runbook only learned by being run |
| [§59](handoff/59-issue-invitation-and-the-undeliverable-link.md) | `issue-invitation` — the link that was never emailed, the mail that stays off, and the CI lane that stopped running tests |
| [§60](handoff/60-the-clock-that-outresolved-its-database.md) | The clock that out-resolved its database — §58's carried debt closed, two call sites that bypassed the fix, and the rule that watches them |
| [§61](handoff/61-the-artifact-register-and-the-plan-scope-qualifier.md) | The artifact register — the dead link in the nav rail, and the qualifier the roles spec has had since M0 |
| [§62](handoff/62-cases-bulk-and-the-gate-that-depends-on-its-argument.md) | `cases/bulk` — the gate that depends on its argument, and the option that could not be chosen |
| [§63](handoff/63-project-access-and-the-password-managers.md) | The project's own access screen — the exemption a test moved into the helper, and telling the password managers |
| [§64](handoff/64-the-shared-step-library.md) | The shared-step library — the derived count, and the reference persisted unchecked since M1 |
| [§65](handoff/65-permission-qualifiers-on-the-wire.md) | Permission qualifiers on the wire — two computations become one, and the two verification queues |
| [§66](handoff/66-the-own-qualifier-reaches-the-row.md) | The `OWN` qualifier reaches the row — the author on the wire, and the test that passed while pending |
| [§67](handoff/67-evidence-attach-and-delete.md) | Evidence attach and delete — the port, the write ordering, and the invariant deliberately not implemented |
| [§68](handoff/68-classification-assignment.md) | Classification assignment — the reconcile's bound, the leaf rule that never existed, and the required field the detail never served |
| [§69](handoff/69-the-classification-vocabulary-writer.md) | The classification vocabulary editor — the gate the dimension decides, and the label that stopped spelling the name |
| [§70](handoff/70-the-verb-the-contract-checks-could-not-see.md) | The verb the contract checks could not see — `api.delete` never existed, and eight call sites went unchecked behind a guard that counts |
| [§71](handoff/71-team-management.md) | Team management — the permission with no consumer, and the membership that must be a membership |
| [§72](handoff/72-the-run-lifecycle.md) | The run lifecycle — the status nothing ever wrote, and the freeze §67 deferred |
| [§73](handoff/73-the-plan-lifecycle.md) | The plan lifecycle — the ladder nothing could climb, and the gate decided by the edge |
| [§74](handoff/74-the-plan-editor.md) | The plan editor — the gates nothing could author, and the optimistic lock that could not fail |
| [§75](handoff/75-the-suite-lifecycle.md) | The suite lifecycle — the last status nothing ever wrote, and the rescind §73 left on cases |
| [§76](handoff/76-the-review-record.md) | The review record — the tables three ladders were written for, and the tier below approval |
| [§77](handoff/77-per-project-review-settings.md) | Per-project review settings — the row four behaviours were waiting on, and the demotion that must not cost the approve key |
| [§78](handoff/78-open-question-decisions-round-2.md) | Open-question decision round 2 — §8 emptied, five that were answered by the product and never struck, and the chart that now depends on the S3 adapter |
| [§79](handoff/79-review-comments.md) | Review comments — the one comment key where there are three review keys, and the resolution that named nobody |
| [§80](handoff/80-reviewer-assignment.md) | Reviewer assignment — the request nothing recorded, and the table that is not part of the record |
| [§81](handoff/81-the-reviewers-inbox.md) | The reviewer's inbox — the query no module could write, and the mock that invented the feature |
| [§82](handoff/82-the-gating-rules.md) | §6g's gating rules — the edge that is not a target, and the override nobody had to invent |
| [§83](handoff/83-the-cross-project-board.md) | The cross-project board — the parameter that was left off, and the mock that had been showing the wrong project since Phase 1 |
| [§84](handoff/84-the-team-board.md) | The team board — the bound that is the reader's, and the third selector on one operation |
| [§85](handoff/85-the-team-board-screen.md) | The team board's screen — the route that is not administration, and the 404 the server refuses to word |
| [§86](handoff/86-the-fact-log-gets-a-writer.md) | The fact log gets a writer — the module that had to exist first, and the dimensions that must not move |
| [§87](handoff/87-the-report-pipeline.md) | The report pipeline — the job record, the second store, and the gates that could not fail |
| [§88](handoff/88-the-html-renderer-and-the-report-header.md) | The HTML renderer and the report header — the block a renderer cannot strip, and the template engine that was not taken |
| [§89](handoff/89-the-reports-screen.md) | The Reports screen — the download an anchor cannot make, and the job list the server cannot give it |
| [§90](handoff/90-the-on-screen-report-view.md) | The on-screen report view — the model with no generated type, and the permission that reads a report |
| [§91](handoff/91-the-way-back-to-the-picker.md) | The way back to the picker — a screen every session starts on that no screen linked to, and the rail that collapses |
| [§92](handoff/92-saved-report-definitions.md) | Saved report definitions — the row that is one person's, and the delete a foreign key would have refused |
| [§93](handoff/93-the-plan-document.md) | The test plan document — the half a plan is mostly made of, and the link four years of schema had no writer for |
| [§94](handoff/94-writing-steps.md) | Writing steps — the corpus that needs no curating, and the index spelling that decides whether it works **(unverified — 11 browser, 4 mutation rows)** |
| [§95](handoff/95-conditions-get-a-writer.md) | Conditions get a writer — the library nothing could fill, and the usage count that was always zero **(unverified — 12 browser, 9 mutation rows; 3 name tests not yet written)** |
| [§96](handoff/96-tags-become-real.md) | Tags become real, and a suite resolves from them — the nullable key that drops rows in four places, and the union written twice on purpose **(unverified — 14 browser, 20 mutation rows)** |
| [§97](handoff/97-the-plan-detail-rework.md) | The test plan detail becomes one form — the tabs that cannot be headings, and the rename that would have moved a case out of its own section **(unverified — 32 browser rows across the M3 lane)** |
| [§98](handoff/98-suites-become-flat.md) | Suites become flat — the count and the grid that have answered different questions since §96, and the frozen path that outlived its column **(unverified — 12 browser, 6 mutation rows, plus a `V33` row on the Hetzner list)** |
| [§99](handoff/99-a-run-is-scoped-by-suites-cases-and-tags.md) | A run is scoped by suites, cases and tags — the suite arm that reads right and resolves to nothing, and the gate that needed no edit **(unverified — 11 browser, 7 mutation rows)** |
| [§100](handoff/100-the-design-comp-lands.md) | The design comp lands — the contract that declared a shape nobody served, and the mock that evaluated gates the server never evaluates **(its mock-mode pass is void since §107; 3 rows queued)** |
| [§101](handoff/101-the-m2-rollups.md) | The M2 rollups — the membership that freezes, the prune its sibling must not have, and the cold cache that read as a plan nobody had started **(unverified — 9 browser rows, all live-only, and 11 mutation rows)** |
| [§102](handoff/102-tags-become-reusable.md) | Tags become reusable — the endpoint that had one consumer, and the free text a suggestion list must not close **(unverified — 5 browser rows)** |
| [§103](handoff/103-the-references-show-their-cases.md) | The references show their cases — one read per reference because they union where `?tag=` ANDs, and the `case.suiteId` filter it must never be **(unverified — 11 browser rows)** |
| [§104](handoff/104-two-links-per-execution-row.md) | Two links per execution row — the key opens the case and the title opens the verdict, with two distinct accessible names because two links announced alike is a dead end **(unverified — 7 browser rows)** |
| [§105](handoff/105-what-failed.md) | What failed — the run's failed cases and their steps on one panel, with blocked kept out of the failure count and unmarked steps out of the passes **(unverified — 11 browser rows)** |
| [§106](handoff/106-the-board-learns-to-be-dragged.md) | The board learns to be dragged — the native HTML5 drag worked on no touch device and no keyboard; `@dnd-kit`, a pure drop resolver, and a rollback that says so out loud **(unverified — 11 browser, 2 mutation rows)** |
| [§107](handoff/107-the-mock-layer-is-deleted.md) | The mock layer is deleted, and the deploy stops eating evidence — ~9,300 lines of MSW removed, the fault switch rehomed in front of `fetch`, and the redeploy that destroyed every evidence file because `compose.prod.yaml` mounted nothing **(gates green; the box itself unverified)** |
| [§108](handoff/108-the-signing-key-was-in-the-repository.md) | The signing key was in the repository — `${VAR:?…}` is shell syntax Spring reads as a *default*, so production signed every session token with a string from this repo, past a correct comment, a real `@Size`, and 1000+ green tests **(fixed + `ConfigConventionTest`; THE OPERATOR MUST STILL ROTATE)** |
| [§109](handoff/109-the-browser-queue-gets-a-lane-to-run-in.md) | The browser queue gets a lane to run in — the 260 mock rows re-derived against a fixture charter the pass authors through the product, and three rows found already wrong **(361 of 362 ids preserved; executable, not executed)** |
| [§111](handoff/111-the-aggregate-execution-report.md) | The aggregate execution report — M2.5's R6: the first report spanning more than one run, the policy implemented a **second** time in SQL and legal only because a cross-check asserts the two against each other **(nine mutations run; 5 browser rows queued — built and unverified)** |
| [§112](handoff/112-the-inconsistency-callout.md) | The inconsistency callout, the split that is two columns, and the retries the default policy erases — M2.5's R7: three sections that must read the **un-collapsed** rows, because a policy exists to hide exactly what they are about, and one written over `resolvedCte` is empty in every project for ever **(built and unverified — 4 browser, 6 mutation rows)** |
| [§113](handoff/113-the-deploy-learns-a-second-target.md) | The deploy learns a second target — a `lab` job that runs **on** the homelab box via a self-hosted runner (no SSH key, no stored secret), the nightly Linux gate `ci.yml` gave up, and the `target` default that had to stay `production` or every existing release command would have silently shipped elsewhere **(built and unverified — nothing has run on shanks)** |
| [§114](handoff/114-version-divergence.md) | Version divergence — M2.5's R8 and the last reporting slice: the section reads the un-collapsed rows for R7's reason, is **wider** than the callout because §6e's harm is the blending and not the disagreement, and each row says whether that version reached the totals as a **computed** column rather than a sentence keyed on the policy's name **(built and unverified — 4 browser, 7 mutation rows, and NO RED run)** |
| [§115](handoff/115-the-coverage-matrix.md) | The coverage matrix — §6e's *requirement → cases → last result* with a **document as its spine**, unblocked by a writer §93 shipped two slices earlier and nobody noticed; a second foreign-module seam, a statement definition that refuses to call a specification's prose an untested requirement, and a frontend gate that stopped being a negation before a third report type made the negation wrong **(built and unverified — 7 browser, 9 mutation rows, five of the nine RUN and stated as a deviation)** |
| [§116](handoff/116-api-tokens.md) | API tokens — M3's first slice and `api_token`'s first writer since `V1`: the credential is presented **before any company context can exist**, so `V36` adds an instance-level `api_token_lookup` (`V8`'s own recorded answer, and `V6`'s decision a second time), the digest **moves** rather than being copied, and the ERD §0.1 exception list gains one entry with the cost stated. A token's permissions are its owner's **intersected** with its role's — never a second resolution path — so it can exceed neither, and `api_token.name`'s uniqueness, which ERD §0.1 names by name, turned out never to have been created **(built and unverified — 12 mutation rows, none run; no browser rows, and the token screen is the slice that will carry them. THE SLICE HAD A REAL RED RUN: the context opened inside an already-begun transaction never reaches the GUC, so every valid credential 401'd)** |
| [§117](handoff/117-result-ingestion.md) | CI result ingestion — M3's second slice, and the last two `V1` tables get writers: four parsers behind one Strategy whose values **are** `ingestion_job.format`'s `CHECK`, each with a different shape that reads a red suite as **green** (the worst being a Cucumber scenario failing mid-way, whose last step is skipped). §6c's ladder is **three batched passes, not a chain per test** — 3 queries, not 15,000 — and each rung is tested **alone**, because one collapsed to rung 1 would keep its count and pass. Nothing is auto-created for an unmatched test, which is a design rather than a gap only because `automation_key_mapping` gets its writer in the same slice — gated on **`case:edit`, which Automation Service does not hold**, so a leaked CI token can post results and cannot launder a red suite into a green one. `ReportXml` is the application's only `DocumentBuilderFactory`, and **a mutation corrected its own comment about which flag stops what**. §116's self-invoked-`@Transactional` defect was written and corrected before it ran **(built and unverified — 8 mutations RUN and stated as a deviation, 4 queued; no browser rows, and the two screens it names are recorded as unbuilt)** |
| [§118](handoff/118-the-token-screen.md) | The token screen — M3's third slice and §116's endpoints' first consumer: the reveal is a **second step of the dialog that refuses Esc and a backdrop click**, because every other create dialog in the area closes on success and a refactor toward that consistency destroys a credential with a success message on top. The list is **refused rather than narrowed**, so the read is gated by the caller's own verdict and the check is an *absent* request that nothing on screen can show. `lastUsedAt` is on the wire and deliberately **not a column**; expired and revoked are **not collapsed**, because revoking a lapsed credential is a real act and one dead state would take it away **(built and unverified — 11 browser rows across two lanes, none run; no mutation rows, §116's twelve cover the endpoints)** |
| [§119](handoff/119-the-ingestion-history-and-the-binder.md) | The ingestion history and the unmatched-test binder — M3's fourth slice: `POST /ingestions` has answered 201 with a `Location` naming a resource that **404s** since it shipped, and the two `GET`s that make the claim true serve **different shapes on purpose** — the list has no `unmatched` array at all, because the names live in a `JSONB` blob and an empty array beside a count of 12 reads as *cut*, which is the meaning `unmatchedCount` is documented to carry. Reading the payload back repaired the **replay**, which served `unmatchedCount: 12` beside `unmatched: []` to the one caller that most needs the names — a CI retry — and it is a **second implementation** of `unmatchedIn`, legal only because the two are asserted against each other. The fixture grew a **second project**, so one of the seven project-predicate-over-RLS files finally has a test behind it. The binder **closes on success**, deliberately the opposite of §118's dialog, and sends the test name **raw**: the only observer of a client that normalized is the request body, so that is what the check asserts **(built and unverified — 11 browser, 8 mutation rows, none run)** |
| [§120](handoff/120-automation-drift.md) | Automation drift — M3's fifth slice and the first with no screen: `case_health.stale_automation` has been a column since `V1` with no writer, and the reason was not neglect — its predicate is *"no **ingested** result in N days"* and until §117 nothing in the product could write `test_run.source = 'INGESTED'`, so the computation would have selected the empty set in every deployment and reported a confident zero. `V37` adds the mirror §6c names in the same sentence and the schema never had, and both read **one fact on two sides of one cutoff**, so a case can never raise both. Staleness requires a **previous** ingested result (*stopped* presupposes started, and §6c makes `automationKey` optional precisely so a case can be `AUTOMATED` before its automation exists — the literal predicate fires an ERROR on every case the day a team declares intent); the mirror is `<> 'AUTOMATED'` and not §6c's `MANUAL`, because `NOT_AUTOMATABLE` moves the **denominator** of §6c's own coverage formula. The frontend half is two exhaustive records and one decision: the pair **must not look alike**, because only one of them can be hiding a red test. **A mutation attempted the wrong way taught the durable thing** — deleting the `IS NOT NULL` clause reddens all 19 tests by *crashing* the insert, which measures nothing; mutating to the rival implementation reddens exactly one. Also: `CaseHealthAttributesTest` now exists, having been named in a javadoc since §47 **(built and unverified — 7 browser, 10 mutation rows, 3 of the mutations RUN and stated as a deviation)** |
