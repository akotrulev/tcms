package com.tcms.documentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes a page's authored history — {@code doc_page_version}.
 *
 * <p>Written from this slice although nothing reads it, for handoff §3 lesson 5's reason: history
 * you did not record is history you never get back, and the diff view can be built later against
 * real rows or against none — only one of those remains a choice.
 *
 * <h2>The hash decides, not the fact that a save happened (plan §6j)</h2>
 *
 * <p>Opening a page and pressing Save must not manufacture a version. So the snapshot is hashed and
 * compared with the newest stored hash: equal means nothing is written and
 * {@code current_version_no} does not move.
 *
 * <p>That check is what makes a <strong>retitle</strong> interesting. A title is content — it is the
 * page's first line as far as a reader is concerned, and it is in the search vector's 'A' weight —
 * so it is inside the snapshot and a retitle does author a version. This is the opposite of
 * {@code SuiteVersions}, where a rename writes nothing, and the difference is not an inconsistency:
 * a suite version is defined as <em>composition</em>, so a folder's name is outside it by
 * definition, while a page version is the page.
 *
 * <h2>What is deliberately not recorded: the status change</h2>
 *
 * <p>Publishing does not write a version and does not bump the number. Nobody authored anything, and
 * a history reading "v4 — published, v5 — unpublished, v6 — published" is a history whose numbers no
 * longer count edits. The publication decision belongs to {@code audit_event}, which is the table
 * that answers "who did that".
 */
@Component
class PageVersions {

    private final DocPageVersionRepository versions;

    /**
     * The application's configured mapper, injected for {@code SuiteVersions}' reason: a private
     * {@code new ObjectMapper()} is a second serialization configuration, and the snapshot drifts
     * from what the API serves the moment either is configured.
     */
    private final ObjectMapper json;

    PageVersions(DocPageVersionRepository versions, ObjectMapper json) {
        this.versions = versions;
        this.json = json;
    }

    /**
     * The page's content at one moment.
     *
     * <p>A record of its own rather than the {@link DocPageView} the API serves, because the view
     * carries {@code updatedAt} and {@code lockVersion} — two values that move on every save
     * including one that changed nothing, which would make the hash useless at the one job it has.
     *
     * @param blocks in order, carrying their ids. The ids are in the snapshot deliberately: a
     *               version that recorded only the text could not answer "was this paragraph edited,
     *               or replaced?" — which is exactly the question the id-preserving edit exists to
     *               make answerable.
     */
    private record Snapshot(String title, List<SnapshotBlock> blocks) {
    }

    private record SnapshotBlock(UUID id, DocBlockType blockType, Integer level, String text) {
    }

    /**
     * Writes v1 for a newly created page, in the caller's transaction.
     *
     * <p>Same transaction as the page row and never after it, for {@code CaseVersions}' reason: a
     * committed create whose version row failed to write is a page whose history has a hole exactly
     * where it began.
     *
     * <p>Not a {@link #recordEdit} call with an empty "previous": that method's whole job is
     * deciding whether anything changed, and at creation the answer is definitionally yes. §29's
     * defect on cases was precisely this — a created row taking v2 while its v1 never existed.
     */
    void writeInitial(DocPage page, List<DocBlock> blocks, UUID authorId, Instant now) {
        Snapshot snapshot = snapshotOf(page, blocks);
        versions.save(DocPageVersion.of(
                page.getId(), 1, serialize(snapshot), hash(snapshot), authorId, now));
    }

    /**
     * Writes a version if the content actually changed, and returns whether it did.
     *
     * <p>Bumps {@link DocPage#bumpVersion} itself rather than leaving it to the caller: the row's
     * {@code current_version_no} and the newest {@code version_no} are the same fact stored twice,
     * and a caller that writes one without the other produces a page whose displayed version matches
     * no history entry.
     */
    boolean recordEdit(DocPage page, List<DocBlock> blocks, UUID authorId, Instant now) {
        Snapshot snapshot = snapshotOf(page, blocks);
        String hash = hash(snapshot);

        boolean unchanged = versions.findLatest(page.getId())
                .map(latest -> latest.getContentHash().equals(hash))
                .orElse(false);
        if (unchanged) {
            return false;
        }

        int next = page.getCurrentVersionNo() + 1;
        versions.save(DocPageVersion.of(
                page.getId(), next, serialize(snapshot), hash, authorId, now));
        page.bumpVersion(next);
        return true;
    }

    private static Snapshot snapshotOf(DocPage page, List<DocBlock> blocks) {
        return new Snapshot(
                page.getTitle(),
                blocks.stream()
                        .sorted((left, right) ->
                                Integer.compare(left.getPosition(), right.getPosition()))
                        .map(block -> new SnapshotBlock(
                                block.getId(),
                                block.getBlockType(),
                                block.getContent() == null ? null : block.getContent().level(),
                                block.getContent() == null ? null : block.getContent().text()))
                        .toList());
    }

    /**
     * A stable digest of the snapshot — SHA-256 over its canonical JSON.
     *
     * <p>Deterministic because a record serializes its components in declaration order and the block
     * list is sorted by position first. Order is content: a page whose paragraphs were resequenced
     * is a different page, which is the thing this hash is for.
     */
    private String hash(Snapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(snapshot)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException("A Snapshot must be serializable", cannotSerialize);
        }
    }

    private String serialize(Snapshot snapshot) {
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException("A Snapshot must be serializable", cannotSerialize);
        }
    }
}
