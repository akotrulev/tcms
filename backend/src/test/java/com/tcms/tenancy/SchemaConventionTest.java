package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The schema test from ERD §0.1 — the mechanism that makes the {@code company_id} rule
 * survive a year of feature work.
 *
 * <p>It exists because <strong>the rule is not visible by reading the schema top to
 * bottom.</strong> The first cross-check of V1 found five join tables written without
 * {@code company_id} ({@code case_classification}, {@code case_tag},
 * {@code case_automation_tag}, {@code case_condition_optout}, {@code work_item_link})
 * and four version tables absent from the RLS list ({@code suite_version},
 * {@code test_plan_version}, {@code doc_page_version},
 * {@code derived_item_annotation}) — none of them noticed by careful reading, all of
 * them found by scripting the comparison. Join tables and version tables are exactly
 * where the column is forgotten, and both are severe: a join table without it is
 * unfilterable <em>and</em> un-RLS-able, and a version table holds a complete snapshot
 * of a client's content.
 *
 * <p>So a new table fails this build until it complies. That is the whole point: the
 * feature can slip indefinitely, the column cannot.
 */
@DisplayName("Schema conventions — the company_id rule (ERD §0.1)")
class SchemaConventionTest extends IntegrationTestBase {

    /**
     * Deliberate instance-level tables (ERD §0.1). <strong>Nothing may be added here
     * without a decision</strong> — every addition is a table that no longer has a
     * client boundary.
     */
    private static final Set<String> INSTANCE_LEVEL_TABLES = Set.of(
            "company",                    // it IS the company
            "app_user",                   // one identity may belong to several companies
            "permission",                 // the catalogue is instance-wide reference data
            "role",                       // built-in roles seeded once; no custom roles in v1
            "role_permission",            // join between two instance-level tables
            "instance_role_assignment",   // SYSTEM-scope grants (see the table's own comment)
            // Redeemed BEFORE authentication, so no company context can exist when the
            // token is looked up — and CompanyContext is never populated from request
            // input. Under RLS the row would be permanently unreachable. Decided in V6,
            // recorded in ERD §0.1; the row carries no email or name, and the token is
            // stored only as a digest.
            "membership_invitation",
            "instance_setting",           // infrastructure
            "instance_audit_event",       // operator actions across companies
            "flyway_schema_history");     // infrastructure

    private static Connection catalogConnection;

    @BeforeAll
    static void connect() throws SQLException {
        catalogConnection = superuserConnection();
    }

    @Test
    @DisplayName("every table not on the exception list has company_id NOT NULL")
    void everyCompanyOwnedTableHasCompanyIdNotNull() throws SQLException {
        List<String> missingColumn = new ArrayList<>();
        List<String> nullableColumn = new ArrayList<>();

        for (String table : allTables()) {
            if (INSTANCE_LEVEL_TABLES.contains(table)) {
                continue;
            }
            String nullability = companyIdNullability(table);
            if (nullability == null) {
                missingColumn.add(table);
            } else if (!"NO".equals(nullability)) {
                nullableColumn.add(table);
            }
        }

        assertThat(missingColumn)
                .as("""
                        Tables with no company_id column. Each is unfilterable AND un-RLS-able, so a \
                        query starting from it can cross clients. Add company_id UUID NOT NULL — or, \
                        if the table is genuinely instance-level, add it to INSTANCE_LEVEL_TABLES here \
                        and to ERD §0.1, which requires a decision.""")
                .isEmpty();

        assertThat(nullableColumn)
                .as("""
                        Tables whose company_id is nullable. A NULL discriminator matches no RLS \
                        policy, so those rows are invisible to the application forever — the defect \
                        that split SYSTEM-scope grants out of role_assignment. Make it NOT NULL.""")
                .isEmpty();
    }

