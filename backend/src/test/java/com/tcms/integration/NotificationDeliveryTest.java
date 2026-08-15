package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.execution.RunStatusRequest;
import com.tcms.execution.RunStatusService;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.OutboxDispatcher;
import com.tcms.tenancy.OutboxEvents;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>The third leg of the payload pin: producer to handler, through the real outbox.</strong>
 *
 * <h2>Why this exists when both pins already do</h2>
 *
 * <p>{@code com.tcms.execution.RunCompletedPayloadTest} says what the producer writes and
 * {@code RunCompletedPayloadTest} in this package says what the consumer requires. Between them
 * they catch a change on either side — and they do <em>not</em> catch a change made on both sides
 * at once, which is exactly what somebody renaming a field does. This test is the one that fails
 * then, because it drives the real service, writes a real {@code outbox} row, runs the real
 * dispatcher, and asserts a delivery attempt was made against a real channel.
 *
 * <p>It is also the answer to {@code Outbox#record}'s standing debt in the strongest available
 * form: that method had no product caller from §131 until this slice, so the serialization, the
 * aggregate naming and a handler receiving a non-empty payload had never once run outside
 * {@code OutboxDispatchTest}.
 *
 * <h2>The webhook is deliberately unreachable, and the test is stronger for it</h2>
 *
 * <p>Port 1 on loopback refuses instantly, so every assertion below is about the path that
 * <em>fails</em> — which is the path where a credential leaks. What is asserted is that the parse
 * happened at all (a delivery row exists, keyed on an id that could only have come out of the
 * payload), that the secret is in neither durable place it could have landed, and that a retry
 * updates the attempt rather than duplicating the message.
 *
 * <p>Ids are unique to this class — {@code d6…}.
 */
@DisplayName("Notifications end to end — a closed run reaches a channel, and the URL does not leak")
@TestPropertySource(properties = {
        // Zero backoff so a retry is due immediately: the subject is the second attempt's
        // bookkeeping, not the ladder's arithmetic, which OutboxPropertiesTest owns.
        "tcms.outbox.backoff=PT0S"
})
class NotificationDeliveryTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("d6000000-0000-4000-8000-00000000000a");
    private static final UUID BANKING = UUID.fromString("d6001000-0000-4000-8000-000000000001");
    private static final UUID LEAD = UUID.fromString("d6003000-0000-4000-8000-000000000001");
    private static final UUID RUN = UUID.fromString("d6004000-0000-4000-8000-000000000001");

    /**
     * Refused instantly by the kernel, so nothing here waits on a timeout — and the path is the
     * real one: {@code WebhookPoster} builds a request and the client raises.
     */
    private static final String WEBHOOK = "http://127.0.0.1:1/services/T0/B0/AcMeSeCrEtTaIl";

    @Autowired
    private NotificationChannelService channels;

    @Autowired
    private RunStatusService runStatus;

    @Autowired
    private OutboxDispatcher dispatcher;

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
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            if (companies.findById(ACME).isEmpty()) {
                companies.save(Company.provision(ACME, "nd-acme", "Acme", now));
            }
            if (users.findById(LEAD).isEmpty()) {
                AppUser user = AppUser.invite(LEAD, "lead@nd-acme.test", "L. Lead", now);
                user.activate("hash", now);
                users.save(user);
            }
        });

        try (var ignored = CompanyContext.open(ACME)) {
            tx.executeWithoutResult(status -> {
                if (projects.findScopedById(BANKING).isEmpty()) {
                    projects.save(Project.create(BANKING, ACME, "NDAOLB", "Online Banking", null, now));
                }
                if (memberships.findByCompanyIdAndUserId(ACME, LEAD).isEmpty()) {
                    CompanyMembership membership =
                            CompanyMembership.invite(ACME, LEAD, "L. Lead", null, now);
                    membership.accept(now);
                    memberships.save(membership);
                }
                UUID grantId = UUID.nameUUIDFromBytes("notification-delivery:lead".getBytes());
                if (roleAssignments.findById(grantId).isEmpty()) {
                    roleAssignments.save(RoleAssignment.forUserOnProject(
                            grantId, ACME, Role.TEST_LEAD_ID, LEAD, BANKING, null, now));
                }
            });
        }

        seedDefaults();
        clearRows();
        seedRun();
    }

    @Test
    @DisplayName("closing a run records an occurrence with a payload, inside the same transaction")
    void theProducerRecordsAPayload() throws SQLException {
        completeTheRun();

        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT aggregate_type, aggregate_id, event_type, payload::text FROM outbox")) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the close and its notice commit together").isTrue();
                assertThat(rows.getString(1)).isEqualTo("run");
                assertThat(UUID.fromString(rows.getString(2))).isEqualTo(RUN);
                assertThat(rows.getString(3)).isEqualTo(OutboxEvents.RUN_COMPLETED);
                assertThat(rows.getString(4))
                        .as("the payload IS the message — integration may not read test_run")
                        // JSONB, so Postgres renders it back with its own spacing rather than the
                        // producer's — the fields are what is being asserted, not the bytes.
                        .contains("\"runKey\": \"ND-1\"")
                        .contains("\"projectId\": \"" + BANKING + "\"")
                        .contains("\"status\": \"COMPLETED\"");
                assertThat(rows.next()).as("exactly one occurrence, not one per handler").isFalse();
            }
        }
    }

    @Test
    @DisplayName("reopening and aborting record nothing — the event is COMPLETED, not \"status moved\"")
    void onlyCompletionIsAnOccurrence() throws SQLException {
        inCompany(() -> {
            runStatus.setStatus(LEAD, BANKING, RUN, new RunStatusRequest("ABORTED"));
            runStatus.setStatus(LEAD, BANKING, RUN, new RunStatusRequest("IN_PROGRESS"));
        });

        assertThat(count("SELECT COUNT(*) FROM outbox"))
                .as("a single \"the status moved\" name would make every subscriber re-derive "
                        + "which moves it cared about, and one of them would announce a reopened "
                        + "run as finished")
                .isZero();
    }

    @Test
    @DisplayName("the handler parses the payload and attempts the subscribed channel")
    void theConsumerReadsWhatTheProducerWrote() throws SQLException {
        UUID channelId = channel("Releases", 0);
        completeTheRun();

        dispatch();

        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT channel_id, event_type, status, attempt, error
                       FROM notification_delivery
                     """)) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("a delivery row can only exist if the payload parsed — its project id "
                                + "and event id came out of the document and nowhere else")
                        .isTrue();
                assertThat(UUID.fromString(rows.getString(1))).isEqualTo(channelId);
                assertThat(rows.getString(2)).isEqualTo(OutboxEvents.RUN_COMPLETED);
                assertThat(rows.getString(3)).isEqualTo("FAILED");
                assertThat(rows.getInt(4)).isEqualTo(1);
                assertThat(rows.getString(5)).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("neither durable place records the webhook URL — the leak with no symptom")
    void theCredentialIsInNeitherErrorColumn() throws SQLException {
        channel("Releases", 0);
        completeTheRun();

        dispatch();

        assertThat(text("SELECT error FROM notification_delivery"))
                .as("this column is browsed by an operator, copied by a backup and exported in a "
                        + "support bundle — and an HTTP client's message quotes the URL it posted "
                        + "to, which for this one caller IS the credential")
                .doesNotContain("AcMeSeCrEtTaIl")
                .doesNotContain(WEBHOOK)
                .contains(SecretRedaction.REPLACEMENT);

        assertThat(text("SELECT last_error FROM outbox"))
                .as("the exception the handler throws is written here, so it names channels and "
                        + "not causes")
                .doesNotContain("AcMeSeCrEtTaIl")
                .doesNotContain("127.0.0.1")
                .contains("Releases");
    }

    @Test
    @DisplayName("a retry updates the attempt rather than sending a second message")
    void theDeliveryIsKeyedOnTheOccurrence() throws SQLException {
        channel("Releases", 0);
        completeTheRun();

        dispatch();
        makeTheOutboxRowDue();
        dispatch();

        assertThat(count("SELECT COUNT(*) FROM notification_delivery"))
                .as("one row per (channel, occurrence) — at-least-once delivery is what the key "
                        + "exists for")
                .isOne();
        assertThat(count("SELECT attempt FROM notification_delivery")).isEqualTo(2);
    }

    @Test
    @DisplayName("a threshold the run does not clear leaves no attempt at all")
    void theThresholdFilters() throws SQLException {
        // This run has no executions, so it has no failures. A subscription that only wants runs
        // with at least one is not told — and the predicate lives on the subscription, so
        // `execution` never learns what any subscriber considers alarming.
        channel("Only when it breaks", 1);
        completeTheRun();

        dispatch();

        assertThat(count("SELECT COUNT(*) FROM notification_delivery")).isZero();
    }

    @Test
    @DisplayName("a disabled channel is not attempted")
    void aDisabledChannelIsSilent() throws SQLException {
        UUID channelId = channel("Releases", 0);
        inCompany(() -> channels.update(LEAD, BANKING, channelId, new NotificationChannelInput(
                NotificationChannelKind.SLACK, "Releases", false, List.of(), null,
                List.of(new NotificationChannelInput.SubscriptionInput(
                        OutboxEvents.RUN_COMPLETED, 0)))));
        completeTheRun();

        dispatch();

        assertThat(count("SELECT COUNT(*) FROM notification_delivery"))
                .as("`enabled` is applied in the query rather than at a call site, so \"switched "
                        + "off\" cannot come to mean \"still delivered but not shown\"")
                .isZero();
    }

    @Test
    @DisplayName("no subscribed channel is a successful delivery, not a DEAD row")
    void nobodySubscribedIsStillDelivered() throws SQLException {
        completeTheRun();

        dispatch();

        assertThat(text("SELECT status FROM outbox"))
                .as("a handler claims the type, so the row is SENT — DEAD means nothing in this "
                        + "deployment consumes the event at all, which is a different fact")
                .isEqualTo("SENT");
    }

    // =====================================================================
    // Fixture
    // =====================================================================

    private void completeTheRun() {
        inCompany(() -> assertThat(
                runStatus.setStatus(LEAD, BANKING, RUN, new RunStatusRequest("COMPLETED")))
                .isInstanceOf(RunStatusService.Outcome.Updated.class));
    }

    private void dispatch() {
        inCompany(() -> dispatcher.dispatchDue());
    }

    private UUID channel(String name, int minFailures) {
        return inCompanyReturning(() -> {
            NotificationChannelService.Outcome outcome = channels.create(LEAD, BANKING,
                    new NotificationChannelInput(
                            NotificationChannelKind.SLACK, name, true, List.of(), WEBHOOK,
                            List.of(new NotificationChannelInput.SubscriptionInput(
                                    OutboxEvents.RUN_COMPLETED, minFailures))));
            assertThat(outcome).isInstanceOf(NotificationChannelService.Outcome.Saved.class);
            return ((NotificationChannelService.Outcome.Saved) outcome).view().id();
        });
    }

    /** Due again, so the next dispatch re-delivers what the first one failed at. */
    private void makeTheOutboxRowDue() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE outbox SET next_attempt_at = now() - interval '1 hour'");
        }
    }

    private void seedDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            try (var statement = connection.prepareStatement(
                    "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
                statement.setString(1, ACME.toString());
                statement.execute();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setString(1, ACME.toString());
                statement.setString(2, BANKING.toString());
                statement.execute();
            }
        }
    }

    private void clearRows() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM notification_channel");
            statement.executeUpdate("DELETE FROM outbox");
            statement.executeUpdate("DELETE FROM audit_event");
            statement.executeUpdate("DELETE FROM test_run");
        }
    }

    /**
     * One run, IN_PROGRESS, with no executions.
     *
     * <p>Inserted rather than created through {@code POST /runs}: this class is about what happens
     * <em>after</em> a run closes, and building a plan with a scope to materialize a checklist
     * would put a second slice's fixture in front of the subject. IN_PROGRESS because
     * {@code RunLifecycle} refuses to complete a run nothing was recorded in — a 0% run reported
     * as a pass is the failure that rule exists for.
     */
    private void seedRun() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     INSERT INTO test_run (id, company_id, project_id, configuration_id, key,
                                           key_seq, name, status, started_at)
                     SELECT CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), c.id, 'ND-1', 1,
                            'Regression 4.3', 'IN_PROGRESS', now()
                       FROM configuration c
                      WHERE c.project_id = CAST(? AS uuid) AND c.is_default
                     """)) {
            statement.setString(1, RUN.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, BANKING.toString());
            statement.setString(4, BANKING.toString());
            assertThat(statement.executeUpdate())
                    .as("tcms_seed_project_defaults must have seeded a default configuration")
                    .isOne();
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private String text(String sql) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).as("a row was expected for: %s", sql).isTrue();
            return rows.getString(1);
        }
    }

    private static void inCompany(Runnable work) {
        try (var ignored = CompanyContext.open(ACME)) {
            work.run();
        }
    }

    private static <T> T inCompanyReturning(java.util.function.Supplier<T> work) {
        try (var ignored = CompanyContext.open(ACME)) {
            return work.get();
        }
    }
}
