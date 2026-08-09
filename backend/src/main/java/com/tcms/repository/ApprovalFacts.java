package com.tcms.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names {@code review}</strong> — {@code project}'s table,
 * read to derive {@link HealthSignal#STALE_APPROVAL} (plan §6g).
 *
 * <p>{@code ExecutionFacts}' shape, with one difference worth stating because it changes what the
 * class is: that seam exists because ArchUnit <em>forbids</em> the edge, so the one-file rule is
 * what keeps the crossing findable. This one is a permitted edge — {@code project} is a foundation
 * module every other module may depend on (plan §3), and {@code repository} already imports
 * {@code ReviewStanding} and calls {@code ReviewService} from two write paths. The single file is
 * therefore a convention rather than a boundary, and it is kept for the second half of
 * {@code ExecutionFacts}' argument, which does apply: a second JPA entity mapped over
 * {@code review} would give {@code ddl-auto: validate} two sets of column expectations for one
 * table. SQL through the {@link EntityManager} states plainly what this is — a read of rows another
 * module owns, not a claim on their mapping.
 *
 * <h2>Why not call {@code ReviewService}, which is right there</h2>
 *
 * <p>Because the answer is never needed as a value. The recompute derives one boolean per live
 * case in the company, and a per-case call is one query per case on an hourly job — the shape
 * {@code ExecutionFacts}' header rejects for the same reason. The fact is folded into the statement
 * that consumes it.
 *
 * <p>RLS covers both sides, as it does there: this runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}, the {@code SELECT}
 * can only see the open company's cases and reviews, and the {@code INSERT} can only write that
 * company's rows.
 */
@Component
class ApprovalFacts {

    private final EntityManager entities;

    ApprovalFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Re-derive {@link HealthSignal#STALE_APPROVAL} for every live case in the currently-open
     * company, in one statement — §6g's <em>"case edited since its approval"</em>.
     *
     * <h2>The predicate is a version comparison, not a timestamp comparison</h2>
     *
     * <p>{@code review.subject_version_no} against {@code test_case.current_version_no}, which is
     * the same comparison {@code ReviewPanel} has rendered its stale banner from since §76 —
     * deliberately, because a second definition of "edited since" is a screen and a facet that
     * disagree about the same case. The timestamp spelling ({@code updated_at > approved_at}) looks
     * equivalent and is not: a save whose content hash is unchanged writes no version
     * ({@code CaseVersions.decide}) and still moves {@code updated_at}, so it would report an
     * approval as stale because somebody opened the case and pressed Save.
     *
     * <h2>{@code max(subject_version_no)}, not the newest sign-off</h2>
     *
     * <p>Reviews accumulate — approving does not close the record, and a rescinded approval leaves
     * its row standing (see {@code Review}'s header). So a case can carry several {@code APPROVED}
     * rows, and the question this signal asks is whether the content the case shows now has ever
     * been signed off. The highest approved version answers that; <em>the latest</em> approval
     * answers a different question and answers it wrongly in the one case they differ — a review
     * opened at v3, left open through an edit to v4, approved after a separate review of v4 had
     * already been signed — where ordering by {@code approved_at} reports a case as stale whose
     * current content somebody demonstrably read.
     *
     * <h2>An approval opened before an edit is stale the moment it is granted, and that is §6g</h2>
     *
     * <p>{@code subject_version_no} is stamped at <em>submission</em> and never re-stamped, because
     * re-stamping "would silently transfer a reviewer's attention to content they never saw"
     * ({@code Review}'s header). Under {@code EditAfterApproval.RETAIN} — the default — a case may
     * be edited while in review, so approving it can raise this signal immediately. That is the
     * intended reading and not an edge case: the approval covers the version somebody was asked to
     * read, and the case has moved past it.
     *
     * <h2>Not gated on the project's review settings, deliberately</h2>
     *
     * <p>{@code ReviewService.moved} opens no review at all for a project with §6g switched off for
     * cases, so those projects have nothing for this statement to find and report an honest zero
     * without a policy predicate. Adding one would make the signal <em>disappear</em> for a project
     * that turns review off while approvals granted under it are still on the wall — the record
     * would still say v1 was signed and the case would still say v7, with the dashboard silent
     * about it. {@code ReviewPanel} consults no setting either, which is the agreement that matters:
     * the banner and the facet are one claim.
     *
     * <h2>The case's own status is a term, so the signal self-heals in both directions</h2>
     *
     * <p>{@code lifecycle_status = 'APPROVED'} is in the expression rather than in the
     * {@code WHERE}, and the difference is not cosmetic. In the {@code WHERE} it would be a filter,
     * every non-approved case would be skipped by the statement, and a case flagged stale and then
     * sent back to {@code DRAFT} — which is exactly what {@code EditAfterApproval.RE_REVIEW} does —
     * would keep a signal nothing could ever clear. {@code HealthSignal}'s "no button for lying"
     * rule arriving from the other side.
     *
     * <h2>{@code ON CONFLICT} names two columns</h2>
     *
     * <p>{@code stale_approval} and {@code computed_at}, and nothing else: the row is shared with
     * two other writers and the columns are not (see {@code ExecutionFacts}). This is the first
     * writer of a signal that is not execution-derived, so it is also the first time the rule is
     * load-bearing for a job in this module rather than for a hypothetical future one.
     *
     * @param now stamped into {@code computed_at} — what tells the dashboard "nobody has looked"
     *            apart from "nothing is wrong" (see {@code CaseHealth}'s header).
     * @return how many cases were re-derived, for the caller's log line.
     */
    int recomputeStaleApproval(Instant now) {
        return entities.createNativeQuery("""
                INSERT INTO case_health (
                        case_id, company_id, project_id,
                        stale_approval, computed_at)
                SELECT c.id, c.company_id, c.project_id,
                       c.lifecycle_status = 'APPROVED'
                           AND approved.version_no IS NOT NULL
                           AND approved.version_no < c.current_version_no,
                       :now
                  FROM test_case c
                  LEFT JOIN LATERAL (
                           SELECT max(r.subject_version_no) AS version_no
                             FROM review r
                            WHERE r.subject_id = c.id
                              AND r.subject_type = 'CASE'
                              AND r.state = 'APPROVED'
                       ) approved ON true
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET stale_approval = EXCLUDED.stale_approval,
                       computed_at    = EXCLUDED.computed_at
                """)
                .setParameter("now", now)
                .executeUpdate();
    }
}
