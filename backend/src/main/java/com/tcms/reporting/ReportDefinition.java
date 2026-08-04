package com.tcms.reporting;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A saved report — {@code V1}'s {@code report_definition}, mapped here and <strong>written by
 * nothing yet</strong>.
 *
 * <h2>Why it is mapped in the slice that cannot create one</h2>
 *
 * <p>{@code report_job.definition_id} references this table, and R5 is the slice that gives it a
 * writer, an endpoint and a schedule. Mapping it now is not speculation: the job's own read path
 * needs the name of the definition it ran from to render, and an entity added later would arrive
 * together with three other things, which is how a reader loses track of which part the defect is
 * in.
 *
 * <p>What is deliberately absent is any mutator. There is no {@code create}, no {@code applyEdit}
 * and no delete — the same shape {@code TestPlan} had between M2 slice 2 and §74, and for the same
 * stated reason: the writes are a separate permission and a separate slice. Anything that starts
 * writing here also owns {@link #scheduleCron}, which is a scheduler's input and not a form field.
 *
 * <p><strong>There is deliberately no {@code ReportDefinitionRepository} either.</strong> The slice
 * plan lists one under R3; nothing in R3 reads a definition, because every job it can produce is
 * ad-hoc and carries {@code definition_id} null. A repository with no caller is dead code that reads
 * as a seam, and the next author extends it instead of asking whether it is the right shape. It
 * lands in R5 with the first query that needs it.
 *
 * <h2>{@code schedule_cron} is the one column with a trap in it</h2>
 *
 * <p>A definition with a cron expression is a report that generates itself, which means a job row
 * written by nobody, with {@code requested_by} null — a shape the job record already permits and
 * that no v1 code produces. R5 arrives at that; it is named here so it is not discovered by a
 * scheduler that quietly attributes scheduled runs to whoever saved the definition.
 */
@Entity
@Table(name = "report_definition")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ReportDefinition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "name", nullable = false)
    private String name;

    /** Includes the aggregation policy — {@code V1}'s comment, and §6e's "how reports lie". */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false)
    private String parameters;

    /** Null ⇒ run on demand only. See the class header for what a non-null value implies. */
    @Column(name = "schedule_cron")
    private String scheduleCron;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "shared_with_team_id")
    private UUID sharedWithTeamId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected ReportDefinition() {
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getType() {
        return type;
    }

    String getName() {
        return name;
    }

    String getParameters() {
        return parameters;
    }

    String getScheduleCron() {
        return scheduleCron;
    }

    UUID getOwnerId() {
        return ownerId;
    }

    UUID getSharedWithTeamId() {
        return sharedWithTeamId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
