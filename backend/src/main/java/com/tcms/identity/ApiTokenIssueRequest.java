package com.tcms.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The body of {@code POST /api-tokens}.
 *
 * <h2>There is no {@code ownerId}</h2>
 *
 * <p>The owner is the caller, always. It is not a field because the owner's grants <em>cap</em> what
 * the token can do ({@link PermissionEvaluator}'s intersection), so a nominated owner would be a
 * way to mint a credential more powerful than yourself by naming somebody more powerful — the exact
 * escalation the cap exists to prevent, offered as a form field. If delegation is ever wanted, it is
 * a decision about who may name whom, not a parameter.
 *
 * @param name      what this token is for, in the issuer's words. Unique among the company's live
 *                  tokens ({@code V36}), because two rows called "CI" are two rows nobody can tell
 *                  apart when one has to be revoked in a hurry.
 * @param roleId    the role the token carries. Not the caller's roles — see {@link ApiToken}.
 *                  Automation Service is the intended answer and the server does not require it:
 *                  code checks permissions, never role names, and hard-coding one role here would
 *                  be the first place in the product that does.
 * @param projectId one project, or null for every project the owner can reach. Present is the
 *                  narrower answer and the one a CI job wants.
 * @param expiresAt when it stops working, or null for a token that does not expire. Null is
 *                  permitted deliberately: a credential that dies mid-release without anybody
 *                  having decided to withdraw it is an outage, and the withdrawal that <em>is</em>
 *                  a decision is {@code DELETE}.
 */
public record ApiTokenIssueRequest(
        String name,
        UUID roleId,
        UUID projectId,
        Instant expiresAt) {
}
