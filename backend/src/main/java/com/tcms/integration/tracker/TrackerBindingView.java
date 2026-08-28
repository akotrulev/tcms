package com.tcms.integration.tracker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code TrackerBinding} in {@code docs/specs/openapi.yaml} — a binding as a settings screen reads
 * it.
 *
 * <p><strong>There is no property here that could carry a credential, and that is checked rather
 * than intended</strong>: {@code ContractConventionTest} compares this record's components to the
 * schema's properties in both directions, so a field added to the entity's view without the
 * contract's knowledge fails the build. Under 004 D2 there is nothing to leak — the row holds no
 * secret — and the guarantee is worth keeping structural anyway, because the shape of the mistake
 * (a view growing a field from an entity beside it) is exactly how the connected-members endpoint
 * would leak one.
 *
 * @param config      the binding's JSONB, parsed. Free-form because the keys are the adapter's.
 * @param lockVersion sent back as {@code If-Match} on the {@code PATCH}.
 */
public record TrackerBindingView(
        UUID id,
        UUID projectId,
        TrackerProvider provider,
        String baseUrl,
        String externalProject,
        Map<String, Object> config,
        boolean isDefault,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        long lockVersion) {
}
