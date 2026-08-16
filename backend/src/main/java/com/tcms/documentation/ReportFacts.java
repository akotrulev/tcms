package com.tcms.documentation;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names a {@code reporting} table.</strong>
 *
 * <p>{@link PlanFacts}' pattern, second instance here and fifth in the codebase — {@code execution
 * .CaseFacts} reads {@code repository}, {@code repository.ExecutionFacts} reads {@code execution},
 * {@code work.BoardFacts} reads both, {@code PlanFacts} reads {@code test_plan}, and this reads
 * {@code report_job}. ArchUnit permits a cross-module dependency only toward {@code tenancy},
 * {@code identity}, {@code project} and {@code storage} (plan §3), so a module needing a fact from a
 * sibling holds an id into it and reads it through exactly one file.
 *
 * <p>Native SQL rather than an entity, for {@code BoardFacts}' reason: a second JPA entity mapped
 * over a table another module owns is one table with two sets of column expectations for
 * {@code ddl-auto: validate}, and two places a mapping can drift.
 *
 * <h2>Why the question is asked of the job and answered with the artifact</h2>
 *
 * <p>{@code report_artifact} carries no {@code project_id} — {@code V1} scoped it by company and
 * definition, and {@code ReportArtifact}'s header records that as designed rather than missed. So an
 * artifact id alone cannot be proved to belong to the project in the request path, and the
 * reporting module's own download route already routes through {@code report_job} for exactly this
 * reason. Registering a pinned report therefore takes the <em>job</em> id and stores the
 * <em>artifact</em> id: the caller names the thing they were looking at, and the register keeps the
 * thing that will not change under it.
 *
 * <p>RLS covers the statement — it runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager} and another company's
 * job is already invisible. The project predicate is layered on top as the second gate, never
 * instead of it: RLS answers "is it this company's", and only the predicate answers "is it this
 * project's".
 */
@Component
class ReportFacts {

    private final EntityManager entities;

    ReportFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * The artifact a completed job of {@code projectId} produced, if it is one.
     *
     * <p>{@code artifact_id IS NOT NULL} rather than {@code status = 'COMPLETED'}, and the two
     * agree in the designed system — {@code ReportJob.complete} sets both in one call, after the
     * bytes are in the store. The column is the one that is actually false for the row this refusal
     * exists to catch: a job that failed, is still running, or was completed by a deployment whose
     * renderer wrote no artifact. {@code ReportJobView.modelUrl} keys on its sibling column for the
     * same reason and says so.
     *
     * @return empty when no such job exists, when it belongs to another project, or when it has no
     *     bytes to pin — one answer, because the caller turns all three into the same field error
     *     (§6t: a distinct message would let a caller probe which job ids are real)
     */
    Optional<UUID> findRenderedArtifactInProject(UUID projectId, UUID jobId) {
        List<?> rows = entities
                .createNativeQuery("""
                        SELECT artifact_id FROM report_job
                         WHERE id = :jobId
                           AND project_id = :projectId
                           AND artifact_id IS NOT NULL
                         LIMIT 1
                        """)
                .setParameter("jobId", jobId)
                .setParameter("projectId", projectId)
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of((UUID) rows.get(0));
    }
}
