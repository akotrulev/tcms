package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.IdentityProvider;
import com.tcms.identity.IdentityProviderKind;
import com.tcms.identity.IdentityProviderRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only deleter {@code sso_login_attempt} has.
 *
 * <p>Every anonymous hit on the authorize leg inserts a row and nothing in the flow ever removes
 * one — {@code consume} stamps, expiry merely stops {@code isUsable} — so without the purge the
 * table the {@code V53} comment calls "empty in steady state" grows with every crawler that follows
 * a sign-in button's URL. The assertions are by id rather than by count, so rows another test class
 * left behind cannot fail this one.
 *
 * <p>Fixture UUIDs take prefix {@code f4} — hand-managed, no check behind it, collided five times
 * (§19…§62). The SSO flow next door holds {@code f2}, the provider CRUD {@code f3}.
 */
@DisplayName("SsoLoginAttemptPurge — expired attempts leave, recent and live ones stay")
class SsoLoginAttemptPurgeTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("f4000000-0000-4000-8000-00000000000a");
    private static final UUID PROVIDER = UUID.fromString("f4000000-0000-4000-8000-00000000000b");

    @Autowired
    private SsoLoginAttemptPurge purge;

    @Autowired
    private SsoLoginAttemptRepository attempts;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seed() {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            if (companies.findById(COMPANY).isEmpty()) {
                companies.save(Company.provision(COMPANY, "purge-sso", "Purgewind", now));
            }
        });
        try (var ignored = CompanyContext.open(COMPANY)) {
            tx.executeWithoutResult(status -> {
                if (providers.findById(PROVIDER).isEmpty()) {
                    providers.saveAndFlush(IdentityProvider.register(
                            PROVIDER,
                            COMPANY,
                            "purge-stub",
                            IdentityProviderKind.OIDC,
                            "Purge stub",
                            "https://idp.purge.test",
                            "tcms-client",
                            null,
                            null,
                            now));
                }
            });
        }
    }

    @Test
    @DisplayName("deletes what expired beyond the grace, keeps the freshly expired and the live")
    void deletesOnlyBeyondTheGrace() {
        Instant now = clock.instant();
        UUID longDead = attempt(now, now.minus(SsoLoginAttemptPurge.GRACE).minusSeconds(60));
        UUID freshlyExpired = attempt(now, now.minusSeconds(60));
        UUID live = attempt(now, now.plusSeconds(300));

        purge.purgeExpired();

        assertThat(attempts.findById(longDead))
                .describedAs("expired beyond the grace — the row the table would otherwise keep "
                        + "forever")
                .isEmpty();
        assertThat(attempts.findById(freshlyExpired))
                .describedAs("within the grace: the one durable trace of a sign-in somebody may "
                        + "still be chasing")
                .isPresent();
        assertThat(attempts.findById(live)).isPresent();
    }

    @Test
    @DisplayName("a consumed attempt leaves by the same door once its expiry passes")
    void aConsumedAttemptLeavesOnceExpired() {
        Instant now = clock.instant();
        UUID consumed = attempt(now, now.minus(SsoLoginAttemptPurge.GRACE).minusSeconds(60));
        tx.executeWithoutResult(status -> attempts.consume(consumed, now));

        purge.purgeExpired();

        assertThat(attempts.findById(consumed)).isEmpty();
    }

    /** No company context on purpose: the table is instance-level, exactly like the flow's reads. */
    private UUID attempt(Instant createdAt, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> attempts.saveAndFlush(SsoLoginAttempt.begin(
                id,
                COMPANY,
                PROVIDER,
                "state-" + id,
                "verifier-" + id,
                "nonce-" + id,
                "https://tcms.test/api/v1/auth/sso/callback",
                createdAt,
                expiresAt)));
        return id;
    }
}
