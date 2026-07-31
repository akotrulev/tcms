package com.tcms.repository;

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
 * Writes a suite's <strong>composition</strong> history — the counterpart to {@link CaseVersions},
 * and deliberately a much smaller one.
 *
 * <h2>What this writes today: v1, at creation, and nothing else</h2>
 *
 * <p>{@code V1}'s comment on {@code suite_version.snapshot} defines a suite version as composition —
 * member cases, order, nesting — and explicitly not the content of its cases. Measured against that
 * definition, of the three writes this slice ships:
 *
 * <ul>
 *   <li><strong>create</strong> changes a composition (the folder exists, empty, under its parent) —
 *       so it writes <strong>v1</strong>;</li>
 *   <li><strong>rename</strong> changes content, not composition — so it writes <strong>nothing</strong>,
 *       and adding a bump there would start the every-edit-bumps-every-ancestor spiral the schema
 *       comment exists to refuse;</li>
 *   <li><strong>delete</strong> ends the row's history rather than extending it; the deletion is in
 *       {@code audit_event}, which is the table that answers "who removed this".</li>
 * </ul>
 *
 * <h2>Why v1 is written at all, when nothing reads it yet</h2>
 *
 * <p>Handoff §3 lesson 5: versioning is written from M1 even though the diff UI comes later, because
 * history you did not record is history you never get back. And §29's precedent is the sharper half —
 * a row created after the write path exists must not be born with the same gap as the rows that
 * predate it. Every suite in the database today has {@code current_version_no = 1} and no version
 * row; suites created here have both, so the two populations stay distinguishable by the fact that
 * matters rather than by a flag somebody maintains.
 *
 * <h2>What owes the later versions, named so the debt is not silent</h2>
 *
 * <ul>
 *   <li>moving a case between suites — the case-move slice, which changes two suites' membership;</li>
 *   <li>reparenting or reordering a suite — the move slice this one deliberately left out;</li>
 *   <li>deleting a case out of a suite — the delete slice that {@code case:delete}'s {@code OWN}
 *       qualifier is still owed.</li>
 * </ul>
 *
 * <p>Each of those is where a {@code decide()} sibling of {@link CaseVersions#decide} belongs. There
 * is none here, because a "has the composition changed" comparison with exactly one caller that
 * always answers yes is a branch no test can make take its other path.
 */
@Component
public class SuiteVersions {

    private final SuiteVersionRepository versions;

    /**
     * The application's configured mapper, injected for {@link CaseVersions}' reason: a private
     * {@code new ObjectMapper()} is a second serialization configuration, and the snapshot drifts
     * from what the API serves the moment either is configured.
     */
    private final ObjectMapper json;

    public SuiteVersions(SuiteVersionRepository versions, ObjectMapper json) {
        this.versions = versions;
        this.json = json;
    }

    /**
     * The composition of one suite at one moment.
     *
     * <p>A record rather than the {@link SuiteView} the API serves, which is the opposite of the
     * choice {@link CaseVersions} makes and for a reason that follows from the definition: the view
     * carries {@code name}, {@code description} and {@code caseCount}, of which the first two are
     * content and the third is a number that changes without this suite changing at all. Snapshotting
     * the view would make the hash move on a rename and on any case edit beneath — which is exactly
     * the coupling the schema comment refuses.
     *
     * @param parentId the nesting, which is the whole of a suite's structural position
     * @param caseIds  the membership, in order. Empty at creation, and empty is the value: a folder
     *                 with no cases is the normal state of a new one, not a missing measurement.
     */
    record Composition(UUID parentId, List<UUID> caseIds) {
    }

    /**
     * Writes v1 for a newly created suite, in the caller's transaction.
     *
     * <p>Same transaction as the {@code suite} row and never after it, for {@link CaseVersions}'
     * reason: a committed create whose version row failed to write is a folder whose history has a
     * hole exactly where it began.
     */
    void writeInitial(Suite suite, UUID authorId, Instant now) {
        // Empty membership, not "unknown": a suite is created before anything can be put in it, so
        // there is no window in which this could be a partial reading of a fuller composition.
        Composition composition = new Composition(suite.getParentId(), List.of());

        versions.save(SuiteVersion.of(
                suite.getId(),
                // Not a decide() call. suite.current_version_no is 1 on a fresh row by column
                // default, and CaseVersions.decide answers currentVersionNo + 1 — which is precisely
                // the bug §29 caught on cases, where a created row would take v2 and its v1 would
                // never exist.
                1,
                serialize(composition),
                hash(composition),
                authorId,
                now));
    }

    /**
     * A stable digest of the composition — SHA-256 over its canonical JSON.
     *
     * <p>Deterministic because a record serializes its components in declaration order and the one
     * collection in it preserves order. Order is content here rather than incidental: a suite whose
     * cases were resequenced has a different composition, which is the thing this hash is for.
     */
    private String hash(Composition composition) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(composition)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException("A Composition must be serializable", cannotSerialize);
        }
    }

    private String serialize(Composition composition) {
        try {
            return json.writeValueAsString(composition);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException("A Composition must be serializable", cannotSerialize);
        }
    }
}
