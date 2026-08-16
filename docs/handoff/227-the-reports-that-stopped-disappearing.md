# §227 — the reports that stopped disappearing, and the report a reader may open

**Phase 1 of the A1–A8 run over `docs/audits/2026-08-15-flow-review-and-improvements.md`.** That
audit is three read-only traces with thirty-odd improvements graded P1/P2/P3; the authorised scope
is **every P1 — A1 through A8** — and the audit now carries a progress ledger (§0) that this slice
opened. This phase is the audit's own suggested first step: **A2 + A3 + A5**, the reports slice.

Touches contract, `backend/reporting`, `frontend/features/reports`, one `packages/mcp` fixture, and
both `schema` copies. **No migration**, no permission moved, no new permission key.

---

## What was broken, in one paragraph each

**A2 — the two-key split described a distinction nothing implemented.** `V3` has seeded
`report:view` and `report:export` since M0, and §6e names the persona they exist for: *may read a
report on screen, may not carry it off.* The on-screen model was served through
`GET …/reports/artifacts/{artifactId}/content` — the **download** route, gated on `report:export` —
so that persona could watch a job succeed and then be refused the report it had just watched being
made. Three files said so at length (`ReportViewPage`'s header, `useReportModel`'s, the page's own
403 copy) and each correctly said the fix belonged to the server.

**A3 — a generated report had no durable handle.** `POST …/generate` handed back a job and
`GET …/jobs/{jobId}` resolved one by id, and that was the whole surface. So the set of reports a
person could reach was the set their current tab had asked for; a reload lost every download link
permanently while the artifacts sat in the store, named by nothing a client could ask for.
`ReportsPage` refused to work around it with a client-side register — correctly, and that refusal is
why the fix was one hook rather than an unwinding.

**A3's other half — a job stranded in `RUNNING` was unresolvable by anybody.** `ReportJobRunner`'s
header argued there should be no reaper. Re-read, every word of that argument is about **retrying**,
and it is right about retrying. It left the row standing: a worker that dies after claiming leaves
`RUNNING` for ever, so the client polls a spinner with no end and no `error`.

**A5 — a complete backend feature with no control in front of it.** `POST` and `DELETE` on saved
report definitions were implemented, gated and tested in §92. The panel that lists them had no Save
button, so the list was `[]` on every deployment, and the screen said *"nothing can create one in
this version yet"* — for eight weeks after something could.

---

## What shipped

### The contract

Two operations and one required field.

- **`GET /projects/{projectId}/reports/jobs`** (`listReportJobs`, `report:view`) — the project's
  jobs, newest first, top 100, no paging. **The project's and not the caller's**, which is the
  opposite call to `GET /projects/{projectId}/reports` one path up, and the difference is what the
  two rows *are*: a definition is a saved question owned by whoever wrote it, and a job is a report
  produced *about this project*. `report_job.requested_by` is nullable since §66, so a "mine"
  reading would hide every report requested by somebody who has since left.
- **`GET /projects/{projectId}/reports/jobs/{jobId}/model`** (`readReportModel`, `report:view`) —
  the stored model, `application/json`, **inline**. Its body is deliberately **not** a declared
  schema (`type: object, additionalProperties: true`): every report type computes different
  sections, so a contract type here is a second definition of every report's output — the identical
  argument `report_job.parameters` makes on the way in. It is keyed by **job** rather than by
  artifact because the gate that answers it is the poll's gate, and a route keyed by artifact would
  have to prove the artifact's project through the job anyway.
- **`ReportJob.requestedAt`**, required and never null. The list is ordered by it, and an order a
  reader cannot see is one they have to take on trust. Deliberately not `startedAt`/`finishedAt` —
  those are the worker's business, and a client that renders them starts explaining the queue rather
  than the report.

`ReportJob.modelUrl` now points at the model route. `downloadUrl` is unchanged and the artifact
route still costs `report:export`. **The two descriptions that claimed `modelUrl` pointed at the
artifact route are struck through in place**, not deleted.

### The server

- `ReportReadService.list` and `.openModel`, both on `VIEW_REPORT`, both through
  `ProjectService.readGranted` — so an unreachable project is a bodiless 404 before any key is
  consulted and a 403 never tells a caller that a project they cannot see exists.
- `ReportJobRepository.findTop100ByProjectIdOrderByRequestedAtDesc`. **Bounded in the method name
  rather than by a `Pageable`**: the operation declares no paging, so a page size arriving as an
  argument would be a knob with exactly one setting.
- `ReportJobRunner.failStuckJobs`, called first on every company tick, plus
  `tcms.reporting.stuck-job-timeout` (`PT15M`, refuses non-positive). It **does not retry** — the
  row goes to `FAILED` with a sentence written for the person watching, and a re-request is still a
  new row, so one request per row survives. Its count is kept **apart from `MAX_JOBS_PER_TICK`**:
  folding it in means a company that lost twenty-five jobs to a restart spends its whole next tick
  on rows nobody is computing and leaves the live queue untouched.
- The reaper's cutoff is `startedAt`, not `requestedAt`: the age that matters is how long the work
  has been *claimed*, and a job that sat in a long queue before a healthy worker took it is not
  stuck.

