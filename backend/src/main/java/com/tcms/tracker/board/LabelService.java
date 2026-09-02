package com.tcms.tracker.board;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.Issue;
import com.tcms.tracker.IssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's labels, and which issues carry them.
 *
 * <h2>Two keys, and neither of them is new</h2>
 *
 * <p>Creating, renaming and deleting a label is <strong>{@code board:configure}</strong> — it is
 * project configuration, beside columns and WIP limits, which is what that key's own description
 * already says. Putting a label <em>on</em> an issue is <strong>{@code issue:edit}</strong>, because
 * it edits the issue. The catalogue has no {@code label:*} and this module adds none (D-e): a second
 * key for an act an existing key already covers is a permission somebody can hold without being able
 * to use it, and adding one is a three-document edit plus the copies pinned by
 * {@code matrixCopies.test.ts}.
 *
 * <h2>Deleted without ceremony, on purpose</h2>
 *
 * <p>{@code issue_label_assignment.label_id} is {@code ON DELETE CASCADE}, so deleting a label takes
 * it off every issue holding it. An informal axis whose deletion needed a migration plan would not
 * be informal — the SPA asks for confirmation and says how many issues carry it, and nothing on the
 * wire carries that state.
 */
@Service
public class LabelService {

    private static final String CONFIGURE = "board:configure";
    private static final String EDIT_ISSUE = "issue:edit";
    private static final int MAX_NAME = 32;

    private final ProjectService projects;
    private final IssueLabelRepository labels;
    private final IssueLabelAssignmentRepository assignments;
    private final IssueRepository issues;
    private final Clock clock;

    public LabelService(
            ProjectService projects,
            IssueLabelRepository labels,
            IssueLabelAssignmentRepository assignments,
            IssueRepository issues,
            Clock clock) {

        this.projects = projects;
        this.labels = labels;
        this.assignments = assignments;
        this.issues = issues;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controllers' mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** A field the caller can fix — a blank name, an over-long one, a colour that is not set. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Another label in this project already has that name, case-insensitively. */
        record Duplicate(String name) implements Outcome {
        }

        record Written(LabelView view) implements Outcome {
        }

        record Deleted() implements Outcome {
        }

        record Replaced(List<LabelView> views) implements Outcome {
        }
    }

