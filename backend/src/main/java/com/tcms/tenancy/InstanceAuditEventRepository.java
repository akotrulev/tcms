package com.tcms.tenancy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator trail. Instance-level and therefore <strong>not</strong> company-filtered
 * — it exists precisely to record actions that cross the boundary.
 *
 * <p>No update or delete method is offered and none should be added. {@code JpaRepository}
 * inherits {@code delete*} and {@code save} regardless, which is a known cost of the
 * Spring Data base interface rather than an invitation: {@link InstanceAuditEvent} has no
 * mutator, so {@code save} on a loaded row is a no-op re-persist.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface InstanceAuditEventRepository extends JpaRepository<InstanceAuditEvent, UUID> {

    List<InstanceAuditEvent> findByCompanyIdOrderByOccurredAtDesc(UUID companyId);
}
