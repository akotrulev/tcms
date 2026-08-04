# §88 — The HTML renderer, and the header a renderer cannot strip

**Date:** 2026-08-04 · **Slice:** R4 of `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md` ·
**Three phases, three commits** · **No migration — `V27` is still the head and `V28` still free** ·
**No new table, no new column, no new permission key, policy count unchanged at 59** ·
**Contract: prose only** (two descriptions, `npm run gen:api` re-run).

---

## What was missing, and it was recorded rather than discovered

R3 shipped two renderers and `ReportModel`'s own javadoc names what it left out, in as many words:

> **What is deliberately NOT here.** No generation timestamp, no filter set, no generating user, no
> branding. §6e requires all four on the output and R4 is the slice that makes the report *header*
> mandatory; putting them in the model now would mean either two headers later or a half-built one
> now.

So this slice is two things that had to land together: the header, and the format that is actually
sent to a client. Building the renderer first would have shipped §6e's primary format **without** the
block §6e makes mandatory on it, which is the one combination that produces a document somebody
forwards.

---

## Phase 1 — the header, and the type that makes it unskippable

### The header is the JOB's knowledge, so a computation cannot be asked for it

A `ReportComputation` takes a project and a parameter object. It does not know who requested the
report, when the worker got to it, or which company the deployment is serving — so it could only
receive those in order to hand them straight back, and **a report type that forgot to would produce
a headerless document that renders perfectly.** That is the failure mode: not a crash, a clean
report with no provenance.

Hence `ReportDocument(header, report)`, and `ReportRenderer.render` takes it. The rejected shape is a
second parameter — `render(model, header)` — which works and leaves every renderer free to ignore
the second argument. One argument that **is** the whole document makes "render the report" and
"render the report without saying what it is of" the same call, so the second is not available.

It is also what the `JSON` artifact stores, which is slice-plan decision F doing its job: F2 renders
the header the export printed, rather than assembling a second one from the job row. Two assemblies
of one header is exactly the drift the single-model architecture exists to prevent.

### The aggregation policy is deliberately NOT a header field

§6e's header list names it, and it is the one item on that list **only the report can answer**:
whether a policy applies at all, which one, and what it means for these numbers. `RunSummaryReport`
prints *"Not applicable — one run holds at most one result per case"* and says why; R6's aggregate
report will take it as a user-chosen parameter. A field on `ReportHeader` would be a **second place
to put it**, and a value with two homes is how one of the two comes to be blank.

So it stays in `ReportModel.metadata()`, and `ReportDocument.headerBlock()` is the one assembly:
**provenance, then selection, then the report's own metadata**, in that order, for every format.
Three renderers cannot print three subsets or three orders of a block that is built once.

### The selection is what the CLIENT sent, never what the report understood

`ReportHeaders.selection` reads the stored `parameters` JSON, not the values the computation looked
up. The reason is a defect that no fixture in this repo can currently produce: **a report ignores
parameters it does not know.** A client that sends `environment` to a report with no environment
dimension gets numbers over everything, and a header assembled from what was *used* would print a
selection that silently omits the filter the reader believes narrowed it. Printing what was sent puts
the mismatch on the document instead of leaving it to be discovered by reading the code.

`run_summary` takes one parameter and uses it, so the two readings are indistinguishable today —
which is why the argument is in the code and the mutation row says so rather than claiming a test.

Selection entries are **prefixed** (`Selection — runId`). A parameter is named by the client and
could be called `Company`; merged in flat, that row reads as the header's own and the document lies
about itself. `ReportRenderingTest` asserts the spoof directly.

### §6u branding is a display name, and that is stated rather than skipped

The plan's R4 line asks for *"company logo with per-project override (§6u branding, defaulting to the
company's)"*. What exists: `company.logo_storage_key`, `brand_primary_color` and `brand_accent_color`
are `V1` columns that are **unmapped on the `Company` entity and written by nothing anywhere** —
branding is M6 — and **there is no per-project override column in the schema at all**. So the
alternative to printing the company's display name is inventing a migration for another milestone's
feature, in a slice whose contract with the plan is that `V27` stays the head. The header carries the
name; `ReportHeader`'s javadoc names the rest as not built.

