package com.tcms.project;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.hibernate.annotations.Filter;

/**
 * A company-owned aggregate, and the M0 vertical slice that proves the isolation
 * plumbing end to end.
 *
 * <p>Note the two things every company-owned entity must carry:
 * <ul>
 *   <li>{@code company_id} as a real, NOT NULL column — never derived, never nullable</li>
 *   <li>{@code @Filter} naming the tenancy filter, so a Session-based query is scoped
 *       even before RLS is consulted</li>
 * </ul>
 * {@code SchemaConventionTest} enforces the first mechanically over every table, and
 * {@code CompanyFilterTest} the second over every entity — because "remember to add
 * it" is not a mechanism that survives a year of feature work (ERD §0.1).
 */
@Entity
@Table(name = "project")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Project {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * Unique per COMPANY, not globally — two clients may both run a project keyed
     * "OLB". The uniqueness constraint is {@code (company_id, key)} with company_id
     * leading (ERD §0.1 point 3).
     */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /**
     * The client this project is for, or {@code null} for internal work (scope D1).
     *
     * <p><strong>A plain {@code UUID} and not a {@code @ManyToOne}.</strong> The row lives in
     * {@code crm}, which is not a foundation module, so an association would be an import this
     * module may not make — and {@code ddl-auto: validate} would then check two mappings over one
     * table, which is {@code execution.CaseFacts}' recorded reason for refusing that shape. The
     * name is resolved through {@link ClientDirectory}, which {@code crm} supplies.
     *
     * <p><strong>Nullable forever, and there is no synthetic "Internal" client</strong> (scope D1
     * rule 1). That row would put a fake customer on every internal project, pollute every client
     * rollup with a company billing itself, and make "which projects are contracted work?" — the
     * question this column exists to answer — unanswerable. A project with {@code null} here is not
     * incomplete, and nothing in this product renders it as missing one.
     *
     * <p>The column has existed since {@code V2}, deliberately unmapped: {@code ArchitectureRulesTest}
     * sweeps {@code @Entity} fields for readerless and writerless columns in both directions, so a
     * mapping added by M0 would have been an entry Mc then had to remove. {@code V11} adds the
     * foreign key and this adds the mapping, together, as {@code V2}'s own comment promised.
     */
    @Column(name = "client_id")
    private UUID clientId;

    /**
     * The design-token name ({@code project-1} … {@code project-8}) of the colour this project's key
     * chip is drawn in — a token and not a hex, so the palette can be re-tuned without a migration
     * (023 D17).
     */
    @Column(name = "color", nullable = false)
    private String color;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When this project was retired — ~~and <strong>nothing has ever set it</strong>~~ →
     * <strong>{@code PUT /projects/{projectId}/archive} sets it (§159)</strong>.
     *
     * <p>From {@code V1} to §159 this column had no writer any request could reach: {@link #archive}
     * existed and was correct, {@code ProjectView.archived} was <em>required</em> by the contract and
     * derived from it, and the answer was {@code false} in every deployment there had ever been.
     * §156 found it as the expensive half of the writerless shape — not a column nobody looks at,
     * but one a consumer draws a conclusion from. The one caller {@code archive} did have was a
     * <em>test fixture</em>, which seeded by hand the state the product could not enter: §153's
     * rule, that a test caller is a consumer for a read seam and not for a write one, arriving on a
     * class §153 could not see.
     *
     * <p><strong>A timestamp rather than a boolean, and that is not merely how {@code V1} spelled
     * it.</strong> "Retired on 3 March" is a fact somebody will want on the screen and in an export;
     * a boolean throws it away and cannot be widened later without a migration and a backfill that
     * has nothing to backfill from. {@code ProjectView.archived} narrows it for the wire because the
     * picker renders a chip, not a date — the same direction as {@code deleted_at} everywhere else
     * in this schema, and never the reverse.
     *
     * <p>{@code ProjectWriteRequest}'s header argues the adjacent half — why create and edit must
     * <em>not</em> accept {@code archived} — and it still holds: the state moves through its own
     * endpoint, as a doc page's publication state does, so a project cannot be born retired and a
     * rename cannot retire one by carrying a stale field.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    public static final List<String> COLORS = IntStream.rangeClosed(1, 8)
            .mapToObj(n -> "project-" + n)
            .toList();

    /** JPA only. */
    protected Project() {
    }

    /**
     * @param companyId taken from {@link com.tcms.tenancy.CompanyContext}, never from
     *                  the request body — a client-supplied company id is the whole
     *                  attack (ERD §0.1 point 2)
     */
    public static Project create(UUID id, UUID companyId, String key, String name, String description, Instant now) {
        Project project = new Project();
        project.id = id;
        project.companyId = companyId;
        project.key = key;
        project.name = name;
        project.description = description;
        project.color = defaultColorFor(key);
        project.createdAt = now;
        return project;
    }

    /** Deterministic from the key, so a create that does not choose still gets a colour. */
    static String defaultColorFor(String key) {
        return COLORS.get(Math.floorMod(key.hashCode(), COLORS.size()));
    }

    public void recolor(String newColor) {
        this.color = newColor;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void describe(String newDescription) {
        this.description = newDescription;
    }

    /**
     * Retires the project.
     *
     * <p>Not idempotent by itself and deliberately not made so: calling this on an archived project
     * would re-stamp the day it was retired with today's. {@link ProjectArchiveService} decides
     * whether the state is moving at all and calls neither mutator when it is not, which is also
     * what keeps a repeated click out of the audit trail.
     */
    public void archive(Instant now) {
        this.archivedAt = now;
    }

    /**
     * Brings the project back — and <strong>loses the date it was retired on</strong>, which is the
     * whole content of the decision.
     *
     * <p>The alternative is a second column recording the un-archiving, and that is a history of
     * this one state kept in the row rather than in {@code audit_event}, where every other "who
     * changed what, when" in this product lives. {@code project.archived} and
     * {@code project.unarchived} are written there with the actor, so the sequence is recoverable
     * from the place a person would look for it.
     */
    public void unarchive() {
        this.archivedAt = null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getColor() {
        return color;
    }

    public UUID getClientId() {
        return clientId;
    }

    /**
     * Attach, change or detach the client — {@code null} detaches, and is a legitimate target state
     * rather than a clearing operation.
     *
     * <p><strong>The caller has already checked the id is in this company.</strong> This does not,
     * and could not honestly: {@code fk_project_client} is on {@code id} alone and referential
     * integrity is not subject to RLS, so the constraint would accept another company's client.
     * {@code ClientDirectory.existsInCompany} is the check, and it runs where the outcome can still
     * become a {@code 400} on the right field (018 D-l).
     */
    public void attachClient(UUID newClientId) {
        this.clientId = newClientId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
