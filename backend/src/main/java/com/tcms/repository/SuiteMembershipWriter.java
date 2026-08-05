package com.tcms.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reconciles the two membership arms a suite <em>owns</em> — its tag rules and the cases added to
 * it individually. {@link CaseTags}' shape, on the other side of the relationship.
 *
 * <p>It does <strong>not</strong> touch the third arm. Filing is {@code test_case.suite_id} and
 * belongs to the case's own write path: a suite edit that could re-file cases would let two
 * screens move the same column, and the one that loses is whichever saved second. So a suite may
 * claim a case (add it, or write a rule that matches it) and may never move one.
 *
 * <p>Set semantics and no update arm, for {@link CaseTags}' reason: both tables are keyed by their
 * whole content and nothing references them, so a row is present or it is not. Rows the suite
 * already has and still wants are left untouched rather than deleted and reinserted — which is what
 * keeps {@code added_by}/{@code added_at} meaning "who first added this case", instead of "who last
 * saved the suite".
 */
@Component
class SuiteMembershipWriter {

    private final SuiteTagRuleRepository tagRules;
    private final SuiteCaseRepository addedCases;

    SuiteMembershipWriter(SuiteTagRuleRepository tagRules, SuiteCaseRepository addedCases) {
        this.tagRules = tagRules;
        this.addedCases = addedCases;
    }

    /**
     * Applies one suite write's membership half, in the caller's transaction.
     *
     * @param submittedTagRules the rules the suite should claim afterwards, or <strong>null for
     *                          "leave them alone"</strong> — which is what the rename dialog sends
     *                          by not mentioning the field, and what a save must never read as
     *                          "clear them"
     * @param submittedCaseIds  the individually-added cases afterwards, or null on the same terms.
     *                          Already bounded to this project's live cases by {@link SuiteEdit}
     * @param companyId         written onto every row: RLS refuses an insert whose value does not
     *                          match {@code app.current_company} (ERD §0.1)
     */
    void reconcile(
            Suite suite,
            List<String> submittedTagRules,
            List<UUID> submittedCaseIds,
            UUID companyId,
            UUID userId,
            Instant now) {

        if (submittedTagRules != null) {
            reconcileTagRules(suite, submittedTagRules, companyId);
        }
        if (submittedCaseIds != null) {
            reconcileAddedCases(suite, submittedCaseIds, companyId, userId, now);
        }
    }

    private void reconcileTagRules(Suite suite, List<String> submitted, UUID companyId) {
        Set<String> wanted = new HashSet<>(submitted);

        List<SuiteTagRule> stale = new ArrayList<>();
        Set<String> carried = new HashSet<>();
        for (SuiteTagRule rule : tagRules.findForSuite(suite.getId())) {
            if (wanted.contains(rule.getTag())) {
                carried.add(rule.getTag());
            } else {
                stale.add(rule);
            }
        }

        List<SuiteTagRule> added = new ArrayList<>();
        for (String tag : submitted) {
            if (!carried.contains(tag)) {
                added.add(SuiteTagRule.of(suite.getId(), tag, companyId));
            }
        }

        // Deleted and flushed before the insert, for CaseTags' reason: within one flush Hibernate
        // orders inserts before deletes regardless of call order, and a save that removes and
        // re-adds the same tag would be refused by the primary key.
        if (!stale.isEmpty()) {
            tagRules.deleteAll(stale);
            tagRules.flush();
        }
        if (!added.isEmpty()) {
            tagRules.saveAll(added);
        }
    }

    private void reconcileAddedCases(
            Suite suite, List<UUID> submitted, UUID companyId, UUID userId, Instant now) {

        Set<UUID> wanted = new HashSet<>(submitted);

        List<SuiteCase> stale = new ArrayList<>();
        Set<UUID> carried = new HashSet<>();
        for (SuiteCase member : addedCases.findForSuite(suite.getId())) {
            if (wanted.contains(member.getCaseId())) {
                carried.add(member.getCaseId());
            } else {
                stale.add(member);
            }
        }

        List<SuiteCase> added = new ArrayList<>();
        for (UUID caseId : submitted) {
            if (!carried.contains(caseId)) {
                added.add(SuiteCase.of(suite.getId(), caseId, companyId, userId, now));
            }
        }

        if (!stale.isEmpty()) {
            addedCases.deleteAll(stale);
            addedCases.flush();
        }
        if (!added.isEmpty()) {
            addedCases.saveAll(added);
        }
    }
}
