package com.tcms.project;

/**
 * The body of {@code POST} and {@code PATCH …/repositories} — a full replacement of the five values.
 *
 * <p>The {@code PATCH} takes the whole shape rather than a delta, so §74's absent/null/empty rules
 * do not apply: the form shows five inputs and submits five, nothing here is a list, and nothing
 * here is clearable.
 *
 * <p><strong>No {@code position}.</strong> A new repository is appended and the lowest position is
 * primary — reordering is a capability nothing asks for yet, and a settable position on a create
 * form is how two rows end up claiming the same one.
 */
public record ProjectRepoInput(
        String name,
        String url,
        String defaultBranch,
        String subdirectory,
        String buildCommand) {
}
