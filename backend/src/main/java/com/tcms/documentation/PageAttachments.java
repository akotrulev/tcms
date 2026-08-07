package com.tcms.documentation;

import com.tcms.storage.Attachment;
import com.tcms.storage.AttachmentOwner;
import com.tcms.storage.AttachmentRepository;
import com.tcms.tenancy.CompanyContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A page's files, rendered — the assembly {@link DocPageService}, {@link DocPageWriteService} and
 * {@link PlanDocumentService} all share.
 *
 * <p>{@link BlockLinks#forPage} in shape and for the same reason: three call sites build a
 * {@link DocPageView}, and an assembly written out at each of them is three places the file list can
 * come out differently — the drift that matters being a page rendered after an edit showing a
 * different set from the same page rendered by a {@code GET} a second later.
 *
 * <h2>Two queries, and the second one is the one that goes wrong</h2>
 *
 * <p>The rows are one query; the uploaders are one more, batched through {@link ArtifactPeople}
 * exactly as the register's are. Resolving a name per row is the N+1 that looks harmless because
 * each query is fast, and a specification page with twenty screenshots on it is not unusual.
 *
 * <p><strong>The uploader map is a {@link java.util.HashMap} and the lookup key is nullable.</strong>
 * {@code attachment.created_by} is nullable in {@code V1}, and {@code Map.of().get(null)} throws by
 * contract — §38's trap, which §121 hit a second time and which 500'd every case detail on the
 * common path. {@link ArtifactPeople#resolve} returns a {@code HashMap} on both arms for exactly
 * this; this class must not "tidy" that into an immutable empty map.
 */
@Component
class PageAttachments {

    private final AttachmentRepository attachments;
    private final ArtifactPeople people;

    PageAttachments(AttachmentRepository attachments, ArtifactPeople people) {
        this.attachments = attachments;
        this.people = people;
    }

    /**
     * Everything attached to one page, oldest first.
     *
     * <p>Singular where {@code repository.CaseRenderer.attachmentsFor} is plural, and that is not an
     * inconsistency: cases are rendered up to 500 at a time by {@code CaseBulkService}, and pages are
     * only ever rendered one at a time. The tree endpoint serves {@link DocPageSummaryView}, which
     * has no file list — so there is no batch caller here to design for, and inventing the plural
     * form would be the seam-ahead-of-its-consumer §121 phase 3 deleted.
     */
    List<DocPageAttachmentView> forPage(UUID projectId, UUID pageId) {
        List<Attachment> rows = attachments.findOwnedBy(AttachmentOwner.DOC_PAGE, List.of(pageId));
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<UUID> uploaderIds = new HashSet<>();
        for (Attachment row : rows) {
            if (row.getCreatedBy() != null) {
                uploaderIds.add(row.getCreatedBy());
            }
        }

        Map<UUID, UserRefView> named = people.resolve(CompanyContext.require(), uploaderIds);

        List<DocPageAttachmentView> views = new ArrayList<>(rows.size());
        for (Attachment row : rows) {
            views.add(DocPageAttachmentView.of(
                    projectId, pageId, row, named.get(row.getCreatedBy())));
        }
        return views;
    }
}
