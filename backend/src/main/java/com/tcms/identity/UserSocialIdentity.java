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
 * One social identity (GitHub, GitLab, Google), bound to one TCMS account.
 *
 * <p>The key is {@code (provider, subject)} and never email — {@link UserIdentity}'s rule for
 * the same reason (§6r). Unlike {@code user_identity} this table is <strong>instance-level</strong>
 * (ERD §0.1, decided in {@code V58}): a person's social identity is theirs across every company
 * they belong to, and the login path reads it before any company context can exist. No company
 * filter, no RLS.
 *
 * <p>Rows are made by <strong>linking only</strong> — a signed-in act on the security page.
 * No account-creation path writes one.
 */
@Entity
@Table(name = "user_social_identity")
public class UserSocialIdentity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private SocialProviderKey provider;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    /** What the provider asserted at link time. Provenance only; never read as a key. */
    @Column(name = "email_at_link", updatable = false)
    private String emailAtLink;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** JPA only. */
    protected UserSocialIdentity() {
    }

    public static UserSocialIdentity link(
            UUID id,
            UUID userId,
            SocialProviderKey provider,
            String subject,
            String emailAtLink,
            Instant now) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "A social identity needs the provider's subject — linking on email is the "
                            + "account-takeover vector §6r exists to close");
        }
        UserSocialIdentity identity = new UserSocialIdentity();
        identity.id = id;
        identity.userId = userId;
        identity.provider = provider;
        identity.subject = subject;
        identity.emailAtLink = emailAtLink;
        identity.linkedAt = now;
        return identity;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public SocialProviderKey getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmailAtLink() {
        return emailAtLink;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
