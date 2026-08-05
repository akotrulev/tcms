package com.tcms.repository;

import java.util.UUID;

/**
 * {@code SuiteInput} in {@code docs/specs/openapi.yaml} — the body of both suite writes.
 *
 * <p>One record for create and rename, because the contract declares one schema for both. The
 * difference between them is not the shape but what {@code parentId} is allowed to say: on a create
 * it chooses the parent, and on a rename it may only repeat the one already stored. See
 * {@link SuiteEdit#validateRename} for why a differing value is refused rather than ignored.
 *
 * <p>What is <strong>not</strong> here is the point of it being a separate type from {@link Suite}:
 * no {@code key}, {@code path}, {@code lifecycleStatus}, {@code position} or {@code lockVersion}.
 * Each is server-owned, and a field on the input schema is a field somebody eventually binds — the
 * key and the path are allocated and derived (§6t), the status is the review workflow's (§6g), and
 * the lock version travels as {@code If-Match} rather than in the body, for the reason
 * {@link CaseController#update} records.
 */
public record SuiteWriteRequest(
        String name,
        UUID parentId,
        String description,

        // Absent (null) means "leave them alone"; an empty list means "clear them". The same split
        // TestCaseInput.tags uses and for the same reason — these are collections, so "not sent"
        // and "sent empty" have different representations, and a rename dialog that sends neither
        // must not empty a collection somebody assembled.
        java.util.List<String> tagRules,
        java.util.List<UUID> caseIds) {
}
