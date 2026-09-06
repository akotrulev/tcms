package com.tcms.work;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names an {@code execution}, {@code repository} or
 * {@code tracker} table.</strong>
 *
 * <p>Third instance of a pattern this codebase now has a name for: {@code execution.CaseFacts} reads
 * {@code repository}, {@code repository.ExecutionFacts} reads {@code execution}, and this reads
 * both — <strong>and, since 011 M1, {@code tracker}</strong>, which is the crossing that carries the
 * board's only row-backed card.
 *
 * <p>ArchUnit permits a cross-module dependency only toward {@code tenancy}, {@code identity} and
 * {@code project} (plan §3), so a module that needs rows from a sibling holds ids into them and
 * reads them through exactly one file — a shared row is not a shared class, and what §3 forbids is
 * two modules becoming deployable only together.
 *
 * <p>Five facts live on the other side, one per implemented generator:
 *
 * <ol>
 *   <li><strong>Which issues are assigned, and where they stand</strong> — {@link #assignedIssues},
 *       over {@code issue} joined to {@code issue_type} and {@code issue_status}. This is the
 *       {@code ISSUE} card, <strong>the row-backed one and the only one</strong>, and it is the card
 *       that replaced the card this module used to own.</li>
 *   <li><strong>Which executions are outstanding, and for whom</strong> — {@link #assignedExecutions},
 *       over {@code test_execution} joined to {@code test_run} and {@code test_case}. This is the
 *       {@code EXECUTION} card.</li>
 *   <li><strong>Which cases the health computation has flagged</strong> — {@link #casesNeedingAttention},
 *       over {@code case_health} joined to {@code test_case}. This is the {@code CASE_MAINTENANCE}
 *       card, and it went from structurally empty to real in the same slice, because §53 also built
 *       {@code case_health}'s first writer.</li>
 *   <li><strong>Who has been asked to review what, and has not answered yet</strong> —
 *       {@link #pendingReviews}, over {@code review} joined to {@code review_assignment} (§80's
 *       table) and to whichever of {@code test_case} / {@code suite} / {@code test_plan} the
 *       review's opaque subject triple names. This is the {@code REVIEW} card, and it is the one
 *       generator that reads tables from <em>three</em> modules at once — which is exactly why it
 *       is here rather than in {@code project}, whose {@code allowedDependencies} is
 *       {@code tenancy} alone.</li>
 *   <li><strong>Which tests somebody has stopped gating on</strong> —
 *       {@link #quarantinedCases}, over {@code case_flake}'s <em>authored</em> half joined to
 *       {@code test_case}. This is the {@code QUARANTINE} card, §6l's fourth generator, and it was
 *       the one of the four that had no data of any kind until §121 created the table — open
 *       question #24's *"no data until M3 ingestion exists"*, which §117 removed.</li>
 * </ol>
 *
 * <p>Two further crossings that are not generators. {@link #teamMembers} reads
 * {@code team_membership} ({@code identity}) on its own, for §6l's <strong>team board</strong> (§84):
 * it produces no cards — it produces the set of people whose cards are kept — which is why it sits
 * beside the generators rather than among them, and its header records why the expansion belongs
 * here and not behind {@code project.TeamDirectory}. {@link #legalMoves} reads
 * {@code issue_transition} for the same shape of reason: it produces no cards either, it says where
 * the ones the first generator produced may <em>go</em>, and it is one-to-many so it cannot be
 * folded into that generator's row.
 *
 * <h2>Every generator takes a SET of projects, and the project board is the one-element case</h2>
 *
 * <p>Since §83 the board is not always about one project — §6l's "My Work" aggregates every project
 * the caller can see, and asking each generator once per project would be 3N round trips for the
 * person the board was designed for, the one working on three. So the predicate is
 * {@code project_id IN (:projectIds)} in all five, and the project-scoped board passes a set of one.
 * A per-project overload beside it would be a second spelling of each query for the older caller to
 * keep in step with (§58's E1) — and the two would drift in the direction nobody tests, since only
 * one board would exercise each.
 *
 * <p><strong>The collection must never be empty.</strong> {@code IN ()} is a syntax error rather than
 * a query matching nothing, so {@code BoardService} answers an empty board without calling here —
 * the same guard {@code ProjectService.listVisible} states at its own grouped query, and for once it
 * is the ordinary path rather than an edge: a company member holding no project grants is exactly who
 * opens a personal board first.
 *
 * <p>Each record carries its own {@code projectId} for that reason: with several projects in one
 * answer, the card's project is a property of the row rather than of the request.
 *
 * <p>Native SQL rather than entities, for {@code CaseFacts}' reason: a second JPA entity mapped over
 * a table another module owns is one table with two sets of column expectations for
 * {@code ddl-auto: validate} to check, and two places a mapping can drift.
 *
 * <p>RLS covers every statement here — it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}. The project
 * predicate is layered on top as the second gate, never instead of it.
 */
@Component
class BoardFacts {

    private final EntityManager entities;

    BoardFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * One issue assigned to somebody — <strong>the row-backed card, and the only one</strong>.
     *
     * <p>Fifth crossing in this class and the first that reads {@code tracker}. Same rule as the
     * other four and stated again because it is the one a reader will question: {@code work} may not
     * depend on {@code tracker} ({@code allowedDependencies} is unchanged and stays unchanged), so
     * this holds ids into that module's tables and reads them through <em>this file</em>. A shared
     * row is not a shared class, and what plan §3 forbids is two modules becoming deployable only
     * together.
     *
     * <p><strong>The card that replaced the card this module used to own.</strong> Until M1 the
     * stored card was {@code work_item}, mapped by an entity in this package. It is now an
     * {@code issue} in another module, read here as a fact — which is why this method exists at all
     * and why {@code WorkItemRepository} does not.
     *
     * @param typeLevel {@code EPIC}, {@code STORY} or {@code TASK}, as a String because it is
     *                  {@code tracker}'s vocabulary and re-declaring it as an enum here would put a
     *                  second copy of another module's value set in a module forbidden to import the
     *                  first. It is read for one thing: D31 gives an Epic no legal moves.
     * @param statusId  what the transition lookup is keyed on. The card's allowed moves are the
     *                  project's workflow rather than "every column but this one", so the status has
     *                  to travel as an id and not only as a name.
     * @param priority  {@code tracker}'s four values as a String, for {@code typeLevel}'s reason.
     */
    record AssignedIssue(
            UUID issueId,
            UUID projectId,
            String issueKey,
            String title,
            String description,
            String typeLevel,
            UUID statusId,
            String statusName,
            String statusKind,
            String priority,
            LocalDate dueDate,
            UUID assigneeId) {
    }

    /**
     * The issues still open in these projects — §6l's stored card, one module over.
     *
     * <h2>{@code kind <> 'DONE'} is the "card leaves" rule the other four generators already keep</h2>
     *
     * <p>Expressed against the one column that survives a project renaming its columns. §6l puts it
     * plainly for executions — <em>"execution completed → card leaves"</em> — and without it a board
     * becomes an append-only list of everything anybody was ever assigned. {@code my-work.html}'s
     * "Hide done" checkbox is checked by default and is a <em>client</em> filter over what is left,
     * not a substitute for this.
     *
     * <h2>An Epic's kind is DERIVED here, and that is the second copy of one derivation</h2>
     *
     * <p>D31 derives an epic's status from its stories and refuses to let anybody set it, so an epic
     * whose stored {@code status_id} still says Backlog while every story is done would sit on this
     * board for ever <em>and</em> disagree with its own screen — the "two contradictory numbers on
     * one screen" this repository has shipped once, reached through the card that replaced the one
     * this module used to own. The {@code CASE} below is {@code tracker.EpicStatus}' four arms in
     * SQL, and it is deliberately the same statement {@code tracker.IssueFactProjection} runs for the
     * same reason: this module may not call that one, and the alternative to copying it is a board
     * that is wrong about epics.
     *
     * <p>The display name follows the same rule that projection uses — the project's own first status
     * at the derived kind, by position — because a hardcoded "Done" is the word-lookup story B1
     * exists to remove.
     *
     * <p>{@code deleted_at IS NULL}: an issue is soft-deleted (scope §3), so without it a board would
     * render work that is gone from every other screen.
     */
    List<AssignedIssue> assignedIssues(Collection<UUID> projectIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT f.id, f.project_id, f.issue_key, f.title, f.description,
                       f.type_level, f.status_id,
                       COALESCE(ds.name, f.status_name), f.derived_kind,
                       f.priority, f.due_date, f.assignee_id
                  FROM (
                        SELECT i.id, i.project_id, i.issue_key, i.title, i.description,
                               t.level AS type_level, i.status_id, s.name AS status_name,
                               CASE WHEN t.level <> 'EPIC'             THEN s.kind
                                    WHEN COALESCE(kids.counted, 0) = 0 THEN 'NOT_STARTED'
                                    WHEN kids.done = kids.counted      THEN 'DONE'
                                    WHEN kids.fresh = kids.counted     THEN 'NOT_STARTED'
                                    ELSE 'IN_PROGRESS'
                               END AS derived_kind,
                               i.priority, i.due_date, i.assignee_id, i.key_seq
                          FROM issue        i
                          JOIN issue_type   t ON t.id = i.type_id
                          JOIN issue_status s ON s.id = i.status_id
                          LEFT JOIN LATERAL (
                                   SELECT count(*)                                        AS counted,
                                          count(*) FILTER (WHERE cs.kind = 'DONE')        AS done,
                                          count(*) FILTER (WHERE cs.kind = 'NOT_STARTED') AS fresh
                                     FROM issue        c
                                     JOIN issue_type   ct ON ct.id = c.type_id
                                     JOIN issue_status cs ON cs.id = c.status_id
                                    WHERE c.parent_id = i.id
                                      AND c.deleted_at IS NULL
                                      AND ct.counts_toward_completion
                               ) kids ON t.level = 'EPIC'
                         WHERE i.project_id IN (:projectIds)
                           AND i.deleted_at IS NULL
                       ) f
                  LEFT JOIN LATERAL (
                           SELECT ps.name
                             FROM issue_status ps
                            WHERE ps.project_id = f.project_id
                              AND ps.kind = f.derived_kind
                            ORDER BY ps."position"
                            LIMIT 1
                       ) ds ON f.type_level = 'EPIC'
                 WHERE f.derived_kind <> 'DONE'
                 ORDER BY f.key_seq
                """)
                .setParameter("projectIds", projectIds)
                .getResultList();

        return rows.stream()
                .map(row -> new AssignedIssue(
                        (UUID) row[0],
                        (UUID) row[1],
                        (String) row[2],
                        (String) row[3],
                        (String) row[4],
                        (String) row[5],
                        (UUID) row[6],
                        (String) row[7],
                        (String) row[8],
                        (String) row[9],
                        row[10] == null ? null : ((java.sql.Date) row[10]).toLocalDate(),
                        (UUID) row[11]))
                .toList();
    }

    /**
     * One legal move out of one status — the project's workflow, as the board needs it.
     *
     * <p>Sixth crossing and the second into {@code tracker}, and it is here rather than folded into
     * {@link #assignedIssues} because it is one-to-many: a card's allowed moves are a list, and a
     * join would multiply every issue row by its workflow's out-edges for the board to fold back up.
     */
    record LegalMove(
            UUID fromStatusId,
            UUID toStatusId,
            String toName,
            String toKind,
            int toPosition,
            boolean toBlocking) {
    }

    /**
     * Every legal move in these projects' workflows, ordered by the destination's position.
     *
     * <p>One statement for the whole board rather than one per card, for the reason every generator
     * here gives: the personal board of somebody on six projects is a handful of statements rather
     * than one per card. The board keys them by {@code fromStatusId} in Java.
     *
     * <p><strong>This is what makes an {@code ISSUE} card's allowed moves a fact about the project
     * rather than about the column set.</strong> {@code work_item} let a card go anywhere and called
     * that the model; the workflow is the authority now, which is what makes
     * {@code settings-workflow.html}'s "Allowed moves" panel mean anything.
     */
    List<LegalMove> legalMoves(Collection<UUID> projectIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT t.from_status_id, s.id, s.name, s.kind, s."position", s.blocking
                  FROM issue_transition t
                  JOIN issue_status s ON s.id = t.to_status_id
                 WHERE t.project_id IN (:projectIds)
                 ORDER BY s."position", s.name
                """)
                .setParameter("projectIds", projectIds)
                .getResultList();

        return rows.stream()
                .map(row -> new LegalMove(
                        (UUID) row[0],
                        (UUID) row[1],
                        (String) row[2],
                        (String) row[3],
                        ((Number) row[4]).intValue(),
                        (Boolean) row[5]))
                .toList();
    }

    /**
     * One outstanding execution — a case somebody is expected to run.
     *
     * @param executionId the source id. The card's own id is derived from it
     *                    ({@code execution:{uuid}}) and is not a row anywhere.
     * @param caseKey     {@code TC-42}, so the card can link to the case it is about.
     * @param assigneeId  nullable. An execution in a run nobody has divided up yet is real work with
     *                    no owner, and dropping those would hide the whole of an unassigned run from
     *                    the project board.
     */
    record AssignedExecution(
            UUID executionId,
            UUID projectId,
            String caseKey,
            String caseTitle,
            String runName,
            UUID assigneeId) {
    }

    /**
     * Executions that are still outstanding in this project.
     *
     * <h2>Outstanding means unrecorded, in a run that is still going</h2>
     *
     * <p>Two predicates, and each removes a different kind of card that would otherwise never leave
     * the board:
     *
     * <ul>
     *   <li>{@code e.status_id IS NULL} — a recorded execution is finished work. §6l puts it
     *       plainly: "execution completed → card leaves". Leaving them would make the board an
     *       append-only list of everything ever run.</li>
     *   <li>{@code r.status = 'IN_PROGRESS'} — a run that is {@code NOT_STARTED} has not been
     *       handed to anybody yet, and one that is {@code COMPLETED} or withdrawn is over.
     *       <strong>The unrecorded executions in a completed run are exactly the cases that were
     *       skipped</strong>, and they are a reporting fact rather than outstanding work; putting
     *       them on the board would resurrect closed runs as a permanent backlog nobody can clear.
     *       That is the same call §52's suite timeline made from the other side, where an unrecorded
     *       row counts as {@code notRun} for coverage — a row can be work outstanding <em>and</em>
     *       not a task, depending on which question is being asked.</li>
     * </ul>
     *
     * <p>The {@code test_case} join is for the key and title only. It also drops executions whose
     * case has since been soft-deleted, which is correct and worth noticing: the execution row
     * survives for history (§6p), but "go and run this" is not something to say about a deleted
     * case.
     */
    List<AssignedExecution> assignedExecutions(Collection<UUID> projectIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT e.id, e.project_id, c.key, c.title, r.name, e.assignee_id
                  FROM test_execution e
                  JOIN test_run r  ON r.id = e.run_id
                  JOIN test_case c ON c.id = e.case_id AND c.deleted_at IS NULL
                 WHERE e.project_id IN (:projectIds)
                   AND e.status_id IS NULL
                   AND r.status = 'IN_PROGRESS'
                   AND r.deleted_at IS NULL
                 ORDER BY c.key_seq
                """)
                .setParameter("projectIds", projectIds)
                .getResultList();

        return rows.stream()
                .map(row -> new AssignedExecution(
                        (UUID) row[0],
                        (UUID) row[1],
                        (String) row[2],
                        (String) row[3],
                        (String) row[4],
                        (UUID) row[5]))
                .toList();
    }

    /**
     * One outstanding review request — somebody was asked and has not answered.
     *
     * @param reviewId    the review the request belongs to. Part of the card's synthetic id, and not
     *                    a link target: a review has no URL of its own, because §6g's panel is
     *                    rendered on the artifact's own page.
     * @param subjectType {@code CASE} · {@code SUITE} · {@code PLAN}, read from the review's opaque
     *                    triple. It reaches the wire as the card's {@code sourceType}, because it is
     *                    what tells the SPA which screen to open.
     * @param subjectKey  {@code TC-42} / {@code TS-3} / {@code TP-7} — all three subject tables have
     *                    a display key, which is what makes one card shape serve them.
     * @param reviewerId  the person whose list this is on. Never null: a request always names
     *                    somebody, and a team's request is expanded into its members here.
     * @param requestedBy who asked, nullable — {@code assigned_by} has no FK and no cascade, so an
     *                    offboarded account leaves a request nobody is named for.
     */
    record PendingReview(
            UUID reviewId,
            UUID projectId,
            String subjectType,
            UUID subjectId,
            String subjectKey,
            String subjectTitle,
            UUID reviewerId,
            UUID requestedBy) {
    }

    /**
     * Review requests that are still outstanding in this project — §6l's third generator, and the
     * inbox §6g's apparatus did not have.
     *
     * <h2>This query is the reason the inbox is here and not in {@code project}</h2>
     *
     * <p>It names four tables belonging to three modules: {@code review} and
     * {@code review_assignment} ({@code project}), {@code team_membership} ({@code identity}), and
     * {@code test_case} / {@code suite} / {@code test_plan} ({@code repository} and
     * {@code execution}). No module may write it. {@code ReviewController}'s own header records the
     * refusal from the other end — <em>"the pending-reviews queue §6l wants is a different question
     * and it will need the subject expanded, which this endpoint refuses to do"</em> — because
     * {@code project} may depend on {@code tenancy} alone and cannot see two of those three
     * modules. {@code work} may see all of them, and this class is where that permission is spent.
     *
     * <h2>A team is expanded HERE, and that is the mirror image of §80's refusal</h2>
     *
     * <p>§80 refused to expand a team into per-member rows when the request is <em>written</em>,
     * because membership moves and a stored expansion is a snapshot of who was on the team the day
     * somebody clicked. The same fact makes expanding it at <em>read</em> time correct: the card set
     * is recomputed on every board load, so a person who joins the team tomorrow finds the request
     * waiting and a person who leaves stops seeing it — which is precisely §6g's <em>"any member of
     * the team can action it"</em>. It is the derived-item rule doing the work it was designed for,
     * one generator over.
     *
     * <p>{@code DISTINCT} in the CTE rather than {@code UNION ALL}: somebody asked directly
     * <em>and</em> through a team they belong to has been asked once, and two cards for one request
     * is the board saying they owe two verdicts.
     *
     * <h2>Three predicates, and each removes a card that would otherwise never leave</h2>
     *
     * <ul>
     *   <li>{@code r.state = 'IN_REVIEW'} — an approved, rejected or withdrawn review is over.
     *       Requests outlive it in the table (unassigning is the only thing that deletes a row,
     *       §80), so without this the board would carry every request ever made.</li>
     *   <li><strong>No participation row by that person</strong> — §6l's "execution completed → card
     *       leaves", said about a verdict. Note what it does <em>not</em> say: the card leaves when
     *       they have <em>spoken</em>, not when they agreed. Somebody who requested changes has
     *       done what was asked of them and the ball is with the author; leaving the card up would
     *       make "changes requested" read as work still owed by the reviewer.</li>
     *   <li><strong>The subject still exists</strong> — the {@code COALESCE(...) IS NOT NULL} guard,
     *       which the three {@code LEFT JOIN}s make load-bearing. "Go and review this" is not
     *       something to say about a soft-deleted case, and an {@code INNER JOIN} per type is not
     *       available: one query serves all three subject types precisely because the triple has no
     *       FK.</li>
     * </ul>
     *
     * <p>Ordered by the review's age, oldest first — the request that has been waiting longest is
     * the one most worth answering, and the board's own sort (overdue, then priority, then id) has
     * nothing to separate these cards by, since a review carries neither.
     */
    List<PendingReview> pendingReviews(Collection<UUID> projectIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                WITH asked AS (
                    SELECT DISTINCT a.review_id, a.assignee_user_id AS reviewer_id, a.assigned_by
                      FROM review_assignment a
                     WHERE a.assignee_user_id IS NOT NULL
                    UNION
                    SELECT DISTINCT a.review_id, m.user_id, a.assigned_by
                      FROM review_assignment a
                      JOIN team_membership m ON m.team_id = a.assignee_team_id
                     WHERE a.assignee_team_id IS NOT NULL
                )
                SELECT r.id, r.project_id, r.subject_type, r.subject_id,
                       COALESCE(c.key, s.key, p.key),
                       COALESCE(c.title, s.name, p.name),
                       asked.reviewer_id, asked.assigned_by
                  FROM review r
                  JOIN asked ON asked.review_id = r.id
                  LEFT JOIN test_case c ON r.subject_type = 'CASE'
                                       AND c.id = r.subject_id AND c.deleted_at IS NULL
                  LEFT JOIN suite s     ON r.subject_type = 'SUITE'
                                       AND s.id = r.subject_id AND s.deleted_at IS NULL
                  LEFT JOIN test_plan p ON r.subject_type = 'PLAN'
                                       AND p.id = r.subject_id AND p.deleted_at IS NULL
                 WHERE r.project_id IN (:projectIds)
                   AND r.state = 'IN_REVIEW'
                   AND COALESCE(c.id, s.id, p.id) IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM review_participation v
                                    WHERE v.review_id = r.id
                                      AND v.user_id = asked.reviewer_id)
                 ORDER BY r.created_at, r.id, asked.reviewer_id
                """)
                .setParameter("projectIds", projectIds)
                .getResultList();

        return rows.stream()
                .map(row -> new PendingReview(
                        (UUID) row[0],
                        (UUID) row[1],
                        (String) row[2],
                        (UUID) row[3],
                        (String) row[4],
                        (String) row[5],
                        (UUID) row[6],
                        (UUID) row[7]))
                .toList();
    }

    /**
     * The members of one team — §6l's <strong>team board</strong> expanded at read time (§84).
     *
     * <h2>Why the expansion is here rather than behind {@code project.TeamDirectory}</h2>
     *
     * <p>That port's header forbids it in terms: it answers <em>"what team is this"</em> and never
     * <em>"who may"</em>, because §6g's "any member of the team can action it" is the permission
     * evaluator's question and a second expansion of a team into user ids, sitting in the module
     * least entitled to hold one, is plan §6b's second authorization path. <strong>This expansion is
     * not that question.</strong> Nothing here decides what anybody may do — the caller's own project
     * visibility has already bounded the board before this set is applied — so what a team is expanded
     * <em>for</em> is which cards to keep, exactly as {@link #pendingReviews} expands one to decide
     * whose inbox a request is on. Both live in this file, which is where this module's crossings are
     * concentrated, and neither widens the port.
     *
     * <p><strong>Read time, never write time</strong> — §80's rule and its stated reason, membership
     * moves. A team board is recomputed on every load, so somebody who joined this morning sees the
     * team's work this morning; a materialised member list would be a snapshot of who was on the team
     * the day the board was first opened.
     *
     * <p><strong>Direct membership only, no hierarchy.</strong> {@code team} has a parent edge (§6k),
     * and a board that walked it would show a parent team every child team's work — which is a
     * different board, and one §6l does not describe. {@link #pendingReviews} joins
     * {@code team_membership} the same flat way, so a review asked of a parent team already reaches
     * its direct members alone; two different readings of "on the team" in one file would be the
     * worse outcome than either reading. Named here rather than left implicit, because it is the
     * first thing somebody will ask for.
     *
     * <p>An empty result is a real answer, not a failure: a team that exists and has nobody in it is
     * a board with nothing on it, and the caller must be able to tell that from a team that does not
     * exist — which is why existence is asked of {@code TeamDirectory} and never inferred from this.
     *
     * <p>RLS bounds this to the caller's company, and {@code team_membership} carries {@code
     * company_id} like every other company-owned table (ERD §0.1). The team id has additionally been
     * resolved in this company before this is called, so a foreign team reaches here never — and
     * would return nothing if it did.
     */
    Set<UUID> teamMembers(UUID teamId) {
        @SuppressWarnings("unchecked")
        List<UUID> rows = entities.createNativeQuery("""
                SELECT m.user_id
                  FROM team_membership m
                 WHERE m.team_id = :teamId
                """)
                .setParameter("teamId", teamId)
                .getResultList();

        return Set.copyOf(rows);
    }

    /**
     * One case the health computation has flagged.
     *
     * @param signals the raised signal column names, so the card can say <em>why</em> rather than
     *                "needs attention". A card that does not name its reason is one nobody can act
     *                on without opening the case and guessing.
     */
    record FlaggedCase(
            UUID caseId,
            UUID projectId,
            String caseKey,
            String caseTitle,
            UUID ownerId,
            List<String> signals) {
    }

    /**
     * Cases carrying at least one health signal.
     *
     * <h2>This reads {@code case_health}, which had no writer until this same slice</h2>
     *
     * <p>Worth stating because it changes what "empty" means here. Before §53 this query would have
     * returned nothing in every deployment forever — the §52 version-marker situation, where wiring
     * exists against a table nobody fills. §53 built {@code repository.CaseHealthRecomputeService},
     * so two of the nine signals ({@code never_executed}, {@code long_unexecuted}) are now real, and
     * this generator produces real cards.
     *
     * <p>The other seven columns are read anyway rather than being left out. <del>They are false in
     * every row today, so including them costs nothing and means the board gains stale-approval and
     * spec-changed cards on the day those computations land, with no change here.</del>
     *
     * <p><strong>§157 was that day for the first of them, and the prediction held exactly.</strong>
     * {@code repository.ApprovalFacts} derives {@code stale_approval}, so this generator produces
     * stale-approval cards now — with no change in this file, which is what the paragraph above was
     * betting on. <del>Five of the nine are real; {@code outdated_spec_changed},
     * {@code condition_drift}, {@code unlinked} and {@code orphaned} are still waiting on the same
     * bet.</del>
     *
     * <p><strong>§158 was the second such day, and this file is unchanged again.</strong>
     * {@code repository.ConditionDrift} derives {@code condition_drift}, so <strong>six of the nine
     * are real</strong> and the board gains ERROR-severity drift cards; <del>{@code outdated_spec_changed},
     * {@code unlinked} and {@code orphaned} are still waiting on the same bet.</del> Two for two is worth
     * recording rather than restating: the bet was that reading a column with no writer is cheap
     * when the read is generic, and both payouts arrived with a diff of zero here.
     *
     * <p><strong>§178 was the third day and it paid twice.</strong> {@code repository.SpecLinkage}
     * derives {@code outdated_spec_changed} and {@code unlinked} from {@code case_doc_link}, so
     * <strong>eight of the nine are real</strong> and this file is unchanged for the third time —
     * four payouts, diff of zero on every one. Only {@code orphaned} is left, and it is the one
     * waiting on a decision rather than on a statement, so the bet is settled: the last card this
     * generator gains will arrive the same way, with nothing to change here.
     *
     * <p>{@code incomplete} is deliberately not among them, matching {@code HealthSignal}: it exists
     * in the schema, has no counterpart in the contract's enum, and putting it on a card would be
     * inventing a reason the SPA has no words for.
     */
    List<FlaggedCase> casesNeedingAttention(Collection<UUID> projectIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT c.id, c.project_id, c.key, c.title, c.owner_id,
                       ARRAY_REMOVE(ARRAY[
                           CASE WHEN h.outdated_spec_changed THEN 'OUTDATED_SPEC_CHANGED' END,
                           CASE WHEN h.stale_approval        THEN 'STALE_APPROVAL'        END,
                           CASE WHEN h.condition_drift       THEN 'CONDITION_DRIFT'       END,
                           CASE WHEN h.stale_automation      THEN 'STALE_AUTOMATION'      END,
                           CASE WHEN h.never_executed        THEN 'NEVER_EXECUTED'        END,
                           CASE WHEN h.long_unexecuted       THEN 'LONG_UNEXECUTED'       END,
                           CASE WHEN h.unlinked              THEN 'UNLINKED'              END,
                           CASE WHEN h.failing_repeatedly    THEN 'FAILING_REPEATEDLY'    END,
                           CASE WHEN h.orphaned              THEN 'ORPHANED'              END
                       ], NULL)
                  FROM case_health h
                  JOIN test_case c ON c.id = h.case_id AND c.deleted_at IS NULL
                 WHERE h.project_id IN (:projectIds)
                   AND (h.outdated_spec_changed OR h.stale_approval OR h.condition_drift
                        OR h.stale_automation OR h.never_executed OR h.long_unexecuted
                        OR h.unlinked OR h.failing_repeatedly OR h.orphaned)
                 ORDER BY c.key_seq
                """)
                .setParameter("projectIds", projectIds)
                .getResultList();

        return rows.stream()
                .map(row -> new FlaggedCase(
                        (UUID) row[0],
                        (UUID) row[1],
                        (String) row[2],
                        (String) row[3],
                        (UUID) row[4],
                        List.of((String[]) row[5])))
                .toList();
    }

    /**
     * One test somebody has decided to stop gating on.
     *
     * @param ownerId       the case's owner, nullable — this is the card's <em>assignee</em>, and it
     *                      is deliberately not {@code quarantinedBy}. Whoever pulled a test out of
     *                      the gate is rarely the person who owes the fix, and a board that put the
     *                      card on their list would make quarantining somebody else's test an act
     *                      that assigns the debt to yourself.
     * @param quarantinedBy who decided, and never null: {@code case_flake_quarantine_complete}
     *                      makes the four authored columns all-or-nothing, so a quarantined row has
     *                      one. It may still fail to <em>resolve</em> to a name — the column
     *                      references instance-level {@code app_user}, so somebody offboarded from
     *                      the company leaves a decision nobody is named for, exactly as
     *                      {@code review_assignment.assigned_by} does.
     * @param reason        never null, for the same {@code CHECK}'s reason. It is the whole value of
     *                      the card: a card saying a test is excluded without saying why is debt
     *                      nobody can evaluate without reading the test.
     */
    record QuarantinedCase(
            UUID caseId,
            UUID projectId,
            String caseKey,
            String caseTitle,
            UUID ownerId,
            UUID quarantinedBy,
            String reason) {
    }

    /**
     * Cases whose flake row carries a live quarantine — §6l's fourth derived-item generator, and the
     * one that had been silently empty for the whole life of the board.
     *
     * <h2>The card is the reason quarantine is authored rather than derived</h2>
     *
     * <p>{@code CaseFlake}'s header makes the argument from the schema side and it lands here: a
     * card generated from the <em>score</em> crossing a threshold would vanish the moment the test
     * stopped flaking — taking the debt off the board while the test was still excluded from
     * gating. Reading the authored flag instead means the card leaves when somebody
     * <strong>releases</strong> the test, which is the act it is asking for.
     *
     * <p>So this generator is the one whose card does <em>not</em> clear itself, unlike
     * {@link #casesNeedingAttention}, where §53's whole argument is that the signal self-heals when
     * the underlying condition is fixed. That asymmetry is the design and not an oversight in this
     * query: a derived signal is a claim about the world and an authored flag is a decision, and a
     * decision is undone by a person.
     *
     * <h2>Only the seven derived columns are absent from this query, and that is deliberate</h2>
     *
     * <p>{@code flake_score} is not selected. It is what the case's own screen shows and what a
     * flake worklist would rank by, but a board card has one line for its reason and the reason is
     * the sentence a human wrote. Ordering by it does not survive either: {@code BoardService}
     * re-sorts the whole assembled board by (overdue, priority, id), so a score order established
     * here would be discarded a few frames later — which is worth knowing before somebody adds an
     * {@code ORDER BY flake_score} to make a worklist out of this. The order is {@code key_seq},
     * matching both siblings, because what it actually has to be is total and stable.
     *
     * <p>The {@code test_case} join drops a soft-deleted case for {@link #assignedExecutions}'
     * reason: the flake row survives a soft delete (only a hard delete cascades, {@code V38}), and
     * "this test is excluded from the gate" is not something to say about a case nobody is running.
     */
    List<QuarantinedCase> quarantinedCases(Collection<UUID> projectIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT c.id, c.project_id, c.key, c.title, c.owner_id,
                       f.quarantined_by, f.quarantine_reason
                  FROM case_flake f
                  JOIN test_case c ON c.id = f.case_id AND c.deleted_at IS NULL
                 WHERE f.project_id IN (:projectIds)
                   AND f.quarantined
                 ORDER BY c.key_seq
                """)
                .setParameter("projectIds", projectIds)
                .getResultList();

        return rows.stream()
                .map(row -> new QuarantinedCase(
                        (UUID) row[0],
                        (UUID) row[1],
                        (String) row[2],
                        (String) row[3],
                        (UUID) row[4],
                        (UUID) row[5],
                        (String) row[6]))
                .toList();
    }
}
