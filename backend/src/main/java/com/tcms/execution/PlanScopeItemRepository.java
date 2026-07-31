package com.tcms.execution;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** A plan's scope lines. {@code @Transactional} for the reason on {@link TestPlanRepository}. */
@Transactional(readOnly = true)
public interface PlanScopeItemRepository extends JpaRepository<PlanScopeItem, UUID> {
}
