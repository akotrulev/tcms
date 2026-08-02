package com.tcms.repository;

import java.util.List;

/**
 * {@code SharedStepInput} in {@code docs/specs/openapi.yaml} — the body of
 * {@code POST /projects/{projectId}/shared-steps}.
 *
 * <p>No {@code usageCount}: it is derived from the references to the entry, so there is nothing a
 * client could say about it that the server would not immediately contradict. A field accepted and
 * discarded is worse than an absent one — it reads as supported.
 *
 * @param title what the picker lists, and what a step referencing this entry shows as its action
 * @param body  the ordered actions. Nullable on the wire so a missing array is a validation error
 *              naming the field rather than a deserialization failure naming nothing
 */
public record SharedStepWriteRequest(String title, List<String> body) {
}
