package com.tcms.tracker.imports;

import java.util.UUID;

/**
 * The body of {@code POST /imports/preview}.
 *
 * @param projectId        the TCMS project the issues would arrive in. Gated: the caller needs
 *                         {@code issue:import} <em>on this project</em>, and the preview is
 *                         computed against this project's own types and statuses.
 * @param connection       the credential, for this operation only (D-a)
 * @param sourceProjectKey {@code PROJ} — a key and not a selection, because
 *                         {@code POST /imports/source-projects} is a convenience for finding it and
 *                         not a step anybody is required to take
 */
public record ImportPreviewInput(
        UUID projectId, ImportConnection connection, String sourceProjectKey) {
}
