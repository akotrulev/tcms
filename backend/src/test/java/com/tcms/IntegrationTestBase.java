package com.tcms;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for every integration test: one Postgres container for the whole run, the real
 * application context, real Flyway, and the privilege split that makes RLS testing
 * mean something.
 *
 * <p>Booting the real context is itself a gate — {@code ddl-auto: validate} means
 * Hibernate checks every entity against the migrated schema at startup, so an entity
 * that drifts from V1 fails here rather than at the first query in production.
 *
 * <h2>Why two roles</h2>
 *
 * <p>The most important detail in the setup, and getting it wrong makes every isolation
 * test pass while nothing is protected:
 *
 * <ul>
 *   <li><strong>A superuser always bypasses RLS.</strong> Testcontainers' default user
 *       is one, so a suite that migrated and queried as it would never see a policy
 *       take effect.</li>
 *   <li><strong>A table's owner bypasses plain {@code ENABLE ROW LEVEL SECURITY}.</strong>
 *       V1 also issues {@code FORCE}, which does cover the owner — but connecting as
 *       the owner anyway would leave the suite unable to notice if {@code FORCE} were
 *       ever dropped.</li>
 * </ul>
 *
 * <p>So Flyway migrates as the container's owner/superuser, and the application pool
 * connects as {@code tcms_app}: a plain {@code LOGIN} role owning nothing, holding no
 * {@code BYPASSRLS}. It is created by the test-only after-migrate callback in
 * {@code src/test/resources/db/testcallback} — creating roles belongs to the
 * deployment, not to a production migration, which is why that location is added to
 * {@code spring.flyway.locations} only here.
 *
 * <p>{@code RlsIsolationTest} asserts both role properties rather than trusting this.
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    /** The unprivileged role the application connects as. */
    public static final String APP_ROLE = "tcms_app";
    public static final String APP_PASSWORD = "app-secret";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withDatabaseName("tcms")
                    .withUsername("tcms_owner")
                    .withPassword("owner-secret");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/testcallback");

        // The signing key has no default in application.yml — deliberately, so no
        // deployment can start with one taken from this repository. The suite supplies its
        // own, and it is not a value any environment would plausibly reuse.
        registry.add("tcms.auth.jwt.secret",
                () -> "test-only-signing-key-not-for-any-real-deployment");

        // Production sizes this at 20. The suite must not.
        //
        // Spring caches one context per distinct configuration, and this suite now has
        // several — MockMvc, non-web, virtual-threads-off, mail-configured — each holding
        // its OWN Hikari pool against the ONE shared container. At 20 apiece they exceed
        // Postgres's default 100 connection slots, and the failure surfaces as
        // "remaining connection slots are reserved for roles with the SUPERUSER attribute"
        // in whichever unrelated test happened to start last. It reads as a leak or a
        // hung transaction; it is arithmetic.
        //
        // Five is ample: no test drives concurrent load, and a low ceiling means a genuine
        // connection leak shows up as exhaustion quickly rather than hiding behind headroom.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
    }

    /**
     * A connection as the owner/superuser — ground truth, past RLS. Use it to assert
     * what a company-scoped query <em>should not</em> have been able to see.
     */
    protected static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** A connection as the unprivileged application role, with RLS in force. */
    protected static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    /**
     * An application-role connection with a company already active — the raw-JDBC
     * equivalent of {@code CompanyContext.open()}, for the tables that have no entity yet.
     *
     * <p>Session-scoped ({@code set_config(..., false)}) rather than transaction-scoped, so
     * the setting survives the autocommit statements a schema-level test issues. Production
     * uses the transaction-scoped form via {@code CompanyAwareTransactionManager}; a test
     * that borrowed a pooled connection this way would leak the company into the next
     * borrower, which is exactly why the production path sets it per transaction instead.
     */
    protected static Connection appConnection(UUID companyId) throws SQLException {
        Connection connection = appConnection();
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
}
