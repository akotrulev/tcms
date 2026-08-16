package com.tcms.repository;

/**
 * The body of {@code PUT /projects/{projectId}/cases/{caseId}/status} — {@code CaseStatusInput}.
 *
 * <p>A {@code String} rather than a {@link LifecycleStatus}, for {@link SuiteStatusRequest}'s
 * reason: Jackson's failure on an unknown enum constant is a 400 raised in the message converter,
 * before any handler runs, with a body naming the Java type and its constants. Parsed in the service
 * instead, the same mistake is a field error the form can land on.
 *
 * <p>A record of its own rather than a reuse of {@link SuiteStatusRequest}, and the two are
 * identical today. They are separate because the operations are, and
 * {@code ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers} compares each contract
 * schema against the record that serves it — one record serving two schemas would make a field added
 * to one of them a field on both, silently.
 *
 * @param status one of {@code DRAFT}, {@code IN_REVIEW}, {@code APPROVED}, {@code DEPRECATED} — an
 *               absolute target, not a verb. Which of them a given case may move to, and what each
 *               move costs in permissions, is {@link LifecycleLadder}'s question and not the
 *               client's.
 */
public record CaseStatusRequest(String status) {
}
