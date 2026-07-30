package com.tcms.identity;

/**
 * A user's standing inside one company.
 *
 * <p>Only {@link #ACTIVE} opens the gate. {@link #INVITED} deliberately does not: an
 * unaccepted invitation must convey nothing, so an invite cannot be used to peek at a
 * client's data before the person has agreed to anything.
 *
 * <p>{@link #REMOVED} rather than a deleted row, so offboarding is one status update
 * and stale grants elsewhere become inert without having to be found and deleted — the
 * membership is a <em>gate</em>, not a term of the permission union (plan §6b).
 */
public enum MembershipStatus {

    INVITED,

    ACTIVE,

    /** Offboarded. Every grant in this company becomes inert without being deleted. */
    REMOVED
}
