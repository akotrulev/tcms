package com.tcms.time;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names a foreign table.</strong>
 *
 * <p>Read-only, and that is the module's central rule rather than a property of this class: the
 * tracker's issue projection has exactly one writer, {@code tracker.IssueFactProjection}, fed by an
 * outbox event. A second writer would end the tracker's authority over issue state and the two would
 * disagree <em>silently</em>. See the package comment; {@code TimeWritesNoIssueFactTest} asserts
 * both halves of the rule, including that this is the only file naming the table.
 *
 * <p>Fourth instance of a pattern with a name here: {@code work.BoardFacts} reads {@code tracker},
 * {@code execution.CaseFacts} reads {@code repository}, {@code tracker.release.ReleaseFacts} reads
 * {@code execution}. Native SQL rather than a second JPA entity mapped over another module's table,
 * for {@code CaseFacts}' reason — one table with two sets of column expectations is two places a
 * mapping can drift, and {@code ddl-auto: validate} would check both.
 *
 * <p>RLS covers every statement here: it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}. The gate on top of
 * it is the caller's — nothing in this class decides who may see an issue.
 *
 * <p><strong>There is deliberately no "just fetch these rows" convenience method.</strong> Every
 * method here answers one question a caller in this module actually asks, so the whole crossing can
 * be read by opening one file, which is the property the pattern exists for.
 */
@Component
class IssueFacts {

    /**
     * An issue as an entry's subject.
     *
     * @param typeLevel {@code EPIC}, {@code STORY} or {@code TASK}, as a String because it is the
     *                  tracker's vocabulary and re-declaring it as an enum here would put a second
     *                  copy of another module's value set in a module forbidden to import the first
     */
    record IssueSubject(
            UUID issueId,
            String issueKey,
            String title,
            String typeLevel,
            UUID projectId,
            String projectName,
            BigDecimal estimateHours) {
    }

    /** One counted child of an epic — the estimate-vs-actual table's left-hand columns. */
    record EpicChildFact(UUID issueId, String issueKey, String title, BigDecimal estimateHours) {
    }

    private static final String SUBJECT_COLUMNS = """
            SELECT f.issue_id, f.issue_key, f.title, f.type_level, f.project_id, p.name,
                   f.estimate_hours
              FROM issue_fact f
              JOIN project p ON p.id = f.project_id
            """;

    private final EntityManager entities;

    IssueFacts(EntityManager entities) {
        this.entities = entities;
    }

    /** The subject behind a key. Empty when the key names nothing this company has. */
    Optional<IssueSubject> byKey(String issueKey) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(
                        SUBJECT_COLUMNS + " WHERE f.issue_key = :key AND f.deleted_at IS NULL")
                .setParameter("key", issueKey)
                .getResultList();
        return rows.stream().findFirst().map(IssueFacts::toSubject);
    }

    /**
     * The subjects behind a set of ids, for a whole period in one query.
     *
     * <p>Deleted issues are included here and excluded by {@link #byKey}, and the difference is
     * deliberate: an entry logged against an issue somebody has since deleted is still an hour
     * somebody worked, and dropping its subject would render the row with no name at all. What
     * {@link #byKey} refuses is <em>creating</em> a new entry against a deleted issue.
     */
    List<IssueSubject> byIds(Collection<UUID> issueIds) {
        if (issueIds.isEmpty()) {
            // `IN ()` is a syntax error rather than a predicate matching nothing — BoardFacts'
            // guard, and here the empty case is the ordinary one: a period of meetings and leave.
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(
                        SUBJECT_COLUMNS + " WHERE f.issue_id IN (:issueIds)")
                .setParameter("issueIds", issueIds)
                .getResultList();
        return rows.stream().map(IssueFacts::toSubject).toList();
    }

    /**
     * The subjects behind a set of keys, for the work table's {@code Logged} column.
     *
     * <p>Deleted issues are excluded, matching {@link #byKey} rather than {@link #byIds}: this
     * answers a question about rows a table is drawing now, and the tracker's own list does not
     * draw a deleted issue either. A key naming nothing simply has no row in the result, which is
     * the operation's documented behaviour — one unreadable key must not cost the column.
     */
    List<IssueSubject> byKeys(Collection<String> issueKeys) {
        if (issueKeys.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery(
                        SUBJECT_COLUMNS
                                + " WHERE f.issue_key IN (:issueKeys) AND f.deleted_at IS NULL")
                .setParameter("issueKeys", issueKeys)
                .getResultList();
        return rows.stream().map(IssueFacts::toSubject).toList();
    }

    /**
     * The counted children of an epic, for the estimate-vs-actual table.
     *
     * <p>{@code counts_toward_completion} is how a spike is excluded without importing
     * {@code tracker}, and {@code type_level} is how the epic grain is reached at all — both are
     * columns M1 put on the projection for exactly this caller.
     */
    List<EpicChildFact> epicChildren(UUID epicId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT f.issue_id, f.issue_key, f.title, f.estimate_hours
                          FROM issue_fact f
                         WHERE f.epic_id = :epicId
                           AND f.counts_toward_completion
                           AND f.deleted_at IS NULL
                         ORDER BY f.issue_key
                        """)
                .setParameter("epicId", epicId)
                .getResultList();
        return rows.stream()
                .map(row -> new EpicChildFact(
                        (UUID) row[0], (String) row[1], (String) row[2], (BigDecimal) row[3]))
                .toList();
    }

    private static IssueSubject toSubject(Object[] row) {
        return new IssueSubject(
                (UUID) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (UUID) row[4],
                (String) row[5],
                (BigDecimal) row[6]);
    }
}
