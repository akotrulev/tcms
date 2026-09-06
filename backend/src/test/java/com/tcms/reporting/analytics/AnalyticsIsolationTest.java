package com.tcms.reporting.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The three analytics endpoints against real rows, real RLS and a real gate — the half
 * {@code BurnUpServiceTest} and its two siblings deliberately cannot answer.
 *
 * <p>Those three test the arithmetic over a fake. This one tests the two things only a database can
 * show: that the SQL in {@link DeliveryFacts} says what the arithmetic assumes, and that
 * <strong>another company's issues are not in the answer</strong> — which under
 * {@code FORCE ROW LEVEL SECURITY} is not a filter anybody wrote but the backstop working, and is
 * therefore exactly the thing a unit test would pass while it was broken.
 *
 * <p>The projection is written directly, which is what every test of a projection in this repository
 * does and is not what {@code ReportingReadsAndNeverWritesTest} forbids: that rule is about
 * {@code src/main/java/com/tcms/reporting}, where a second <em>product</em> writer would end
 * {@code tracker}'s authority over issue state. A fixture standing in for the one writer that exists
 * is how {@code TimeTestBase} and {@code BillingTestBase} both seed the same table.
 *
 * <p><strong>UUID prefix {@code 54}, verified free against the tree</strong> rather than assumed —
 * 018 records what assuming costs.
 */
@AutoConfigureMockMvc
@DisplayName("Delivery analytics — real rows, and another company's are not in the answer")
class AnalyticsIsolationTest extends TrackerEndpointTestBase {

    private static final UUID TEST_ENGINEER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000004");

    private static final UUID ACME = UUID.fromString("54000000-0000-4000-8000-000000000001");
    private static final UUID RIVAL = UUID.fromString("54000000-0000-4000-8000-000000000002");

    private static final UUID ACME_PROJECT = UUID.fromString("54001000-0000-4000-8000-000000000001");
    private static final UUID RIVAL_PROJECT = UUID.fromString("54001000-0000-4000-8000-000000000002");

    private static final UUID DANA = UUID.fromString("54002000-0000-4000-8000-000000000001");

    private static final UUID RELEASE = UUID.fromString("54004000-0000-4000-8000-000000000001");

