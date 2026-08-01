package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import com.tcms.tenancy.CompanyContext;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The invitation/activation path — the thing that stood between a provisioned company and
 * a usable one (handoff §17).
 *
 * <p>What is at stake is not "a row changed state". It is that the <strong>only</strong>
 * way into a client company is a single-use secret that was mailed to the invitee: that
 * redeeming it opens exactly one membership and no more, that a failed attempt costs the
 * invitation nothing, and that no failure mode tells a stranger anything about who exists
 * on this instance.
 *
 * <p>Per the async-harness lesson (§14) this class owns the {@code inv-} slug namespace and
 * provisions its own companies rather than seeding fixed ids — the container is shared, and
 * a test that assumes it is alone finds a collision and reports it as a leak.
 */
@DisplayName("Invitations — the only way into a company")
class InvitationServiceTest extends IntegrationTestBase {

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private MembershipInvitationRepository invitationRows;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    private static final String GOOD_PASSWORD = "correct-horse-battery";

    @Nested
    @DisplayName("Issuing")
    class Issuing {

        @Test
        @DisplayName("provisioning mints a link and an expiry for the first admin")
        void provisioningIssuesAnInvitation() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-issue"));

            assertThat(result.invitationLink())
                    .as("provisioning must leave a way in, or it stands up a company nobody "
                            + "can enter — which is exactly the state handoff §17 blocked on")
                    .isPresent();
            assertThat(result.invitationExpiry()).isPresent();
            assertThat(result.invitationLink().orElseThrow())
                    .startsWith("http://localhost:5173/accept-invitation?token=");
        }

        @Test
        @DisplayName("stores a digest, never the token")
        void storesOnlyADigest() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-digest"));
            String secret = tokenOf(result);

            MembershipInvitation row = invitationRows
                    .findByCompanyIdAndUserIdAndConsumedAtIsNull(
                            result.companyId(), result.adminUserId())
                    .orElseThrow();

            assertThat(row.getTokenHash())
                    .as("a readable token in this table would make a database read enough to "
                            + "become a client's administrator")
                    .isNotEqualTo(secret)
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        // Disabled 2026-08-01, re-enabled 2026-08-02 with the defect actually fixed. The
        // expiry equality below is the assertion that was failing: it compares an expiry
        // returned from memory against the same expiry read back from Postgres, which are
        // two different values unless the application's clock is truncated to the
        // resolution of a timestamptz. TcmsApplication.clock() now is; ClockResolutionTest
        // pins it in a way that can fail on macOS, where this one structurally cannot.
        @DisplayName("a re-run does NOT replace an outstanding invitation")
        void reRunLeavesTheLiveLinkAlone() {
            ProvisionCompanyResult first = provisioning.provision(request("inv-rerun"));
            String secret = tokenOf(first);

            ProvisionCompanyResult second = provisioning.provision(request("inv-rerun"));

            assertThat(second.invitationLink())
                    .as("minting a replacement would invalidate a link already in someone's "
                            + "inbox, and re-running provision-company is a supported top-up")
                    .isEmpty();
            assertThat(second.invitationExpiry()).isEqualTo(first.invitationExpiry());
            assertThat(invitations.accept(secret, GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Accepted.class);
        }

        @Test
        @DisplayName("a re-run after expiry issues a fresh one, and the lapsed token stays dead")
        void reRunAfterExpiryReissues() throws SQLException {
            ProvisionCompanyResult first = provisioning.provision(request("inv-expired"));
            String lapsed = tokenOf(first);
            expire(first.companyId(), first.adminUserId());

            ProvisionCompanyResult second = provisioning.provision(request("inv-expired"));

            assertThat(second.invitationLink()).isPresent();
            assertThat(second.wasNoOp())
                    .as("every other part existed, but re-issuing a lapsed invitation is a real "
                            + "repair — reporting a no-op would send the operator looking "
                            + "elsewhere for the thing that just got fixed")
                    .isFalse();
            assertThat(invitations.accept(lapsed, GOOD_PASSWORD))
                    .isEqualTo(new InvitationService.Rejected(
                            InvitationService.Rejection.NOT_REDEEMABLE, null));
            assertThat(invitations.accept(tokenOf(second), GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Accepted.class);
        }

        @Test
        @DisplayName("nothing is issued once the administrator has accepted")
        void noInvitationOnceAccepted() {
            ProvisionCompanyResult first = provisioning.provision(request("inv-accepted"));
            invitations.accept(tokenOf(first), GOOD_PASSWORD);

            ProvisionCompanyResult second = provisioning.provision(request("inv-accepted"));

            assertThat(second.invitationLink()).isEmpty();
            assertThat(second.invitationExpiry()).isEmpty();
            assertThat(second.wasNoOp()).isTrue();
        }
    }

    @Nested
    @DisplayName("Accepting")
    class Accepting {

        @Test
        @DisplayName("activates the account and the membership, and sets the password")
        void activatesBothRows() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-accept"));
            int before = users.findById(result.adminUserId()).orElseThrow().getPermissionVersion();

            InvitationService.AcceptOutcome outcome =
                    invitations.accept(tokenOf(result), GOOD_PASSWORD);

            assertThat(outcome).isInstanceOf(InvitationService.Accepted.class);
            assertThat(((InvitationService.Accepted) outcome).passwordSet()).isTrue();

            AppUser user = users.findById(result.adminUserId()).orElseThrow();
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getPasswordHash())
                    .as("the delegating encoder writes the algorithm into the hash, so the cost "
                            + "can be raised later without orphaning existing rows")
                    .startsWith("{bcrypt}");
            assertThat(user.getPermissionVersion())
                    .as("INVITED -> ACTIVE changes what this account may do, so every token "
                            + "issued before it must stop being accepted")
                    .isGreaterThan(before);

            try (var ignored = CompanyContext.open(result.companyId())) {
                CompanyMembership membership = memberships
                        .findByCompanyIdAndUserId(result.companyId(), result.adminUserId())
                        .orElseThrow();
                assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
                assertThat(membership.getAcceptedAt()).isNotNull();
            }
        }

