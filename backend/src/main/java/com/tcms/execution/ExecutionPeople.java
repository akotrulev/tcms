package com.tcms.execution;

import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.project.PeopleDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The people on an execution — an assignee and an executor — resolved to display names.
 *
 * <p>What is asked of {@code identity} here is <em>what is this person called</em>, never <em>may
 * they</em>: the gate stays {@code project.ProjectService}, and no class in this package asks what a
 * caller holds. The naming rule itself is {@link PeopleDirectory}'s since §76 — this class used to
 * carry a second copy of it, beside {@code repository.CaseOwners}' first.
 *
 * <p>Two queries for a whole run, never two per row. A run detail lists two hundred executions and
 * resolving a person per row is the N+1 that looks harmless because each query is fast.
 */
@Component
class ExecutionPeople {

    private final CompanyMembershipRepository memberships;
    private final PeopleDirectory people;

    ExecutionPeople(CompanyMembershipRepository memberships, PeopleDirectory people) {
        this.memberships = memberships;
        this.people = people;
    }

    /**
     * Resolves each id, skipping any that names no account.
     *
     * <p>An unresolvable id yields no entry, and the caller renders a null. That is deliberate for
     * an <em>executor</em> in particular: {@code test_execution.executed_by} is nullable and has no
     * cascade, so "the account that recorded this has since been removed" is a state real rows are
     * in. Inventing a person there would attribute a verdict to somebody, which is the one thing a
     * result must not do wrongly.
     *
     * @param companyId the active company, passed rather than read from the context so the caller's
     *                  scope and this lookup's scope are visibly the same one
     */
    /**
     * Whether {@code userId} is an <strong>active member of this company</strong> — the question
     * {@link #resolve} deliberately does not answer.
     *
     * <p>The two look interchangeable and are not, which cost this slice a red test. {@code app_user}
     * is an <em>instance-level</em> table with no {@code company_id}: it is on ERD §0.1's exception
     * list because one person may work with several clients. So {@code resolve} finds any account on
     * the instance — correctly, because its job is to name the people already on a row, including
     * somebody since offboarded from this company (§38). Used as a membership check it accepts a
     * user id belonging to <em>another client entirely</em>, and a run created that way assigns a
     * client's testing to a stranger and puts their name on this company's screens.
     *
     * <p>{@code ACTIVE}, not merely present: an {@code INVITED} membership conveys nothing until it
     * is accepted, which is the rule {@code existsByCompanyIdAndUserIdAndStatus}'s own header states
     * — an unaccepted invitation must not become a way to be handed a client's work.
     *
     * <p>This still asks {@code identity} only <em>who is this person</em> / <em>are they here</em>,
     * never <em>may they</em>. The gate stays {@code project.ProjectService}.
     */
    boolean isActiveMember(UUID companyId, UUID userId) {
        return memberships.existsByCompanyIdAndUserIdAndStatus(
                companyId, userId, com.tcms.identity.MembershipStatus.ACTIVE);
    }

    Map<UUID, UserRefView> resolve(UUID companyId, Collection<UUID> userIds) {
        // Delegated, not implemented. This method was a byte-for-byte copy of
        // repository.CaseOwners#resolve, under a comment saying the two classes must not disagree —
        // §58's E1 exactly, and the copy survived because neither module can import the other. The
        // rule they were both applying (plan §6u: a person is called what their COMPANY MEMBERSHIP
        // calls them, falling back to the account's canonical name for somebody since offboarded)
        // now lives in project.PeopleDirectory, which both may call. What is left here is the
        // mapping onto this module's own UserRefView, which is a shape rather than a rule.
        Map<UUID, UserRefView> resolved = new HashMap<>();
        for (Map.Entry<UUID, com.tcms.project.UserRefView> person
                : people.resolve(companyId, userIds).entrySet()) {
            resolved.put(person.getKey(), new UserRefView(
                    person.getValue().id(),
                    person.getValue().displayName(),
                    person.getValue().email(),
                    person.getValue().avatarUrl()));
        }
        // A HashMap rather than Map.of() all the way down: the latter throws NPE on get(null), which
        // made exactly this method a 500 on an unassigned, unexecuted execution (§38).
        return resolved;
    }
}