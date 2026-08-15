package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import com.unboundid.util.ssl.cert.ManageCertificates;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLSocketFactory;
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
 * in milliseconds without Docker.
 *
 * <p>The client trusts that generated certificate through a per-target socket factory, which is
 * also how the production path reaches a customer's internal CA: the trust decision belongs to the
 * deployment, never to a column an administrator fills in on a web form for a host they name.
 */
@DisplayName("LdapDirectory — search, bind, and the three refusals that look like successes")
class LdapDirectoryTest {

    private static InMemoryDirectoryServer server;
    private static LdapDirectory directory;

    @BeforeAll
    static void startDirectory() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=acme,dc=test");
        config.addAdditionalBindCredentials("cn=service,dc=acme,dc=test", "service-password");

        // A KEY manager, not just a trust manager. A trust-manager-only SSLUtil leaves the server
        // with no certificate to present, and the handshake fails with the client's own alert —
        // which every test in this class then reads as an ordinary refusal, because an ordinary
        // refusal is exactly what an unreachable directory produces. Six of these seven tests were
        // green against a server that had never completed a handshake.
        //
        // ManageCertificates directly, NOT SelfSignedCertificateGenerator: the convenience
        // generator builds its SAN list from the machine's own addresses and silently swallows the
        // validation failure that keeps "localhost" out of it, so the JDK's LDAPS endpoint
        // identification — which runs even under a trust-all manager — refuses every handshake
        // with "no subject alternative name matching".
        File keyStoreFile = File.createTempFile("ldap-test-keystore", ".jks");
        assertThat(keyStoreFile.delete()).isTrue();
        keyStoreFile.deleteOnExit();
        char[] keyStorePassword = "test-keystore-password".toCharArray();
        ResultCode generated = ManageCertificates.main(
                (java.io.InputStream) null, (java.io.OutputStream) null, null,
                "generate-self-signed-certificate",
                "--keystore", keyStoreFile.getAbsolutePath(),
                "--keystore-password", new String(keyStorePassword),
                "--keystore-type", "JKS",
                "--alias", "server-cert",
                "--subject-dn", "CN=localhost",
                "--days-valid", "1",
                "--subject-alternative-name-dns", "localhost",
                "--subject-alternative-name-ip-address", "127.0.0.1");
        assertThat(generated).isEqualTo(ResultCode.SUCCESS);
        SSLUtil serverSsl = new SSLUtil(
                new KeyStoreKeyManager(keyStoreFile, keyStorePassword, "JKS", "server-cert"),
                new TrustAllTrustManager());
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPSConfig(
                "ldaps", null, 0, serverSsl.createSSLServerSocketFactory(),
                (SSLSocketFactory) SSLSocketFactory.getDefault()));
        // The fixture's entries are deliberately not schema-perfect; the subject here is the
        // client, not the server's schema enforcement.
        config.setSchema(null);

        server = new InMemoryDirectoryServer(config);
        URL ldif = LdapDirectoryTest.class.getResource("/ldap/f3-directory.ldif");
        server.importFromLDIF(true, Path.of(ldif.toURI()).toString());
        server.startListening();

        directory = new TrustingTheTestServer(new LdapProperties(
                Duration.ofSeconds(3), Duration.ofSeconds(5), 5, Duration.ofSeconds(60)));
    }

    /**
     * The in-memory server's certificate is generated at startup and signed by nobody, so the JVM
     * default trust store rejects it — correctly.
     *
     * <p>Trusting it therefore goes through the same seam a deployment uses for a customer's
     * internal CA ({@link LdapDirectory#environmentFor}), rather than by mutating the JVM's default
     * SSL context, which would leak into every other test sharing this fork. Note what is NOT
     * relaxed: the connection is still LDAPS and the bind still travels encrypted, which is the
     * point — these tests drive the transport production drives.
     */
    private static final class TrustingTheTestServer extends LdapDirectory {

        private TrustingTheTestServer(LdapProperties properties) {
            super(properties);
        }

        @Override
        protected java.util.Map<String, Object> environmentFor(LdapDirectory.Target target) {
            java.util.Map<String, Object> environment = super.environmentFor(target);
            environment.put("java.naming.ldap.factory.socket", TrustAllSockets.class.getName());
            return environment;
        }
    }

    /** JNDI instantiates this by name and calls its static {@code getDefault()}. */
    public static final class TrustAllSockets extends javax.net.SocketFactory {

        private static final javax.net.ssl.SSLSocketFactory DELEGATE = delegate();

        private static javax.net.ssl.SSLSocketFactory delegate() {
            try {
                javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
                context.init(null, new javax.net.ssl.TrustManager[] {new TrustAllTrustManager()},
                        new java.security.SecureRandom());
                return context.getSocketFactory();
            } catch (java.security.GeneralSecurityException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        public static javax.net.SocketFactory getDefault() {
            return new TrustAllSockets();
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return DELEGATE.createSocket(host, port);
        }

        @Override
        public java.net.Socket createSocket(String host, int port,
                                            java.net.InetAddress localHost, int localPort)
                throws java.io.IOException {
            return DELEGATE.createSocket(host, port, localHost, localPort);
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port)
                throws java.io.IOException {
            return DELEGATE.createSocket(host, port);
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port,
                                            java.net.InetAddress localAddress, int localPort)
                throws java.io.IOException {
            return DELEGATE.createSocket(address, port, localAddress, localPort);
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
