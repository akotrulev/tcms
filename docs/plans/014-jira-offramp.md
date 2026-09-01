# Jira Offramp Implementation Plan

**Date:** 2026-09-01 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Module **M4** of [009-project-management-expansion-modules.md](009-project-management-expansion-modules.md).
An admin connects their Jira (or Azure DevOps) account, previews exactly what will and will not come
across, maps every source status onto the project's own workflow, confirms, and finds `PROJ-4812`
still called `PROJ-4812` — with a report they can reopen a year after the source system is gone.

**Architecture:** A new `com.tcms.tracker.imports` sub-package. It is *inside* `tracker`, so it may
write `issue`, `issue_type`, `issue_status`, `issue_comment` and `issue_label_assignment` directly
and may call `com.tcms.project.ProjectKeys`; it may not touch `com.tcms.integration` in either
direction, so it carries **its own read client** and **its own URL guard**. The preview is a pure
read that writes nothing (D32). The run is a single all-or-nothing write transaction after a
credential-free read pass, dispatched asynchronously and polled. No credential is ever persisted.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Flyway `V7` · Postgres 17 + forced RLS ·
React 19 + TanStack Query + the render kit · `@tcms/client` + `@tcms/cli`.

**Migration taken: `V7__import.sql`.** ~~`V6`~~ → ~~`V7`~~ → the table in 009 says `V8`, on the
assumption that M5 lands first. **It has not.** `ls backend/src/main/resources/db/migration/` on
2026-09-01 shows `V1 … V6`, `V6__release.sql` being M3's, so the next free number is **`V7`** and
M5 will take `V8` when it runs. This is the third correction to that column in a month and it is
exactly what 009's *"the directory is the authority"* paragraph instructs. The status table and M5's
`**db**` line are corrected in T8.

**Plan number taken: `014`.** 009 projects `014` for M5 and `015` for M4 from the confirmed order.
M4 is running fifth-but-first-of-the-two, so it takes the next free number at start time and never
renumbers — M5 will be `015`.

**Tasks** — in the order they are done:

| # | Task | Stack · Agent | Deliverable | Depends on |
|---|---|---|---|---|
| 1 | T1 — The import contract | `contract` · `be-java` | `openapi.yaml` declares five operations and eighteen schemas, every one with its `required` array; both generated clients are regenerated in the same commit; `npm run build` green in `frontend/` and `packages/` | none |
| 2 | T2 — `V7`: `import_run`, `import_dropped_field`, `issue_comment.external_author` | `be` · `data-architect` | `V7__import.sql` applies to an empty database; both new tables carry `company_id NOT NULL` and a forced isolation policy; `SchemaConventionTest` and `SchemaCommentTest` green | T1 |
| 3 | T3 — `tracker.imports`: the source port, the Jira client, the guard, the preview | `be` · `be-java` | `POST /imports/source-projects` and `POST /imports/preview` answer over HTTP against a Jira Cloud/DC site, and a test that snapshots the whole project before and after a preview finds it byte-identical | T2 |
| 4 | T4 — The run, the report, and the stuck-run reaper | `be` · `be-java` | `POST /imports` imports a Jira project keeping `PROJ-4812` as `PROJ-4812`, `GET /imports/{id}` serves the report, and a test asserts the report's `droppedFields` equals the preview's element for element | T3 |
| 5 | T5 — The Azure DevOps source | `be` · `be-java` | `provider: AZURE_DEVOPS` previews and imports an ADO project through the same port, proven against a recorded-shape fake; no other file in `imports` changes | T4 |
| 6 | T6 — `tcms import list` and `tcms import show` | `ts` · `tooling-engineer` | Two CLI commands print a past import and its dropped-field list from a terminal; the surface document's §3.7 records that MCP deliberately gets nothing | T4 |
| 7 | T7 — The wizard: connect → preview → map → run | `fe` · `fe-react` | `/projects/:projectId/imports/new` walks the three steps, refuses to confirm while one status is unmapped, and starts a run; `tcmsFaults.importing()` drives every failure path | T6 |
| 8 | T8 — The report screen, the imports list, and the close-out | `fe` · `fe-react` | `/projects/:projectId/imports` and `/projects/:projectId/imports/:importRunId` render `settings-import.html` and `import-report.html` with no call to the source system, and 009 · `features.md` · the ERD are closed out | T7 |

---

## What is not in this module

- **Nothing keeps syncing.** This is an offramp, not an integration. `settings-import.html` says so
  in its own footer and the report screen repeats it. There is no delta import, no re-run against
  the same source, no conflict resolution — a second import of the same Jira project creates a
  second set of issues with new keys, and T4 refuses it rather than letting that happen silently.
- **No custom fields (D15).** Counted and named, never stored. This module does not add a
  custom-field system and must not grow one by accident: a field the report names is a field with
  no column anywhere.
- **No sprints (review R8).** Dropped, counted, and — at the importer's option — reduced to one
  label per sprint, which is not a sprint. See D-h.
- **No permission catalogue change.** See the tax section.
- **No change to `com.tcms.integration`.** Not one file. A client mid-migration still links defects
  out to the Jira they are leaving, and `TrackerBinding` / `TrackerIdentity` keep working exactly as
  they do. T3's ArchUnit step asserts the absence rather than trusting it.
- **Test cases, suites, plans, runs, documents and time entries are untouched**, which is what
  `import-preview.html`'s *Untouched* panel promises. T3 proves it with the same snapshot the
  preview test uses.

---

## The cross-cutting tax, and the three lines of it this module does not owe

009's *What every module carries* table applies in full, with three exceptions worth stating so
nobody goes looking for work that is not there.

- **The permission catalogue does not change.** `issue:import` was seeded by **M0's `V2`**
  (`V2__pm_foundation.sql:337`) and is already in the roles spec §A3,
  `permission-matrix-summary.md` and `README.md`. There is no catalogue edit, no three-document
  commit, and `matrixCopies.test.ts` and `SeedDataTest`'s catalogue-size assertion are untouched.
  **Do not add an `import:*` key.** Every operation in this module costs `issue:import`, and D-e
  says why one key is right for both the reads and the writes.
- **`contractBodies.ts` does not change** — in either copy. Both walk the whole document
  generically, so a new path or a new schema needs no entry; the marked region is edited only when
  the *walker* changes. 012's and 013's contract commits touched neither copy for ten operations
  each, and this one adds five.
- **`lib/labels.ts` gains two entries and no enum switch.** `ImportRunStatus`
  (`RUNNING · COMPLETED · FAILED`) and `ImportProvider`
  (`JIRA_CLOUD · JIRA_DATA_CENTER · AZURE_DEVOPS`) each get a
  `satisfies Record<Union, string>` map, so a fourth provider is a compile error until it is
  labelled. There is no status *kind* to derive and no percentage to compute from either.

**Two tax lines this module owes more than usual:**

- **`citedFiles.test.ts`.** This module writes comments naming
  `backend/src/main/java/com/tcms/integration/OutboundUrlGuard.java` from inside `tracker`, and
  naming `docs/mockups/import-*.html` from inside `frontend/src`. Every such path must be on disk
  when the packages job runs. All six named in this plan exist today; check before adding a seventh.
- **`backend/build.gradle.kts` gains two `inputs.files` lines.** D-c's mirror test reads two Java
  files as *text*, and a comment-only edit to either does not change bytecode — so without the
  declaration `:test` stays `UP-TO-DATE` and the mirror rule is green by cache. The file's own
  comment already instructs *"a second such read must add its own line here"*. T3 adds the lines in
  the same commit as the test.

---

## Decisions taken in this plan

Numbered `D-a` … `D-m` in 012's and 013's convention. Each is settled here so no task re-argues it,
and each says what it refuses as well as what it takes.

### D-a — R14 closed: the connection is entered per run, held for the run, and never persisted

**This closes review R14 of plan 009.** The three candidates and the reason each was taken or
refused:

| | Verdict |
|---|---|
| **Reuse `integration.tracker.TrackerIdentity` / `TrackerCredential`** | **Refused — it does not compile.** `ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation` computes a type's module from its first package segment, so `com.tcms.tracker.imports` is `tracker`, and `tracker`'s `@ApplicationModule(allowedDependencies = {"api","tenancy","project","identity","storage"})` does not name `integration`. This is not a style objection; the rule is a hard gate with no exception list. |
| **Store it encrypted on `import_run`, the way `TrackerCredential` is** | **Refused, and this is the one 009 expected to be taken.** The cipher that does it is `com.tcms.integration.SecretCipher`, which is in `integration` and therefore just as unreachable as the row above it — so taking this option means a **second AES-GCM key user** in `tracker`. `SecretCipher`'s own class header names that outcome as the thing to avoid: *"two places a key is used, which is how a rotation misses one."* Rejecting a forbidden import by writing a second copy of the cipher is the forbidden crossing plus a key-management defect. |
| **Entered per run, carried in the request body, held for the life of the run, written nowhere** | **Taken.** `import-connect.html` already promises it in the form's own help text — *"The token is used for this import and is not stored afterwards."* — and this is the only option that makes that sentence true. |

**What `import_run` therefore stores:** `provider`, `site_url`, `account_email`, `source_project_key`
— the *identity* of the run, which is what the report has to name a year later — and **no secret
column at all**. The migration carries a comment saying so and why, because a column absent by
decision is indistinguishable from a column nobody thought of.

**What it costs, stated rather than discovered:**

1. **The token is sent twice** — once with `POST /imports/preview`, once with `POST /imports` — and
   the browser holds it in component state between the two. It is never in a URL, never in a query
   parameter, never in `localStorage` and never in a query key (D-i is the rule that keeps it out of
   the cache).
2. **A browser reload mid-wizard means re-typing it.** T7's connect step says so before the token
   field, in the mockup's own register.
3. **A server restart mid-run cannot resume it.** The run is all-or-nothing (D-b), so nothing is
   half-written; the reaper in T4 moves the stranded row to `FAILED` with a sentence, and the report
   says the project was not changed. `ReportJobRunner.failStuckJobs` is the precedent and the
   argument is the same one: a row stuck `RUNNING` for ever is a spinner with no end and no error.

### D-b — The read pass is credential-carrying and transaction-free; the write pass is one transaction and all-or-nothing

Two passes, in this order, and neither is the other's shape:

1. **Read.** Page the source system into an in-memory `SourceProjectSnapshot`. No database
   transaction is open. This takes minutes for a real project and is where every timeout,
   401, 429 and mid-flight disconnection happens.
2. **Write.** One `TransactionTemplate` block: allocate keys, insert types, statuses, transitions,
   issues, comments, labels, releases, record the run and its dropped-field rows, then
   `outbox.recordCoalesced(OutboxEvents.ISSUES_CHANGED)` **once**. Commit.

**Why not the tempting shape** — a transaction around both, or a batch-committing loop:

- A transaction spanning the read holds a Postgres connection open for minutes against a third
  party's availability. That is one pool slot per import, and the import is the operation most
  likely to be slow.
- Batch commits make a mid-import failure leave **part of a Jira project** in the customer's new
  tracker, with no way to tell which part, at the exact moment they are being asked to trust the
  migration. `import-preview.html`'s *"No data has been written"* promise is worth having on the
  other side of Confirm too: either the whole project arrived or nothing did.

**The cost, accepted:** the snapshot is bounded by heap, so there is a hard cap —
`tcms.tracker.imports.max-issues`, default **5 000**. The preview reports the source's issue count
and **refuses to continue above the cap with a message naming the number**, rather than starting a
run that dies. One coalesced outbox event rather than 1 482 is not an optimisation but the shape
`Outbox.recordCoalesced` exists for: `IssueFactOnIssuesChanged` is a whole-company recompute that
reads no payload.

### D-c — `tracker.imports` gets its own URL guard, and a test pins it byte-for-byte against `integration`'s

The importer dials a host somebody typed into a form and carries a credential there. That is
verbatim the attack `integration.OutboundUrlGuard` exists for — and `tracker` may not import it.

