package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.storage.ReportStore;
import com.tcms.storage.StoredBlob;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pipeline end to end, with no HTTP in the way: request writes a row, the worker turns it into
 * bytes somebody can download.
 *
 * <h2>What this class is guarding</h2>
 *
 * <ol>
 *   <li><strong>A {@code COMPLETED} job whose download 404s.</strong> The single failure the
 *       bytes-before-row ordering exists to prevent, and the one that looks fine in every other
 *       assertion: the status is right, the artifact ids are set, the row joins — and the file is
 *       not there. {@link Pipeline#aCompletedJobHasBytesForBothArtifacts()} opens the store.</li>
 *   <li><strong>A worker that sees nothing, successfully.</strong> {@code report_job} is under
 *       {@code FORCE ROW LEVEL SECURITY}, so a claim with the wrong company open — or none — reads
 *       zero rows and the tick logs success having done nothing. Asserted with <strong>two
 *       companies</strong> rather than trusting the comment, because the shape that always answers
 *       empty passes any single-company test that only checks its own job ran.</li>
 *   <li><strong>A refusal that arrives as a {@code FAILED} job instead of a 400.</strong> An
 *       unknown report type and an unrenderable format are known at request time; a run id that
 *       names nothing is not, and the two must land in different places.</li>
 *   <li><strong>A JSON request writing the same bytes twice.</strong> The one format where the
 *       artifact and the model are the same document.</li>
 * </ol>
 *
 * <p>Ids are {@code be…} — hex, which is not optional: {@code ExecutionFactRefreshTest}'s header
 * records the run a non-hex prefix cost, where the static initialiser threw and all eleven tests
 * failed before one of them ran. Slugs are {@code rj-…}.
 *
 * <p>The scheduler is not exercised here — {@code tcms.reporting.enabled=false} in
 * {@code IntegrationTestBase} keeps it from firing mid-suite, and what it adds over
 * {@link ReportJobRunner} is the company loop, which is what points 2's two companies are for.
 */
@DisplayName("The report pipeline — request, claim, render, download")
class ReportJobPipelineTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("be000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("be000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("be001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT =
            UUID.fromString("be001000-0000-4000-8000-000000000002");

    private static final UUID SUITE = UUID.fromString("be002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("be002000-0000-4000-8000-000000000009");

    private static final UUID RUN = UUID.fromString("be003000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_RUN = UUID.fromString("be003000-0000-4000-8000-000000000009");

    private static final UUID PASSED = UUID.fromString("be004000-0000-4000-8000-000000000001");
    private static final UUID FAILED_CASE = UUID.fromString("be004000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_CASE = UUID.fromString("be004000-0000-4000-8000-000000000009");

    /** Test Lead on the Acme project — holds {@code report:export} (roles spec §A4). */
    private static final UUID LEAD = UUID.fromString("be005000-0000-4000-8000-000000000001");
    /** A member of Acme with no grant on any project. */
    private static final UUID NO_GRANT = UUID.fromString("be005000-0000-4000-8000-000000000002");
    /** Test Lead on the Umbra project, so the sibling company's job has a legitimate requester. */
    private static final UUID UMBRA_LEAD = UUID.fromString("be005000-0000-4000-8000-000000000003");

    private static final UUID GRANT_LEAD = UUID.fromString("be006000-0000-4000-8000-000000000001");
    private static final UUID GRANT_UMBRA = UUID.fromString("be006000-0000-4000-8000-000000000002");

    private static final Instant BASE = Instant.parse("2026-03-09T09:00:00Z");

    private static final String RUN_SUMMARY = "run_summary";

    @Autowired
    private ReportJobService requests;

    @Autowired
    private ReportJobRunner runner;

    @Autowired
    private ReportJobRepository jobs;

    @Autowired
    private ReportReadService reads;

    @Autowired
    private ReportArtifactRepository artifacts;

    @Autowired
    private ReportStore store;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "rj-acme", "Acme", now);
        company(UMBRA, "rj-umbra", "Umbra", now);

        inCompany(ACME, () -> seedProject(ACME_PROJECT, ACME, "RJAC", "Acme Product", now));
        inCompany(UMBRA, () -> seedProject(UMBRA_PROJECT, UMBRA, "RJUM", "Umbra Product", now));

        clearEverything();

        person(LEAD, "lead@rj.test", "Lead Canonical");
        person(NO_GRANT, "nobody@rj.test", "Nobody Canonical");
        person(UMBRA_LEAD, "umbra@rj.test", "Umbra Canonical");

        try (Connection acme = appConnection(ACME)) {
            seedProjectDefaults(acme, ACME, ACME_PROJECT);
            membership(acme, ACME, LEAD, "Lead Tester");
            membership(acme, ACME, NO_GRANT, "No Grant");

            suite(acme, SUITE, ACME, ACME_PROJECT, "TS-1", 1, "billing", "Billing");
            testCase(acme, PASSED, ACME, ACME_PROJECT, SUITE, "TC-1", 1);
            testCase(acme, FAILED_CASE, ACME, ACME_PROJECT, SUITE, "TC-2", 2);

            run(acme, RUN, ACME, ACME_PROJECT, "TR-1", 1, "Release 5.0 regression");
            execution(acme, ACME, ACME_PROJECT, RUN, PASSED, "Passed", LEAD, 1_000L, BASE);
            execution(acme, ACME, ACME_PROJECT, RUN, FAILED_CASE, "Failed", LEAD, 500L,
                    BASE.plusSeconds(60));
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            membership(umbra, UMBRA, UMBRA_LEAD, "Umbra Tester");

            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "TS-1", 1, "billing", "Billing");
            testCase(umbra, UMBRA_CASE, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1);
            run(umbra, UMBRA_RUN, UMBRA, UMBRA_PROJECT, "TR-1", 1, "Umbra regression");
            execution(umbra, UMBRA, UMBRA_PROJECT, UMBRA_RUN, UMBRA_CASE, "Passed", UMBRA_LEAD,
                    900L, BASE);
        }

        inCompany(ACME, () -> tx.executeWithoutResult(status ->
                grantOnProject(GRANT_LEAD, ACME, LEAD, ACME_PROJECT, now)));
        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                grantOnProject(GRANT_UMBRA, UMBRA, UMBRA_LEAD, UMBRA_PROJECT, now)));
    }

    @Nested
    @DisplayName("Requesting")
    class Requesting {

        @Test
        @DisplayName("writes a PENDING row and hands back the id the client polls")
        void writesAPendingRow() {
            UUID jobId = accepted(request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD));

            ReportJob job = readIn(ACME, () -> jobs.findById(jobId).orElseThrow());
            assertThat(job.getStatus()).isEqualTo(ReportJobStatus.PENDING);
            assertThat(job.getProjectId()).isEqualTo(ACME_PROJECT);
            assertThat(job.getRequestedBy()).isEqualTo(LEAD);
            // Nothing has run yet, and the three columns that say so are the ones a poll reads.
            assertThat(job.getAttempts()).isZero();
            assertThat(job.getStartedAt()).isNull();
            assertThat(job.getArtifactId()).isNull();
        }

        @Test
        @DisplayName("refuses a project the caller cannot reach with a 404, never a 403")
        void refusesAnUnreachableProject() {
            // A 403 here would tell a caller with no grant that the project exists (§6t), and this
            // endpoint is the cheapest possible probe: it names a project and nothing else.
            assertThat(request(NO_GRANT, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD))
                    .isInstanceOf(ReportJobService.Outcome.NotFound.class);
            assertThat(readIn(ACME, () -> jobs.count())).isZero();
        }

        @Test
        @DisplayName("refuses a report type this server does not have, at request time")
        void refusesAnUnknownType() {
            ReportJobService.Outcome outcome =
                    request(LEAD, ACME_PROJECT, "quarterly_vanity_metrics", ReportFormat.MD);

            assertThat(outcome).isInstanceOf(ReportJobService.Outcome.Invalid.class);
            assertThat(((ReportJobService.Outcome.Invalid) outcome).errors())
                    .containsOnlyKeys("type");
            // The whole point of refusing here: no row was written, so nothing goes FAILED half a
            // minute later carrying a message the client has to poll for.
            assertThat(readIn(ACME, () -> jobs.count())).isZero();
        }

        @Test
        @DisplayName("refuses PDF at request time, and says it is not installed")
        void refusesPdf() {
            ReportJobService.Outcome outcome =
                    request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.PDF);

            assertThat(outcome).isInstanceOf(ReportJobService.Outcome.Invalid.class);
            List<String> messages =
                    ((ReportJobService.Outcome.Invalid) outcome).errors().get("format");
            assertThat(messages).isNotNull();
            // "Not supported" would read as a bad request; the truth is a missing renderer, which
            // is something an operator can change and a client cannot.
            assertThat(messages.getFirst()).contains("not installed");
            assertThat(readIn(ACME, () -> jobs.count())).isZero();
        }

        @Test
        @DisplayName("refuses parameters that are not a JSON object")
        void refusesMalformedParameters() {
            ReportJobService.Outcome outcome = requests(LEAD, ACME_PROJECT, RUN_SUMMARY,
                    ReportFormat.MD, "[\"not\", \"an\", \"object\"]");

            assertThat(outcome).isInstanceOf(ReportJobService.Outcome.Invalid.class);
            assertThat(((ReportJobService.Outcome.Invalid) outcome).errors())
                    .containsOnlyKeys("parameters");
        }
    }

    @Nested
    @DisplayName("Running")
    class Pipeline {

        @Test
        @DisplayName("a COMPLETED job has bytes for both artifacts")
        void aCompletedJobHasBytesForBothArtifacts() throws IOException {
            UUID jobId = accepted(request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD));

            assertThat(runIn(ACME)).isEqualTo(1);

            ReportJob job = readIn(ACME, () -> jobs.findById(jobId).orElseThrow());
            assertThat(job.getStatus()).isEqualTo(ReportJobStatus.COMPLETED);
            assertThat(job.getAttempts()).isEqualTo(1);
            assertThat(job.getStartedAt()).isNotNull();
            assertThat(job.getFinishedAt()).isNotNull();
            assertThat(job.getError()).isNull();

            // Two artifacts: what was asked for, and the model the on-screen view reads (decision
            // F). Asserting the pair is what stops "COMPLETED" from meaning "the export exists".
            ReportArtifact rendered = artifact(job.getArtifactId());
            ReportArtifact model = artifact(job.getModelArtifactId());
            assertThat(rendered.getId()).isNotEqualTo(model.getId());
            assertThat(rendered.getFormat()).isEqualTo(ReportFormat.MD);
            assertThat(model.getFormat()).isEqualTo(ReportFormat.JSON);

            // THE assertion of this class. Every id above is set and every join resolves in the
            // version of this pipeline that never wrote a byte.
            assertThat(read(rendered.getStorageKey())).contains("Release 5.0 regression");
            assertThat(read(model.getStorageKey())).contains("\"title\"");
        }

        @Test
        @DisplayName("sizeBytes is the stored artifact's size, and null until there is one (005 S1)")
        void sizeBytesIsTheStoredSize() throws IOException {
            UUID jobId = accepted(request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD));

            ReportJobView pending = polled(jobId);
            assertThat(pending.sizeBytes())
                    .as("nothing has been rendered yet, so there is nothing to size — and the "
                            + "contract declares null on the same terms as downloadUrl")
                    .isNull();

            assertThat(runIn(ACME)).isEqualTo(1);

            ReportJob job = readIn(ACME, () -> jobs.findById(jobId).orElseThrow());
            long onDisk = read(artifact(job.getArtifactId()).getStorageKey())
                    .getBytes(StandardCharsets.UTF_8).length;

            assertThat(polled(jobId).sizeBytes())
                    .as("read from the STORE and not from a column: a column would have needed a "
                            + "backfill no migration can perform under forced RLS, so every "
                            + "already-completed job would have served null for ever")
                    .isEqualTo(onDisk);
        }

        @Test
        @DisplayName("the artifact records what it was computed over, and how fresh the facts were")
        void theArtifactRecordsItsProvenance() {
            UUID jobId = accepted(requests(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD,
                    "{\"runId\":\"" + RUN + "\"}"));
            runIn(ACME);

            ReportArtifact rendered = artifact(
                    readIn(ACME, () -> jobs.findById(jobId).orElseThrow()).getArtifactId());

            // V1: "a report you cannot reproduce is a report nobody trusts" — the snapshot is the
            // request verbatim, not a re-serialisation of whatever the code happened to read.
            assertThat(rendered.getFilterSnapshot()).contains(RUN.toString());
            // Set from the refresh this job ran, so it is exact rather than "some time in the last
            // quarter hour". A null here means the worker skipped the refresh.
            assertThat(rendered.getDataWatermark()).isNotNull();
            assertThat(rendered.getGeneratedBy()).isEqualTo(LEAD);
        }

        @Test
        @DisplayName("the artifact carries the header §6e makes mandatory, filled from the job")
        void theArtifactCarriesItsHeader() throws IOException {
            UUID jobId = accepted(requests(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD,
                    "{\"runId\":\"" + RUN + "\"}"));
            runIn(ACME);

            String out = read(artifact(
                    readIn(ACME, () -> jobs.findById(jobId).orElseThrow()).getArtifactId())
                    .getStorageKey());

            // Every one of these is a database read ReportHeaders makes inside the worker's
            // transaction, and each would come back EMPTY rather than fail if it ran with no company
            // in context — V4's nullif makes an unset app.current_company zero rows, so the failure
            // mode this asserts against is a header of em dashes on a report whose body is correct.
            assertThat(out).contains("**Company:** Acme");
            assertThat(out).contains("**Project scope:** RJAC — Acme Product");
            // §6u: a person's name is their COMPANY MEMBERSHIP's, not the account's canonical one.
            // "Lead Canonical" here would mean the header resolved the wrong side of that rule.
            assertThat(out).contains("**Generated by:** Lead Tester");
            assertThat(out).doesNotContain("Lead Canonical");
            // Plan §390's sentence, and it is on the document rather than in a comment.
            assertThat(out).contains("in full and no other project");
            // The selection is what the CLIENT sent, read off the stored JSON — so a parameter the
            // report ignored still appears, which is the whole reason it is built from the node.
            assertThat(out).contains("**Selection — runId:** " + RUN);
        }

        @Test
        @DisplayName("the header says WHERE THE NUMBERS CAME FROM, and the two answers differ (E1)")
        void theHeaderStatesItsFreshness() throws IOException {
            // E1's finding: three surfaces in this product answer "pass rate" and they do not read
            // the same data — two go to the tables, one goes to execution_fact. Two documents that
            // print the same percentage and cannot be told apart is the defect, and it is only
            // visible when the two report types are rendered side by side, which is what this does.
            UUID live = accepted(requests(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD,
                    "{\"runId\":\"" + RUN + "\"}"));
            UUID projected = accepted(requests(
                    LEAD, ACME_PROJECT, "aggregate_execution", ReportFormat.MD, "{}"));
            runIn(ACME);
            runIn(ACME);

            String liveOut = read(artifact(
                    readIn(ACME, () -> jobs.findById(live).orElseThrow()).getArtifactId())
                    .getStorageKey());
            String projectedOut = read(artifact(
                    readIn(ACME, () -> jobs.findById(projected).orElseThrow()).getArtifactId())
                    .getStorageKey());

            assertThat(liveOut).contains("**Data freshness:** Live —");
            // "As of", and the instant is the refresh ReportJobRunner performs immediately before
            // computing — so a GENERATED report is exact, while the same table read outside a job
            // is up to a scheduler tick behind. Naming the refresh rather than the schedule is the
            // half a reader cannot work out for themselves.
            assertThat(projectedOut).contains("**Data freshness:** As of ");
            assertThat(projectedOut).contains("execution fact projection");
            assertThat(projectedOut).doesNotContain("Live —");
        }

        @Test
        @DisplayName("a JSON request writes ONE artifact, with both columns naming it")
        void aJsonRequestWritesOneArtifact() {
            UUID jobId = accepted(request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.JSON));
            runIn(ACME);

            ReportJob job = readIn(ACME, () -> jobs.findById(jobId).orElseThrow());
            assertThat(job.getStatus()).isEqualTo(ReportJobStatus.COMPLETED);
            // Two rows over identical bytes would be two files that are equal only until something
            // between the two renders changes.
            assertThat(job.getArtifactId()).isEqualTo(job.getModelArtifactId());
            assertThat(readIn(ACME, () -> artifacts.count())).isEqualTo(1);
        }

        @Test
        @DisplayName("a run id that names nothing FAILS the job with a sentence, and no artifact")
        void anUnknownRunFailsTheJob() {
            UUID jobId = accepted(requests(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD,
                    "{\"runId\":\"" + UUID.randomUUID() + "\"}"));

            assertThat(runIn(ACME)).isEqualTo(1);

            ReportJob job = readIn(ACME, () -> jobs.findById(jobId).orElseThrow());
            assertThat(job.getStatus()).isEqualTo(ReportJobStatus.FAILED);
            assertThat(job.getArtifactId()).isNull();
            // The computation's own sentence, not a stack frame: this string is served on the poll.
            assertThat(job.getError()).isEqualTo("This project has no run with that id");
            assertThat(job.getError()).doesNotContain("Exception");
            assertThat(readIn(ACME, () -> artifacts.count())).isZero();
        }

        @Test
        @DisplayName("a failed job is not retried by the next tick")
        void aFailedJobIsNotRetried() {
            accepted(requests(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD,
                    "{\"runId\":\"" + UUID.randomUUID() + "\"}"));
            runIn(ACME);

            // There is no retry policy on purpose — the user re-requests, which is a NEW row, so
            // the record of what was asked and what it produced stays one request per row.
            assertThat(runIn(ACME)).isZero();
        }
    }

    /**
     * The reaper — a job whose worker died after claiming it.
     *
     * <p>The failure it closes has no symptom anywhere: the row stays {@code RUNNING} for ever, the
     * client polling it watches a spinner with no end and no {@code error}, and the reports list
     * shows a job permanently about to finish. {@code ReportJobRunner}'s header used to argue
     * against a reaper, and every word of that argument was about <em>retrying</em> — which this
     * does not do.
     *
     * <p>The stuck state is written in SQL rather than produced by killing a worker, because the
     * thing under test is what the sweep does with a row in that state, and there is no way to
     * abandon a claim from inside a single-threaded test that does not also change the code being
     * tested.
     */
    @Nested
    @DisplayName("Reaping")
    class Reaping {

        @Test
        @DisplayName("a claim older than the timeout becomes FAILED, with a sentence for the client")
        void anAbandonedClaimIsFailed() throws SQLException {
            UUID jobId = accepted(request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD));
            abandon(jobId, Duration.ofHours(1));

            assertThat(runIn(ACME))
                    .as("the reaped row counts as work the tick did")
                    .isEqualTo(1);

            ReportJob job = readIn(ACME, () -> jobs.findById(jobId).orElseThrow());
            assertThat(job.getStatus()).isEqualTo(ReportJobStatus.FAILED);
            // A sentence about the report, written for the person watching — never a stack frame,
            // and never left null, which is the one state nobody can act on.
            assertThat(job.getError()).contains("stopped before it finished");
            assertThat(job.getError()).doesNotContain("Exception");
            // NOT back to PENDING. A re-request is a new row, so the record of what was asked for
            // and what it produced stays one request per row.
            assertThat(job.getArtifactId()).isNull();
            assertThat(runIn(ACME))
                    .as("a reaped job is not retried by the next tick either")
                    .isZero();
        }

        @Test
        @DisplayName("a claim younger than the timeout is left alone")
        void aRecentClaimSurvives() throws SQLException {
            UUID jobId = accepted(request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD));
            // One minute against a fifteen-minute timeout. Without this half the sweep could be
            // reaping every RUNNING row it sees — including the one a healthy worker is on — and
            // the test above would still be green.
            abandon(jobId, Duration.ofMinutes(1));

            assertThat(runIn(ACME)).isZero();
            assertThat(readIn(ACME, () -> jobs.findById(jobId).orElseThrow()).getStatus())
                    .isEqualTo(ReportJobStatus.RUNNING);
        }

        @Test
        @DisplayName("another company's stranded job is not this company's to reap")
        void theSweepIsBoundedByTheOpenCompany() throws SQLException {
            UUID umbraJob = accepted(
                    request(UMBRA_LEAD, UMBRA_PROJECT, RUN_SUMMARY, ReportFormat.MD));
            abandon(UMBRA, umbraJob, Duration.ofHours(1));

            // The sweep runs the same unqualified query the claim does, so it is bounded by RLS and
            // the Hibernate filter alone — and a sweep that ran across companies would be a worker
            // failing another tenant's reports.
            assertThat(runIn(ACME)).isZero();
            assertThat(readIn(UMBRA, () -> jobs.findById(umbraJob).orElseThrow()).getStatus())
                    .isEqualTo(ReportJobStatus.RUNNING);
        }

        private void abandon(UUID jobId, Duration age) throws SQLException {
            abandon(ACME, jobId, age);
        }

        /**
         * Puts a job in the state a dead worker leaves: RUNNING, claimed {@code age} ago.
         *
         * <p>The company is a parameter because the connection is RLS-bound — an update run with
         * the wrong company open touches zero rows and the assertion below is what says so, rather
         * than a test that quietly proves nothing.
         */
        private void abandon(UUID companyId, UUID jobId, Duration age) throws SQLException {
            try (Connection connection = appConnection(companyId);
                    PreparedStatement statement = connection.prepareStatement("""
                            UPDATE report_job
                               SET status = 'RUNNING', attempts = 1, started_at = ?
                             WHERE id = ?
                            """)) {
                statement.setObject(1, Instant.now().minus(age).atOffset(ZoneOffset.UTC));
                statement.setObject(2, jobId);
                assertThat(statement.executeUpdate())
                        .as("the fixture must have updated exactly the job it named")
                        .isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("Isolation")
    class Isolation {

        @Test
        @DisplayName("a job is invisible to the worker with any other company open")
        void aJobIsInvisibleToAnotherCompany() {
            UUID acmeJob = accepted(request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD));
            UUID umbraJob = accepted(
                    request(UMBRA_LEAD, UMBRA_PROJECT, RUN_SUMMARY, ReportFormat.MD));

            // Umbra's tick must run Umbra's job and only Umbra's. A worker whose query is not
            // bounded by the open context runs both here and passes any test that checks only that
            // its own job completed.
            assertThat(runIn(UMBRA)).isEqualTo(1);

            assertThat(readIn(UMBRA, () -> jobs.findById(umbraJob).orElseThrow()).getStatus())
                    .isEqualTo(ReportJobStatus.COMPLETED);
            assertThat(readIn(ACME, () -> jobs.findById(acmeJob).orElseThrow()).getStatus())
                    .isEqualTo(ReportJobStatus.PENDING);

            assertThat(runIn(ACME)).isEqualTo(1);
            assertThat(readIn(ACME, () -> jobs.findById(acmeJob).orElseThrow()).getStatus())
                    .isEqualTo(ReportJobStatus.COMPLETED);
        }

        @Test
        @DisplayName("a worker with no company open refuses rather than claiming nothing quietly")
        void aWorkerWithNoCompanyRefuses() {
            accepted(request(LEAD, ACME_PROJECT, RUN_SUMMARY, ReportFormat.MD));

            // The silent version of this bug is the whole reason the company loop exists: with the
            // GUC unset V4's nullif matches zero rows, so a global worker logs a successful tick
            // for ever while every client polls a job that never moves.
            assertThat(CompanyContext.current()).isEmpty();
            try {
                runner.runPendingJobs();
                throw new AssertionError("The worker claimed to run with no company open");
            } catch (IllegalStateException expected) {
                assertThat(expected).hasMessageContaining("company");
            }

            assertThat(readIn(ACME, () -> jobs.findAll()).getFirst().getStatus())
                    .isEqualTo(ReportJobStatus.PENDING);
        }
    }

    // ---------------------------------------------------------------- driving

    private ReportJobService.Outcome request(
            UUID userId, UUID projectId, String type, ReportFormat format) {
        return requests(userId, projectId, type, format, "{\"runId\":\"" + runFor(projectId) + "\"}");
    }

    private ReportJobService.Outcome requests(
            UUID userId, UUID projectId, String type, ReportFormat format, String parameters) {
        // The service takes the format as text since §87 — the parse is one of its three
        // request-time refusals, so an unrenderable format and an unspellable one are worded once.
        // These call sites keep the enum because they are all asking for a format that exists.
        // definitionId null: every request in this class is ad-hoc. Regenerating a saved one is
        // ReportEndpointTest.Regenerating's subject, because what §92 added is decided before the
        // pipeline this class drives ever sees the job.
        return readIn(companyOf(projectId),
                // idempotencyKey null: every request in this class is a distinct intent, and the
                // replay is ReportEndpointTest's subject (E2, V66) because it is decided at the
                // edge, before the pipeline this class drives ever sees a job.
                () -> requests.request(userId, projectId, null, type,
                        format == null ? null : format.name(), parameters, null));
    }

    private int runIn(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            return runner.runPendingJobs();
        }
    }

    /** The poll's own view — the read path the SPA uses, not a hand-built record. */
    private ReportJobView polled(UUID jobId) {
        ReportReadService.Outcome<ReportJobView> outcome =
                readIn(ACME, () -> reads.poll(LEAD, ACME_PROJECT, jobId));
        assertThat(outcome).isInstanceOf(ReportReadService.Outcome.Found.class);
        return ((ReportReadService.Outcome.Found<ReportJobView>) outcome).value();
    }

    private static UUID accepted(ReportJobService.Outcome outcome) {
        assertThat(outcome).isInstanceOf(ReportJobService.Outcome.Accepted.class);
        return ((ReportJobService.Outcome.Accepted) outcome).job().jobId();
    }

    private ReportArtifact artifact(UUID id) {
        assertThat(id).isNotNull();
        return readIn(ACME, () -> artifacts.findById(id).orElseThrow());
    }

    /** The bytes as the download endpoint would serve them, straight out of the store. */
    private String read(String key) throws IOException {
        Optional<StoredBlob> blob = store.open(key);
        assertThat(blob).as("bytes under %s", key).isPresent();
        try (StoredBlob open = blob.get(); InputStream stream = open.stream()) {
            byte[] bytes = stream.readAllBytes();
            assertThat(open.size()).isEqualTo(bytes.length);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static UUID companyOf(UUID projectId) {
        return projectId.equals(UMBRA_PROJECT) ? UMBRA : ACME;
    }

    private static UUID runFor(UUID projectId) {
        return projectId.equals(UMBRA_PROJECT) ? UMBRA_RUN : RUN;
    }

    // ---------------------------------------------------------------- fixture

    private void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    /**
     * Deliberately not a second {@code inCompany} overload, for {@code RunSummaryReportTest}'s
     * reason: an implicitly-typed lambda whose body is a method call is compatible with both
     * {@link Runnable} and {@link Supplier}, so the two would be ambiguous at every call site.
     */
    private <T> T readIn(UUID companyId, Supplier<T> work) {
        try (var ignored = CompanyContext.open(companyId)) {
            return tx.execute(status -> work.get());
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    private void grantOnProject(UUID id, UUID companyId, UUID userId, UUID projectId, Instant now) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, Role.TEST_LEAD_ID, userId, projectId, null, now));
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    /** {@code app_user} is instance-level (ERD §0.1's exception list), so this needs no context. */
    private void person(UUID id, String email, String canonicalName) throws SQLException {
        try (Connection connection = appConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO app_user (id, email, canonical_name, status)
                        VALUES (CAST(? AS uuid), ?, ?, 'ACTIVE')
                            ON CONFLICT (id) DO NOTHING
                        """)) {
            statement.setString(1, id.toString());
            statement.setString(2, email);
            statement.setString(3, canonicalName);
            statement.executeUpdate();
        }
    }

    private void membership(Connection connection, UUID companyId, UUID userId, String displayName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_membership
                    (company_id, user_id, display_name, status, accepted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, 'ACTIVE', now())
                    ON CONFLICT (company_id, user_id) DO UPDATE
                        SET display_name = EXCLUDED.display_name
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, userId.toString());
            statement.setString(3, displayName);
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String path, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED', NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 1,
                        CAST('{}' AS jsonb), ?, now())
                    ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, id.toString());
            statement.setString(3, "hash-" + id);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?,
                        'IN_PROGRESS', 'MANUAL',
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setString(7, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, String statusName, UUID executedBy, Long durationMs, Instant executedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_by, executed_at, duration_ms, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        (SELECT s.id FROM case_status s
                          WHERE s.project_id = CAST(? AS uuid) AND s.name = ?),
                        CAST(? AS uuid), CAST(? AS timestamptz), ?, now(), now())
                    ON CONFLICT (run_id, case_id) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, projectId.toString());
            statement.setString(7, statusName);
            statement.setString(8, executedBy == null ? null : executedBy.toString());
            statement.setString(9, executedAt == null ? null : executedAt.toString());
            if (durationMs == null) {
                statement.setNull(10, java.sql.Types.BIGINT);
            } else {
                statement.setLong(10, durationMs);
            }
            statement.executeUpdate();
        }
    }

    /**
     * Both companies, every table this class writes.
     *
     * <p>{@code report_job} and {@code report_artifact} lead, because a leftover job from a previous
     * method is claimed by the next one's tick and turns "ran 1" into "ran 2" — the one assertion
     * in this class that would then be wrong for a reason having nothing to do with the code.
     */
    private void clearEverything() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                for (String table : List.of("report_job", "report_artifact", "execution_fact",
                        "case_health", "step_result", "test_execution", "test_run",
                        "case_classification", "test_case_version", "test_case", "suite")) {
                    try (PreparedStatement statement =
                            connection.prepareStatement("DELETE FROM " + table)) {
                        statement.executeUpdate();
                    }
                }
            }
        }
    }
}
