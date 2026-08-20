package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The parse {@link ReviewEndorsedNotifier} and {@link ReviewChangesRequestedNotifier} share.
 *
 * <p>Two handlers read one payload record, so the failure sentence would otherwise be written twice
 * and the two copies would name different records the day somebody renames one.
 */
final class ReviewVerdicts {

    private ReviewVerdicts() {
    }

    /** Throws for {@code RunCompletedNotifier#handle}'s reason — a retry cannot fix a bad shape. */
    static ReviewVerdictPayload parse(ObjectMapper json, String payload, String eventType) {
        try {
            return json.readValue(payload, ReviewVerdictPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + eventType + " payload could not be read as the shape this module "
                            + "declares — see ReviewVerdictPayload", unreadable);
        }
    }
}
