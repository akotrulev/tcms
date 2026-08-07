package com.tcms.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code IngestionJobSummary} in {@code docs/specs/openapi.yaml} — one row of the history.
 *
 * <p><strong>A distinct shape from {@link IngestionJobView}, and the difference is the one field it
 * does not have.</strong> {@code unmatched} is not a column: the names live inside
 * {@code raw_payload}, which for a 5,000-test build is megabytes, so a list that carried them would
 * select every blob in the project's history to render one screen. The list is a JPQL constructor
 * projection for exactly that reason — the blob is never selected, rather than selected and dropped.
 *
 * <p>Serving {@link IngestionJobView} here with an empty array would have been the smaller diff and
 * it lies in the way this contract already worries about: {@code unmatchedCount} exists so *"a
 * caller can always tell that {@code unmatched} was cut"*, and an empty array beside a count of 12
 * reads as cut when the truth is "not served on this endpoint". A shape with no such field cannot
 * be misread — the names are on the detail read, which is one request away.
 *
 * @param runKey null both when the job opened no run (nothing matched) and when the run it opened
 *               has since been deleted. The two are told apart by {@link #runId}, which is null only
 *               in the first case.
 */
public record IngestionJobSummary(
        UUID id,
        IngestionStatus status,
        ReportFormat format,
        UUID runId,
        String runKey,
        int matchedCount,
        int unmatchedCount,
        Instant createdAt) {
}
