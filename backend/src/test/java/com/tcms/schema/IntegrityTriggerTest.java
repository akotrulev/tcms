package com.tcms.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two V2 integrity triggers — the constraints that exist as triggers only because no
 * declarative constraint can express them.
 *
 * <ul>
 *   <li><strong>SINGLE-cardinality classification</strong> (ERD §538): the rule is
 *       {@code UNIQUE (case_id, dimension_id) WHERE <the dimension is SINGLE>}, and a
 *       partial-index predicate cannot reach {@code classification_dimension}.</li>
 *   <li><strong>Team hierarchy</strong> (ERD §217): a cycle needs a recursive walk, and a
 *       cross-company parent needs a comparison against another row.</li>
 * </ul>
 *
 * <p>Both guard failures that are quiet rather than loud. A case with two Test Types
 * reports under both and inflates every type × level coverage figure it appears in; a team
 * cycle hangs the first recursive query someone writes against the org chart, months
 * later, in whatever code that happens to be.
 */
@DisplayName("Integrity triggers — classification cardinality and team hierarchy (V2)")
class IntegrityTriggerTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("7a1cce00-0000-4000-8000-000000000001");
    private static final UUID OTHER_COMPANY = UUID.fromString("7a1cce00-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("7a1cce00-0000-4000-8000-000000000003");
    private static final UUID SUITE = UUID.fromString("7a1cce00-0000-4000-8000-000000000004");
    private static final UUID CASE = UUID.fromString("7a1cce00-0000-4000-8000-000000000005");

    private static Connection connection;

    /**
     * {@code @BeforeEach} rather than {@code @BeforeAll} — the Spring context, and so
     * Flyway and the {@code tcms_app} role its test-only callback creates, are built when
     * the test instance is post-processed, after a static {@code @BeforeAll} would already
     * have tried to connect.
     */
    @BeforeEach
    void seed() throws SQLException {
        if (connection != null) {
            return;
        }
        connection = appConnection(COMPANY);
        execute("INSERT INTO company (id, slug, display_name) VALUES ('%s', 'triggerco', 'Trigger Co')"
                .formatted(COMPANY));
        execute("INSERT INTO company (id, slug, display_name) VALUES ('%s', 'otherco', 'Other Co')"
                .formatted(OTHER_COMPANY));
        execute("""
                INSERT INTO project (id, company_id, key, name) VALUES ('%s', '%s', 'TRG', 'Triggers')
                """.formatted(PROJECT, COMPANY));
        execute("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name)
                VALUES ('%s', '%s', '%s', 'TS-1', 1, 'Root')
                """.formatted(SUITE, COMPANY, PROJECT));
        execute("""
                INSERT INTO test_case (id, company_id, project_id, suite_id, key, key_seq, title)
                VALUES ('%s', '%s', '%s', '%s', 'TC-1', 1, 'Refund a settled payment')
                """.formatted(CASE, COMPANY, PROJECT, SUITE));

        // The vocabularies come from the V2 seed function, so this exercises it too: the
        // cardinality flags the trigger reads are the ones provision-company will write.
        execute("SELECT tcms_seed_company_defaults('%s')".formatted(COMPANY));
        execute("SELECT tcms_seed_project_defaults('%s', '%s')".formatted(COMPANY, PROJECT));
    }

    @AfterAll
    static void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Test
    @DisplayName("a SINGLE dimension accepts one value and refuses a second")
    void singleCardinalityIsEnforced() throws SQLException {
        classify("test_type", "Functional");

        assertThatThrownBy(() -> classify("test_type", "Security"))
                .as("""
                        Test Type is SINGLE. A case carrying two of them reports under both and \
                        inflates every type × level coverage figure it appears in — the kind of wrong \
                        number that gets quoted in a release review before anyone questions it.""")
                .hasMessageContaining("already has a value for SINGLE-cardinality dimension");

        assertThat(valuesOn("test_type")).containsExactly("Functional");
    }

    @Test
    @DisplayName("a MULTI dimension accepts several values")
    void multiCardinalityIsAllowed() throws SQLException {
        classify("execution_phase", "Smoke");
        classify("execution_phase", "Regression");

        assertThat(valuesOn("execution_phase"))
                .as("Execution Phase is MULTI — a smoke test is very often also a regression test")
                .containsExactlyInAnyOrder("Smoke", "Regression");
    }

    @Test
    @DisplayName("a dimension_id that disagrees with the value's own dimension is refused")
    void denormalizedDimensionMustAgree() throws SQLException {
        // The hole the denormalization itself opens, and the reason the trigger checks more
        // than cardinality: dimension_id is a COPY of classification_value.dimension_id, so
        // a caller writing a MULTI dimension's id next to a SINGLE dimension's value would
        // skip the check above entirely AND leave a row that lies about which axis it
        // classifies. Every facet count computed from dimension_id would then be wrong.
        UUID levelValue = valueId("test_level", "API");
        UUID phaseDimension = dimensionId("execution_phase");

        assertThatThrownBy(() -> execute("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                VALUES ('%s', '%s', '%s', '%s')
                """.formatted(COMPANY, CASE, levelValue, phaseDimension)))
                .hasMessageContaining("does not match the value's dimension");
    }

    @Test
    @DisplayName("a team cannot be its own parent")
    void selfParentIsRefused() throws SQLException {
        UUID team = insertTeam("Self");

        assertThatThrownBy(() ->
                execute("UPDATE team SET parent_team_id = id WHERE id = '" + team + "'"))
                .hasMessageContaining("cannot be its own parent");
    }

    @Test
    @DisplayName("legal nesting is allowed, and closing the loop is refused")
    void cyclesAreRefused() throws SQLException {
        UUID payments = insertTeam("Payments");
        UUID checkout = insertTeam("Checkout");

        execute("UPDATE team SET parent_team_id = '%s' WHERE id = '%s'".formatted(payments, checkout));
        assertThat(parentOf(checkout)).as("ordinary nesting must still work").isEqualTo(payments);

        assertThatThrownBy(() -> execute(
                "UPDATE team SET parent_team_id = '%s' WHERE id = '%s'".formatted(checkout, payments)))
                .as("""
                        A cycle here breaks the org-chart render and hangs any recursive query written \
                        against it later. The trigger's own walk is depth-capped for the same reason: \
                        if a cycle ever does get in, the transaction that tries to remove it must not \
                        be the one that hangs.""")
                .hasMessageContaining("would create a cycle");
    }

    @Test
    @DisplayName("team nesting may not cross companies")
    void crossCompanyParentIsRefused() throws SQLException {
        UUID parent = insertTeam("Parent In This Company");

        // Written from the OTHER company's context, which is the realistic shape: the caller
        // is legitimately inside their own company and supplies a foreign parent id.
        //
        // Under RLS the parent row is invisible from here, so that is the branch this hits —
        // and asserting the message proves the trigger REFUSED rather than silently treating
        // an unreadable parent as absent, which is what a plain FK would do (the FK is
        // checked past the policy, so it would have accepted the edge). The explicit
        // company comparison in the same function covers the caller who can see both
        // companies at once: the Instance Admin path, where RLS is not the guard.
        try (Connection other = appConnection(OTHER_COMPANY);
             Statement statement = other.createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO team (id, company_id, name, parent_team_id)
                    VALUES ('%s', '%s', 'Foreign child', '%s')
                    """.formatted(UUID.randomUUID(), OTHER_COMPANY, parent)))
                    .as("""
                            One client's team nested inside another's org chart is a leak whose \
                            endpoints are each individually legitimate, so it has to be refused \
                            explicitly rather than assumed impossible.""")
                    .hasMessageContaining("is not visible in this company");
        }
    }

    private static void classify(String dimensionKey, String valueName) throws SQLException {
        execute("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                SELECT '%s', '%s', v.id, v.dimension_id
                  FROM classification_value v
                  JOIN classification_dimension d ON d.id = v.dimension_id
                 WHERE d.key = '%s' AND v.name = '%s'
                """.formatted(COMPANY, CASE, dimensionKey, valueName));
    }

    private static java.util.List<String> valuesOn(String dimensionKey) throws SQLException {
        java.util.List<String> names = new java.util.ArrayList<>();
        String sql = """
                SELECT v.name FROM case_classification cc
                  JOIN classification_value v ON v.id = cc.value_id
                  JOIN classification_dimension d ON d.id = cc.dimension_id
                 WHERE cc.case_id = '%s' AND d.key = '%s' ORDER BY v.name
                """.formatted(CASE, dimensionKey);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static UUID valueId(String dimensionKey, String valueName) throws SQLException {
        return UUID.fromString(queryOne("""
                SELECT v.id::text FROM classification_value v
                  JOIN classification_dimension d ON d.id = v.dimension_id
                 WHERE d.key = '%s' AND v.name = '%s'
                """.formatted(dimensionKey, valueName)));
    }

    private static UUID dimensionId(String key) throws SQLException {
        return UUID.fromString(queryOne(
                "SELECT id::text FROM classification_dimension WHERE key = '" + key + "'"));
    }

    private static UUID insertTeam(String name) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO team (id, company_id, name) VALUES ('%s', '%s', '%s')"
                .formatted(id, COMPANY, name));
        return id;
    }

    private static UUID parentOf(UUID team) throws SQLException {
        return UUID.fromString(
                queryOne("SELECT parent_team_id::text FROM team WHERE id = '" + team + "'"));
    }

    private static void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryOne(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
            return rs.getString(1);
        }
    }
}
