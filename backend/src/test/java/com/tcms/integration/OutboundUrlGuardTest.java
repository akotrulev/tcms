package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link OutboundUrlGuard} — the allow/deny matrix, which is the point of the class.
 *
 * <h2>Table-driven, and that is a decision rather than a style</h2>
 *
 * <p>A guard with one happy-path test is a guard nobody can change: the next person to touch it has
 * no way to tell which of its refusals were deliberate and which fell out of an implementation, so
 * they either leave it alone or break something silently. Every row below is a rule somebody has to
 * re-argue before deleting it.
 *
 * <h2>No Spring context and no database</h2>
 *
 * <p>§150's pure lane. The subject is a function of a string and a boolean, and the failures being
 * pinned are failures of <em>reachability</em>, not of persistence. A rule that only runs when
 * Docker is up is one that gets skipped exactly when somebody is in a hurry.
 *
 * <h2>Two rows here perform real DNS, and that is deliberate and bounded</h2>
 *
 * <p>The whole argument for resolving before dialling is that a <em>name</em> can point inside the
 * network. {@code localhost} is the one hostname every machine that can run this suite resolves to a
 * loopback address without a network, so it is what the resolving arm is proved with. Nothing here
 * reaches a public resolver: {@code 127.0.0.1} and the other literals are parsed rather than looked
 * up, and there is no row asserting what an internet name resolves to — that would be a test of
 * somebody else's DNS.
 */
@DisplayName("OutboundUrlGuard — what this instance will and will not connect to")
class OutboundUrlGuardTest {

    private final OutboundUrlGuard strict = new OutboundUrlGuard(new OutboundProperties(false));
    private final OutboundUrlGuard permissive = new OutboundUrlGuard(new OutboundProperties(true));

    /**
     * The matrix. One row per rule, each with the reason it exists in its display name — so a
     * failure reads as "we stopped refusing metadata endpoints" rather than as "row 7 failed".
     */
    static Stream<Arguments> destinations() {
        return Stream.of(
                Arguments.of("https://acme.atlassian.net", OutboundUrlGuard.Verdict.ALLOWED,
                        "an ordinary public tracker — the case the feature exists for"),
                Arguments.of("https://acme.atlassian.net/rest/api/3/myself",
                        OutboundUrlGuard.Verdict.ALLOWED,
                        "a path is not the guard's business; the destination is"),

                Arguments.of("http://acme.atlassian.net", OutboundUrlGuard.Verdict.NOT_HTTPS,
                        "cleartext. A token sent over http is already spent by the time a redirect "
                                + "to TLS arrives, so it is refused rather than upgraded"),
                Arguments.of("ftp://acme.atlassian.net", OutboundUrlGuard.Verdict.NOT_HTTPS,
                        "anything that is not https, not a list of known-bad schemes"),
                Arguments.of("file:///etc/passwd", OutboundUrlGuard.Verdict.NOT_HTTPS,
                        "the scheme that turns an outbound call into a local file read"),
                Arguments.of("javascript:alert(1)", OutboundUrlGuard.Verdict.NOT_HTTPS,
                        "refused here as well as at every href bound in the product"),

                Arguments.of("https://127.0.0.1", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "this process. A binding pointed here reaches TCMS itself"),
                Arguments.of("https://[::1]", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "loopback in v6 — the same address written the way a v4-only check misses"),
                Arguments.of("https://10.1.2.3", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "somebody's intranet"),
                Arguments.of("https://172.16.4.5", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "the range people forget is private, because it is not a /8 or a /16"),
                Arguments.of("https://192.168.0.9", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "the home and office LAN"),
                Arguments.of("https://169.254.169.254",
                        OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "THE ATTACK. A cloud instance's credential endpoint, reachable with no "
                                + "authentication from inside the instance"),
                Arguments.of("https://[fd00::1]", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "IPv6 unique-local. InetAddress.isSiteLocalAddress() is FALSE for this "
                                + "range — the JDK's method predates it — so it is tested against "
                                + "its own prefix and this row is what proves that arm runs"),
                Arguments.of("https://100.64.0.1", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "carrier-grade NAT, which the JDK calls public and which reaches a network "
                                + "this deployment did not choose to be on"),
                Arguments.of("https://0.0.0.0", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "the wildcard address"),

                Arguments.of("https://localhost", OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                        "A NAME, NOT A LITERAL — the row this whole class is arranged around. A "
                                + "string check sees nothing wrong with a hostname; what makes it "
                                + "an attack is what it RESOLVES to, and resolution is why the "
                                + "guard is not a regex"),

                Arguments.of("", OutboundUrlGuard.Verdict.MALFORMED, "empty"),
                Arguments.of("   ", OutboundUrlGuard.Verdict.MALFORMED, "blank"),
                Arguments.of("not a url at all", OutboundUrlGuard.Verdict.MALFORMED,
                        "a sentence somebody pasted"),
                Arguments.of("https://", OutboundUrlGuard.Verdict.MALFORMED,
                        "a scheme with no host — parses, and names nothing"),
                Arguments.of("//acme.atlassian.net", OutboundUrlGuard.Verdict.NOT_HTTPS,
                        "protocol-relative, which has no scheme at all and must not inherit ours"));
    }

