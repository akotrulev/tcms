package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.InvitationService;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 005 S10 — the local sign-in lockout, per address and silent.
 *
 * <p>Two lanes: the throttle's arithmetic against a controlled clock (no container needed, but the
 * class shares this suite's context for the properties), and the endpoint's behaviour, which is the
 * property that matters — a locked address is answered exactly like a wrong password.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tcms.rate-limit.enabled=true",
        "tcms.rate-limit.sign-in-attempts=3",
        "tcms.rate-limit.sign-in-lockout=15m",
})
@DisplayName("Sign-in lockout — per address, silent, and forgotten by success")
class SignInLockoutTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private RateLimitProperties properties;

    @Test
    @DisplayName("the arithmetic: N failures inside the window lock, the window is from the FIRST failure")
    void windowIsMeasuredFromTheFirstFailure() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T10:00:00Z"));
        SignInThrottle throttle = new SignInThrottle(properties, clock);

        throttle.recordFailure("10.0.0.7");
        throttle.recordFailure("10.0.0.7");
        assertThat(throttle.mayAttempt("10.0.0.7")).as("two of three spent").isTrue();
        throttle.recordFailure("10.0.0.7");
        assertThat(throttle.mayAttempt("10.0.0.7")).as("the third locks").isFalse();

        clock.advance(Duration.ofMinutes(14));
        throttle.recordFailure("10.0.0.7");
        assertThat(throttle.mayAttempt("10.0.0.7"))
                .as("a failure inside the window does not restart it — the guesser cannot keep "
                        + "the lock alive by guessing, and cannot shorten it either")
                .isFalse();

        clock.advance(Duration.ofMinutes(2));
        assertThat(throttle.mayAttempt("10.0.0.7")).as("fifteen minutes after the first").isTrue();
    }

    @Test
    @DisplayName("the arithmetic: another address is unaffected, and a success forgets")
    void keyedPerAddressAndForgottenBySuccess() {
        SignInThrottle throttle = new SignInThrottle(
                properties, Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneOffset.UTC));

        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.recordFailure("10.0.0.7");
        }
        assertThat(throttle.mayAttempt("10.0.0.7")).isFalse();
        assertThat(throttle.mayAttempt("10.0.0.8"))
                .as("keyed on the address, never the account — locking by email would let "
                        + "anybody lock a colleague out by mistyping their address")
                .isTrue();

        throttle.recordSuccess("10.0.0.7");
        assertThat(throttle.mayAttempt("10.0.0.7")).isTrue();
    }

    @Test
    @DisplayName("over HTTP: the fourth attempt is refused unverified, with the same silent 401")
    void aLockedAddressIsAnsweredLikeAWrongPassword() throws Exception {
        Fixture fixture = activated("lockout-http", "Locked Out");

        MvcResult wrongPassword = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            wrongPassword = login(fixture.email(), "wrong-password-entirely");
            assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult locked = login(fixture.email(), PASSWORD);
        assertThat(locked.getResponse().getStatus())
                .as("the RIGHT password is refused while the address is locked")
                .isEqualTo(401);
        assertThat(locked.getResponse().getContentAsString())
                .as("no counter, no reason, no Retry-After: a locked address is told nothing a "
                        + "wrong password is not told")
                .isEqualTo(wrongPassword.getResponse().getContentAsString())
                .isEmpty();
        assertThat(locked.getResponse().getHeader("Retry-After")).isNull();
        assertThat(locked.getResponse().getHeaderNames())
                .isEqualTo(wrongPassword.getResponse().getHeaderNames());
    }

    private record Fixture(String email) {
    }

    private Fixture activated(String slug, String displayName) {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                slug, "Lockout " + slug, "admin@" + slug + ".test", displayName));
        String token = result.invitationLink().orElseThrow().replaceAll(".*\\?token=", "");
        assertThat(invitations.accept(token, PASSWORD))
                .isInstanceOf(InvitationService.Accepted.class);
        return new Fixture("admin@" + slug + ".test");
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new AuthController.LoginRequest(email, password, false))))
                .andReturn();
    }

    /** A clock a test can move — the seam {@code TokenRateLimiterTest} uses for the same reason. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
