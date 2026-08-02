package com.tcms.execution;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** A plan's scope lines. {@code @Transactional} for the reason on {@link TestPlanRepository}. */
@Transactional(readOnly = true)
public interface PlanScopeItemRepository extends JpaRepository<PlanScopeItem, UUID> {

    /**
     * The scope lines of several plans at once.
     *
     * <p><strong>Batched rather than per-plan on purpose.</strong> The plan list serves this shape
     * too, so a per-plan lookup would be one query per row — the N+1 that
     * {@link CaseFacts#caseCountsByPlan} is batched to avoid two fields over, on the same endpoint.
     * The caller guards the empty case, which is the convention there as well.
     *
     * <p>Unordered. The two id lists are sorted by {@link PlanService} before they are served,
     * because they are compared: a plan whose scope is served in one order and stored in another
     * would hash as changed on a save that changed nothing — see {@link PlanVersions}.
     */
    @Query("SELECT i FROM PlanScopeItem i WHERE i.planId IN :planIds")
    List<PlanScopeItem> findForPlans(@Param("planIds") Collection<UUID> planIds);

    /**
     * Removes every scope line of one plan, so the edit can write the submitted set.
     *
     * <p>Replace rather than reconcile, unlike {@code repository.CaseClassifications}: a scope item
     * carries no identity of its own and nothing references one — no id is on the wire, no row
     * points at it, and {@code plan_scope_item} has no timestamps — so a delete-and-insert loses
     * nothing a reconcile would have preserved. The reconcile there exists because a
     * classification's row is the assignment; here the set <em>is</em> the value.
     *
     * <p><strong>The version row is what makes this safe</strong>: the composition this replaces is
     * recorded in {@code test_plan_version} before it is discarded, which is exactly what §6j's
     * scope history is for.
     */
    @Modifying
    @Query("DELETE FROM PlanScopeItem i WHERE i.planId = :planId")
    void deleteForPlan(@Param("planId") UUID planId);
}
