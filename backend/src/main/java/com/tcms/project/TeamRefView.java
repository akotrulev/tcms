package com.tcms.project;

import java.util.UUID;

/**
 * The contract's {@code TeamRef} — a team, reduced to what a screen renders beside a request.
 *
 * <p>{@link com.tcms.api.UserRef}'s sibling, and deliberately as small: a name and an id. It is emphatically
 * <strong>not</strong> {@code identity.TeamView}, which carries the team's leads, its members and
 * the projects its grants reach — three things a review panel has no business rendering and one of
 * which ({@code projects}) is filtered by what the <em>caller</em> may see, so serving it from here
 * would mean this module reasoning about a disclosure rule that belongs to the org chart.
 *
 * <p>Deliberately no member list. §6g says <em>"any member of the team can action it"</em>, and a
 * panel that expanded the team into names would freeze the membership as it was when the page
 * loaded and invite a reader to treat it as the list of people who owe a review. The team is the
 * addressee; who is in it is the org chart's question and is answered there.
 */
public record TeamRefView(UUID id, String name) {
}
