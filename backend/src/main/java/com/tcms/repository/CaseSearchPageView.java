package com.tcms.repository;

import java.util.List;

/**
 * A page of cross-project hits (§249).
 *
 * <p>{@code PageMeta} + {@code items}, which is the envelope {@code GET /projects/{id}/cases} and
 * {@code /audit-events} already serve — and deliberately not {@code /work-items}' bare array.
 * That endpoint can answer without paging because, as {@code BoardService.personalBoard} puts it,
 * "the ANSWER is person-sized"; a case search is bounded by nothing of the kind, and a company with
 * 40,000 cases can match thousands.
 *
 * <p><strong>{@code total} is computed over the same narrowed set as {@code items}.</strong> That is
 * the whole point of the area: a count taken before the grant narrowing tells the caller how many
 * cases exist in projects they were never granted, which is a name-free existence claim about the
 * client's portfolio (§6t).
 */
public record CaseSearchPageView(int page, int size, long total, List<CaseSearchHitView> items) {
}
