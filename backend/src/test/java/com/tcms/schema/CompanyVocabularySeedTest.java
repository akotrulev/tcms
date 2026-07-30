package com.tcms.schema;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code tcms_seed_company_defaults} and {@code tcms_seed_project_defaults} — the
 * per-company and per-project vocabularies, which are functions rather than migration
 * INSERTs for two independent reasons, both worth keeping in mind before someone
 * "simplifies" them back into V3:
 *
 * <ol>
 *   <li><strong>There are no companies at migration time.</strong> Each client gets its
 *       own COPY of the seeded vocabulary at provisioning time, because one client renaming
 *       "Compatibility" must not rename it for another (plan §6d, §6s).</li>
 *   <li><strong>Every table involved has {@code FORCE ROW LEVEL SECURITY}</strong>, which
 *       covers the migration role too. A migration seeding these would have to set
 *       {@code app.current_company} to a placeholder company — encoding a fixture as
 *       schema.</li>
 * </ol>
 *
 * <p>So the vocabulary content is versioned in V2 and applied by {@code provision-company}.
 * This class is what makes that split safe: the CLI does not exist yet, and when it is
 * written it will call exactly these two functions.
 */
@DisplayName("Per-company vocabulary seeding — the V2 provisioning functions")
class CompanyVocabularySeedTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("5eed0000-0000-4000-8000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("5eed0000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("5eed0000-0000-4000-8000-000000000003");

    private static Connection acme;

    /**
     * {@code @BeforeEach} rather than {@code @BeforeAll} — the Spring context, and so
     * Flyway and the {@code tcms_app} role its test-only callback creates, are built when
     * the test instance is post-processed, after a static {@code @BeforeAll} would already
     * have tried to connect.
     */
    @BeforeEach
    void seed() throws SQLException {
        if (acme != null) {
            return;
        }
        acme = appConnection(ACME);
        execute(acme, "INSERT INTO company (id, slug, display_name) VALUES ('%s', 'seedacme', 'Acme')"
                .formatted(ACME));
        execute(acme, "INSERT INTO company (id, slug, display_name) VALUES ('%s', 'seedglobex', 'Globex')"
                .formatted(GLOBEX));
        execute(acme, """
                INSERT INTO project (id, company_id, key, name) VALUES ('%s', '%s', 'OLB', 'Banking')
                """.formatted(ACME_PROJECT, ACME));
        execute(acme, "SELECT tcms_seed_company_defaults('%s')".formatted(ACME));
        execute(acme, "SELECT tcms_seed_project_defaults('%s', '%s')".formatted(ACME, ACME_PROJECT));
    }

    @AfterAll
    static void close() throws SQLException {
        if (acme != null) {
            acme.close();
            acme = null;
        }
    }

    @Test
    @DisplayName("the three company-level dimensions are seeded, locked, and correctly sized")
    void companyDimensionsAreSeeded() throws SQLException {
        assertThat(rows(acme, """
                SELECT d.key || ' ' || d.cardinality || ' locked=' || d.locked
                       || ' hierarchical=' || d.hierarchical || ' values=' || count(v.id)
                  FROM classification_dimension d
                  LEFT JOIN classification_value v ON v.dimension_id = d.id
                 WHERE d.project_id IS NULL
                 GROUP BY d.key, d.cardinality, d.locked, d.hierarchical, d.position
                 ORDER BY d.position
                """))
                .as("""
                        Test Type and Test Level are SEPARATE SINGLE axes on purpose (plan §6d): an \
                        API test is almost always functional, and a non-functional API test is a \
                        performance test at the API layer. Collapsing them into one dimension forces \
                        a false choice and destroys the type × level coverage matrix — the specific \
                        modelling mistake this vocabulary exists to avoid.""")
                .containsExactly(
                        "test_type SINGLE locked=true hierarchical=false values=8",
                        "test_level SINGLE locked=true hierarchical=false values=7",
                        "execution_phase MULTI locked=true hierarchical=false values=5");
    }

    @Test
    @DisplayName("Feature is the one project-scoped dimension, hierarchical, unlocked, and empty")
    void featureIsProjectScopedAndUnseeded() throws SQLException {
        assertThat(rows(acme, """
                SELECT d.key || ' ' || d.cardinality || ' locked=' || d.locked
                       || ' hierarchical=' || d.hierarchical || ' values=' || count(v.id)
                  FROM classification_dimension d
                  LEFT JOIN classification_value v ON v.dimension_id = d.id
                 WHERE d.project_id = '%s'
                 GROUP BY d.key, d.cardinality, d.locked, d.hierarchical
                """.formatted(ACME_PROJECT)))
                .as("""
                        Feature is per-project, hierarchical and editable, and seeded EMPTY — \
                        inventing a client's feature taxonomy for them is how a controlled \
                        vocabulary gets worked around with naming conventions in the title field.""")
                .containsExactly("feature MULTI locked=false hierarchical=true values=0");
    }

    @Test
    @DisplayName("ltree paths are slugged, so a value name with punctuation is still storable")
    void ltreePathsAreSlugged() throws SQLException {
        // "UI / E2E" is not a legal ltree label — spaces and slashes are rejected — and it is
        // in the seeded vocabulary, so this is not a hypothetical edge case. Slugging lives in
        // the schema (tcms_ltree_label) rather than at the call site, so the path a value gets
        // is a property of the schema and not of whoever seeded it.
        assertThat(rows(acme, """
                SELECT v.name || ' -> ' || v.path::text FROM classification_value v
                  JOIN classification_dimension d ON d.id = v.dimension_id
                 WHERE d.key = 'test_level' AND v.name = 'UI / E2E'
                """))
                .containsExactly("UI / E2E -> ui_e2e");
    }

    @Test
    @DisplayName("the six default execution statuses, with kind and counts_as_pass separated")
    void projectStatusesAreSeeded() throws SQLException {
        assertThat(rows(acme, """
                SELECT name || ' ' || kind || ' final=' || is_final || ' pass=' || counts_as_pass
                  FROM case_status WHERE project_id = '%s' ORDER BY position
                """.formatted(ACME_PROJECT)))
                .as("""
                        `kind` is what lets reporting aggregate a project's CUSTOM statuses without \
                        knowing their names, and counts_as_pass is separate from it because a project \
                        may add "Passed with comment" — which is PASSED-kind and still needs its own \
                        pass semantics. Retest and Untested are the two non-final ones.""")
                .containsExactly(
                        "Passed PASSED final=true pass=true",
                        "Failed FAILED final=true pass=false",
                        "Blocked BLOCKED final=true pass=false",
                        "Skipped SKIPPED final=true pass=false",
                        "Retest RETEST final=false pass=false",
                        "Untested UNTESTED final=false pass=false");
    }

    @Test
    @DisplayName("every project gets exactly one default configuration")
    void projectHasOneDefaultConfiguration() throws SQLException {
        assertThat(rows(acme,
                "SELECT name FROM configuration WHERE project_id = '%s' AND is_default"
                        .formatted(ACME_PROJECT)))
                .as("""
                        Runs without an explicit environment point at this rather than carrying NULL, \
                        which is what keeps test_run.configuration_id NOT NULL through the rollup read \
                        models — suite_run_summary and plan_snapshot key on it, and a nullable PK \
                        column is not expressible.""")
                .containsExactly("Default");
    }

    @Test
    @DisplayName("both functions are idempotent — re-provisioning adds nothing")
    void seedingTwiceChangesNothing() throws SQLException {
        long dimensions = count(acme, "classification_dimension");
        long values = count(acme, "classification_value");
        long statuses = count(acme, "case_status");
        long configurations = count(acme, "configuration");

        execute(acme, "SELECT tcms_seed_company_defaults('%s')".formatted(ACME));
        execute(acme, "SELECT tcms_seed_project_defaults('%s', '%s')".formatted(ACME, ACME_PROJECT));

        // Idempotence is not cosmetic here: it is what lets a company provisioned before a
        // vocabulary was extended be topped up by re-running provision-company, instead of
        // needing a bespoke backfill migration per vocabulary change.
        assertThat(count(acme, "classification_dimension")).isEqualTo(dimensions);
        assertThat(count(acme, "classification_value")).isEqualTo(values);
        assertThat(count(acme, "case_status")).isEqualTo(statuses);
        assertThat(count(acme, "configuration")).isEqualTo(configurations);
    }

    @Test
    @DisplayName("seeding refuses to run with no company context")
    void seedingWithoutContextIsRefused() throws SQLException {
        // Without the guard the INSERTs would be silently discarded by RLS — the WITH CHECK
        // the policies inherit from USING would reject them, and a caller that swallowed the
        // error would end up with a company whose vocabulary is simply missing. Failing at
        // the top with a legible message beats a per-row constraint violation.
        try (Connection noContext = appConnection();
             Statement statement = noContext.createStatement()) {
            assertThatThrownBy(() ->
                    statement.execute("SELECT tcms_seed_company_defaults('%s')".formatted(GLOBEX)))
                    .hasMessageContaining("no company context is active");
        }
    }

    @Test
    @DisplayName("seeding refuses a company other than the active one")
    void seedingAnotherCompanyIsRefused() throws SQLException {
        // The realistic slip: a provisioning loop that opens the context once and then
        // iterates companies. Every row it wrote would land under the wrong company_id or be
        // rejected one policy check at a time; here it stops on the first call.
        try (Statement statement = acme.createStatement()) {
            assertThatThrownBy(() ->
                    statement.execute("SELECT tcms_seed_company_defaults('%s')".formatted(GLOBEX)))
                    .hasMessageContaining("RLS would hide every row written");
        }
    }

    @Test
    @DisplayName("each company gets its own copy — renaming a value in one leaves the other alone")
    void vocabulariesAreCopiedPerCompany() throws SQLException {
        try (Connection globex = appConnection(GLOBEX)) {
            execute(globex, "SELECT tcms_seed_company_defaults('%s')".formatted(GLOBEX));

            execute(globex, """
                    UPDATE classification_value SET name = 'Cross-browser'
                     WHERE name = 'Compatibility'
                    """);

            assertThat(rows(globex, """
                    SELECT name FROM classification_value
                     WHERE name IN ('Compatibility', 'Cross-browser') ORDER BY name
                    """))
                    .as("Globex sees only its own renamed value")
                    .containsExactly("Cross-browser");
        }

        assertThat(rows(acme, """
                SELECT name FROM classification_value
                 WHERE name IN ('Compatibility', 'Cross-browser') ORDER BY name
                """))
                .as("""
                        This is the whole reason the vocabulary is company-level rather than \
                        instance-level. Shared rows would have made one client's rename everybody's, \
                        and the UPDATE above carries no company predicate at all — RLS is what scoped \
                        it, which is exactly the property that must not silently regress.""")
                .containsExactly("Compatibility");
    }

    @Test
    @DisplayName("a project in another company cannot be seeded, even with valid ids")
    void projectSeedingRefusesAForeignProject() throws SQLException {
        try (Connection globex = appConnection(GLOBEX);
             Statement statement = globex.createStatement()) {
            assertThatThrownBy(() -> statement.execute(
                    "SELECT tcms_seed_project_defaults('%s', '%s')".formatted(GLOBEX, ACME_PROJECT)))
                    .as("under RLS an unreachable project is indistinguishable from a missing one, "
                            + "which is the behaviour the API surface needs too (§3 lesson 13)")
                    .hasMessageContaining("is not visible in company");
        }
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
