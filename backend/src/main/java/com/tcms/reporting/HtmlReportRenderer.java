package com.tcms.reporting;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@link ReportDocument} → <strong>one self-contained HTML file</strong> — §6e's primary format, and
 * the one a client is actually sent.
 *
 * <h2>Self-contained is a requirement, not an aspiration</h2>
 *
 * <p>§6e: <em>"a self-contained file that needs a CDN to draw its diagrams is not
 * self-contained."</em> Nothing here emits a {@code <script>}, a {@code <link>}, an {@code <img>} or
 * any absolute URL: the CSS is inline, the charts are CSS bars rather than a charting library, and
 * there is no font beyond the reader's own stack. That matters because of who opens the file — a
 * client, from an email attachment, months later, quite possibly with no network — and because this
 * is the document a headless browser will print when PDF lands (slice plan §8), where an asset that
 * fails to load is a blank rectangle in a deliverable. {@code HtmlIsSelfContained} in
 * {@code ReportRenderingTest} is the check, and it is a check rather than a habit for §65's reason:
 * the one place a {@code https://} would creep in is whichever line somebody adds next.
 *
 * <h2>Written by hand, and NOT with Thymeleaf — a deliberate deviation from the slice plan</h2>
 *
 * <p>The M2.5 plan proposes Thymeleaf for this renderer. It is not used, and the reason is worth
 * more than the line it saves. Thymeleaf's argument is escaping-by-default, which is a real one: the
 * whole correctness surface of an HTML renderer is that every value in this product is
 * user-controlled — a client's own status name, a suite path somebody typed, a colleague's display
 * name. But the escaping guarantee is available here without the dependency, because
 * {@link #text(String)} is the <em>only</em> way a string reaches the output and
 * {@code everyFieldIsEscaped} asserts that over a model whose every field is hostile, which is a
 * stronger statement than "a template engine was used". Against it: this is a REST API serving an
 * SPA, with no server-side view layer anywhere, and adding a template engine installs view
 * resolution into an application that has never had any — a new lane, its caching and resolution
 * settings, and a template file whose relationship to the model nothing type-checks. The Markdown
 * renderer already set the convention one slice ago; this file matches it.
 *
 * <h2>Charts are CSS bars, because §6e's alternative is worse in both directions</h2>
 *
 * <p>Markdown degrades a series to a table (a number survives where an image does not). HTML can do
 * better without doing more: a labelled bar whose width is a percentage of the largest value, drawn
 * with a {@code div}. No library, no SVG generation, no font metrics — and the number is printed
 * beside every bar, so a reader whose CSS was stripped by a mail client loses the picture and not
 * the data. A JavaScript chart would fail exactly where this document is most likely to be read.
 *
 * <h2>Deterministic bytes</h2>
 *
 * <p>{@code \n} throughout, UTF-8 declared rather than defaulted, percentages formatted with
 * {@link Locale#ROOT}, and nothing generated from a clock — every timestamp on the page arrived
 * pre-formatted in the {@link ReportHeader}. §6e's determinism bullet is that regenerating an
 * unchanged report reproduces it, and §58 is why the locale and the line ending are spelled out
 * rather than inherited.
 */
@Component
class HtmlReportRenderer implements ReportRenderer {

    /**
     * The whole stylesheet, inline.
     *
     * <p>A system font stack rather than a webfont (a webfont is a network request, see above),
     * millimetre page margins and a {@code print} block, because this file is also what the PDF
     * slice will hand to a browser — and a table that breaks across a page boundary mid-row is the
     * defect that only ever shows up on paper.
     */
    private static final String STYLE = """
            :root { color-scheme: light; }
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial,
                   sans-serif; color: #1a1a1a; background: #fff; margin: 0; padding: 2rem;
                   line-height: 1.5; }
            main, header { max-width: 60rem; margin: 0 auto; }
            h1 { font-size: 1.75rem; margin: 0 0 1rem; }
            h2 { font-size: 1.25rem; margin: 2rem 0 0.5rem; border-bottom: 1px solid #e3e3e3;
                 padding-bottom: 0.25rem; }
            h3 { font-size: 1rem; margin: 1.5rem 0 0.5rem; font-weight: 600; }
            .provenance { border: 1px solid #e3e3e3; border-radius: 6px; padding: 1rem 1.25rem;
                          background: #fafafa; margin-bottom: 1.5rem; }
            .provenance dl { display: grid; grid-template-columns: max-content 1fr; gap: 0.35rem 1rem;
                             margin: 0; }
            .provenance dt { font-weight: 600; color: #4a4a4a; }
            .provenance dd { margin: 0; }
            .narrative { color: #4a4a4a; margin: 0 0 1rem; }
            table { border-collapse: collapse; width: 100%; margin-bottom: 1rem; }
            th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid #e3e3e3;
                     vertical-align: top; }
            th { background: #f4f4f4; font-weight: 600; }
            .chart { display: grid; grid-template-columns: max-content 1fr max-content;
                     gap: 0.25rem 0.75rem; align-items: center; margin-bottom: 1rem; }
            .chart .label { color: #4a4a4a; }
            .chart .track { background: #efefef; border-radius: 3px; height: 0.9rem; }
            .chart .bar { background: #4a6fa5; border-radius: 3px; height: 100%; }
            .chart .value { font-variant-numeric: tabular-nums; }
            @media print {
              body { padding: 0; }
              tr, .chart { break-inside: avoid; }
              h2 { break-after: avoid; }
            }
            """;

    @Override
    public ReportFormat format() {
        return ReportFormat.HTML;
    }

    @Override
    public String contentType() {
        return "text/html; charset=utf-8";
    }

    @Override
    public String fileExtension() {
        return "html";
    }

    @Override
    public byte[] render(ReportDocument document) {
        ReportModel model = document.report();
        StringBuilder out = new StringBuilder();

        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(text(model.title())).append("</title>\n")
                .append("<style>\n").append(STYLE).append("</style>\n")
                .append("</head>\n<body>\n");

        out.append("<header>\n<h1>").append(text(model.title())).append("</h1>\n");
        provenance(out, document.headerBlock());
        out.append("</header>\n<main>\n");

        for (ReportModel.Section section : model.sections()) {
            out.append("<section>\n<h2>").append(text(section.heading())).append("</h2>\n");
            if (section.narrative() != null) {
                out.append("<p class=\"narrative\">").append(text(section.narrative()))
                        .append("</p>\n");
            }
            if (section.table() != null) {
                table(out, section.table());
            }
            for (ReportModel.Series series : section.series()) {
                chart(out, series);
            }
            out.append("</section>\n");
        }

        out.append("</main>\n</body>\n</html>\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The header block — one definition list, in {@link ReportDocument#headerBlock}'s order. */
    private void provenance(StringBuilder out, List<ReportModel.Entry> entries) {
        out.append("<div class=\"provenance\">\n<dl>\n");
        for (ReportModel.Entry entry : entries) {
            out.append("<dt>").append(text(entry.label())).append("</dt>\n")
                    .append("<dd>").append(text(entry.value())).append("</dd>\n");
        }
        out.append("</dl>\n</div>\n");
    }

    private void table(StringBuilder out, ReportModel.Table table) {
        out.append("<table>\n<thead>\n<tr>");
        for (String column : table.columns()) {
            out.append("<th>").append(text(column)).append("</th>");
        }
        out.append("</tr>\n</thead>\n<tbody>\n");

        for (List<String> row : table.rows()) {
            out.append("<tr>");
            for (String value : row) {
                out.append("<td>").append(cell(value)).append("</td>");
            }
            out.append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n");
    }

    /**
     * A series as labelled bars, each scaled against the largest value in that series.
     *
     * <p>Against the largest and never against a total: a series is not required to be parts of a
     * whole ({@code RunSummaryReport}'s is, R7's first-attempt-versus-after-retry is not), and a bar
     * drawn as a share of a total that is not one reads as a percentage nobody computed.
     *
     * <p>An all-zero series draws no bar rather than a full one — {@code 0/0} is the case the obvious
     * arithmetic turns into {@code NaN%} in the style attribute, which most browsers render as a
     * full-width bar and no reader would question.
     */
    private void chart(StringBuilder out, ReportModel.Series series) {
        out.append("<h3>").append(text(series.name())).append("</h3>\n<div class=\"chart\">\n");

        long max = series.points().stream().mapToLong(ReportModel.Point::value).max().orElse(0L);
        for (ReportModel.Point point : series.points()) {
            String width = max <= 0
                    ? "0"
                    : String.format(Locale.ROOT, "%.1f", (point.value() * 100.0d) / max);
            out.append("<div class=\"label\">").append(text(point.label())).append("</div>\n")
                    .append("<div class=\"track\"><div class=\"bar\" style=\"width: ")
                    .append(width).append("%\"></div></div>\n")
                    // The number is printed beside the bar on purpose: a mail client that strips the
                    // stylesheet takes the picture with it, and a report that then says nothing is
                    // worse than one that says it in text.
                    .append("<div class=\"value\">").append(point.value()).append("</div>\n");
        }
        out.append("</div>\n");
    }

    /** An empty cell reads as an em dash, for {@code MarkdownReportRenderer#cell}'s reason. */
    private String cell(String value) {
        String escaped = text(value);
        return escaped.isBlank() ? "—" : escaped;
    }

    /**
     * <strong>The only way a string reaches the output.</strong>
     *
     * <p>Every value in a report is user-controlled, so this is the file's whole correctness surface:
     * one interpolation that skipped it would put a client's own project name into the document as
     * markup. Quotes and apostrophes are escaped as well as the three that matter for text, because
     * the difference between a text position and an attribute position is exactly the distinction a
     * later edit gets wrong.
     */
    private String text(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
