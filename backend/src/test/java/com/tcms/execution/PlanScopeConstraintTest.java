package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <strong>{@code ck_plan_scope_target}, asserted directly rather than trusted to review.</strong>
 *
 * <p>{@code V32}'s header names this class, and the reason it gets one of its own is that every
 * violation the constraint prevents is <em>a row that reads as ordinary data</em>. A scope line
 * naming both a suite and a tag, or naming nothing at all, does not throw anywhere downstream: it
 * expands to some cases or to none, and a plan covering fewer cases than its author believes is the
 * failure this product has paid for repeatedly (§32, §47, §96). Nothing else in the suite would go
 * red.
 *
 * <h2>Written against the database and not through the entity</h2>
 *
 * <p>{@link PlanScopeItem}'s factories check the same condition earlier, so a test going through
 * them would pass with the CHECK constraint <em>dropped entirely</em> — it would be asserting the
 * Java guard twice and the schema not at all. The whole value of the constraint is that it also
 * binds SQL nobody has written yet: a migration, a support fix, a future bulk import. So these
 * statements go over a plain JDBC connection as the application role.
 *
 * <h2>It is deliberately NOT a three-way exactly-one, which is the part worth pinning</h2>
 *
 * <p>The obvious widening of V1's two-way XOR is "exactly one of suite / case / tag". That would
 * refuse the row an <strong>empty section</strong> is stored as — a section has no table of its own,
 * so it exists only as a column on its member rows, and a section nobody has filled needs one row
 * that names it and targets nothing. The contract requires that state (an author names the
 * sub-feature before filling it). So the rule is <em>at most one target, and a targetless row must
 * name a section</em>, and both halves are asserted below in both directions.
 */
@DisplayName("plan_scope_item — the widened target constraint (V32)")
class PlanScopeConstraintTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("5d000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("5d001000-0000-4000-8000-000000000001");
    private static final UUID PLAN = UUID.fromString("5d002000-0000-4000-8000-000000000001");
    private static final UUID SUITE = UUID.fromString("5d003000-0000-4000-8000-000000000001");
    private static final UUID CASE = UUID.fromString("5d004000-0000-4000-8000-000000000001");

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        if (companies.findById(ACME).isEmpty()) {
            companies.save(Company.provision(ACME, "sc-acme", "Acme", now));
        }
        try (var ignored = CompanyContext.open(ACME)) {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, ACME, "SCAC", "Acme Product", null, now));
            }
        }
        try (Connection connection = appConnection(ACME)) {
            clear(connection);
            plan(connection);
            suite(connection);
            testCase(connection);
        }
    }

    @Test
    @DisplayName("each of the three arms is a legal row on its own")
    void oneTargetIsAccepted() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            assertThatCode(() -> insert(connection, SUITE, null, null, null))
                    .as("a suite line")
                    .doesNotThrowAnyException();
            assertThatCode(() -> insert(connection, null, CASE, null, null))
                    .as("a case line")
                    .doesNotThrowAnyException();
            assertThatCode(() -> insert(connection, null, null, "smoke", null))
                    .as("a tag line — the arm V32 added")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("two targets on one row are refused, in all three pairings")
    void twoTargetsAreRefused() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            // All three pairings, because the constraint is an arithmetic sum rather than three
            // written-out cases: a version that had accidentally omitted one term would still
            // refuse the other two pairings and pass a test that checked only those.
            assertThatThrownBy(() -> insert(connection, SUITE, CASE, null, null))
                    .as("suite + case")
                    .hasMessageContaining("ck_plan_scope_target");
            assertThatThrownBy(() -> insert(connection, SUITE, null, "smoke", null))
                    .as("suite + tag")
                    .hasMessageContaining("ck_plan_scope_target");
            assertThatThrownBy(() -> insert(connection, null, CASE, "smoke", null))
                    .as("case + tag")
                    .hasMessageContaining("ck_plan_scope_target");
            assertThatThrownBy(() -> insert(connection, SUITE, CASE, "smoke", null))
                    .as("all three")
                    .hasMessageContaining("ck_plan_scope_target");
        }
    }

    @Test
    @DisplayName("a row naming nothing and no section is refused")
    void aRowThatMeansNothingIsRefused() throws SQLException {
        // The reason the widening is not merely "at most one": a line with no target and no section
        // expands to no cases and renders on every screen as a plan somebody forgot to fill in.
        try (Connection connection = appConnection(ACME)) {
            assertThatThrownBy(() -> insert(connection, null, null, null, null))
                    .hasMessageContaining("ck_plan_scope_target");
        }
    }

    @Test
    @DisplayName("a targetless row IS legal when it names a section — the empty section")
    void anEmptySectionIsHeldOpenByATargetlessRow() throws SQLException {
        // The one row shape a three-way exactly-one would have refused. Asserted as its own test
        // rather than as a fourth line above, because it is the half of the rule that looks like a
        // loophole and is the reason the constraint is shaped the way it is: without it, naming a
        // sub-feature before filling it would delete the heading on save.
        try (Connection connection = appConnection(ACME)) {
            assertThatCode(() -> insert(connection, null, null, null, "Navigation"))
                    .doesNotThrowAnyException();

            assertThat(sectionNamesOf(connection))
                    .as("and it survives as a row, which is the only way an empty section reloads")
                    .containsExactly("Navigation");
        }
    }

    @Test
    @DisplayName("a section name does not license two targets")
    void aSectionDoesNotWeakenTheOtherHalf() throws SQLException {
        // The natural way to get the rule wrong is an OR that lets a named section satisfy the
        // whole constraint. It would admit every nonsense row above as long as it carried a
        // heading — which is exactly the shape the editor writes.
        try (Connection connection = appConnection(ACME)) {
            assertThatThrownBy(() -> insert(connection, SUITE, CASE, null, "Navigation"))
                    .hasMessageContaining("ck_plan_scope_target");
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private void insert(Connection connection, UUID suiteId, UUID caseId, String tag, String section)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plan_scope_item
                    (id, company_id, plan_id, suite_id, case_id, tag, section_name, ordinal)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid), ?, ?, 0)
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, PLAN.toString());
            statement.setString(3, suiteId == null ? null : suiteId.toString());
            statement.setString(4, caseId == null ? null : caseId.toString());
            statement.setString(5, tag);
            statement.setString(6, section);
            statement.executeUpdate();
        }
    }

    private List<String> sectionNamesOf(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT section_name FROM plan_scope_item "
                        + "WHERE section_name IS NOT NULL ORDER BY section_name")) {
            var results = statement.executeQuery();
            List<String> names = new java.util.ArrayList<>();
            while (results.next()) {
                names.add(results.getString(1));
            }
            return List.copyOf(names);
        }
    }

    private void clear(Connection connection) throws SQLException {
        for (String table : List.of("plan_scope_item", "test_plan", "test_case", "suite")) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
    }

    private void plan(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_plan
                    (id, company_id, project_id, key, key_seq, name, status, current_version_no,
                     created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TP-1', 1, 'Release 1',
                        'DRAFT', 1, now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, PLAN.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, path, name, position,
                                   lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TS-1', 1,
                        CAST('root' AS ltree), 'Root', 0, 'APPROVED')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, SUITE.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case (id, company_id, project_id, suite_id, key, key_seq, title)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        'TC-1', 1, 'A case')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, CASE.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, SUITE.toString());
            statement.executeUpdate();
        }
    }
}
