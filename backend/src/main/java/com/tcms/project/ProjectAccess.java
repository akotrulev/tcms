package com.tcms.project;

import java.util.Map;
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
     * caller see the project" but "may they do <em>this</em> to it". Every one of the six built-in
     * roles holds {@code project:read} — that is what makes a granted project visible at all — so a
     * write gated on {@code canView} is a write every Viewer may perform. The client's own
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
}
