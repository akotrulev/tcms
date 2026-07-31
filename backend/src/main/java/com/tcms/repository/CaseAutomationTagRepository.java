package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
@Transactional(readOnly = true)
public interface CaseAutomationTagRepository
        extends JpaRepository<CaseAutomationTag, CaseTagId> {

    /**
     * One case's automation tags.
     *
     * <p>Keyed on the case rather than on a page of them, unlike {@link CaseTagRepository}: the
     * grid renders organisational tags and does not render these, so there is no page to batch and
     * a collection parameter would only invite a list screen to load them.
     *
     * <p>Ordered by tag. There is no authored order to preserve — the table has no position column
     * because selection tags are a set — and a fixed order is what stops a row's chips reshuffling
     * between two reads of the same case.
     *
     * <p>Served by the table's primary key {@code (case_id, tag)}, whose leading column is the one
     * being sought. The only other index on the table is on {@code tag} alone, for the query that
     * runs in the other direction: "which cases carry {@code @smoke}".
     */
    @Query("""
            SELECT t FROM CaseAutomationTag t
             WHERE t.caseId = :caseId
             ORDER BY t.tag ASC
            """)
    List<CaseAutomationTag> findForCase(@Param("caseId") UUID caseId);
}
