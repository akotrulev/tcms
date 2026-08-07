package com.tcms.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * One history row as the database returns it — {@link IngestionJobRepository#findSummariesInProject}'s
 * constructor projection, before the run key is resolved.
 *
 * <p>Not the same record as {@link IngestionJobSummary} and deliberately not merged with it: the
 * projection is a list of columns of <em>one table</em>, and {@code runKey} comes from another one,
 * resolved for the whole page in a single query. A record carrying a field the query cannot fill
 * would have to be constructed with a null the JPQL {@code new} expression has to type, which is the
 * awkward half of a shape that exists to avoid a join.
 *
 * <p>It exists at all so the {@code JSONB} {@code raw_payload} is <strong>never selected</strong>
 * for a list — see {@link IngestionJobSummary}'s header.
 */
public record IngestionJobRow(
        UUID id,
        IngestionStatus status,
        ReportFormat format,
        UUID runId,
        int matchedCount,
        int unmatchedCount,
        Instant createdAt) {
}
