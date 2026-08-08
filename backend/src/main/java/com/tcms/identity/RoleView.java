package com.tcms.identity;

import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code Role} — a built-in, with its permissions expanded.
 *
 * <h2>Why the permissions are on the wire at all</h2>
 *
 * <p>An admin deciding whether to grant Test Engineer needs to see that it excludes final
 * approval. A name alone does not say that, and the resulting guess is how people end up
 * over-privileged. The SPA renders the list grouped by area for exactly that reason.
 *
 * <p><strong>There is no {@code '*'} and there never will be.</strong> Instance Admin enumerates
 * the whole catalogue, because a sentinel keeps reading as "everything" after a migration adds a
 * permission the role does not hold — handoff §6 lesson 10, and the reason the SPA derives
 * "holds every permission" from the catalogue rather than looking for a wildcard.
 *
 * <p>{@link #scope} is what decides whether a role may be granted through
 * {@code POST /role-assignments} and at which scope: {@link RoleScope#PROJECT} needs a
 * {@code projectId}, {@link RoleScope#COMPANY} needs the absence of one, and
 * {@link RoleScope#SYSTEM} is not grantable there at all — see {@link RoleGrantService}.
 *
 * <h2>{@link #assignable} is a second, independent axis, and V40 is where they parted</h2>
 *
 * <p>{@link #scope} answers <em>at which target</em> a role may be granted; {@code assignable}
 * answers whether it may be granted to a person <em>at all</em>. They agreed while every listed
 * role was grantable somewhere. {@link Role#AGENT_ID} is PROJECT-scope and grantable nowhere: it is
 * carried by an API token, so it must appear in the token-issue form and must not appear in a grant
 * picker. A client filtering only on scope offers it beside a Grant button the server refuses.
 */
public record RoleView(
        UUID id,
        String name,
        RoleScope scope,
        boolean builtIn,
        boolean assignable,
        String description,
        List<String> permissions) {
}
