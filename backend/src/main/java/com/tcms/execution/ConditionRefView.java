package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code CaseConditionRef} in {@code docs/specs/openapi.yaml} — one condition, as the pinned case
 * version recorded it.
 *
 * <p>Read out of {@code test_case_version.snapshot}, where {@code repository.CaseConditions} has
 * <em>already walked the inheritance chain</em>. That is the whole reason this module can serve
 * preconditions at all without reading {@code suite_condition}: the resolution happened once, when
 * the version was authored, and pinning it is what stops a shared condition edited today from
 * changing what a tester was told to check last March.
 *
 * @param inherited kept from the snapshot rather than recomputed. On this screen it is history —
 *                  "this came from the Billing folder <em>at the time</em>" — and recomputing it
 *                  against today's tree would put a live answer beside pinned text.
 */
public record ConditionRefView(
        UUID id,
        int position,
        UUID conditionId,
        String title,
        List<String> body,
        boolean inline,
        boolean inherited,
        String inheritedFrom) {
}
