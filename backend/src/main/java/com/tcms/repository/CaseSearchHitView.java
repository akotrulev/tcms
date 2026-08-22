package com.tcms.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * One cross-project search result — a <strong>waypoint</strong>, not a grid row (§249).
 *
 * <p>Deliberately not {@code TestCaseSummaryView}. That shape carries the owner, the tags, the
 * classifications, the health signals and the last result, and {@code CaseSummaries} runs four extra
 * queries per page plus the §6u membership resolution to fill them — which across N projects is
 * work for data a search result does not need. Two of its fields ({@code suiteId},
 * {@code suiteName}) name a folder that only exists inside one project.
 *
 * <p>The three project fields are what the caller cannot supply, because not knowing which project
 * holds the case is the premise of the endpoint. They disclose nothing new: the set of projects
 * named here is exactly {@code readAllVisible}'s output, which is {@code project:read}, which is
 * what {@code GET /projects} already serves in full.
 */
public record CaseSearchHitView(
        UUID id,
        String key,
        long keySeq,
        String title,
        UUID projectId,
        String projectKey,
        String projectName,
        LifecycleStatus lifecycleStatus,
        AutomationStatus automationStatus,
        Priority priority,
        Instant updatedAt) {
}
