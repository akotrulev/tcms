package com.tcms.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
//
// NOT readOnly, like CaseFlakeRepository and for its reason: the accept and reject paths write the
// decision columns through this repository, and readOnly = true skips the flush — a write that
// silently does nothing while every assertion about the read still passes.
@Transactional
public interface AiProposalRepository extends JpaRepository<AiProposal, UUID> {

    /**
     * A project's proposals in one status, newest first — the review queue.
     *
     * <p>Served by {@code ai_proposal_pending_idx} for the {@code PENDING} case, which is the only
     * one anybody browses; a decided status falls back to a filtered scan of a small table.
     */
    @Query("""
            SELECT p FROM AiProposal p
             WHERE p.projectId = :projectId
               AND p.status = :status
             ORDER BY p.createdAt DESC
            """)
    List<AiProposal> findByStatus(
            @Param("projectId") UUID projectId,
            @Param("status") ProposalStatus status,
            Pageable pageable);

    /** How many are in that status, for the page total the list endpoint serves. */
    @Query("""
            SELECT COUNT(p) FROM AiProposal p
             WHERE p.projectId = :projectId
               AND p.status = :status
            """)
    long countByStatus(@Param("projectId") UUID projectId, @Param("status") ProposalStatus status);

    /**
     * One proposal, scoped to its project.
     *
     * <p><strong>Not {@code findById}</strong>, for {@link CaseFlakeRepository#findScopedById}'s
     * reason: a primary-key lookup resolves through the persistence context and Hibernate filters
     * do not apply to that path. The project term is here as well as the company filter because a
     * proposal id from another project must 404 rather than open — §6t's rule that a read cannot be
     * used to probe for what exists elsewhere.
     */
    @Query("""
            SELECT p FROM AiProposal p
             WHERE p.id = :id
               AND p.projectId = :projectId
            """)
    Optional<AiProposal> findScopedById(
            @Param("id") UUID id, @Param("projectId") UUID projectId);

    /**
     * The replay lookup for {@code Idempotency-Key}, keyed on exactly the columns
     * {@code ai_proposal_created_idempotency_key_idx} constrains.
     *
     * <p>{@code TestCaseRepository#findByIdempotencyKey}'s shape, and its warning applies: naming
     * fewer columns than the index does means a key reused across two projects passes the lookup and
     * then violates the constraint — a validated request that 500s.
     */
    @Query("""
            SELECT p FROM AiProposal p
             WHERE p.projectId = :projectId
               AND p.createdIdempotencyKey = :key
            """)
    Optional<AiProposal> findByIdempotencyKey(
            @Param("projectId") UUID projectId, @Param("key") String key);
}
