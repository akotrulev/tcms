package com.tcms.tracker.release;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code RoadmapRelease} — one lane of the timeline.
 *
 * <p>It carries its {@code verdict} so a bar can be coloured by state without a second read per
 * release. A release with no target date still gets a lane: the client draws a point marker instead
 * of a bar, which is what {@code work-timeline.html} does for {@code 2.10}.
 */
public record RoadmapReleaseView(
        UUID id,
        String version,
        LocalDate targetDate,
        ReleaseStatus status,
        ReleaseVerdict verdict,
        List<RoadmapEpicView> epics) {
}
