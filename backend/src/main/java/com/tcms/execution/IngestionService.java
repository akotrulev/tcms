package com.tcms.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcms.project.CaseStatusView;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.project.StatusKind;
import com.tcms.storage.BlobUpload;
import com.tcms.storage.RawReportStore;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.CompanyContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A CI job posts a report — <strong>{@code ingestion_job}'s first writer</strong>, and the endpoint
 * §116's credential exists for.
 *
 * <h2>Two gates, each asked where it has always been asked</h2>
 *
 * <p>This endpoint does two things a person could do separately: it opens a run, and it records
 * results in it. So it costs two permissions and asks for neither in a new place —
 * {@code result:submit} here, and {@code run:create} inside
 * {@link RunCreateService#createIngested}, which is the same method the public endpoint reaches.
 * Both are in Automation Service's five (roles spec §A6).
 *
 * <p>The alternative — one gate on a new {@code results:ingest} key — was refused for §67's reason,
 * now the tenth time it has been made: a key {@code V3} seeds to nobody is a 403 nobody can grant
 * their way out of, and this operation is exactly the two capabilities the catalogue already names.
 * The cost is one extra permission evaluation per posted report, which is nothing against parsing
 * one.
 *
 * <h2>What is written, and what deliberately is not</h2>
 *
 * <p>One audit row for the <em>job</em>, not one per result (slice plan decision M).
 * {@link ExecutionService} writes {@code execution.recorded} per verdict because a person recorded
 * one verdict and the interesting fact is the verdict. Here a 2,000-test report would write 2,000
 * rows and bury the project's audit log in the one event whose interesting fact is the job. The
 * {@code ingestion_job} row is the per-result record: {@code raw_payload} holds every test, its
 * outcome, and which rung of the ladder placed it.
 *
 * <p><strong>Nothing is created for an unmatched test</strong> — see {@link AutomationMatcher}'s
 * header. They are counted, named in the response, and recorded on the job.
 *
 * <h2>A report that matches nothing creates NO run</h2>
 *
 * <p>The tempting shape is to create the run first and then fill it, which produces a run of zero
 * cases when the ladder places nothing — and a run of zero cases reports 0 of 0, 100%, complete.
 * That is §99's own defect in a second shape, and it is worse here because the report that most
 * often matches nothing is the <em>first</em> one a team posts, while they are deciding whether the
 * integration works. So the run is created only once there is something to put in it, and the job
 * says plainly that it matched none.
 *
 * <h2>Several tests may match one case, and the case gets the worst of them</h2>
 *
 * <p>A parameterised test posts three rows against one case; an execution holds one verdict. The
 * reduction is {@link IngestedOutcome#severity()} — the same ordering {@link CucumberReportParser}
 * uses over a scenario's steps, shared rather than copied, because the copy is where one of them
 * ends up ranking a skip above a failure. The job row still records all three, so the collapse is
 * visible rather than inferred from a count that does not add up.
 */
@Service
public class IngestionService {

    /** Roles spec §A4/§A6 — the same key {@link ExecutionService} asks for a first verdict. */
    private static final String SUBMIT_RESULT = "result:submit";

    private static final String AUDIT_ACTION = "ingestion.completed";
    private static final String AUDIT_TARGET = "ingestion_job";

    /**
     * How many unmatched names the response carries.
     *
     * <p>A first integration attempt matches nothing, so the unbounded list is the <em>common</em>
     * case at exactly the moment the response is largest — a 5,000-name array in the body of a CI
     * step's HTTP response, which most CI systems truncate in the log anyway.
     * {@code unmatchedCount} carries the true total, and the whole list is on the job row.
     */
    static final int UNMATCHED_NAMES_SERVED = 50;

    private final ProjectService projects;
    private final ReportParsers parsers;
    private final AutomationMatcher matcher;
    private final RunCreateService runCreates;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;
    private final ResultStatuses statuses;
    private final IngestionJobRepository jobs;
    private final AuditEventRepository auditEvents;
    private final ObjectMapper json;

    /** Reads {@code raw_payload} back on a replay — see the call site for what it repaired. */
    private final UnmatchedTests unmatchedTests;

    /**
     * <strong>A {@link TransactionTemplate}, not {@code @Transactional} on a second method of this
     * class</strong> — §116's red run, and it cost that slice eight tests and every valid
     * credential.
     *
     * <p>The parse must happen outside the transaction (see {@link #ingest}), which means the
     * transaction starts partway through a public method. Written as a private or protected
     * {@code @Transactional} method called from {@link #ingest}, Spring's proxy never sees the
     * call — a self-invocation is not intercepted — so the annotation does nothing at all and the
     * whole write runs outside any transaction. Under {@code CompanyAwareTransactionManager} that
     * is not merely a missing rollback: isolation is established in {@code doBegin}, so
     * {@code app.current_company} is never set and every RLS-protected read returns zero rows,
     * silently. {@code ReportJobRunner} made the same argument for its three transactions.
     */
    private final TransactionTemplate transactions;

    /** {@code ExecutionService}'s clock, for its reason — a timestamptz round-trips at micros. */
    /**
     * The transactional outbox (round 3 #52, phase 2). The <em>same</em> notice
     * {@code ExecutionService} records, deliberately: what moved is this company's results, and
     * nothing downstream cares whether a person typed them or a CI job posted them.
     *
     * <p>This is the path where the coalescing matters most. One ingestion can write a thousand
     * executions in one transaction, and an event per result would be a thousand rows standing for
     * one fact.
     */
    private final Outbox outbox;

    /**
     * The verbatim upload's home (§160). The <em>third</em> blob port and deliberately not
     * {@code ReportStore} — that one holds reports this product generated and can regenerate, and
     * this holds the one artifact in the product nothing can reproduce.
     */
    private final RawReportStore rawReports;

    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public IngestionService(
            ProjectService projects,
            ReportParsers parsers,
            AutomationMatcher matcher,
            RunCreateService runCreates,
            TestRunRepository runs,
            TestExecutionRepository executions,
            ResultStatuses statuses,
            IngestionJobRepository jobs,
            AuditEventRepository auditEvents,
            ObjectMapper json,
            UnmatchedTests unmatchedTests,
            TransactionTemplate transactions,
            Outbox outbox,
            RawReportStore rawReports,
            Clock clock) {

        this.projects = projects;
        this.parsers = parsers;
        this.matcher = matcher;
        this.runCreates = runCreates;
        this.runs = runs;
        this.executions = executions;
        this.statuses = statuses;
        this.jobs = jobs;
        this.auditEvents = auditEvents;
        this.json = json;
        this.unmatchedTests = unmatchedTests;
        this.transactions = transactions;
        this.outbox = outbox;
        this.rawReports = rawReports;
        this.clock = clock;
    }

    /** The outcomes, so the controller maps statuses and re-decides nothing. */
    public sealed interface Outcome {

        /** Unreachable project, or a {@code runId} that is not this project's live run. */
        record NotFound() implements Outcome {
        }

        record Forbidden() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /**
         * The run named by {@code runId} has ended, so its results are frozen —
         * {@link ExecutionService.Outcome.RunClosed}'s 409, for its reason. A CI job posting into a
         * closed run is usually a build re-run after somebody signed the run off, and the way
         * forward is reopening it.
         */
        record RunClosed() implements Outcome {
        }

        /** Ingested. 201. */
        record Ingested(IngestionJobView view) implements Outcome {
        }

        /**
         * A retry under a key already used. 200 with the original job — and, critically, no second
         * set of results. {@code RunCreateService.Outcome.Replayed}'s argument, one level up: a
         * replay that fell through would not merely duplicate rows, it would overwrite a run's
         * verdicts with a second reading of the same build.
         */
        record Replayed(IngestionJobView view) implements Outcome {
        }
    }

    /**
     * Parses, matches and records a posted report.
     *
     * <p><strong>Parsing happens before the gate and before the transaction, and both are
     * deliberate.</strong> Before the transaction, because parsing a megabyte of XML while holding a
     * database connection and a company GUC is the long-transaction rule in
     * {@code be-java-database-code}'s own terms. Before the gate is the awkward one — it is the
     * opposite of {@code ExecutionService}'s "validation runs inside the gate, never before it", and
     * the reason that rule exists does not apply: the refusal leaks nothing about the project. "That
     * XML is malformed" is a statement about the bytes the caller sent, not about this project's
     * vocabulary, and a caller who may not post results learns only what they already knew about
     * their own file.
     *
     * @param idempotencyKey the {@code Idempotency-Key} header, or null — required, see
     *                       {@link IngestionEdit}
     */
    public Outcome ingest(
            UUID userId, UUID projectId, IngestionRequest request, String idempotencyKey) {

        Map<String, List<String>> errors = IngestionEdit.validate(request, idempotencyKey);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        List<IngestedResult> parsed;
        try {
            parsed = parsers.parse(request.format(), request.report());
        } catch (ReportXml.MalformedReportException malformed) {
            // A field error on `report`, not a 500 and not a 415: the caller sent a file this
            // server could not read, which is a payload they can fix.
            return new Outcome.Invalid(Map.of("report", List.of(malformed.getMessage())));
        }

        List<IngestedResult> results = parsed;
        return transactions.execute(status -> {
            GateResult<Outcome> gated = projects.writeGranted(userId, projectId, SUBMIT_RESULT,
                    project -> apply(userId, project.getId(), request, idempotencyKey, results));

            return switch (gated) {
                case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
                case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
                case GateResult.Allowed<Outcome> allowed -> allowed.value();
            };
        });
    }

    private Outcome apply(
            UUID userId,
            UUID projectId,
            IngestionRequest request,
            String idempotencyKey,
            List<IngestedResult> parsed) {

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();

        // FIRST, and before anything is written — RunCreateService's rule, and the stakes are the
        // same one level up: a replay that fell through would post a second reading of one build
        // over a run's verdicts.
        Optional<IngestionJob> already = jobs.findByIdempotencyKey(idempotencyKey);
        if (already.isPresent()) {
            // The names are read back OUT of the stored payload rather than served as an empty
            // array. Until UnmatchedTests existed this answered `unmatchedCount: 12` beside
            // `unmatched: []`, and the retry is the caller that most needs the names: it is the
            // second time one build has failed to bind the same tests, and the first response is
            // long gone from the CI log by then.
            IngestionJob job = already.get();
            return new Outcome.Replayed(viewOf(job, unmatchedTests.of(job.getRawPayload())));
        }

        List<AutomationMatcher.Match> matches = matcher.match(projectId, parsed);

        // Resolved BEFORE the run is created, so a project missing a status kind refuses without
        // having burned a display key or written a checklist — §29's rule, which RunCreateService
        // applies to its own validation.
        Map<StatusKind, CaseStatusView> vocabulary = statusesByKind(projectId);
        Map<String, List<String>> missing = missingKinds(matches, vocabulary);
        if (!missing.isEmpty()) {
            return new Outcome.Invalid(missing);
        }

        // The worst outcome per case, and the message that came with it.
        Map<UUID, AutomationMatcher.Match> byCase = worstPerCase(matches);

        TestRun run;
        if (request.runId() != null) {
            Optional<TestRun> named = runs.findLiveInProject(projectId, request.runId());
            if (named.isEmpty()) {
                return new Outcome.NotFound();
            }
            run = named.get();
            if (run.isClosed()) {
                return new Outcome.RunClosed();
            }
        } else if (byCase.isEmpty()) {
            // See the class header — no run, rather than a run of zero cases reading as complete.
            return new Outcome.Ingested(
                    completed(companyId, projectId, userId, null, request, idempotencyKey,
                            matches, byCase, Set.of(), now));
        } else {
            OpenedRun opened = openRun(userId, projectId, request, idempotencyKey, byCase.keySet());
            if (opened.refusal() != null) {
                return opened.refusal();
            }
            // The id RunCreateService returned, not a second lookup by the derived key: the run is
            // in this transaction's persistence context either way, and re-reading it by a key
            // would make this method depend on runKey() being injective — which is a property of a
            // string prefix rather than of anything the database enforces.
            run = runs.findLiveInProject(projectId, opened.runId()).orElseThrow();
        }

        Set<UUID> recorded = write(run, byCase, vocabulary, now);

        return new Outcome.Ingested(
                completed(companyId, projectId, userId, run, request, idempotencyKey,
                        matches, byCase, recorded, now));
    }

    /**
     * Either the run that was opened or the refusal to answer with — never both, never neither.
     *
     * <p>A record rather than a nullable {@code Outcome} return: "null means it worked" reads
     * backwards at the call site, and the one place it is unpacked has to branch on something.
     */
    private record OpenedRun(UUID runId, Outcome refusal) {
    }

    /** Creates the run through {@link RunCreateService}, translating its refusals to this one's. */
    private OpenedRun openRun(
            UUID userId,
            UUID projectId,
            IngestionRequest request,
            String idempotencyKey,
            Set<UUID> caseIds) {

        RunWriteRequest write = new RunWriteRequest(
                // Named after the build when there is one. A CI job rarely sends a name and
                // "run" on every row is a list nobody can read; the build number is the thing a
                // person searching for "which run was build 4412" actually types.
                request.runName() != null && !request.runName().isBlank()
                        ? request.runName()
                        : request.buildNumber() != null && !request.buildNumber().isBlank()
                                ? "CI " + request.buildNumber()
                                : "Ingested results",
                null,
                request.configurationId(),
                request.releaseVersion(),
                request.buildNumber(),
                List.copyOf(caseIds),
                List.of(),
                List.of(),
                null,
                // §6g's approval gate is NOT overridden. A project that requires approved cases in
                // a run means it for a CI run too — arguably most of all, since that is the release
                // run — and overriding on a caller's behalf would make the gate silently inapplicable
                // to the one path nobody watches. The refusal reaches the CI log as a 409.
                null);

        RunCreateService.Outcome outcome =
                runCreates.createIngested(userId, projectId, write, runKey(idempotencyKey));

        return switch (outcome) {
            case RunCreateService.Outcome.Created created ->
                    new OpenedRun(created.view().id(), null);
            // Reachable only if the ingestion's own idempotency check somehow missed — the derived
            // run key is a function of it — and the honest answer to "that run already exists" is
            // that run, not an error.
            case RunCreateService.Outcome.Replayed replayed ->
                    new OpenedRun(replayed.view().id(), null);
            case RunCreateService.Outcome.NotFound ignored ->
                    new OpenedRun(null, new Outcome.NotFound());
            case RunCreateService.Outcome.Forbidden ignored ->
                    new OpenedRun(null, new Outcome.Forbidden());
            case RunCreateService.Outcome.Invalid invalid ->
                    new OpenedRun(null, new Outcome.Invalid(invalid.errors()));
            // §6g refused the run. Reported on `report` rather than on a field of this request,
            // because no field of this request is wrong: the project's policy refuses the cases the
            // report named.
            case RunCreateService.Outcome.Refused refused ->
                    new OpenedRun(null, new Outcome.Invalid(
                            Map.of("report", List.of(refused.message()))));
        };
    }

    /**
     * The run's own idempotency key, derived from the ingestion's.
     *
     * <p>Derived rather than passed through, and the prefix is what makes it safe: a CI job that
     * uses one key for both {@code POST /runs} and {@code POST /ingestions} would otherwise have its
     * ingestion silently replay a run it created by hand. Deterministic, so a retry of the ingestion
     * that failed <em>after</em> the run was written finds the run rather than making a second one.
     */
    private static String runKey(String idempotencyKey) {
        return "ingest:" + idempotencyKey;
    }

    /**
     * Records the verdicts on the run's existing executions.
     *
     * @return the case ids that actually got one — see the class header on a case whose execution is
     *         not in the run
     */
    private Set<UUID> write(
            TestRun run,
            Map<UUID, AutomationMatcher.Match> byCase,
            Map<StatusKind, CaseStatusView> vocabulary,
            Instant now) {

        Map<UUID, TestExecution> byCaseId = new HashMap<>();
        for (TestExecution execution : executions.findForRun(run.getProjectId(), run.getId())) {
            byCaseId.put(execution.getCaseId(), execution);
        }

        Set<UUID> recorded = new LinkedHashSet<>();
        List<TestExecution> written = new ArrayList<>();
        for (Map.Entry<UUID, AutomationMatcher.Match> entry : byCase.entrySet()) {
            TestExecution execution = byCaseId.get(entry.getKey());
            if (execution == null) {
                // The case matched, but this run's checklist does not contain it — only reachable
                // when the caller named an existing runId. Silently ADDING an execution would grow
                // a checklist a person is working through, and its denominator, from a CI job. So
                // the result is not recorded and the test is reported as unmatched, which is what
                // it is from this run's point of view.
                continue;
            }
            IngestedResult result = entry.getValue().result();
            execution.record(
                    vocabulary.get(result.outcome().kind()).id(),
                    // executedBy stays NULL. The verdict was not this person's — they hold the
                    // token, they did not run the test. Filling it with the token's owner would
                    // attribute a machine's claim to a person, which is the attribution
                    // TestExecution#record's own header refuses in the amendment case.
                    //
                    // executed_by_token is the column V1 added for exactly this, and since §132 it
                    // is filled — by `record` itself, from the ambient token, so this call passes
                    // nothing. An ingestion posted with a session rather than a credential (the
                    // operator CLI, a test harness) therefore records NEITHER actor, which is
                    // honest: nothing about that request identifies who ran the suite.
                    null,
                    result.message(),
                    now);
            written.add(execution);
            recorded.add(entry.getKey());
        }
        executions.saveAll(written);

        // The run starts here or it never starts — ExecutionService's rule (§72), and an ingested
        // run would otherwise read "not started" for ever while carrying a full set of verdicts.
        //
        // A conditional UPDATE since §155, and the null actor is the point of it here: nobody
        // performed this start, so there is no one to attribute it to, and the statement must not
        // resurrect a run a lead closed between this job's read and its write.
        if (!recorded.isEmpty()) {
            runs.startIfNotStarted(run.getProjectId(), run.getId(), null, now);
        }
        if (!recorded.isEmpty()) {
            // Guarded on `recorded`, not on the parse: a report whose every test was unmatched
            // changes no execution row, so there is nothing downstream to recompute and an event
            // would ask two modules to re-derive an unchanged world. §117's whole point is that an
            // unmatched test creates nothing.
            outbox.recordCoalesced(OutboxEvents.RESULTS_CHANGED);
        }
        return recorded;
    }

    /** Writes the job row and its audit event, and renders the response. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private IngestionJobView completed(
            UUID companyId,
            UUID projectId,
            UUID userId,
            TestRun run,
            IngestionRequest request,
            String idempotencyKey,
            List<AutomationMatcher.Match> matches,
            Map<UUID, AutomationMatcher.Match> byCase,
            Set<UUID> recorded,
            Instant now) {

        List<UnmatchedTestView> unmatched = unmatchedIn(matches, byCase, recorded);
        int matchedCount = matches.size() - unmatched.size();

        // The key is built before the row so the row can carry it, and its middle segment is the
        // JOB's id rather than the run's: a run can be posted into many times, and a key keyed by
        // run would put several builds' uploads in one directory with nothing but a random tail to
        // tell them apart. The job is the thing one upload belongs to.
        UUID jobId = UUID.randomUUID();
        String rawStorageKey = BlobUpload.key(companyId, jobId);

        IngestionJob job = IngestionJob.completed(
                jobId,
                companyId,
                projectId,
                run == null ? null : run.getId(),
                request.format(),
                idempotencyKey,
                payload(matches, recorded),
                rawStorageKey,
                matchedCount,
                unmatched.size(),
                now);
        jobs.saveAndFlush(job);

        // Row first, bytes second — EvidenceService's ordering and its reason (a row whose blob is
        // missing downloads as a 404 and is findable; a blob no row names is unreachable and
        // uncollectable). This is I/O inside the transaction, which the general rule is against:
        // the report is capped at 1 MB by IngestionEdit and the write is local, and the sibling
        // caller in this module does exactly this. RawReportStore's header carries the argument.
        rawReports.put(rawStorageKey, request.report().getBytes(StandardCharsets.UTF_8));

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(job.getId()),
                // The run's KEY rather than its id, as every audit row in this module does: a
                // reader reconstructing "what did build 4412 post" should not have to join to find
                // out which run this was.
                "{\"run\":" + (run == null ? "null" : AuditEvent.jsonString(run.getKey()))
                        + ",\"format\":" + AuditEvent.jsonString(request.format().name())
                        + ",\"matched\":" + matchedCount
                        + ",\"unmatched\":" + unmatched.size()
                        + "}",
                now));

        return viewOf(job, unmatched);
    }

    /**
     * Every test that produced no result, in report order.
     *
     * <p>Two populations, and they are one list on purpose: a test the ladder placed nowhere, and a
     * test whose case is not in the run the caller named. From the caller's side both are "this test
     * ran and no result was recorded", which is the fact they have to act on; splitting them into
     * two arrays would make a CI step's failure message branch on a distinction that changes nothing
     * about what to do next.
     */
    private static List<UnmatchedTestView> unmatchedIn(
            List<AutomationMatcher.Match> matches,
            Map<UUID, AutomationMatcher.Match> byCase,
            Set<UUID> recorded) {

        List<UnmatchedTestView> unmatched = new ArrayList<>();
        for (AutomationMatcher.Match match : matches) {
            boolean landed = match.matched()
                    && byCase.containsKey(match.caseId())
                    && recorded.contains(match.caseId());
            if (!landed) {
                IngestedResult result = match.result();
                unmatched.add(new UnmatchedTestView(
                        result.fullName(), result.normalizedName(), result.outcome()));
            }
        }
        return unmatched;
    }

    /**
     * One entry per matched case, holding the most severe of the tests that matched it.
     *
     * <p>A {@link LinkedHashMap} so the run's case list is in report order, which is the order the
     * caller can still see.
     */
    private static Map<UUID, AutomationMatcher.Match> worstPerCase(
            List<AutomationMatcher.Match> matches) {

        Map<UUID, AutomationMatcher.Match> byCase = new LinkedHashMap<>();
        for (AutomationMatcher.Match match : matches) {
            if (!match.matched()) {
                continue;
            }
            AutomationMatcher.Match held = byCase.get(match.caseId());
            if (held == null
                    || match.result().outcome().severity() > held.result().outcome().severity()) {
                byCase.put(match.caseId(), match);
            }
        }
        return byCase;
    }

    /**
     * The project's first status of each kind, in the project's own order.
     *
     * <p>First by position, then name — {@link ResultStatuses#byId} returns them in exactly that
     * order, so "first" is the project's own preference rather than an arbitrary pick. A project
     * with two passing statuses (§6f's "Passed with comment") gets its primary one, which is the
     * one it put first.
     */
    private Map<StatusKind, CaseStatusView> statusesByKind(UUID projectId) {
        Map<StatusKind, CaseStatusView> byKind = new EnumMap<>(StatusKind.class);
        for (CaseStatusView status : statuses.byId(projectId).values()) {
            byKind.putIfAbsent(status.kind(), status);
        }
        return byKind;
    }

    /**
     * A field error when the project has no status for a kind the report needs.
     *
     * <p>Only the kinds the report <em>produced</em>, so a project that deleted its Skipped status
     * ingests a clean build without complaint and is told the moment a test is skipped. Refused
     * rather than substituted: recording a skip as a failure would be a verdict nobody's runner
     * reported, and dropping it would make a report's totals silently short.
     *
     * <p>A {@link TreeSet} so the message names the kinds in a stable order — a CI log line that
     * differs between builds for no reason is one people stop reading.
     */
    private static Map<String, List<String>> missingKinds(
            List<AutomationMatcher.Match> matches, Map<StatusKind, CaseStatusView> vocabulary) {

        Set<String> absent = new TreeSet<>();
        for (AutomationMatcher.Match match : matches) {
            if (match.matched() && !vocabulary.containsKey(match.result().outcome().kind())) {
                absent.add(match.result().outcome().kind().name());
            }
        }
        if (absent.isEmpty()) {
            return Map.of();
        }
        return Map.of("report", List.of(
                "This project has no result status of kind " + String.join(", ", absent)
                        + ", so those results have nowhere to go. Add one in the project's result "
                        + "statuses."));
    }

    /**
     * {@code raw_payload} — the normalized parse and where each test landed.
     *
     * <p><strong>Not the verbatim file</strong> (slice plan decision N). That is in
     * {@code storage.RawReportStore} under {@code raw_storage_key} since §160 — <del>and there is no
     * adapter (§67's port argument)</del>, an argument that expired in §125 and was restated as a
     * standing decision twice afterwards. What makes <em>this</em> column useful is what was
     * understood and what it matched to, which is what a person binding unmatched names reads and
     * the only record of why a test that clearly ran produced no result. The two columns answer
     * different questions and neither replaces the other.
     */
    private String payload(List<AutomationMatcher.Match> matches, Set<UUID> recorded) {
        ArrayNode results = json.createArrayNode();
        for (AutomationMatcher.Match match : matches) {
            IngestedResult result = match.result();
            ObjectNode node = results.addObject();
            node.put("name", result.fullName());
            node.put("outcome", result.outcome().name());
            node.put("match", match.kind().name());
            if (match.caseId() != null) {
                node.put("caseId", match.caseId().toString());
                // Whether it actually landed, which is NOT the same as whether it matched — see
                // #write on a matched case that is not in the named run. A reader looking at a job
                // whose matched count is lower than its match rungs suggest needs this to be a
                // field rather than a subtraction they perform themselves.
                node.put("recorded", recorded.contains(match.caseId()));
            }
            if (result.durationMs() != null) {
                node.put("durationMs", result.durationMs());
            }
        }
        ObjectNode root = json.createObjectNode();
        root.set("results", results);
        return root.toString();
    }

    private IngestionJobView viewOf(IngestionJob job, List<UnmatchedTestView> unmatched) {
        String runKey = job.getRunId() == null
                ? null
                : runs.findLiveInProject(job.getProjectId(), job.getRunId())
                        .map(TestRun::getKey)
                        .orElse(null);

        return new IngestionJobView(
                job.getId(),
                job.getStatus(),
                job.getFormat(),
                job.getRunId(),
                runKey,
                job.getMatchedCount(),
                job.getUnmatchedCount(),
                cap(unmatched),
                IngestionJobView.reportUrl(
                        job.getProjectId(), job.getId(), job.getRawStorageKey()),
                job.getCreatedAt());
    }

    /**
     * The first {@link #UNMATCHED_NAMES_SERVED} names.
     *
     * <p>Shared with {@link IngestionReadService} rather than reapplied there, because a detail read
     * that could ask for all 5,000 by following this endpoint's own {@code Location} header would
     * make the cap here cosmetic — and a second copy of a bound is how one of the two drifts.
     */
    static List<UnmatchedTestView> cap(List<UnmatchedTestView> unmatched) {
        return unmatched.size() > UNMATCHED_NAMES_SERVED
                ? List.copyOf(unmatched.subList(0, UNMATCHED_NAMES_SERVED))
                : List.copyOf(unmatched);
    }
}
