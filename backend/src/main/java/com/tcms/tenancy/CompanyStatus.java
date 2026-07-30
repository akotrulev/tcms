package com.tcms.tenancy;

/**
 * Company lifecycle. Deliberately two states: the policy is suspend, never delete, and
 * there is no ARCHIVED state to reason about (plan §6s).
 *
 * <p>Persisted as {@code EnumType.STRING} against a {@code TEXT} column with a CHECK
 * constraint — never ORDINAL, which is fragile to reordering, and never a native
 * Postgres enum, whose values cannot be removed.
 */
public enum CompanyStatus {
    ACTIVE,
    SUSPENDED
}