        @Test
        @DisplayName("grants the company:read baseline, repairing a membership that lacks one")
        void acceptingGrantsTheCompanyReadBaseline() throws SQLException {
            ProvisionCompanyResult result = provisioning.provision(request("inv-baseline"));
            // Provisioning already granted it, so acceptance would normally be a no-op. Removed
            // first, which is the state a membership row created by a path that forgot would be
            // in — a future invite-a-colleague endpoint, a restore tool, a hand-written row. The
            // person must not find an empty company on the way in.
            try (var connection = appConnection(result.companyId());
                 var statement = connection.prepareStatement(
                         "DELETE FROM role_assignment WHERE role_id = ?")) {
                statement.setObject(1, Role.COMPANY_MEMBER_ID);
                statement.executeUpdate();
            }

            assertThat(invitations.accept(tokenOf(result), GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Accepted.class);

            assertThat(baselineGrantCount(result.companyId(), result.adminUserId()))
                    .as("""
                            Accepting is the moment a person actually joins, and roles spec §A3 \
                            gives every member company:read. Without the grant the company-wide \
                            question answers no and the admin directory reads as empty rather \
                            than as forbidden (handoff §43).""")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("is single-use — the second attempt with the same token is refused")
        void isSingleUse() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-once"));
            String secret = tokenOf(result);

            assertThat(invitations.accept(secret, GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Accepted.class);
            assertThat(invitations.accept(secret, GOOD_PASSWORD))
                    .isEqualTo(new InvitationService.Rejected(
                            InvitationService.Rejection.NOT_REDEEMABLE, null));
        }

        @Test
        @DisplayName("stays spent even if the membership is invited again")
        void consumptionIsWhatMakesItSingleUse() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-respent"));
            String secret = tokenOf(result);
            invitations.accept(secret, GOOD_PASSWORD);

            // Re-invite the membership, which is what a future "remove and re-add a member"
            // path will do. This is the assertion that isolates consumed_at: without it,
            // single-use rests on the membership having left INVITED, and the moment
            // anything puts it back the old token opens the company again.
            //
            // Found by mutation: deleting the consumed_at check from isRedeemable() failed
            // NO test, because every existing case had also moved the membership to ACTIVE.
            reInvite(result.companyId(), result.adminUserId());

            assertThat(invitations.accept(secret, GOOD_PASSWORD))
                    .isEqualTo(new InvitationService.Rejected(
                            InvitationService.Rejection.NOT_REDEEMABLE, null));
            assertThat(invitationRows
                    .findByCompanyIdAndUserIdAndConsumedAtIsNull(
                            result.companyId(), result.adminUserId()))
                    .as("the spent invitation must not read as outstanding, or the next issue "
                            + "attempt would see one and decline to mint a usable replacement")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unknown token is refused with the SAME answer as a spent one")
        void unknownTokenIsIndistinguishable() {
            InvitationService.Rejected expected = new InvitationService.Rejected(
                    InvitationService.Rejection.NOT_REDEEMABLE, null);

            // Three different causes, deliberately one answer: distinct replies would let
            // anyone with a token-shaped string learn whether an address is on this
            // instance (§6t's no-oracle rule, on a pre-auth surface).
            assertThat(invitations.accept("not-a-real-token", GOOD_PASSWORD)).isEqualTo(expected);
            assertThat(invitations.accept("", GOOD_PASSWORD)).isEqualTo(expected);
            assertThat(invitations.accept(null, GOOD_PASSWORD)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a rejected password costs the invitation nothing")
        void rejectedPasswordDoesNotSpendTheInvitation() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-weak"));
            String secret = tokenOf(result);

            InvitationService.AcceptOutcome tooShort = invitations.accept(secret, "short");

            assertThat(tooShort).isInstanceOf(InvitationService.Rejected.class);
            assertThat(((InvitationService.Rejected) tooShort).reason())
                    .isEqualTo(InvitationService.Rejection.PASSWORD_UNACCEPTABLE);
            assertThat(users.findById(result.adminUserId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.INVITED);
            assertThat(invitations.accept(secret, GOOD_PASSWORD))
                    .as("a typo in the password field must not burn the only way in")
                    .isInstanceOf(InvitationService.Accepted.class);
        }

        @Test
        @DisplayName("a password past BCrypt's 72-byte ceiling is refused, not truncated")
        void refusesOversizedPassword() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-long"));

            String tooLong = "x".repeat(73);
            InvitationService.AcceptOutcome outcome = invitations.accept(tokenOf(result), tooLong);

            assertThat(((InvitationService.Rejected) outcome).reason())
                    .as("accepting it would verify only the first 72 bytes, so two different "
                            + "passphrases sharing a prefix would both open the account")
                    .isEqualTo(InvitationService.Rejection.PASSWORD_UNACCEPTABLE);
        }

        @Test
        @DisplayName("an offboarded member cannot re-admit themselves with an old invitation")
        void removedMembershipIsRefused() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-removed"));
            String secret = tokenOf(result);
            setMembershipStatus(result.companyId(), result.adminUserId(), "REMOVED");

            // Found by mutation: dropping the must-be-INVITED gate failed no test, because
            // every other case reached it with the invitation already consumed. This is the
            // one it uniquely covers — and without it, accept() would walk a REMOVED
            // membership straight back to ACTIVE, so offboarding would be undone by a link
            // the person still has in their mailbox.
            assertThat(invitations.accept(secret, GOOD_PASSWORD))
                    .isEqualTo(new InvitationService.Rejected(
                            InvitationService.Rejection.NOT_REDEEMABLE, null));

            try (var ignored = CompanyContext.open(result.companyId())) {
                assertThat(memberships
                        .findByCompanyIdAndUserId(result.companyId(), result.adminUserId())
                        .orElseThrow().getStatus())
                        .isEqualTo(MembershipStatus.REMOVED);
            }
        }

        @Test
        @DisplayName("a deactivated account cannot be activated by an outstanding invitation")
        void deactivatedAccountIsRefused() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-deactivated"));
            String secret = tokenOf(result);
            deactivate(result.adminUserId());

            assertThat(invitations.accept(secret, GOOD_PASSWORD))
                    .as("deactivation is the instance-wide off switch; a live invitation must "
                            + "not be a way around it")
                    .isEqualTo(new InvitationService.Rejected(
                            InvitationService.Rejection.NOT_REDEEMABLE, null));
        }
    }

