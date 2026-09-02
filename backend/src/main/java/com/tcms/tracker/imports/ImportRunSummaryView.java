package com.tcms.tracker.imports;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code settings-import.html}'s table — enough to recognise a past import and choose
 * which report to open, and no more.
 *
 * <p>Deliberately not {@link ImportRunView} reused: the list is served for a whole project, and the
 * detail carries three JSON documents whose combined size is unbounded in the number of statuses
 * and people. A list of thirty reports would be thirty dropped-field lists nobody asked for.
 */
public record ImportRunSummaryView(
        UUID id,
        ImportProvider provider,
        String siteUrl,
        String sourceProjectKey,
        ImportRunStatus status,
        Instant startedAt,
        int issuesImported,
        int statusesMapped,
        int labelsCreated,
        int droppedFieldCount) {
}
