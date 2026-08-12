package com.tcms.documentation;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writer for {@code doc_link}'s other two target kinds — a case verifying a range of lines in a
 * repository, or a published document at a URL (plan §6m; open question #26, resolved
 * <em>build it</em> on 2026-08-02).
 *
 * <p>{@link BlockLinkService} is the sibling and this is not an arm of it. The two write the same
 * table and share nothing else: a different source of truth for what the target is, a different
 * project resolution (there is no page to find), a different idempotency key, and — the one that
 * decided it — <strong>a different permission</strong>.
 *
 * <h2>The gate is {@code case:edit}, and that is a reversal of {@link BlockLinkService}'s argument
 * rather than an inconsistency with it</h2>
 *
 * <p>That class gates on {@code doc:edit} and states the reason: "a link is a property of the
 * documentation, so it costs what editing the documentation costs", and {@code case:edit} was
 * refused there because it "would let a person with no documentation rights write into a page's
 * rendering".
 *
 * <p><strong>Every clause of that argument turns on the target being a doc block, and here it is
 * not.</strong> An external link renders in exactly one place — the case's Traceability panel — and
 * on no page, because {@code BlockLinks} reads by {@code target_block_id} and an external row has
 * none. So the act writes into a case's rendering and nothing else, which makes {@code doc:edit}
 * the wrong charge twice over: it would let somebody with documentation rights and no case rights
 * annotate a case they may not edit, and it would require a documentation grant to record a fact
 * about a case in a project that has no documentation at all.
 *
 * <p>The cost is stated rather than hidden: {@code doc_link} now has two writers with two gates, so
 * "who may create a link" has no single answer. The answer it does have is the one that reads off
 * the screen — <em>whoever may edit the thing the link renders on</em> — which is checkable, where
 * one gate for the table would have required knowing that a row's kind decides where it appears.
 *
 * <h2>The source is a case and not a suite</h2>
 *
 * <p>{@link LinkSourceType} offers both and this endpoint takes the case alone, because a suite has
 * no screen that renders traceability: {@code case_doc_link} mirrors {@code source_type =
 * 'TEST_CASE'} rows only, and a suite's external link would be a row nothing could ever show. That
 * is §156's shape — a writer whose value has no reader — and the fix is one path parameter on the
 * day a suite grows the panel, not a source picker now.
 *
 * <h2>Re-asserting the same target answers 200, exactly as a block link does</h2>
 *
 * <p>Same argument, same failure it prevents: two people recording the same coverage is agreement,
 * and a second row doubles every count over this table. What differs is how sameness is decided —
 * see {@link #sameTarget}, where it is a Java comparison over the identifying columns rather than a
 * query predicate, because half of them are nullable and SQL equality is not.
 */
@Service
public class ExternalLinkService {

    /** Roles spec §A3 — see the class header for why it is not {@code doc:edit}. */
    private static final String EDIT_CASE = "case:edit";

    private static final String AUDIT_TARGET = "doc_link";

    private static final String GIT = "EXTERNAL_GIT";
    private static final String URL = "EXTERNAL_URL";

    /**
     * A git object name is 7 to 40 hex characters — an abbreviated sha through a full SHA-1.
     *
     * <p>Checked because the value is the whole point of the kind (#26) and an unchecked one is
     * accepted, stored and discovered to be a branch name by the reader who clicks it. The bound is
     * deliberately loose at the bottom: teams paste abbreviated shas out of terminals, and refusing
     * a seven-character one would be this product deciding how somebody's git is configured.
     *
     * <p>SHA-256 object names are 64 characters and are <em>refused</em> by this pattern. That is a
     * known limit rather than an oversight: no repository this product has met uses them, and
     * widening the bound is one number here on the day one does — where accepting anything hex and
     * long would also accept the sixty-character paste of two shas nobody noticed.
     */
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,40}");

    private final ProjectService projects;
    private final DocLinkRepository links;
    private final CatalogueFacts catalogue;
    private final AuditEventRepository auditEvents;
    /** How {@code repository}'s mirror learns — {@link BlockLinkService} says the rest. */
    private final Outbox outbox;
    private final Clock clock;

    public ExternalLinkService(
            ProjectService projects,
            DocLinkRepository links,
            CatalogueFacts catalogue,
            AuditEventRepository auditEvents,
            Outbox outbox,
            Clock clock) {

        this.projects = projects;
        this.links = links;
        this.catalogue = catalogue;
        this.auditEvents = auditEvents;
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

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A new link. 201. */
        record Created(ExternalLinkView view) implements Outcome {
        }

        /** The same target was already linked and nothing was written. 200. */
        record Existing(ExternalLinkView view) implements Outcome {
        }

        /** Removed. 204. */
        record Deleted() implements Outcome {
        }
    }

    /** Links a case to a repository range or a URL. */
    @Transactional
    public Outcome link(UUID userId, UUID projectId, UUID caseId, ExternalLinkInput input) {

        return gated(projects.writeGranted(userId, projectId, EDIT_CASE, project -> {

            Map<String, List<String>> errors = validate(input);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }

            // After validation and before anything is written. The case check is CatalogueFacts'
            // project predicate rather than RLS: a sibling project's case is this company's, so RLS
            // returns it, and a link to it would put another project's traceability on this one.
            Optional<CatalogueFacts.CatalogueRef> source =
                    catalogue.find(project.getId(), LinkSourceType.TEST_CASE, caseId);
            if (source.isEmpty()) {
                return new Outcome.NotFound();
            }

            String kind = normalise(input.targetKind());
            Instant now = clock.instant();

            Optional<DocLink> already = links.findExternalForSource(LinkSourceType.TEST_CASE, caseId)
                    .stream()
                    .filter(link -> sameTarget(link, kind, input))
                    .findFirst();
            if (already.isPresent()) {
                // No row, no audit event, no outbox notice — BlockLinkService's three reasons, and
                // the third is the expensive one: a coalesced recompute per duplicate click.
                return new Outcome.Existing(ExternalLinkView.of(already.get()));
            }

            LinkRelationship relationship = relationshipOf(input);
            DocLink created = links.save(GIT.equals(kind)
                    ? DocLink.toGit(
                            LinkSourceType.TEST_CASE,
                            caseId,
                            input.repo().trim(),
                            input.path().trim(),
                            input.lineStart(),
                            input.lineEnd(),
                            input.commitSha().trim(),
                            relationship,
                            userId,
                            now)
                    : DocLink.toUrl(
                            LinkSourceType.TEST_CASE, caseId, input.url().trim(), relationship,
                            userId, now));

            audit(project.getId(), userId, "doc_link.created", created, source.get(), now);
            outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);
            return new Outcome.Created(ExternalLinkView.of(created));
        }));
    }

    /** Removes one of a case's external links. */
    @Transactional
    public Outcome unlink(UUID userId, UUID projectId, UUID caseId, UUID linkId) {

        return gated(projects.writeGranted(userId, projectId, EDIT_CASE, project -> {

            Optional<CatalogueFacts.CatalogueRef> source =
                    catalogue.find(project.getId(), LinkSourceType.TEST_CASE, caseId);
            if (source.isEmpty()) {
                return new Outcome.NotFound();
            }

            // Scoped to the case, exactly as the block delete is scoped to the page: a link id
            // belonging to another case must 404 rather than be deleted from under it, and findById
            // would resolve it through the persistence context with only RLS in the way.
            Optional<DocLink> found =
                    links.findExternalForSource(LinkSourceType.TEST_CASE, caseId).stream()
                            .filter(link -> link.getId().equals(linkId))
                            .findFirst();
            if (found.isEmpty()) {
                // Not an idempotent 204 — BlockLinkService's reason: the caller named a row, and
                // naming one that is not there is a mistake worth reporting.
                return new Outcome.NotFound();
            }

            DocLink link = found.get();
            links.delete(link);
            audit(project.getId(), userId, "doc_link.deleted", link, source.get(), clock.instant());
            outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);
            return new Outcome.Deleted();
        }));
    }

    /**
     * Every rule this endpoint enforces, in one place and reported all at once.
     *
     * <p>All fields are checked before any is rejected, so a form with three empty inputs lands on
     * three of them rather than on the first — {@link DocPageWriteService}'s convention here.
     */
    private static Map<String, List<String>> validate(ExternalLinkInput input) {

        Map<String, List<String>> errors = new LinkedHashMap<>();
        String kind = normalise(input.targetKind());

        if (!GIT.equals(kind) && !URL.equals(kind)) {
            errors.put("targetKind",
                    List.of("Choose what is being linked: EXTERNAL_GIT or EXTERNAL_URL."));
            // The remaining rules are per kind and there is no kind, so they would all fire. A form
            // told that six fields are wrong when one is unset has been told nothing.
            return errors;
        }
        if (input.relationship() != null && LinkRelationship.parse(input.relationship()) == null) {
            errors.put("relationship",
                    List.of("Choose a relationship: VERIFIES, DERIVED_FROM or REFERENCES."));
        }

        if (GIT.equals(kind)) {
            if (blank(input.repo())) {
                errors.put("repo", List.of("Name the repository, e.g. acme/checkout-service."));
            }
            if (blank(input.path())) {
                errors.put("path", List.of("Name the file, e.g. src/main/java/Checkout.java."));
            }
            if (blank(input.commitSha())) {
                // The one required field somebody will ask to make optional, so the message says
                // why rather than that it is required (#26, plan §6m).
                errors.put("commitSha", List.of(
                        "Pin the commit. Without one these lines point at different code after the "
                                + "next push."));
            } else if (!SHA.matcher(input.commitSha().trim()).matches()) {
                errors.put("commitSha",
                        List.of("That is not a commit id — 7 to 40 hexadecimal characters. A branch "
                                + "or tag name moves, which is what pinning is against."));
            }
            errors.putAll(lineErrors(input));
            if (input.url() != null) {
                errors.put("url", List.of("A git link has no URL — remove it or link a URL instead."));
            }
        } else {
            List<String> url = urlErrors(input.url());
            if (!url.isEmpty()) {
                errors.put("url", url);
            }
            // Reported as one error naming the offending fields rather than one per field: they are
            // not six mistakes, they are one — the wrong kind was chosen, or the wrong fields sent.
            List<String> gitFields = List.of("repo", "path", "lineStart", "lineEnd", "commitSha");
            List<Object> sent = Arrays.asList(
                    input.repo(), input.path(), input.lineStart(), input.lineEnd(),
                    input.commitSha());
            for (int i = 0; i < gitFields.size(); i++) {
                if (sent.get(i) != null) {
                    errors.put(gitFields.get(i),
                            List.of("A URL link has no repository coordinates — choose EXTERNAL_GIT "
                                    + "to link lines of code."));
                }
            }
        }
        return errors;
    }

    /**
     * The line range, which is optional as a whole and ordered when it is present.
     *
     * <p>A whole-file link is legitimate — "this case verifies that class" — so both null is an
     * answer. An end without a start is not: it would render as {@code Checkout.java:null–120}, and
     * the row that produced it would satisfy every constraint in the schema.
     */
    private static Map<String, List<String>> lineErrors(ExternalLinkInput input) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        Integer start = input.lineStart();
        Integer end = input.lineEnd();

        if (start == null && end == null) {
            return errors;
        }
        if (start == null) {
            errors.put("lineStart", List.of("Give the first line of the range, or remove the last."));
            return errors;
        }
        if (start < 1) {
            errors.put("lineStart", List.of("Line numbers start at 1."));
        }
        if (end != null && end < start) {
            errors.put("lineEnd", List.of("The range ends before it begins."));
        }
        return errors;
    }

    /**
     * {@code http} and {@code https} and nothing else.
     *
     * <p>The SPA renders this value as an anchor's {@code href}, so the scheme is a security bound
     * and not tidiness: {@code javascript:} in an {@code href} executes in the reader's session, and
     * {@code data:} renders attacker-authored HTML on this product's origin. Refusing at the write
     * is the one place it can be refused once — the value is read back by the case detail, the case
     * version snapshot, the coverage report and the API client, and an escape rule remembered in
     * four renderers is an escape rule that is wrong in one of them.
     */
    private static List<String> urlErrors(String submitted) {
        if (blank(submitted)) {
            return List.of("Give the address of the document.");
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

    /**
     * Whether an existing link already makes this claim.
     *
     * <p><strong>Compared in Java rather than in the query, and the reason is null.</strong> Four of
     * the six identifying columns are nullable, and {@code external_line_start = :start} is
     * <em>unknown</em> rather than true when both sides are null — so the SQL spelling of this would
     * silently fail to match a whole-file link against itself and write a second row every time
     * somebody clicked twice. {@code IS NOT DISTINCT FROM} says it correctly and JPQL cannot; the
     * alternative is a native query in the one module file that is not allowed to hold one.
     *
     * <p>The candidate set is a case's external links, which is a handful. It is not a page's worth
     * of rows and it is not batched over cases, so the read this compares over is small by the shape
     * of the thing rather than by a limit somebody has to maintain.
     *
     * <p>The line range is part of the identity: two links into the same file at the same commit
     * naming different ranges are two claims, and folding them together would answer the second one
     * with the first link and silently drop what the author meant.
     */
    private static boolean sameTarget(DocLink link, String kind, ExternalLinkInput input) {
        if (!kind.equals(link.getTargetKind())) {
            return false;
        }
        if (URL.equals(kind)) {
            return Objects.equals(link.getExternalUrl(), input.url().trim());
        }
        return Objects.equals(link.getExternalRepo(), input.repo().trim())
                && Objects.equals(link.getExternalPath(), input.path().trim())
                && Objects.equals(link.getExternalLineStart(), input.lineStart())
                && Objects.equals(link.getExternalLineEnd(), input.lineEnd())
                && Objects.equals(link.getExternalCommitSha(), input.commitSha().trim());
    }

    private static LinkRelationship relationshipOf(ExternalLinkInput input) {
        return input.relationship() == null
                ? LinkRelationship.VERIFIES
                : LinkRelationship.parse(input.relationship());
    }

    /** Tolerant of case and whitespace, like every other vocabulary parsed on this module's edge. */
    private static String normalise(String submitted) {
        return submitted == null ? null : submitted.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * The same two actions {@link BlockLinkService} writes, deliberately — {@code doc_link.created}
     * and {@code doc_link.deleted}. An audit reader filtering for what happened to traceability
     * must not have to know that this product has two link writers, which is exactly what a third
     * action name would require.
     *
     * <p>The detail names the target rather than the block id, because that is the field a reader of
     * this row is trying to recognise, and it is the only thing that tells the two writers apart in
     * the trail.
     */
    private void audit(
            UUID projectId,
            UUID userId,
            String action,
            DocLink link,
            CatalogueFacts.CatalogueRef source,
            Instant now) {

        String target = GIT.equals(link.getTargetKind())
                ? link.getExternalRepo() + "/" + link.getExternalPath()
                        + "@" + link.getExternalCommitSha()
                : link.getExternalUrl();

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action,
                AUDIT_TARGET,
                List.of(link.getId()),
                "{\"sourceType\":\"" + link.getSourceType()
                        + "\",\"sourceKey\":" + AuditEvent.jsonString(source.key())
                        + ",\"targetKind\":\"" + link.getTargetKind()
                        + "\",\"target\":" + AuditEvent.jsonString(target)
                        + ",\"relationship\":\"" + link.getRelationship() + "\"}",
                now));
    }
}
