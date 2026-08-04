package com.tcms.reporting;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Artifact rows. Written by the job runner, read by the download route.
 *
 * <p>No declared query methods, and that is not an oversight: an artifact is always reached
 * <em>through</em> its job ({@link ReportJobRepository#findByArtifact}), because {@code V1} gave
 * this table no {@code project_id} and the download route is project-scoped. A
 * {@code findByIdAndProjectId} here would have nothing to filter on and would look like the scoping
 * the route needs while providing none of it.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} is not optional</strong> even with no declared
 * methods — {@code RepositoryTransactionTest} derives its list from the live context and requires it
 * on every repository interface, precisely so that adding the first derived method later cannot
 * silently start returning zero rows.
 */
@Transactional(readOnly = true)
public interface ReportArtifactRepository extends JpaRepository<ReportArtifact, UUID> {
}
