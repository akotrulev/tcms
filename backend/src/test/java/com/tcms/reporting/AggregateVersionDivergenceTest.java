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
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>R8 — version divergence, the last section in the M2.5 reporting plan.</strong>
 *
 * <p>§6e's reason for it is one sentence: <em>"otherwise you are averaging results for two different
 * tests presented as one."</em> {@code execution_fact.case_version_id} is what makes it answerable —
 * {@code V1} calls that column <strong>THE PIN</strong> — and two other shipped surfaces already
 * carry the rule in prose, so a report that blended silently would contradict both.
 *
 * <h2>Three assertions here are written in the direction that fails against the tempting implementation</h2>
 *
 * <ol>
 *   <li>{@link Divergence#theSectionSurvivesTheDefaultPolicy()} — R7's trap, one section later.
 *       Under {@code LATEST}, the default, each case resolves to one row and one row pins one
 *       version, so a divergence section computed over the resolved set reports <strong>no
 *       divergence in any project, ever</strong>. The assertion is that the case <em>is</em> listed
 *       under {@code LATEST}, and the equality across policies is guarded by a non-empty check so it
 *       cannot be three empty maps agreeing (§112's own lesson, and §66's before it).
 *   <li>{@link Divergence#divergenceIsWiderThanTheInconsistencyCallout()} — the predicate is
 *       {@code count(DISTINCT case_version_id) > 1} and says nothing about outcomes.
 *       {@link #STILL_FAILING} failed at both versions: it disagrees with nothing and it is still
 *       two tests averaged as one. Written over the callout's rows instead, it would be absent.
 *   <li>{@link Counted#latestPerConfigBlendsAcrossConfigurations()} — the narrative's closing
 *       sentence is computed from which versions reached the totals, not from the policy's name.
 *       {@link #PER_CONFIG} is the fixture row that separates the two: under
 *       {@code LATEST_PER_CONFIG} two configurations pinned different versions, so the totals blend,
 *       and a check spelled {@code policy == ALL} says they do not.
 * </ol>
 *
 * <h2>The fixture's five cases</h2>
 *
 * <ul>
 *   <li>{@link #REWRITTEN} — failed at v1, passed at v2. Divergent <em>and</em> in the inconsistency
 *       callout, where it reads <em>Across versions</em>.
 *   <li>{@link #STILL_FAILING} — failed at v1, failed at v2. Divergent and in no callout.
 *   <li>{@link #THREE_VERSIONS} — v1 and v2 failed, v3 passed. Three rows, and the most-divided case
 *       is the one the table has to put first.
 *   <li>{@link #STEADY} — two executions, one version. Must be listed nowhere: this section is about
 *       versions and not about retries.
 *   <li>{@link #PER_CONFIG} — v1 on the default configuration, v2 on Firefox. The only case whose
 *       divergence survives {@code LATEST_PER_CONFIG}.
 * </ul>
 *
 * <p>Every first execution is on 2026-05-01 and every later one on 2026-05-02, so a {@code to} of
 * midnight between them selects a slice of history in which nothing had been re-versioned yet —
 * which is how the "we looked and found none" wording gets asserted against real rows.
 *
 * <p>Ids are prefixed {@code c4} and slugs {@code avd-}. Hex, for {@code ExecutionFactRefreshTest}'s
 * reason.
 */
@DisplayName("Aggregate execution report — version divergence")
class AggregateVersionDivergenceTest extends IntegrationTestBase {

    private static final String DIVERGENCE = "Version divergence";
    private static final String INCONSISTENCY = "Inconsistent results";
    private static final String FLAG = "Cases executed at more than one version";

    private static final UUID ACME = UUID.fromString("c4000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("c4001000-0000-4000-8000-000000000001");

    private static final UUID BILLING = UUID.fromString("c4002000-0000-4000-8000-000000000001");

    /** The second configuration — what makes {@code LATEST_PER_CONFIG} expressible at all. */
    private static final UUID FIREFOX = UUID.fromString("c4006000-0000-4000-8000-000000000001");

    private static final UUID RUN_ONE = UUID.fromString("c4003000-0000-4000-8000-000000000001");
    private static final UUID RUN_TWO = UUID.fromString("c4003000-0000-4000-8000-000000000002");
    private static final UUID RUN_THREE = UUID.fromString("c4003000-0000-4000-8000-000000000003");
    /** On Firefox — the only run on the second configuration. */
    private static final UUID RUN_FIREFOX = UUID.fromString("c4003000-0000-4000-8000-000000000004");

    /** Failed at v1, passed at v2 — divergent, and in the inconsistency callout too. */
    private static final UUID REWRITTEN = UUID.fromString("c4004000-0000-4000-8000-000000000001");
    /** Failed at v1 and again at v2 — divergent, and in no callout. The discriminating row. */
    private static final UUID STILL_FAILING =
            UUID.fromString("c4004000-0000-4000-8000-000000000002");
    /** Three versions, so the ordering has something to order. */
    private static final UUID THREE_VERSIONS =
            UUID.fromString("c4004000-0000-4000-8000-000000000003");
    /** Two executions, one version. Not divergent. */
    private static final UUID STEADY = UUID.fromString("c4004000-0000-4000-8000-000000000004");
    /** v1 on the default configuration, v2 on Firefox. */
    private static final UUID PER_CONFIG = UUID.fromString("c4004000-0000-4000-8000-000000000005");

    /** Distinct to the second, so no {@code execution_id} tiebreak is ever consulted. */
    private static final Instant T1 = Instant.parse("2026-05-01T09:00:01Z");
    private static final Instant T2 = Instant.parse("2026-05-01T09:00:02Z");
    private static final Instant T3 = Instant.parse("2026-05-01T09:00:03Z");
    private static final Instant T4 = Instant.parse("2026-05-01T09:00:04Z");
    private static final Instant T5 = Instant.parse("2026-05-01T09:00:05Z");
    private static final Instant T6 = Instant.parse("2026-05-02T09:00:06Z");
    private static final Instant T7 = Instant.parse("2026-05-02T09:00:07Z");
    private static final Instant T8 = Instant.parse("2026-05-02T09:00:08Z");
    private static final Instant T9 = Instant.parse("2026-05-02T09:00:09Z");
    private static final Instant T10 = Instant.parse("2026-05-02T09:00:10Z");
    private static final Instant T11 = Instant.parse("2026-05-02T09:00:11Z");

    /** Excludes every re-versioned execution, so the whole fixture reads as single-version. */
    private static final Instant BEFORE_ANY_REWRITE = Instant.parse("2026-05-02T00:00:00Z");

    @Autowired
    private AggregateExecutionReport report;

    @Autowired
    private ExecutionFactRefreshService facts;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        if (companies.findById(ACME).isEmpty()) {
            companies.save(Company.provision(ACME, "avd-acme", "Divergence", now));
        }
        try (var ignored = CompanyContext.open(ACME)) {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(
                        Project.create(PROJECT, ACME, "AVER", "Divergence Product", null, now));
            }
        }

        try (Connection connection = appConnection(ACME)) {
            clear(connection);
            seedCompanyDefaults(connection, ACME);
            seedProjectDefaults(connection, ACME, PROJECT);

            configuration(connection, FIREFOX, "Firefox");
            suite(connection, BILLING, "AS-1", 1, "Billing");

            testCase(connection, REWRITTEN, "TC-1", 1);
            testCase(connection, STILL_FAILING, "TC-2", 2);
            testCase(connection, THREE_VERSIONS, "TC-3", 3);
            testCase(connection, STEADY, "TC-4", 4);
            testCase(connection, PER_CONFIG, "TC-5", 5);

            version(connection, REWRITTEN, 2);
            version(connection, STILL_FAILING, 2);
            version(connection, THREE_VERSIONS, 2);
            version(connection, THREE_VERSIONS, 3);
            version(connection, PER_CONFIG, 2);

            run(connection, RUN_ONE, null, "TR-1", 1);
            run(connection, RUN_TWO, null, "TR-2", 2);
            run(connection, RUN_THREE, null, "TR-3", 3);
            run(connection, RUN_FIREFOX, FIREFOX, "TR-4", 4);

            // Day one — every case at v1, so a date-narrowed selection can ask what the section
            // says about a project where nothing has been re-versioned.
            execution(connection, RUN_ONE, REWRITTEN, "Failed", T1, 1);
            execution(connection, RUN_ONE, STILL_FAILING, "Failed", T2, 1);
            execution(connection, RUN_ONE, THREE_VERSIONS, "Failed", T3, 1);
            execution(connection, RUN_ONE, STEADY, "Passed", T4, 1);
            execution(connection, RUN_ONE, PER_CONFIG, "Failed", T5, 1);

            // Day two — the rewrites. uq_execution_run_case is real, so each is a later run.
            execution(connection, RUN_TWO, REWRITTEN, "Passed", T6, 2);
            execution(connection, RUN_TWO, STILL_FAILING, "Failed", T7, 2);
            execution(connection, RUN_TWO, THREE_VERSIONS, "Failed", T8, 2);
            execution(connection, RUN_THREE, THREE_VERSIONS, "Passed", T9, 3);
            execution(connection, RUN_TWO, STEADY, "Passed", T10, 1);
            execution(connection, RUN_FIREFOX, PER_CONFIG, "Passed", T11, 2);
        }

        refresh();
    }

    // =====================================================================

    @Test
    @DisplayName("the fixture holds what every assertion below depends on")
    void theFixtureHolds() {
        // Asserted before anything is asserted over it: a read outside a transaction returns zero
        // rows under RLS and would satisfy most of the "is not listed" checks below (§96).
        assertThat(executions(policy("ALL"))).as("recorded executions").isEqualTo(11L);
        assertThat(executions(whole()))
                .as("cases, under the default policy — one row each, one version each")
                .isEqualTo(5L);
    }

    @Nested
    @DisplayName("which cases are listed — and it is not the callout's question")
    class Divergence {

        /**
         * <strong>The test this class was written for.</strong> {@code LATEST} is the default and
         * collapses every case to one row; one row pins one version; a section computed over the
         * resolved set therefore reports a project that has never re-versioned anything.
         */
        @Test
        @DisplayName("the section survives the DEFAULT policy, which leaves one version per case")
        void theSectionSurvivesTheDefaultPolicy() {
            Map<String, List<String>> counts = divergenceCounts(policy("LATEST"));

            assertThat(counts.keySet())
                    .as("the policy leaves one version per case; this section must not read that")
                    .contains("TC-1 — Case TC-1 · v1", "TC-1 — Case TC-1 · v2");
            assertThat(counts.get("TC-1 — Case TC-1 · v1"))
                    .as("one execution, no pass, one failure — at the version that was rewritten")
                    .isEqualTo(List.of("1", "0", "1"));
            assertThat(counts)
                    .as("and the same answer under every policy, because it ignores all of them")
                    .isEqualTo(divergenceCounts(policy("ALL")))
                    .isEqualTo(divergenceCounts(policy("WORST")));
        }

        /**
         * §6e's harm is the blending, not the disagreement. A case that failed at both versions is
         * still two different tests averaged into one figure.
         */
        @Test
        @DisplayName("a case that failed at both versions is divergent and is in no callout")
        void divergenceIsWiderThanTheInconsistencyCallout() {
            assertThat(divergenceRows(whole()).keySet())
                    .as("two versions, no disagreement — still two tests")
                    .contains("TC-2 — Case TC-2 · v1", "TC-2 — Case TC-2 · v2");
            assertThat(inconsistencyLabels(whole()))
                    .as("nothing passed, so the callout has nothing to say about it")
                    .doesNotContain("TC-2 — Case TC-2");
            assertThat(inconsistencyLabels(whole()))
                    .as("and the callout's Across-versions rows are a strict subset of these")
                    .contains("TC-1 — Case TC-1");
        }

        @Test
        @DisplayName("a case executed twice at one version is not listed")
        void aCaseAtOneVersionIsNotListed() {
            assertThat(divergenceRows(whole()).keySet())
                    .as("this section is about versions, not about retries")
                    .noneSatisfy(key -> assertThat(key).startsWith("TC-4"));
        }

        @Test
        @DisplayName("the most divided case is first, and its versions ascend")
        void theMostDividedCaseIsFirst() {
            List<String> keys = List.copyOf(divergenceRows(whole()).keySet());

            assertThat(keys.subList(0, 3))
                    .as("three versions outrank two, and a divergence is read as a sequence")
                    .containsExactly(
                            "TC-3 — Case TC-3 · v1",
                            "TC-3 — Case TC-3 · v2",
                            "TC-3 — Case TC-3 · v3");
        }

        @Test
        @DisplayName("the case is named by its key and the version by its number, never by a UUID")
        void nothingIsNamedByAUuid() {
            assertThat(divergenceRows(whole()).keySet())
                    .allSatisfy(key -> assertThat(key).doesNotContain("c4004000"));
        }

        @Test
        @DisplayName("no divergence is a sentence saying so, never an empty table")
        void noDivergenceIsSaidOutLoud() {
            ReportModel model = inTransaction(() -> compute(everyRow(
                    "{\"to\":\"" + BEFORE_ANY_REWRITE + "\"}")));

            assertThat(table(model, DIVERGENCE))
                    .as("nothing had been re-versioned yet inside this range")
                    .isNull();
            assertThat(narrative(model, DIVERGENCE))
                    .as("and the section says that rather than rendering blank")
                    .contains("Every case in this selection was executed at one version");
        }

        @Test
        @DisplayName("the metadata flags the count, and prints it even when it is zero")
        void theMetadataFlagsTheCount() {
            assertThat(metadata(whole(), FLAG))
                    .as("REWRITTEN, STILL_FAILING, THREE_VERSIONS and PER_CONFIG")
                    .isEqualTo("4");
            assertThat(metadata(everyRow("{\"to\":\"" + BEFORE_ANY_REWRITE + "\"}"), FLAG))
                    .as("a line that appears only when it has something to say is one a reader "
                            + "cannot tell from a build that does not compute it")
                    .isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("what the policy did with the divergence — the computed column")
    class Counted {

        @Test
        @DisplayName("under the default policy one version per case reaches the totals")
        void theDefaultPolicyCountsOneVersionPerCase() {
            Map<String, List<String>> counted = countedVersions(policy("LATEST"));

            assertThat(counted)
                    .as("every divergent case, one counted version each")
                    .hasSize(4)
                    .allSatisfy((label, versions) -> assertThat(versions).hasSize(1));
            assertThat(counted.get("TC-1 — Case TC-1"))
                    .as("LATEST keeps the newest execution, which pinned the newer version")
                    .containsExactly("v2");
            assertThat(narrative(inTransaction(() -> compute(policy("LATEST"))), DIVERGENCE))
                    .as("so the totals blend nothing, and the section must not claim they do")
                    .contains("one version of each case reaches the totals");
        }

        @Test
        @DisplayName("under ALL every version reaches the totals, which is §6e's sentence literally")
        void allCountsEveryVersion() {
            assertThat(countedVersions(policy("ALL")).get("TC-3 — Case TC-3"))
                    .containsExactly("v1", "v2", "v3");
            assertThat(narrative(inTransaction(() -> compute(policy("ALL"))), DIVERGENCE))
                    .contains("those totals average results across versions");
        }

        /**
         * The narrative's closing sentence is computed from the rows, not from the policy's name —
         * and this is the fixture row that tells the two apart. {@code LATEST_PER_CONFIG} keeps one
         * execution per configuration, and {@link #PER_CONFIG}'s two configurations pinned different
         * versions, so the totals blend after all.
         */
        @Test
        @DisplayName("LATEST_PER_CONFIG blends where two configurations pinned different versions")
        void latestPerConfigBlendsAcrossConfigurations() {
            assertThat(countedVersions(policy("LATEST_PER_CONFIG")).get("TC-5 — Case TC-5"))
                    .as("one execution per configuration, and they are at different versions")
                    .containsExactly("v1", "v2");
            assertThat(countedVersions(policy("LATEST")).get("TC-5 — Case TC-5"))
                    .as("while the default policy keeps only the newer of the two")
                    .containsExactly("v2");
            assertThat(narrative(
                            inTransaction(() -> compute(policy("LATEST_PER_CONFIG"))), DIVERGENCE))
                    .as("a check spelled `policy == ALL` would print the other sentence here")
                    .contains("those totals average results across versions");
        }

        /**
         * {@code WORST} chooses by severity <em>across</em> versions, so a failure recorded against
         * text that has since been rewritten outranks a pass against the current text — which is the
         * third of the section's four consequences, and the least obvious one.
         */
        @Test
        @DisplayName("WORST can count a version whose text has since been rewritten")
        void worstCanCountTheOlderVersion() {
            assertThat(countedVersions(policy("WORST")).get("TC-1 — Case TC-1"))
                    .as("the failure at v1 outranks the pass at v2, and it is v1 in the totals")
                    .containsExactly("v1");
            assertThat(countedVersions(policy("LATEST")).get("TC-1 — Case TC-1"))
                    .as("and the other way round under LATEST, which is what makes this a "
                            + "consequence of the POLICY rather than a property of the fixture")
                    .containsExactly("v2");
        }
    }

    @Test
    @DisplayName("a selection matching nothing says so rather than rendering a table")
    void anEmptySelectionSaysSo() {
        ReportModel model = inTransaction(() -> compute(everyRow(
                "{\"planIds\":[\"c4005000-0000-4000-8000-0000000000ff\"]}")));

        assertThat(table(model, DIVERGENCE)).isNull();
        assertThat(narrative(model, DIVERGENCE))
                .as("and it is the empty-selection wording, not the no-divergence one")
                .contains("No results contribute to this selection");
    }

    // =====================================================================

    private static ReportParameters whole() {
        return everyRow("{}");
    }

    private static ReportParameters policy(String name) {
        return everyRow("{\"policy\":\"" + name + "\"}");
    }

    private static ReportParameters everyRow(String json) {
        return ReportParameters.parse(json);
    }

    private ReportModel compute(ReportParameters parameters) {
        return report.compute(PROJECT, parameters);
    }

    private long executions(ReportParameters parameters) {
        return Long.parseLong(metadata(parameters, "Contributing executions"));
    }

    private String metadata(ReportParameters parameters, String label) {
        return inTransaction(() -> compute(parameters)).metadata().stream()
                .filter(entry -> label.equals(entry.label()))
                .map(ReportModel.Entry::value)
                .findFirst()
                .orElseThrow();
    }

    /** Keyed {@code "<case> · <version>"} — the row's two dimensions, joined. */
    private Map<String, List<String>> divergenceRows(ReportParameters parameters) {
        ReportModel.Table table = table(inTransaction(() -> compute(parameters)), DIVERGENCE);
        Map<String, List<String>> rows = new LinkedHashMap<>();
        if (table != null) {
            for (List<String> row : table.rows()) {
                rows.put(row.get(0) + " · " + row.get(1), List.copyOf(row));
            }
        }
        return rows;
    }

    /**
     * The three counts alone, with the {@code Counted in the totals} column dropped.
     *
     * <p>That column is the one cell of this table that <em>does</em> move with the policy, so an
     * equality across policies has to be taken over what is left — otherwise the test asserting the
     * section ignores the policy would fail on the one column that is supposed to follow it.
     */
    private Map<String, List<String>> divergenceCounts(ReportParameters parameters) {
        Map<String, List<String>> counts = new LinkedHashMap<>();
        divergenceRows(parameters).forEach((key, row) -> counts.put(key, row.subList(2, 5)));
        return counts;
    }

    /** Case label → the versions whose results reached the totals, in the table's own order. */
    private Map<String, List<String>> countedVersions(ReportParameters parameters) {
        Map<String, List<String>> counted = new LinkedHashMap<>();
        ReportModel.Table table = table(inTransaction(() -> compute(parameters)), DIVERGENCE);
        if (table != null) {
            for (List<String> row : table.rows()) {
                if ("Counted".equals(row.get(5))) {
                    counted.computeIfAbsent(row.get(0), key -> new ArrayList<>()).add(row.get(1));
                }
            }
        }
        return counted;
    }

    private List<String> inconsistencyLabels(ReportParameters parameters) {
        ReportModel.Table table = table(inTransaction(() -> compute(parameters)), INCONSISTENCY);
        List<String> labels = new ArrayList<>();
        if (table != null) {
            table.rows().forEach(row -> labels.add(row.get(0)));
        }
        return labels;
    }

    /** Null when the section is narrative-only — {@code findFirst} cannot carry a null itself. */
    private static ReportModel.Table table(ReportModel model, String heading) {
        return model.sections().stream()
                .filter(section -> heading.equals(section.heading()))
                .findFirst()
                .map(ReportModel.Section::table)
                .orElse(null);
    }

    private static String narrative(ReportModel model, String heading) {
        return model.sections().stream()
                .filter(section -> heading.equals(section.heading()))
                .map(ReportModel.Section::narrative)
                .findFirst()
                .orElse("");
    }

    private void refresh() {
        try (var ignored = CompanyContext.open(ACME)) {
            facts.refresh();
        }
    }

    /**
     * In a transaction, not merely in a company context — outside one, {@code app.current_company}
     * is unset and RLS answers zero rows, which would satisfy most of the absence checks here.
     */
    private <T> T inTransaction(Supplier<T> work) {
        try (var ignored = CompanyContext.open(ACME)) {
            return transactions.execute(status -> work.get());
        }
    }

    // ------------------------------------------------------------------ fixture ---

    private void clear(Connection connection) throws SQLException {
        for (String table : List.of("plan_snapshot", "suite_run_summary", "execution_fact",
                "case_health", "step_result", "test_execution", "test_run", "plan_scope_item",
                "test_plan_version", "test_plan", "case_tag", "suite_tag_rule", "suite_case",
                "case_classification", "test_case_version", "test_case", "suite")) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
    }

    private void seedCompanyDefaults(Connection connection, UUID companyId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.execute();
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

    private void configuration(Connection connection, UUID id, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration (id, company_id, project_id, name, attributes, is_default)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST('{}' AS jsonb),
                        false)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, name);
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection, UUID id, String key, long keySeq, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position,
                                   lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED',
                        NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, String key, long keySeq)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at,
                     deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', now(), now(), NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, BILLING.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.executeUpdate();
        }
        version(connection, id, 1);
    }

    private void version(Connection connection, UUID caseId, int versionNo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), ?,
                        CAST('{}' AS jsonb), ?, now())
                    ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setInt(3, versionNo);
            statement.setString(4, "hash-" + caseId + "-" + versionNo);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID configurationId, String key, long keySeq)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, plan_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, ?, ?,
                        'Divergence fixture run', 'IN_PROGRESS', 'MANUAL',
                        COALESCE(CAST(? AS uuid),
                                 (SELECT c.id FROM configuration c
                                   WHERE c.project_id = CAST(? AS uuid) AND c.is_default)),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, configurationId == null ? null : configurationId.toString());
            statement.setString(7, PROJECT.toString());
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID runId, UUID caseId, String statusName,
            Instant executedAt, int versionNo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_at, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = ?),
                        (SELECT s.id FROM case_status s
                          WHERE s.project_id = CAST(? AS uuid) AND s.name = ?),
                        CAST(? AS timestamptz), now(), now())
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, PROJECT.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setInt(6, versionNo);
            statement.setString(7, PROJECT.toString());
            statement.setString(8, statusName);
            statement.setString(9, executedAt == null ? null : executedAt.toString());
            statement.executeUpdate();
        }
    }
}
