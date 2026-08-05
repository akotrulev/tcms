package com.tcms.documentation;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
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

    /**
     * The plan's key and name, when it is a live plan of {@code projectId}.
     *
     * <p>The second question this file asks, and the one that needed more than a boolean:
     * {@link PlanDocumentService} titles a plan's document after the plan, and a document called
     * "Untitled" beside a plan called something else is how a documentation space becomes
     * unnavigable. So the columns come back, and the alternative — returning the boolean and letting
     * the caller title the page from what the client sent — would let a client name the document
     * after a different plan entirely.
     *
     * <p><strong>{@code deleted_at IS NULL} here and not in {@link #planExistsInProject}</strong>,
     * and the asymmetry is deliberate rather than an oversight in one of them. That method guards an
     * artifact register entry against a plan of another project; a deleted plan's artifacts are
     * still its artifacts and refusing them would strand rows nobody could file. This one is a
     * create, and starting to write a document for a plan somebody has deleted produces a page that
     * is about nothing, holds the plan's only unique-index slot, and is reachable from no screen.
     *
     * @return empty when there is no such plan, when it belongs to another project, or when it has
     *         been deleted — one answer, because the caller turns all three into the same 404 (§6t)
     */
    Optional<PlanRef> findLivePlanInProject(UUID projectId, UUID planId) {
        List<?> rows = entities
                .createNativeQuery("""
                        SELECT key, name FROM test_plan
                         WHERE id = :planId
                           AND project_id = :projectId
                           AND deleted_at IS NULL
                         LIMIT 1
                        """)
                .setParameter("planId", planId)
                .setParameter("projectId", projectId)
                .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        return Optional.of(new PlanRef((String) row[0], (String) row[1]));
    }

    /**
     * A plan's key, <strong>deleted plans included</strong> — the one lookup here that does not
     * filter on {@code deleted_at}.
     *
     * <p>It has one caller: the refusal that tells somebody the page they are trying to adopt is
     * already another plan's document. A soft-deleted plan still holds its page's subject slot —
     * {@code uq_doc_page_subject} is partial on the <em>page's</em> delete and knows nothing about
     * the plan's — so the plan named in that {@code 409} is routinely one that no longer appears in
     * any list. Filtering it out here would produce the refusal with no name in it, which is the
     * answer that cannot be acted on: the reader is told the page is taken and not by what.
     *
     * @return empty only when the id names no plan of this project at all, which after
     *         {@code ck_doc_page_subject_kind} means a page whose subject was written by something
     *         other than this service
     */
    Optional<String> findAnyPlanKeyInProject(UUID projectId, UUID planId) {
        List<?> rows = entities
                .createNativeQuery("""
                        SELECT key FROM test_plan
                         WHERE id = :planId
                           AND project_id = :projectId
                         LIMIT 1
                        """)
                .setParameter("planId", planId)
                .setParameter("projectId", projectId)
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.get(0));
    }

    /**
     * The two columns of a plan this module is allowed to know about.
     *
     * <p>Not a {@code TestPlan} and not a {@code TestPlanView}: those are {@code execution}'s
     * vocabulary, and holding a foreign module's shape in order to read two fields off it is how a
     * boundary drawn in {@code package-info} stops being real.
     */
    record PlanRef(String key, String name) {
    }
}
