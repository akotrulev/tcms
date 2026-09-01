package com.tcms.tracker.release;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story ↔ test-case links.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface: Spring Data gives a transaction only
 * to the inherited CRUD methods, so a derived method declared here would run with no company set and
 * every RLS read would answer zero rows, fail-closed and silent.
 *
 * <p>Every method is keyed on {@code targetType} even though this module writes only one value —
 * the table is shared with target types three later modules will add, and a query that omitted it
 * would start returning their rows the day they arrive, without failing.
 */
@Transactional(readOnly = true)
public interface VerificationLinkRepository extends JpaRepository<VerificationLink, VerificationLinkId> {

    List<VerificationLink> findByIssueIdAndTargetTypeOrderByTargetId(UUID issueId, String targetType);

    List<VerificationLink> findByTargetTypeAndIssueIdIn(String targetType, Collection<UUID> issueIds);

    /**
     * Every link in one project, for the reverse direction — {@code test-cases.html}'s Verifies
     * column. Joined to {@code issue} rather than filtered on a denormalised project id, because
     * {@code issue_link} does not carry one: the row belongs to the issue's project by definition.
     */
    @Query("""
            SELECT l FROM VerificationLink l
             WHERE l.targetType = :targetType
               AND l.issueId IN (
                    SELECT i.id FROM Issue i
                     WHERE i.projectId = :projectId AND i.deletedAt IS NULL)
            """)
    List<VerificationLink> findInProject(UUID projectId, String targetType);
}
