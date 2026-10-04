package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deployment's privilege split, asserted in the suite — two roles, and the
 * difference between them is what the entire RLS design rests on.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Every property below was proved <strong>by hand, once, against
 * {@code docker compose up}</strong> (handoff §17) and by nothing thereafter, while
 * {@code IntegrationTestBase} ran an arrangement production does not have: Flyway
 * migrated as the container's <strong>superuser</strong>, and a superuser bypasses RLS
 * unconditionally.
 *
 * <p>That gap was not theoretical. It hid a HIGH defect through 171 green tests — V7's
 * {@code SECURITY DEFINER} company lookup, which returned <em>zero rows in production</em>
 * because {@code FORCE ROW LEVEL SECURITY} covers the table owner, so the first
 * administrator of a new instance signed in holding no company and no permissions, with
 * no error anywhere. Under test the definer's rights really did see past the policy,
 * because the owner was a superuser. See the V8 migration header.
 *
 * <p>So the harness now mirrors {@code deploy/postgres/initdb/10-tcms-roles.sql}: a
 * non-superuser {@code tcms_owner} migrates, unprivileged {@code tcms_app} connects, and
 * the container's superuser is used only to create those roles and to answer
 * ground-truth questions. This class is what stops that arrangement quietly reverting.
 */
@DisplayName("The database privilege split — a non-superuser owner (deploy/postgres/initdb)")
class PrivilegeSplitTest extends IntegrationTestBase {

    /**
     * This class's own probe company. Deliberately an id no other class uses — it was
     * briefly {@code 3333…}, which {@code PermissionEvaluatorTest} also seeds projects
     * into, and the count assertions below then depended on test ordering.
     */
    private static final UUID COMPANY = UUID.fromString("5b1c0a11-0000-4000-8000-000000000001");

    private static final UUID PROJECT = UUID.fromString("5b1c0a11-0000-4000-8000-0000000000aa");

    /** Never created. It only ever has to be a company id that is not {@link #COMPANY}. */
    private static final UUID OTHER_COMPANY = UUID.fromString("5b1c0a11-0000-4000-8000-0000000000ff");

