package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The model and its two renderers, with no Spring context and no database — which is the point of
 * splitting them out of the pipeline.
 *
 * <p>Every other test in this module boots Postgres because it is testing a statement. Nothing here
 * is: a renderer defect found through a job, a worker and a store is a defect found five layers away
 * from the eleven lines that caused it. This is also the one class in the module that runs in
 * milliseconds, which matters because the escaping rules below are the kind of thing that gets
 * extended.
 */
@DisplayName("The report model and the two renderers R3 ships")
class ReportRenderingTest {

    /**
     * A header with a distinct, findable value in every component — see {@link Header}, which
     * derives its assertions from this record's own components rather than listing them.
     */
    private static final ReportHeader HEADER = new ReportHeader(
            "run_summary",
            "A single run: status breakdown, progress per tester, and results by suite.",
            "Acme Industries",
            "ACME — Acme Web",
            "This report covers ACME — Acme Web in full and no other project.",
            "Dana Lead",
            "2026-08-04 14:03:22 UTC",
            // E1 — a live report's sentence. The `every component reaches every renderer` test
            // below covers this field the moment it exists, which is what that test is for, and
            // the APOSTROPHE the real sentence carries is dropped here on purpose: that test's
            // premise is that every header value survives verbatim in all three formats, and HTML
            // escapes an apostrophe. See its own note.
            "Live — computed directly from the project records as at 2026-08-04 14:03:22 UTC.",
            List.of(new ReportModel.Entry("runId", "8b0f2c1e-0000-0000-0000-000000000001")));

    private static final ReportModel MODEL = new ReportModel(
            "Run summary — TR-1",
            List.of(new ReportModel.Entry("Pass rate (of recorded)", "50.0%")),
            List.of(
                    new ReportModel.Section(
                            "Results",
                            "Shares are of the 4 executions recorded in this run.",
                            new ReportModel.Table(
                                    List.of("Status", "Executions"),
                                    List.of(List.of("PASSED", "2"), List.of("FAILED", "1"))),
                            List.of(new ReportModel.Series(
                                    "Executions by status",
                                    List.of(new ReportModel.Point("PASSED", 2))))),
                    ReportModel.Section.text("Results by suite", "Nothing yet.")));

    private static final ReportDocument DOCUMENT = new ReportDocument(HEADER, MODEL);

    private static ReportDocument documentOf(ReportModel model) {
        return new ReportDocument(HEADER, model);
    }

    // =====================================================================

    @Nested
    @DisplayName("The model refuses what a renderer cannot draw")
    class Model {