**A second copy of a security control is the bad option that is nonetheless the least bad one here.**
The alternatives were a sixth foundation module (`ArchitectureRules.FOUNDATION_MODULES`' javadoc
sets the bar and says *"a foundation module is a decision, not an import"*; one class does not
justify one), or moving the guard into `api` (whose charter is narrower and explicit — *a type
belongs there only if the **contract** defines it and more than one module serves it* — and a URL
guard is not a contract shape).

**So it is copied, and the copy is not allowed to drift.** `ImportUrlGuard` reproduces
`OutboundUrlGuard`'s rules between two marker comments, and
`ImportUrlGuardMirrorsTheOutboundGuardTest` reads **both source files as text** and fails when the
marked regions differ — the same instrument `contractPairing.test.ts` uses on `contractBodies.ts`'s
marked region, for the same reason. Both files are declared as test inputs in
`backend/build.gradle.kts` (see the tax section), or the rule is green by cache and the whole
argument above is decoration.

The importer **enforces**, like `TrackerClient` and unlike `WebhookPoster`: a refused destination is
never dialled. There is no warn-then-enforce phase, because there is no installed base of import
connections to break.

### D-d — Original keys survive by writing `issue_key` and allocating `key_seq` anyway (D5)

`issue` carries the key in **two columns** and M1's comment on `key_seq` is the whole mechanism:
*"THE ONLY COLUMN THAT MAY ORDER ISSUE KEYS."* An imported issue therefore gets

- `issue_key = 'PROJ-4812'` — the source's key, verbatim, which is what makes J3 true; and
- `key_seq` from **`com.tcms.project.ProjectKeys.allocate(projectId, n)`** — the project's own
  counter, one block for the whole run.

**Not the Jira number as `key_seq`.** Jira's 4 812 would move the project's counter past four
thousand unused values, or collide with the local `ACME-12` if it did not, and `key_seq` is what
orders keys for every screen in the product. The two columns are independent for exactly this case.

**The collision that can actually happen, and what is done about it.**
`uq_issue_key UNIQUE (company_id, issue_key)` is **company-wide**, not project-wide — so importing
`PROJ-*` into a company where some other project already holds a `PROJ-` prefixed key is a
constraint violation, and under D-b it would fail the whole run at the last moment. So the
**preview** computes it: it selects the source keys that already exist in the company and reports
them as `keyCollisions[]`. Non-empty ⇒ the preview says the import cannot proceed and names up to
ten of them, and `POST /imports` refuses with the same list. `import-preview.html`'s *Keys* panel
gains that state; it has no collision state today because the mockup shows the happy path.

### D-e — Every operation in this module costs `issue:import`, reads included

`issue:import` is Project Manager and above (`permission-matrix-summary.md` — Test Engineer does
**not** hold it). The reads could have been cheaper — `project:read` would let anybody who can see
the project read the report that explains why their Story Points are missing — and they are not,
for one reason: **the report names the source site, the account that connected to it and the person
who ran the migration.** That is operational detail about somebody's Jira account, and it belongs
with the people who can run one. It also keeps the family to one key, so §2 of the surface document
has nothing to split.

**The consequence on screen, and it is deliberate:** the *Imports* rail entry is **gated and
un-listed** without `issue:import`, unlike General, Workflow and Boards which are ungated because
they render read-only. The precedent is the Notifications entry (005 C2): *"the entry led to a
screen whose whole content is a refusal."* Every route in this module is behind the same gate.

### D-f — The dropped-field list is computed once, by one class, and the report reads rows

**The done-when is "matches the preview's word for word", so the two must not be two derivations.**
`DroppedFields` is one class in `tracker.imports` holding one `LinkedHashMap` of field name → reason
sentence. The preview serves what it returns. The run persists what it returns, one row per field
into `import_dropped_field` — `field_name`, `field_type`, `value_count`, `reason`, `position`. The
report reads those rows.