### Nothing in `ReportHeaders` can fail a report

Every lookup falls back to a printable value: a project deleted between the request and the worker, a
company row that cannot be read, an account offboarded while its job sat in the queue. None of those
is a reason to refuse a report whose numbers are right, and **a `FAILED` job carrying "could not read
the company" reads as the report itself being broken.** `RunSummaryReport` makes the same call for
its tester column.

The header is built **after** the computation, for the mirror-image reason: a report that refuses its
parameters must fail with *that* sentence, not with whatever the header assembly hit first.

### The completeness check is computed, not a list

`ReportHeader` has seven string components. The test iterates `getRecordComponents()` and asserts each
value reaches **every renderer's** output. The defect that catches: a field added later — a milestone,
a branding line, R6's policy if that decision is ever revisited — that one renderer prints and another
quietly does not, which nobody sees until two people compare the HTML and the Markdown of one job.
This project's standing rule is that a catalogue-wide invariant needs a computed check rather than a
careful reading.

---

## Phase 2 — the HTML renderer

### Self-contained is a requirement with a test, not an aspiration

§6e: *"a self-contained file that needs a CDN to draw its diagrams is not self-contained."* Nothing
emits a `<script>`, a `<link>`, an `<img>` or an absolute URL — the CSS is inline and the charts are
CSS bars. Who opens the file is the argument: a **client**, from an email attachment, months later,
quite possibly offline. And this same document is what the PDF slice will hand to a headless browser,
where an asset that fails to load is a blank rectangle in a deliverable.

### Written by hand, and NOT with Thymeleaf — a deviation from the plan, recorded

The M2.5 plan proposes Thymeleaf for this renderer. It is not used.

Thymeleaf's argument is escaping-by-default and it is a real one: the entire correctness surface of
an HTML renderer is that **every value in a report is user-controlled** — a client's own status name,
a suite path somebody typed, a colleague's display name. But that guarantee is available here without
the dependency, because `text(String)` is the only way a string reaches the output and the test
asserts it over a model whose every field is hostile — **a stronger statement than "a template engine
was used"**. Against the dependency: this is a REST API serving an SPA with no server-side view layer
anywhere, so the starter installs view resolution into an application that has never had any, plus
its resolution and caching settings, for one template whose relationship to the model nothing
type-checks. `MarkdownReportRenderer` set the convention one slice ago and this file matches it.

### The escaping test is a COUNT

`doesNotContain("<img")` passes cleanly against a renderer that escapes *some* positions. So the test
asserts the hostile string appears escaped **exactly 19 times** — one per interpolation, enumerated in
the comment: the title twice, the seven header values, the selection's prefixed label and its value,
the metadata pair, then a heading, a narrative, a column, a cell, a series name and a point label.

### Two chart decisions, one defect each

- **A bar is scaled against the largest value in its series, never against a total.** A series is not
  required to be parts of a whole (`RunSummaryReport`'s is; R7's first-attempt-versus-after-retry is
  not), and a bar drawn as a share of a total that is not one reads as a percentage nobody computed.
- **An all-zero series draws no bar rather than a full one.** `0/0` is `NaN`, and `width: NaN%` is
  rendered by most browsers as a **full-width** bar — a chart saying everything happened, on a report
  where nothing did, that no reader would question.

The number is printed beside every bar, so a mail client that strips the stylesheet costs the picture
and not the data.

### The format became requestable because a bean exists

Nothing in the controller, the service or the request validation was edited. `ReportRenderers.supports`
answers `HTML` because `HtmlReportRenderer` is on the classpath — which is precisely what R3 built
that class for, and R3's own test said so:

> `assertThat(renderers.supports(ReportFormat.HTML)).as("R4 adds the renderer and this answer changes
> with it, which is the point").isFalse();`

