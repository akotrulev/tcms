package com.tcms.reporting;

import com.tcms.storage.ReportStore;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker: claim a pending job, refresh the facts, compute it, write the bytes, complete the row.
 *
 * <p>Called once per company per tick by {@link ReportJobScheduler}, which is what establishes the
 * company this class then takes for granted. Every method here requires an open
 * {@link CompanyContext} and none of them takes a company id — {@code report_job} is under
 * {@code FORCE ROW LEVEL SECURITY}, so the context is not a convenience, it is the only thing that
 * makes {@link ReportJobRepository#findFirstByStatusOrderByRequestedAtAsc} return anything at all.
 *
 * <h2>Three transactions per job, not one, and the claim is the reason</h2>
 *
 * <p>The tempting shape is one transaction around the whole thing: claim, compute, complete. It is
 * simpler and it makes a process that dies mid-run leave the job {@code PENDING}, to be retried by
 * the next tick — which sounds like the better failure until the job is one that kills the process
 * <em>every</em> time. Then nothing ever commits, {@code attempts} stays {@code 0} for ever, and the
 * only trace is a worker that appears to be idle. So the claim commits on its own
 * ({@link ReportJob#claim} makes the same argument from the entity's side): a poison job ends up
 * visibly {@code RUNNING} with {@code attempts} counting, rather than invisibly retried.
 *
 * <p>~~There is deliberately no reaper for a job stranded in {@code RUNNING}. Bringing one back to
 * {@code PENDING} is the retry policy this slice does not have — a {@code FAILED} or stuck job is
 * re-requested by the user, which is honest and costs one click, and a re-request is a new row so
 * the record of what was asked for and what it produced stays one request per row.~~ →
 * <strong>superseded: {@link #failStuckJobs} exists, and the paragraph above was answering a
 * question nobody asked.</strong> Every word of it is about <em>retrying</em>, and the reaper does
 * not retry: a claim older than {@code tcms.reporting.stuck-job-timeout} moves to {@code FAILED}
 * with a sentence, a re-request is still a new row, and the state machine still only goes forwards.
 * What the old paragraph left standing was the row itself — a job whose worker died after claiming
 * stayed {@code RUNNING} for ever, so the client polling it watched a spinner with no end and no
 * error, and nothing in the product could tell that reader or an operator what had happened.
 *
 * <p>{@link TransactionTemplate} rather than three {@code @Transactional} methods, because they
 * would be self-invocations on this bean and Spring's proxy would not see them — a boundary that
 * silently is not there. {@code InvitationService} and {@code CompanyProvisioningService} are the
 * precedent for the idiom.
 *
 * <h2>BYTES FIRST, ROW SECOND — the inverse of {@code EvidenceService}, deliberately</h2>
 *
 * <p>A filesystem does not roll back with the transaction that wrote the row, so one of the two
 * orderings has to be chosen and the choice is about which half of the inconsistency is
 * recoverable. §67 chose rows-first for evidence, because a row whose blob is missing is findable by
 * an operator while a blob no row names is unreachable and invisible.
 *
 * <p>Here the argument runs the other way, and it is not a contradiction: <strong>the job row
 * already names the work</strong>. It was written by the request, it is {@code RUNNING}, and the
 * storage key is built from its id — so an orphan blob <em>is</em> findable, it belongs to the job
 * that failed beside it. What is not recoverable is the other ordering's failure: a
 * {@code COMPLETED} job whose download 404s, which the client has already been told is ready and
 * which no sweep can repair. This paragraph exists in {@link ReportStore}'s header too, because a
 * reader who knows {@code EvidenceService} will otherwise read this file as a bug.
 *
 * <h2>The facts are refreshed before every job</h2>
 *
 * <p>{@link ExecutionFactRefreshService#refresh()} runs for the open company immediately before the
 * computation (slice-plan decision A), so {@code report_artifact.data_watermark} is exact rather
 * than "some time since the last quarter-hour tick". {@link ExecutionFactScheduler}'s schedule is
 * therefore the floor on staleness for anything reading facts <em>outside</em> a job, and never what
 * a generated report says.
 */
@Service
class ReportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportJobRunner.class);

    /**
     * How many jobs one tick will drain for one company.
     *
     * <p>A bound rather than "until empty", so a company that queues faster than the worker renders
     * cannot hold the loop and starve every company after it. Reports arrive in ones and twos; the
     * number is high enough that the bound is never the reason a queue moves slowly, and low enough
     * that a runaway client costs one tick rather than the tick.
     */
    private static final int MAX_JOBS_PER_TICK = 25;

    /**
     * What the client is told when the failure is not one the report can explain.
     *
     * <p>{@code report_job.error} is served on the poll, so it must be a sentence about the report
     * and never an exception's {@code toString()}: a stack frame rendered in a UI is useless to the
     * reader and an unintended description of the server's internals. The real cause is logged with
     * the job id, which is where an operator looks.
     */
    private static final String UNEXPLAINED =
            "The report could not be generated. The server log has the details.";

    /**
     * What a job reaped out of {@code RUNNING} is told to say.
     *
     * <p>Written for the person watching, like every other value of {@code report_job.error}: it
     * says the report stopped rather than that a worker died, because the reader cannot act on the
     * second and can act on the first. "Generate it again" is the whole remedy and the sentence
     * names it, rather than leaving a reader at a dead end — the {@code ui-rules} §4 requirement,
     * arriving from the server side.
     */
    private static final String ABANDONED =
            "This report stopped before it finished — the server was interrupted while producing "
                    + "it. Nothing was saved; generate it again.";

    private final ReportJobRepository jobs;
    private final ReportArtifactRepository artifacts;
    private final ReportComputations computations;
    private final ReportHeaders headers;
    private final ReportRenderers renderers;
    private final ReportStore store;
    private final ExecutionFactRefreshService facts;
    private final ReportingProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ReportJobRunner(
            ReportJobRepository jobs,
            ReportArtifactRepository artifacts,
            ReportComputations computations,
            ReportHeaders headers,
            ReportRenderers renderers,
            ReportStore store,
            ExecutionFactRefreshService facts,
            ReportingProperties properties,
            TransactionTemplate transactions,
            Clock clock) {

        this.jobs = jobs;
        this.artifacts = artifacts;
        this.computations = computations;
        this.headers = headers;
        this.renderers = renderers;
        this.store = store;
        this.facts = facts;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Drains the open company's queue, up to {@link #MAX_JOBS_PER_TICK}.
     *
     * <p>One job at a time and single-threaded: v1 has no {@code SKIP LOCKED} and no second worker
     * to race, because the filesystem store pins the deployment to one replica. A second replica
     * needs {@link ReportJobRepository#findFirstByStatusOrderByRequestedAtAsc} to change, which is
     * where that work starts.
     *
     * <p>The tick also reaps: see {@link #failStuckJobs}, called first and counted apart from the
     * budget below.
     *
     * @return how many jobs the tick moved — completed, failed, or reaped. All three are progress;
     *     the count exists so a log line can say the tick did something, not so a caller can act
     *     on it.
     * @throws IllegalStateException via {@link CompanyContext#require()} if no company is open.
     *     Loud on purpose: the alternative is a worker that claims nothing for ever because RLS is
     *     answering zero rows, and "the reports stopped generating" is far harder to trace back
     *     here than a stack trace is.
     */
    int runPendingJobs() {
        CompanyContext.require();

        // Counted separately from the tick's budget: reaping is not generating, and a company that
        // lost twenty-five jobs to a restart must not spend its whole tick on rows nobody is
        // computing and then leave the queue behind them untouched.
        int reaped = failStuckJobs();

        int run = 0;
        while (run < MAX_JOBS_PER_TICK) {
            Optional<UUID> claimed = transactions.execute(status -> claim());
            if (claimed == null || claimed.isEmpty()) {
                break;
            }
            generate(claimed.get());
            run++;
        }
        return run + reaped;
    }

    /**
     * Fails every job in the open company whose claim is older than the configured timeout.
     *
     * <p>Run before the queue rather than after it, once per company per tick: a job the previous
     * process died on is older news than anything pending, and a client polling it has been waiting
     * longer than anybody in the queue.
     *
     * <p><strong>It cannot reach the job this process is running</strong>, and that is a property of
     * the ordering rather than of a lock: {@link #runPendingJobs} calls this and then claims, on one
     * thread, so nothing of this worker's is {@code RUNNING} when the sweep reads. A second replica
     * would break that — and would already have broken the claim, which has no {@code SKIP LOCKED}
     * (see {@link ReportJobRepository#findFirstByStatusOrderByRequestedAtAsc}). Both are the same
     * one-worker assumption and they go together.
     *
     * @return how many rows were reaped — zero on every healthy tick.
     */
    private int failStuckJobs() {
        Instant cutoff = clock.instant().minus(properties.stuckJobTimeout());
        List<UUID> stranded = transactions.execute(status ->
                jobs.findByStatusAndStartedAtBefore(ReportJobStatus.RUNNING, cutoff).stream()
                        .map(ReportJob::getId)
                        .toList());

        if (stranded == null || stranded.isEmpty()) {
            return 0;
        }
        // At WARN and one line per row, deliberately: this is a process that died, which is the
        // thing an operator wants to find in a log, and a count alone would not name the reports
        // whose requesters are about to be told they failed.
        for (UUID jobId : stranded) {
            log.warn("Report job {} was claimed before {} and is still RUNNING — failing it",
                    jobId, cutoff);
            fail(jobId, ABANDONED, null);
        }
        return stranded.size();
    }

    /** The oldest pending job in the open company, now {@code RUNNING}. Its own transaction. */
    private Optional<UUID> claim() {
        Optional<ReportJob> pending =
                jobs.findFirstByStatusOrderByRequestedAtAsc(ReportJobStatus.PENDING);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        ReportJob job = pending.get();
        job.claim(clock.instant());
        jobs.save(job);
        return Optional.of(job.getId());
    }

    /**
     * Everything after the claim, for one job. Never throws: a job that fails is a {@code FAILED}
     * row, and one company's bad report must not cost the twenty-four jobs behind it.
     */
    private void generate(UUID jobId) {
        try {
            Computed computed = transactions.execute(status -> compute(jobId));
            if (computed == null) {
                // Only reachable if the row vanished between the claim and here, which nothing in
                // the product can do — reports are never deleted. Recorded rather than assumed away.
                throw new IllegalStateException("Job " + jobId + " disappeared after being claimed");
            }
            Stored stored = renderAndStore(jobId, computed);
            transactions.executeWithoutResult(status -> complete(jobId, computed, stored));

        } catch (IllegalArgumentException refusal) {
            // The parameters do not describe something computable — a missing run id, or one that
            // names nothing in this project. These messages are written to be read by the person
            // who asked (see ReportParameters and ReportComputation), so they are the error.
            fail(jobId, refusal.getMessage(), refusal);

        } catch (RuntimeException failure) {
            fail(jobId, UNEXPLAINED, failure);
        }
    }

    /** What the byte-writing half needs, all of it read inside the transaction that computed it. */
    private record Computed(
            UUID companyId,
            UUID definitionId,
            ReportFormat format,
            String filterSnapshot,
            UUID requestedBy,
            Instant watermark,
            ReportDocument document) {
    }

    /** The keys the bytes went under, in the order the artifact rows are written. */
    private record Stored(String key, String modelKey) {
    }

    /**
     * Refresh, then compute. One transaction, and the order inside it is the same argument
     * {@link ExecutionFactRefreshService} makes about its own two statements: a report computed
     * before the projection is reading the previous tick's facts and is stale by construction —
     * invisible, because a stale number renders exactly like a fresh one.
     */
    private Computed compute(UUID jobId) {
        ReportJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return null;
        }

        facts.refresh();
        Instant watermark = clock.instant();

        ReportParameters parameters = ReportParameters.parse(job.getParameters());
        ReportModel model = computations.require(job.getType())
                .compute(job.getProjectId(), parameters);

        // The header is built AFTER the computation, and only because a computation that refuses its
        // parameters must fail the job rather than fail while assembling a header for a report that
        // was never going to exist — the error the client polls would then name the company lookup
        // instead of the run id they mistyped.
        ReportHeader header = headers.of(job, parameters, clock.instant(), watermark);

        return new Computed(
                job.getCompanyId(),
                job.getDefinitionId(),
                job.getFormat(),
                job.getParameters(),
                job.getRequestedBy(),
                watermark,
                new ReportDocument(header, model));
    }

    /**
     * Renders and writes the bytes — outside any transaction, deliberately.
     *
     * <p>Rendering is CPU and the store is a disk; doing either inside the computation's transaction
     * would hold a database connection for the length of both, on the one operation whose size the
     * product does not choose.
     *
     * <h2>Every job writes the JSON model as well as what was asked for</h2>
     *
     * <p>Slice-plan decision F: §6e's "the on-screen report view consumes the same model via JSON,
     * so what you see is what exports". The screen reads a stored artifact of the exact model the
     * export was rendered from, rather than a preview endpoint recomputing a second answer that can
     * disagree with it.
     *
     * <p>When the requested format <em>is</em> {@code JSON} there is one artifact and both columns
     * on the job point at it. Writing the same bytes twice under two keys would make a job's two
     * downloads two files that are equal only as long as nothing between them changes.
     */
    private Stored renderAndStore(UUID jobId, Computed computed) {
        ReportRenderer renderer = renderers.require(computed.format());
        String key = storageKey(computed.companyId(), jobId, renderer.fileExtension());
        store.put(key, renderer.render(computed.document()));

        if (computed.format() == ReportFormat.JSON) {
            return new Stored(key, key);
        }

        ReportRenderer json = renderers.require(ReportFormat.JSON);
        String modelKey = storageKey(computed.companyId(), jobId, json.fileExtension());
        store.put(modelKey, json.render(computed.document()));
        return new Stored(key, modelKey);
    }

    /**
     * The artifact rows and the job's terminal state, in one transaction.
     *
     * <p>{@link ReportJob#complete} takes both artifact ids and the status together for the reason
     * its own javadoc gives: a status written before the artifact reference is a job the client
     * believes is downloadable, holding a {@code downloadUrl} the contract declares and the server
     * cannot serve.
     */
    private void complete(UUID jobId, Computed computed, Stored stored) {
        ReportArtifact artifact = artifacts.save(ReportArtifact.of(
                computed.companyId(),
                computed.definitionId(),
                computed.format(),
                stored.key(),
                computed.filterSnapshot(),
                computed.watermark(),
                computed.requestedBy(),
                clock.instant()));

        ReportArtifact model = stored.key().equals(stored.modelKey())
                ? artifact
                : artifacts.save(ReportArtifact.of(
                        computed.companyId(),
                        computed.definitionId(),
                        ReportFormat.JSON,
                        stored.modelKey(),
                        computed.filterSnapshot(),
                        computed.watermark(),
                        computed.requestedBy(),
                        clock.instant()));

        jobs.findById(jobId).ifPresent(job -> {
            job.complete(artifact.getId(), model.getId(), clock.instant());
            jobs.save(job);
        });
    }

    /**
     * Records the failure, in a transaction of its own.
     *
     * <p>Separate from everything that failed, because whatever rolled back must not take the
     * explanation with it — a job that fails and stays {@code RUNNING} with an empty {@code error}
     * is the one state nobody can act on.
     *
     * @param cause null when there is nothing to attach — {@link #failStuckJobs} reaps a row whose
     *     exception, if there ever was one, died with the process. Branched rather than passed
     *     through, because a null trailing argument is bound as a <em>parameter</em> by SLF4J's
     *     arity rules and would silently print nothing where a stack trace is expected.
     */
    private void fail(UUID jobId, String reason, RuntimeException cause) {
        if (cause == null) {
            log.warn("Report job {} failed: {}", jobId, reason);
        } else {
            log.warn("Report job {} failed: {}", jobId, reason, cause);
        }
        try {
            transactions.executeWithoutResult(status -> jobs.findById(jobId).ifPresent(job -> {
                job.fail(reason == null || reason.isBlank() ? UNEXPLAINED : reason, clock.instant());
                jobs.save(job);
            }));
        } catch (RuntimeException unrecordable) {
            // The database is what failed. Nothing here can fix that, and rethrowing would abandon
            // every job behind this one in the same tick for a fault the next tick will meet again.
            log.error("Report job {} could not be marked failed", jobId, unrecordable);
        }
    }

    /**
     * Where a job's bytes live: {@code company/job/random.ext}.
     *
     * <p>Nothing a client sent appears in it — {@code EvidenceService}'s traversal defence, and the
     * reason {@code storage.FilesystemReportStore} re-checks the resolved path anyway. The company
     * leads so
     * a per-company sweep is a subtree rather than a scan, and the job id is what makes an orphan
     * blob attributable, which is the whole basis of the bytes-first ordering above.
     */
    private static String storageKey(UUID companyId, UUID jobId, String extension) {
        return companyId + "/" + jobId + "/" + UUID.randomUUID() + "." + extension;
    }
}
