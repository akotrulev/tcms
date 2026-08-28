package com.tcms.documentation;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.ProjectService;
import com.tcms.project.TrackerBindingDirectory;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A case's links to issues in somebody else's tracker — the {@code EXTERNAL_ISSUE} {@code doc_link}
 * (004 D7, {@code V75}).
 *
 * <h2>The path is a case's and this service is {@code documentation}'s</h2>
 *
 * <p>{@link ExternalLinkService}'s split, and its argument verbatim:
 * {@code /projects/{id}/cases/{caseId}/issue-links} sits under an aggregate {@code repository} owns,
 * and the row it writes is {@code doc_link}, which this module owns. The alternative — putting this
 * in {@code integration} beside the tracker code — is not available: {@code integration} may depend
 * on {@code api}, {@code tenancy} and {@code project} and on nothing else, so it cannot write this
 * table at all.
 *
 * <p><strong>It reads {@code doc_link} directly and not the mirror</strong>, which is a small
 * divergence from the contract's prose (<em>"read from the case-side mirror"</em>) and the honest
 * one: the mirror exists because {@code repository} may not read this module's tables, and this
 * service is in the module that owns them. Reading the mirror here would serve a copy that lags its
 * source by one outbox tick, on the endpoint that just wrote the source. The mirror still gains the
 * three columns, because the case detail's Links panel — which <em>is</em> in {@code repository} —
 * renders from it.
 *
 * <h2>What this link does NOT do, and it costs a predicate to keep it that way</h2>
 *
 * <p>004 A1, taken literally as D13: <strong>an {@code EXTERNAL_ISSUE} link changes no derived
 * fact.</strong> It does not take a case out of {@code UNLINKED} and it cannot go stale. The default
 * behaviour is the opposite — {@code SpecLinkage} counts every mirror row — so this costs an
 * explicit {@code AND target_kind <> 'EXTERNAL_ISSUE'} over there and a test that fails loudly if a
 * later reader tidies it back to a bare {@code count(*)}.
 *
 * <h2>The provider and the host are the server's to state</h2>
 *
 * <p>The caller names a binding; the server resolves it through
 * {@link TrackerBindingDirectory} and records what that binding says. A caller-supplied provider
 * would be an unverified assertion recorded as a fact — {@code DefectLink}'s argument, one table
 * over. The binding id itself is <em>not</em> stored (D18): the host is, and a reader matches it
 * back, so deleting a binding degrades a chip instead of destroying a traceability record.
 */
@Service
public class IssueLinkService {

    /** Roles spec §A3 — the case's own key, for {@link ExternalLinkService}'s reason. */
    private static final String READ_CASE = "case:read";

    private static final String EDIT_CASE = "case:edit";

    private static final String ISSUE = "EXTERNAL_ISSUE";

    private final ProjectService projects;
    private final DocLinkRepository links;
    private final CatalogueFacts catalogue;
    private final TrackerBindingDirectory bindings;
    private final PeopleDirectory people;
    /** How {@code repository}'s mirror learns — {@link BlockLinkService} says the rest. */
    private final Outbox outbox;
    private final Clock clock;

