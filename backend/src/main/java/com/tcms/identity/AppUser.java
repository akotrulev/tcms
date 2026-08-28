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

    /**
     * {@code V1 NOT NULL} — and <strong>written only as the constant {@code "en"} and read by
     * nothing</strong> (§154, {@code ArchitectureRulesTest.READERLESS_BY_DECISION}).
     *
     * <p>{@link #invite} is the only writer and it hard-codes the value; {@link #getLocale} has no
     * caller anywhere in {@code src/main} or {@code src/test}. Kept as a decision rather than
     * dropped, because the two halves must arrive together: a preference a person can set and
     * nothing honours is a broken promise, and a preference something honours that nobody can set
     * is a hidden default. Give it a writer and a reader in one slice or leave it alone.
     */
    @Column(name = "locale", nullable = false)
    private String locale;

    /**
     * {@link #locale}'s shape, constant {@code "UTC"}, and the more pointed of the two.
     *
     * <p>The {@code @Version} javadoc below reasons about what happens "when someone edited their
     * own timezone" — <strong>an act this product cannot perform</strong>, since there is no writer
     * but {@link #invite}'s constant and no reader at all (§154). Every timestamp renders in the
     * browser's zone. The comment is left standing because the argument it makes about
     * {@code tokenVersion} is correct and independent of whether that particular edit exists.
     */
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
     * Creates an account that an external issuer has just authenticated — {@link UserStatus#ACTIVE}
     * from the first moment, and with <strong>no password at all</strong>.
     *
     * <p>The two differences from {@link #invite} are the same fact stated twice. There is no
     * invitation to accept because the IdP has already proved who this is, and there is no password
     * because this account has no local credential to prove it with: {@code AuthenticationService}
     * refuses a null hash, so the only way in stays the provider that created it. Reached only when
     * {@link IdentityProvider#isJitProvisioning()} is on (#38).
     */
    public static AppUser federate(UUID id, String email, String canonicalName, Instant now) {
        AppUser user = invite(id, email, canonicalName, now);
        user.status = UserStatus.ACTIVE;
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
     * forget it, and from {@link MembershipRemovalService} — the company membership change
     * above, and the first caller outside this class. Removing somebody whose token is still
     * valid leaves them authenticated inside a company they are no longer in, for as long as
     * the token lasts.
     *
     * <p><strong>A grant change deliberately does not call it</strong>, and that is not the
     * same gap: the session carries no grant claims, so {@link PermissionEvaluator} reads the
     * database on every request and a revoke is effective on the next one.
     * {@link RoleGrantService}'s header states the reasoning and names the day it changes.
     * A team membership change has no writer yet.
     */
    public void revokeSessions() {
        this.permissionVersion++;
    }

    /**
     * Sets or replaces the password (005 S9a). <strong>Bumps nothing</strong>: the epoch is a
     * revocation channel, and rotating a credential on a schedule is not a reason to sign the
     * account out of its phone — a person who wants the other sessions gone asks for that by name
     * ({@code POST /me/sign-out-others}), and coupling the two would make the safe habit costly.
     */
    public void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.updatedAt = now;
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
