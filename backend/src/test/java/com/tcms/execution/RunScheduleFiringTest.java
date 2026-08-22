package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.AuditEventPageView;
import com.tcms.identity.AuditEventView;
import com.tcms.identity.AuditLogQuery;
import com.tcms.identity.AuditLogService;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What happens when a schedule fires (§247).
 *
 * <p>The firing service is driven <strong>directly</strong> rather than through
 * {@code RunScheduleScheduler}: the poller's own contribution is the company loop, which
 * {@code AsyncCompanyContextTest} covers, and waiting a minute per arm for a timer is not a test.
 *
 * <h2>The failures this class exists for, and none of them is visible to the CRUD test</h2>
 *
 * <ol>
 *   <li><strong>A run attributed to a person who was asleep.</strong> The whole authority-versus-
 *       attribution split is one line in {@code RunCreateService.apply}, and getting it wrong
 *       produces a perfectly valid run whose audit row says a name.</li>
 *   <li><strong>A schedule that fires into an archived project</strong>, quietly resurrecting work
 *       somebody put away.</li>
 *   <li><strong>A schedule that fires twice for one slot</strong> — two checklists, one tester's
 *       results split across them, and two pass rates over denominators nobody chose.</li>
 *   <li><strong>A schedule that fails once and never advances</strong>, becoming a hot loop that
 *       re-fires on every tick for ever.</li>
 *   <li><strong>A missed slot caught up on restart</strong>, producing a week of backdated runs
 *       none of which means what its timestamp says.</li>
 * </ol>
 *
 * <p>Ids are unique to this class — {@code 3d…}, shared with {@code RunScheduleEndpointTest} by
 * prefix and distinct in the second group, which is this repository's convention.
 *
 * <p>⚠️ <strong>This class has never been run.</strong> Written under the no-gates directive of
 * 2026-08-22.
 */
