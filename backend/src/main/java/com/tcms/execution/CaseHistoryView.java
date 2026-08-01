package com.tcms.execution;

import com.tcms.project.StatusKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The case timeline's wire shape — the response of {@code GET /cases/{caseId}/history} (plan §6p).
 *
 * <p>All three fields are always sent, which the contract states and this record enforces by having
 * no nullable component. {@code summary} in particular: a case with no executions still gets a
 * summary of zeros, because the panel's job is to state the counts, and an absent summary would
 * render as a failed load rather than as "never executed".
 *
 * @param versionMarkers the authored versions, merged into the timeline by the client. Kept as a
 *                       separate array rather than interleaved server-side because the two carry
 *                       different fields and a discriminated union on the wire would be a shape
 *                       every consumer has to branch on before it can read either.
 */
public record CaseHistoryView(
        SummaryView summary,
        List<EntryView> entries,
        List<MarkerView> versionMarkers) {

    /**
     * The strip above the timeline.
     *
     * @param scope             prose, and it is the server's job rather than the client's: the
     *                          client asked for {@code CURRENT_VERSION} without knowing which
     *                          version that is, and "3 executions, 1 passed" is only readable once
     *                          the reader knows the 3 are all against v7.
     * @param flakinessScore    0–1, or null when the question does not arise. See
     *                          {@link CaseHistoryService} for the scoping that makes it mean
     *                          anything.
     * @param firstFailedAt     the start of the CURRENT failing streak, not the earliest failure
     *                          ever recorded (§6p). Null when the latest result is not a failure —
     *                          there is no current streak to date.
     */
    public record SummaryView(
            String scope,
            int currentVersionNo,
            long executions,
            long passed,
            long failed,
            Instant lastExecutedAt,
            Double flakinessScore,
            Instant firstFailedAt,
            String firstFailedInRelease) {
    }

    /**
     * One recorded result.
     *
     * @param statusName  and {@code statusColor} come from the project's own vocabulary, so a client
     *                    that renamed "Blocked" still reads its own word; {@code statusKind} is what
     *                    any counting reads (see {@code ResultStatuses}).
     * @param runDeleted  the run was withdrawn and the entry survives it — §6p, "the entry marked
     *                    rather than vanishing". The client must not offer navigation into a
     *                    withdrawn run, which answers 404.
     * @param executedByToken always null until M3 ingestion issues tokens. Declared because the
     *                    contract does and because {@code source} can already say {@code INGESTED}:
     *                    a run created by the operator CLI carries that source today, and a field
     *                    that exists for it is better than one added when the first token appears.
     */
    public record EntryView(
            UUID executionId,
            StatusKind statusKind,
            String statusName,
            String statusColor,
            int caseVersionNo,
            String releaseVersion,
            String buildNumber,
            UUID runId,
            String runKey,
            String runName,
            boolean runDeleted,
            String planKey,
            String configuration,
            UserRefView executedBy,
            String executedByToken,
            RunSource source,
            Instant executedAt,
            Long durationMs,
            long evidenceCount,
            List<DefectLinkView> defects) {
    }

    /** One authored version, rendered on the timeline so causation is visible (§6p). */
    public record MarkerView(
            int versionNo,
            Instant occurredAt,
            UserRefView author,
            String changeComment) {
    }
}
