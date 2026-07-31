package com.tcms.repository;

import java.util.Collection;
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
public interface CaseClassificationRepository
        extends JpaRepository<CaseClassification, CaseClassificationId> {

    /**
     * Every classification on a page of cases, in <strong>one</strong> query.
     *
     * <p>Same one-query-per-page reasoning as {@link CaseTagRepository#findForCases}, and the same
     * requirement that the caller not pass an empty collection.
     *
     * <p>The rows come back unresolved — value ids, not names — because one page can carry values
     * from all four dimensions and the caller needs them bucketed by dimension anyway. Resolving a
     * name here would mean a join to {@code classification_value} that returns the retired ones
     * too, and a retired value must not be rendered as a live classification.
     *
     * <p>No ordering: the caller orders each case's values by the vocabulary's own
     * {@code position}, which is the order the SPA already shows them in elsewhere. Ordering by
     * {@code value_id} here would be a UUID ordering pretending to be an authored one.
     */
    @Query("""
            SELECT c FROM CaseClassification c
             WHERE c.caseId IN :caseIds
            """)
    List<CaseClassification> findForCases(@Param("caseIds") Collection<UUID> caseIds);
}