    /**
     * {@code GET /projects/{projectId}/labels}.
     *
     * <p>Visibility alone, exactly as the board's configuration read is: reading a project's labels
     * is reading its vocabulary, and every one of the six project roles can already see the cards
     * they are on.
     */
    @Transactional(readOnly = true)
    public Optional<List<LabelView>> list(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project ->
                labels.findByProjectIdOrderByNameAsc(project.getId()).stream()
                        .map(LabelService::viewOf)
                        .toList());
    }

    public GateResult<Outcome> create(UUID userId, UUID projectId, LabelInput input) {
        return projects.writeGranted(userId, projectId, CONFIGURE,
                project -> applyCreate(project, userId, input));
    }

    public GateResult<Outcome> update(UUID userId, UUID labelId, LabelInput input) {
        return onLabel(userId, labelId, (project, label) -> applyUpdate(project, label, input));
    }

    public GateResult<Outcome> delete(UUID userId, UUID labelId) {
        return onLabel(userId, labelId, (project, label) -> {
            labels.delete(label);
            return new Outcome.Deleted();
        });
    }

    /**
     * {@code PUT /issues/{key}/labels} — the whole set, written over whatever was there.
     *
     * <p>A replacement rather than add/remove pairs: the SPA's control is a multi-select whose state
     * is the whole set, and two endpoints for it would let a stale client remove a label somebody
     * else had just added.
     */
    public GateResult<Outcome> putIssueLabels(UUID userId, String key, IssueLabelsInput input) {
        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (issue.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, issue.get().getProjectId(), EDIT_ISSUE,
                project -> applyIssueLabels(project, key, input));
    }

    /**
     * <strong>The importer's seam</strong> — labels by name, created if this project lacks them,
     * reused if it has them (plan 014 D-h).
     *
     * <h2>Un-gated, and that is the decision rather than an oversight</h2>
     *
     * <p>Every other write here costs {@code board:configure}, and a caller running an import may
     * not hold it: {@code issue:import} and {@code board:configure} are two different rows of §A3
     * and one role holds the first without the second. Routing the importer through
     * {@link #create} would therefore refuse a migration halfway through, for a permission about
     * <em>configuring a board</em> that nobody was asked for and that the wizard never mentions.
     * So the gate is the caller's — {@code ImportRunService} has already required
     * {@code issue:import} on this project — and this method takes a {@code projectId} it trusts
     * because the only caller obtained it from a gate.
     *
     * <p>Reusing an existing name rather than refusing it is D-h's <em>"409 read as already
     * there"</em>: an importer hits a duplicate on the second issue carrying a label and on every
     * issue after it, and a duplicate is the ordinary case rather than the failure.
     *
     * @param colors  cycled over in order, one per label this call creates — {@code Label.color} is
     *                a design-token name and the column refuses {@code #RRGGBB} (012 T9), so the
     *                source's own colours cannot come across and the report says they were assigned
     * @return every requested name mapped to its label id, created or found, folded to lower case
     *         the way {@code uq_issue_label_name} folds it
     */
    public Map<String, UUID> importLabels(
            UUID projectId, List<String> names, List<String> colors, UUID actorId, Instant now) {

        Map<String, UUID> byFoldedName = new LinkedHashMap<>();
        labels.findByProjectIdOrderByNameAsc(projectId).forEach(label ->
                byFoldedName.put(label.getName().toLowerCase(Locale.ROOT), label.getId()));

        int created = 0;
        for (String name : names) {
            String trimmed = name.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Truncated rather than refused: a source label longer than this product's limit is
            // not a reason to fail somebody's whole migration, and MAX_NAME is a rendering bound
            // (the chip wraps over the card face) rather than a correctness one.
            String bounded = trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
            String folded = bounded.toLowerCase(Locale.ROOT);
            if (byFoldedName.containsKey(folded)) {
                continue;
            }
            IssueLabel label = labels.save(IssueLabel.create(
                    UUID.randomUUID(), CompanyContext.require(), projectId, bounded,
                    colors.get(created % colors.size()), actorId, now));
            byFoldedName.put(folded, label.getId());
            created++;
        }
        return byFoldedName;
    }

    /**
     * Puts one label on one issue, for the importer.
     *
     * <p>Un-gated for {@link #importLabels}' reason, and unconditional: the importer creates the
     * issue in the same transaction, so there is no assignment to collide with and no read to do
     * first.
     */
    public void importAssignment(UUID issueId, UUID labelId, Instant now) {
        assignments.save(
                IssueLabelAssignment.of(CompanyContext.require(), issueId, labelId, now));
    }

    // =====================================================================

    private GateResult<Outcome> onLabel(
            UUID userId, UUID labelId, BiFunction<Project, IssueLabel, Outcome> write) {

        Optional<IssueLabel> resolved = labels.findScopedById(labelId);
        if (resolved.isEmpty()) {
            // Resolve first, for BoardConfigService's reason: the project to gate against is a
            // property of the stored row, so a caller whose label does not exist cannot be told 403
            // without the server inventing which project they failed on.
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, resolved.get().getProjectId(), CONFIGURE,
                project -> labels.findScopedById(labelId)
                        .map(label -> write.apply(project, label))
                        .orElseThrow());
    }

    private Outcome applyCreate(Project project, UUID userId, LabelInput input) {
        Optional<Outcome> refused = validate(input);
        if (refused.isPresent()) {
            return refused.get();
        }
        String name = input.name().strip();
        if (taken(project.getId(), name, null)) {
            return new Outcome.Duplicate(name);
        }
        IssueLabel created = labels.save(IssueLabel.create(
                UUID.randomUUID(), CompanyContext.require(), project.getId(),
                name, input.color().strip(), userId, clock.instant()));
        return new Outcome.Written(viewOf(created));
    }

    private Outcome applyUpdate(Project project, IssueLabel label, LabelInput input) {
        Optional<Outcome> refused = validate(input);
        if (refused.isPresent()) {
            return refused.get();
        }
        String name = input.name().strip();
        if (taken(project.getId(), name, label.getId())) {
            return new Outcome.Duplicate(name);
        }
        label.redefine(name, input.color().strip());
        return new Outcome.Written(viewOf(labels.save(label)));
    }

    private Outcome applyIssueLabels(Project project, String key, IssueLabelsInput input) {
        Issue issue = issues.findByIssueKeyAndDeletedAtIsNull(key).orElseThrow();
        List<UUID> wanted = input == null || input.labelIds() == null
                ? List.of() : input.labelIds();

        Map<UUID, IssueLabel> known = new LinkedHashMap<>();
        labels.findByProjectIdOrderByNameAsc(project.getId())
                .forEach(label -> known.put(label.getId(), label));

        Set<UUID> foreign = new LinkedHashSet<>(wanted);
        foreign.removeAll(known.keySet());
        if (!foreign.isEmpty()) {
            // Named as a field error rather than a 404: the issue is reachable and the labels are
            // the caller's mistake, and a 404 here would say the issue had been deleted.
            return new Outcome.Invalid(Map.of("labelIds", List.of(
                    foreign.size() + " of those labels belong to another project, or have been "
                            + "deleted since this issue was opened.")));
        }

        Set<UUID> keep = new LinkedHashSet<>(wanted);
        List<IssueLabelAssignment> held = assignments.findByIssueId(issue.getId());
        held.stream()
                .filter(assignment -> !keep.contains(assignment.getLabelId()))
                .forEach(assignments::delete);
        Set<UUID> already = held.stream()
                .map(IssueLabelAssignment::getLabelId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assignments.flush();

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();
        keep.stream()
                .filter(labelId -> !already.contains(labelId))
                .forEach(labelId -> assignments.save(
                        IssueLabelAssignment.of(companyId, issue.getId(), labelId, now)));
        assignments.flush();

        List<LabelView> written = new ArrayList<>();
        known.values().stream()
                .filter(label -> keep.contains(label.getId()))
                .forEach(label -> written.add(viewOf(label)));
        return new Outcome.Replaced(written);
    }

    private Optional<Outcome> validate(LabelInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            return Optional.of(new Outcome.Invalid(Map.of("name", List.of(
                    "A label needs a name — it is the whole of what a chip on a card says."))));
        }
        if (input.name().strip().length() > MAX_NAME) {
            return Optional.of(new Outcome.Invalid(Map.of("name", List.of(
                    "A label name is at most " + MAX_NAME + " characters. Longer than that and the "
                            + "chip wraps over the card face it is meant to be scanned from."))));
        }
        if (input.color() == null || input.color().isBlank()) {
            return Optional.of(new Outcome.Invalid(Map.of("color", List.of(
                    "Name one of the palette's label tokens — label-1 to label-10."))));
        }
        return Optional.empty();
    }

    /**
     * Whether the name is already in use in this project.
     *
     * <p>Case-insensitively, because {@code uq_issue_label_name} is on {@code lower(name)} — so
     * without this the caller gets a constraint name from Postgres instead of a sentence.
     */
    private boolean taken(UUID projectId, String name, UUID excluding) {
        String folded = name.toLowerCase(Locale.ROOT);
        return labels.findByProjectIdOrderByNameAsc(projectId).stream()
                .filter(label -> !label.getId().equals(excluding))
                .anyMatch(label -> label.getName().toLowerCase(Locale.ROOT).equals(folded));
    }

    private static LabelView viewOf(IssueLabel label) {
        return new LabelView(label.getId(), label.getName(), label.getColor());
    }
}
