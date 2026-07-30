package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * Memberships of the active company only. Listing a <em>user's</em> memberships as
     * <em>rows</em> is deliberately absent: that is an Instance-Admin-only question, and
     * offering it here would make the cross-client disclosure channel one autocomplete
     * away. {@link #findCompanyIdsForUser} is the one narrow exception, and it returns ids.
     */
    List<CompanyMembership> findByCompanyId(UUID companyId);

    /**
     * The companies an account may open a session in — <strong>ids only</strong>, ordered
     * by the membership they accepted first.
     *
     * <p>The one query in this codebase that reads across companies, and the only caller is
     * the login path. It has to exist: authentication precedes company context, so the
     * derived query above would correctly return nothing at the moment the session's
     * company must be chosen.
     *
     * <p>It goes through {@code tcms_companies_for_user}, which runs as <em>this</em> caller
     * with RLS fully in force — it walks the active companies and asks the ordinary policed
     * question for each, rather than bypassing the policy. {@code V7} tried the bypass, with
     * {@code SECURITY DEFINER}, and returned nothing in production because
     * {@code FORCE ROW LEVEL SECURITY} covers the table owner too; {@code V8} is the fix and
     * its header is worth reading before touching this.
     *
     * <p>Ids and nothing else, on purpose: every human-readable fact about a membership is
     * still read afterwards through RLS with the company open. Callers must pass an
     * already-authenticated account's id, <strong>never</strong> one taken from a request.
     *
     * <p>Ordered here rather than in the function, because a set-returning function's own
     * {@code ORDER BY} is not guaranteed to survive being inlined into an outer query.
     */
    @Query(nativeQuery = true,
            value = "SELECT company_id FROM tcms_companies_for_user(CAST(:userId AS uuid)) "
                    + "ORDER BY accepted_at NULLS LAST, company_id")
    List<UUID> findCompanyIdsForUser(@Param("userId") UUID userId);
}
