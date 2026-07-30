package com.tcms.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations. Instance-level, so — like {@link AppUserRepository} — not company-filtered;
 * the redemption path has no company context to be filtered by, which is the whole reason
 * the table is on the ERD §0.1 exception list.
 *
 * <p>There is deliberately <strong>no</strong> {@code findByCompanyIdAndUserId} that
 * ignores {@code consumedAt}: every caller either wants the outstanding one (to supersede
 * it) or is redeeming by hash. A method returning consumed invitations would be a listing
 * of who was invited where, across clients, one autocomplete away.
 */
// @Transactional or the query methods below run outside any transaction — see
// AppUserRepository's comment and RepositoryTransactionTest.
@Transactional(readOnly = true)
public interface MembershipInvitationRepository extends JpaRepository<MembershipInvitation, UUID> {

    /**
     * The redemption lookup. By hash, never by id: the caller holds a secret, and the
     * digest of that secret is the only thing that should identify a row — an id-based
     * lookup would let anyone who learned an invitation id redeem it.
     */
    Optional<MembershipInvitation> findByTokenHash(String tokenHash);

    /**
     * The one invitation that could still be redeemed for this pair, if any.
     *
     * <p>{@code uq_membership_invitation_outstanding} guarantees at most one, so this
     * returns an {@link Optional} rather than a list — and a second row appearing would be
     * a constraint violation at write time rather than a silent ambiguity here.
     */
    Optional<MembershipInvitation> findByCompanyIdAndUserIdAndConsumedAtIsNull(
            UUID companyId, UUID userId);
}
