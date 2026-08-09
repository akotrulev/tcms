package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * {@code V44} — the backfill that gives every stored {@code release_sort_key} its precedence flag.
 *
 * <h2>Why this needs a real database and could not be a unit test</h2>
 *
 * <p>{@link ReleaseSortKeyTest} covers what the application <em>writes</em>. Three things about
 * {@code V44} are not statements about Java at all:
 *
 * <ol>
 *   <li><strong>The ordering is Postgres's, not Java's.</strong> The whole reason the flag is a
 *       digit is that this column is compared under the database's collation, where the obvious
 *       {@code '~'} sentinel is a no-op — {@link Collation} measures both against the live engine
 *       rather than trusting the migration's comment about them.</li>
 *   <li><strong>The three tables are under FORCE ROW LEVEL SECURITY, which covers the owner the
 *       migration runs as.</strong> An {@code UPDATE} with no {@code app.current_company} set
 *       matches zero rows and reports success, so the failure mode of getting this wrong is a
 *       migration that runs clean and changes nothing. {@link Isolation} asserts both halves.</li>
 *   <li><strong>The migration is the thing under test, so the test executes the migration file
 *       itself</strong> — read off the classpath, not retyped here. A copy of the {@code UPDATE}
 *       in this class would be a second implementation that could pass while the shipped one was
 *       wrong, which is the defect shape the file's own §2 refuses for the parser.</li>
 * </ol>
 *
 * <p>Re-executing it is legitimate precisely because it is idempotent, which is itself one of the
 * claims under test ({@link Rewrite#runningItTwiceChangesNothing()}).
 *
 * <p>Ids are unique to this class — {@code f5…}. Hex, per {@code ExecutionFactRefreshTest}'s header.
 */
@DisplayName("V44 — backfilling the release sort key's precedence flag")
class ReleaseSortKeyBackfillTest extends IntegrationTestBase {

    private static final String MIGRATION =
            "db/migration/V44__release_sort_key_precedence_flag.sql";

    private static final UUID ACME = UUID.fromString("f5000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("f5000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("f5001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("f5001000-0000-4000-8000-000000000002");

    /**
     * Release version to the key the application stored <em>before</em> V44, which is what the rows
     * on a live instance actually hold. The last three are the shapes the backfill must NOT touch.
     */
    private static final Map<String, String> OLD_KEYS = new LinkedHashMap<>();

    static {
        OLD_KEYS.put("4.3.0", "00004.00003.00000");
        OLD_KEYS.put("4.3.0-rc.1", "00004.00003.00000-rc.1");
        OLD_KEYS.put("4.3.0-rc.2", "00004.00003.00000-rc.2");
        OLD_KEYS.put("4.3.1", "00004.00003.00001");
        OLD_KEYS.put("4.3.0+build.7", "00004.00003.00000+build.7");
        OLD_KEYS.put("2026-W31", "02026.00000.00000-W31");
        // The text fallback, stored raw and which must stay raw.
        OLD_KEYS.put("nightly", "nightly");
        // Falls back to itself because " hotfix" is neither '-' nor '+' — and it LOOKS padded. An
        // unanchored prefix match would rewrite it into a key the application would never write.
        OLD_KEYS.put("12345.12345.12345 hotfix", "12345.12345.12345 hotfix");
        // Six digits: the major group caps at five, so this never parsed either.
        OLD_KEYS.put("123456.0.0", "123456.0.0");
    }

    @Autowired
    private CompanyRepository companies;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        company(ACME, "f5-acme", "Acme", now);
        company(UMBRA, "f5-umbra", "Umbra", now);

        // execution_fact is a denormalized log with no foreign keys at all (V1), so a fixture row
        // needs no project, case or run to exist — which is what keeps this class cheap while
        // still exercising one of the three tables the migration names for real.
        try (Connection acme = appConnection(ACME)) {
            clearFacts(acme);
            OLD_KEYS.forEach((release, key) -> fact(acme, ACME, ACME_PROJECT, release, key));
        }
        try (Connection umbra = appConnection(UMBRA)) {
            clearFacts(umbra);
            fact(umbra, UMBRA, UMBRA_PROJECT, "4.3.0-rc.1", "00004.00003.00000-rc.1");
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("What the rewrite produces")
    class Rewrite {

        @Test
        @DisplayName("every backfilled row becomes exactly what the application would write today")
        void everyRowMatchesTheApplication() throws SQLException {
            runMigration();

            Map<String, String> stored = storedKeys(ACME);

            // The assertion is against ReleaseSortKey.of() rather than against literals, so the
            // migration and the writer cannot drift apart without this going red. That equivalence
            // is the entire correctness claim: a row the backfill produces must be
            // indistinguishable from one written by a run created after the deploy.
            assertThat(stored).allSatisfy((release, key) ->
                    assertThat(key).isEqualTo(ReleaseSortKey.of(release)));
            assertThat(stored).hasSize(OLD_KEYS.size());
        }

        @Test
        @DisplayName("the text fallback is left exactly as it was, including when it looks padded")
        void theTextFallbackIsUntouched() throws SQLException {
            runMigration();

            Map<String, String> stored = storedKeys(ACME);
            assertThat(stored).containsEntry("nightly", "nightly");
            // The trap. '12345.12345.12345 hotfix' begins with a padded triple and is NOT a version;
            // an unanchored predicate would append a flag mid-string and corrupt a value that was
            // correct before the migration ran.
            assertThat(stored)
                    .containsEntry("12345.12345.12345 hotfix", "12345.12345.12345 hotfix")
                    .containsEntry("123456.0.0", "123456.0.0");
        }

        @Test
        @DisplayName("running it twice changes nothing — the new format matches neither arm")
        void runningItTwiceChangesNothing() throws SQLException {
            runMigration();
            Map<String, String> afterFirst = storedKeys(ACME);

            runMigration();

            // Not a nicety: this test executes the shipped migration a second time on a database
            // Flyway has already migrated, and every other assertion here depends on that being
            // harmless. A predicate matching its own output would append a flag per run.
            assertThat(storedKeys(ACME)).isEqualTo(afterFirst);
        }

        @Test
        @DisplayName("the backfill names every table that has the column")
        void everyTableWithTheColumnIsCovered() throws SQLException {
            // Computed rather than read, per the working agreement: the risk is a fourth table
            // gaining release_sort_key later and this migration — and the format decision behind
            // it — silently not applying to it. Three tables today.
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

            String migration = migrationText();
            assertThat(withColumn)
                    .containsExactlyInAnyOrder("execution_fact", "test_execution", "test_run");
            assertThat(withColumn).allSatisfy(table ->
                    assertThat(migration).contains("'" + table + "'"));
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The ordering, measured against the engine that does the ordering")
    class Collation {

        @Test
        @DisplayName("THE §151 invariant: a release candidate sorts BEFORE its release in SQL")
        void theCandidateSortsBeforeItsRelease() throws SQLException {
            runMigration();

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
            runMigration();

            // The fourth component must not have pushed a version above a named build: 'nightly'
            // and the dated builds are the block that has to stay on the far side.
            assertThat(releasesInKeyOrder(ACME)).containsSubsequence("4.3.1", "nightly");
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("Row-level security — the reason the migration loops over companies")
    class Isolation {

        @Test
        @DisplayName("every company's rows are migrated, not just the first")
        void theLoopReachesEveryCompany() throws SQLException {
            runMigration();

            // Umbra's single row is the whole point: a migration that set the context once, or not
            // at all, leaves other companies holding the old format for ever — and reports success.
            assertThat(storedKeys(UMBRA))
                    .containsExactly(Map.entry("4.3.0-rc.1", "00004.00003.00000.0-rc.1"));
        }

        @Test
        @DisplayName("the same UPDATE with no company context matches ZERO rows and succeeds")
        void withoutContextTheUpdateIsASilentNoOp() throws SQLException {
            // The failure this migration's §3 is written against, reproduced. FORCE ROW LEVEL
            // SECURITY covers the table owner Flyway connects as, so the naive spelling of this
            // backfill is not an error — it is a clean run that changes nothing, on every table,
            // for every company.
            try (Connection owner = migrationOwnerConnection();
                 PreparedStatement statement = owner.prepareStatement(
                         "UPDATE execution_fact SET release_sort_key = release_sort_key")) {
                assertThat(statement.executeUpdate()).isZero();
            }

            // And the rows are still there to be seen with a context, so the zero above is
            // isolation rather than an empty fixture.
            assertThat(storedKeys(ACME)).hasSize(OLD_KEYS.size());
        }
    }

    // =====================================================================

    /**
     * Executes the shipped migration file against the owner role — the same role and the same text
     * Flyway runs, so this test cannot pass on a migration the deployment would not perform.
     */
    private void runMigration() throws SQLException {
        try (Connection owner = migrationOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute(migrationText());
        }
    }

    private String migrationText() {
        // Off the classpath rather than off a relative path: this must be the same bytes Flyway
        // reads, and it must not depend on the test's working directory.
        try (var source = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            if (source == null) {
                throw new IllegalStateException("V44 is the thing under test and it is not on the "
                        + "classpath: " + MIGRATION);
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("V44 is the thing under test and it was not readable", e);
        }
    }

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
