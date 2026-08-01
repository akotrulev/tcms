package com.tcms.execution;

import java.util.UUID;

/**
 * {@code PlanCaseRef} in {@code docs/specs/openapi.yaml} — a case in a plan's scope, as a plan-level
 * list names it.
 *
 * <p>Not {@code TestCaseSummary}, and the narrowing was a contract change made for this slice. The
 * summary requires an owner, features, tags, health signals, an automation status and an update
 * timestamp; producing them here would mean re-deriving {@code repository.CaseSummaries} inside the
 * one module forbidden to import it, through the seam {@code CaseFacts}' header exists to keep
 * narrow — to serve a panel that renders a key, a title and a suite name.
 *
 * @param keySeq the numeric half of {@link #key}. On the wire because the not-run panel is read as a
 *               work list and {@code TC-10000} sorts before {@code TC-9999} as text — the same rule
 *               the case grid and a run's checklist follow. Ordered correctly by the server too, so
 *               this is the client's means of keeping that order through a re-sort rather than its
 *               only chance to get it right.
 */
public record PlanCaseRefView(
        UUID id, String key, long keySeq, String title, UUID suiteId, String suiteName) {
}