        @Test
        @DisplayName("a row narrower than the header is refused at construction")
        void aRaggedRowIsRefused() {
            // Not pedantry. In Markdown a short row renders as a visibly broken table, which is
            // survivable; in HTML it renders as a NEATLY SHIFTED one, which puts a number under the
            // wrong heading and still looks like a report. Refusing at construction is what keeps
            // the failure in the computation that built it rather than in a document somebody
            // forwarded to a client.
            assertThatThrownBy(() -> new ReportModel.Table(
                    List.of("Status", "Executions"), List.of(List.of("PASSED"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2 columns");
        }

        @Test
        @DisplayName("the lists are copied, so nothing can reorder a model after it is built")
        void theModelIsImmutable() {
            List<ReportModel.Section> sections = new ArrayList<>(MODEL.sections());
            ReportModel model = new ReportModel("t", List.of(), sections);

            sections.clear();

            assertThat(model.sections())
                    .as("§6e's determinism bullet is about reproducing a report, and a renderer "
                            + "that could sort would make one model produce two documents")
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("Markdown — where every correctness question is about escaping")
    class Markdown {

        private final MarkdownReportRenderer renderer = new MarkdownReportRenderer();

        @Test
        @DisplayName("a pipe in a cell does not open a new column")
        void pipesAreEscaped() {
            // The whole reason cell() exists. Every cell in this product is user-controlled — a
            // project's own status name, a suite path a client typed, a colleague's display name —
            // and one pipe shifts every number after it under the wrong heading.
            String out = render(new ReportModel("t", List.of(), List.of(
                    ReportModel.Section.of("S", null, new ReportModel.Table(
                            List.of("Name", "Count"),
                            List.of(List.of("Pass | with comment", "3")))))));

            assertThat(out).contains("| Pass \\| with comment | 3 |");
        }

        @Test
        @DisplayName("a newline in a cell does not break the row")
        void newlinesAreFlattened() {
            String out = render(new ReportModel("t", List.of(), List.of(
                    ReportModel.Section.of("S", null, new ReportModel.Table(
                            List.of("Name"), List.of(List.of("two\nlines")))))));

            assertThat(out).contains("| two lines |");
            assertThat(out.lines().filter(line -> line.startsWith("| two")).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty cell renders as an em dash, not as nothing")
        void blankCellsAreVisible() {
            String out = render(new ReportModel("t", List.of(), List.of(
                    ReportModel.Section.of("S", null, new ReportModel.Table(
                            List.of("Name"), List.of(List.of("")))))));

            assertThat(out).contains("| — |");
        }

        @Test
        @DisplayName("charts degrade to a table of labels and values")
        void seriesRenderAsTables() {
            // §6e: "charts degrade to tables plus an optional linked PNG". The number has to survive
            // — a missing image is unreadable and a number is not.
            assertThat(render(DOCUMENT)).contains("### Executions by status").contains("| PASSED | 2 |");
        }

        @Test
        @DisplayName("headings, metadata and a narrative-only section all render")
        void theWholeModelRenders() {
            String out = render(DOCUMENT);

            assertThat(out).startsWith("# Run summary — TR-1\n");
            assertThat(out).contains("- **Pass rate (of recorded):** 50.0%");
            assertThat(out).contains("## Results\n");
            assertThat(out).contains("| Status | Executions |");
            assertThat(out).contains("## Results by suite\n\nNothing yet.");
        }

        @Test
        @DisplayName("line endings are \\n on every platform")
        void lineEndingsAreDeterministic() {
            // §58: this project lost seven consecutive CI runs to a difference that could not fail
            // on macOS. Two artifacts differing only in line endings are two artifacts.
            assertThat(render(DOCUMENT)).doesNotContain("\r");
        }

        private String render(ReportModel model) {
            return render(documentOf(model));
        }

        private String render(ReportDocument document) {
            return new String(renderer.render(document), StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("JSON — the artifact the on-screen view will read")
    class Json {

        private final JsonReportRenderer renderer = new JsonReportRenderer();

        @Test
        @DisplayName("the whole model survives a round trip")
        void theModelRoundTrips() throws Exception {
            JsonNode parsed = new ObjectMapper().readTree(renderer.render(DOCUMENT));

            assertThat(parsed.get("report").get("title").asText()).isEqualTo("Run summary — TR-1");
            assertThat(parsed.get("report").get("sections")).hasSize(2);
            assertThat(parsed.get("report").get("sections")
                    .get(0).get("table").get("rows").get(0).get(0).asText())
                    .isEqualTo("PASSED");
            assertThat(parsed.get("report").get("sections").get(0).get("series").get(0)
                    .get("points").get(0).get("value").asLong())
                    .as("a chart needs the magnitude, which is why Point carries a number")
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("an absent table is written as null rather than omitted")
        void nullsAreWritten() {
            // F2 has to tell "this section has no table" from "the producer did not know about
            // tables", and an absent key says the second.
            JsonNode parsed;
            try {
                parsed = new ObjectMapper().readTree(renderer.render(DOCUMENT));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(parsed.get("report").get("sections").get(1).has("table")).isTrue();
            assertThat(parsed.get("report").get("sections").get(1).get("table").isNull()).isTrue();
        }

        @Test
        @DisplayName("the header travels with the model, so F2 reads the header the export printed")
        void theHeaderIsStored() throws Exception {
            // Slice-plan decision F is what makes this an assertion rather than a detail: the screen
            // reads THIS artifact. A JSON model with no header would leave F2 either rendering a
            // report with no provenance or reassembling a second header from the job row — two
            // assemblies of one header, which is the drift the one-model architecture exists to
            // prevent.
            JsonNode parsed = new ObjectMapper().readTree(renderer.render(DOCUMENT));

            assertThat(parsed.get("header").get("reportType").asText()).isEqualTo("run_summary");
            assertThat(parsed.get("header").get("generatedBy").asText()).isEqualTo("Dana Lead");
            assertThat(parsed.get("header").get("selection").get(0).get("label").asText())
                    .isEqualTo("runId");
        }

        @Test
        @DisplayName("indentation uses \\n, not the platform separator")
        void lineEndingsAreDeterministic() {
            // Jackson's INDENT_OUTPUT alone uses SYSTEM_LINEFEED_INSTANCE, so this is the assertion
            // that the explicit indenter in the renderer is doing something.
            String out = new String(renderer.render(DOCUMENT), StandardCharsets.UTF_8);
            assertThat(out).contains("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("the same model renders to the same bytes twice")
        void isDeterministic() {
            assertThat(renderer.render(DOCUMENT)).isEqualTo(renderer.render(DOCUMENT));
        }
    }

    @Nested
    @DisplayName("HTML — one self-contained file, and escaping is the whole correctness surface")
    class Html {

        private final HtmlReportRenderer renderer = new HtmlReportRenderer();

        /** Every string position in the model, filled with something that is markup if unescaped. */
        private static final String HOSTILE = "<img src=x onerror=alert(1)>";

        @Test
        @DisplayName("nothing in the model can reach the output as markup")
        void everyFieldIsEscaped() {
            // COMPUTED over the whole model rather than asserted field by field: a renderer grows a
            // field at a time and the one that skipped text() is whichever line was added last.
            // Every value in this product is user-controlled — a client's own status name, a suite
            // path somebody typed — so this is the file's entire correctness surface.
            ReportHeader header = new ReportHeader(HOSTILE, HOSTILE, HOSTILE, HOSTILE, HOSTILE,
                    HOSTILE, HOSTILE, HOSTILE,
                    List.of(new ReportModel.Entry(HOSTILE, HOSTILE)));
            ReportModel model = new ReportModel(HOSTILE,
                    List.of(new ReportModel.Entry(HOSTILE, HOSTILE)),
                    List.of(new ReportModel.Section(
                            HOSTILE,
                            HOSTILE,
                            new ReportModel.Table(List.of(HOSTILE), List.of(List.of(HOSTILE))),
                            List.of(new ReportModel.Series(
                                    HOSTILE, List.of(new ReportModel.Point(HOSTILE, 2)))))));

            String out = render(new ReportDocument(header, model));

            // The hostile string never appears as itself — the angle brackets are what make it
            // markup, and `onerror=alert(1)` sitting in the page as TEXT is inert and readable,
            // which is exactly what a report of a project somebody named that should do.
            assertThat(out).doesNotContain(HOSTILE);
            assertThat(out).doesNotContain("<img");
            assertThat(out).contains("&lt;img src=x onerror=alert(1)&gt;");

            // Every position, counted: the title twice (<title> and <h1>), the EIGHT header values
            // (their labels are the header's own and fixed), the selection's prefixed label and its
            // value, the metadata pair, then a heading, a narrative, a column, a cell, a series name
            // and a point label. A renderer that escaped some positions and not others would still
            // satisfy both assertions above; this is what makes them a count rather than a spot
            // check.
            assertThat(out.split("&lt;img src=x onerror=alert\\(1\\)&gt;", -1).length - 1)
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("the file needs no network to render — no URL, no script, no external asset")
        void theFileIsSelfContained() {
            // §6e: "a self-contained file that needs a CDN to draw its diagrams is not
            // self-contained." The reader is a client, from an attachment, months later, possibly
            // offline — and this is also what the PDF slice hands to a browser, where an asset that
            // fails to load is a blank rectangle in a deliverable.
            String out = render(DOCUMENT);

            assertThat(out).doesNotContain("http://").doesNotContain("https://");
            assertThat(out).doesNotContain("<script").doesNotContain("<link").doesNotContain("<img");
            assertThat(out).contains("<style>");
        }

        @Test
        @DisplayName("the header, the sections, the tables and the narratives all render")
        void theWholeDocumentRenders() {
            String out = render(DOCUMENT);

            assertThat(out).startsWith("<!DOCTYPE html>");
            assertThat(out).contains("<title>Run summary — TR-1</title>");
            assertThat(out).contains("<dt>Generated by</dt>\n<dd>Dana Lead</dd>");
            assertThat(out).contains("<h2>Results</h2>");
            assertThat(out).contains("<th>Status</th><th>Executions</th>");
            assertThat(out).contains("<td>PASSED</td><td>2</td>");
            assertThat(out).contains("Shares are of the 4 executions recorded in this run.");
        }

        @Test
        @DisplayName("a series draws a bar AND prints its number")
        void chartsAreCssBarsWithTheNumberBeside() {
            // A JavaScript chart would fail exactly where this document is most likely to be read,
            // and a bar with no number beside it loses the data when a mail client strips the CSS.
            String out = render(DOCUMENT);

            assertThat(out).contains("<h3>Executions by status</h3>");
            assertThat(out).contains("style=\"width: 100.0%\"");
            assertThat(out).contains("<div class=\"value\">2</div>");
        }

        @Test
        @DisplayName("an all-zero series draws no bar rather than a full one")
        void anEmptySeriesDoesNotDrawFullWidth() {
            // 0/0 is NaN, and "width: NaN%" is rendered by most browsers as a FULL bar — a chart
            // saying everything happened, on a report where nothing did, that no reader would
            // question.
            String out = render(documentOf(new ReportModel("t", List.of(), List.of(
                    new ReportModel.Section("S", null, null, List.of(new ReportModel.Series(
                            "Nothing yet", List.of(new ReportModel.Point("PASSED", 0)))))))));

            assertThat(out).contains("style=\"width: 0%\"");
            assertThat(out).doesNotContain("NaN");
        }

        @Test
        @DisplayName("an empty cell renders as an em dash, not as a gap")
        void blankCellsAreVisible() {
            String out = render(documentOf(new ReportModel("t", List.of(), List.of(
                    ReportModel.Section.of("S", null, new ReportModel.Table(
                            List.of("Name"), List.of(List.of(""))))))));

            assertThat(out).contains("<td>—</td>");
        }

        @Test
        @DisplayName("the same document renders to the same bytes, with \\n on every platform")
        void isDeterministic() {
            assertThat(renderer.render(DOCUMENT)).isEqualTo(renderer.render(DOCUMENT));
            assertThat(render(DOCUMENT)).doesNotContain("\r");
        }

        private String render(ReportDocument document) {
            return new String(renderer.render(document), StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("The header — mandatory on every format §6e ships")
    class Header {

        @Test
        @DisplayName("every component of the header reaches every renderer's output")
        void nothingInTheHeaderIsSilentlyDropped() {
            // A COMPUTED check, and deliberately not a list of assertions: this project's standing
            // rule is that a catalogue-wide invariant needs one. The failure it prevents is a field
            // added to ReportHeader — a milestone, a branding line, R6's aggregation policy — that
            // one renderer prints and another quietly does not, which nobody sees until two people
            // compare an HTML report against the Markdown of the same job.
            //
            // The header's string values are chosen to contain nothing a format escapes, so "the
            // value appears verbatim" is a fair question to ask of Markdown, JSON and HTML alike.
            for (ReportRenderer renderer : List.of(
                    new MarkdownReportRenderer(),
                    new JsonReportRenderer(),
                    new HtmlReportRenderer())) {

                String out = new String(renderer.render(DOCUMENT), StandardCharsets.UTF_8);

                for (RecordComponent component : ReportHeader.class.getRecordComponents()) {
                    if (component.getType() != String.class) {
                        continue;
                    }
                    Object value;
                    try {
                        value = component.getAccessor().invoke(HEADER);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                    assertThat(out)
                            .as("%s must print the header's %s",
                                    renderer.getClass().getSimpleName(), component.getName())
                            .contains((String) value);
                }

                assertThat(out)
                        .as("%s must print the selection the job carried",
                                renderer.getClass().getSimpleName())
                        .contains("runId")
                        .contains("8b0f2c1e-0000-0000-0000-000000000001");
            }
        }

        @Test
        @DisplayName("the block is provenance, then selection, then the report's own metadata")
        void theBlockIsAssembledOnce() {
            // One assembly for every renderer (ReportDocument#headerBlock), so three formats cannot
            // print three different subsets. The report's metadata is part of the header block
            // because that is where the aggregation policy and every denominator live — a second
            // place to put the policy is how one of the two comes to be blank.
            List<String> labels = DOCUMENT.headerBlock().stream()
                    .map(ReportModel.Entry::label)
                    .toList();

            assertThat(labels).containsExactly(
                    "Report",
                    "Report type",
                    "Company",
                    "Project scope",
                    "Scope",
                    "Generated by",
                    "Generated at",
                    // E1 — last of the provenance lines, because it is the one that qualifies
                    // every number under it.
                    "Data freshness",
                    "Selection — runId",
                    "Pass rate (of recorded)");
        }

        @Test
        @DisplayName("a caller's own parameter name cannot displace a provenance line")
        void selectionEntriesArePrefixed() {
            // A parameter is named by the client and could be called anything, including "Company".
            // Merged in flat, that row would read as the header's own and the document would lie
            // about itself.
            ReportHeader spoofed = new ReportHeader(
                    HEADER.reportType(), HEADER.description(), "Acme Industries",
                    HEADER.projectScope(), HEADER.scopeNote(), HEADER.generatedBy(),
                    HEADER.generatedAt(), HEADER.dataFreshness(),
                    List.of(new ReportModel.Entry("Company", "Somebody Else Ltd")));

            List<ReportModel.Entry> block = new ReportDocument(spoofed, MODEL).headerBlock();

            assertThat(block).contains(new ReportModel.Entry("Company", "Acme Industries"));
            assertThat(block).contains(
                    new ReportModel.Entry("Selection — Company", "Somebody Else Ltd"));
        }
    }

    @Nested
    @DisplayName("The registries — what makes a refusal derived rather than a list")
    class Registries {

        private final ReportRenderers renderers = new ReportRenderers(
                List.of(new MarkdownReportRenderer(), new JsonReportRenderer(),
                        new HtmlReportRenderer()));

        @Test
        @DisplayName("MD, HTML and JSON are supported; the three with no renderer are not")
        void supportIsDerivedFromTheBeansPresent() {
            assertThat(renderers.supported())
                    .containsExactly(ReportFormat.MD, ReportFormat.HTML, ReportFormat.JSON);
            assertThat(renderers.supports(ReportFormat.HTML))
                    .as("R3 asserted this was FALSE and said the answer changes with the renderer "
                            + "rather than with a list somebody maintains — this is that change")
                    .isTrue();
            assertThat(renderers.supports(ReportFormat.PDF)).isFalse();
            assertThat(renderers.supports(ReportFormat.CSV)).isFalse();
            assertThat(renderers.supports(ReportFormat.XLSX)).isFalse();
        }

        @Test
        @DisplayName("a format with no renderer is loud, never a silent empty artifact")
        void requiringAnUnsupportedFormatThrows() {
            assertThatThrownBy(() -> renderers.require(ReportFormat.PDF))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("two renderers claiming one format refuse to start")
        void duplicateFormatsAreRefusedAtStartup() {
            // A coin-toss over which bytes a client downloads would present as an intermittently
            // wrong artifact rather than as a wiring mistake.
            assertThatThrownBy(() -> new ReportRenderers(
                    List.of(new MarkdownReportRenderer(), new MarkdownReportRenderer())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MD");
        }
    }

    @Nested
    @DisplayName("Parameters — one wording for every bad request")
    class Parameters {

        @Test
        @DisplayName("a missing parameter names itself")
        void aMissingUuidIsRefused() {
            assertThatThrownBy(() -> ReportParameters.none().requireUuid("runId"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runId");
        }

        @Test
        @DisplayName("a value that is not a uuid is a different message")
        void aMalformedUuidIsRefused() {
            assertThatThrownBy(
                    () -> ReportParameters.parse("{\"runId\":\"nope\"}").requireUuid("runId"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a valid id");
        }

        @Test
        @DisplayName("a JSON array is refused rather than read as an object with no fields")
        void nonObjectParametersAreRefused() {
            // The column's CHECK does not constrain the shape, so this is storable — and a report
            // reading fields off it would find none and compute a report of nothing.
            assertThatThrownBy(() -> ReportParameters.parse("[1,2]"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an empty or absent parameter object parses to no parameters")
        void emptyParsesRatherThanThrowing() {
            assertThat(ReportParameters.parse(null).node().isObject()).isTrue();
            assertThat(ReportParameters.parse("{}").node().isEmpty()).isTrue();
        }
    }
}
