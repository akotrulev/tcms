package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The §6r policy engine, driven as pure logic — no Spring context, no Postgres, no issuer.
 *
 * <p>Every branch here is a rule somebody decided, and four of the six are silent when wrong: they
 * produce a session for the wrong account, or a second account for the right person, and neither
 * raises anything. {@link #anExistingIdentityWinsBeforeAnyLinkingPolicyIsConsulted} is the one that
 * matters most and is the easiest to omit — it is the difference between a corporate rename being
 * invisible and it costing somebody their account and their authorship.
 *
 * <p>What is deliberately <strong>not</strong> here: that a foreign company's provider cannot
 * resolve to this company's user. That is carried by RLS on {@code identity_provider} and
 * {@code user_identity}, so the only honest observer of it is a test with a real database and two
 * companies in it — it is a row in the backend mutation queue, not an assertion over a mock that
 * would agree with whatever this class did.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FederatedIdentityService — link, provision or refuse, and the subject is asked first")
class FederatedIdentityServiceTest {

    private static final UUID COMPANY = UUID.fromString("f1000000-0000-0000-0000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("f1000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Mock private UserIdentityRepository identities;
    @Mock private AppUserRepository users;
    @Mock private CompanyMembershipRepository memberships;
    @Mock private EntityManager entityManager;
    @Mock private Query baselineQuery;

    private FederatedIdentityService service;

    @BeforeEach
    void setUp() {
        service = new FederatedIdentityService(
                identities, users, memberships, entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("an already-linked subject resolves without the email claim being read at all")
    void anExistingIdentityWinsBeforeAnyLinkingPolicyIsConsulted() {
        AppUser kim = activeUser("kim@acme.test");
        UserIdentity existing = linkedIdentity(kim.getId());
        when(identities.findByProviderIdAndSubject(PROVIDER, "sub-1"))
                .thenReturn(Optional.of(existing));
        isAMember(kim);
        when(users.findById(kim.getId())).thenReturn(Optional.of(kim));

        var resolution = service.resolve(
                provider(false, false), claims("sub-1", "moved@elsewhere.test", true));

        assertThat(resolution).isEqualTo(new FederatedIdentityService.Resolution.Resolved(
                kim.getId(), false));
        assertThat(existing.getLastLoginAt()).isEqualTo(NOW);
        verify(users, never()).findByEmail(any());
    }

    @Test
    @DisplayName("a linked identity whose account was deactivated is refused, not admitted")
    void aLinkedIdentityIsReCheckedAgainstTheAccountEveryTime() {
        AppUser kim = activeUser("kim@acme.test");
        kim.deactivate(NOW);
        when(identities.findByProviderIdAndSubject(PROVIDER, "sub-1"))
                .thenReturn(Optional.of(linkedIdentity(kim.getId())));
        when(users.findById(kim.getId())).thenReturn(Optional.of(kim));

        assertThat(service.resolve(provider(true, true), claims("sub-1", "kim@acme.test", true)))
                .isEqualTo(FederatedIdentityService.Resolution.refused());
    }

    @Test
    @DisplayName("an unverified email never links, even with auto-linking on")
    void anUnverifiedEmailNeverLinksEvenWhenAutoLinkIsOn() {
        AppUser kim = activeUser("kim@acme.test");
        noIdentityYet();
        when(users.findByEmail("kim@acme.test")).thenReturn(Optional.of(kim));

        var resolution = service.resolve(
                provider(true, false), claims("sub-1", "kim@acme.test", false));

        assertThat(resolution).isEqualTo(FederatedIdentityService.Resolution.refused());
        verify(identities, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a verified email links to the existing account rather than making a second one")
    void aVerifiedEmailLinksToTheExistingUserRatherThanCreatingASecond() {
        AppUser kim = activeUser("kim@acme.test");
        noIdentityYet();
        when(users.findByEmail("kim@acme.test")).thenReturn(Optional.of(kim));
        isAMember(kim);

        var resolution = service.resolve(
                provider(true, false), claims("sub-1", "kim@acme.test", true));

        assertThat(resolution).isEqualTo(new FederatedIdentityService.Resolution.Resolved(
                kim.getId(), false));
        UserIdentity written = savedIdentity();
        assertThat(written.getSubject()).isEqualTo("sub-1");
        assertThat(written.getUserId()).isEqualTo(kim.getId());
        assertThat(written.getCompanyId()).isEqualTo(COMPANY);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("first login with neither JIT nor an existing account is refused")
    void firstLoginWithoutJitOrAnInvitationIsRefused() {
        noIdentityYet();
        when(users.findByEmail("new@acme.test")).thenReturn(Optional.empty());

        assertThat(service.resolve(provider(false, false), claims("sub-1", "new@acme.test", true)))
                .isEqualTo(FederatedIdentityService.Resolution.refused());
    }

    @Test
    @DisplayName("JIT grants exactly what accepting an invitation grants: a member, and no role")
    void jitProvisioningGrantsExactlyWhatAcceptingAnInvitationGrants() {
        noIdentityYet();
        when(users.findByEmail("new@acme.test")).thenReturn(Optional.empty());
        baselineIsGranted();

        var resolution = service.resolve(
                provider(false, true), claims("sub-1", "new@acme.test", true, "New Person"));

        assertThat(resolution).isInstanceOf(FederatedIdentityService.Resolution.Resolved.class);
        assertThat(((FederatedIdentityService.Resolution.Resolved) resolution).provisioned()).isTrue();

        ArgumentCaptor<AppUser> user = ArgumentCaptor.forClass(AppUser.class);
        verify(users).saveAndFlush(user.capture());
        assertThat(user.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getValue().getPasswordHash()).isNull();

        ArgumentCaptor<CompanyMembership> membership =
                ArgumentCaptor.forClass(CompanyMembership.class);
        verify(memberships).saveAndFlush(membership.capture());
        assertThat(membership.getValue().getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membership.getValue().getDisplayName()).isEqualTo("New Person");
    }

    @Test
    @DisplayName("JIT cannot fork an existing address into a second account")
    void jitDoesNotApplyWhenAnAccountAlreadyHoldsTheAddress() {
        noIdentityYet();
        when(users.findByEmail("kim@acme.test"))
                .thenReturn(Optional.of(activeUser("kim@acme.test")));

        assertThat(service.resolve(provider(false, true), claims("sub-1", "kim@acme.test", true)))
                .isEqualTo(FederatedIdentityService.Resolution.refused());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an account that is not a member of this company cannot be linked into it")
    void linkingRequiresAnActiveMembershipInTheProvidersCompany() {
        AppUser kim = activeUser("kim@acme.test");
        noIdentityYet();
        when(users.findByEmail("kim@acme.test")).thenReturn(Optional.of(kim));
        when(memberships.existsByCompanyIdAndUserIdAndStatus(
                COMPANY, kim.getId(), MembershipStatus.ACTIVE)).thenReturn(false);

        assertThat(service.resolve(provider(true, true), claims("sub-1", "kim@acme.test", true)))
                .isEqualTo(FederatedIdentityService.Resolution.refused());
        verify(identities, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an assertion with no subject is refused before anything is read")
    void anAssertionWithoutASubjectIsRefused() {
        assertThat(service.resolve(provider(true, true), claims("  ", "kim@acme.test", true)))
                .isEqualTo(FederatedIdentityService.Resolution.refused());
    }

    private void noIdentityYet() {
        when(identities.findByProviderIdAndSubject(PROVIDER, "sub-1")).thenReturn(Optional.empty());
    }

    private void isAMember(AppUser user) {
        when(memberships.existsByCompanyIdAndUserIdAndStatus(
                COMPANY, user.getId(), MembershipStatus.ACTIVE)).thenReturn(true);
    }

    private void baselineIsGranted() {
        when(entityManager.createNativeQuery(
                "SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))"))
                .thenReturn(baselineQuery);
        when(baselineQuery.setParameter("companyId", COMPANY.toString())).thenReturn(baselineQuery);
        when(baselineQuery.getSingleResult()).thenReturn(1);
    }

    private UserIdentity savedIdentity() {
        ArgumentCaptor<UserIdentity> captor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identities).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static IdentityProvider provider(boolean autoLink, boolean jit) {
        IdentityProvider provider = IdentityProvider.register(
                PROVIDER, COMPANY, "corporate", IdentityProviderKind.OIDC, "Corporate",
                "https://idp.acme.test", "tcms", null, null, NOW);
        provider.applyEdit(
                "Corporate", "https://idp.acme.test", "tcms", null, jit, autoLink, null, true,
                null, NOW);
        return provider;
    }

    private static FederatedIdentityService.Claims claims(
            String subject, String email, boolean verified) {
        return claims(subject, email, verified, null);
    }

    private static FederatedIdentityService.Claims claims(
            String subject, String email, boolean verified, String name) {
        return new FederatedIdentityService.Claims(subject, email, verified, name);
    }

    private static AppUser activeUser(String email) {
        return AppUser.federate(UUID.randomUUID(), email, email, NOW);
    }

    private static UserIdentity linkedIdentity(UUID userId) {
        return UserIdentity.link(
                UUID.randomUUID(), COMPANY, userId, PROVIDER, "sub-1", "kim@acme.test", NOW);
    }
}
