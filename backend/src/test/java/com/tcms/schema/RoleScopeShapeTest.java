package com.tcms.schema;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.Role;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The V56 scope-shape triggers — the guarantee behind {@code RoleGrantService.scopeError}.
 *
 * <p>The service refuses these shapes on the one HTTP path that writes grants; the triggers
 * refuse them for every writer, because the evaluator reads both tables without checking
 * {@code role.scope} — deliberately, per {@code RoleAssignmentRepository}'s headers — so a
 * mis-shaped row would not error anywhere. It would <em>answer</em>: a COMPANY-scope role
 * naming a project trips the override switch and replaces the subject's inherited
 * company-wide default, and a non-SYSTEM role in {@code instance_role_assignment} confers
 * its keys instance-wide with no company gate in front of them.
 *
 * <p>Everything here goes through raw SQL on purpose, exactly like {@link IntegrityTriggerTest}:
 * the application layer already refuses these shapes, so driving them through the entities
 * would prove the service, not the trigger.
 */
@DisplayName("Scope-shape triggers — role.scope agrees with the grant's shape (V56)")
class RoleScopeShapeTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("5c09e000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("5c09e000-0000-4000-8000-000000000002");
    private static final UUID USER = UUID.fromString("5c09e000-0000-4000-8000-000000000011");
    private static final UUID SECOND_USER = UUID.fromString("5c09e000-0000-4000-8000-000000000012");

    private static Connection connection;

    /** Same {@code @BeforeEach}-not-{@code @BeforeAll} reasoning as {@link IntegrityTriggerTest}. */
    @BeforeEach
    void seed() throws SQLException {
        if (connection != null) {
            return;
        }
        connection = appConnection(COMPANY);
        execute("INSERT INTO company (id, slug, display_name) VALUES ('%s', 'scopeshapeco', 'Scope Shape Co')"
                .formatted(COMPANY));
        execute("INSERT INTO project (id, company_id, key, name) VALUES ('%s', '%s', 'SCP', 'Scope Shapes')"
                .formatted(PROJECT, COMPANY));
        execute("INSERT INTO app_user (id, email, canonical_name) VALUES ('%s', 'scope-shape@example.test', 'Scope Shape')"
                .formatted(USER));
        execute("INSERT INTO app_user (id, email, canonical_name) VALUES ('%s', 'scope-shape-2@example.test', 'Scope Shape II')"
                .formatted(SECOND_USER));
    }

    @AfterAll
    static void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Test
    @DisplayName("a COMPANY-scope role granted on a project is refused")
    void companyRoleNamingAProjectIsRefused() {
        assertThatThrownBy(() -> grant(Role.COMPANY_ADMIN_ID, PROJECT))
                .as("""
                        The row the evaluator misreads twice: its keys would resolve as project \
                        permissions, and its existence would trip the override switch — replacing \
                        the subject's inherited company-wide default with this row's grant, so a \
                        row written to widen would quietly remove.""")
                .hasMessageContaining("COMPANY-scope");
    }

    @Test
    @DisplayName("a SYSTEM role in role_assignment is refused at either shape")
    void systemRoleInRoleAssignmentIsRefused() {
        assertThatThrownBy(() -> grant(Role.INSTANCE_ADMIN_ID, null))
                .as("instance-wide powers scoped to one company is a tier the model does not have")
                .hasMessageContaining("SYSTEM-scope");

        assertThatThrownBy(() -> grant(Role.INSTANCE_ADMIN_ID, PROJECT))
                .hasMessageContaining("SYSTEM-scope");
    }

    @Test
    @DisplayName("a non-SYSTEM role in instance_role_assignment is refused")
    void nonSystemRoleInInstanceTableIsRefused() {
        assertThatThrownBy(() -> grantInstanceWide(Role.TEST_LEAD_ID))
                .as("""
                        findSystemHeldPermissions reads the role's keys with scope unread and no \
                        company gate in front of the SYSTEM term, so this row would be an \
                        instance-wide grant of a project role's keys.""")
                .hasMessageContaining("SYSTEM grants only");
    }

    @Test
    @DisplayName("every legal shape still inserts")
    void theLegalShapesStillInsert() throws SQLException {
        assertThatCode(() -> {
            grant(Role.TEST_LEAD_ID, PROJECT);
            grant(Role.TEST_ENGINEER_ID, null);
            grant(Role.COMPANY_ADMIN_ID, null);
            grantInstanceWideFor(SECOND_USER, Role.INSTANCE_ADMIN_ID);
        }).doesNotThrowAnyException();

        try (Connection owner = migrationOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("DELETE FROM instance_role_assignment WHERE user_id = '%s'"
                    .formatted(SECOND_USER));
        }
    }

    private void grant(UUID roleId, UUID projectId) throws SQLException {
        execute("""
                INSERT INTO role_assignment (id, company_id, role_id, user_id, project_id)
                VALUES ('%s', '%s', '%s', '%s', %s)
                """.formatted(
                UUID.randomUUID(), COMPANY, roleId, USER,
                projectId == null ? "NULL" : "'" + projectId + "'"));
    }

    private void grantInstanceWide(UUID roleId) throws SQLException {
        grantInstanceWideFor(USER, roleId);
    }

    /**
     * Through the migration owner, not {@code tcms_app}: the instance table is written by
     * provisioning, so the app role's privileges on it are not this test's subject — the
     * trigger fires identically for every writer.
     */
    private static void grantInstanceWideFor(UUID userId, UUID roleId) throws SQLException {
        try (Connection owner = migrationOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("""
                    INSERT INTO instance_role_assignment (id, role_id, user_id)
                    VALUES ('%s', '%s', '%s')
                    """.formatted(UUID.randomUUID(), roleId, userId));
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
