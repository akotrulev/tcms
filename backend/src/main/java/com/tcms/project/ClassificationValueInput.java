package com.tcms.project;

import java.util.UUID;

/**
 * {@code ClassificationValueInput} in {@code docs/specs/openapi.yaml} — the body of both writes.
 *
 * <p>One record for the create and the edit, unlike {@code SuiteWriteRequest}'s sibling pair,
 * because the two bodies are genuinely the same shape: the edit ignores nothing and refuses what it
 * cannot change. {@link #dimensionId} and {@link #parentId} are read on both — on the edit they
 * must <em>match</em> the stored row, and a differing one is a field error rather than a move.
 * Accepting-and-dropping them is the third answer, the one where the client redraws the tree and a
 * reload puts it back.
 *
 * <p><strong>Every member nullable, all validation in {@link ClassificationValueEdit}.</strong> A
 * record deserialized from JSON cannot tell an absent key from an explicit null, so a missing
 * {@code name} arrives here as null exactly like {@code "name": null} — which is why "required" is
 * a validation verdict with a message on the field, and never a deserialization failure that would
 * answer 400 with nothing a form could show.
 *
 * @param dimensionId which axis; on an edit, the axis the value already belongs to
 * @param parentId    null for a root; on an edit, the parent the value already has
 * @param color       {@code #rrggbb} or null. Null is a real answer — everything the seed creates
 *                    has no colour, so "clear it" has to be expressible
 */
public record ClassificationValueInput(
        UUID dimensionId,
        String name,
        UUID parentId,
        String color) {
}
