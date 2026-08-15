package com.tcms.identity;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One external authority a company authenticates its own people against (§6r) — an OIDC issuer or a
 * directory, which is what {@link #kind} says and what decides which half of these columns is
 * populated. {@code ck_identity_provider_shape} (V57) is where that split is enforced.
 *
 * <p><strong>Issuer trust is company-local.</strong> The row is company-owned and FORCE
 * RLS-protected, so a different client's IdP asserting a subject can never resolve to this
 * company's user — see {@link UserIdentity}, whose key folds this company scope into
 * {@code (provider, subject)}.
 *
 * <p>Provider <em>resolution</em> on the authorize leg does not go through this entity at all:
 * that read happens before any session exists, so {@code app.current_company} is unset and RLS
 * yields zero rows. {@code tcms_resolve_identity_provider} (V53) answers it instead, and the
 * lifecycle flags below are read here, <em>after</em> the context is open.
 *
 * <p>{@link #enabled} and {@link #jitProvisioning} both default to refusing: a half-configured
 * provider is never a live sign-in route, and an IdP authoritative for one organisation is not
 * authoritative for who may enter a client's company (#38).
 */
@Entity
@Table(name = "identity_provider")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class IdentityProvider {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** Unique per company, not globally: two clients may both call their issuer "corporate". */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private IdentityProviderKind kind;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "issuer_uri", nullable = false)
    private String issuerUri;

    /**
     * OIDC only, and <strong>nullable since V57</strong> — an LDAP provider has no relying-party
     * identity. What replaced the {@code NOT NULL} is {@code ck_identity_provider_shape}, which
     * requires it conditionally on {@link #kind}: dropping the constraint outright would have taken
     * the guarantee away from the OIDC rows too, and one of those with a null client id fails at
     * the token exchange rather than at the insert.
     */
    @Column(name = "client_id")
    private String clientId;

    /**
     * <strong>Encrypted, not hashed</strong> — unlike every other credential in this schema. A
     * token exchange needs the plaintext back, so {@link InvitationTokens}' and
     * {@link ApiTokenSecrets}' SHA-256 digest is the wrong tool here and reusing it would make the
     * secret unusable. Null for a public client, which uses PKCE alone.
     */
    @Column(name = "client_secret_encrypted")
    private byte[] clientSecretEncrypted;

    @Column(name = "bind_dn")
    private String bindDn;

    /**
     * {@link #clientSecretEncrypted}'s rule and reason: a bind <strong>presents</strong> it, so it
     * is encrypted rather than digested. Null means an anonymous search, which some directories
     * permit and Active Directory does not.
     */
    @Column(name = "bind_password_encrypted")
    private byte[] bindPasswordEncrypted;

    @Column(name = "start_tls", nullable = false)
    private boolean startTls;

    @Column(name = "user_search_base")
    private String userSearchBase;

    /** Contains {@code {0}}, where the typed address is substituted with RFC 4515 escaping. */
    @Column(name = "user_search_filter")
    private String userSearchFilter;

    /** {@code objectGUID} or {@code entryUUID} — never a DN, which encodes tree position. */
    @Column(name = "user_id_attribute")
    private String userIdAttribute;

    @Column(name = "email_attribute")
    private String emailAttribute;

    @Column(name = "display_name_attribute")
    private String displayNameAttribute;

    @Column(name = "jit_provisioning", nullable = false)
    private boolean jitProvisioning;

    @Column(name = "auto_link_verified_email", nullable = false)
    private boolean autoLinkVerifiedEmail;

    @Column(name = "group_claim")
    private String groupClaim;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected IdentityProvider() {
    }

    /**
     * Registers a provider <strong>disabled</strong>, whatever the caller intends: configuring an
     * issuer and turning it into a live sign-in route are two acts, and the second one is
     * {@link #applyEdit}.
     *
     * @param companyId taken from {@link com.tcms.tenancy.CompanyContext}, never from a request body
     */
    public static IdentityProvider register(
            UUID id,
            UUID companyId,
            String key,
            IdentityProviderKind kind,
            String displayName,
            String issuerUri,
            String clientId,
            byte[] clientSecretEncrypted,
            UUID createdBy,
            Instant now) {

        IdentityProvider provider = new IdentityProvider();
        provider.id = id;
        provider.companyId = companyId;
        provider.key = key;
        provider.kind = kind;
        provider.displayName = displayName;
        provider.issuerUri = issuerUri;
        provider.clientId = clientId;
        provider.clientSecretEncrypted = clientSecretEncrypted;
        provider.jitProvisioning = false;
        provider.autoLinkVerifiedEmail = false;
        provider.enabled = false;
        provider.createdAt = now;
        provider.createdBy = createdBy;
        provider.updatedAt = now;
        provider.updatedBy = createdBy;
        return provider;
    }

    /**
     * Registers a directory provider — <strong>disabled</strong>, like {@link #register}, and for
     * the same reason: a search base typed wrong is a sign-in route that refuses everybody.
     *
     * <p>A second factory rather than a widened one. The two kinds share nine fields and disagree
     * about six, so one factory would take fourteen arguments of which five are null at every call
     * site, and a caller that passed an LDAP argument to an OIDC registration would compile.
     *
     * @param companyId taken from {@link com.tcms.tenancy.CompanyContext}, never from a request body
     */
    public static IdentityProvider registerDirectory(
            UUID id,
            UUID companyId,
            String key,
            String displayName,
            String directoryUrl,
            String bindDn,
            byte[] bindPasswordEncrypted,
            boolean startTls,
            String userSearchBase,
            String userSearchFilter,
            String userIdAttribute,
            String emailAttribute,
            String displayNameAttribute,
            UUID createdBy,
            Instant now) {

        IdentityProvider provider = new IdentityProvider();
        provider.id = id;
        provider.companyId = companyId;
        provider.key = key;
        provider.kind = IdentityProviderKind.LDAP;
        provider.displayName = displayName;
        provider.issuerUri = directoryUrl;
        provider.bindDn = bindDn;
        provider.bindPasswordEncrypted = bindPasswordEncrypted;
        provider.startTls = startTls;
        provider.userSearchBase = userSearchBase;
        provider.userSearchFilter = userSearchFilter;
        provider.userIdAttribute = userIdAttribute;
        provider.emailAttribute = emailAttribute;
        provider.displayNameAttribute = displayNameAttribute;
        provider.jitProvisioning = false;
        provider.autoLinkVerifiedEmail = false;
        provider.enabled = false;
        provider.createdAt = now;
        provider.createdBy = createdBy;
        provider.updatedAt = now;
        provider.updatedBy = createdBy;
        return provider;
    }

    /**
     * Applies a whole validated edit — one mutator, because the write is one PUT, per
     * {@link Team#applyEdit}. Two mutators each stamp {@link #updatedAt}, so an edit changing
     * fields on both would touch the row twice for one request and leave a half-applied
     * configuration behind a 200 saying otherwise.
     *
     * <p><strong>Two credentials, one rule:</strong> {@code null} for
     * {@code newClientSecretEncrypted} or {@code newBindPasswordEncrypted} <em>keeps</em> the stored
     * one rather than clearing it. Neither is ever on a read path, so an edit cannot echo one back
     * and a caller has nothing to resend.
     */
    public void applyEdit(
            String newDisplayName,
            String newIssuerUri,
            String newClientId,
            byte[] newClientSecretEncrypted,
            String newBindDn,
            byte[] newBindPasswordEncrypted,
            boolean newStartTls,
            String newUserSearchBase,
            String newUserSearchFilter,
            String newUserIdAttribute,
            String newEmailAttribute,
            String newDisplayNameAttribute,
            boolean newJitProvisioning,
            boolean newAutoLinkVerifiedEmail,
            String newGroupClaim,
            boolean newEnabled,
            UUID editedBy,
            Instant now) {

        this.displayName = newDisplayName;
        this.issuerUri = newIssuerUri;
        this.clientId = newClientId;
        if (newClientSecretEncrypted != null) {
            this.clientSecretEncrypted = newClientSecretEncrypted;
        }
        this.bindDn = newBindDn;
        if (newBindPasswordEncrypted != null) {
            this.bindPasswordEncrypted = newBindPasswordEncrypted;
        }
        this.startTls = newStartTls;
        this.userSearchBase = newUserSearchBase;
        this.userSearchFilter = newUserSearchFilter;
        this.userIdAttribute = newUserIdAttribute;
        this.emailAttribute = newEmailAttribute;
        this.displayNameAttribute = newDisplayNameAttribute;
        this.jitProvisioning = newJitProvisioning;
        this.autoLinkVerifiedEmail = newAutoLinkVerifiedEmail;
        this.groupClaim = newGroupClaim;
        this.enabled = newEnabled;
        this.updatedBy = editedBy;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getKey() {
        return key;
    }

    public IdentityProviderKind getKind() {
        return kind;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public String getClientId() {
        return clientId;
    }

    public byte[] getClientSecretEncrypted() {
        return clientSecretEncrypted;
    }

    public String getBindDn() {
        return bindDn;
    }

    public byte[] getBindPasswordEncrypted() {
        return bindPasswordEncrypted;
    }

    public boolean isStartTls() {
        return startTls;
    }

    public String getUserSearchBase() {
        return userSearchBase;
    }

    public String getUserSearchFilter() {
        return userSearchFilter;
    }

    public String getUserIdAttribute() {
        return userIdAttribute;
    }

    public String getEmailAttribute() {
        return emailAttribute;
    }

    public String getDisplayNameAttribute() {
        return displayNameAttribute;
    }

    public boolean isJitProvisioning() {
        return jitProvisioning;
    }

    public boolean isAutoLinkVerifiedEmail() {
        return autoLinkVerifiedEmail;
    }

    public String getGroupClaim() {
        return groupClaim;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
