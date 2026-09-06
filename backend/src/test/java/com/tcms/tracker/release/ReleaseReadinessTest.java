package com.tcms.tracker.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * <em>"Can we ship 2.4?"</em> — the module's headline claim, and the three answers it has to keep
 * apart.
 *
 * <p>The one this class exists for is the third: <strong>a version nothing has run against is not a
 * version with zero failures</strong>. That distinction has no symptom if it is got wrong — the
 * screen renders a clean meter and a green badge over a release nobody has tested — so it is
 * asserted on the null-ness of the counts and not only on the verdict.
 *
 * <p>UUID prefix {@code c6}, taken 2026-09-01 and free at the time. The whole namespace this class
 * uses is greppable from here, which is what {@code BoardEndpointTest} lost eleven tests to.
 */
@AutoConfigureMockMvc
@DisplayName("Release readiness — done AND proven, and the difference between untested and clean")
class ReleaseReadinessTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("c6000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("c6001000-0000-4000-8000-000000000001");

    /** Project Manager — the tier holding {@code release:manage} and {@code release:publish} (§A3). */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("c6002000-0000-4000-8000-000000000001");
    private static final UUID MANAGER_GRANT = UUID.fromString("c6003000-0000-4000-8000-000000000001");

    private static final UUID SUITE_LESS_CONFIG = UUID.fromString("c6004000-0000-4000-8000-000000000001");

    @Autowired
    private ReleaseService releases;

    @Autowired
    private ReadinessService readiness;

    @Autowired
    private VerificationService verifications;

    private int nextSeq;
    private int nextCase;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "readiness-acme", "Acme", now);
            activeUser(MANAGER, "pm@readiness.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "RDY", "Readiness Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        wipe();
        nextSeq = 1;
        nextCase = 1;
    }

    @AfterEach
    void putEverythingBack() throws SQLException {
        wipe();
    }

    @Test
    @DisplayName("a done story whose linked case failed makes the release not ready, and names it")
    void aDoneStoryWithAFailedCaseBlocksTheRelease() throws SQLException {
        UUID release = release("2.4", LocalDate.now().plusDays(17));
        String story = story("Guest checkout without an account");
        target(story, release);
        moveToDone(story);

        UUID passing = testCase("Guest order, valid card");
        UUID failing = testCase("Guest order, expired card");
        link(story, List.of(passing, failing));
        result(passing, "2.4", "PASSED", true, Instant.now().minus(3, ChronoUnit.HOURS));
        result(failing, "2.4", "FAILED", false, Instant.now().minus(2, ChronoUnit.HOURS));

        ReadinessView answer = readinessOf(release);

        assertThat(answer.verdict()).isEqualTo(ReleaseVerdict.NOT_READY);
        assertThat(answer.stories().done()).isEqualTo(answer.stories().total());
        assertThat(answer.tests().failed()).isEqualTo(1);
        // Named, not counted. "One story is blocking" is not an answer somebody can act on.
        assertThat(answer.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.key()).isEqualTo(story);
            assertThat(blocker.failedCaseKeys()).containsExactly(keyOf(failing));
            assertThat(blocker.casesPassed()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a release nothing has run against says so, and does not say zero failures")
    void aReleaseWithNoRunIsNotTestedRatherThanClean() throws SQLException {
        UUID release = release("2.5", LocalDate.now().plusDays(60));
        String story = story("Search relevance");
        target(story, release);
        moveToDone(story);
        link(story, List.of(testCase("Relevance smoke")));

        ReadinessView answer = readinessOf(release);

        assertThat(answer.verdict()).isEqualTo(ReleaseVerdict.NOT_TESTED);
        assertThat(answer.tests().hasRun()).isFalse();
        // THE assertion of this module. A zero here is a meter that reads as a clean release, and
        // an untested version and a clean version are not the same claim.
        assertThat(answer.tests().failed()).isNull();
        assertThat(answer.tests().passed()).isNull();
        assertThat(answer.tests().untested()).isNull();
        assertThat(answer.tests().linkedCases()).isEqualTo(1);
        assertThat(answer.blockers()).isEmpty();
    }

    @Test
    @DisplayName("nothing has run AND a story is open reads not ready, not untested")
    void anOpenStoryOutranksTheAbsenceOfARun() throws SQLException {
        UUID release = release("2.6", null);
        String open = story("Still in progress");
        target(open, release);

        ReadinessView answer = readinessOf(release);

        assertThat(answer.verdict()).isEqualTo(ReleaseVerdict.NOT_READY);
        assertThat(answer.tests().hasRun()).isFalse();
        assertThat(answer.blockers()).singleElement()
                .satisfies(blocker -> assertThat(blocker.key()).isEqualTo(open));
    }

    @Test
    @DisplayName("every story done and every linked case passing is READY")
    void doneAndProvenIsReady() throws SQLException {
        UUID release = release("2.3", LocalDate.now().minusDays(20));
        String story = story("Accessibility pass");
        target(story, release);
        moveToDone(story);

        UUID passing = testCase("Contrast on the checkout button");
        link(story, List.of(passing));
        result(passing, "2.3", "PASSED", true, Instant.now().minus(1, ChronoUnit.HOURS));

        ReadinessView answer = readinessOf(release);

        assertThat(answer.verdict()).isEqualTo(ReleaseVerdict.READY);
        assertThat(answer.tests().passed()).isEqualTo(1);
        assertThat(answer.tests().untested()).isZero();
        assertThat(answer.blockers()).isEmpty();
    }

    @Test
    @DisplayName("the LATEST result wins, not the best one")
    void aFailureIsNotAveragedAwayByAnEarlierPass() throws SQLException {
        UUID release = release("2.7", null);
        String story = story("Card capture without a redirect");
        target(story, release);
        moveToDone(story);

        UUID flaky = testCase("Card capture without leaving the page");
        link(story, List.of(flaky));
        // Passed two hours ago, failed one hour ago. Taking the best result would let a release
        // pass on a run somebody re-ran until it went green.
        result(flaky, "2.7", "PASSED", true, Instant.now().minus(2, ChronoUnit.HOURS));
        result(flaky, "2.7", "FAILED", false, Instant.now().minus(1, ChronoUnit.HOURS));

        ReadinessView answer = readinessOf(release);

        assertThat(answer.verdict()).isEqualTo(ReleaseVerdict.NOT_READY);
        assertThat(answer.tests().failed()).isEqualTo(1);
        assertThat(answer.tests().passed()).isZero();
    }

    @Test
    @DisplayName("a run against another version does not count for this one")
    void resultsAreScopedToTheVersion() throws SQLException {
        UUID release = release("2.8", null);
        String story = story("Gift cards");
        target(story, release);
        moveToDone(story);

        UUID linked = testCase("Apply a gift card");
        link(story, List.of(linked));
        result(linked, "2.7", "PASSED", true, Instant.now().minus(1, ChronoUnit.HOURS));

        ReadinessView answer = readinessOf(release);

        assertThat(answer.verdict()).isEqualTo(ReleaseVerdict.NOT_TESTED);
        assertThat(answer.tests().hasRun()).isFalse();
    }

    @Test
    @DisplayName("a spike blocks nothing, in either half")
    void spikesAreExcludedFromReadinessAsTheyAreFromCompletion() throws SQLException {
        UUID release = release("2.9", null);
        String done = story("A shippable story");
        target(done, release);
        moveToDone(done);

        String spike = insert("Can we drop the legacy cart cookie?", "Spike", null);
        target(spike, release);

        ReadinessView answer = readinessOf(release);

        // D8's set, computed the same way as an epic's percentage, so the release and the epic
        // meter cannot disagree about what counts.
        assertThat(answer.stories().total()).isEqualTo(1);
        assertThat(answer.stories().excludedSpikes()).isEqualTo(1);
        assertThat(answer.verdict()).isEqualTo(ReleaseVerdict.NOT_TESTED);
        assertThat(answer.blockers()).isEmpty();
    }

    @Test
    @DisplayName("1.10 sorts after 1.9, and 4.3.0-rc.1 before 4.3.0")
    void versionsAreOrderedByKeyNotByText() {
        release("1.10", null);
        release("4.3.0", null);
        release("1.9", null);
        release("4.3.0-rc.1", null);

        List<ReleaseSummaryView> listed = allowed(scoped(() -> releases.list(MANAGER, PROJECT)));

        assertThat(listed).extracting(ReleaseSummaryView::version)
                .containsExactly("1.9", "1.10", "4.3.0-rc.1", "4.3.0");
    }

    @Test
    @DisplayName("publishing twice does not restamp releasedAt")
    void publishIsIdempotent() {
        UUID release = release("3.0", null);

        ReleaseView first = written(scoped(() -> releases.publish(MANAGER, release)));
        ReleaseView second = written(scoped(() -> releases.publish(MANAGER, release)));

        assertThat(first.status()).isEqualTo(ReleaseStatus.RELEASED);
        assertThat(second.releasedAt()).isEqualTo(first.releasedAt());
    }

    @Test
    @DisplayName("a second release with the same version, in any case, is a conflict")
    void versionsAreUniqueWithinTheProjectCaseInsensitively() {
        release("2.4", null);

        GateResult<ReleaseService.Outcome> again = scoped(() -> releases.create(
                MANAGER, PROJECT, new ReleaseInput("2.4", null, null)));

        assertThat(allowedOutcome(again)).isInstanceOf(ReleaseService.Outcome.Duplicate.class);
    }

    @Test
    @DisplayName("linking a case from another project is a field error, not a 404 on the issue")
    void aForeignCaseIsRefusedByName() throws SQLException {
        String story = story("Something to prove");

        GateResult<VerificationService.Outcome> refused = scoped(() -> verifications.replace(
                MANAGER, story, new IssueTestCasesInput(List.of(UUID.randomUUID()))));

        assertThat(allowedOutcome(refused))
                .isInstanceOfSatisfying(VerificationService.Outcome.Invalid.class, invalid ->
                        assertThat(invalid.errors()).containsKey("caseIds"));
    }

    @Test
    @DisplayName("a case knows which stories it verifies — the same fact, read from the other end")
    void theReverseDirectionAnswersFromTrackersOwnTables() throws SQLException {
        String first = story("First story");
        String second = story("Second story");
        UUID shared = testCase("Cart survives sign-in mid-checkout");
        link(first, List.of(shared));
        link(second, List.of(shared));

        List<CaseVerificationView> found =
                allowed(scoped(() -> verifications.verificationsIn(MANAGER, PROJECT)));

        assertThat(found).singleElement().satisfies(entry -> {
            assertThat(entry.caseId()).isEqualTo(shared);
            assertThat(entry.issues()).extracting(IssueRefView::key)
                    .containsExactlyInAnyOrder(first, second);
        });
    }

    // =====================================================================

    private ReadinessView readinessOf(UUID release) {
        return allowed(scoped(() -> readiness.readiness(MANAGER, release)));
    }

    /**
     * <strong>Every in-process service call goes through this.</strong>
     *
     * <p>A test calling a service directly has to open the company scope itself: without it
     * {@code app.current_company} is unset, every RLS read answers zero rows, and the gate reports
     * {@code Unreachable} — which reads as an authorization defect and is not one. Twelve of this
     * class's tests failed that way on the first run, exactly as 012 T3's nine did.
     */
    private static <T> T scoped(Supplier<T> work) {
        try (var ignored = CompanyContext.open(ACME)) {
            return work.get();
        }
    }

    private static <T> T allowed(GateResult<T> gate) {
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<T>) gate).value();
    }

    private static <T> T allowedOutcome(GateResult<T> gate) {
        return allowed(gate);
    }

    private static ReleaseView written(GateResult<ReleaseService.Outcome> gate) {
        ReleaseService.Outcome outcome = allowed(gate);
        assertThat(outcome).isInstanceOf(ReleaseService.Outcome.Written.class);
        return ((ReleaseService.Outcome.Written) outcome).view();
    }

    private UUID release(String version, LocalDate target) {
        return written(scoped(() -> releases.create(
                MANAGER, PROJECT, new ReleaseInput(version, null, target)))).id();
    }

    private void link(String issueKey, List<UUID> caseIds) {
        GateResult<VerificationService.Outcome> gate = scoped(() ->
                verifications.replace(MANAGER, issueKey, new IssueTestCasesInput(caseIds)));
        assertThat(allowed(gate)).isInstanceOf(VerificationService.Outcome.Replaced.class);
    }

    private String story(String title) throws SQLException {
        return insert(title, "Story", null);
    }

    private String insert(String title, String typeName, String parentKey) throws SQLException {
        int seq = nextSeq++;
        String key = "RDY-" + seq;
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue (id, company_id, project_id, key_seq, issue_key,
                                           type_id, status_id, parent_id, title)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                (SELECT id FROM issue_type
                                  WHERE project_id = CAST(? AS uuid) AND name = ?),
                                (SELECT id FROM issue_status
                                  WHERE project_id = CAST(? AS uuid) AND name = 'New'),
                                (SELECT id FROM issue WHERE issue_key = ?),
                                ?)
                        """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setInt(4, seq);
            statement.setString(5, key);
            statement.setObject(6, PROJECT);
            statement.setString(7, typeName);
            statement.setObject(8, PROJECT);
            statement.setString(9, parentKey);
            statement.setString(10, title);
            statement.executeUpdate();
        }
        return key;
    }

    private void target(String issueKey, UUID releaseId) throws SQLException {
        update("UPDATE issue SET release_id = CAST(? AS uuid) WHERE issue_key = ?",
                releaseId, issueKey);
    }

    /**
     * Sets the status directly rather than walking the workflow.
     *
     * <p>New to Done is not a legal move in the seeded workflow and walking the eight steps would
     * make every fixture here about the workflow instead of about readiness —
     * {@code BoardCardServiceTest} takes the same shortcut for the same reason.
     */
    private void moveToDone(String key) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE issue SET status_id = (SELECT id FROM issue_status
                                                       WHERE project_id = CAST(? AS uuid)
                                                         AND name = 'Done'),
                                         done_at = now()
                         WHERE issue_key = ?
                        """)) {
            statement.setObject(1, PROJECT);
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    /**
     * The display key behind a fixture case id.
     *
     * <p>Derived from the id's last group and NOT from its first eight characters, which was the
     * first spelling and collided on the first run: every id in this class shares the
     * {@code c6005000} prefix, so two cases produced one key and {@code uq_test_case_key} said so.
     */
    private static String keyOf(UUID caseId) {
        String id = caseId.toString();
        return "TC-" + Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
    }

    /**
     * A minimal {@code test_case} row, written straight through the app connection.
     *
     * <p>The alternative is {@code CaseWriteService}, which would pull a second module's whole
     * validation surface into a fixture about releases. Readiness reads three columns of this table
     * — id, key, title — and this writes exactly those plus what the schema requires.
     */
    private UUID testCase(String title) throws SQLException {
        int seq = nextCase++;
        UUID id = UUID.fromString(String.format("c6005000-0000-4000-8000-%012d", seq));
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO test_case (id, company_id, project_id, key, key_seq, title)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setString(4, keyOf(id));
            statement.setLong(5, seq);
            statement.setString(6, title);
            statement.executeUpdate();
        }
        return id;
    }

    /**
     * One {@code execution_fact} row, written directly.
     *
     * <p>{@code reporting} refreshes that table from {@code test_execution} on a tick, and driving a
     * run through {@code execution} to produce one would make this class a test of the ingestion
     * path. What readiness reads is the fact log, so the fact log is what the fixture writes — the
     * same choice {@code ReleaseSortKeyOrderingTest} makes one module over.
     */
    private void result(UUID caseId, String version, String kind, boolean pass, Instant at)
            throws SQLException {

        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO execution_fact (execution_id, company_id, project_id, case_id,
                                                    case_version_id, run_id, configuration_id,
                                                    release_version, status_kind, counts_as_pass,
                                                    automation_status, source, executed_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                                CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                                ?, ?, ?, 'MANUAL', 'MANUAL', ?)
                        """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setObject(4, caseId);
            statement.setObject(5, UUID.randomUUID());
            // No test_run row: execution_fact carries no foreign key to one — it is an append-only
            // log that deliberately duplicates its dimensions — and the evidence panel's run name
            // is a LEFT-hand join that simply finds nothing here.
            statement.setObject(6, UUID.randomUUID());
            statement.setObject(7, SUITE_LESS_CONFIG);
            statement.setString(8, version);
            statement.setString(9, kind);
            statement.setBoolean(10, pass);
            statement.setObject(11, java.sql.Timestamp.from(at));
            statement.executeUpdate();
        }
    }

    private void update(String sql, Object first, Object second) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            statement.executeUpdate();
        }
    }

    /** In FK order: the links point at issues, and the facts and cases stand alone. */
    private void wipe() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            for (String table : List.of(
                    "issue_link", "execution_fact", "test_case", "issue", "release")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE company_id = CAST(? AS uuid)")) {
                    statement.setObject(1, ACME);
                    statement.executeUpdate();
                }
            }
        }
    }
}
