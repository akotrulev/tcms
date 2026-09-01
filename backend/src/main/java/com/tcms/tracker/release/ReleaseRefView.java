package com.tcms.tracker.release;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The contract's {@code ReleaseRef} — a release as it appears on an issue or a backlog row, and
 * never its readiness.
 *
 * <p>Public because {@code com.tcms.tracker}'s own views carry one: an issue's target release
 * travels on {@code IssueView}, {@code IssueSummaryView} and {@code BacklogRowView}, which are one
 * package up and one package over.
 */
public record ReleaseRefView(UUID id, String version, LocalDate targetDate) {

    public static ReleaseRefView of(Release release) {
        return release == null ? null
                : new ReleaseRefView(release.getId(), release.getVersion(), release.getTargetDate());
    }
}
