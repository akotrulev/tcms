package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins the behaviour that {@code AppUser.email}'s {@code @JdbcTypeCode(OTHER)} exists to
 * preserve.
 *
 * <p>The annotation was added to satisfy {@code ddl-auto: validate}, which refuses to
 * start against a {@code citext} column mapped as {@code varchar}. An annotation added to
 * silence a startup error is exactly the kind that a later cleanup removes — and removing
 * it fails loudly at boot, so that part is safe. What is <em>not</em> obvious is that the
 * binding also carries the case-insensitive comparison, so this asserts the product
 * behaviour rather than the mapping.
 *
 * <p>It matters beyond tidiness: email is unique instance-wide, and if uniqueness became
 * case-sensitive, {@code Alice@corp.test} and {@code alice@corp.test} would be two
 * accounts. Under M4's SSO that is an account-takeover shape, not a duplicate-row
 * annoyance.
 */
@DisplayName("app_user.email — CITEXT semantics survive the JPA mapping")
class AppUserEmailCaseTest extends IntegrationTestBase {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx.executeWithoutResult(status -> {
            if (users.findById(USER).isEmpty()) {
                users.save(AppUser.invite(USER, "Mixed.Case@Example.Test", "Mixed Case", Instant.now()));
            }
        });
    }

    @Test
    @DisplayName("lookup is case-insensitive in both directions")
    void lookupIgnoresCase() {
        tx.executeWithoutResult(status -> {
            assertThat(users.findByEmail("mixed.case@example.test")).isPresent();
            assertThat(users.findByEmail("MIXED.CASE@EXAMPLE.TEST")).isPresent();
            assertThat(users.findByEmail("Mixed.Case@Example.Test")).isPresent();
        });
    }

    @Test
    @DisplayName("the stored value keeps its original casing — case-insensitive, not case-folding")
    void storedValueIsNotLowerCased() {
        // A repository that defensively lower-cased on write would pass the lookup test
        // above while destroying the user's own rendering of their address.
        tx.executeWithoutResult(status ->
                assertThat(users.findById(USER).orElseThrow().getEmail())
                        .isEqualTo("Mixed.Case@Example.Test"));
    }

    @Test
    @DisplayName("uniqueness is case-insensitive — two casings cannot become two accounts")
    void uniquenessIgnoresCase() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                users.saveAndFlush(AppUser.invite(
                        UUID.randomUUID(), "MIXED.CASE@EXAMPLE.TEST", "Impostor", Instant.now()))))
                .as("a second casing of the same address must be refused by uq_app_user_email")
                .isInstanceOf(Exception.class);
    }
}
