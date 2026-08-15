package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The LDAP half, driven against a real directory server in-process.
 *
 * <p>UnboundID's {@code InMemoryDirectoryServer} rather than a container: it speaks the protocol,
 * it serves LDAPS with a generated certificate — so these tests drive the ENCRYPTED path production
 * drives, which is the whole reason D2 needs no {@code allowInsecure} escape hatch — and it starts
 * in milliseconds without Docker. The server recipe and the trust arrangement live in
 * {@link TlsTestDirectory}, whose header carries the two ways they are silently wrong.
 */
@DisplayName("LdapDirectory — search, bind, and the three refusals that look like successes")
class LdapDirectoryTest {

    private static InMemoryDirectoryServer server;
    private static LdapDirectory directory;

    @BeforeAll
    static void startDirectory() throws Exception {
        server = TlsTestDirectory.start();
        directory = new TrustingTheTestServer(new LdapProperties(
                Duration.ofSeconds(3), Duration.ofSeconds(5), 5, Duration.ofSeconds(60)));
    }

    /**
     * Trusts the test server's generated certificate through {@link LdapDirectory#environmentFor}
     * — the same seam a deployment uses for a customer's internal CA. See
     * {@link TlsTestDirectory.TrustAllSockets} for what is and is not relaxed.
     */
    private static final class TrustingTheTestServer extends LdapDirectory {

        private TrustingTheTestServer(LdapProperties properties) {
            super(properties);
        }

        @Override
        protected java.util.Map<String, Object> environmentFor(LdapDirectory.Target target) {
            java.util.Map<String, Object> environment = super.environmentFor(target);
            environment.put("java.naming.ldap.factory.socket",
                    TlsTestDirectory.TrustAllSockets.class.getName());
            return environment;
        }
    }

    @AfterAll
    static void stopDirectory() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    private LdapDirectory.Target target() {
        return new LdapDirectory.Target(
                "ldaps://127.0.0.1:" + server.getListenPort("ldaps"),
                false,
                "cn=service,dc=acme,dc=test",
                "service-password",
                "ou=people,dc=acme,dc=test",
                "(mail={0})",
                "entryUUID",
                "mail",
                "cn");
    }

    @Test
    @DisplayName("the right password authenticates and the subject is the directory's own id")
    void aCorrectBindResolvesAnEntry() {
        Optional<LdapDirectory.Entry> entry =
                directory.authenticate(target(), "dana@acme.test", "directory-password");

        assertThat(entry).isPresent();
        assertThat(entry.get().email()).isEqualTo("dana@acme.test");
        assertThat(entry.get().displayName()).isEqualTo("Dana Director");
        // entryUUID, not the DN: D3. Whatever it is, it is stable and it is not "uid=dana,…".
        assertThat(entry.get().subject()).isNotBlank().doesNotContain("uid=dana");
    }

    @Test
    @DisplayName("the wrong password resolves nothing")
    void aWrongPasswordRefuses() {
        assertThat(directory.authenticate(target(), "dana@acme.test", "not-it")).isEmpty();
    }

    @Test
    @DisplayName("an address matching two entries refuses rather than picking one")
    void anAmbiguousMatchRefuses() {
        assertThat(directory.authenticate(target(), "twin@acme.test", "twin-password")).isEmpty();
    }

    @Test
    @DisplayName("a filter metacharacter is escaped rather than interpolated")
    void theFilterIsEscaped() {
        // Concatenation would make this `(mail=*)(objectClass=*)` and match the whole subtree.
        assertThat(directory.authenticate(target(), "*)(objectClass=*", "anything")).isEmpty();
    }

    @Test
    @DisplayName("an empty password is refused without reaching the directory")
    void anEmptyPasswordNeverBinds() {
        // An empty password is an UNAUTHENTICATED bind in the protocol, which most directories
        // answer with success — so a client that passes it through treats "no password" as "signed
        // in". RFC 4513's own warning, and the single most common LDAP authentication defect.
        assertThat(directory.authenticate(target(), "dana@acme.test", "")).isEmpty();
    }

    @Test
    @DisplayName("nobody at all resolves nothing, and it costs a bind anyway")
    void anUnknownAddressRefuses() {
        // The synthetic bind is not observable from here — what this pins is that the refusal is
        // the ordinary empty one and not an exception escaping the search.
        assertThat(directory.authenticate(target(), "nobody@acme.test", "directory-password"))
                .isEmpty();
    }

    @Test
    @DisplayName("an unreachable directory is the same refusal, not an exception")
    void anUnreachableDirectoryRefuses() {
        LdapDirectory.Target unreachable = new LdapDirectory.Target(
                "ldaps://127.0.0.1:1", false, null, null,
                "ou=people,dc=acme,dc=test", "(mail={0})", "entryUUID", "mail", "cn");

        assertThat(directory.authenticate(unreachable, "dana@acme.test", "directory-password"))
                .isEmpty();
    }
}