**Rows, not a rendered string** (009 `t25`'s acceptance criterion): a year later somebody wants to
ask *"how many Story Points values did we drop across every import"*, and a paragraph cannot answer
it. The reason sentence is stored **per row** rather than looked up at read time, because the
sentence is part of what was said at the time and editing `DroppedFields` must not retroactively
rewrite a report — the same argument `issue.issue_key`'s comment makes about composed keys.

`ImportReportMatchesPreviewTest` drives a preview and a run against one fake source and asserts the
two responses' `droppedFields` are equal element for element, including the reason strings.

### D-g — `status_mapping` and `people` are JSONB on `import_run`; only the dropped fields get a table

Both reference rows that can legitimately disappear after the run: a status is removable
(`DELETE /projects/{projectId}/workflow/statuses/{statusId}`) and a user can be deactivated. A real
FK would either cascade the report away or block the deletion — and the report's whole promise is
that it outlives the things it names. So each entry captures the **name at run time** beside the id,
in a JSONB array read whole by one screen, joined to by nothing and reported on by nothing. That is
the argument `issue.acceptance_criteria`'s column comment already makes.

The dropped fields are the exception and get a table because 009's `**db**` line names it, because
they are the artifact the module is judged on, and because they reference nothing that can vanish.

### D-h — `label-per-sprint` is offered, defaults off, and appears in the dropped list either way

Sprints are **dropped and counted** (review R8). M2 landed `issue_label` and
`issue_label_assignment`, so the mapping step additionally offers *"keep sprint membership as a
label"*, one label per distinct sprint name, one assignment per issue.

- **Default off.** A 1 482-issue project with 60 sprints turns into 60 labels nobody chose, and the
  label picker is a project-wide vocabulary.
- **`Sprint` stays in `droppedFields` whether it is on or not**, with its reason sentence adjusted
  to say what was kept in its place. A reader has to see both what was lost and what survived it;
  removing the row when the option is on would make the report claim nothing was dropped.
- **Colours are assigned round-robin over `label-1` … `label-10`** and the report says so.
  `Label.color` is a design-token name and the column refuses `#RRGGBB` (012 T9), so Jira's own
  colours cannot come across; a stored hex is invisible in the other colour scheme.
- **`POST /projects/{projectId}/labels` answers 409 on a duplicate name.** The importer creates
  labels through `LabelService` in the same transaction and treats an existing name as *already
  there*, never as a failure.

### D-i — Backlog order is imported when it is asked for, and the report says which

`issue.rank` is filled by a `BEFORE INSERT` trigger with `max(rank) + 1024` per project (012 D-k),
so a thousand imported issues get a thousand ranks in insert order — **an ordering nobody chose**.
The mapping step offers *"keep the source backlog order"* (default **on** for Jira, whose board
ranking is meaningful). When on, the importer sets `rank` explicitly and the trigger stands aside;
when off, the report's `backlogOrderArbitrary` flag is `true` and the report says the order is
insertion order rather than letting it read as imported.

### D-j — An unmatched comment author is kept as text, in a new column

`import-report.html`'s *People matched* panel promises *"An unmatched author is preserved as text on
the comment rather than reassigned to somebody who did not write it."* `issue_comment.created_by` is
a bare `UUID` with no FK and no text fallback, so there is nowhere to put
`j.arnold@old-vendor.test` today.

`V7` adds **`issue_comment.external_author TEXT NULL`**. It is set only by the importer and only
when the source author matched no `app_user` by email; `created_by` stays `NULL` in that case, so
the two are never both set and no screen has to choose. `CommentView` renders `external_author` when
present. **Not** the alternative — prefixing the body with *"Originally by …"* — because that
edits somebody's words to carry metadata, and the next person to export the comments gets the
prefix as content.

### D-k — Jira Cloud and Jira DC in T3; Azure DevOps in T5, through the same port, with its own screen affordance

009's `**be**` line names both providers, and both are built. They are **separate tasks** because one
session that writes two paging models, two field vocabularies and two user models exceeds the
budget, and the seam between them is the `ImportSource` port — which is the seam the plan wants
anyway.

**The disagreement with the mockup, recorded rather than resolved silently:**
`import-connect.html` is titled *"Import from Jira"* and its form is Jira-shaped. 009's `be` line
says two providers. Under 009's precedence rule the mockup wins on what a screen *shows* — so the
form keeps its shape, its copy and its help text — and the plan wins on what the system *does*, so a
provider selector sits above the Site field with **Jira Cloud preselected**, and the heading is
*"Import from another tracker"*. `settings-import.html`'s button keeps its *Import from Jira*
label right up until T5 lands, and T5 changes it to *Start an import*.

### D-l — MCP gets nothing; the CLI gets two read commands and no write

**MCP: nothing, and this follows from §3.3's own rule rather than from taste.** The surface document
says *a tool the role can never hold is a definition nobody's `tools/list` will ever include, so it
would be dead schema.* `Agent` is Test Engineer minus `company:read` (`V52`), and Test Engineer does
**not** hold `issue:import` — it is one of the three keys on the Test Engineer → Test Lead line
(`SeedDataTest.testEngineerToTestLeadBoundary` asserts that delta exactly, in both directions). So
every operation here is unreachable by every MCP token that can exist.

**CLI: `tcms import list` and `tcms import show <id>`, both reads.** The CLI runs on a person's
token, and the release verbs (013 T4) already established that it serves operations `Agent` cannot
reach. Two reads earn their place: *"reachable a year later when the source system is gone"* is this
module's actual promise, and a terminal is where somebody goes looking.

**No `tcms import run` and no `tcms import preview`**, and the reason is not effort:

1. The Jira token would arrive as an argument or an environment variable, which is shell history and
   a CI log. D-a's whole point is that this credential has the shortest possible life.
2. The mapping step is a **confirmation a person makes once**, having read a preview. A flag file
   passed to a command is that confirmation with the reading removed, which is the property D32
   exists to prevent.

### D-m — A second import into the same project is refused, not merged

`POST /imports` answers **409** when the project already has a `COMPLETED` import run from the same
`(provider, siteUrl, sourceProjectKey)`. There is no delta import and no reconciliation: a second
run would create a second `PROJ-4812`, which the company-wide unique index refuses anyway — this
turns that into a sentence a person can read instead of a constraint violation at minute four.
Re-importing after deleting the issues is not blocked; the refusal keys on the *run*, and a run
whose issues were deleted is the operator's own decision.

---

## What every mockup asked for that the contract had to grow

Recorded so a reader comparing the five screens against the five operations does not think something
was missed.

| Mockup element | Where it comes from |
|---|---|
| *"Connected · 4 projects visible to this account"* + the project dropdown with issue counts | `POST /imports/source-projects` — an operation 009 does not list, and the connect step cannot be built without it |
| *"1 482 issues"*, the type table, *"Also arriving: 214 labels · 4 892 comments · 11 306 history entries · 38 fix versions → releases"* | `ImportPreview.totals` |
| The dropped table, and the same table in the report | `DroppedField[]`, one shape, two responses (D-f) |
| *"Both prefixes resolve in this project afterwards"* | D-d, plus `keyCollisions[]` for the case the mockup does not draw |
| *"1 unmapped"* and the disabled Confirm | Client-side over `ImportPreview.statuses[]`; the server refuses it again (T4) |
| *"Add a status here if none of yours fits"* → `settings-workflow.html` and back | T7 keeps the wizard's state across the trip; see its step 6 |
| The four KPI tiles, *People matched*, *Status mapping applied* | `ImportRun` |
| *Download* on the report | Rendered **client-side** from the `ImportRun` already in the cache — a JSON blob and a CSV of the dropped fields. **No server artifact.** D15 says *"persisted as an `artifact`"*, and `artifact` is the `repository` module's table, which `tracker` may not write. `import_run` + `import_dropped_field` is what 009's `db` line replaced it with, and it is strictly better for the purpose: queryable rows rather than a rendered blob. Recorded here as a deliberate departure from D15's mechanism, not from its intent. |
| *Open report* from Settings → Imports | `GET /imports?projectId=…` then `GET /imports/{id}` |

---

## File structure

**Created — backend**

```
backend/src/main/resources/db/migration/V7__import.sql
backend/src/main/java/com/tcms/tracker/imports/
  package-info.java            no @ApplicationModule — a sub-package is not a module (see T3)
  ImportProvider.java          JIRA_CLOUD | JIRA_DATA_CENTER | AZURE_DEVOPS
  ImportConnection.java        record; no toString; the credential's only carrier
  ImportUrlGuard.java          D-c's mirrored copy
  ImportHttp.java              the read client's RestClient — timeouts, no redirects, a byte cap
  ImportSource.java            the port: projects(), snapshot()
  SourceProjectSnapshot.java   what one read pass produced
  SourceIssue.java  SourceComment.java  SourceStatus.java  SourceType.java
  SourceProject.java  SourcePerson.java  SourceVersion.java
  JiraImportSource.java        T3 — Cloud and DC behind one class, two auth headers
  AzureDevOpsImportSource.java T5
  ImportSources.java           picks the implementation for a provider
  DroppedFields.java           D-f's single derivation
  ImportPreviewService.java    T3 — reads only
  ImportPreviewView.java  PreviewTypeRow.java  PreviewStatusRow.java  DroppedFieldView.java
  ImportRunService.java        T4 — the write pass
  ImportWriter.java            T4 — the one transaction
  ImportRun.java  ImportRunRepository.java  ImportRunStatus.java
  ImportDroppedField.java  ImportDroppedFieldRepository.java
  ImportRunView.java  ImportRunSummaryView.java  AppliedStatusMappingView.java  ImportedPersonView.java
  ImportInput.java  ImportPreviewInput.java  StatusMappingEntry.java  ImportOptions.java
  ImportController.java        all five operations
  ImportReaper.java            T4 — a RUNNING row older than the timeout becomes FAILED
  ImportProperties.java        max-issues, run-timeout, read-timeout
```

**Created — tests**

```
backend/src/test/java/com/tcms/tracker/imports/
  ImportTestBase.java                            extends TrackerEndpointTestBase
  FakeImportSource.java                          the backend's fault switch: failAfter(n), pages, 401
  ImportPreviewWritesNothingTest.java            T3 — the byte-identical snapshot
  ImportSourceProjectsEndpointTest.java          T3
  ImportUrlGuardTest.java                        T3
  ImportUrlGuardMirrorsTheOutboundGuardTest.java T3 — reads both files as text
  JiraImportSourceTest.java                      T3 — MockRestServiceServer
  ImportRunEndpointTest.java                     T4 — PROJ-4812 survives; 409s; unmapped refusal
  ImportReportMatchesPreviewTest.java            T4 — D-f's equality
  ImportWriterTest.java                          T4 — rank, labels, external_author, one outbox event
  ImportReaperTest.java                          T4
  AzureDevOpsImportSourceTest.java               T5
backend/src/test/java/com/tcms/architecture/     T3 — one added case, no new file
```

**Created — frontend**

```
frontend/src/api/queries/imports.ts
frontend/src/features/imports/
  ImportWizardPage.tsx      T7 — owns the three steps' state, including the token
  ConnectStep.tsx           T7
  PreviewStep.tsx           T7
  MappingStep.tsx           T7
  DroppedFieldTable.tsx     T7 — one component, both screens (D-f on screen)
  ImportStepper.tsx         T7 — the mockups' four-chip rail
  ImportsListPage.tsx       T8 — settings-import.html
  ImportReportPage.tsx      T8 — import-report.html
  downloadReport.ts         T8 — the client-side Download
  *.test.tsx                beside each
```

**Modified**

```
docs/specs/openapi.yaml                                   T1
frontend/src/api/schema.d.ts  packages/client/src/schema.ts  T1 (generated)
docs/specs/2026-08-08-mcp-tool-and-command-surface.md      T1 §3.7 + §6, T6
backend/build.gradle.kts                                   T3 — two inputs.files lines
backend/src/main/java/com/tcms/tracker/IssueComment.java   T4 — externalAuthor
backend/src/main/java/com/tcms/tracker/CommentView.java    T4 — externalAuthor
docs/specs/2026-07-30-tcms-erd.md                          T2 §7d
packages/cli/src/reads.ts  commands.ts  main.ts            T6
frontend/src/lib/devFaults.ts                              T7 — the import arm
frontend/src/lib/labels.ts                                 T7 — two maps
frontend/src/api/queries/keys.ts                           T7 — the imports family
frontend/src/api/queries/index.ts                          T7
frontend/src/app/router.tsx                                T7 (new) + T8 (list, report)
frontend/src/app/AppShell.tsx                              T8 — the gated Imports rail entry
docs/features.md                                           T8
docs/plans/009-project-management-expansion-modules.md     T8 — hand-off protocol
```

---

## The 150k budget, per task

Counted as the rule asks: this plan's own section, `CLAUDE.md`, the pattern files the session must
read, the files it writes, and the build output it reads back.

| Task | Reads | Writes | Est. |
|---|---|---|---|
| T1 | plan §T1 + `openapi.yaml`'s tracker block (~1.4k of 18.4k lines) + the surface doc §2, §3.3, §6 | ~640 lines of YAML, two generated clients (machine-written), ~90 lines of surface doc | **~90k** |
| T2 | plan §T2 + `V6` whole (~300) + `V3`'s `issue_comment` block + `SchemaConventionTest` + `SchemaCommentTest` | ~260 lines of SQL + the ERD §7d | **~70k** |
| T3 | plan §T3 + `OutboundUrlGuard` + `TrackerClient` + `JiraAdapter` + `TrackerEndpointTestBase` + `WorkflowService` + `ArchitectureRulesTest`'s cross-module rule | ~24 files, ~2.1k lines Java + ~900 test | **~140k — at the ceiling.** Split seam if it overruns: `JiraImportSource` + `JiraImportSourceTest` are a second session, and the seam is the `ImportSource` port |
| T4 | plan §T4 + `IssueService.create` + `ProjectKeys` + `LabelService` + `RankService` + `ReportJobRunner` + `ReportJob` + `Outbox` | ~16 files, ~1.6k lines Java + ~1.1k test | **~135k** |
| T5 | plan §T5 + `ImportSource` + `JiraImportSource` (as the template) + `AzureDevOpsAdapter` (for the API shapes only) | ~2 files, ~500 lines Java + ~350 test | **~75k** |
| T6 | plan §T6 + `packages/cli/src/reads.ts` + `commands.ts` release block + the surface doc §6 | ~230 lines TS + tests + surface doc | **~85k** |
| T7 | plan §T7 + three mockups + `WorkflowSettingsPage` and `BoardSettingsPage` as kit templates + `devFaults.ts` + `keys.ts` | ~1.5k lines TSX + ~700 test | **~135k** |
| T8 | plan §T8 + two mockups + `ImportWizardPage` + `AppShell`'s Project rail + the three close-out documents | ~800 lines TSX + ~450 test + four markdown files | **~130k** |

---

## T1 — The import contract

**Deliverable:** `docs/specs/openapi.yaml` declares five new operations and eighteen new schemas —
every schema with its `required` array, inline nested objects included — and both generated clients
are regenerated in the same commit. `npm run build` is green in `frontend/` and in `packages/`.
**Depends on:** none
**Stack:** `contract` · **Agent:** `be-java` · **Skills:** `be-shared-api-surface-plan`

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify (generated): `frontend/src/api/schema.d.ts`, `packages/client/src/schema.ts`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` (new §3.7, and §6's noun list)

### The five operations

| Operation | `operationId` | Gate | Why it exists |
|---|---|---|---|
| `POST /imports/source-projects` | `listSourceProjects` | `issue:import` | The connect step's *"4 projects visible to this account"* and its dropdown. Reads the source only. |
| `POST /imports/preview` | `previewImport` | `issue:import` | D32's dry run. **Writes nothing.** |
| `POST /imports` | `startImport` | `issue:import` | `202` + the `ImportRun` row to poll. |
| `GET /imports/{importRunId}` | `getImportRun` | `issue:import` | The report, and the poll target. |
| `GET /imports` | `listImportRuns` | `issue:import` | `settings-import.html`'s table. `projectId` required. |

**Three of them are `POST` with a body and none of them takes the credential any other way.** A
token in a query string lands in an access log, a proxy log and the browser's history; `GET` is not
available for the first three for that reason alone, and the summary text says so.

- [ ] **Step 1: Add the five paths.** Insert after the `/issues/{key}/doc-links` block, before
      `/boards/{boardId}`, so the tracker family stays contiguous.

```yaml
  /imports/source-projects:
    post:
      tags: [tracker]
      summary: Which projects this connection can see
      operationId: listSourceProjects
      description: |
        `issue:import` — **Project Manager, Test Lead, Company Admin and Instance Admin**
        (roles spec §A3). Test Engineer does not hold it, which is why there is no MCP tool
        for anything in this family: `Agent` is Test Engineer minus `company:read`.

        `POST` rather than `GET` because the body carries a credential, and a credential in a
        query string is in the access log, the proxy log and the browser history. **Nothing is
        written** — this reads the source system and returns what it saw.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ImportConnection' }
      responses:
        '200':
          description: The projects the credential can read, with their issue counts.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/SourceProjectList' }
        '400':
          description: |
            The site URL is refused before it is dialled — not HTTPS, or it resolves into a
            private range. See `tracker/imports/ImportUrlGuard.java`.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '502':
          description: |
            The source system rejected the credential, refused the account, or did not answer.
            The `detail` is the source's own message and is shown only to the person whose
            credential it is.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /imports/preview:
    post:
      tags: [tracker]
      summary: What an import would and would not bring across
      operationId: previewImport
      description: |
        `issue:import`. D32's dry run, and story J1: counts by type, every field that will be
        dropped with how many values it holds, every source status with a suggested target, and
        the keys that would collide.

        **This operation writes nothing.** `ImportPreviewWritesNothingTest` snapshots every
        company-owned table before and after and fails on any difference — the promise
        `import-preview.html` makes in its own banner is a test, not a convention.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ImportPreviewInput' }
      responses:
        '200':
          description: The preview. `blockers` non-empty means the import cannot proceed.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ImportPreview' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '502':
          description: The source system did not answer, or answered with something else.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /imports:
    get:
      tags: [tracker]
      summary: A project's past imports
      operationId: listImportRuns
      description: |
        `issue:import`. `settings-import.html`'s table. Reports are kept indefinitely and this
        reaches them long after the source system is gone — no call leaves this server.
      parameters:
        - { name: projectId, in: query, required: true, schema: { type: string, format: uuid } }
      responses:
        '200':
          description: Newest first.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/ImportRunSummary' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    post:
      tags: [tracker]
      summary: Import the source project
      operationId: startImport
      description: |
        `issue:import`. Confirmation, after a preview and a status mapping (D32).

        **`202`, not `201`.** The read pass against the source takes minutes for a real project,
        so the run is dispatched and this returns the row to poll at
        `GET /imports/{importRunId}`. The write is one all-or-nothing transaction after the read
        completes: either the whole project arrived or nothing did, and a run interrupted by a
        restart leaves the project unchanged and the row `FAILED`.

        **Refused with `400`** when any source status has no mapping — the client disables
        Confirm for the same reason and the server does not trust it to.
        **Refused with `409`** when this project already completed an import from the same
        `(provider, siteUrl, sourceProjectKey)`: there is no delta import, and a second run would
        try to create a second `PROJ-4812`.
      parameters: [{ $ref: '#/components/parameters/IdempotencyKey' }]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ImportInput' }
      responses:
        '202':
          description: Accepted. Poll the `Location`.
          headers:
            Location: { schema: { type: string }, description: The run's canonical URL. }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ImportRun' }
        '200':
          description: A replay — the `Idempotency-Key` had already started this run.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ImportRun' }
        '400':
          description: |
            An unmapped source status, a target status that is not this project's, a source
            project whose issue count exceeds `tcms.tracker.imports.max-issues`, or a key that
            already exists in this company.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409':
          description: This project has already imported that source project.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }

  /imports/{importRunId}:
    parameters:
      - name: importRunId
        in: path
        required: true
        schema: { type: string, format: uuid }
    get:
      tags: [tracker]
      summary: One import report
      operationId: getImportRun
      description: |
        `issue:import`. `import-report.html`, and the poll target while `status` is `RUNNING`.

        The report is self-contained: the dropped-field list, the status mapping as applied and
        the people it matched are all stored on the run, with the names captured at the time. It
        answers a year later, with the source system decommissioned and its statuses long since
        renamed here.
      responses:
        '200':
          description: The run.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ImportRun' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

