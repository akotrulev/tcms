package com.tcms.project;

import java.util.UUID;

/**
 * The second half of an {@code OWN}-qualified permission check: <em>this</em> row, given who wrote
 * it — handed to the callback of {@link ProjectService#writeGrantedByAuthorship}.
 *
 * <p>It is a function and not a value on purpose. A module acting on a row is told yes or no about
 * an author it supplies; it is never told what its grant <em>is</em>, so it has nothing to
 * re-interpret and no way to grow a second reading of what {@code OWN} means. Roles spec §A5 is
 * implemented once, in {@code identity}, and reaches the row through this.
 *
 * <p>Applying it to a row's author is the caller's responsibility, and a caller that ignores it gets
 * the unqualified behaviour — see the gate's header, and the endpoint test that asserts the refusal
 * from outside rather than trusting the type.
 */
@FunctionalInterface
public interface AuthorshipGate {

    /**
     * @param authorId the row's {@code created_by}, nullable — an unattributable row is not the
     *                 caller's own work, so an {@code OWN} grant does not cover it
     */
    boolean mayActOn(UUID authorId);
}
