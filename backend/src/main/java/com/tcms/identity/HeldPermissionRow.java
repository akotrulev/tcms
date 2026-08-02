package com.tcms.identity;

/**
 * One {@code role_permission} row as the evaluator reads it — a key and the qualifier stored
 * beside it.
 *
 * <p>A Spring Data interface projection, shared by {@link RoleAssignmentRepository} and
 * {@link InstanceRoleAssignmentRepository} because all four of their grant queries return the same
 * two columns and a second declaration would be a second thing to keep in step.
 *
 * <h2>Why the qualifier comes back with the key rather than being asked for separately</h2>
 *
 * <p>It did not, until §65: the key projections fed a keys-only {@code PermissionSet} and a
 * qualifier was fetched per key by the one gate that needed one. See {@link PermissionSet}'s header
 * for the consumer that argument did not survive — and for the property gained by collapsing them,
 * which is that "which grant wins" is now decided in one place instead of two that must agree.
 *
 * <p><strong>{@code DISTINCT} is over both columns, so one key can legitimately come back
 * twice</strong> — two grants conferring it with different qualifiers, which is precisely the case
 * {@link PermissionQualifier#strongest} exists to resolve. A {@code DISTINCT} over the key alone
 * would have Postgres pick one row arbitrarily and would make an authorization decision by row
 * order.
 *
 * <p>The stored qualifier is a {@code String} rather than the enum: Hibernate is reading a native
 * query over a table with no entity, and the conversion belongs in
 * {@link PermissionQualifier#ofStored}, which throws on a value no code path implements rather than
 * defaulting to one.
 */
public interface HeldPermissionRow {

    String getPermissionKey();

    String getQualifier();
}