- [ ] **Step 2: Add the eighteen schemas** under `components/schemas`, beside the tracker block.
      **Every one carries `required`, and so does every inline nested object** — `required` does
      not descend, and without it every generated property is optional and the SPA compiles against
      a lie.

```yaml
    ImportProvider:
      type: string
      enum: [JIRA_CLOUD, JIRA_DATA_CENTER, AZURE_DEVOPS]
      description: |
        The source system. `JIRA_CLOUD` authenticates with Basic (`accountEmail` + an API token);
        the other two with a Bearer PAT, and `accountEmail` is ignored.

    ImportConnection:
      type: object
      description: |
        One connection, for one operation. **The credential is never stored** — not on
        `import_run`, not encrypted, not anywhere (plan 014 D-a, closing review R14). Reusing
        `integration.tracker`'s per-person credential is a forbidden module crossing, and
        encrypting a copy here would mean a second AES key user in a second module.
      required: [provider, siteUrl, apiToken]
      properties:
        provider: { $ref: '#/components/schemas/ImportProvider' }
        siteUrl:
          type: string
          description: |
            `https://northwind.atlassian.net`. HTTPS only, and refused before it is dialled if it
            resolves into a private range.
        accountEmail:
          type: string
          nullable: true
          description: Required for `JIRA_CLOUD`, ignored otherwise.
        apiToken:
          type: string
          description: Read-only is enough. Held for this operation and written nowhere.

    SourceProject:
      type: object
      required: [key, name, issueCount]
      properties:
        key: { type: string, description: '`PROJ` — the prefix imported issues keep (D5).' }
        name: { type: string }
        issueCount: { type: integer }

    SourceProjectList:
      type: object
      required: [projects]
      properties:
        projects:
          type: array
          items: { $ref: '#/components/schemas/SourceProject' }

    ImportPreviewInput:
      type: object
      required: [projectId, connection, sourceProjectKey]
      properties:
        projectId: { type: string, format: uuid, description: The TCMS project the issues arrive in. }
        connection: { $ref: '#/components/schemas/ImportConnection' }
        sourceProjectKey: { type: string }

    DroppedField:
      type: object
      description: |
        One source field that is **not** imported, and how many values it holds (D15). The same
        shape is served by the preview and by the report, from one derivation, so the two cannot
        disagree — `ImportReportMatchesPreviewTest` asserts they are equal element for element.
      required: [name, fieldType, valueCount, reason]
      properties:
        name: { type: string, description: '`Story Points`, `Sprint`, `Custom: "Squad"`.' }
        fieldType: { type: string, description: '`number`, `picker`, `colour` — the source''s own word.' }
        valueCount: { type: integer }
        reason:
          type: string
          description: |
            Why, in the words the preview used. Stored per row rather than looked up at read
            time: the sentence is part of what was said at the time, and editing the catalogue
            must not retroactively rewrite a report.

    PreviewTypeRow:
      type: object
      required: [sourceTypeName, targetTypeId, targetTypeName, targetLevel, countsTowardCompletion, count]
      properties:
        sourceTypeName: { type: string }
        targetTypeId: { type: string, format: uuid }
        targetTypeName: { type: string }
        targetLevel: { $ref: '#/components/schemas/IssueLevel' }
        countsTowardCompletion: { type: boolean }
        count: { type: integer }

    PreviewStatusRow:
      type: object
      description: |
        One source status, its issue count, and the target this project would suggest. `matched`
        is true when the names are equal ignoring case — the mockup's *name matched* badge.
        `suggestedStatusId` null means nothing matched, and the mapping step shows it unmapped.
      required: [sourceStatusName, count, matched]
      properties:
        sourceStatusName: { type: string }
        count: { type: integer }
        matched: { type: boolean }
        suggestedStatusId: { type: string, format: uuid, nullable: true }

    ImportPreviewTotals:
      type: object
      required: [issues, labels, comments, historyEntries, fixVersions, sprintValues]
      properties:
        issues: { type: integer }
        labels: { type: integer }
        comments: { type: integer }
        historyEntries: { type: integer }
        fixVersions: { type: integer, description: Each becomes a release. }
        sprintValues: { type: integer, description: Dropped, or kept as one label per sprint (D-h). }

    ImportBlocker:
      type: object
      description: |
        A reason the import cannot proceed, computed by the preview rather than discovered at
        minute four of the run.
      required: [kind, detail]
      properties:
        kind:
          type: string
          enum: [KEY_COLLISION, TOO_MANY_ISSUES, ALREADY_IMPORTED]
        detail: { type: string, description: The sentence the screen shows. }
        keys:
          type: array
          description: For `KEY_COLLISION` — up to ten of the offending keys.
          items: { type: string }

    ImportPreview:
      type: object
      required: [projectId, sourceProjectKey, sourceKeyPrefix, projectKeyPrefix, totals, types, statuses, droppedFields, blockers]
      properties:
        projectId: { type: string, format: uuid }
        sourceProjectKey: { type: string }
        sourceKeyPrefix: { type: string, description: '`PROJ`. Imported keys keep it (D5).' }
        projectKeyPrefix: { type: string, description: '`ACME`. Unchanged; both resolve afterwards.' }
        totals: { $ref: '#/components/schemas/ImportPreviewTotals' }
        types:
          type: array
          items: { $ref: '#/components/schemas/PreviewTypeRow' }
        statuses:
          type: array
          items: { $ref: '#/components/schemas/PreviewStatusRow' }
        droppedFields:
          type: array
          items: { $ref: '#/components/schemas/DroppedField' }
        blockers:
          type: array
          description: Empty means the mapping step may be reached.
          items: { $ref: '#/components/schemas/ImportBlocker' }

    StatusMappingEntry:
      type: object
      required: [sourceStatusName, targetStatusId]
      properties:
        sourceStatusName: { type: string }
        targetStatusId: { type: string, format: uuid }

    ImportOptions:
      type: object
      required: [labelPerSprint, preserveBacklogOrder]
      properties:
        labelPerSprint:
          type: boolean
          description: |
            One label per distinct sprint name, one assignment per issue. **Default false** — a
            project with sixty sprints becomes sixty labels nobody chose. `Sprint` stays in the
            dropped list either way, with its reason adjusted to say what was kept (D-h).
        preserveBacklogOrder:
          type: boolean
          description: |
            Set `issue.rank` from the source's own order instead of letting the insert trigger
            assign `max(rank) + 1024`. False means the report says the order is arbitrary rather
            than letting it read as imported (D-i).

    ImportInput:
      type: object
      required: [projectId, connection, sourceProjectKey, statusMapping, options]
      properties:
        projectId: { type: string, format: uuid }
        connection: { $ref: '#/components/schemas/ImportConnection' }
        sourceProjectKey: { type: string }
        statusMapping:
          type: array
          description: |
            **Every source status the preview listed, or `400`.** An unmapped status would
            silently become the default and put its issues in the wrong column, which is exactly
            what `import-mapping.html`'s disabled Confirm exists to prevent.
          items: { $ref: '#/components/schemas/StatusMappingEntry' }
        options: { $ref: '#/components/schemas/ImportOptions' }

    ImportRunStatus:
      type: string
      enum: [RUNNING, COMPLETED, FAILED]
      description: |
        Forwards only. A re-request is a new row, so the record of what was asked for and what it
        produced stays one request per row — `report_job`'s state machine, and its argument.

    AppliedStatusMapping:
      type: object
      description: |
        The mapping as it was applied, with the target's **name and kind captured at the time**.
        There is no FK: a status can be removed later, and the report has to outlive that (D-g).
      required: [sourceStatusName, targetStatusId, targetStatusName, targetStatusKind, issueCount]
      properties:
        sourceStatusName: { type: string }
        targetStatusId: { type: string, format: uuid }
        targetStatusName: { type: string }
        targetStatusKind: { $ref: '#/components/schemas/IssueStatusKind' }
        issueCount: { type: integer }

    ImportedPerson:
      type: object
      description: |
        A source author and what became of them. `matched` false means their comments carry the
        source's own text on `externalAuthor` rather than being reassigned to somebody who did
        not write them.
      required: [sourceName, matched]
      properties:
        sourceName: { type: string }
        matched: { type: boolean }
        userId: { type: string, format: uuid, nullable: true }
        userName: { type: string, nullable: true }

    ImportRunSummary:
      type: object
      required: [id, provider, siteUrl, sourceProjectKey, status, startedAt, issuesImported, statusesMapped, labelsCreated, droppedFieldCount]
      properties:
        id: { type: string, format: uuid }
        provider: { $ref: '#/components/schemas/ImportProvider' }
        siteUrl: { type: string }
        sourceProjectKey: { type: string }
        status: { $ref: '#/components/schemas/ImportRunStatus' }
        startedAt: { type: string, format: date-time }
        issuesImported: { type: integer }
        statusesMapped: { type: integer }
        labelsCreated: { type: integer }
        droppedFieldCount: { type: integer }

    ImportRun:
      type: object
      required:
        [id, projectId, provider, siteUrl, sourceProjectKey, status, startedAt, startedBy,
         issuesImported, releasesCreated, commentsImported, labelsCreated, droppedValueCount,
         labelPerSprint, backlogOrderPreserved, droppedFields, statusMapping, people]
      properties:
        id: { type: string, format: uuid }
        projectId: { type: string, format: uuid }
        provider: { $ref: '#/components/schemas/ImportProvider' }
        siteUrl: { type: string }
        accountEmail: { type: string, nullable: true }
        sourceProjectKey: { type: string }
        status: { $ref: '#/components/schemas/ImportRunStatus' }
        startedAt: { type: string, format: date-time }
        finishedAt: { type: string, format: date-time, nullable: true }
        startedBy:
          type: object
          required: [id, name]
          properties:
            id: { type: string, format: uuid }
            name: { type: string }
        issuesImported: { type: integer }
        releasesCreated: { type: integer }
        commentsImported: { type: integer }
        labelsCreated: { type: integer }
        droppedValueCount: { type: integer }
        labelPerSprint: { type: boolean }
        backlogOrderPreserved:
          type: boolean
          description: |
            False ⇒ the report says the backlog order is insertion order, chosen by nobody, rather
            than letting it read as imported (D-i).
        failureReason: { type: string, nullable: true }
        droppedFields:
          type: array
          items: { $ref: '#/components/schemas/DroppedField' }
        statusMapping:
          type: array
          items: { $ref: '#/components/schemas/AppliedStatusMapping' }
        people:
          type: array
          items: { $ref: '#/components/schemas/ImportedPerson' }
