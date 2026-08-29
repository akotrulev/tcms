package com.tcms.integration.tracker;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * {@code tracker_issue_creation} — every attempt this instance made to create an issue in somebody
 * else's tracker ({@code V73}).
 *
 * <h2>Diagnostic, not recovery, and the distinction is load-bearing</h2>
 *
 * <p>Recovery is the outbox handler's job: {@code TrackerIssueCreatedEvent} is recorded in the same
 * transaction as the row below, and {@code execution}'s backstop writes the {@code defect_link} if
 * the client's own follow-up never arrives. This table is what you read when that mechanism cannot
 * help — an issue that exists in Jira for an execution the payload got wrong, or a create that the
 * tracker accepted and this process died before recording. It mirrors
 * {@code notification_delivery}'s shape for that reason: written {@code PENDING} <em>before</em> the
 * call, completed after, so an attempt that vanished mid-flight is visible as one that never
 * finished rather than as one that never happened.
 *
 * <h2>{@code user_id} is here because "who filed this" is a real question under D2</h2>
 *
 * <p>The issue was created with that person's credential, so the tracker's own {@code reporter} is
 * them — and the tracker's answer is only visible to people who can read that tracker. A TCMS
 * administrator investigating a duplicate has no other way to ask.
 */
@Entity
@Table(name = "tracker_issue_creation")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TrackerIssueCreation {

    /** Written before the call. A row still in this state is an attempt nothing completed. */
    static final String PENDING = "PENDING";

    static final String CREATED = "CREATED";

    static final String FAILED = "FAILED";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "binding_id", updatable = false)
    private UUID bindingId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "execution_id", updatable = false)
    private UUID executionId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "external_key")
    private String externalKey;

    @Column(name = "url")
    private String url;

    /** Redacted before it gets here — {@code SecretRedaction}, for the reason its header gives. */
    @Column(name = "error")
    private String error;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When the attempt stopped being in flight, set by both {@link #succeeded} and {@link #failed}.
     *
     * <p><strong>Readerless by decision.</strong> This table is diagnostic and not recovery — the
     * class header argues that at length — and the question this column answers is asked by a human
     * with {@code psql}, not by the product. What the product reads is {@code status}: an attempt
     * still {@code PENDING} is one that vanished mid-flight, which is the whole point of writing
     * the row before the call. A reader here would be an operator screen over somebody else's
     * tracker, which is not built and is a contract change rather than a wiring fix.
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** JPA only. */
    protected TrackerIssueCreation() {
    }

    static TrackerIssueCreation begin(
            UUID projectId,
            UUID bindingId,
            UUID userId,
            UUID executionId,
            TrackerProvider provider,
            String idempotencyKey,
            Instant now) {

        TrackerIssueCreation attempt = new TrackerIssueCreation();
        attempt.id = UUID.randomUUID();
        attempt.companyId = CompanyContext.require();
        attempt.projectId = projectId;
        attempt.bindingId = bindingId;
        attempt.userId = userId;
        attempt.executionId = executionId;
        attempt.status = PENDING;
        attempt.provider = provider.name();
        attempt.idempotencyKey = idempotencyKey;
        attempt.createdAt = now;
        return attempt;
    }

    void succeeded(TrackerIssue issue, Instant now) {
        this.status = CREATED;
        this.externalId = issue.id();
        this.externalKey = issue.key();
        this.url = issue.url();
        this.error = null;
        this.completedAt = now;
    }

    void failed(String redactedError, Instant now) {
        this.status = FAILED;
        this.error = redactedError;
        this.completedAt = now;
    }

    boolean isCreated() {
        return CREATED.equals(status);
    }

    UUID getId() {
        return id;
    }

    UUID getBindingId() {
        return bindingId;
    }

    String getProvider() {
        return provider;
    }

    String getExternalId() {
        return externalId;
    }

    String getExternalKey() {
        return externalKey;
    }

    String getUrl() {
        return url;
    }
}
