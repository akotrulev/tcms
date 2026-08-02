package com.tcms.identity;

import java.util.UUID;

/**
 * The contract's {@code TeamInput} — the editable half of a team, as {@code POST /teams} and
 * {@code PUT /teams/{teamId}} both receive it.
 *
 * <p><strong>The people are deliberately not in here.</strong> A membership has its own URL per
 * person, because the two writes fail differently: a name clash is a fact about the team, and "that
 * person is not in this company" is a fact about one member. A create that also took a member list
 * could half-succeed — the team exists, three of the five people named do not — with one status
 * code to report it and no obvious thing for the client to retry.
 *
 * @param parentTeamId {@code null} makes this a root team, and the distinction is why the edit is a
 *                     {@code PUT}: a record deserialized from JSON reads an absent field and an
 *                     explicit {@code null} identically, so under {@code PATCH} either "leave the
 *                     parent alone" or "move this to the root" would be unreachable. The same
 *                     reasoning {@code ClassificationValueInput} carries.
 */
public record TeamInput(String name, String description, UUID parentTeamId) {
}
