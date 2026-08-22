package com.tcms.provisioning;

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

/**
 * Which tables an export walks, which it deliberately leaves out, and the refusal when a table is
 * neither.
 *
 * <p><strong>Derived from the catalogue, never listed in Java.</strong> ERD §0.1 paid for this
 * lesson twice: reading V1 top to bottom missed five join tables and four version tables, and
 * scripting the comparison found them. A hard-coded list of ~78 names is a list that is wrong the
 * first time somebody adds a table and right-looking forever after. The consequence worth stating:
 * when area C's {@code V68} adds {@code run_schedule} with a {@code company_isolation} policy, it is
 * exported <em>with no change to this class</em>, because it arrives in the walked partition by
 * construction.
 *
 * <p>The exclusion map is the one hand-written half, and it is the {@code SchemaConventionTest}
 * exception-list shape: an entry per table, a reason per entry, swept in both directions by
 * {@code ExportTablesTest}.
 */
final class ExportTables {

    /** {@code company} is walked, filtered on {@code id} rather than {@code company_id}. */
    static final String COMPANY_TABLE = "company";

    /** {@code app_user} is walked member-scoped — see {@link #APP_USER_SQL}. */
    static final String APP_USER_TABLE = "app_user";

    /**
     * {@code app_user} has no {@code company_id} — one identity belongs to several companies
     * (ERD §0.1). Exported member-scoped, because without it the archive is a graph of UUIDs and
     * every {@code created_by} is unreadable, which fails the GDPR half of the goal outright.
     *
     * <p>The inner {@code company_membership} read is <strong>itself RLS-protected</strong>, so this
     * statement is one more thing that returns nothing outside the company context — which is why
     * the sentinel in {@code ExportCompanyService} counts {@code company_membership} and not
     * something incidental.
     */
    static final String APP_USER_SQL = """
            SELECT (to_jsonb(u) - ?::text[])::text AS row FROM app_user u
             WHERE u.id IN (SELECT m.user_id FROM company_membership m WHERE m.company_id = ?)
             ORDER BY u.id""";

    /** Why a table is not exported. The value travels into manifest.json. */
    enum Exclusion {
        /** Instance-wide reference data — the same for every client on the instance. */
        INSTANCE_REFERENCE,
        /** Credential material and nothing else; every fact worth exporting is on a policed row. */
        CREDENTIAL_ONLY,
        /** This instance's own plumbing. Not the client's data at all. */
        INSTANCE_INFRASTRUCTURE,
        /** Real data that spans clients, so no honest single-company slice of it exists. */
        CROSS_COMPANY
    }

    /**
     * Not exported, each with a reason that goes into the manifest.
     *
     * <p>Every table here is one of ERD §0.1's instance-level exceptions. The three §0.1 entries
     * that are <em>absent</em> are absent deliberately and each is exported by a different rule:
     * {@code company} (filtered on {@code id}), {@code app_user} (member-scoped) and
     * {@code membership_invitation} (a {@code company_id} TARGET column with no policy — an
     * outstanding invitation is a fact about this company's onboarding).
     */
    static final Map<String, Exclusion> EXCLUDED = Map.ofEntries(
            // The pre-auth digest index (V36). A digest, a token id and a company id and NOTHING
            // else; every fact worth exporting is on the policed api_token row.
            Map.entry("api_token_lookup", Exclusion.CREDENTIAL_ONLY),
            // state_hash, the PKCE verifier and the nonce. Single-use, 10-minute expiry, empty in
            // steady state, and nothing identifying is in it by construction (ERD §0.1).
            Map.entry("sso_login_attempt", Exclusion.CREDENTIAL_ONLY),
            // The same, one provider family over (V58). Instance-level with no company_id at all,
            // so it could not be attributed to this company even if we wanted it.
            Map.entry("social_login_attempt", Exclusion.CREDENTIAL_ONLY),
            // V58, instance-level by decision: a person's GitHub identity spans every company they
            // belong to, so it is not this company's data to export.
            Map.entry("user_social_identity", Exclusion.CROSS_COMPANY),
            Map.entry("instance_setting", Exclusion.INSTANCE_INFRASTRUCTURE),
            // Infrastructure. The head version is in the manifest instead, which is the one fact a
            // future importer must check before believing any of the rest.
            Map.entry("flyway_schema_history", Exclusion.INSTANCE_INFRASTRUCTURE),
            // Instance-wide reference data. The catalogue is in the roles spec, which is a better
            // artifact than a dump of it. role_assignment — WHICH role this company's people hold —
            // IS exported, and it is the part that carries meaning.
            Map.entry("permission", Exclusion.INSTANCE_REFERENCE),
            Map.entry("role", Exclusion.INSTANCE_REFERENCE),
            Map.entry("role_permission", Exclusion.INSTANCE_REFERENCE),
            // SYSTEM-scope operator grants. Cross-company by definition.
            Map.entry("instance_role_assignment", Exclusion.CROSS_COMPANY),
            // Operator actions ACROSS clients. It carries a nullable company_id, so a filtered
            // export is technically possible and is still refused: the trail spans clients and
            // correlating it is an operator's job, not an offboarded client's. Recorded as a
            // deliberate refusal in the area's handoff rather than left looking like an oversight.
            Map.entry("instance_audit_event", Exclusion.CROSS_COMPANY));

