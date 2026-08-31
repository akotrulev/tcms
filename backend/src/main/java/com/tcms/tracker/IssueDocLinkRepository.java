package com.tcms.tracker;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The issue end of the documentation mirror.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface for {@link IssueRepository}'s reason,
 * which is the same in every repository here: Spring Data gives a transaction only to the inherited
 * CRUD methods, so a derived method without it runs with no company set and RLS returns zero rows,
 * fail-closed and silent.
 *
 * <p>Read-only in practice as well as in annotation. Nothing saves through this — the table is
 * replaced whole by {@link IssueDocLinkFacts}.
 */
@Transactional(readOnly = true)
public interface IssueDocLinkRepository extends JpaRepository<IssueDocLink, UUID> {

    /** One issue's links, block links first and then external ones, stable inside each group. */
    List<IssueDocLink> findByIssueIdOrderByTargetKindAscPageKeyAscIdAsc(UUID issueId);
}
