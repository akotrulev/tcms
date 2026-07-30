package com.tcms.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * An account, at the <em>instance</em> level.
 *
 * <p><strong>Deliberately carries no {@code company_id} and no company filter</strong>
 * — one account belongs to several companies with independent permissions in each
 * (plan §6s). It is on the ERD §0.1 exception list, so {@code SchemaConventionTest}
 * does not require the column, and {@code CompanyFilterTest} does not require the
 * filter.
 *
 * <p>The consequence is a disclosure channel that has to be handled above this class:
 * email is unique instance-wide, so an invitation flow must never reveal whether an
 * address already exists, and nothing below Instance Admin may enumerate a user's other
 * memberships.
 *
 * <p><strong>No user-facing name lives here.</strong> {@link #canonicalName} is for the
 * Instance Admin directory only; every rendering site resolves the name through the
 * active company's {@link CompanyMembership} (§6u), because a contractor may legitimately
 * be "A. Kotrulev" to one client and "Aleks (Vendor)" to another, and neither client may
 * see or overwrite the other's.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * {@code CITEXT}, so uniqueness and lookup are case-insensitive without every call
     * site remembering to lower-case. Never a key for anything — identity is keyed on
     * {@link #id} so M4's SSO work stays additive (plan §6r).
     *
     * <p><strong>The {@code columnDefinition} is load-bearing, not decoration.</strong>
     * {@code citext} is an extension type that reports as {@code Types#OTHER}, while a
     * plain {@code String} field maps to {@code varchar} — so {@code ddl-auto: validate}
     * refuses to start with "found [citext (Types#OTHER)], but expecting [varchar(255)]".
     * Hibernate's validator falls back to comparing the SQL type <em>name</em> when the
     * type codes differ, and naming {@code citext} here is what satisfies that fallback.
     *
     * <p>Binding stays the default {@code setString}: Postgres coerces {@code varchar} to
     * {@code citext} on assignment and comparison, so case-insensitivity is preserved.
     * ({@code @JdbcTypeCode(OTHER)} is the tempting fix and is wrong — Hibernate resolves
     * {@code OTHER} to a binary descriptor and fails with "Could not convert
     * 'java.lang.String' to '[B'".) Dropping this annotation fails at boot rather than at
     * the first query, but the behaviour it protects is pinned by
     * {@code AppUserEmailCaseTest}.
     */
    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "canonical_name", nullable = false)
    private String canonicalName;

    /** Null when SSO-only (M4). The break-glass Instance Admin must always have one. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "locale", nullable = false)
    private String locale;

    @Column(name = "timezone", nullable = false)
    private String timezone;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * The {@code pv} revocation epoch (plan §6b). Bumped by {@link #revokeSessions} on any
     * change to this account's effective authorization; a session token carries the value
     * it was issued with, and a mismatch on any request means the token predates a
     * revocation.
     *
     * <p><strong>Not behind {@code @Version}.</strong> The two look alike and answer
     * different questions: {@code lockVersion} detects a concurrent write to this row and
     * is incremented by Hibernate on every update, including ones that change nothing about
     * authorization. Driving revocation off it would invalidate every live session when
     * someone edited their own timezone.
     */
    @Column(name = "permission_version", nullable = false)
    private int permissionVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected AppUser() {
    }

    /**
     * Creates an account in {@link UserStatus#INVITED} — the only state a new account
     * may start in, so accepting an invitation is always an explicit act.
     */
    public static AppUser invite(UUID id, String email, String canonicalName, Instant now) {
        AppUser user = new AppUser();
        user.id = id;
        user.email = email;
        user.canonicalName = canonicalName;
        user.status = UserStatus.INVITED;
        user.locale = "en";
        user.timezone = "UTC";
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    /**
     * Accepts an invitation: sets the first password and opens the account.
     *
     * <p>Bumps the revocation epoch, because {@code INVITED → ACTIVE} changes what this
     * account may do — every gate in {@link PermissionEvaluator} keys off
     * {@link UserStatus#ACTIVE}.
     */
    public void activate(String passwordHash, Instant now) {
        this.status = UserStatus.ACTIVE;
        this.passwordHash = passwordHash;
        this.updatedAt = now;
        revokeSessions();
    }

    /**
     * The instance-wide off switch. Zeroes the permission set in every company,
     * including SYSTEM-scope grants, without deleting the row that authorship depends on.
     */
    public void deactivate(Instant now) {
        this.status = UserStatus.DEACTIVATED;
        this.updatedAt = now;
        revokeSessions();
    }

    /**
     * Bumps the {@code pv} epoch, invalidating every session token issued for this account
     * so far.
     *
     * <p>Public because the callers that must invoke it live outside this class: a grant
     * change, a team membership change, a company membership change. Each of those is a
     * write to another aggregate, and the epoch is what turns those writes into prompt
     * revocation instead of "effective when the token expires".
     *
     * <p>Called from {@link #activate} and {@link #deactivate} so status changes cannot
     * forget it. <strong>The other call sites do not exist yet</strong> — grant and
     * membership mutation still only happens in provisioning, which creates rather than
     * changes — and that gap is recorded here rather than being silently assumed covered.
     */
    public void revokeSessions() {
        this.permissionVersion++;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }

    public String getLocale() {
        return locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public int getPermissionVersion() {
        return permissionVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
