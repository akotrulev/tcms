package com.tcms.project;

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
public interface ClassificationValueRepository extends JpaRepository<ClassificationValue, UUID> {

    /**
     * Live values for a set of dimensions, in <strong>one</strong> query.
     *
     * <p>One query for all dimensions rather than one per dimension — the N+1 that a mapped
     * {@code @OneToMany} would have produced for free. The service groups the result by
     * {@code dimensionId}; nothing here needs an association to do that, and not mapping one keeps
     * the two aggregates independently readable.
     *
     * <p>{@code deletedAt IS NULL} is not optional. A value may already be attached to authored
     * cases and to executions that ran under it, so retiring one is a soft delete — and without
     * this predicate a retired value comes back as pickable in the case editor.
     *
     * <p>Ordered by {@code position} then {@code name}: position carries the seed's intended order
     * for the flat vocabularies (Functional before Performance, not alphabetical), and the name
     * breaks ties so the order is stable between requests. For a hierarchical dimension the client
     * builds the tree from {@code parentId}; sibling ordering within a Feature tree becomes the
     * writer's problem when a writer exists, since nothing allocates positions there yet.
     */
    @Query("""
            SELECT v FROM ClassificationValue v
             WHERE v.dimensionId IN :dimensionIds
               AND v.deletedAt IS NULL
             ORDER BY v.position ASC, v.name ASC
            """)
    List<ClassificationValue> findLiveForDimensions(
            @Param("dimensionIds") Collection<UUID> dimensionIds);
}
