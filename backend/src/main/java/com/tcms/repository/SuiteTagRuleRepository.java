package com.tcms.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for the reason SuiteCaseRepository states: without it the derived and @Query
// methods run outside any transaction, app.current_company is never set, and RLS answers zero rows.
@Transactional(readOnly = true)
public interface SuiteTagRuleRepository extends JpaRepository<SuiteTagRule, SuiteTagRuleId> {

    /** One suite's tag rules, alphabetically — a set, so the order is the reader's to choose. */
    @Query("""
            SELECT r FROM SuiteTagRule r
             WHERE r.suiteId = :suiteId
             ORDER BY r.tag
            """)
    List<SuiteTagRule> findForSuite(@Param("suiteId") UUID suiteId);

    /** Every rule for a page of suites, in one query. Never call with an empty collection. */
    @Query("""
            SELECT r FROM SuiteTagRule r
             WHERE r.suiteId IN :suiteIds
             ORDER BY r.tag
            """)
    List<SuiteTagRule> findForSuites(@Param("suiteIds") Collection<UUID> suiteIds);
}
