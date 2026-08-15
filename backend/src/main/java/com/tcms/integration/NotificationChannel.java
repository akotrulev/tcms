package com.tcms.integration;

import com.tcms.tenancy.CompanyContext;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Where one project sends its notices.
 *
 * <h2>The secret is set separately from everything else, and that is the whole shape</h2>
 *
 * <p>{@link #rename} takes the fields a person can read back; {@link #replaceSecret} takes the
 * credential. They are two methods because the update path has to be able to say <em>leave the
 * webhook alone</em> — a screen that never shows a secret cannot send one back, so an edit form
 * that submitted every field would blank the credential of every channel anybody renamed. That is
 * a defect with no error message: the row still looks configured, and delivery starts failing
 * later against a URL of empty bytes.
 *
 * <p>{@link #getSecret()} hands back ciphertext and there is no accessor for plaintext anywhere on
 * this class. Decryption lives in {@link SecretCipher} and is reached only from
 * {@link NotificationChannelPorts}, which is the one place a URL exists in memory.
 *
 * <h2>{@code lock_version} is a real {@code @Version}, unlike {@code work_item}'s</h2>
 *
 * <p>{@code ApiToken}'s rubric decides it the other way here. A channel edit is a <em>field</em>
 * edit — a name and a recipient list typed over what somebody read — so two administrators editing
 * one channel really do lose each other's words, which is the lost update {@code work.WorkItem}
 * has no way to suffer because its only write names an absolute destination.
 */
@Entity
@Table(name = "notification_channel")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class NotificationChannel {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /**
     * Not updatable, deliberately.
     *
     * <p>Changing a Slack channel into an email one keeps the row's name and history while
     * replacing everything they were about — the config means something else, the secret is
     * meaningless, and the delivery log below it describes a transport that no longer exists.
     * Deleting and creating says that, and costs one row nobody was reading.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private NotificationChannelKind kind;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** The non-credential settings, as the document {@link ChannelConfig} parses. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private String config;

    /** AES-GCM ciphertext, or null for a kind that has no credential of its own. */
    @Column(name = "secret")
    private byte[] secret;

    @Column(name = "secret_hint")
    private String secretHint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected NotificationChannel() {
    }

    /**
     * A new channel in the active company.
     *
     * <p>{@code companyId} comes from {@link CompanyContext} and never from the caller (ERD §0.1
     * point 2): the caller names a project, and which company that project belongs to is not
     * theirs to assert.
     */
    static NotificationChannel create(
            UUID projectId,
            NotificationChannelKind kind,
            String name,
            boolean enabled,
            String config,
            byte[] secret,
            String secretHint,
            Instant now) {

        NotificationChannel created = new NotificationChannel();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.kind = kind;
        created.name = name;
        created.enabled = enabled;
        created.config = config;
        created.secret = secret;
        created.secretHint = secretHint;
        created.createdAt = now;
        created.updatedAt = now;
        return created;
    }

    /** Everything a person can read back on the screen they are editing. */
    void rename(String name, boolean enabled, String config, Instant now) {
        this.name = name;
        this.enabled = enabled;
        this.config = config;
        this.updatedAt = now;
    }

    /** The credential, on its own — see the class header for why it is not part of {@link #rename}. */
    void replaceSecret(byte[] secret, String secretHint, Instant now) {
        this.secret = secret;
        this.secretHint = secretHint;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    NotificationChannelKind getKind() {
        return kind;
    }

    String getName() {
        return name;
    }

    boolean isEnabled() {
        return enabled;
    }

    String getConfig() {
        return config;
    }

    byte[] getSecret() {
        return secret;
    }

    String getSecretHint() {
        return secretHint;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
