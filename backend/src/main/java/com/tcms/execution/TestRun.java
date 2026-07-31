package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One pass over a plan's scope, in one configuration (ERD §5).
 *
 * <p>Read-only in this slice, for the reason {@link TestPlan} gives.
 *
 * <p>{@code release_sort_key} is deliberately <strong>not mapped</strong>. It is computed on write
 * from {@code release_version} — semver parsed to {@code %05d.%05d.%05d} — and this slice performs
 * no write that could compute it. Mapping a column nothing here maintains is how it comes to hold a
 * value that disagrees with the field it is derived from, and that failure is silent: "1.10" sorts
 * before "1.9" and every latest-release query is quietly wrong (§6q). It joins the entity in the
 * slice that creates runs.
 */
@Entity
@Table(name = "test_run")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null for an ad-hoc run — a run need not belong to a plan. */
    @Column(name = "plan_id", updatable = false)
    private UUID planId;

    @Column(name = "configuration_id", nullable = false)
    private UUID configurationId;

    /** {@code TR-1} — sequential per COMPANY and immutable (§6t). */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RunStatus status;

    @Column(name = "release_version")
    private String releaseVersion;

    /** Distinct from {@link #releaseVersion} — see V1's column comment for what conflating costs. */
    @Column(name = "build_number")
    private String buildNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RunSource source;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected TestRun() {
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    UUID getPlanId() {
        return planId;
    }

    UUID getConfigurationId() {
        return configurationId;
    }

    String getKey() {
        return key;
    }

    long getKeySeq() {
        return keySeq;
    }

    String getName() {
        return name;
    }

    RunStatus getStatus() {
        return status;
    }

    String getReleaseVersion() {
        return releaseVersion;
    }

    String getBuildNumber() {
        return buildNumber;
    }

    RunSource getSource() {
        return source;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }
}
