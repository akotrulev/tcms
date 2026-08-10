package com.tcms.execution;

import com.tcms.project.ProjectService;
import com.tcms.storage.RawReportStore;
import com.tcms.storage.StoredBlob;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ingestion history — what CI has posted to this project, and what each report did.
 *
 * <p>Two reads over {@code ingestion_job}, and between them they close the last of §117's dead
 * surfaces: the repository's history query had no caller, and {@code POST /ingestions} has been
 * answering <strong>201 with a {@code Location} naming a resource that 404s</strong> since the day
 * it shipped.
 *
 * <h2>The gate is membership, and no key was invented</h2>
 *
 * <p>Through {@link com.tcms.project.ProjectService#readVisible}, which is exactly how
 * {@link RunService#list} gates a project's runs. There is no {@code run:read} or {@code result:read}
 * in the catalogue — a project member may read that project's runs and their verdicts — and an
 * ingestion job is the record of how some of those verdicts got there. A new
 * {@code ingestion:read} key would be §67's refused shape for the twelfth time: a key seeded to
 * nobody is a 403 nobody can grant their way out of.
 *
 * <p>Note what that does <em>not</em> grant. Reading the history is not confirming a mapping: the
 * binder screen reads here and writes through {@link AutomationMappingService}, which costs
 * {@code case:edit} — so the person who can see which tests failed to bind is a superset of the
 * people who may decide what they mean, which is the right way round.
 *
 * <h2>Why the two reads serve different shapes</h2>
 *
 * <p>{@link IngestionJobSummary}'s header carries it in full. In one line: {@code unmatched} is not
 * a column, it lives inside a {@code JSONB} blob, and a list that served it would select every blob
 * in the project's history to render one screen.
 */
@Service
public class IngestionReadService {

    private final ProjectService projects;
    private final IngestionJobRepository jobs;
    private final TestRunRepository runs;
    private final UnmatchedTests unmatchedTests;

    /** The verbatim upload's home (§160) — {@code IngestionService} is the writer. */
    private final RawReportStore rawReports;

    public IngestionReadService(
            ProjectService projects,
            IngestionJobRepository jobs,
            TestRunRepository runs,
            UnmatchedTests unmatchedTests,
            RawReportStore rawReports) {

        this.projects = projects;
        this.jobs = jobs;
        this.runs = runs;
        this.unmatchedTests = unmatchedTests;
        this.rawReports = rawReports;
    }

    /**
     * A project's ingestion jobs, newest first — empty {@link Optional} when the caller may not see
     * the project, an empty <em>list</em> when it has never had a report posted.
     *
     * <p>{@code RunService.list}'s two-level answer, for its reason: the caller named a project, so
     * "not reachable" and "reachable and empty" are different facts and only one of them is a 404.
     */
    @Transactional(readOnly = true)
    public Optional<List<IngestionJobSummary>> list(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project -> {
            List<IngestionJobRow> rows = jobs.findSummariesInProject(project.getId());
            if (rows.isEmpty()) {
                return List.<IngestionJobSummary>of();
            }

            Map<UUID, String> runKeys = runKeys(project.getId(), rows);

            List<IngestionJobSummary> summaries = new ArrayList<>(rows.size());
            for (IngestionJobRow row : rows) {
                summaries.add(new IngestionJobSummary(
                        row.id(),
                        row.status(),
                        row.format(),
                        row.runId(),
                        row.runId() == null ? null : runKeys.get(row.runId()),
                        row.matchedCount(),
                        row.unmatchedCount(),
                        row.createdAt()));
            }
            return List.copyOf(summaries);
        });
    }

    /**
     * One job with its unmatched tests — what the binder screen reads, and the resource
     * {@code POST /ingestions}' {@code Location} names.
     *
     * <p>Empty for every way it can be unreachable, which the controller answers as one bodiless
     * 404: an unreachable project, another project's job id, and a job id that is nothing at all are
     * deliberately indistinguishable (§6t).
     */
    @Transactional(readOnly = true)
    public Optional<IngestionJobView> findOne(UUID userId, UUID projectId, UUID jobId) {
        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> jobs.findOneInProject(project.getId(), jobId))
                .map(job -> new IngestionJobView(
                        job.getId(),
                        job.getStatus(),
                        job.getFormat(),
                        job.getRunId(),
                        job.getRunId() == null
                                ? null
                                : runs.findLiveInProject(job.getProjectId(), job.getRunId())
                                        .map(TestRun::getKey)
                                        .orElse(null),
                        job.getMatchedCount(),
                        job.getUnmatchedCount(),
                        // Capped exactly as the post's own response is, by the same constant: a
                        // screen that could ask for 5,000 names by following a Location header would
                        // make the cap on the post cosmetic.
                        IngestionService.cap(unmatchedTests.of(job.getRawPayload())),
                        IngestionJobView.reportUrl(
                                job.getProjectId(), job.getId(), job.getRawStorageKey()),
                        job.getCreatedAt()));
    }

    /** A download, ready to be written to the response. The caller closes {@link #blob()}. */
    public record RawReport(String filename, String contentType, StoredBlob blob) {
    }

    /**
     * The verbatim file CI posted for this job (§160) — the read half of {@code raw_storage_key}.
     *
     * <p><strong>Not {@code @Transactional}, and neither is {@link #locate}</strong> — which is
     * {@code ReportReadService.open}'s shape for two reasons that compound. This returns an
     * <em>open stream</em> the controller writes to the response after the method returns, so a
     * transaction around it would either be closed under the stream or held for the length of the
     * download. And annotating {@code locate} instead would be a <em>self-invocation</em>: Spring's
     * proxy does not intercept a call from this class to its own method, so the annotation would do
     * nothing — under {@code CompanyAwareTransactionManager} not merely a missing rollback but a
     * missing {@code app.current_company}, and every RLS-protected read silently returning zero
     * rows. {@code IngestionService}'s {@code TransactionTemplate} field carries the same warning
     * from the write side, where it cost §116 a red run. The boundaries here are
     * {@code ProjectService.readVisible}'s and each repository's own, which is where isolation is
     * established for both other reads on this class.
     *
     * <p><strong>Membership, not a new key.</strong> Same gate as {@link #list} and {@link #findOne}
     * — {@code ProjectService.readVisible} — and for this class's stated reason: there is no
     * {@code ingestion:read} in the catalogue and inventing one would be §67's refused shape, a key
     * seeded to nobody that no administrator can grant their way out of. The upload is the evidence
     * behind verdicts a project member may already read in full, and it is the same bytes that
     * member's own CI job posted.
     *
     * <p><strong>Three different facts answer as one empty.</strong> An unreachable project, a job
     * id that is not this project's, a pre-§160 row whose key is null, and a key whose blob is gone
     * all return empty and the controller answers one bodiless 404 (§6t). The first three are the
     * §6t rule; the fourth is a judgment and worth naming: a caller cannot act differently on "never
     * stored" than on "stored and pruned", so a second spelling would be a distinction with no act
     * behind it. The operator's signal is the store adapter's own log line.
     */
    public Optional<RawReport> openRawReport(UUID userId, UUID projectId, UUID jobId) {
        return locate(userId, projectId, jobId)
                .flatMap(located -> rawReports.open(located.storageKey())
                        .map(blob -> new RawReport(located.filename(), located.contentType(), blob)));
    }

    /** Everything the download needs out of the database, read before the stream is opened. */
    private record Located(String storageKey, String filename, String contentType) {
    }

    /** The row behind the bytes. Deliberately unannotated — see {@link #openRawReport}. */
    private Optional<Located> locate(UUID userId, UUID projectId, UUID jobId) {
        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> jobs.findOneInProject(project.getId(), jobId))
                .filter(job -> job.getRawStorageKey() != null)
                .map(job -> new Located(
                        job.getRawStorageKey(),
                        filename(job.getId(), job.getFormat()),
                        contentType(job.getFormat())));
    }

    /**
     * What the file is called when it lands in somebody's downloads folder.
     *
     * <p>{@code ReportReadService.filename}'s rule verbatim: composed from server-generated values
     * only. This string reaches a {@code Content-Disposition} header, where a newline is header
     * injection rather than an odd name, and nothing a client sent is in it — CI never named this
     * file on the wire in the first place, because {@code IngestionRequest} carries the report as a
     * string and has no filename field.
     */
    private static String filename(UUID jobId, ReportFormat format) {
        return "ingestion-" + jobId + extensionOf(format);
    }

    /**
     * <strong>The dialect decides the extension, and two of the four are XML.</strong> This is
     * {@code IngestionRequest}'s own opening argument read backwards: a {@code Content-Type} could
     * not tell JUnit from TestNG on the way in, and it cannot on the way out either — so the name
     * carries the dialect and the media type carries only what a browser needs to not mangle it.
     */
    private static String extensionOf(ReportFormat format) {
        return switch (format) {
            case JUNIT -> "-junit.xml";
            case TESTNG -> "-testng.xml";
            case ALLURE -> "-allure.json";
            case CUCUMBER -> "-cucumber.json";
        };
    }

    /**
     * Served as its dialect's media type, not as {@code application/octet-stream}.
     *
     * <p>The point of keeping the file is that somebody can read it, and the commonest reader is a
     * browser tab opened from the ingestion history. XML and JSON both render there; an octet-stream
     * forces a download and then a second application to look at 40 lines of test names.
     */
    private static String contentType(ReportFormat format) {
        return switch (format) {
            case JUNIT, TESTNG -> "application/xml";
            case ALLURE, CUCUMBER -> "application/json";
        };
    }

    /** Every run key the page names, in one query — {@code AutomationMappingService.render}'s rule. */
    private Map<UUID, String> runKeys(UUID projectId, List<IngestionJobRow> rows) {
        List<UUID> runIds = rows.stream()
                .map(IngestionJobRow::runId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> keys = new HashMap<>();
        for (Object[] pair : runs.findRunKeys(projectId, runIds)) {
            keys.put((UUID) pair[0], (String) pair[1]);
        }
        return keys;
    }
}
