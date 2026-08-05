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
 * @param parentId  null for a root folder. <strong>This is what the tree is built from</strong>,
 *                  not {@link #path} — the contract says so explicitly ("a flat list with
 *                  parentId, client builds the tree"), and the two are independent: reparenting
 *                  rewrites paths, and a consumer that parsed the path would have to know the
 *                  label-sanitizing rules to get back to a row.
 * @param path      the materialized {@code ltree} path, <strong>not</strong> a display string.
 *                  Labels admit only {@code [A-Za-z0-9_]}, so a suite named "UI / E2E" has a path
 *                  that does not spell it. Render {@link #name}; use the path for subtree matching.
 * @param caseCount the suite's <strong>own</strong> resolved membership, not its subtree's — the
 *                  cases filed in it, plus those added individually, plus every live case carrying
 *                  any of its {@link #tagRules}, unioned and deduped by {@link SuiteMembership}.
 *                  The SPA's tree sums a node's count with its children's to get the rollup, so a
 *                  subtree count here would be counted again at every ancestor.
 *                  <p><strong>These counts no longer sum to the project's case count</strong>, and
 *                  that is the design rather than a defect: a case can be in three suites at once.
 * @param tagRules  the tags this suite claims, <strong>unioned</strong> — either, not both. The
 *                  opposite of the case grid's {@code tag} filter, which ANDs, because a collection
 *                  is being assembled there and a list narrowed here.
 * @param caseIds   the cases added to this suite <em>individually</em> — the explicit arm only.
 *                  A case merely filed here, or matched by a tag rule, is not in this list: it is
 *                  what an editor has to render and reconcile, not the membership.
 * @param lockVersion sent back on a rename so a concurrent edit is a 409 rather than a silent
 *                  overwrite (plan §6j). The one internal field that is on the wire on purpose.
 */
public record SuiteView(
        UUID id,
        String key,
        UUID parentId,
        String name,
        String description,
        String path,
        long caseCount,
        List<String> tagRules,
        List<UUID> caseIds,
        LifecycleStatus lifecycleStatus,
        List<SuiteConditionView> conditions,
        long lockVersion) {
}
