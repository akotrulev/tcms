package com.tcms.project;

import java.util.UUID;

/**
 * {@code ClassificationValue} in {@code docs/specs/openapi.yaml} — the wire shape.
 *
 * <p>A DTO for the same reason {@link ProjectView} is one: {@code companyId} must never leave the
 * building (ERD §0.1 point 2). {@code position} and {@code deletedAt} are dropped too — the first
 * because array order already carries it and two authorities for order is one too many, the second
 * because a deleted value is never in the response at all.
 *
 * @param parentId null for a root value; the Feature tree is built client-side from this
 * @param path     the materialized {@code ltree} path, <strong>not</strong> a display string —
 *                 labels are sanitized to {@code [A-Za-z0-9_]}, so "UI / E2E" has a path that does
 *                 not spell it. Render {@link #name}; use the path for subtree matching (§6q).
 * @param color    nullable, per the contract, and null for everything the seed creates
 */
public record ClassificationValueView(
        UUID id,
        UUID parentId,
        String name,
        String path,
        String color) {

    static ClassificationValueView of(ClassificationValue value) {
        return new ClassificationValueView(
                value.getId(),
                value.getParentId(),
                value.getName(),
                value.getPath(),
                value.getColor());
    }
}
