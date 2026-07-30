package com.tcms.identity;

/**
 * Account lifecycle at the <em>instance</em> level. Users are never hard-deleted:
 * authorship on versions, results and audit rows must keep resolving to a real row
 * (plan §6b, and load-bearing detail 10 — identity stays keyed on {@code app_user.id},
 * never email).
 *
 * <p>An IdP-removed user is {@link #DEACTIVATED}, never deleted. Deactivation is the
 * instance-wide off switch and zeroes the permission set in <em>every</em> company,
 * including SYSTEM-scope grants — see {@link PermissionEvaluator}.
 */
public enum UserStatus {

    /** Invited but has not accepted; holds no permissions anywhere. */
    INVITED,

    ACTIVE,

    /** Retained for authorship, holds nothing, cannot authenticate. */
    DEACTIVATED
}
