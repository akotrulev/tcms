package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.InvitationService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Re-issuing an invitation — the operator's only way to deliver a link on an instance with
 * no mail relay.
 *
 * <p>What is at stake is not "a row was replaced". The token is stored as a digest and
 * nothing can reprint it, so before this path existed a UI-raised invitation on a
 * relay-less instance was <strong>undeliverable by anyone</strong>: the API response carries
 * no URL, the mailer only warned, and {@code provision-company} refuses to replace an
 * outstanding invitation and only addresses a company's first admin. The UI told the invitee
 * to ask an operator who had no mechanism to answer.
 *
 * <p>The load-bearing property is that re-issuing <em>kills</em> the previous token rather
 * than adding a second live one — otherwise "here is a fresh link" would quietly mean "there
 * are now two ways into this account, one of them in a mailbox you don't control".
 *
 * <p>Per the async-harness lesson (§14) this class owns the {@code iss-} slug namespace and
 * provisions its own companies: the container is shared, and a test that assumes it is alone
 * finds a collision and reports it as a leak.
 */
@DisplayName("Re-issuing an invitation — the operator path when nothing was emailed")
class IssueInvitationServiceTest extends IntegrationTestBase {

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private IssueInvitationService issuing;

    @Autowired
    private InvitationService invitations;

    private static final String GOOD_PASSWORD = "correct-horse-battery";

    @Nested
    @DisplayName("Issuing a replacement")
    class Replacing {

        @Test
        @DisplayName("prints a link for a member who was invited and never accepted")
        void issuesForAnInvitedMember() {
            ProvisionCompanyResult provisioned = provisioning.provision(request("iss-basic"));

            IssueInvitationResult result =
                    issuing.issue("iss-basic", "admin@iss-basic.test", null);

            assertThat(result.outcome()).isEqualTo(IssueInvitationResult.Outcome.ISSUED);
            assertThat(result.link())
                    .as("the link is the whole point of the command")
                    .isPresent();
            assertThat(result.link().orElseThrow()).contains("/accept-invitation?token=");
            assertThat(result.expiresAt()).isPresent();
            assertThat(result.userId()).contains(provisioned.adminUserId());
        }

        @Test
        @DisplayName("the NEW link redeems")
        void theNewLinkWorks() {
            provisioning.provision(request("iss-redeem"));

            IssueInvitationResult result =
                    issuing.issue("iss-redeem", "admin@iss-redeem.test", null);

            assertThat(invitations.accept(tokenOf(result), GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Accepted.class);
        }

        @Test
        @DisplayName("and the OLD one is dead — not a second way in")
        void theOldLinkStopsWorking() {
            ProvisionCompanyResult provisioned = provisioning.provision(request("iss-kill"));
            String original = tokenOf(provisioned);

            IssueInvitationResult reissued =
                    issuing.issue("iss-kill", "admin@iss-kill.test", null);

            assertThat(tokenOf(reissued))
                    .as("a replacement that reused the secret would not be a replacement")
                    .isNotEqualTo(original);
            assertThat(invitations.accept(original, GOOD_PASSWORD))
                    .as("a link sitting in a mailbox the operator does not control must not "
                            + "survive the operator issuing a new one")
                    .isInstanceOf(InvitationService.Rejected.class);
            // And the new one still works afterwards — proving the old token was rejected
            // because it was replaced, not because the whole membership was broken.
            assertThat(invitations.accept(tokenOf(reissued), GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Accepted.class);
        }

        @Test
        @DisplayName("re-issuing twice leaves one live token, not two")
        void repeatedIssuesLeaveOneLiveToken() {
            provisioning.provision(request("iss-twice"));

            String first = tokenOf(issuing.issue("iss-twice", "admin@iss-twice.test", null));
            String second = tokenOf(issuing.issue("iss-twice", "admin@iss-twice.test", null));

            assertThat(first).isNotEqualTo(second);
            assertThat(invitations.accept(first, GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Rejected.class);
            assertThat(invitations.accept(second, GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Accepted.class);
        }
    }

    @Nested
    @DisplayName("Refusals — each needs a different next action from the operator")
    class Refusals {

        @Test
        @DisplayName("an unknown slug issues nothing")
        void unknownCompany() {
            IssueInvitationResult result =
                    issuing.issue("iss-no-such-company", "anyone@example.test", null);

            assertThat(result.outcome())
                    .isEqualTo(IssueInvitationResult.Outcome.NO_SUCH_COMPANY);
            assertThat(result.link()).isEmpty();
        }

        @Test
        @DisplayName("an address that is not a member issues nothing")
        void notAMember() {
            provisioning.provision(request("iss-stranger"));

            IssueInvitationResult result =
                    issuing.issue("iss-stranger", "nobody@iss-stranger.test", null);

            assertThat(result.outcome()).isEqualTo(IssueInvitationResult.Outcome.NOT_A_MEMBER);
            assertThat(result.link()).isEmpty();
        }

        @Test
        @DisplayName("a member who already accepted gets nothing — an invitation is not a "
                + "password reset")
        void alreadyActive() {
            ProvisionCompanyResult provisioned = provisioning.provision(request("iss-active"));
            assertThat(invitations.accept(tokenOf(provisioned), GOOD_PASSWORD))
                    .isInstanceOf(InvitationService.Accepted.class);

            IssueInvitationResult result =
                    issuing.issue("iss-active", "admin@iss-active.test", null);

            assertThat(result.outcome())
                    .as("minting an invitation for a live account would be a supported way to "
                            + "take one over")
                    .isEqualTo(IssueInvitationResult.Outcome.ALREADY_ACTIVE);
            assertThat(result.link()).isEmpty();
        }
    }

    private static ProvisionCompanyRequest request(String slug) {
        return ProvisionCompanyRequest.of(
                slug, "Iss " + slug, "admin@" + slug + ".test", "Iss Admin");
    }

    /** Pulls the secret back out of the link, the way an invitee's browser would. */
    private static String tokenOf(IssueInvitationResult result) {
        return secretIn(result.link().orElseThrow(() ->
                new AssertionError("this call issued no invitation")));
    }

    private static String tokenOf(ProvisionCompanyResult result) {
        return secretIn(result.invitationLink().orElseThrow(() ->
                new AssertionError("this run issued no invitation")));
    }

    private static String secretIn(String link) {
        return URLDecoder.decode(link.substring(link.indexOf("?token=") + "?token=".length()),
                StandardCharsets.UTF_8);
    }
}
