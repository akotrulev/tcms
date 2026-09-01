package com.tcms.tracker.release;

import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code ReleaseReadiness} — the whole of {@code release.html}, and the one answer to
 * <em>"can we ship 2.4?"</em>.
 *
 * <p>{@code scopeAddedAfterCreation} is the only number here that does not come from live
 * {@code issue} rows. It reads {@code issue_fact.release_assigned_at}, an event-fed projection with
 * a one-minute delivery floor, so it can lag the rest of this payload by a tick — and the screen
 * says so. It is served from there because that timestamp exists nowhere else and cannot be
 * reconstructed after the fact (009 review R12); everything else reads live precisely so a story
 * somebody just closed is not still counted as open on the screen that asks whether the work is
 * finished (plan 013 D-f).
 */
public record ReadinessView(
        UUID releaseId,
        String version,
        ReleaseStatus status,
        ReleaseVerdict verdict,
        ReleaseStoriesView stories,
        ReleaseTestsView tests,
        List<ReleaseBlockerView> blockers,
        List<RoadmapEpicView> epics,
        int scopeAddedAfterCreation) {
}
