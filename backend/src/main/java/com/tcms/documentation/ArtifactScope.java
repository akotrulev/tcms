package com.tcms.documentation;

/**
 * Which level an artifact belongs to — plan §6n's two scopes.
 *
 * <p>Not a presentation detail. It decides three separate things: whether the row carries a
 * {@code plan_id}, whether a plan's register shows the row as {@code inherited}, and — since V19 —
 * whether a Test Engineer may write it at all.
 */
public enum ArtifactScope {

    /**
     * The project's own, durable across cycles: the test strategy, the environment guide, the
     * reusable data sets.
     *
     * <p><strong>Inherited read-only by every plan</strong>, which is why editing one is a Lead-level
     * act (roles spec, Artifacts table). A tester opening a cycle should find the environment guide
     * without hunting for it, and must not be able to rewrite the project's copy from inside one
     * cycle — the alternative is that every release takes its own copy and they diverge immediately,
     * the failure plan §6n names as the single most common one for a register.
     */
    PROJECT,

    /**
     * One cycle's own: the sign-off, the environment snapshot as it was at execution time, the
     * known-issues list.
     *
     * <p>Belongs to the plan, so a Test Engineer running that cycle curates it.
     */
    PLAN
}
