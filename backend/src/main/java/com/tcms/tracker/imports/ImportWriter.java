package com.tcms.tracker.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.ProjectMembershipService;
import com.tcms.project.ProjectKeys;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tracker.Issue;
import com.tcms.tracker.IssueComment;
import com.tcms.tracker.IssueCommentRepository;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueRepository;
import com.tcms.tracker.IssueStatus;
import com.tcms.tracker.IssueStatusRepository;
import com.tcms.tracker.IssueType;
import com.tcms.tracker.IssueTypeRepository;
import com.tcms.tracker.board.LabelService;
import com.tcms.tracker.release.ReleaseService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The write pass: one transaction, all of it or none of it.
 *
 * <h2>Why not batch commits, which are easier and faster</h2>
 *
 * <p>A batched failure leaves <strong>part</strong> of a Jira project in the customer's new
 * tracker, with no way to tell which part, at the exact moment they are being asked to trust the
 * migration. {@code import-preview.html}'s <em>"No data has been written"</em> is worth having on
 * the far side of Confirm too: either the whole project arrived or nothing did. The cost is that
 * the read pass must finish first and is therefore bounded by heap —
 * {@code tcms.tracker.imports.max-issues}, refused by the preview and by
 * {@link ImportRunService} with the number in the message.
 *
 * <h2>The order below is a dependency order, not a preference</h2>
 *
 * <ol>
 *   <li>Reserve {@code n} keys — <strong>one</strong> {@link ProjectKeys#allocate} call, whose row
 *       lock must be held until this transaction commits.</li>
 *   <li>Releases, from the source's fix versions.</li>
 *   <li>Labels, through {@link LabelService}, an existing name read as "already there".</li>
 *   <li>Issues, EPIC then STORY then TASK, resolving parents through a map built as we go.</li>
 *   <li>Comments, then label assignments, then the run row and its dropped-field rows.</li>
 *   <li>{@link Outbox#recordCoalesced} — <strong>ONCE</strong>. Without it {@code issue_fact} does
 *       not hold a single imported issue and every board, report and percentage in the product is
 *       blind to the migration.</li>
 * </ol>
 *
 * <p>Statuses and types are <em>not</em> in that list and their absence is a decision: the mapping
 * targets only this project's own statuses ({@link ImportRunService} refuses anything else with a
 * 400), and a source type with no local counterpart lands on the type at its level through
 * {@link ImportPreviewService#matchType} — the same derivation the preview showed. An import that
 * created workflow rows would be a migration silently reconfiguring a project.
 *
 * <h2>The transaction is a {@link TransactionTemplate}, not {@code @Transactional}</h2>
 *
 * <p>{@link #run} is the {@code @Async} entry point and calls the write pass on itself, which is a
 * self-invocation Spring's proxy does not see — the annotation would be a boundary that silently is
 * not there and every RLS-protected read inside would return zero rows. {@code ReportJobRunner} and
 * {@link ImportPreviewService#assemble} took the same idiom for the same reason.
 */
@Service
public class ImportWriter {

    private static final Logger log = LoggerFactory.getLogger(ImportWriter.class);

    /**
     * The palette's label tokens, cycled over (D-h).
     *
     * <p>{@code IssueLabel.color} holds a design-token name and the column refuses
     * {@code #RRGGBB} (012 T9), so the source's own colours cannot come across — a stored hex is
     * invisible in the other colour scheme. Round-robin rather than a hash of the name, so two runs
     * of the same import produce the same colours in the same order.
     */
    private static final List<String> LABEL_COLORS = List.of(
            "label-1", "label-2", "label-3", "label-4", "label-5",
            "label-6", "label-7", "label-8", "label-9", "label-10");

    /** {@code V5}'s {@code issue_rank_default} spacing, matched so an import leaves no odd gap. */
    private static final BigDecimal RANK_SPACING = new BigDecimal("1024");

    /** What a run reaching a key that exists is told. See {@link #refuseCollisions}. */
    private static final String COLLIDED =
            "The source's issue keys are no longer free in this company — they were when the "
                    + "preview ran. Imported issues keep their original key, so the import cannot "
                    + "create a second one. Nothing was written; the project is unchanged.";

    /** What a failure with no sentence of its own is told. {@code ReportJobRunner}'s rule. */
    private static final String UNEXPLAINED =
            "The import could not be completed. Nothing was written — the project is unchanged. "
                    + "The server log has the details.";

    private final ImportSources sources;
    private final ProjectKeys keys;
    private final IssueRepository issues;
    private final IssueCommentRepository comments;
    private final IssueTypeRepository types;
    private final IssueStatusRepository statuses;
    private final LabelService labels;
    private final ReleaseService releases;
    private final ImportRunRepository runs;
    private final ImportDroppedFieldRepository droppedFields;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final ProjectMembershipService projectMembers;
    private final Outbox outbox;
    private final EntityManager entities;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ImportWriter(
            ImportSources sources,
            ProjectKeys keys,
            IssueRepository issues,
            IssueCommentRepository comments,
            IssueTypeRepository types,
            IssueStatusRepository statuses,
            LabelService labels,
            ReleaseService releases,
            ImportRunRepository runs,
            ImportDroppedFieldRepository droppedFields,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            ProjectMembershipService projectMembers,
            Outbox outbox,
            EntityManager entities,
            ObjectMapper json,
            PlatformTransactionManager transactionManager,
            Clock clock) {

        this.sources = sources;
        this.keys = keys;
        this.issues = issues;
        this.comments = comments;
        this.types = types;
        this.statuses = statuses;
        this.labels = labels;
        this.releases = releases;
        this.runs = runs;
        this.droppedFields = droppedFields;
        this.users = users;
        this.memberships = memberships;
        this.projectMembers = projectMembers;
        this.outbox = outbox;
        this.entities = entities;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * The dispatched run — read, then write, then one terminal state either way.
     *
     * <p><strong>{@code CompanyContext.openFor} is the first statement, and it has to be.</strong>
     * {@code CompanyContextTaskDecorator} runs every task submitted to an application executor with
     * the company <em>cleared</em>, so without this every RLS-protected read below returns zero
     * rows — silently, with a green log line and an import that finds nothing. The dispatch is a
     * {@code CompanyScopedEvent} so that forgetting is a compile error rather than an empty run.
     *
     * <p>It never throws. A failure is a {@code FAILED} row with a sentence somebody can read,
     * because the caller is an executor with nowhere to report one — {@code ReportJobRunner}'s
     * argument, and the reason {@link ImportReaper} exists for the failure this cannot catch.
     */
    @Async
    public void run(ImportDispatch dispatch) {
        try (var ignored = CompanyContext.openFor(dispatch)) {
            try {
                // No transaction is open here, and that is the whole of D-b's first half: a
                // transaction spanning this would hold a Postgres connection open for minutes
                // against a third party's availability, one pool slot per import.
                SourceProjectSnapshot snapshot = sources.forProvider(dispatch.connection().provider())
                        .snapshot(dispatch.connection(), dispatch.sourceProjectKey(), read -> { });

                transactions.executeWithoutResult(status -> write(dispatch, snapshot));

            } catch (ImportSourceUnavailableException unreachable) {
                // The message is written for the person watching and says what happened to their
                // project, which is the half a source's own text never carries.
                fail(dispatch.runId(), unreachable.getMessage()
                        + " Nothing was written — the project is unchanged.", unreachable);
            } catch (IllegalStateException refusal) {
                fail(dispatch.runId(), refusal.getMessage(), refusal);
            } catch (RuntimeException failure) {
                fail(dispatch.runId(), UNEXPLAINED, failure);
            }
        }
    }

    // =====================================================================

    /** Everything, in one transaction, in the order the class header sets out. */
    private void write(ImportDispatch dispatch, SourceProjectSnapshot snapshot) {
        UUID companyId = CompanyContext.require();
        UUID projectId = dispatch.projectId();
        Instant now = clock.instant();

        refuseCollisions(snapshot);

        Map<String, IssueType> typesBySourceName = typesOf(snapshot, projectId);
        List<SourceIssue> ordered = inParentOrder(snapshot, typesBySourceName);

        // ONE call for the whole run. The row lock it takes has to be held until the issues using
        // the numbers commit — a second call, or a call in its own transaction, would release it
        // early and hand out numbers that survive a rollback the issues do not.
        ProjectKeys.Allocation allocation = keys.allocate(projectId, Math.max(1, ordered.size()));

        Map<String, UUID> releaseIds = importReleases(dispatch, snapshot);
        Map<String, UUID> labelIds = importLabels(dispatch, snapshot, now);
        Map<String, IssueStatus> mapping = mappingOf(dispatch, projectId);
        Map<String, ImportedPersonView> people = matchPeople(companyId, snapshot);

        BigDecimal rankFrom = nextRank(companyId, projectId);
        Map<String, UUID> idsBySourceKey = new LinkedHashMap<>();
        Map<String, Integer> perStatus = new LinkedHashMap<>();
        int commentsWritten = 0;

        for (int index = 0; index < ordered.size(); index++) {
            SourceIssue source = ordered.get(index);
            IssueStatus target = mapping.get(fold(source.statusName()));
            if (target == null) {
                // Reachable only when the source grew a status between the confirmation and the
                // read — ImportRunService refuses an unmapped one with a 400 before this row
                // exists. Loud, because the alternative is an issue in a column nobody chose.
                throw new IllegalStateException(
                        "The source project uses a status this import was not asked about: \""
                                + source.statusName() + "\". Nothing was written — the project is "
                                + "unchanged. Run the preview again and map it.");
            }
            IssueType type = typesBySourceName.get(fold(source.typeName()));

            Issue issue = Issue.imported(
                    companyId,
                    projectId,
                    // The SOURCE's key, verbatim — this is what makes PROJ-4812 still PROJ-4812.
                    source.key(),
                    // The PROJECT's counter, never Jira's number: 4812 would push this project's
                    // counter past four thousand unused values, or collide with a local ACME-12 if
                    // it did not, and key_seq is the only column that may order issue keys (D-d).
                    allocation.seqAt(index),
                    type.getId(),
                    target.getId(),
                    parentOf(source, idsBySourceKey),
                    source.title(),
                    source.description(),
                    firstRelease(source, releaseIds),
                    assignableId(projectId, matchedId(people, source.assigneeEmail())),
                    matchedId(people, source.reporterEmail()),
                    dispatch.actorId(),
                    source.createdAt() == null ? now : source.createdAt(),
                    now);

            if (dispatch.options().preserveBacklogOrder()) {
                // The BEFORE INSERT trigger stands aside whenever the insert supplies a rank
                // (012 D-k). Without this the source's backlog order becomes insert order, which
                // is an ordering nobody chose — and the report says so (D-i).
                issue.rankAt(rankFrom.add(RANK_SPACING.multiply(BigDecimal.valueOf(index))));
            }

            Issue written = issues.save(issue);
            idsBySourceKey.put(source.key(), written.getId());
            perStatus.merge(target.getName(), 1, Integer::sum);

            commentsWritten += writeComments(companyId, written.getId(), source, people);
            assignLabels(dispatch, source, labelIds, written.getId(), now);
        }

        ImportRun run = runs.findById(dispatch.runId()).orElseThrow(() -> new IllegalStateException(
                "Import run " + dispatch.runId() + " disappeared after it was accepted"));

        List<DroppedFieldView> dropped =
                DroppedFields.of(snapshot, dispatch.options().labelPerSprint());
        for (int position = 0; position < dropped.size(); position++) {
            droppedFields.save(ImportDroppedField.of(
                    companyId, run.getId(), dropped.get(position), position));
        }

        run.complete(
                new ImportCounts(
                        ordered.size(),
                        releaseIds.size(),
                        commentsWritten,
                        labelIds.size(),
                        dropped.stream().mapToInt(DroppedFieldView::valueCount).sum()),
                asJson(appliedMapping(dispatch, mapping, perStatus)),
                asJson(List.copyOf(people.values())),
                clock.instant());
        runs.save(run);

        // ONCE, and inside this transaction. Outbox.recordCoalesced throws outside one, and
        // IssueFactOnIssuesChanged is a whole-company recompute that reads no payload — so 1482
        // events would be 1482 identical recomputes. WITHOUT THIS CALL issue_fact holds not one
        // imported issue and every board, report and percentage in the product is blind to the
        // migration.
        outbox.recordCoalesced(OutboxEvents.ISSUES_CHANGED);
    }

    /**
     * Records the failure in a transaction of its own.
     *
     * <p>Separate from whatever rolled back, for {@code ReportJobRunner.fail}'s reason: what failed
     * must not take the explanation with it. A run that fails and stays {@code RUNNING} with no
     * {@code failure_reason} is the one state nobody can act on — it is a spinner with no end.
     */
    private void fail(UUID runId, String reason, RuntimeException cause) {
        log.warn("Import run {} failed: {}", runId, reason, cause);
        try {
            transactions.executeWithoutResult(status -> runs.findById(runId).ifPresent(run -> {
                run.fail(reason == null || reason.isBlank() ? UNEXPLAINED : reason, clock.instant());
                runs.save(run);
            }));
        } catch (RuntimeException unrecordable) {
            // The database is what failed. ImportReaper is the backstop and will move the row.
            log.error("Import run {} could not be marked failed", runId, unrecordable);
        }
    }

    /**
     * The keys the company already holds, refused before anything is inserted.
     *
     * <p>The preview reports these as a {@code KEY_COLLISION} blocker, so reaching one here means
     * the company gained a colliding key between the preview and the confirmation. Checked rather
     * than left to {@code uq_issue_key}, because the constraint's message names an index and this
     * one names what happened.
     */
    private void refuseCollisions(SourceProjectSnapshot snapshot) {
        List<String> sourceKeys = snapshot.issues().stream().map(SourceIssue::key).toList();
        if (!sourceKeys.isEmpty() && !issues.findExistingKeys(sourceKeys).isEmpty()) {
            throw new IllegalStateException(COLLIDED);
        }
    }

    /**
     * EPIC first, then STORY, then TASK — and stable within each level.
     *
     * <p>{@code issue.parent_id} is a real foreign key with {@code ON DELETE RESTRICT}, and the
     * depth rule is checked against {@code issue_type.level}, so a child inserted before its parent
     * is a violation on the row rather than a resolvable ordering problem. Sorted rather than
     * topologically walked because the hierarchy is exactly three levels deep by construction
     * (scope §4) and a general walk would be machinery for a case this schema cannot produce.
     *
     * <p>The sort is stable, so the source's own order survives inside each level — which is what
     * {@link ImportOptions#preserveBacklogOrder} then ranks by.
     */
    private static List<SourceIssue> inParentOrder(
            SourceProjectSnapshot snapshot, Map<String, IssueType> byName) {

        List<SourceIssue> ordered = new ArrayList<>(snapshot.issues());
        ordered.sort(Comparator.comparingInt(source -> {
            IssueType type = byName.get(fold(source.typeName()));
            return type == null ? IssueLevel.STORY.ordinal() : type.getLevel().ordinal();
        }));
        return List.copyOf(ordered);
    }

    /**
     * Each source type on the project type the <em>preview</em> showed for it.
     *
     * <p>{@link ImportPreviewService#matchType} is the one derivation — by name, then by level.
     * A second one here would be a preview promising one type and a run writing another.
     */
    private Map<String, IssueType> typesOf(SourceProjectSnapshot snapshot, UUID projectId) {
        List<IssueType> projectTypes = types.findByProjectIdOrderByPositionAsc(projectId);
        Map<String, IssueType> matched = new LinkedHashMap<>();
        for (SourceType type : snapshot.types()) {
            matched.put(fold(type.name()), ImportPreviewService.matchType(type, projectTypes));
        }
        return matched;
    }

    private Map<String, IssueStatus> mappingOf(ImportDispatch dispatch, UUID projectId) {
        Map<UUID, IssueStatus> byId = new LinkedHashMap<>();
        statuses.findByProjectIdOrderByPositionAsc(projectId)
                .forEach(status -> byId.put(status.getId(), status));

        Map<String, IssueStatus> mapping = new LinkedHashMap<>();
        for (StatusMappingEntry entry : dispatch.statusMapping()) {
            IssueStatus target = byId.get(entry.targetStatusId());
            if (target != null) {
                mapping.put(fold(entry.sourceStatusName()), target);
            }
        }
        return mapping;
    }

    private Map<String, UUID> importReleases(
            ImportDispatch dispatch, SourceProjectSnapshot snapshot) {

        Map<String, UUID> byName = new LinkedHashMap<>();
        for (SourceVersion version : snapshot.versions()) {
            byName.put(fold(version.name()), releases.importRelease(
                    dispatch.projectId(), version.name(), version.released(), dispatch.actorId()));
        }
        return byName;
    }

    /**
     * The project's labels after this run — the source's own, plus one per sprint when that option
     * was taken (D-h).
     *
     * <p>Both sets go through {@link LabelService#importLabels} in one call, so a sprint whose name
     * is also a label name is one label rather than a duplicate the unique index would refuse.
     */
    private Map<String, UUID> importLabels(
            ImportDispatch dispatch, SourceProjectSnapshot snapshot, Instant now) {

        Set<String> names = new LinkedHashSet<>();
        for (SourceIssue issue : snapshot.issues()) {
            names.addAll(issue.labels());
            if (dispatch.options().labelPerSprint()) {
                names.addAll(issue.sprintNames());
            }
        }
        if (names.isEmpty()) {
            return Map.of();
        }
        return labels.importLabels(
                dispatch.projectId(), List.copyOf(names), LABEL_COLORS, dispatch.actorId(), now);
    }

    private void assignLabels(
            ImportDispatch dispatch,
            SourceIssue source,
            Map<String, UUID> labelIds,
            UUID issueId,
            Instant now) {

        Set<String> wanted = new LinkedHashSet<>(source.labels());
        if (dispatch.options().labelPerSprint()) {
            wanted.addAll(source.sprintNames());
        }
        Set<UUID> placed = new LinkedHashSet<>();
        for (String name : wanted) {
            UUID labelId = labelIds.get(fold(name));
            // A label whose name folded onto another's is one label, so the set is what keeps this
            // from writing issue_label_assignment's primary key twice for one issue.
            if (labelId != null && placed.add(labelId)) {
                labels.importAssignment(issueId, labelId, now);
            }
        }
    }

    private int writeComments(
            UUID companyId,
            UUID issueId,
            SourceIssue source,
            Map<String, ImportedPersonView> people) {

        int written = 0;
        for (SourceComment comment : source.comments()) {
            ImportedPersonView author = people.get(fold(comment.authorEmail()));
            comments.save(IssueComment.imported(
                    companyId,
                    issueId,
                    comment.body(),
                    author == null ? null : author.userId(),
                    // D-j: kept as TEXT rather than reassigned to somebody who did not write it,
                    // and never as a prefix on the body, which would edit somebody's words to
                    // carry metadata and come back out as content in the next export.
                    comment.authorName(),
                    comment.createdAt()));
            written++;
        }
        return written;
    }

    /**
     * Every distinct source person, matched to an {@code app_user} by email, or not.
     *
     * <p>Matched means <strong>an account that is a member of this company</strong>, not merely an
     * account with that address: an instance serves several client companies, and assigning one
     * client's issue to another client's user because the addresses agree would be a tenancy leak
     * dressed as a convenience.
     *
     * <p>Both halves are recorded. A report that listed only the matches would leave a reader
     * unable to tell "everybody was found" from "we did not look".
     */
    private Map<String, ImportedPersonView> matchPeople(
            UUID companyId, SourceProjectSnapshot snapshot) {

        Map<String, ImportedPersonView> byEmail = new LinkedHashMap<>();
        for (SourcePerson person : snapshot.people()) {
            String email = fold(person.email());
            if (email.isEmpty()) {
                // Jira Cloud hides an address unless the profile is public, which is why the
                // report has a `matched` flag rather than an assumption.
                byEmail.put(fold(person.sourceName()),
                        ImportedPersonView.unmatched(person.sourceName()));
                continue;
            }
            byEmail.put(email, resolve(companyId, person));
        }
        return byEmail;
    }

    private ImportedPersonView resolve(UUID companyId, SourcePerson person) {
        Optional<AppUser> user = users.findByEmail(person.email());
        if (user.isEmpty()) {
            return ImportedPersonView.unmatched(person.sourceName());
        }
        Optional<CompanyMembership> membership =
                memberships.findByCompanyIdAndUserId(companyId, user.get().getId());
        if (membership.isEmpty()) {
            return ImportedPersonView.unmatched(person.sourceName());
        }
        return new ImportedPersonView(
                person.sourceName(), true, user.get().getId(), membership.get().getDisplayName());
    }

    /**
     * The assignee, unless handing them work here would be a card only they cannot open.
     *
     * <p>{@code IssueService.checkAssignee} refuses an unassignable assignee outright, and this
     * silently drops one instead — deliberately, and the difference is who is being answered. That
     * one is answering a person who typed a name into a form and can pick another; this is
     * answering a migration of a thousand issues, where refusing the run because one Jira assignee
     * has no grant on the target project would make the whole import contingent on a project
     * membership list nobody was asked about. An unassigned imported issue is the same outcome as
     * an unmatched one, which {@code SourceIssue#assigneeEmail} already names as the answer.
     *
     * <p>The reporter is <strong>not</strong> filtered this way: a reporter is a record of who
     * raised the work and confers nothing, so somebody who has left the project still reported it.
     */
    private UUID assignableId(UUID projectId, UUID userId) {
        return userId != null && projectMembers.isAssignableTo(userId, projectId) ? userId : null;
    }

    private static UUID matchedId(Map<String, ImportedPersonView> people, String email) {
        ImportedPersonView person = people.get(fold(email));
        return person == null || !person.matched() ? null : person.userId();
    }

    /**
     * The first of the source's fix versions, as a release here.
     *
     * <p>{@code issue.release_id} is a single nullable FK with no join table (scope D6), so an
     * issue Jira had in three versions arrives in the first one it named. That loss is stated on
     * {@link Issue#imported} rather than in the dropped-field list, because the field itself did
     * arrive.
     */
    private static UUID firstRelease(SourceIssue source, Map<String, UUID> releaseIds) {
        return source.fixVersions().stream()
                .map(version -> releaseIds.get(fold(version)))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static UUID parentOf(SourceIssue source, Map<String, UUID> idsBySourceKey) {
        // Jira's `parent` first, then its separate epic link, which is a parent by another name
        // for an issue that has no parent of its own.
        UUID parent = source.parentKey() == null ? null : idsBySourceKey.get(source.parentKey());
        if (parent != null) {
            return parent;
        }
        return source.epicKey() == null ? null : idsBySourceKey.get(source.epicKey());
    }

    /**
     * The mapping as it was applied, with each target's name and kind captured now (D-g).
     *
     * <p>Only the statuses issues actually arrived in: a mapping line for a status the source
     * defines and no issue is in would put a zero row on the report for something that never
     * happened.
     */
    private static List<AppliedStatusMappingView> appliedMapping(
            ImportDispatch dispatch,
            Map<String, IssueStatus> mapping,
            Map<String, Integer> perStatus) {

        List<AppliedStatusMappingView> applied = new ArrayList<>();
        for (StatusMappingEntry entry : dispatch.statusMapping()) {
            IssueStatus target = mapping.get(fold(entry.sourceStatusName()));
            if (target == null) {
                continue;
            }
            applied.add(new AppliedStatusMappingView(
                    entry.sourceStatusName(),
                    target.getId(),
                    target.getName(),
                    target.getKind(),
                    perStatus.getOrDefault(target.getName(), 0)));
        }
        return List.copyOf(applied);
    }

    /**
     * The rank the next insert into this project would take.
     *
     * <p>{@code max(rank) + 1024} over every issue in the project, deleted rows included — which is
     * {@code tcms_issue_rank_default}'s own predicate, restated here rather than approximated. A
     * different base would make an imported backlog interleave with what was already there.
     */
    private BigDecimal nextRank(UUID companyId, UUID projectId) {
        Object max = entities.createNativeQuery("""
                        SELECT coalesce(max(i.rank), 0) + 1024 FROM issue i
                         WHERE i.company_id = CAST(:companyId AS uuid)
                           AND i.project_id = CAST(:projectId AS uuid)
                        """)
                .setParameter("companyId", companyId.toString())
                .setParameter("projectId", projectId.toString())
                .getSingleResult();
        return max instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(max));
    }

    /** @throws IllegalStateException the way {@link Outbox} refuses an unserializable payload. */
    private String asJson(Object document) {
        try {
            return json.writeValueAsString(document);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException(
                    "the import report's JSON columns cannot be serialized", unwritable);
        }
    }

    private static String fold(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
