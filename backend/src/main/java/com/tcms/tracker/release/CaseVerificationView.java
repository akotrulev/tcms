package com.tcms.tracker.release;

import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code CaseVerification} — one case and the stories it verifies.
 *
 * <p>The same fact as {@link IssueTestCaseView} read from the other end, and served from
 * {@code tracker} rather than widened onto {@code TestCaseSummary}: that would make
 * {@code repository} read {@code issue_link} and {@code issue} on the request path of the product's
 * busiest list, a fourth foreign-table seam in that module. From this side it crosses no boundary at
 * all — both tables are this module's — and the client merges it into the grid (plan 013 D-i).
 *
 * <p><strong>Only cases with at least one link appear.</strong> A case with none is absent rather
 * than present with an empty array, so the payload is proportional to the links and not to the
 * catalogue; the column renders an em dash for a key it does not find, which is also what it renders
 * while the read is in flight.
 */
public record CaseVerificationView(UUID caseId, List<IssueRefView> issues) {
}
