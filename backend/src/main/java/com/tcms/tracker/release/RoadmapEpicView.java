package com.tcms.tracker.release;

import com.tcms.tracker.IssueStatusKind;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The contract's {@code RoadmapEpic} — an epic as a bar on the timeline, or as a line under a
 * release. Shared by the roadmap and by readiness rather than one record each, because the two draw
 * the same thing and a second shape would be a second answer to "how far along is this epic".
 *
 * <p>{@code completionPercent} is D8's number, computed by {@code tracker.Completion} and never
 * recomputed in a component — this repository has shipped two contradictory percentages on one
 * screen, and that is what the rule is for.
 */
public record RoadmapEpicView(
        String key,
        String title,
        LocalDate startDate,
        LocalDate targetDate,
        UUID releaseId,
        IssueStatusKind statusKind,
        Integer completionPercent) {
}
