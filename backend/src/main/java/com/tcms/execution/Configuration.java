package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The environment a run was executed in — "Chrome / Windows", "staging", a device matrix cell.
 *
 * <p>Every project is seeded with a {@code Default} row by {@code tcms_seed_project_defaults}, and
 * {@code test_run.configuration_id} is {@code NOT NULL} because of it. {@code V1}'s own column
 * comment gives the reason and it is not tidiness: {@code suite_run_summary} and
 * {@code plan_snapshot} key on the configuration, and a nullable column cannot participate in a
 * primary key — so a run with no configuration could never be summarized.
 *
 * <p>{@link #attributes} is {@code JSONB} because the matrix dimensions differ per project. Held as
 * a {@code String} here and parsed once at the edge, for the reason {@link TestPlan} gives about
 * its criteria.
 */
@Entity
@Table(name = "configuration")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Configuration {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false)
    private String attributes;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    /** JPA only. */
    protected Configuration() {
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getName() {
        return name;
    }

    String getAttributes() {
        return attributes;
    }

    boolean isDefault() {
        return isDefault;
    }
}
