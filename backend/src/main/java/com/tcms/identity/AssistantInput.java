package com.tcms.identity;

import java.util.UUID;

/**
 * {@code POST /me/assistants} — connecting an assistant.
 *
 * <h2>There is no {@code role}, no {@code owner} and no {@code expiresAt}</h2>
 *
 * <p>Each of the three is the server's, and each for its own reason. The role is
 * {@link Role#AGENT_ID} because that is the only thing this surface issues — a field would make
 * {@code /me/assistants} a second, ungated spelling of {@code POST /api-tokens}. The owner is the
 * caller for {@link ApiTokenIssueRequest}'s reason, sharpened: the owner's grants <em>cap</em> the
 * credential, so a nominated owner is how you mint one more powerful than yourself. And the expiry
 * is set here rather than asked for because {@code ApiTokenService}'s own rule requires an Agent
 * token to have one, and a required field on a self-service form is a field somebody fills in with
 * the furthest date the picker offers.
 *
 * <p>So nothing on this record can grant the assistant more than the caller already holds, which is
 * the whole argument for the surface needing no {@code token:issue}.
 *
 * @param projectId scope it to one project, or null for every project the caller can reach
 */
public record AssistantInput(String name, UUID projectId) {
}