```

- [ ] **Step 3: Amend `Comment`** — the one existing schema this module changes. Add beside
      `author`:

```yaml
        externalAuthor:
          type: string
          nullable: true
          description: |
            The source system's own author string, for a comment imported from a tracker whose
            author matched no user here — `j.arnold@old-vendor.test`. Never set together with
            `author`: an unmatched author is preserved as text rather than reassigned to somebody
            who did not write it (`import-report.html`'s People panel, plan 014 D-j).
```

- [ ] **Step 4: Regenerate both clients.**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

Expected: `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` both gain the five
paths and the eighteen schemas. **Both, in this commit.** One regenerated copy is the defect
`schemaFreshness.test.ts` exists to catch.

- [ ] **Step 5: Add §3.7 to the surface document**, recording that MCP gets nothing and why —
      the argument is §3.3's own and belongs beside it:

```markdown
### 3.7 The import verbs (014 T1) — and why MCP gets none of them

**No tool, and this follows from §3.3 rather than from taste.** Every operation in plan 014 costs
`issue:import`. `Agent` is Test Engineer minus `company:read` (`V52`, §210), and `issue:import` is
one of the three keys on the **Test Engineer → Test Lead** line — `SeedDataTest`'s
`testEngineerToTestLeadBoundary` asserts that delta exactly, in both directions. So no MCP token
that can exist holds it, and a tool would be a definition nobody's `tools/list` will ever include:
§3.3's dead schema, arriving through a permission rather than through a decision.

**The CLI gets two, both reads** — see §6. It runs on a person's token, so a Test Lead reaches
them, and the release verbs (§3.6) already established that this surface serves operations `Agent`
cannot. The two writes are absent on purpose: `POST /imports` takes a Jira credential, which as a
CLI argument or an environment variable is shell history and a CI log — and the mapping step is a
confirmation a person makes **having read a preview**, which a flag file is that confirmation with
the reading removed. That is the property D32 exists to create.
```

- [ ] **Step 6: Build both stacks.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote packages npm run build
```

Expected: both green. A `required` array missed on a nested object shows up here as a property the
SPA can compile against but the server never omits — **it does not fail the build**, which is why
step 2 is written as a rule rather than checked by one.

- [ ] **Step 7: Commit.**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts \
        docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "feat(contract): the import offramp — preview, run and report"
```

---

## T2 — `V7`: `import_run`, `import_dropped_field`, `issue_comment.external_author`

**Deliverable:** `V7__import.sql` applies to an empty database and creates `import_run` and
`import_dropped_field` with `company_id NOT NULL`, forced RLS and the byte-identical isolation
predicate, plus one nullable column on `issue_comment`. `SchemaConventionTest` and
`SchemaCommentTest` are green.
**Depends on:** T1
**Stack:** `be` · **Agent:** `data-architect` · **Skills:** `data-postgresql`, `data-migrations`

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__import.sql`
- Modify: `docs/specs/2026-07-30-tcms-erd.md` (new §7d)

**Before writing the file:** `ls backend/src/main/resources/db/migration/`. The directory is the
authority on the number, not this plan's title and not 009's table — that column has been wrong
three times in a month, from three unrelated causes. If `V7` is taken by the time this runs, take
the next free number and correct this plan's header in the same commit.

- [ ] **Step 1: Write the migration.**

```sql
-- V7 — the Jira offramp (plan 009 module M4, plan 014).
--
-- Two tables, so the report outlives the migration and can be reopened a year
-- later when the source system has been decommissioned, plus one nullable
-- column on issue_comment.
--
-- WHAT IS DELIBERATELY NOT HERE: a credential column.
-- Review R14 asked whether the importer may reuse integration.tracker's
-- per-person credential; it may not — ArchitectureRulesTest computes a type's
-- module from its first package segment, so tracker.imports IS tracker, and
-- tracker does not declare integration. The obvious second option was an
-- encrypted column here, the way tracker_identity does it. That is refused
-- too, and for a reason about keys rather than about imports: the cipher is
-- integration.SecretCipher, equally unreachable, so an encrypted column here
-- means a SECOND AES key user in a second module — which SecretCipher's own
-- header names as "how a rotation misses one".
--
-- So the connection is entered per run, carried in the request body, held for
-- the life of the run and written nowhere. import-connect.html already
-- promises exactly that: "The token is used for this import and is not stored
-- afterwards." A column absent by decision is indistinguishable from one
-- nobody thought of, which is why this paragraph is here.

-- -----------------------------------------------------------------------------
-- 1. THE RUN
-- -----------------------------------------------------------------------------

CREATE TABLE import_run (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    provider                TEXT        NOT NULL
                            CHECK (provider IN ('JIRA_CLOUD','JIRA_DATA_CENTER','AZURE_DEVOPS')),
    site_url                TEXT        NOT NULL,
    account_email           TEXT,
    source_project_key      TEXT        NOT NULL,
    status                  TEXT        NOT NULL
                            CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    issues_imported         INTEGER     NOT NULL DEFAULT 0,
    releases_created        INTEGER     NOT NULL DEFAULT 0,
    comments_imported       INTEGER     NOT NULL DEFAULT 0,
    labels_created          INTEGER     NOT NULL DEFAULT 0,
    dropped_value_count     INTEGER     NOT NULL DEFAULT 0,
    label_per_sprint        BOOLEAN     NOT NULL DEFAULT false,
    backlog_order_preserved BOOLEAN     NOT NULL DEFAULT false,
    status_mapping          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    people                  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    failure_reason          TEXT,
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_by              UUID,
    finished_at             TIMESTAMPTZ,
    created_idempotency_key TEXT
);
COMMENT ON TABLE import_run IS
    'One migration off Jira or Azure DevOps, in flight or finished. It OUTLIVES ITS SOURCE: the '
    'point of storing the mapping and the people rather than re-deriving them is that a year '
    'later the Jira site is gone, its statuses were renamed here, and the report still has to '
    'say what happened. Reports are kept indefinitely (settings-import.html).';
COMMENT ON COLUMN import_run.site_url IS
    'The site, for the record. THERE IS NO TOKEN COLUMN — see this file''s header. The credential '
    'is entered per run and written nowhere, which is what import-connect.html''s "not stored '
    'afterwards" promises.';
COMMENT ON COLUMN import_run.status IS
    'RUNNING -> COMPLETED | FAILED, forwards only. A re-request is a new row, so the record of '
    'what was asked for and what it produced stays one request per row — report_job''s state '
    'machine and its argument. A row stuck RUNNING past tcms.tracker.imports.run-timeout is '
    'moved to FAILED by ImportReaper, because a spinner with no end and no error is what the '
    'ReportJobRunner reaper was added to stop.';
COMMENT ON COLUMN import_run.status_mapping IS
    'The mapping AS APPLIED, each entry carrying the target status''s name and kind captured at '
    'the time. JSONB and not a table with an FK, for the reason the people column shares: a '
    'status is removable through the workflow screen, so an FK would either cascade the report '
    'away or block the removal — and the report''s whole promise is that it outlives what it '
    'names. Read whole by one screen, joined to by nothing.';
COMMENT ON COLUMN import_run.people IS
    'Source author -> matched user, or unmatched. Same argument as status_mapping: a user can be '
    'deactivated, and the name is what the report has to be able to say.';
COMMENT ON COLUMN import_run.backlog_order_preserved IS
    'FALSE means issue.rank came from the BEFORE INSERT trigger — max(rank) + 1024 in insert '
    'order, WHICH IS AN ORDERING NOBODY CHOSE. The report says so rather than letting the '
    'backlog read as imported (plan 014 D-i).';
CREATE INDEX ix_import_run_project ON import_run (company_id, project_id, started_at DESC);
CREATE UNIQUE INDEX uq_import_run_idempotency
    ON import_run (company_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
-- One completed import per source project per project: there is no delta import
-- and no reconciliation, so a second run would try to create a second
-- PROJ-4812 and die on uq_issue_key at minute four. This turns that into a 409
-- a person can read. Partial on COMPLETED, so a FAILED run may be retried.
CREATE UNIQUE INDEX uq_import_run_completed_source
    ON import_run (company_id, project_id, provider, site_url, source_project_key)
    WHERE status = 'COMPLETED';

-- -----------------------------------------------------------------------------
-- 2. WHAT WAS DROPPED — ROWS, NOT A PARAGRAPH
-- -----------------------------------------------------------------------------

CREATE TABLE import_dropped_field (
    id            UUID    PRIMARY KEY,
    company_id    UUID    NOT NULL REFERENCES company(id),
    import_run_id UUID    NOT NULL REFERENCES import_run(id) ON DELETE CASCADE,
    field_name    TEXT    NOT NULL,
    field_type    TEXT    NOT NULL,
    value_count   INTEGER NOT NULL,
    reason        TEXT    NOT NULL,
    "position"    INTEGER NOT NULL,
    CONSTRAINT uq_import_dropped_field UNIQUE (import_run_id, field_name)
);
COMMENT ON TABLE import_dropped_field IS
    'D15''s report, one row per field type the import did not bring across. ROWS AND NOT A '
    'RENDERED STRING: a year on, the question is "how many Story Points values did we drop '
    'across every import", and a paragraph cannot answer it. THE SAME LIST THE PREVIEW SHOWED — '
    'one derivation in tracker/imports/DroppedFields.java feeds both, and '
    'ImportReportMatchesPreviewTest asserts the two responses are equal element for element.';
COMMENT ON COLUMN import_dropped_field.reason IS
    'Why, in the words the preview used, STORED rather than looked up at read time. The sentence '
    'is part of what was said at the time, so editing the catalogue must not retroactively '
    'rewrite a report — the argument issue.issue_key''s comment makes about composed keys.';
CREATE INDEX ix_import_dropped_field_run ON import_dropped_field (company_id, import_run_id, "position");

-- -----------------------------------------------------------------------------
-- 3. THE AUTHOR WHO IS NOT A USER HERE
-- -----------------------------------------------------------------------------

ALTER TABLE issue_comment ADD COLUMN external_author TEXT;
COMMENT ON COLUMN issue_comment.external_author IS
    'The source system''s own author string for an imported comment whose author matched no '
    'app_user by email — j.arnold@old-vendor.test. NEVER SET TOGETHER WITH created_by, so no '
    'screen has to choose between them. import-report.html''s People panel is the promise: an '
    'unmatched author is preserved as text rather than reassigned to somebody who did not write '
    'it. NOT a prefix on the body, which would edit somebody''s words to carry metadata and '
    'would come back out as content in the next export.';

-- -----------------------------------------------------------------------------
-- 4. ROW LEVEL SECURITY — two more forced policies
-- -----------------------------------------------------------------------------
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to a table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an
-- owner-run query reads every company's rows, which is the trap
-- docs/specs/tenancy-invariants.md names.
--
-- The predicate is byte-identical to V1's, including the nullif() —
-- SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares them
-- and fails on a divergent one, and it fails equally on an explicit WITH CHECK,
-- which is why there is none: a policy with USING and no WITH CHECK reuses
-- USING as the insert check, so the read boundary IS the write boundary.
--
-- issue_comment already carries its policy; a column added to a protected
-- table is protected by it.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['import_run', 'import_dropped_field'];
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

    IF array_length(protected, 1) <> 2 THEN
        RAISE EXCEPTION 'expected 2 new company-isolated tables, found %',
            array_length(protected, 1);
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 5. tcms_seed_project_defaults IS DELIBERATELY NOT TOUCHED
-- -----------------------------------------------------------------------------
--
-- V5's header explains why the whole function is restated whenever it changes:
-- CREATE OR REPLACE FUNCTION replaces the BODY, so a block left out of the
-- reproduction silently stops being seeded for every project created from that
-- migration onward. ProjectKeySequenceSchemaTest is the check that one went
-- missing.
--
-- Nothing is added to it here. An import is something a person starts once, not
-- a per-project default, and there is no row a new project should have.
```

- [ ] **Step 2: Run the schema suite.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew test --tests "com.tcms.tenancy.SchemaConventionTest" --tests "com.tcms.schema.SchemaCommentTest" --tests "com.tcms.tenancy.RlsIsolationTest"'
```

Expected: green. `SchemaConventionTest` is what catches a missing `company_id`, an index that does
not lead with it, and a divergent policy predicate; `SchemaCommentTest` catches a table or column
with no `COMMENT ON`.

- [ ] **Step 3: Add ERD §7d**, in the style of §7c (releases). One paragraph per table saying what
      it is for, plus the credential paragraph — the ERD is where somebody looks for *why there is
      no token column*, and finding the answer only in a migration comment is finding it late.

- [ ] **Step 4: Commit.**

```bash
git add backend/src/main/resources/db/migration/V7__import.sql docs/specs/2026-07-30-tcms-erd.md
git commit -m "feat(db): V7 — the import run, and the report that outlives its source"
```

---

## T3 — `tracker.imports`: the source port, the Jira client, the guard, the preview

**Deliverable:** `POST /imports/source-projects` and `POST /imports/preview` answer over HTTP
against a Jira Cloud or Jira DC site, and `ImportPreviewWritesNothingTest` snapshots every
company-owned table before and after a preview and finds the project byte-identical.
**Depends on:** T2
**Stack:** `be` · **Agent:** `be-java` · **Skills:** `be-java-database-code`, `be-java-unit-testing`,
`be-java-pattern-writer`

**Files:** as listed in *File structure*, everything under `tracker/imports/` except
`ImportRunService`, `ImportWriter`, `ImportReaper`, `AzureDevOpsImportSource` and the entities.

### Traps this task will hit, named before it starts

1. **`package-info.java` in `tracker/imports/` must NOT carry `@ApplicationModule`.** A
   sub-package is not a module: `ArchitectureRules.moduleOf` takes the **first** segment after
   `com.tcms`, so this package is `tracker` and inherits `tracker`'s allowed dependencies.
   Declaring a module here would make Spring Modulith treat it as a sixth feature module, and
   `ModuleStructureTest` would then demand its own declaration for a package that must not have
   one. `tracker/board/` and `tracker/release/` are the precedent — neither has a `package-info`.
2. **`integration` is unreachable and the compiler will tell you late.** `OutboundUrlGuard`,
   `SecretCipher`, `TrackerClient`, `TrackerProvider` and `TrackerUnavailableException` all look
   like exactly what this task needs and all are forbidden. The failure is not a compile error —
   it compiles fine — it is `ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation`
   at the end of a long suite. Write the copies first.
3. **Every repository interface needs `@Transactional(readOnly = true)` on the *interface*.**
   Spring Data gives a transaction only to the inherited CRUD methods, so a derived or `@Query`
   method otherwise runs with no company set and RLS returns **zero rows, fail-closed and silent**.
   This is the single most common way a new module in this repo appears to work and does not.
4. **The read client runs on a request thread.** `TrackerClient`'s header is the argument:
   a source that accepts the connection and never answers holds a Tomcat worker per viewer, and
   the JDK client's default read timeout is indefinite. Both timeouts, a response cap, and
   `DONT_FOLLOW` — a 3xx would relocate the credential past the guard.

- [ ] **Step 1: Write `ImportUrlGuard`, and the mirror test that pins it.**

`ImportUrlGuard` reproduces `integration.OutboundUrlGuard`'s verdicts and its private-range checks
verbatim, between two markers:

```java
    // ---- BEGIN MIRRORED FROM integration/OutboundUrlGuard.java ----
    //  Pinned byte-for-byte by ImportUrlGuardMirrorsTheOutboundGuardTest. Edit one, edit both.
    //  This is a SECOND COPY OF A SECURITY CONTROL and that is the bad option, taken because the
    //  two alternatives are worse: importing it is a forbidden module crossing with no exception
    //  list, and promoting it to a sixth foundation module fails the bar
    //  ArchitectureRules.FOUNDATION_MODULES sets — "a foundation module is a decision, not an
    //  import", and one class is not that decision. The mirror test is what keeps the copy from
    //  being the drift it looks like.
    ...
    // ---- END MIRRORED ----
```

The test:

```java
@Test
@DisplayName("the import guard's rules are byte-identical to integration's, or the copy has drifted")
void theMirroredRegionsAreIdentical() throws IOException {
    String outbound = region(Path.of("src/main/java/com/tcms/integration/OutboundUrlGuard.java"),
            "BEGIN MIRRORED SOURCE", "END MIRRORED SOURCE");
    String imports = region(Path.of("src/main/java/com/tcms/tracker/imports/ImportUrlGuard.java"),
            "BEGIN MIRRORED FROM", "END MIRRORED");

    assertThat(imports)
            .as("""
                tracker.imports may not import integration.OutboundUrlGuard (ArchUnit), so the \
                rules are copied. A copy of a security control that is allowed to drift is worse \
                than no copy: the private-range list gets a fix in one file and the other keeps \
                dialling 169.254.169.254. Apply the edit to both.""")
            .isEqualTo(outbound);
}
```

`OutboundUrlGuard` gains the two matching markers around its own rule block — the only change to
`integration` in this whole module, and it is two comment lines that change no behaviour.

- [ ] **Step 2: Declare both files as test inputs.** In `backend/build.gradle.kts`, inside the
      `inputs.files(...)` call, following the block comment's own instruction that *a second such
      read must add its own line here*:

```kotlin
        file("src/main/java/com/tcms/integration/OutboundUrlGuard.java"),
        file("src/main/java/com/tcms/tracker/imports/ImportUrlGuard.java"),
```

with a comment saying why two *in-source* files are declared: Gradle tracks a `Test` task's inputs
as its classpath, and a **comment-only** edit to either file changes no bytecode — so without these
lines the mirror rule is green by cache, which is the exact failure the surrounding comment was
written about.

- [ ] **Step 3: Verify the mirror test actually fails.** Delete one line from inside
      `ImportUrlGuard`'s marked region, run the test, confirm it is red, then restore it and confirm
      green.

```bash
~/bin/tcms-remote backend './gradlew test --tests "*ImportUrlGuardMirrors*"'
```

This step is not optional and it is not ceremony: §185's rules were verified by deleting the fix
they check, and the build reported success without running them.

- [ ] **Step 4: The port and its records.**

```java
/** One read pass against a source system. Nothing here writes. */
public interface ImportSource {

    /** The projects this credential can see, for the connect step's dropdown. */
    List<SourceProject> projects(ImportConnection connection);

    /**
     * Everything one source project holds, read whole.
     *
     * <p>Whole, and not streamed, for plan 014 D-b's reason: the write is one all-or-nothing
     * transaction, so it cannot begin until the read has finished. Bounded by
     * {@code tcms.tracker.imports.max-issues} — a source above the cap is refused by the
     * preview with the number in the message, rather than by a run that dies at minute four.
     *
     * @param onProgress called per page, so a long read is visible; never with a credential
     */
    SourceProjectSnapshot snapshot(ImportConnection connection, String projectKey,
                                   IntConsumer onProgress);
}
```

`SourceProjectSnapshot` is a record of `List<SourceIssue>`, `List<SourceStatus>`,
`List<SourceType>`, `List<SourceVersion>`, `List<SourcePerson>`, `Map<String,Integer> fieldValueCounts`
and `int historyEntryCount`. `SourceIssue` carries `key`, `typeName`, `statusName`, `title`,
`description`, `parentKey`, `epicKey`, `assigneeEmail`, `reporterEmail`, `labels`, `fixVersions`,
`sprintNames`, `rankPosition`, `createdAt`, `comments`.

**`ImportConnection` has no generated `toString`.** `TrackerCredential`'s header is the argument:
the realistic leak is a log line or an exception message that interpolated an object somebody
assumed was safe.

- [ ] **Step 5: `JiraImportSource`** — Cloud and Data Center behind one class, because the two
      differ in the **auth header** (`Basic base64(email:token)` vs `Bearer <pat>`) and in nothing
      else this module reads. Paging is `/rest/api/3/search/jql` with `nextPageToken`, 100 at a
      time; comments come with the issue through `fields=comment`; the changelog count comes from
      `expand=changelog`. `JiraAdapter` is the reference for the field names and **is not imported**.

- [ ] **Step 6: `FakeImportSource` — the backend's fault switch, and it lands before the importer.**
      *Build the fault switch before the feature it proves.* A source that stops answering
      mid-import cannot be triggered by using the app, so:

```java
/**
 * A source under test's control — and the only way the failures this module exists to handle can
 * be driven at all.
 *
 * <p>The frontend's half of this is {@code tcmsFaults.importing()} in {@code lib/devFaults.ts},
 * added in T7. This half is what the backend suite drives, and it lands in the same task as the
 * read client rather than in the task that writes the importer: a resilience behaviour you cannot
 * trigger is one you have not tested.
 */
public final class FakeImportSource implements ImportSource {

    /** Throw after this many issues have been read, as a real source going away mid-page does. */
    public FakeImportSource failAfter(int issues) { ... }

    /** 401, 403 or a connection that never answers — the three the screen words differently. */
    public FakeImportSource rejecting(int status) { ... }
}
```

- [ ] **Step 7: `DroppedFields`** — D-f's single derivation.

```java
/**
 * Which source fields are not imported, and the sentence the report will still be showing a year
 * from now.
 *
 * <p><strong>One derivation, two responses.</strong> The preview serves what this returns and the
 * run persists it; {@code ImportReportMatchesPreviewTest} asserts the two are equal element for
 * element, which is the module's stated done-when — "the dropped-field list in the report matches
 * the preview's word for word".
 *
 * <p>The reason is stored on the row rather than looked up when the report is read: editing this
 * map must not retroactively rewrite what somebody was told at the time.
 */
final class DroppedFields {

    private static final Map<String, String> REASONS = new LinkedHashMap<>(Map.of(
            "Story Points", "estimates here are hours; points have no conversion",
            "Sprint",       "releases replace sprints (D6); this run kept nothing in their place",
            "Epic Colour",  "no equivalent",
            "Flagged",      "use a label or the Blocked status",
            "Request Type", "this is not a service desk",
            "Time Tracking","time entries here belong to a person and a period"));

    /** The sentence when {@code labelPerSprint} was taken — the row stays, and says what survived. */
    private static final String SPRINT_KEPT =
            "releases replace sprints (D6); each sprint was kept as a label, which carries no "
            + "dates, capacity or board";
    ...
}
```

Everything the source holds that is not in `REASONS` and not a standard field is reported as
`Custom: "<name>"` with *"no custom fields in this product"* — which is how the mockup's
`Custom: "Squad"` row arises without this map naming somebody's field.

- [ ] **Step 8: `ImportPreviewService`,** and it is a pure function of the snapshot plus the
      project's current types and statuses. Type mapping is by name against `issue_type`
      case-insensitively, falling back on level: a source `Sub-task` → the project's `TASK`-level
      type, `Epic` → `EPIC`, everything else → the `STORY`-level type of the same name or `Story`.
      Status suggestion is name equality ignoring case, which is the mockup's *name matched* badge.
      Blockers are computed here: `KEY_COLLISION` from
      `IssueRepository.findExistingKeys(sourceKeys)`, `TOO_MANY_ISSUES` from the cap,
      `ALREADY_IMPORTED` from `import_run`.

- [ ] **Step 9: `ImportPreviewWritesNothingTest`** — the test the module is judged on.

```java
@Test
@DisplayName("a preview leaves the project byte-identical — nothing written, D32")
void aPreviewWritesNothing() {
    Map<String, String> before = snapshotOfEveryCompanyOwnedTable();

    var preview = previewAs(testLead, new ImportPreviewInput(projectId, connection, "PROJ"));

    assertThat(preview.totals().issues()).isEqualTo(1482);   // it really did read
    assertThat(snapshotOfEveryCompanyOwnedTable())
            .as("""
                import-preview.html promises "No data has been written. Leaving this page now \
                changes nothing in Acme Storefront — not one issue, status or label", and D32 \
                makes the promise the whole reason the preview exists. A convention is what gets \
                forgotten; this is the observer.""")
            .isEqualTo(before);
}
```

`snapshotOfEveryCompanyOwnedTable()` reads the table list from `information_schema` rather than a
hand-kept array — a table added later is covered without anybody remembering — and hashes each
table's rows ordered by primary key.

- [ ] **Step 10: ArchUnit.** Add one case to `ArchitectureRulesTest`:

```java
@Test
@DisplayName("the importer reaches nothing in integration — R14's decision, as a rule")
void theImporterDoesNotReachIntegration() { ... }
```

It is narrower than the general cross-module rule on purpose: the general one already fails, but its
message says *"module 'tracker' may not depend on module 'integration'"*, and the person who wrote
the import has just been told by a plan that the adapters are right there. This one names R14.

- [ ] **Step 11: Run the gate.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend './gradlew build'
```

Expected: green. `BUILD FAILED` with `Could not write XML test results` and **zero** assertion
failures is the fd limit, not a red suite — `ulimit -n 65535; ./gradlew build`. Read
`docs/specs/shanks-runner-details.md` before deciding a failure is real.

- [ ] **Step 12: Commit.**

```bash
git add backend/src/main/java/com/tcms/tracker/imports backend/src/test/java/com/tcms/tracker/imports \
        backend/src/test/java/com/tcms/architecture backend/build.gradle.kts \
        backend/src/main/java/com/tcms/integration/OutboundUrlGuard.java
git commit -m "feat(tracker): the import source, its own URL guard, and a preview that writes nothing"
```

---

## T4 — The run, the report, and the stuck-run reaper

**Deliverable:** `POST /imports` imports a Jira project into a TCMS project with `PROJ-4812` still
called `PROJ-4812`, `GET /imports/{id}` serves the report, and `ImportReportMatchesPreviewTest`
asserts the report's `droppedFields` equals the preview's element for element.
**Depends on:** T3
**Stack:** `be` · **Agent:** `be-java` · **Skills:** `be-java-database-code`, `be-java-unit-testing`

**Files:** `ImportRun`, `ImportDroppedField` and their repositories; `ImportRunService`,
`ImportWriter`, `ImportReaper`, the run views; `ImportController`'s three remaining operations;
`IssueComment` + `CommentView` for `externalAuthor`; the five tests.

### Traps this task will hit, named before it starts

1. **`@Async` clears the company.** `CompanyContextTaskDecorator` runs every task submitted to an
   application executor with `CompanyContext` **cleared**, so the dispatched run starts with no
   company and every RLS-protected read returns zero rows — silently. The run's entry point takes a
   `CompanyScopedEvent`-shaped parameter and opens it with `CompanyContext.openFor(dispatch)` as its
   first statement. That parameter also carries the credential, which is why it is a method
   parameter and **never** an outbox payload: the outbox is a table.
2. **`recordCoalesced` must be inside the producing transaction**, and it is called **once** for the
   whole run — `Outbox.recordCoalesced` throws otherwise, and `IssueFactOnIssuesChanged` is a
   whole-company recompute that reads no payload, so 1 482 events would be 1 482 identical
   recomputes.
3. **`ProjectKeys.allocate` joins the caller's transaction and must.** One call for the whole run,
   reserving `n` numbers; the row lock has to be held until the issues that use them commit.
4. **Parents before children.** `issue.parent_id` is `ON DELETE RESTRICT` with a real FK, and the
   depth rule is enforced in the service against `issue_type.level`. Insert `EPIC`, then `STORY`,
   then `TASK`, and resolve `parentKey` through a map built as you go.
5. **`LabelService` answers 409 on a duplicate name.** Treat it as *already there*. An importer
   hits this on the second issue carrying a label and every issue after it.

- [ ] **Step 1: The entities.** `ImportRun` maps every column; `status_mapping` and `people` are
      `@JdbcTypeCode(SqlTypes.JSON)` for `ReportJob#parameters`' reason — a malformed value cannot
      commit, and Postgres will not implicitly cast `varchar` to `jsonb`. There is **no
      `lock_version`**: the row is written by one thread at two moments (`RUNNING`, then terminal),
      and `ArchitectureRulesTest.everyLockVersionColumnIsAVersion` is only about a column that
      exists — so do not add one.

- [ ] **Step 2: `ImportWriter`** — the one transaction, in this order.

```java
/**
 * The write pass: one transaction, all of it or none of it.
 *
 * <h2>Why not batch commits, which are easier and faster</h2>
 *
 * <p>A batched failure leaves PART of a Jira project in the customer's new tracker, with no way to
 * tell which part, at the exact moment they are being asked to trust the migration.
 * {@code import-preview.html}'s "No data has been written" is worth having on the far side of
 * Confirm too: either the whole project arrived or nothing did. The cost is that the read pass
 * must finish first and is therefore bounded by heap — {@code tcms.tracker.imports.max-issues},
 * refused by the preview with the number in the message.
 *
 * <h2>The order is a dependency order, not a preference</h2>
 *
 * <ol>
 *   <li>Reserve {@code n} keys — one {@link com.tcms.project.ProjectKeys#allocate} call, whose row
 *       lock must be held until this transaction commits.</li>
 *   <li>Statuses and types the mapping needs but the project lacks. (It never lacks a status: the
 *       mapping targets only this project's own.)</li>
 *   <li>Releases, from the source's fix versions.</li>
 *   <li>Labels, through {@code LabelService}, 409 read as "already there".</li>
 *   <li>Issues, EPIC then STORY then TASK, resolving parents through a map built as we go.</li>
 *   <li>Comments, then label assignments, then the run row and its dropped-field rows.</li>
 *   <li>{@code outbox.recordCoalesced(OutboxEvents.ISSUES_CHANGED)} — ONCE. Without it
 *       {@code issue_fact} does not hold a single imported issue and every board, report and
 *       percentage in the product is blind to the migration.</li>
 * </ol>
 */
```

The issue insert, and the two columns that make J3 true:

```java
Issue issue = Issue.imported(
        UUID.randomUUID(), companyId, projectId,
        allocation.seqAt(index),          // the PROJECT's counter — never Jira's number
        source.key(),                     // 'PROJ-4812', verbatim (D5)
        typeId, statusIdFor(source.statusName()), parentId,
        source.title(), source.description(), ...);
if (options.preserveBacklogOrder()) {
    issue.setRank(baseRank + (long) index * 1024);   // the trigger stands aside (012 D-k)
}
```

- [ ] **Step 3: The test that proves the key survived.**

```java
@Test
@DisplayName("PROJ-4812 is still PROJ-4812 — story J3")
void originalKeysSurvive() {
    startImportAndAwait(mappingForEveryStatus(), ImportOptions.defaults());

    var found = getAs(testLead, "/issues/PROJ-4812", IssueView.class);

    assertThat(found.key()).isEqualTo("PROJ-4812");
    assertThat(found.title()).isEqualTo("Cart survives a sign-in");
    assertThat(issueRow("PROJ-4812").keySeq())
            .as("""
                key_seq comes from the PROJECT's counter, not from Jira's number. Using 4812 here \
                would push the project's counter past four thousand unused values, or collide with \
                a local ACME-12 if it did not — and key_seq is the only column that may order issue \
                keys anywhere in the product.""")
            .isEqualTo(1L);
}
```

- [ ] **Step 4: The test the module's done-when names.**

```java
@Test
@DisplayName("the report's dropped-field list is the preview's, word for word")
void theReportMatchesThePreview() {
    var preview = previewAs(testLead, input());
    var run = startImportAndAwait(mappingForEveryStatus(), ImportOptions.defaults());

    assertThat(run.droppedFields())
            .as("""
                One derivation feeds both (DroppedFields). Two would be two derivations that agree \
                today — and the whole promise of the report is that a year from now it still says \
                what the person was told before they confirmed.""")
            .containsExactlyElementsOf(preview.droppedFields());
}
```

- [ ] **Step 5: The refusals.** Three tests: an unmapped source status is `400` naming it; a second
      import of the same source is `409`; a run against `FakeImportSource.failAfter(400)` ends
      `FAILED` **and leaves the project byte-identical**, reusing T3's snapshot helper — which is
      the strongest statement this module can make and the reason D-b chose one transaction.

- [ ] **Step 6: `ImportReaper`.** A scheduled sweep per company, `ReportJobRunner.failStuckJobs`'s
      shape: a `RUNNING` row whose `started_at` is older than `tcms.tracker.imports.run-timeout`
      (default `PT30M`) becomes `FAILED` with *"The server restarted or lost its connection to the
      source while this import was running. Nothing was written — the project is unchanged."* It
      does **not** retry: the state machine only goes forwards, and a re-request is a new row.

- [ ] **Step 7: `externalAuthor` through to the wire.** `IssueComment` gains the field,
      `CommentService` sets it when the source author matched nothing, and `CommentView` carries it.

- [ ] **Step 8: Gate and commit.**

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote backend './gradlew build'
git add backend/src/main/java/com/tcms/tracker backend/src/test/java/com/tcms/tracker
git commit -m "feat(tracker): the import run, the report, and the keys that survive it"
```

---

## T5 — The Azure DevOps source

**Deliverable:** `provider: AZURE_DEVOPS` previews and imports an Azure DevOps project through the
same `ImportSource` port, proven against a fake built from recorded response shapes. No file outside
`AzureDevOpsImportSource`, `ImportSources` and their tests changes.
**Depends on:** T4
**Stack:** `be` · **Agent:** `be-java` · **Skills:** `be-java-pattern-writer`, `be-java-unit-testing`

**Why this is its own task and not part of T3:** two paging models, two field vocabularies and two
user models in one session exceeds the budget, and the seam between them — the port — is the seam
the design wants anyway. **Why it is in this module at all:** 009's `**be**` line names both
providers, and a port with one implementation is a port nobody has tested is a port.

- [ ] **Step 1:** `AzureDevOpsImportSource`. Work items through WIQL (`POST
      _apis/wit/wiql`) then `GET _apis/wit/workitems?ids=…&$expand=all` in batches of 200;
      comments through `_apis/wit/workItems/{id}/comments`; the revision count through
      `_apis/wit/workItems/{id}/revisions?$top=0`. Auth is `Basic base64(":" + pat)`, which is ADO's
      own spelling and looks wrong until you check it — a comment says so.
- [ ] **Step 2:** The type map: `Epic` → `EPIC`; `Feature`, `User Story`, `Product Backlog Item`,
      `Bug` → `STORY`; `Task` → `TASK`. ADO's `Feature` sits at `STORY` deliberately — the depth
      rule is about **levels**, and a fourth story-level type is a row (M1), never a migration.
- [ ] **Step 3:** ADO's dropped fields: `Effort`/`Story Points`, `Iteration Path`,
      `Area Path`, `Board Column`, `Original Estimate`, plus every `Custom.*` field.
      `Iteration Path` is ADO's sprint and takes the same `labelPerSprint` option (D-h).
- [ ] **Step 4:** `AzureDevOpsImportSourceTest` against `MockRestServiceServer`. Bind the transport
      the way `TrackerClient`'s second constructor exists for — installing the real request factory
      over the top silently un-binds the double, and the tests then dial the public internet and
      fail as parse errors.
- [ ] **Step 5:** Gate and commit.

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote backend './gradlew build'
git add backend/src/main/java/com/tcms/tracker/imports backend/src/test/java/com/tcms/tracker/imports
git commit -m "feat(tracker): the Azure DevOps import source, through the same port"
```

---

## T6 — `tcms import list` and `tcms import show`

**Deliverable:** `tcms import list` prints a project's past imports and `tcms import show <id>`
prints one report with its dropped-field table, both from a terminal with the source system gone.
The surface document records in §6 that MCP gets nothing and why.
**Depends on:** T4
**Stack:** `ts` · **Agent:** `tooling-engineer` · **Skills:** `tool-cli-builder`

**Files:** `packages/cli/src/commands.ts`, `reads.ts`, `main.ts`, their tests;
`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §6.

- [ ] **Step 1:** Two commands in `COMMANDS`, both `kind: 'read'`, both `permission:
      'issue:import'`, spelled the way the release verbs are:

```
tcms import list                    → GET /imports?projectId=…
tcms import show <importRunId>      → GET /imports/{importRunId}
```

`import list` takes no `--project`: no CLI command in this repo takes a `projectId`
(surface doc §4.1), it comes from the context.

- [ ] **Step 2:** `import show` prints four blocks — the header line, the counts, the status
      mapping as applied, and the dropped-field table — with the reason column intact. **Not
      truncated at the output budget** for the dropped list specifically: it is the artifact, and a
      report that elides the thing it exists to record is worse than no command. §4.6's budget
      applies to the mapping table, which is capped with a *"n more"* line.

- [ ] **Step 3:** `writeConformance.test.ts` and `format.test.ts` stay green — neither command is a
      write, so neither needs an `Idempotency-Key` or an `If-Match`, and the conformance suite
      asserts that in both directions.

- [ ] **Step 4:** Add the §6 row and the sentence recording the absence of the two write verbs, the
      reason being D-l's — the credential in shell history, and a mapping confirmed without a
      preview being read.

- [ ] **Step 5:** Gate and commit.

```bash
~/bin/tcms-remote sync && ~/bin/tcms-remote packages 'npm run build && npm run lint --workspaces && npm run test:run --workspaces'
git add packages/cli/src docs/specs/2026-08-08-mcp-tool-and-command-surface.md
git commit -m "feat(clients): the two import read verbs on the CLI, and no MCP tool"
```

`npm run build` at the packages root, **never `--workspaces`** — that runs them alphabetically, so
`cli` would typecheck against a stale `client` dist.

---

## T7 — The wizard: connect → preview → map → run

**Deliverable:** `/projects/:projectId/imports/new` walks `import-connect.html` →
`import-preview.html` → `import-mapping.html`, refuses to confirm while one status is unmapped, and
starts a run. `tcmsFaults.importing(...)` drives every failure path from the browser console.
**Depends on:** T6
**Stack:** `fe` · **Agent:** `fe-react` · **Skills:** `fe-react-forms`, `fe-react-data-fetching`,
`fe-react-component-testing`, `fe-react-error-handling`

**Read the three mockups before writing a line.** They are the source of truth for what the screens
show; this plan and the scope spec are the source of truth for what the system does.

- [ ] **Step 1 — the fault switch, first, before the screens it proves.** Add the `import` arm to
      `frontend/src/lib/devFaults.ts`, beside `tracker`, `issue`, `rank` and `release`:

```ts
/**
 * How an import misbehaves, or `null` for a source that answers.
 *
 * **None of these can be reached by using the app**, which is the whole reason they are here: the
 * failures this module exists to word correctly all live inside somebody else's Jira. `'midway'`
 * is the one the module was built for — a source that answered the preview and stopped answering
 * during the run — and its correct rendering is a FAILED report saying the project is unchanged,
 * never a spinner and never a partial success.
 */
export type ImportFault = 'unauthorized' | 'forbidden' | 'down' | 'midway' | 'collision' | 'slow';
```

with `tcmsFaults.importing(fault)` on the console object and its line in the module header's
example block. Both `import.meta.env.DEV` guards stay **literal**, so Rollup drops the module from
a production build.

- [ ] **Step 2 — `keys.ts`.** One family in the single factory. Never a second factory.

```ts
  imports: {
    all: ['imports'] as const,
    forProject: (projectId: string) => ['imports', 'project', projectId] as const,
    run: (importRunId: string) => ['imports', 'run', importRunId] as const,
  },
```

**No key for the preview or the source-project list.** Both are `POST`s carrying a credential, and
a query key is a cache key — the token would sit in the query cache, in the devtools panel and in
any future persister. They are `useMutation` and their results live in the wizard's own state.
Write that sentence into `imports.ts`, because it is the kind of thing somebody optimises away.

- [ ] **Step 3 — `ImportWizardPage`** owns the whole flow's state, including the token, in one
      `useState`. The step rail is `ImportStepper`, drawn from the mockups' four chips.

- [ ] **Step 4 — `ConnectStep`.** The mockup's form, plus D-k's provider selector above Site with
      Jira Cloud preselected. The help text under the token field is the mockup's own —
      *"Read-only is enough. The token is used for this import and is not stored afterwards."* —
      and it is now **true**, which is what D-a bought. One sentence is added beside it that the
      mockup has no reason to carry: *"Reloading this page means entering it again."* The
      *"Connected · n projects visible"* note appears after `listSourceProjects` resolves.

- [ ] **Step 5 — `PreviewStep`.** The three panels the mockup draws, plus the state it does not:
      `blockers` non-empty replaces *Continue* with the blocker's `detail` and, for
      `KEY_COLLISION`, the offending keys as `Identifier` chips. The green *No data has been
      written* banner is the mockup's copy verbatim.

- [ ] **Step 6 — `MappingStep`, and the thing it must get right.** Confirm is disabled while any
      row has no target, with the mockup's own reason on the control's `title` — *"an unmapped
      status would silently become the default and put n issues in the wrong column"* — and the
      count of unmapped rows in the footer. Disable-don't-hide, per §31.

      **The trip to `settings-workflow.html` and back.** The mockup's *"Add a status here if none
      of yours fits — the mapping picks it up on return"* is a promise about state. The wizard
      writes its state (connection **included**, in memory only) into a React Router `location.state`
      before navigating and restores it on return; the workflow query is invalidated so the new
      status appears in every picker. A full page load loses it, and the connect step's warning is
      why that is acceptable rather than a bug.

- [ ] **Step 7 — Confirm** issues `POST /imports` with an `Idempotency-Key`, then navigates to
      `/projects/:projectId/imports/:importRunId`, which T8 builds. Until T8 lands, that route does
      not exist — so this task ships the route as a **minimal polling page** showing the run's
      status and nothing else, and T8 replaces its body. The tree builds and the flow works end to
      end at this boundary; it is not pretty until T8.

- [ ] **Step 8 — Tests.** RTL over the three steps: an unmapped row disables Confirm and says why;
      a preview with a `KEY_COLLISION` blocker cannot continue; `drivingAFailedRead()` on the
      source-project list renders `describeError`'s copy and not an empty dropdown. Use
      `createTestQueryClient` — **never build your own `QueryClient` in a test**, only that one has
      the caches.

- [ ] **Step 9 — Gate and commit.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
git add frontend/src/features/imports frontend/src/api/queries frontend/src/lib/devFaults.ts \
        frontend/src/lib/labels.ts frontend/src/app/router.tsx
git commit -m "feat(imports): the wizard — connect, preview, map, confirm"
```

---

## T8 — The report screen, the imports list, and the close-out

**Deliverable:** `/projects/:projectId/imports` and `/projects/:projectId/imports/:importRunId`
render `settings-import.html` and `import-report.html` with no call to the source system, the
*Imports* rail entry appears for holders of `issue:import`, and 009 · `docs/features.md` · the
surface doc are closed out.
**Depends on:** T7
**Stack:** `fe` · **Agent:** `fe-react` · **Skills:** `fe-react-data-fetching`,
`fe-react-component-testing`

The documentation close-out rides with this task rather than taking a session of its own: this is
the only session that knows what actually landed and what is built-but-unverified.

- [ ] **Step 1 — `ImportsListPage`** = `settings-import.html`. The table, the *"reports are kept
      indefinitely"* hint, and the footer note that nothing keeps syncing with Jira afterwards.
      Empty state: *"No imports yet"* with the start button, never a bare empty table.

- [ ] **Step 2 — `ImportReportPage`** = `import-report.html`, replacing T7's minimal polling body.
      Four KPI tiles, *Keys survived*, *Dropped fields* (the same `DroppedFieldTable` component the
      preview step uses — D-f, on screen as well as on the wire), *Status mapping applied*,
      *People matched*. While `status === 'RUNNING'` it polls `keys.imports.run(id)` at 2s through
      `refetchInterval` and shows progress instead of the tiles; `FAILED` shows `failureReason`
      and the sentence that the project is unchanged.

      **The mockup's *Open question* panel is replaced, not ported.** It says sprint membership is
      *"undecided, and recorded as undecided rather than quietly resolved"*. Review R8 decided it
      on 2026-08-30. The panel becomes *Sprints* and says what this run did — dropped, or kept as
      n labels — which is the mockup being **out of date about a decision**, not about a layout.

- [ ] **Step 3 — `downloadReport.ts`.** The mockup's *Download* renders client-side from the
      `ImportRun` already in the cache: a JSON blob of the whole run and a CSV of the dropped
      fields. **No server artifact** — D15 says *"persisted as an `artifact`"*, and `artifact` is
      the `repository` module's table, which `tracker` may not write; `import_run` +
      `import_dropped_field` is what 009's `db` line replaced that mechanism with, and it is
      better for the purpose. A comment in the file records the departure so a reader who knows
      D15 does not read this as an omission.

- [ ] **Step 4 — The rail entry**, in `AppShell.tsx`'s `Project` group after Boards, at
      `settings-import.html`'s position in the mockup's subnav — and **gated**, unlike General,
      Workflow and Boards:

```tsx
        /*
          014 T8's imports. GATED and un-listed without `issue:import`, which is the opposite of
          the three entries above it and matches Notifications (005 C2). Those three are ungated
          because each renders read-only for a caller who cannot write, so a reader still learns
          what their project's statuses mean. There is no read-only view of an import: every
          operation in the family costs `issue:import` (014 D-e, because a report names somebody's
          Jira account and the person who connected to it), so the entry would lead to a screen
          whose whole content is a refusal.

          It also retires the last of the three sentences BoardSettingsPage and this file have
          been carrying since 012 T9 — "Repositories is M5's and Imports is M4's". Imports has
          landed; Repositories has not, and its half of the sentence stays.
        */
        ...(canImport ? [{ label: 'Imports', to: `${p}/imports`, icon: <Import className="size-4" /> }] : []),
```

- [ ] **Step 5 — Tests.** A report renders from `GET /imports/{id}` alone with **no other fetch** —
      asserted by failing the test on any unexpected call, which is the "reachable after the source
      is gone" done-when made checkable. A `RUNNING` run polls and then stops on `COMPLETED`. The
      dropped table renders identically in the preview step and the report from the same fixture.

- [ ] **Step 6 — `docs/features.md`.** Under `## Issues`, a new `### Moving in from Jira`
      subsection: the four-step flow, keys surviving, what is dropped and that it cannot be
      recovered, the report kept indefinitely, and that nothing keeps syncing. Amend the
      `Planned but Not Yet Implemented` list — line 507's *"Bulk import/export — CSV/Excel import,
      TestRail/Xray migration importers"* is **not** what this built and stays as a gap; strike
      through nothing there.

- [ ] **Step 7 — Close 009 per the hand-off protocol**, in its **own** commit, staging only that
      file:

  1. **Status table** — M4's row to `done 2026-09-0N @ <short sha>`.
  2. **The V actually taken** — M4's `**db**` line: `~~V6~~ → ~~V7~~ → ~~V8~~ → **V7**`, with one
     sentence saying M5 had not landed so the number the table reserved on the assumption that it
     had was wrong, and the directory was the authority. Correct **M5's** `**db**` line to `V8` in
     the same edit, and its prompt block.
  3. **Built and unverified** — a `**Built and unverified:**` list under the M4 section. Everything
     driven only by `FakeImportSource` belongs on it, and so does every screen state nobody has
     clicked: a real Jira Cloud site has not been dialled by any gate, the ADO source has been
     driven only against recorded shapes, and the workflow round-trip in T7 step 6 is asserted in
     a test rather than performed in a browser.
  4. **Close R14** in the review table with D-a's outcome and its one-line reason.
  5. **Rewrite dependent prompts** — **no module depends on M4**, so nothing downstream needs
     editing, and the commit message says so rather than the document carrying a note about it.
     M5's prompt is edited only for its migration number, which is a correction and not a
     dependency.
  6. Commit `docs(plan): M4 done`.

- [ ] **Step 8 — Gates and commits.**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend 'npm run build && npm run lint && npm run test:run'
~/bin/tcms-remote packages 'npm run build && npm run test:run --workspaces'
~/bin/tcms-remote backend './gradlew build'

git add frontend/src/features/imports frontend/src/app docs/features.md
git commit -m "feat(imports): the report, the imports list, and the M4 close-out"

git add docs/plans/009-project-management-expansion-modules.md
git commit -m "docs(plan): M4 done — V7 taken, R14 closed, no module depends on M4"
```

---

## Self-review

**Spec coverage.** D5 → D-d + T4 step 3. D15 → D-f + T2 + T8 step 3 (mechanism departure recorded).
D32 → T1's preview operation + T3 step 9 + T7 step 6. §10's open question and review R8 → D-h + T8
step 2. Stories J1 → T3 step 9 and T7 step 5; J2 → T7 step 6 and T4 step 5; J3 → D-d and T4 step 3,
with *"open the import report later from the project's settings"* in T8. Review R7 → the whole
package placement, asserted in T3 step 10. Review R14 → D-a, closed in T8 step 7.
All five mockups are ported: connect/preview/mapping in T7, report/settings-import in T8.

**Order.** T1 declares what T3–T8 bind to. T2's tables exist before T4 maps them. T3's port exists
before T5 adds an implementation. T4's endpoints exist before T6 reads them and before T7 calls
them. T7's route exists before T8 replaces its body. Every `Depends on` points backwards.

**Two known soft spots, stated rather than hidden.** T3 and T7 are both estimated at the top of the
150k budget and each names its split seam. And no user-facing capability works until both T4 and T7
have landed — that is the stated price of one agent switch per stack, and the module's deliverable
is still what closes it.
