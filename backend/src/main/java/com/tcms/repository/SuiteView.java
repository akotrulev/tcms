package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code Suite} in {@code docs/specs/openapi.yaml} — the wire shape.
 *
 * <p>A DTO for the same reason {@code ProjectView} is one: {@code companyId} must never leave the
 * building (ERD §0.1 point 2), and echoed back it invites the next author to accept it on the way
 * in. {@code projectId} is withheld too — it is already in the request path, and a body field that
 * could disagree with the path is a field someone will eventually trust over the path.
 * {@code position} is dropped because the array's order carries it and two authorities for order
 * is one too many.
 *
 * <p><strong>{@code parentId} and {@code path} left this record in §98</strong>, with the tree.
 * They are worth a line because the pair was load-bearing and is easy to reach for again: the tree
 * was built from {@code parentId} and never from {@code path} (the two were independent —
 * reparenting rewrote paths, and a consumer parsing a path would have needed the label-sanitizing
 * rules to get back to a row). A suite is a flat collection now, so a client assembles nothing.
 *
 * @param caseCount the suite's resolved membership — the cases filed in it, plus those added
 *                  individually, plus every live case carrying any of its {@link #tagRules},
 *                  unioned and deduped by {@link SuiteMembership}. Since §98 there is no subtree
 *                  for it to be confused with, and it is also exactly what the case grid's
 *                  {@code suiteId} filter selects: one query, so the number beside a suite and the
 *                  length of the list you get by clicking it cannot disagree.
 *                  <p><strong>These counts do not sum to the project's case count</strong>, and
 *                  that is the design rather than a defect: a case can be in three suites at once.
 * @param tagRules  the tags this suite claims, <strong>unioned</strong> — either, not both. The
 *                  opposite of the case grid's {@code tag} filter, which ANDs, because a collection
 *                  is being assembled there and a list narrowed here.
 * @param caseIds   the cases added to this suite <em>individually</em> — the explicit arm only.
 *                  A case merely filed here, or matched by a tag rule, is not in this list: it is
 *                  what an editor has to render and reconcile, not the membership.
 * @param preconditions the suite's own setup conditions, in the author's order. <strong>Two lists
 *                  since §186, where there was one {@code conditions}</strong> — {@code
 *                  CaseConditionRef} carries no {@code kind}, so a single array served both kinds
 *                  undifferentiated and a suite lost a distinction {@code suite_condition.kind} has
 *                  stored since M0. Which array a row is in is what the missing field would have
 *                  said, which is how {@code TestCaseDetailView} has always answered it.
 * @param postconditions cleanup, on the same terms. Resolved and deduped independently of
 *                  {@link #preconditions} — the same library entry legitimately appears in both.
 * @param lockVersion sent back on a rename so a concurrent edit is a 409 rather than a silent
 *                  overwrite (plan §6j). The one internal field that is on the wire on purpose.
 */
public record SuiteView(
        UUID id,
        String key,
        String name,
        String description,
        long caseCount,
        List<String> tagRules,
        List<UUID> caseIds,
        LifecycleStatus lifecycleStatus,
        List<SuiteConditionView> preconditions,
        List<SuiteConditionView> postconditions,
        long lockVersion) {
}
