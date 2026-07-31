package com.tcms.execution;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One line of a plan's scope — <em>either</em> a suite <em>or</em> a case, never both and never
 * neither ({@code ck_plan_scope_target}, V1).
 *
 * <h2>Two ids into {@code repository}, and this entity does not read either of them</h2>
 *
 * <p>{@code suite_id} and {@code case_id} are foreign keys into the module this one may not import.
 * That is the schema's deliberate shape (ERD §5) and the package comment's "a shared row is not a
 * shared class": this entity <strong>stores</strong> the ids and never resolves them. Every question
 * that needs the other side — is this suite live, which cases does this folder contain — goes
 * through {@link CaseFacts}, which stays the one crossing.
 *
 * <p>Which is why there is no {@code @ManyToOne} here. A JPA association would be the code edge
 * ArchUnit forbids, arrived at by autocomplete rather than by decision.
 *
 * <h2>A suite item is the folder AND its subtree, and that is not stored</h2>
 *
 * <p>Nothing here records the expansion. A plan whose scope names "Payments" covers whatever is in
 * Payments <em>when the question is asked</em> — which is what the person who added the folder
 * meant, and it is why {@link CaseFacts#caseCountsByPlan} walks the {@code ltree} path on every
 * read rather than materialising a membership list that would be wrong by the next case authored.
 *
 * <p>The run is where that stops being true: creating one resolves the expansion once and writes an
 * execution per case, so the run's checklist is fixed even though the plan's scope is not. Both are
 * correct and they are different questions — "what is in scope" versus "what did we agree to test
 * on this pass".
 */
@Entity
@Table(name = "plan_scope_item")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class PlanScopeItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    /** Null when this item names a case. Exactly one of the two is set. */
    @Column(name = "suite_id", updatable = false)
    private UUID suiteId;

    /** Null when this item names a suite. */
    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    /** JPA only. */
    protected PlanScopeItem() {
    }

    /** A suite item — the folder and, at read time, everything beneath it. */
    static PlanScopeItem ofSuite(UUID planId, UUID suiteId) {
        return of(planId, suiteId, null);
    }

    /** A case item — exactly that case. */
    static PlanScopeItem ofCase(UUID planId, UUID caseId) {
        return of(planId, null, caseId);
    }

    /**
     * @throws IllegalArgumentException if both or neither id is set — the same condition
     *         {@code ck_plan_scope_target} enforces, checked here so the failure names the two
     *         factories above rather than arriving as a constraint violation at flush with no
     *         indication of which caller built the row.
     */
    private static PlanScopeItem of(UUID planId, UUID suiteId, UUID caseId) {
        if ((suiteId == null) == (caseId == null)) {
            throw new IllegalArgumentException(
                    "A plan scope item names a suite or a case, never both and never neither");
        }
        PlanScopeItem item = new PlanScopeItem();
        item.id = UUID.randomUUID();
        item.companyId = CompanyContext.require();
        item.planId = planId;
        item.suiteId = suiteId;
        item.caseId = caseId;
        return item;
    }

    UUID getId() {
        return id;
    }

    UUID getPlanId() {
        return planId;
    }

    UUID getSuiteId() {
        return suiteId;
    }

    UUID getCaseId() {
        return caseId;
    }
}
