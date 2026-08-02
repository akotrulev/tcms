package com.tcms.execution;

import com.tcms.project.ProjectService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one resolution of {@code /projects/{p}/runs/{r}/executions/{e}} — <strong>the three read
 * gates a three-segment path needs, in one place</strong>.
 *
 * <p>Extracted in §72 from the byte-identical private copies in {@link ExecutionService} and
 * {@link EvidenceService}. The copy was not merely duplication: {@code EvidenceService}'s own
 * javadoc said <em>"the same three predicates {@code ExecutionService#resolve} applies, and for the
 * same reasons"</em> — a hand-maintained copy under an instruction to keep it in step, which is the
 * shape §58's E1 and §70's verb list each turned out to be. It became a defect the moment both
 * needed the same change, which is this slice: the run-closed rule has to see the <em>run</em>, and
 * the old signature threw it away.
 *
 * <h2>Three predicates, and each one closes a different path</h2>
 *
 * <ol>
 *   <li>the project gate — may this caller see this project at all;</li>
 *   <li>the run's project — is that run in it, and is it live;</li>
 *   <li>the execution's run — is that execution in <em>that</em> run.</li>
 * </ol>
 *
 * <p>Dropping the last would serve an execution from a different run through a path naming the run
 * the client is looking at: a result on the wrong screen, with no error.
 *
 * <p><strong>Not {@code @Transactional}</strong>, deliberately and unchanged from the copies it
 * replaces. The repositories bring their own read transaction, and {@link EvidenceService#open}
 * calls this from a method that must <em>not</em> be in one — it returns an open stream the
 * controller writes to the response afterwards, and a transaction spanning that would hold a
 * database connection for the length of a download.
 */
@Component
class ExecutionTargets {

    private final ProjectService projects;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;

    ExecutionTargets(
            ProjectService projects,
            TestRunRepository runs,
            TestExecutionRepository executions) {

        this.projects = projects;
        this.runs = runs;
        this.executions = executions;
    }

    /**
     * An execution and the run it belongs to.
     *
     * <p>The run is carried rather than re-read because every write on an execution now has to ask
     * the run whether it is still open ({@link TestRun#isClosed()}), and a second lookup for a row
     * this resolution already loaded is both a query and a chance for the two reads to disagree.
     */
    record Target(TestRun run, TestExecution execution) {
    }

    /** The target, or empty for every way it is unreachable — all of them one bodiless 404 (§6t). */
    Optional<Target> resolve(UUID userId, UUID projectId, UUID runId, UUID executionId) {
        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> runs.findLiveInProject(project.getId(), runId))
                .flatMap(run -> executions
                        .findInRun(run.getProjectId(), run.getId(), executionId)
                        .map(execution -> new Target(run, execution)));
    }
}
