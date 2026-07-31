package com.tcms.execution;

import java.util.Map;
import java.util.UUID;

/**
 * {@code Configuration} in {@code docs/specs/openapi.yaml} — the environment a run executed in.
 *
 * <p>{@code companyId} and {@code projectId} are dropped: the first must never leave the building
 * (ERD §0.1 point 2) and the second is already in the request path, where a body field that can
 * disagree with it is a field someone will eventually trust over the path.
 *
 * @param attributes the matrix cell — {@code {"browser":"Chrome","os":"Windows 11"}}. A map because
 *                   the dimensions differ per project, which is also why the column is JSONB.
 */
public record ConfigurationView(
        UUID id, String name, boolean isDefault, Map<String, String> attributes) {
}
