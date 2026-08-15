package com.tcms.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V57 relaxed a NOT NULL, and this is what replaced it.
 *
 * <p>{@code client_id} had to become nullable so an LDAP row could exist at all — and the moment it
 * did, every OIDC row lost the guarantee it shipped with. A provider with a null client id does not
 * fail at the insert; it fails at the token exchange, in a deployment, days later, as a sign-in
 * route that refuses everybody. {@code ck_identity_provider_shape} is the conditional replacement,
 * and it is strictly stronger than what it replaced because it also pins the three LDAP columns
 * without which a search is not constructible.
 *
 * <p>Neither schema-wide rule covers it: {@code SchemaConventionTest} compares {@code company_id},
 * policies and indexes, and {@code SchemaCommentTest} compares the identifiers a comment names. A
 * conditional shape check is invisible to both, so this class is the only observer it has.
 *
 * <p>The third test is about the <em>grant</em>, not the column list. {@code V57} must
 * {@code DROP} the discovery function rather than replace it, because Postgres refuses to change a
 * {@code RETURNS TABLE} shape (42P13) — and a dropped function takes its grants with it. Forgetting
 * to re-grant fails on the login screen of a <em>migrated</em> deployment while every
 * fresh-container test stays green, which is the shape this suite structurally cannot see unless
 * something asks as the application role.
 */
@DisplayName("V57 — the LDAP columns, the per-kind shape check, and the function that was dropped")
class LdapSchemaTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("f3000000-0000-4000-8000-000000000001");

    private static Connection connection;

    /**
     * {@code @BeforeEach} rather than {@code @BeforeAll}, for {@link IntegrityTriggerTest}'s
     * reason: Flyway and the {@code tcms_app} role run when the test instance is post-processed,
     * after a static {@code @BeforeAll} would already have tried to connect.
     */
    @BeforeEach
    void seed() throws SQLException {
        if (connection != null) {
            return;
        }
        connection = superuserConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO company (id, slug, display_name) VALUES ('%s', 'ldapco', 'Ldap Co')"
                            .formatted(COMPANY));
        }
    }

    @AfterAll
    static void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Test
    @DisplayName("an OIDC row still cannot exist without a client id")
    void oidcStillRequiresAClientId() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO identity_provider (id, company_id, key, kind, display_name,
                                                   issuer_uri, client_id)
                    VALUES ('f3000000-0000-4000-8000-000000000011', '%s',
                            'oidc-without-a-client', 'OIDC', 'Broken',
                            'https://issuer.acme.test', NULL)
                    """.formatted(COMPANY)))
                    .hasMessageContaining("ck_identity_provider_shape");
        }
    }

    @Test
    @DisplayName("an LDAP row cannot exist without a search base, a filter and an id attribute")
    void ldapRequiresItsOwnThreeColumns() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO identity_provider (id, company_id, key, kind, display_name,
                                                   issuer_uri, client_id)
                    VALUES ('f3000000-0000-4000-8000-000000000012', '%s',
                            'ldap-without-a-base', 'LDAP', 'Broken',
                            'ldaps://dc.acme.test:636', NULL)
                    """.formatted(COMPANY)))
                    .hasMessageContaining("ck_identity_provider_shape");
        }
    }

    @Test
    @DisplayName("an LDAP row cannot carry a client id either — the check binds both ways")
    void ldapRefusesARelyingPartyIdentity() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO identity_provider (id, company_id, key, kind, display_name,
                                                   issuer_uri, client_id, user_search_base,
                                                   user_search_filter, user_id_attribute)
                    VALUES ('f3000000-0000-4000-8000-000000000013', '%s',
                            'ldap-with-a-client', 'LDAP', 'Broken',
                            'ldaps://dc.acme.test:636', 'tcms', 'ou=people,dc=acme,dc=test',
                            '(mail={0})', 'objectGUID')
                    """.formatted(COMPANY)))
                    .hasMessageContaining("ck_identity_provider_shape");
        }
    }

    @Test
    @DisplayName("a complete directory row is accepted")
    void aCompleteDirectoryRowIsAccepted() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO identity_provider (id, company_id, key, kind, display_name,
                                                   issuer_uri, client_id, start_tls,
                                                   user_search_base, user_search_filter,
                                                   user_id_attribute, email_attribute,
                                                   display_name_attribute)
                    VALUES ('f3000000-0000-4000-8000-000000000014', '%s',
                            'acme-directory', 'LDAP', 'Acme Directory',
                            'ldaps://dc.acme.test:636', NULL, FALSE,
                            'ou=people,dc=acme,dc=test', '(|(userPrincipalName={0})(mail={0}))',
                            'objectGUID', 'mail', 'displayName')
                    """.formatted(COMPANY));
        }
    }

    @Test
    @DisplayName("discovery answers with the kind, and the grant survived the drop")
    void discoveryCarriesTheKind() throws SQLException {
        // As the APPLICATION role on purpose: the drop took the function's grants with it, so this
        // is the assertion that a migrated deployment does not answer `permission denied for
        // function` on its login screen.
        try (Connection app = appConnection();
                Statement statement = app.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT * FROM tcms_resolve_providers_for_domain('nobody.test')")) {

            assertThat(rows.getMetaData().getColumnCount()).isEqualTo(3);
            assertThat(rows.getMetaData().getColumnName(3)).isEqualTo("kind");
        }
    }
}
