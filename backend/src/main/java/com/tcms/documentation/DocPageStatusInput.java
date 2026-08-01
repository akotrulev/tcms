package com.tcms.documentation;

/**
 * {@code DocPageStatusInput} in {@code docs/specs/openapi.yaml}.
 *
 * <p>A {@code String} rather than a {@link DocPageStatus}, so an unknown value is a field error
 * naming the three valid ones instead of Jackson's own 400 about a JSON parse — which reaches the
 * SPA as "check your input" with nothing highlighted. The same call
 * {@code WorkItemStatusUpdateRequest} makes for board columns.
 */
public record DocPageStatusInput(String status) {
}
