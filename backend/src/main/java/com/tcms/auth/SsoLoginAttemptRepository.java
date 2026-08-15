package com.tcms.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pre-authentication half of the SSO flow — <strong>read with no company context open, and that
 * is the whole reason this type exists.</strong>
 *
 * <p>{@code sso_login_attempt} carries no RLS and {@link SsoLoginAttempt} carries no company filter,
 * so both layers pass. Every company-owned repository would answer these calls with zero rows, which
 * is correct behaviour for company-owned data and the exact defect {@code V7} shipped.
 *
 * <p>The transaction is still opened by {@code CompanyAwareTransactionManager}; with no context it
 * simply leaves the GUC unset, so anything company-owned reached from it sees nothing. That is the
 * layering rather than a gap: this read resolves a company, and the reads that follow happen inside
 * one.
 */
// See ApiTokenRepository's note: a derived method on an un-annotated repository interface runs
// outside any transaction.
@Transactional(readOnly = true)
public interface SsoLoginAttemptRepository extends JpaRepository<SsoLoginAttempt, UUID> {

    /** Resolves a presented {@code state} by its digest. Never by the state itself — see the entity. */
    Optional<SsoLoginAttempt> findByStateHash(String stateHash);

    /**
     * Claims the attempt, and answers whether this caller is the one that claimed it.
     *
     * <p><strong>A conditional {@code UPDATE} rather than a read-then-write</strong>, and the
     * difference is the whole single-use guarantee. Two callbacks carrying the same {@code state}
     * can both observe {@code consumed_at IS NULL}; only one of them can make this statement report
     * 1, because the database decides. A version column would do the same job and would need a
     * column on a table that is empty in steady state.
     *
     * @return 1 for the caller that claimed it, 0 for a replay or a race that lost
     */
    @Modifying
    @Transactional
    @Query("""
            update SsoLoginAttempt a
               set a.consumedAt = :now
             where a.id = :id
               and a.consumedAt is null""")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Drops every attempt that expired before {@code cutoff} — {@link SsoLoginAttemptPurge}'s one
     * call, over the {@code sso_login_attempt_expires_idx} index {@code V53} created for it.
     *
     * <p>Expiry alone decides. A consumed attempt also passes its {@code expires_at} within
     * {@code tcms.oidc.attempt-validity} of being written, so consumed and abandoned rows leave by
     * the same door and no second predicate is needed.
     */
    @Modifying
    @Transactional
    @Query("delete from SsoLoginAttempt a where a.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
