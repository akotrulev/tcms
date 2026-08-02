package com.tcms.project;

import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adding to, renaming and retiring a project's classification vocabulary — <strong>the writer
 * {@code classification_value} has waited for since {@code V1}.</strong>
 *
 * <p>{@code tcms_seed_project_defaults} creates the Feature dimension and, deliberately, no values
 * in it: <em>"inventing a client's feature taxonomy for them is how a controlled vocabulary gets
 * worked around"</em>. Handoff §68 then built the case editor's tree picker against that dimension
 * and recorded what was left — the tree was <strong>usable and not fillable</strong>, so a freshly
 * provisioned project offered an empty Feature picker and no path to a non-empty one short of an
 * operator with a psql prompt. That is §34's shape ("provisioned company plus hand-written SQL")
 * one vocabulary over, and this class closes it.
 *
 * <h2>The gate is decided by the row, and the two answers differ in SCOPE as well as in key</h2>
 *
 * <p>A dimension whose {@code project_id} is set — Feature, and only Feature today — is the
 * project's own, and editing it needs {@code project:settings} on <em>this</em> project: roles spec,
 * Project table, Test Lead and above. A dimension whose {@code project_id} is null is the company's,
 * shared by every project in it, and editing it needs {@code company:vocabulary} — Company table,
 * Company Admin and above, and a <strong>COMPANY-scope</strong> question rather than a project one.
 * Asking a company key at project scope is the "held anywhere in this company" shortcut roles spec
 * §A3 records rejecting, so {@link ProjectService.RequiredPermission} carries the scope and
 * {@link ProjectService#writeGrantedOnTarget} asks the matching question.
 *
 * <p>The consequence is the point of the split rather than an artefact of it: a Test Lead may build
 * out their own product's Feature tree all day and cannot add a value to Test Type, because
 * "Performance" has to mean the same thing in every one of that client's projects or the type ×
 * level coverage matrix stops comparing anything (plan §6d).
 *
 * <h2>What {@code locked} means, settled — three documents disagreed</h2>
 *
 * <p>{@code V2}'s seed says <em>"a client editing them is fine"</em>;
 * {@link ClassificationDimensionView}'s javadoc said <em>"the value list is not editable by the
 * client"</em>; the roles spec grants {@code company:vocabulary} to Instance and Company Admin
 * covering, in its own words, <em>"Test Type / Level / Phase values"</em>. Two of the three say
 * editable, and the third makes a permission that exists, is seeded, and covers nothing — the
 * {@code case:approve} shape §62 found, where a granted key had no consumer in Java at all.
 *
 * <p>So {@code locked} is <strong>not</strong> read-only. It means the dimension is the company's
 * and cannot be overridden per project, and the operational reading — the one that now has a
 * consumer — is <em>which permission, at which scope, may edit its values</em>. The view's javadoc
 * is corrected rather than quietly re-interpreted.
 *
 * <h2>What this class refuses, and why refusing is the feature</h2>
 *
 * <ol>
 *   <li><strong>Moving a value.</strong> {@link ClassificationValueEdit#validateEdit} rejects a
 *       payload whose {@code parentId} differs from the stored one, for
 *       {@code SuiteWriteService}'s reason plus one of its own — see that method.</li>
 *   <li><strong>Retiring a value that still has live children.</strong> 409 naming the count. The
 *       cascade is not built here: retiring a subtree unclassifies every case beneath it in one
 *       request, with no preview of what it is about to touch, and every one of those cases loses
 *       an assignment <em>nobody can restore through the API</em> — a retired value is served to
 *       no client and therefore cannot be resubmitted (§68's bound). {@code suite:delete}'s
 *       non-empty refusal is the same decision about the same kind of tree.</li>
 * </ol>
 *
 * <p>What it does <strong>not</strong> refuse is a value that is in use by cases. Being in use is
 * precisely what the soft delete exists for: the row survives, so an execution that ran under it
 * still resolves what it was classified as, and the vocabulary stops offering it. Refusing here
 * would make a vocabulary that can only ever grow.
 */
@Service
public class ClassificationValueWriteService {

    /** Roles spec, Project table. Test Lead and above; the project's own Feature tree. */
    private static final String PROJECT_SETTINGS = "project:settings";

    /**
     * Roles spec, Company table — "Test Type / Level / Phase values". COMPANY scope, so Company
     * Admin and above. <strong>Its first consumer in Java</strong>, seeded and granted since M0 and
     * never once asked, which is §62's {@code case:approve} again and §64's {@code library:manage}
     * before it.
     */
    private static final String COMPANY_VOCABULARY = "company:vocabulary";

    private static final String AUDIT_TARGET = "classification_value";

    private final ProjectService projects;
    private final ClassificationDimensionRepository dimensions;
    private final ClassificationValueRepository values;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ClassificationValueWriteService(
            ProjectService projects,
            ClassificationDimensionRepository dimensions,
            ClassificationValueRepository values,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.dimensions = dimensions;
        this.values = values;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** No such project, no such dimension here, or no such live value. Always bodiless 404. */
        record NotFound() implements Outcome {
        }

        record Forbidden() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The value still has live children — 409, and the count is the whole message. */
        record HasChildren(long children) implements Outcome {
        }

        record Created(ClassificationValueView view) implements Outcome {
        }

        record Updated(ClassificationValueView view) implements Outcome {
        }

        record Retired() implements Outcome {
        }
    }

    /**
     * Adds a value to one of this project's dimensions.
     *
     * <p>The dimension is resolved <em>inside the gate</em> and an unknown one answers 404, which is
     * {@link ProjectService#writeGrantedOnTarget}'s contract: the permission to ask is a property of
     * the dimension, so a caller whose dimension does not exist cannot be told 403 without the
     * server inventing which permission they failed.
     */
    @Transactional
    public Outcome create(UUID userId, UUID projectId, ClassificationValueInput request) {
        UUID companyId = CompanyContext.require();

        return gated(projects.<ClassificationDimension, Outcome>writeGrantedOnTarget(
                userId,
                projectId,
                project -> dimensionOf(project.getId(), request.dimensionId()),
                ClassificationValueWriteService::permissionFor,
                (project, dimension) ->
                        applyCreate(companyId, userId, project, dimension, request)));
    }

    private Outcome applyCreate(
            UUID companyId,
            UUID userId,
            Project project,
            ClassificationDimension dimension,
            ClassificationValueInput request) {

        List<ClassificationValue> live = liveVocabularyOf(project.getId());

        ClassificationValueEdit.Outcome validated =
                ClassificationValueEdit.validateCreate(request, dimension.getId(), live);
        if (validated instanceof ClassificationValueEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        ClassificationValueEdit edit = ((ClassificationValueEdit.Outcome.Valid) validated).edit();

        // Resolved from the list already fetched rather than by a second query — SuiteWriteService's
        // arrangement. Null for a root, which is what ClassificationValue.create expects.
        String parentPath = null;
        int position = 0;
        for (ClassificationValue sibling : live) {
            if (!sibling.getDimensionId().equals(dimension.getId())) {
                continue;
            }
            if (sibling.getId().equals(edit.parentId())) {
                parentPath = sibling.getPath();
            }
            if (Objects.equals(sibling.getParentId(), edit.parentId())) {
                // Append: one past the highest position among the new value's own siblings. The
                // read orders by position then name, so this is what puts a new value at the end of
                // its level instead of alphabetically inside a hand-ordered vocabulary. Retired
                // siblings are not in this list and their positions are therefore reusable, which
                // is harmless — position is an ordering, not an identity.
                position = Math.max(position, sibling.getPosition() + 1);
            }
        }

        Instant now = clock.instant();
        ClassificationValue created = ClassificationValue.create(
                companyId,
                dimension.getId(),
                edit.parentId(),
                parentPath,
                edit.name(),
                edit.color(),
                position);

        // Flushed here so V20's unique index fails at its cause — inside this method, where the
        // race handler can still turn it into the field error it would have been a moment earlier —
        // rather than on commit under whatever ran next.
        values.saveAndFlush(created);
        audit(project.getId(), userId, "classification_value.created", created, now);

        return new Outcome.Created(ClassificationValueView.of(created));
    }

    /** Renames and recolours a value. Refuses a move and a change of dimension — see the edit. */
    @Transactional
    public Outcome update(
            UUID userId, UUID projectId, UUID valueId, ClassificationValueInput request) {

        return gated(projects.<ClassificationValue, Outcome>writeGrantedOnTarget(
                userId,
                projectId,
                project -> liveValueOf(project.getId(), valueId),
                value -> permissionFor(dimensionOfValue(value)),
                (project, value) -> {
                    List<ClassificationValue> live = liveVocabularyOf(project.getId());

                    ClassificationValueEdit.Outcome validated =
                            ClassificationValueEdit.validateEdit(request, value, live);
                    if (validated instanceof ClassificationValueEdit.Outcome.Invalid invalid) {
                        return new Outcome.Invalid(invalid.errors());
                    }
                    ClassificationValueEdit edit =
                            ((ClassificationValueEdit.Outcome.Valid) validated).edit();

                    Instant now = clock.instant();
                    value.rename(edit.name(), edit.color());
                    values.saveAndFlush(value);

                    audit(project.getId(), userId, "classification_value.renamed", value, now);

                    return new Outcome.Updated(ClassificationValueView.of(value));
                }));
    }

    /** Retires a value — a soft delete, refused while it still has live children. */
    @Transactional
    public Outcome retire(UUID userId, UUID projectId, UUID valueId) {
        return gated(projects.<ClassificationValue, Outcome>writeGrantedOnTarget(
                userId,
                projectId,
                project -> liveValueOf(project.getId(), valueId),
                value -> permissionFor(dimensionOfValue(value)),
                (project, value) -> {
                    long children = liveVocabularyOf(project.getId()).stream()
                            .filter(candidate -> value.getId().equals(candidate.getParentId()))
                            .count();
                    if (children > 0) {
                        return new Outcome.HasChildren(children);
                    }

                    Instant now = clock.instant();
                    value.retire(now);
                    values.saveAndFlush(value);

                    audit(project.getId(), userId, "classification_value.retired", value, now);

                    return new Outcome.Retired();
                }));
    }

    /**
     * Which permission a dimension's values need, and at which scope — <strong>the one place that
     * decides it</strong>, so the create, the edit and the retire cannot drift apart.
     *
     * <p>Reads {@code project_id}, not {@code locked} and not {@code key}. The three seeded
     * company-level dimensions happen to be exactly the locked ones and exactly the ones named in
     * the roles spec's sentence, so all three tests agree today — and only one of them is the
     * <em>reason</em>. A key list would need editing the day a company gains a fourth shared axis;
     * {@code locked} is a flag someone may one day set on a project-level dimension for an unrelated
     * reason, and it would silently move that dimension's gate to a permission Test Leads do not
     * hold.
     */
    private static ProjectService.RequiredPermission permissionFor(ClassificationDimension dimension) {
        return dimension.getProjectId() == null
                ? ProjectService.RequiredPermission.inCompany(COMPANY_VOCABULARY)
                : ProjectService.RequiredPermission.onProject(PROJECT_SETTINGS);
    }

    /**
     * One dimension of this project's set, or empty.
     *
     * <p>Resolved through {@link ClassificationDimensionRepository#findForProject} rather than by
     * {@code findById}, and the difference is the security property: {@code findById} would accept
     * <em>another project's</em> Feature dimension, which is in the caller's own company and so
     * passes every RLS and cross-company check in the suite. The caller would then extend a
     * taxonomy belonging to a project they may hold no grant on, gated on
     * {@code project:settings} for the project in the path.
     */
    private Optional<ClassificationDimension> dimensionOf(UUID projectId, UUID dimensionId) {
        if (dimensionId == null) {
            return Optional.empty();
        }
        return dimensions.findForProject(projectId).stream()
                .filter(dimension -> dimension.getId().equals(dimensionId))
                .findFirst();
    }

    /** One live value belonging to a dimension of this project, or empty — same scoping rule. */
    private Optional<ClassificationValue> liveValueOf(UUID projectId, UUID valueId) {
        return liveVocabularyOf(projectId).stream()
                .filter(value -> value.getId().equals(valueId))
                .findFirst();
    }

    /**
     * Every live value of every dimension that applies to this project.
     *
     * <p>The same two queries {@code ProjectConfigurationService} makes, in the same order, and
     * deliberately the same {@code findLiveForDimensions} — one implementation of "what this project
     * offers", so the set a write is validated against cannot differ from the set the client was
     * served. Two readings of that is §68's defect in the other direction: there a save could drop a
     * value the client was never shown, here a save could accept a parent the client cannot see.
     */
    private List<ClassificationValue> liveVocabularyOf(UUID projectId) {
        List<UUID> dimensionIds = new ArrayList<>();
        for (ClassificationDimension dimension : dimensions.findForProject(projectId)) {
            dimensionIds.add(dimension.getId());
        }
        if (dimensionIds.isEmpty()) {
            // An empty collection bound into SQL `IN ()` is a syntax error, so the query must not
            // run — ProjectConfigurationService's guard, same reason.
            return List.of();
        }
        return values.findLiveForDimensions(dimensionIds);
    }

    /**
     * The dimension a value belongs to, for the gate.
     *
     * <p>{@code findById} is safe <em>here</em>, unlike in {@link #dimensionOf}, and the difference
     * is worth stating rather than leaving to the reader: this id comes from a row that has already
     * been scoped to this project's vocabulary by {@link #liveValueOf}, not from the request. It
     * cannot name another project's dimension because no value of another project's dimension can
     * reach this method.
     */
    private ClassificationDimension dimensionOfValue(ClassificationValue value) {
        return dimensions.findById(value.getDimensionId())
                // Unreachable: dimension_id is NOT NULL with a foreign key, and the value was read
                // through findForProject's own dimension list one call earlier. Thrown rather than
                // defaulted, because defaulting would have to invent a permission.
                .orElseThrow(() -> new IllegalStateException(
                        "Value " + value.getId() + " names dimension "
                                + value.getDimensionId() + ", which does not exist"));
    }

    /** Collapses the gate's three states onto this class's own — {@code SuiteWriteService}'s. */
    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private void audit(
            UUID projectId, UUID userId, String action, ClassificationValue value, Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action,
                AUDIT_TARGET,
                List.of(value.getId()),
                // The name, and the dimension it sits on. The name because a retired value's is
                // otherwise only recoverable by reading a soft-deleted row, which is what an audit
                // trail exists to avoid needing; the dimension because "Regression" alone does not
                // say which axis was edited. Escaped through AuditEvent's own escaper — a second
                // copy diverges on exactly the input neither was tested with.
                "{\"dimensionId\":\"" + value.getDimensionId() + "\",\"name\":"
                        + AuditEvent.jsonString(value.getName()) + "}",
                now));
    }
}
