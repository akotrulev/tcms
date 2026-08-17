package com.tcms.identity;

/**
 * How a person holds the role a matrix cell shows — the contract's {@code OrgChartAssignment.via}.
 *
 * <h2>Why the chart says this at all</h2>
 *
 * <p>A matrix built the obvious way — {@code role_assignment WHERE project_id = ?} — renders
 * {@link #DIRECT} and {@link #TEAM} identically and omits {@link #INHERITED} entirely.
 * {@code ProjectMembershipService}'s header is written about exactly that: it would silently omit
 * every person staffed through a team, which is the shape an org chart exists to encourage, and
 * every person reaching the project company-wide. Naming the arm makes the omission visible instead
 * of plausible.
 *
 * <p>There is no fourth member and no {@code COMPANY} arm. A Company Admin reaches every project
 * through a COMPANY-scope grant and produces <strong>no assignment row at all</strong> — they
 * appear in {@code companyRoles}, on the person, because roles spec §A1.1 property 2 says authority
 * over the company is not a default for its projects. An arm here would be that misreading given a
 * name.
 */
public enum AssignmentVia {

    /** A grant naming this person and this project. */
    DIRECT,

    /** A grant to a team they belong to, naming this project. */
    TEAM,

    /**
     * A PROJECT-scope role granted company-wide, applying here because no grant names this project.
     *
     * <p>Roles spec §A1.1's replace-don't-union rule seen from the display side: this arm is what a
     * person has <em>until</em> a grant names the project, at which point the named grant replaces
     * it rather than joining it.
     */
    INHERITED
}
