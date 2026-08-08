package com.tcms.execution;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A test plan — a scope, a milestone and a set of criteria (ERD §5).
 *
 * <p>Creatable since M2 slice 2. Its <strong>status</strong> became writable in §73
 * ({@link #applyStatus}) and its row removable ({@link #softDelete}); its <em>content</em> — name,
 * description, milestone, criteria and scope — still has no mutator, because {@code plan:edit}
 * covers authoring and that is a separate slice. The factory is package-private and takes an
 * already-validated {@link PlanEdit}, so an entity that can be constructed before anything validates
 * it — the shape that writes a plan with no name and no key — is not expressible.
 *
 * <h2>The criteria are held as JSON text, not as a mapped graph</h2>
 *
 * <p>{@code entry_criteria} and {@code exit_criteria} are {@code JSONB} holding an array of
 * structured predicates ({@code {"metric":"pass_rate","op":">=","value":95}}), so the plan page can
 * <em>evaluate</em> them rather than display prose nobody can check (plan §6p). They are mapped as
 * {@code String} for the reason {@code repository.TestCaseVersion} gives for its snapshot: the
 * column type still checks well-formedness at insert, and nothing in this module needs to walk into
 * one — {@link PlanCriteria} parses them at the edge, once, into the wire shape.
 *
 * <p>Null is an ordinary value for both. A plan with no exit criteria has not failed to declare
 * them; it has not reached the point of declaring them, and rendering {@code []} says the same
 * thing without claiming the question was asked.
 */
@Entity
@Table(name = "test_plan")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestPlan {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** {@code TP-1} — sequential per COMPANY and immutable (§6t). */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "key_seq", nullable = false, updatable = false)
    private long keySeq;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PlanStatus status;

    @Column(name = "milestone_name")
    private String milestoneName;

    /**
     * A {@code DATE}, not a timestamp, and mapped as one: a milestone is a day everybody in the
     * project agrees on, and giving it an instant makes it a different day either side of a time
     * zone — the whole point of the field being the shared deadline.
     */
    @Column(name = "milestone_date")
    private LocalDate milestoneDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_criteria")
    private String entryCriteria;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exit_criteria")
    private String exitCriteria;

    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo;

    /**
     * What this plan says <strong>will</strong> be tested, as prose (§97 D6, {@code V32}).
     *
     * <p><strong>Not the plan's scope items.</strong> {@code plan_scope_item} has meant "which cases
     * the plan covers" since {@code V1}, and this is the document section a test plan calls its
     * Scope — two meanings of one word on one aggregate, which is why the column, the contract field
     * and this javadoc each name the other.
     *
     * <p>Nullable, and null is not the empty string: nobody has written one, as against somebody
     * having cleared it. It is deliberately <em>not</em> in {@link PlanSnapshot} — a plan version
     * pins what will be <em>run</em> (§6j), so editing the prose writes no version, exactly as a
     * rename does not.
     */
    @Column(name = "scope_text")
    private String scopeText;

    /**
     * What is deliberately not tested here.
     *
     * <p>Its whole value is being explicit: an absent out-of-scope section reads as an oversight to
     * the person signing the release off, which is why it is a field of its own rather than a
     * paragraph somebody may or may not add to {@link #scopeText}.
     */
    @Column(name = "out_of_scope_text")
    private String outOfScopeText;

    @Column(name = "deleted_at")
    private java.time.Instant deletedAt;

    /**
     * Optimistic lock — <strong>{@code @Version} was missing until the edit endpoint needed it, and
     * that is a defect this slice found rather than a field it added</strong>.
     *
     * <p>The column has been mapped since M2 slice 2 and {@code lockVersion} has been a
     * <em>required</em> property of the contract's {@code TestPlan} for just as long, so every plan
     * this API has ever served carried one — and without this annotation Hibernate never incremented
     * it, so the value was {@code 0} on every plan for ever. An {@code If-Match} check against it
     * could not fail: the client would send 0, the row would hold 0, and the precondition would pass
     * over any number of intervening saves. The endpoint would have shipped with a concurrency
     * guard that was structurally incapable of firing, and no test asserting "a stale header is
     * refused" would have gone red, because there is no such thing as a stale 0.
     *
     * <p>{@code TestCase}, {@code Suite}, {@code DocPage}, {@code Artifact}, {@code Project},
     * {@code Team}, {@code Company}, {@code AppUser}, {@code CompanyMembership},
     * {@code MembershipInvitation} and {@code TestExecution} all had it. <strong>{@code TestRun} and
     * {@code work.WorkItem} still do not</strong> — found by the same sweep, deliberately left, and
     * recorded in the handoff: neither serves {@code lockVersion} on the wire and nothing sends an
     * {@code If-Match} for either, so both are latent rather than live. Adding it to {@code TestRun}
     * is specifically <em>not</em> a free repair: {@code TestRun.begin} touches the run on every
     * result recorded, so two testers working through the same run concurrently would start
     * colliding on the product's most-used write.
     */
    @jakarta.persistence.Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;

    /** Mapped in §73 with the first writer that changes a plan after it was created. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Opaque, write-once, never read as data — see {@code V14}. */
    @Column(name = "created_idempotency_key", updatable = false)
    private String createdIdempotencyKey;

    /** JPA only. */
    protected TestPlan() {
    }

    /**
     * A new plan, {@code DRAFT} at v1.
     *
     * <p><strong>Not {@code APPROVED}.</strong> The status ladder is DRAFT → IN_REVIEW → APPROVED
     * (V32, M7) and {@code plan:review} / {@code plan:approve} are separate permissions held by
     * fewer roles (roles spec §A4). A create that landed past DRAFT would let a Test Engineer
     * grant their own plan the approval the catalogue says they may not — the same argument
     * {@code repository.TestCase.applyEdit} makes for leaving {@code lifecycleStatus} off an edit,
     * and {@code TestPlanInput} agrees by having no status field.
     *
     * <p>{@code companyId} comes from {@link CompanyContext}, never from the caller (ERD §0.1
     * point 2).
     */
    static TestPlan create(
            UUID projectId,
            String key,
            long keySeq,
            PlanEdit edit,
            String entryCriteriaJson,
            String exitCriteriaJson,
            UUID creatorId,
            String idempotencyKey,
            java.time.Instant now) {

        TestPlan created = new TestPlan();
        created.id = UUID.randomUUID();
        created.companyId = com.tcms.tenancy.CompanyContext.require();
        created.projectId = projectId;
        created.key = key;
        created.keySeq = keySeq;
        created.name = edit.name();
        created.description = edit.description();
        created.status = PlanStatus.DRAFT;
        created.milestoneName = edit.milestoneName();
        created.milestoneDate = edit.milestoneDate();
        // ~~Both null~~ → PASSED IN since §129, and the distinction they encode is unchanged: a
        // plan that has not reached the point of declaring criteria has not declared an empty set of
        // them, and PlanCriteria renders null as [] for the wire anyway. Writing "[]" here would
        // make "nobody has decided" and "there are deliberately none" indistinguishable in the
        // column plan:edit has to tell apart.
        //
        // What changed is WHERE the null comes from. It was hard-coded here, which made the entity
        // hold a policy that belongs to the validator — and the clone is what proved it: a clone
        // carries the source's criteria, so a create that could not accept any was a create no
        // clone could route through, and the alternative was a second orchestration writing the
        // same row a different way. PlanEdit#validateCreate still produces null for both, so the
        // ordinary create path is byte-for-byte what it was.
        created.entryCriteria = entryCriteriaJson;
        created.exitCriteria = exitCriteriaJson;
        // Null on every ordinary create for the same reason — validateCreate does not populate
        // them, because a plan is named before anybody has written down what is out of scope.
        created.scopeText = edit.scopeText();
        created.outOfScopeText = edit.outOfScopeText();
        created.currentVersionNo = 1;
        created.createdAt = now;
        created.createdBy = creatorId;
        created.updatedAt = now;
        created.updatedBy = creatorId;
        created.createdIdempotencyKey = idempotencyKey;
        return created;
    }

    /**
     * Moves the plan along its ladder — the transition {@link PlanLifecycle} has already ruled legal.
     *
     * <p>Nothing is re-decided here, for the reason {@code TestRun.applyStatus} gives: a second copy
     * of the transition table inside the entity is a second answer to "may this happen", and the two
     * are consulted in different orders by the callers that exist a year from now.
     *
     * <p>~~{@code closedAt} is derived from the target, in both directions, so reopening clears
     * it.~~ → the column went with {@code CLOSED} in {@code V32} (M7); when a release ended is
     * answered by its runs and by {@code audit_event}. {@code currentVersionNo} is deliberately
     * <em>not</em> bumped: §6j defines
     * a plan version as its scope — suites, cases, milestone, criteria — and a status change alters
     * none of them. Recording one here would fill the history with rows whose snapshots are
     * identical, which is exactly what {@code SuiteVersions} refuses for a rename.
     */
    void applyStatus(PlanStatus target, UUID actorId, java.time.Instant now) {
        status = target;
        updatedAt = now;
        updatedBy = actorId;
    }

    /**
     * Applies an authored edit — <strong>the first writer {@code entry_criteria} and
     * {@code exit_criteria} have ever had</strong>.
     *
     * <p>Both columns have been in {@code V1} from the beginning, both were set to null by
     * {@link #create} and nothing else ever touched them, so {@code PlanCriteria.evaluate} and the
     * exit-criteria panel §46 built on top of it have rendered an empty list on every real instance
     * for their whole lives. The javadoc above said this was coming; this is it.
     *
     * <p><strong>A null criteria list means "leave the column alone", not "clear it"</strong> — the
     * three-way reading {@link PlanUpdateRequest} sets out, and the reason {@link #create}'s comment
     * insisted on storing null rather than {@code "[]"}: it is what keeps <em>nobody has decided</em>
     * distinguishable from <em>there are deliberately none</em>, in a column whose two states
     * otherwise look identical on the wire.
     *
     * <p>{@code status} is not here. It has its own writer, its own two permissions and its own
     * ladder ({@link PlanLifecycle}), and a status an ordinary save could set is an approval anybody
     * grants themselves — {@code repository.TestCase.applyEdit}'s call, one aggregate over.
     *
     * <p>The version counter is stamped separately by {@link #stampVersion}, because whether this
     * edit <em>is</em> a new version is {@link PlanVersions}' question and not the entity's: a
     * rename changes these fields and changes no version.
     *
     * @param entryCriteriaJson the serialized column value, or null to leave the stored one
     * @param exitCriteriaJson  likewise
     */
    void applyEdit(
            PlanEdit edit,
            String entryCriteriaJson,
            String exitCriteriaJson,
            UUID actorId,
            java.time.Instant now) {

        name = edit.name();
        description = edit.description();
        milestoneName = edit.milestoneName();
        milestoneDate = edit.milestoneDate();
        // Assigned unconditionally, unlike the criteria below, because they are SCALARS: null means
        // "clear it" (PlanUpdateRequest's convention) and the only client submits every field it
        // renders on every save. The lists get the opposite treatment two lines down, and the split
        // is the whole reason that convention exists — a list has an empty spelling of its own.
        scopeText = edit.scopeText();
        outOfScopeText = edit.outOfScopeText();
        if (edit.entryCriteria() != null) {
            entryCriteria = entryCriteriaJson;
        }
        if (edit.exitCriteria() != null) {
            exitCriteria = exitCriteriaJson;
        }
        updatedAt = now;
        updatedBy = actorId;
    }

    /**
     * Advances the version counter, once {@link PlanVersions} has decided the edit changed the scope.
     *
     * <p>Separate from {@link #applyEdit} so the entity holds no opinion about what counts as a new
     * version, and called <em>before</em> the snapshot is taken so the row and the version row agree
     * about which number this is.
     */
    void stampVersion(int versionNo) {
        currentVersionNo = versionNo;
    }

    /**
     * Removes the plan from every read, without removing the row.
     *
     * <p>Soft, like a case and a suite: {@code plan_scope_item} cascades on a hard delete and
     * {@code test_run.plan_id} references this row, so a physical delete would take a plan's scope
     * with it and leave the runs that executed it pointing at nothing. {@link PlanDeleteService}
     * refuses the delete outright while any live run names the plan, so what this method removes is
     * always a plan nothing has been executed against.
     */
    void softDelete(UUID actorId, java.time.Instant now) {
        deletedAt = now;
        updatedAt = now;
        updatedBy = actorId;
    }

    UUID getId() {
        return id;
    }

    UUID getProjectId() {
        return projectId;
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

    String getDescription() {
        return description;
    }

    PlanStatus getStatus() {
        return status;
    }

    String getMilestoneName() {
        return milestoneName;
    }

    LocalDate getMilestoneDate() {
        return milestoneDate;
    }

    String getEntryCriteria() {
        return entryCriteria;
    }

    String getExitCriteria() {
        return exitCriteria;
    }

    int getCurrentVersionNo() {
        return currentVersionNo;
    }

    String getScopeText() {
        return scopeText;
    }

    String getOutOfScopeText() {
        return outOfScopeText;
    }

    java.time.Instant getDeletedAt() {
        return deletedAt;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
