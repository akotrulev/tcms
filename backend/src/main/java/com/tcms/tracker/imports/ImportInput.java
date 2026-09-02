package com.tcms.tracker.imports;

import java.util.List;
import java.util.UUID;

/**
 * The confirmation — everything {@code POST /imports} needs, sent once, after a preview was read.
 *
 * <p>The connection is here for the second and last time (D-a): the token was sent with the
 * preview, is sent again here, and is written nowhere either time. That is the cost of a credential
 * with no storage, stated rather than discovered — and it is why the browser holds it in component
 * state between the two steps and a reload means re-typing it.
 *
 * @param statusMapping every source status the preview listed, or the run is refused with a
 *                      {@code 400} naming the ones that are missing
 */
public record ImportInput(
        UUID projectId,
        ImportConnection connection,
        String sourceProjectKey,
        List<StatusMappingEntry> statusMapping,
        ImportOptions options) {
}
