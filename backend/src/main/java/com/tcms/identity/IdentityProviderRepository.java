package com.tcms.identity;

import java.util.List;
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
public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, UUID> {

    /**
     * The provider a callback names, if it is live — read <strong>after</strong> the company
     * context is open, which is what makes this the ordinary RLS-protected question rather than
     * the pre-authentication one {@code tcms_resolve_identity_provider} answers.
     *
     * <p>An empty result means "disabled, foreign, or absent" and the caller must not tell those
     * apart: one refusal, no reason, per §6r's rule that a login surface is never an oracle for
     * which companies exist on the instance.
     */
    Optional<IdentityProvider> findByIdAndEnabledTrue(UUID id);

    // There was a findByCompanyIdAndEnabledTrue here, written for provider discovery. It was
    // deleted in §217 phase 4 rather than wired: discovery runs BEFORE any company context
    // exists, so this repository would have answered it with zero rows, and
    // tcms_resolve_providers_for_domain (V55) answers it instead. A query with no caller is an
    // unverified claim about a read path that has never executed — §152's rule, and
    // everyDeclaredRepositoryMethodHasACaller reported this one on its first run.
}
