package com.tcms.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for the reason spelled out on ProjectRepository: without it a query method runs
// outside any transaction, app.current_company is never set, and RLS returns zero rows.
@Transactional(readOnly = true)
public interface CaseStatusRepository extends JpaRepository<CaseStatus, UUID> {

    /**
     * One project's statuses, in the order the project meant them to be read.
     *
     * <p>A plain equality on the project id, unlike
     * {@link ClassificationDimensionRepository#findForProject}: statuses are project-scoped, so
     * there is no company-level tier to union in (see {@link CaseStatus}). The name breaks
     * position ties so the order does not depend on row order.
     */
    List<CaseStatus> findByProjectIdOrderByPositionAscNameAsc(UUID projectId);
}
