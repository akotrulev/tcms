package com.tcms.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes a case's authored history — <strong>the one place a version is decided on</strong> (plan §6j).
 *
 * <h2>Two values, two different questions, and conflating them is the trap</h2>
 *
 * <p>{@code test_case_version} stores a {@code snapshot} and a {@code content_hash}, and it is
 * tempting to derive one from the other. They answer different questions:
 *
 * <ul>
 *   <li><strong>{@code snapshot}</strong> — <em>what did this case say?</em> The full resolved case,
 *       exactly as {@code GET /cases/{caseId}} served it at that moment: steps, both condition lists
 *       with inheritance already walked, classification, tags. Resolved rather than referential
 *       because a shared condition edited today must not change what a tester was told to do last
 *       March (handoff §3 load-bearing detail 2).</li>
 *   <li><strong>{@code content_hash}</strong> — <em>did the author change anything?</em> Computed
 *       over the <strong>authored</strong> content only, which is precisely {@link CaseEdit}: the
 *       normalized set of fields a save can change.</li>
 * </ul>
 *
 * <p>Hashing the snapshot instead is the plausible shortcut and it breaks the property the hash
 * exists for. The snapshot contains resolved values that move on their own — the owner's display
 * name, the health signals derived from run results, {@code updatedAt} — so a colleague renaming
 * themselves, or a nightly run turning a case red, would make the next save look like an edit. The
 * version list would fill with entries whose diff is empty, which is exactly the noise plan §6j
 * added the hash to keep out. It also fails the other way round: two saves that genuinely differ
 * only in a field the detail view does not serve would hash identically.
 *
 * <p>Hashing {@link CaseEdit} also makes the coupling self-maintaining. When a later slice makes
 * conditions or classification editable, they join {@code CaseEdit} to be validated at all — and
 * they join the hash in the same commit, without anyone remembering to add them here.
 *
 * <h2>Why the first version of an existing case is v2, and the v1 gap is real</h2>
 *
 * <p>Every case in the database predates this write path: {@code current_version_no} is
 * {@code NOT NULL DEFAULT 1} and no {@code test_case_version} row has ever existed. So a case's first
 * edit writes <strong>v2</strong>, and its v1 is permanently absent.
 *
 * <p>That gap is deliberate and it is the honest option. Writing the edit as v1 would label the
 * edited text as the original and destroy the only thing history is for. Backfilling a synthetic v1
 * from the current row would be worse: it would assert that the pre-edit content was the post-edit
 * content, in a table whose entire purpose is that past claims stay true. The version list showing
 * "v2 (earliest recorded)" tells the truth — history began when it began. Cases created after the
 * create endpoint ships get their v1 written with them and have no gap.
 */
@Component
public class CaseVersions {

    private final TestCaseVersionRepository versions;

    /**
     * Injected rather than constructed, so it is the application's configured mapper — the same one
     * that serializes the response. A private {@code new ObjectMapper()} here would be a second
     * serialization configuration, and the snapshot would drift from what the API actually served
     * the moment either is configured.
     */
    private final ObjectMapper json;

    public CaseVersions(TestCaseVersionRepository versions, ObjectMapper json) {
        this.versions = versions;
        this.json = json;
    }

    /**
     * Whether this edit is a new version, and which number it takes.
     *
     * @param versionNo    the number the case carries after this save — advanced when
     *                     {@code isNew}, unchanged otherwise
     * @param isNew        false when the authored content is byte-identical to the newest version
     * @param contentHash  carried so {@link #write} does not recompute it and cannot disagree
     */
    record Decision(int versionNo, boolean isNew, String contentHash) {
    }

    /**
     * Decides, without writing anything.
     *
     * <p>Split from {@link #write} so the caller can stamp the version number onto the case
     * <em>before</em> rendering the snapshot, and therefore render once. Rendering twice — once for
     * the snapshot, once for the response — is what this shape avoids, and the two renders would
     * differ by exactly the field most likely to be read closely.
     */
    Decision decide(TestCase testCase, CaseEdit edit) {
        String hash = hash(edit);

        Optional<TestCaseVersion> newest = versions.findNewestForCase(testCase.getId());
        if (newest.isPresent() && newest.get().getContentHash().equals(hash)) {
            // Plan §6j: a save whose hash is unchanged writes no version. Without this, opening a
            // case and pressing Save produces an entry, and the version list becomes a log of who
            // looked at it — useless for the thing it is for, finding the edit that broke a test.
            //
            // Compared against the NEWEST version only, not against every version the case has
            // had. Reverting a case to what v3 said is a real change from v9 and gets its own
            // version; matching an older hash means the content came back, not that nothing
            // happened.
            return new Decision(testCase.getCurrentVersionNo(), false, hash);
        }
        return new Decision(testCase.getCurrentVersionNo() + 1, true, hash);
    }

    /**
     * Writes the version row, if {@link Decision#isNew}.
     *
     * <p>Runs in the caller's transaction — the version row and the change to {@code test_case} must
     * commit together or neither, or a case's history has a hole exactly where somebody changed
     * something.
     *
     * <p>Calling this with a stale {@link Decision} is the one way to misuse the split, and it is
     * bounded: the decision is taken and used within a single method of a single caller, a few lines
     * apart. Handing the hash along in the record is what stops the two halves computing it
     * separately and disagreeing.
     *
     * @param snapshot the resolved case as the API serves it, already rendered by the caller so this
     *                 class does not become a second assembler of a case's shape
     */
    void write(
            TestCase testCase,
            CaseEdit edit,
            Decision decision,
            TestCaseDetailView snapshot,
            UUID authorId,
            Instant now) {

        if (!decision.isNew()) {
            return;
        }
        versions.save(TestCaseVersion.of(
                testCase.getId(),
                decision.versionNo(),
                serialize(snapshot),
                decision.contentHash(),
                edit.changeComment(),
                authorId,
                now));
    }

    /**
     * A stable digest of the authored content.
     *
     * <p>SHA-256 over the canonical JSON of {@link CaseEdit}. Deterministic because a record
     * serializes its components in declaration order and every collection in it preserves order —
     * there is no map in the shape, which is what would otherwise make two equal edits hash
     * differently depending on iteration order.
     *
     * <p>{@code changeComment} is part of the hashed value, which is a deliberate consequence rather
     * than an oversight: saving with no content change but a new comment writes a version. The
     * comment is authored content — it is the sentence the version list displays — and a comment
     * that silently failed to save because nothing else changed would be a note the author believes
     * they left.
     */
    private String hash(CaseEdit edit) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(json.writeValueAsBytes(edit)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException(
                    "A validated CaseEdit must be serializable", cannotSerialize);
        }
    }

    private String serialize(TestCaseDetailView snapshot) {
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException cannotSerialize) {
            // The same object was about to be serialized into the HTTP response, so this failing
            // means the response would have failed too — worth an exception rather than a version
            // row holding a placeholder that reads as the case having said nothing.
            throw new IllegalStateException(
                    "The case snapshot could not be serialized", cannotSerialize);
        }
    }
}
