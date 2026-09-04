package com.tcms.staffing;

import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /staffing/people/{userId}/skills} — <strong>the whole set, not a delta.</strong>
 *
 * <p>A chip list is a set, so a body naming one skill takes the others off. That is the opposite
 * reading from every {@code PATCH} in this API, and it is why the operation is a {@code PUT}: the
 * verb is what tells a reader which of the two they are looking at, and B1's defect was an endpoint
 * where it did not.
 */
public record PersonSkillsInput(List<UUID> skillIds) {
}
