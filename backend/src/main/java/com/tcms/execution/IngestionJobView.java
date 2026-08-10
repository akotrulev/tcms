package com.tcms.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code IngestionJob} in {@code docs/specs/openapi.yaml} — what a posted report did.
 *
 * <p>{@code companyId} is dropped for the reason every view here gives, and so is
 * {@code rawPayload}: it is the per-test record the job row keeps, and serving it inline would make
 * the response to a 5,000-test build a multi-megabyte document nobody reads. What a caller needs
 * <em>on the response to their own post</em> is the counts and the names that did not match, which
 * is what {@link #unmatched} is.
 *
 * @param runId    the run the results landed in. <strong>Null exactly when nothing matched</strong>
 *                 — a report none of whose tests resolve to a case creates no run, because a run of
 *                 zero cases reports 0 of 0, 100%, complete, which is §99's own defect in a second
 *                 shape.
 * @param runKey   {@code TR-4}, beside the id, so a CI log line is something a person can search
 *                 for. Null with {@link #runId}.
 * @param unmatched the tests that resolved to no case, capped — see
 *                 {@link IngestionService#UNMATCHED_NAMES_SERVED}. {@link #unmatchedCount} is the
 *                 true total, so a caller can always tell that the list was cut.
 * @param reportUrl where the verbatim upload is (§160), or <strong>null when this job has none</strong>
 *                 — which is every row written before §160 and cannot be repaired by a migration.
 *                 <p>Composed by the <em>server</em>, {@code ReportJob.downloadUrl}'s rule and for
 *                 its reason: the client holds no copy of this server's routing. Two consequences
 *                 follow and both are the point. A client keys its control on the URL rather than on
 *                 a status or a boolean, so it cannot render a link to a 404; and the null is the
 *                 only thing on the wire that distinguishes "posted before uploads were kept" from
 *                 "posted since", which no other field can carry because {@code raw_storage_key} is
 *                 not itself served — serving a storage key would put an implementation detail on
 *                 the wire and invite a client to build the URL after all.
 */
public record IngestionJobView(
        UUID id,
        IngestionStatus status,
        ReportFormat format,
        UUID runId,
        String runKey,
        int matchedCount,
        int unmatchedCount,
        List<UnmatchedTestView> unmatched,
        String reportUrl,
        Instant createdAt) {

    /**
     * Null in, null out — {@code ReportJobView.contentUrl}'s rule, and what makes this field's
     * nullability mean the one thing it says.
     *
     * <p>A job with no stored upload must produce <em>nothing to render</em>, not a link to
     * {@code …/ingestions/{id}/report} that 404s: a string that is not null is a string a client
     * will draw a button around. Written once here rather than at the two call sites that build the
     * view, because the two are a post's response and a detail read of the same row and a client
     * that saw a URL from one and null from the other would be right to call it a bug.
     */
    static String reportUrl(UUID projectId, UUID jobId, String rawStorageKey) {
        return rawStorageKey == null
                ? null
                : "/api/v1/projects/" + projectId + "/ingestions/" + jobId + "/report";
    }
}
