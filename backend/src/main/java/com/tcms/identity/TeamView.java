package com.tcms.identity;

import com.tcms.api.UserRef;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code Team} — an org-chart node with its people and the projects its grants
 * reach.
 *
 * <p><strong>{@link #parentTeamId} is structure, never a permission edge.</strong> A parent team's
 * grants are not inherited by its children ({@link Team}'s header), so a consumer rendering the
 * tree must not read depth as authority. The org chart is a picture; {@link PermissionEvaluator}
 * is the answer.
 *
 * @param projects the projects this team's grants reach, <strong>filtered to the ones the caller
 *                 may see</strong>. A team can hold a grant on a project the reader has none on,
 *                 and listing its name here would leak a project's existence through the org chart
 *                 — the same disclosure {@code GET /projects} exists to prevent (§6t). An empty
 *                 list therefore means "none you can see", not "none".
 */
public record TeamView(
        UUID id,
        UUID parentTeamId,
        String name,
        String description,
        List<UserRef> leads,
        List<UserRef> members,
        List<ProjectRole> projects) {

    /** One project this team holds a role on, as the org chart renders it. */
    public record ProjectRole(UUID projectId, String projectName, String roleName) {
    }
}
