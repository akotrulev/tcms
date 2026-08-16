package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The social policy layer against the real database — the unique constraints are half the
 * behaviour here (the linking race's backstop, one-per-provider-per-account), so a mock that
 * agrees with whatever the service did would prove nothing.
 *
 * <p>Every fixture account arrives through an existing on-ramp: {@code provision-company} +
 * invitation for the passworded ones, {@link AppUser#federate} for the passwordless one —
 * because that is the plan's core decision (social sign-in never creates an account), and the
 * fixtures should look like the accounts production will actually hold.
 */
@DisplayName("SocialIdentityService — resolve, link, unlink; never provision")
class SocialIdentityServiceTest extends IntegrationTestBase {

    @Autowired
    private SocialIdentityService service;

    @Autowired
    private UserSocialIdentityRepository identities;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private TransactionTemplate transactions;

    // ------------------------------------------------------------------ resolveLogin ----

    @Test
    @DisplayName("a linked subject on an ACTIVE account resolves and stamps last_login_at on both rows")
    void aLinkedSubjectResolves() {
        UUID userId = activated("sis-login", "sis-login@test.test");
        assertThat(service.link(userId, SocialProviderKey.GITHUB, claims("gh-1")))
                .isInstanceOf(SocialIdentityService.LinkOutcome.Linked.class);

        var resolution = service.resolveLogin(SocialProviderKey.GITHUB, claims("gh-1"));

        assertThat(resolution)
                .isEqualTo(new SocialIdentityService.LoginResolution.Resolved(userId));
        UserSocialIdentity identity = transactions.execute(status ->
                identities.findByProviderAndSubject(SocialProviderKey.GITHUB, "gh-1")
                        .orElseThrow());
        assertThat(identity.getLastLoginAt()).isNotNull();
        AppUser user = transactions.execute(status -> users.findById(userId).orElseThrow());
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("an unknown subject refuses — never a provision")
    void anUnknownSubjectRefuses() {
        assertThat(service.resolveLogin(SocialProviderKey.GITHUB, claims("gh-nobody")))
                .isInstanceOf(SocialIdentityService.LoginResolution.Refused.class);
        Optional<UserSocialIdentity> stored = transactions.execute(status ->
                identities.findByProviderAndSubject(SocialProviderKey.GITHUB, "gh-nobody"));
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("a linked subject on a DEACTIVATED account refuses with the same refusal")
    void aDeactivatedAccountRefuses() {
        UUID userId = activated("sis-deact", "sis-deact@test.test");
        service.link(userId, SocialProviderKey.GITHUB, claims("gh-deact"));
        transactions.executeWithoutResult(status -> {
            AppUser user = users.findById(userId).orElseThrow();
            user.deactivate(Instant.now());
            users.saveAndFlush(user);
        });

        assertThat(service.resolveLogin(SocialProviderKey.GITHUB, claims("gh-deact")))
                .isInstanceOf(SocialIdentityService.LoginResolution.Refused.class);
    }

    @Test
    @DisplayName("an INVITED account refuses — an unaccepted invitation must not open a session")
    void anInvitedAccountRefuses() {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                "sis-invited", "SIS Invited", "sis-invited@test.test", "Not Yet"));
        // The identity is written directly: nothing in the product links an INVITED account,
        // so this is the defence-in-depth reading of resolveLogin's status check.
        transactions.executeWithoutResult(status -> identities.saveAndFlush(
                UserSocialIdentity.link(UUID.randomUUID(), result.adminUserId(),
                        SocialProviderKey.GITHUB, "gh-invited", null, Instant.now())));

        assertThat(service.resolveLogin(SocialProviderKey.GITHUB, claims("gh-invited")))
                .isInstanceOf(SocialIdentityService.LoginResolution.Refused.class);
    }

    @Test
    @DisplayName("email plays no part — a linked identity resolves even when the asserted email is someone else's")
    void emailIsNeverAKey() {
        UUID kim = activated("sis-email-a", "sis-kim@test.test");
        activated("sis-email-b", "sis-other@test.test");
        service.link(kim, SocialProviderKey.GOOGLE, claims("goog-1"));

        var resolution = service.resolveLogin(SocialProviderKey.GOOGLE,
                new SocialIdentityService.Claims("goog-1", "sis-other@test.test", "Kim"));

        assertThat(resolution)
                .isEqualTo(new SocialIdentityService.LoginResolution.Resolved(kim));
    }

    @Test
    @DisplayName("a blank subject refuses before any read")
    void aBlankSubjectRefuses() {
        assertThat(service.resolveLogin(SocialProviderKey.GITHUB,
                new SocialIdentityService.Claims(" ", "x@test.test", "X")))
                .isInstanceOf(SocialIdentityService.LoginResolution.Refused.class);
        assertThat(service.resolveLogin(SocialProviderKey.GITHUB, null))
                .isInstanceOf(SocialIdentityService.LoginResolution.Refused.class);
    }

    // ------------------------------------------------------------------------- link ----

    @Test
    @DisplayName("linking records the subject and the asserted email as provenance")
    void linkingRecordsProvenance() {
        UUID userId = activated("sis-link", "sis-link@test.test");

        var outcome = service.link(userId, SocialProviderKey.GITLAB,
                new SocialIdentityService.Claims("gl-1", "  sis-link@gitlab.test  ", "Kim"));

        assertThat(outcome).isInstanceOf(SocialIdentityService.LinkOutcome.Linked.class);
        UserSocialIdentity identity = transactions.execute(status ->
                identities.findByProviderAndSubject(SocialProviderKey.GITLAB, "gl-1")
                        .orElseThrow());
        assertThat(identity.getUserId()).isEqualTo(userId);
        assertThat(identity.getEmailAtLink()).isEqualTo("sis-link@gitlab.test");
    }

    @Test
    @DisplayName("a subject already bound to ANOTHER account refuses — policy, not a 500")
    void aTakenSubjectRefuses() {
        UUID first = activated("sis-taken-a", "sis-taken-a@test.test");
        UUID second = activated("sis-taken-b", "sis-taken-b@test.test");
        service.link(first, SocialProviderKey.GITHUB, claims("gh-taken"));

        assertThat(service.link(second, SocialProviderKey.GITHUB, claims("gh-taken")))
                .isInstanceOf(SocialIdentityService.LinkOutcome.Refused.class);
        UUID holder = transactions.execute(status ->
                identities.findByProviderAndSubject(SocialProviderKey.GITHUB, "gh-taken")
                        .orElseThrow().getUserId());
        assertThat(holder).isEqualTo(first);
    }

    @Test
    @DisplayName("re-linking the same subject to the same account is idempotent success")
    void relinkingIsIdempotent() {
        UUID userId = activated("sis-idem", "sis-idem@test.test");
        service.link(userId, SocialProviderKey.GITHUB, claims("gh-idem"));

        assertThat(service.link(userId, SocialProviderKey.GITHUB, claims("gh-idem")))
                .isInstanceOf(SocialIdentityService.LinkOutcome.Linked.class);
        Long count = transactions.execute(status -> identities.countByUserId(userId));
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("a second identity from the same provider refuses — link all three is the ceiling")
    void oneIdentityPerProvider() {
        UUID userId = activated("sis-second", "sis-second@test.test");
        service.link(userId, SocialProviderKey.GITHUB, claims("gh-first"));

        assertThat(service.link(userId, SocialProviderKey.GITHUB, claims("gh-second")))
                .isInstanceOf(SocialIdentityService.LinkOutcome.Refused.class);
    }

    // ----------------------------------------------------------------------- unlink ----

    @Test
    @DisplayName("unlinking works when the account keeps a password to sign in with")
    void unlinkWithPasswordRemains() {
        UUID userId = activated("sis-unlink", "sis-unlink@test.test");
        service.link(userId, SocialProviderKey.GITHUB, claims("gh-unlink"));

        assertThat(service.unlink(userId, SocialProviderKey.GITHUB))
                .isInstanceOf(SocialIdentityService.UnlinkOutcome.Unlinked.class);
        Optional<UserSocialIdentity> remaining = transactions.execute(status ->
                identities.findByProviderAndUserId(SocialProviderKey.GITHUB, userId));
        assertThat(remaining).isEmpty();
    }

    @Test
    @DisplayName("a passwordless account may not unlink its only identity — the last-credential guard")
    void lastCredentialRefuses() {
        UUID userId = federated("sis-jit@test.test");
        service.link(userId, SocialProviderKey.GITHUB, claims("gh-jit"));

        assertThat(service.unlink(userId, SocialProviderKey.GITHUB))
                .isInstanceOf(SocialIdentityService.UnlinkOutcome.LastCredential.class);
        Optional<UserSocialIdentity> kept = transactions.execute(status ->
                identities.findByProviderAndUserId(SocialProviderKey.GITHUB, userId));
        assertThat(kept).isPresent();
    }

    @Test
    @DisplayName("a passwordless account with a second identity may unlink one of them")
    void aSecondIdentityFreesTheFirst() {
        UUID userId = federated("sis-jit2@test.test");
        service.link(userId, SocialProviderKey.GITHUB, claims("gh-jit2"));
        service.link(userId, SocialProviderKey.GOOGLE, claims("goog-jit2"));

        assertThat(service.unlink(userId, SocialProviderKey.GITHUB))
                .isInstanceOf(SocialIdentityService.UnlinkOutcome.Unlinked.class);
        Long remaining = transactions.execute(status -> identities.countByUserId(userId));
        assertThat(remaining).isEqualTo(1L);
    }

    @Test
    @DisplayName("unlinking what was never linked answers Unlinked — the end state is the same")
    void unlinkingNothingIsUnlinked() {
        UUID userId = activated("sis-noop", "sis-noop@test.test");

        assertThat(service.unlink(userId, SocialProviderKey.GITLAB))
                .isInstanceOf(SocialIdentityService.UnlinkOutcome.Unlinked.class);
    }

    // ------------------------------------------------------------------ identitiesOf ----

    @Test
    @DisplayName("identitiesOf lists the caller's identities oldest-first")
    void identitiesAreListedOldestFirst() {
        UUID userId = activated("sis-list", "sis-list@test.test");
        service.link(userId, SocialProviderKey.GOOGLE, claims("goog-list"));
        service.link(userId, SocialProviderKey.GITHUB, claims("gh-list"));

        assertThat(service.identitiesOf(userId))
                .extracting(UserSocialIdentity::getProvider)
                .containsExactly(SocialProviderKey.GOOGLE, SocialProviderKey.GITHUB);
    }

    // --------------------------------------------------------------------- fixtures ----

    private static SocialIdentityService.Claims claims(String subject) {
        return new SocialIdentityService.Claims(subject, subject + "@social.test", "Somebody");
    }

    /** Provisions a company and walks its administrator to ACTIVE with a password. */
    private UUID activated(String slug, String email) {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                slug, "SIS " + slug, email, "SIS Admin"));
        result.invitationLink().ifPresent(link -> {
            String token = link.replaceAll(".*\\?token=", "");
            boolean needsPassword = invitations.preview(token)
                    .map(InvitationService.InvitationPreview::passwordRequired)
                    .orElse(false);
            assertThat(invitations.accept(token, needsPassword ? "correct-horse-battery" : null))
                    .isInstanceOf(InvitationService.Accepted.class);
        });
        return result.adminUserId();
    }

    /** A passwordless ACTIVE account, the way company-IdP JIT (§6r) creates one. */
    private UUID federated(String email) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> users.saveAndFlush(
                AppUser.federate(id, email, "JIT " + email, Instant.now())));
        return id;
    }
}
