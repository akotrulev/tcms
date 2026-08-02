package com.tcms.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Renders evidence rows into the wire shape — the one assembler, for the same reason
 * {@link ExecutionViews} is: the execution detail and the step list both show evidence, and two
 * renderers would let the same file appear differently depending on which list it was in.
 *
 * <h2>Two bulk lookups, never per row</h2>
 *
 * <p>The attachments in one query and the people in one more, for any number of evidence rows. A
 * per-row lookup here is the N+1 that looks harmless because each query is fast — the same argument
 * {@code repository.CaseOwners} makes, and the reason §66 unioned two id sets rather than adding a
 * second call.
 */
@Component
class EvidenceViews {

    private final AttachmentRepository attachments;
    private final ExecutionPeople people;

    EvidenceViews(AttachmentRepository attachments, ExecutionPeople people) {
        this.attachments = attachments;
        this.people = people;
    }

    /** One execution's evidence, already ordered by the caller. */
    List<EvidenceView> render(UUID companyId, UUID projectId, UUID runId, List<Evidence> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<UUID> attachmentIds = new HashSet<>();
        Set<UUID> userIds = new HashSet<>();
        for (Evidence row : rows) {
            attachmentIds.add(row.getAttachmentId());
            if (row.getCreatedBy() != null) {
                userIds.add(row.getCreatedBy());
            }
        }

        Map<UUID, Attachment> files = new HashMap<>();
        for (Attachment file : attachments.findByIdIn(attachmentIds)) {
            files.put(file.getId(), file);
        }
        Map<UUID, UserRefView> resolved = people.resolve(companyId, userIds);

        List<EvidenceView> views = new ArrayList<>(rows.size());
        for (Evidence row : rows) {
            Attachment file = files.get(row.getAttachmentId());
            if (file == null) {
                // The attachment row is unreadable while the evidence row is not — in practice a
                // company boundary RLS has already refused. Skipped rather than rendered blank: an
                // entry with no filename, no size and no working link is a broken thumbnail the
                // user can neither open nor explain, and one unreadable row must not take down the
                // list around it. ExecutionViews makes the same call for an unresolvable pin.
                continue;
            }
            views.add(new EvidenceView(
                    row.getId(),
                    file.getFilename(),
                    file.getContentType(),
                    file.getSizeBytes(),
                    contentUrl(projectId, runId, row.getExecutionId(), row.getId()),
                    // No thumbnailing in this slice. Null is the true answer for a field the
                    // contract declares nullable — a guessed url would 404 on every image.
                    null,
                    row.getCaption(),
                    // Nullable: a row with no recorded author is the same row an OWN grant refuses.
                    row.getCreatedBy() == null ? null : resolved.get(row.getCreatedBy()),
                    row.getCreatedAt()));
        }
        return List.copyOf(views);
    }

    /**
     * One execution's evidence, split by what it documents.
     *
     * @param onExecution  the verdict as a whole — {@code step_result_id IS NULL}
     * @param byStepResult keyed by {@code step_result.id}, <strong>not</strong> by step id: that is
     *                     the column the row carries, and a step with no recorded result has no
     *                     result id and therefore cannot have evidence
     */
    record Partitioned(List<EvidenceView> onExecution, Map<UUID, List<EvidenceView>> byStepResult) {
    }

    /**
     * Renders and partitions in one pass, so the two lists cannot disagree about the same file and
     * the rows are read once.
     *
     * <p>Joined by id rather than by position. {@link #render} may drop a row (an unreadable
     * attachment), so the two lists are not index-aligned — and a positional zip would then
     * misattribute every row after the first skip, quietly moving one tester's screenshot onto
     * another step. The map costs one pass and cannot do that.
     */
    Partitioned partition(UUID companyId, UUID projectId, UUID runId, List<Evidence> rows) {
        List<EvidenceView> views = render(companyId, projectId, runId, rows);
        Map<UUID, EvidenceView> byId = new HashMap<>(views.size());
        for (EvidenceView view : views) {
            byId.put(view.id(), view);
        }

        List<EvidenceView> onExecution = new ArrayList<>();
        Map<UUID, List<EvidenceView>> byStepResult = new HashMap<>();
        for (Evidence row : rows) {
            EvidenceView view = byId.get(row.getId());
            if (view == null) {
                // Dropped by render — an unreadable attachment. Skipped here too, so the two
                // halves stay consistent with each other and with what the user can open.
                continue;
            }
            if (row.getStepResultId() == null) {
                onExecution.add(view);
            } else {
                byStepResult
                        .computeIfAbsent(row.getStepResultId(), id -> new ArrayList<>())
                        .add(view);
            }
        }
        return new Partitioned(List.copyOf(onExecution), Map.copyOf(byStepResult));
    }

    /**
     * The route {@code Evidence.url} points at, built in exactly one place.
     *
     * <p>Relative and prefixed with the API base, because the server does not know its own public
     * origin — behind a reverse proxy it only ever sees {@code localhost:8080}, which is the defect
     * {@code tcms.invitation.base-url} exists to avoid one module over. A relative url is resolved
     * by the browser against the page it is already on, which is always right.
     */
    private static String contentUrl(UUID projectId, UUID runId, UUID executionId, UUID id) {
        return "/api/v1/projects/" + projectId + "/runs/" + runId + "/executions/" + executionId
                + "/evidence/" + id + "/content";
    }
}