@AutoConfigureMockMvc
@DisplayName("a schedule fires — with no actor, no catch-up and no second checklist")
class RunScheduleFiringTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("3d100000-0000-4000-8000-00000000000a");

    private static final UUID BANKING = UUID.fromString("3d101000-0000-4000-8000-000000000001");

    private static final UUID OWNER = UUID.fromString("3d103000-0000-4000-8000-000000000001");
    private static final UUID AUDITOR = UUID.fromString("3d103000-0000-4000-8000-000000000002");

    private static final UUID SMOKE_SUITE = UUID.fromString("3d104000-0000-4000-8000-000000000001");

    /** Every day at 03:00 UTC. */
    private static final String NIGHTLY = "0 0 3 * * *";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private com.tcms.auth.SessionTokens tokens;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private RunScheduleFiringService firing;

    @Autowired
    private RunScheduleRepository schedules;

    @Autowired
    private AuditLogService auditLog;

    @Autowired
    private TransactionTemplate tx;

    private UUID smokeCase;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "rf-acme", "Acme", now);
            activeUser(OWNER, "owner@rf-acme.test", "O. Owner", now);
            activeUser(AUDITOR, "auditor@rf-acme.test", "A. Auditor", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(BANKING, ACME, "RFAOLB", "Online Banking", now);
            member(ACME, OWNER, "O. Owner", now);
            member(ACME, AUDITOR, "A. Auditor", now);
            grant("rf-owner", ACME, Role.TEST_LEAD_ID, OWNER, BANKING, now);
            grant("rf-auditor", ACME, Role.TEST_LEAD_ID, AUDITOR, BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        clearMutableRows();
        seedTrees();

        smokeCase = createCase("Sign in with a valid card");
        tagCase(smokeCase, "smoke");
    }

    // =====================================================================
    // The actorless write — §242 LG-5's producer
    // =====================================================================

    @Test
    @DisplayName("a fired schedule creates a run with no creator")
    void aFiredScheduleCreatesARunWithNoCreator() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        Instant slot = makeDue(scheduleId, minutesAgo(1));

        fire(scheduleId, Instant.now());

        Map<String, String> run = onlyRun();
        assertThat(run.get("source"))
                .as("the THIRD door — not MANUAL, which would label it a person's act on the run "
                        + "list beside a created_by of nobody")
                .isEqualTo("SCHEDULED");
        assertThat(run.get("created_by"))
                .as("nobody performed the act at 03:00; the schedule's owner is the AUTHORITY the "
                        + "create ran under, which is a different column")
                .isNull();
        assertThat(run.get("name"))
                .as("named from the schedule and its slot, dated in the schedule's own zone")
                .startsWith("Nightly smoke — ");

        assertThat(executionCount())
                .as("one execution per resolved case — the checklist really was materialised")
                .isEqualTo(1);
        assertThat(slot).isNotNull();
    }

    /**
     * <strong>This arm is {@code §242 LG-5}.</strong>
     *
     * <p>That browser row was recorded as NOT DRIVABLE because no write path in the product lacked
     * a caller — the audit reader's "Automatic" branch had no producer. A poller firing a run is
     * the first one.
     *
     * <p>Both halves are asserted, and asserting only the column would not do: a reader that
     * invented an actor — resolving the schedule's owner, say, because it is the only person on the
     * row — would pass a column check and still put a name on the screen.
     */
    @Test
    @DisplayName("the audit row for a scheduled run names nobody, at the column AND at the reader")
    void theAuditRowForAScheduledRunNamesNobody() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        makeDue(scheduleId, minutesAgo(1));

        fire(scheduleId, Instant.now());

        Map<String, String> row = onlyAuditRow("run.created");
        assertThat(row.get("actor_user_id"))
                .as("AuditEvent#record's own @param: null only for an unattended job — this is "
                        + "that job, and it is the product's first")
                .isNull();
        assertThat(row.get("actor_token_id"))
                .as("no credential either; a poller is not a CI token acting for somebody")
                .isNull();

        AuditEventView read = throughTheShippedReader("run.created");
        assertThat(read.actorUserId()).isNull();
        assertThat(read.actorUserName())
                .as("the shipped reader must resolve to no person — this is what the SPA renders "
                        + "as \"Automatic\"")
                .isNull();
        assertThat(read.actorTokenId()).isNull();
        assertThat(read.actorTokenName()).isNull();
    }

    /**
     * The contrast that makes the arm above a claim about SCHEDULED rather than about audit rows.
     *
     * <p>A run created by a person through the ordinary endpoint, in the same company and the same
     * fixture, must still name them — otherwise "the scheduled row names nobody" would be true of
     * an implementation that had simply stopped recording actors.
     */
    @Test
    @DisplayName("a hand-created run in the same project still names its creator")
    void aHandCreatedRunStillNamesItsCreator() throws Exception {
        createRunManually();

        Map<String, String> row = onlyAuditRow("run.created");
        assertThat(row.get("actor_user_id")).isEqualTo(OWNER.toString());

        AuditEventView read = throughTheShippedReader("run.created");
        assertThat(read.actorUserName()).isEqualTo("O. Owner");
    }

    // =====================================================================
    // The scope is a standing request, not a snapshot
    // =====================================================================

    /**
     * The property that distinguishes a schedule from a clone — {@code RunCloneService}
     * deliberately does the opposite, re-running the materialised set.
     *
     * <p>The second fire uses a <strong>different slot</strong>, which is what makes it a second
     * fire rather than a replay: the idempotency key is the schedule and the slot.
     */
    @Test
    @DisplayName("the scope is re-resolved on every fire — today's smoke cases, not the day's")
    void theScopeIsReResolvedOnEveryFire() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));

        makeDue(scheduleId, minutesAgo(10));
        fire(scheduleId, Instant.now());
        assertThat(executionCount()).isEqualTo(1);

        UUID second = createCase("Sign in with an expired card");
        tagCase(second, "smoke");

        makeDue(scheduleId, minutesAgo(1));
        fire(scheduleId, Instant.now());

        assertThat(runCount()).isEqualTo(2);
        assertThat(executionCount())
                .as("the second run's checklist is one longer — \"everything tagged smoke\" means "
                        + "TONIGHT's smoke cases")
                .isEqualTo(3);
    }

    // =====================================================================
    // At-most-once
    // =====================================================================

    /**
     * Two instances polling one tick, or a retry after a partial failure.
     *
     * <p>The slot is restored between the two calls, so both fires ask for the <em>same</em> slot —
     * which is also what pins the key's shape: keyed on {@code now} instead, this arm would produce
     * two runs and two checklists.
     */
    @Test
    @DisplayName("the second fire of ONE slot makes no second checklist")
    void theSecondFireOfOneSlotMakesNoSecondChecklist() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        Instant slot = makeDue(scheduleId, minutesAgo(1));

        fire(scheduleId, Instant.now());
        setNextFireAt(scheduleId, slot);
        fire(scheduleId, Instant.now());

        assertThat(runCount())
                .as("a duplicate here is not harmless — it is a SECOND CHECKLIST")
                .isEqualTo(1);
        assertThat(executionCount()).isEqualTo(1);
        assertThat(scheduleColumn(scheduleId, "last_fire_error"))
                .as("a replay is not an error: another instance won the tick and the run exists")
                .isNull();
    }

    /** No catch-up. A missed slot is a slot that did not happen. */
    @Test
    @DisplayName("a missed slot is not caught up — one run, not a week of them")
    void aMissedSlotIsNotCaughtUp() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        makeDue(scheduleId, Instant.now().minus(7, ChronoUnit.DAYS));

        Instant now = Instant.now();
        fire(scheduleId, now);

        assertThat(runCount())
                .as("seven identical runs appearing at once, each materialised against today's "
                        + "case versions, is what this decision refuses")
                .isEqualTo(1);
        assertThat(Instant.parse(scheduleColumn(scheduleId, "next_fire_at")))
                .as("recomputed FROM THE MOMENT OF FIRING, never from the missed slot")
                .isAfter(now);
    }

    @Test
    @DisplayName("a disabled schedule is not due, even with its slot in the past")
    void aDisabledScheduleIsNotDue() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        makeDue(scheduleId, minutesAgo(1));
        setEnabled(scheduleId, false);

        fire(scheduleId, Instant.now());

        assertThat(runCount()).isZero();
        assertThat(dueCount(Instant.now()))
                .as("belt and braces over run_schedule_due_idx's own partial predicate")
                .isZero();
    }

    // =====================================================================
    // Every refusal is recorded, and every branch still advances
    // =====================================================================

    /** Task 20's decision, pinned. A job that quietly resurrects archived work is worse than one
     * that stops. */
    @Test
    @DisplayName("an archived project stops the fire and says so")
    void anArchivedProjectStopsTheFireAndSaysSo() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        makeDue(scheduleId, minutesAgo(1));
        archiveProject();

        Instant now = Instant.now();
        fire(scheduleId, now);

        assertThat(runCount()).isZero();
        assertThat(scheduleColumn(scheduleId, "last_fire_error"))
                .as("a schedule that stops SILENTLY is the failure mode of scheduling anything")
                .contains("archived");
        assertThat(Instant.parse(scheduleColumn(scheduleId, "next_fire_at")))
                .as("still advanced — a refusal is not a reason to re-fire on every tick")
                .isAfter(now);
    }

    /**
     * The arm that proves a schedule is not a standing, unrevokable right.
     *
     * <p>The owner is demoted to Viewer rather than removed: Viewer is the only role that does not
     * hold {@code run:create} and still holds {@code project:read}, so the gate answers
     * <strong>Denied</strong> and not {@code Unreachable} — which is what makes the message about
     * the grant rather than about the project.
     */
    @Test
    @DisplayName("an owner who lost the grant stops the schedule")
    void anOwnerWhoLostTheGrantStopsTheSchedule() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        makeDue(scheduleId, minutesAgo(1));
        demoteOwnerToViewer();

        fire(scheduleId, Instant.now());

        assertThat(runCount()).isZero();
        assertThat(scheduleColumn(scheduleId, "last_fire_error"))
                .as("the grant, named — the reader has to know it is a permission and not a bug")
                .contains("permission to create runs");
    }

    /** The hot-loop arm. */
    @Test
    @DisplayName("a failed fire still advances, twice over")
    void aFailedFireStillAdvances() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        archiveProject();

        makeDue(scheduleId, minutesAgo(10));
        fire(scheduleId, Instant.now());
        Instant afterFirst = Instant.parse(scheduleColumn(scheduleId, "next_fire_at"));

        makeDue(scheduleId, minutesAgo(1));
        Instant now = Instant.now();
        fire(scheduleId, now);
        Instant afterSecond = Instant.parse(scheduleColumn(scheduleId, "next_fire_at"));

        assertThat(afterFirst).isAfter(minutesAgo(10));
        assertThat(afterSecond)
                .as("a schedule that fails and does not advance is re-fired on every tick for "
                        + "ever, which is the worst outcome available here")
                .isAfter(now);
        assertThat(runCount()).isZero();
    }

    /**
     * The reason {@code RunScheduleEdit} deliberately does not refuse a dead scope arm at write
     * time: the emptiness belongs to a night, not to the edit.
     */
    @Test
    @DisplayName("a scope that resolves to nothing tonight is a fire error, not a stored fault")
    void anEmptyResolvedScopeIsRecordedRatherThanThrown() throws Exception {
        UUID scheduleId = schedule("Nightly nothing", NIGHTLY, tagScope("no-such-tag"));
        makeDue(scheduleId, minutesAgo(1));

        Instant now = Instant.now();
        fire(scheduleId, now);

        assertThat(runCount())
                .as("an empty run renders as 0 of 0, 100%, complete — a run that looks finished "
                        + "and never started")
                .isZero();
        assertThat(scheduleColumn(scheduleId, "last_fire_error")).isNotNull();
        assertThat(Instant.parse(scheduleColumn(scheduleId, "next_fire_at"))).isAfter(now);
    }

    /** A schedule that failed and then worked must not keep the red sentence beside a fresh run. */
    @Test
    @DisplayName("a successful fire clears the previous failure's sentence")
    void aSuccessfulFireClearsTheError() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        archiveProject();
        makeDue(scheduleId, minutesAgo(10));
        fire(scheduleId, Instant.now());
        assertThat(scheduleColumn(scheduleId, "last_fire_error")).isNotNull();

        unarchiveProject();
        makeDue(scheduleId, minutesAgo(1));
        fire(scheduleId, Instant.now());

        assertThat(scheduleColumn(scheduleId, "last_fire_error"))
                .as("a stale sentence beside a fresh last_run_id is a screen saying the schedule "
                        + "is broken while it works")
                .isNull();
        assertThat(scheduleColumn(scheduleId, "last_run_id")).isNotNull();
        assertThat(scheduleColumn(scheduleId, "last_fired_at")).isNotNull();
    }

    /**
     * A failure must not move {@code last_fired_at}: it means "when a run last happened", and no
     * run happened. It is the honest record of the gap the no-catch-up decision leaves.
     */
    @Test
    @DisplayName("a failed fire does not move lastFiredAt")
    void aFailedFireDoesNotClaimARunHappened() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        archiveProject();
        makeDue(scheduleId, minutesAgo(1));

        fire(scheduleId, Instant.now());

        assertThat(scheduleColumn(scheduleId, "last_fired_at"))
                .as("a reader compares this against the cadence to see what did not happen")
                .isNull();
    }

    /**
     * The stored document and the run's own must be the same bytes — one writer
     * ({@code RunScopeDocument}) and one reader ({@code RunScopeRequestView.parse}).
     *
     * <p>That is what makes "why is this case in this run" answerable for a run nobody asked for.
     */
    @Test
    @DisplayName("the run's scope_request is the schedule's own document")
    void theRunRecordsTheSameRequestTheScheduleHolds() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        makeDue(scheduleId, minutesAgo(1));

        fire(scheduleId, Instant.now());

        assertThat(onlyRun().get("scope_request"))
                .isEqualTo(scheduleColumn(scheduleId, "CAST(scope_request AS text)"));
    }

    /**
     * The production query, from the package that owns it.
     *
     * <p>{@code AsyncCompanyContextTest} asserts the same boundary with the poller's predicate in
     * raw SQL, because {@code RunScheduleRepository} is package-private here and that class is in
     * {@code tenancy}. This half drives the actual method, which is the one the scheduler calls —
     * the two together cover the policy and the query, and neither covers both.
     */
    @Test
    @DisplayName("findDue answers nothing with no company open, and fails CLOSED")
    void theDueQueryIsCompanyScoped() throws Exception {
        UUID scheduleId = schedule("Nightly smoke", NIGHTLY, tagScope("smoke"));
        makeDue(scheduleId, minutesAgo(1));

        try (var ignored = CompanyContext.open(ACME)) {
            assertThat(schedules.findDue(Instant.now()))
                    .as("with the company open, the poller sees its due schedule")
                    .hasSize(1);
        }

        assertThat(schedules.findDue(Instant.now()))
                .as("with NO context, the tempting one-query-for-the-instance shape returns zero "
                        + "rows and logs a successful tick having fired nothing — V4's nullif() "
                        + "is what makes that the right failure and an invisible one")
                .isEmpty();
    }

    // =====================================================================
    // Driving the fire
    // =====================================================================

    /**
     * Opens the company OUTSIDE the transaction, which is where it has to be.
     *
     * <p>{@code CompanyAwareTransactionManager} reads {@code CompanyContext} in {@code doBegin}, so
     * a context opened inside the transactional method leaves the GUC unset for its whole life and
     * every policed read answers zero rows without raising anything (§246). The poller does the
     * same, which is the arrangement this helper is copying rather than inventing.
     */
    private void fire(UUID scheduleId, Instant now) {
        try (var ignored = CompanyContext.open(ACME)) {
            firing.fire(scheduleId, now);
        }
    }

    /** Creates a schedule through the real endpoint, so the fixture is checked by the product. */
    private UUID schedule(String name, String cron, Map<String, Object> scope) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("cronExpression", cron);
        body.put("zoneId", "UTC");
        body.put("scope", scope);
        body.put("configurationId", null);
        body.put("assigneeId", null);
        body.put("enabled", true);

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + BANKING + "/run-schedules")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("%s", created.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private static Map<String, Object> tagScope(String tag) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("planId", null);
        scope.put("caseIds", List.of());
        scope.put("suiteIds", List.of());
        scope.put("tags", List.of(tag));
        return scope;
    }

    /** Backdates the slot so the next call to {@link #fire} finds the schedule due. */
    private Instant makeDue(UUID scheduleId, Instant slot) throws SQLException {
        setNextFireAt(scheduleId, slot);
        return slot;
    }

    private static Instant minutesAgo(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
    }

    // =====================================================================
    // Reading rows back, past the API
    // =====================================================================

    private void setNextFireAt(UUID scheduleId, Instant slot) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE run_schedule SET next_fire_at = CAST(? AS timestamptz) "
                             + "WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, slot.toString());
            statement.setString(2, scheduleId.toString());
            statement.executeUpdate();
        }
    }

    private void setEnabled(UUID scheduleId, boolean enabled) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE run_schedule SET enabled = ? WHERE id = CAST(? AS uuid)")) {
            statement.setBoolean(1, enabled);
            statement.setString(2, scheduleId.toString());
            statement.executeUpdate();
        }
    }

    private String scheduleColumn(UUID scheduleId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM run_schedule WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, scheduleId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the schedule row must still be there").isTrue();
                return rows.getString(1);
            }
        }
    }

    private long dueCount(Instant now) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM run_schedule "
                             + "WHERE enabled AND next_fire_at <= CAST(? AS timestamptz)")) {
            statement.setString(1, now.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private Map<String, String> onlyRun() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT name, source, CAST(created_by AS text), "
                             + "CAST(scope_request AS text) FROM test_run "
                             + "WHERE deleted_at IS NULL");
             ResultSet rows = statement.executeQuery()) {

            assertThat(rows.next()).as("exactly one run was expected and none was found").isTrue();
            Map<String, String> run = new LinkedHashMap<>();
            run.put("name", rows.getString(1));
            run.put("source", rows.getString(2));
            run.put("created_by", rows.getString(3));
            run.put("scope_request", rows.getString(4));
            assertThat(rows.next()).as("exactly one run was expected and more were found").isFalse();
            return run;
        }
    }

    private Map<String, String> onlyAuditRow(String action) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT CAST(actor_user_id AS text), CAST(actor_token_id AS text) "
                             + "FROM audit_event WHERE action = ?")) {
            statement.setString(1, action);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no %s audit row was written", action).isTrue();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("actor_user_id", rows.getString(1));
                row.put("actor_token_id", rows.getString(2));
                assertThat(rows.next()).as("more than one %s row", action).isFalse();
                return row;
            }
        }
    }

    /** The same row, read back through the trail the SPA actually renders (§242). */
    private AuditEventView throughTheShippedReader(String action) {
        // The context is opened OUTSIDE the read, never inside it. CompanyAwareTransactionManager
        // reads CompanyContext in doBegin, so a context opened within the transactional method
        // leaves the GUC unset and every policed read answers zero rows without raising anything
        // (§246) — which here would look exactly like "the audit row was never written".
        try (var ignored = CompanyContext.open(ACME)) {
            GateResult<AuditEventPageView> page = auditLog.forProject(AUDITOR, BANKING,
                    new AuditLogQuery(null, null, action, null, null, 0, 50));
            assertThat(page)
                    .as("the auditor is a Test Lead and holds project:audit")
                    .isInstanceOf(GateResult.Allowed.class);
            List<AuditEventView> items =
                    ((GateResult.Allowed<AuditEventPageView>) page).value().items();
            assertThat(items).as("the trail must contain the %s row", action).hasSize(1);
            return items.getFirst();
        }
    }

    private long runCount() throws SQLException {
        return count("SELECT COUNT(*) FROM test_run WHERE deleted_at IS NULL");
    }

    private long executionCount() throws SQLException {
        return count("SELECT COUNT(*) FROM test_execution");
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void archiveProject() throws SQLException {
        setArchived("now()");
    }

    private void unarchiveProject() throws SQLException {
        setArchived("NULL");
    }

    private void setArchived(String value) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE project SET archived_at = " + value + " WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, BANKING.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Viewer, not "no grant".
     *
     * <p>Viewer still holds {@code project:read}, so the gate reaches its permission half and
     * answers {@code Denied} — which is the branch whose message is about the grant. Removing the
     * assignment altogether would answer {@code Unreachable} and a different sentence, and the arm
     * would then be asserting the wrong one.
     */
    private void demoteOwnerToViewer() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE role_assignment SET role_id = CAST(? AS uuid) "
                             + "WHERE user_id = CAST(? AS uuid) AND project_id = CAST(? AS uuid)")) {
            statement.setString(1, Role.VIEWER_ID.toString());
            statement.setString(2, OWNER.toString());
            statement.setString(3, BANKING.toString());
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // Fixture
    // =====================================================================

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void activeUser(UUID id, String email, String canonicalName, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, canonicalName, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    private void member(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    private void grant(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("run-schedule-fire:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    /**
     * Every mutable row this class writes, and the project's archive flag with them.
     *
     * <p>The archive is reset explicitly: {@code project} rows are seeded once and survive between
     * methods, so one archiving test would otherwise stop every later fire in the class — and the
     * failure would read as a defect in the firing service rather than as fixture bleed.
     */
    private void clearMutableRows() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            execute(connection, "DELETE FROM audit_event");
            execute(connection, "DELETE FROM run_schedule");
            execute(connection, "DELETE FROM step_result");
            execute(connection, "DELETE FROM test_execution");
            execute(connection, "DELETE FROM test_run");
            execute(connection, "DELETE FROM test_case_version");
            execute(connection, "DELETE FROM test_step");
            execute(connection, "DELETE FROM case_tag");
            execute(connection, "DELETE FROM test_case");
            execute(connection, "DELETE FROM suite_version");
            execute(connection, "DELETE FROM suite_tag_rule");
            execute(connection, "DELETE FROM suite");
            execute(connection, "DELETE FROM project_settings");
            execute(connection, "UPDATE key_sequence SET next_value = 1");
            execute(connection, "UPDATE project SET archived_at = NULL");
        }
        // And the owner's grant, which one arm demotes.
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE role_assignment SET role_id = CAST(? AS uuid) "
                             + "WHERE user_id = CAST(? AS uuid) AND project_id = CAST(? AS uuid)")) {
            statement.setString(1, Role.TEST_LEAD_ID.toString());
            statement.setString(2, OWNER.toString());
            statement.setString(3, BANKING.toString());
            statement.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTrees() throws Exception {
        try (Connection connection = appConnection(ACME)) {
            try (var statement = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setString(1, ACME.toString());
                statement.setString(2, BANKING.toString());
                statement.execute();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO suite
                        (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TS-1', 1,
                            'Smoke', 0, 'APPROVED')
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, SMOKE_SUITE.toString());
                statement.setString(2, ACME.toString());
                statement.setString(3, BANKING.toString());
                statement.executeUpdate();
            }
        }
    }

    private void tagCase(UUID caseId, String tag) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    private UUID createCase(String title) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", SMOKE_SUITE.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by RunScheduleFiringTest.");
        payload.put("expectedOutcome", "It works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", null);
        step.put("keyword", null);
        step.put("action", "Do the thing");
        step.put("testData", null);
        step.put("expectedResult", null);
        payload.put("steps", List.of(step));

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + BANKING + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("%s", created.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private void createRunManually() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Made by a person");
        body.put("planId", null);
        body.put("configurationId", null);
        body.put("releaseVersion", null);
        body.put("buildNumber", null);
        body.put("commitSha", null);
        body.put("branch", null);
        body.put("caseIds", List.of(smokeCase.toString()));
        body.put("suiteIds", List.of());
        body.put("tags", List.of());
        body.put("assigneeId", null);
        body.put("overrideApprovalGate", null);

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + BANKING + "/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("%s", created.getResponse().getContentAsString()).isEqualTo(201);
    }

    private String tokenFor(UUID userId) {
        return tx.execute(status -> tokens.issue(userId, ACME, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
