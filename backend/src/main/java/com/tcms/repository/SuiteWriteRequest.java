package com.tcms.repository;

import java.util.UUID;

/**
 * {@code SuiteInput} in {@code docs/specs/openapi.yaml} — the body of both suite writes.
 *
 * <p>One record for create and rename, because the contract declares one schema for both — and
 * since §98 they differ in nothing at all. The one field the two verbs disagreed about was
 * {@code parentId}: a create chose the parent, a rename could only repeat it, and a differing
 * value was refused rather than ignored. Suites are flat, so the field and the disagreement are
 * both gone.
 *
 * <p>What is <strong>not</strong> here is the point of it being a separate type from {@link Suite}:
 * no {@code key}, {@code lifecycleStatus}, {@code position} or {@code lockVersion}. Each is
 * server-owned, and a field on the input schema is a field somebody eventually binds — the key is
 * allocated (§6t), the status is the review workflow's (§6g), and
 * the lock version travels as {@code If-Match} rather than in the body, for the reason
 * {@link CaseController#update} records.
 */
public record SuiteWriteRequest(
        String name,
        String description,

        // Absent (null) means "leave them alone"; an empty list means "clear them". The same split
        // TestCaseInput.tags uses and for the same reason — these are collections, so "not sent"
        // and "sent empty" have different representations, and a rename dialog that sends neither
        // must not empty a collection somebody assembled.
        java.util.List<String> tagRules,
        java.util.List<UUID> caseIds) {
}
