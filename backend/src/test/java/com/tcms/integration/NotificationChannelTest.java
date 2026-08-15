package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
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
import com.tcms.tenancy.OutboxEvents;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A project's notification channels — the gate, the credential, and the isolation of all three new
 * tables.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A credential on the wire.</strong> A webhook URL <em>is</em> the authentication for
 *       the room it posts to, so a read model that carried it would put a live secret into every
 *       response, log and browser cache that rendered a settings screen. Asserted on the view and
 *       on the stored bytes, which are two different mistakes.</li>
 *   <li><strong>An update blanking the secret.</strong> The screen never shows one, so an edit form
 *       has nothing to send back — and the usual "null means clear" convention would silently
 *       unconfigure every channel anybody renamed, with the delivery failing later at a time
 *       unconnected to the edit.</li>
 *   <li><strong>The three new tables leaking across companies.</strong> Both spellings of "no
 *       company": never set, and reset to {@code ''} on a recycled pooled connection — {@code V4}'s
 *       trap, which raises {@code invalid input syntax for type uuid} rather than returning nothing
 *       when the {@code nullif} is dropped.</li>
 *   <li><strong>{@code integration:manage} widened.</strong> It is Test Lead and up, which is what
 *       makes this slice a feature rather than a catalogue change — {@code V52} decided the tier
 *       line is approvals and project configuration, and a channel is project configuration.</li>
 * </ol>
 *
 * <p>Ids are unique to this class — {@code d5…}.
 */
