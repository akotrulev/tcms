package com.tcms.billing;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The only file in this module that names {@code issue_fact}.</strong>
 *
 * <p>The seventh instance of a pattern with a name here, and the second this module makes:
 * {@code work.BoardFacts} reads {@code tracker}, {@code execution.CaseFacts} reads
 * {@code repository}, {@code tracker.release.ReleaseFacts} reads {@code execution},
 * {@code time.IssueFacts} reads {@code tracker}, {@code staffing.TimeFacts} reads {@code time}, and
 * {@link ApprovedTimeFacts} is this module's first. Read-only, and that is the module's rule rather
 * than a property of this class — {@code tracker.IssueFactProjection} is the projection's one
 * writer, fed by an outbox event, and a second writer would end the tracker's authority over issue
 * state with the two disagreeing silently. {@code BillingReadsAndNeverWritesTest} asserts both
 * halves over source text, because ArchUnit cannot see the rule: SQL is not a Java dependency.
 *
 * <p><strong>The name is {@code IssueSubjectFacts} and not {@code IssueFacts}</strong> (019 D-r).
 * {@code time.IssueFacts} already exists and is a {@code @Component}; Spring's default bean name is
 * the simple class name, so two of them are a {@code ConflictingBeanDefinitionException} and a
 * context that does not start.
 * {@code ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames} is the rule that reports
 * it, and §131 is why that rule reads CLASSES rather than a running context — the collision it was
 * written for could not occur anywhere in the suite and would have occurred exactly once, on the
 * first boot of a real deployment.
 *
 * <p><strong>It lands here rather than in T3, with its first caller</strong> (019 D-s). The rate
 * card, pricing and budgets needed no issue's key or title, so building this alongside them would
 * have been a seam with no caller — the shape §121 deleted rather than kept. It arrives with the
 * {@code invoice_line_entry} rows that snapshot those two columns.
 *
 * <p><strong>Two columns and no more.</strong> An invoice entry shows <em>ACME-122 Card capture</em>
 * and nothing else about the issue; the estimate, the status, the epic and the assignee are all on
 * this projection and none of them belongs on a document sent to a client. A wider select here would
 * be the "just fetch these rows" convenience {@code time.IssueFacts}' header refuses, on the
 * projection where the extra columns are somebody else's business.
 *
 * <p>A deleted issue is <strong>included</strong>: an hour logged against an issue somebody has
 * since deleted is still an hour somebody worked and has already been billed for, and dropping its
 * subject would render an invoice line's supporting row with no name at all. That is
 * {@code time.IssueFacts#byIds}' choice, for the same reason, and the opposite of what its
 * {@code byKey} does — which refuses to <em>create</em> against a deleted issue.
 *
 * <p>RLS covers the statement: it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager}. The gate on top of
 * it is the caller's — nothing here decides who may see an issue.
 */
@Component
class IssueSubjectFacts {

    /** What an invoice's supporting row renders: the display key and the title, and nothing else. */
    record Subject(String issueKey, String title) {
    }

    private final EntityManager entities;

    IssueSubjectFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * The subjects behind a fortnight's entries, in one query.
     *
     * <p>An id that names nothing simply has no entry in the map, and the caller renders the row
     * without a subject rather than dropping the hour: the hour is what is being billed, and the
     * issue is how it is described.
     */
    Map<UUID, Subject> byIds(Collection<UUID> issueIds) {
        if (issueIds.isEmpty()) {
            // `IN ()` is a syntax error rather than a predicate matching nothing, and the empty case
            // is ordinary here: a period whose every hour is a meeting reaches this with nothing.
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT f.issue_id, f.issue_key, f.title
                          FROM issue_fact f
                         WHERE f.issue_id IN (:issueIds)
                        """)
                .setParameter("issueIds", issueIds)
                .getResultList();

        Map<UUID, Subject> byId = new HashMap<>();
        for (Object[] row : rows) {
            byId.put((UUID) row[0], new Subject((String) row[1], (String) row[2]));
        }
        return byId;
    }
}
