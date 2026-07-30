package com.tcms.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One of the six built-in roles, seeded by {@code V3__seed_permissions_and_roles.sql}
 * from the authoritative
 * {@code docs/specs/2026-07-30-tcms-roles-permissions-naming.md}.
 *
 * <p><strong>Instance-level and deliberately without {@code company_id}</strong>: the six
 * built-ins are the complete role set in v1 — no custom roles, not even clone-and-edit
 * (plan §6b). It is on the ERD §0.1 exception list.
 *
 * <p>There is no factory method here on purpose. Roles are reference data owned by a
 * migration; a {@code Role.create(...)} would be an invitation to write the seventh one
 * from application code, and the whole JWT-grant design in plan §8 is viable
 * <em>only because</em> {@code role → permission[]} is a compile-time constant.
 *
 * <p>Nothing anywhere checks a role by name. Code checks permissions
 * ({@link PermissionEvaluator}), which is what keeps a custom-role designer additive
 * later — and when that lands, custom roles must be company-owned, because a client
 * editing "Test Engineer" must never mutate a row another client reads.
 */
@Entity
@Table(name = "role")
public class Role {

    /** Instance Admin — SYSTEM scope. Seeded id, referenced by the provisioning path. */
    public static final UUID INSTANCE_ADMIN_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");

    /** Company Admin — COMPANY scope. */
    public static final UUID COMPANY_ADMIN_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");

    /** Test Lead — PROJECT scope. */
    public static final UUID TEST_LEAD_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000003");

    /** Test Engineer — PROJECT scope. Always an explicit grant, never a default. */
    public static final UUID TEST_ENGINEER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000004");

    /** Automation Service — PROJECT scope. Non-human; attached to API tokens only. */
    public static final UUID AUTOMATION_SERVICE_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000005");

    /** Viewer — PROJECT scope. The default at every entry point. */
    public static final UUID VIEWER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000006");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private RoleScope scope;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected Role() {
    }

    public UUID getId() {
        return id;
    }

    public RoleScope getScope() {
        return scope;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
