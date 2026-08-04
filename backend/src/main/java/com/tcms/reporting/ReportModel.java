package com.tcms.reporting;

import java.util.List;

/**
 * What a report <em>is</em>, before anybody has decided what it looks like — plan §6e's
 * "{@code ReportModel} ← pure data: title, metadata, sections, tables, chart series", built once per
 * job and handed to every renderer that job produces.
 *
 * <h2>The mistake this type exists to prevent</h2>
 *
 * <p>§6e names it in one line: <em>"the mistake to avoid is writing each report three times"</em>.
 * Three renderers over one model means a fourth format is one renderer rather than N report
 * rewrites, and — the half that matters more here — <strong>the on-screen view and the export cannot
 * disagree</strong>, because slice-plan decision F makes the screen read a stored {@code JSON}
 * artifact of this exact value rather than recomputing anything. A synchronous "preview" endpoint
 * would be a second code path producing a second answer, which is the drift the one-model
 * architecture exists to prevent.
 *
 * <h2>Every cell is a STRING, and that is the load-bearing decision in this file</h2>
 *
 * <p>A table cell is text that has already been formatted, not a number for a renderer to format.
 * The tempting shape is typed cells — numbers stay numbers, and each renderer prints them the way
 * its medium prefers. That is exactly how one screen comes to read 94% while the export of the same
 * model reads 93.5%, and this repository has already paid for the general form of it: §65 records
 * "two contradictory percentages on one screen" as one of the defects that typechecked and linted
 * clean. Formatting <em>once</em>, in the computation, is what makes "what you see is what exports"
 * a property of the design rather than a promise in a comment.
 *
 * <p>{@link Point#value()} is the exception and is not a counter-example: a chart needs magnitude to
 * draw a bar at all, so a series carries the number <em>and</em> the table beside it carries the
 * rendered text. A renderer that has no charts (Markdown — §6e: "charts degrade to tables") never
 * reads it.
 *
 * <h2>Ordering is the model's, never the renderer's</h2>
 *
 * <p>Sections, rows and points come out in the order the computation put them in, and every list
 * here is copied on construction so nothing can reorder one afterwards. §6e's last delivery bullet
 * is determinism — <em>"a report you can't reproduce is a report nobody trusts"</em> — and a
 * renderer that sorted would make the same model produce two different documents depending on which
 * format was asked for first.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p>No generation timestamp, no filter set, no generating user, no branding. §6e requires all four
 * on the output and R4 is the slice that makes the report <em>header</em> mandatory; putting them in
 * the model now would mean either two headers later or a half-built one now. What this type has is
 * {@link #metadata()} — an ordered list of label/value pairs that R4's header will render from and
 * that the Markdown renderer prints today, so the shape does not have to change to acquire one.
 *
 * @param title the document's title. One line, no markup.
 * @param metadata ordered label/value pairs describing what was computed — the selection, the
 *     denominators and anything a reader needs in order to know what the numbers are <em>of</em>.
 *     Ordered rather than a map because a header reads top to bottom and a map does not.
 * @param sections the body, in order
 */
public record ReportModel(String title, List<Entry> metadata, List<Section> sections) {

    public ReportModel {
        metadata = List.copyOf(metadata);
        sections = List.copyOf(sections);
    }

    /** One label/value pair of {@link ReportModel#metadata()}. Both sides are already formatted. */
    public record Entry(String label, String value) {}

    /**
     * A heading, an optional sentence about what the numbers mean, an optional table, and any chart
     * series drawn beside it.
     *
     * <p><strong>{@code narrative} is not decoration.</strong> It is where a section says what its
     * denominator is — "of the executions recorded", not "of the cases in the run" — and a section
     * whose numbers could be read two ways without one is a section that will be read the wrong way
     * by somebody who was not in the room.
     *
     * @param heading required
     * @param narrative nullable — omitted when the heading and the column names say everything
     * @param table nullable — a section may be narrative alone
     * @param series never null; empty when there is nothing to chart
     */
    public record Section(String heading, String narrative, Table table, List<Series> series) {

        public Section {
            series = List.copyOf(series);
        }

        /** A section that is a table and nothing else. */
        public static Section of(String heading, String narrative, Table table) {
            return new Section(heading, narrative, table, List.of());
        }

        /** A section with nothing to tabulate — used to say so rather than to render blank. */
        public static Section text(String heading, String narrative) {
            return new Section(heading, narrative, null, List.of());
        }
    }

    /**
     * Column headings and rows of pre-formatted cells.
     *
     * <p>The constructor refuses a row whose width is not the header's. A short row renders as a
     * broken table in Markdown and as a shifted one in HTML — the second is worse, because it puts
     * a number under the wrong column heading and still looks like a report.
     */
    public record Table(List<String> columns, List<List<String>> rows) {

        public Table {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            for (List<String> row : rows) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException(
                            "A report row must have one cell per column: " + columns.size()
                                    + " columns, row of " + row.size() + " — " + row);
                }
            }
        }
    }

    /** A named series of labelled magnitudes. Charts are R4's; the value is carried from here. */
    public record Series(String name, List<Point> points) {

        public Series {
            points = List.copyOf(points);
        }
    }

    /** One labelled magnitude. {@code label} is what a chart's axis shows. */
    public record Point(String label, long value) {}
}