    @Test
    @DisplayName("company_id LEADS every index on a company-owned table that includes it")
    void companyIdLeadsCompositeIndexes() throws SQLException {
        // Leading, not merely present: a trailing company_id costs storage and buys
        // nothing at query time, because the planner cannot seek on it (ERD §0.1 point 3).
        Map<String, List<String>> offenders = new LinkedHashMap<>();

        String sql = """
                SELECT t.relname AS table_name,
                       i.relname AS index_name,
                       (SELECT array_agg(a.attname ORDER BY k.ord)
                          FROM unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord)
                          JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                       ) AS columns
                  FROM pg_index ix
                  JOIN pg_class i ON i.oid = ix.indexrelid
                  JOIN pg_class t ON t.oid = ix.indrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'public' AND t.relkind = 'r'
                """;

        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                if (INSTANCE_LEVEL_TABLES.contains(table)) {
                    continue;
                }
                java.sql.Array array = rs.getArray("columns");
                if (array == null) {
                    continue;   // expression-only index (e.g. GIN on a tsvector)
                }
                List<String> columns = List.of((String[]) array.getArray());
                if (columns.contains("company_id") && !"company_id".equals(columns.getFirst())) {
                    offenders.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(rs.getString("index_name") + " " + columns);
                }
            }
        }

        assertThat(offenders)
                .as("""
                        Indexes where company_id is present but not leading. Reorder so company_id \
                        comes first — otherwise every company-scoped query scans more of the index \
                        than it needs, and the column is pure overhead (ERD §0.1 point 3).""")
                .isEmpty();
    }

    @Test
    @DisplayName("every company-owned table has RLS ENABLED and FORCED")
    void everyCompanyOwnedTableForcesRls() throws SQLException {
        // FORCE, not merely ENABLE: a table's owner bypasses plain ENABLE, and in most
        // compose setups the app connects as the schema owner — so ENABLE alone would
        // protect nothing. Silent when wrong.
        List<String> notEnabled = new ArrayList<>();
        List<String> notForced = new ArrayList<>();
        List<String> noPolicy = new ArrayList<>();

        Set<String> tablesWithPolicy = new LinkedHashSet<>();
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT tablename FROM pg_policies WHERE schemaname = 'public'")) {
            while (rs.next()) {
                tablesWithPolicy.add(rs.getString(1));
            }
        }

        String sql = """
                SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND c.relkind = 'r'
                """;
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (INSTANCE_LEVEL_TABLES.contains(table)) {
                    continue;
                }
                if (!rs.getBoolean(2)) {
                    notEnabled.add(table);
                }
                if (!rs.getBoolean(3)) {
                    notForced.add(table);
                }
                if (!tablesWithPolicy.contains(table)) {
                    noPolicy.add(table);
                }
            }
        }

        assertThat(notEnabled).as("Company-owned tables without ROW LEVEL SECURITY enabled").isEmpty();
        assertThat(notForced)
                .as("""
                        Company-owned tables with ENABLE but not FORCE. The owner bypasses plain \
                        ENABLE, so these are unprotected whenever the app connects as the schema \
                        owner — which is the default in most compose setups.""")
                .isEmpty();
        assertThat(noPolicy).as("Company-owned tables with RLS on but no policy — these deny everything").isEmpty();
    }

    @Test
    @DisplayName("every isolation policy carries the SAME predicate — none has been widened")
    void everyPolicyHasTheExactIsolationPredicate() throws SQLException {
        // "Has a policy" is not "is protected". USING (true) is a policy. So is a
        // predicate over the wrong column, or one that ORs in an escape hatch to make a
        // failing query pass. Every check above would still be green.
        //
        // The predicate is pinned literally, including the nullif() V4 added: both
        // spellings of "no company" — never set, and reset to '' on a recycled pooled
        // connection — must collapse to NULL, and `company_id = NULL` is never true.
        // Dropping the nullif() reintroduces "invalid input syntax for type uuid" on
        // every warm connection; dropping the equality reintroduces the leak.
        //
        // Same family as the exception-list check: derive the invariant, do not read for it.
        String expected =
                "(company_id = (NULLIF(current_setting('app.current_company'::text, true), ''::text))::uuid)";

        Map<String, String> divergent = new LinkedHashMap<>();
        int checked = 0;
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT tablename, policyname, qual, with_check
                       FROM pg_policies
                      WHERE schemaname = 'public'
                      ORDER BY tablename
                     """)) {
            while (rs.next()) {
                checked++;
                String table = rs.getString(1);
                if (!"company_isolation".equals(rs.getString(2))) {
                    divergent.put(table, "unexpected policy name: " + rs.getString(2));
                    continue;
                }
                if (!expected.equals(rs.getString(3))) {
                    divergent.put(table, rs.getString(3));
                }
                // A policy with USING and no explicit WITH CHECK reuses USING as the
                // insert check, so the read boundary is also the write boundary. Stating
                // WITH CHECK explicitly — even as the same predicate — is how someone
                // "fixes" an insert error into WITH CHECK (true) later.
                if (rs.getString(4) != null) {
                    divergent.put(table, "has an explicit WITH CHECK: " + rs.getString(4));
                }
            }
        }

        assertThat(divergent)
                .as("Isolation policies whose predicate is not the one every other table uses")
                .isEmpty();
        assertThat(checked)
                .as("fixture precondition: policies must have been found, or this asserts nothing")
                // 56 from V1, + project_settings (V23), + review_assignment (V25),
                // + report_job (V27), + suite_case and suite_tag_rule (V31). A new company-owned
                // table moves this number, deliberately: the count is what makes the predicate
                // check non-vacuous, and a table added without its policy would otherwise pass by
                // not being looked at.
                .isEqualTo(61);
    }

    @Test
    @DisplayName("no RLS policy is attached to an instance-level table")
    void instanceLevelTablesAreNotRlsProtected() throws SQLException {
        // The reverse direction. An instance-level table that acquires a company_id
        // policy becomes unreadable without company context — which is how the login
        // path breaks silently.
        List<String> unexpected = new ArrayList<>();
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT DISTINCT tablename FROM pg_policies WHERE schemaname = 'public'")) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (INSTANCE_LEVEL_TABLES.contains(table)) {
                    unexpected.add(table);
                }
            }
        }
        assertThat(unexpected)
                .as("Instance-level tables must carry no company_id RLS policy")
                .isEmpty();
    }

    @Test
    @DisplayName("the exception list matches the schema exactly, in both directions")
    void exceptionListHasNoStaleEntries() throws SQLException {
        // Catches a renamed or dropped table leaving a stale exemption behind — a
        // silent hole, since the name simply stops matching anything.
        Set<String> actual = new LinkedHashSet<>(allTables());
        List<String> stale = INSTANCE_LEVEL_TABLES.stream()
                .filter(table -> !actual.contains(table))
                .sorted()
                .toList();

        assertThat(stale)
                .as("Exception-list entries that name no existing table — remove them or fix the name")
                .isEmpty();
    }

    private static List<String> allTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static String companyIdNullability(String table) throws SQLException {
        String sql = """
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ? AND column_name = 'company_id'
                """;
        try (var statement = catalogConnection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