    /**
     * {@code @BeforeEach} rather than {@code @BeforeAll}: Spring loads the application
     * context — and therefore runs Flyway — per test instance, so a static setup method
     * finds no schema at all ("relation \"company\" does not exist").
     */
    @BeforeEach
    void seedOneCompanyOwnedRow() throws SQLException {
        // Seeded as the superuser: this fixture exists to prove that the OWNER cannot read
        // it, so the thing that writes it must not be the owner.
        //
        // Bound from the constants rather than written as literals. They were literals
        // once, the constant was later changed to avoid a collision, and the seed then
        // wrote one company while every assertion counted another — a precondition failure
        // that reads exactly like a broken privilege split.
        try (Connection connection = superuserConnection()) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO company (id, slug, display_name)
                    VALUES (?, 'split-probe', 'Split Probe')
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setObject(1, COMPANY);
                statement.execute();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO project (id, company_id, key, name, color)
                    VALUES (?, ?, 'SPLIT', 'Split Probe Project', 'project-1')
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setObject(1, PROJECT);
                statement.setObject(2, COMPANY);
                statement.execute();
            }
        }
    }

    @Test
    @DisplayName("the migration owner is neither superuser nor BYPASSRLS")
    void migrationOwnerCannotBypassRls() throws SQLException {
        // The property the whole class turns on. A superuser owner makes every policy in
        // the schema advisory under test while remaining mandatory in production — which
        // is the exact shape of the V8 defect.
        assertRoleIsUnprivileged(OWNER_ROLE);
    }

    @Test
    @DisplayName("the application role is neither superuser nor BYPASSRLS")
    void appRoleCannotBypassRls() throws SQLException {
        assertRoleIsUnprivileged(APP_ROLE);
    }

    @Test
    @DisplayName("FORCE ROW LEVEL SECURITY covers the OWNER — no context, no rows, even for the owner")
    void forceRowLevelSecurityCoversTheTableOwner() throws SQLException {
        // V7's reasoning was "run as the owner and you see past the policy". This is the
        // assertion that refutes it, and it is the one the old harness could not make:
        // as a superuser the count below was every project in the instance.
        long groundTruth = countProjectsAs(() -> superuserConnection());
        assertThat(groundTruth)
                .as("fixture precondition: there must be rows, or 'the owner sees none' is vacuous")
                .isPositive();

        assertThat(countProjectsAs(() -> migrationOwnerConnection()))
                .as("the owner of the table must be subject to its own policy")
                .isZero();
    }

    @Test
    @DisplayName("the owner is policed, not blocked — with a company set it sees exactly that company")
    void theOwnerIsFilteredRatherThanDenied() throws SQLException {
        // Stated separately because "the owner sees zero rows" would also pass if the
        // owner had simply lost SELECT on the table — a permission error and a policy
        // filter are different failures with the same count.
        //
        // Compared against ground truth rather than against the literal 1: a hard-coded
        // count couples this to the fixture never growing, and asserting "the owner sees
        // everything the superuser sees, for this company" is the actual claim.
        long groundTruth = countProjectsAs(() -> superuserConnection());
        assertThat(countProjectsAs(() -> ownerConnectionWithCompany(COMPANY)))
                .as("with its company set, a policed owner sees that company in full")
                .isEqualTo(groundTruth);

        assertThat(countProjectsAs(() -> ownerConnectionWithCompany(OTHER_COMPANY)))
                .as("and a foreign company's context yields nothing")
                .isZero();
    }

    @Test
    @DisplayName("every migration was installed by the non-superuser owner")
    void flywayRanAsTheOwner() throws SQLException {
        // Guards the other half of the arrangement: the roles could be correct while
        // spring.flyway.user still pointed at the superuser, and every assertion above
        // would still pass because the OWNER of the tables would then be the superuser.
        List<String> installers = new ArrayList<>();
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT DISTINCT installed_by FROM flyway_schema_history")) {
            while (rs.next()) {
                installers.add(rs.getString(1));
            }
        }
        assertThat(installers)
                .as("Flyway must connect as the schema owner, and as nothing else")
                .containsExactly(OWNER_ROLE);
    }

    @Test
    @DisplayName("the owner owns every table and the application role owns nothing")
    void ownershipSitsWhereItShould() throws SQLException {
        List<String> notOwnedByOwner = new ArrayList<>();
        List<String> ownedByApp = new ArrayList<>();

        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT c.relname, pg_get_userbyid(c.relowner) AS owner
                       FROM pg_class c
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'public'
                        AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
                      ORDER BY c.relname
                     """)) {
            while (rs.next()) {
                String owner = rs.getString("owner");
                if (APP_ROLE.equals(owner)) {
                    ownedByApp.add(rs.getString("relname"));
                } else if (!OWNER_ROLE.equals(owner)) {
                    notOwnedByOwner.add(rs.getString("relname") + " -> " + owner);
                }
            }
        }

        assertThat(ownedByApp)
                .as("the application pool must own nothing — ownership carries DROP and ALTER POLICY")
                .isEmpty();
        assertThat(notOwnedByOwner)
                .as("every relation in public belongs to the migration owner")
                .isEmpty();
    }

    @Test
    @DisplayName("the application role cannot rewrite the isolation layer that constrains it")
    void theAppRoleCannotTouchThePolicies() throws SQLException {
        // §17's argument for the split, as assertions rather than as prose: FORCE would
        // still hold if the application ran as the owner, so nothing would LOOK wrong —
        // the application would simply also hold DROP POLICY on all 56 tables.
        try (Connection connection = appConnection();
             Statement statement = connection.createStatement()) {

            assertThatThrownBy(() -> statement.execute("ALTER TABLE project DISABLE ROW LEVEL SECURITY"))
                    .as("disabling RLS requires ownership")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must be owner of table");

            assertThatThrownBy(() -> statement.execute("DROP POLICY company_isolation ON project"))
                    .as("dropping a policy requires ownership")
                    .isInstanceOf(SQLException.class);

            // TRUNCATE is deliberately not granted: it bypasses row-level DELETE policies
            // entirely, so a company could otherwise empty another's table.
            assertThatThrownBy(() -> statement.execute("TRUNCATE project"))
                    .as("TRUNCATE bypasses row-level DELETE policies and is never granted")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    private static void assertRoleIsUnprivileged(String role) throws SQLException {
        try (Connection connection = superuserConnection();
             var statement = connection.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("role %s must exist", role).isTrue();
                assertThat(rs.getBoolean("rolsuper"))
                        .as("%s must not be a superuser — a superuser bypasses RLS unconditionally", role)
                        .isFalse();
                assertThat(rs.getBoolean("rolbypassrls"))
                        .as("%s must not hold BYPASSRLS", role)
                        .isFalse();
            }
        }
    }

    private static Connection ownerConnectionWithCompany(UUID companyId) throws SQLException {
        Connection connection = migrationOwnerConnection();
        try (var statement =
                connection.prepareStatement("SELECT set_config('app.current_company', ?, false)")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private static long countProjectsAs(ConnectionSupplier supplier) throws SQLException {
        try (Connection connection = supplier.get();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM project WHERE company_id = ?")) {
            statement.setObject(1, COMPANY);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get() throws SQLException;
    }
}
