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
 * <h2>Why three roles, and why none of them is a superuser</h2>
 *
 * <p>The most important detail in the setup, and getting it wrong makes every isolation
 * test pass while nothing is protected:
 *
 * <ul>
 *   <li><strong>A superuser always bypasses RLS</strong> — {@code FORCE} included.
 *       Testcontainers' default user is one, so a suite that migrated as it exercises an
 *       arrangement production does not have.</li>
 *   <li><strong>A table's owner bypasses plain {@code ENABLE ROW LEVEL SECURITY}</strong>,
 *       which is why V1 issues {@code FORCE} as well. {@code FORCE} <em>does</em> cover a
 *       non-superuser owner, and the reflex belief that it does not cost this project a
 *       HIGH defect — see the V8 migration header.</li>
 * </ul>
 *
 * <p>So the container's superuser ({@value #SUPERUSER_ROLE}) does exactly two things:
 * create the two real roles, and answer ground-truth questions in assertions. Nothing
 * under test connects as it. Flyway migrates as non-superuser {@code tcms_owner} and the
 * application pool connects as {@code tcms_app}, both created by
 * {@code src/test/resources/db/testinit/tcms-roles.sql}, which runs as a Testcontainers
 * init script — i.e. <em>before</em> Flyway, the same ordering the deployment has.
 *
 * <p>That file mirrors {@code deploy/postgres/initdb/10-tcms-roles.sql}. It replaced a
 * Flyway {@code afterMigrate} callback that created {@code tcms_app} after the fact and
 * left the owner a superuser; §18 of the handoff records what that gap hid.
 *
 * <p>{@code PrivilegeSplitTest} asserts every one of these properties rather than trusting
 * this comment — a comment claiming a privilege arrangement is not a privilege arrangement,
 * which is the lesson the callback's own header failed to apply to itself.
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    /** The unprivileged role the application connects as. */
    public static final String APP_ROLE = "tcms_app";
    public static final String APP_PASSWORD = "app-secret";

    /**
     * The schema owner Flyway connects as — non-superuser, so every policy applies to it
     * exactly as in the deployment. Its credentials are duplicated in
     * {@code db/testinit/tcms-roles.sql}, which JDBC cannot parameterize.
     */
    public static final String OWNER_ROLE = "tcms_owner";
    public static final String OWNER_PASSWORD = "owner-secret";

    /**
     * The container's superuser. Creates the two roles above and answers ground-truth
     * questions; <strong>nothing under test connects as it.</strong>
     */
    public static final String SUPERUSER_ROLE = "tcms_super";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withDatabaseName("tcms")
                    .withUsername(SUPERUSER_ROLE)
                    .withPassword("super-secret")
                    // Runs after the container is ready and BEFORE anything else touches
                    // the database, so tcms_owner exists in time to migrate.
                    .withInitScript("db/testinit/tcms-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);

        // Flyway migrates as the OWNER, never as the container's superuser and never as
        // the pool's role. application.yml gives Flyway its own DB_MIGRATION_USER for the
        // same reason; this is the suite's spelling of it.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> OWNER_ROLE);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        // No test-only location: spring.flyway.locations stays at the production default,
        // so the suite runs exactly the migration set the deployment does. The callback
        // that used to live at classpath:db/testcallback granted the app role its
        // privileges after the fact; db/testinit/tcms-roles.sql now does it beforehand,
        // through the deployment's own ALTER DEFAULT PRIVILEGES mechanism.

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
     * A connection as the container's <strong>superuser</strong> — ground truth, past RLS.
     * Use it to assert what a company-scoped query <em>should not</em> have been able to
     * see, and to set up fixtures in tables the caller has no context for.
     *
     * <p>This is the <em>only</em> RLS-exempt connection in the suite. It is deliberately
     * not the migration owner: see {@link #migrationOwnerConnection()}.
     */
    protected static Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * A connection as the schema owner Flyway migrates with — owns every table, and is
     * <strong>still subject to every policy</strong>, because V1 issues
     * {@code FORCE ROW LEVEL SECURITY}.
     *
     * <p>Separate from {@link #superuserConnection()} on purpose, and named so a call site
     * cannot confuse the two: "the owner" is not a way past RLS in this schema, and the
     * reflex to assume it is cost this project a HIGH defect
     * (V8's header — a {@code SECURITY DEFINER} lookup that returned zero rows in
     * production while 171 tests stayed green).
     */
    protected static Connection migrationOwnerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER_ROLE, OWNER_PASSWORD);
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
