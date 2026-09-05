package com.tcms.billing;

import com.tcms.tracker.TrackerEndpointTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The fixture plumbing this module's endpoint tests share, and nothing else — no ids, no company
 * slugs, no project keys.
 *
 * <p>That division is {@code TrackerEndpointTestBase}'s and it is kept for its reason: a slug or an
 * id shared with another class is green alone and red in the suite, and the failure names neither
 * class. 018 records the cost of getting it wrong — {@code fa} and {@code fb} looked free, were not,
 * and the suite failed on a {@code project_pkey} duplicate naming neither class.
 *
 * <h2>Time is written directly, and that is the point of the fixture</h2>
 *
 * <p>An approved fortnight is four screens and two state transitions away in {@code time}, so
 * driving one through that module would make every class here a test of the timesheet lifecycle.
 * What this module reads is the resulting rows, so the rows are what the fixture writes — the choice
 * {@code TimeTestBase} makes for {@code issue_fact} one module over, for the same reason.
 *
 * <p><strong>A test writing those tables is not what {@code BillingReadsAndNeverWritesTest}
 * forbids.</strong> That rule is about {@code src/main/java/com/tcms/billing}: a second
 * <em>product</em> writer would make two modules the authority on the same hours. A fixture standing
 * in for the module that owns them is how every crossing in this repository is tested.
 */
public abstract class BillingTestBase extends TrackerEndpointTestBase {

    /** A client row, written directly because {@code billing} may not import {@code crm}. */
    protected void client(UUID companyId, UUID clientId, String name) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO client (id, company_id, name, status)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, 'ACTIVE')
                        ON CONFLICT (id) DO NOTHING
                        """)) {
            statement.setObject(1, clientId);
            statement.setObject(2, companyId);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    /** Attaches a project to a client, or detaches it when {@code clientId} is null. */
    protected void attach(UUID companyId, UUID projectId, UUID clientId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE project SET client_id = CAST(? AS uuid) WHERE id = CAST(? AS uuid)")) {
            statement.setObject(1, clientId);
            statement.setObject(2, projectId);
            statement.executeUpdate();
        }
    }

    /**
     * One person's fortnight, in whatever state the test needs.
     *
     * <p>{@code ck_timesheet_period_note} refuses a {@code REJECTED} period with no note, and
     * {@code ck_timesheet_period_state} refuses a state {@code time} does not have — both are the
     * database refusing a fixture that has drifted from the module it stands in for, which is the
     * property that makes writing these rows directly safe.
     */
    protected void period(
            UUID companyId,
            UUID periodId,
            UUID personId,
            LocalDate startsOn,
            LocalDate endsOn,
            String state,
            Instant decidedAt,
            UUID decidedBy) throws SQLException {

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO timesheet_period (id, company_id, person_id, starts_on, ends_on,
                                                      state, submitted_at, decided_at, decided_by)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                ?, ?, ?, CAST(? AS uuid))
                        """)) {
            statement.setObject(1, periodId);
            statement.setObject(2, companyId);
            statement.setObject(3, personId);
            statement.setObject(4, startsOn);
            statement.setObject(5, endsOn);
            statement.setString(6, state);
            /*
              An Instant is not a type the JDBC driver can infer a SQL type for — it answers "Can't
              infer the SQL type to use for an instance of java.time.Instant", which reads as a
              fixture bug and is a binding one. OffsetDateTime is what maps to TIMESTAMPTZ.
            */
            OffsetDateTime decided =
                    decidedAt == null ? null : decidedAt.atOffset(ZoneOffset.UTC);
            statement.setObject(7, decided);
            statement.setObject(8, decided);
            statement.setObject(9, decidedBy);
            statement.executeUpdate();
        }
    }

    /**
     * One {@code ISSUE}-subject hour.
     *
     * <p>{@code ck_time_entry_subject_shape} requires both a subject and a project for this kind,
     * and forbids both for the other four — which is exactly why four of the five subject kinds can
     * never reach a rate card (019 D-e). {@link #nonIssueEntry} is the other half of that shape and
     * exists so a test can prove the exclusion rather than assume it.
     */
    protected void issueEntry(
            UUID companyId,
            UUID entryId,
            UUID periodId,
            UUID personId,
            UUID projectId,
            UUID issueId,
            LocalDate spentOn,
            BigDecimal hours) throws SQLException {

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO time_entry (id, company_id, period_id, person_id, subject_type,
                                                subject_id, project_id, spent_on, hours)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                                'ISSUE', CAST(? AS uuid), CAST(? AS uuid), ?, ?)
                        """)) {
            statement.setObject(1, entryId);
            statement.setObject(2, companyId);
            statement.setObject(3, periodId);
            statement.setObject(4, personId);
            statement.setObject(5, issueId);
            statement.setObject(6, projectId);
            statement.setObject(7, spentOn);
            statement.setBigDecimal(8, hours);
            statement.executeUpdate();
        }
    }

    /** A meeting, a leave day, training or internal work — no subject, and therefore no project. */
    protected void nonIssueEntry(
            UUID companyId,
            UUID entryId,
            UUID periodId,
            UUID personId,
            String subjectType,
            LocalDate spentOn,
            BigDecimal hours) throws SQLException {

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO time_entry (id, company_id, period_id, person_id, subject_type,
                                                spent_on, hours)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                                ?, ?, ?)
                        """)) {
            statement.setObject(1, entryId);
            statement.setObject(2, companyId);
            statement.setObject(3, periodId);
            statement.setObject(4, personId);
            statement.setString(5, subjectType);
            statement.setObject(6, spentOn);
            statement.setBigDecimal(7, hours);
            statement.executeUpdate();
        }
    }

    /**
     * Everything a billing class writes, in foreign-key order, plus the time rows behind it.
     *
     * <p>Per test method rather than per class. Every constraint this module leans on is a
     * uniqueness one — {@code uq_rate_card_client}, {@code uq_budget_project} — so a class that let
     * rows accumulate would fail its second test on a conflict rather than on anything it is about,
     * and the failure would name the constraint instead of the fixture.
     */
    protected void wipeBilling(UUID companyId) throws SQLException {
        try (Connection connection = superuserConnection()) {
            for (String table : List.of(
                    "invoice_line_entry", "invoice_line", "invoice", "expense", "budget",
                    "rate_card_entry", "rate_card", "time_entry", "timesheet_period")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE company_id = CAST(? AS uuid)")) {
                    statement.setObject(1, companyId);
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE project SET client_id = NULL WHERE company_id = CAST(? AS uuid)")) {
                statement.setObject(1, companyId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM client WHERE company_id = CAST(? AS uuid)")) {
                statement.setObject(1, companyId);
                statement.executeUpdate();
            }
        }
    }
}
