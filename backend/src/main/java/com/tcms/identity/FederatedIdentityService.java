package com.tcms.identity;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an authenticated external assertion is allowed to become here — link, provision, or refuse.
 *
 * <p>The protocol half lives in {@code com.tcms.auth}: by the time anything here runs, an
 * {@code id_token} has been verified against the issuer's keys and the subject below is a fact.
 * This class decides the only question left, which is <strong>whose account that subject is</strong>
 * — and it is pure policy over the database, so it has no HTTP client and could not acquire one:
 * {@code identity} may not reach the network (§6r, trap 5).
 *
 * <h2>The subject is consulted first, and email is not consulted at all when it hits</h2>
 *
 * <p>{@code (provider, subject)} is the identity key. A person whose corporate address changed
 * still resolves to their own account, their own memberships and their own authorship, because the
 * already-linked branch below returns before {@link AppUserRepository#findByEmail} is reached. Get
 * that order wrong and a rename at the IdP silently forks somebody into a second account.
 *
 * <p>Issuer trust is company-local, and that is carried by the schema rather than by a check here:
 * {@code identity_provider} is company-owned and FORCE RLS-protected, so a provider id from another
 * company resolves to nothing long before this class sees it (§6r).
 *
 * <h2>Every refusal is the same refusal</h2>
 *
 * <p>{@link Resolution.Refused} is one value for every cause — no identity, no invitation, JIT off,
 * unverified email, deactivated account, membership removed. That is
 * {@code AuthenticationService}'s rule on the surface it already covers, applied to the surface
 * this adds: a login that told those apart would be an oracle for who has an account on this
 * instance and which client they work for, and email is unique instance-wide, so that directory
 * would span every company.
 *
 * <p>The enum-less shape is what makes it structural. There is no reason field to populate, so a
 * later edit cannot leak one by filling it in.
 */
@Service
public class FederatedIdentityService {

    private static final Logger log = LoggerFactory.getLogger(FederatedIdentityService.class);

    /**
     * What the verified {@code id_token} said.
     *
     * @param subject       the IdP's {@code sub} — the only stable handle, and the key
     * @param email         the {@code email} claim, which may be absent, may change, and may be
     *                      reassigned to somebody else
     * @param emailVerified the {@code email_verified} claim. An issuer that does not send it is
     *                      treated as not having verified anything
     * @param name          the {@code name} claim, for the membership's display name when this
     *                      sign-in provisions one
     */
    public record Claims(String subject, String email, boolean emailVerified, String name) {
    }

    /** Who signed in, or that nobody did. */
    public sealed interface Resolution {

        /** One value for every cause — see the class header. */
        record Refused() implements Resolution {
        }

        /** @param provisioned true when this sign-in is what created the account */
        record Resolved(UUID userId, boolean provisioned) implements Resolution {
        }

        static Resolution refused() {
            return new Refused();
        }
    }

    private final UserIdentityRepository identities;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final EntityManager entityManager;
    private final Clock clock;

    public FederatedIdentityService(
            UserIdentityRepository identities,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            EntityManager entityManager,
            Clock clock) {

        this.identities = identities;
        this.users = users;
        this.memberships = memberships;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Resolves a verified assertion to the account that will hold the session.
     *
     * <p><strong>Must be called with {@code provider}'s company context already open</strong> —
     * every repository below reads a company-owned, RLS-protected table, so a call outside one
     * reads zero rows and refuses everybody. The company comes from the provider row rather than
     * from a parameter, so there is one answer to "which company is this" and nothing can disagree
     * with it (ERD §0.1 point 2).
     *
     * <p>Four outcomes in a fixed order, and the order is the policy:
     *
     * <ol>
     *   <li>the subject is already linked — done, and email is never read;</li>
     *   <li>an account holds the asserted address, the provider auto-links, and the issuer says the
     *       address is verified — linked;</li>
     *   <li>no account holds it and the provider provisions just-in-time — created, with exactly
     *       what accepting an invitation grants and nothing more;</li>
     *   <li>anything else — refused.</li>
     * </ol>
     */
    @Transactional
    public Resolution resolve(IdentityProvider provider, Claims claims) {
        if (claims == null || isBlank(claims.subject())) {
            return Resolution.refused();
        }
        Instant now = clock.instant();

        Optional<UserIdentity> linked =
                identities.findByProviderIdAndSubject(provider.getId(), claims.subject());
        if (linked.isPresent()) {
            return admit(linked.get(), now);
        }

        String email = normalised(claims.email());
        if (email == null) {
            return refuse(provider, "the assertion carried no email address");
        }

        Optional<AppUser> byEmail = users.findByEmail(email);
        if (byEmail.isPresent()) {
            // JIT cannot help here even when it is on: email is unique instance-wide, so there is
            // no second account to create. Linking on an address the issuer has not verified is
            // the account-takeover vector §6r exists to close, so the only remaining answer is no.
            if (!provider.isAutoLinkVerifiedEmail() || !claims.emailVerified()) {
                return refuse(provider, "an account holds the address and auto-linking does not apply");
            }
            return link(provider, byEmail.get(), claims, email, now);
        }

        if (!provider.isJitProvisioning()) {
            return refuse(provider, "no account holds the address and the provider does not provision");
        }
        return provision(provider, claims, email, now);
    }

    /**
     * The already-linked branch. Both gates are re-asked here rather than trusted from link time:
     * an account deactivated instance-wide, or a membership since removed, must stop signing in
     * through the IdP as promptly as it stops signing in locally.
     */
    private Resolution admit(UserIdentity identity, Instant now) {
        AppUser user = users.findById(identity.getUserId()).orElse(null);
        if (user == null || !user.isActive()
                || !isMember(identity.getCompanyId(), identity.getUserId())) {
            log.info("SSO refused for a linked identity that is no longer admissible");
            return Resolution.refused();
        }
        identity.recordLogin(now);
        user.recordLogin(now);
        return new Resolution.Resolved(user.getId(), false);
    }

    private Resolution link(
            IdentityProvider provider, AppUser user, Claims claims, String email, Instant now) {

        if (!user.isActive() || !isMember(provider.getCompanyId(), user.getId())) {
            return refuse(provider, "the account holding the address may not sign in here");
        }
        UserIdentity identity = UserIdentity.link(
                UUID.randomUUID(),
                provider.getCompanyId(),
                user.getId(),
                provider.getId(),
                claims.subject(),
                email,
                now);
        identity.recordLogin(now);
        identities.saveAndFlush(identity);
        user.recordLogin(now);

        log.info("Linked user {} to provider {} on a verified address", user.getId(), provider.getId());
        return new Resolution.Resolved(user.getId(), false);
    }

    /**
     * Just-in-time provisioning — <strong>exactly what accepting an invitation produces</strong>: an
     * account, an {@code ACTIVE} membership, and the {@code company:read} baseline. Deliberately no
     * {@link RoleAssignment} of any kind, so SSO cannot become a side door to more capability than
     * the invite flow grants (roles spec §A7), and project access stays an explicit act by somebody
     * who holds {@code project:members}.
     */
    private Resolution provision(
            IdentityProvider provider, Claims claims, String email, Instant now) {

        UUID companyId = provider.getCompanyId();
        String displayName = Optional.ofNullable(normalised(claims.name())).orElse(email);

        AppUser user = AppUser.federate(UUID.randomUUID(), email, displayName, now);
        user.recordLogin(now);
        users.saveAndFlush(user);

        CompanyMembership membership =
                CompanyMembership.invite(companyId, user.getId(), displayName, null, now);
        membership.accept(now);
        memberships.saveAndFlush(membership);
        grantCompanyReadBaseline(companyId);

        UserIdentity identity = UserIdentity.link(
                UUID.randomUUID(),
                companyId,
                user.getId(),
                provider.getId(),
                claims.subject(),
                email,
                now);
        identity.recordLogin(now);
        identities.saveAndFlush(identity);

        log.info("Provisioned user {} from provider {}", user.getId(), provider.getId());
        return new Resolution.Resolved(user.getId(), true);
    }

    /**
     * Grants the {@code company:read} baseline to every member of the company who lacks it.
     *
     * <p>The third call site of {@code tcms_grant_company_read_baseline} (V15), and the one
     * {@link MembershipInviteService}'s header argued against needing: it reasoned that
     * {@link InvitationService#accept} is where a person joins. A JIT membership joins without ever
     * passing through an acceptance, so this path would otherwise leave somebody signed in to a
     * company they cannot read. What is copied is the <em>call</em> — the rule about who gets the
     * baseline is still stated once, in the migration.
     */
    private void grantCompanyReadBaseline(UUID companyId) {
        entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
    }

    private boolean isMember(UUID companyId, UUID userId) {
        return memberships.existsByCompanyIdAndUserIdAndStatus(
                companyId, userId, MembershipStatus.ACTIVE);
    }

    /**
     * The one refusal, logged with its cause and answering without one. The reason is an operator's
     * to read in the log; it never travels back to the caller.
     */
    private Resolution refuse(IdentityProvider provider, String because) {
        log.info("SSO refused against provider {}: {}", provider.getId(), because);
        return Resolution.refused();
    }

    private static String normalised(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
