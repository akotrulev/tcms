package com.tcms.tracker;

import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <strong>only</strong> writer of {@code issue_fact}.
 *
 * <p>Scope §3 states the trap this class exists to close: <em>"issue_fact must be fed by an event,
 * never written by {@code time} or {@code billing}. The moment a downstream module writes it,
 * {@code tracker} is no longer the authority on issue state and the two disagree silently."</em>
 * Silently is the word that matters — nothing fails, no test reddens, and a report is simply wrong.
 * {@code IssueFactProjectionTest} greps the whole main source tree for a second writer, because a
 * rule nothing computes is a sentence somebody has to remember.
 *
 * <p>So the write is here, it is one statement, and the four timestamps are maintained in one place:
 *
 * <ul>
 *   <li>{@code created_at} — the issue's, carried.</li>
 *   <li>{@code status_entered_at} — moves on every accepted transition.</li>
 *   <li>{@code done_at} — set the first time a {@code DONE}-kind status is reached, never cleared by
 *       a reopen. {@link Issue#moveTo} is what makes that true; this copies it, so the projection
 *       cannot re-introduce the defect the entity refuses.</li>
 *   <li>{@code release_assigned_at} — <del>null until M3</del> → <strong>written since M3</strong>,
 *       and it is the one number in M9's burn-up that <strong>cannot be reconstructed after the
 *       fact</strong>: "scope added after the release was created" is the difference between this
 *       and {@code release.created_at}, and an assignment that already happened leaves no other
 *       trace (009 review R12).
 *
 *       <p>Stamped with <strong>this projection's</strong> {@code now()} rather than the moment the
 *       assignment was made, because nothing carries that moment — {@code issue.updated_at} moves
 *       for every edit. The delivery floor is one minute, so the value is "assigned at most one
 *       outbox tick ago", which is the accuracy that chart needs and is why readiness reads live
 *       {@code issue} rows for every other number it serves (013 D-f).</li>
 * </ul>
 *
 * <h2>Whole-company, upsert, and no payload — the same shape {@code execution_fact} has</h2>
 *
 * <p>{@code ExecutionFactRefreshService} is the precedent and this is the same trade: one
 * {@code INSERT … SELECT … ON CONFLICT DO UPDATE} bounded by RLS to the open company, so an
 * at-least-once delivery costs a repeated statement rather than a wrong answer, and a delivery that
 * arrives late writes the current truth rather than a stale one. Nothing is read from the event, so
 * there is nothing in it that can drift from the issue.
 *
 * <h2>An epic's {@code status_kind} is its OWN, exactly as it is on the wire</h2>
 *
 * <p>~~Derived here from the epic's stories (D31), because the endpoints refused to let anybody set
 * an epic's status and a copied {@code status_id} would make a report and the epic's own screen
 * answer differently about the same epic.~~ → superseded (021 R10). The endpoints let the PO set it
 * now, so the stored column IS the answer both give, and the four-armed {@code CASE} that used to
 * live here — the third spelling of a rule that had two too many — is gone with it. The reason for
 * the paragraph has not changed: the projection must say what the wire says, and what the wire says
 * changed. {@code IssueFactProjectionTest} still asserts the two agree rather than trusting this.
 *
 * <p><strong>Nothing downstream changes meaning by accident.</strong> {@code reporting} and
 * {@code work} band this column into burn-up, cycle time and flow, and an epic's kind is still one
 * of the same three — it is now the kind of the status the epic is sitting in rather than a roll-up
 * of the statuses under it. Stories and tasks, which is what those figures are computed over,
 * were never touched by the {@code CASE} at all.
 */
@Service
public class IssueFactProjection {

    private static final Logger log = LoggerFactory.getLogger(IssueFactProjection.class);

    /**
     * The one statement.
     *
     * <p>{@code deleted_at} is mirrored rather than the row being removed, because reporting over a
     * closed period must not change when somebody tidies the backlog afterwards.
     */
    private static final String PROJECT_ISSUES = """
            INSERT INTO issue_fact (
                    issue_id, company_id, project_id, issue_key, title,
                    type_name, type_level, counts_toward_completion,
                    status_name, status_kind, epic_id, parent_id, assignee_id, estimate_hours,
                    release_id, created_at, status_entered_at, done_at, release_assigned_at,
                    deleted_at)
            SELECT i.id, i.company_id, i.project_id, i.issue_key, i.title,
                   t.name, t.level, t.counts_toward_completion,
                   -- 021 R10: the status the issue is IN, at every level. An epic used to get a
                   -- CASE over its children here and a lateral join to spell the derived kind
                   -- with one of this project's own status names; both are gone, because the
                   -- epic's own row is now the answer the wire gives.
                   s.name, s.kind,
                   CASE WHEN pt.level = 'EPIC' THEN p.id
                        WHEN gt.level = 'EPIC' THEN g.id
                   END,
                   i.parent_id, i.assignee_id, i.estimate_hours, i.release_id,
                   i.created_at, i.status_entered_at, i.done_at,
                   CASE WHEN i.release_id IS NOT NULL THEN now() END,
                   i.deleted_at
              FROM issue            i
              JOIN issue_type       t  ON t.id  = i.type_id
              JOIN issue_status     s  ON s.id  = i.status_id
              LEFT JOIN issue       p  ON p.id  = i.parent_id
              LEFT JOIN issue_type  pt ON pt.id = p.type_id
              LEFT JOIN issue       g  ON g.id  = p.parent_id
              LEFT JOIN issue_type  gt ON gt.id = g.type_id
                ON CONFLICT (issue_id) DO UPDATE
               SET issue_key                = EXCLUDED.issue_key,
                   title                    = EXCLUDED.title,
                   type_name                = EXCLUDED.type_name,
                   type_level               = EXCLUDED.type_level,
                   counts_toward_completion = EXCLUDED.counts_toward_completion,
                   status_name              = EXCLUDED.status_name,
                   status_kind              = EXCLUDED.status_kind,
                   epic_id                  = EXCLUDED.epic_id,
                   parent_id                = EXCLUDED.parent_id,
                   assignee_id              = EXCLUDED.assignee_id,
                   estimate_hours           = EXCLUDED.estimate_hours,
                   release_id               = EXCLUDED.release_id,
                   -- FIRST pointed at its CURRENT release, which is what M9's burn-up scope line
                   -- measures against release.created_at. Three arms and each is load-bearing:
                   -- a re-projection must not restamp an assignment that has not changed (or the
                   -- scope line grows every tick); clearing the release clears the moment (a null
                   -- release with a timestamp would claim scope was added to nothing); and a MOVE
                   -- from 2.4 to 2.5 restamps, because it IS scope added to 2.5.
                   release_assigned_at      = CASE
                       WHEN EXCLUDED.release_id IS NULL                                 THEN NULL
                       WHEN issue_fact.release_id IS DISTINCT FROM EXCLUDED.release_id
                            THEN EXCLUDED.release_assigned_at
                       ELSE issue_fact.release_assigned_at
                   END,
                   status_entered_at        = EXCLUDED.status_entered_at,
                   done_at                  = EXCLUDED.done_at,
                   deleted_at               = EXCLUDED.deleted_at
            """;

    private final EntityManager entityManager;

    IssueFactProjection(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Re-projects every issue in the open company.
     *
     * <p>{@code CompanyContext.require()} rather than a parameter, for the reason every read in this
     * codebase gives: the company comes from the session the handler re-established it from, and a
     * parameter would let the caller choose whose rows to rewrite.
     *
     * @return how many rows were written
     */
    @Transactional
    public int refresh() {
        CompanyContext.require();

        int facts = entityManager.createNativeQuery(PROJECT_ISSUES).executeUpdate();
        log.debug("Projected {} issue facts", facts);
        return facts;
    }
}
