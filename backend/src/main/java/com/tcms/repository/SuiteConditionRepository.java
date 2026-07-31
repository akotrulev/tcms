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
public interface SuiteConditionRepository extends JpaRepository<SuiteCondition, UUID> {

    /**
     * Reference-backed condition attachments for a set of suites, in <strong>one</strong> query.
     *
     * <p>One query for all suites rather than one per suite — the N+1 a mapped
     * {@code @OneToMany} would have produced for free. The service groups by {@code suiteId}.
     *
     * <p><strong>{@code conditionId IS NOT NULL} excludes inline attachments, and that exclusion
     * is a decision rather than an oversight</strong> — see {@link SuiteConditionView} for the
     * whole argument. Briefly: the contract requires a {@code title} on every condition reference,
     * an inline attachment has nowhere to store one, and no writer can currently create one. The
     * predicate is here rather than in the service so the thing being excluded is visible next to
     * the thing being selected.
     *
     * <p>Ordered by {@code position}: the author's order is the reading order, and a condition
     * list is a sequence of actions where "log in" before "navigate to Billing" is the content.
     * {@code id} breaks ties so the order is stable between requests rather than merely usually
     * right.
     */
    @Query("""
            SELECT sc FROM SuiteCondition sc
             WHERE sc.suiteId IN :suiteIds
               AND sc.conditionId IS NOT NULL
             ORDER BY sc.position ASC, sc.id ASC
            """)
    List<SuiteCondition> findForSuites(@Param("suiteIds") Collection<UUID> suiteIds);
}
