package com.tcms.time;

import com.tcms.tracker.TrackerEndpointTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * The fixture plumbing the three {@code time} test classes share, and nothing else — no ids, no
 * company slugs, no project keys.
 *
 * <p>That division is {@code TrackerEndpointTestBase}'s, and it is kept for its reason: a slug
 * shared with another class is green alone and red in the suite, and the failure names neither
 * class. Every id stays in the subclass that owns it, so one prefix greps to one namespace.
 *
 * <h2>The projection is written directly, and that is the point of the fixture</h2>
 *
 * <p>{@code issue_fact} is fed by an outbox event with a delivery floor around a minute, so driving
 * an issue through {@code tracker} to produce a row would make every class here a test of that
 * pipeline. What this module reads is the projection, so the projection is what the fixture writes —
 * the choice {@code ReleaseReadinessTest} makes for {@code execution_fact} one module over.
 *
 * <p><strong>A test writing that table is not the thing {@code TimeWritesNoIssueFactTest} forbids.</strong>
 * The rule is about {@code src/main/java/com/tcms/time}: a second <em>product</em> writer ends the
 * tracker's authority over issue state. A fixture standing in for the one writer that exists is how
 * every projection in this repository is tested.
 */
public abstract class TimeTestBase extends TrackerEndpointTestBase {

    /** One row of the tracker's projection, with the six columns this module reads. */
    protected void issueFact(
            UUID companyId,
            UUID projectId,
            UUID issueId,
            String issueKey,
            String title,
            String typeLevel,
            UUID epicId,
            BigDecimal estimateHours) throws SQLException {

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue_fact (issue_id, company_id, project_id, issue_key, title,
                                                type_name, type_level, counts_toward_completion,
                                                status_name, status_kind, epic_id, estimate_hours,
                                                created_at, status_entered_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                ?, ?, true, 'Backlog', 'NOT_STARTED', CAST(? AS uuid), ?,
                                now(), now())
                        """)) {
            statement.setObject(1, issueId);
            statement.setObject(2, companyId);
            statement.setObject(3, projectId);
            statement.setString(4, issueKey);
            statement.setString(5, title);
            statement.setString(6, "EPIC".equals(typeLevel) ? "Epic" : "Story");
            statement.setString(7, typeLevel);
            statement.setObject(8, epicId);
            statement.setBigDecimal(9, estimateHours);
            statement.executeUpdate();
        }
    }

    /** In FK order: entries hang off periods, and the rollup and the projection stand alone. */
    protected void wipeTime(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            for (String table :
                    List.of("time_entry", "timesheet_period", "issue_time_rollup", "issue_fact")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE company_id = CAST(? AS uuid)")) {
                    statement.setObject(1, companyId);
                    statement.executeUpdate();
                }
            }
        }
    }
}