    @Nested
    @DisplayName("A person who already has an account")
    class SecondCompany {

        @Test
        @DisplayName("joins the second company without touching their password")
        void secondCompanyDoesNotResetThePassword() {
            ProvisionCompanyResult first = provisioning.provision(
                    ProvisionCompanyRequest.of("inv-two-a", "Inv Two A", "two@inv.test", "Two A"));
            invitations.accept(tokenOf(first), GOOD_PASSWORD);
            String hashAfterFirst = users.findById(first.adminUserId()).orElseThrow()
                    .getPasswordHash();

            ProvisionCompanyResult second = provisioning.provision(
                    ProvisionCompanyRequest.of("inv-two-b", "Inv Two B", "two@inv.test", "Two B"));

            assertThat(second.adminUserId())
                    .as("one account, several companies — the instance-wide email rule (§6s)")
                    .isEqualTo(first.adminUserId());

            InvitationService.AcceptOutcome outcome = invitations.accept(tokenOf(second), null);

            assertThat(outcome).isInstanceOf(InvitationService.Accepted.class);
            assertThat(((InvitationService.Accepted) outcome).passwordSet()).isFalse();
            assertThat(users.findById(first.adminUserId()).orElseThrow().getPasswordHash())
                    .isEqualTo(hashAfterFirst);

            try (var ignored = CompanyContext.open(second.companyId())) {
                assertThat(memberships
                        .findByCompanyIdAndUserId(second.companyId(), second.adminUserId())
                        .orElseThrow().getStatus())
                        .isEqualTo(MembershipStatus.ACTIVE);
            }
        }

