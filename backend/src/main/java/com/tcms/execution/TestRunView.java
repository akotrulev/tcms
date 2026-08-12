package com.tcms.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code TestRun} in {@code docs/specs/openapi.yaml} — one row of the run list.
 *
 * @param planKey resolved server-side, in one query for the whole page. Letting the SPA join it
 *                against a plan list it may not have loaded breaks the moment the run list is shown
 *                on its own, and puts a second copy of "which plan is this" in the client.
 * @param counts  the result bar. Never percentages — see {@link ResultCountsView}.
 * @param commitSha the code this run tested, if it is known — Git traceability (plan §3 phase 2).
 *                <strong>Here, and not only on the detail, because the detail is built from this
 *                record</strong> ({@link TestRunDetailView#of}) — a field absent from the list view
 *                cannot reach the screen that renders it. The run <em>list</em> deliberately shows
 *                neither: a commit id is a coordinate somebody follows once they are looking at one
 *                run, and a table column of shas is noise in the cell where the release already is.
 * @param branch  the branch that commit came from.
 */
public record TestRunView(
        UUID id,
        String key,
        String name,
        UUID planId,
        String planKey,
        ConfigurationView configuration,
        RunStatus status,
        String releaseVersion,
        String buildNumber,
        String commitSha,
        String branch,
        RunSource source,
        Instant startedAt,
        Instant completedAt,
        ResultCountsView counts) {
}