    public IssueLinkService(
            ProjectService projects,
            DocLinkRepository links,
            CatalogueFacts catalogue,
            TrackerBindingDirectory bindings,
            PeopleDirectory people,
            Outbox outbox,
            Clock clock) {

        this.projects = projects;
        this.links = links;
        this.catalogue = catalogue;
        this.bindings = bindings;
        this.people = people;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping is exhaustive at compile time. */
    public sealed interface Outcome {

        /** The project, the case or the link is not reachable here — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code case:edit} is not held. */
        record Forbidden() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The same {@code (provider, host, id)} is already linked to this case — 409. */
        record Duplicate() implements Outcome {
        }

        record Linked(IssueLinkView view) implements Outcome {
        }

        record Unlinked() implements Outcome {
        }
    }

    @Transactional(readOnly = true)
    public GateResult<List<IssueLinkView>> list(UUID userId, UUID projectId, UUID caseId) {
        return projects.readGranted(userId, projectId, READ_CASE, project -> {
            if (catalogue.find(project.getId(), LinkSourceType.TEST_CASE, caseId).isEmpty()) {
                return null;
            }
            List<DocLink> rows = links
                    .findExternalForSource(LinkSourceType.TEST_CASE, caseId)
                    .stream()
                    .filter(link -> ISSUE.equals(link.getTargetKind()))
                    .toList();
            return toViews(project.getId(), rows);
        });
    }

    @Transactional
    public Outcome link(UUID userId, UUID projectId, UUID caseId, IssueLinkInput input) {
        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, EDIT_CASE, project -> {
            if (catalogue.find(project.getId(), LinkSourceType.TEST_CASE, caseId).isEmpty()) {
                return new Outcome.NotFound();
            }

            Map<String, List<String>> errors = new LinkedHashMap<>();
            if (input.bindingId() == null) {
                errors.put("bindingId", List.of("Choose which tracker this issue is in."));
            }
            if (input.issueId() == null || input.issueId().isBlank()) {
                errors.put("issueId", List.of("Give the issue's id in the tracker."));
            }
            List<String> url = urlErrors(input.url());
            if (!url.isEmpty()) {
                errors.put("url", url);
            }
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }

            Optional<TrackerBindingDirectory.TrackerTarget> target =
                    bindings.resolve(project.getId(), input.bindingId());
            if (target.isEmpty()) {
                // A binding id this project does not have. A field error rather than a 404, because
                // the case and the project both resolved: the form has somewhere to put it, and a
                // bodiless 404 here would read as "your case is gone".
                return new Outcome.Invalid(Map.of("bindingId", List.of(
                        "That tracker connection is not set up on this project.")));
            }

            String issueId = input.issueId().trim();
            boolean already = links
                    .findExternalForSource(LinkSourceType.TEST_CASE, caseId)
                    .stream()
                    .anyMatch(existing -> ISSUE.equals(existing.getTargetKind())
                            && target.get().provider().equals(existing.getExternalProvider())
                            && target.get().host().equals(existing.getExternalHost())
                            && issueId.equals(existing.getExternalIssueId()));
            if (already) {
                // Identity is (provider, host, id) — D17. Two bindings to DIFFERENT hosts holding
                // the same numeric id are two different issues and both may be linked; the same id
                // on the same host twice is one claim made twice.
                return new Outcome.Duplicate();
            }

            DocLink created = DocLink.toIssue(
                    LinkSourceType.TEST_CASE,
                    caseId,
                    target.get().provider(),
                    target.get().host(),
                    issueId,
                    input.url().trim(),
                    LinkRelationship.REFERENCES,
                    userId,
                    clock.instant());
            links.saveAndFlush(created);
            // The mirror is refreshed off this event, exactly as every other doc_link write is.
            outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);
            return new Outcome.Linked(toViews(project.getId(), List.of(created)).get(0));
        });
        return unwrap(gate);
    }

    /** Removes the annotation and nothing in the tracker. */
    @Transactional
    public Outcome unlink(UUID userId, UUID projectId, UUID caseId, UUID linkId) {
        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, EDIT_CASE, project -> {
            if (catalogue.find(project.getId(), LinkSourceType.TEST_CASE, caseId).isEmpty()) {
                return new Outcome.NotFound();
            }
            Optional<DocLink> found = links
                    .findExternalForSource(LinkSourceType.TEST_CASE, caseId)
                    .stream()
                    .filter(link -> link.getId().equals(linkId) && ISSUE.equals(link.getTargetKind()))
                    .findFirst();
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            links.delete(found.get());
            outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);
            return new Outcome.Unlinked();
        });
        return unwrap(gate);
    }

    /**
     * Rows to views, resolving each row's binding <strong>at read time</strong>.
     *
     * <p>The match is {@code (provider, host)} against the project's current bindings, which is what
     * makes D18 work: a deleted binding leaves the row untouched and answers {@code bindingId: null},
     * and the chip keeps its href and stops previewing. Nothing is stored that a deletion could
     * invalidate, and nothing cascades.
     */
    private List<IssueLinkView> toViews(UUID projectId, List<DocLink> rows) {
        Map<UUID, UserRef> authors = people.resolve(
                CompanyContext.require(),
                rows.stream()
                        .map(DocLink::getCreatedBy)
                        .filter(id -> id != null)
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));

        List<IssueLinkView> views = new ArrayList<>();
        for (DocLink row : rows) {
            views.add(new IssueLinkView(
                    row.getId(),
                    row.getExternalProvider(),
                    row.getExternalHost(),
                    row.getExternalIssueId(),
                    row.getExternalUrl(),
                    bindings.forHost(projectId, row.getExternalProvider(), row.getExternalHost())
                            .map(TrackerBindingDirectory.TrackerTarget::bindingId)
                            .orElse(null),
                    row.getCreatedAt(),
                    row.getCreatedBy() == null ? null : authors.get(row.getCreatedBy())));
        }
        return views;
    }

    /**
     * {@code http} and {@code https} and nothing else — {@code DefectLinkService}'s bound, restated
     * because {@code documentation} and {@code execution} may not import each other.
     *
     * <p>A security bound rather than tidiness: the value is rendered as an anchor's {@code href},
     * where {@code javascript:} executes in the reader's session and {@code data:} renders
     * attacker-authored HTML on this product's origin.
     */
    private static List<String> urlErrors(String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return List.of("Give the address of the issue.");
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

    private static Outcome unwrap(GateResult<Outcome> gate) {
        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }
}
