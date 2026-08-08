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
 * {@code docs/specs/2026-07-30-tcms-roles-permissions-naming.md} — plus two rows that are
 * deliberately <em>not</em> among them: {@link #COMPANY_MEMBER_ID}, the membership baseline V15
 * added, and {@link #AGENT_ID}, the token-only ceiling V40 added. Both are
 * {@link #isAssignable() non-assignable}, and there the resemblance stops — the baseline never
 * appears on the wire, where Agent must.
 *
 * <p><strong>Non-assignable is not the same list as "not one of the six".</strong> Since {@code V41}
 * {@link #AUTOMATION_SERVICE_ID} — which <em>is</em> one of the six — is non-assignable too, so the
 * flag now separates the two token-carried ceilings and the membership baseline from the five roles
 * an administrator may give a person.
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

    /**
     * Automation Service — PROJECT scope. Non-human; attached to API tokens only.
     *
     * <p><strong>One of the six, and non-assignable since {@code V41}.</strong> {@code V3} has
     * said "never people" since M0, and until {@code V41} nothing enforced it: the role was offered
     * by every grant picker and accepted by {@code POST /role-assignments}. It is the second row to
     * use {@link #isAssignable()}, so "the six" and "the assignable set" are no longer the same
     * list — five of the six may be granted to a person (roles spec §A2).
     *
     * <p>Everything else about it is unchanged and deliberately so: the five capabilities plus
     * {@code project:read} ({@code V16}), still the smallest role in the product, still issuable to
     * a token and still listed. A leaked CI credential that cannot edit or delete a real case is
     * the entire value of the role, and that is independent of who may hold it.
     */
    public static final UUID AUTOMATION_SERVICE_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000005");

    /** Viewer — PROJECT scope. The default at every entry point. */
    public static final UUID VIEWER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000006");

    /**
     * Company Member — COMPANY scope, holding exactly {@code company:read}, granted to every
     * member on joining (V15).
     *
     * <p><strong>Not one of the six.</strong> It is not offered by {@code GET /roles}, cannot be
     * granted through {@code POST /role-assignments}, and its grants are filtered out of
     * {@code GET /role-assignments} so nothing renders a Revoke for them — see
     * {@link AdminDirectoryService#roleCatalogue()} and {@link RoleGrantService}. It exists because
     * {@code company:read} is granted to every member (roles spec §A3) while four of the five roles
     * holding it are PROJECT-scope, and {@link PermissionEvaluator#evaluateCompanyWide} answers from
     * the SYSTEM and COMPANY terms only — so without it nobody below Company Admin could answer a
     * company-level question. Handoff §43.
     */
    public static final UUID COMPANY_MEMBER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000007");

    /**
     * Agent — PROJECT scope, carried by API tokens only, seeded by {@code V40}.
     *
     * <p><strong>Also not one of the six</strong>, and non-assignable for {@link #COMPANY_MEMBER_ID}'s
     * reason expressed the other way round: nobody is ever an Agent, so granting it to a person is
     * meaningless rather than merely unhelpful. {@link RoleGrantService} refuses it in both
     * directions — through {@link #isAssignable()}, not through a comparison with this constant.
     *
     * <p><strong>But it is issuable and it is listed</strong>, which is exactly where it parts
     * company with the baseline: {@code GET /roles} returns it because the token-issue form is the
     * only place it is ever chosen, and {@link ApiTokenService} accepts it. Three different
     * questions — may it be granted, may a token carry it, is it listed — and
     * {@code assignable} answers only the first. See {@code V40} §2 before wiring it into a
     * fourth site.
     *
     * <p>It is the ceiling for an agent acting on behalf of the person who issued the token:
     * Test Engineer minus {@code library:manage} (gated by an impact modal an API caller never
     * sees), minus {@code case:delete} and {@code evidence:delete} (both {@code OWN}, and
     * {@code OWN} follows the token's <em>owner</em>, so the agent would delete what the human
     * authored). The intersection in {@link PermissionEvaluator} does the rest: a Viewer's agent
     * token still resolves to Viewer's set.
     */
    public static final UUID AGENT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000008");

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

    /**
     * Whether {@code POST /role-assignments} may grant this role to a person or a team. False for
     * {@link #COMPANY_MEMBER_ID}, {@link #AGENT_ID} and — since {@code V41} —
     * {@link #AUTOMATION_SERVICE_ID}.
     *
     * <p><strong>It answers one question of four, and {@code V41} is where the fourth left.</strong>
     * It is not "hidden": both ceilings are returned by {@code GET /roles}, because the token-issue
     * form is the only place either is chosen. It is not "not issuable": both are issuable, which is
     * what they exist for. And it is <strong>not "not revocable"</strong> — that sentence used to be
     * here and was wrong the moment a second row wanted a different answer. {@link RoleGrantService}
     * refuses a revoke only for the baseline, whose removal takes away a colleague's sight of the
     * company; an Automation Service grant made before {@code V41} must stay revocable or it can
     * never be cleaned up.
     *
     * <p>The questions coincided while there was one non-assignable role — see {@link #AGENT_ID},
     * {@code V40} §2 and {@code V41} §1. {@link AdminDirectoryService}'s two filters,
     * {@link ApiTokenService}'s issue check and {@code RoleGrantService.revoke} stay
     * baseline-specific.
     */
    @Column(name = "assignable", nullable = false)
    private boolean assignable;

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

    public boolean isAssignable() {
        return assignable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
