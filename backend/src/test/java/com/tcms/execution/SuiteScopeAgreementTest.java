package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.repository.SuiteMembership;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>The two implementations of "what is in this suite" must answer the same thing.</strong>
 *
 * <p>Since §96 a suite is a resolvable collection — filed ∪ added ∪ tagged — and that union is
 * written twice, on purpose and unavoidably. {@code repository.SuiteMembership} owns it for the
 * tree, the counts and the case grid; {@code execution.CaseFacts#memberOf} owns it for plan
 * scope and a suite's execution history, because ERD §0.2 lets the {@code execution} module read
 * the repository's tables <em>only</em> through its one seam class, and calling across instead
 * would trade a duplicated predicate for a dependency the architecture test refuses.
 *
 * <p><strong>§98 was expected to retire this class and does the opposite.</strong> The plan for
 * that slice read the duplication as a consequence of the subtree — "with no subtree it becomes
 * membership, which {@code SuiteMembership} already answers, so one of §96's two duplicated
 * resolution predicates disappears and {@code SuiteScopeAgreementTest} loses its reason to exist".
 * The duplication is caused by the module boundary above, which flattening does not touch. What
 * changed is that the two predicates are now supposed to be <em>identical</em> rather than merely
 * equivalent, which makes this class stricter, not redundant.
 *
 * <p>So the drift has to be caught by a test rather than prevented by sharing code. That is the
 * same device {@code PlanStatisticsEndpointTest} uses for the three plan-scope expansions, and the failure
 * it guards against is silent in the worst possible way: a plan scoped on a tag-defined suite would
 * create a run of <em>fewer cases than the suite holds</em>, reported as a successful run covering
 * a plan, with two screens each showing a plausible number and neither identifiable as the wrong
 * one.
 *
 * <h2>There are THREE copies since §101, and this class holds two of them</h2>
 *
 * <p>{@code reporting.ExecutionSource} is the third — the {@code suite_run_summary} rollup resolves
 * the same union to decide which of a run's cases each suite claims. It is forced by the same
 * boundary and could not be asserted here: {@code ExecutionSource} is package-private in a module
 * this test package cannot see, and giving it a public resolution method purely so this class could
 * call it would be production code with no production caller.
 *
 * <p>So it is held by {@code SuiteRunSummaryRefreshTest.Agreement} instead, against
 * {@code SuiteMembership} — the same canonical side this class compares to — and through the
 * {@code membership_snapshot} column the shipping statement actually writes, rather than through a
 * method added for the test. Two classes, one canonical union, three copies checked.
 *
 * <h2>The one place they deliberately DISAGREE, and it is asserted too</h2>
 *
 * <p>{@code caseIdsInSuite} <strong>includes soft-deleted cases</strong> — a suite's history
 * says what a run covered at the time, and dropping a case somebody deleted last week would lower
 * the totals of runs that finished before the deletion. {@code SuiteMembership} excludes them,
 * because its consumers are the tree and the grid, where a deleted case is gone. Asserting only
 * "the two agree" would make the fixture's deleted case a reason to weaken this class rather than
 * a documented difference, so both directions are pinned here.
 *
 * <p>Ids are {@code 5c…} — hex, which is not optional; {@code ExecutionFactRefreshTest}'s header
 * records the run a non-hex prefix cost.
 */
@DisplayName("Suite scope — the repository's union and the execution seam's must agree")
class SuiteScopeAgreementTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("5c000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("5c001000-0000-4000-8000-000000000001");

    /**
     * The suite under test.
     *
     * <p>It was the ROOT of a two-level subtree until §98, with every arm hanging off its CHILD —
     * which is what made the old fixture a subtree question. Flattening removed the child, so the
     * three arms attach here directly and {@link #ELSEWHERE} is the whole of the bound.
     */
    private static final UUID SUBJECT = UUID.fromString("5c002000-0000-4000-8000-000000000001");
    /** Another suite in the project — its cases must appear in neither answer unless claimed. */
    private static final UUID ELSEWHERE = UUID.fromString("5c002000-0000-4000-8000-000000000003");

    /** Filed in the subject: the arm that existed before V31. */
    private static final UUID FILED = UUID.fromString("5c003000-0000-4000-8000-000000000001");
    /** Added to the subject individually, and filed somewhere else entirely. */
    private static final UUID ADDED = UUID.fromString("5c003000-0000-4000-8000-000000000002");
    /** Matched by the subject's tag rule, and filed somewhere else entirely. */
    private static final UUID TAGGED = UUID.fromString("5c003000-0000-4000-8000-000000000003");
    /** Added AND tagged — the row that separates a union from a deduped one. */
    private static final UUID BOTH = UUID.fromString("5c003000-0000-4000-8000-000000000004");
    /** Filed in the subject and soft-deleted — the one intended disagreement. */
    private static final UUID REMOVED = UUID.fromString("5c003000-0000-4000-8000-000000000005");
    /** Filed outside the subtree, untagged, unadded. Must appear in neither. */
    private static final UUID OUTSIDE = UUID.fromString("5c003000-0000-4000-8000-000000000006");

    @Autowired
    private CaseFacts facts;

    @Autowired
    private SuiteMembership membership;

    /**
     * The seam's queries carry no {@code @Transactional} of their own — every production caller is
     * a service that already has one — so calling them straight from a test runs them outside any
     * transaction, {@code CompanyAwareTransactionManager} never sets {@code app.current_company},
     * and RLS answers <strong>zero rows</strong>. That is fail-closed and it is silent: the first
     * draft of this class asserted an empty list against an empty list and reported agreement.
     */
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
            companies.save(Company.provision(ACME, "sa-acme", "Acme", now));
        }
        try (var ignored = CompanyContext.open(ACME)) {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, ACME, "SAAC", "Acme Product", null, now));
            }
        }

        try (Connection connection = appConnection(ACME)) {
            clear(connection);

            suite(connection, SUBJECT, null, "TS-1", 1, "root", "Subject");
            suite(connection, ELSEWHERE, null, "TS-3", 3, "elsewhere", "Elsewhere");

            testCase(connection, FILED, SUBJECT, "TC-1", 1, false);
            testCase(connection, ADDED, ELSEWHERE, "TC-2", 2, false);
            testCase(connection, TAGGED, ELSEWHERE, "TC-3", 3, false);
            testCase(connection, BOTH, ELSEWHERE, "TC-4", 4, false);
            testCase(connection, REMOVED, SUBJECT, "TC-5", 5, true);
            testCase(connection, OUTSIDE, ELSEWHERE, "TC-6", 6, false);

            addedCase(connection, SUBJECT, ADDED);
            addedCase(connection, SUBJECT, BOTH);

            tag(connection, TAGGED, "smoke");
            tag(connection, BOTH, "smoke");
            tagRule(connection, SUBJECT, "smoke");
        }
    }

    @Test
    @DisplayName("both resolutions find the same live members of the suite")
    void theTwoUnionsAgree() {
        // Neither answer can be produced by the filed arm alone: only ONE of the four live members
        // is filed in the suite. A resolution that missed the other two arms would
        // answer {FILED} on both sides and this assertion would still be comparing two equal
        // things — which is why the expected set is written out rather than left as
        // `assertThat(a).isEqualTo(b)`.
        List<UUID> viaSeam = inCompany(() -> facts.caseIdsInSuite(PROJECT, SUBJECT));
        List<UUID> viaRepository = inCompany(() -> membership.caseIdsIn(ACME, PROJECT, SUBJECT));

        assertThat(viaRepository)
                .containsExactlyInAnyOrder(FILED, ADDED, TAGGED, BOTH);
        assertThat(viaSeam)
                .as("the execution seam resolves the same three arms as the repository's union — "
                        + "a plan scoped on a tag-defined suite would otherwise create a run of "
                        + "fewer cases than the suite holds, and report success")
                .contains(FILED, ADDED, TAGGED, BOTH);
    }

    @Test
    @DisplayName("a case qualifying through two arms is one member, on both sides")
    void bothSidesDedupe() {
        // BOTH is added individually AND matched by the tag rule. Without DISTINCT it appears
        // twice, which is how a plan's case count exceeds the number of cases it can name.
        assertThat(inCompany(() -> facts.caseIdsInSuite(PROJECT, SUBJECT)))
                .filteredOn(BOTH::equals)
                .hasSize(1);
        assertThat(inCompany(() -> membership.caseIdsIn(ACME, PROJECT, SUBJECT)))
                .filteredOn(BOTH::equals)
                .hasSize(1);
    }

    @Test
    @DisplayName("neither resolves a case that is outside the suite by every arm")
    void theSuiteIsABound() {
        assertThat(inCompany(() -> facts.caseIdsInSuite(PROJECT, SUBJECT)))
                .doesNotContain(OUTSIDE);
        assertThat(inCompany(() -> membership.caseIdsIn(ACME, PROJECT, SUBJECT)))
                .doesNotContain(OUTSIDE);
    }

    @Test
    @DisplayName("they disagree about a deleted case, deliberately, and each way is right")
    void theDeletedCaseIsTheOneIntendedDifference() {
        // The seam feeds a suite's execution HISTORY, where a case deleted last week must not
        // lower the totals of runs that finished before the deletion. The repository's union feeds
        // the tree and the grid, where a deleted case is gone. Pinned in both directions so the
        // difference stays a decision rather than becoming the reason this class gets weakened.
        assertThat(inCompany(() -> facts.caseIdsInSuite(PROJECT, SUBJECT)))
                .as("history includes it")
                .contains(REMOVED);
        assertThat(inCompany(() -> membership.caseIdsIn(ACME, PROJECT, SUBJECT)))
                .as("membership does not")
                .doesNotContain(REMOVED);
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private <T> T inCompany(java.util.function.Supplier<T> work) {
        try (var ignored = CompanyContext.open(ACME)) {
            // In a transaction, not merely in a company context — see the field's comment. The
            // context names the company; the transaction is what puts it on the connection.
            return transactions.execute(status -> work.get());
        }
    }

    private void clear(Connection connection) throws SQLException {
        for (String table : List.of(
                "suite_tag_rule", "suite_case", "case_tag", "test_case", "suite")) {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
    }

    private void suite(
            Connection connection, UUID id, UUID parentId, String key, long keySeq,
            String path, String name) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED')
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

    private void testCase(
            Connection connection, UUID id, UUID suiteId, String key, long keySeq, boolean deleted)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, CAST(? AS timestamptz))
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, PROJECT.toString());
            statement.setString(4, suiteId == null ? null : suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void addedCase(Connection connection, UUID suiteId, UUID caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO suite_case (company_id, suite_id, case_id) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid)) "
                        + "ON CONFLICT DO NOTHING")) {
            statement.setString(1, ACME.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, caseId.toString());
            statement.executeUpdate();
        }
    }

    private void tag(Connection connection, UUID caseId, String tag) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO case_tag (company_id, case_id, tag) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    private void tagRule(Connection connection, UUID suiteId, String tag) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO suite_tag_rule (company_id, suite_id, tag) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, ACME.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }
}
