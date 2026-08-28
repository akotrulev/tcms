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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reading and filing issues — the three availability states, the two-user leak <em>through the
 * service</em>, and the create's idempotency.
 *
 * <h2>The {@code tracker-fake} profile, and why the fake had to exist first</h2>
 *
 * <p>Per the working agreement: <em>build the fault switch before the feature it proves</em>. Every
 * failure this feature has to render — a tracker that is down, an issue the viewer cannot see, an
 * account that was never connected — is unreachable by using the product, so
 * {@link FakeTrackerAdapter} is where each of them is one call away. The real adapters are absent
 * under this profile, so nothing here depends on which bean would otherwise have won.
 *
 * <h2>The leak, asserted a second time and at a different altitude</h2>
 *
 * <p>{@code TrackerCacheTest} pins the cache in isolation. This pins the same property through
 * {@link TrackerIssueService} — which is where the mistake would actually be made, because the
 * service is what chooses the key it passes. A cache that is correct and a caller that hands it the
 * binding twice is the same defect with a green unit test in front of it.
 *
 * <p>Ids are unique to this class — {@code dd…}.
 */
@ActiveProfiles("tracker-fake")
@DisplayName("Tracker issues — availability, the per-user boundary, and one issue per double-click")
class TrackerIssueEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("dd000000-0000-4000-8000-00000000000a");
    private static final UUID ACME_BANKING = UUID.fromString("dd001000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("dd003000-0000-4000-8000-000000000001");
    /** Connected, and entitled to the issue. */
    private static final UUID ALICE = UUID.fromString("dd003000-0000-4000-8000-000000000002");
    /** Connected, and NOT entitled — the other half of the leak. */
    private static final UUID BOB = UUID.fromString("dd003000-0000-4000-8000-000000000003");
    /** Never connected. */
    private static final UUID CARLA = UUID.fromString("dd003000-0000-4000-8000-000000000004");

    private static final String JIRA = "https://acme.atlassian.net";
    private static final String ISSUE_ID = "10042";

    private static final String ALICE_ACCOUNT = "alice@acme.test";
    private static final String BOB_ACCOUNT = "bob@acme.test";

    @Autowired
    private TrackerBindingService bindings;

    @Autowired
    private TrackerIdentityService identities;

    @Autowired
    private TrackerIssueService issues;

    @Autowired
    private FakeTrackerAdapter tracker;

    @Autowired
    private TrackerCache cache;

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

    private UUID bindingId;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "ti-acme", "Acme", now);
            activeUser(LEAD, "lead@acme.test", "L. Lead", now);
            activeUser(ALICE, ALICE_ACCOUNT, "A. Lice", now);
            activeUser(BOB, BOB_ACCOUNT, "B. Ob", now);
            activeUser(CARLA, "carla@acme.test", "C. Arla", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "TIOLB", "Online Banking", now);
            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ALICE, "A. Lice", now);
            member(ACME, BOB, "B. Ob", now);
            member(ACME, CARLA, "C. Arla", now);
            grant("lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("alice", ACME, Role.TEST_ENGINEER_ID, ALICE, ACME_BANKING, now);
            grant("bob", ACME, Role.TEST_ENGINEER_ID, BOB, ACME_BANKING, now);
            grant("carla", ACME, Role.TEST_ENGINEER_ID, CARLA, ACME_BANKING, now);
        }));

        clearTracker();
        tracker.reset();
        cache.forget(ALICE);
        cache.forget(BOB);
        cache.forget(CARLA);

        inCompany(ACME, () -> {
            bindingId = ((TrackerBindingService.Outcome.Saved) bindings.create(
                    LEAD, ACME_BANKING,
                    new TrackerBindingInput("JIRA_CLOUD", JIRA, "PAY", Map.of(), true, true)))
                    .view().id();

            identities.connect(ALICE, connect(ALICE_ACCOUNT));
            identities.connect(BOB, connect(BOB_ACCOUNT));
        });

        tracker.register(new TrackerIssue(
                TrackerProvider.JIRA_CLOUD, ISSUE_ID, "PAY-991", "Fix the rounding",
                TrackerIssueStatusCategory.IN_PROGRESS, "In Progress",
                JIRA + "/browse/PAY-991", bindingId));
        tracker.entitle(ALICE_ACCOUNT, ISSUE_ID, true);
        tracker.entitle(BOB_ACCOUNT, ISSUE_ID, false);
    }

    @Nested
    @DisplayName("The three availability states")
    class Availability {

        @Test
        @DisplayName("an entitled viewer gets the issue, live")
        void anEntitledViewerSeesIt() {
            inCompany(ACME, () -> {
                TrackerIssueLookupView answer =
                        allowed(issues.lookup(ALICE, ACME_BANKING, ISSUE_ID, null));

                assertThat(answer.state()).isEqualTo(TrackerIssueAvailability.AVAILABLE);
                assertThat(answer.issue().title()).isEqualTo("Fix the rounding");
                assertThat(answer.issue().key())
                        .as("today's key, fetched — never a stored one, because a key moves")
                        .isEqualTo("PAY-991");
            });
        }

        @Test
        @DisplayName("AN UNCONNECTED VIEWER GETS 200 WITH NOT_CONNECTED, NEVER A 401")
        void unconnectedIsNotAnAuthFailure() {
            // The specific, easy defect the plan spends a paragraph preventing: the viewer is
            // authenticated to TCMS perfectly well, and a 401 here would trip the SPA's
            // session-recovery path for a condition that has nothing to do with their session.
            inCompany(ACME, () -> {
                assertThat(allowed(issues.lookup(CARLA, ACME_BANKING, ISSUE_ID, null)).state())
                        .isEqualTo(TrackerIssueAvailability.NOT_CONNECTED);
                assertThat(allowed(issues.search(CARLA, ACME_BANKING, "rounding", null)).state())
                        .isEqualTo(TrackerIssueAvailability.NOT_CONNECTED);
            });
        }

        @Test
        @DisplayName("a connected viewer who cannot see the issue gets UNAVAILABLE and no reason")
        void noAccessIsUnavailable() {
            inCompany(ACME, () -> {
                TrackerIssueLookupView answer =
                        allowed(issues.lookup(BOB, ACME_BANKING, ISSUE_ID, null));

                assertThat(answer.state()).isEqualTo(TrackerIssueAvailability.UNAVAILABLE);
                assertThat(answer.issue())
                        .as("no title, no key, no status — and no message saying WHICH of D16's "
                                + "four conditions this was, which would make the preview an "
                                + "oracle for what exists in a tracker the viewer cannot read")
                        .isNull();
            });
        }

        @Test
        @DisplayName("a tracker that is down is the same UNAVAILABLE as a refusal")
        void anOutageIsUnavailable() {
            inCompany(ACME, () -> {
                tracker.breakHost(JIRA, "connection reset");

                assertThat(allowed(issues.lookup(ALICE, ACME_BANKING, ISSUE_ID, null)).state())
                        .isEqualTo(TrackerIssueAvailability.UNAVAILABLE);
            });
        }

        @Test
        @DisplayName("a project with no binding renders, degraded, rather than 404ing")
        void noBindingIsNotA404() {
            inCompany(ACME, () -> {
                bindings.delete(LEAD, ACME_BANKING, bindingId);

                assertThat(allowed(issues.lookup(ALICE, ACME_BANKING, ISSUE_ID, null)).state())
                        .as("the page renders and the panel says the tracker is not set up — "
                                + "nothing about the case is unreachable")
                        .isEqualTo(TrackerIssueAvailability.UNAVAILABLE);
            });
        }
    }

    @Nested
    @DisplayName("The per-user boundary, through the service")
    class TheLeak {

        @Test
        @DisplayName("B GETS NOTHING AFTER A GOT A TITLE — one issue, one TTL window")
        void bobIsNotServedAlicesAnswer() {
            inCompany(ACME, () -> {
                TrackerIssueLookupView forAlice =
                        allowed(issues.lookup(ALICE, ACME_BANKING, ISSUE_ID, null));
                assertThat(forAlice.issue().title()).isEqualTo("Fix the rounding");

                TrackerIssueLookupView forBob =
                        allowed(issues.lookup(BOB, ACME_BANKING, ISSUE_ID, null));

                assertThat(forBob.state())
                        .as("If this reads AVAILABLE, the cache is keyed by binding and Bob is "
                                + "looking at Alice's copy of an issue he has no access to. Every "
                                + "other test in this feature still passes")
                        .isEqualTo(TrackerIssueAvailability.UNAVAILABLE);
                assertThat(forBob.issue()).isNull();
            });
        }

        @Test
        @DisplayName("the second read for one person does not reach the tracker")
        void oneUsersSecondReadIsCached() {
            inCompany(ACME, () -> {
                issues.lookup(ALICE, ACME_BANKING, ISSUE_ID, null);
                int after = tracker.countCalls();
                issues.lookup(ALICE, ACME_BANKING, ISSUE_ID, null);

                assertThat(tracker.countCalls())
                        .as("the cache has to actually cache, or the isolation above is satisfied "
                                + "by not caching at all")
                        .isEqualTo(after);
            });
        }

        @Test
        @DisplayName("rotating a credential drops that person's cached answers and nobody else's")
        void rotationForgets() {
            inCompany(ACME, () -> {
                issues.lookup(ALICE, ACME_BANKING, ISSUE_ID, null);
                int before = tracker.countCalls();

                identities.connect(ALICE, connect(ALICE_ACCOUNT));
                issues.lookup(ALICE, ACME_BANKING, ISSUE_ID, null);

                assertThat(tracker.countCalls())
                        .as("without this, disconnecting an account leaves a minute in which the "
                                + "product still previews with a token the person told it to forget")
                        .isGreaterThan(before);
            });
        }
    }

    @Nested
    @DisplayName("Filing — as the caller, once per key")
    class Filing {

        @Test
        @DisplayName("an Idempotency-Key replay answers the issue that was already filed")
        void aReplayFilesNothing() {
            inCompany(ACME, () -> {
                TrackerIssueService.Outcome first = issues.file(
                        ALICE, ACME_BANKING, draft(), "key-1");
                int afterFirst = tracker.countCalls();

                TrackerIssueService.Outcome again = issues.file(
                        ALICE, ACME_BANKING, draft(), "key-1");

                assertThat(filed(first).created()).isTrue();
                assertThat(filed(again).created())
                        .as("200, not 201 — a duplicate here is not a row this product can delete, "
                                + "it is a second issue in somebody else's tracker")
                        .isFalse();
                assertThat(filed(again).view().id()).isEqualTo(filed(first).view().id());
                assertThat(tracker.countCalls())
                        .as("and the tracker was not called a second time")
                        .isEqualTo(afterFirst);
            });
        }

        @Test
        @DisplayName("an unconnected caller is refused 409 rather than answered 'unavailable'")
        void anUnconnectedCallerIsRefused() {
            // A create cannot answer "unavailable" the way a read can: nothing happened, and the
            // caller has to know.
            inCompany(ACME, () -> assertThat(issues.file(CARLA, ACME_BANKING, draft(), null))
                    .isInstanceOf(TrackerIssueService.Outcome.NotConnected.class));
        }

        @Test
        @DisplayName("a tracker that refuses is a 502 and a FAILED ledger row, not a lost attempt")
        void afailureIsRecorded() throws SQLException {
            inCompanyThrowing(() -> {
                tracker.breakHost(JIRA, "502 from the gateway");

                assertThat(issues.file(ALICE, ACME_BANKING, draft(), null))
                        .isInstanceOf(TrackerIssueService.Outcome.Unavailable.class);
            });

            assertThat(ledgerStatus())
                    .as("written PENDING before the call and completed after, so an attempt this "
                            + "process died in the middle of is visible as one that never finished")
                    .isEqualTo("FAILED");
        }

        @Test
        @DisplayName("a real failure updates the person's own connection health")
        void aFailureLandsOnTheConnection() {
            inCompany(ACME, () -> {
                tracker.breakHost(JIRA, "401 Unauthorized");
                issues.file(ALICE, ACME_BANKING, draft(), null);

                assertThat(identities.list(ALICE))
                        .singleElement()
                        .satisfies(identity -> assertThat(identity.lastError())
                                .as("a dead token gets noticed on the connections page without "
                                        + "anybody pressing Test")
                                .isNotNull());
            });
        }

        @Test
        @DisplayName("a success writes the outbox backstop event with the binding's provider")
        void theBackstopEventIsRecorded() throws SQLException {
            inCompany(ACME, () -> issues.file(ALICE, ACME_BANKING, draft(), null));

            assertThat(outboxPayload())
                    .as("provider is IN the payload because it is in "
                            + "defect_link_execution_key_idx — the handler's row and the client's "
                            + "row must agree on it or one defect renders twice")
                    .contains("\"provider\":\"JIRA_CLOUD\"");
        }

        @Test
        @DisplayName("the gate is passed before the connection is checked, not after")
        void thePermissionIsCheckedFirst() {
            // LEAD holds defect:create and has connected to nothing, so the refusal that comes back
            // is NotConnected rather than Forbidden — which is the ordering being asserted. A
            // service that checked the credential first would answer "connect your account" to
            // somebody who may not file defects at all, and send them to a settings page that would
            // not have helped.
            inCompany(ACME, () -> assertThat(issues.file(LEAD, ACME_BANKING, draft(), null))
                    .isInstanceOf(TrackerIssueService.Outcome.NotConnected.class));
        }

        @Test
        @DisplayName("another client's project is 404, never 403 — no existence oracle")
        void anUnreachableProjectIs404() {
            inCompany(ACME, () -> assertThat(issues.file(
                    ALICE, UUID.fromString("dd001000-0000-4000-8000-0000000000ff"), draft(), null))
                    .isInstanceOf(TrackerIssueService.Outcome.NotFound.class));
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static TrackerIssueCreateInput draft() {
        return new TrackerIssueCreateInput(null, "Rounding is wrong on the summary", "body", null);
    }

    private static TrackerIdentityInput connect(String account) {
        return new TrackerIdentityInput(
                "JIRA_CLOUD", JIRA, "PAT", "token-for-" + account, account, null);
    }

    private static TrackerIssueService.Outcome.Filed filed(TrackerIssueService.Outcome outcome) {
        assertThat(outcome).isInstanceOf(TrackerIssueService.Outcome.Filed.class);
        return (TrackerIssueService.Outcome.Filed) outcome;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allowed(GateResult<T> gated) {
        assertThat(gated).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<T>) gated).value();
    }

    private String ledgerStatus() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT status FROM tracker_issue_creation ORDER BY created_at DESC LIMIT 1");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).as("the ledger row must exist").isTrue();
            return rows.getString(1);
        }
    }

    private String outboxPayload() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT payload FROM outbox WHERE event_type = "
                             + "'integration.tracker_issue_created' ORDER BY created_at DESC LIMIT 1");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).as("the event must have been recorded").isTrue();
            return rows.getString(1).replace(" ", "");
        }
    }

    private void clearTracker() throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM tracker_issue_creation");
            statement.executeUpdate("DELETE FROM tracker_binding");
            statement.executeUpdate("DELETE FROM tracker_identity");
            statement.executeUpdate(
                    "DELETE FROM outbox WHERE event_type = 'integration.tracker_issue_created'");
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
        UUID id = UUID.nameUUIDFromBytes(("tracker-issue:" + marker).getBytes());
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

    private static void inCompanyThrowing(Runnable work) {
        try (var ignored = CompanyContext.open(ACME)) {
            work.run();
        }
    }
}