### The SPA

- `useReportJobs` — one poll for the whole screen, stopping when nothing on the list is unfinished.
- **`ReportJobCard` stopped fetching.** It used to hold its own `useReportJob` because the screen
  above it had no list. With one, keeping the per-card fetch would mean *n* requests every two
  seconds for rows the page already has, plus two ages of one job whenever a card's poll and the
  list's landed apart. The polling pair test moved with the behaviour, to `ReportsPage.test.tsx`.
- `useSaveReportDefinition` / `useDeleteReportDefinition`; an optional **"Save these settings as"**
  field on the generate dialog, and per-row **Generate** and **Delete** on the saved-reports panel.
- Regenerating opens the same dialog in a second mode: it asks for the **format alone** and posts
  `{definitionId, format}`. Every parameter control is hidden because the request does not send
  them — with a `definitionId` the server takes the type from the definition and *merges* the
  request's parameters over the saved ones, so a form that re-posted its own defaults would silently
  overwrite the selection somebody saved. **That is the trap**, and the natural implementation
  (pre-fill from `definition.parameters`, post it back) falls into it the moment a parameter the
  form cannot express — this dialog's header lists three — is dropped on every regeneration.
- The save runs **before** the generate and a failure stops both. The other order queues a report
  and then loses the definition, which is indistinguishable on screen from a save that worked.

---

## The rules worth carrying

**A permission split that nothing implements is not a split.** Both keys were seeded in M0, both
were named in `ReportReadService`'s header, and the persona was invented in `ReportEndpointTest` as
a custom role — and the product still charged the export key to read a report on screen. The
instrument that would have caught it does not exist: nothing compares *which key an operation costs*
to the distinction the catalogue draws. The persona in that test class is the closest thing, and it
was there the whole time, applied to the poll and not to the model.

**Copy that describes a gap outlives the gap unless it is deleted in the same change.** Six places
described the two gaps this phase closed, in careful, correct prose — the card's *"leaving this page
loses the link"*, `ReportsPage`'s *"no operation lists them"*, the empty panel's *"nothing can create
one in this version yet"*, `ReportViewPage`'s and `useReportModel`'s `report:export` finding, and
two contract descriptions. Each was right when written. Removing them was a larger diff than the
feature, and the one that would have hurt most to leave is the card's, which tells a person that
what they are looking at will disappear.

**An argument can be right and still leave the defect standing.** The no-reaper paragraph is a
correct argument about retries, in front of a row nobody could resolve. When a header explains why
something is deliberately absent, check what the argument is actually about before treating it as
settled.

**Ordering assertions tie.** `Listing.listsTheProjectsJobs` asserts the ordering *property* —
`requestedAt` never rising down the list — rather than a position, because two requests milliseconds
apart can share a timestamp and a positional assertion would then be a coin toss rather than a check
on the `ORDER BY`. And the stamps are parsed to `Instant` before comparing: Jackson writes a
variable number of fractional digits, so `…:00Z` and `…:00.5Z` sort the wrong way round as text.

---

## Gates

All on `shanks` per `CLAUDE.md`.

| Stack | Result |
|---|---|
| `backend` `./gradlew build` | **BUILD SUCCESSFUL** — 2864 tests + this slice's additions, 0 failures |
| `frontend` `npm run build` / `lint` / `test:run` | clean, 0 warnings, **1442 tests** |
| `packages` `npm run build` / `lint --workspaces` / `test:run --workspaces` | clean, 0 warnings, **78 / 128 / 123** |

One backend failure was met and is the point of the change: `ReportEndpointTest.Polling
.aCompletedJobCarriesItsUrls` pinned `modelUrl` to the artifact route. It is now the assertion that
it is **not**.

Queue rows written and **not run** (§65): `§227 RP-1…RP-7` in
`docs/verification/frontend-browser-checks.md` (lane **A6**) and `§227 PM-1…PM-8` in
`docs/verification/backend-mutation-checks.md`. **`RP-4` is flagged as undriveable through the UI**
— the screen-only persona needs a custom role and the product has none — with the two options
written out, because choosing the silent one is what that paragraph exists to prevent.

---

## What is NOT done

- **A1, A4, A6, A7, A8** — the rest of the authorised scope. The audit's §0 ledger is the tracker.
- **E2 (`Idempotency-Key` on `POST /reports/generate`)** was deliberately not taken. It pairs
  naturally with this phase and is a P2 outside the authorised scope; it is the cheapest remaining
  work in this area and the ledger says so.
- **B5 (`PDF` in the format enum, a guaranteed 400 on every deployment)** is untouched and still
  offered in the dialog, which the dialog's header argues for at length.
- **The reaper has no browser observer and cannot have one.** Producing a stranded claim means
  killing the process between the claim and the completion. `ReportJobPipelineTest.Reaping` writes
  the state in SQL and is the only observer there is.
- **Nothing compares an operation's permission key to the catalogue's own distinctions.** That is
  the instrument A2 wanted, and it is not written. It would be a rule over `openapi.yaml`'s
  descriptions and the services' constants — plausible, and larger than this phase.
