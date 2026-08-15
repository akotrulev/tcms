package com.tcms.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    /**
     * The identity an assertion names — <strong>the first question the callback asks, and the
     * only one it asks when the answer is a hit</strong>.
     *
     * <p>Keyed on the subject, never the email, per {@link UserIdentity}. A user whose corporate
     * address changed must still resolve to their own account and their own authorship, so an
     * already-linked subject is answered here before any linking policy is consulted.
     *
     * <p>{@code providerId} carries the company, and RLS carries it again: the provider row is
     * company-owned, so a foreign provider id resolves to nothing before this is ever reached.
     */
    Optional<UserIdentity> findByProviderIdAndSubject(UUID providerId, String subject);
}
