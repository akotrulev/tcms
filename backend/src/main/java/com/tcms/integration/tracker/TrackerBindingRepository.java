package com.tcms.integration.tracker;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code tracker_binding}'s reads and writes.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} on the interface is not optional.</strong>
 * Isolation is established in {@code CompanyAwareTransactionManager#doBegin}, and Spring Data gives
 * a transaction only to the methods inherited from {@code SimpleJpaRepository} — so every derived
 * method declared here would otherwise run with {@code app.current_company} unset and return zero
 * rows, fail-closed and silent, while the company context holds the right company.
 * {@code RepositoryTransactionTest} fails the build on an interface missing it.
 */
@Transactional(readOnly = true)
public interface TrackerBindingRepository extends JpaRepository<TrackerBinding, UUID> {

    /** Default first, then oldest — the order the list serves and the promotion rule reads. */
    List<TrackerBinding> findByProjectIdOrderByDefaultBindingDescCreatedAtAsc(UUID projectId);

    /**
     * Scoped by project as well as by id, for {@code EvidenceRepository#findByIdAndExecutionId}'s
     * reason: the id alone would edit a sibling project's binding through a path naming this one.
     */
    Optional<TrackerBinding> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<TrackerBinding> findByProjectIdAndDefaultBindingIsTrue(UUID projectId);

    /**
     * The create's uniqueness check, keyed on exactly the columns of
     * {@code tracker_binding_target_idx} — a lookup naming fewer would pass and then violate the
     * index on insert, which is a validated request that 500s ({@code V14}'s account).
     */
    Optional<TrackerBinding> findByProjectIdAndProviderAndBaseUrlAndExternalProject(
            UUID projectId, TrackerProvider provider, String baseUrl, String externalProject);

    /**
     * Whether any identity could still be pointed at this host by this project.
     *
     * <p>Used by the delete and by the refusal to edit a host: see {@link TrackerBinding}'s header
     * for why a binding's host is immutable.
     */
    List<TrackerBinding> findByProjectIdAndEnabledIsTrue(UUID projectId);
}
