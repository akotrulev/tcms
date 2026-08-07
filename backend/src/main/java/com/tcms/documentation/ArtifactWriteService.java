package com.tcms.documentation;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.project.RowScopeGate;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registering an artifact — <strong>the write that makes the register honest.</strong>
 *
 * <p>Until this class existed the endpoint served a table nothing could fill, so every provisioned
 * company's register was permanently empty and the screen rendering it could only ever show its
 * empty state. Four sessions (§54, §55, §56, §57) recorded that as the reason not to ship reads
 * alone, and §55 named the sharper version of it: the only way to get a row in was an operator with
 * a psql prompt.
 *
 * <h2>The gate is {@code artifact:edit}, and it is scope-qualified</h2>
 *
 * <p>Unlike the read — which uses the project gate because {@code artifact:read}'s distribution is
 * identical to {@code project:read}'s — this permission genuinely differs between roles, and differs
 * <em>within</em> a role by the row's scope. The roles spec grants it outright to Instance Admin,
 * Company Admin and Test Lead, and <strong>plan-scope-only</strong> to a Test Engineer, because a
 * project-scope artifact is inherited read-only by every plan (plan §6n): editing one edits every
 * cycle at once, which is a Lead-level act, while curating your own cycle's sign-off is not.
 *
 * <p>V19 is what made that expressible — {@code role_permission.qualifier} accepted only
 * {@code ALL} and {@code OWN} before it — and {@link ProjectService#writeGrantedByRowScope} is how
 * the verdict reaches the row. The scope in the <em>request</em> is the scope the row would have, so
 * gating a create on it is the only point at which the restriction can be applied at all.
 *
 * <p><strong>The honest cost</strong>, which the gate's own header states: a callback that never
 * calls {@link RowScopeGate#mayActOn} silently gets the unqualified behaviour. So the refusal is
 * asserted from outside — a Test Engineer creating a project-scope artifact — rather than trusted
 * from the shape of this code.
 */
@Service
public class ArtifactWriteService {

    private static final String PERMISSION = "artifact:edit";

    /**
     * Roles spec §A: Instance Admin, Company Admin and Test Lead — and <strong>not</strong> Test
     * Engineer, who holds {@link #PERMISSION} at plan scope and this at no scope at all. That one
     * difference is the reason this constant exists rather than the delete reusing the create's.
     */
    private static final String DELETE_PERMISSION = "artifact:delete";
    private static final String AUDIT_TARGET = "ARTIFACT";

    private final ProjectService projects;
    private final ArtifactRepository artifacts;
    private final DocPageRepository pages;
    private final PlanFacts plans;
    private final ArtifactPeople people;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ArtifactWriteService(
            ProjectService projects,
            ArtifactRepository artifacts,
            DocPageRepository pages,
            PlanFacts plans,
            ArtifactPeople people,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.artifacts = artifacts;
        this.pages = pages;
        this.plans = plans;
        this.people = people;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so every controller mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code artifact:edit} is not held here at all. Bodiless 403. */
        record Forbidden() implements Outcome {
        }

        /**
         * Readable, {@code artifact:edit} <em>is</em> held — and not for the scope asked for.
         *
         * <p>A separate outcome from {@link Forbidden} because the client can act on it and cannot
         * act on the other. §31's rule is that an action the server will refuse renders disabled
         * with a reason rather than inviting a 403, and the SPA cannot honour that here: its
         * permission model is keys-only, so {@code artifact:edit} reads as held and the control
         * enables. Given that, the next best thing is a refusal that says which field was the
         * problem, so the form can put the sentence on the scope selector instead of showing a bare
         * "forbidden" for a form the user is one radio button away from submitting successfully.
         *
         * <p>This discloses nothing §6t protects. The caller already reads the project and already
         * holds the permission; what they learn is the shape of their own grant.
         */
        record ForbiddenAtScope() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A new artifact. 201. */
        record Created(ArtifactView view) implements Outcome {
        }

        /**
         * The register refuses to lose the row — a <strong>409</strong>, not a 403.
         *
         * <p>The caller's permissions are not the problem and asking an administrator would not
         * help: it is this artifact that may not be removed, and the message says which of the two
         * reasons applies and what to do instead. Same call {@code RunLifecycle} makes for a closed
         * run and {@code SuiteWriteService} for a suite that still holds cases.
         *
         * @param message written for the person looking at the register, naming the way forward
         */
        record Undeletable(String message) implements Outcome {
        }

        /** Gone. 204. */
        record Deleted() implements Outcome {
        }
    }

    /**
     * Registers a reference.
     *
     * <p>Validation runs <em>before</em> the scope gate is consulted but <em>after</em> the project
     * gate, and the order is deliberate. A caller who cannot see the project learns nothing about
     * their payload (§6t). A caller who can see it gets their field errors even when the scope they
     * asked for is one they may not write — otherwise a Test Engineer fixing a malformed URL on a
     * project-scope form would get a 403, correct it, and get the same 403, with no way to discover
     * that the scope was the problem.
     */
    @Transactional
    public Outcome create(UUID userId, UUID projectId, ArtifactInput input) {
        return gated(projects.writeGrantedByRowScope(userId, projectId, PERMISSION,
                (project, rowScope) -> {
                    Map<String, List<String>> errors = validate(project.getId(), input);
                    if (!errors.isEmpty()) {
                        return new Outcome.Invalid(errors);
                    }
                    if (!rowScope.mayActOn(input.scope() == ArtifactScope.PLAN)) {
                        // The qualifier, applied. A Test Engineer reaching for PROJECT scope lands
                        // here; the same caller with PLAN scope does not.
                        return new Outcome.ForbiddenAtScope();
                    }

                    Instant now = clock.instant();
                    Artifact artifact = artifacts.save(Artifact.register(
                            project.getId(),
                            input.scope(),
                            input.scope() == ArtifactScope.PLAN ? input.planId() : null,
                            input.kind(),
                            input.category().trim(),
                            input.title().trim(),
                            trimmedOrNull(input.description()),
                            input.kind() == ArtifactKind.DOC_PAGE_REF ? input.docPageId() : null,
                            input.kind() == ArtifactKind.EXTERNAL_URL ? input.url().trim() : null,
                            userId,
                            now));

                    audit(project.getId(), userId, artifact, now);

                    Map<UUID, UserRefView> authors =
                            people.resolve(CompanyContext.require(), List.of(userId));
                    // `inherited` is false: a row just created at either scope is this register's
                    // own. It only becomes inherited when read from inside a plan.
                    return new Outcome.Created(ArtifactView.of(artifact, false, authors));
                }));
    }

    /**
     * Unregisters an artifact — <strong>{@code artifact:delete}'s first consumer</strong>.
     *
     * <p>The key has been in {@code V3}'s catalogue since M0 and nothing had ever asked for it, so
     * there was no delete endpoint at all: the sixth instance of the shape §116, §117, §120, §121
     * and §122 each closed one of, and the one the scope assessment called a cheap win in two
     * consecutive rounds without anybody taking it (plan §8 #53).
     *
     * <h2>The gate is {@code artifact:delete} and NOT {@code artifact:edit}</h2>
     *
     * <p>This is the whole point of wiring it. A Test Engineer holds {@code artifact:edit} at plan
     * scope, so they may register a cycle's documents; {@code V3} withholds {@code artifact:delete}
     * from them entirely. Reusing {@link #PERMISSION} here would have compiled, passed review, and
     * quietly given every engineer the ability to remove a lead's sign-off record from the
     * register — the roles spec's creative-versus-destructive line, in the one module where the
     * artefact being destroyed <em>is</em> the audit trail.
     *
     * <p><strong>Unqualified, unlike the create.</strong> {@link ProjectService#writeGranted} rather
     * than {@code writeGrantedByRowScope}, because {@code V19}'s own comment says so: "artifact:read
     * is unqualified for everyone who holds it and artifact:delete" likewise. Only the three roles
     * that hold it at all hold it everywhere, so there is no scope for a qualifier to narrow.
     *
     * <h2>Delete is for a mis-registration; everything else supersedes or archives</h2>
     *
     * <p>Plan §6n makes the register's value the audit trail, and both non-current states keep the
     * row readable. Deletion answers a narrower question — <em>"that link should never have been in
     * here"</em> — where keeping the row leaves the register asserting something untrue about the
     * project. The two refusals below are what keep it from answering any wider one.
     */
    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID artifactId) {
        return gated(projects.writeGranted(userId, projectId, DELETE_PERMISSION, project -> {
            Optional<Artifact> found = artifacts.findInProject(project.getId(), artifactId);
            if (found.isEmpty()) {
                // Absent, another project's, and another company's are one bodiless 404 (§6t) —
                // and the caller holds the strongest key in this module, which does not entitle
                // them to learn that a sibling project has an artifact by this id.
                return new Outcome.NotFound();
            }
            Artifact artifact = found.get();

            if (artifact.isWriteOnce()) {
                return new Outcome.Undeletable(
                        "This is a sign-off record, and the register keeps those for the audit "
                                + "trail. Supersede it with a correction instead.");
            }
            if (artifacts.isSupersedingSomething(artifactId)) {
                return new Outcome.Undeletable(
                        "Another artifact was superseded by this one. Deleting it would leave "
                                + "that record pointing at nothing.");
            }

            Instant now = clock.instant();
            // The audit row before the delete, and inside the same transaction: it names an id
            // that is about to stop existing, which is the point — "who removed the strategy
            // document" is unanswerable from a register the row is no longer in. A rollback takes
            // both, so the trail can never record a removal that did not happen.
            auditRemoval(project.getId(), userId, artifact, now);
            artifacts.delete(artifact);
            return new Outcome.Deleted();
        }));
    }

    /**
     * Every cross-field rule the schema cannot express, reported together.
     *
     * <p>All of them at once rather than the first: a form that surfaces one error per round trip
     * makes the user discover their payload by bisection.
     */
    private Map<String, List<String>> validate(UUID projectId, ArtifactInput input) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (input.scope() == null) {
            errors.put("scope", List.of("Choose whether this belongs to the project or a plan."));
        }
        if (input.kind() == null) {
            errors.put("kind", List.of("Choose what kind of artifact this is."));
        }
        if (isBlank(input.title())) {
            errors.put("title", List.of("Give the artifact a title."));
        } else if (input.title().trim().length() > Artifact.MAX_TITLE) {
            errors.put("title", List.of("Keep the title to " + Artifact.MAX_TITLE + " characters."));
        }
        if (isBlank(input.category())) {
            errors.put("category", List.of("Give the artifact a category."));
        } else if (input.category().trim().length() > Artifact.MAX_CATEGORY) {
            errors.put("category",
                    List.of("Keep the category to " + Artifact.MAX_CATEGORY + " characters."));
        }
        if (input.description() != null
                && input.description().trim().length() > Artifact.MAX_DESCRIPTION) {
            errors.put("description",
                    List.of("Keep the description to " + Artifact.MAX_DESCRIPTION + " characters."));
        }

        validateScope(projectId, input, errors);
        validateKind(projectId, input, errors);
        return errors;
    }

    /**
     * {@code PLAN} needs a plan of <em>this</em> project; {@code PROJECT} needs none.
     *
     * <p>A plan of another project is refused with the same message as a plan that does not exist,
     * and that is the §6t rule rather than laziness: a distinct message would let a caller probe
     * which plan ids are real in a project they cannot see.
     */
    private void validateScope(
            UUID projectId, ArtifactInput input, Map<String, List<String>> errors) {

        if (input.scope() == ArtifactScope.PLAN) {
            if (input.planId() == null) {
                errors.put("planId", List.of("Choose the plan this artifact belongs to."));
            } else if (!plans.planExistsInProject(projectId, input.planId())) {
                errors.put("planId", List.of("That plan is not in this project."));
            }
        } else if (input.scope() == ArtifactScope.PROJECT && input.planId() != null) {
            // Not ignored. A client sending a plan id believes the artifact is scoped to it, and
            // storing a project-scope row while dropping the field is the silent disagreement §50
            // built two checks to catch.
            errors.put("planId",
                    List.of("A project-scope artifact belongs to every plan, so it takes no plan."));
        }
    }

    /** Each reference kind needs its own target, and neither may carry the other's. */
    private void validateKind(
            UUID projectId, ArtifactInput input, Map<String, List<String>> errors) {

        switch (input.kind()) {
            case DOC_PAGE_REF -> {
                if (input.docPageId() == null) {
                    errors.put("docPageId", List.of("Choose the page this artifact points to."));
                } else if (pages.findLiveInProject(projectId, input.docPageId()).isEmpty()) {
                    // Same message for "no such page", "a page of another project" and "a deleted
                    // page", for validateScope's reason.
                    errors.put("docPageId", List.of("That page is not in this project."));
                }
                if (input.url() != null) {
                    errors.put("url",
                            List.of("A documentation reference points at a page, not a URL."));
                }
            }
            case EXTERNAL_URL -> {
                if (isBlank(input.url())) {
                    errors.put("url", List.of("Give the link this artifact points to."));
                } else if (input.url().trim().length() > Artifact.MAX_URL) {
                    errors.put("url", List.of("Keep the link to " + Artifact.MAX_URL + " characters."));
                } else if (!isHttpUrl(input.url().trim())) {
                    errors.put("url", List.of("Links must start with http:// or https://."));
                }
                if (input.docPageId() != null) {
                    errors.put("docPageId",
                            List.of("An external link points at a URL, not a page."));
                }
            }
            // Declared on the response because the register must render a row of any kind; refused
            // here because this endpoint cannot honour them. See ArtifactKind.
            case FILE -> errors.put("kind", List.of(
                    "Uploading a file is not available yet — register the document as a link or a "
                            + "documentation page."));
            case GENERATED_REPORT -> errors.put("kind", List.of(
                    "Pinning a generated report is not available yet."));
            case null -> {
                // Already reported by validate(); nothing to add, and switching on null would throw.
            }
        }
    }

    /**
     * Whether a string is a URL this register may render as a link.
     *
     * <p>The scheme check is the whole point and it is a security control, not tidiness. The page
     * renders {@code EXTERNAL_URL} rows inside an anchor, so a {@code javascript:} or {@code data:}
     * value stored here is stored XSS wearing the product's own markup — and the register is exactly
     * the screen where a colleague's link is clicked without being read.
     *
     * <p>Allow-list rather than deny-list: the set of dangerous schemes is open-ended
     * ({@code vbscript:}, {@code blob:}, and whatever a browser adds next), while the set this
     * feature needs is two.
     */
    private static boolean isHttpUrl(String candidate) {
        try {
            String scheme = new URI(candidate).getScheme();
            if (scheme == null) {
                return false;
            }
            String normalized = scheme.toLowerCase(Locale.ROOT);
            return normalized.equals("http") || normalized.equals("https");
        } catch (URISyntaxException notAUri) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimmedOrNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /**
     * Collapses the gate's three states onto this class's own — one place rather than a switch per
     * write, so a fourth gate state cannot be handled in some of them.
     */
    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * The removal, recorded with enough of the row to be readable after the row is gone.
     *
     * <p>A target id alone would name something an auditor cannot look up, which is the one case
     * where the event body has to carry the content rather than a pointer to it. The URL is
     * included for exactly that reason and only for {@code EXTERNAL_URL}: "who took the compliance
     * link out of the register" is answerable only if the link is in the event.
     */
    private void auditRemoval(UUID projectId, UUID userId, Artifact artifact, Instant now) {
        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                "ARTIFACT_REMOVED",
                AUDIT_TARGET,
                List.of(artifact.getId()),
                "{\"title\":" + AuditEvent.jsonString(artifact.getTitle())
                        + ",\"scope\":\"" + artifact.getScope() + "\""
                        + ",\"kind\":\"" + artifact.getKind() + "\""
                        + ",\"status\":\"" + artifact.getStatus() + "\""
                        + ",\"category\":" + AuditEvent.jsonString(artifact.getCategory())
                        + (artifact.getExternalUrl() == null ? ""
                                : ",\"url\":" + AuditEvent.jsonString(artifact.getExternalUrl()))
                        + "}",
                now));
    }

    private void audit(UUID projectId, UUID userId, Artifact artifact, Instant now) {
        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                "ARTIFACT_REGISTERED",
                AUDIT_TARGET,
                List.of(artifact.getId()),
                // Scope and kind alongside the title, because those are what the act was about: an
                // audit reader asking "who put the project's strategy document here" is asking a
                // scope question, and a row saying only the title cannot answer it.
                "{\"title\":" + AuditEvent.jsonString(artifact.getTitle())
                        + ",\"scope\":\"" + artifact.getScope() + "\""
                        + ",\"kind\":\"" + artifact.getKind() + "\""
                        + ",\"category\":" + AuditEvent.jsonString(artifact.getCategory()) + "}",
                now));
    }
}