@DisplayName("Notification channels — the gate, the credential, and RLS on three new tables")
class NotificationChannelTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("d5000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("d5000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("d5001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_BANKING = UUID.fromString("d5002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("d5003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("d5003000-0000-4000-8000-000000000002");
    private static final UUID OUTSIDER = UUID.fromString("d5003000-0000-4000-8000-000000000003");

    private static final String WEBHOOK =
            "https://hooks.slack.com/services/T00000000/B00000000/AcMeSeCrEtTaIl";

    @Autowired
    private NotificationChannelService service;

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
            company(ACME, "nc-acme", "Acme", now);
            company(UMBRA, "nc-umbra", "Umbra", now);
            activeUser(LEAD, "lead@nc-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@nc-acme.test", "E. Engineer", now);
            activeUser(OUTSIDER, "out@nc-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "NCAOLB", "Online Banking", now);
            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            grant("lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "NCUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        clearChannels(ACME);
        clearChannels(UMBRA);
    }

    // =====================================================================
    // The gate
    // =====================================================================

    @Nested
    @DisplayName("The gate — integration:manage, held by Test Lead and up")
    class Gate {

        @Test
        @DisplayName("a Test Lead creates a channel")
        void aLeadCreates() {
            inCompany(ACME, () -> assertThat(create(LEAD, slack("Releases")))
                    .isInstanceOf(NotificationChannelService.Outcome.Saved.class));
        }

        @Test
        @DisplayName("a Test Engineer reads the list and is refused the write")
        void anEngineerIsRefused() {
            inCompany(ACME, () -> {
                create(LEAD, slack("Releases"));

                assertThat(service.list(ENGINEER, ACME_BANKING))
                        .as("everyone who can see a project can already see whether its notices "
                                + "go anywhere, by receiving one")
                        .get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                        .hasSize(1);
                assertThat(create(ENGINEER, slack("Mine")))
                        .isInstanceOf(NotificationChannelService.Outcome.Forbidden.class);
            });
        }

        @Test
        @DisplayName("another client's project is 404, not 403 — no existence oracle")
        void theOtherClientIs404() {
            inCompany(UMBRA, () -> assertThat(
                    service.create(OUTSIDER, ACME_BANKING, slack("Theirs")))
                    .isInstanceOf(NotificationChannelService.Outcome.NotFound.class));
        }
    }

    // =====================================================================
    // The credential
    // =====================================================================

    @Nested
    @DisplayName("The credential — write-only, encrypted, hinted")
    class Credential {

        @Test
        @DisplayName("the view carries a hint and nothing that could be replayed")
        void theViewHasNoSecret() {
            inCompany(ACME, () -> {
                NotificationChannelView view = saved(create(LEAD, slack("Releases")));

                assertThat(view.secretHint()).isEqualTo("…TaIl");
                assertThat(view.toString())
                        .as("a record's generated toString prints every component — a secret one "
                                + "would leak into any log line that interpolated it")
                        .doesNotContain("hooks.slack.com")
                        .doesNotContain(WEBHOOK);
            });
        }

        @Test
        @DisplayName("the stored column holds no fragment of the URL")
        void theColumnIsCiphertext() throws SQLException {
            UUID channelId = inCompanyReturning(ACME,
                    () -> saved(create(LEAD, slack("Releases"))).id());

            try (Connection connection = appConnection(ACME);
                 var statement = connection.prepareStatement(
                         "SELECT encode(secret, 'escape'), secret_hint FROM notification_channel "
                                 + "WHERE id = CAST(? AS uuid)")) {
                statement.setString(1, channelId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1))
                            .as("this is what a stolen backup contains")
                            .doesNotContain("hooks.slack.com")
                            .doesNotContain("AcMeSeCrEtTaIl");
                    assertThat(rows.getString(2)).isEqualTo("…TaIl");
                }
            }
        }

        @Test
        @DisplayName("an update with no secret leaves the stored one alone")
        void anAbsentSecretMeansUnchanged() {
            inCompany(ACME, () -> {
                UUID channelId = saved(create(LEAD, slack("Releases"))).id();

                NotificationChannelInput renamed = new NotificationChannelInput(
                        NotificationChannelKind.SLACK, "Release channel", true, List.of(), null,
                        List.of(new NotificationChannelInput.SubscriptionInput(
                                OutboxEvents.RUN_COMPLETED, 0)));
                NotificationChannelView view =
                        saved(service.update(LEAD, ACME_BANKING, channelId, renamed));

                assertThat(view.name()).isEqualTo("Release channel");
                assertThat(view.secretHint())
                        .as("the screen never shows a secret, so an edit form has nothing to send "
                                + "back — treating that as a clear unconfigures every channel "
                                + "anybody renames, and the failure arrives hours later")
                        .isEqualTo("…TaIl");
            });
        }

        @Test
        @DisplayName("creating a webhook channel with no URL is a field error, not a broken row")
        void aWebhookNeedsAUrl() {
            inCompany(ACME, () -> {
                NotificationChannelInput noUrl = new NotificationChannelInput(
                        NotificationChannelKind.SLACK, "Releases", true, List.of(), null,
                        List.of());

                assertThat(create(LEAD, noUrl))
                        .isInstanceOf(NotificationChannelService.Outcome.Invalid.class);
            });
        }

        @Test
        @DisplayName("an email channel needs recipients and no credential of its own")
        void anEmailChannelHasNoSecret() {
            inCompany(ACME, () -> {
                assertThat(create(LEAD, new NotificationChannelInput(
                        NotificationChannelKind.EMAIL, "Leads", true, List.of(), null, List.of())))
                        .isInstanceOf(NotificationChannelService.Outcome.Invalid.class);

                NotificationChannelView view = saved(create(LEAD, new NotificationChannelInput(
                        NotificationChannelKind.EMAIL, "Leads", true,
                        List.of("lead@acme.test"), null, List.of())));

                assertThat(view.recipients()).containsExactly("lead@acme.test");
                assertThat(view.secretHint())
                        .as("null is how an email channel and an unconfigured webhook are told "
                                + "apart on the screen")
                        .isNull();
            });
        }
    }

    // =====================================================================
    // Subscriptions
    // =====================================================================

    @Nested
    @DisplayName("Subscriptions — reconciled, never merged")
    class Subscriptions {

        @Test
        @DisplayName("an edit that drops one removes it, which an upsert could not")
        void removalIsPossible() {
            inCompany(ACME, () -> {
                UUID channelId = saved(create(LEAD, new NotificationChannelInput(
                        NotificationChannelKind.SLACK, "Everything", true, List.of(), WEBHOOK,
                        List.of(new NotificationChannelInput.SubscriptionInput(
                                        OutboxEvents.RUN_COMPLETED, 0),
                                new NotificationChannelInput.SubscriptionInput(
                                        OutboxEvents.ITEM_ASSIGNED, 0))))).id();

                NotificationChannelView narrowed = saved(service.update(
                        LEAD, ACME_BANKING, channelId, new NotificationChannelInput(
                                NotificationChannelKind.SLACK, "Runs only", true, List.of(), null,
                                List.of(new NotificationChannelInput.SubscriptionInput(
                                        OutboxEvents.RUN_COMPLETED, 3)))));

                assertThat(narrowed.subscriptions())
                        .extracting(NotificationSubscriptionView::eventType)
                        .containsExactly(OutboxEvents.RUN_COMPLETED);
                assertThat(narrowed.subscriptions().getFirst().minFailures()).isEqualTo(3);
            });
        }

        @Test
        @DisplayName("an event nothing records is refused rather than stored and never fired")
        void anUnknownEventTypeIsRefused() {
            inCompany(ACME, () -> assertThat(create(LEAD, new NotificationChannelInput(
                    NotificationChannelKind.SLACK, "Wishful", true, List.of(), WEBHOOK,
                    List.of(new NotificationChannelInput.SubscriptionInput(
                            "work.defect_created", 0)))))
                    .as("silence is the one outcome an administrator cannot debug")
                    .isInstanceOf(NotificationChannelService.Outcome.Invalid.class));
        }
    }

    // =====================================================================
    // Isolation
    // =====================================================================

    @Nested
    @DisplayName("RLS — three new tables, both spellings of no company")
    class Isolation {

        @Test
        @DisplayName("one client's channels, subscriptions and deliveries are invisible to the other")
        void neitherClientSeesTheOther() throws SQLException {
            // Deliberately hostile, RlsIsolationTest's rule: both companies use the SAME channel
            // name and the same webhook, so a dispatcher or a query that crossed the boundary
            // would return something that looks exactly right.
            UUID acmeChannel =
                    inCompanyReturning(ACME, () -> saved(create(LEAD, slack("Releases"))).id());
            inCompany(UMBRA, () -> service.create(OUTSIDER, UMBRA_BANKING, slack("Releases")));

            seedDelivery(ACME, acmeChannel);

            assertThat(countIn(ACME, "notification_channel")).isOne();
            assertThat(countIn(UMBRA, "notification_channel")).isOne();
            assertThat(countIn(ACME, "notification_subscription")).isOne();
            assertThat(countIn(UMBRA, "notification_subscription")).isOne();
            assertThat(countIn(ACME, "notification_delivery")).isOne();
            assertThat(countIn(UMBRA, "notification_delivery")).isZero();

            // Past RLS, and scoped to this class's two companies: the container is shared for the
            // whole run, so an unscoped count here would be an assertion about every other test
            // class's fixtures.
            try (Connection connection = superuserConnection();
                 var statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM notification_channel "
                                 + "WHERE company_id IN (CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setString(1, ACME.toString());
                statement.setString(2, UMBRA.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    assertThat(rows.getLong(1))
                            .as("ground truth: both rows exist, and each client sees one")
                            .isEqualTo(2);
                }
            }
        }

        @Test
        @DisplayName("a connection with no company sees nothing — both spellings of unset")
        void noCompanySeesNothing() throws SQLException {
            inCompany(ACME, () -> create(LEAD, slack("Releases")));

            // Never set: the GUC reads NULL.
            try (Connection fresh = appConnection()) {
                assertThat(countOn(fresh, "notification_channel")).isZero();
                assertThat(countOn(fresh, "notification_subscription")).isZero();
                assertThat(countOn(fresh, "notification_delivery")).isZero();
            }

            // Set and then reset — which is the state EVERY pooled connection is in after its
            // first transaction, because set_config leaves a reset value of '' rather than NULL.
            // Without V4's nullif() this raises "invalid input syntax for type uuid" instead of
            // answering nothing, which is a 500 rather than a leak and is still a defect.
            try (Connection recycled = appConnection(ACME)) {
                try (var reset = recycled.prepareStatement(
                        "SELECT set_config('app.current_company', '', false)")) {
                    reset.execute();
                }
                assertThat(countOn(recycled, "notification_channel")).isZero();
                assertThat(countOn(recycled, "notification_subscription")).isZero();
                assertThat(countOn(recycled, "notification_delivery")).isZero();
            }
        }

        @Test
        @DisplayName("deleting a channel takes its subscriptions and its history with it")
        void theCascadesHold() throws SQLException {
            UUID channelId =
                    inCompanyReturning(ACME, () -> saved(create(LEAD, slack("Releases"))).id());
            seedDelivery(ACME, channelId);

            inCompany(ACME, () -> assertThat(service.delete(LEAD, ACME_BANKING, channelId))
                    .isInstanceOf(NotificationChannelService.Outcome.Deleted.class));

            assertThat(countIn(ACME, "notification_channel")).isZero();
            assertThat(countIn(ACME, "notification_subscription")).isZero();
            assertThat(countIn(ACME, "notification_delivery"))
                    .as("a history of a channel that no longer exists names a credential nobody "
                            + "can rotate and cannot be replayed into anything")
                    .isZero();
        }
    }

    // =====================================================================
    // Fixture
    // =====================================================================

    private NotificationChannelInput slack(String name) {
        return new NotificationChannelInput(
                NotificationChannelKind.SLACK, name, true, List.of(), WEBHOOK,
                List.of(new NotificationChannelInput.SubscriptionInput(
                        OutboxEvents.RUN_COMPLETED, 0)));
    }

    private NotificationChannelService.Outcome create(UUID userId, NotificationChannelInput input) {
        return service.create(userId, ACME_BANKING, input);
    }

    private static NotificationChannelView saved(NotificationChannelService.Outcome outcome) {
        assertThat(outcome).isInstanceOf(NotificationChannelService.Outcome.Saved.class);
        return ((NotificationChannelService.Outcome.Saved) outcome).view();
    }

    /** A delivery row, written the way the handler writes one — through the ledger's own factory. */
    private void seedDelivery(UUID companyId, UUID channelId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("""
                     INSERT INTO notification_delivery
                            (id, company_id, channel_id, event_id, event_type, status, attempt,
                             created_at, updated_at)
                     VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid),
                             gen_random_uuid(), ?, 'SENT', 1, now(), now())
                     """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, channelId.toString());
            statement.setString(3, OutboxEvents.RUN_COMPLETED);
            statement.executeUpdate();
        }
    }

    private void clearChannels(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.createStatement()) {
            // The cascades take the other two, which is asserted separately rather than assumed.
            statement.executeUpdate("DELETE FROM notification_channel");
        }
    }

    private long countIn(UUID companyId, String table) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            return countOn(connection, table);
        }
    }

    private static long countOn(Connection connection, String table) throws SQLException {
        // The table name is a literal from this class and never from input.
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
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
        UUID id = UUID.nameUUIDFromBytes(("notification-channel:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private static void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    private static <T> T inCompanyReturning(UUID companyId, java.util.function.Supplier<T> work) {
        try (var ignored = CompanyContext.open(companyId)) {
            return work.get();
        }
    }

    @Test
    @DisplayName("fixture precondition: the two personas actually differ on this key")
    void theFixtureDiscriminates() {
        // If both answered the same, every assertion in Gate would pass without the gate existing.
        // The pair is chosen because V52 moved seven keys down to Test Engineer and left this one
        // where it was, so it is a line the suite can still tell apart.
        inCompany(ACME, () -> {
            assertThat(create(LEAD, slack("Probe")))
                    .isInstanceOf(NotificationChannelService.Outcome.Saved.class);
            assertThat(create(ENGINEER, slack("Probe two")))
                    .isInstanceOf(NotificationChannelService.Outcome.Forbidden.class);
        });
    }
}
