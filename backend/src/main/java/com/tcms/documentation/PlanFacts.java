package com.tcms.documentation;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names an {@code execution} table.</strong>
 *
 * <p>Fourth instance of the pattern {@code work.BoardFacts} documents: {@code execution.CaseFacts}
 * reads {@code repository}, {@code repository.ExecutionFacts} reads {@code execution},
 * {@code work.BoardFacts} reads both, and this reads {@code test_plan}. ArchUnit permits a
 * cross-module dependency only toward {@code tenancy}, {@code identity} and {@code project} (plan
 * §3), so a module that needs a fact from a sibling holds an id into it and reads it through exactly
 * one file.
 *
 * <p>One fact lives on the other side, and the register genuinely cannot be correct without it. A
 * plan-scope artifact carries a {@code plan_id}, and {@code V1}'s foreign key guarantees only that
 * the id names <em>some</em> plan — not one belonging to the project the request was gated on. Left
 * unchecked, a caller could attach their cycle's sign-off to a plan of a sibling project, and the
 * row would then be invisible to both registers: {@link ArtifactRepository#findForPlan} filters on
 * project and plan together, so it would match neither project's reads. Data that no screen can show
 * and no query will find is worse than a rejected request.
 *
 * <p>Native SQL rather than an entity, for {@code BoardFacts}' reason: a second JPA entity mapped
 * over a table another module owns is one table with two sets of column expectations for
 * {@code ddl-auto: validate}, and two places a mapping can drift.
 *
 * <p>RLS covers the statement — it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager} and a plan in another
 * company is already invisible. The project predicate is layered on top as the second gate, never
 * instead of it: RLS answers "is it this company's", and only the predicate answers "is it this
 * project's".
 */
@Component
class PlanFacts {

    private final EntityManager entities;

    PlanFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Whether {@code planId} names a plan of {@code projectId}, in the company currently open.
     *
     * <p>A single boolean rather than the plan itself. Nothing here renders a plan — the register
     * shows the artifacts, and the SPA already has the plan list it filtered by — so returning a row
     * would be handing this module a shape from another one's vocabulary in order to ignore it.
     */
    boolean planExistsInProject(UUID projectId, UUID planId) {
        return !entities
                .createNativeQuery("""
                        SELECT 1 FROM test_plan
                         WHERE id = :planId
                           AND project_id = :projectId
                         LIMIT 1
                        """)
                .setParameter("planId", planId)
                .setParameter("projectId", projectId)
                .getResultList()
                .isEmpty();
    }
}
