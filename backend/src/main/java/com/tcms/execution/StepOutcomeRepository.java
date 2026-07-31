package com.tcms.execution;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recorded step results. {@code @Transactional} for the reason on {@link TestPlanRepository}.
 *
 * <p>Not {@code readOnly} at the interface level, unlike its siblings: {@link #saveAll} is the one
 * write in this slice's persistence layer, and a {@code readOnly = true} default would flush it
 * silently in some drivers and refuse it in others. The read methods declared here carry the flag
 * individually so they still get the transaction RLS needs.
 */
@Transactional
public interface StepOutcomeRepository extends JpaRepository<StepOutcome, UUID> {

    @Transactional(readOnly = true)
    @Query("SELECT s FROM StepOutcome s WHERE s.executionId IN :executionIds")
    List<StepOutcome> findForExecutions(@Param("executionIds") Collection<UUID> executionIds);
}
