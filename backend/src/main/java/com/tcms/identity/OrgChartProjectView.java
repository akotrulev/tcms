package com.tcms.identity;

import java.util.UUID;

/**
 * The contract's {@code OrgChartProject} — one matrix column.
 *
 * <p>A column exists only because the caller holds {@code project:read} on it, so every field here
 * is a fact they could already read from {@code GET /projects}. That is the test a field has to
 * pass to be on this shape at all.
 */
public record OrgChartProjectView(UUID id, String key, String name, boolean archived) {
}
