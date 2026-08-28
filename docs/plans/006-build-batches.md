# 006 — Build batches for 003 + 004 + 005, ordered for one operator and few tokens

**Status:** proposed · **Date:** 2026-08-28
**Supersedes** the per-plan sequencing in [`003`](003-case-authoring-redesign.md),
[`004`](004-issue-tracker-integration.md) and [`005`](005-renders-backend-support.md) §8 for the
question *in what order does an agent build this*. Those sections were written for lanes landing
independently with a gate between each; this is written for **one operator, sequential batches, and
the gates run once at the end.** The plans stay the authority on *what* is built.

## The three rules that make it cheap

1. **Batch by agent and by files touched, not by plan.** An agent that has `CaseEditorPage.tsx` open
   does all three plans' work in it. Every plan-boundary inside one file is a second read of that
   file and a second explanation of its rules.
2. **One contract wave.** `openapi.yaml` changes from all three plans land in one edit and one
   `gen:api` in both places. Every downstream agent types against the finished contract; nobody
   regenerates twice. (005 §8.1's "as few waves as possible", taken to one.)
3. **Agents write tests; nobody runs them until B7.** Every brief says so. Verification is one
   `shanks` pass at the end, then a fix batch. Builds (`tsc -b`, `gradlew compileJava`) are not
   tests and the one in B1 is worth its cost because everything after it types against it.

Migration numbers are fixed by the batch order and claimed nowhere else: **`V71` · `V72` · `V73` ·
`V74`**, all written in B2/B3, in that order, before any frontend batch. Disk high-water is `V69`;
`V70` stays reserved for `002-`.

## The batches

| # | Agent | Batch | What goes in it (plan → section) |
|---|---|---|---|
| **B0** | *you*, `psql` | **The purge** | [`runbooks/2026-08-28-purge-project-content.md`](../runbooks/2026-08-28-purge-project-content.md). Lab, then prod. Not an agent task. Independent of every batch below; do it first so the empty-corpus state is what B4 is built into. |
| **B1** | `be-java` (contract only) | **The contract wave** | Every `openapi.yaml` change from all three plans, once: 005 S1 (`Problem.permission/scope/line`; `RoleAssignment.grantedBy`, `TestRun.createdBy`, `lastResult.configurationName`, `Suite` arm counts, `ReportJob.size`; `healthComputedAt` comment; `/me/social-identities` wording; members read-gate description) · 005 S5–S9b shapes (`TestPlanSummary` exit criteria, `OrgChart.gaps`, review blast-radius counts, `DashboardBreakdown` by-last-run, `POST /me/password`, sign-out-others, `ProjectSettings.reviewEnabledDoc`, `ReviewSubjectType` `DOC`) · 003 Phase 2 `POST …/cases/render-gherkin` · 004 *Contract* (all eleven paths, their schemas, `DefectLinkInput.bindingId`). `required` on every schema and every inline object. Then `frontend npm run gen:api`, `packages/client npm run gen:api`, `@tcms/client` wrappers. **Run `packages npm run build`** — the one mid-way build that earns its keep. |
| **B2** | `be-java` | **Foundation + identity + project backend** | `api.Problem` record + both envelope writers + `ErrorBodyPolicyTest` expectations (005 C1a) · members read → `project:read` (005 Q2) · lockout on `tcms.rate-limit` (005 S10) · `grantedBy` on the wire · org-chart gaps (005 S6) · `POST /me/password` + sign-out-others via `pv` (005 S9a) · **`V71`**: `project_settings.review_enabled_doc` **and the `doc:review` seed rows** (permission + grants — a 56th key needs an `INSERT`, which 005 S9b's "no migration" did not count) · `ReviewSubjectType.DOC` + `documentation` `ReviewSubjectDirectory` impl · roles spec §A3 + `permission-matrix-summary.md` + `README.md` + `lib/permissions.ts` (005 S9b) · the N7 SPI declared in `project` with the review-owned counts (005 §4, S7). |
| **B3** | `be-java` | **Repository + execution + reporting backend** | `render-gherkin` endpoint (003 Phase 2 *Backend*, plus `Problem.line` surfaced from `GherkinSyntaxException` on the four parse refusals) · `TestRun.createdBy`, `lastResult.configurationName`, `Suite` arm counts, `ReportJob.size` (005 S1 wire) · plan list `passRate` / `coverage` / exit criteria batched writer (005 S5) · dashboard by-last-run breakdown (005 S8) · N7 SPI impls in `documentation` and `execution` (005 S9b half) · the `FAILING_REPEATEDLY` predicate **read**, so B5's copy is honest (005 §5.4). |
| **B4** | `be-java` | **Integration module — 004 backend, whole** | 004 *Module placement* as one package: `OutboundUrlGuard` + table-driven test, `TrackerPort` + `FakeTrackerAdapter` (`tracker-fake` profile, per-user entitlement map), `TrackerClient`, `JiraAdapter`, `AzureDevOpsAdapter`, **`V72`** (`tracker_binding`, `tracker_identity`, `tracker_issue_creation`), **`V73`** (`defect_link.provider` comment), **`V74`** (`EXTERNAL_ISSUE`, mirror, refresh, `SpecLinkage` predicate), bindings / identities / connected-members / issues / issue-links controllers and services, `TrackerCache` keyed per user, `TrackerIssueCreatedEvent` → outbox, `WebhookPoster` adopts the guard in warn mode. `MockRestServiceServer` for transport tests. |
| **B5** | `fe-react` | **The case editor — 003 Phase 1 + 2 as one** | `CaseEditorPage.tsx` once: the three starts, `Given/When/Then` ghost text, `useStepCorpus` + corpus panel with its empty state, `ClassificationPicker` empty-vocabulary branch, suite carried from `CaseListPage`, `CaseDocument.tsx` + `gherkinText.ts` (one mirror for colour and caret), the rail as controls with Postconditions, `Background:` as own preconditions with the inherited count in the rail, C4 refusals anchored on `Problem.line`, draft buffer keeps raw text, `gherkin_source` refusal, deletion of the step-row machinery (`@dnd-kit` stays — the board uses it), `featureFiles.ts:16` amended. Tests per 003's two *Tests* lists, written not run. |
| **B6** | `fe-react` | **Every other screen — 005 views + 004 UI** | 005: Refused state names `permission` + "Ask a Company Admin" (C1), credential rail entries un-listed (C2), Project access read-only, org chart Gaps panel, Review blast radius, dashboard breakdown, plan list stats + exit criteria, Account security page (set password, sign out others), doc review toggle, the sixth Flakiness state, "Not run recently", run-schedule subtitle off `updatedAt/updatedBy` — i.e. 005 §6's fourteen design changes **applied in code, not redrawn in the HTML renders** (a redraw is tokens with no product value; the renders stay as the historical input). 004: `tcmsFaults.tracker(...)`, `TrackerConnectionsPage`, bindings + connected-members on project settings, `IssueChip` in the shared layer with `lib/labels.ts` categories, the Links panel, `DefectPanel` file-a-defect (frozen on closed run, hidden-with-reason when unconnected), no label ever from the href. |
| **B7** | *you* + one fix agent | **Verify once, fix once** | `~/bin/tcms-remote sync` · `packages npm run build` · `backend ./gradlew build` (`ulimit -n 65535` first) · `frontend npm run build && npm run lint && npm run test:run`. Route each failure to the agent that owns the file (B2–B6), one fix batch, re-run. Then `gh workflow run tests.yml` and read it. |

Seven agent runs. B2 → B3 → B4 are ordered only by migration number; B5 and B6 need B1 and nothing
else, so if a backend batch stalls the frontend batches can proceed against the generated types.

## Commits

One per batch, staged by path, on the current branch, message ending *"built and unverified"* until
B7 — the repo has lost committed work silently before, and an unverified commit is cheaper than an
uncommitted batch. B7's fix commit is the one that says the gates passed.

## What each brief must say — the token rules

- **Point at headings, not files.** *"003 → Phase 2 → Frontend, and C3/C4"*, not the whole plan. The
  agent reads the section; it does not read the three documents.
- **"Write the tests named in the section. Do not run any test or gate."** Every brief, verbatim.
- **Name the files it owns and the ones it must not touch** — `openapi.yaml` and both generated
  copies are B1's and nobody else's after B1; `keys.ts` gains keys only in B5/B6; no second
  `QueryClient`; no Flyway number outside the four above.
- **Ask for a ten-line hand-back**: what changed, what was skipped and why, which test it wrote
  for which rule. That is the only thing you read.
- **One agent at a time.** Never a second, even with disjoint files — the standing rule.

## Corrections to the plans this batching found

- **005 S9b said "no migration — `V71` already landed in S3."** A new catalogue key is a seed row
  (`V3` seeded the 55; nothing adds one without an `INSERT`), so `V71` carries the column **and**
  the `doc:review` permission + grants. Same number, one file, no new claim.
- 005's S3 "inert column first" split exists to release the numbering block early across lanes.
  With one operator and the numbers fixed by batch order, the split buys nothing; `V71` lands
  complete in B2.
- 003 and 004's "regenerate, never merge" handover (005 §8.5) becomes moot: there is exactly one
  regeneration, in B1.

## What this does not claim

Nothing here is built. The batch sizes are a judgement, not a measurement — if B4 or B5 overruns a
single agent's useful context, split it at the module or file boundary named in its row and keep
the order. Everything is *built and unverified* until B7 says otherwise.
