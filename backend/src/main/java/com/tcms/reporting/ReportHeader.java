package com.tcms.reporting;

import java.util.List;

/**
 * The block §6e makes mandatory at the top of every report — <strong>what this document is of, who
 * asked for it, when, and over what</strong>.
 *
 * <h2>Why it is not part of {@link ReportModel}</h2>
 *
 * <p>A {@link ReportComputation} is handed a project and a parameter object and knows nothing about
 * the job that asked for it: not who requested it, not when the worker got to it, not which company
 * the deployment is serving. Every field here is the <em>job's</em> knowledge, so a computation
 * could only receive it in order to hand it straight back — and a report type that forgot to would
 * produce a headerless document that renders perfectly.
 *
 * <p>{@link ReportDocument} is therefore what a renderer is given, and this half is built by
 * {@link ReportHeaders} from the job row. A renderer cannot produce an unheaded document because it
 * is never handed a model on its own.
 *
 * <h2>The two defects it exists to prevent, both from §6e and plan §390</h2>
 *
 * <ul>
 *   <li><em>"Two teams reading '94% pass' can mean entirely different things."</em> The selection is
 *       printed rather than recorded in metadata, so a number that was computed over one run cannot
 *       be read as the release's. {@link #selection()} is the <strong>complete</strong> parameter
 *       set as it was sent — see its own note.</li>
 *   <li>Plan §390: <em>"an aggregate report run by a regular user silently covers less than the same
 *       report run by a Company Admin… the report header must state the scope it actually covered,
 *       or two people will compare incomparable numbers."</em> That is §83's and §84's bound — two
 *       people asking for the same team's board receive different boards — with the answer printed
 *       on paper and forwarded to a client, which is what makes stating it non-negotiable rather
 *       than nice. {@link #projectScope()} and {@link #scopeNote()} are that statement.</li>
 * </ul>
 *
 * <h2>The aggregation policy is deliberately NOT a field here</h2>
 *
 * <p>§6e's header list names it, and it is the one item on that list only the report itself can
 * answer: which policy applies, whether one applies at all, and what it means for <em>this</em>
 * report's numbers ({@code RunSummaryReport} prints "not applicable" and says why; R6's aggregate
 * report takes it as a user-chosen parameter). So it stays in {@link ReportModel#metadata()}, and
 * every renderer prints this header and that metadata as <strong>one block</strong>. A field here
 * would be a second place to put it, which is how one of the two comes to be blank.
 *
 * <h2>Every component is a pre-formatted string</h2>
 *
 * <p>{@link ReportModel}'s rule, for {@link ReportModel}'s reason: formatting once is what makes
 * "what you see is what exports" a property of the design. It also means a renderer needs no clock,
 * no locale and no zone — a header that read differently per JVM would be the one part of the
 * document that is about provenance.
 *
 * @param reportType the durable wire string ({@code run_summary}), not a class name and not a title
 * @param description the computation's own one-line description of what this report is
 * @param company the company's display name. §6u's logo and brand colours are <strong>not</strong>
 *     here: {@code company.logo_storage_key} and the two colour columns are unmapped and have no
 *     writer anywhere (branding is M6), and a per-project override column does not exist in the
 *     schema at all — so the alternative to a name is an invented migration for a feature another
 *     milestone owns. Named as not built rather than quietly dropped.
 * @param projectScope the project this report covers, as {@code KEY — Name}
 * @param scopeNote what that scope means for the numbers, in a sentence a reader can act on
 * @param generatedBy who asked for it, resolved to a display name
 * @param generatedAt when the worker produced it, formatted UTC
 * @param selection every parameter the job carried, one entry each
 */
public record ReportHeader(
        String reportType,
        String description,
        String company,
        String projectScope,
        String scopeNote,
        String generatedBy,
        String generatedAt,
        List<ReportModel.Entry> selection) {

    public ReportHeader {
        selection = List.copyOf(selection);
    }

    /**
     * The header as label/value pairs, in the order a header reads.
     *
     * <p>Derived here rather than in each renderer, so three formats cannot print three different
     * subsets of it — and so {@link #selection()} is appended in one place with one label. The
     * {@code ReportRenderingTest} check that every component of this record reaches the rendered
     * output goes through this method, which is what makes a new field a failing test rather than a
     * field nobody prints.
     */
    public List<ReportModel.Entry> entries() {
        return List.of(
                new ReportModel.Entry("Report", description),
                new ReportModel.Entry("Report type", reportType),
                new ReportModel.Entry("Company", company),
                new ReportModel.Entry("Project scope", projectScope),
                new ReportModel.Entry("Scope", scopeNote),
                new ReportModel.Entry("Generated by", generatedBy),
                new ReportModel.Entry("Generated at", generatedAt));
    }
}
