package com.tcms.project;

/**
 * The second half of a {@code PLAN_SCOPE}-qualified permission check: <em>this</em> row, given the
 * scope it sits at — handed to the callback of {@link ProjectService#writeGrantedByRowScope}.
 *
 * <p>The exact shape of {@link AuthorshipGate}, one axis over, and for the same reason. A module
 * acting on a row is told yes or no about a scope it supplies; it is never told what its grant
 * <em>is</em>, so it has nothing to re-interpret and no way to grow a second reading of what
 * {@code PLAN_SCOPE} means. The roles spec's Artifacts rule is implemented once, in
 * {@code identity}, and reaches the row through this.
 *
 * <p>Applying it is the caller's responsibility, and a caller that ignores it gets the unqualified
 * behaviour — see the gate's header, and the endpoint test that asserts the refusal from outside
 * rather than trusting the type.
 *
 * <p><strong>It takes a boolean rather than a scope enum</strong>, which is the one place this is
 * uglier than its sibling. The alternative is an enum owned here and mapped from
 * {@code documentation}'s own {@code ArtifactScope}, and that trade was refused: {@code project}
 * would then own a vocabulary about artifacts in order to avoid a boolean, and the second enum's
 * only job would be to be converted into the first. The parameter is named for the question rather
 * than the value so the call site reads as a sentence.
 */
@FunctionalInterface
public interface RowScopeGate {

    /**
     * @param rowIsPlanScoped whether the row being acted on belongs to a single plan, as opposed to
     *                        the project as a whole. A project-scope row is inherited read-only by
     *                        every plan (plan §6n), so a grant restricted to plan scope does not
     *                        cover it — changing it changes every cycle at once.
     */
    boolean mayActOn(boolean rowIsPlanScoped);
}
