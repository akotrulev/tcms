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
 * The pre-authentication half of the social flow — read with no company context open, which is
 * possible only because {@code social_login_attempt} is instance-level ({@code V58}, ERD §0.1).
 * {@link SsoLoginAttemptRepository}'s reasoning applies verbatim.
 */
// See ApiTokenRepository's note: a derived method on an un-annotated repository interface runs
// outside any transaction.
@Transactional(readOnly = true)
public interface SocialLoginAttemptRepository extends JpaRepository<SocialLoginAttempt, UUID> {

    /** Resolves a presented {@code state} by its digest. Never by the state itself — see the entity. */
    Optional<SocialLoginAttempt> findByStateHash(String stateHash);

    /**
     * Claims the attempt, and answers whether this caller is the one that claimed it — a
     * conditional {@code UPDATE} rather than a read-then-write, for
     * {@link SsoLoginAttemptRepository#consume}'s stated reason.
     *
     * @return 1 for the caller that claimed it, 0 for a replay or a race that lost
     */
    @Modifying
    @Transactional
    @Query("""
            update SocialLoginAttempt a
               set a.consumedAt = :now
             where a.id = :id
               and a.consumedAt is null""")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Drops every attempt that expired before {@code cutoff} — {@link SsoLoginAttemptPurge}'s
     * second call. Expiry alone decides, for {@link SsoLoginAttemptRepository#deleteExpiredBefore}'s
     * stated reason.
     */
    @Modifying
    @Transactional
    @Query("delete from SocialLoginAttempt a where a.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
