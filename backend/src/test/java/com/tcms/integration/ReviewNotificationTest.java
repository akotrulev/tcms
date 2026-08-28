package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.execution.PlanCreateService;
import com.tcms.execution.PlanStatusRequest;
import com.tcms.execution.PlanStatusService;
import com.tcms.execution.PlanWriteRequest;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.EditAfterApproval;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectSettingsRequest;
import com.tcms.project.ProjectSettingsService;
import com.tcms.project.ReviewAssignmentRequest;
import com.tcms.project.ReviewParticipationRequest;
import com.tcms.project.ReviewService;
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
 * <strong>The third leg of the review payload pins: producer to handler, through the real
 * outbox.</strong>
 *
 * <p>{@code com.tcms.project.ReviewEventPayloadTest} says what the producer writes and
 * {@link ReviewPayloadTest} says what the consumer requires. Between them they catch a change on
 * either side — and they do <em>not</em> catch a change made on both at once, which is exactly what
 * renaming a field looks like. This is the test that fails then, because it drives the real
 * services, writes real {@code outbox} rows, runs the real dispatcher and asserts delivery attempts
 * against a real channel. {@link NotificationDeliveryTest}'s header carries the argument in full,
 * and this class copies its fixture down to the unreachable webhook.
 *
 * <p><strong>The subject is a plan throughout</strong>, because a plan is the artifact this suite
 * can put into review in two calls. Which of the three types it is changes nothing about the path
 * under test: one {@code ReviewService} records all four occurrences, and the subject directory it
 * is resolved through is chosen by the payload's own {@code subjectType}.
 *
 * <p>Ids are unique to this class — {@code ba…}.
 */
