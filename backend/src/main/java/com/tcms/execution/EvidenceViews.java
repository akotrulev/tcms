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
     * Groups a batch by execution, for the run detail.
     *
     * <p>Returns rows whose {@code stepResultId} is null <em>and</em> those it is not — the caller
     * partitions, because only it knows which list it is filling.
     */
    Map<UUID, List<Evidence>> byExecution(Collection<Evidence> rows) {
        Map<UUID, List<Evidence>> grouped = new HashMap<>();
        for (Evidence row : rows) {
            grouped.computeIfAbsent(row.getExecutionId(), id -> new ArrayList<>()).add(row);
        }
        return grouped;
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
