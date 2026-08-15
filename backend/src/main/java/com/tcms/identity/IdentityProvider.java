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
 * One external issuer a company authenticates its own people against (§6r).
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

    @Column(name = "client_id", nullable = false)
    private String clientId;

    /**
     * <strong>Encrypted, not hashed</strong> — unlike every other credential in this schema. A
     * token exchange needs the plaintext back, so {@link InvitationTokens}' and
     * {@link ApiTokenSecrets}' SHA-256 digest is the wrong tool here and reusing it would make the
     * secret unusable. Null for a public client, which uses PKCE alone.
     */
    @Column(name = "client_secret_encrypted")
    private byte[] clientSecretEncrypted;

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
     * Applies a whole validated edit — one mutator, because the write is one PUT, per
     * {@link Team#applyEdit}. Two mutators each stamp {@link #updatedAt}, so an edit changing
     * fields on both would touch the row twice for one request and leave a half-applied
     * configuration behind a 200 saying otherwise.
     *
     * <p>{@code null} for {@code newClientSecretEncrypted} <strong>keeps the stored secret</strong>
     * rather than clearing it: the secret is never on a read path, so an edit cannot echo it back
     * and a caller has nothing to resend.
     */
    public void applyEdit(
            String newDisplayName,
            String newIssuerUri,
            String newClientId,
            byte[] newClientSecretEncrypted,
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
