package com.tcms.identity;

import java.util.Set;
import java.util.UUID;

/**
 * How far one caller's administrative sight and reach extend in the company currently open —
 * computed once per request and then consulted, rather than re-asked per row.
 *
 * <h2>Why this exists as a type</h2>
 *
 * <p>Every endpoint in the admin surface asks the same two questions of every row it is about to
 * render or change: <em>may this caller see the project it names</em>, and <em>may they staff
 * it</em>. Asking {@link PermissionEvaluator} per row is N queries per row rather than per project,
 * and — worse — it is N places the question could be asked slightly differently. So it is asked
 * once, in {@link AdminDirectoryService#reachOf}, and the answer is passed around.
 *
 * <p><strong>The two sets are deliberately not the same set.</strong> A Test Engineer sees a
 * project and may not staff it; a Company Admin staffs projects they hold no per-project grant on
 * at all. Collapsing them would either hide rows from readers or offer a Revoke button that ends in
 * a 403 — the two halves of the defect §31 exists to prevent.
 *
 * @param visibleProjects   projects the caller holds {@code project:read} on. Only these projects'
 *                          <em>names</em> may appear in a response; an id the caller already holds
 *                          is not a disclosure, a name is (§6t).
 * @param staffableProjects projects the caller holds {@code project:members} on — exactly the
 *                          PROJECT-scope grants they may create and revoke. A subset of
 *                          {@link #visibleProjects}, because every role holding
 *                          {@code project:members} holds {@code project:read} too.
 * @param companyWide       the caller holds {@code company:members}: they may grant and revoke
 *                          across the whole company, including the company-wide grants that name no
 *                          project. Company Admin and Instance Admin, per roles spec §A3.
 * @param manageTeams       the caller holds {@code company:teams}: they may create, edit, delete and
 *                          staff the org chart. Company Admin and Instance Admin, per roles spec
 *                          §A3. <strong>Deliberately a separate term from {@link #companyWide}</strong>
 *                          even though the two are held by the same two roles today — they are two
 *                          permissions in the catalogue, and folding them together would make
 *                          {@code company:teams} unaskable and silently grant org-chart editing to a
 *                          future role that holds {@code company:members} alone. That the seeded
 *                          data makes them coincide is an accident of the seed, not a rule; the
 *                          {@code case:approve} gate §62 built states the same distinction.
 * @param issueTokens       the caller holds {@code token:issue}: they may issue and revoke this
 *                          company's API and CI tokens. Company Admin and Instance Admin, per roles
 *                          spec §A3. <strong>A separate term again</strong>, for the reason
 *                          {@link #manageTeams} already states — and it is worth stating twice,
 *                          because a token is the credential a CI job authenticates with and
 *                          folding it into {@link #companyWide} would silently hand token issuance
 *                          to any future role holding {@code company:members} alone.
 * @param readsCompany      the caller holds {@code company:read} <strong>at company scope</strong>.
 *                          This read "or on any project they hold" until handoff §43, because five
 *                          of the six built-in roles carry {@code company:read} on a PROJECT-scope
 *                          grant and the company-wide question therefore answered "no" for a Test
 *                          Lead who unambiguously held it. {@code V15} fixed that at the root — the
 *                          {@link Role#COMPANY_MEMBER_ID} baseline every member is granted on
 *                          joining — so the honest question and the direct one are now the same
 *                          question. See {@link AdminDirectoryService#reachOf}.
 */
public record AdminReach(
        Set<UUID> visibleProjects,
        Set<UUID> staffableProjects,
        boolean companyWide,
        boolean manageTeams,
        boolean issueTokens,
        boolean readsCompany) {

    /**
     * May this caller grant and revoke on {@code projectId} — {@code null} meaning the company-wide
     * scope?
     *
     * <p><strong>The one place the scope-dependent gate is decided</strong>, so the list filter,
     * the grant and the revoke cannot disagree about it. The contract states the rule on both write
     * endpoints and the failure mode of two implementations of it is the ugly one: a screen that
     * offers a Revoke the server then refuses, or hides one it would have allowed.
     *
     * <p>The project branch asks {@link #staffableProjects} and <em>not</em> "or company-wide",
     * even though a Company Admin passes both. Folding {@link #companyWide} in here would make the
     * gate two rules where the contract states one, and would quietly cover a future role that
     * holds {@code company:members} without {@code project:members} — which the permission model
     * permits and nobody has decided is allowed to staff a project.
     */
    public boolean mayStaff(UUID projectId) {
        return projectId == null ? companyWide : staffableProjects.contains(projectId);
    }

    /** Whether a project's <em>name</em> may be put on the wire for this caller. */
    public boolean maySee(UUID projectId) {
        return projectId != null && visibleProjects.contains(projectId);
    }
}
