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
        //
        // That was TRUE AS AN INTENTION AND FALSE AS A DESCRIPTION until 2026-08-06.
        // application.yml said `${TCMS_JWT_SECRET:?…}`, which is shell syntax Spring does not
        // implement — it took the message after the colon as the default, so every deployment
        // without the variable signed sessions with a string from this repository, production
        // included. Worth recording HERE, at the line that hid it: this override replaces the
        // placeholder outright, so no test in the suite has ever evaluated the expression that
        // actually ships. A green suite said nothing about it and could not have.
        // `ConfigConventionTest` now checks the shipped text instead of the resolved property.
        registry.add("tcms.auth.jwt.secret",
                () -> "test-only-signing-key-not-for-any-real-deployment");

        // The integration credential key has no default either, and for a sharper version of the
        // same reason: it decrypts every notification-channel webhook in every company, and a
        // webhook URL IS the authentication for the room it posts to. So the placeholder in
        // application.yml has no colon and the context refuses to start without it — which means
        // EVERY test in this suite needs one, including the several hundred that have nothing to
        // do with notifications.
        //
        // Base64 of exactly 32 bytes; IntegrationProperties refuses anything else. And the same
        // warning the JWT override carries applies here: this replaces the shipped expression
        // outright, so no test can observe a defect in its TEXT. ConfigConventionTest is what
        // reads that.
        registry.add("tcms.integration.secret-key",
                () -> "dGVzdC1vbmx5LWludGVncmF0aW9uLWtleS0zMi1ieXQ=");

        // The OIDC client-secret key, same shape and same reason one module over: `identity` may
        // not depend on `integration`, so federation has its own key, its own placeholder with no
        // colon, and therefore its own refusal to start without one — which every context in this
        // suite needs, including the several hundred that have nothing to do with sign-in.
        registry.add("tcms.identity.provider-secret-key",
                () -> "dGVzdC1vbmx5LXByb3ZpZGVyLWtleS0zMi1ieXRlcyE=");

        // The case-health recompute is a WRITE on a timer, over every company in the
        // database. Left enabled it fires two minutes into a multi-minute suite run and
        // rewrites case_health for whichever fixtures have seeded by then — most visibly
        // DashboardEndpointTest, whose subject is that healthComputedAt is null. That
        // presents as a test which passes alone and fails in the suite at a different
        // point each run, which is §19's first defect and the hardest kind to attribute.
        //
        // Off here rather than in a test-only application.yml: this file is already the
        // one place the suite states how it differs from a deployment, and a second
        // properties source is a second place to look. CaseHealthRecomputeTest turns it
        // back on for its own context and otherwise drives the service directly.
        registry.add("tcms.case-health.enabled", () -> false);

        // Off for the same reason, one module over: ExecutionFactScheduler is a WRITE on a timer
        // over every company in the database. Left enabled it projects execution facts for
        // whichever fixtures have seeded by the time it fires, and — because the fact log is
        // append-only and its dimensions are frozen on insert — a row it writes early is not
        // corrected by a later refresh, so the damage outlives the tick that caused it. That is
        // strictly worse than the case-health case, where a rerun repairs everything.
        // ExecutionFactRefreshTest drives the service directly.
        registry.add("tcms.reporting.enabled", () -> false);

        // Off for the third time, and this one is the widest: the outbox dispatcher is a WRITE on
        // a timer that then hands rows to HANDLERS, so what it moves is not its own table but
        // whatever the consuming modules do — execution facts, case health, anything a later slice
        // registers. A tick landing partway through a suite run would apply another test class's
        // events under this one's fixtures, and the failure would surface in the module that
        // handled them rather than here. OutboxDispatchTest drives the dispatcher directly, which
        // is how a scheduled job's logic should be tested anyway.
        registry.add("tcms.outbox.enabled", () -> false);

        // Evidence bytes land in a directory, not in Postgres — the one piece of product state
        // Testcontainers does not throw away for us. The production default is /var/lib/tcms,
        // which is not writable on a developer's machine, so the suite gets its own path under
        // the JVM's temp directory.
        //
        // NOT cleaned between classes, on purpose: keys are three server-generated UUIDs, so two
        // tests cannot collide, and a leftover file is the evidence you want when a download
        // assertion fails. The OS clears the directory; §19's fixture-collision defect is about
        // hand-managed prefixes and does not apply to a key space that is entirely random.
        //
        // THE USER NAME IS IN THE PATH SINCE §155, and it is not tidiness. The fixture ids are
        // random, the DIRECTORIES above them are not: the tree is companyId/runId/key, and the
        // first two segments come from a test class's hand-assigned prefix, which is the same on
        // every machine and in every checkout. This box runs the nightly lane as `ghrunner` and a
        // developer as themselves, both against /tmp — so the first user to run ExecutionEndpointTest
        // creates /tmp/tcms-evidence-test/e2000000-…/e2007000-… mode 0755, and the second user gets
        // AccessDeniedException on eleven tests that have nothing to do with what they changed.
        // Observed, not hypothesised: it is what the §155 gate ran into, one day after the runner
        // was registered on the same machine.
        registry.add("tcms.storage.evidence.root",
                () -> System.getProperty("java.io.tmpdir") + "/tcms-evidence-test-" + userName());

        // The same, for report bytes (§87) — a second root because the product has two, and
        // pointing the suite's two at one directory would be the one configuration no deployment
        // is obliged to use and every test would then depend on.
        registry.add("tcms.storage.reports.root",
                () -> System.getProperty("java.io.tmpdir") + "/tcms-reports-test-" + userName());

        // The third root (§160) — the verbatim CI upload. A third directory for the reason the
        // second one is separate: the product has three roots, and a suite that pointed them at one
        // would be testing the single configuration no deployment is obliged to use. It would also
        // hide the one defect the split exists to catch, a store constructed with its sibling's
        // property key — which CA-20 in the mutation queue names against these very lines.
        registry.add("tcms.storage.raw-reports.root",
                () -> System.getProperty("java.io.tmpdir") + "/tcms-raw-reports-test-" + userName());

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
     * The account this JVM runs as, sanitised to a single path segment — see the storage roots.
     *
     * <p>{@code "shared"} when the JVM cannot say. That is the honest fallback rather than a
     * generated name: it collides exactly as the old path did instead of scattering evidence into
     * a directory nothing else can find.
     */
    private static String userName() {
        String sanitised = System.getProperty("user.name", "").replaceAll("[^A-Za-z0-9_.-]", "");
        return sanitised.isEmpty() ? "shared" : sanitised;
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