@DisplayName("Review notifications end to end — asked, judged and signed off all arrive")
@TestPropertySource(properties = {
        // Zero backoff so a retry is due immediately — NotificationDeliveryTest's reason.
        "tcms.outbox.backoff=PT0S"
})
class ReviewNotificationTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ba000000-0000-4000-8000-00000000000a");
    private static final UUID BANKING = UUID.fromString("ba001000-0000-4000-8000-000000000001");
    private static final UUID LEAD = UUID.fromString("ba003000-0000-4000-8000-000000000001");
    private static final UUID PEER = UUID.fromString("ba003000-0000-4000-8000-000000000002");

    /** Refused instantly by the kernel, so nothing here waits on a timeout. */
    private static final String WEBHOOK = "http://127.0.0.1:1/services/T0/B0/rEvIeWsEcReT";

    @Autowired
    private NotificationChannelService channels;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private PlanCreateService planCreate;

    @Autowired
    private PlanStatusService planStatus;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private ProjectSettingsService settings;

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
                companies.save(Company.provision(ACME, "rn-acme", "Acme", now));
            }
            seedPerson(LEAD, "lead@rn-acme.test", "L. Lead", now);
            seedPerson(PEER, "peer@rn-acme.test", "P. Peer", now);
        });

        try (var ignored = CompanyContext.open(ACME)) {
            tx.executeWithoutResult(status -> {
                if (projects.findScopedById(BANKING).isEmpty()) {
                    projects.save(
                            Project.create(BANKING, ACME, "RNAOLB", "Online Banking", null, now));
                }
                seedMembership(LEAD, "L. Lead", now);
                seedMembership(PEER, "P. Peer", now);
                grant("review-notification:lead", LEAD, now);
                grant("review-notification:peer", PEER, now);
            });
        }

        seedDefaults();
        clearRows();
        enableReview(true);
    }

    // =====================================================================
    // The four occurrences
    // =====================================================================

    @Test
    @DisplayName("asking somebody to review reaches a subscribed channel, naming the artifact")
    void theRequestIsDelivered() throws SQLException {
        UUID channelId = channel("Reviews", OutboxEvents.REVIEW_REQUESTED);
        UUID review = openAReview();

        askPeerToReview(review);
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_REQUESTED))
                .as("a delivery row can only exist if the payload parsed — its project id and "
                        + "event id came out of the document and nowhere else")
                .isEqualTo(1);
        assertThat(deliveredChannel(OutboxEvents.REVIEW_REQUESTED)).isEqualTo(channelId);
        assertThat(payloadOf(OutboxEvents.REVIEW_REQUESTED))
                .as("the payload IS the message — integration may not read review or test_plan, "
                        + "so everything the handler needs to ask a question with is in here")
                .contains("\"subjectType\": \"PLAN\"")
                .contains("\"assigneeUserId\": \"" + PEER + "\"")
                .contains("\"assigneeTeamId\": null");
    }

    @Test
    @DisplayName("the two peer verdicts are two event types, and a channel may take just one")
    void theVerdictsAreSeparatelySubscribable() throws SQLException {
        channel("Only when it needs work", OutboxEvents.REVIEW_CHANGES_REQUESTED);
        UUID review = openAReview();

        recordVerdict(review, "LGTM");
        dispatch();

        // THE MAPPING, asserted on the occurrence and in both directions. Counting deliveries
        // alone does not observe it: the channel here takes one of the two names, so swapping the
        // arms of the switch in ReviewService moves BOTH verdicts and the delivery totals come out
        // identical. Measured — the swap was applied and this test stayed green until these two
        // lines existed. §241's finding in a new place: a check written about the total passes
        // straight through a defect in which one it was.
        assertThat(occurrences(OutboxEvents.REVIEW_ENDORSED))
                .as("an LGTM is an endorsement, and nothing else records that name")
                .isEqualTo(1);
        assertThat(occurrences(OutboxEvents.REVIEW_CHANGES_REQUESTED))
                .as("and it is emphatically not a request for changes")
                .isZero();
        assertThat(deliveries(OutboxEvents.REVIEW_ENDORSED))
                .as("""
                        The subscription names one event type. An LGTM arriving on a channel that \
                        asked only for changes-requested is what a single "a peer spoke" name \
                        would have made unavoidable — every subscriber re-deriving which verdict \
                        it wanted, and one of them getting it wrong.""")
                .isZero();

        recordVerdict(review, "CHANGES_REQUESTED");
        dispatch();

        assertThat(occurrences(OutboxEvents.REVIEW_CHANGES_REQUESTED)).isEqualTo(1);
        assertThat(deliveries(OutboxEvents.REVIEW_CHANGES_REQUESTED))
                .as("the one the channel asked for does arrive — otherwise the arm above would "
                        + "pass on a module that delivers nothing at all")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("approving delivers the sign-off, naming the version that was reviewed")
    void theApprovalIsDelivered() throws SQLException {
        channel("Reviews", OutboxEvents.REVIEW_APPROVED);
        UUID plan = aPlanInReview();

        approve(plan);
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_APPROVED)).isEqualTo(1);
        assertThat(payloadOf(OutboxEvents.REVIEW_APPROVED))
                .contains("\"subjectType\": \"PLAN\"")
                .contains("\"approvedBy\": \"" + LEAD + "\"")
                .as("stamped at submission and never re-stamped — the version somebody was asked "
                        + "to read, not whatever the artifact says now")
                .contains("\"subjectVersionNo\": 1");
    }

    @Test
    @DisplayName("with review switched off, approving records nothing at all")
    void aProjectWithoutReviewIsSilent() throws SQLException {
        enableReview(false);
        channel("Reviews", OutboxEvents.REVIEW_APPROVED);
        UUID plan = aPlanInReview();

        approve(plan);
        dispatch();

        assertThat(occurrences(OutboxEvents.REVIEW_APPROVED))
                .as("""
                        §6g's opt-in: no review is opened, so there is nothing to have been asked \
                        about or signed off, and an event here would announce a review that did \
                        not happen. The whole review.* vocabulary is facts about a review.""")
                .isZero();
        assertThat(deliveries(OutboxEvents.REVIEW_APPROVED)).isZero();
    }

    @Test
    @DisplayName("a retry updates the attempt rather than sending a second message")
    void theDeliveryIsKeyedOnTheOccurrence() throws SQLException {
        channel("Reviews", OutboxEvents.REVIEW_REQUESTED);
        UUID review = openAReview();

        askPeerToReview(review);
        dispatch();
        makeTheOutboxRowDue();
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_REQUESTED))
                .as("""
                        One row per (channel, occurrence) — at-least-once delivery is what the key \
                        exists for, and a notifier that minted a fresh eventId per attempt would \
                        double-send on every retry.""")
                .isEqualTo(1);
        assertThat(attemptsFor(OutboxEvents.REVIEW_REQUESTED)).isEqualTo(2);
    }

    @Test
    @DisplayName("no subscribed channel is a successful delivery, not a DEAD row")
    void nobodySubscribedIsStillDelivered() throws SQLException {
        UUID review = openAReview();

        askPeerToReview(review);
        dispatch();

        assertThat(statusOf(OutboxEvents.REVIEW_REQUESTED))
                .as("a handler claims the type, so the row is SENT — DEAD means nothing in this "
                        + "deployment consumes the event at all, which is a different fact")
                .isEqualTo("SENT");
    }

    // =====================================================================
    // Fixture
    // =====================================================================

    private void seedPerson(UUID id, String email, String name, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, name, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void seedMembership(UUID userId, String name, Instant now) {
        if (memberships.findByCompanyIdAndUserId(ACME, userId).isEmpty()) {
            CompanyMembership membership = CompanyMembership.invite(ACME, userId, name, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    /** Test Lead on the project, for both people — the peer needs {@code plan:review} to speak. */
    private void grant(String seed, UUID userId, Instant now) {
        UUID grantId = UUID.nameUUIDFromBytes(seed.getBytes());
        if (roleAssignments.findById(grantId).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    grantId, ACME, Role.TEST_LEAD_ID, userId, BANKING, null, now));
        }
    }

    /**
     * §6g's workflow, on or off for all three artifact types.
     *
     * <p>Through the real endpoint's service rather than the entity, because
     * {@code ProjectSettings}' mutators are package-private to {@code project} — which is the right
     * shape and means this class configures the project the way an administrator does.
     *
     * <p>Zero required peers and the author permitted to approve, because this class is about what
     * reaches a channel and not about the policy that decides whether a move is allowed —
     * {@code ReviewEndpointTest} owns that, and a fixture that made approval conditional here would
     * make a silent channel ambiguous between "not notified" and "not approved".
     */
    private void enableReview(boolean on) {
        inCompany(() -> assertThat(settings.update(LEAD, BANKING, new ProjectSettingsRequest(
                on, on, on, on, 0, true, EditAfterApproval.RETAIN.name(), false)))
                .isInstanceOf(ProjectSettingsService.Outcome.Updated.class));
    }

    /** A plan, submitted — which is what opens the review when the workflow is on. */
    private UUID aPlanInReview() {
        return inCompanyReturning(() -> {
            PlanCreateService.Outcome created = planCreate.create(
                    LEAD,
                    BANKING,
                    new PlanWriteRequest("Release " + UUID.randomUUID(), null, null, null,
                            List.of(), List.of(), null, null, null, null, null, null),
                    null);
            assertThat(created)
                    .as("the fixture this class depends on")
                    .isInstanceOf(PlanCreateService.Outcome.Created.class);
            UUID planId = ((PlanCreateService.Outcome.Created) created).view().id();

            assertThat(planStatus.setStatus(LEAD, BANKING, planId,
                    new PlanStatusRequest("IN_REVIEW")))
                    .isInstanceOf(PlanStatusService.Outcome.Updated.class);
            return planId;
        });
    }

    /** The open review that {@link #aPlanInReview()} caused the ladder to write. */
    private UUID openAReview() throws SQLException {
        UUID planId = aPlanInReview();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM review WHERE subject_id = CAST(? AS uuid) "
                             + "AND state = 'IN_REVIEW'")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("submitting a plan with review on opens exactly one review")
                        .isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private void askPeerToReview(UUID reviewId) {
        inCompany(() -> assertThat(reviews.assign(
                LEAD, BANKING, reviewId, new ReviewAssignmentRequest(PEER, null)))
                .isInstanceOf(ReviewService.Outcome.Recorded.class));
    }

    private void recordVerdict(UUID reviewId, String verdict) {
        inCompany(() -> assertThat(reviews.record(
                PEER, BANKING, reviewId, new ReviewParticipationRequest(verdict, null)))
                .isInstanceOf(ReviewService.Outcome.Recorded.class));
    }

    private void approve(UUID planId) {
        inCompany(() -> assertThat(planStatus.setStatus(
                LEAD, BANKING, planId, new PlanStatusRequest("APPROVED")))
                .isInstanceOf(PlanStatusService.Outcome.Updated.class));
    }

    private void dispatch() {
        inCompany(dispatcher::dispatchDue);
    }

    private UUID channel(String name, String eventType) {
        return inCompanyReturning(() -> {
            NotificationChannelService.Outcome outcome = channels.create(LEAD, BANKING,
                    new NotificationChannelInput(
                            NotificationChannelKind.SLACK, name, true, List.of(), WEBHOOK,
                            List.of(new NotificationChannelInput.SubscriptionInput(eventType, 0))));
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
            statement.executeUpdate("DELETE FROM review_assignment");
            statement.executeUpdate("DELETE FROM review_participation");
            statement.executeUpdate("DELETE FROM review");
            statement.executeUpdate("DELETE FROM test_plan");
        }
    }

    private long occurrences(String eventType) throws SQLException {
        return count("SELECT count(*) FROM outbox WHERE event_type = '" + eventType + "'");
    }

    private long deliveries(String eventType) throws SQLException {
        return count("SELECT count(*) FROM notification_delivery WHERE event_type = '"
                + eventType + "'");
    }

    private long attemptsFor(String eventType) throws SQLException {
        return count("SELECT attempt FROM notification_delivery WHERE event_type = '"
                + eventType + "'");
    }

    private UUID deliveredChannel(String eventType) throws SQLException {
        return UUID.fromString(text("SELECT channel_id::text FROM notification_delivery "
                + "WHERE event_type = '" + eventType + "'"));
    }

    private String payloadOf(String eventType) throws SQLException {
        // JSONB, so Postgres renders it back with its own spacing rather than the producer's — the
        // fields are what is being asserted, not the bytes.
        return text("SELECT payload::text FROM outbox WHERE event_type = '" + eventType + "'");
    }

    private String statusOf(String eventType) throws SQLException {
        return text("SELECT status FROM outbox WHERE event_type = '" + eventType + "'");
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
