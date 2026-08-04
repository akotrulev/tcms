package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            assertThat(render(MODEL)).contains("### Executions by status").contains("| PASSED | 2 |");
        }

        @Test
        @DisplayName("headings, metadata and a narrative-only section all render")
        void theWholeModelRenders() {
            String out = render(MODEL);

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
            assertThat(render(MODEL)).doesNotContain("\r");
        }

        private String render(ReportModel model) {
            return new String(renderer.render(model), StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("JSON — the artifact the on-screen view will read")
    class Json {

        private final JsonReportRenderer renderer = new JsonReportRenderer();

        @Test
        @DisplayName("the whole model survives a round trip")
        void theModelRoundTrips() throws Exception {
            JsonNode parsed = new ObjectMapper().readTree(renderer.render(MODEL));

            assertThat(parsed.get("title").asText()).isEqualTo("Run summary — TR-1");
            assertThat(parsed.get("sections")).hasSize(2);
            assertThat(parsed.get("sections").get(0).get("table").get("rows").get(0).get(0).asText())
                    .isEqualTo("PASSED");
            assertThat(parsed.get("sections").get(0).get("series").get(0)
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
                parsed = new ObjectMapper().readTree(renderer.render(MODEL));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(parsed.get("sections").get(1).has("table")).isTrue();
            assertThat(parsed.get("sections").get(1).get("table").isNull()).isTrue();
        }

        @Test
        @DisplayName("indentation uses \\n, not the platform separator")
        void lineEndingsAreDeterministic() {
            // Jackson's INDENT_OUTPUT alone uses SYSTEM_LINEFEED_INSTANCE, so this is the assertion
            // that the explicit indenter in the renderer is doing something.
            String out = new String(renderer.render(MODEL), StandardCharsets.UTF_8);
            assertThat(out).contains("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("the same model renders to the same bytes twice")
        void isDeterministic() {
            assertThat(renderer.render(MODEL)).isEqualTo(renderer.render(MODEL));
        }
    }

    @Nested
    @DisplayName("The registries — what makes a refusal derived rather than a list")
    class Registries {

        private final ReportRenderers renderers = new ReportRenderers(
                List.of(new MarkdownReportRenderer(), new JsonReportRenderer()));

        @Test
        @DisplayName("MD and JSON are supported; the four formats with no renderer are not")
        void supportIsDerivedFromTheBeansPresent() {
            assertThat(renderers.supported())
                    .containsExactly(ReportFormat.MD, ReportFormat.JSON);
            assertThat(renderers.supports(ReportFormat.PDF)).isFalse();
            assertThat(renderers.supports(ReportFormat.HTML))
                    .as("R4 adds the renderer and this answer changes with it, which is the point")
                    .isFalse();
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
