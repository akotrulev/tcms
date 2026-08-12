package com.tcms.api;

import java.util.UUID;

/**
 * {@code TeamRef} in {@code docs/specs/openapi.yaml} — a team, reduced to what a screen renders
 * beside something else.
 *
 * <p>{@link UserRef}'s sibling and deliberately as small: an id and a name. It is emphatically
 * <strong>not</strong> {@code identity.TeamView}, which carries the team's leads, its members and
 * the projects its grants reach — the last of those filtered by what the <em>caller</em> may see,
 * so serving it from a review panel would mean that module reasoning about an org-chart disclosure
 * rule.
 *
 * <p>Deliberately no member list. Plan §6g says <em>"any member of the team can action it"</em>, and
 * a panel that expanded the team into names would freeze the membership as it was when the page
 * loaded and invite a reader to treat it as the list of people who owe a review. The team is the
 * addressee; who is in it is the org chart's question and is answered there.
 *
 * <h2>This was three records until §175, and only one of them served the schema</h2>
 *
 * <p>{@code project.TeamRefView} served {@code ReviewAssignee.team} — the {@code TeamRef} schema's
 * single {@code $ref}. {@code identity.RoleAssignmentView.TeamRef} served {@code RoleAssignment.team}
 * and {@code auth.CurrentUserService.TeamRef} served {@code CurrentUser.teams}, both of which the
 * contract declared as <strong>inline</strong> objects — so the three were related by nothing a
 * compiler or a rule could see, and §165 had to write the sentence telling them apart.
 *
 * <p>§147 named this family as {@link UserRef}'s next tenant and left it, on the grounds that two
 * copies is not six and the extraction should wait for a third consumer. There were three copies
 * when that was written. What made the wait right was not the count: the two inline schemas had to
 * become {@code $ref}s before the Java collapse was anything but a coincidence of fields, and one
 * of the three carried a component ({@code isLead}) the schema did not declare. §175 answered both
 * in the contract first — the two inline objects are {@code $ref}s now, and {@code isLead} is gone
 * from the wire — which is what leaves one record here rather than one record and an argument.
 *
 * <p>{@code ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape} holds it: a fourth copy,
 * under any name, in any module, fails the build. Measured before this record moved here — exactly
 * two production records had these components, and both are deleted.
 */
public record TeamRef(UUID id, String name) {
}
