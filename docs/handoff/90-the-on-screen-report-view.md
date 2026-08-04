# §90 — the on-screen report view, and the permission that reads a report

**Date:** 2026-08-04 · **Slice:** F2 of `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md` ·
**Three phases, three commits** · **No migration (`V27` still head, `V28` still free), no contract
change, no `gen:api`, no new permission key, no backend file touched at all.**

§87 built the pipeline, §88 gave its output a header, §89 gave it a client that could ask for a
report and download one. This is the half §6e names in a single clause — *"the on-screen report view
consumes the same model via JSON, so what you see is what exports"* — and it was fully unblocked:
the job already carries `modelUrl`, the artifact already answers `{ header, report }`, and F2's job
is to **render** that rather than to compose anything.

The plan's F2 line said "after R6". It is shipped before R6 for the reason §89 named on its way out:
the pieces exist, and a `Ready` card that can only be downloaded is a screen with an obvious missing
verb.

---

## 1. The finding: reading a report on screen costs the permission for taking it away

`ReportReadService.open` — the artifact route, the only way to the bytes — gates on
**`report:export`**. That is correct for a download, and it is the route the *stored model* is
served through, because the model is an artifact like any other (`V27` widened
`report_artifact.format` to admit `JSON` precisely so it would be).

So the on-screen view costs `report:export` too. And that contradicts a split this project went to
real trouble to make expressible:

- §6e describes two keys where one would do, and the difference is *may read on screen* versus *may
  carry off*.
- §87's phase 6 is a whole phase about that split: it built the suite's **first two custom roles**
  because *no built-in persona lacked either key*, so every gate assertion in the pipeline was
  satisfied by an implementation with no gate. One of those roles holds `report:view` and **not**
  `report:export`, and the handoff records what it is for: *"may read on screen, may not carry
  off"*.

The persona that role describes can, today, watch a job go `Queued → Building → Ready` and then be
refused the report it just watched being made. **The split works everywhere except the one screen it
was written for.**

This slice does not fix it, because the fix is the server's and there are two of them — serve the
`JSON` model artifact under `report:view` (it is the same read the poll is already allowed), or give
the on-screen view a route of its own. Both are a backend slice. What this slice does is **refuse to
paper over it**: `ReportViewPage` branches on `status === 403` and says, in as many words, that
reading a report on screen needs the same permission as downloading one and that the reader does not
have it. `describeError`'s generic 403 copy — *"Ask a project lead to grant you the permission this
view needs"* — is true and useless, and worse, it makes the contradiction look like an ordinary
permission error rather than a design defect somebody should report.

**Browser row 31 is live-only** and its expectation is written as *record what this looks like*,
not as *confirm the product behaves correctly*.

---

## 2. The model is the one shape in this client with no generated type behind it

Every wire shape in the frontend comes from `src/api/schema.d.ts`, generated from `openapi.yaml`.
This one cannot, and the reason is worth stating because the alternative looks reasonable: the
artifact route's response is declared **binary**, since it serves five formats' bytes — Markdown,
HTML, and this JSON among them. A schema on that response would be a claim about four things it is
not. So the contract carries the shape in **prose**, on `ReportJob.modelUrl`, and the authority for
the field names is `ReportDocument.java` + `ReportModel.java`.

Given that, the choice is between a cast and a parse, and it is not close.

**A cast would put the drift three components deep**, where a half-recognised document renders as a
report with **no sections** — which is exactly what a selection that matched nothing looks like. Two
states that must never be confusable become one screen. §79's cast defect, one layer further out and
with nothing under the compiler's eye at all.