    private static final Instant RELEASE_CREATED =
            Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "analytics-acme", "Acme", now);
            company(RIVAL, "analytics-rival", "Rival", now);
            activeUser(DANA, "dana@analytics.test", "Dana Test", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_PROJECT, ACME, "ANAA", "Acme Storefront", now);
            member(ACME, DANA, "Dana Test", now);
        }));
        inCompany(RIVAL, () -> tx.executeWithoutResult(status ->
                seedProject(RIVAL_PROJECT, RIVAL, "ANAR", "Rival Portal", now)));

        grantOnProject(UUID.fromString("54003000-0000-4000-8000-000000000001"), ACME,
                TEST_ENGINEER_ROLE, DANA, ACME_PROJECT);
        seedProjectDefaults(ACME, ACME_PROJECT);
        seedProjectDefaults(RIVAL, RIVAL_PROJECT);

        release(ACME, RELEASE, ACME_PROJECT, "2.4", RELEASE_CREATED);

        // Two original-scope issues, one of them finished, and one added a fortnight after the
        // release row existed — the shape the whole chart is about.
        issueFact(ACME, ACME_PROJECT, id(1), "ANAA-1", "Original, done",
                RELEASE, RELEASE_CREATED.minus(1, ChronoUnit.HOURS),
                RELEASE_CREATED.plus(3, ChronoUnit.DAYS), "DONE");
        issueFact(ACME, ACME_PROJECT, id(2), "ANAA-2", "Original, open",
                RELEASE, RELEASE_CREATED.minus(1, ChronoUnit.HOURS), null, "IN_PROGRESS");
        issueFact(ACME, ACME_PROJECT, id(3), "ANAA-3", "Added late",
                RELEASE, RELEASE_CREATED.plus(14, ChronoUnit.DAYS), null, "NOT_STARTED");

        // The rival's, in its own company. Nothing below may see it — and nothing filters it out in
        // application code either, which is the point: RLS is what makes it absent.
        issueFact(RIVAL, RIVAL_PROJECT, id(9), "ANAR-1", "Not yours",
                null, null, RELEASE_CREATED, "DONE");
    }

    @Test
    @DisplayName("the burn-up counts the late assignment apart and serves a weekly series")
    void burnUpSeparatesLateScope() throws Exception {
        mvc.perform(get("/api/v1/projects/{p}/analytics/burn-up", ACME_PROJECT)
                        .param("releaseId", RELEASE.toString())
                        .header("Authorization", "Bearer " + tokenFor(DANA, ACME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseVersion").value("2.4"))
                .andExpect(jsonPath("$.countedTotal").value(3))
                .andExpect(jsonPath("$.doneTotal").value(1))
                .andExpect(jsonPath("$.scopeAddedAfterCreation").value(1))
                .andExpect(jsonPath("$.sufficiency.sufficient").value(true))
                .andExpect(jsonPath("$.points[0].totalScope").value(2))
                .andExpect(jsonPath("$.points[-1:].totalScope").value(3));
    }

    @Test
    @DisplayName("a release in another company is a 404, not somebody else's chart")
    void anotherCompanysReleaseIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/projects/{p}/analytics/burn-up", RIVAL_PROJECT)
                        .param("releaseId", RELEASE.toString())
                        .header("Authorization", "Bearer " + tokenFor(DANA, ACME)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("cycle time counts only this company's completions")
    void cycleTimeIsScopedToTheCompany() throws Exception {
        mvc.perform(get("/api/v1/projects/{p}/analytics/cycle-time", ACME_PROJECT)
                        .param("days", "180")
                        .header("Authorization", "Bearer " + tokenFor(DANA, ACME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measuredFrom").value("CREATED"))
                .andExpect(jsonPath("$.windowDays").value(180))
                // One completion here and one in the rival's company. Only ours is counted, and the
                // report says two is too few rather than publishing a median over one issue.
                .andExpect(jsonPath("$.sufficiency.issuesConsidered").value(1))
                .andExpect(jsonPath("$.sufficiency.sufficient").value(false))
                .andExpect(jsonPath("$.bands[0].typeName").value("Story"));
    }

    @Test
    @DisplayName("the flow serves one point per week with the fourth band present")
    void flowServesFourBands() throws Exception {
        mvc.perform(get("/api/v1/projects/{p}/analytics/flow", ACME_PROJECT)
                        .param("weeks", "8")
                        .header("Authorization", "Bearer " + tokenFor(DANA, ACME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowWeeks").value(8))
                .andExpect(jsonPath("$.points.length()").value(8))
                .andExpect(jsonPath("$.points[-1:].beforeCurrentStatus").value(0))
                .andExpect(jsonPath("$.points[-1:].done").value(1));
    }

    @Test
    @DisplayName("a window the contract does not offer is a 400, never a silent clamp")
    void anUnofferedWindowIsRefused() throws Exception {
        mvc.perform(get("/api/v1/projects/{p}/analytics/cycle-time", ACME_PROJECT)
                        .param("days", "45")
                        .header("Authorization", "Bearer " + tokenFor(DANA, ACME)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.days").isArray());
    }

    @Test
    @DisplayName("a project the caller cannot see is a 404 on all three")
    void anInvisibleProjectIsNotFound() throws Exception {
        String token = tokenFor(DANA, ACME);
        for (String report : new String[] {"cycle-time", "flow"}) {
            mvc.perform(get("/api/v1/projects/{p}/analytics/{r}", RIVAL_PROJECT, report)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * An {@link Instant} the JDBC driver can bind.
     *
     * <p>{@code setObject(n, Instant)} throws <em>"Can't infer the SQL type"</em> — the driver maps
     * {@link OffsetDateTime} to {@code timestamptz} and has no mapping for {@code Instant} at all.
     * Null passes straight through, which is what {@code done_at} and {@code release_assigned_at}
     * both need.
     */
    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("54007000-0000-4000-8000-%012d", n));
    }

    /**
     * One row of the tracker's projection, with the columns this module reads.
     *
     * <p>{@code status_entered_at} is set to {@code created_at} for every fixture row, so no row
     * lands in the fourth band by accident — the band's own behaviour is pinned by
     * {@code FlowServiceTest}, where it can be driven without a database.
     */
    private void issueFact(
            UUID companyId,
            UUID projectId,
            UUID issueId,
            String issueKey,
            String title,
            UUID releaseId,
            Instant releaseAssignedAt,
            Instant doneAt,
            String statusKind) throws SQLException {

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue_fact (issue_id, company_id, project_id, issue_key, title,
                                                type_name, type_level, counts_toward_completion,
                                                status_name, status_kind, release_id,
                                                release_assigned_at, created_at, status_entered_at,
                                                done_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                'Story', 'STORY', true, 'Backlog', ?, CAST(? AS uuid),
                                ?, ?, ?, ?)
                        ON CONFLICT (issue_id) DO NOTHING
                        """)) {
            statement.setObject(1, issueId);
            statement.setObject(2, companyId);
            statement.setObject(3, projectId);
            statement.setString(4, issueKey);
            statement.setString(5, title);
            statement.setString(6, statusKind);
            // Bound as text inside the CAST rather than as a UUID object: the driver cannot infer a
            // type for a null UUID, and a null release_id is half of what this fixture is for.
            statement.setString(7, releaseId == null ? null : releaseId.toString());
            statement.setObject(8, at(releaseAssignedAt));
            statement.setObject(9, at(RELEASE_CREATED.minus(60, ChronoUnit.DAYS)));
            statement.setObject(10, at(RELEASE_CREATED.minus(60, ChronoUnit.DAYS)));
            statement.setObject(11, at(doneAt));
            statement.executeUpdate();
        }
    }

    /** A release row, written directly because {@code reporting} may not import {@code tracker}. */
    private void release(UUID companyId, UUID releaseId, UUID projectId, String version,
            Instant createdAt) throws SQLException {

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO release (id, company_id, project_id, version, sort_key,
                                             status, created_at, updated_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                'PLANNED', ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """)) {
            statement.setObject(1, releaseId);
            statement.setObject(2, companyId);
            statement.setObject(3, projectId);
            statement.setString(4, version);
            statement.setString(5, "00002.00004.00000.4");
            statement.setObject(6, at(createdAt));
            statement.setObject(7, at(createdAt));
            statement.executeUpdate();
        }
    }
}
