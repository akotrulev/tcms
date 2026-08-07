package com.tcms.documentation;

import com.tcms.storage.Attachment;
import com.tcms.storage.AttachmentOwner;
import com.tcms.storage.AttachmentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The bytes behind a register's {@code FILE} rows, resolved in one query.
 *
 * <p>{@link PageAttachments} in shape and the opposite of it in arity, which is the distinction
 * worth keeping: a page is rendered one at a time so its file list is singular, and a register is
 * rendered whole — a mature project's is dozens of rows — so a per-row lookup here is the N+1 that
 * looks harmless because each query is fast. {@code AttachmentRepository.findOwnedBy} is plural and
 * has no singular sibling for exactly this reason, and this class is the caller that argument was
 * about.
 *
 * <h2>One attachment per artifact, and this is where that stops being an assumption</h2>
 *
 * <p>{@code attachment} has no uniqueness constraint over {@code (owner_type, owner_id)} — it cannot
 * have one, because a case and a page legitimately hold many — so "a {@code FILE} artifact has one
 * file" is enforced by {@link Artifact#getAttachmentId()} being a single column and by nothing else.
 * {@link #forArtifacts} therefore keys on <strong>the artifact's own {@code attachment_id}</strong>
 * and not on the owner side. Reading the owner side instead would look equivalent, and would quietly
 * pick one row of two the day anything ever wrote a second — where the payload column cannot,
 * because it names exactly one.
 */
@Component
class ArtifactFiles {

    private final AttachmentRepository attachments;

    ArtifactFiles(AttachmentRepository attachments) {
        this.attachments = attachments;
    }

    /**
     * The attachment row for every artifact in {@code rows} that has one, keyed by artifact id.
     *
     * <p>An artifact whose {@code attachment_id} names no surviving row is simply absent from the
     * map, and the register renders it with no filename rather than failing. That is not a state the
     * product can reach today — the delete removes both together and the foreign key refuses the
     * order that would leave it — but a projection that throws on it would turn a repairable data
     * fault into an unreadable register.
     */
    Map<UUID, Attachment> forArtifacts(List<Artifact> rows) {
        List<UUID> attachmentIds = new ArrayList<>();
        for (Artifact artifact : rows) {
            if (artifact.getAttachmentId() != null) {
                attachmentIds.add(artifact.getAttachmentId());
            }
        }
        if (attachmentIds.isEmpty()) {
            // The ordinary state of every register with no uploads in it, and an empty collection
            // bound into SQL `IN ()` is a syntax error — ArtifactPeople's note, same reason.
            return new HashMap<>();
        }

        Map<UUID, Attachment> byId = new HashMap<>();
        for (Attachment row : attachments.findByIdIn(attachmentIds)) {
            byId.put(row.getId(), row);
        }

        Map<UUID, Attachment> byArtifact = new HashMap<>();
        for (Artifact artifact : rows) {
            Attachment file = byId.get(artifact.getAttachmentId());
            if (file != null) {
                byArtifact.put(artifact.getId(), file);
            }
        }
        return byArtifact;
    }

    /**
     * The one attachment behind one artifact — the download's lookup, and deliberately not served
     * from {@link #forArtifacts}.
     *
     * <p>Singular where the class's other method is plural, and legitimate here for the reason
     * {@code AttachmentRepository}'s plural-only rule is about: the caller is a single-resource
     * endpoint that will never be inside a loop over a register. Both halves of the predicate are
     * load-bearing — an id alone would let {@code /artifacts/{a}/content} serve the bytes of an
     * attachment reached through an artifact that does not own it.
     */
    Optional<Attachment> forArtifact(Artifact artifact) {
        return artifact.getAttachmentId() == null
                ? Optional.empty()
                : attachments.findOwnedById(
                        AttachmentOwner.ARTIFACT, artifact.getId(), artifact.getAttachmentId());
    }
}
