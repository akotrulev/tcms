package com.tcms.identity;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A validated, normalized write to one team — everything {@link Team} needs and nothing it has to
 * check.
 *
 * <p>The shape is {@code project.ClassificationValueEdit}'s and {@code repository.SuiteEdit}'s: the
 * request is normalized once, before anything acts on it, so no service, entity or query re-derives
 * whether the input was acceptable. Two entry points rather than one method with a nullable
 * "current team", for the reason those classes give — on a create every existing team is a possible
 * parent, and on an edit the team itself and everything beneath it are not.
 *
 * <h2>The sibling list is the argument, and the parent rules are what it buys</h2>
 *
 * <p>Both methods take every team in the company. Four refusals fall out of having it, and every
 * one of them would otherwise reach the database and be answered by a trigger in SQL states:
 *
 * <ol>
 *   <li>a duplicate name, compared <strong>case-insensitively</strong> — "QA" and "qa" are the same
 *       team to everyone who reads an org chart, and {@code V21} says the same thing in the schema
 *       so the check and its backstop cannot disagree;</li>
 *   <li>a {@code parentTeamId} naming no team in this company — which includes a <em>sibling
 *       company's</em> team, the shape {@code sharedStepId} (§64) and {@code parentId} (§69) each
 *       closed;</li>
 *   <li>a team parented to itself;</li>
 *   <li>a {@code parentTeamId} naming one of the team's own descendants, which is a cycle: the
 *       subtree would be severed from the root and unreachable by any walk of the chart.</li>
 * </ol>
 *
 * <p>{@code tcms_enforce_team_hierarchy} (V2) enforces the last three as a backstop and raises
 * {@code 23514} / {@code 23503}, which surface as a 500. A trigger is the right place for the
 * invariant and the wrong place for the <em>answer</em> — this is where the answer is produced.
 */
record TeamEdit(String name, String description, UUID parentTeamId) {

    /**
     * The longest name the org chart will accept.
     *
     * <p>The column is unbounded {@code TEXT}, so this is a product rule: a team name is rendered
     * as a chip beside a grant and as a node in a tree, and a name that wraps in either is not a
     * label. The contract states the same bound.
     */
    static final int MAX_NAME = 200;

    /** Long enough for a real charter sentence, short enough not to be a document. */
    static final int MAX_DESCRIPTION = 2000;

    /** Sealed, so the write service's mapping over it is exhaustive — {@code GateResult}'s reason. */
    sealed interface Outcome {

        /** Field name, as spelled on {@link TeamInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(TeamEdit edit) implements Outcome {
        }
    }

    /**
     * Validates a create.
     *
     * @param existing every team in this company — the company filter and RLS have already bounded
     *                 it, so "not in this list" and "not in this company" are the same statement
     */
    static Outcome validateCreate(TeamInput request, List<Team> existing) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = validName(request, null, existing, errors);
        String description = validDescription(request, errors);
        UUID parentTeamId = validParent(request.parentTeamId(), null, existing, errors);

        return errors.isEmpty()
                ? new Outcome.Valid(new TeamEdit(name, description, parentTeamId))
                : new Outcome.Invalid(errors);
    }

    /**
     * Validates an edit — a rename, a re-description, a move, or all three.
     *
     * <h2>A move is allowed here, and refused one tree over, and the difference is the point</h2>
     *
     * <p>{@code ClassificationValueEdit.validateEdit} refuses to reparent, because the values below
     * a classification value are what authored cases are <em>classified by</em> — moving a subtree
     * silently re-states what every case beneath it asserts. Nothing hangs off a team's parent edge
     * in that way: it is org-chart structure and never a permission edge (plan §6k, and
     * {@link Team}'s own header), so grants do not cascade through it and moving a team changes a
     * picture. The field that had to be refused there is the field this method exists for.
     *
     * @param team     the stored team, so it can be excluded from its own duplicate check and its
     *                 descendants from its possible parents
     * @param existing every team in this company, including {@code team} itself
     */
    static Outcome validateEdit(TeamInput request, Team team, List<Team> existing) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = validName(request, team.getId(), existing, errors);
        String description = validDescription(request, errors);
        UUID parentTeamId = validParent(request.parentTeamId(), team, existing, errors);

