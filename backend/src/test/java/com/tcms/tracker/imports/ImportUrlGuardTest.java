package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ImportUrlGuard}'s matrix, against both settings of {@code allow-private-hosts}.
 *
 * <p>No Spring context and no container: the guard is a function of a URL, a DNS answer and one
 * boolean, and constructing it directly is what lets both settings be driven in one run — which is
 * the reason its constructor is public.
 *
 * <p>{@code ImportUrlGuardMirrorsTheOutboundGuardTest} is the other half and asks a different
 * question: that one asks whether the copy still matches {@code integration}'s, this one asks
 * whether the rules are right. A drifted copy of correct rules and a faithful copy of wrong ones
 * are different defects.
 */
@DisplayName("ImportUrlGuard — what this instance will and will not dial")
class ImportUrlGuardTest {

    private static ImportUrlGuard guard(boolean allowPrivateHosts) {
        return new ImportUrlGuard(
                new ImportProperties(
                        5000, Duration.ofSeconds(30), Duration.ofMinutes(30), allowPrivateHosts));
    }

    @Nested
    @DisplayName("with allow-private-hosts off, which is the default")
    class Enforcing {

        private final ImportUrlGuard guard = guard(false);

        @Test
        @DisplayName("a public HTTPS site is allowed")
        void aPublicSiteIsAllowed() {
            assertThat(guard.check("https://example.com"))
                    .isEqualTo(ImportUrlGuard.Verdict.ALLOWED);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "http://example.com",
                "ftp://example.com",
                "file:///etc/passwd"})
        @DisplayName("anything but https is refused rather than upgraded")
        void nonHttpsIsRefused(String url) {
            // Refused and not upgraded: a token sent in cleartext is already spent by the time a
            // redirect to TLS arrives, so there is nothing for a retry over HTTPS to protect.
            assertThat(guard.check(url)).isEqualTo(ImportUrlGuard.Verdict.NOT_HTTPS);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://localhost",
                "https://127.0.0.1",
                "https://10.0.0.1",
                "https://192.168.1.1",
                "https://172.16.0.1",
                "https://169.254.169.254",
                "https://100.64.0.1",
                "https://[::1]",
                "https://[fd00::1]"})
        @DisplayName("every private, loopback, link-local, CGNAT and unique-local destination")
        void privateDestinationsAreRefused(String url) {
            // 169.254.169.254 is the one this rule exists for, and 100.64/10 and fd00::/7 are the
            // two the JDK's own isSiteLocalAddress calls public.
            assertThat(guard.check(url))
                    .isEqualTo(ImportUrlGuard.Verdict.PRIVATE_DESTINATION);
        }

        @Test
        @DisplayName("a name nothing answers to is refused rather than deferred to the dial")
        void anUnresolvableHostIsRefused() {
            assertThat(guard.check("https://no-such-host.invalid"))
                    .isEqualTo(ImportUrlGuard.Verdict.UNRESOLVABLE);
        }

        @Test
        @DisplayName("nothing that is not a URL reaches the resolver")
        void malformedInputIsRefused() {
            // Not parameterized: a blank case is one of the values that matters most here, and
            // JUnit refuses a parameterized display name that is empty.
            assertThat(guard.check(null)).isEqualTo(ImportUrlGuard.Verdict.MALFORMED);
            assertThat(guard.check("")).isEqualTo(ImportUrlGuard.Verdict.MALFORMED);
            assertThat(guard.check("   ")).isEqualTo(ImportUrlGuard.Verdict.MALFORMED);
            // Spaces are not legal in a URI at all, so this never reaches the scheme check.
            assertThat(guard.check("not a url at all")).isEqualTo(ImportUrlGuard.Verdict.MALFORMED);

            // Parses, and has no host. Refused as MALFORMED rather than NOT_HTTPS because the
            // scheme IS https — the more useful sentence for the person who typed it.
            assertThat(guard.check("https://")).isEqualTo(ImportUrlGuard.Verdict.MALFORMED);
        }
    }

    @Nested
    @DisplayName("with allow-private-hosts on, which an operator has to have said")
    class Permissive {

        private final ImportUrlGuard guard = guard(true);

        @Test
        @DisplayName("a self-hosted source on a private address is allowed")
        void aPrivateDestinationIsAllowed() {
            assertThat(guard.check("https://192.168.1.1"))
                    .isEqualTo(ImportUrlGuard.Verdict.ALLOWED);
        }

        @Test
        @DisplayName("an unresolvable name is allowed too, because resolution is skipped entirely")
        void resolutionIsSkipped() {
            // Not an oversight: with the switch on there is nothing left for a DNS round trip to
            // decide, and paying for one per call would be latency spent on an answer that cannot
            // change the outcome. The dial fails on its own if the name is wrong.
            assertThat(guard.check("https://no-such-host.invalid"))
                    .isEqualTo(ImportUrlGuard.Verdict.ALLOWED);
        }

        @Test
        @DisplayName("the switch does not permit plain http — it is about the destination only")
        void plainHttpIsStillRefused() {
            assertThat(guard.check("http://192.168.1.1"))
                    .isEqualTo(ImportUrlGuard.Verdict.NOT_HTTPS);
        }
    }
}
