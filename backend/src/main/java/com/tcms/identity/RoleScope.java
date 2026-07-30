package com.tcms.identity;

/**
 * The three grant scopes (roles spec A1). A role's scope determines which table its
 * assignments live in and which gates apply to them.
 *
 * <table border="1">
 *   <caption>Scope, storage and gating</caption>
 *   <tr><th>Scope</th><th>Assignment table</th><th>Gated by company membership?</th></tr>
 *   <tr><td>{@link #SYSTEM}</td><td>{@code instance_role_assignment}</td><td><strong>no</strong></td></tr>
 *   <tr><td>{@link #COMPANY}</td><td>{@code role_assignment}, {@code project_id IS NULL}</td><td>yes</td></tr>
 *   <tr><td>{@link #PROJECT}</td><td>{@code role_assignment}, {@code project_id} set</td><td>yes</td></tr>
 * </table>
 *
 * <p>The "no" on {@link #SYSTEM} is not an oversight — see {@link PermissionEvaluator}
 * for why an Instance Admin must resolve permissions in companies they are not a member
 * of.
 */
public enum RoleScope {

    /** The instance. Instance Admin only, bootstrapped by the operator. */
    SYSTEM,

    /** One company, covering every project in it — present and future. */
    COMPANY,

    /** One project. The finest boundary: there are no per-suite or per-case ACLs. */
    PROJECT
}