So `lib/reportDocument.ts` **parses** — with Zod, which is already a dependency and which
`data-fetching` names for this exact job (*"a parse failure is an error, not an emptiness — never
`catch` it into `[]`/`null`, which turns a broken contract into a silent 'no records'"*). Two
behaviours fall out of it and they point in **opposite directions on purpose**:

- **Unknown fields are dropped, not refused.** `JsonReportRenderer`'s own argument for not sharing
  the web layer's `ObjectMapper` is that the artifact is *"a file that outlives the deployment that
  wrote it, that a screen will parse months later"*. So this screen will be handed documents written
  by later releases — a header field R6 adds, a section property R7 adds — and refusing them would
  make every report of that vintage unopenable. That is the worse failure of the two, by a distance.
- **Missing or mistyped known fields throw.** The query fails, the region renders its failed state
  with a retry, and the sentence says the stored model is not in a shape this version can display.

The one that is easy to miss: a table cell arriving as a **number** rather than a formatted string
throws. `ReportModel`'s central decision is that every cell is text the computation already
formatted — *"that is exactly how one screen comes to read 94% while the export of the same model
reads 93.5%"* — and the type is the only place this client can hold that line.

---

## 3. The view computes nothing, which is a departure from the plan's own line

The plan says F2 should *"reuse `Identifier`, `lib/format.ts`, `lib/results.ts` for the count
arithmetic rather than computing percentages in the page"*. **`lib/results.ts` is deliberately not
imported, and `lib/format.ts` is not either.**

There is no arithmetic to do. Every cell in the model is a pre-formatted string, and every timestamp
in the header is a pre-formatted string, for `ReportModel`'s and `ReportHeader`'s stated reason:
formatting **once**, in the computation, is what makes *"what you see is what exports"* a property of
the design rather than a promise in a comment. A percentage recomputed here — even correctly, even
through the shared helper — is a second implementation of a number that appears in a file somebody
has already sent to a client. §65 records "two contradictory percentages on one screen" as one of
the defects that typechecked and linted clean; this would be the same defect across two formats of
one report, where nobody can even see both at once to notice.

So the plan's instruction is followed in spirit by doing less than it asks, and the departure is
recorded here rather than left as an omission a later reader takes for an oversight.

**The one number is the bar width**, and it is not a counter-example: a chart needs magnitude to draw
a bar at all, which is why `ReportModel.Point` carries a number beside the table's rendered text.
`barPercents` carries **both of the defects `HtmlReportRenderer` already fixed once**, restated
because the two renderers are in different languages and nothing fails if they disagree:

- a bar is scaled against **the largest value in its own series**, never against a total — a series
  need not be parts of a whole, and "executions per tester" summed is a meaningless denominator that
  draws every bar too short;
- an **all-zero series draws no bar**, rather than the full-width one `width: NaN%` renders as in
  most browsers — a chart saying everything happened on a report where nothing did.

### The header block is reassembled once, and asserted as an exact ordered list

`ReportDocument.headerBlock()` assembles provenance → selection → the report's own metadata in one
place *"so three formats cannot print three different subsets or three different orders"*. This
screen is the **fourth renderer** of that block and the one furthest from it — another language,
another half of the repository, nothing that fails if it quietly drops a line. So the assembly is a
single function (`headerBlock` in `lib/reportDocument.ts`) rather than JSX walking the header field
by field: the two are comparable only if the client's version is one list in one place.

And both tests over it assert an **exact ordered list of labels**. The natural spelling —
`toContain` per label — reddens **nothing** when a line goes missing, and a report that does not say
who generated it is precisely the artifact §6e's header requirement exists to prevent. The mutation
row says so.

---

## 4. A route, not a dialog — and why that is not the register §89 refused

`ui-rules` §7: a modal is for a short, self-contained task that needs the screen behind it. A report
is neither. It is a document somebody reads, scrolls, and refers back to, and it wants a URL.

It wants one here more than usual. **Nothing lists jobs** — §89's central limitation — so the cards
on the reports screen live in component state and a reload loses them while the reports themselves
still exist. `/projects/:projectId/reports/jobs/:jobId` is the only durable handle a report has in
this version: it survives a reload, it can be pasted to a colleague who holds the project grant, and
it costs nothing to have.

**This is not the client-side job register §89 refused.** That refusal was about inventing a *list*
the server does not have, which would be a second drifting copy of a table that exists (§81). This
URL names **one** job and is resolved by the server's own `GET …/reports/jobs/{jobId}` — the
operation that does exist. A list endpoint is still missing and still the server's.

### The page is a job first and a report second

Landing on a `PENDING` job is ordinary: the View link appears the moment `modelUrl` does, and a
pasted URL arrives at any point in a job's life. So the page renders the job's four states, and
`useReportModel` is **gated on `modelUrl`** — `data-fetching`'s dependent-query rule, whose failure
mode it names exactly: *"a disabled dependent query left rendering its skeleton is the most common
form of the eternal-spinner bug"*. While the job runs, the page says the job is running. When the
poll lands `COMPLETED`, the model request fires by itself.

`COMPLETED` with **no** `modelUrl` is stated rather than left to the region, for the same reason:
with no URL there is no request to succeed or fail, so anything short of an explicit sentence is a
spinner that never stops. It is reachable from a deployment older than §88, or from a store that
lost the file — and the download stays offered, because that is a different artifact.

---

## 5. The download became a component, because F2 made it a second call site

`DownloadReportButton` is extracted from `ReportJobCard` unchanged in behaviour. `ui-rules` §8 is the
argument: two implementations of *this* download would be two implementations of the authenticated
download — the transport, the `Content-Disposition` filename with its composed fallback, the busy
guard, and the in-place failure — and the copy that drifts is whichever one was added second.

The card keeps its own shape and gains one affordance: **View report**, a real `<Link>`, keyed on
`modelUrl` and not on the status, for the same reason the download keys on `downloadUrl`. A real
link earns middle-click, ⌘-click and copy-link for free, and copying that link is the one way a
report outlives the tab that asked for it.

The failure testid moved with the extraction: `Reports.Job.DownloadError` → `Reports.Job.Download.Error`.

---

## 6. §89's untested guard is closed, and the pair is the point

§89 shipped `fetchArtifact`'s same-origin guard with **no test** and recorded the gap as a mutation
row rather than implying coverage. F2 had to touch that function anyway — the model needs the same
guarded, budgeted, credential-carrying request with a different parser — so the shared half became
`artifactResponse` and `src/api/artifacts.test.ts` now drives **both readers** over five refused URL
shapes: another host, this host absolutely, protocol-relative, outside the API base, and the base
itself.

The pair is the part worth keeping. A guard tested on `fetchArtifact` alone would leave
`fetchArtifactJson` — the newer reader, and the one that runs **without a click** — unprotected,
which is exactly the copy this file's own comment says drifts.

---

## 7. Gate

- `npm run build` — clean, all three phases.
- `npm run lint` — 0 warnings.
- `npm run test:run` — **765/765**, 51 files (723 → 749 → 765).

**Two of the sixteen phase-2 tests failed on their first run, and both were the assertion rather
than the code**: a `<th scope="row">` reads as a `rowheader` and not a cell, and `RUNNING`'s label is
"Building" (now derived from `reportJobStatusLabels` rather than spelled out, so a copy change moves
one file). Phase 1's twenty-six all passed first time — stated plainly rather than taken as evidence
they can fail.

**No contract-hygiene tests were generated by this slice**, and that is a report about the checks
rather than a gap: `fetchArtifactJson` is not an `api.*` call site, so `contractOperations` and
`apiResponseTypes` correctly have nothing to say about a route whose response the contract declares
as binary.

---

## Found and not fixed

- **The `report:export` gate on the on-screen view** — §1 above. The slice's finding; the fix is the
  server's, and it is two lines of argument away from being R5's or R6's opening move.
- **`EvidencePanel`'s anchor is still a 401 in live mode** — §89 named it, this slice did not sweep
  it. It is the same class as §89's finding and a different fix (evidence is served `inline`).
- **Still no operation that lists jobs.** This route makes one report durable; it does nothing for
  the set of them.
- **§86/§87's three open rows are untouched**: the unobservable bytes-first ordering, the two
  unasserted scheduler company loops, and `case_health`'s missing index on `failing_repeatedly`.

---

## Verification status

**Built and unverified.** Per §65 **zero mutations were run inline** and no browser pass was driven.
Queued this slice:

- `docs/verification/frontend-browser-checks.md` — **eight rows (26–33)**. Row 26 is the one that
  proves the slice (screen and file, line by line — no gate can compare an HTML file to a React
  tree); row 31 is the finding, live-only.
- `docs/verification/backend-mutation-checks.md` — **six frontend rows**, and §89's untested-guard
  row is **struck through** because this slice closed it.

---

## Named next

- **R5 — saved report definitions get a server.** The panel, its empty state and the "no Save
  control" note have been waiting since §89, and `definitionId` on generate is what makes §6e's
  *"regenerates next release with one click"* real.
- **R6 — the aggregate execution report**, which is where the dialog grows the policy and filter
  fields and where the header's aggregation-policy metadata stops reading "not applicable".
- **The model's permission**, from §1 — small, backend, and it makes a role the suite already builds
  mean what its name says.
- **A way to list jobs.** Third slice running that this has been the honest answer to "where did my
  report go".
