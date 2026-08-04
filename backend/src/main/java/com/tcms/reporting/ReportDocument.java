package com.tcms.reporting;

import java.util.ArrayList;
import java.util.List;

/**
 * What a renderer renders: a {@link ReportHeader} and the {@link ReportModel} it heads.
 *
 * <h2>Why the pair is a type and not two arguments</h2>
 *
 * <p>{@link ReportRenderer#render} took a model alone until R4. Adding the header as a second
 * parameter would have worked and would have left every renderer free to ignore it — a document
 * with no provenance renders perfectly, forwards to a client perfectly, and is exactly the artifact
 * §6e's header requirement exists to prevent. A single argument that <em>is</em> the whole document
 * makes "render the report" and "render the report without saying what it is of" the same call, so
 * the second one is not available.
 *
 * <p>It is also what the {@code JSON} artifact stores (slice-plan decision F), so the on-screen view
 * (F2) reads the header the export printed rather than reassembling one from the job row — two
 * assemblies of one header being the drift the one-model architecture exists to prevent.
 *
 * @param header the job's knowledge — who, when, over what, and what scope
 * @param report the computation's knowledge — the title, the metadata and the sections
 */
public record ReportDocument(ReportHeader header, ReportModel report) {

    /**
     * The whole header block, in the order it reads: the job's provenance, then the selection, then
     * the report's own metadata.
     *
     * <p>Assembled here rather than in each renderer, so three formats cannot print three different
     * subsets or three different orders of one block — and so the report's metadata (which is where
     * the aggregation policy and every denominator live, per {@link ReportHeader}) is part of the
     * header everywhere rather than in the header of whichever renderer remembered.
     *
     * <p>Selection entries are prefixed rather than merged in flat: a parameter is named by the
     * client and could be called anything, including "Company", and a header where a caller's own
     * key silently takes the place of a provenance line is a document that lies about itself.
     */
    public List<ReportModel.Entry> headerBlock() {
        List<ReportModel.Entry> block = new ArrayList<>(header.entries());
        for (ReportModel.Entry entry : header.selection()) {
            block.add(new ReportModel.Entry("Selection — " + entry.label(), entry.value()));
        }
        block.addAll(report.metadata());
        return List.copyOf(block);
    }
}
