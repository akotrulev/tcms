# 013 — Releases, roadmap & readiness (module M3 of 009)

**Status (filed 2026-10-05):** Delivered — module M3, done 2026-09-01 @ `55cb4bc0`. Unticked checkboxes below are not open work. Any `Status`, task table or checkbox below this line is the plan's history, not its state.

**Date:** 2026-09-01 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **One task is one session** — the
> paste-ready prompts are in [Prompts — one per task](#prompts--one-per-task) at the foot.

**Goal:** A person creates a release, targets epics and stories at it, reads the roadmap, links a
story to the test cases that prove it, and gets **one** answer to *"can we ship 2.4?"* — stories done
**and** linked test cases passing.

**Architecture:** `release` is a new table in `tracker` (project-scoped, D13), `issue.release_id` is a
nullable FK with no join table (D6), and story↔test-case links are `issue_link` rows with
`target_type = 'TEST_CASE'` — a table `V3` created and nothing has written yet. Readiness joins
`execution_fact` for the latest result per case **within one version**, through exactly one file, so
`tracker`'s single crossing into another module's tables is findable by opening one class.
`ReleaseSortKey` moves from `execution` to `api` because `tracker → execution` is a forbidden
crossing, and it gets its first reader in the same commit its `READERLESS_BY_DECISION` entries change.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Flyway `V6` · Postgres 17 (RLS forced) · TypeScript ·
React 19 + Vite 8 + Tailwind v4 + TanStack Query · `@tcms/client` / `@tcms/cli` / `@tcms/mcp`.

**Migration:** **`V6__release.sql`** — confirmed against
`backend/src/main/resources/db/migration/` on 2026-09-01, which holds `V1`…`V5`. 009's table
reserved `V5` for this module and then corrected itself to `V6`; the directory is the authority and
it agrees.

**Tasks** — in the order they are done. One stack, one agent, one session each.

| # | Task | Stack | Agent | Deliverable | Depends on |
|---|---|---|---|---|---|
| 1 | T1 — The release contract | `contract` | `be-java` | `openapi.yaml` declares eleven new operations, three amended ones and fifteen schemas; both generated clients regenerated; `npm run build` green in `frontend/` and `packages/` | none |
| 2 | T2 — `V6`: release, `issue.release_id`, `test_run.release_id` | `db` | `data-architect` | `V6__release.sql` applies on an empty database; `release` is company-isolated and forced-RLS; `SchemaConventionTest` green | T1 |
| 3 | T3 — `tracker.release`: CRUD, readiness, roadmap, the QA link | `be` | `be-java` | Every operation T1 declared answers over HTTP; a release with a done story whose linked case failed reads **not ready** and names that story; `1.10` sorts after `1.9` | T2 |
| 4 | T4 — Release verbs on the CLI and MCP | `ts` | `tooling-engineer` | `tcms release list/show/create/edit/publish/delete`, `tcms roadmap`, `tcms issue cases`, `tcms issue release`, `tcms issue cases set` — **ten**, not the eight this line first said; `tcms_read_release` and `tcms_write_release` registered; the shipped guide names 30 tools | T3 |
| 5 | T5 — Releases, the roadmap and the calendar | `fe` | `fe-react` | `/projects/:id/releases`, `/releases/new`, `/releases/:releaseId`, and the Timeline and Calendar views of `/projects/:id/work`; the empty roadmap explains itself and carries the action that resolves it | T4 |
| 6 | T6 — The overview panels, the QA link on screen, and the close-out | `fe` | `fe-react` | The overview's **Next release** panel renders real data, the backlog groups by release and bulk-moves into one, a story lists the cases that prove it, `test-cases.html` gains its **Verifies** column, and 009's status table reads `done` | T5 |

**Mapping to `009-execution-breakdown.md` phase 3:** T1 = `t14` (its "module plan" half is *this
document*), T2 = `t15`, T3 = `t16`, T5 = `t17`, T6 = `t18`. **T4 has no counterpart there** — phase 3
lists the CLI/MCP surface *document* under `t14`'s files and never gives the verbs a task. 012 hit
the same gap and answered it the same way, with a `ts` task of its own (`a7be32cc`). The breakdown is
wrong by omission, not by design; this plan adds the task rather than folding `packages/` work into a
`contract` or `fe` session.

---

## What is not in this module

- **The burn-up and completion *report types* in `reporting`.** 009's M3 `**be**` line names them and
  so does `t16` in the execution breakdown; they are **deferred to M9 deliberately**, and T6 writes
  that into M9's prompt rather than leaving it to be rediscovered.

  Two reasons, either sufficient. **They would have no reader.** The burn-up's screen is M9's, and
  `release.html`'s *Burn-up* button links to `reports.html`, which this module does not touch — so
  building the computation here means shipping a report type nothing renders, in a repository whose
  architecture tests exist to find exactly that. **And they do not fit.** A `ReportComputation` in
  this codebase is not one class: `ReportTypes`, `ReportComputations`, `ReportCapabilitiesView` and
  three renderers all have to answer for a new type, and T3 is already at the 150k ceiling with the
  readiness join. Splitting a `reporting` task off would be a seventh session for work whose screen
  arrives a module later.

  **What M3 does deliver for them is the data**: `release.created_at`, `issue_fact.release_id` and
  `issue_fact.release_assigned_at` are written from this module onward, and `scopeAddedAfterCreation`
  on the readiness payload is the burn-up's scope line computed once, so M9 inherits the arithmetic
  as well as the columns.
- **Analytics beyond readiness and the scope-change count** — burn-up over time, cycle time and CFD
  are M9's. `release.created_at` and `issue_fact.release_assigned_at` are the two columns M9 needs
  and this module is what starts filling them.
- **Un-publishing a release.** `POST /releases/{id}/publish` is one-way in M3 and idempotent. There
  is no screen affordance for taking it back, and inventing one now means inventing what happens to
  `released_at`.
- **Absences and timesheet periods on the calendar** (`work-calendar.html` draws both). They are M7's
  and M6's tables. The calendar names them as absent rather than drawing an empty month — see D-n.
- **A cross-project release.** D13: a version shipped from two projects is two releases, and this
  module does not make "is 2.4 ready?" answerable across both.

## No backfill, and the roadmap is empty on the day this merges

D14, and it is an **acceptance criterion rather than a caveat**. Nothing is migrated into `release`:
`test_run.release_version`, `test_run.release_sort_key` and `test_plan.milestone_name` /
`milestone_date` keep working exactly as they do, and the first release row in any instance is one a
person types.

So on merge day: **the roadmap is empty, the release list is empty, and readiness has no history.**
Every screen this module ships must say *why* it is empty and carry the action that resolves it —
`create a release` — because an empty timeline that says nothing reads as a defect in a screen that
has just been built, and the reader has no way to tell the difference. T5's acceptance criteria pin
this and T6's overview panel is the same rule one screen over.

## The cross-cutting tax, and the two lines of it this module does not owe

009's *What every module carries* table applies in full, with two exceptions worth stating so nobody
goes looking for work that is not there:

- **The permission catalogue does not change.** `release:read`, `release:manage` and
  `release:publish` were seeded by **M0's `V2`** (`V2__pm_foundation.sql:343-345`) and are already in
  the roles spec §A3, `permission-matrix-summary.md` and `README.md`. So there is no catalogue edit,
  no three-document commit, and `matrixCopies.test.ts` and `SeedDataTest`'s catalogue-size assertion
  are untouched. **Do not add a `release:*` key**; if a gate seems to need one, it does not — read
  D-e of plan 012, which made the same argument for labels.
- **`contractBodies.ts` does not change.** Both copies walk the whole document generically, so a new
  path or schema needs no entry; the marked region is edited only when the *walker* changes. 012's
  contract commit (`eb10bc07`) touched neither copy for its ten operations.

**One tax line this module does owe more than usual:** `tierClaims.test.ts` fails on a **comment**
claiming somebody lacks a permission §A3 grants them. This module writes several such comments on
purpose — *"Automation Service does not hold `release:read`"* appears in the contract, in
`ReleaseService`, in the surface document and in `server.test.ts`. Every one of them is **true**
against §A3, which is why they pass; check the row before writing a fourth.

---

## Decisions taken in this plan

Numbered `D-a` … `D-p` in 012's convention. Each is settled here so no task re-argues it, and each
says what it refuses as well as what it takes.

### D-a — `ReleaseSortKey` moves to `api` and becomes public; the two `READERLESS_BY_DECISION` entries stay

009's M3 section settles the move (review R6): `ReleaseSortKey` is in `com.tcms.execution` today,
`tracker` may not import `execution`, and the class is a pure value type with no domain concept of
its own — so it goes to `api` beside `AutomationKeys` and `PatchBody`, and `TestRun` and
`TestExecution` follow the import. It becomes `public final class` with a `public static String of`,
because a package-private one in `api` is unreachable from either caller.

**What does NOT follow, and 009 predicts otherwise:** the plan says *"`release_sort_key` comes off
`READERLESS_BY_DECISION` in the same commit that first reads it, or `ArchitectureRulesTest` fails."*
That rule is computed over **mapped entity columns**, and the two entries are
`TestRun.releaseSortKey` and `TestExecution.releaseSortKey`. This module reads neither: the roadmap
orders `release.sort_key`, which is a **third** column on a **new** table with its own reader from
the first commit. Removing either entry would make the rule red, not green — it fails on an entry
whose column still has no reader as loudly as on a reader with no entry.

So T3 **keeps both entries and rewrites their text**, striking through the sentence that said a
roadmap would consume them and recording what actually happened: the class got its reader, the
columns did not, and the reason is that a release's ordering key belongs to the release rather than
to each run that mentions its name. Getting this backwards is a red gate at the end of T3 and a
confusing one, because the failure names a column nobody touched.

### D-b — Readiness matches runs on `release_version` **text**, and `test_run.release_id` is not its reader

`execution_fact` already carries `release_version` denormalised off the run, indexed, and it is the
column every existing release filter in this product matches on (D6, note 2). Readiness matches the
same way: `execution_fact.release_version = :version` within the project.

The alternative — join back to `test_run.release_id` — is wrong in this module and would be wrong
silently. **A run recorded before its release row existed has a NULL `release_id` forever**, and D14
guarantees that is *every run in every existing instance*. Readiness filtered on the FK would answer
"no run yet" for a version with three hundred executions against it, which is the exact shape of
wrong answer this codebase legislates against. The text column is also what CI passes and always
will.

`test_run.release_id` is still added (T2) — see D-c for what maintains it and why it has no reader
here.

### D-c — `test_run.release_id` is written by a trigger and read by nothing in M3, and both halves are deliberate

009 and D6 both call for the column, and it lands in `V6` rather than later because adding a foreign
key to `test_run` on an instance with real data is a lock on the product's hottest table, and the
cheap moment is now.

It is **not mapped on the `TestRun` entity.** A mapped column needs a reader and a writer or an entry
in one of the two by-decision lists (`ArchitectureRulesTest`), and this one would need both entries
on the day it lands. Leaving it unmapped is the same choice §38 made for `release_sort_key` and for
the same stated reason: *mapping a column nothing here maintains is how it comes to disagree with the
field it derives from.*

It is **maintained by `tcms_test_run_release_link()`, a `BEFORE INSERT` trigger**, so the column is
not a lie. The trigger resolves `NEW.release_version` to a `release` row in the same project and
leaves it NULL when there is none. `BEFORE INSERT` only, never `BEFORE UPDATE`: `TestRun`'s own
mapping declares `release_version` `updatable = false`, so there is no update path that could change
the answer. This is `tcms_issue_rank_default`'s argument one table over — one definition, on every
creation path including the ones written later, rather than a rule that holds for the callers who
remember.

**It has no reader in M3, and that is stated rather than hidden.** Readiness uses the text (D-b).
What the column buys is that M9's burn-up and M4's importer inherit a maintained FK rather than an
empty one, and that `ON DELETE SET NULL` keeps a deleted release from orphaning a run.

### D-d — The verdict has three members, and `NOT_TESTED` is not the same claim as `NOT_READY`

```
ReleaseVerdict = READY | NOT_READY | NOT_TESTED
```

- `NOT_TESTED` — **no execution has been recorded against this version, and every counted story is
  done.** Nothing is outstanding except proof.
- `NOT_READY` — anything is outstanding: a counted story not in a `DONE` status, a linked case whose
  latest result *for this version* is not a pass, **or a linked case with no result for this version
  at all** while others do have one. An unrun case is unproven, and R4's criterion is *cases passing
  vs total*, not *vs executed*.
- `READY` — every counted story done and every linked case passing.

The order matters and is the module's own acceptance criterion: *a release with no run says so rather
than showing zero failures.* `tests.hasRun` is on the wire beside the verdict for exactly this, and
`tests.passed` / `tests.failed` are **`null` rather than `0`** when it is false. A zero and an
unknown are different numbers and a meter drawn from a zero looks like a clean release.

"Counted story" is D8's set — story-level issues whose type has `counts_toward_completion`, so
**Spikes are excluded from both halves**, as they are from an epic's completion percentage. A release
whose only outstanding item is a spike is `READY`, and that is the same arithmetic the epic meter
already uses rather than a second one.

### D-e — The readiness badge's vocabulary is the verdict's, and it diverges from `releases.html`

The mockup's **Readiness** column holds `Not ready` · `Planned` · `Planned` · `Released` ·
`Released`. Two of those are a *status a person set* and three are a *verdict the server computed*,
in one column under one heading. Mockups win on what a screen shows, and this is recorded as the
module's one deliberate divergence:

| Wire | Badge |
|---|---|
| `status = RELEASED` | **Released** |
| `verdict = READY` | **Ready** |
| `verdict = NOT_TESTED` | **Not tested** |
| `verdict = NOT_READY` | **Not ready** |

Why: `releases.html`'s row for 2.5 shows `0/6` stories done and reads **Planned**, and its row for
2.4 shows `21/27` and reads **Not ready**. The same badge would mean "nobody has started" on one row
and "somebody has, and it failed" on the next. The mockup is right about everything else on that
screen — the meters, the `no run yet` cell, the panel foot about `2.10` — and the `Tests` column
still renders the literal words **no run yet** exactly as drawn, which is where the "says so rather
than showing zero failures" criterion is actually met. `release.html`'s own *"If nothing had run"*
panel makes the same argument in prose; this is that argument applied to the badge.

### D-f — Readiness reads live `issue` rows; only the scope-change count reads `issue_fact`

`issue_fact` is event-fed through the outbox, whose delivery has a one-minute floor. A readiness
screen that read it would show a story as not-done for up to a minute after somebody moved it, on the
one screen whose whole purpose is to answer whether the work is finished.

So the story arm, the blockers and the epics all read `issue`, `issue_type` and `issue_status`
directly — `tracker`'s own tables, no crossing, no delay. **One number comes from `issue_fact`:**
`scopeAddedAfterCreation`, the mockup's `+4 · 4 stories added after the release was created`, which is
`count(*) WHERE release_assigned_at > release.created_at`. There is no live column carrying that
timestamp — M1 put `release_assigned_at` on `issue_fact` on purpose (009 review R12), because it is
the one number in M9's burn-up that cannot be reconstructed after the fact.

The cost is stated: the scope count can lag the rest of the panel by a projection tick, so the tile
carries the same `as of` treatment `DataFreshness` already gives the report headers, and the two
numbers beside it never disagree because they never come from the same source about the same fact.

### D-g — One `ReleaseFacts` file names every foreign table, and readiness is a request-path read

`repository.ExecutionFacts` and `execution.CaseFacts` are the pattern and its rule: *each module
reads rows it holds ids into, through exactly one file, so a reviewer can find every crossing by
opening two.* `tracker.release.ReleaseFacts` is `tracker`'s, and it names three tables it does not
own — `test_case`, `case_health` and `execution_fact` — in native SQL through the `EntityManager`.
No second class in `tracker` may name any of them.

`ExecutionFacts`' javadoc warns against *synchronous request-path coupling*, and readiness is exactly
that. The difference that makes it acceptable rather than a violation of the same rule: `case_health`
is a **read model** `repository` maintains on a tick, and `execution_fact` is an append-only fact log
`reporting` refreshes on a tick — neither is a live transactional table of another module, and
neither read can block a write anywhere. What `ExecutionFacts` refuses is a convenience method that
moves *rows* across the seam for the caller to loop over; `ReleaseFacts` returns aggregates and
per-case verdicts computed in one statement, which is the shape that file recommends.

### D-h — `issue_link` gets an entity, and it is **not** called `IssueLink`

`com.tcms.documentation.IssueLinkView` / `IssueLinkInput` / `IssueLinkService` /
`IssueLinkController` already exist and are the **external** tracker link — a Jira or ADO issue
attached to a doc block. `ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames` would catch
a colliding bean and nothing catches a colliding *record*, which is worse: two `IssueLink` types one
import apart is how a wrong import survives review.

The table is `issue_link`; the entity is **`VerificationLink`**, its id class is
`VerificationLinkId`, its repository is `VerificationLinkRepository`, and the wire schemas are
`IssueTestCase` / `IssueTestCasesInput` / `CaseVerification`. `@Table(name = "issue_link")` carries
the mapping and a class comment carries this paragraph.

`target_type` is a `TEXT` column with no CHECK (V3 left it open, deliberately: *"nearly everything in
a PM system points at an issue"*). The entity maps it as a string and the service writes the single
constant `TEST_CASE`; a Java enum over one member would be a vocabulary claim this module has no
right to make on a column three later modules will add to.

### D-i — The reverse direction of the QA link costs no crossing at all, and gets its own endpoint

`test-cases.html`'s new **Verifies** column shows, per case, the stories it verifies. Two ways to
serve it:

1. Widen `TestCaseSummary` with the issues — `repository` then reads `issue_link` and `issue`, which
   is a **fourth** foreign-table seam in that module and a synchronous request-path coupling into
   `tracker` on the product's busiest list.
2. `GET /projects/{projectId}/case-verifications` in `tracker`, keyed by case id, merged into the
   table client-side.

(2), and it is not a compromise: the reverse direction reads `issue_link` and `issue` — **both
`tracker`'s own tables** — so it needs no `ReleaseFacts` entry and no crossing in either direction.
The case list pays one extra parallel read; the column is `helper.display`, fed from a `Map<caseId,
IssueRef[]>`, and renders `—` when the map has no entry, which is also what it renders while the
second read is in flight. The mockup's `Verifies a story ×` filter chip is client-side over that map
for the same reason and is stated as such on the screen.

### D-j — `SwimlaneBy` gains `RELEASE`, and the compile error is the mechanism

One member on the contract's enum, one arm in `BoardCardService`'s lane grouping, and one entry in
`frontend/src/lib/labels.ts::swimlaneByLabels` — which is `satisfies Record<SwimlaneBy, string>`, so
the new member **does not compile** until it is labelled. That file exists for this.

`V6` widens `board.swimlane_by`'s CHECK to six members. `frontend/src/features/board/Swimlane.tsx`
renders whatever lanes the server sends and needs no change. A card with no release lands in the
`None` lane the grouping already produces for every other axis.

### D-k — The backlog's release grouping is a slice of one order, not a second order

`issue.rank` is **one project-wide sequence** (012, and the board and the backlog both read it). A
release-filtered backlog is a *filter over that order*. So:

- `GET /projects/{projectId}/backlog` gains `releaseId` (uuid) and `unscheduled` (boolean). Two
  parameters rather than `releaseId=null`, for the same reason `unparented` is a flag beside
  `epicKey`: an absent parameter already means *don't filter*, and a client cannot spell the
  difference.
- `BacklogRow` gains `release: ReleaseRef | null`.
- The grouping is rendered **inside `BacklogView`'s existing `DataRegion`, above the `<ol>`** — one
  list, section headers between runs of rows. A drag from one section to another is still one
  `PUT /issues/{key}/rank` against a neighbour, because the neighbour is the row above or below it in
  the same sequence.

**No new component.** 012 D-f left the two sections and the `Move to release ▾` control out of
`BacklogView` because `release` is this module's table; they go back into that file.

### D-l — `Move to release` is N `PATCH`es and does not become a bulk endpoint

`useBulkUpdateIssues(projectId)` already exists: `Promise.allSettled` over one `PATCH /issues/{key}`
each, returning `{ applied, failed }` and **never rejecting**, because a mixed outcome is ordinary
(012 D-ab). `Move to release` joins `Set epic` and `Assign` in `BulkBar` and gets that report for
free.

A bulk endpoint over issues was considered and refused **for this module**: it is a `be` task with a
partial-failure contract of its own, and the screen that would justify it — a hundred-row release
move — is not a flow anybody has yet. Recorded so the next person finds the decision rather than the
limit.

### D-m — Publishing is `POST /releases/{id}/publish`, not a `status` field on `PATCH`

`release:publish` is a separate key from `release:manage` in the catalogue **and the roles spec says
why**: *publishing moves a state, and it is its own gate and its own audit row.* A `status` property
on `ReleaseUpdate` would be that state moved behind the other key.

Idempotent: a second call answers 200 with the release unchanged and does not restamp `released_at`.
One-way in M3 (see *What is not in this module*).

### D-n — The calendar draws what M3 has data for, and names the rest

`work-calendar.html` draws five kinds of event. This module can serve two:

| Drawn | Source |
|---|---|
| Issue due dates, including overdue | `GET /issues` with the shared work filter — `dueDate`, `overdue` |
| Release target dates | `GET /projects/{projectId}/releases` |
| ~~Test runs~~ | Not drawn. A run has three candidate dates and picking one blind is a decision this module has no reason to take |
| ~~Absences~~ | M7's `absence` table |
| ~~Timesheet period ends~~ | M6's `timesheet_period` |

The month footer names the three absent kinds and the module each arrives with, exactly as
`WorkTablePage`'s `VIEWS` entries already do for a view that is not built — because *"coming soon"
names nothing a reader can plan around*.

### D-o — The roadmap's `unscheduled` lane holds every epic that cannot be drawn under a release

`work-timeline.html`'s note is the rule: *an epic with no dates does not disappear from the roadmap —
it drops into the unscheduled lane.* Two shapes qualify and both go in one list:

- an epic with **no dates at all** (`startDate` and `targetDate` both null), whatever its release;
- an epic with **no target release**, whatever its dates.

The second is not in the mockup's note and is implied by its layout: the unscheduled track draws bars
with real spans, and an epic with dates and no release has nowhere else to be drawn. One list, one
name, documented on the schema.

A release's **bar span** is computed on the client, in
`frontend/src/features/releases/timelineScale.ts`, from `min(epic.startDate) … release.targetDate`,
falling back to a point marker at the target date when no epic under it has a start. It is a pure
function with its own tests and it is not in a component, because it is the one piece of arithmetic
on that screen a reader could get wrong twice.

### D-p — The story's linked-cases panel ships in this module, or the QA link has no writer

`PUT /issues/{key}/test-cases` is the only thing that creates a `TEST_CASE` link, and readiness is
computed over those links. Without a screen that writes them, every release in every instance reads
`NOT_TESTED` forever and the module's headline capability is unreachable.

009's `**fe**` line for M3 does not name `story.html`, and that is an omission rather than a
deferral: story H7 is in this module's own reading list and its first criterion is *"Given I open a
story, when I choose 'Link test cases', then I can pick from the project's cases and the story lists
them."* `LinkedCasesPanel` lands in T6 beside the **Verifies** column — the two directions of one
fact, built together, which is what `test-cases.html`'s panel foot says out loud.

---

## File structure

**New — backend (T3)** · `backend/src/main/java/com/tcms/tracker/release/`

| File | Responsibility |
|---|---|
| `Release.java` | The entity. Factory computes `sortKey` through `api.ReleaseSortKey`; `publish(Instant)` moves the status |
| `ReleaseRepository.java` | `findByProjectIdOrderBySortKeyAsc`, `findScopedById`, `findByProjectIdAndVersionIgnoreCase` |
| `ReleaseView.java` · `ReleaseSummaryView.java` · `ReleaseRefView.java` | The three wire shapes of a release |
| `ReleaseInput.java` · `ReleaseUpdate.java` | Create and patch bodies |
| `ReleaseService.java` | CRUD + publish, sealed `Outcome` inside `GateResult` |
| `ReleaseController.java` | `/projects/{id}/releases`, `/releases/{id}`, `/releases/{id}/publish` |
| `ReadinessService.java` | The verdict, the blockers, the epics, the scope count |
| `ReadinessController.java` | `/releases/{id}/readiness` |
| `ReadinessView.java` · `ReleaseBlockerView.java` · `RoadmapEpicView.java` · `ReadinessSummaryView.java` · `ReleaseEvidenceView.java` | The readiness shapes |
| `RoadmapService.java` · `RoadmapController.java` · `RoadmapView.java` · `RoadmapReleaseView.java` | `/projects/{id}/roadmap` |
| `ReleaseFacts.java` | **The only file in `tracker` naming `test_case`, `case_health` or `execution_fact`** (D-g) |
| `VerificationLink.java` · `VerificationLinkId.java` · `VerificationLinkRepository.java` | `issue_link` with `target_type = 'TEST_CASE'` (D-h) |
| `VerificationService.java` · `VerificationController.java` | `/issues/{key}/test-cases`, `/projects/{id}/case-verifications` |
| `IssueTestCaseView.java` · `IssueTestCasesInput.java` · `CaseVerificationView.java` · `IssueRefView.java` | The QA-link shapes |
| `ReleaseVerdict.java` | The three-member enum (D-d) |

**Moved (T3)** · `com/tcms/execution/ReleaseSortKey.java` → `com/tcms/api/ReleaseSortKey.java`,
public; imports updated in `execution/TestRun.java` and `execution/TestExecution.java`, and in
`test/java/com/tcms/execution/ReleaseSortKeyTest.java` and `ReleaseSortKeyOrderingTest.java`.

**Modified — backend (T3)** · `tracker/Issue.java` (`releaseId` + `targetRelease(UUID)`),
`tracker/IssueService.java` (`releaseId` on create and update), `tracker/IssueView.java`,
`tracker/IssueSummaryView.java`, `tracker/IssueInput.java`, `tracker/IssueUpdate.java`,
`tracker/IssueFactProjection.java` (the two release columns), `tracker/board/BacklogRowView.java`,
`tracker/board/BoardCardService.java` (the `releaseId` / `unscheduled` filter and the `RELEASE`
lane), `tracker/board/SwimlaneBy.java`.

**New — frontend (T5, T6)** · `frontend/src/features/releases/` — `ReleaseListPage.tsx`,
`ReleaseNewPage.tsx`, `ReleaseDetailPage.tsx`, `ReadinessPanels.tsx`, `RoadmapView.tsx`,
`CalendarView.tsx`, `timelineScale.ts`, `releaseBadge.ts`; and
`frontend/src/features/tracker/LinkedCasesPanel.tsx`.
**New — data** · `frontend/src/api/queries/releases.ts`.

**Modified — frontend** · `api/queries/keys.ts`, `api/queries/index.ts`, `api/types.ts`,
`lib/labels.ts`, `lib/devFaults.ts`, `app/router.tsx`, `app/AppShell.tsx`,
`features/tracker/WorkTablePage.tsx`, `features/tracker/StoryPanels.tsx`,
`features/board/BacklogView.tsx`, `features/projects/ProjectOverviewPage.tsx`,
`features/cases/CaseGrid.tsx`.

---

## The 150k budget, per task

Counted as the rule asks: this plan's own section, `CLAUDE.md`, the pattern files the session must
read, the files it writes, and the build output it reads back.

| Task | Reads | Writes | Est. |
|---|---|---|---|
| T1 | plan §T1 + `openapi.yaml`'s tracker block (~1.4k lines of 17.7k) + `contractBodies` header + the surface doc §3.4–3.5 | ~900 lines of YAML, two generated clients (machine-written) | **~95k** |
| T2 | plan §T2 + `V5` whole (690) + `V3`'s `issue_fact`/`issue_link` blocks + `SchemaConventionTest` | ~330 lines of SQL + the ERD §7c | **~75k** |
| T3 | plan §T3 + `LabelService`/`LabelController`/`IssueLabel` as templates + `IssueFactProjection` + `ExecutionFacts` + `BoardCardService` + `ArchitectureRulesTest`'s two entries + `ContractConventionTest`'s two maps | ~30 files, ~2.6k lines Java + ~900 lines of test | **~145k — at the ceiling.** Split seam if it overruns: `ReleaseFacts` + `ReadinessService` + `VerificationService` are a second session, and the seam is the `ReleaseFacts` file itself |
| T4 | plan §T4 + `packages/cli/src/reads.ts` + `writes.ts` tail + `packages/mcp/src/reads.ts`/`writes.ts` tails + the surface doc | ~700 lines TS + tests + `sections.tsx` | **~120k** |
| T5 | plan §T5 + four mockups + `BacklogView`/`ProjectBoardView` as kit templates + `WorkTablePage` + `keys.ts` | ~2.1k lines TSX + ~700 test | **~140k** |
| T6 | plan §T6 + `ProjectOverviewPage` + `CaseGrid` + `StoryPanels` + `BacklogView` + the three close-out documents | ~900 lines TSX + ~500 test + four markdown files | **~130k** |

---

## T1 — The release contract

**Deliverable:** `docs/specs/openapi.yaml` declares eleven new operations, three amended ones and
fifteen new schemas — every schema with its `required` array, inline objects included — and both
generated clients are regenerated in the same commit. `npm run build` is green in `frontend/` and in
`packages/`.
**Depends on:** none
**Stack:** `contract` · **Agent:** `be-java` · **Skills:** `be-shared-api-surface-plan`

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify (generated): `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` (new §3.6)

### The eleven operations

| Path | Verb | operationId | Gate |
|---|---|---|---|
| `/projects/{projectId}/releases` | GET | `listReleases` | `release:read` |
| `/projects/{projectId}/releases` | POST | `createRelease` | `release:manage` |
| `/releases/{releaseId}` | GET | `getRelease` | `release:read` |
| `/releases/{releaseId}` | PATCH | `updateRelease` | `release:manage` |
| `/releases/{releaseId}` | DELETE | `deleteRelease` | `release:manage` |
| `/releases/{releaseId}/publish` | POST | `publishRelease` | `release:publish` |
| `/releases/{releaseId}/readiness` | GET | `getReleaseReadiness` | `release:read` |
| `/projects/{projectId}/roadmap` | GET | `getRoadmap` | `release:read` |
| `/issues/{key}/test-cases` | GET | `listIssueTestCases` | `issue:read` |
| `/issues/{key}/test-cases` | PUT | `putIssueTestCases` | `issue:edit` |
| `/projects/{projectId}/case-verifications` | GET | `listCaseVerifications` | `issue:read` |

### The three amendments

1. `GET /projects/{projectId}/backlog` gains `releaseId` and `unscheduled` (D-k).
2. `SwimlaneBy` gains `RELEASE` (D-j).
3. `Issue`, `IssueSummary` and `BacklogRow` gain `release: ReleaseRef | null`; `IssueInput` and
   `IssueUpdate` gain `releaseId`.

- [ ] **Step 1: Check the names nothing else has taken**

`IssueRef` is a new schema name and `ReleaseRef` is another. Confirm neither exists, and confirm
`getRelease` / `listReleases` are not already operationIds — `getBoardConfig` is in the contract
because `getBoard` was taken and nobody checked until the generated client could not name one of them.

```bash
cd ~/RiderProjects/tcms
grep -n "^    IssueRef:\|^    ReleaseRef:\|^    Release:\|^    Roadmap:" docs/specs/openapi.yaml
grep -c "operationId: getRelease$\|operationId: listReleases$\|operationId: getRoadmap$" docs/specs/openapi.yaml
```

Expected: no output from the first, `0` from the second.

- [ ] **Step 2: Add the release schemas**

Append to `components.schemas`, after `BacklogRow`'s block, in this order.

```yaml
    ReleaseStatus:
      type: string
      description: |
        `PLANNED` until somebody presses **Mark released**, which is `POST /releases/{id}/publish`
        and costs `release:publish` — a separate key from `release:manage` because publishing moves
        a state and earns its own gate and its own audit row (roles spec, Releases).
      enum: [PLANNED, RELEASED]

    ReleaseVerdict:
      type: string
      description: |
        The one answer to "can we ship 2.4?".

        `NOT_TESTED` is **not** `NOT_READY` with zero failures: it means no execution has been
        recorded against this version at all, while every counted story is done. An untested version
        and a clean version are not the same claim, and a meter drawn from a zero looks like the
        second. Read `ReleaseTests.hasRun` beside this.

        Counted stories are D8's set — story-level issues whose type counts toward completion — so a
        **Spike never blocks a release**, exactly as it never moves an epic's percentage.
      enum: [READY, NOT_READY, NOT_TESTED]

    ReleaseRef:
      type: object
      required: [id, version]
      description: A release as it appears on an issue or a backlog row — never its readiness.
      properties:
        id: { type: string, format: uuid }
        version: { type: string, example: '2.4' }
        targetDate: { type: string, format: date, nullable: true }

    Release:
      type: object
      required: [id, projectId, version, status, createdAt]
      properties:
        id: { type: string, format: uuid }
        projectId: { type: string, format: uuid }
        version:
          type: string
          example: '2.4'
          description: |
            Unique within the project, case-insensitively (D13 — a version shipped from two
            projects is two releases). Compared segment by segment for ordering, never as text:
            `1.10` comes after `1.9`.
        description: { type: string, nullable: true }
        targetDate:
          type: string
          format: date
          nullable: true
          description: |
            Optional. Without one the release still exists and still appears on the roadmap, in
            version order, with a point marker instead of a bar.
        status: { $ref: '#/components/schemas/ReleaseStatus' }
        releasedAt: { type: string, format: date-time, nullable: true }
        createdAt:
          type: string
          format: date-time
          description: |
            The baseline "scope added after the release was created" is measured from (009 review
            R12). Never edited.
        updatedAt: { type: string, format: date-time, nullable: true }

    ReleaseSummary:
      type: object
      required: [id, version, status, createdAt, readiness]
      description: |
        A row in `releases.html`'s table. Carries its readiness inline because the list draws two
        meters and a badge per row, and a list of ids the client then resolves one by one is N+1
        over a screen that already knows every answer.
      properties:
        id: { type: string, format: uuid }
        version: { type: string }
        description: { type: string, nullable: true }
        targetDate: { type: string, format: date, nullable: true }
        status: { $ref: '#/components/schemas/ReleaseStatus' }
        releasedAt: { type: string, format: date-time, nullable: true }
        createdAt: { type: string, format: date-time }
        readiness: { $ref: '#/components/schemas/ReadinessSummary' }

    ReadinessSummary:
      type: object
      required: [verdict, storiesDone, storiesTotal, linkedCases, hasRun]
      properties:
        verdict: { $ref: '#/components/schemas/ReleaseVerdict' }
        storiesDone: { type: integer }
        storiesTotal: { type: integer, description: Counted story-level issues. Zero is a real answer. }
        linkedCases: { type: integer, description: Distinct test cases linked to those stories. }
        hasRun:
          type: boolean
          description: |
            False when no execution has been recorded against this version. `casesPassed` and
            `casesFailed` are then **null and not zero** — see `ReleaseVerdict`.
        casesPassed: { type: integer, nullable: true }
        casesFailed: { type: integer, nullable: true }

    ReleaseTests:
      type: object
      required: [hasRun, linkedCases]
      description: The test half of readiness, and the latest result **per case** — not the best one.
      properties:
        hasRun: { type: boolean }
        linkedCases: { type: integer }
        withResult: { type: integer, nullable: true, description: Linked cases with any result against this version. }
        passed: { type: integer, nullable: true }
        failed: { type: integer, nullable: true }
        blocked: { type: integer, nullable: true }
        untested:
          type: integer
          nullable: true
          description: Linked cases with no result against this version. Null when `hasRun` is false, where the number is every linked case and saying so twice invites the client to draw it as a bar.
        latestExecutedAt: { type: string, format: date-time, nullable: true }
        latestRunId: { type: string, format: uuid, nullable: true }
        latestRunName: { type: string, nullable: true }
        executedBy:
          allOf: [{ $ref: '#/components/schemas/UserRef' }]
          nullable: true

    # `blocked` was in this plan and is NOT in the contract that landed: IssueStatusKind has
    # exactly three members (NOT_STARTED, IN_PROGRESS, DONE), so "blocked" is a status NAME a
    # project chose and not a class this contract can count. release.html draws "1 blocked"; the
    # blockers list carries each story's real status by name, which is where the word appears.
    ReleaseStories:
      type: object
      required: [done, total, inProgress, notStarted, excludedSpikes]
      properties:
        done: { type: integer }
        total: { type: integer }
        inProgress: { type: integer }
        notStarted: { type: integer }
        excludedSpikes:
          type: integer
          description: |
            Left out of both halves (D8). A spike produces knowledge, not shippable work, so it
            never blocks a release — and the screen says how many, so a total that does not add up
            to the backlog is explained rather than doubted.

    ReleaseBlocker:
      type: object
      required: [key, title, type, status, casesPassed, casesFailed, casesBlocked, casesUntested, failedCaseKeys]
      description: |
        One row of `release.html`'s **What stands between here and shipping**. A story is here when
        it is not in a `DONE` status, **or** when it is done and one of its linked cases did not
        pass in the latest result recorded against this version — which is the criterion this whole
        module exists for, and the reason `status` is on the wire rather than a boolean.
      properties:
        key: { type: string, example: ACME-118 }
        title: { type: string }
        type: { $ref: '#/components/schemas/IssueTypeRef' }
        status: { $ref: '#/components/schemas/IssueStatusRef' }
        casesPassed: { type: integer }
        casesFailed: { type: integer }
        casesBlocked: { type: integer }
        casesUntested: { type: integer }
        failedCaseKeys:
          type: array
          description: The cases that failed, by display key — `TC-311`. Empty when none did.
          items: { type: string }

    RoadmapEpic:
      type: object
      required: [key, title, statusKind]
      properties:
        key: { type: string }
        title: { type: string }
        startDate: { type: string, format: date, nullable: true }
        targetDate: { type: string, format: date, nullable: true }
        releaseId: { type: string, format: uuid, nullable: true }
        statusKind: { $ref: '#/components/schemas/IssueStatusKind' }
        completionPercent:
          type: integer
          nullable: true
          minimum: 0
          maximum: 100
          description: D8's percentage, computed server-side and never recomputed in a component. Null when the epic counts nothing.

    ReleaseReadiness:
      type: object
      required: [releaseId, version, status, verdict, stories, tests, blockers, epics, scopeAddedAfterCreation]
      properties:
        releaseId: { type: string, format: uuid }
        version: { type: string }
        status: { $ref: '#/components/schemas/ReleaseStatus' }
        verdict: { $ref: '#/components/schemas/ReleaseVerdict' }
        stories: { $ref: '#/components/schemas/ReleaseStories' }
        tests: { $ref: '#/components/schemas/ReleaseTests' }
        blockers:
          type: array
          items: { $ref: '#/components/schemas/ReleaseBlocker' }
        epics:
          type: array
          items: { $ref: '#/components/schemas/RoadmapEpic' }
        scopeAddedAfterCreation:
          type: integer
          description: |
            Counted issues first pointed at this release **after** it was created, from
            `issue_fact.release_assigned_at`. That projection is event-fed, so this one number can
            lag the rest of this payload by a tick — the screen says so. It is here rather than
            derived live because the timestamp exists nowhere else and cannot be reconstructed
            after the fact (009 review R12).

    RoadmapRelease:
      type: object
      required: [id, version, status, verdict, epics]
      properties:
        id: { type: string, format: uuid }
        version: { type: string }
        targetDate: { type: string, format: date, nullable: true }
        status: { $ref: '#/components/schemas/ReleaseStatus' }
        verdict: { $ref: '#/components/schemas/ReleaseVerdict' }
        epics:
          type: array
          items: { $ref: '#/components/schemas/RoadmapEpic' }

    Roadmap:
      type: object
      required: [releases, unscheduled]
      description: |
        `work-timeline.html`. **Empty on the day this module merges** and that is by decision, not by
        defect: D14 migrates nothing into `release`, so the first row here is one a person typed.
        A client rendering this must say so and offer to create one.
      properties:
        releases:
          type: array
          description: Every release, in version order — `2.10` after `2.5`, never between `2.1` and `2.2`.
          items: { $ref: '#/components/schemas/RoadmapRelease' }
        unscheduled:
          type: array
          description: |
            Every epic that cannot be drawn under a release: one with **no dates at all**, whatever
            its release, and one with **no target release**, whatever its dates. An epic with no
            dates does not disappear from the roadmap — it lands here, where it can be seen and
            given some.
          items: { $ref: '#/components/schemas/RoadmapEpic' }

    ReleaseInput:
      type: object
      required: [version]
      properties:
        version: { type: string, minLength: 1, maxLength: 40 }
        description: { type: string, nullable: true }
        targetDate: { type: string, format: date, nullable: true }

    ReleaseUpdate:
      type: object
      required: []
      description: |
        Every field optional; an absent key is "leave it". **`status` is not here** — publishing is
        `POST /releases/{releaseId}/publish` and costs `release:publish`, a key `release:manage`
        does not imply (D-m).
      properties:
        version: { type: string, minLength: 1, maxLength: 40 }
        description: { type: string, nullable: true }
        targetDate: { type: string, format: date, nullable: true }

    IssueRef:
      type: object
      required: [key, title, typeLevel]
      description: An issue as it appears on something that is not an issue — a test case's Verifies column.
      properties:
        key: { type: string }
        title: { type: string }
        typeLevel: { $ref: '#/components/schemas/IssueLevel' }

    IssueTestCase:
      type: object
      required: [caseId, caseKey, title]
      description: |
        A case linked to this story (H7), with the latest result it has anywhere — **not** scoped to
        a release. Release-scoped results are `GET /releases/{id}/readiness`, and the two answer
        different questions on purpose.
      properties:
        caseId: { type: string, format: uuid }
        caseKey: { type: string, example: TC-311 }
        title: { type: string }
        lastResultKind: { $ref: '#/components/schemas/CaseResultKind' }
        lastExecutedAt: { type: string, format: date-time, nullable: true }
        lastReleaseVersion: { type: string, nullable: true }

    IssueTestCasesInput:
      type: object
      required: [caseIds]
      description: |
        The whole set, written over whatever was there — `PUT`, for `IssueLabelsInput`'s reason: the
        control is a multi-select whose state is the set, and add/remove pairs let a stale client
        unlink a case somebody else had just linked.
      properties:
        caseIds:
          type: array
          items: { type: string, format: uuid }

    CaseVerification:
      type: object
      required: [caseId, issues]
      properties:
        caseId: { type: string, format: uuid }
        issues:
          type: array
          items: { $ref: '#/components/schemas/IssueRef' }
```

> **`CaseResultKind`** — do **not** invent it. `case_health.last_status_kind` already reaches the
> wire on `TestCaseSummary.lastResult`; find the schema that carries it
> (`grep -n "lastResult:" -A 12 docs/specs/openapi.yaml`) and `$ref` the enum it uses, nullable. If
> the existing shape is an inline object rather than a named enum, `$ref` that object instead and
> drop `lastExecutedAt` / `lastReleaseVersion` from `IssueTestCase` — they are already inside it.
> A second spelling of the same fact is what `noRecordOutsideApiRepeatsAnApiShape` exists to catch.

- [ ] **Step 3: Add the eleven paths**

Append after `/issues/{key}/labels`. Full text for the two that carry a rule; the other nine follow
`/projects/{projectId}/labels` and `/labels/{labelId}` exactly in status codes and parameter style.

```yaml
  /releases/{releaseId}/readiness:
    parameters:
      - { name: releaseId, in: path, required: true, schema: { type: string, format: uuid } }
    get:
      tags: [tracker]
      summary: Can we ship this version?
      operationId: getReleaseReadiness
      description: |
        `release:read` — Instance Admin, Company Admin, Project Manager, Test Lead, Test Engineer
        and Viewer. **Automation Service does not hold it** (roles spec §A3).

        One answer over two halves: every counted story done, **and** every linked test case passing
        in the latest result recorded against this version. Story R4, and the question neither Jira
        nor a test tool answers alone.

        **The latest result per case, not the best one.** A case that failed and was not re-run is a
        failure; taking the best result would let a release pass on a run somebody re-ran until it
        went green.

        **A version nothing has run against answers `NOT_TESTED` with `tests.hasRun: false` and
        null counts** — never zero failures. That distinction is the operation's reason for
        existing in this shape rather than as two numbers.
      responses:
        '200':
          description: The answer, with the stories that stand in the way named.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ReleaseReadiness' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }

  /releases/{releaseId}/publish:
    parameters:
      - { name: releaseId, in: path, required: true, schema: { type: string, format: uuid } }
    post:
      tags: [tracker]
      summary: Mark a release released
      operationId: publishRelease
      description: |
        `release:publish` — Instance Admin, Company Admin and Project Manager. **A separate key from
        `release:manage`**, for the reason `doc:publish` is separate from `doc:edit`: publishing
        moves a state, and it is its own gate and its own audit row.

        **Idempotent.** Publishing an already-released release answers 200 with it unchanged and
        does not restamp `releasedAt`.

        Readiness does not gate this. A team may ship a version with a known failure and record that
        they did; a server that refused would be asserting a policy this product has no way to know.
      responses:
        '200':
          description: Released.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Release' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

The remaining nine, in one block:

- `GET /projects/{projectId}/releases` → `200: ReleaseSummary[]`, `401`, `404`. Description names
  the version ordering and that an empty array is the ordinary state before anybody creates one.
- `POST /projects/{projectId}/releases` → `201: Release` + `Location`, `400`, `401`, `403`, `404`,
  **`409`** (a release with that version already exists in this project, case-insensitively).
  Carries `$ref: '#/components/parameters/IdempotencyKey'`.
- `GET /releases/{releaseId}` → `200: Release`, `401`, `404`.
- `PATCH /releases/{releaseId}` → `200: Release`, `400`, `401`, `403`, `404`, `409`.
- `DELETE /releases/{releaseId}` → `204`, `401`, `403`, `404`. Description: the issues pointing at it
  keep existing and lose their target release — `ON DELETE SET NULL`, because a release is a plan and
  deleting a plan does not delete the work.
- `GET /projects/{projectId}/roadmap` → `200: Roadmap`, `401`, `404`.
- `GET /issues/{key}/test-cases` → `200: IssueTestCase[]`, `401`, `404`.
- `PUT /issues/{key}/test-cases` → `200: IssueTestCase[]`, **`400`** (a case id belonging to another
  project, or one that does not exist — named as a field error, not a 404, because the issue is
  reachable and the case ids are the caller's mistake), `401`, `403`, `404`.
- `GET /projects/{projectId}/case-verifications` → `200: CaseVerification[]`, `401`, `404`.
  Description: only cases with at least one link appear; a case with none is absent rather than
  present with an empty array, and the client renders `—` for a missing key.

> **A contract gap M2 left open and this task closes** (012's own close-out names it): the four label
> operations answer **400** with `Problem.validation` on a blank or over-long name and declare only
> `401/403/404/409`. Nothing compares a handler's status codes to the declared set, so it is
> invisible to every gate. Add `'400': { $ref: '#/components/responses/BadRequest' }` to
> `createLabel`, `updateLabel` and `putIssueLabels` while this file is open — three lines, and this
> task is the one that owns `openapi.yaml`.

- [ ] **Step 4: Amend the backlog, the swimlanes and the issue shapes**

```yaml
# under /projects/{projectId}/backlog → get → parameters, after `unparented`
        - name: releaseId
          in: query
          description: |
            Only issues targeting this release (story B4). **A filter over the one project-wide rank
            order, not a second order** — the rows come back in the same sequence they would
            without it, with the others removed.
          schema: { type: string, format: uuid }
        - name: unscheduled
          in: query
          description: |
            Only issues with **no** target release. A flag rather than `releaseId=null`, for
            `unparented`'s reason: an absent parameter already means "don't filter", and a client
            cannot spell the difference. Sending both is a 400.
          schema: { type: boolean }
```

```yaml
# SwimlaneBy — add RELEASE and delete the sentence saying it is absent
    SwimlaneBy:
      type: string
      description: |
        Story B3's grouping axis. `RELEASE` groups by target release, with everything unscheduled in
        the `None` lane — ~~absent until M3, because a grouping axis whose data does not exist yields
        one lane called "None"~~ → **landed in M3, which is when that data exists.**
      enum: [NONE, EPIC, ASSIGNEE, LABEL, PRIORITY, RELEASE]
```

On `Issue`, `IssueSummary` and `BacklogRow`:

```yaml
        release:
          allOf: [{ $ref: '#/components/schemas/ReleaseRef' }]
          nullable: true
          description: The target release, or null. A story either has one or it does not — there is no join table (D6).
```

On `IssueInput` and `IssueUpdate`:

```yaml
        releaseId:
          type: string
          format: uuid
          nullable: true
          description: |
            The target release. Must belong to this issue's project (D13) — a release from another
            project is a 400 naming the project, never a silent null.
```

- [ ] **Step 5: Regenerate both clients**

```bash
cd ~/RiderProjects/tcms/frontend && npm run gen:api
cd ~/RiderProjects/tcms/packages/client && npm run gen:api
```

Expected: `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` both change. **Both, in
this commit** — one regenerated copy is the failure `schemaFreshness.test.ts` exists to catch.

- [ ] **Step 6: Verify every schema has its `required` array — by script, not by reading**

CLAUDE.md: *a schema-wide invariant needs a computed check.* `required` does not descend into inline
objects and reading for it is exactly how the five join tables without `company_id` survived.

```bash
cd ~/RiderProjects/tcms
node -e '
const yaml = require("./frontend/node_modules/yaml");
const doc = yaml.parse(require("fs").readFileSync("docs/specs/openapi.yaml","utf8"));
const bad = [];
const walk = (node, path) => {
  if (!node || typeof node !== "object") return;
  if (node.type === "object" && node.properties && !node.required) bad.push(path);
  for (const [k, v] of Object.entries(node.properties ?? {})) walk(v, path + "." + k);
  if (node.items) walk(node.items, path + "[]");
};
for (const [name, s] of Object.entries(doc.components.schemas)) walk(s, name);
console.log(bad.length ? bad.join("\n") : "every object schema declares required");
'
```

Expected: `every object schema declares required`. Any line naming one of this task's schemas is a
defect to fix before committing; a pre-existing one is not this task's and is left alone.

- [ ] **Step 7: Write §3.6 of the surface document**

Add after §3.5, following its shape exactly — the gate table first, then what §2's rule decides.

```markdown
### 3.6 The release verbs (013 T4) — two tools, because the gates split two ways

| Operation | Gate | Roles holding it (§A3) |
|---|---|---|
| `listReleases` · `getRelease` · `getReleaseReadiness` · `getRoadmap` | `release:read` | six of seven — **not Automation Service** |
| `listIssueTestCases` · `listCaseVerifications` | `issue:read` | Test Engineer and up, plus Viewer and Automation Service |
| `createRelease` · `updateRelease` · `deleteRelease` | `release:manage` | **Project Manager and up** |
| `publishRelease` | `release:publish` | **Project Manager and up** |
| `putIssueTestCases` | `issue:edit` | Test Engineer and up |

`release:manage` and `release:publish` are granted to the *same* three roles, so they merge into one
`tcms_write_release`. `putIssueTestCases` does **not** join them: it costs `issue:edit`, which an
`Agent ∩ Test Engineer` credential holds while holding neither release key — §2's failure exactly —
so it becomes a **`cases` arm on the existing `tcms_write_issue`**, beside `label`.

**`release:read` is the first gate on this surface that an Automation Service credential does not
hold**, and `tcms_read_release` is therefore the first read tool narrowed away from one. That is
correct rather than awkward: a CI token posts results and has no business reading a delivery plan.
```

- [ ] **Step 8: Build both TypeScript trees**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend "npm run build && npm run lint"
~/bin/tcms-remote packages "npm run build && npm run lint --workspaces"
```

Expected: both green, oxlint at 0 warnings. **The backend is expected to be red at this point** —
`ContractConventionTest` has no pairing entry for fifteen new schemas and
`ContractParameterBindingTest` none for eleven new operations. That is T3's to clear, exactly as
012 T1 left it for 012 T3/T4. Do not run `./gradlew build` here and do not "fix" it by adding
entries for records that do not exist yet.

- [ ] **Step 9: Commit**

```bash
cd ~/RiderProjects/tcms
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts \
        docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "$(cat <<'EOF'
feat(contract): releases, readiness, the roadmap and the QA link

Eleven operations and fifteen schemas for 009 M3, plus three amendments:
the backlog takes a releaseId and an unscheduled flag, SwimlaneBy gains
RELEASE, and Issue / IssueSummary / BacklogRow carry a ReleaseRef.

Readiness distinguishes "not ready, and here is the story" from "nothing
has run against this version": ReleaseTests.hasRun is on the wire and the
counts beside it are null rather than zero when it is false. An untested
version and a clean version are not the same claim, and a meter drawn
from a zero looks like the second.

Also closes a gap 012 left open and named: three label operations answer
400 with Problem.validation and declared only 401/403/404/409. Nothing
compares a handler's status codes to the declared set, so it was
invisible to every gate.

The backend suite is red until T3: fifteen schemas and eleven operations
have no pairing entry yet.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Acceptance criteria**

- [ ] Readiness responses distinguish *"not ready, and here is the story"* from *"no run has happened"*
- [ ] Every new schema declares `required`, verified by the Step 6 script and not by reading
- [ ] Both generated clients move in this commit; `npm run build` green in `frontend/` and `packages/`
- [ ] The surface document's §3.6 records why the release verbs are two tools and one new arm

---

## T2 — `V6`: release, `issue.release_id`, `test_run.release_id`

**Deliverable:** `V6__release.sql` applies to an empty database and creates `release` with forced
row-level security, `issue.release_id` and `test_run.release_id`; `SchemaConventionTest` is green;
the ERD gains §7c.
**Depends on:** T1
**Stack:** `db` · **Agent:** `data-architect` · **Skills:** `data-postgresql`, `data-migrations`

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__release.sql`
- Modify: `docs/specs/2026-07-30-tcms-erd.md` (new §7c, after §7b)

- [ ] **Step 1: Re-read the directory. The plan's table is not the authority**

```bash
ls ~/RiderProjects/tcms/backend/src/main/resources/db/migration/
```

Expected: `V1` … `V5`, so this file is **`V6`**. `out-of-order` is unset in `application.yml`, so a
lower number arriving after a higher one is **silently not applied**, and Testcontainers builds every
test database from empty and structurally cannot show you the conflict. If the listing shows a `V6`
that somebody else added, take `V7` and say so in the commit.

- [ ] **Step 2: Write the migration**

```sql
-- =============================================================================
-- V6 — releases, and the two foreign keys that point at them (plan 013, M3 of 009)
--
-- One table, two columns, one trigger and one widened CHECK. What a person can
-- do afterwards that they could not before: plan a version, point epics and
-- stories at it, and ask whether it can ship.
--
-- NOTHING IS BACKFILLED (scope D14). test_run.release_version,
-- test_run.release_sort_key and test_plan.milestone_name / milestone_date all
-- keep working exactly as they do, and no statement below reads them into this
-- table. The consequence is deliberate and is an acceptance criterion of the
-- module rather than a caveat: on the day this merges the roadmap is EMPTY and
-- readiness has no history, until somebody types a version. That is the trade
-- for having no migration to get wrong — milestone names are prose, and version
-- strings across a real instance are not as clean as they look.
--
-- A RELEASE BELONGS TO ONE PROJECT (D13), and the cost is accepted and real: a
-- company shipping 2.4 from a web project and an API project keeps two rows
-- named 2.4, gets two roadmaps, and cannot ask "is 2.4 ready?" across both. The
-- alternative pushes a cross-project visibility filter into every roadmap and
-- readiness query, where a caller may hold a grant on one of the two projects
-- and the number would be computed partly from rows they cannot see.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. THE RELEASE
-- -----------------------------------------------------------------------------

CREATE TABLE release (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    project_id   UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    version      TEXT        NOT NULL,
    -- Computed on write by api.ReleaseSortKey, which moved there from
    -- com.tcms.execution in this module because tracker may not import
    -- execution. THIS is the column the roadmap orders by, and it is a THIRD
    -- copy of that computation's output rather than a reader of the two on
    -- test_run and test_execution: a release's ordering key belongs to the
    -- release, not to each run that happens to mention its name. Those two
    -- columns still have no ORDER BY anywhere and stay in
    -- ArchitectureRulesTest.READERLESS_BY_DECISION (plan 013 D-a).
    --
    -- NOT NULL: ReleaseSortKey.of falls back to the raw string for anything it
    -- cannot parse and returns null only for a blank version, which `version`
    -- forbids. A nullable key would put every non-semver release into one
    -- unordered bucket that ORDER BY places arbitrarily.
    sort_key     TEXT        NOT NULL,
    description  TEXT,
    -- Optional. Without one the release still exists and still appears on the
    -- roadmap, in version order, with a point marker instead of a bar — which
    -- is what release-new.html's help text promises.
    target_date  DATE,
    status       TEXT        NOT NULL DEFAULT 'PLANNED'
                 CHECK (status IN ('PLANNED','RELEASED')),
    released_at  TIMESTAMPTZ,
    -- M9's burn-up baseline, and the reason this column is NOT just audit
    -- furniture (009 review R12): "scope added after the release was created"
    -- is the difference between this and issue_fact.release_assigned_at, and it
    -- is the one number in that chart that cannot be reconstructed afterwards.
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    -- A released release has a moment; a planned one does not. Stated as a
    -- constraint rather than left to the service, because the pair is what
    -- every "when did we ship 2.3" question reads and a RELEASED row with a
    -- null timestamp answers it with silence.
    CONSTRAINT release_released_at_matches_status CHECK (
        (status = 'RELEASED') = (released_at IS NOT NULL))
);
COMMENT ON TABLE release IS
    'The only planning cadence in this product (scope D6). There is no sprint table, no '
    'sprint_issue, no velocity and no burndown: a release is a version, optionally a date, and the '
    'thing both halves of this product care about — delivery asks whether the stories for 2.4 are '
    'done, QA already asks whether the runs for 2.4 passed, and release readiness is those two '
    'joined. Project-scoped per D13.';
COMMENT ON COLUMN release.sort_key IS
    'Computed on write from version by api.ReleaseSortKey — %05d.%05d.%05d.<precedence>, so 1.10 '
    'sorts after 1.9 AND 4.3.0-rc.1 before 4.3.0. Getting this wrong is silent: text collation '
    'answers about a release from three months ago with a number that looks exactly right.';

-- lower(version), so 2.4 and 2.4 differing only in a leading v or in case are
-- one release. Leading company_id like every composite index in this schema —
-- the RLS predicate is on it, SchemaConventionTest checks it mechanically.
CREATE UNIQUE INDEX uq_release_version ON release (company_id, project_id, lower(version));
CREATE INDEX ix_release_project ON release (company_id, project_id, sort_key);

-- -----------------------------------------------------------------------------
-- 2. WHAT POINTS AT A RELEASE
-- -----------------------------------------------------------------------------
--
-- A NULLABLE FK AND NO JOIN TABLE (D6). A story or a task either has a target
-- release or it does not; there is no issue_release, and there will not be one.
-- ON DELETE SET NULL rather than RESTRICT: a release is a PLAN, and deleting a
-- plan must not require deleting or reassigning the work first — the issues
-- survive with no target, which is exactly the state they were in before.
ALTER TABLE issue ADD COLUMN release_id UUID REFERENCES release(id) ON DELETE SET NULL;
CREATE INDEX ix_issue_release ON issue (company_id, project_id, release_id);

COMMENT ON COLUMN issue.release_id IS
    'The target release, or NULL. Nullable and unindexed-for-uniqueness on purpose: not all work '
    'belongs to a version, and requiring one would force a junk release per stray bug — the same '
    'argument scope §4 makes for an optional epic.';

-- THE FK FROM AN EXECUTION TABLE INTO A TRACKER TABLE, AND IT IS ALLOWED
-- (009 review R6). ArchUnit governs JAVA IMPORTS, not DDL, and scope §3's "no
-- foreign key" cost applies to POLYMORPHIC references — issue_link.target_id,
-- which points at four kinds of row — which this is not. `execution` never
-- imports `tracker`: it holds a UUID and the database keeps it honest.
--
-- It sits BESIDE release_version, which keeps working and is not deprecated:
-- CI passes a string and always will, and every existing release filter in this
-- product is an exact match on that text.
ALTER TABLE test_run ADD COLUMN release_id UUID REFERENCES release(id) ON DELETE SET NULL;
CREATE INDEX ix_test_run_release ON test_run (company_id, release_id);

COMMENT ON COLUMN test_run.release_id IS
    'Resolved from release_version by tcms_test_run_release_link() on insert, and NULL when no '
    'release row of that version exists yet — which, given D14''s no-backfill, is every run in '
    'every existing instance. THAT IS WHY READINESS DOES NOT READ THIS COLUMN: it matches '
    'execution_fact.release_version as text, so a version with three hundred executions against it '
    'is not reported as untested (plan 013 D-b). The column is here for M9''s burn-up and M4''s '
    'importer, and because adding a foreign key to this table later is a lock on the busiest table '
    'in the product.';

-- A COLUMN NOTHING WRITES IS A COLUMN THAT LIES, so it gets a trigger rather
-- than a service. tcms_issue_rank_default's argument one table over: the
-- resolution has to happen on every creation path, including the ingestion door
-- and the ones written later, and a caller that forgets does not fail — it
-- records a run against no release at all.
--
-- BEFORE INSERT ONLY. TestRun maps release_version as updatable = false, so
-- there is no update path that could change the answer, and a BEFORE UPDATE arm
-- would re-resolve on every unrelated edit for nothing.
--
-- This is NOT a backfill: it fires on rows written from here on. D14 stands.
CREATE FUNCTION tcms_test_run_release_link() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.release_id IS NULL AND NEW.release_version IS NOT NULL THEN
        SELECT r.id INTO NEW.release_id
          FROM release r
         WHERE r.company_id = NEW.company_id
           AND r.project_id = NEW.project_id
           AND lower(r.version) = lower(NEW.release_version);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER test_run_release_link
    BEFORE INSERT ON test_run
    FOR EACH ROW EXECUTE FUNCTION tcms_test_run_release_link();

-- -----------------------------------------------------------------------------
-- 3. THE SIXTH SWIMLANE AXIS
-- -----------------------------------------------------------------------------
--
-- V5 left RELEASE out deliberately — "a swimlane axis whose column is NULL for
-- every row renders one lane called None, which reads as a broken board rather
-- than as an unbuilt feature". The column now exists, so the axis does.
ALTER TABLE board DROP CONSTRAINT board_swimlane_by_check;
ALTER TABLE board ADD CONSTRAINT board_swimlane_by_check
    CHECK (swimlane_by IN ('NONE','EPIC','ASSIGNEE','LABEL','PRIORITY','RELEASE'));

-- -----------------------------------------------------------------------------
-- 4. ROW LEVEL SECURITY — one more forced policy
-- -----------------------------------------------------------------------------
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to a table's OWNER too, and
-- tcms_owner is what Flyway connects as. The predicate is byte-identical to
-- V1's, including the nullif() — SchemaConventionTest compares them and fails
-- on a divergent one, and equally on an explicit WITH CHECK: a policy with
-- USING and no WITH CHECK reuses USING as the insert check, so the read
-- boundary IS the write boundary and there is no second clause to relax later.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['release'];
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

    IF array_length(protected, 1) <> 1 THEN
        RAISE EXCEPTION 'expected 1 new company-isolated table, found %',
            array_length(protected, 1);
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 5. tcms_seed_project_defaults IS DELIBERATELY NOT TOUCHED
-- -----------------------------------------------------------------------------
--
-- V5's header explains why the whole function is restated whenever it changes:
-- CREATE OR REPLACE FUNCTION replaces the BODY, so a block left out silently
-- stops being seeded. It is not restated here because NOTHING IS ADDED TO IT. A
-- seeded release would be a version this product invented for a client — 0.1?
-- 1.0? — and D14's whole argument is that guessing a version string is worse
-- than an empty list a person fills in.
```

- [ ] **Step 3: Apply it against a throwaway Postgres 17 and read the errors**

Testcontainers builds from empty, so it will tell you the file parses. It will **not** tell you what
happens on a database that already holds `test_run` rows and boards.

```bash
~/bin/tcms-remote sync
ssh shanks 'docker run --rm -d --name v6check -e POSTGRES_PASSWORD=x postgres:17 >/dev/null; \
  for i in $(seq 1 30); do docker exec v6check pg_isready -q && break; sleep 1; done; \
  docker exec v6check psql -U postgres -q -c "CREATE TABLE t()" >/dev/null; \
  docker exec v6check psql -U postgres -c "SELECT 1 FROM pg_constraint WHERE conname = '"'"'board_swimlane_by_check'"'"'"; \
  docker rm -f v6check >/dev/null'
```

The one thing that check is for: **`board_swimlane_by_check` is the name Postgres gave V5's inline
`CHECK` and this migration drops it by that name.** If Postgres named it something else, the `ALTER
TABLE board DROP CONSTRAINT` fails on every instance. Confirm the real name against a database built
from `V1`…`V5` before trusting the literal above:

```bash
~/bin/tcms-remote backend "./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest'"
```

If the drop fails, replace the two `ALTER TABLE board` statements with a lookup:

```sql
DO $$
DECLARE c TEXT;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'board'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%swimlane_by%';
    IF c IS NULL THEN
        RAISE EXCEPTION 'board has no swimlane_by CHECK to widen';
    END IF;
    EXECUTE format('ALTER TABLE board DROP CONSTRAINT %I', c);
END $$;
ALTER TABLE board ADD CONSTRAINT board_swimlane_by_check
    CHECK (swimlane_by IN ('NONE','EPIC','ASSIGNEE','LABEL','PRIORITY','RELEASE'));
```

- [ ] **Step 4: Run the schema gate**

```bash
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest' --tests 'com.tcms.tenancy.RlsIsolationTest'"
```

Expected: green. `SchemaConventionTest` checks `company_id NOT NULL` on every company-owned table,
that every composite index leads with it, and that every policy's predicate is byte-identical to
V1's. If it names `release`, the fix is in the migration and not in the test.

> `BUILD FAILED` with `Could not write XML test results` and **zero** assertion failures is shanks'
> file-descriptor limit, not a red suite — `grep -c ' FAILED$'` excluding `> Task` lines before
> believing it, and re-run with `ulimit -n 65535`.

- [ ] **Step 5: Write ERD §7c**

Insert after §7b (Board & backlog), matching its shape — a table of tables, then the numbered
decisions.

```markdown
## 7c. Releases (`tracker.release`)

Added `V6` (plan 013, module M3 of 009).

| Table | What it is |
|---|---|
| `release` | A version this project ships: `version`, a computed `sort_key`, an optional `target_date`, a `PLANNED`/`RELEASED` status and `created_at` |

Plus two columns on tables that already existed — `issue.release_id` and `test_run.release_id`, both
nullable, both `ON DELETE SET NULL` — and one widened CHECK on `board.swimlane_by`.

**1. There is no `issue_release` join table and there will not be one** (scope D6). A story or a task
either has a target release or it does not. The board is a continuous flow ordered by fractional
rank, optionally filtered to a release; the roadmap is releases on a timeline with their epics
inside; "what's left" is the open issues in the next release. No `sprint`, no `sprint_issue`, no
velocity.

**2. `release.sort_key` is a third copy of `ReleaseSortKey`'s output, not a reader of the other two.**
`test_run.release_sort_key` and `test_execution.release_sort_key` are still ordered by nothing in
`src/main` and remain in `ArchitectureRulesTest.READERLESS_BY_DECISION`. A release's ordering key
belongs to the release rather than to each run that mentions its name — and the class that computes
it moved from `com.tcms.execution` to `com.tcms.api` in the same commit, because `tracker` may not
import `execution` and the value type has no domain concept of its own.

**3. `test_run.release_id` is a real foreign key from an `execution` table into a `tracker` one, and
it is allowed** (009 review R6). ArchUnit governs Java imports, not DDL, and §3's "no FK" cost
applies to *polymorphic* references — `issue_link.target_id` — which this is not. `execution` never
imports `tracker`: it holds a UUID, and the database keeps it honest. It is maintained by
`tcms_test_run_release_link()`, a `BEFORE INSERT` trigger resolving `release_version` to a row of
that version in the same project, so the FK is filled on every creation path including CI ingestion.
**Readiness does not read it** — a run recorded before its release row existed has a NULL forever,
which under D14's no-backfill is every run in every existing instance, so readiness matches
`execution_fact.release_version` as text and the FK is there for M9 and M4.

**4. `issue_link` finally has rows.** `V3` created it and said *"`TEST_CASE` is the target type story
H7 needs and it costs one enum member; the rows arrive with M3."* They arrive here. The entity over
it is called `VerificationLink`, **not** `IssueLink`: `documentation.IssueLinkView` is the *external*
Jira/ADO link and two types of that name one import apart is how a wrong import survives review.

**5. Nothing is backfilled** (scope D14). `test_run.release_version`, `test_run.release_sort_key` and
`test_plan.milestone_name` / `milestone_date` keep working exactly as they do. The roadmap is empty
and readiness has no history until somebody creates releases by hand — the trade for having no
migration to get wrong, and an acceptance criterion of the screens rather than a caveat about them.
```

- [ ] **Step 6: Commit**

```bash
cd ~/RiderProjects/tcms
git add backend/src/main/resources/db/migration/V6__release.sql \
        docs/specs/2026-07-30-tcms-erd.md
git commit -m "$(cat <<'EOF'
feat(db): V6 — release, and the two keys that point at it

One table, two nullable foreign keys, one trigger and one widened CHECK.
V6 and not the V5 the 009 table first reserved: M1 spent two versions and
M2 took V5, and out-of-order is unset, so a V5 written here would be
silently skipped on every instance that already ran the board migration.

test_run.release_id is a real FK from an execution table into a tracker
one and it is allowed — ArchUnit governs Java imports, not DDL, and §3's
no-FK cost is about polymorphic references. It is filled by a BEFORE
INSERT trigger resolving release_version, so the column is maintained on
every creation path including the ingestion door rather than on the ones
a service remembers.

Nothing is backfilled (D14). The roadmap is empty on merge day and the
screens say so; that is the trade for having no migration to get wrong.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Acceptance criteria**

- [ ] `release` carries `created_at` and is project-scoped; `SchemaConventionTest` green
- [ ] `test_run.release_version` and `test_plan.milestone_name` are untouched and still work
- [ ] **No backfill statement exists in the migration** — `grep -c "INSERT INTO release" V6__release.sql` is `0`
- [ ] The `board_swimlane_by_check` drop was verified against a database built from `V1`…`V5`, not assumed

---

## T3 — `tracker.release`: CRUD, readiness, roadmap, the QA link

**Deliverable:** Every operation T1 declared answers over HTTP. A release with a done story whose
linked test case failed reads **not ready** and names that story; a release with no run answers
`NOT_TESTED` with null counts; `1.10` sorts after `1.9`. `./gradlew build` on shanks is green apart
from the six inherited `FlakeScoreTest` failures.
**Depends on:** T2
**Stack:** `be` · **Agent:** `be-java` · **Skills:** `be-java-database-code`, `be-java-unit-testing`,
`be-java-code-review`

**Files:** the thirty listed under [File structure](#file-structure), plus
`backend/src/test/java/com/tcms/tracker/release/` and edits to
`backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java` and
`backend/src/test/java/com/tcms/contract/ContractConventionTest.java`.

### Traps this task will hit, named before it starts

1. **A service reading an entity BEFORE its gate gets a DETACHED one.** `projects.writeGranted` opens
   the transaction, so a repository read outside it commits and detaches. `RankService` re-reads
   inside the gate because `EntityManager.refresh` refuses a detached entity; `ReleaseService.update`
   and `publish` must do the same. Cost 012 T4 a round trip to find.
2. **A test calling a service in-process must open the company scope itself** —
   `try (var ignored = CompanyContext.open(companyId))`. Without it every RLS read is empty, the gate
   answers *no such project*, and it reads as an authorization defect. Nine of 012 T3's tests failed
   this way.
3. **There is no `JdbcTemplate` in this codebase.** Raw SQL is
   `entityManager.createNativeQuery(...)`.
4. **A repository interface must carry `@Transactional(readOnly = true)`** — Spring Data gives a
   transaction only to the inherited CRUD methods, so a derived method otherwise runs with no company
   set and every RLS read returns zero rows, fail-closed and silent.
5. **`b0`–`be` and `bf1`/`bf2`/`bf3` are taken** as fixture-UUID prefixes. `grep` before claiming one;
   this task should take `c1`, `c2`, `c3` under a fresh prefix and confirm with
   `grep -rn "c1000000-" backend/src/test`.
6. **The two `READERLESS_BY_DECISION` entries do not come off.** D-a. Rewriting their text is this
   task's; deleting either is a red gate naming a column nobody touched.

- [ ] **Step 1: Move `ReleaseSortKey` to `api`, and prove nothing else moved**

```bash
cd ~/RiderProjects/tcms
git mv backend/src/main/java/com/tcms/execution/ReleaseSortKey.java \
       backend/src/main/java/com/tcms/api/ReleaseSortKey.java
git mv backend/src/test/java/com/tcms/execution/ReleaseSortKeyTest.java \
       backend/src/test/java/com/tcms/api/ReleaseSortKeyTest.java
```

`ReleaseSortKeyOrderingTest` stays in `com.tcms.execution` — it is an integration test over
`execution_fact`'s collation, not over the class, and it imports the class like any other caller.

Edit the moved file: `package com.tcms.api;`, `public final class ReleaseSortKey`,
`public static String of(...)`. Add to its class javadoc, above the existing text:

```java
/**
 * <h2>Why this is in {@code api} and not in {@code execution}, where it was written</h2>
 *
 * <p>009 M3 gives {@code tracker} a {@code release} table whose {@code sort_key} is this
 * computation's output, and <strong>{@code tracker → execution} is a forbidden crossing</strong> —
 * a cross-module dependency may point only at the five foundation modules. This class is a pure
 * value type with no domain concept of its own, which is the bar {@code api} sets, so it moves here
 * beside {@code AutomationKeys} and {@code PatchBody} rather than being copied or wrapped behind an
 * SPI. {@link com.tcms.execution.TestRun} and {@link com.tcms.execution.TestExecution} follow the
 * import and are otherwise unchanged.
 *
 * <p><strong>This does not give {@code test_run.release_sort_key} a reader.</strong> The class has
 * one; the two columns do not, and both stay in
 * {@code ArchitectureRulesTest.READERLESS_BY_DECISION} — a release's ordering key belongs to the
 * release, not to each run that mentions its name (plan 013 D-a).
 */
```

Fix the four import sites and run the narrow gate:

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend "./gradlew test --tests 'com.tcms.api.ReleaseSortKeyTest' --tests 'com.tcms.architecture.*'"
```

Expected: `ReleaseSortKeyTest` green, `apiDependsOnNothing` green (the class imports only
`java.util.regex`), `crossModuleDependenciesPointOnlyAtTheFoundation` green.

- [ ] **Step 2: The entity, the repository and the three views**

```java
package com.tcms.tracker.release;

import com.tcms.api.ReleaseSortKey;
import com.tcms.tenancy.CompanyFilter;
// … jakarta.persistence.*, java.time.*, java.util.UUID, org.hibernate.annotations.Filter

/**
 * A version this project ships — <strong>the only planning cadence in this product</strong>
 * (scope D6).
 *
 * <p>There is no {@code sprint} table, no velocity and no burndown. A release is a version,
 * optionally a date, and the one object both halves of this product care about: delivery asks
 * whether the stories for 2.4 are done, QA already asks whether the runs for 2.4 passed, and
 * release readiness is those two joined.
 *
 * <p><strong>{@code sortKey} is computed here and never accepted from a caller.</strong> It is the
 * column the roadmap orders by, and the failure it prevents is silent: text collation puts
 * {@code "1.10"} before {@code "1.9"}, so the query asking what the newest release did answers about
 * one from three months ago, with no error and a number that looks exactly right.
 */
@Entity
@Table(name = "release")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Release {

    @Id @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Column(name = "description")
    private String description;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReleaseStatus status;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Release() {
    }

    static Release create(UUID id, UUID companyId, UUID projectId, String version,
                          String description, LocalDate targetDate, UUID by, Instant now) {
        Release created = new Release();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.rename(version);
        created.description = description;
        created.targetDate = targetDate;
        created.status = ReleaseStatus.PLANNED;
        created.createdAt = now;
        created.createdBy = by;
        created.updatedAt = now;
        created.updatedBy = by;
        return created;
    }

    /** The one place {@code version} and {@code sortKey} change, so they cannot disagree. */
    void rename(String version) {
        this.version = version;
        this.sortKey = ReleaseSortKey.of(version);
    }

    void revise(String description, LocalDate targetDate, UUID by, Instant now) {
        this.description = description;
        this.targetDate = targetDate;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    /**
     * Idempotent (D-m): publishing an already-released release does not restamp the moment. A
     * second press of a button is not a second shipment.
     */
    void publish(UUID by, Instant now) {
        if (status == ReleaseStatus.RELEASED) {
            return;
        }
        this.status = ReleaseStatus.RELEASED;
        this.releasedAt = now;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    // getters: getId, getProjectId, getVersion, getSortKey, getDescription, getTargetDate,
    // getStatus, getReleasedAt, getCreatedAt, getUpdatedAt
}
```

`ReleaseStatus` is a two-member enum in the same package. `ReleaseRepository`:

```java
@Transactional(readOnly = true)
public interface ReleaseRepository extends JpaRepository<Release, UUID> {

    /** Version order — 2.10 after 2.5, never between 2.1 and 2.2. */
    List<Release> findByProjectIdOrderBySortKeyAsc(UUID projectId);

    /** A query-based lookup, so the company filter applies in the application layer too. */
    Optional<Release> findScopedById(UUID id);

    Optional<Release> findByProjectIdAndVersionIgnoreCase(UUID projectId, String version);
}
```

- [ ] **Step 3: `ReleaseService` — CRUD and publish**

Copy `LabelService`'s shape exactly: a sealed `Outcome` inside a `GateResult`, an `onRelease` helper
that resolves the row before gating (so a caller whose release does not exist is told 404 rather
than a 403 naming a project the server invented), and `CompanyContext.require()` for the company.

```java
public sealed interface Outcome {
    record Invalid(Map<String, List<String>> errors) implements Outcome { }
    record Duplicate(String version) implements Outcome { }
    record Written(ReleaseView view) implements Outcome { }
    record Deleted() implements Outcome { }
}
```

Gates: `list` and `get` on `projects.readGranted(userId, projectId, "release:read", …)`;
`create`/`update`/`delete` on `writeGranted(… "release:manage" …)`; `publish` on
`writeGranted(… "release:publish" …)`.

> **`readGranted`, not `readVisible`.** The board's configuration read uses visibility alone because
> `board:read` is held by all seven roles and gating would hide nothing. `release:read` is **not** —
> Automation Service does not hold it — so this is the first read on this surface where the two
> differ, and using `readVisible` would serve a delivery plan to a CI token.

Validation, all as `Outcome.Invalid` with a sentence per field:
`version` blank → *"A release needs a version — the string you ship, like 2.4."*; longer than 40 →
*"A version is at most 40 characters."*; `targetDate` is accepted freely, including in the past (a
release that slipped is a real state).

`Duplicate` → 409 with *"This project already has a release called 2.4. Versions are matched without
regard to case, so two that differ only in capitals would be one release twice."*

- [ ] **Step 4: `ReleaseFacts` — the one file that names another module's tables**

```java
package com.tcms.tracker.release;

/**
 * <strong>The one place in {@code tracker} that names an {@code execution}, {@code repository} or
 * {@code reporting} table</strong> — {@code repository.ExecutionFacts} and {@code execution.CaseFacts}
 * are the pattern, and the rule is theirs: each module reads rows it holds ids into through exactly
 * one file, so a reviewer can find every crossing by opening one class per module.
 *
 * <p>Three foreign tables, and each is read for a different question:
 *
 * <ul>
 *   <li>{@code test_case} — does this case exist, in this project, and what is its key and title.
 *       The write path validates against it and the read path renders from it.</li>
 *   <li>{@code case_health} — the latest result a case has <em>anywhere</em>, for a story's own
 *       panel (H7). A read model {@code repository} maintains on a tick.</li>
 *   <li>{@code execution_fact} — the latest result per case <em>within one release version</em>,
 *       which is readiness. An append-only fact log {@code reporting} refreshes on a tick.</li>
 * </ul>
 *
 * <h2>Why a request-path read here is not the coupling {@code ExecutionFacts} refuses</h2>
 *
 * <p>That file's header warns against <em>synchronous request-path coupling</em> to another module,
 * and readiness is exactly a request-path read. The difference is what is on the other side: both
 * tables here are <strong>read models refreshed on a tick</strong>, not live transactional tables,
 * so nothing this reads can block a write anywhere. What {@code ExecutionFacts} actually refuses is
 * a convenience method that moves ROWS across the seam for the caller to loop over; every method
 * below returns an aggregate or a per-case verdict computed in one statement, which is the shape
 * that file recommends. <strong>Do not add a "just fetch these cases" method.</strong>
 *
 * <h2>Native SQL, and no entity for another module's table</h2>
 *
 * <p>{@code CaseFacts}' argument, verbatim: a second JPA entity over a table another module owns is
 * one table with two sets of column expectations for {@code ddl-auto: validate}, and two places a
 * mapping can drift. RLS still covers every statement — it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager} and one company's
 * executions are unreachable from another's session regardless of what this asks for.
 */
@Component
class ReleaseFacts {
```

The readiness statement — the one piece of SQL worth writing out, because it is where the module's
headline claim is either true or silently wrong:

```java
    /**
     * The latest result per case for one version, from {@code execution_fact}.
     *
     * <p><strong>Matched on {@code release_version} as text, never on {@code test_run.release_id}</strong>
     * (plan 013 D-b). D14 backfills nothing, so every run in every existing instance has a NULL FK,
     * and filtering on it would report a version with three hundred executions against it as
     * untested — the exact shape of silent wrong answer this codebase legislates against.
     *
     * <p><strong>The LATEST result, not the best one.</strong> {@code DISTINCT ON (case_id)} ordered
     * by {@code executed_at DESC} — a case that failed and was not re-run is a failure, and taking
     * the best result would let a release pass on a run somebody re-ran until it went green.
     * {@code execution_fact_case_idx} is {@code (company_id, case_id, executed_at DESC)}, so this is
     * an index scan.
     */
    private static final String LATEST_PER_CASE = """
            SELECT latest.case_id, latest.status_kind, latest.counts_as_pass,
                   latest.executed_at, latest.run_id
              FROM (
                    SELECT DISTINCT ON (f.case_id)
                           f.case_id, f.status_kind, f.counts_as_pass, f.executed_at, f.run_id
                      FROM execution_fact f
                     WHERE f.project_id = :projectId
                       AND f.release_version = :version
                       AND f.executed_at IS NOT NULL
                       AND f.case_id IN (:caseIds)
                     ORDER BY f.case_id, f.executed_at DESC
                   ) latest
            """;
```

`caseIds` comes from `VerificationLinkRepository` — `tracker`'s own table — so the statement never
joins across the seam, it filters by a set this module already holds. **An empty `caseIds` short-
circuits in Java rather than reaching `IN ()`, which is a syntax error in Postgres.**

Two more statements in the same file: `titlesOf(projectId, caseIds)` over `test_case`
(`id, key, title`, `deleted_at IS NULL` if the column exists — check it), and
`lastResultsOf(caseIds)` over `case_health` (`last_status_kind, last_executed_at,
last_release_version`).

- [ ] **Step 5: `ReadinessService` — the verdict**

```java
    /**
     * D-d's three members, in the order that makes the module's own acceptance criterion true.
     *
     * <p>A release with no run answers {@code NOT_TESTED} and its test counts are <strong>null,
     * not zero</strong>: an untested version and a clean version are not the same claim, and a
     * meter drawn from a zero looks like the second.
     */
    private ReleaseVerdict verdictOf(ReleaseStories stories, ReleaseTests tests) {
        boolean storiesDone = stories.done() == stories.total();
        if (!tests.hasRun()) {
            return storiesDone ? ReleaseVerdict.NOT_TESTED : ReleaseVerdict.NOT_READY;
        }
        if (!storiesDone || tests.failed() > 0 || tests.blocked() > 0 || tests.untested() > 0) {
            return ReleaseVerdict.NOT_READY;
        }
        return ReleaseVerdict.READY;
    }
```

The story arm reads `issue`, `issue_type` and `issue_status` **live** (D-f) — counted issues are
those whose type has `counts_toward_completion`, so Spikes are in neither half and
`excludedSpikes` says how many. The scope count is the one `issue_fact` read:

```sql
SELECT count(*) FROM issue_fact f
 WHERE f.release_id = :releaseId
   AND f.deleted_at IS NULL
   AND f.counts_toward_completion
   AND f.release_assigned_at > :createdAt
```

Blockers: every counted issue in the release that is **not** in a `DONE` status, plus every one that
**is** done and has a linked case whose latest result for this version did not pass. Ordered by
status position then key, so the list reads in workflow order.

- [ ] **Step 6: `VerificationService` and the two link endpoints**

`VerificationLink` maps `issue_link` with an `@IdClass(VerificationLinkId.class)` over
`(issueId, targetType, targetId)`. `TARGET_TYPE = "TEST_CASE"` is a `String` constant, not an enum
(D-h).

`GET /issues/{key}/test-cases` — `issue:read`, through `projects.readGranted`. Joins
`ReleaseFacts.titlesOf` and `lastResultsOf`, ordered by case key.

`PUT /issues/{key}/test-cases` — `issue:edit`. Replaces the set. A case id that is not in this
project is `Outcome.Invalid` with the field `caseIds` and the sentence *"N of those cases belong to
another project, or have been deleted."* — `LabelService.applyIssueLabels`' wording, one noun over,
and a 400 rather than a 404 for its reason: the issue is reachable and the case ids are the caller's
mistake.

`GET /projects/{projectId}/case-verifications` — `issue:read`, and **no `ReleaseFacts` read at all**
(D-i): it is `issue_link` joined to `issue`, both `tracker`'s.

- [ ] **Step 7: `RoadmapService`**

Releases in `sort_key` order with their epics inside; `unscheduled` is every epic with no dates at
all **or** no release (D-o). Each release carries its verdict, which means the roadmap computes
readiness per release — acceptable, and stated: a project has tens of releases, not thousands, and
the alternative is a timeline whose bars cannot be coloured by state.

- [ ] **Step 8: Thread `releaseId` through the issue surface**

`Issue` gains `releaseId` and `targetRelease(UUID)`; `IssueService.create`/`update` validate that the
release belongs to the issue's project and answer 400 naming it otherwise. `IssueView`,
`IssueSummaryView` and `BacklogRowView` gain a `ReleaseRefView`. `BoardCardService.backlog` takes
`releaseId` and `unscheduled` and refuses both together with a 400.

`SwimlaneBy.RELEASE` gets its arm in the lane grouping: lane key is the release id, lane name is the
version, and a card with no release lands in the existing `None` lane.

- [ ] **Step 9: `IssueFactProjection` — the two columns M1 landed for this module**

The `SELECT` list currently writes `NULL, … , NULL` for `release_id` and `release_assigned_at`.
Replace with `i.release_id` and, for the timestamp:

```sql
-- in the SELECT list, beside release_id
CASE WHEN f.release_id IS NOT NULL THEN now() END AS release_assigned_at
```

and in the `ON CONFLICT DO UPDATE` set-list:

```sql
release_id = EXCLUDED.release_id,
-- FIRST pointed at its CURRENT release, which is what M9's burn-up scope line
-- measures against release.created_at. Re-projection must not restamp an
-- assignment that has not changed, and clearing the release clears the moment:
-- a null release with a timestamp would claim scope was added to nothing.
release_assigned_at = CASE
    WHEN EXCLUDED.release_id IS NULL                             THEN NULL
    WHEN issue_fact.release_id IS DISTINCT FROM EXCLUDED.release_id THEN EXCLUDED.release_assigned_at
    ELSE issue_fact.release_assigned_at
END
```

Update the class javadoc's fourth bullet: strike *"null until M3"* and record that this is M3, that
the stamp is the **projection's** clock rather than the assignment's, and that the difference is at
most one outbox tick and is why readiness reads live `issue` rows for everything else (D-f).

- [ ] **Step 10: Rewrite the two `READERLESS_BY_DECISION` entries — do not delete them**

```java
            Map.entry("TestRun.releaseSortKey",
                    "§151's column. ~~The eventual reader is §6q's latest-release view and a "
                            + "feature~~ → 013 M3 built that feature and this column is STILL "
                            + "readerless, which is the outcome worth recording: the roadmap orders "
                            + "release.sort_key, a third column on a new table, because a release's "
                            + "ordering key belongs to the release and not to each run that "
                            + "mentions its name. api.ReleaseSortKey — moved there from execution "
                            + "in the same module — now has three callers; this COLUMN has none, "
                            + "and there is still no WHERE and no ORDER BY over it in src/main"),
```

and the same correction on `TestExecution.releaseSortKey`. **009 predicts these come off the list and
they do not** (D-a); removing either makes `everyMappedColumnHasAReader` red on a column nobody
touched, which is a confusing gate to debug at the end of a long task.

- [ ] **Step 11: Pair every new record in `ContractConventionTest`**

Fifteen `RESPONSE_RECORDS` entries and any inline object in `INLINE_RECORDS` —
`ReleaseStories`, `ReleaseTests` and `ReadinessSummary` are named schemas here rather than inline
objects, so they belong to the first map. Then:

```bash
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew test --tests 'com.tcms.contract.*'"
```

- [ ] **Step 12: The tests that prove the deliverable**

`backend/src/test/java/com/tcms/tracker/release/` — extending the **public**
`TrackerEndpointTestBase`, and any class needing `mvc` also needs `@AutoConfigureMockMvc`.

```java
@Test
@DisplayName("a done story whose linked case failed makes the release not ready, and names it")
void aDoneStoryWithAFailedCaseBlocksTheRelease() throws Exception {
    // ACME-118 in a DONE status, linked to TC-311, whose newest execution_fact row for 2.4 is a
    // FAILED — and to TC-308, which passed. The failure must not be averaged away.
    mvc.perform(get("/api/v1/releases/" + release + "/readiness").with(pm))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.verdict").value("NOT_READY"))
       .andExpect(jsonPath("$.blockers[?(@.key=='ACME-118')]").exists())
       .andExpect(jsonPath("$.blockers[?(@.key=='ACME-118')].failedCaseKeys[0]").value("TC-311"))
       .andExpect(jsonPath("$.tests.failed").value(1));
}

@Test
@DisplayName("a release nothing has run against says so, and does not say zero failures")
void aReleaseWithNoRunIsNotTestedRatherThanClean() throws Exception {
    mvc.perform(get("/api/v1/releases/" + untouched + "/readiness").with(pm))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.verdict").value("NOT_TESTED"))
       .andExpect(jsonPath("$.tests.hasRun").value(false))
       // The whole point: NULL, not 0. A zero here is a meter that reads as a clean release.
       .andExpect(jsonPath("$.tests.failed").doesNotExist())
       .andExpect(jsonPath("$.tests.passed").doesNotExist());
}

@Test
@DisplayName("1.10 sorts after 1.9 in the release list, and 4.3.0-rc.1 before 4.3.0")
void versionsAreOrderedByKeyNotByText() throws Exception {
    mvc.perform(get("/api/v1/projects/" + project + "/releases").with(pm))
       .andExpect(jsonPath("$[*].version")
           .value(contains("1.9", "1.10", "4.3.0-rc.1", "4.3.0")));
}

@Test
@DisplayName("the latest result wins, not the best one")
void aFailureIsNotAveragedAwayByAnEarlierPass() {
    // TC-402 passed at T-2h and failed at T-1h, both against 2.4. Readiness reads the failure.
}

@Test
@DisplayName("a spike blocks nothing, in either half")
void spikesAreExcludedFromReadinessAsTheyAreFromCompletion() {
    // D8's set, computed the same way, so the release and the epic meter cannot disagree.
}

@Test
@DisplayName("publishing twice does not restamp releasedAt")
void publishIsIdempotent() {
}

@Test
@DisplayName("a release from another project is refused as a field error, not silently dropped")
void anIssueCannotTargetAnotherProjectsRelease() {
}
```

Plus a pure unit test for `verdictOf`'s truth table — six rows, no Spring context, in the
`TokenRateLimiterTest` style.

- [ ] **Step 13: The full gate**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew build"
```

Expected: green **apart from the six `FlakeScoreTest` failures**, which have been red on master since
before 012 T1 and are owned by no task in either plan. A seventh failure is yours. Confirm the count
before believing anything:

```bash
~/bin/tcms-remote backend "grep -rc ' FAILED$' build/reports 2>/dev/null | head"
```

- [ ] **Step 14: Commit**

Two commits are fine here and the seam is the obvious one — the move plus CRUD, then readiness and
the QA link. Stage only this task's paths.

**Acceptance criteria**

- [ ] ArchUnit and `ArchitectureRulesTest` green, **including** the two rewritten (not deleted) entries
- [ ] A release with a done story whose linked case failed reads not ready and names that story
- [ ] A release with no run reports that, with null counts rather than zero
- [ ] `1.10` sorts after `1.9`, covered by both a unit test and an endpoint test
- [ ] `ReleaseFacts` is the only file in `tracker` naming `test_case`, `case_health` or `execution_fact` — `grep -rln "execution_fact\|case_health" backend/src/main/java/com/tcms/tracker/` returns one path

---

## T4 — Release verbs on the CLI and MCP

**Deliverable:** `tcms release list|show|create|edit|publish|delete`, `tcms roadmap`,
`tcms issue cases`, `tcms issue release` and `tcms issue cases set` work against a contract double;
`tcms_read_release` and `tcms_write_release` are registered and narrowed correctly; the shipped guide
names **30** tools. `npm run build`, `lint --workspaces` and `test:run --workspaces` green in
`packages/`.

**~~Eight~~ ten CLI commands.** The pipe-list above named six `release` verbs, `roadmap` and
`issue cases`; Step 2 below then spells `issue release` and `issue cases set` as well, and both are
the CLI's *only* writer for their field — §6 records that there is no `tcms issue edit`, so without
the first a person can create a release at a terminal and never put a story into it. The eight was
the summary line being loose rather than a scope decision, and every count in this task now reads
ten. The registry holds **53** commands after this task; the surface document §6 says so.
**Depends on:** T3
**Stack:** `ts` · **Agent:** `tooling-engineer` · **Skills:** `tool-cli-builder`, `tool-mcp-builder`

**Files:**
- Modify: `packages/cli/src/reads.ts`, `packages/cli/src/writes.ts`, `packages/cli/src/writes.test.ts`,
  `packages/cli/src/writeConformance.test.ts`, and — not predicted here — `packages/cli/src/commands.ts`
  (`findCommand` tried two words and `issue cases set` is three), `packages/cli/src/guideCopies.test.ts`
  (its parse had the same ceiling) and `packages/cli/src/main.test.ts`, which is where the *read*
  tests live and therefore where the `no run yet` criterion is actually asserted
- Modify: `packages/mcp/src/reads.ts`, `packages/mcp/src/writes.ts`, `packages/mcp/src/reads.test.ts`,
  `packages/mcp/src/writes.test.ts`, `packages/mcp/src/server.test.ts`,
  `packages/mcp/src/writeConformance.test.ts`, ~~`packages/mcp/src/instructions.ts`~~ — **not
  touched, and measured rather than skipped**: the capability summary carries the facts a caller
  cannot get from a tool description, and both release facts a model could get wrong (`hasRun`
  false means null and not zero; an empty roadmap is the ordinary state) are in
  `tcms_read_release`'s own description. The file's header records that it already sacrifices whole
  sections against a 2KB ceiling as the registry grows, so a paragraph repeating a tool description
  would cost the "and here is what would let me" list, which nothing repeats
- Modify: `packages/client/src/test/fixtures.ts`, and `packages/client/src/test/shapesContract.test.ts`
  — its `UNOFFERED` map fails on `IssueInput.releaseId` until the create-side decision is recorded
- Modify: `frontend/src/features/help/sections.tsx` — **an `fe` file, edited from a `ts` task, and
  that is correct**: `guideCopies.test.ts` in *both* packages reads it and fails the **packages** job
  when it names a tool the registry does not have or states the wrong count. 012 T5 did the same
  (`a7be32cc`). It is the one crossing this rule permits, because the check that enforces it lives on
  this side.
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` (§3.6 gains its typed-input tables
  and §6 its command rows)

- [ ] **Step 1: The CLI reads**

`release list` and `roadmap` follow `backlog`'s shape in `reads.ts` exactly — `projectOf(context)`,
`out.json(rows)` under `--json`, a `table(out, …)` otherwise.

```ts
const releaseList: Command = {
  name: 'release list',
  summary: 'The project\'s releases, in version order',
  usage: 'tcms release list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const rows = await client.GET('/projects/{projectId}/releases', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(rows);

    table(out, rows, [
      { header: 'VERSION', value: (row) => show(row.version) },
      { header: 'TARGET', value: (row) => show(row.targetDate) },
      { header: 'STORIES', value: (row) => `${row.readiness.storiesDone}/${row.readiness.storiesTotal}` },
      // "no run yet" and NOT "0/0" — an untested version and a clean one are not the same
      // claim, and this is the column where a terminal would otherwise say they were.
      { header: 'TESTS', value: (row) => row.readiness.hasRun
          ? `${String(row.readiness.casesPassed)}/${String(row.readiness.linkedCases)}`
          : 'no run yet' },
      { header: 'READINESS', value: (row) =>
          row.status === 'RELEASED' ? 'Released' : VERDICT[row.readiness.verdict] },
    ]);
    out.line();
    out.line('2.10 sorts after 2.5 — versions are compared segment by segment, not as text.');
  },
};
```

`release show <version>` resolves the version to an id off the list (display keys in both
directions, §4.4) and prints readiness: the verdict, the two meters, then the blockers as a table
with a `FAILED CASES` column. `roadmap` prints releases in order with their epics indented and the
unscheduled lane last.

`issue cases <KEY>` prints the linked cases with their last result.

- [ ] **Step 2: The CLI writes**

`release create --version 2.6 [--date 2026-11-14] [--description …]` with
`write: { kind: 'create', permission: 'release:manage' }`;
`release edit <version> [--version] [--date] [--description]` with `kind: 'edit'`;
`release publish <version>` with `kind: 'state', permission: 'release:publish'`;
`release delete <version>` with **`kind: 'delete'`**, `permission: 'release:manage'` — ~~`state`~~,
which is what this step first said and is wrong: both kinds imply no header, so every assertion in
`writeConformance.test.ts` passes either way, and the kind is what `main.ts` reads to raise the
confirmation. A delete arm declared `state` loses its prompt silently, which is the one failure no
check in this tree would have reported;
`issue release <KEY> --release 2.4 | --none` with `kind: 'edit', permission: 'issue:edit'` — a
`PATCH /issues/{key}` carrying `releaseId`;
`issue cases set <KEY> --case TC-311 --case TC-308` with `kind: 'state', permission: 'issue:edit'`.

`writeConformance.test.ts` checks each write declares the right `kind` for the headers the endpoint
reads. `release create` is the only one carrying `Idempotency-Key`; `publish` is `state` and carries
none, because the endpoint declares none — and a `create` kind would send a header nothing reads.

- [ ] **Step 3: `tcms_read_release`**

Gate `release:read`. Three branches on one tool, all read-only, all the same gate — `tcms_read_board`
is the shape.

| Field | Type | Notes |
|---|---|---|
| `release` | string — a **version** | omit for the list; §3.4's rule, one noun over |
| `readiness` | boolean | with `release`: the full readiness rather than the summary |
| `roadmap` | boolean | the timeline instead of the list |

`release` resolves against `GET /projects/{id}/releases` before the request, and an unknown version
is refused **with the list** — `tcms_read_board`'s label/assignee rule, and for its reason: a version
passed through as a filter matching nothing reads to a model as *an empty release* rather than as a
mistake.

- [ ] **Step 4: `tcms_write_release` and the `cases` arm**

```ts
{
  name: 'tcms_write_release',
  title: 'Plan a version, retarget it, or mark it released',
  gate: { mode: 'any', permissions: ['release:manage', 'release:publish'] },
  arms: [
    { action: 'create', kind: 'create', permission: 'release:manage' },
    { action: 'update', kind: 'edit',   permission: 'release:manage' },
    // `delete`, NOT `state` — `annotationsFor` reads the kind for `destructiveHint` and
    // `server.ts`'s consent gate refuses when there is no channel to ask on. Every header
    // assertion passes either way, so nothing else would have noticed the prompt going.
    { action: 'delete', kind: 'delete', permission: 'release:manage', targets: ['release'] },
    // Its own permission and deliberately in the same tool: release:manage and
    // release:publish are granted to the SAME three roles (§A3), so the merge
    // cannot list an action to a caller who 403s on it — which is the whole of
    // §2's rule. Two keys, one audience.
    { action: 'publish', kind: 'state', permission: 'release:publish' },
  ],
}
```

`putIssueTestCases` becomes a **`cases` arm on `tcms_write_issue`**, beside `label`, because it costs
`issue:edit` — a credential holding `issue:edit` and neither release key is reachable
(`Agent ∩ Test Engineer`), and merging it into `tcms_write_release` is exactly §2's failure.

**A second arm arrives with it, and this plan did not predict it: `release`.** T1 amended
`IssueUpdate` with `releaseId`, and §3.6's operation table lists the eleven *new* operations and not
the amendments — so nothing here gave the MCP a way to put a story into a release, and the two
release tools would have shipped able to plan a version, read it and publish it while no arm on the
surface could target work at one. It costs `issue:edit`, so it is an arm of the same tool and by the
same rule, takes the **version**, and an explicit `null` clears it while absent is refused. It is
**not** offered on `create`: `shapesContract.test.ts`'s `UNOFFERED` carries `IssueInput.releaseId`
with the reason, which is D-l's — the product retargets from the backlog, after a story exists.

`server.test.ts` gains the assertion that an **Automation Service** credential is *not* shown
`tcms_read_release` — the first read tool narrowed away from one, and worth an explicit test rather
than a count.

- [ ] **Step 5: The guide, and the count that drifts independently of the list**

`frontend/src/features/help/sections.tsx` names every tool in prose and states the total.
`guideCopies.test.ts` checks three things: every registered tool is named, no unregistered one is,
and the prose says the right number. Two new tools → **30**.

012's own close-out found two *group* headings ("Reading — 12", "Writing — 11") contradicting lists
of 13 and 12, with every gate green, because the test pins the names and the total and not the group
counts. **Fix the group headings by hand while you are in the file** and check them by counting the
list items, not by trusting the previous number.

```bash
~/bin/tcms-remote packages "npm run test:run --workspaces"
```

- [ ] **Step 6: The gate and the commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages "npm run build && npm run lint --workspaces && npm run test:run --workspaces"
~/bin/tcms-remote packages "npm run pack:release -- --version 0.0.0 --out /tmp/rel"
~/bin/tcms-remote frontend "npm run build"
```

The last one because `sections.tsx` is a frontend file.

**Acceptance criteria**

- [ ] ~~Eight~~ **ten** CLI commands and two MCP tools (plus two new arms on `tcms_write_issue`),
      every exchange compared to `openapi.yaml` by the doubles
- [ ] `tcms release list` prints **`no run yet`**, never `0/0`, for a version nothing has run against
- [ ] An Automation Service credential is not shown `tcms_read_release`, asserted rather than assumed
- [ ] The shipped guide names 30 tools and its group headings match its own lists
- [ ] `pack:release` green

---

## T5 — Releases, the roadmap and the calendar

**Deliverable:** `/projects/:projectId/releases`, `/projects/:projectId/releases/new` and
`/projects/:projectId/releases/:releaseId` render; `?view=timeline` and `?view=calendar` replace the
two "arrives in M3" panels on the Work screen. **The empty roadmap explains why it is empty and
offers to create a release.** `npm run build`, `lint` and `test:run` green.
**Depends on:** T4
**Stack:** `fe` · **Agent:** `fe-react` · **Skills:** `fe-react-data-fetching`, `fe-react-ui-rules`,
`fe-react-component-testing`

**Files:**
- Create: `frontend/src/features/releases/{ReleaseListPage,ReleaseNewPage,ReleaseDetailPage,ReadinessPanels,RoadmapView,CalendarView}.tsx`,
  `timelineScale.ts`, `releaseBadge.ts`, and a `.test.tsx` beside each page
- Create: `frontend/src/api/queries/releases.ts`
- Modify: `frontend/src/api/queries/keys.ts`, `frontend/src/api/queries/index.ts`,
  `frontend/src/api/types.ts`, `frontend/src/lib/labels.ts`, `frontend/src/lib/devFaults.ts`,
  `frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx`,
  `frontend/src/features/tracker/WorkTablePage.tsx`

### Rules this screen set is bound by

- **One `keys` factory.** `keys.ts`, never a second file. New roots:
  `releases: (p) => ['projects', p, 'releases']`,
  `release: (id) => ['releases', id]`,
  `readiness: (id) => ['releases', id, 'readiness']`,
  `roadmap: (p) => ['projects', p, 'roadmap']`,
  `issueTestCases: (key) => ['issues', key, 'test-cases']`,
  `caseVerifications: (p) => ['projects', p, 'case-verifications']`.
  **`readiness` under `['releases', id]` is deliberate here and is the opposite of 012 D-u's board
  decision:** a release's readiness has exactly one parent and changes for the same reasons the
  release does, so prefix eviction is what you want. The board's cards were kept out of
  `['boards', id]` because a *column edit* would have evicted them; nothing edits a release without
  changing its readiness.
- **No `sx`, no CSS-in-JS, no second stylesheet.** Tailwind utilities, the `components/kit/` nine,
  and the `border-line` / `bg-wash-*` / `bg-rail` token classes — **a raw `-white/` alpha in `src/`
  fails `tokenSystem.test.ts`.**
- **Display logic in the shared layer.** `lib/labels.ts` gets `releaseVerdictLabels` and
  `releaseStatusLabels`, each `satisfies Record<Union, string>`; `features/releases/releaseBadge.ts`
  gets the one function that turns `(status, verdict)` into a badge (D-e), so the list, the detail
  page, the roadmap and the overview cannot disagree about what 2.4 is.
- **Never build your own `QueryClient` in a test** — `createTestQueryClient` is the only one with the
  caches. `drivingAFailedRead()` / `drivingAFailedWrite()` / `renderingIntoAnErrorBoundary()` take no
  arguments; the reason goes in a comment beside the call.

- [x] **Step 1: `api/types.ts` and `lib/labels.ts`**

```ts
// api/types.ts, in the tracker.release block
export type ReleaseStatus = S['ReleaseStatus'];
/**
 * The one answer to "can we ship 2.4?".
 *
 * `NOT_TESTED` is not `NOT_READY` with no failures: it means nothing has run against this version
 * at all. Read `ReleaseTests.hasRun` beside it, and never draw a meter from `passed` / `failed`
 * when it is false — they are null, and a zero drawn as a bar looks like a clean release.
 */
export type ReleaseVerdict = S['ReleaseVerdict'];
export type Release = S['Release'];
export type ReleaseSummary = S['ReleaseSummary'];
export type ReleaseRef = S['ReleaseRef'];
export type ReleaseReadiness = S['ReleaseReadiness'];
export type ReleaseBlocker = S['ReleaseBlocker'];
export type Roadmap = S['Roadmap'];
export type RoadmapRelease = S['RoadmapRelease'];
export type RoadmapEpic = S['RoadmapEpic'];
export type ReleaseInput = S['ReleaseInput'];
export type ReleaseUpdate = S['ReleaseUpdate'];
export type IssueTestCase = S['IssueTestCase'];
export type CaseVerification = S['CaseVerification'];
```

> **`swimlaneByLabels` was already done in T1 and is not yours.** The `RELEASE` member broke
> `lib/labels.ts`, `ProjectBoardView.tsx` and `BoardSettingsPage.tsx` in one `tsc -b` the moment the
> contract's enum changed — which is what `satisfies Record<Union, string>` is for, and which means
> the wording had to land in the task that landed the enum, because the tree builds at every task
> boundary. The plan put it here; T1 corrected that in place. Add `releaseVerdictLabels` below it.

```ts
/**
 * D-e — the readiness badge's vocabulary, and it diverges from `releases.html` deliberately.
 *
 * The mockup's Readiness column holds `Not ready · Planned · Planned · Released · Released` — two
 * of which are a status a person set and three a verdict the server computed, under one heading. Its
 * row for 2.5 shows 0/6 stories done and reads "Planned" while 2.4 shows 21/27 and reads "Not
 * ready", so the same badge would mean "nobody started" on one row and "somebody did, and it failed"
 * on the next. Everything else on that screen is the mockup's, including the literal `no run yet`
 * in the Tests column, which is where "says so rather than showing zero failures" is actually met.
 */
export const releaseVerdictLabels = {
  READY: 'Ready',
  NOT_READY: 'Not ready',
  NOT_TESTED: 'Not tested',
} as const satisfies Record<ReleaseVerdict, string>;
```

- [x] **Step 2: `api/queries/releases.ts`**

Nine hooks — `useReleases`, `useRelease`, `useReadiness`, `useRoadmap`, `useCreateRelease`,
`useUpdateRelease`, `useDeleteRelease`, `usePublishRelease`, `useIssueTestCases` /
`usePutIssueTestCases` / `useCaseVerifications` (the last three land here rather than in `issues.ts`
because they are the release module's QA link and the file that owns readiness owns them).

Every read carries `timeoutMs: TIMEOUTS.interactive` and an `enabled` gate on a non-empty id. Every
write invalidates `keys.releases(projectId)` **and** `keys.roadmap(projectId)` — the roadmap draws
the same rows and a create that refreshed only the list leaves the timeline a version behind.

- [x] **Step 3: The fault switch, before the screens it proves**

*Build the fault switch before the feature it proves.* `lib/devFaults.ts` gains one arm:

```ts
/**
 * `tcmsFaults.release('untested')` — answers every readiness read with `hasRun: false` and null
 * counts, which is the state no seeded instance can reach once anybody has run anything, and the
 * one the screen most easily gets wrong: a `?? 0` anywhere in the meter turns "nothing has run"
 * into "nothing failed".
 *
 * `release('empty')` answers the roadmap with `{ releases: [], unscheduled: [] }` — merge day, which
 * is an acceptance criterion of this task and otherwise reachable only on a fresh database.
 */
release(fault: ReleaseFault | null) { … }
```

Both guards stay a **literal** `import.meta.env.DEV` so Rollup drops the module — routing that check
through an imported constant ships it. The path test is **anchored**
(`/^\/releases\/[^/]+\/readiness$/`), so it cannot catch the release detail read.

- [x] **Step 4: `ReleaseListPage` — `releases.html`**

Header, `+ New release` gated on `release:manage`, `On the timeline` linking to
`?view=timeline`, then one `DataRegion` over a TanStack table: Version · Target date · Description ·
Stories · Tests · Readiness. The Tests cell:

```tsx
{row.readiness.hasRun ? (
  <Meter passed={row.readiness.casesPassed ?? 0} total={row.readiness.linkedCases} />
) : (
  // The mockup's words, and the module's acceptance criterion. NOT a 0/0 meter: an untested
  // version and a clean version are not the same claim.
  <span className="text-ink-4">no run yet</span>
)}
```

The panel foot carries `releases.html`'s sentence about `2.10`. The empty state is
`DataRegion`'s `EmptyState` with `kind="by-design"`: *"No releases yet — nothing is migrated into
releases when this module ships, so the first one is one you type."* plus the gated action.

- [x] **Step 5: `ReleaseNewPage` — `release-new.html`**

Two panels: the form (version, target date, description) and the mockup's **Not in this product**
card, verbatim — sprints, velocity and sprint burndown, a release shared between two projects. That
card is not decoration: it is where a person looking for a sprint field finds out there is not one.

Field errors come off `Problem.errors` through `describeError`; the 409 renders on the version field
as the server's sentence.

- [x] **Step 6: `ReleaseDetailPage` and `ReadinessPanels` — `release.html`**

Header with the badge and the target chip; the red banner when a blocker is **done with a failed
case** — derived, not sent:

```ts
/**
 * The mockup's red banner, and the module's headline sentence. Derived rather than a `headline`
 * string on the wire, because prose assembled server-side cannot link the issue key or the case key,
 * and both are links here.
 */
export function doneButFailing(blockers: readonly ReleaseBlocker[]) {
  return blockers.find((b) => b.status.kind === 'DONE' && b.failedCaseKeys.length > 0) ?? null;
}
```

Three tiles — Stories, Linked test cases, Scope change. The middle one renders the mockup's
*"If nothing had run"* empty state in place of the meter when `tests.hasRun` is false, with its
`Plan a run` action. The Scope change tile carries the `as of` note (D-f).

Then **What stands between here and shipping** (the blockers table, with the mockup's Verdict column
derived from `status.kind` and `failedCaseKeys`), **Epics in 2.4**, and the **Test evidence** panel —
which the same `hasRun` false branch replaces with *"No test run against 2.5"*.

`Mark released` is gated on `release:publish` and confirms first.

- [x] **Step 7: `RoadmapView` and `timelineScale.ts`**

```ts
/**
 * The roadmap's horizontal scale, and the one piece of arithmetic on that screen a reader could get
 * wrong twice — so it is a pure function with its own tests and not a component.
 *
 * A release has a target date and no start, so its bar spans `min(epic.startDate) … targetDate`. A
 * release whose epics have no start dates gets a point marker at the target instead of a zero-width
 * bar, and one with no target date gets neither: it appears in version order with its epics and no
 * bar of its own, which is what `work-timeline.html` draws for 2.10.
 */
export function spanOf(release: RoadmapRelease, window: DateWindow): Span | null
```

The view: a month scale, one row per release with its epics indented beneath, a `today` marker, and
the unscheduled lane last. **Every bar is a link** — clicking one opens that epic or that release
(`work-timeline.html`'s closing note), and every bar is therefore a real `<a>` reachable by tab, not
a div with an onClick.

**The empty roadmap is this task's acceptance criterion:**

```tsx
<EmptyState
  testId={`${TEST_ID}.Empty`}
  kind="by-design"
  icon={CalendarRange}
  title="No releases to lay out yet"
  body={
    'The roadmap draws releases in version order with their epics inside them. Nothing was '
    + 'migrated into releases when this module shipped — deliberately, because version strings '
    + 'across a real instance are not as clean as they look — so this is empty until somebody '
    + 'creates the first one, and it is not a fault in this screen.'
  }
  action={/* Create a release, gated on release:manage, linking to /releases/new */}
/>
```

- [x] **Step 8: `CalendarView` — `work-calendar.html`, D-n**

A month grid from `useIssues(projectId, filters, 'dueDate')` and `useReleases(projectId)`: due dates,
overdue in the danger tone, release targets in the release tone. Month navigation in the header, the
shared filter strip above it.

The footer names what is not drawn and which module brings it — test runs, absences (M7), timesheet
period ends (M6) — because *"coming soon" names nothing a reader can plan around*, which is the
sentence `WorkTablePage`'s own `VIEWS` entries are built on.

- [x] **Step 9: Wire the routes, the rail and the two view arms**

`router.tsx` — three routes under `/projects/:projectId/releases`. `AppShell.tsx` — a **Releases**
entry in the **Execution** group, immediately after **Work**, matching the mockup rail's order
(Overview · Work · Releases · Documents · …); ungated like every other rail item, because
`release:read` is held by every project-scoped role that renders a rail at all.

`WorkTablePage.tsx` — replace the `timeline` and `calendar` entries' `module: 'M3'` panels with the
two components, and delete the two `body` sentences that named this module as pending.

- [x] **Step 10: Tests and the gate**

Per page: renders, empty state, failed read (`drivingAFailedRead()`), and the one behaviour that
screen exists for. `ReleaseListPage` asserts `no run yet` and **not** `0/0`. `RoadmapView` asserts
the empty state names the reason and carries the action. `timelineScale.test.ts` covers the three
span shapes.

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend "npm run build && npm run lint && npm run test:run"
```

**Acceptance criteria**

- [x] The empty roadmap explains why it is empty and offers `create a release`, rather than reading as a bug
- [x] The release page shows readiness with the blocking stories named
- [x] A release with no run renders `no run yet` and the *"If nothing had run"* panel, never a zero meter
- [x] Timeline and calendar are reachable and usable from the keyboard — every bar is an `<a>`
- [x] `build`, `lint` and `test:run` green


### What T5 landed differently from this plan, and why

Recorded in place rather than appended, per this plan's own rule about living text.

1. **`doneButFailing` and `blockerVerdict` are in `releaseBadge.ts`, not `ReadinessPanels.tsx`.**
   oxlint's `react(only-export-components)` is a warning and the lint gate is zero, so a module
   exporting components may not also export a function. `features/board/cardLink.ts` exists for
   exactly this reason and the comment says so.
2. **`keys.releaseRoot` — a seventh key, not in this plan's list.** `usePutIssueTestCases`
   changes readiness **without naming a release**: the payload is a story's cases, so nothing in
   it says which release that story targets. Sweeping `['releases']` is the only correct answer
   available, and it costs a refetch of a detail screen usually not mounted.
3. **`useIssueFilterChips` was extracted into `useWorkFilter.ts`, and `month` added to its
   `CARRIED` list.** The calendar reads the same six `IssueFilters` parameters the table does, so
   a second chip list would have regrown exactly the drift 012 T8's extraction removed — and the
   first thing to drift between two such lists is *which* filters get a chip at all.
   `WorkTablePage` lost its inline copy and its `useProjectMembers` read with it, since the chips
   were that read's only consumer. `month` is in `CARRIED` because it is *which month you are
   looking at*, not which work: a clear that reset it would throw the reader back to today while
   removing a label chip.
4. **Two additions to `lib/format.ts`, which this plan's file list does not name.** `pluralNoun`
   gained a consonant-`y` → `ies` rule — the scope tile read **"4 storys"** until it did, and this
   module is the first caller to pass such a noun. And `formatDueIn` is new: `formatAge` reads a
   negative interval as *"just now"*, so the header's `target 18 Sep 2026 · in 17 days` was
   rendering as `· just now` for every release in the future.
5. **The roadmap has NO filter strip.** `work-timeline.html` draws the shared chips with *"the
   filter came with you from the board"*, and `GET /projects/{id}/roadmap` declares no filter
   parameters at all — so a strip there would be chrome that narrows nothing, which is worse than
   none: a reader who removed a chip and saw no change would learn the filter is broken. The
   calendar does carry it, because `GET /issues` does take the filter.
6. **The unscheduled lane renders two shapes, not one row of bars.** An epic with dates and no
   release gets a real bar — it has a span and just nowhere else to be drawn — while one with no
   dates at all is a link in a list under the track. That is the one thing `work-timeline.html`
   does which the payload cannot support: it draws *"Loyalty tie-in — no dates"* at a `left` and a
   `width`, both invented.
7. **`timelineScale`'s month labels are `en-US`, not the `en-GB` the rest of the app formats
   dates in.** `en-GB`'s short September is `Sept` — four characters — which breaks the mockup's
   `Jul Aug Sep` scale and the column rhythm it sets.
8. **`WorkTablePage`'s `VIEWS` table lost `module`, `body` and `existing`**, and the
   `WorkTable.NotYet.*` panel with them: all five views render now, so there is nothing left for
   those columns to say. Its test kept its place in the file and asserts the roadmap and the
   calendar render rather than a deferral panel.
---

## T6 — The overview panels, the QA link on screen, and the close-out

**Deliverable:** The overview's **Next release** panel renders real data and its two release KPI
tiles appear; the backlog groups by release and bulk-moves into one; a story lists the cases that
prove it and can link more; `test-cases.html` gains its **Verifies** column; `docs/features.md`, the
surface document and 009's status table are closed out.
**Depends on:** T5
**Stack:** `fe` · **Agent:** `fe-react` · **Skills:** `fe-react-ui-rules`, `fe-react-data-fetching`,
`fe-react-component-testing`

**Files:**
- Modify: `frontend/src/features/projects/ProjectOverviewPage.tsx`
- Modify: `frontend/src/features/board/BacklogView.tsx`
- Modify: `frontend/src/features/board/useWorkFilter.ts` and `frontend/src/api/types.ts` — the
  release chip is a `BoardFilters` parameter, and neither `BoardFilters` nor
  `useBoardFilterChips` carries `releaseId` / `unscheduled` yet (T5 note)
- Modify: `frontend/src/features/tracker/StoryPanels.tsx`; create `LinkedCasesPanel.tsx` beside it
- Modify: `frontend/src/features/cases/CaseGrid.tsx`
- Modify: `docs/features.md`, `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`,
  `docs/plans/implemented/009-project-management-expansion-modules.md`, `docs/agent-context/fe-react.md`

- [ ] **Step 1: Turn `NextReleasePanel` on — do not replace it**

M1 built it against this endpoint from the start (`ProjectOverviewPage.tsx:539`): it renders
`solo-overview.html`'s empty state with a **disabled** *Create a release* button and a `title`
explaining why. Give it `useReleases(projectId)` and `useReadiness(nextId)`:

- **No releases** → the same empty state, with the button **enabled** and linked, gated on
  `release:manage`. The copy stays word for word — it was written for this and `solo-overview.html`
  is the render it came from.
- **Releases** → `project-overview.html`'s panel: the version, the badge, the target date, two
  meters, the red note when a story is done with a failed case, and *Open release readiness*.

"Next" is the earliest `PLANNED` release by target date, falling back to the lowest `sortKey` among
those with no date. Put that rule in `features/releases/nextRelease.ts` with a test — the overview,
the calendar and any later module that asks "which release are we in" must agree, and it is two lines
that three screens would otherwise each guess at.

Then the two KPI tiles `project-overview.html` carries and M1 left out because they are a release's
numbers: **`2.4 complete`** (from readiness, with its basis named beneath) and **`To target date`**
(days, from the target date). Both render `—` with a `title` when there is no next release, rather
than disappearing — a tile that vanishes moves the three beside it.

**Two functions T5 landed that this step must reuse rather than re-derive.** The red note is
`doneButFailing`, and it lives in **`features/releases/releaseBadge.ts`**, not in
`ReadinessPanels.tsx` as this plan first said — oxlint's `react(only-export-components)` is a
warning and the lint gate is zero, so a module exporting components may not also export a function
(`features/board/cardLink.ts` exists for the same reason). `blockerVerdict` is beside it. And
**`To target date` is `lib/format.ts::formatDueIn`**, added in T5: `formatAge` reads a negative
interval as *"just now"*, so it cannot answer a future deadline at all — every future target
rendered as `target 18 Sep 2026 · just now` until that function existed.

- [ ] **Step 2: The backlog's release sections and `Move to release` (D-k, D-l)**

Inside `BacklogView.tsx`, **not a new component**:

- A release filter chip in the strip, written to the URL by `useWorkFilter`'s `setParam` — `release`
  as a version-free `releaseId`, plus an `unscheduled` chip.
- Section headers between runs of rows, **inside the existing `DataRegion` and above the `<ol>`**, so
  the list stays one sequence: a drag from one section into another is still one
  `PUT /issues/{key}/rank` against a neighbour. `2.4 · target 18 Sep` and `Unscheduled`, the
  mockup's words.
- `Move to release ▾` in `BulkBar`, beside `Set epic` and `Assign`, on `useBulkUpdateIssues` —
  **N `PATCH`es, `Promise.allSettled`, never rejecting**, because a mixed outcome is ordinary. The
  caller names the failures and keeps them selected.

The section headers must not break the `place(fromIndex, toIndex)` expression both reorder
affordances end in — `toIndex` is an index into the **flat row array**, and a header is not a row.
Assert that with a test that moves a row across a section boundary and checks the request body names
the neighbour, not the header.

- [ ] **Step 3: `LinkedCasesPanel` on the story (D-p, story H7)**

Mounted from `StoryPanels.tsx`: the linked cases with their latest result badge, and a **Link test
cases** dialog picking from the project's cases (`useCases`, searchable), writing the whole set
through `usePutIssueTestCases`. Gated on `issue:edit` — and `PermissionGate` wraps the *button*, not
the list: its denied branch renders the child inside a `<span role="none">`, which around an `<ol>`
is both invalid HTML and a tooltip over every row.

**Without this panel nothing in the product creates a `TEST_CASE` link**, and every release reads
`NOT_TESTED` forever. It is the writer for the read T3 built.

- [ ] **Step 4: The **Verifies** column on `CaseGrid` (D-i)**

A `helper.display` column, last, fed from `useCaseVerifications(projectId)` as a
`Map<caseId, IssueRef[]>`:

```tsx
helper.display({
  id: 'verifies',
  header: 'Verifies',
  cell: ({ row }) => {
    const issues = verifications.get(row.original.id);
    // `—` for both "no links" and "the second read has not landed", deliberately: a spinner in a
    // table cell moves the row, and this column is never the reason somebody opened this screen.
    if (!issues?.length) return <span className="text-ink-4">—</span>;
    return issues.map((issue) => <RouterLink key={issue.key} to={`/issues/${issue.key}`}>…</RouterLink>);
  },
})
```

Add the column id to `CaseListPage`'s `knownFields` set so the `?cols=` round-trip keeps it, and the
mockup's `Verifies a story ×` chip as a client-side filter over the same map, labelled as such.

- [ ] **Step 5: `docs/features.md`**

Strike the Phase 3 backlog line — *"Milestones & release readiness — gate view per milestone"* — with
`→ superseded 2026-09-01 (plan 013, module M3)` and what actually landed, and add a **Releases and
readiness** section beside *Boards and the Backlog*. Superseded decisions are struck through and
kept, never deleted, so the next reader does not think the old reasoning was never considered.

- [ ] **Step 6: The gate**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend "npm run build && npm run lint && npm run test:run"
~/bin/tcms-remote packages "npm run build && npm run test:run --workspaces"
~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew build"
```

All three, because this is the module's last task. `citedFiles.test.ts` in **packages** fails on a
path named in a comment anywhere — including in `frontend/src` and `backend/src` — that is not on
disk, so a stale citation written in T3 reddens the packages job here.

- [ ] **Step 7: Close out 009, per the hand-off protocol**

1. **Status table** — M3's row to `done 2026-09-0N @ <short sha>`.
2. **Built and unverified** — a `**Built and unverified:**` list under the M3 section. Long and
   specific: the gates prove it compiles, not that it works.
3. **The migration actually taken** — `V6`, which is what the table already says; confirm rather than
   correct, and say the directory was re-read.
4. **Rewrite the dependent prompts.** M9 *Depends on* M3, so its prompt gets the readiness and
   roadmap endpoints **as they landed** — the paths, `ReleaseVerdict`'s three members and why
   `NOT_TESTED` is not `NOT_READY`, that `issue_fact.release_id` and `release_assigned_at` are now
   written, that the two `release_sort_key` entries **stayed** in `READERLESS_BY_DECISION`
   against 009's prediction (D-a) — M9 is the module most likely to try removing them — and that
   **the burn-up and completion report types 009 put on M3's `be` line are M9's**, with this plan's
   reason and with `scopeAddedAfterCreation` already computed on the readiness payload. M5's
   prompt gets one line if `Issue`'s shape changed for it. And say that `SwimlaneBy` gained
   `RELEASE`, that the enum lives in **two** files, and that `lib/labels.ts` is the second — a later
   module adding a sixth axis needs to know it exists.
5. **Review table** — M3 owns no `R` number; note that R6 was implemented as written.
6. **Commit `009-project-management-expansion-modules.md` on its own**, `docs(plan): M3 done`.

Also refresh `docs/agent-context/fe-react.md` and `docs/agent-context/be-java.md` — the seams this
module added, and the known unknowns it leaves.

**Acceptance criteria**

- [ ] The overview's release panel replaces the explanatory placeholder M1 shipped, and its two release tiles appear
- [ ] `solo-overview.html` still renders correctly for a company with no clients — the panel's empty state is unchanged in copy, only its button is now live
- [ ] The test-cases column links to the stories a case verifies and is empty, not broken, when there are none
- [ ] A story can link test cases, so readiness has data to read
- [ ] `build`, `lint` and `test:run` green on all three stacks; 009's status table reads `done`

---

## Prompts — one per task

Each block is what is pasted into a fresh session. Living text: a task that lands something different
from what this plan predicted **rewrites the prompts below it before it stops**, in place, with no
changelog appended.

Every task has landed. Its prompt blocks were deleted when the plan was filed (2026-10-05); they are in git history before that commit.
