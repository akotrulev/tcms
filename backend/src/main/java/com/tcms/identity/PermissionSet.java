package com.tcms.identity;

import java.util.Arrays;
import java.util.Set;

/**
 * The permissions one user effectively holds in one context — the result of
 * {@link PermissionEvaluator}.
 *
 * <p>Immutable, and every lookup is validated against {@link PermissionCatalogue}, so a
 * mistyped key throws rather than quietly denying. See the catalogue's class comment for
 * why that matters more than it looks.
 *
 * <p>A final class rather than a record: the catalogue reference is machinery, not part
 * of the value, and a record would put it in {@code equals}, {@code toString} and the
 * public accessor list.
 */
public final class PermissionSet {

    private final PermissionCatalogue catalogue;
    private final Set<String> keys;

    private PermissionSet(PermissionCatalogue catalogue, Set<String> keys) {
        this.catalogue = catalogue;
        this.keys = keys;
    }

    static PermissionSet of(PermissionCatalogue catalogue, Set<String> keys) {
        return new PermissionSet(catalogue, Set.copyOf(keys));
    }

    /**
     * The empty set — what a non-member, a deactivated account, or a member with no grant
     * resolves to. Not an error condition: "no capability" is the correct answer to most
     * questions about most users.
     */
    static PermissionSet empty(PermissionCatalogue catalogue) {
        return new PermissionSet(catalogue, Set.of());
    }

    /**
     * @throws IllegalArgumentException if {@code permissionKey} is not in the catalogue —
     *                                  deliberately, rather than returning {@code false}
     */
    public boolean has(String permissionKey) {
        catalogue.requireKnown(permissionKey);
        return keys.contains(permissionKey);
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
        return keys.isEmpty();
    }

    public int size() {
        return keys.size();
    }

    /** The raw keys, immutable. For serializing into a token or rendering an admin screen. */
    public Set<String> keys() {
        return keys;
    }

    /**
     * Whether this set covers the entire catalogue — <strong>derived, never a stored
     * sentinel</strong>. A {@code '*'} would keep reading as "everything" after a
     * permission it does not hold is added (handoff §6 lesson 10).
     */
    public boolean coversEverything() {
        return keys.containsAll(catalogue.keys());
    }

    @Override
    public String toString() {
        return "PermissionSet" + keys.stream().sorted().toList();
    }
}
