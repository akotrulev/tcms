package com.tcms.project;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The second gate, asked from this side of the module boundary.
 *
 * <h2>Why this interface exists at all</h2>
 *
 * <p>Listing projects needs two things {@code project} cannot compute: whether the caller
 * holds a grant, and what their role is called. Both live in {@code identity}. The obvious
 * move — importing {@code PermissionEvaluator} here — is <strong>forbidden and would be a
 * cycle</strong>: {@code identity} already depends on {@code project}, because the evaluator
 * resolves a project through {@code ProjectRepository} to check it belongs to the active
 * company. Both modules' package comments say the edge is one-way, and Spring Modulith fails
 * the build on the cycle rather than leaving it to be noticed.
 *
 * <p>So the dependency is inverted instead of reversed: {@code project} declares what it
 * needs, and {@code identity} — which may already see {@code project} — supplies it. The
 * arrow still points one way. Nothing about the graph changed to make this endpoint possible.
 *
 * <p><strong>Do not "fix" a Modulith failure here by adding {@code identity} to
 * {@code project}'s allowed dependencies.</strong> That declaration is the rule, not the
 * obstacle, and the cycle it prevents is the thing that keeps a permission question answerable
 * in one place.
 *
 * <h2>What an implementation must not do</h2>
 *
 * <p>It must resolve permissions through {@code PermissionEvaluator} and nothing else. A second
 * query that computes visibility independently is the bug class plan §6b names: two answers to
 * "may they?", diverging quietly, with the quieter one deciding what a client can read.
 */
public interface ProjectAccess {

    /**
     * May this caller see this project, in the company currently open?
     *
     * <p>False for a project in another company and for one in this company the caller holds
     * no grant on — the two are deliberately indistinguishable from here, because the caller
     * turns both into the same 404 (§6t).
     */
    boolean canView(UUID userId, UUID projectId);

    /**
     * Does this caller hold {@code permission} on this project, in the company currently open?
     *
     * <p>{@link #canView} is this question asked with {@code project:read}, and is kept as its own
     * method because every read in the application calls it and "the permission that means visible"
     * is a decision that belongs on the implementation side rather than at two hundred call sites.
     *
     * <h2>Why a write needs this and cannot reuse {@code canView}</h2>
     *
     * <p>Access is two independent gates and a write adds a third question to them: not "may this
     * caller see the project" but "may they do <em>this</em> to it". Every PROJECT-scope built-in
     * role holds {@code project:read} — that is what makes a granted project visible at all — so a
     * write gated on {@code canView} is a write every Viewer may perform. (This sentence read
     * "every one of the six built-in roles" until V16, and was false: Automation Service held no
     * {@code project:read}, so the role 404'd on every project-scoped path. The claim was the
     * justification for this whole method existing, which is the kind of sentence worth checking
     * rather than believing — {@code SeedDataTest} now derives it from the catalogue.) The client's
     * own
     * stakeholder, invited as a Viewer precisely so they can read coverage without touching it
     * (roles spec §A7), would be able to rewrite the test suite they were shown.
     *
     * <p>The answer must come from {@code PermissionEvaluator} like every other, for the reason the
     * header gives: a second computation of "may they?" diverges quietly, and the quiet one decides
     * what a client can change.
     *
     * <p><strong>This answers permissions, never the ownership qualifier.</strong> Roles spec §A5
     * qualifies {@code case:delete} and {@code evidence:delete} with {@code OWN}, resolved against
     * the row's author — which needs the row, not just the project, and so cannot be answered from
     * this signature. A caller of this method for an {@code OWN}-qualified permission would get a
     * true that means "holds it for something" and read it as "holds it for this one".
     * {@link #canDoOnAuthored} is that question asked correctly, and
     * {@link ProjectService#writeGrantedByAuthorship} is how a write reaches it.
     */
    boolean canDo(UUID userId, UUID projectId, String permission);

    /**
     * May this caller do {@code permission} to a row <strong>authored by {@code authorId}</strong>?
     *
     * <h2>Why the author is a parameter rather than the answer being a qualifier</h2>
     *
     * <p>The alternative port hands back "your grant is {@code OWN}" and lets the caller compare it
     * to the row. That puts an authorization rule — what {@code OWN} means, and what an unknown
     * author means — in every module that owns a deletable row, which is the second
     * authorization-resolution path plan §6b names and {@link ProjectService}'s header refuses. It
     * would also land it in {@code repository}, whose own package documentation says a permission
     * check appearing there is a defect regardless of what its dependency list permits.
     *
     * <p>So the row's author crosses the boundary and the verdict comes back. The caller supplies a
     * fact about its row and is told yes or no.
     *
     * @param authorId the row's {@code created_by}; <strong>nullable</strong>, and a null is not the
     *                 caller — a row nobody can vouch for must not be covered by a grant that only
     *                 covers the caller's own work
     */
    boolean canDoOnAuthored(UUID userId, UUID projectId, String permission, UUID authorId);