        return errors.isEmpty()
                ? new Outcome.Valid(new TeamEdit(name, description, parentTeamId))
                : new Outcome.Invalid(errors);
    }

    // =====================================================================
    // fields
    // =====================================================================

    /**
     * @param selfId the team being edited, excluded from its own duplicate check — without it every
     *               edit that does not rename would fail on the team's own name
     */
    private static String validName(
            TeamInput request,
            UUID selfId,
            List<Team> existing,
            Map<String, List<String>> errors) {

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            errors.put("name", List.of("Enter a name for the team."));
            return null;
        }
        if (name.length() > MAX_NAME) {
            errors.put("name", List.of(
                    "Keep the name to " + MAX_NAME + " characters or fewer."));
            return null;
        }

        String folded = name.toLowerCase(Locale.ROOT);
        for (Team candidate : existing) {
            if (candidate.getId().equals(selfId)) {
                continue;
            }
            if (candidate.getName().toLowerCase(Locale.ROOT).equals(folded)) {
                // The stored spelling, not the submitted one: the useful sentence names the team
                // they would have to look for, and "QA already exists" about a submitted "qa" reads
                // like the server ignored them.
                errors.put("name", List.of(
                        "This company already has a team called \"" + candidate.getName() + "\"."));
                return null;
            }
        }
        return name;
    }

    /** Absent and blank both mean "no description"; the column is nullable and the view renders it. */
    private static String validDescription(TeamInput request, Map<String, List<String>> errors) {
        String description = request.description() == null ? "" : request.description().trim();
        if (description.isEmpty()) {
            return null;
        }
        if (description.length() > MAX_DESCRIPTION) {
            errors.put("description", List.of(
                    "Keep the description to " + MAX_DESCRIPTION + " characters or fewer."));
            return null;
        }
        return description;
    }

    /**
     * The parent edge — the only field here with more than one way to be wrong.
     *
     * @param team the team being edited, or {@code null} on a create. A create cannot make a cycle
     *             because nothing points at a team that does not exist yet, so the descendant walk
     *             is genuinely edit-only rather than skipped for convenience.
     */
    private static UUID validParent(
            UUID parentTeamId,
            Team team,
            List<Team> existing,
            Map<String, List<String>> errors) {

        if (parentTeamId == null) {
            return null;
        }

        if (team != null && parentTeamId.equals(team.getId())) {
            errors.put("parentTeamId", List.of("A team cannot be its own parent."));
            return null;
        }

        Team parent = null;
        for (Team candidate : existing) {
            if (candidate.getId().equals(parentTeamId)) {
                parent = candidate;
                break;
            }
        }
        if (parent == null) {
            // Not "no such team" — the caller may not learn from this endpoint that a uuid names a
            // team in somebody else's company. Both readings produce the same sentence.
            errors.put("parentTeamId", List.of("That parent team is not in this company."));
            return null;
        }

        if (team != null && descends(parent, team.getId(), existing)) {
            errors.put("parentTeamId", List.of(
                    "\"" + parent.getName() + "\" is below this team in the org chart, so moving "
                            + "it there would leave the branch pointing at itself."));
            return null;
        }

        return parentTeamId;
    }

    /**
     * Whether {@code candidate} is {@code ancestorId} or sits anywhere beneath it.
     *
     * <p>Walks upward from the candidate rather than downward from the team, so the loop is bounded
     * by the chart's depth rather than its size — and bounded at all even if the stored data already
     * contains a cycle, which it cannot through this API but could through psql. Without the visited
     * set a pre-existing cycle would hang the request rather than refuse it.
     */
    private static boolean descends(Team candidate, UUID ancestorId, List<Team> existing) {
        Set<UUID> visited = new HashSet<>();
        Team current = candidate;
        while (current != null && visited.add(current.getId())) {
            if (Objects.equals(current.getId(), ancestorId)) {
                return true;
            }
            UUID parentId = current.getParentTeamId();
            current = null;
            if (parentId != null) {
                for (Team team : existing) {
                    if (team.getId().equals(parentId)) {
                        current = team;
                        break;
                    }
                }
            }
        }
        return false;
    }
}
