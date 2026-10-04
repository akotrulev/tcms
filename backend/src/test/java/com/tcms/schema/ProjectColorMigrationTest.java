package com.tcms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code V15} applied over a database that already holds two companies' projects.
 *
 * <p>Its own container, migrated to {@code V14} first, because the shared one is already at head.
 * Flyway migrates as the non-superuser {@code tcms_owner}, so {@code FORCE ROW LEVEL SECURITY}
 * applies to the back-fill exactly as it does in production: an {@code UPDATE} with no company set
 * would touch zero rows and the {@code SET NOT NULL} after it would fail. Seeded and read as the
 * superuser, which is ground truth the policy cannot hide.
 */
@DisplayName("V15 — every existing project, in every company, is given a colour")
class ProjectColorMigrationTest {

    private static final List<String> KEYS_A = List.of("ALPHA", "BETA", "GAMMA", "DELTA");
    private static final List<String> KEYS_B = List.of("ALPHA", "EPSILON", "ZETA");

    @Test
    @DisplayName("both companies' projects are coloured with a token, and the constraint holds")
    void everyExistingProjectIsColoured() throws Exception {
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "14");
            seed(db);
            assertThat(countProjects(db)).as("the fixture must hold projects").isEqualTo(7);

            migrateTo(db, "15");

            assertThat(colours(db, "SELECT color FROM project")).hasSize(7)
                    .allSatisfy(colour -> assertThat(colour).matches("^project-[1-8]$"));
            assertThat(colours(db, "SELECT color FROM project WHERE company_id = "
                    + "(SELECT id FROM company WHERE slug = 'clr-b')")).hasSize(3);
        }
    }

    @Test
    @DisplayName("the back-fill is a function of the key")
    void sameKeySameColour() throws Exception {
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "14");
            seed(db);
            migrateTo(db, "15");

            assertThat(colours(db, "SELECT DISTINCT color FROM project WHERE key = 'ALPHA'"))
                    .as("ALPHA exists in both companies and hashes the same in each")
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("the CHECK refuses a colour outside the eight")
    void checkRefusesUnknown() throws Exception {
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "14");
            seed(db);
            migrateTo(db, "15");

            try (Connection connection = superuser(db); Statement statement = connection.createStatement()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> statement.execute("UPDATE project SET color = 'project-9'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_project_color");
            }
        }
    }

    private static void seed(PostgreSQLContainer<?> db) throws SQLException {
        try (Connection connection = superuser(db); Statement statement = connection.createStatement()) {
            seedCompany(statement, "clr-a", KEYS_A);
            seedCompany(statement, "clr-b", KEYS_B);
        }
    }

    private static void seedCompany(Statement statement, String slug, List<String> keys)
            throws SQLException {
        statement.execute("INSERT INTO company (id, slug, display_name) VALUES "
                + "(gen_random_uuid(), '" + slug + "', '" + slug + "')");
        for (String key : keys) {
            statement.execute("INSERT INTO project (id, company_id, key, name) "
                    + "SELECT gen_random_uuid(), id, '" + key + "', '" + key + "' "
                    + "FROM company WHERE slug = '" + slug + "'");
        }
    }

    private static PostgreSQLContainer<?> freshDatabase() {
        PostgreSQLContainer<?> db = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                .withDatabaseName("tcms")
                .withUsername("tcms_super")
                .withPassword("super-secret")
                .withInitScript("db/testinit/tcms-roles.sql");
        db.start();
        return db;
    }

    private static void migrateTo(PostgreSQLContainer<?> db, String version) {
        Flyway.configure()
                .dataSource(db.getJdbcUrl(), "tcms_owner", "owner-secret")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static long countProjects(PostgreSQLContainer<?> db) throws SQLException {
        try (Connection connection = superuser(db);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM project")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static List<String> colours(PostgreSQLContainer<?> db, String sql) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection connection = superuser(db);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }

    private static Connection superuser(PostgreSQLContainer<?> db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }
}
