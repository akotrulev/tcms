package com.tcms.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an authenticated social assertion may become — {@link FederatedIdentityService}'s twin
 * for the instance-level providers, and deliberately narrower: it may resolve an existing link
 * or make one for a signed-in caller, and it may <strong>never provision</strong>. Account
 * creation stays with the invitation flow and company-IdP JIT (the 2026-08-16 plan's decision
 * 1); a social subject nobody linked is one more cause inside the one refusal.
 *
 * <p>Pure policy over the database — no HTTP client, per §6r trap 5. The protocol half
 * ({@code SocialLoginService}) has verified the assertion before anything here runs.
 *
 * <p>Runs with <strong>no company context</strong>, which is only possible because both tables
 * are instance-level (ERD §0.1, decided in V58).
 */
@Service
public class SocialIdentityService {

    private static final Logger log = LoggerFactory.getLogger(SocialIdentityService.class);

    /** What the provider asserted. Email is provenance, never a key (§6r). */
    public record Claims(String subject, String email, String name) {
    }

    /** Who signed in, or that nobody did — one refusal for every cause. */
    public sealed interface LoginResolution {
        record Refused() implements LoginResolution {
        }

        record Resolved(UUID userId) implements LoginResolution {
        }
    }

    /** The outcome of a link attempt. */
    public sealed interface LinkOutcome {
        record Linked() implements LinkOutcome {
        }

        /**
         * The subject belongs to a different account, or this account already has one from
         * this provider. One value: told apart, a linker could probe whose GitHub is whose.
         */
        record Refused() implements LinkOutcome {
        }
    }

    /** The outcome of an unlink attempt. */
    public sealed interface UnlinkOutcome {
        record Unlinked() implements UnlinkOutcome {
        }

        /** Removing it would leave the account with no way to sign in. */
        record LastCredential() implements UnlinkOutcome {
        }
    }

    private final UserSocialIdentityRepository identities;
    private final AppUserRepository users;
    private final Clock clock;

    public SocialIdentityService(
            UserSocialIdentityRepository identities, AppUserRepository users, Clock clock) {
        this.identities = identities;
        this.users = users;
        this.clock = clock;
    }

    /** Resolves a verified assertion to the account holding it, or refuses. Never creates. */
    @Transactional
    public LoginResolution resolveLogin(SocialProviderKey provider, Claims claims) {
        if (claims == null || isBlank(claims.subject())) {
            return new LoginResolution.Refused();
        }
        Instant now = clock.instant();

        Optional<UserSocialIdentity> linked =
                identities.findByProviderAndSubject(provider, claims.subject());
        if (linked.isEmpty()) {
            return refuse(provider, "no account holds this subject");
        }
        AppUser user = users.findById(linked.get().getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            return refuse(provider, "the linked account may not sign in");
        }
        linked.get().recordLogin(now);
        user.recordLogin(now);
        return new LoginResolution.Resolved(user.getId());
    }

    /** Binds {@code (provider, subject)} to {@code userId}. Idempotent for the same pair. */
    @Transactional
    public LinkOutcome link(UUID userId, SocialProviderKey provider, Claims claims) {
        if (claims == null || isBlank(claims.subject())) {
            return new LinkOutcome.Refused();
        }
        Optional<UserSocialIdentity> bySubject =
                identities.findByProviderAndSubject(provider, claims.subject());
        if (bySubject.isPresent()) {
            return bySubject.get().getUserId().equals(userId)
                    ? new LinkOutcome.Linked()
                    : refuseLink(provider, "the subject belongs to a different account");
        }
        if (identities.findByProviderAndUserId(provider, userId).isPresent()) {
            return refuseLink(provider, "the account already has an identity from this provider");
        }
        try {
            identities.saveAndFlush(UserSocialIdentity.link(
                    UUID.randomUUID(),
                    userId,
                    provider,
                    claims.subject(),
                    normalised(claims.email()),
                    clock.instant()));
        } catch (DataIntegrityViolationException raced) {
            // Two callbacks racing past the reads above: the unique constraints decide, and the
            // loser gets the same refusal it would have gotten a millisecond later.
            return refuseLink(provider, "a concurrent link won the race");
        }
        log.info("Linked user {} to social provider {}", userId, provider);
        return new LinkOutcome.Linked();
    }

    /**
     * Removes the link — unless it is the account's only credential. The guard counts the
     * password and the other social identities and deliberately NOT company-IdP identities:
     * {@code user_identity} is RLS-protected per company, so a cross-company count from here
     * would be structurally incomplete, and the safe direction is to refuse (the plan's
     * decision 10).
     */
    @Transactional
    public UnlinkOutcome unlink(UUID userId, SocialProviderKey provider) {
        Optional<UserSocialIdentity> linked = identities.findByProviderAndUserId(provider, userId);
        if (linked.isEmpty()) {
            return new UnlinkOutcome.Unlinked();
        }
        AppUser user = users.findById(userId).orElse(null);
        boolean hasPassword = user != null && user.getPasswordHash() != null;
        if (!hasPassword && identities.countByUserId(userId) <= 1) {
            return new UnlinkOutcome.LastCredential();
        }
        identities.delete(linked.get());
        log.info("Unlinked user {} from social provider {}", userId, provider);
        return new UnlinkOutcome.Unlinked();
    }

    @Transactional(readOnly = true)
    public List<UserSocialIdentity> identitiesOf(UUID userId) {
        return identities.findByUserIdOrderByLinkedAt(userId);
    }

    private LoginResolution refuse(SocialProviderKey provider, String because) {
        log.info("Social sign-in refused against {}: {}", provider, because);
        return new LoginResolution.Refused();
    }

    private LinkOutcome refuseLink(SocialProviderKey provider, String because) {
        log.info("Social link refused against {}: {}", provider, because);
        return new LinkOutcome.Refused();
    }

    private static String normalised(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
