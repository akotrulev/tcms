package com.tcms.tracker.release;

import java.util.List;

/**
 * The contract's {@code Roadmap}.
 *
 * <p><strong>Both arrays are empty on the day this module merges</strong>, on every instance, and
 * that is D14 rather than a defect: nothing is migrated into {@code release}, so the first lane here
 * is one a person typed. A client rendering this has to say so and offer to create one.
 */
public record RoadmapView(List<RoadmapReleaseView> releases, List<RoadmapEpicView> unscheduled) {
}
