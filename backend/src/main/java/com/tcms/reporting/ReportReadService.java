package com.tcms.reporting;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.storage.ReportStore;
import com.tcms.storage.StoredBlob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two reads a generated report needs: where did my job get to, and give me the bytes.
 *
 * <p>Separate from {@link ReportJobService}, whose header says it in one line — that class is the
 * request half and writes one row. These two share nothing with it but the table, and they are the
 * only things in the module that a person waits on.
 *
 * <h2>Two endpoints, two keys, and the split is not arbitrary</h2>
 *
 * <p>The poll costs {@code report:view} and the download costs {@code report:export}, both seeded by
 * {@code V3} since M0 and neither invented here — a key {@code V3} seeds to nobody is a 403 no
 * administrator can grant their way out of (§67's argument against {@code evidence:read}, §79's,
 * §80's, §82's). Watching your own request finish is not taking anything away; fetching the file is
 * exactly what "export" names. A role that may read reports on screen and not carry them off is
 * therefore expressible, which is the only reason the catalogue has two keys rather than one.
 *
 * <h2>Both gates answer 403 where every other read in the product answers 404</h2>
 *
 * <p>Through {@link ProjectService#readGranted}, which is new in §87 and whose javadoc carries the
 * argument: visibility still answers 404, and what answers 403 is a feature of a project the caller
 * is demonstrably looking at. Telling a caller their own report request does not exist would be
 * false in the one direction a reporting pipeline must never be wrong.
 *
 * <h2>Neither read trusts an id alone</h2>
 *
 * <p>The gate is evaluated against the project in the path, so a job or an artifact resolved by id
 * alone would be served through a gate that had passed for a <em>different</em> project — the
 * version of that defect that survives an access-control test, because the access control ran and
 * ran on the wrong row. Hence {@link ReportJobRepository#findByIdAndProjectId} and
 * {@link ReportJobRepository#findByArtifact}, and no {@code findById} anywhere in this file.
 */
@Service
class ReportReadService {

    /** Roles spec §A5 / {@code V3}. Reading where a job got to is not exporting it. */
    private static final String VIEW_REPORT = "report:view";

    /** Roles spec §A5 / {@code V3}. Fetching the file is precisely what this key names. */
    private static final String EXPORT_REPORT = "report:export";

    private final ProjectService projects;
    private final ReportJobRepository jobs;
    private final ReportArtifactRepository artifacts;
    private final ReportRenderers renderers;
    private final ReportStore store;

    ReportReadService(
            ProjectService projects,
            ReportJobRepository jobs,
            ReportArtifactRepository artifacts,
            ReportRenderers renderers,
            ReportStore store) {

        this.projects = projects;
        this.jobs = jobs;
        this.artifacts = artifacts;
        this.renderers = renderers;
        this.store = store;
    }

    /** What a read produced. Sealed, so the controller's switch is exhaustive at compile time. */
    sealed interface Outcome<T> {

        /** Unreachable project, or an id that names nothing in it — one bodiless 404 (§6t). */
        record NotFound<T>() implements Outcome<T> {
        }

        /** The caller can see the project and does not hold the key this read costs. */
        record Forbidden<T>() implements Outcome<T> {
        }

        /** Here it is. */
        record Found<T>(T value) implements Outcome<T> {
        }
    }

    /** A download, ready to be written to the response. The caller closes {@link #blob()}. */
    record Download(String filename, String contentType, StoredBlob blob) {
    }

    /**
     * Where a generation request got to.
     *
     * <p>A job that {@code FAILED} is a 200 carrying {@code error}, never an error status. The
     * request to <em>look</em> succeeded; the report is what failed, and a 4xx or 5xx here would say
     * the poll was malformed or the server broken — which is what a client retries, rather than
     * showing the sentence that explains the actual problem.
     */
    @Transactional(readOnly = true)
    Outcome<ReportJobView> poll(UUID userId, UUID projectId, UUID jobId) {
        return gate(projects.readGranted(userId, projectId, VIEW_REPORT,
                project -> jobs.findByIdAndProjectId(jobId, project.getId())
                        .map(ReportJobView::of)));
    }

    /**
     * The artifact's bytes.
     *
     * <p><strong>Not {@code @Transactional}</strong>, and that is {@code ExecutionTargets}' reason
     * one module over: this returns an <em>open stream</em> that the controller writes to the
     * response after the method has returned, so a transaction around it would either be closed
     * under the stream or held open for the length of a download. The rows it reads are each read
     * inside the gate's own read-only transaction; what escapes it is a file handle, which was never
     * transactional to begin with.
     */
    Outcome<Download> open(UUID userId, UUID projectId, UUID artifactId) {
        Outcome<Located> located = gate(projects.readGranted(userId, projectId, EXPORT_REPORT,
                project -> locate(project.getId(), artifactId)));

        // Re-stated arm by arm rather than cast across the type parameter. The refusals carry no
        // value, so the "same" outcome at a different T is a new record and not a conversion — and
        // the switch is exhaustive, so a fourth outcome would be a compile error here rather than
        // whatever an `else` branch happened to be.
        return switch (located) {
            case Outcome.NotFound<Located> ignored -> new Outcome.NotFound<>();
            case Outcome.Forbidden<Located> ignored -> new Outcome.Forbidden<>();
            case Outcome.Found<Located> found -> read(found.value());
        };
    }

    /** Opens the bytes for an artifact the caller has already been gated onto. */
    private Outcome<Download> read(Located artifact) {
        Optional<StoredBlob> blob = store.open(artifact.storageKey());
        if (blob.isEmpty()) {
            // The row says the bytes exist and the store disagrees. Unlike evidence — where §67
            // chose row-first writes and this orphan is a designed outcome — the worker here writes
            // the bytes BEFORE the row, so reaching this branch means the store lost the file. A
            // 404 is still the honest answer to the client; the operator's signal is the store
            // adapter's own log line, and the job row that names exactly which report it was.
            return new Outcome.NotFound<>();
        }
        return new Outcome.Found<>(new Download(
                artifact.filename(), artifact.contentType(), blob.get()));
    }

    /** Everything the download needs out of the database, read before the stream is opened. */
    private record Located(String storageKey, String filename, String contentType) {
    }

    /**
     * The artifact, if the project in the path is the one that produced it.
     *
     * <p>Two lookups rather than one because {@code report_artifact} has no {@code project_id}
     * ({@code V1} scoped it by company and definition), so the job is the only row that scopes it —
     * and the job is also where the download's filename comes from, since an artifact does not
     * record which report it is.
     */
    private Optional<Located> locate(UUID projectId, UUID artifactId) {
        return jobs.findByArtifact(projectId, artifactId)
                .flatMap(job -> artifacts.findById(artifactId)
                        .map(artifact -> new Located(
                                artifact.getStorageKey(),
                                filename(job.getType(), artifactId, artifact.getFormat()),
                                contentType(artifact.getFormat()))));
    }

    /**
     * What the file is called when it lands in somebody's downloads folder.
     *
     * <p>Composed from the report type and the artifact id, both server-generated. Nothing a client
     * sent reaches it — this string goes into a {@code Content-Disposition} header, where a newline
     * is header injection rather than an odd name, and the surest way to keep it out is for no
     * client-supplied value to be in the string at all.
     */
    private String filename(String type, UUID artifactId, ReportFormat format) {
        return type + "-" + artifactId + "." + extensionOf(format);
    }

    /**
     * The media type and the extension both come from the renderer that produced the format.
     *
     * <p>{@link ReportRenderer}'s header states why: one fact in one place, so R4's HTML renderer is
     * a class answering {@code text/html} rather than a class plus a line in a {@code switch} here.
     * The fallbacks exist because {@code ReportFormat} has six values and only the renderable ones
     * have a renderer — an artifact in a format this deployment can no longer render is still
     * downloadable, as bytes, which is better than a 500 on a file that is sitting right there.
     */
    private String contentType(ReportFormat format) {
        return renderers.find(format)
                .map(ReportRenderer::contentType)
                .orElse("application/octet-stream");
    }

    private String extensionOf(ReportFormat format) {
        return renderers.find(format)
                .map(ReportRenderer::fileExtension)
                .orElseGet(() -> format.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Collapses a gate result over an {@link Optional} into the three outcomes.
     *
     * <p>An unreachable project and an id that names nothing inside a reachable one are the same
     * {@code NotFound}, deliberately and for §6t's reason: a client must not be able to use the
     * difference to learn that a project exists.
     */
    private static <T> Outcome<T> gate(GateResult<Optional<T>> result) {
        return switch (result) {
            case GateResult.Unreachable<Optional<T>> ignored -> new Outcome.NotFound<>();
            case GateResult.Denied<Optional<T>> ignored -> new Outcome.Forbidden<>();
            case GateResult.Allowed<Optional<T>> allowed -> allowed.value()
                    .<Outcome<T>>map(Outcome.Found::new)
                    .orElseGet(Outcome.NotFound::new);
        };
    }
}
