package com.tcms.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The case editor's autosave buffer — <strong>{@code test_case_draft}'s first writer, and the half
 * of plan §6j that was decided in M1 and never built</strong> (decision round 3, #57).
 *
 * <p>The Resolved table settled this as one sentence — <em>"versions written on explicit save only;
 * autosave goes to a draft buffer"</em> — and only the first clause shipped, in §29. So the product
 * has had the machinery that keeps history free of keystroke noise for twenty-odd slices, and no
 * keystrokes to keep out of it: closing the tab lost the work outright.
 *
 * <h2>There is no promote endpoint, and there must not be</h2>
 *
 * <p>V1's comment says an explicit save "promotes this into {@code test_case}", which reads like an
 * operation this class should offer. It is not one. A promote would be a <strong>second write path
 * into {@code test_case}</strong> whose input is by construction unvalidated — that is what a buffer
 * holds — so it would either duplicate {@link CaseEdit}'s validation, giving the product two
 * definitions of a legal case that are free to disagree, or skip it, which is how a case with a
 * blank title and a step referencing a retired shared block reaches the database.
 *
 * <p>Promotion is therefore the client's: it restores the draft into the form and the user presses
 * Save, which is the ordinary {@code PATCH} — validated, versioned, audited, {@code If-Match}ed.
 * This class's whole contribution to that flow is the last line of V1's comment, <em>"and clears the
 * row"</em>, which {@link CaseUpdateService} does on every successful save.
 *
 * <h2>The gate is {@code case:edit} on all three verbs, including the read</h2>
 *
 * <p>Writing one obviously needs it. Reading one is the choice worth stating: the content is the
 * case's own content, so serving it under {@code project:read} would disclose nothing a Viewer
 * cannot already see in the case itself — the argument {@code GET /versions} is served on. It is
 * gated harder anyway, because a draft is not the case: it is somebody's unfinished sentence, shown
 * nowhere else in the product, and a Viewer is specifically the client's stakeholder invited to read
 * coverage rather than to watch it being written. One gate across the three verbs also means one
 * refusal sentence rather than three that must agree.
 *
 * <h2>A deleted case keeps its buffer, and never serves it</h2>
 *
 * <p>V1 gives the row {@code ON DELETE CASCADE}, which never fires: this product soft-deletes cases.
 * Every method here goes through {@link TestCaseRepository#findLiveInProject} for that reason, so a
 * soft-deleted case answers 404 with its draft row still sitting in the table — consistent with the
 * case itself, which also survives and is also unreadable.
 *
 * <h2>What has no buffer at all, stated rather than left to be discovered</h2>
 *
 * <p><strong>A case being created has none.</strong> The primary key is a foreign key to
 * {@code test_case}, so there is nothing to key a draft on until the case exists — the New Case form
 * is exactly as lossy as it was before this slice. Fixing that is a different table with a different
 * lifetime (what expires an abandoned new-case draft, and who sees it), not a column on this one.
 */
@Service
public class CaseDraftService {

    /** The same key the edit takes. See the class header for why the read takes it too. */
    private static final String EDIT_CASE = "case:edit";

    /**
     * The ceiling on one buffered draft, serialized.
     *
     * <p>256 KB, and it exists because nothing else bounds this. The content is deliberately
     * unvalidated (see {@link CaseDraftInput}), the endpoint is called on a timer rather than by a
     * person pressing a button, and the row has no retention policy behind it — so without a bound,
     * one client looping on a large payload writes unboundedly into a table nothing prunes.
     *
     * <p>Generous against the real shape: a 200-step case with prose in every field serializes to
     * tens of kilobytes. A draft that exceeds this is a client defect rather than an author being
     * thorough, and it is answered as a field error rather than a 413 so it lands on the form like
     * every other refusal this endpoint can give.
     */
    static final int MAX_CONTENT_BYTES = 256 * 1024;

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final TestCaseDraftRepository drafts;
    private final CaseOwners people;

    /**
     * The application's configured mapper, injected for {@link CaseVersions}' reason: a private
     * {@code new ObjectMapper()} would be a second serialization configuration, and the buffered
     * content would drift from the shape the API actually serves the moment either is configured.
     */
    private final ObjectMapper json;

    private final Clock clock;

    CaseDraftService(
            ProjectService projects,
            TestCaseRepository cases,
            TestCaseDraftRepository drafts,
            CaseOwners people,
            ObjectMapper json,
            Clock clock) {
        this.projects = projects;
        this.cases = cases;
        this.drafts = drafts;
        this.people = people;
        this.json = json;
        this.clock = clock;
    }

    /** What happened, for all three verbs. */
    public sealed interface Outcome {

        /** Unreachable project, a case that is not in it, or a soft-deleted case — one bodiless 404. */
        record NotFound() implements Outcome {
        }

        /** {@code case:edit} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** A malformed autosave — a missing {@code lockVersion}, or content over the ceiling. */
        record Invalid(String field, String message) implements Outcome {
        }

        /**
         * The case is readable and has no buffered draft.
         *
         * <p>Distinct from {@link NotFound}, and the distinction is most of the installation: almost
         * every case has no draft almost all of the time, which is an ordinary state of a perfectly
         * readable case rather than a missing resource. Collapsing the two would tell an editor
         * their case was gone every time they opened one they had not left work in.
         */
        record Empty() implements Outcome {
        }

        /** The buffered draft, rendered. */
        record Found(CaseDraftView draft) implements Outcome {
        }

        /** The buffer is gone — or was never there, which answers the same. */
        record Discarded() implements Outcome {
        }
    }

    /**
     * Buffers the editor's current state, replacing whatever was there.
     *
     * <p>Answers the stored draft rather than 204, so the editor can render "saved a moment ago"
     * from the server's clock instead of its own — two clocks disagreeing about when work was last
     * safe is a small lie told on exactly the screen where trust is the feature.
     */
    @Transactional
    public Outcome save(UUID userId, UUID projectId, UUID caseId, CaseDraftInput request) {
        GateResult<Outcome> gated = projects.writeGranted(
                userId, projectId, EDIT_CASE, project -> buffer(userId, project, caseId, request));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome buffer(UUID userId, Project project, UUID caseId, CaseDraftInput request) {
        if (request == null || request.lockVersion() == null) {
            // Checked before the case is looked up, and therefore before the 404: the request is
            // malformed whichever case it names, and answering 404 would send the caller to check
            // an id that is fine. CaseQuarantineService's ordering, for its reason.
            //
            // Absent is a 400 rather than a default, because every value it could default to is a
            // claim about which version the draft was written against — and the whole point of the
            // field is that guessing it wrong loses somebody's committed edit. See CaseDraftInput.
            return new Outcome.Invalid("lockVersion",
                    "Send the lockVersion the editor is holding, so a restored draft can be "
                            + "recognised as written against an older version of the case.");
        }

        Optional<TestCase> found = cases.findLiveInProject(project.getId(), caseId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestCase testCase = found.get();

        String serialized = serialize(request);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            // Measured in BYTES, on the serialized envelope — not in characters, and not on the
            // title. The column stores bytes and a case full of non-ASCII prose costs up to four of
            // them per character, so a character bound would be a different ceiling per language.
            return new Outcome.Invalid("content",
                    "This draft is too large to buffer. Save the case to keep your work.");
        }

        Instant now = clock.instant();
        Optional<TestCaseDraft> existing = drafts.findScopedById(caseId);
        TestCaseDraft draft = existing
                .map(stored -> {
                    stored.overwrite(serialized, userId, now);
                    return stored;
                })
                .orElseGet(() -> TestCaseDraft.of(caseId, serialized, userId, now));
        drafts.save(draft);

        return new Outcome.Found(render(CompanyContext.require(), testCase, draft, request));
    }

    /**
     * The case's buffered draft, if there is one.
     *
     * <p>Read in a transaction and rendered inside it, because {@link CaseOwners} resolves the
     * author's name through the tenancy filter — §121 phase 4's lesson, where a resolution done
     * after the transaction closed would have run with no {@code app.current_company} and named
     * nobody, failing closed and silently.
     */
    @Transactional(readOnly = true)
    public Outcome findOne(UUID userId, UUID projectId, UUID caseId) {
        GateResult<Outcome> gated = projects.writeGranted(
                userId, projectId, EDIT_CASE, project -> read(project, caseId));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome read(Project project, UUID caseId) {
        Optional<TestCase> found = cases.findLiveInProject(project.getId(), caseId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        Optional<TestCaseDraft> draft = drafts.findScopedById(caseId);
        if (draft.isEmpty()) {
            return new Outcome.Empty();
        }
        return new Outcome.Found(render(
                CompanyContext.require(), found.get(), draft.get(),
                deserialize(draft.get().getContent())));
    }

    /**
     * Throws the buffer away.
     *
     * <p>Discarding a draft that does not exist <strong>succeeds</strong> rather than 404ing. The
     * caller asked for a state — no unsaved work on this case — and that is the state it is in;
     * §121's release-a-case-that-was-never-quarantined argument, and the same practical reason: the
     * editor sends this when the user clicks "discard", and a second click, a retry after a timeout,
     * or a concurrent save that already cleared the row must not read as an error.
     */
    @Transactional
    public Outcome discard(UUID userId, UUID projectId, UUID caseId) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_CASE, project -> {
            if (cases.findLiveInProject(project.getId(), caseId).isEmpty()) {
                return new Outcome.NotFound();
            }
            drafts.deleteForCase(caseId);
            return new Outcome.Discarded();
        });

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * Renders a stored draft beside the case it buffers.
     *
     * <p>{@code stale} is computed here, from the case row this transaction already loaded, rather
     * than being left to the client to derive from two numbers it happens to hold — see
     * {@link CaseDraftView#stale()}.
     */
    private CaseDraftView render(
            UUID companyId, TestCase testCase, TestCaseDraft draft, CaseDraftInput content) {

        UUID author = draft.getUpdatedBy();
        return new CaseDraftView(
                content.lockVersion(),
                content.lockVersion() == null
                        || content.lockVersion() != testCase.getLockVersion(),
                draft.getUpdatedAt(),
                // One lookup, and only when the row names somebody. `updated_by` is nullable in V1,
                // and CaseOwners returns a HashMap precisely so a null key is a miss rather than the
                // NullPointerException Map.of() throws by contract (§38, and §121 phase 4's red run).
                author == null ? null : people.resolve(companyId, List.of(author)).get(author),
                content.content());
    }

    private String serialize(CaseDraftInput request) {
        try {
            return json.writeValueAsString(request);
        } catch (JsonProcessingException cannotSerialize) {
            // The same object arrived as JSON a moment ago, so this failing means Jackson cannot
            // round-trip its own parse — worth an exception rather than a row holding a placeholder
            // that would restore as a case with nothing in it.
            throw new IllegalStateException(
                    "A parsed draft must be serializable", cannotSerialize);
        }
    }

    private CaseDraftInput deserialize(String content) {
        try {
            return json.readValue(content, CaseDraftInput.class);
        } catch (JsonProcessingException cannotRead) {
            // Only reachable if something other than this class wrote the column. Loud rather than
            // an empty draft: a silent null here would offer the author a restore that quietly
            // dropped everything they typed, which is worse than telling them it broke.
            throw new IllegalStateException(
                    "A buffered draft could not be read back", cannotRead);
        }
    }
}
