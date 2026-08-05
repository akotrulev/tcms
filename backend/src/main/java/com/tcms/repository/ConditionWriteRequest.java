package com.tcms.repository;

import java.util.List;

/**
 * {@code ConditionInput} in {@code docs/specs/openapi.yaml}, exactly as it arrives on the wire.
 *
 * <p>Serves both the create and the edit, because the two carry the same authored content: a
 * condition has no field a caller would legitimately set on one and not the other, which is also
 * why the edit is a {@code PUT} rather than a {@code PATCH}.
 *
 * <p>{@code usageCount}, {@code suiteUsageCount} and {@code lockVersion} are deliberately absent
 * rather than declared-and-refused (the shape {@link CaseUpdateRequest} uses for its five). The
 * distinction is whether a caller could plausibly believe the field is theirs to set: the counts
 * are derived from the referencing tables and the version is the server's, so there is nothing to
 * mistake. {@code lockVersion} travels as {@code If-Match} — a header, never a body field, so that
 * a precondition about the state the client started from cannot be confused with the state it is
 * asking for.
 */
public record ConditionWriteRequest(String title, ConditionType type, List<String> body) {
}
