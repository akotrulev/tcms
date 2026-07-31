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
 * <h2>Ordered, and the order is the merge rule</h2>
 *
 * <p>Declared weakest first so {@link Enum#compareTo} is the "strongest wins" rule a caller with two
 * grants needs: hold {@code case:delete} unqualified through a COMPANY-scope Company Admin grant and
 * {@code OWN} through a project's Test Engineer grant, and the answer is {@link #ALL}. Demoting them
 * for holding an <em>additional</em> weaker grant would make adding a role take capability away.
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

    /** Held for every row in scope — what every unqualified grant means, and the default in V12. */
    ALL;

    /** The stronger of two, for unioning the SYSTEM, COMPANY and PROJECT terms. */
    PermissionQualifier strongest(PermissionQualifier other) {
        return compareTo(other) >= 0 ? this : other;
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
            default -> throw new IllegalStateException(
                    "role_permission.qualifier holds '" + stored + "', which no code path implements. "
                            + "Every authorization decision reading it would be a guess.");
        };
    }
}
