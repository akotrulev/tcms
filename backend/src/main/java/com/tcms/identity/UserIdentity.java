package com.tcms.identity;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One external identity, bound to one TCMS user.
 *
 * <p>The key is {@code (provider_id, subject)} and <strong>never the email</strong>: emails
 * change and get reassigned, and the IdP {@code sub} is the only stable handle (§6r). Because
 * {@code identity_provider} is itself company-scoped, that key is {@code (company, issuer,
 * subject)} — which is the property that stops a different client's IdP asserting the same
 * {@code sub} from resolving to this user. That is the multi-company account-takeover vector,
 * and it is the reason issuer trust is company-local rather than instance-wide.
 *
 * <p>{@link #emailAtLink} is a record of what the IdP said at link time. It is never read back
 * as a key.
 */
@Entity
@Table(name = "user_identity")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class UserIdentity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "email_at_link", updatable = false)
    private String emailAtLink;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** JPA only. */
    protected UserIdentity() {
    }

    public static UserIdentity link(
            UUID id,
            UUID companyId,
            UUID userId,
            UUID providerId,
            String subject,
            String emailAtLink,
            Instant now) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "An external identity needs the IdP subject — linking on email is the "
                            + "account-takeover vector §6r exists to close");
        }
        UserIdentity identity = new UserIdentity();
        identity.id = id;
        identity.companyId = companyId;
        identity.userId = userId;
        identity.providerId = providerId;
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

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProviderId() {
        return providerId;
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
