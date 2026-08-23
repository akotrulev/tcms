package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>Two projects in ONE company</strong> — the fixture six sections of the mutation queue have
 * been asking for, and the only arrangement in which a dropped {@code AND x.project_id = :projectId}
 * is observable at all.
 *
 * <h2>The boundary this fixture crosses, and why one that does not would be green</h2>
 *
 * <p>Every other class in this package seeds <em>one project per company</em> and asserts the
 * absence of a sibling <em>company's</em> row. That arrangement cannot see a missing project
 * predicate, and not by a little: {@code FORCE ROW LEVEL SECURITY} refuses the foreign row before
 * the {@code WHERE} clause is consulted, so the query returns nothing whether or not it asked to.
 * Deleting the predicate from any of the three seams below leaves such a class entirely green — the
 * measured fact behind {@code R7-6}, {@code R8-7} and {@code CM-6} being recorded as gaps rather
 * than as measurements six sections running.
 *
 * <p>So the isolation boundary is the company and the <strong>project bound is the application's
 * alone</strong>. Both projects here belong to {@link #COMPANY}; one session sees both; the database
 * refuses nothing. What each query returns is decided entirely by the predicate under test, which is
 * the property {@link Reachability#bothProjectsRowsAreVisibleToOneSession()} asserts directly rather
 * than leaving as this comment's claim.
 *
 * <h2>Every absence is paired with its control</h2>
 *
 * <p>An empty result proves nothing on its own — the row may not exist, the fixture may not have
 * committed, RLS may have refused it. So each test asks the same question twice: once bounded to
 * {@link #HOME} (must not see the sibling's row) and once bounded to {@link #SIBLING} (must). A
 * fixture whose second project holds no row the query could reach is the same defect as a severity
 * pair that is already sorted — §179 {@code RS-M3}'s lesson, which is this wave's subject.
 *
 * <p>Ids are {@code 5b…} and slugs {@code spr-…}, unique to this class; §19's first defect was two
 * classes sharing a naturally-keyed fixture value and passing alone.
 */
@DisplayName("The project bound over RLS — two projects, one company")
class SiblingProjectReadsTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("5b000000-0000-4000-8000-000000000001");

    /** The project every query below is bounded to. */
    private static final UUID HOME = UUID.fromString("5b001000-0000-4000-8000-000000000001");

    /**
     * Its sibling in the same company — same {@code company_id}, so RLS passes every one of its
     * rows to the session {@link #HOME}'s reads run in. Nothing but the predicate keeps them apart.
     */
    private static final UUID SIBLING = UUID.fromString("5b001000-0000-4000-8000-000000000002");

    private static final UUID HOME_SUITE = UUID.fromString("5b002000-0000-4000-8000-000000000001");
    private static final UUID SIBLING_SUITE = UUID.fromString("5b002000-0000-4000-8000-000000000002");

    private static final UUID HOME_CASE = UUID.fromString("5b004000-0000-4000-8000-000000000001");
    private static final UUID SIBLING_CASE = UUID.fromString("5b004000-0000-4000-8000-000000000002");

    private static final UUID HOME_SPACE = UUID.fromString("5b005000-0000-4000-8000-000000000001");
    private static final UUID SIBLING_SPACE = UUID.fromString("5b005000-0000-4000-8000-000000000002");
    private static final UUID HOME_PAGE = UUID.fromString("5b006000-0000-4000-8000-000000000001");
    private static final UUID SIBLING_PAGE = UUID.fromString("5b006000-0000-4000-8000-000000000002");

    private static final UUID DECIDER = UUID.fromString("5b007000-0000-4000-8000-000000000001");

    @Autowired
    private ExecutionSource executions;

    @Autowired
    private DocumentationSource documents;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        if (companies.findById(COMPANY).isEmpty()) {
            companies.save(Company.provision(COMPANY, "spr-acme", "Sibling Projects", now));
        }
        try (var ignored = CompanyContext.open(COMPANY)) {
            if (projects.findScopedById(HOME).isEmpty()) {
                projects.save(Project.create(HOME, COMPANY, "SPRH", "Home Product", null, now));
            }
            if (projects.findScopedById(SIBLING).isEmpty()) {
                projects.save(
                        Project.create(SIBLING, COMPANY, "SPRS", "Sibling Product", null, now));
            }
        }

        try (Connection connection = appConnection(COMPANY)) {
            clear(connection);
            seedProjectDefaults(connection, COMPANY, HOME);
            seedProjectDefaults(connection, COMPANY, SIBLING);
            decider(connection);

            suite(connection, HOME_SUITE, HOME, "SPR-1", 1);
            suite(connection, SIBLING_SUITE, SIBLING, "SPR-2", 2);

            // One case per project. Display keys are sequential per COMPANY, not per project
            // (§6t, and uq_test_case_key is on (company_id, key)) — so the sibling's case carries
            // a key and a title that resolve perfectly well, and a query that has lost its project
            // bound returns a label a reader has no way to tell from the right one.
            testCase(connection, HOME_CASE, HOME, HOME_SUITE, "TC-1", 1, "Home's own case");
            testCase(connection, SIBLING_CASE, SIBLING, SIBLING_SUITE, "TC-2",
                    2, "The sibling's case");

            space(connection, HOME_SPACE, HOME);
            space(connection, SIBLING_SPACE, SIBLING);
            page(connection, HOME_PAGE, HOME_SPACE, HOME, "SPRH-1", 1, "sprhome",
                    "Home's specification");
            page(connection, SIBLING_PAGE, SIBLING_SPACE, SIBLING, "SPRS-1", 2, "sprsibling",
                    "The sibling's specification");

            quarantine(connection, SIBLING_CASE, SIBLING, "Flaky in the sibling project");
        }
    }

    @Nested
    @DisplayName("Reachability — what makes this fixture able to fail")
    class Reachability {

        @Test
        @DisplayName("one session sees both projects' rows, so RLS refuses nothing here")
        void bothProjectsRowsAreVisibleToOneSession() throws SQLException {
            // The premise the three tests below rest on, asserted rather than argued. If this ever
            // goes red the others become vacuous: they would be measuring RLS, which every
            // one-project-per-company class in this package already measures.
            try (Connection connection = appConnection(COMPANY);
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT project_id FROM test_case WHERE id IN "
                                    + "(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setString(1, HOME_CASE.toString());
                statement.setString(2, SIBLING_CASE.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    Set<UUID> visible = new HashSet<>();
                    while (rows.next()) {
                        visible.add(UUID.fromString(rows.getString(1)));
                    }
                    assertThat(visible)
                            .as("both projects belong to one company, so the database passes both "
                                    + "rows to one session — the project bound is the query's alone")
                            .containsExactlyInAnyOrder(HOME, SIBLING);
                }
            }
        }
    }

    @Nested
    @DisplayName("ExecutionSource — the labels a report prints")
    class Labels {

        @Test
        @DisplayName("a sibling project's case title cannot reach this project's report")
        void caseLabelsAreBounded() {
            // R7-6. caseLabels takes ids that came off execution_fact, and a report naming a case
            // by a title the reader holds no grant on is a disclosure, not a display bug.
            assertThat(inTransaction(() -> executions.caseLabels(HOME, List.of(SIBLING_CASE))))
                    .as("bounded to HOME, the sibling's case must not resolve")
                    .isEmpty();

            assertThat(inTransaction(() -> executions.caseLabels(SIBLING, List.of(SIBLING_CASE))))
                    .as("THE CONTROL — the row exists and this query can reach it, so the absence "
                            + "above is the predicate's doing and not the fixture's")
                    .containsOnlyKeys(SIBLING_CASE);
        }

        @Test
        @DisplayName("a sibling project's version number cannot reach this project's report")
        void caseVersionNumbersAreBounded() {
            // R8-7. The same predicate one method over, and the fifth file to layer it.
            UUID siblingVersion = versionOf(SIBLING_CASE);

            assertThat(inTransaction(
                    () -> executions.caseVersionNumbers(HOME, List.of(siblingVersion))))
                    .as("bounded to HOME, the sibling's version must not number")
                    .isEmpty();

            assertThat(inTransaction(
                    () -> executions.caseVersionNumbers(SIBLING, List.of(siblingVersion))))
                    .as("THE CONTROL — the join through test_case is the only route to a project "
                            + "bound here, and it reaches the row when asked for the right project")
                    .containsOnlyKeys(siblingVersion);
        }

        @Test
        @DisplayName("a sibling project's quarantine does not discount this project's results")
        void quarantinedCasesAreBounded() {
            // The fourth project-bounded read in this seam. Unlike the two above it takes no ids
            // from the caller at all, so the predicate is the ONLY thing scoping it.
            assertThat(inTransaction(() -> executions.quarantinedCases(HOME)))
                    .as("HOME has quarantined nothing")
                    .isEmpty();

            assertThat(inTransaction(() -> executions.quarantinedCases(SIBLING)))
                    .as("THE CONTROL — the quarantine exists and this query finds it in its own "
                            + "project")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("DocumentationSource — the page a coverage report is computed over")
    class Pages {

        @Test
        @DisplayName("a sibling project's document cannot become this project's report spine")
        void findPageIsBounded() {
            // CM-6, and the widest of the four: findPage answers the project question ONCE for the
            // whole report and the two methods beside it take the page id it returned, so a
            // predicate lost here is a predicate lost for every block and link in the output.
            assertThat(inTransaction(() -> documents.findPage(HOME, SIBLING_PAGE)))
                    .as("bounded to HOME, the sibling's page must not be reportable")
                    .isEmpty();

            assertThat(inTransaction(() -> documents.findPage(SIBLING, SIBLING_PAGE)))
                    .as("THE CONTROL — the page is live, published and visible to this session")
                    .isPresent();
        }
    }

    /**
     * In a transaction, not merely in a company context — outside one {@code app.current_company} is
     * unset, V4's {@code nullif} matches zero rows, and every absence assertion here would pass for
     * the one reason this class exists to rule out.
     */
    private <T> T inTransaction(Supplier<T> work) {
        try (var ignored = CompanyContext.open(COMPANY)) {
            return transactions.execute(status -> work.get());
        }
    }

    private UUID versionOf(UUID caseId) {
        try (Connection connection = appConnection(COMPANY);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM test_case_version WHERE case_id = CAST(? AS uuid)"
                                + " AND version_no = 1")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the fixture must have written a v1").isTrue();
                return UUID.fromString(rows.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ fixture ---

    private void clear(Connection connection) throws SQLException {
        for (String table : List.of("execution_fact", "case_flake", "case_health", "doc_link",
                "doc_block", "doc_page", "doc_space", "step_result", "test_execution", "test_run",
                "case_classification", "test_case_version", "test_case", "suite")) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    /**
     * Instance-level and never cleared, so this is an upsert:
     * {@code case_flake_quarantine_complete} refuses a quarantine without a decider, and
     * {@code quarantined_by} is a foreign key.
     */
    private void decider(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_user (id, email, canonical_name, status)
                VALUES (CAST(? AS uuid), CAST('lead@spr.test' AS citext), 'SPR Lead', 'ACTIVE')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, DECIDER.toString());
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, UUID projectId, String key, long keySeq)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position,
                                   lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, 'Root', 0,
                        'APPROVED', NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, COMPANY.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID projectId, UUID suiteId, String key,
            long keySeq, String title) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, COMPANY.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 1,
                        CAST('{}' AS jsonb), ?, now())
                    ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, COMPANY.toString());
            statement.setString(2, id.toString());
            statement.setString(3, "hash-" + id);
            statement.executeUpdate();
        }
    }

    private void space(Connection connection, UUID id, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_space (id, company_id, project_id, name)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'Specifications')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, COMPANY.toString());
            statement.setString(3, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void page(Connection connection, UUID id, UUID spaceId, UUID projectId, String key,
            long keySeq, String path, String title) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_page
                    (id, company_id, space_id, project_id, key, key_seq, path, slug, title,
                     status, current_version_no)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?, ?, 'PUBLISHED', 1)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, COMPANY.toString());
            statement.setString(3, spaceId.toString());
            statement.setString(4, projectId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, path);
            statement.setString(8, path);
            statement.setString(9, title);
            statement.executeUpdate();
        }
    }

    private void quarantine(Connection connection, UUID caseId, UUID projectId, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_flake
                    (case_id, company_id, project_id, executions, transitions, alternations, cells,
                     quarantined, quarantine_reason, quarantined_at, quarantined_by, computed_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 0, 0, 0, 0,
                        TRUE, ?, now(), CAST(? AS uuid), now())
                    ON CONFLICT (case_id) DO NOTHING
                """)) {
            statement.setString(1, caseId.toString());
            statement.setString(2, COMPANY.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, reason);
            statement.setString(5, DECIDER.toString());
            statement.executeUpdate();
        }
    }
}
