package com.tcms.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes a plan's <strong>scope</strong> history — {@code test_plan_version}'s first writer, and the
 * last of plan §6j's four versioned aggregates to get one.
 *
 * <p>{@code repository.CaseVersions} and {@code repository.SuiteVersions} are the two siblings; this
 * one sits between them in shape. Like a suite's, the versioned thing is structure rather than prose
 * ({@link PlanSnapshot} says which fields and why). <del>Unlike a suite's, there is a real
 * {@link #decide} here — a suite's composition could only ever change at creation when
 * {@code SuiteVersions} was written, so a "has it changed" branch there had a path no test could
 * make it take.</del> → §238 gave {@code SuiteVersions} a {@link #decide} of its own for exactly the
 * reason this one exists, so the three siblings now agree on shape. A plan's scope is edited by the
 * endpoint this class was written for, so both arms are reachable and both are covered.
 *
 * <h2>Two writes, and the second one usually writes nothing</h2>
 *
 * <ul>
 *   <li><strong>{@link #writeInitial}</strong> — v1, with the plan, in its transaction.</li>
 *   <li><strong>{@link #decide} + {@link #write}</strong> — on an edit, and only when the scope
 *       actually moved. Renaming a plan, rewriting its description, or saving it unchanged writes no
 *       row and leaves {@code current_version_no} alone.</li>
 * </ul>
 *
 * <h2>Why the first version of an existing plan is v2, and the v1 gap is real</h2>
 *
 * <p>Every plan created before this class existed has {@code current_version_no = 1} and no version
 * row, because nothing ever wrote one. So the first scope edit of such a plan writes
 * <strong>v2</strong>, and its v1 is permanently absent.
 *
 * <p>That gap is deliberate and it is the honest option — {@code CaseVersions}' argument, unchanged.
 * Writing the edit as v1 would label the edited scope as the original and destroy the only thing
 * history is for; backfilling a synthetic v1 from the current row would be worse, asserting that the
 * pre-edit scope was the post-edit scope in a table whose whole purpose is that past claims stay
 * true. Plans created through {@code POST} from this slice on have no gap.
 */
@Component
class PlanVersions {

    private final TestPlanVersionRepository versions;

    /**
     * The application's configured mapper, injected for {@code CaseVersions}' reason: a private
     * {@code new ObjectMapper()} is a second serialization configuration, and the snapshot drifts
     * from what the API serves the moment either is configured. It is also what makes
     * {@code milestoneDate} serialize as {@code "2026-09-01"} rather than as an array of ints, which
     * a hash computed on a differently-configured mapper would not agree with.
     */
    private final ObjectMapper json;

    PlanVersions(TestPlanVersionRepository versions, ObjectMapper json) {
        this.versions = versions;
        this.json = json;
    }

    /**
     * Whether this edit is a new version of the scope, and which number it takes.
     *
     * @param versionNo   the number the plan carries after this save — advanced when {@code isNew},
     *                    unchanged otherwise
     * @param isNew       false when the scope is byte-identical to the newest recorded version
     * @param contentHash carried so {@link #write} does not recompute it and cannot disagree
     */
    record Decision(int versionNo, boolean isNew, String contentHash) {
    }

    /**
     * Writes v1 for a newly created plan, in the caller's transaction.
     *
     * <p>Same transaction as the {@code test_plan} row and never after it: a committed create whose
     * version row failed to write is a plan whose history has a hole exactly where it began.
     *
     * <p>Not a {@link #decide} call, for {@code CaseVersions.initial}'s reason — {@code decide}
     * answers {@code currentVersionNo + 1} and a new row's counter is already 1, so a created plan
     * would take v2 and its v1 would never exist. It also cannot be a lookup: a plan being created
     * has no versions, and an empty answer from {@link TestPlanVersionRepository#findNewestForPlan}
     * would mean two different things.
     */
    void writeInitial(TestPlan plan, PlanSnapshot snapshot, UUID authorId, Instant now) {
        versions.save(TestPlanVersion.of(
                plan.getId(), 1, serialize(snapshot), hash(snapshot), authorId, now));
    }

    /**
     * Decides, without writing anything.
     *
     * <p>Split from {@link #write} so the caller can stamp the version number onto the plan
     * <em>before</em> the snapshot is taken, and therefore take it once — {@code CaseVersions}' shape.
     */
    Decision decide(TestPlan plan, PlanSnapshot snapshot) {
        String hash = hash(snapshot);

        Optional<TestPlanVersion> newest = versions.findNewestForPlan(plan.getId());
        if (newest.isPresent() && newest.get().getContentHash().equals(hash)) {
            // Plan §6j: a save whose hash is unchanged writes no version. Without this, opening a
            // plan and pressing Save produces an entry, and the scope history becomes a log of who
            // looked at it — useless for the thing it is for, which is telling a release meeting
            // when what was being tested last changed.
            //
            // Compared against the NEWEST version only. Restoring a plan's scope to what v3 covered
            // is a real change from v9 and gets its own version; matching an older hash means the
            // scope came back, not that nothing happened.
            return new Decision(plan.getCurrentVersionNo(), false, hash);
        }
        return new Decision(plan.getCurrentVersionNo() + 1, true, hash);
    }

    /**
     * Writes the version {@link #decide} said was new.
     *
     * <p>Takes the decision rather than recomputing the hash, so the number stamped on the plan and
     * the number on the row cannot come from two different answers.
     */
    void write(TestPlan plan, PlanSnapshot snapshot, Decision decision, UUID authorId, Instant now) {
        versions.save(TestPlanVersion.of(
                plan.getId(),
                decision.versionNo(),
                serialize(snapshot),
                decision.contentHash(),
                authorId,
                now));
    }

    /**
     * A stable digest of the scope — SHA-256 over its canonical JSON.
     *
     * <p>Deterministic because a record serializes its components in declaration order and every
     * collection reachable from {@link PlanSnapshot} is <strong>sorted</strong>: {@link PlanScope}
     * sorts both id lists precisely so this hash cannot move when the database returns the same rows
     * in a different order, and the criteria keep the author's order, which is content — a plan
     * whose gates were reordered has been edited.
     */
    private String hash(PlanSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(snapshot)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException("A PlanSnapshot must be serializable", cannotSerialize);
        }
    }

    private String serialize(PlanSnapshot snapshot) {
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException("A PlanSnapshot must be serializable", cannotSerialize);
        }
    }
}
