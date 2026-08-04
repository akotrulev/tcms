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
 * A saved report — {@code V1}'s {@code report_definition}, and since §91 (R5) a row somebody can
 * create, list and delete.
 *
 * <h2>What a definition is, and what it deliberately is not</h2>
 *
 * <p>It is <strong>what to compute</strong>: a type and the parameters to compute it over. It is
 * <em>not</em> what came out and not how it was rendered — {@code V1} gave this table no format
 * column, so the same saved report is downloaded as Markdown today and HTML next release without
 * being a different saved report. §6e's sentence is "the same cycle report regenerates next release
 * with one click", and a format is not part of the cycle.
 *
 * <h2>The row is its owner's, which is why there is no create-time gate on anything but the key</h2>
 *
 * <p>{@link #ownerId} is stamped from the session and {@link #sharedWithTeamId} is written by
 * nothing: v1 has no way to share a definition, so a row is visible to, and deletable by, exactly
 * one person. That is what makes {@code report:view} the right key for the writes — saving one is
 * writing to your own workspace, not to project content — and it is why
 * {@code ReportDefinitionService} scopes every lookup by owner rather than asking a second
 * permission question about somebody else's row.
 *
 * <p>Sharing is the one part of this surface that would want a key the catalogue does not have, and
 * a key {@code V3} seeds to nobody is a 403 no administrator can grant their way out of (§67, §79,
 * §80, §82). It stays unmapped until there is a decision about that key.
 *
 * <h2>{@code schedule_cron} is the one column with a trap in it, and it is still unwritten</h2>
 *
 * <p>A definition with a cron expression is a report that generates itself, which means a job row
 * written by nobody, with {@code requested_by} null — a shape the job record already permits and
 * that no v1 code produces. Scheduled reports are §6e phase 2; the column is deliberately absent
 * from {@link #create} so a scheduler cannot arrive by accident and quietly attribute scheduled runs
 * to whoever saved the definition.
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

    /**
     * A new saved report, owned by the person saving it.
     *
     * <p>No {@code scheduleCron} and no {@code sharedWithTeamId} parameter — see the class header.
     * A factory that took them would make both reachable from the endpoint the moment somebody
     * added a field to the input record, which is the opposite of a decision.
     *
     * @param parameters the selection as JSON text, already checked for parseability by the service.
     *     Stored verbatim, because it is what a later generate merges over and what
     *     {@code report_artifact.filter_snapshot} is ultimately frozen from.
     */
    static ReportDefinition create(
            UUID companyId,
            UUID projectId,
            String type,
            String name,
            String parameters,
            UUID ownerId,
            Instant now) {

        ReportDefinition definition = new ReportDefinition();
        definition.id = UUID.randomUUID();
        definition.companyId = companyId;
        definition.projectId = projectId;
        definition.type = type;
        definition.name = name;
        definition.parameters = parameters;
        definition.ownerId = ownerId;
        definition.createdAt = now;
        return definition;
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
