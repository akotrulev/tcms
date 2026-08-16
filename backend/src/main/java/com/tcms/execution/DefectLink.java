package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A link out to a defect in somebody else's tracker — {@code V1}'s {@code defect_link} table, first
 * writer, arriving with {@code V62} (audit C5).
 *
 * <p>TCMS is not a defect tracker; it links out. The row is therefore a key, a URL and the name of
 * whatever holds them, and nothing that would have to be kept in step with the tracker.
 *
 * <h2>{@link #provider} is {@code EXTERNAL} and the caller cannot say otherwise</h2>
 *
 * <p>{@code V1} defaulted the column to {@code 'JIRA'}, which named a product the instance does not
 * talk to; {@code V62} dropped that default and the argument is in its header. What is decided
 * <em>here</em> is the other half: {@link DefectLinkInput} has no {@code provider} field, so a
 * caller cannot supply one either.
 *
 * <p>That is deliberate and it is not tidiness. Pre-M4 there is no integration to check a claim
 * against, so a caller-supplied {@code JIRA} would be an unverified assertion recorded as a fact —
 * and worse than the old default, because it reads as somebody's deliberate statement rather than
 * as a column nobody filled in. {@link #PROVIDER_EXTERNAL} is the only thing this writer can say
 * truthfully: a person made this link. M4's adapter states a real provider because it will have
 * spoken to one.
 *
 * <h2>{@code status_cache}, {@code synced_at} and {@code case_id} are not mapped</h2>
 *
 * <p>All three are columns this writer must not fill, so they are absent from the mapping rather
 * than present and permanently null — an unmapped column cannot be set by a later edit that did not
 * read {@code V62}'s header. The reasons are that header's; the one that matters at this seam is
 * that {@code status_cache} is served to clients, so writing a caller's guess into it would publish
 * a tracker's answer nobody obtained.
 *
 * <p>The read is unaffected: {@link TestExecutionRepository#findDefectLinks} is native and selects
 * the columns directly, which is why serving {@code status_cache} costs no mapping here.
 */
@Entity
@Table(name = "defect_link")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class DefectLink {

    /**
     * What {@link #provider} says for every row written before the {@code IssueTracker} port
     * exists. Not a default and not an enum: the column is free text because M4's adapters name
     * their own trackers, and constraining it to a Java enum now would make adding an adapter a
     * schema change.
     */
    static final String PROVIDER_EXTERNAL = "EXTERNAL";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "external_key", nullable = false, updatable = false)
    private String externalKey;

    @Column(name = "url", nullable = false, updatable = false)
    private String url;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected DefectLink() {
    }

    /**
     * Every mapped column is {@code updatable = false}, so there is no edit path and no
     * {@code PATCH} could grow one by accident.
     *
     * <p>A link is a pair of facts — which defect, and where it lives — and correcting either makes
     * it a link to a different defect. Unlink and link again, which is what the two endpoints do
     * and what leaves the audit trail saying what happened.
     */
    static DefectLink to(
            UUID companyId,
            UUID executionId,
            String externalKey,
            String url,
            UUID userId,
            Instant now) {

        DefectLink link = new DefectLink();
        link.id = UUID.randomUUID();
        link.companyId = companyId;
        link.executionId = executionId;
        link.provider = PROVIDER_EXTERNAL;
        link.externalKey = externalKey;
        link.url = url;
        link.createdAt = now;
        link.createdBy = userId;
        return link;
    }

    UUID getId() {
        return id;
    }

    UUID getExecutionId() {
        return executionId;
    }

    String getProvider() {
        return provider;
    }

    String getExternalKey() {
        return externalKey;
    }

    String getUrl() {
        return url;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    /** What the readers serve. {@code statusCache} is null by construction until M4 — {@code V62}. */
    DefectLinkView toView() {
        return new DefectLinkView(id, externalKey, provider, url, null);
    }
}
