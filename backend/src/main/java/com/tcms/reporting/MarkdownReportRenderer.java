package com.tcms.reporting;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link ReportModel} → GitHub-Flavored Markdown. <strong>The first format the product can actually
 * produce</strong>, and it is first on purpose.
 *
 * <h2>Why Markdown before HTML, and long before PDF</h2>
 *
 * <p>§6e's renderer table puts HTML first and PDF behind headless Chromium, and R3's job is to prove
 * the <em>pipeline</em> — request, job row, worker, company context, store, download — rather than a
 * renderer. Markdown needs no template engine, no CSS inlining, no browser and no ~300MB in the
 * image, so it is the format that leaves the least between "the model was computed" and "the bytes
 * were right". HTML is R4. PDF is refused with a 400 at request time (R3 phase 5) rather than
 * accepted into a job that fails an hour later.
 *
 * <p>It is also the format §6e says is "ideal for pasting into a PR, a wiki, or a tracker comment",
 * which is the first thing anybody will do with a run summary.
 *
 * <h2>Escaping is the whole correctness surface here</h2>
 *
 * <p>A GFM table row is delimited by {@code |}. Every cell in this product is user-controlled text —
 * a project's own status name, a suite path a client typed, a colleague's display name — so one
 * pipe in one name silently splits a row into an extra column and shifts every number after it under
 * the wrong heading. It still <em>looks</em> like a report, which is the dangerous kind of wrong, so
 * {@link #cell(String)} escapes pipes and flattens newlines rather than trusting that no data will
 * contain them.
 *
 * <h2>Deterministic bytes</h2>
 *
 * <p>{@code \n} always, never the platform separator, and UTF-8 declared rather than defaulted. §6e's
 * determinism bullet is about being able to regenerate a report and get the same document; a line
 * separator that depends on which machine ran the worker is a diff nobody can explain.
 */
@Component
class MarkdownReportRenderer implements ReportRenderer {

    @Override
    public ReportFormat format() {
        return ReportFormat.MD;
    }

    @Override
    public String contentType() {
        return "text/markdown; charset=utf-8";
    }

    @Override
    public String fileExtension() {
        return "md";
    }

    @Override
    public byte[] render(ReportModel model) {
        StringBuilder out = new StringBuilder();

        out.append("# ").append(inline(model.title())).append("\n\n");

        for (ReportModel.Entry entry : model.metadata()) {
            out.append("- **").append(inline(entry.label())).append(":** ")
                    .append(inline(entry.value())).append('\n');
        }
        if (!model.metadata().isEmpty()) {
            out.append('\n');
        }

        for (ReportModel.Section section : model.sections()) {
            out.append("## ").append(inline(section.heading())).append("\n\n");

            if (section.narrative() != null) {
                out.append(inline(section.narrative())).append("\n\n");
            }
            if (section.table() != null) {
                table(out, section.table());
            }
            for (ReportModel.Series series : section.series()) {
                // §6e: "charts degrade to tables plus an optional linked PNG". The table is the
                // degradation — a chart image would need a rendering step this format exists to
                // avoid, and a number is readable where a missing image is not.
                out.append("### ").append(inline(series.name())).append("\n\n");
                out.append("| Label | Value |\n| --- | ---: |\n");
                for (ReportModel.Point point : series.points()) {
                    out.append("| ").append(cell(point.label())).append(" | ")
                            .append(point.value()).append(" |\n");
                }
                out.append('\n');
            }
        }

        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void table(StringBuilder out, ReportModel.Table table) {
        out.append('|');
        for (String column : table.columns()) {
            out.append(' ').append(cell(column)).append(" |");
        }
        out.append("\n|");
        out.append(" --- |".repeat(table.columns().size()));
        out.append('\n');

        for (List<String> row : table.rows()) {
            out.append('|');
            for (String value : row) {
                out.append(' ').append(cell(value)).append(" |");
            }
            out.append('\n');
        }
        out.append('\n');
    }

    /**
     * A cell's text, made safe to sit between two pipes.
     *
     * <p>Escapes {@code |} and collapses every line break to a space. An empty result would close
     * the cell against its own delimiter in some readers, so an empty value renders as an em dash —
     * which is also what a reader expects to see where there is no value, rather than a gap they
     * have to decide the meaning of.
     */
    private String cell(String value) {
        String flattened = inline(value).replace("|", "\\|");
        return flattened.isBlank() ? "—" : flattened;
    }

    /** Text that must stay on one line — headings, labels, narratives between blank lines. */
    private String inline(String value) {
        return value == null ? "" : value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }
}
