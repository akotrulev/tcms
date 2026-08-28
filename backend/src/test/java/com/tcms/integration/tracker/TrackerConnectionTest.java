package com.tcms.integration.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
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
import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bindings, identities, and who has connected — the gate, the credential and the isolation of all
 * three new tables.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A colleague's credential row reachable through any endpoint.</strong> RLS gives
 *       company isolation and <em>not</em> per-person isolation, so the whole of that boundary is
 *       {@link TrackerIdentityService} filtering on {@code user_id = the caller}. A schema reader
 *       who sees {@code FORCE ROW LEVEL SECURITY} on {@code tracker_identity} would reasonably
 *       conclude the database has it covered; it does not, and this is what says so.</li>
 *   <li><strong>{@code /tracker/connected-members} growing a field.</strong> D19 was taken over a
 *       stated privacy counter-argument and bounded by what the screen may show. The natural
 *       implementation reuses {@link TrackerIdentityView}, which carries five fields this endpoint
 *       must not, so the assertion <strong>closes the set of response keys</strong> rather than
 *       naming the leaks somebody thought of.</li>
 *   <li><strong>A project with no default binding, or two.</strong> "At most one" is an index;
 *       "never zero" is the service, and a project with a binding and no default makes "where does
 *       a defect get filed" unanswerable on the one screen where the answer has to be immediate.</li>
 *   <li><strong>A host the guard would refuse being stored anyway.</strong> Under per-user
 *       credentials that is not one bot's token going somewhere unintended, it is one per
 *       person.</li>
 *   <li><strong>The three new tables leaking across companies</strong>, in both spellings of "no
 *       company": never set, and reset to {@code ''} on a recycled pooled connection — {@code V4}'s
 *       trap.</li>
 * </ol>
 *
 * <p>The RLS arms live here rather than in {@code RlsIsolationTest}, following
 * {@code NotificationChannelTest}'s precedent: that class is about the mechanism and its two traps,
 * and a feature's tables are asserted beside the feature that writes them.
 *
 * <p>Ids are unique to this class — {@code dc…}.
 */
