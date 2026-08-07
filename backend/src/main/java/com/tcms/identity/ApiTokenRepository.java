package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** API tokens in the active company. Company-filtered; live names are unique per company. */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest.
@Transactional(readOnly = true)
public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {

    /** The company's tokens, newest first. Revoked ones included — see {@code ApiTokenService}. */
    List<ApiToken> findAllByOrderByCreatedAtDesc();

    /**
     * One token by id, through a query so the company filter applies in the application layer too —
     * {@code findById} goes through {@code EntityManager.find}, which Hibernate does not apply
     * {@code @Filter} to. {@code TeamRepository.findScopedById}'s reasoning exactly, and it matters
     * more here: the id arrives from a request path, and "a token in another company" and "no such
     * token" must be one answer decided by both layers rather than by RLS alone.
     */
    Optional<ApiToken> findScopedById(UUID id);

    /**
     * Whether this company already has a live token by this name.
     *
     * <p>{@code V36}'s unique index is the real guarantee; this is what turns the collision into a
     * field error on the form rather than a duplicate-key 500. Revoked tokens are excluded on both
     * sides, so a name written into somebody's CI configuration can be reused after rotation.
     */
    boolean existsByNameAndRevokedAtIsNull(String name);
}
