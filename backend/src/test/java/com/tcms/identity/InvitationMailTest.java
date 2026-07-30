package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.tcms.IntegrationTestBase;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import com.tcms.tenancy.CompanyContext;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The invitation actually leaves the building.
 *
 * <p>Against a real SMTP server, in-JVM: {@link SmtpInvitationMailer} is the only piece of
 * this slice whose failure is invisible from inside the application — a mailer that
 * silently sends nothing looks exactly like a mailer that works, right up until an invitee
 * says they never received anything.
 *
 * <p>The two properties that matter are both about the token: it <strong>must</strong> be
 * in the message, because the message is its only delivery channel, and the message is the
 * <strong>only</strong> place it may appear.
 */
@DisplayName("Invitation email — the token's one legitimate destination")
class InvitationMailTest extends IntegrationTestBase {

    /**
     * Started in a static initializer rather than as a JUnit extension: the Spring context
     * is built when the test instance is post-processed, and it reads the port below
     * through {@code @DynamicPropertySource} — which is evaluated before any
     * {@code @BeforeEach}. This is the {@code @BeforeAll} ordering trap from handoff §12,
     * one layer up.
     */
    private static final int PORT = freePort();

    private static final GreenMail SMTP = new GreenMail(new ServerSetup(
            PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    static {
        SMTP.start();
    }

    /**
     * A port the OS says is free, taken once and then held for the run.
     *
     * <p><strong>Not {@code ServerSetup(0, ...)}</strong>, which is the obvious way to ask
     * for an ephemeral port and is wrong here: {@link GreenMail#reset()} stops and restarts
     * the server, and with port 0 it rebinds to a <em>different</em> port each time — while
     * Spring captured the first one at context creation. Every test after the first
     * {@code reset()} then fails with "connection refused", which reads as a broken mailer
     * rather than as a moving target. Measured, not guessed: that is exactly how this class
     * failed on its first run.
     *
     * <p>A hard-coded port would collide with whatever else the developer is running, and
     * that failure reads the same way.
     */
    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not reserve a port for the test SMTP server", e);
        }
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> PORT);
    }

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @BeforeEach
    void clearInbox() {
        SMTP.reset();
    }

    /**
     * Marks the outstanding invitation spent without accepting it, so the next
     * {@code issueIfPending} mints a fresh one while the membership stays INVITED. As the
     * owner, because the application has no path that does this — which is the point.
     */
    private void spendInvitation(java.util.UUID companyId, java.util.UUID userId) {
        try (var connection = ownerConnection();
                var statement = connection.prepareStatement(
                        "UPDATE membership_invitation SET consumed_at = now() "
                                + "WHERE company_id = ? AND user_id = ? AND consumed_at IS NULL")) {
            statement.setObject(1, companyId);
            statement.setObject(2, userId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (java.sql.SQLException e) {
            throw new AssertionError("could not spend the fixture invitation", e);
        }
    }

    @Test
    @DisplayName("carries the activation link to the invitee, and only to them")
    void sendsTheInvitation() throws Exception {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                "mail-send", "Mail Send Ltd", "newcomer@mail-send.test", "New Comer"));

        assertThat(SMTP.waitForIncomingEmail(10_000, 1)).isTrue();
        MimeMessage[] received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);

        MimeMessage message = received[0];
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("newcomer@mail-send.test");
        assertThat(message.getSubject()).contains("Mail Send Ltd");

        // getContent(), NOT GreenMailUtil.getBody(): the raw body is quoted-printable, so a
        // literal "=" arrives as "=3D" and the 43-character token is split across a soft
        // line break. Asserting on the wire form would fail on a message that is perfectly
        // correct — and, worse, could be "fixed" by shortening the token.
        String body = message.getContent().toString();
        assertThat(body)
                .as("the link is the entire purpose of the message; without it the invitee "
                        + "has been told they were invited and given no way to accept")
                .contains(result.invitationLink().orElseThrow())
                .contains("New Comer")
                .contains("expires on");
    }

    @Test
    @DisplayName("a rolled-back issue sends nothing — the link would name a row that never was")
    void sendsNothingWhenTheTransactionRollsBack() throws Exception {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                "mail-rollback", "Mail Rollback Ltd", "rb@mail-rollback.test", "Rolled Back"));
        assertThat(SMTP.waitForIncomingEmail(10_000, 1)).isTrue();
        spendInvitation(result.companyId(), result.adminUserId());
        SMTP.reset();

        // Issue again inside a transaction that is then abandoned. This is the property
        // after-commit dispatch exists for, and it is only observable from the outside like
        // this: sent inline, the invitee would receive a working-looking link to a row the
        // rollback erased — an invitation that fails for a reason nobody can reconstruct.
        try (var ignored = CompanyContext.open(result.companyId())) {
            transactions.execute(status -> {
                InvitationService.IssueOutcome issued = invitations.issueIfPending(
                        result.companyId(), result.adminUserId(), null);
                assertThat(issued.issued()).isTrue();
                status.setRollbackOnly();
                return null;
            });
        }

        assertThat(SMTP.waitForIncomingEmail(1_000, 1)).isFalse();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("a dead relay does not undo the company it was announcing")
    void aFailedSendDoesNotFailProvisioning() {
        // Found by mutation: making the mailer rethrow instead of swallow failed NO test,
        // so the one claim the swallow exists for — "a mail outage cannot take a
        // provisioned company down with it" — was unverified. Provisioning would have
        // reported failure, and the operator would have gone looking for a database
        // problem that was never there.
        SMTP.stop();
        try {
            ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                    "mail-down", "Mail Down Ltd", "down@mail-down.test", "Relay Down"));

            assertThat(result.companyCreated()).isTrue();
            assertThat(result.invitationLink())
                    .as("the invitation is valid whether or not anyone could be told about "
                            + "it — which is exactly why the CLI prints the link")
                    .isPresent();
            assertThat(invitations.accept(
                    result.invitationLink().orElseThrow().replaceAll(".*\\?token=", ""),
                    "correct-horse-battery"))
                    .isInstanceOf(InvitationService.Accepted.class);
        } finally {
            SMTP.start();
        }
    }

    @Test
    @DisplayName("a re-run that issues nothing sends nothing")
    void silentWhenNothingWasIssued() throws Exception {
        provisioning.provision(ProvisionCompanyRequest.of(
                "mail-quiet", "Mail Quiet Ltd", "quiet@mail-quiet.test", "Quiet One"));
        assertThat(SMTP.waitForIncomingEmail(10_000, 1)).isTrue();
        SMTP.reset();

        provisioning.provision(ProvisionCompanyRequest.of(
                "mail-quiet", "Mail Quiet Ltd", "quiet@mail-quiet.test", "Quiet One"));

        // A top-up run is routine — it is how a company gets a vocabulary added in a later
        // release. Mailing the invitee every time would train them to ignore the message
        // that matters, and the link would not even be new.
        assertThat(SMTP.waitForIncomingEmail(1_000, 1)).isFalse();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }
}
