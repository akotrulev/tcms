package com.tcms.work;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names an {@code execution} or {@code repository}
 * table.</strong>
 *
 * <p>Third instance of a pattern this codebase now has a name for: {@code execution.CaseFacts} reads
 * {@code repository}, {@code repository.ExecutionFacts} reads {@code execution}, and this reads
 * both. ArchUnit permits a cross-module dependency only toward {@code tenancy}, {@code identity} and
 * {@code project} (plan §3), so a module that needs rows from a sibling holds ids into them and
 * reads them through exactly one file — a shared row is not a shared class, and what §3 forbids is
 * two modules becoming deployable only together.
 *
 * <p>Two facts live on the other side, one per implemented generator:
 *
 * <ol>
 *   <li><strong>Which executions are outstanding, and for whom</strong> — {@link #assignedExecutions},
 *       over {@code test_execution} joined to {@code test_run} and {@code test_case}. This is the
 *       {@code EXECUTION} card.</li>
 *   <li><strong>Which cases the health computation has flagged</strong> — {@link #casesNeedingAttention},
 *       over {@code case_health} joined to {@code test_case}. This is the {@code CASE_MAINTENANCE}
 *       card, and it went from structurally empty to real in the same slice, because §53 also built
 *       {@code case_health}'s first writer.</li>
 * </ol>
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
    List<AssignedExecution> assignedExecutions(UUID projectId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                SELECT e.id, e.project_id, c.key, c.title, r.name, e.assignee_id
                  FROM test_execution e
                  JOIN test_run r  ON r.id = e.run_id
                  JOIN test_case c ON c.id = e.case_id AND c.deleted_at IS NULL
                 WHERE e.project_id = :projectId
                   AND e.status_id IS NULL
                   AND r.status = 'IN_PROGRESS'
                   AND r.deleted_at IS NULL
                 ORDER BY c.key_seq
                """)
                .setParameter("projectId", projectId)
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
     * <p>The other seven columns are read anyway rather than being left out. They are false in every
     * row today, so including them costs nothing and means the board gains stale-approval and
     * spec-changed cards on the day those computations land, with no change here.
     *
     * <p>{@code incomplete} is deliberately not among them, matching {@code HealthSignal}: it exists
     * in the schema, has no counterpart in the contract's enum, and putting it on a card would be
     * inventing a reason the SPA has no words for.
     */
    List<FlaggedCase> casesNeedingAttention(UUID projectId) {
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
                 WHERE h.project_id = :projectId
                   AND (h.outdated_spec_changed OR h.stale_approval OR h.condition_drift
                        OR h.stale_automation OR h.never_executed OR h.long_unexecuted
                        OR h.unlinked OR h.failing_repeatedly OR h.orphaned)
                 ORDER BY c.key_seq
                """)
                .setParameter("projectId", projectId)
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
}
