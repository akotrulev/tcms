package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Memberships of the active company.
 *
 * <p>{@link #existsByCompanyIdAndUserIdAndStatus} is <em>the</em> gate — the single
 * check that turns a stale grant into no capability at all. It is deliberately an
 * existence check on {@code status = ACTIVE}, not "a row exists": an {@code INVITED}
 * membership must convey nothing, or an unaccepted invitation becomes a way to look at a
 * client's data before agreeing to anything.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface CompanyMembershipRepository
        extends JpaRepository<CompanyMembership, CompanyMembershipId> {

    boolean existsByCompanyIdAndUserIdAndStatus(UUID companyId, UUID userId, MembershipStatus status);

    Optional<CompanyMembership> findByCompanyIdAndUserId(UUID companyId, UUID userId);

    /**
     * Memberships of the active company only. Listing a <em>user's</em> companies is
     * deliberately absent: that is an Instance-Admin-only question, and offering it here
     * would make the cross-client disclosure channel one autocomplete away.
     */
    List<CompanyMembership> findByCompanyId(UUID companyId);
}
