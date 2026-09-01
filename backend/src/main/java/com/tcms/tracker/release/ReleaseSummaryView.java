package com.tcms.tracker.release;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The contract's {@code ReleaseSummary} — a row in {@code releases.html}'s table.
 *
 * <p>It carries its readiness <strong>inline</strong> because the list draws two meters and a badge
 * per row, and a list of ids the client then resolves one at a time is N+1 over a screen the server
 * already has every answer for.
 */
public record ReleaseSummaryView(
        UUID id,
        String version,
        String description,
        LocalDate targetDate,
        ReleaseStatus status,
        Instant releasedAt,
        Instant createdAt,
        ReadinessSummaryView readiness) {
}
