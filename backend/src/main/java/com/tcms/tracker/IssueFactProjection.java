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
 *   <li>{@code release_assigned_at} — null until M3. It is here now because it is the one number in
 *       M9's burn-up that <strong>cannot be reconstructed after the fact</strong>: "scope added
 *       after the release was created" is the difference between this and
 *       {@code release.created_at}, and an assignment that already happened leaves no other trace
 *       (009 review R12).</li>
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
 * <h2>An epic's {@code status_kind} is DERIVED here, exactly as it is on the wire</h2>
 *
 * <p>D31 puts an epic's status in {@link EpicStatus} and the endpoints refuse to let anybody set it.
 * If this projection copied the stored {@code status_id} instead, a report and the epic's own screen
 * would answer differently about the same epic — which is the disagreement D31 exists to make
 * unrepresentable, reappearing one table over. The {@code CASE} below is
 * {@link EpicStatus#of}'s four arms in SQL, and {@code IssueFactProjectionTest} asserts the two
 * agree rather than trusting this paragraph.
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
            SELECT f.id, f.company_id, f.project_id, f.issue_key, f.title,
                   f.type_name, f.type_level, f.counts_toward_completion,
                   COALESCE(ds.name, f.status_name), f.derived_kind,
                   f.epic_id, f.parent_id, f.assignee_id, f.estimate_hours,
                   NULL, f.created_at, f.status_entered_at, f.done_at, NULL,
                   f.deleted_at
              FROM (
                    SELECT i.id, i.company_id, i.project_id, i.issue_key, i.title,
                           t.name AS type_name, t.level AS type_level,
                           t.counts_toward_completion,
                           s.name AS status_name,
                           CASE WHEN t.level <> 'EPIC'            THEN s.kind
                                WHEN COALESCE(kids.counted, 0) = 0 THEN 'NOT_STARTED'
                                WHEN kids.done = kids.counted      THEN 'DONE'
                                WHEN kids.fresh = kids.counted     THEN 'NOT_STARTED'
                                ELSE 'IN_PROGRESS'
                           END AS derived_kind,
                           CASE WHEN pt.level = 'EPIC' THEN p.id
                                WHEN gt.level = 'EPIC' THEN g.id
                           END AS epic_id,
                           i.parent_id, i.assignee_id, i.estimate_hours,
                           i.created_at, i.status_entered_at, i.done_at, i.deleted_at
                      FROM issue            i
                      JOIN issue_type       t  ON t.id  = i.type_id
                      JOIN issue_status     s  ON s.id  = i.status_id
                      LEFT JOIN issue       p  ON p.id  = i.parent_id
                      LEFT JOIN issue_type  pt ON pt.id = p.type_id
                      LEFT JOIN issue       g  ON g.id  = p.parent_id
                      LEFT JOIN issue_type  gt ON gt.id = g.type_id
                      LEFT JOIN LATERAL (
                               SELECT count(*)                                          AS counted,
                                      count(*) FILTER (WHERE cs.kind = 'DONE')          AS done,
                                      count(*) FILTER (WHERE cs.kind = 'NOT_STARTED')   AS fresh
                                 FROM issue        c
                                 JOIN issue_type   ct ON ct.id = c.type_id
                                 JOIN issue_status cs ON cs.id = c.status_id
                                WHERE c.parent_id = i.id
                                  AND c.deleted_at IS NULL
                                  AND ct.counts_toward_completion
                           ) kids ON t.level = 'EPIC'
                   ) f
              LEFT JOIN LATERAL (
                       SELECT ps.name
                         FROM issue_status ps
                        WHERE ps.project_id = f.project_id
                          AND ps.kind = f.derived_kind
                        ORDER BY ps."position"
                        LIMIT 1
                   ) ds ON f.type_level = 'EPIC'
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
