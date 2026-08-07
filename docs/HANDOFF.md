# TCMS — Session Handoff

**Date:** 2026-08-07 · **Branch:** `master` · **Latest slice:** [§126 — attachment owners, and the
table that stopped being one-third claimed](handoff/126-attachment-owners.md) (**four phases
shipped** — decision round 3's step 6, #49 + #50)

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
SVG pre-render both need a headless renderer; ~~evidence-bundle export needs the `shared`
module~~ → **half-unblocked by §125**: `EvidenceStore` and `attachment` are `storage`'s now and
`storage` is a foundation module, so the answer was never `shared`; what remains is that `evidence`
is still an `execution` table, reachable from `reporting` only through the seam; scheduled reports
need a notification channel). The organization-model restatement (§97, §98, §99) is complete with nothing
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
in M0. **§121 is M3's sixth slice and closes the milestone's last named scope item** — "quarantine
flow", which had no schema at all and whose blocker open question #24 stated in as many words
(*"no data until M3 ingestion exists"*) and §117 removed. It is the **fourth** time a plan's own
"blocked" note has outlived its blocker. `V38`'s `case_flake` is the first table whose row is half
derived and half authored. ~~**It is HALF SHIPPED: phases 1–2 … phases 3 and 4 are NOT BUILT.**
`CaseFlakeRepository.findQuarantined` has no caller~~ → **phase 3 shipped and only phase 4 (any
frontend at all) is left.** The board's fourth generator is real, `BoardFacts.quarantinedCases` is
where it lives, and **`findQuarantined` is DELETED**: the generator its javadoc named could never
have called it, because `work` may not import `repository` (ArchUnit, and the board has read
`case_health` through native SQL since §53). A seam written ahead of its consumer guessed a module
boundary wrong — the writerless shape this slice was started to fix, one level up. `QUARANTINE` is a
**seventh `kind`** on the wire, the first the enum was not already ahead of, and it is deliberately
not `CASE_MAINTENANCE`: the SPA splits *that* kind's description on commas and labels it code by
code, so a sentence somebody typed would render as its own fragments with nothing failing.
**Phase 4 closed the slice**, and found that **`CaseFlake` had no read path at all** — served only as
the `PUT` response for three phases, so the product could set a state it could not show. It is
`TestCase.flake` now, nullable and detail-only, and that null is a **third** state the queue had not
anticipated: `flake === null` (nobody has looked) against `flakeScore === null` (not enough evidence
to say) against `flakeScore === 0` (evidently stable), where the last two are opposite claims and
`score ?? 0` merges them while type-checking on both sides of the wire. `quarantinedBy` was the
**only bare-uuid actor field on the API** against ~25 `UserRef`s, under a javadoc asserting it
followed the convention it broke (§100's rule, third sighting). The red run was
**`Map.of().get(null)`** — §38's trap, whose comment this code copied the words of and not the
behaviour — 500ing every case detail on the path where *nobody* was quarantined, and reddening three
tests about conditions, automation and steps.

**§125 is decision round 3's step 5 and the round's first purely architectural slice** — no
migration, no contract change, no frontend change, nothing a user can see. `Attachment`, both blob
ports and both filesystem adapters left `execution` and `reporting` for a new `com.tcms.storage`,
and the cost the plan's own row named came due: **the foundation is four modules now**, because
`execution` and `reporting` both need bytes back *synchronously inside a request* and a download
cannot be an event. `storage` is the first foundation module that is not a domain concept, so
`ArchitectureRules` records the three properties that make it defensible and **a new ArchUnit rule
checks the load-bearing one from the code** — Modulith enforces a declaration and has nothing to say
about that declaration being *widened*, which is one legal word. §3's own list had a home for this
(`shared — storage, audit, attachments, search`) and that is the option that was **refused**: a
module every other module may depend on must not be a grab bag. Phase 2 collapsed §87's documented
copy now that its stated reason had expired — `ReportBlob` deleted, one `FilesystemBlobStore` body,
**two ports kept as two types on purpose** (one interface with two beans puts "which store is this"
in a qualifier string, and the wrong one writes evidence under the reports root without failing).
**The finding is that `FilesystemReportStore`'s traversal guard had never been asserted** — the
evidence copy's test was the only test either adapter had, and the two files were in different
packages so nothing put them side by side.

**Gates, as of §126 phase 4** — both run locally, and only locally:

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **BUILD SUCCESSFUL — 2088 tests across 445 result classes, 0 failures** (§126 phase 3; phase 2's baseline was 2072/439, §125's 2028/428). **Phase 4 changed no backend file and did not re-run it.** |
| `cd frontend && npm run build` | clean, 0 errors (§126 phase 4, re-run independently) |
| `npm run lint` | 0 warnings |
| `npm run test:run` | **1088/1088 across 83 files** (measured, solo run, re-run after the work landed). **SEVENTH DRIFT, and this one was measured on both sides:** §126 phase 3's commit message claims `1045/1045`, but a clean solo run at `e95ef1a` measures **1055/79** — so phase 4's real delta is **+33/+4**, not the +43 the carried figure implies. The number has now been wrong in the orientation cache header, in a commit message, and here, every time by being **copied rather than run**. **Read it off a solo run or do not state it.** |

**CI runs no backend tests** — `.github/workflows/ci.yml` has run `./gradlew build -x test` since
§59, so `-Werror` and the Modulith wiring are checked and nothing else is. `./gradlew build` on your
own machine is the only thing that runs the integration suite. See §58 for what a macOS-only gate
structurally cannot observe.

> **A nightly Linux lane is written but NOT LIVE (§113).** `.github/workflows/tests.yml` runs the
> full `./gradlew build` on `[self-hosted, tcms-lab]` — the homelab box — which is a *differently
> shaped* gate, not just a free one. **It is inert until that runner is registered**
> (`deploy/shanks/install-runner.sh`), so the paragraph above still holds in full. Do not read the
> file's existence as coverage.

**Schema.** Flyway head is **`V39__attachment_owner_types.sql`** (§126 phase 1, the round's only
migration in its first six steps); **`V40` is the next free number**. `V39` adds **no table** — it
narrows `attachment.owner_type`'s `CHECK` to the five values that now have writers, dropping `STEP`
(#50), so the policed-table count is unchanged at **62**. Before it,
`V38` added a **new company-owned table**, unlike `V37`'s column, so the count moved to
**62 tables under `FORCE ROW LEVEL SECURITY`** — `SchemaConventionTest` pins it, which is what makes
a new policed table a decision rather than a side effect — `V36`'s `api_token_lookup` is the **twelfth**
instance-level table and the ERD §0.1 exception list's newest entry, decided rather than assumed. Flyway owns all DDL
(`hibernate.ddl-auto: validate`).

**There is no mock API** (§107). `src/mocks/` — ~9,300 lines of MSW handlers and fixtures — and
`VITE_API_MODE` were deleted, so `npm run dev` proxies `/api` to `localhost:8080` and **requires
`docker compose up` at the repo root plus a provisioned company**. The fault switch (`tcmsFaults`)
now sits in front of `fetch` and faults the real backend.

## Open actions — do these

> **The order is fixed and it lives in the plan, not here** — `docs/plans/2026-07-30-tcms-high-level-plan.md`
> §8, **decision round 3 (2026-08-07, sixteen questions, #45–#60)**. Nine steps: **#46 register the
> shanks runner → #56 rotate the key → ~~#60 write §110~~ → ~~#47/#53~~ → ~~#51 extract `storage`~~ →
> #49/#50 attachment owners → #48 doc blocks → #57/#59 → #52 the outbox.**
>
> **Steps 3, 4, 5 and 6 are DONE** ([§110](handoff/110-the-live-pass-runs.md),
> [§124](handoff/124-the-decided-and-the-unwired.md),
> [§125](handoff/125-storage-becomes-a-module.md),
> [§126](handoff/126-attachment-owners.md) — four phases, `attachment` fully claimed, `V39` the
> round's only migration in its first six steps). **Steps 1 and 2 are next and both need a
> terminal a person is sitting at** — `sudo` on shanks cannot be answered from a tool call, and the
> rotation touches a live instance's session keys. **The next thing an agent can do alone is step 7
> (#48, doc blocks).** Items 1–2 below are steps 2 and 1; they are kept here because they were open
> before the round. **Steps 1 and 2 have a written procedure —
> [`docs/plans/2026-08-07-linux-gate-and-key-rotation.md`](plans/2026-08-07-linux-gate-and-key-rotation.md).**
>
> **The chosen block is "close the small gaps", NOT the verification drain.** Every item in the round
> adds rows to both queues. §65 stands, and so does what it costs: everything below §65 remains
> *built and unverified*, and the round makes that set larger rather than smaller.

1. **Rotate the production JWT signing key (§108).** `${TCMS_JWT_SECRET:?…}` is shell syntax Spring
   does not implement, so it was read as a *default* and the production instance signed every session
   token with a 59-character string that is in this repository. The code is fixed and
   `ConfigConventionTest` reads the shipped text, but **the key itself is still public** — the
   operator must rotate it, and `TCMS_JWT_SECRET` must exist in `/etc/tcms/tcms.env` before the next
   deploy or the app will refuse to start.
2. **Run the verification queues.** See below.
3. ~~**Write §110's section file.**~~ → **DONE 2026-08-07** — [§110](handoff/110-the-live-pass-runs.md),
   round 3's step 3. Six product findings in ~60 driven rows, **none of them reachable by any gate**;
   six of the fourteen findings were about the observer rather than the product.
4. **Register the shanks self-hosted runner (§113, decision #46) — this is step 1 of the order.**
   `deploy/shanks/install-runner.sh`. `.github/workflows/tests.yml` has been written and inert since
   §113, so the nightly `./gradlew build` on Linux does not run and **the 2014-test suite is observed
   by exactly one macOS machine**. Doing this first is deliberate: every later step in the round
   then lands on a differently-shaped gate rather than on the one §58 proved structurally blind.

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

~~**§110 has no row because it has no file yet**~~ → **written 2026-08-07 as decision round 3's #60**
(§123 step 3), from the commits and the evidence file, five days after the pass. It is thinner than
it would have been on the first day — which is the finding, not an apology.

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
| [§110](handoff/110-the-live-pass-runs.md) | The live pass runs — §109's thirteen lanes driven for the first time, in two sittings: ~60 rows confirmed against their exact wording and **fourteen findings, of which only six are the product**. Six product defects and gaps, **none reachable by any gate**: an invitation link opened while signed in is silently swallowed (and the flow it breaks is the *designed* multi-company one); the `report:view`/`report:export` split **cannot be expressed by any deployment** because `/roles` is GET-only and every built-in role holds both — §87 fixed its own unsatisfiable assertions with fixture-only roles, so the gates are asserted against personas the product cannot make; **there is no password reset anywhere**, which stopped the pass dead when five redeemed personas' passwords existed only in somebody's head; two mutations that update a status chip and leave a sibling panel on the same page reading the old world (teams-and-roles, then plan review — a class, not a bug); and a suite's conditions can be **read but never written**, the writerless shape one level up from a table, with the server silently dropping the field a guessing client would send. Two findings are the **queue** being wrong and the product right — an expectation derived from one instance's history rather than from the code, one of them on a row §109 had rewritten the day before. Four are the harness, and they are why sitting 1's `F` lane produced nothing: `AbortSignal.timeout` is throttled so hard in a background tab that the timeout budget never fires, **which reads exactly like the endless spinner the row exists to catch**, and `expire()` armed before a route mount is eaten by StrictMode's double-invoke, **which reads exactly like a swallowed 401** — a false positive and a false negative, both for serious defects **(part-driven; ~60 of ~360 rows, and the section written five days later as §123 step 3)** |
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
| [§121](handoff/121-flake-detection-and-quarantine.md) | Flake detection and quarantine — M3's last named scope item, whose blocker open question #24 recorded and §117 removed (the **fourth** time a plan's own "blocked" note has outlived its blocker). `V38`'s `case_flake` is the first table in the product whose row is half **derived** and half **authored**, kept apart by the `ON CONFLICT` rule now binding two writers of one *row* rather than two modules — an `EXCLUDED.*` set-list would release every quarantined test on the next tick, silently and in the reassuring direction. **THE FINDING CHANGED THE CODE**: this is the **third** implementation of §6l's one sentence — `CaseHistoryService.flakiness` has computed it since §49 under the same name — and the first draft used `counts_as_pass` where that one uses `status_kind`. Those are *independent* columns: they agree on all six seeded defaults and diverge the moment a project defines "Passed with known issues" as `RETEST`-kind counting toward the pass rate, so a case page and a worklist would quote different numbers with nothing to say which is right. **THE GATE IS THE SLICE**: `case:edit`, not the `result:submit` an endpoint about CI results looks like it should use — Automation Service does not hold it, so a leaked CI token can post results and **cannot quarantine the tests those results fail**, which with §117 is what makes "a token cannot launder a red suite" a design rather than a hope. Also: a blocked run between two passes is not two alternations; `transitions` and not `executions` is the denominator; the score is **null** below the evidence floor, which is not `0` **Phase 3 (2026-08-07) is the board's fourth generator**, and it deleted the seam phase 1 wrote for it: `CaseFlakeRepository.findQuarantined` named §6l's generator as its caller, and that caller lives in `work`, which **may not import `repository`** — the board has read foreign rows through native SQL in `BoardFacts` since §53. A seam built ahead of its consumer guessed a module boundary wrong. `QUARANTINE` is a **seventh `kind`**, the first §53's declared-ahead enum did not already cover, and not `CASE_MAINTENANCE` because the SPA splits that kind's description on commas and labels it code by code — a typed sentence would render as fragments with nothing failing. The id prefix is `quarantine:` and not `case:` so a case that is both flagged and quarantined is two cards rather than two cards with one id; the assignee is the **owner** and the decider is in the sentence. **The red run was a fixture-id collision that failed in §84's team-board test, eighty lines and one nested class away — sixth instance of §19's family.** **Phase 4 (2026-08-07) is the screen, and the read path that did not exist**: `CaseFlake` was served **only as the `PUT` response** for three phases, so the product could set a state it could not show — a write path's response looks like a read, which is why three phases passed. It is `TestCase.flake` now (nullable, **detail-only** for `classificationValueIds`' reason), and its null turned out to be a **third** state nobody had anticipated: the queue had recorded `flakeScore: null` against `0` since phase 1, and *no row at all* only appears once something has to render the case most cases are. Three claims, two of them opposite, one `??` between them. **`quarantinedBy` was the ONLY bare-uuid actor field on this API** — ~25 others are `UserRef` — under a javadoc asserting it followed the convention it broke, and it is resolved **inside the service's transaction** because `CaseOwners` reads through the tenancy filter and a controller doing it would fail *closed*, answering null on every successful quarantine with nothing failing. **THE RED RUN WAS `Map.of().get(null)`**, which throws by contract: the batch lookup short-circuited to an immutable empty map and keyed on a **nullable** column, so every case detail 500'd whenever *nobody* in the batch was quarantined — the common path — while the batch that did contain one worked perfectly. §38's trap, and `CaseOwners` carries a comment about it that this code copied the words of and not the behaviour; three of the five red tests were about conditions, automation and steps. Also: `findForCases` was the **second** consumerless seam in `CaseFlakeRepository`, missed by phase 3 because phase 3 was hunting the one whose javadoc named a caller; the dialog **closes on success**, deliberately not generalising §118's stay-open rule; and the pluralizer shipped **"4 switchs"** until its own test read it **(built and unverified — 26 mutation rows, 3 RUN, 6 browser rows `M1-29`…`M1-34` in the board lane and 8 more `M2-54`…`M2-61` in the repository lane, of which only `M2-54` is drivable before `L4`)** |
| [§122](handoff/122-the-operator-tier-gets-a-writer.md) | The operator tier gets a writer — Instance Admin was designed, enforced, documented and **unreachable**: `V3` seeds the role, `PermissionEvaluator` resolves the SYSTEM term as its own independent term, `instance_role_assignment` has existed since `V1`, and **no path in `src/main` had ever written a row into it** — only three test classes had ever called `InstanceRoleAssignment.forUser`, so a fresh instance shipped an operator role nobody could hold and a hand-written `INSERT` was the only route to it. `RoleGrantService` had promised this command in a header comment since it shipped. **`ALREADY_HELD` exits 0 where `issue-invitation`'s `ALREADY_ACTIVE` does not** — a grant is a *state* and an invitation is an *artifact*, so re-running is the supported way to make sure, as with `provision-company`. **`INVITED` is granted and `DEACTIVATED` is refused**, the two states that look alike and must not be: `provision-company` leaves its first admin `INVITED`, so refusing that one makes the command unable to create the very first operator, while a deactivated account's grant confers nothing today and everything the moment somebody reactivates them. No `CompanyContext` is opened — an instance-wide power scoped to one company is a tier the model does not have. The load-bearing line is the **verb registration**, read before the context exists to pick `WebApplicationType.NONE`; omit it and the command succeeds, prints, and then hangs for ever **(built and unverified — 10 mutation rows, NONE run; no browser rows and no screen, deliberately. The grant itself is not done — it needs a redeployed image)** |
| [§123](handoff/123-decision-round-3.md) | Decision round 3 — sixteen questions (#45–#60), thirteen decided and three deferred **with their blockers named**, derived from the code rather than from the plan. §78 closed with *"nothing in §8 is open"* and the sentence explaining how it would decay: a decision taken in a slice and never written back. **That is precisely what happened** — none of the sixteen is a new uncertainty; every one was already visible as a writerless table, an unconsumed enum value, an unenforced permission key, or a decision applied by half. **The list did not stay closed, it stopped being read**, which is a different failure with a different fix (re-derive on a schedule — the six computed checks are what make round 4 cheap). Two entries are the failure mode in miniature: #17's `BEST` removal was resolved 2026-08-01 and **never applied**, and the draft buffer was decided beside the version snapshot that shipped in §29 and **never built**, so the editor has no autosave at all. The order is **fixed and sequenced on what each step lets the next one observe** — the shanks runner first because seven of the nine steps write backend code and would otherwise land on the gate §58 proved blind; the outbox last because it is the widest blast radius and by then a reddening is attributable. **#51 must not be folded into #49**, because `Attachment`'s javadoc names both the move and its precondition and doing them in one diff makes a module extraction indistinguishable from a feature. Also folded in: the instance operator console into M6, which reverses §6s's "no company management UI" and roughly doubles the milestone — the exclusion was load-bearing on "a week of surface, not a phase", and the cost is struck through rather than dropped **(documents only. The honesty note is the entry that will be forgotten: closing small gaps does not retire the ~790 unrun verification rows, it GROWS them)** |
| [§124](handoff/124-the-decided-and-the-unwired.md) | The decided-and-unapplied, and the two keys nothing asked for — decision round 3's step 4, in three phases with **no new idea in any of them**: #17's `BEST` removal (resolved 2026-08-01, applied six days later), and #53's two permission keys that had been in `V3`'s catalogue since M0 with no consumer. **`BEST` went because a label is not neutral** — it was offered on the argument that *"it passed on the re-run" is better said in the open under a label than smuggled into a default*, which is right about people and wrong about products: a dropdown value carries the product's endorsement, and a policy named *best* reads as the generous reading rather than the dishonest one. It cost nothing because `WORST` was already there **and** because the product has since grown the honest way to say it (§121's flake score, §112's variability). A stale `BEST` in a saved URL is a **400 naming the four that remain**, never a silent fallback. The removal is pinned from outside — the test that asserted `BEST` emptied the blocker list now asserts **no remaining policy can** — and the duplicate-enum check that existed for additions turned out to matter more for a deletion, since a copy that still *accepts* a retired policy answers a number the plan page cannot produce. **`run:execute` is the only key in the catalogue that separates a person from a credential**: Automation Service holds `result:submit` (ingestion is its purpose) and is the one role `V3` withholds this from, so without it a leaked CI token could hand-record verdicts through the endpoint a tester uses, with an `If-Match`, with evidence, indistinguishable in the audit trail. **THE ORDER IS THE DESIGN and the first draft had it wrong**: asked in front of the verdict gate, a Viewer — holding neither key — is told to use an ingestion endpoint they may not use either, and advice that is confidently wrong is worse than a flat refusal; asked inside it, "you have another door" is true by construction. **`artifact:delete` had no endpoint at all**, and the gate is the whole point — a Test Engineer may register a plan's documents and reusing the create's `artifact:edit` would let them delete a lead's sign-off record, in the one module where the artefact being destroyed *is* the audit trail. Two 409s that **cannot fire in any deployment today**: `write_once` is honoured ahead of its writer for `Company.suspend()`'s reason (enforcement arriving after the control it protects is a guarantee that is believed and does nothing), and a fixture flipping the column by SQL is what makes an early guard assertable rather than aspirational — the opposite call from §121 phase 3's deleted seam, because a refusal guesses at nothing while a seam guesses at a consumer. And **`Artifact`'s javadoc was wrong** — *"nothing targets an artifact … removing a row breaks no inbound edge"* — about a self-referencing foreign key, surviving because it described the schema from memory and, decisively, because nothing could delete a row and therefore nothing could disprove it (§100's rule, fourth sighting) **(built and unverified — 16 mutation rows, 3 RUN as a stated deviation, 4 browser rows; `artifact:delete` ships an ENDPOINT WITH NO SCREEN, recorded as unbuilt: a key with no consumer became an endpoint with no consumer)** |
| [§125](handoff/125-storage-becomes-a-module.md) | `storage` becomes a module, and the foundation becomes four — decision round 3's step 5 (#51), the round's first purely architectural slice: no migration, no contract change, nothing a user can see. `Attachment`, `AttachmentRepository`, both blob ports, `StoredBlob` and both filesystem adapters leave `execution` and `reporting`. **The condition was written down long before it was met and it held**: `Attachment`'s javadoc refused the move until there was "a second caller to design against" — which #49 creates twice over — and named what would make it cheap when it came, `forExecution`/`forStepResult` meaning **no call site ever spelled an owner type**. It was a package line and two modifiers. That is the **opposite** outcome from §121 phase 3's deleted seam, and the difference is the lesson: that seam guessed at a *caller*, this javadoc committed to a *shape* and left the caller open. **THE COST IS A FOURTH FOUNDATION MODULE** — `execution` and `reporting` both need bytes back *synchronously inside a request* and a download cannot be an event, so `FOUNDATION_MODULES` names four and `ModuleStructureTest` names eleven; §76's *"a fourth foundation module is a decision, not an import"* is still the rule and this is the decision. `storage` is the first entry that is not a domain concept, so the constant now carries the three properties that make it defensible (declares `tenancy` **alone** · holds no domain concept · answers a question the database cannot) and **a new ArchUnit rule checks the first from the code** — Modulith enforces a declaration and cannot object to that declaration being *widened*, and adding `identity` to it is one legal word past every other test in the file. **§3's own list had a home for this** — `shared — storage, audit, attachments, search` — and that is the option refused: a module everything may depend on **must not be a grab bag**, or audit and search become reachable from everywhere too. Phase 2 collapsed §87's documented copy now that its stated reason (`reporting` may not import `execution`) had expired: **`ReportBlob` deleted** — bytes and a length twice over, with no wrong one to be handed — one `FilesystemBlobStore` body so #55's deferred S3 adapter is **one** piece of work again, and **the two ports kept as two types on purpose**, because one `BlobStore` with two beans puts "which store is this" in a qualifier string and the wrong one writes evidence under the reports root with nothing failing. The `kind` label deliberately did not collapse: the two roots differ in retention and loss consequences, so a failure has to name which one is gone. **THE FINDING: `FilesystemReportStore`'s traversal guard had never been asserted** — the evidence adapter's test was the only test either had, and it is not a review failure but a packaging one: the two files were in different packages, so nothing in the repo ever put them side by side, and the move is what surfaced it. **NO RED RUN**, stated because a move of this shape either compiles or does not **(built and unverified — 13 mutation rows `ST-1`…`ST-13`, none run; no browser rows, deliberately: a package rename has nothing a browser pass can see)** |
| [§126](handoff/126-attachment-owners.md) | Attachment owners — decision round 3's step 6 (#49 + #50), four phases: `V1` declared `attachment` polymorphic over six `owner_type` values and **two had ever been written**; `CASE`, `DOC_PAGE` and `ARTIFACT` get writers, `STEP` leaves the `CHECK` in `V39`, and the table goes from one-third claimed to **fully claimed**. **THE PLAN DOCUMENT WAS WRONG ABOUT ONE OF ITS OWN THREE OWNERS AND THE SCHEMA HAD SAID SO** — it generalised "a family per owner" from two examples that agreed, which would have given an artifact a *list* of attachments; `artifact.attachment_id` is a single nullable FK among **four payload columns, one per kind**, because a case *has* files while a `FILE` artifact **is** one, and §6n replaces it by superseding. So the bytes travel **with** the registration in one multipart request and there is no attach-afterwards, detach, re-upload or `PUT`: a `FILE` row with no file is a register entry claiming a document it cannot produce. Two gates are the ones most likely to be "fixed" into failing — **`case:edit` with NO `OWN` arm** (an authorship arm would make removing the screenshot stricter than removing the step that references it, incoherent in the direction that reads as security) and **`doc:edit`, not `doc:publish`** (attaching a signed-off PDF *sounds* like publication; an attachment is content, and the publish key leaves a Test Engineer able to author a specification they cannot illustrate). **THE FINDING IS IN PHASE 4 AND IT IS OLDER THAN THE SLICE: this app cannot render a stored file as a link.** The session is a bearer token and `SecurityConfiguration` is `STATELESS`, so `<a href="/api/v1/…">` sends **no credential** and answers 401 *while rendering as a working link* — and `<img src>` is the same request, as a broken image. `api/client.ts` has named the anchor half since §89; **the `<img>` half is named nowhere and `EvidencePanel.tsx:169` renders one**, so §67's surface has the un-fixed version and browser row **`M4-7` is expected to FAIL against a real deployment** — written in mock mode against a handler that needed no credential, never driven live, which is §107's argument arriving once more. Phase 4 ships **download-only** through `FileDownloadLink` (extracted at its **third** call site) and records the inline preview as unbuilt with its reason rather than shipping a broken image on every row. Also: **the `artifact` multipart part must be a JSON `Blob`, not a string** — `@RequestPart` picks its converter from the part's own `Content-Type` and a string part arrives `text/plain`, failing as a `415` about media types nowhere near the field it is about; `artifact.attachment_id` and `owner_type='ARTIFACT'` are **both written and are not rivals**, so the reader who assumes one relationship was declared twice deletes the wrong half; `Artifact.register` takes the id as a **parameter** because setting the column on a *managed* instance compiles, runs and persists nothing; and `Artifact`'s "four deliberately unmapped columns" is three now — **the fifth blocker note in this repo to outlive its blocker**. **SEVENTH DRIFT OF THE FRONTEND TEST COUNT, measured on both sides**: phase 3's commit claims 1045, a clean run at `e95ef1a` measures **1055/79**, so phase 4's real delta is **+33/+4** **(built and unverified — 11 browser rows `M2-62`…`M2-66`, `M3-65`…`M3-69`, `F-8`…`F-10`, which author their own fixtures including a zero-byte and a 25MB file; `M3-38` CORRECTED IN PLACE because it asserted the absence of the control this slice adds; three absences stated rather than left to look like oversights)** |