        @Test
        @DisplayName("cannot set a new password through the invitation")
        void refusesAPasswordItWouldOtherwiseIgnore() {
            ProvisionCompanyResult first = provisioning.provision(ProvisionCompanyRequest.of(
                    "inv-pw-a", "Inv Pw A", "pw@inv.test", "Pw A"));
            invitations.accept(tokenOf(first), GOOD_PASSWORD);
            ProvisionCompanyResult second = provisioning.provision(ProvisionCompanyRequest.of(
                    "inv-pw-b", "Inv Pw B", "pw@inv.test", "Pw B"));

            InvitationService.AcceptOutcome outcome =
                    invitations.accept(tokenOf(second), "a-brand-new-password");

            assertThat(((InvitationService.Rejected) outcome).reason())
                    .as("holding an invitation to a second company must not confer the ability "
                            + "to overwrite an existing account's credential — and silently "
                            + "ignoring the field would leave them believing they changed it")
                    .isEqualTo(InvitationService.Rejection.PASSWORD_NOT_REQUIRED);
        }
    }

    @Nested
    @DisplayName("Preview")
    class Preview {

        @Test
        @DisplayName("tells the holder who they are and whether a password is needed")
        void describesTheInvitation() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-preview"));

            InvitationService.InvitationPreview preview =
                    invitations.preview(tokenOf(result)).orElseThrow();

            assertThat(preview.email()).isEqualTo("admin@inv-preview.test");
            assertThat(preview.displayName()).isEqualTo("Inv Admin");
            assertThat(preview.passwordRequired()).isTrue();
        }

        @Test
        @DisplayName("says nothing at all for a token it does not recognise")
        void isSilentForAnUnknownToken() {
            assertThat(invitations.preview("nope")).isEmpty();
            assertThat(invitations.preview(null)).isEmpty();
        }

        @Test
        @DisplayName("stops describing an invitation once it has been redeemed")
        void isSilentAfterRedemption() {
            ProvisionCompanyResult result = provisioning.provision(request("inv-preview-used"));
            String secret = tokenOf(result);
            invitations.accept(secret, GOOD_PASSWORD);

            assertThat(invitations.preview(secret)).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers ----

    private static ProvisionCompanyRequest request(String slug) {
        return ProvisionCompanyRequest.of(
                slug, "Inv " + slug, "admin@" + slug + ".test", "Inv Admin");
    }

    /** Pulls the secret back out of the link, the way an invitee's browser would. */
    private static String tokenOf(ProvisionCompanyResult result) {
        String link = result.invitationLink().orElseThrow(() ->
                new AssertionError("this run issued no invitation"));
        return URLDecoder.decode(link.substring(link.indexOf("?token=") + "?token=".length()),
                StandardCharsets.UTF_8);
    }

    /** How many V15 baseline grants one member holds, read as the app role with RLS in force. */
    private long baselineGrantCount(UUID companyId, UUID userId) throws SQLException {
        try (var connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM role_assignment WHERE role_id = ? AND user_id = ?")) {
            statement.setObject(1, Role.COMPANY_MEMBER_ID);
            statement.setObject(2, userId);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Backdates the invitation past its expiry. Done in SQL as the owner because the entity
     * deliberately has no mutator for it — an expiry the application can move is not an
     * expiry.
     */
    private void expire(UUID companyId, UUID userId) throws SQLException {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        try (var connection = superuserConnection();
                var statement = connection.prepareStatement(
                        "UPDATE membership_invitation SET expires_at = ? "
                                + "WHERE company_id = ? AND user_id = ? AND consumed_at IS NULL")) {
            statement.setObject(1, past.atOffset(java.time.ZoneOffset.UTC));
            statement.setObject(2, companyId);
            statement.setObject(3, userId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /** Puts a membership back to INVITED — what a future remove-and-re-add path will do. */
    private void reInvite(UUID companyId, UUID userId) {
        setMembershipStatus(companyId, userId, "INVITED");
    }

    /**
     * Forces a membership state the application has no path to yet. As the owner, because
     * these tests assert what happens when the row is in a state the service must refuse —
     * and a service method that could produce it would make the assertion circular.
     */
    private void setMembershipStatus(UUID companyId, UUID userId, String status) {
        try (var connection = superuserConnection();
                var statement = connection.prepareStatement(
                        "UPDATE company_membership SET status = ?, accepted_at = NULL "
                                + "WHERE company_id = ? AND user_id = ?")) {
            statement.setString(1, status);
            statement.setObject(2, companyId);
            statement.setObject(3, userId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            throw new AssertionError("could not set the fixture membership status", e);
        }
    }

    private void deactivate(UUID userId) {
        try (var connection = superuserConnection();
                var statement = connection.prepareStatement(
                        "UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?")) {
            statement.setObject(1, userId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            throw new AssertionError("could not deactivate the fixture user", e);
        }
    }
}
