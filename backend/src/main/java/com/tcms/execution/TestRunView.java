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
        RunSource source,
        Instant startedAt,
        Instant completedAt,
        ResultCountsView counts) {
}
