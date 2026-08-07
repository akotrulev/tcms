package com.tcms.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code AutomationMapping} in {@code docs/specs/openapi.yaml} — one confirmed binding.
 *
 * @param testName the normalized key the match is made on. Named {@code testName} on the wire rather
 *                 than {@code normalizedTestName} because it is the only name a client ever sees or
 *                 sends: the raw form a report used is not stored, and pretending there is a second
 *                 unnormalized field would invite a client to send one.
 * @param caseKey  {@code TC-42}, beside the id. A mapping list is read to answer "which case does
 *                 this test point at", and an id is not an answer a person can check.
 * @param confirmedBy who vouched for it — the whole value of the row (see
 *                 {@link AutomationKeyMapping}). Null only for a row written before this endpoint
 *                 existed, which is no row at all today.
 */
public record AutomationMappingView(
        UUID id,
        String testName,
        UUID caseId,
        String caseKey,
        UUID confirmedBy,
        Instant confirmedAt) {
}
