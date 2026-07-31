package com.tcms.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for the reason spelled out on ProjectRepository: without it a query method runs
// outside any transaction, app.current_company is never set, and RLS returns zero rows.
@Transactional(readOnly = true)
public interface ConditionRepository extends JpaRepository<Condition, UUID> {

    /**
     * The library entries behind a set of attachments, live only.
     *
     * <p>{@code deletedAt IS NULL} matters more here than it looks. Retiring a library condition
     * is a soft delete because attachments to it survive, so a retired entry still has rows
     * pointing at it — and serving it would offer a withdrawn precondition as current. An
     * attachment whose condition has been retired therefore resolves to nothing and drops out of
     * the response; {@link SuiteConditionView} records that as the deliberate half of a real gap
     * rather than as a lookup that quietly failed.
     */
    @Query("""
            SELECT c FROM Condition c
             WHERE c.id IN :ids
               AND c.deletedAt IS NULL
            """)
    List<Condition> findLiveByIds(@Param("ids") Collection<UUID> ids);
}
