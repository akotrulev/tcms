package com.tcms.staffing;

import java.util.UUID;

/**
 * {@code Skill} in {@code docs/specs/openapi.yaml} — one word of a company's vocabulary, as much of
 * it as a chip renders.
 *
 * <p><strong>It has the same two components as {@link com.tcms.api.TeamRef} and is not a copy of
 * it.</strong> A skill and a team are different concepts that both reduce, on the wire, to an id and
 * a name; the contract declares two schemas and generates two types, and merging them would make a
 * team's shape a thing this module could change. The names cannot be made more specific either —
 * {@code id} and {@code name} are what {@code openapi.yaml} declares, and a record whose components
 * do not match the schema's properties is a different failure one file over
 * ({@code ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers}). So this pair is
 * recorded in {@code ArchitectureRulesTest.SHAPE_TWINS_BY_DECISION}, in both directions, rather than
 * resolved.
 */
public record SkillView(UUID id, String name) {
}