That line is now `isTrue()` with the note kept. A decision made one slice earlier paid for itself.

---

## Phase 3 — end to end, the contract, and the mock

`ReportEndpointTest` downloads an HTML artifact and asserts `text/html`, a `.html` filename and
`<!DOCTYPE html>` in the body. It is the assertion that R4's renderer is reachable **through the
product** and not only from a unit test — a media type or an extension hard-coded anywhere in the
controller path would surface here as a `.md` called `text/markdown` holding a web page.

**Contract: prose only.** `format`'s description said *"Today only `MD` renders … `HTML` is a later
slice"*; it now names both and says what self-contained means. `ReportJob.modelUrl` gained the stored
model's **shape** — `{ header, report }` — with the instruction that the on-screen view must render
the header it finds rather than assembling one from the job row. `npm run gen:api` re-run;
`schema.d.ts` moved by the two descriptions and nothing else.

**The mock moved with it**, because the alternative is §81's and §87's finding a third time: `FORMATS`
gained `HTML` (a registry, not a literal — the same request must not be a 400 in mock mode and a 202
live), each format now carries its own media type and extension, HTML is served as a **whole
document** rather than a fragment, and the model artifact answers `{ header, report }`. A mock still
answering a bare `{ title, sections }` is a shape F2 would be built against and the server does not
serve.

---

## Gates

Every phase ran `./gradlew build`; phase 3 also `npm run build`, `npm run lint` (0 warnings) and the
full Vitest suite.

- Phase 1 — **1620** tests / 319 classes (1615 → 1620)
- Phase 2 — **1627** tests / 320 classes
- Phase 3 — **1628** tests / 320 classes, **0 failures, 0 skipped**, counted from the XML
- Frontend — **705/705** unchanged (no frontend test touches the reporting handler yet)

**One test failed on the first run and it was the assertion, not the code**: the escaping test also
asserted the output does not contain `onerror=alert`, which is wrong — the angle brackets are what
make the string markup, and `onerror=alert(1)` sitting in the page as **text** is inert and readable,
which is exactly what a report of a project somebody named that should do. The corrected assertion is
the count.

---

## Found and not fixed

- **The bytes-before-row ordering is still unobservable** (§87's row, unchanged): nothing in the
  suite can make the store fail.
- **The two schedulers' company loops are still asserted nowhere** (§86 and §87's shared hole).
  R4 adds no scheduler and does not close it.
- **`case_health` still has no index on `failing_repeatedly`** (§86's finding, third slice running).
  R4 has no migration and taking it here would make one slice two things.
- **`ReportHeaders` has no test of its own** — it is covered end-to-end through
  `ReportJobPipelineTest`, because every field it fills is a database read and a unit test would have
  to mock three collaborators to assert string concatenation. Named rather than left implied.

---

## Verification status

**Built and unverified.** Per §65 (restated 2026-08-02) **zero mutations were run inline** and no
browser pass was driven. Queued this slice:

- `docs/verification/backend-mutation-checks.md` — **nine rows**, of which the escaping count and the
  "delete the renderer" pair are the two to run first.
- `docs/verification/frontend-browser-checks.md` — **five rows** (9–13), two mock and three live-only.
  Row 11 is the one no unit test can make: open the saved HTML file **with the network off**.

---

## Named next

- **F1 — the Reports screen**, and **F2 — the on-screen report view**. Both are now unblocked in
  full: the contract, the mock, the stored model *and its header* are all in place, and F2's job is
  to render `{ header, report }` rather than to compose anything.
- **R5 — saved report definitions get a server** (`GET` is declared and unserved; `POST`/`DELETE`
  are the additions).
- **R6 — the aggregate execution report**, which is where `AggregationPolicy` becomes a user-chosen
  parameter and where decision D's cross-check test against `PlanStatisticsService` binds.
- **PDF**, still an infrastructure slice with a devops half (§8 of the plan), still refused with a
  400 — and the HTML this slice ships is what it will print.
