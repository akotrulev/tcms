package com.tcms.identity;

import java.util.UUID;

/**
 * {@code OrgChartGap} in the contract — one catalogue key that no active member holds on one project
 * column (005 N6). Computed server-side and only there: the client is forbidden to branch on a role
 * name, and "who can approve here" is a question about {@code role_permission}, not about names.
 */
public record OrgChartGapView(UUID projectId, String permission) {
}
