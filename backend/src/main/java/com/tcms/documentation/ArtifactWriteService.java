package com.tcms.documentation;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.RowScopeGate;
import com.tcms.storage.Attachment;
import com.tcms.storage.AttachmentOwner;
import com.tcms.storage.AttachmentRepository;
import com.tcms.storage.AttachmentStore;
import com.tcms.storage.BlobUpload;
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
import java.util.Objects;
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
 * <h2>Every column of {@code artifact} that this product decides now has a writer here</h2>
 *
 * <p>Recorded because the last two arrived together and for one reason (audit C6 and C7). Both had
 * been enforced or rendered for slices against a value nothing could produce:
 *
 * <ul>
 *   <li>{@code report_artifact_id} — {@link ArtifactKind#GENERATED_REPORT} was declared by {@code V1}
 *       and refused by this service with "not available yet" while {@code reporting} generated
 *       reports from §87 onward. The pin takes a <em>job</em> id and stores the artifact's, through
 *       {@link ReportFacts}; what it buys is that the register's entry answers the same bytes for
 *       ever, because a {@code report_artifact} is immutable.
 *   <li>{@code write_once} — honoured by {@link #delete} since §126 and by {@link #edit} since §229,
 *       and settable by nobody. A guarantee that is believed and never exercised is one nothing
 *       proves; both refusals had test coverage only because the tests set the column in SQL.
 * </ul>
 *
 * <p>{@code FILE} remains the one kind {@link #create} refuses, and that refusal is about the
 * <em>endpoint</em> rather than the kind — see {@link #createFile}.
 *
 * <h2>The gate is {@code artifact:edit}, and it is scope-qualified</h2>
 *
 * <p>Unlike the read — which uses the project gate because {@code artifact:read}'s distribution is
 * identical to {@code project:read}'s — this permission genuinely differs between roles, and differed
 * <em>within</em> a role by the row's scope. The roles spec granted it outright to Instance Admin,
 * Company Admin and Test Lead, and <strong>plan-scope-only</strong> to a Test Engineer,
 * because a project-scope artifact is inherited read-only by every plan (plan §6n): editing one
 * edits every cycle at once, which was a Lead-level act, while curating your own cycle's sign-off
 * was not.
 *
 * <h3>{@code V52} (§210) de-qualified it, and the scope seam is retained anyway</h3>
 *
 * <p>The requirement is that a contributor creates, edits and deletes, unqualified, so a Test
 * Engineer now holds this at {@code ALL} and may edit the project-scope row. <strong>This is a real
 * widening and it is a silent one</strong> — {@link ProjectService#writeGrantedByRowScope} still
 * runs, still compares, and now simply always says yes for that caller. Nothing 403s that used to,
 * and no test of this gate goes red on its own.
 *
 * <p>The seam is <em>not</em> removed, for the reason the whole qualifier mechanism is kept: it is
 * driven by <em>data</em>, so a future migration that qualifies this key again gets the narrowing
 * back, whereas a gate deleted today would have to be rebuilt and would fail <strong>open</strong>
 * until somebody noticed. Roles spec §A5 carries the argument and
 * {@code SeedDataTest.artifactEditIsUnqualifiedEverywhere} is where the current answer is pinned.
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
    private final ReportFacts reports;
    private final ArtifactPeople people;
    /**
     * The longest {@code Idempotency-Key} this service will look at — {@code SuiteWriteService}'s
     * number, and the same reason: an unbounded header is a value a hostile caller controls and the
     * index would carry.
     */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ArtifactFiles files;
    private final AttachmentRepository attachments;
    private final AttachmentStore store;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ArtifactWriteService(
            ProjectService projects,
            ArtifactRepository artifacts,
            DocPageRepository pages,
            PlanFacts plans,
            ReportFacts reports,
            ArtifactPeople people,
            ArtifactFiles files,
            AttachmentRepository attachments,
            AttachmentStore store,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.artifacts = artifacts;
        this.pages = pages;
        this.plans = plans;
        this.reports = reports;
        this.people = people;
        this.files = files;
        this.attachments = attachments;
        this.store = store;
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
         * The artifact a previous request with this {@code Idempotency-Key} registered (B4).
         *
         * <p><strong>200 and no {@code Location}</strong>, which is the shape every other keyed
         * create in this API answers with ({@code CaseCreateService.Outcome.Replayed}, and the
         * suite's): nothing was created by <em>this</em> request, so a 201 would be a lie a client
         * could act on, and a {@code Location} for a resource this call did not make is the same
         * lie in a header.
         *
         * <p>The view is re-rendered from the live row rather than stored, so a replay and a reload
         * cannot disagree — {@code V42}'s argument for why this family is a column and not a
         * general request table.
         */
        record Replayed(ArtifactView view) implements Outcome {
        }

        /** The row after a status move. 200. */
        record Updated(ArtifactView view) implements Outcome {
        }

        /**
         * The register refuses to move the status — a <strong>409</strong>, like
         * {@link Undeletable} and for the same reason: the caller's permissions are not the problem
         * and an administrator cannot help.
         *
         * <p>One state reaches it. {@link ArtifactStatus#SUPERSEDED} is not a judgement about this
         * row but a fact about another one, so it can be neither chosen nor left, and a caller who
         * asks is told what the state means rather than being given a way out that does not exist.
         *
         * @param message written for the person looking at the register
         */
        record Unchangeable(String message) implements Outcome {
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
    public Outcome create(
            UUID userId, UUID projectId, ArtifactInput input, String idempotencyKey) {

        return gated(projects.writeGrantedByRowScope(userId, projectId, PERMISSION,
                (project, rowScope) -> {
                    Outcome replay = replayed(project.getId(), idempotencyKey);
                    if (replay != null) {
                        return replay;
                    }
                    Map<String, List<String>> errors = validate(project.getId(), input, false);
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
                            UUID.randomUUID(),
                            project.getId(),
                            input.scope(),
                            input.scope() == ArtifactScope.PLAN ? input.planId() : null,
                            input.kind(),
                            input.category().trim(),
                            input.title().trim(),
                            trimmedOrNull(input.description()),
                            input.kind() == ArtifactKind.DOC_PAGE_REF ? input.docPageId() : null,
                            input.kind() == ArtifactKind.EXTERNAL_URL ? input.url().trim() : null,
                            null,
                            // Validation proved the job and gave back its artifact; re-reading it
                            // here keeps the id the row stores and the id the refusal checked the
                            // same one, rather than two lookups that could answer differently.
                            input.kind() == ArtifactKind.GENERATED_REPORT
                                    ? reports.findRenderedArtifactInProject(
                                            project.getId(), input.reportJobId()).orElseThrow()
                                    : null,
                            input.writeOnce(),
                            userId,
                            idempotencyKey,
                            now));

                    audit(project.getId(), userId, artifact, now);
                    applySupersession(project.getId(), userId, input, artifact, now);

                    Map<UUID, UserRef> authors =
                            people.resolve(CompanyContext.require(), List.of(userId));
                    // `inherited` is false: a row just created at either scope is this register's
                    // own. It only becomes inherited when read from inside a plan.
                    return new Outcome.Created(
                            ArtifactView.of(project.getId(), artifact, null, false, authors));
                }));
    }

    /**
     * Registers an uploaded file — <strong>{@code ArtifactKind.FILE}'s first writer, and the last of
     * {@code attachment.owner_type}'s five surviving values to get one.</strong>
     *
     * <p>{@code V1} declared {@code artifact.attachment_id} and {@code owner_type = 'ARTIFACT'} at
     * the beginning and {@link ArtifactKind#FILE} said "not registrable yet" in three places, on a
     * blocker — open question #28, the storage port — that §67 resolved and §125 finished. The
     * fifth time in this project a "blocked" note has outlived its blocker.
     *
     * <h2>The file arrives WITH the registration, and cannot arrive after it</h2>
     *
     * <p>There is no "create the artifact, then attach the bytes". A {@code FILE} row with no file
     * is a register entry that says a document is held and cannot produce it — the register's whole
     * claim, false, in the one module where §6n makes the audit trail the point. Making the two acts
     * one request is what removes that state entirely, rather than making it a window somebody has
     * to remember to close.
     *
     * <p>It is also why there is <strong>no re-upload and no detach</strong>. Replacing the document
     * is superseding it (plan §6n), which produces a second artifact naming the first, and the
     * column is {@code updatable = false} so the shortcut does not exist to be taken. An artifact
     * that swapped its bytes would keep the author, the timestamp and the audit trail of the file it
     * replaced.
     *
     * <h2>The gate is the reference create's, unchanged</h2>
     *
     * <p>{@code artifact:edit}, scope-qualified through {@link ProjectService#writeGrantedByRowScope}
     * — a Test Engineer may upload a cycle's sign-off and may not upload the project's test strategy,
     * for the same reason they may not <em>link</em> one: a project-scope artifact is inherited
     * read-only by every plan. Nothing about the payload being bytes changes who may write it, and
     * inventing a stricter key for uploads would be a rule the roles spec does not have.
     *
     * <h2>Rows first, bytes second</h2>
     *
     * <p>{@code EvidenceService}'s ordering for the third owner in this slice, with one wrinkle the
     * other two do not have: <strong>the attachment row is written before the artifact row</strong>,
     * because {@code artifact.attachment_id} is a foreign key and Postgres checks it at statement
     * time. The bytes go last regardless, so the failure mode stays a row whose blob is missing —
     * findable by joining — rather than a blob no row names.
     */
    @Transactional
    public Outcome createFile(
            UUID userId,
            UUID projectId,
            ArtifactFileInput fileInput,
            BlobUpload upload,
            String idempotencyKey) {

        ArtifactInput input = fileInput.asRegistration();
        return gated(projects.writeGrantedByRowScope(userId, projectId, PERMISSION,
                (project, rowScope) -> {
                    // FIRST, and before the bytes are looked at: a replayed upload must answer with
                    // the artifact it made rather than writing a second blob. This is the path the
                    // key matters most on — a retry here duplicates the attachment row AND the
                    // stored bytes, and the bytes are the copy nobody sees.
                    Outcome replay = replayed(project.getId(), idempotencyKey);
                    if (replay != null) {
                        return replay;
                    }
                    Map<String, List<String>> errors = validate(project.getId(), input, true);
                    if (!errors.isEmpty()) {
                        return new Outcome.Invalid(errors);
                    }
                    if (!rowScope.mayActOn(input.scope() == ArtifactScope.PLAN)) {
                        return new Outcome.ForbiddenAtScope();
                    }
                    if (upload.sizeBytes() <= 0) {
                        // The controller already refuses an empty part; this is the same refusal one
                        // layer down, where a future caller that is not that controller would write
                        // a zero-byte blob and a register row promising a document.
                        return new Outcome.Invalid(
                                Map.of("file", List.of("Choose a file to register.")));
                    }
                    return writeFile(userId, project, input, upload, idempotencyKey);
                }));
    }

    /** The two rows and the bytes, in the only order the foreign key and the filesystem allow. */
    private Outcome writeFile(
            UUID userId,
            Project project,
            ArtifactInput input,
            BlobUpload upload,
            String idempotencyKey) {

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();

        // The artifact's id, before either row exists — the cycle Artifact.register's header
        // describes. The blob's owner is the artifact, so the key is keyed on the artifact too, and
        // BlobUpload.key stays the one place a storage key is built.
        UUID artifactId = UUID.randomUUID();
        String key = BlobUpload.key(companyId, artifactId);

        Attachment file =
                Attachment.forArtifact(companyId, artifactId, key, upload, userId, now);
        // FIRST, and flushed: artifact.attachment_id references it, and Postgres checks a foreign
        // key at statement time rather than at commit.
        attachments.saveAndFlush(file);

        Artifact artifact = artifacts.save(Artifact.register(
                artifactId,
                project.getId(),
                input.scope(),
                input.scope() == ArtifactScope.PLAN ? input.planId() : null,
                ArtifactKind.FILE,
                input.category().trim(),
                input.title().trim(),
                trimmedOrNull(input.description()),
                null,
                null,
                file.getId(),
                null,
                input.writeOnce(),
                userId,
                idempotencyKey,
                now));

        audit(project.getId(), userId, artifact, now);
        applySupersession(project.getId(), userId, input, artifact, now);

        // LAST — the half that does not roll back goes after the half that does.
        store.put(key, upload.bytes());

        Map<UUID, UserRef> authors = people.resolve(companyId, List.of(userId));
        return new Outcome.Created(
                ArtifactView.of(project.getId(), artifact, file, false, authors));
    }

    /**
     * Points the replaced artifact at its replacement — <strong>{@link ArtifactStatus#SUPERSEDED}'s
     * only writer</strong>, and a no-op for an ordinary registration.
     *
     * <p>Runs after the new row is saved and flushes before touching the old one, because
     * {@code artifact.superseded_by_id} is a foreign key and Postgres checks it at statement time.
     * Hibernate does order inserts before updates within one flush; the explicit call is cheaper
     * than depending on that, and it is the same reasoning {@link #writeFile} applies to the
     * attachment.
     *
     * <p><strong>No permission is consulted here and that is the design, not an omission.</strong>
     * {@link #validate} has already refused a replacement whose scope or plan differs from the row
     * it replaces, so the create's own scope-qualified gate — which passed for the new row — passed
     * for a row sitting in exactly the same place. Drop the same-scope rule and this becomes the
     * escalation it exists to prevent: a Test Engineer registering a plan-scope artifact that
     * retires the project's test strategy, which they may not touch.
     *
     * <p>{@code write_once} is deliberately <em>not</em> checked. Superseding is precisely what a
     * sign-off record is for — the delete's own refusal says so and names it as the way forward.
     */
    private void applySupersession(
            UUID projectId, UUID userId, ArtifactInput input, Artifact replacement, Instant now) {

        if (input.supersedes() == null) {
            return;
        }
        artifacts.flush();
        artifacts.findInProject(projectId, input.supersedes()).ifPresent(replaced -> {
            replaced.supersededBy(replacement.getId(), userId, now);
            artifacts.save(replaced);
            auditSupersession(projectId, userId, replaced, replacement, now);
        });
    }

    /**
     * Corrects an artifact's title, category or description — <strong>the register's first edit
     * path, and the gap {@code artifact:edit} had carried since {@code V1}</strong>.
     *
     * <p>The key has been seeded since M0 and had two consumers, both of which <em>register</em> or
     * <em>circulate</em> a row; nothing could change what one says. So a typo in a title could be
     * answered only by superseding — which asserts on the record that a newer <em>document</em>
     * replaced it — or by deleting and re-registering, which loses the author and the date. Both
     * say something untrue about the project in order to fix a spelling.
     *
     * <h2>The gate is {@link #setStatus}'s, word for word</h2>
     *
     * <p>{@code artifact:edit}, scope-qualified against the <strong>row's</strong> scope rather than
     * the request's — and it is not a choice this method gets to make, because there is no scope in
     * the request at all: {@link ArtifactEditInput} has three fields and none of them is scope.
     * That is the same rule expressed one layer further down, where it cannot be defeated by an
     * omitted field.
     *
     * <h2>Two refusals, and only one of them exists</h2>
     *
     * <p>{@code write_once} is honoured — a sign-off record's stated meaning is that corrections
     * supersede and never overwrite, which is a rule about editing at least as much as about
     * deleting, and the delete has enforced it since §126. Nothing sets the column {@code true}
     * yet; it is honoured now for the delete's reason, which is that enforcement arriving after the
     * control it protects is a guarantee that is believed and does nothing.
     *
     * <p><strong>{@link ArtifactStatus#SUPERSEDED} is NOT refused, and that is a decision.</strong>
     * {@link #setStatus} refuses it because a status is a fact about <em>another</em> row; a title
     * is not. A register that cannot correct a typo in what it says was replaced is the same dead
     * end this method exists to close, one row further back. Permanence is {@code write_once}'s
     * job, and it is the guard above — the delete draws exactly this line in its own header.
     *
     * <p>Idempotent, like {@link #setStatus}: a body that changes nothing answers 200 and writes no
     * audit event.
     */
    @Transactional
    public Outcome edit(
            UUID userId, UUID projectId, UUID artifactId, ArtifactEditInput input) {

        return gated(projects.writeGrantedByRowScope(userId, projectId, PERMISSION,
                (project, rowScope) -> {
                    Optional<Artifact> found = artifacts.findInProject(project.getId(), artifactId);
                    if (found.isEmpty()) {
                        return new Outcome.NotFound();
                    }
                    Artifact artifact = found.get();
                    // Before validation, and before the write-once refusal: a caller who may not
                    // touch this row learns nothing about their payload, which is the ordering
                    // §34's thePermissionIsCheckedFirst pins one module over.
                    if (!rowScope.mayActOn(artifact.getScope() == ArtifactScope.PLAN)) {
                        return new Outcome.ForbiddenAtScope();
                    }

                    Map<String, List<String>> errors = validateEdit(input);
                    if (!errors.isEmpty()) {
                        return new Outcome.Invalid(errors);
                    }
                    if (artifact.isWriteOnce()) {
                        return new Outcome.Unchangeable(
                                "This is a sign-off record, and the register keeps those as they "
                                        + "were signed. Supersede it with a correction instead.");
                    }

                    String title = input.title().trim();
                    String category = input.category().trim();
                    String description = trimmedOrNull(input.description());
                    boolean unchanged = title.equals(artifact.getTitle())
                            && category.equals(artifact.getCategory())
                            && Objects.equals(description, artifact.getDescription());

                    Instant now = clock.instant();
                    if (!unchanged) {
                        artifact.amend(title, category, description, userId, now);
                        artifacts.saveAndFlush(artifact);
                        auditAmendment(project.getId(), userId, artifact, now);
                    }

                    Map<UUID, UserRef> authors = people.resolve(
                            CompanyContext.require(), List.of(artifact.getUpdatedBy()));
                    return new Outcome.Updated(ArtifactView.of(
                            project.getId(),
                            artifact,
                            files.forArtifact(artifact).orElse(null),
                            false,
                            authors));
                }));
    }

    /**
     * The edit's field rules — the create's, minus every field the edit cannot reach.
     *
     * <p>Not {@link #validate} with a flag. That method's job is the cross-field scope/kind/
     * supersedes reasoning, none of which has an input here, and threading a third mode through it
     * would make the create's rules read as conditional when they are not.
     */
    private static Map<String, List<String>> validateEdit(ArtifactEditInput input) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (isBlank(input.title())) {
            // Covers the omitted field too: §68's convention makes an absent scalar a null, and a
            // null title is a request to clear a NOT NULL column. Answered as a field error rather
            // than as a 500 from the database.
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
        return errors;
    }

    /**
     * Takes an artifact out of circulation, or puts it back —
     * <strong>{@link ArtifactStatus#ARCHIVED}'s only writer</strong>.
     *
     * <h2>The gate is the create's key read against the ROW's scope</h2>
     *
     * <p>{@code artifact:edit}, scope-qualified, exactly as both creates are — and the one word that
     * differs carries the whole rule. A create is gated on the scope in the <em>request</em>, because
     * that is the scope the row will have; this is gated on the scope the row already
     * <em>has</em>. Read the request instead and a Test Engineer archives the project's test
     * strategy by not mentioning its scope, which is the inheritance rule (plan §6n) defeated by an
     * omitted field.
     *
     * <p>Not {@code artifact:delete}. Archiving keeps the row, keeps it readable and can be undone;
     * §208's suite argument one module over, and the same conclusion — removal is the Lead's key,
     * taking something out of use is the contributor's.
     *
     * <p>Idempotent: setting the status a row already has answers 200 with the row and writes no
     * audit event. {@code DocPageWriteService.setStatus}'s call — two people archiving the same
     * artifact are agreeing, not conflicting, and an audit row per repeated click buries the change
     * that happened.
     */
    @Transactional
    public Outcome setStatus(
            UUID userId, UUID projectId, UUID artifactId, ArtifactStatusInput input) {

        return gated(projects.writeGrantedByRowScope(userId, projectId, PERMISSION,
                (project, rowScope) -> {
                    ArtifactStatus target = parseCirculation(input.status());
                    if (target == null) {
                        return new Outcome.Invalid(Map.of("status", List.of(
                                "Choose a status: ACTIVE or ARCHIVED. An artifact becomes "
                                        + "SUPERSEDED by having a replacement registered against "
                                        + "it, so it cannot be set here.")));
                    }
                    Optional<Artifact> found = artifacts.findInProject(project.getId(), artifactId);
                    if (found.isEmpty()) {
                        return new Outcome.NotFound();
                    }
                    Artifact artifact = found.get();
                    if (!rowScope.mayActOn(artifact.getScope() == ArtifactScope.PLAN)) {
                        return new Outcome.ForbiddenAtScope();
                    }
                    if (artifact.getStatus() == ArtifactStatus.SUPERSEDED) {
                        return new Outcome.Unchangeable(
                                "A newer artifact has replaced this one, so the register keeps it "
                                        + "as the record of what it replaced. Archive the "
                                        + "replacement instead if the document is no longer "
                                        + "relevant.");
                    }

                    Instant now = clock.instant();
                    ArtifactStatus previous = artifact.getStatus();
                    artifact.circulate(target, userId, now);
                    artifacts.saveAndFlush(artifact);
                    if (previous != target) {
                        auditCirculation(project.getId(), userId, artifact, previous, now);
                    }

                    Map<UUID, UserRef> authors = people.resolve(
                            CompanyContext.require(), List.of(artifact.getUpdatedBy()));
                    return new Outcome.Updated(ArtifactView.of(
                            project.getId(),
                            artifact,
                            files.forArtifact(artifact).orElse(null),
                            false,
                            authors));
                }));
    }

    /**
     * The two values the status endpoint takes, or {@code null} for anything else.
     *
     * <p>{@code SUPERSEDED} lands in the {@code null} arm along with a typo, deliberately: it is not
     * a status a caller chooses, and the field message says which two are and why the third is not.
     *
     * <p>Package-private for its own test — §184's call. The rule is total over a string and needs
     * no context, no container and no row, so proving it from the endpoint would cost a Spring boot
     * per case and would still only reach the values somebody thought to send.
     */
    static ArtifactStatus parseCirculation(String candidate) {
        if (candidate == null) {
            return null;
        }
        String normalized = candidate.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals(ArtifactStatus.ACTIVE.name())) {
            return ArtifactStatus.ACTIVE;
        }
        return normalized.equals(ArtifactStatus.ARCHIVED.name()) ? ArtifactStatus.ARCHIVED : null;
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
     * <p>~~This is the whole point of wiring it. A Test Engineer holds {@code artifact:edit} at plan
     * scope, so they may register a cycle's documents; {@code V3} withholds {@code artifact:delete}
     * from them entirely. Reusing {@link #PERMISSION} here would have compiled, passed review, and
     * quietly given every engineer the ability to remove a lead's sign-off record from the
     * register — the roles spec's creative-versus-destructive line, in the one module where the
     * artefact being destroyed <em>is</em> the audit trail.~~
     *
     * <p><strong>{@code V52} (§210) granted {@code artifact:delete} to Test Engineer and deleted the
     * creative-versus-destructive line outright</strong>, so the paragraph above no longer describes
     * a difference between roles: every caller who may edit here may now also delete. <strong>The
     * separate key is still correct and is not collapsed into {@link #PERMISSION}.</strong> Two
     * reasons that never depended on the tier: a Viewer holds neither but a company may grant one
     * without the other, and removing a register entry is a distinct, separately auditable act from
     * amending one. What is gone is the <em>tier</em> argument, not the <em>key</em> argument — and
     * the distinction between those two is exactly what §210 found had been restated twice.
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
            removeFile(artifact);
            return new Outcome.Deleted();
        }));
    }

    /**
     * The bytes behind a deleted {@code FILE}, and the row that described them.
     *
     * <p><strong>This is what §124 meant by "a {@code FILE} artifact is the first kind whose
     * deletion also destroys bytes", and it is the reason {@code artifact:delete} is a separate key
     * rather than {@code artifact:edit}'s destructive half.</strong> Unregistering a link removes a
     * pointer to a document that goes on existing somewhere else; unregistering an upload is the
     * last copy leaving the disk. Both are refused for a write-once sign-off, which is the guard
     * that now protects something irreplaceable rather than merely something recorded.
     *
     * <p>Ordering, and it is the mirror of the write: the artifact row goes first (its foreign key
     * points at the attachment, so the other order is a constraint violation), then the attachment
     * row, then the bytes. A crash anywhere leaves at worst an unreferenced blob an operator can
     * sweep — never a register row whose download 404s, and never a blob no row names.
     *
     * <p>A no-op for the three reference kinds, keyed on {@code attachment_id} rather than on
     * {@code kind}: a row either has bytes or does not, and consulting the enum would leave a blob
     * behind the day the two disagreed.
     */
    private void removeFile(Artifact artifact) {
        if (artifact.getAttachmentId() == null) {
            return;
        }
        Optional<Attachment> file = attachments.findOwnedById(
                AttachmentOwner.ARTIFACT, artifact.getId(), artifact.getAttachmentId());
        if (file.isEmpty()) {
            // Already gone, which the product cannot produce today. Deleting nothing is the right
            // answer; throwing would refuse the removal of the register row over a blob that is
            // already in the state the caller asked for.
            return;
        }
        attachments.delete(file.get());
        attachments.flush();
        store.delete(file.get().getStorageKey());
    }

    /**
     * Every cross-field rule the schema cannot express, reported together.
     *
     * <p>All of them at once rather than the first: a form that surfaces one error per round trip
     * makes the user discover their payload by bisection.
     *
     * @param hasUpload whether bytes arrived with the request. The <em>only</em> thing that differs
     *                  between the two create paths, and it is a parameter rather than two
     *                  validators so that title, category, description and both scope rules cannot
     *                  drift apart — a second copy of them is a second answer to what a valid
     *                  registration is, and only one of the two would be the one a reviewer reads
     */
    private Map<String, List<String>> validate(
            UUID projectId, ArtifactInput input, boolean hasUpload) {
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
        validateKind(projectId, input, hasUpload, errors);
        validateSupersedes(projectId, input, errors);
        return errors;
    }

    /**
     * The artifact being replaced has to be one this registration is allowed to replace.
     *
     * <h2>Same scope, same plan — the rule the permission model rests on</h2>
     *
     * <p>This looks like tidiness and is the slice's one security-relevant check. The create's gate
     * asks whether the caller may write at the scope in the <em>request</em>; superseding writes a
     * second row nobody gated. Requiring the two to sit in the same place is what makes the one gate
     * cover both — without it, a Test Engineer registers a plan-scope note that retires the
     * project's test strategy, a document plan §6n gives them read-only.
     *
     * <p>It is also the honest domain rule independently of that: a cycle's sign-off does not
     * replace the project's environment guide, and one plan's document does not replace another
     * plan's. The kinds are free to differ — a Confluence link may be replaced by an upload, which
     * is the register's stated way of steering people off stale copies.
     *
     * <h2>Only an ACTIVE artifact may be replaced</h2>
     *
     * <p>A second replacement would overwrite {@code superseded_by_id} and lose the middle of the
     * chain, leaving the register asserting that the newest document replaced the oldest and that
     * the one in between replaced nothing. An archived row is already out of use, so replacing it
     * answers a question nobody asked; the message names the way back rather than refusing flatly.
     */
    private void validateSupersedes(
            UUID projectId, ArtifactInput input, Map<String, List<String>> errors) {

        if (input.supersedes() == null) {
            return;
        }
        Optional<Artifact> found = artifacts.findInProject(projectId, input.supersedes());
        if (found.isEmpty()) {
            // Absent, another project's and another company's are one message, for validateScope's
            // reason: a distinct one would let a caller probe which artifact ids are real.
            errors.put("supersedes", List.of("That artifact is not in this project."));
            return;
        }
        Artifact replaced = found.get();
        UUID requestedPlan = input.scope() == ArtifactScope.PLAN ? input.planId() : null;
        if (replaced.getScope() != input.scope()
                || !Objects.equals(replaced.getPlanId(), requestedPlan)) {
            errors.put("supersedes", List.of(
                    "A replacement has to sit where the artifact it replaces sits — the same "
                            + "scope, and the same plan."));
            return;
        }
        if (replaced.getStatus() == ArtifactStatus.SUPERSEDED) {
            errors.put("supersedes", List.of(
                    "That artifact has already been replaced. Replace the artifact that replaced "
                            + "it."));
        } else if (replaced.getStatus() == ArtifactStatus.ARCHIVED) {
            errors.put("supersedes", List.of(
                    "That artifact is archived, so nothing is using it. Restore it first if it is "
                            + "the one being replaced."));
        }
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

    /** Each kind needs its own target, and none may carry another's. */
    private void validateKind(
            UUID projectId, ArtifactInput input, boolean hasUpload,
            Map<String, List<String>> errors) {

        if (input.reportJobId() != null && input.kind() != ArtifactKind.GENERATED_REPORT) {
            // Refused rather than ignored, for validateScope's reason and once for the three arms
            // that share it: a client sending a job id believes the register entry points at that
            // report, and storing a row that does not is the silent disagreement §50 built two
            // checks to catch.
            errors.put("reportJobId",
                    List.of("Only a pinned report names a report job."));
        }

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
            // A FILE with bytes beside it is registrable; a FILE without them is a register row
            // promising a document it does not hold. So the refusal is about the ENDPOINT, not
            // about the kind, and it names the way forward — the caller holds the same permission
            // for the other path, so the advice is true by construction rather than hopeful, which
            // is §124's ordering rule (advice that is confidently wrong is worse than a refusal).
            case FILE -> {
                if (!hasUpload) {
                    errors.put("kind", List.of(
                            "A file is registered by uploading it — send it to "
                                    + "POST /projects/{projectId}/artifacts/files instead."));
                }
                if (input.docPageId() != null) {
                    errors.put("docPageId", List.of("An uploaded file is not a page reference."));
                }
                if (input.url() != null) {
                    // Not ignored, for validateScope's reason: a client sending a URL believes the
                    // artifact points at it, and storing bytes instead is a silent disagreement.
                    errors.put("url", List.of("An uploaded file points at its own bytes."));
                }
            }
            // Registrable since audit C6. The other three payload arms name a thing that exists
            // independently of this product; this one names something it made itself, and the
            // check is therefore about provenance rather than about format.
            case GENERATED_REPORT -> {
                if (input.reportJobId() == null) {
                    errors.put("reportJobId", List.of("Choose the report this artifact pins."));
                } else if (reports
                        .findRenderedArtifactInProject(projectId, input.reportJobId())
                        .isEmpty()) {
                    // One message for "no such job", "another project's" and "it produced
                    // nothing", for validateScope's reason — and the third is the one a real
                    // caller meets: a report that failed, or is still running, has no bytes to
                    // pin, and a register entry promising a document it cannot produce is plan
                    // §6n's own failure mode.
                    errors.put("reportJobId", List.of(
                            "That report is not one this project has finished generating."));
                }
                if (input.docPageId() != null) {
                    errors.put("docPageId", List.of("A pinned report is not a page reference."));
                }
                if (input.url() != null) {
                    // Not ignored, for validateScope's reason.
                    errors.put("url", List.of("A pinned report points at its own rendered bytes."));
                }
            }
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
    /**
     * The replay answer for this key, or null when there is nothing to replay (B4).
     *
     * <p>Called <strong>first</strong> on both create paths, before validation and before the bytes
     * are touched — {@code CaseCreateService}'s ordering and its reason: a retry of a create that
     * succeeded must answer with what it made even if the payload has since drifted, because the
     * client is asking "did my intent land" and a 400 would send it to fix fields on a row that
     * already exists.
     *
     * <p>Returning null for "no replay" rather than an {@code Optional<Outcome>} keeps both call
     * sites a two-line guard inside a lambda that already returns {@code Outcome}; the alternative
     * reads as a stream over a thing that is not a value.
     */
    private Outcome replayed(UUID projectId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
            return new Outcome.Invalid(Map.of("Idempotency-Key",
                    List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
        }
        return artifacts.findByIdempotencyKey(projectId, idempotencyKey)
                .map(artifact -> (Outcome) new Outcome.Replayed(ArtifactView.of(
                        projectId,
                        artifact,
                        files.forArtifact(artifact).orElse(null),
                        false,
                        // The row's LAST author, which is what the view renders — a replayed
                        // create answers with the artifact as it now stands, and it may have been
                        // amended since the original request landed. Resolving the creator instead
                        // would render a person the view's own field does not name.
                        people.resolve(
                                CompanyContext.require(), List.of(artifact.getUpdatedBy())))))
                .orElse(null);
    }

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

    /**
     * The replacement, recorded from the replaced row's side.
     *
     * <p>Both ids are in the body because the event answers a question about a <em>pair</em>: "what
     * happened to the January sign-off" is unanswerable from a row that only says its status
     * changed. The registration of the replacement has its own {@code ARTIFACT_REGISTERED} event —
     * this is the edge between them, which neither of the two rows' own events carries.
     */
    private void auditSupersession(
            UUID projectId, UUID userId, Artifact replaced, Artifact replacement, Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                "ARTIFACT_SUPERSEDED",
                AUDIT_TARGET,
                List.of(replaced.getId()),
                "{\"title\":" + AuditEvent.jsonString(replaced.getTitle())
                        + ",\"supersededBy\":\"" + replacement.getId() + "\""
                        + ",\"supersededByTitle\":" + AuditEvent.jsonString(replacement.getTitle())
                        + "}",
                now));
    }

    /**
     * A correction, recorded with the values it produced.
     *
     * <p>The new title rather than the old one, unlike {@link #auditRemoval}: the row is still in
     * the register, so "what did it say before" is answerable from the previous event and "what
     * does it say now" is not answerable from a row that only names an id. Category travels with
     * it because the register groups by it — a document that moved from Compliance to Other has
     * moved on somebody's screen.
     */
    private void auditAmendment(UUID projectId, UUID userId, Artifact artifact, Instant now) {
        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                "ARTIFACT_AMENDED",
                AUDIT_TARGET,
                List.of(artifact.getId()),
                "{\"title\":" + AuditEvent.jsonString(artifact.getTitle())
                        + ",\"category\":" + AuditEvent.jsonString(artifact.getCategory()) + "}",
                now));
    }

    /** Archiving and restoring, with the state left behind — a status event without it says nothing. */
    private void auditCirculation(
            UUID projectId, UUID userId, Artifact artifact, ArtifactStatus previous, Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                "ARTIFACT_STATUS_CHANGED",
                AUDIT_TARGET,
                List.of(artifact.getId()),
                "{\"title\":" + AuditEvent.jsonString(artifact.getTitle())
                        + ",\"from\":\"" + previous + "\""
                        + ",\"to\":\"" + artifact.getStatus() + "\"}",
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
