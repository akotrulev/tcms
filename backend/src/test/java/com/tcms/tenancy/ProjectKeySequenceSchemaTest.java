package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.ProjectCreateService;
import com.tcms.project.ProjectWriteRequest;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code project_key_sequence} — one row per project, on every path that makes one.
 *
 * <p>{@code tcms_seed_project_defaults} is called by {@code ProjectCreateService} and by
 * {@code CompanyProvisioningService} (twice — the company's first project and any extra). Seeding
 * the row there rather than in Java is what makes one statement cover all three, and this is the
 * rule that says so: a fourth caller added later gets the row for free and this class proves it,
 * where a Java-side insert would need the fourth caller's author to remember.
 *
 * <p>The third test is the computed check, in both directions, per the working agreement: reading
 * {@code V1} top to bottom missed five join tables without {@code company_id}, and scripting the
 * comparison found them.
 *
 * <p><strong>It is scoped to projects the seed function actually ran for, and that is the design
 * rather than a weakening.</strong> The obvious sweep — <em>every</em> row of {@code project} has a
 * counter — was written first and is wrong in this suite: every test class shares one container,
 * and a dozen classes build a project by saving the entity directly, without ever calling
 * {@code tcms_seed_project_defaults}. Those projects have no {@code case_status}, no Default
 * {@code configuration} and no {@code doc_space} either, and nothing is broken about them. So what
 * is pinned here is the invariant that is both true and at risk: <em>the function's structural
 * blocks travel together.</em> A project with a Default configuration and no counter means a later
 * {@code CREATE OR REPLACE} reproduced the body without {@code V2}'s block — the silent failure
 * {@code V18}'s reproduce-in-full rule exists for, which would otherwise be found by the first
 * issue nobody could number.
 *
 * <p><strong>Read as the superuser, not as {@code tcms_owner}.</strong> The table carries
 * {@code FORCE ROW LEVEL SECURITY} and {@code tcms_owner} holds no {@code BYPASSRLS}, so a count of
 * zero from that role means the policy is working rather than that the backfill failed. That
 * distinction is what makes a sweep across every company possible from one connection.
 */
@DisplayName("project_key_sequence — one row per project, on every creation path")
class ProjectKeySequenceSchemaTest extends IntegrationTestBase {

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private ProjectCreateService projectCreation;

    @Test
    @DisplayName("a freshly provisioned company's first project has its counter")
    void provisioningSeedsTheCounter() throws SQLException {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.withFirstProject(
                "pks-first", "Pks First Ltd", "ops@pks-first.test", "Ops Person",
                "PKS", "Pks Product"));

        UUID projectId = result.projectId().orElseThrow(
                () -> new AssertionError("provisioning was asked for a first project and made none"));

        assertThat(nextValueOf(result.companyId(), projectId))
                .as("""
                        The provisioned project has no project_key_sequence row, so the first issue \
                        in the company's first project cannot be numbered — and it would fail as an \
                        EMPTY RESULT rather than an error, which is exactly why the row is created \
                        by tcms_seed_project_defaults instead of on the allocation path.""")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /projects seeds the counter too — the other creation path")
    void projectCreationSeedsTheCounter() throws SQLException {
        ProvisionCompanyResult company = provisioning.provision(ProvisionCompanyRequest.of(
                "pks-second", "Pks Second Ltd", "ops@pks-second.test", "Ops Person"));

        // provision-company leaves its administrator INVITED on BOTH rows — deliberately, and its
        // own test says so: the account and the membership are each activated by redeeming the
        // invitation, which is a different path. An invited administrator holds the Company Admin
        // grant and answers no permission with it, so the create below is Forbidden until both are
        // active. Done in SQL rather than through the invitation flow because what this class is
        // about is the counter, not the redemption.
        activate(company.companyId(), company.adminUserId());

        UUID projectId;
        try (var ignored = CompanyContext.open(company.companyId())) {
            ProjectCreateService.Outcome outcome = projectCreation.create(
                    company.adminUserId(),
                    // No client: this test is about the key sequence, and 018 added the field.
                    new ProjectWriteRequest("PKS2", "Pks Second Product", null, null));
            assertThat(outcome)
                    .as("the fixture must actually create a project — a Forbidden or Invalid "
                            + "outcome would make the assertion below vacuously green")
                    .isInstanceOf(ProjectCreateService.Outcome.Created.class);
            projectId = ((ProjectCreateService.Outcome.Created) outcome).view().id();
        }

        assertThat(nextValueOf(company.companyId(), projectId))
                .as("""
                        A project created through ProjectCreateService has no counter. That path \
                        calls tcms_seed_project_defaults, so the block is gone from the FUNCTION \
                        rather than missing from the caller — which is the failure V18's \
                        reproduce-the-whole-body rule exists to prevent.""")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the seed function's blocks travel together — counter and configuration, both ways")
    void noSeededProjectIsWithoutOneAndNoCounterIsOrphaned() throws SQLException {
        // Its own seeded project, rather than whatever the classes before it happened to leave in
        // the shared container. A sweep whose population depends on test ORDER is a sweep that
        // reads as agreement on the day it runs first — the same vacuity every check in this
        // repository is written against, one level up from the assertion.
        provisioning.provision(ProvisionCompanyRequest.withFirstProject(
                "pks-sweep", "Pks Sweep Ltd", "ops@pks-sweep.test", "Ops Person",
                "PKSW", "Pks Sweep Product"));

        // Both directions, because each catches a different mistake: a seeded project with no
        // counter is a CREATE OR REPLACE that dropped the V2 block, and a counter with no project
        // is a cascade that did not fire. Neither shows up in the other's query.
        assertThat(superuserQuery("""
                SELECT p.company_id || ' / ' || p.id
                  FROM project p
                  JOIN configuration c
                    ON c.company_id = p.company_id AND c.project_id = p.id AND c.is_default
                  LEFT JOIN project_key_sequence s
                    ON s.company_id = p.company_id AND s.project_id = p.id
                 WHERE s.project_id IS NULL
                 ORDER BY 1
                """))
                .as("""
                        Projects that tcms_seed_project_defaults ran for — they carry its Default \
                        configuration — and that have no issue-key counter. One function writes \
                        both rows, so this set is empty unless a later CREATE OR REPLACE \
                        reproduced the body without V2's block. That failure is silent: every \
                        project created from then on simply cannot number an issue, because an \
                        UPDATE ... RETURNING matching nothing is an empty result and not an \
                        error.""")
                .isEmpty();

        assertThat(superuserQuery("""
                SELECT s.company_id || ' / ' || s.project_id
                  FROM project_key_sequence s
                  LEFT JOIN project p
                    ON p.company_id = s.company_id AND p.id = s.project_id
                 WHERE p.id IS NULL
                 ORDER BY 1
                """))
                .as("""
                        Counter rows whose project is gone. project_id REFERENCES project(id) ON \
                        DELETE CASCADE, like every other project-owned table in V1, so this set is \
                        empty by construction — and it is asserted because the constraint is the \
                        thing that could be dropped. Without the cascade the row does not orphan, \
                        it REFUSES the delete, which is how 27 tests found it.""")
                .isEmpty();

        // The vacuity half. Both queries above are empty when the tables are empty, and an empty
        // counter table is what a silently-skipped backfill looks like.
        assertThat(superuserQuery("""
                SELECT count(*)::text
                  FROM project p
                  JOIN configuration c
                    ON c.company_id = p.company_id AND c.project_id = p.id AND c.is_default
                """).getFirst())
                .as("the sweep compared something — at least one seeded project exists")
                .isNotEqualTo("0");
        assertThat(superuserQuery("SELECT count(*)::text FROM project_key_sequence").getFirst())
                .as("and the counter table is not empty, which is what a skipped backfill is")
                .isNotEqualTo("0");
    }

    @Test
    @DisplayName("the seed function still seeds every block — all seven, not just the newest")
    void theSeedFunctionStillSeedsEveryBlock() throws SQLException {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.withFirstProject(
                "pks-blocks", "Pks Blocks Ltd", "ops@pks-blocks.test", "Ops Person",
                "PKSB", "Pks Blocks Product"));
        UUID projectId = result.projectId().orElseThrow(
                () -> new AssertionError("provisioning was asked for a first project and made none"));

        // Each of these is a block of tcms_seed_project_defaults. V3 restated the whole function
        // under CREATE OR REPLACE to add the workflow, and a block dropped in that restatement is
        // invisible until somebody opens a screen that needs it — which is why this asserts all
        // seven rather than the one the newest migration added. THIS TEST IS THE WHOLE SAFETY NET
        // UNDER THE COST D-b ACCEPTED: seeding the workflow in this function rather than from a
        // ProjectCreatedEvent buys a synchronous, race-free seed on every creation path, and pays
        // for it by restating four blocks nobody was editing.
        assertThat(countRows("classification_dimension", projectId)).isEqualTo(1);
        assertThat(countRows("case_status", projectId)).isEqualTo(7);
        assertThat(countRows("configuration", projectId)).isEqualTo(1);
        assertThat(countRows("doc_space", projectId)).isEqualTo(1);
        assertThat(countRows("project_key_sequence", projectId)).isEqualTo(1);
        assertThat(countRows("issue_status", projectId)).isEqualTo(6);
        assertThat(countRows("issue_type", projectId)).isEqualTo(6);
    }

    /**
     * Rows one project holds in a table, read as the superuser.
     *
     * <p>The table name is interpolated because a table name cannot be a bind parameter, and the
     * only values it ever takes are the seven literals above.
     */
    private long countRows(String table, UUID projectId) throws SQLException {
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM " + table + " WHERE project_id = CAST(? AS uuid)")) {
            statement.setString(1, projectId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void activate(UUID companyId, UUID userId) throws SQLException {
        try (Connection connection = superuserConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE company_membership SET status = 'ACTIVE'
                     WHERE company_id = CAST(? AS uuid) AND user_id = CAST(? AS uuid)
                    """)) {
                statement.setString(1, companyId.toString());
                statement.setString(2, userId.toString());
                assertThat(statement.executeUpdate())
                        .as("provisioning must have made a membership row to activate")
                        .isEqualTo(1);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE app_user SET status = 'ACTIVE' WHERE id = CAST(? AS uuid)")) {
                statement.setString(1, userId.toString());
                assertThat(statement.executeUpdate())
                        .as("provisioning must have made an account to activate")
                        .isEqualTo(1);
            }
        }
    }

    private long nextValueOf(UUID companyId, UUID projectId) throws SQLException {
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT next_value FROM project_key_sequence
                         WHERE company_id = CAST(? AS uuid) AND project_id = CAST(? AS uuid)
                        """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    private static List<String> superuserQuery(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = superuserConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
