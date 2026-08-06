package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code TestRunInput} on the wire — the contract's ten properties and no more.
 *
 * <p>{@code status}, {@code source}, {@code startedAt} and the key are all absent for the reasons
 * {@link TestRun#create} gives: they are the server's to decide, and a field the client can set is a
 * claim the client can make. {@code commit_sha} and {@code branch} exist in {@code V1} and are not
 * on the contract; they arrive with CI ingestion in M3, and accepting them here would be a field
 * read and dropped.
 *
 * @param suiteIds             §99's second scope arm. Whole suites, resolved through their
 *                             membership rather than through {@code test_case.suite_id} — see
 *                             {@link CaseFacts#caseIdsInRunScope}.
 * @param tags                 §99's third. Every live case carrying <em>any</em> of them, which is
 *                             the opposite of {@code GET /cases?tag=} and deliberately so: this is
 *                             a collection being assembled, that is a list being narrowed.
 * @param overrideApprovalGate §6g's <em>"documented lead override for the 2am release"</em> (§82),
 *                             and the one property here that is not a property of the run — it is a
 *                             statement about <em>this request</em>, which is why nothing on
 *                             {@code test_run} stores it and the audit row does.
 *
 *                             <p>It does not nominate a permission, which
 *                             {@link com.tcms.project.ProjectService#writeGranted} forbids and this
 *                             record must not smuggle past. The override is consulted <em>only</em>
 *                             where §6g's gating rule would otherwise refuse the request, and only
 *                             for a caller already holding {@code case:approve} — so it can never
 *                             widen anybody's reach, and it can never turn a request that would
 *                             have succeeded into one that fails.
 *
 *                             <p><strong>Explicit rather than automatic</strong>, which is the
 *                             whole of §6g's word <em>documented</em>. A lead silently exempted
 *                             never discovers the policy their own project turned on, and the run
 *                             carries no record that anything was overridden.
 */
public record RunWriteRequest(
        String name,
        UUID planId,
        UUID configurationId,
        String releaseVersion,
        String buildNumber,
        List<UUID> caseIds,
        List<UUID> suiteIds,
        List<String> tags,
        UUID assigneeId,
        Boolean overrideApprovalGate) {
}
