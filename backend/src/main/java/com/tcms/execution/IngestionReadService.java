package com.tcms.execution;

import com.tcms.project.ProjectService;
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

    public IngestionReadService(
            ProjectService projects,
            IngestionJobRepository jobs,
            TestRunRepository runs,
            UnmatchedTests unmatchedTests) {

        this.projects = projects;
        this.jobs = jobs;
        this.runs = runs;
        this.unmatchedTests = unmatchedTests;
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
                        job.getCreatedAt()));
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
