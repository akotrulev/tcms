package com.tcms.storage;

/**
 * What an {@link Attachment} hangs off — {@code V1}'s polymorphic {@code owner_type}, as a type.
 *
 * <h2>Why this is an enum now, when five private strings would have done</h2>
 *
 * <p>Through §125 the owner vocabulary was five {@code private static final String}s on
 * {@link Attachment}, and the property that made the {@code storage} extraction mechanical was that
 * <strong>no call site anywhere spelled one</strong> — the factories chose, callers never did.
 *
 * <p>#49 keeps that true for <em>writes</em> and breaks it for <em>reads</em>. Listing what is
 * attached to a case means naming the owner in a query, and a repository method taking
 * {@code String ownerType} would put {@code "CASE"} back at a call site in another module — where a
 * typo is zero rows, silently, which is this schema's whole fail-closed failure mode. An enum makes
 * the same mistake a compile error and keeps the vocabulary in this module, which is the invariant
 * that actually mattered.
 *
 * <p><strong>{@link #name()} is the column value.</strong> The constants are therefore spelled
 * exactly as {@code V39}'s {@code CHECK} spells them, and {@code AttachmentOwnerTest} asserts the
 * two sets are equal by reading the constraint out of the live database — the §120 rule that a
 * catalogue-wide invariant needs a computed check rather than a careful reading. Adding a value here
 * without a migration, or a value there without a factory, fails the build.
 *
 * <p>Persisted as a {@code String} column with no {@code @Enumerated} anywhere: {@link Attachment}
 * keeps a {@code String} field, and the conversion happens in the factories. That is deliberate —
 * a database seeded by an operator, or a row written before a value was retired, must not make a
 * read throw, and {@code EnumType.STRING} on the entity would turn an unrecognised row into an
 * exception on a list endpoint rather than a row somebody can go and look at.
 */
public enum AttachmentOwner {

    /**
     * A test case's <em>definition</em> — a spec extract, a reference screenshot, a data sheet the
     * steps read from.
     *
     * <p>Not a step's. {@code STEP} was the sixth value and {@code V39} drops it: what a step looked
     * like on Tuesday is {@link #STEP_RESULT}, written since §67, and a file hanging off the step
     * <em>definition</em> would be versioned with the case, copied by a clone and frozen into every
     * snapshot that quoted the step. Nothing ever wanted that.
     */
    CASE,

    /** Evidence attached to an execution as a whole. Written since §67. */
    EXECUTION,

    /** Evidence attached to one step's recorded result. Written since §67. */
    STEP_RESULT,

    /** A documentation page's file — including the bytes an {@code IMAGE} block draws. */
    DOC_PAGE,

    /**
     * The bytes behind a {@code FILE} artifact, and the one owner also pointed at from the other
     * direction: {@code artifact.attachment_id} is a real foreign key. Both edges are written
     * deliberately — the FK is what stops a sign-off record's bytes being deleted out from under it,
     * and the owner type is what makes a per-owner sweep find them.
     */
    ARTIFACT
}
