package com.tcms.tracker.release;

import com.tcms.api.ReleaseSortKey;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A version this project ships — <strong>the only planning cadence in this product</strong>
 * (scope D6).
 *
 * <p>There is no {@code sprint} table, no velocity and no burndown. A release is a version,
 * optionally a date, and the one object both halves of this product care about: delivery asks
 * whether the stories for 2.4 are done, QA already asks whether the runs for 2.4 passed, and release
 * readiness is those two joined — the question neither a tracker nor a test tool answers alone.
 *
 * <p><strong>It owns nothing.</strong> {@code issue.release_id} points at it through a nullable
 * foreign key and there is no join table, so deleting a release loses no work: the issues fall back
 * to unscheduled, which is the state they were in before anybody planned them.
 *
 * <h2>{@link #sortKey} is computed here and never accepted from a caller</h2>
 *
 * <p>It is the column the roadmap orders by, and the failure it prevents is silent: text collation
 * puts {@code "1.10"} before {@code "1.9"}, so the query asking what the newest release did answers
 * about one from three months ago, with no error anywhere and a number that looks exactly right.
 * {@link #rename} is the one place the version and its key change, so the two cannot drift.
 *
 * <p>This is the <strong>third</strong> column in the schema holding that computation's output, and
 * it is not a reader of the other two: {@code test_run.release_sort_key} and
 * {@code test_execution.release_sort_key} are still ordered by nothing, and stay in
 * {@code ArchitectureRulesTest.READERLESS_BY_DECISION}. A release's ordering key belongs to the
 * release, not to each run that happens to mention its name (plan 013 D-a).
 */
@Entity
@Table(name = "release")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Release {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Column(name = "description")
    private String description;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReleaseStatus status;

    @Column(name = "released_at")
    private Instant releasedAt;

    /**
     * M9's burn-up baseline (009 review R12), not audit furniture — which is why it is on the wire
     * and why nothing may edit it. "Scope added after the release was created" is the count of
     * issues whose {@code issue_fact.release_assigned_at} is later than this, and that comparison is
     * the one line of the burn-up chart that cannot be reconstructed after the fact.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Release() {
    }

    static Release create(
            UUID id, UUID companyId, UUID projectId, String version, String description,
            LocalDate targetDate, UUID by, Instant now) {

        Release created = new Release();
        created.id = id;
        created.companyId = companyId;
        created.projectId = projectId;
        created.rename(version);
        created.description = description;
        created.targetDate = targetDate;
        created.status = ReleaseStatus.PLANNED;
        created.createdAt = now;
        created.createdBy = by;
        created.updatedAt = now;
        created.updatedBy = by;
        return created;
    }

    /**
     * The one place {@link #version} and {@link #sortKey} change, so they cannot disagree.
     *
     * <p>Renaming {@code 1.9} to {@code 1.10} therefore moves the release in the list rather than
     * leaving it where its old text sorted it — which is the whole reason the key is derived on
     * write instead of computed in an {@code ORDER BY}.
     */
    void rename(String version) {
        this.version = version;
        this.sortKey = ReleaseSortKey.of(version);
    }

    void revise(String description, LocalDate targetDate, UUID by, Instant now) {
        this.description = description;
        this.targetDate = targetDate;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    void touch(UUID by, Instant now) {
        this.updatedAt = now;
        this.updatedBy = by;
    }

    /**
     * Idempotent, and deliberately so: publishing an already-released release does <strong>not</strong>
     * restamp {@link #releasedAt}. A second press of a button is not a second shipment, and the
     * moment a version shipped is the one fact on this row that a re-press would quietly falsify.
     *
     * <p>Readiness does not gate this. A team may ship a version with a known failure and record
     * that they did; a server that refused would be asserting a release policy this product has no
     * way to know.
     */
    void publish(UUID by, Instant now) {
        if (status == ReleaseStatus.RELEASED) {
            return;
        }
        this.status = ReleaseStatus.RELEASED;
        this.releasedAt = now;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getVersion() {
        return version;
    }

    public String getSortKey() {
        return sortKey;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public ReleaseStatus getStatus() {
        return status;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
