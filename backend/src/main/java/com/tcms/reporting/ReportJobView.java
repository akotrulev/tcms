package com.tcms.reporting;

import java.time.Instant;
import java.util.UUID;

/**
 * What a client is told about a generation request — the contract's {@code ReportJob}, served by
 * both the 202 and the poll.
 *
 * <h2>One shape for the accept and the poll, deliberately</h2>
 *
 * <p>The 202 could have answered with the id alone. It answers with the whole job because a client
 * that receives one shape on the accept and a different one from the poll has to hold two readings
 * of "where is my report" — and the accept's shape would be the one that is never exercised again,
 * which is the copy that drifts (§58 E1). Serving the same record means the first response is a
 * poll response that happens to be free.
 *
 * <h2>Every field is present; three are null until they are not</h2>
 *
 * <p>{@code downloadUrl}, {@code modelUrl} and {@code error} are required-and-nullable in the
 * contract. A poller reads {@code status} to decide whether to keep polling, and must not also have
 * to tell "this server did not send the field" from "there is no error" — the second is a fact and
 * the first is a bug, and an absent key renders as whichever the client's language calls missing
 * ({@code ContractConventionTest.requiredPropertiesHaveAComponent}, which this record is registered
 * with).
 *
 * <h2>The URLs are composed HERE and never by the client</h2>
 *
 * <p>{@code Evidence.url}'s rule (§67): a client that builds the download path itself holds a second
 * copy of the server's routing, so moving the route becomes a breaking change nothing detects. The
 * path is project-scoped because that is what the download gate resolves against — see
 * {@link ReportJobRepository#findByArtifact} for why the artifact alone cannot scope itself.
 *
 * <h2>The two URLs name two routes, and the difference is a permission</h2>
 *
 * <p>{@code downloadUrl} is the artifact route ({@code report:export}); {@code modelUrl} is the
 * job's own model route ({@code report:view}). They used to be the same route with the same key,
 * which made reading a report on screen cost exactly what carrying it away costs — the two-key split
 * §6e describes, unexpressible in the product that declared it. The model URL is composed from the
 * <em>job</em> id rather than the artifact id for that reason: the gate that answers it is the poll's
 * gate, and a route keyed by artifact would have to prove the artifact's project through the job
 * anyway ({@link ReportJobRepository#findByArtifact}).
 *
 * @param sizeBytes the rendered artifact's size, or <strong>null when there is no artifact</strong>
 *     — a job that has not completed, one that failed, and one whose bytes have gone missing from
 *     the store. Joined here (005 S1) so a list of jobs can say "2.4 MB" without a call per row, and
 *     named with its unit after {@code Artifact.sizeBytes} rather than as a bare {@code size}: a
 *     number called "size" beside a report is as readable as pages, rows or bytes, and only one of
 *     those is true.
 *     <p>Read from the <em>store</em> and not from a column, which is a decision rather than an
 *     omission. {@code report_artifact} has no size and adding one would have meant a backfill no
 *     migration can perform — Flyway runs as {@code tcms_owner}, which holds no {@code BYPASSRLS},
 *     so an {@code UPDATE} over a forced-RLS table reports a confident zero rows ({@code V45} §4).
 *     Every already-completed job would have served null for ever, on exactly the rows this field
 *     exists to describe. One {@code stat} per row answers all of them.
 * @param modelUrl the serialized {@link ReportModel} — written for every job, so the on-screen view
 *     and the export are one model rendered twice rather than two computations that can disagree
 *     (plan §6e). Null in exactly the states that have no stored model, so a client that follows the
 *     field never asks for one that is not there.
 */
public record ReportJobView(
        UUID jobId,
        String type,
        String status,
        String format,
        Instant requestedAt,
        String downloadUrl,
        String modelUrl,
        String error,
        Long sizeBytes) {

    /**
     * A job with no size resolved — every caller that is not the job list or the poll.
     *
     * <p>Null rather than a lookup, and the two callers that <em>can</em> answer pass it in. The
     * accept (202) is the interesting one: nothing has been rendered yet by construction, so a size
     * would be null however hard it were sought.
     */
    static ReportJobView of(ReportJob job) {
        return of(job, null);
    }

    static ReportJobView of(ReportJob job, Long sizeBytes) {
        return new ReportJobView(
                job.getId(),
                job.getType(),
                job.getStatus().name(),
                job.getFormat().name(),
                job.getRequestedAt(),
                contentUrl(job.getProjectId(), job.getArtifactId()),
                modelUrl(job),
                job.getError(),
                sizeBytes);
    }

    /**
     * The on-screen model's route, or null when there is nothing stored to serve.
     *
     * <p>Keyed on {@code modelArtifactId} rather than on {@code status}: the two agree in the
     * designed system, and the artifact id is the one that is actually false when a deployment older
     * than §88 completed the job. Reading the status instead would offer a URL that 404s on exactly
     * the rows this field exists to describe.
     */
    private static String modelUrl(ReportJob job) {
        return job.getModelArtifactId() == null
                ? null
                : "/api/v1/projects/" + job.getProjectId() + "/reports/jobs/" + job.getId()
                        + "/model";
    }

    /**
     * Null in, null out — which is what makes the field's nullability mean the one thing it says.
     *
     * <p>A job that has not completed has no artifact, and composing a URL for a null id would
     * produce a link to {@code …/artifacts/null/content}: a string that is not null, that a client
     * would render as a working download, and that 404s. The nullable field exists precisely so
     * there is nothing to render.
     */
    private static String contentUrl(UUID projectId, UUID artifactId) {
        return artifactId == null
                ? null
                : "/api/v1/projects/" + projectId + "/reports/artifacts/" + artifactId + "/content";
    }
}
