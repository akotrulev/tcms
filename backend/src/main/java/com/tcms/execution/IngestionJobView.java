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
        Instant createdAt) {
}