    /**
     * May this caller do {@code permission} to a row <strong>at the given scope</strong>?
     *
     * <p>{@link #canDoOnAuthored}'s twin for the other restrictive qualifier, and everything that
     * method's header says about why the row's fact crosses the boundary — rather than the grant
     * crossing back out to be interpreted — applies here unchanged. The alternative would put "what
     * does {@code PLAN_SCOPE} mean" in every module that owns a scoped row, which is the second
     * authorization-resolution path plan §6b names.
     *
     * <p>The rule it answers is the roles spec's Artifacts row: {@code artifact:edit} is held
     * outright by a Test Lead and plan-scope-only by a Test Engineer. Project-scope artifacts are
     * inherited read-only by every plan (plan §6n), so they are the project's shared documents and
     * editing one edits every cycle.
     *
     * <p>Note this is asked of <em>creates</em> as well as edits, where there is no row yet: the
     * scope in the request is the scope the row would have. That is not a widening — a create that
     * lands a project-scope artifact is exactly the act the qualifier withholds, and gating it on
     * the requested scope is the only point at which it can be refused.
     *
     * @param rowIsPlanScoped whether the row belongs to one plan rather than the whole project
     */
    boolean canDoOnScope(UUID userId, UUID projectId, String permission, boolean rowIsPlanScoped);

    /**
     * Does this caller hold {@code permission} <strong>company-wide</strong>, in the company
     * currently open — with no project in the question at all?
     *
     * <h2>Why a project-less variant has to exist, and why it is not {@link #canDo} with a null</h2>
     *
     * <p>{@code project:create} is the one write in the contract whose target does not exist when
     * the gate runs. Every other write asks "may they do this <em>to that project</em>", and the
     * answer folds in the PROJECT term. There is no project to fold in here, and passing a null
     * project id into {@link #canDo} would not merely be ugly — the evaluator refuses it, because a
     * null there silently answers a different question than the caller asked.
     *
     * <p>It is also not "the union of what they hold on some project". A Test Lead holds
     * {@code project:settings} on the project they run; company-wide they hold nothing of the kind,
     * and a gate that unioned their project grants would let them start new projects because they
     * lead an old one. The implementation must therefore resolve this through the evaluator's
     * <em>company-wide</em> question (SYSTEM + COMPANY terms only) rather than by iterating projects.
     *
     * @param permission a COMPANY- or SYSTEM-scope permission key, a constant at the call site for
     *                   the reason {@link ProjectService#writeGranted} gives
     */
    boolean canDoInCompany(UUID userId, String permission);

    /**
     * The caller's <strong>PROJECT-scope</strong> role name per project across the whole
     * company, strongest grant winning — one lookup for the entire list rather than one per
     * project.
     *
     * <p>Projects the caller reaches only through a COMPANY-scope grant are <em>absent</em>,
     * not present with a placeholder: the contract defines {@code myRole} as null in exactly
     * that case, so a Company Admin is correctly unlabelled rather than labelled with a claim
     * about the company.
     *
     * <p>Display only. Nothing may branch on these strings — code checks permissions, never
     * role names (plan §6b).
     */
    Map<UUID, String> projectRoleNames(UUID userId);

    /**
     * The role name the caller <strong>inherits</strong> on every project they hold no grant on, or
     * empty when they inherit none.
     *
     * <p>The display half of the inheritance rule. A PROJECT-scope role granted with no project
     * applies to every project that has not said otherwise, so on those projects it <em>is</em> the
     * caller's role and {@code Project.myRole} has to say so — a {@code null} there would report "no
     * role on this project" to somebody who can demonstrably act on it, which is the same shape as
     * §47's tile and §49's chip: a field that is accurate about the query behind it and false about
     * the thing it is labelling.
     *
     * <p>Deliberately <strong>not</strong> merged into {@link #projectRoleNames}'s map. That map is
     * keyed by project and only the caller knows which projects are being rendered; folding a
     * per-project default into it would mean this method inventing entries for projects nobody
     * asked about. The caller reads the map first and falls back to this — which is also exactly
     * the precedence the evaluator applies, so the label and the permissions cannot disagree.
     *
     * <p>Company Admin is still absent from both, for {@link #projectRoleNames}'s stated reason:
     * theirs is a claim about the company, not about any project.
     */
    Optional<String> inheritedProjectRoleName(UUID userId);
}
