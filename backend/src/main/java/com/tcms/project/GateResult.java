package com.tcms.project;

/**
 * The outcome of gating a <strong>write</strong> — three states, because a write has one more than
 * a read does.
 *
 * <h2>Why every read in this application returns {@link java.util.Optional} and a write cannot</h2>
 *
 * <p>A read collapses "no such project", "another company's project" and "yours but not granted"
 * into one empty {@link java.util.Optional} and one bodiless 404, deliberately: telling them apart
 * makes the endpoint an oracle for what exists in another client's tenancy (§6t, handoff §3 lesson
 * 13). {@link ProjectService#readVisible} exists so no read has to remember that.
 *
 * <p>A write adds a state that is <em>not</em> a concealment question. A caller who holds
 * {@code project:read} on this project but not {@code case:edit} has already read the case — they
 * are looking at it in the editor. Answering 404 conceals nothing they do not already know and
 * instead tells them their case has been deleted, which is both false and alarming about the one
 * thing a test repository must never seem to have done. So that state answers 403, and the two
 * genuinely unreachable states keep answering 404 exactly as before.
 *
 * <p><strong>Since §87 a read can want these three states too</strong>, and it does not weaken the
 * rule above — it locates it. {@link ProjectService#readGranted} gates the reads whose permission is
 * a <em>second</em> question, asked of a caller whose project visibility has already been settled;
 * {@code report:view} is the first. The concealment argument is about visibility, which that method
 * still answers with {@link Unreachable}. What is left for {@link Denied} is a feature of a project
 * the caller is demonstrably looking at, where 404 would claim their own report request had
 * vanished.
 *
 * <p>Modelled as a sealed interface rather than as an enum beside a nullable value, so the
 * controller's {@code switch} over it is exhaustive at compile time. The failure that guards
 * against is the specific one this type exists to prevent: a fourth outcome added later and handled
 * in two of the three write endpoints, with the third defaulting to whatever its {@code else}
 * branch happened to be.
 *
 * @param <T> what the write produced, present only in {@link Allowed}
 */
public sealed interface GateResult<T> {

    /**
     * No such project or case, in this company or at all — and "in another company" is folded in
     * here rather than distinguished, for the reason the header gives.
     */
    record Unreachable<T>() implements GateResult<T> {
    }

    /**
     * The caller may read the target and does not hold the permission the action needs.
     *
     * <p>Carries no detail about what was missing. The SPA's copy for this is "you don't have
     * permission to edit this case", which is actionable without naming the permission key —
     * naming it would publish the catalogue to someone who cannot use it.
     */
    record Denied<T>() implements GateResult<T> {
    }

    /** The write ran, and this is what it produced. */
    record Allowed<T>(T value) implements GateResult<T> {
    }
}
