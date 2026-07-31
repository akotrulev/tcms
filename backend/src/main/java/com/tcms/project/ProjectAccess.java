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
