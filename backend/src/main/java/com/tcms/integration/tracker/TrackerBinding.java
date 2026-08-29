package com.tcms.integration.tracker;

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
 * {@code tracker_binding} — one project's mapping to one tracker project ({@code V73}, 004 D1).
 *
 * <h2>It holds no credential, and there is no field one could be put in</h2>
 *
 * <p>That is the entire point of D2's split. Revision 2 of the plan had one table carrying both the
 * mapping and a shared secret, and the shared secret is what made every preview in the product a
 * cross-user disclosure: everyone who could see the project could see every issue that one
 * credential could see. {@link TrackerIdentity} carries the ciphertext, one row per person per
 * host, and this row carries the question of <em>which tracker project</em> and nothing else.
 *
 * <h2>{@code provider} and {@code baseUrl} are {@code updatable = false}, and that is a rule</h2>
 *
 * <p>Identities are keyed to {@code (provider, baseUrl)}. A binding that could change host would
 * silently start using a <em>different set of people's</em> credentials against a server nobody
 * consented to — and if the new host is attacker-chosen, the guard that checked the original URL
 * never ran. So the columns are immutable here, the {@code PATCH} declares neither, and changing a
 * host is a delete and a create: which invalidates the identities, which is the correct and visible
 * outcome.
 *
 * <h2>The default flag is a property of the project, kept by the service</h2>
 *
 * <p>{@code tracker_binding_default_idx} enforces <em>at most one</em>. "Never zero" is
 * {@link TrackerBindingService}'s: the first binding becomes the default whether or not it said so,
 * a later {@code isDefault: true} moves the flag, {@code isDefault: false} on the current default is
 * refused, and deleting the default promotes the oldest remaining one.
 */
@Entity
@Table(name = "tracker_binding")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TrackerBinding {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private TrackerProvider provider;

    @Column(name = "base_url", nullable = false, updatable = false)
    private String baseUrl;

    @Column(name = "external_project", nullable = false)
    private String externalProject;

    /**
     * The binding's JSONB, held as the raw document.
     *
     * <p>A {@code String} and not a {@code Map}, for {@code run_schedule.scope_request}'s reason:
     * the keys are the <em>adapter's</em>, so a typed mapping here would be this module declaring a
     * shape it does not own, and a fourth adapter would be an entity change. It is parsed once, at
     * the boundary, by {@link TrackerBindingService}.
     *
     * <p><strong>{@code @JdbcTypeCode(JSON)}, not {@code columnDefinition = "jsonb"}</strong> —
     * {@code InstanceAuditEvent.changes} records the trap at length and this field walked into it:
     * {@code columnDefinition} satisfies {@code ddl-auto: validate} while the parameter is still
     * bound as {@code varchar}, and Postgres does not implicitly cast {@code varchar} to
     * {@code jsonb}. Every insert failed with "column config is of type jsonb but expression is of
     * type character varying", which no compile and no schema check can see.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private String config;

    @Column(name = "is_default", nullable = false)
    private boolean defaultBinding;

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

    /**
     * A real {@code @Version}, because the controller takes {@code If-Match}.
     *
     * <p>Two leads editing one binding is not hypothetical — a binding is edited from a settings
     * screen that several people can hold open — and a last-write-wins {@code config} silently
     * discards the JQL scope somebody else just narrowed.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected TrackerBinding() {
    }

    static TrackerBinding create(
            UUID projectId,
            TrackerProvider provider,
            String baseUrl,
            String externalProject,
            String config,
            boolean isDefault,
            boolean enabled,
            UUID creatorId,
            Instant now) {

        TrackerBinding created = new TrackerBinding();
        created.id = UUID.randomUUID();
        created.companyId = CompanyContext.require();
        created.projectId = projectId;
        created.provider = provider;
        created.baseUrl = baseUrl;
        created.externalProject = externalProject;
        created.config = config;
        created.defaultBinding = isDefault;
        created.enabled = enabled;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.updatedAt = now;
        created.updatedBy = creatorId;
        return created;
    }

    /**
     * Applies a patch's present fields. Nulls mean unchanged — see {@link TrackerBindingPatch}.
     *
     * <p>Named rather than four setters, for {@code DocLink#relabel}'s reason: there is no way to
     * move a field without also stamping who moved it and when.
     */
    void apply(
            String externalProject,
            String config,
            Boolean isDefault,
            Boolean enabled,
            UUID actorId,
            Instant now) {

        if (externalProject != null) {
            this.externalProject = externalProject;
        }
        if (config != null) {
            this.config = config;
        }
        if (isDefault != null) {
            this.defaultBinding = isDefault;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /** Used when another binding is promoted or demoted — the flag moves without an operator act. */
    void setDefault(boolean isDefault, Instant now) {
        this.defaultBinding = isDefault;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    public TrackerProvider getProvider() {
        return provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    String getExternalProject() {
        return externalProject;
    }

    String getConfig() {
        return config;
    }

    boolean isDefaultBinding() {
        return defaultBinding;
    }

    boolean isEnabled() {
        return enabled;
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