@DisplayName("Tracker connections — the gate, the credential, and RLS on three new tables")
class TrackerConnectionTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("dc000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("dc000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("dc001000-0000-4000-8000-000000000001");
    private static final UUID ACME_PAYMENTS = UUID.fromString("dc001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("dc002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("dc003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("dc003000-0000-4000-8000-000000000002");
    private static final UUID OUTSIDER = UUID.fromString("dc003000-0000-4000-8000-000000000003");

    private static final String JIRA = "https://acme.atlassian.net";
    private static final String TOKEN = "atl-token-EnDsWiThThIs";

    @Autowired
    private TrackerBindingService bindings;

    @Autowired
    private TrackerIdentityService identities;

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
            company(ACME, "tc-acme", "Acme", now);
            company(UMBRA, "tc-umbra", "Umbra", now);
            activeUser(LEAD, "lead@tc-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@tc-acme.test", "E. Engineer", now);
            activeUser(OUTSIDER, "out@tc-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "TCAOLB", "Online Banking", now);
            seedProject(ACME_PAYMENTS, ACME, "TCAPAY", "Payments", now);
            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            grant("lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("leadpay", ACME, Role.TEST_LEAD_ID, LEAD, ACME_PAYMENTS, now);
            grant("eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "TCUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        clearTracker(ACME);
        clearTracker(UMBRA);
    }

    // =====================================================================
    // Bindings
    // =====================================================================

    @Nested
    @DisplayName("Bindings — integration:manage, and exactly one default")
    class Bindings {

        @Test
        @DisplayName("a Test Lead binds; a Test Engineer is refused the read as well as the write")
        void theGateIsIntegrationManage() {
            inCompany(ACME, () -> {
                assertThat(bindings.create(LEAD, ACME_BANKING, jira("PAY")))
                        .isInstanceOf(TrackerBindingService.Outcome.Saved.class);

                assertThat(bindings.list(ENGINEER, ACME_BANKING))
                        .as("403, not 404 — the caller can see the project, so concealing the "
                                + "feature would say the project had no trackers rather than that "
                                + "they may not look")
                        .isInstanceOf(GateResult.Denied.class);
                assertThat(bindings.create(ENGINEER, ACME_BANKING, jira("MINE")))
                        .isInstanceOf(TrackerBindingService.Outcome.Forbidden.class);
            });
        }

        @Test
        @DisplayName("the first binding is the default whether or not it asked to be")
        void theFirstBindingIsTheDefault() {
            inCompany(ACME, () -> {
                TrackerBindingInput asked = new TrackerBindingInput(
                        "JIRA_CLOUD", JIRA, "PAY", Map.of(), false, true);

                assertThat(saved(bindings.create(LEAD, ACME_BANKING, asked)).isDefault())
                        .as("a project with a binding and no default makes \"where does a defect "
                                + "get filed\" unanswerable")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("a later default MOVES the flag — never zero and never two")
        void theDefaultMoves() {
            inCompany(ACME, () -> {
                UUID first = saved(bindings.create(LEAD, ACME_BANKING, jira("PAY"))).id();
                TrackerBindingInput second = new TrackerBindingInput(
                        "AZURE_DEVOPS", "https://dev.azure.com", "acme/payments",
                        Map.of(), true, true);

                UUID promoted = saved(bindings.create(LEAD, ACME_BANKING, second)).id();

                List<TrackerBindingView> all = allowed(bindings.list(LEAD, ACME_BANKING));
                assertThat(all).filteredOn(TrackerBindingView::isDefault)
                        .extracting(TrackerBindingView::id)
                        .containsExactly(promoted);
                assertThat(all).extracting(TrackerBindingView::id).contains(first);
            });
        }

        @Test
        @DisplayName("clearing the current default is refused, with the remedy in the message")
        void theDefaultCannotBeCleared() {
            inCompany(ACME, () -> {
                TrackerBindingView binding = saved(bindings.create(LEAD, ACME_BANKING, jira("PAY")));

                TrackerBindingService.Outcome outcome = bindings.update(
                        LEAD, ACME_BANKING, binding.id(), binding.lockVersion(),
                        new TrackerBindingPatch(null, null, false, null));

                assertThat(outcome).isInstanceOf(TrackerBindingService.Outcome.Invalid.class);
                assertThat(((TrackerBindingService.Outcome.Invalid) outcome).errors())
                        .containsKey("isDefault");
            });
        }

        @Test
        @DisplayName("deleting the default promotes the oldest remaining binding")
        void deletingTheDefaultPromotes() {
            inCompany(ACME, () -> {
                UUID first = saved(bindings.create(LEAD, ACME_BANKING, jira("PAY"))).id();
                TrackerBindingInput second = new TrackerBindingInput(
                        "AZURE_DEVOPS", "https://dev.azure.com", "acme/payments",
                        Map.of(), true, true);
                UUID promoted = saved(bindings.create(LEAD, ACME_BANKING, second)).id();

                bindings.delete(LEAD, ACME_BANKING, promoted);

                assertThat(allowed(bindings.list(LEAD, ACME_BANKING)))
                        .filteredOn(TrackerBindingView::isDefault)
                        .extracting(TrackerBindingView::id)
                        .containsExactly(first);
            });
        }

        @Test
        @DisplayName("a destination the guard refuses is a field error on baseUrl, not a stored row")
        void theGuardRunsAtTheWrite() {
            inCompany(ACME, () -> {
                TrackerBindingService.Outcome plain = bindings.create(LEAD, ACME_BANKING,
                        new TrackerBindingInput(
                                "JIRA_CLOUD", "http://acme.atlassian.net", "PAY",
                                Map.of(), true, true));

                assertThat(plain).isInstanceOf(TrackerBindingService.Outcome.Invalid.class);
                assertThat(((TrackerBindingService.Outcome.Invalid) plain).errors())
                        .as("under per-user credentials this is not one bot's token going "
                                + "somewhere unintended, it is one per connected person")
                        .containsKey("baseUrl");

                assertThat(allowed(bindings.list(LEAD, ACME_BANKING))).isEmpty();
            });
        }

        @Test
        @DisplayName("the same tracker project on the same host twice is a 409, not a second row")
        void aDuplicateIsRefused() {
            inCompany(ACME, () -> {
                bindings.create(LEAD, ACME_BANKING, jira("PAY"));

                assertThat(bindings.create(LEAD, ACME_BANKING, jira("PAY")))
                        .isInstanceOf(TrackerBindingService.Outcome.Duplicate.class);
            });
        }

        @Test
        @DisplayName("an If-Match naming a version somebody else moved is a conflict")
        void aStaleEditIsRefused() {
            inCompany(ACME, () -> {
                TrackerBindingView first = saved(bindings.create(LEAD, ACME_BANKING, jira("PAY")));

                bindings.update(LEAD, ACME_BANKING, first.id(), first.lockVersion(),
                        new TrackerBindingPatch("ONCE", null, null, null));

                assertThat(bindings.update(LEAD, ACME_BANKING, first.id(), first.lockVersion(),
                        new TrackerBindingPatch("TWICE", null, null, null)))
                        .as("two leads really do hold the settings screen open, and a "
                                + "last-write-wins config silently discards the JQL scope the "
                                + "other one just narrowed")
                        .isInstanceOf(TrackerBindingService.Outcome.Stale.class);
            });
        }

        @Test
        @DisplayName("a binding in a sibling project is the same 404 an unreachable project is")
        void aSiblingsBindingIsNotFound() {
            inCompany(ACME, () -> {
                UUID bindingId = saved(bindings.create(LEAD, ACME_BANKING, jira("PAY"))).id();

                assertThat(allowed(bindings.find(LEAD, ACME_PAYMENTS, bindingId))).isNull();
            });
        }

        @Test
        @DisplayName("another client's project is 404, not 403 — no existence oracle")
        void theOtherClientIs404() {
            inCompany(UMBRA, () -> assertThat(
                    bindings.create(OUTSIDER, ACME_BANKING, jira("THEIRS")))
                    .isInstanceOf(TrackerBindingService.Outcome.NotFound.class));
        }
    }

    // =====================================================================
    // Identities
    // =====================================================================

    @Nested
    @DisplayName("Identities — the caller's own, and nobody else's")
    class Identities {

        @Test
        @DisplayName("a Company Admin sees their own rows and not a colleague's")
        void identitiesArePerPerson() {
            inCompany(ACME, () -> {
                identities.connect(ENGINEER, connect(JIRA));

                assertThat(identities.list(ENGINEER)).hasSize(1);
                assertThat(identities.list(LEAD))
                        .as("RLS gives company isolation, not per-person isolation. This is the "
                                + "service-layer half, and it is the whole of that boundary")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("somebody else's identity id is a 404, indistinguishable from one that is gone")
        void anotherPersonsIdIs404() {
            inCompany(ACME, () -> {
                UUID theirs = connected(identities.connect(ENGINEER, connect(JIRA))).id();

                assertThat(identities.disconnect(LEAD, theirs))
                        .as("the only thing a 403 could disclose here is that a colleague has "
                                + "connected to something")
                        .isInstanceOf(TrackerIdentityService.Outcome.NotFound.class);
                assertThat(identities.test(LEAD, theirs))
                        .isInstanceOf(TrackerIdentityService.Outcome.NotFound.class);
            });
        }

        @Test
        @DisplayName("a second POST for one host rotates rather than adding a row")
        void connectingTwiceRotates() {
            inCompany(ACME, () -> {
                TrackerIdentityService.Outcome first = identities.connect(ENGINEER, connect(JIRA));
                TrackerIdentityService.Outcome second = identities.connect(ENGINEER, connect(JIRA));

                assertThat(((TrackerIdentityService.Outcome.Connected) first).created()).isTrue();
                assertThat(((TrackerIdentityService.Outcome.Connected) second).created())
                        .as("200, not 201 — there is nothing else on the row a person would edit, "
                                + "so the POST is the rotation")
                        .isFalse();
                assertThat(identities.list(ENGINEER)).hasSize(1);
            });
        }

        @Test
        @DisplayName("the view carries a hint and never the credential")
        void theViewCarriesNoSecret() {
            inCompany(ACME, () -> {
                TrackerIdentityView view = connected(identities.connect(ENGINEER, connect(JIRA)));

                assertThat(view.secretHint()).isEqualTo("…ThIs");
                assertThat(Arrays.stream(TrackerIdentityView.class.getRecordComponents())
                        .map(RecordComponent::getName))
                        .as("a component added here from the entity beside it is how a credential "
                                + "reaches a response, a log and a browser cache at once")
                        .doesNotContain("secret", "refreshToken", "userId");
            });
        }

        @Test
        @DisplayName("the column is ciphertext — what a stolen backup contains")
        void theColumnIsCiphertext() throws SQLException {
            UUID identityId = inCompanyReturning(ACME,
                    () -> connected(identities.connect(ENGINEER, connect(JIRA))).id());

            try (Connection connection = appConnection(ACME);
                 var statement = connection.prepareStatement(
                         "SELECT encode(secret, 'escape') FROM tracker_identity "
                                 + "WHERE id = CAST(? AS uuid)")) {
                statement.setString(1, identityId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).doesNotContain(TOKEN);
                }
            }
        }

        @Test
        @DisplayName("Jira Cloud refuses a connection with no account id, and says which field")
        void jiraCloudNeedsAnEmail() {
            inCompany(ACME, () -> {
                TrackerIdentityService.Outcome outcome = identities.connect(ENGINEER,
                        new TrackerIdentityInput("JIRA_CLOUD", JIRA, "PAT", TOKEN, null, null));

                assertThat(outcome).isInstanceOf(TrackerIdentityService.Outcome.Invalid.class);
                assertThat(((TrackerIdentityService.Outcome.Invalid) outcome).errors())
                        .as("its Basic auth pairs the token with the account's email, so a token "
                                + "without one cannot authenticate at all — refused here rather "
                                + "than becoming a 401 the person reads as \"my token is wrong\"")
                        .containsKey("accountId");
            });
        }

        @Test
        @DisplayName("an auth method that is not PAT is refused rather than quietly stored as one")
        void onlyPatIsAccepted() {
            inCompany(ACME, () -> {
                TrackerIdentityService.Outcome outcome = identities.connect(ENGINEER,
                        new TrackerIdentityInput(
                                "JIRA_CLOUD", JIRA, "OAUTH", TOKEN, "eng@acme.test", null));

                assertThat(outcome).isInstanceOf(TrackerIdentityService.Outcome.Invalid.class);
            });
        }
    }

    // =====================================================================
    // Connected members — D19
    // =====================================================================

    @Nested
    @DisplayName("Connected members — a name and a state, and nothing else")
    class ConnectedMembers {

        @Test
        @DisplayName("the response carries a CLOSED set of keys per host")
        void theKeySetIsClosed() {
            // Asserted as a closed set rather than as "does not contain the secret", because the
            // natural implementation reuses TrackerIdentityView — which carries secretHint,
            // accountId, expiresAt, lastError and lastCheckedAt — and the failure mode is a field
            // arriving later rather than a field somebody chose today.
            assertThat(Arrays.stream(
                            TrackerConnectedMemberView.HostState.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactly("provider", "baseUrl", "state");

            assertThat(Arrays.stream(TrackerConnectedMemberView.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactly("user", "hosts");
        }

        @Test
        @DisplayName("every project member is listed, connected or not")
        void everyMemberIsListed() {
            inCompany(ACME, () -> {
                bindings.create(LEAD, ACME_BANKING, jira("PAY"));
                identities.connect(ENGINEER, connect(JIRA));

                List<TrackerConnectedMemberView> rows =
                        allowed(bindings.connectedMembers(LEAD, ACME_BANKING));

                assertThat(rows)
                        .as("the question is \"why can nobody file defects\", so an absent name "
                                + "would read as \"not on this project\"")
                        .hasSize(2);
                assertThat(rows).allSatisfy(row -> assertThat(row.hosts()).hasSize(1));
                assertThat(states(rows))
                        .containsEntry(ENGINEER, TrackerConnectionState.CONNECTED)
                        .containsEntry(LEAD, TrackerConnectionState.NOT_CONNECTED);
            });
        }

        @Test
        @DisplayName("it is scoped to the hosts THIS project's bindings name")
        void itIsScopedToBoundHosts() {
            inCompany(ACME, () -> {
                // The engineer is connected to a Jira site this project is not bound to. A lead
                // learns nothing about it — that scoping is the mitigation the privacy
                // counter-argument bought, not a side effect of a convenient query.
                identities.connect(ENGINEER, connect("https://other.atlassian.net"));
                bindings.create(LEAD, ACME_BANKING, jira("PAY"));

                List<TrackerConnectedMemberView> rows =
                        allowed(bindings.connectedMembers(LEAD, ACME_BANKING));

                assertThat(rows).allSatisfy(row -> assertThat(row.hosts())
                        .extracting(TrackerConnectedMemberView.HostState::baseUrl)
                        .containsExactly(JIRA));
                assertThat(states(rows)).containsEntry(ENGINEER,
                        TrackerConnectionState.NOT_CONNECTED);
            });
        }

        @Test
        @DisplayName("a project with no bindings lists members and no hosts")
        void noBindingsMeansNoHosts() {
            inCompany(ACME, () -> assertThat(allowed(bindings.connectedMembers(LEAD, ACME_BANKING)))
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row.hosts()).isEmpty()));
        }

        @Test
        @DisplayName("it costs integration:manage, like the bindings beside it")
        void itCostsIntegrationManage() {
            inCompany(ACME, () -> assertThat(bindings.connectedMembers(ENGINEER, ACME_BANKING))
                    .isInstanceOf(GateResult.Denied.class));
        }
    }

    // =====================================================================
    // Isolation
    // =====================================================================

    @Nested
    @DisplayName("RLS — the three new tables, in both spellings of \"no company\"")
    class Isolation {

        @Test
        @DisplayName("no company context sees nothing, never set and reset to ''")
        void noCompanySeesNothing() throws SQLException {
            inCompany(ACME, () -> {
                bindings.create(LEAD, ACME_BANKING, jira("PAY"));
                identities.connect(ENGINEER, connect(JIRA));
            });

            try (Connection fresh = appConnection()) {
                assertThat(countOn(fresh, "tracker_binding")).isZero();
                assertThat(countOn(fresh, "tracker_identity")).isZero();
                assertThat(countOn(fresh, "tracker_issue_creation")).isZero();
            }

            // The state EVERY pooled connection is in after its first transaction: set_config
            // leaves a reset value of '' rather than NULL. Without V4's nullif() this raises
            // "invalid input syntax for type uuid" instead of answering nothing — a 500 rather
            // than a leak, and still a defect.
            try (Connection recycled = appConnection(ACME)) {
                try (var statement = recycled.prepareStatement(
                        "SELECT set_config('app.current_company', '', false)")) {
                    statement.execute();
                }
                assertThat(countOn(recycled, "tracker_binding")).isZero();
                assertThat(countOn(recycled, "tracker_identity")).isZero();
            }
        }

        @Test
        @DisplayName("one client's bindings are invisible to another")
        void companiesAreApart() throws SQLException {
            inCompany(ACME, () -> bindings.create(LEAD, ACME_BANKING, jira("PAY")));
            inCompany(UMBRA, () -> bindings.create(OUTSIDER, UMBRA_BANKING, jira("PAY")));

            assertThat(countIn(ACME, "tracker_binding")).isEqualTo(1);
            assertThat(countIn(UMBRA, "tracker_binding")).isEqualTo(1);
        }

        @Test
        @DisplayName("a person in two companies connects twice — the stated papercut, asserted")
        void oneHostTwoCompanies() {
            // V73's header records this as a real cost rather than a design being pretended to have
            // none: a credential row readable across companies would be the exact thing company_id
            // exists to prevent, so the same person and the same Jira site is two rows.
            inCompany(ACME, () -> identities.connect(ENGINEER, connect(JIRA)));
            inCompany(UMBRA, () -> identities.connect(ENGINEER, connect(JIRA)));

            assertThat(inCompanyReturning(ACME, () -> identities.list(ENGINEER))).hasSize(1);
            assertThat(inCompanyReturning(UMBRA, () -> identities.list(ENGINEER))).hasSize(1);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static TrackerBindingInput jira(String project) {
        return new TrackerBindingInput("JIRA_CLOUD", JIRA, project, Map.of(), true, true);
    }

    private static TrackerIdentityInput connect(String baseUrl) {
        return new TrackerIdentityInput(
                "JIRA_CLOUD", baseUrl, "PAT", TOKEN, "eng@acme.test", null);
    }

    private static TrackerBindingView saved(TrackerBindingService.Outcome outcome) {
        assertThat(outcome).isInstanceOf(TrackerBindingService.Outcome.Saved.class);
        return ((TrackerBindingService.Outcome.Saved) outcome).view();
    }

    private static TrackerIdentityView connected(TrackerIdentityService.Outcome outcome) {
        assertThat(outcome).isInstanceOf(TrackerIdentityService.Outcome.Connected.class);
        return ((TrackerIdentityService.Outcome.Connected) outcome).view();
    }

    @SuppressWarnings("unchecked")
    private static <T> T allowed(GateResult<T> gated) {
        assertThat(gated).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<T>) gated).value();
    }

    private static Map<UUID, TrackerConnectionState> states(List<TrackerConnectedMemberView> rows) {
        Map<UUID, TrackerConnectionState> byUser = new java.util.HashMap<>();
        rows.forEach(row -> byUser.put(
                row.user().id(),
                row.hosts().isEmpty()
                        ? TrackerConnectionState.NOT_CONNECTED
                        : row.hosts().get(0).state()));
        return byUser;
    }

    private void clearTracker(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM tracker_issue_creation");
            statement.executeUpdate("DELETE FROM tracker_binding");
            statement.executeUpdate("DELETE FROM tracker_identity");
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
        UUID id = UUID.nameUUIDFromBytes(("tracker-connection:" + marker).getBytes());
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
    @DisplayName("fixture precondition: the two personas actually differ on integration:manage")
    void theFixtureDiscriminates() {
        // If both answered the same, every assertion in Bindings would pass without the gate
        // existing. V52 moved seven keys down to Test Engineer and left this one where it was, so
        // it is a line the suite can still tell apart.
        inCompany(ACME, () -> {
            assertThat(bindings.create(LEAD, ACME_BANKING, jira("PROBE")))
                    .isInstanceOf(TrackerBindingService.Outcome.Saved.class);
            assertThat(bindings.create(ENGINEER, ACME_BANKING, jira("PROBE2")))
                    .isInstanceOf(TrackerBindingService.Outcome.Forbidden.class);
        });
    }
}
