package com.tcms.tracker.release;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The contract's {@code Release} — one release, without its readiness. */
public record ReleaseView(
        UUID id,
        UUID projectId,
        String version,
        String description,
        LocalDate targetDate,
        ReleaseStatus status,
        Instant releasedAt,
        Instant createdAt,
        Instant updatedAt) {

    static ReleaseView of(Release release) {
        return new ReleaseView(
                release.getId(), release.getProjectId(), release.getVersion(),
                release.getDescription(), release.getTargetDate(), release.getStatus(),
                release.getReleasedAt(), release.getCreatedAt(), release.getUpdatedAt());
    }
}
