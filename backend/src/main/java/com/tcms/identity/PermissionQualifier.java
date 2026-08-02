package com.tcms.identity;

/**
 * <strong>Which rows</strong> a grant of a permission covers — roles spec §A5, and the thing
 * {@link PermissionSet} deliberately cannot express.
 *
 * <p>A {@link PermissionSet} answers "does this caller hold {@code case:delete} here?". For a Test
 * Engineer the honest answer is "yes, for cases they wrote" — a qualification that needs the
 * <em>row</em>, which the evaluator never sees. So the evaluator answers with this, and the row-level
 * comparison happens at the gate that does have the row.
 *
 * <h2>Why this is not three permission keys</h2>
 *
 * <p>{@code case:delete:own} beside {@code case:delete} doubles the catalogue, puts two keys where
 * every screen wants one, and still cannot say "in any state" — see V3's section 3, which refused it
 * before this type existed.
 *
 * <h2>Strongest wins — and why that is no longer an ordinal comparison</h2>
 *
 * <p>A caller with two grants gets the stronger: hold {@code case:delete} unqualified through a
 * COMPANY-scope Company Admin grant and {@code OWN} through a project's Test Engineer grant, and the
 * answer is {@link #ALL}. Demoting them for holding an <em>additional</em> weaker grant would make
 * adding a role take capability away.
 *
 * <p>Until V19 that rule was {@link Enum#compareTo} over a weakest-first declaration, and it was
 * exactly right for a lattice with one restrictive value. It stopped being right when
 * {@link #PLAN_SCOPE} arrived, because <strong>{@link #OWN} and {@link #PLAN_SCOPE} are not
 * comparable</strong>: one restricts by who wrote the row, the other by what scope the row is at,
 * and neither contains the other. A caller holding both would be covered for "rows I wrote OR rows
 * at plan scope", and there is no value here that says that.
 *
 * <p>So {@link #strongest} is written out rather than derived from the ordinal, and it
 * <strong>throws</strong> on that pair instead of returning whichever happens to be declared later.
 * Returning one would silently narrow or widen a grant depending on declaration order — an
 * authorization decision taken by a line nobody would think of as authorization code. The throw is
 * unreachable against the seeded catalogue by construction, and {@code SeedDataTest} is what makes
 * that a fact rather than a hope: no permission key may be granted with two different restrictive
 * qualifiers. Custom roles are a stated direction (V1's {@code role} table comment), so the day that
 * invariant can be violated by data this class did not seed, this fails loudly at the gate rather
 * than quietly inside it.
 *
 * <p>The declaration order below is therefore documentation, not mechanism. Nothing reads the
 * ordinal, and reordering these constants changes no behaviour — which is the property that was
 * missing when it did.
 */
public enum PermissionQualifier {

    /** Not held at all, for any row. Distinct from {@link #OWN} — "nothing" is not "some". */
    NONE,

    /**
     * Held only for rows the caller authored, in any state.
     *
     * <p>The comparison is against the row's {@code created_by}. A row whose author is unknown
     * (NULL — rows seeded before authorship was recorded) is <strong>not</strong> the caller's: the
     * alternative fails open on exactly the rows nobody can vouch for.
     */
    OWN,

    /**
     * Held only for rows at <strong>PLAN</strong> scope, whoever authored them — roles spec's
     * Artifacts table, {@code artifact:edit} for a Test Engineer.
     *
     * <p>The comparison is against the row's own scope, not the caller. A project-scope artifact is
     * inherited read-only by every plan (plan §6n), so editing one edits every cycle at once; a
     * plan-scope artifact belongs to the cycle the caller is working in. Deliberately orthogonal to
     * {@link #OWN}: a Test Engineer may supersede a colleague's environment snapshot inside their
     * own cycle, and may not touch a project-scope document they wrote themselves.
     */
    PLAN_SCOPE,

    /** Held for every row in scope — what every unqualified grant means, and the default in V12. */
    ALL;

    /**
     * The stronger of two, for unioning the SYSTEM, COMPANY and PROJECT terms.
     *
     * <p>Written out rather than an ordinal comparison, and it refuses the one pair that has no
     * answer — see the class comment. The four arms are the whole lattice: equal is itself,
     * {@link #ALL} absorbs, {@link #NONE} is the identity, and two <em>different</em> restrictive
     * values are the case the type cannot represent.
     */
    PermissionQualifier strongest(PermissionQualifier other) {
        if (this == other) {
            return this;
        }
        if (this == ALL || other == ALL) {
            return ALL;
        }
        if (this == NONE) {
            return other;
        }
        if (other == NONE) {
            return this;
        }
        // Two different restrictive qualifiers on one key. Their union is a set this enum has no
        // value for, so there is nothing honest to return. SeedDataTest asserts the seeded
        // catalogue never produces this; reaching it means a grant was written that the catalogue
        // check does not cover — a custom role, or a migration — and the caller's true capability
        // is wider than any value here can say.
        throw new IllegalStateException(
                "Cannot merge qualifiers " + this + " and " + other + ": they restrict different "
                        + "things, so neither is the stronger and their union is not expressible. "
                        + "No permission key may be granted with two different restrictive "
                        + "qualifiers — see V19 and SeedDataTest.");
    }

    /**
     * Reads the stored value.
     *
     * <p>Throws on anything else rather than defaulting: the column has a CHECK constraint, so an
     * unknown value means the constraint was dropped or a migration wrote a new qualifier this code
     * does not implement — and defaulting either way is a silent authorization decision. {@code NONE}
     * is never stored (a row that grants nothing is an absent row), so it is not accepted here.
     */
    static PermissionQualifier ofStored(String stored) {
        return switch (stored) {
            case "ALL" -> ALL;
            case "OWN" -> OWN;
            case "PLAN_SCOPE" -> PLAN_SCOPE;
            default -> throw new IllegalStateException(
                    "role_permission.qualifier holds '" + stored + "', which no code path implements. "
                            + "Every authorization decision reading it would be a guess.");
        };
    }
}
