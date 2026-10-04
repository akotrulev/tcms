package com.tcms.project;

/**
 * {@code ProjectColorUpdate} in {@code docs/specs/openapi.yaml} — the body of
 * {@code PUT /projects/{projectId}/color}.
 *
 * @param color boxed and unchecked here, so an absent or unknown value is a field error from
 *              {@link ProjectColorService} and not a deserialization failure
 */
public record ProjectColorRequest(String color) {
}
