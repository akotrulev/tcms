package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code release_sort_key}, measured against the engine that actually does the ordering.
 *
 * <h2>Why this needs a real database and could not be a unit test</h2>
 *
 * <p>{@link ReleaseSortKeyTest} covers what the application <em>writes</em>, which it can only
 * check against Java's {@code String} comparison. Two things about the column are not statements
 * about Java at all:
 *
 * <ol>
 *   <li><strong>The ordering is Postgres's.</strong> The whole reason the precedence flag is a
 *       digit and not the conventional trailing {@code '~'} is that this column is compared under
 *       the deployment's collation, where the sentinel is a no-op. {@link Collation} measures both
 *       spellings against the live engine rather than trusting a comment about them.</li>
 *   <li><strong>The three tables that carry the column are under FORCE ROW LEVEL SECURITY, which
 *       covers the owner a migration runs as.</strong> A bulk {@code UPDATE} with no
 *       {@code app.current_company} set matches zero rows and reports success, so anything that
 *       ever rewrites these keys in bulk has to open a company context per company or it will run
 *       clean and change nothing. {@link Isolation} pins that.</li>
 * </ol>
 *
 * <p>Rows are seeded with {@link ReleaseSortKey#of} rather than with literals, so the fixture and
 * the writer cannot drift apart.
 *
 * <p>Ids are unique to this class — {@code f5…}. Hex, per {@code ExecutionFactRefreshTest}'s header.
 */
@DisplayName("release_sort_key — the ordering, on the database that performs it")
class ReleaseSortKeyOrderingTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("f5000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("f5000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("f5001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("f5001000-0000-4000-8000-000000000002");

    /**
     * The release strings the ordering has to hold over. The last three are the shapes that do not
     * parse as a version and must stay on the far side of every padded numeric.
     */
    private static final List<String> RELEASES = List.of(
            "4.3.0",
            "4.3.0-rc.1",
            "4.3.0-rc.2",
            "4.3.1",
            "4.3.0+build.7",
            "2026-W31",
            "nightly",
            // Begins with a padded triple and is NOT a version — it must not be treated as one.
            "12345.12345.12345 hotfix",
            // Six digits: the major group caps at five, so this never parses either.
            "123456.0.0");

    @Autowired
    private CompanyRepository companies;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        company(ACME, "f5-acme", "Acme", now);
        company(UMBRA, "f5-umbra", "Umbra", now);

        // execution_fact is a denormalized log with no foreign keys at all, so a fixture row needs
        // no project, case or run to exist — which is what keeps this class cheap while still
        // exercising one of the three tables that carry the column for real.
        try (Connection acme = appConnection(ACME)) {
            clearFacts(acme);
            RELEASES.forEach(release ->
                    fact(acme, ACME, ACME_PROJECT, release, ReleaseSortKey.of(release)));
        }
        try (Connection umbra = appConnection(UMBRA)) {
            clearFacts(umbra);
            fact(umbra, UMBRA, UMBRA_PROJECT, "4.3.0-rc.1", ReleaseSortKey.of("4.3.0-rc.1"));
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The ordering, measured against the engine that does the ordering")
    class Collation {

        @Test
        @DisplayName("THE §151 invariant: a release candidate sorts BEFORE its release in SQL")
        void theCandidateSortsBeforeItsRelease() throws SQLException {
            // Ordered by Postgres under the deployment's own collation, which is the only observer
            // that counts — ReleaseSortKeyTest can only assert Java's String comparison.
            assertThat(releasesInKeyOrder(ACME))
                    .containsSubsequence("4.3.0-rc.1", "4.3.0-rc.2", "4.3.0", "4.3.1");
        }

        @Test
        @DisplayName("a '~' sentinel would NOT have fixed this, under the collation in use")
        void theObviousFixWouldHaveBeenANoOp() throws SQLException {
            // Pinning the measurement the format decision rests on. Debian's trailing '~' is the
            // standard answer to this exact problem and it does nothing here, because the libc
            // en_US collation weights punctuation below letters. It passes under C and under ICU,
            // so checking it anywhere but on the real database would have said the opposite.
            //
            // If this ever goes red, the cluster's collation changed and the SECOND assertion is
            // the one to trust: the digit flag is what this schema relies on either way.
            assertThat(compares("'00004.00003.00000-rc.1' < '00004.00003.00000~'")).isFalse();
            assertThat(compares("'00004.00003.00000.0-rc.1' < '00004.00003.00000.1'")).isTrue();
        }

        @Test
        @DisplayName("padded numerics still do not interleave with the text fallback")
        void numericsStillSortAheadOfText() throws SQLException {
            // The fourth component must not have pushed a version above a named build: 'nightly'
            // and the dated builds are the block that has to stay on the far side.
            assertThat(releasesInKeyOrder(ACME)).containsSubsequence("4.3.1", "nightly");
        }

        @Test
        @DisplayName("a value that only looks like a version keeps its raw key")
        void theTextFallbackIsStoredRaw() throws SQLException {
            Map<String, String> stored = storedKeys(ACME);
            assertThat(stored)
                    .containsEntry("nightly", "nightly")
                    .containsEntry("12345.12345.12345 hotfix", "12345.12345.12345 hotfix")
                    .containsEntry("123456.0.0", "123456.0.0");
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("Row-level security — why a bulk rewrite has to open a company context")
    class Isolation {

        @Test
        @DisplayName("a bulk UPDATE with no company context matches ZERO rows and succeeds")
        void withoutContextTheUpdateIsASilentNoOp() throws SQLException {
            // FORCE ROW LEVEL SECURITY covers the table owner Flyway connects as, so the naive
            // spelling of any future rewrite of these keys is not an error — it is a clean run
            // that changes nothing, on every table, for every company.
            try (Connection owner = migrationOwnerConnection();
                 PreparedStatement statement = owner.prepareStatement(
                         "UPDATE execution_fact SET release_sort_key = release_sort_key")) {
                assertThat(statement.executeUpdate()).isZero();
            }

            // And the rows are still there to be seen with a context, so the zero above is
            // isolation rather than an empty fixture.
            assertThat(storedKeys(ACME)).hasSize(RELEASES.size());
        }

        @Test
        @DisplayName("each company sees only its own keys")
        void eachCompanySeesOnlyItsOwn() throws SQLException {
            assertThat(storedKeys(UMBRA))
                    .containsExactly(Map.entry("4.3.0-rc.1", "00004.00003.00000.0-rc.1"));
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("Coverage")
    class Coverage {

        @Test
        @DisplayName("exactly three tables carry the column")
        void threeTablesCarryTheColumn() throws SQLException {
            // Computed rather than read, per the working agreement: the risk is a fourth table
            // gaining release_sort_key later without the writer that fills it correctly.
            List<String> withColumn = new ArrayList<>();
            try (Connection connection = migrationOwnerConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT table_name FROM information_schema.columns
                          WHERE table_schema = current_schema()
                            AND column_name = 'release_sort_key'
                          ORDER BY table_name
                         """);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    withColumn.add(rows.getString(1));
                }
            }

            assertThat(withColumn)
                    .containsExactlyInAnyOrder("execution_fact", "test_execution", "test_run");
        }
    }

    // =====================================================================

    private boolean compares(String expression) throws SQLException {
        try (Connection connection = migrationOwnerConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + expression)) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private Map<String, String> storedKeys(UUID companyId) throws SQLException {
        Map<String, String> keys = new LinkedHashMap<>();
        try (Connection connection = appConnection(companyId);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT release_version, release_sort_key FROM execution_fact "
                             + "ORDER BY release_version");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                keys.put(rows.getString(1), rows.getString(2));
            }
        }
        return keys;
    }

    private List<String> releasesInKeyOrder(UUID companyId) throws SQLException {
        List<String> releases = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT release_version FROM execution_fact ORDER BY release_sort_key");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                releases.add(rows.getString(1));
            }
        }
        return releases;
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void clearFacts(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM execution_fact");
        }
    }

    private void fact(Connection connection, UUID companyId, UUID projectId, String release,
            String sortKey) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO execution_fact
                    (execution_id, company_id, project_id, case_id, case_version_id, suite_id,
                     suite_path, run_id, configuration_id, release_version, release_sort_key,
                     status_kind, counts_as_pass, automation_status, source, executed_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), gen_random_uuid(),
                        gen_random_uuid(), gen_random_uuid(), 's1'::ltree, gen_random_uuid(),
                        gen_random_uuid(), ?, ?, 'PASSED', true, 'MANUAL', 'MANUAL', now())
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, release);
            statement.setString(4, sortKey);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed a fact for " + release, e);
        }
    }
}
