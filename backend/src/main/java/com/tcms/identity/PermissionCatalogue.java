package com.tcms.identity;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The set of permission keys that exist — read once from the seeded {@code permission}
 * table and held for the life of the process.
 *
 * <h2>Why this type exists at all</h2>
 *
 * <p>Permission keys are strings, and a typo in a check
 * ({@code has("case:edt")}) would silently return {@code false}. A false from a typo and
 * a false from a legitimate denial are indistinguishable at the call site, so the bug
 * presents as "this user is missing a permission" — a support ticket, investigated
 * against the grants, where nothing is wrong. {@link PermissionSet#has(String)} validates
 * against this catalogue and throws instead, turning a silent mis-grant into a stack
 * trace.
 *
 * <p>That is the same family as handoff §6 lesson 10 (the {@code '*'} sentinel) and
 * lesson 11 (making {@code PermissionKey} a contract enum): where a field has a closed
 * set, make the closed set enforceable rather than trusting every call site.
 *
 * <h2>Why it is cached, and why lazily</h2>
 *
 * <p>The catalogue is reference data owned by {@code V3__seed_permissions_and_roles.sql}
 * and cannot change while the process runs — a new permission arrives as a migration and
 * therefore a restart. Loading it per check would put a query on every authorization
 * decision for data that is constant.
 *
 * <p>Lazily rather than in {@code @PostConstruct} because bean construction is too early
 * to depend on the database — the same ordering trap that made four test classes fail
 * with what looked like a credentials error (handoff §12). First use is always inside a
 * request or a test method, by which point Flyway has run.
 */
@Component
public class PermissionCatalogue {

    private final PermissionRepository permissions;

    /**
     * Volatile, and only ever assigned a fully-built immutable set. Two threads racing
     * the first load do duplicate work once and then agree — a lock would cost more than
     * the query it avoids.
     */
    private volatile Set<String> keys;

    public PermissionCatalogue(PermissionRepository permissions) {
        this.permissions = permissions;
    }

    /**
     * Every permission key that exists. Immutable.
     *
     * <p>Deliberately <strong>not</strong> {@code @Transactional}. {@link #contains} and
     * {@link #requireKnown} call this on {@code this}, which a Spring proxy cannot
     * intercept — so the annotation would apply on the external path and silently not on
     * the internal one, which is worse than not having it. Spring Data's repository is
     * itself {@code @Transactional(readOnly = true)}, so the one query that ever runs here
     * gets its own boundary either way.
     */
    public Set<String> keys() {
        Set<String> loaded = keys;
        if (loaded == null) {
            loaded = permissions.findAll().stream()
                    .map(Permission::getKey)
                    .collect(Collectors.toUnmodifiableSet());
            if (loaded.isEmpty()) {
                throw new IllegalStateException(
                        "The permission catalogue is empty. V3__seed_permissions_and_roles.sql has not "
                                + "run — every authorization check would deny, which reads as a grants "
                                + "problem rather than a migration one.");
            }
            keys = loaded;
        }
        return loaded;
    }

    public boolean contains(String permissionKey) {
        return keys().contains(permissionKey);
    }

    /**
     * Fails loudly on a key that is not in the catalogue.
     *
     * @throws IllegalArgumentException always, for an unknown key — never returns
     *                                  {@code false}, which is the whole point
     */
    public void requireKnown(String permissionKey) {
        if (!contains(permissionKey)) {
            throw new IllegalArgumentException(
                    "Unknown permission key '" + permissionKey + "'. It is not in the catalogue, so this "
                            + "check would have silently denied forever. Add it to "
                            + "V3__seed_permissions_and_roles.sql and to openapi.yaml's PermissionKey, or "
                            + "fix the typo.");
        }
    }
}
