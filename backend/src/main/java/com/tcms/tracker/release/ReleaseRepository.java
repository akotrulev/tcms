package com.tcms.tracker.release;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's releases.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface, for the reason every repository in
 * this codebase carries it: Spring Data gives a transaction only to the inherited CRUD methods, so a
 * derived method declared here would run with no company set, and every RLS-protected read would
 * return <strong>zero rows</strong> — fail-closed and silent.
 */
@Transactional(readOnly = true)
public interface ReleaseRepository extends JpaRepository<Release, UUID> {

    /**
     * Version order — {@code 2.10} after {@code 2.5}, never between {@code 2.1} and {@code 2.2}.
     * Ordered by the derived key and never by the version text; see {@link Release}.
     */
    List<Release> findByProjectIdOrderBySortKeyAsc(UUID projectId);

    /** A query-based lookup, so the Hibernate company filter applies in the application layer too. */
    Optional<Release> findScopedById(UUID id);

    /** The uniqueness check behind the 409, matched the way {@code uq_release_version} matches. */
    Optional<Release> findByProjectIdAndVersionIgnoreCase(UUID projectId, String version);
}
