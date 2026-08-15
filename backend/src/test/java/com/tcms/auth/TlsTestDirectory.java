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

/**
 * The in-memory LDAPS server every LDAP test binds against, and the socket factory that trusts it.
 *
 * <p>One server recipe shared by {@code LdapDirectoryTest} and {@code LdapLoginEndpointTest},
 * because the recipe is load-bearing in two ways that are silent when wrong:
 *
 * <ul>
 *   <li><strong>A KEY manager, not just a trust manager.</strong> A trust-manager-only
 *       {@code SSLUtil} leaves the server with no certificate to present, and the handshake fails
 *       with the client's own alert — which every negative test reads as an ordinary refusal,
 *       because an ordinary refusal is exactly what an unreachable directory produces. Six of
 *       seven tests were green against a server that had never completed a handshake.</li>
 *   <li><strong>{@code ManageCertificates} directly, NOT {@code SelfSignedCertificateGenerator}.</strong>
 *       The convenience generator builds its SAN list from the machine's own addresses and silently
 *       swallows the validation failure that keeps {@code localhost} out of it, so the JDK's LDAPS
 *       endpoint identification — which runs even under a trust-all manager — refuses every
 *       handshake with "no subject alternative name matching".</li>
 * </ul>
 */
final class TlsTestDirectory {

    private TlsTestDirectory() {
    }

    /** A listening LDAPS server loaded with {@code /ldap/f3-directory.ldif}. Caller shuts it down. */
    static InMemoryDirectoryServer start() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=acme,dc=test");
        config.addAdditionalBindCredentials("cn=service,dc=acme,dc=test", "service-password");

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
                (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault()));
        // The fixture's entries are deliberately not schema-perfect; the subject under test is the
        // client, not the server's schema enforcement.
        config.setSchema(null);

        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        URL ldif = TlsTestDirectory.class.getResource("/ldap/f3-directory.ldif");
        server.importFromLDIF(true, Path.of(ldif.toURI()).toString());
        server.startListening();
        return server;
    }

    /**
     * The client half of the trust arrangement: JNDI instantiates this by name (the
     * {@code java.naming.ldap.factory.socket} property) and calls its static {@code getDefault()}.
     *
     * <p>The server's certificate is generated at startup and signed by nobody, so the JVM default
     * trust store rejects it — correctly. Trusting it goes through the same seam a deployment uses
     * for a customer's internal CA ({@code LdapDirectory#environmentFor}), rather than by mutating
     * the JVM's default SSL context, which would leak into every other test sharing this fork. Note
     * what is NOT relaxed: the connection is still LDAPS and the bind still travels encrypted,
     * which is the point — these tests drive the transport production drives.
     */
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
}