    @ParameterizedTest(name = "{0} → {1} ({2})")
    @MethodSource("destinations")
    @DisplayName("the matrix, with allow-private-hosts off")
    void theMatrix(String url, OutboundUrlGuard.Verdict expected, String why) {
        assertThat(strict.check(url)).as(why).isEqualTo(expected);
    }

    @Nested
    @DisplayName("allow-private-hosts — the operator's sentence")
    class PrivateHostsAllowed {

        @Test
        @DisplayName("a private destination is permitted when the operator has said so")
        void aPrivateDestinationIsPermitted() {
            // The self-hosted Jira Data Center on a LAN, which is who the setting is for. It is a
            // property with no default in application.yml's shell sense and a `false` default in
            // Spring's, so an instance that has never heard of it refuses.
            assertThat(permissive.check("https://jira.internal.acme")).isNotEqualTo(
                    OutboundUrlGuard.Verdict.PRIVATE_DESTINATION);
            assertThat(permissive.check("https://10.1.2.3"))
                    .isEqualTo(OutboundUrlGuard.Verdict.ALLOWED);
            assertThat(permissive.check("https://127.0.0.1"))
                    .isEqualTo(OutboundUrlGuard.Verdict.ALLOWED);
        }

        @Test
        @DisplayName("it does NOT relax https, and that is the half people assume it relaxes")
        void itDoesNotRelaxTheScheme() {
            // A private Jira is still a Jira that holds every connected person's token. The setting
            // says "this network is reachable", not "this network is trusted with cleartext".
            assertThat(permissive.check("http://10.1.2.3"))
                    .isEqualTo(OutboundUrlGuard.Verdict.NOT_HTTPS);
        }

        @Test
        @DisplayName("it does not make a malformed address valid")
        void itDoesNotRelaxParsing() {
            assertThat(permissive.check("not a url"))
                    .isEqualTo(OutboundUrlGuard.Verdict.MALFORMED);
        }
    }

    // ~~"allows() and check() cannot disagree"~~ -> the boolean form was deleted in B7. It had no
    // production caller: every real site needs the VERDICT, because the verdict is the refusal's
    // message and a bare false has nothing to say. It was a convenience whose only caller was this
    // test, which is the shape ArchitectureRulesTest's unexercised-bean rule exists to report, and
    // §121's findQuarantined is the precedent for deleting rather than exempting. Two lines to
    // write again on the day a caller with no message appears.

    @Test
    @DisplayName("fixture precondition: the matrix actually contains refusals of each kind")
    void theMatrixDiscriminates() {
        // Without this, a guard that returned ALLOWED for everything would fail the rows above —
        // but a guard that returned MALFORMED for everything would fail only some of them, and a
        // future edit that collapsed two verdicts into one would quietly narrow what is asserted.
        List<OutboundUrlGuard.Verdict> verdicts = destinations()
                .map(row -> (OutboundUrlGuard.Verdict) row.get()[1])
                .distinct()
                .toList();

        assertThat(verdicts).containsExactlyInAnyOrder(
                OutboundUrlGuard.Verdict.ALLOWED,
                OutboundUrlGuard.Verdict.NOT_HTTPS,
                OutboundUrlGuard.Verdict.PRIVATE_DESTINATION,
                OutboundUrlGuard.Verdict.MALFORMED);
    }
}
