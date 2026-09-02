package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.identity.Role;
import com.tcms.tenancy.CompanyContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The row nobody is running any more.
 *
 * <h2>Why this failure needs a sweep rather than a {@code catch}</h2>
 *
 * <p>{@link ImportWriter#run} turns every exception it can see into a {@code FAILED} row with a
 * sentence. The one it cannot see is the process ending: the run is dispatched in memory and its
 * credential is written nowhere (D-a), so a restart mid-run leaves a {@code RUNNING} row nothing
 * will ever move. Nothing is half-written — the write is one transaction (D-b) — but the client
 * polling it watches a spinner with no end and no error, which is precisely the state
 * {@code ReportJobRunner.failStuckJobs} was added for.
 *
 * <p>The sweep does not retry, and could not: the credential is gone by design. A re-request is a
 * new row, so the state machine still only goes forwards.
 */
// The base class autowires MockMvc for the endpoint tests, so a class in this package needs the
// bean even when, as here, it drives the sweep directly rather than through HTTP.
@AutoConfigureMockMvc
@DisplayName("The import reaper — a run stranded by a restart becomes FAILED with a sentence")
class ImportReaperTest extends ImportTestBase {

    private static final UUID ACME = UUID.fromString("14600000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("14601000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("14602000-0000-4000-8000-000000000001");
    private static final UUID LEAD_GRANT = UUID.fromString("14603000-0000-4000-8000-000000000001");

    @Autowired
    private ImportReaper reaper;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "import-reaper-acme", "Acme", now);
            activeUser(LEAD, "lead@import-reaper.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "IMRE", "Import Reaper", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);

        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM import_dropped_field");
            statement.executeUpdate("DELETE FROM import_run");
        }
    }

    @Test
    @DisplayName("a RUNNING row older than the timeout becomes FAILED, saying nothing was written")
    void aStrandedRunIsReaped() throws Exception {
        UUID runId = strandedRun(Instant.now().minus(90, ChronoUnit.MINUTES));

        int reaped = inCompanyOf(ACME);

        assertThat(reaped).isEqualTo(1);
        assertThat(statusOf(runId)).isEqualTo("FAILED");
        assertThat(failureReasonOf(runId))
                .as("""
                    Written for the person watching: it says what happened to THEIR project rather \
                    than that a process died, because they cannot act on the second and can act on \
                    the first. "Nothing was written" is a sentence D-b earns — the whole write is \
                    one transaction, so an interrupted run left the project exactly as it was.""")
                .isEqualTo(ImportReaper.ABANDONED);
    }

    @Test
    @DisplayName("a run inside the timeout is left alone — this is not a retry policy")
    void aRunningRunIsLeftAlone() throws Exception {
        UUID runId = strandedRun(Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(inCompanyOf(ACME)).isZero();
        assertThat(statusOf(runId))
                .as("a run reading a five-thousand-issue Jira is slow, not stranded; reaping it "
                        + "would be this sweep deciding the import failed while it was working")
                .isEqualTo("RUNNING");
    }

    // =====================================================================

    /**
     * The sweep, for one company.
     *
     * <p>The scheduled entry point loops every company and opens a context per company, because
     * {@code import_run} is under {@code FORCE ROW LEVEL SECURITY} and a scheduled task starts with
     * none — a single instance-wide query would read zero rows and log a successful tick having
     * done nothing. This drives the per-company half directly so the assertion is about the reaping
     * rather than about which other company's fixtures the suite left behind.
     */
    private int inCompanyOf(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            return reaper.reapOpenCompany();
        }
    }

    /** A row exactly as {@code POST /imports} commits it, then abandoned. */
    private UUID strandedRun(Instant startedAt) throws SQLException {
        UUID runId = UUID.randomUUID();
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO import_run
                            (id, company_id, project_id, provider, site_url, source_project_key,
                             status, started_at, started_by)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'JIRA_CLOUD',
                                'https://northwind.atlassian.net', 'PROJ', 'RUNNING', ?,
                                CAST(? AS uuid))
                        """)) {
            statement.setObject(1, runId);
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setObject(4, java.sql.Timestamp.from(startedAt));
            statement.setObject(5, LEAD);
            statement.executeUpdate();
        }
        return runId;
    }

    private String statusOf(UUID runId) throws SQLException {
        return columnOf(runId, "status");
    }

    private String failureReasonOf(UUID runId) throws SQLException {
        return columnOf(runId, "failure_reason");
    }

    private String columnOf(UUID runId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + column + " FROM import_run WHERE id = CAST(? AS uuid)")) {
            statement.setObject(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no import_run row %s", runId).isTrue();
                return rows.getString(1);
            }
        }
    }
}
