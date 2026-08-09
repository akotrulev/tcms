package com.tcms.identity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts. Instance-level and therefore <strong>not</strong> company-filtered — this is
 * one of the few repositories that can see across clients, which is why nothing below
 * Instance Admin may call a listing method on it.
 *
 * <p>Lookup by email is case-insensitive at the database ({@code CITEXT}), so callers
 * must not lower-case defensively — doing so would only hide a schema change.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Case-insensitive lookup — the login path.
     *
     * <p><strong>The cast is required, and a derived query is wrong here.</strong>
     * Hibernate binds the parameter as {@code varchar}, so {@code email = ?} against a
     * {@code citext} column resolves through {@code text} and compares
     * <em>case-sensitively</em>. The unique index stays case-insensitive either way, so
     * the two disagree: {@code Alice@corp.test} cannot be found by {@code alice@...},
     * while an attempt to register that second casing is still refused as a duplicate.
     * The visible symptom is a user who cannot sign in and cannot re-register — and it is
     * invisible in any test whose fixture uses one casing throughout.
     * {@code AppUserEmailCaseTest} covers it.
     */
    @Query(nativeQuery = true, value = "SELECT * FROM app_user WHERE email = CAST(:email AS citext)")
    Optional<AppUser> findByEmail(@Param("email") String email);

    /**
     * The gate {@link PermissionEvaluator} applies before any grant is read. Expressed as
     * an existence check rather than loading the row, because the evaluator runs on every
     * authorization decision and needs nothing else from it.
     */
    boolean existsByIdAndStatus(UUID id, UserStatus status);

    /**
     * The accounts behind a set of ids the caller <strong>already holds company-scoped rows
     * for</strong> — one query for a directory page rather than one per person.
     *
     * <p>This repository can see across clients, so this is the method that most needs its rule
     * stated: the ids must come from {@code company_membership} in the active company, never from a
     * request. Handed ids from a request it becomes an account oracle for guessed uuids, which is
     * precisely the disclosure channel {@link AppUser}'s header describes and the reason there is
     * no {@code findAll}-shaped listing here.
     *
     * <p>Callers must not pass an empty collection: an empty {@code IN ()} is a SQL syntax error.
     */
    List<AppUser> findByIdIn(Collection<UUID> ids);
}
