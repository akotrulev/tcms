package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one cross-company lookup, and the properties that keep it narrow.
 *
 * <p>Authentication precedes company context, so choosing a session's company means asking
 * a question RLS is specifically designed to refuse. {@code V8} answers it without a
 * bypass: the function runs as the caller and walks the active companies, asking the
 * ordinary policed question for each.
 *
 * <p><strong>An honest limit on what this class proves.</strong> {@code V7} got this wrong
 * — a {@code SECURITY DEFINER} function owned by the schema owner, which returns nothing in
 * production because {@code FORCE ROW LEVEL SECURITY} covers the owner — and the suite
 * could not have caught it, because {@code IntegrationTestBase} migrates as the container's
 * SUPERUSER and a superuser bypasses RLS unconditionally. These tests are meaningful now
 * only because {@code V8} runs as the <em>caller</em>, and the caller here is the same
 * unprivileged {@code tcms_app} that production uses. Closing the gap properly means a
 * non-superuser owner in the harness; that is recorded in the handoff rather than pretended
 * away.
 */
@DisplayName("Which companies an account may sign in to")
class CompaniesForUserTest extends IntegrationTestBase {

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("finds the company an activated member belongs to")
    void findsAnActiveMembership() {
        ProvisionCompanyResult result = activated("cfu-one", "cfu-one@test.test");

        List<UUID> companies = transactions.execute(status ->
                memberships.findCompanyIdsForUser(result.adminUserId()));

        assertThat(companies)
                .as("empty here is what a token with no `co` claim looks like, and a session "
                        + "with no company resolves to no permissions at all — an "
                        + "administrator signed in to an application that can do nothing")
                .contains(result.companyId());
    }

    @Test
    @DisplayName("does not see a membership that was never accepted")
    void ignoresAnInvitedMembership() {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                "cfu-invited", "CFU Invited", "cfu-invited@test.test", "Not Yet"));

        List<UUID> companies = transactions.execute(status ->
                memberships.findCompanyIdsForUser(result.adminUserId()));

        assertThat(companies)
                .as("an unaccepted invitation must not open a session — it would let someone "
                        + "look at a client's data before agreeing to anything")
                .doesNotContain(result.companyId());
    }

    @Test
    @DisplayName("finds both companies for someone who works with two clients")
    void findsEveryCompany() {
        ProvisionCompanyResult first = activated("cfu-two-a", "cfu-two@test.test");
        ProvisionCompanyResult second = activated("cfu-two-b", "cfu-two@test.test");

        List<UUID> companies = transactions.execute(status ->
                memberships.findCompanyIdsForUser(first.adminUserId()));

        assertThat(companies).contains(first.companyId(), second.companyId());
    }

    @Test
    @DisplayName("leaves the caller's company context exactly as it found it")
    void restoresTheCompanyContext() {
        ProvisionCompanyResult result = activated("cfu-guc", "cfu-guc@test.test");
        UUID other = activated("cfu-guc-other", "cfu-guc-other@test.test").companyId();

        // The function sets app.current_company per candidate as it walks. Inside a caller's
        // transaction that would otherwise leave the GUC pointing at whichever company the
        // loop finished on — a silently repointed tenancy discriminator, which is the exact
        // shape of a cross-company read that looks entirely ordinary afterwards.
        try (var ignored = CompanyContext.open(other)) {
            transactions.execute(status -> {
                memberships.findCompanyIdsForUser(result.adminUserId());
                assertThat(currentCompanyGuc()).isEqualTo(other.toString());
                return null;
            });
        }
    }

    /** Reads the GUC on the very connection the transaction is holding. */
    private String currentCompanyGuc() {
        return (String) entityManager
                .createNativeQuery(
                        "SELECT nullif(current_setting('app.current_company', true), '')")
                .getSingleResult();
    }

    /**
     * Provisions and walks the administrator to ACTIVE.
     *
     * <p>The password is sent only when the invitation asks for one. Someone joining their
     * SECOND company already has an account, and accept() refuses a password there rather
     * than ignoring it — holding an invitation must not confer the ability to overwrite an
     * existing credential. Passing one unconditionally left the membership INVITED and made
     * this class's two-company test fail for a reason that had nothing to do with it.
     */
    private ProvisionCompanyResult activated(String slug, String email) {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                slug, "CFU " + slug, email, "CFU Admin"));
        result.invitationLink().ifPresent(link -> {
            String token = link.replaceAll(".*\\?token=", "");
            boolean needsPassword = invitations.preview(token)
                    .map(InvitationService.InvitationPreview::passwordRequired)
                    .orElse(false);
            assertThat(invitations.accept(token, needsPassword ? "correct-horse-battery" : null))
                    .isInstanceOf(InvitationService.Accepted.class);
        });
        return result;
    }
}
