package com.tcms.identity;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * The permissions one user effectively holds in one context, <strong>each with the qualifier that
 * says which rows it covers</strong> — the result of {@link PermissionEvaluator}.
 *
 * <p>Immutable, and every lookup is validated against {@link PermissionCatalogue}, so a
 * mistyped key throws rather than quietly denying. See the catalogue's class comment for
 * why that matters more than it looks.
 *
 * <h2>Why the qualifier lives here now — a reversal, recorded</h2>
 *
 * <p>Until §65 this was a set of keys, and {@link PermissionEvaluator#qualifierOf} was a
 * <em>second</em> computation over the same three terms, run per key by the one gate that needed
 * it. The argument for the split is still written out in
 * {@code RoleAssignmentRepository}: every consumer asks "is it held", and a key that arrived
 * qualified is still held, so pairs would have been pushed into call sites that discard them.
 *
 * <p>That argument had one consumer it did not survive. {@code GET /me/permissions} answers the
 * SPA's disable-don't-hide gate, and a keys-only answer <strong>cannot express the verdict a
 * qualified grant produces</strong>: a Test Engineer holds {@code artifact:edit} at
 * {@link PermissionQualifier#PLAN_SCOPE}, so "held" enables a project-scope control the server then
 * refuses. Handoff §61 shipped that refusal with the sentence in the response body and recorded
 * putting qualifiers on the wire as the real fix.
 *
 * <p>So the two computations are now one. The gates' entry point is unchanged — they still ask
 * {@link PermissionEvaluator#qualifierOf} about one key — but it reads this map rather than
 * re-deriving the terms. <strong>The merge rule and the replace-don't-union boundary now exist in
 * exactly one place</strong>, which is the real reason for the change: two implementations of
 * "which grant wins" that must agree is a defect waiting for the day somebody edits one.
 *
 * <p>A final class rather than a record: the catalogue reference is machinery, not part
 * of the value, and a record would put it in {@code equals}, {@code toString} and the
 * public accessor list.
 */
public final class PermissionSet {

    private final PermissionCatalogue catalogue;
    private final Map<String, PermissionQualifier> held;

    private PermissionSet(PermissionCatalogue catalogue, Map<String, PermissionQualifier> held) {
        this.catalogue = catalogue;
        this.held = held;
    }

    /**
     * @throws IllegalArgumentException if any key is absent from the catalogue, or if any value is
     *                                  {@link PermissionQualifier#NONE} — "held, covering nothing"
     *                                  is not a state this type may represent, because every
     *                                  consumer reads presence as capability
     */
    static PermissionSet of(PermissionCatalogue catalogue, Map<String, PermissionQualifier> held) {
        held.forEach((key, qualifier) -> {
            catalogue.requireKnown(key);
            if (qualifier == PermissionQualifier.NONE) {
                throw new IllegalArgumentException(
                        "'" + key + "' was resolved to NONE and put in the held map. A permission "
                                + "covering no rows is an ABSENT entry, never a present one — "
                                + "otherwise has() answers true for a grant that decides nothing.");
            }
        });
        return new PermissionSet(catalogue, Map.copyOf(held));
    }

    /**
     * The empty set — what a non-member, a deactivated account, or a member with no grant
     * resolves to. Not an error condition: "no capability" is the correct answer to most
     * questions about most users.
     */
    static PermissionSet empty(PermissionCatalogue catalogue) {
        return new PermissionSet(catalogue, Map.of());
    }

    /**
     * @throws IllegalArgumentException if {@code permissionKey} is not in the catalogue —
     *                                  deliberately, rather than returning {@code false}
     */
    public boolean has(String permissionKey) {
        catalogue.requireKnown(permissionKey);
        return held.containsKey(permissionKey);
    }

    /**
     * <strong>Which rows</strong> this caller's grant of one permission covers, or
     * {@link PermissionQualifier#NONE} when they do not hold it here at all.
     *
     * <p>{@code NONE} for an unheld key is what makes this safe to consult <em>without</em>
     * {@link #has} first: a gate that reads only this is still correct. Two methods that must be
     * called in the right order to be safe are one method with a footgun.
     *
     * <p>Validated against the catalogue like {@link #has}, so a typo throws here rather than
     * resolving to {@code NONE} — which would read as "this user is not allowed" and be
     * investigated against the grants, where nothing is wrong.
     */
    public PermissionQualifier qualifierOf(String permissionKey) {
        catalogue.requireKnown(permissionKey);
        return held.getOrDefault(permissionKey, PermissionQualifier.NONE);
    }

    /** True only if every key is held. Each is validated. */
    public boolean hasAll(String... permissionKeys) {
        return Arrays.stream(permissionKeys).allMatch(this::has);
    }

    /** True if any key is held. Each is validated, so a typo throws even if another key matches. */
    public boolean hasAny(String... permissionKeys) {
        // Deliberately not short-circuiting on the first match: a typo in a later argument
        // must still be caught, or `hasAny("case:read", "case:edt")` would pass silently.
        return Arrays.stream(permissionKeys).map(this::has).reduce(false, (a, b) -> a || b);
    }

    public boolean isEmpty() {
        return held.isEmpty();
    }

    public int size() {
        return held.size();
    }

    /** The raw keys, immutable. For rendering a role screen or asking a set question. */
    public Set<String> keys() {
        return held.keySet();
    }

    /**
     * Every held key with its qualifier, immutable — what {@code GET /me/permissions} serializes.
     *
     * <p>The whole map rather than a key-at-a-time read, because that endpoint's answer is the
     * caller's entire capability in one context and assembling it from {@link #qualifierOf} calls
     * would re-validate every key against the catalogue for nothing.
     */
    public Map<String, PermissionQualifier> qualifiers() {
        return held;
    }

    /**
     * Whether this set covers the entire catalogue — <strong>derived, never a stored
     * sentinel</strong>. A {@code '*'} would keep reading as "everything" after a
     * permission it does not hold is added (handoff §6 lesson 10).
     *
     * <p>Deliberately a question about the <em>keys</em> only. An Instance Admin whose grants were
     * all {@code OWN}-qualified would still cover the catalogue by this reading, which is correct
     * for what the method is asked: it exists to check that the operator role reaches every
     * capability, not that it reaches every row.
     */
    public boolean coversEverything() {
        return held.keySet().containsAll(catalogue.keys());
    }

    @Override
    public String toString() {
        return "PermissionSet"
                + held.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + "(" + e.getValue() + ")")
                        .toList();
    }
}
