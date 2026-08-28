package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.project.TrackerBindingDirectory;
import com.tcms.tenancy.CompanyContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Linking a failed result to a defect in somebody else's tracker, and unlinking it —
 * {@code defect_link}'s first writer and {@code defect:create}'s first consumer, both seeded in M0
 * and unused until {@code V62} (audit C5).
 *
 * <h2>The link hangs off the EXECUTION, and the table's other foreign key stays null</h2>
 *
 * <p>{@code defect_link} offers {@code execution_id} and {@code case_id} and both are nullable, so
 * "which end does a link attach to" was a real choice rather than a reading of the schema. It is
 * answered by the readers, all three of which are execution-scoped:
 * {@link TestExecutionRepository#findDefectLinks} selects {@code WHERE d.execution_id IN (…)},
 * {@code CaseHistoryService} groups by execution, and the run detail, the case history and the plan
 * statistics all render the links against a result. Nothing reads {@code case_id}.
 *
 * <p>A case-scoped link is therefore a second product — a claim that a defect concerns a test case
 * in general rather than one run of it — with no screen, no reader and no permission argument of its
 * own. It is not built, and {@code V62}'s header records how to get there if it is ever owed.
 *
 * <h2>Both writes cost {@code defect:create}, including the unlink</h2>
 *
 * <p>There is no {@code defect:delete}; the catalogue has taken no new key since {@code V3}, and one
 * seeded to nobody is a 403 no administrator can grant their way out of. The alternatives were to
 * charge the delete to something else it does not describe, or to ship a create with no correction
 * path — which leaves a mistyped key rendering as a broken {@code href} on three screens forever.
 *
 * <p>So the key that makes a link unmakes it, stated here rather than left to be inferred. That is
 * narrower than it sounds: a link carries no content anybody else authored, and re-linking is one
 * request, so unlinking somebody else's row destroys nothing that cannot be restored by the person
 * who notices. There is deliberately <strong>no authorship gate</strong> — {@code defect:create}
 * carries no qualifier (roles spec §A5; nothing has carried one since {@code V51}), and adding a
 * row-scoped rule that the catalogue does not express would be this service inventing a grant.
 *
 * <h2>{@code bindingId} arrived in 004, and it is the collision key</h2>
 *
 * <p>{@link DefectLinkInput#bindingId} is optional and its absence is not neutral. The replay
 * lookup and {@code defect_link_execution_key_idx} both key on {@code provider}, and the outbox
 * backstop ({@link TrackerDefectLinkBackstop}) writes its row with the provider the binding names.
 * A client that files an issue through the tracker and then omits the binding here produces two
 * rows that do not collide — one saying {@code EXTERNAL}, one saying {@code JIRA_CLOUD} — and the
 * run screen shows one defect twice, both rows looking correct. That is documented at the field and
 * pinned by a test.
 *
 * <h2>A closed run freezes both writes</h2>
 *
 * <p>The same rule {@link EvidenceService} applies to attachments, for the same reason: a defect
 * link is part of the record of what happened, and a run that has ended does not acquire new claims
 * about it. Making the link stricter or looser than the evidence beside it would be the incoherence
 * §67 wrote down and §72 closed.
 */
@Service
public class DefectLinkService {

    /** Roles spec §A5 — Test Engineer and up, unqualified. */
    private static final String CREATE_DEFECT = "defect:create";

    private final ProjectService projects;
    private final ExecutionTargets targets;
    private final DefectLinkRepository links;
    /**
     * Resolves {@code DefectLinkInput.bindingId} to a provider (004, {@code V74}).
     *
     * <p>A port declared in {@code project} and implemented in {@code integration}, because this
     * module may not import that one and never may — the constraint that also forces the two-call
     * seam this field exists to make safe.
     */
    private final TrackerBindingDirectory bindings;
    private final Clock clock;

    public DefectLinkService(
            ProjectService projects,
            ExecutionTargets targets,
            DefectLinkRepository links,
            TrackerBindingDirectory bindings,
            Clock clock) {

        this.projects = projects;
        this.targets = targets;
        this.links = links;
        this.bindings = bindings;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, run, execution or link — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** {@code defect:create} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its input. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The run has ended, so its record is frozen — 409, as {@link EvidenceService} answers. */
        record RunClosed() implements Outcome {
        }

        /**
         * Linked. 201 when {@code created}, 200 when this exact defect was already linked — the two
         * codes {@code ExternalLinkController} answers, and for its reason: a client that treats
         * them alike is correct, and one that counts creates is not misled.
         */
        record Linked(DefectLinkView view, boolean created) implements Outcome {
        }

        /** Unlinked. 204. */
        record Unlinked() implements Outcome {
        }
    }

    @Transactional
    public Outcome link(
            UUID userId, UUID projectId, UUID runId, UUID executionId, DefectLinkInput input) {

        Optional<ExecutionTargets.Target> found =
                targets.resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        ExecutionTargets.Target target = found.get();

        GateResult<Outcome> gate = projects.writeGranted(
                userId, projectId, CREATE_DEFECT,
                project -> write(project.getId(), target, input, userId));

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    @Transactional
    public Outcome unlink(
            UUID userId, UUID projectId, UUID runId, UUID executionId, UUID linkId) {

        Optional<ExecutionTargets.Target> found =
                targets.resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        ExecutionTargets.Target target = found.get();

        GateResult<Outcome> gate = projects.writeGranted(
                userId, projectId, CREATE_DEFECT, project -> remove(target, linkId));

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    /**
     * Runs inside the gate's callback, so a caller who may not link anything cannot use the
     * validation errors to learn what is already linked — {@link EvidenceService#attach}'s
     * structural reason for resolving the step id in the same place.
     */
    private Outcome write(
            UUID projectId, ExecutionTargets.Target target, DefectLinkInput input, UUID userId) {
        if (target.run().isClosed()) {
            return new Outcome.RunClosed();
        }

        Map<String, List<String>> errors = validate(input);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        String externalKey = input.externalKey().trim();
        String url = input.url().trim();

        // The provider the SERVER establishes, never one the caller asserted (004, V74). A binding
        // this project does not have resolves to nothing and the row says EXTERNAL — the same answer
        // a request naming no binding gets, because both are "nobody verified which tracker this is".
        String provider = input.bindingId() == null
                ? DefectLink.PROVIDER_EXTERNAL
                : bindings.resolve(projectId, input.bindingId())
                        .map(TrackerBindingDirectory.TrackerTarget::provider)
                        .orElse(DefectLink.PROVIDER_EXTERNAL);

        Optional<DefectLink> existing = links.findByExecutionIdAndProviderAndExternalKey(
                target.execution().getId(), provider, externalKey);
        if (existing.isPresent()) {
            // The same defect, already linked. Answered 200 with the row that is there rather than
            // 409: the caller asked for this result and has it, and the stored URL is served rather
            // than the submitted one so the response describes the record instead of the request.
            return new Outcome.Linked(existing.get().toView(), false);
        }

        Instant now = clock.instant();
        DefectLink link = DefectLink.to(
                CompanyContext.require(),
                target.execution().getId(),
                provider,
                externalKey,
                url,
                userId,
                now);
        links.saveAndFlush(link);
        return new Outcome.Linked(link.toView(), true);
    }

    private Outcome remove(ExecutionTargets.Target target, UUID linkId) {
        if (target.run().isClosed()) {
            return new Outcome.RunClosed();
        }

        Optional<DefectLink> found =
                links.findByIdAndExecutionId(linkId, target.execution().getId());
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        links.delete(found.get());
        return new Outcome.Unlinked();
    }

    private static Map<String, List<String>> validate(DefectLinkInput input) {
        Map<String, List<String>> errors = new java.util.LinkedHashMap<>();
        if (blank(input.externalKey())) {
            errors.put("externalKey", List.of("Give the defect's id in the tracker."));
        }
        List<String> url = urlErrors(input.url());
        if (!url.isEmpty()) {
            errors.put("url", url);
        }
        return errors;
    }

    /**
     * {@code http} and {@code https} and nothing else.
     *
     * <p>The same bound {@code documentation.ExternalLinkService} applies to an external document
     * URL, and re-stated rather than shared because {@code execution} may not depend on
     * {@code documentation} (plan §3). It is a security bound and not tidiness: three screens render
     * this value as an anchor's {@code href}, where {@code javascript:} executes in the reader's
     * session and {@code data:} renders attacker-authored HTML on this product's origin. The write
     * is the one place it can be refused once.
     */
    private static List<String> urlErrors(String submitted) {
        if (blank(submitted)) {
            return List.of("Give the address of the defect.");
        }
        URI parsed;
        try {
            parsed = new URI(submitted.trim());
        } catch (URISyntaxException malformed) {
            return List.of("That is not a valid address.");
        }
        String scheme = parsed.getScheme() == null
                ? null
                : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return List.of("Links must start with http:// or https://.");
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            return List.of("That address names no site.");
        }
        return List.of();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
