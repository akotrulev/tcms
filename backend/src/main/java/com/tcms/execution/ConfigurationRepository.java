package com.tcms.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** A project's configurations. {@code @Transactional} for the reason on {@link TestPlanRepository}. */
@Transactional(readOnly = true)
public interface ConfigurationRepository extends JpaRepository<Configuration, UUID> {

    /** Default first, then by name — the order a picker wants and the order a plan lists them in. */
    List<Configuration> findByProjectIdOrderByIsDefaultDescNameAsc(UUID projectId);
}