    private final List<String> companyScoped;
    private final List<String> policied;

    private ExportTables(List<String> companyScoped, List<String> policied) {
        this.companyScoped = List.copyOf(companyScoped);
        this.policied = List.copyOf(policied);
    }

    /**
     * Partitions the live catalogue, refusing rather than guessing.
     *
     * <p>Reads {@code pg_tables}, {@code pg_policies} and {@code information_schema.columns} on the
     * connection it is given, so it describes the database in front of it rather than the database
     * this build was written against.
     */
    static ExportTables classify(Connection connection) throws SQLException {
        Set<String> all = query(connection,
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename");
        Set<String> policied = query(connection,
                "SELECT tablename FROM pg_policies "
                        + "WHERE schemaname = 'public' AND policyname = 'company_isolation'");
        Set<String> hasCompanyId = query(connection,
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND column_name = 'company_id'");

        List<String> scoped = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        for (String table : all) {
            if (EXCLUDED.containsKey(table)
                    || COMPANY_TABLE.equals(table)
                    || APP_USER_TABLE.equals(table)) {
                continue;
            }
            // Belt-and-braces: the policy is the authority, and the company_id column alone is
            // enough for the four TARGET-column tables that carry no policy. One form, not two.
            if (policied.contains(table) || hasCompanyId.contains(table)) {
                scoped.add(table);
            } else {
                unclassified.add(table);
            }
        }

        if (!unclassified.isEmpty()) {
            // Thrown at EXPORT time, not only in a test. A deployment running a build older than
            // its database is exactly the case where a test cannot help.
            throw new IllegalStateException(
                    "These tables are neither walked nor excluded, so an export would silently omit "
                            + "them: " + unclassified + ". Classify each in ExportTables — a "
                            + "company-owned table needs a company_isolation policy, and an "
                            + "instance-level one needs an Exclusion with a reason. This is the "
                            + "same refusal SchemaConventionTest makes one direction over.");
        }
        return new ExportTables(scoped, List.copyOf(policied));
    }

    /**
     * The tables read with {@code WHERE company_id = ?}, alphabetical.
     *
     * <p>Excludes {@code company} and {@code app_user}, which are in the archive under their own
     * filters — so this is exactly the set a ground-truth {@code count(*) WHERE company_id = ?} can
     * be taken over, which is what {@code ExportCompanyTest}'s superuser arm iterates.
     */
    List<String> companyScoped() {
        return companyScoped;
    }

    /** Every table that gets a {@code tables/&lt;name&gt;.jsonl} entry, alphabetical. */
    List<String> walked() {
        Set<String> everything = new LinkedHashSet<>(companyScoped);
        everything.add(COMPANY_TABLE);
        everything.add(APP_USER_TABLE);
        return everything.stream().sorted().toList();
    }

    /** The tables carrying a {@code company_isolation} policy, as the live database reports them. */
    List<String> policied() {
        return policied;
    }

    /**
     * The primary-key columns of each table, in key order — the export's {@code ORDER BY}.
     *
     * <p>Determinism is not decoration here: two exports of an unchanged company must differ only
     * in their timestamps, because a diff is how somebody proves an export is complete.
     */
    static Map<String, List<String>> primaryKeyColumns(Connection connection) throws SQLException {
        String sql = """
                SELECT c.relname AS table_name, a.attname AS column_name, k.ord
                  FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
                 WHERE i.indisprimary AND n.nspname = 'public'
                 ORDER BY c.relname, k.ord
                """;
        Map<String, List<String>> keys = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                keys.computeIfAbsent(rs.getString("table_name"), t -> new ArrayList<>())
                        .add(rs.getString("column_name"));
            }
        }
        return keys;
    }

    private static Set<String> query(Connection connection, String sql) throws SQLException {
        Set<String> values = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
