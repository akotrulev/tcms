package com.tcms.tracker;

import java.util.UUID;

/**
 * The contract's {@code IssueTypeRef} — a type as every card and every picker renders it.
 *
 * <p><strong>{@code iconCode} and {@code position} are deliberately not here.</strong> The contract
 * declares four members and {@code ContractConventionTest} compares the two in both directions, so a
 * fifth would be a property Jackson writes that every TypeScript consumer reads as absent. The icon
 * and the order are stored ({@code issue_type}) and served through the workflow document's own
 * ordering — the array is in {@code position} order, which is the same fact without a second
 * spelling of it on the wire.
 */
public record IssueTypeView(UUID id, String name, IssueLevel level, boolean countsTowardCompletion) {

    static IssueTypeView of(IssueType type) {
        return new IssueTypeView(
                type.getId(), type.getName(), type.getLevel(), type.isCountsTowardCompletion());
    }
}
