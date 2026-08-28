package com.tcms.identity;

import com.tcms.api.UserRef;
import com.tcms.project.ProjectMemberDirectory;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one implementation of {@link ProjectMemberDirectory}, and it is deliberately thin.
 *
 * <p>{@link IdentityPeopleDirectory} and {@link IdentityProjectAccess} are the siblings:
 * {@code project} declares what it needs and this module, which may already see {@code project},
 * supplies it. The arrow still points one way.
 *
 * <h2>The predicate is {@link ProjectMembershipService}'s and is not repeated here</h2>
 *
 * <p>That class's header spends four paragraphs on why the membership half and the grant half must
 * be <em>the same code</em> as the assignability check, not merely the same intention — a picker
 * offering somebody the server refuses is a form a person fills in correctly and cannot submit. A
 * second query here that "should" agree would be the third copy of that predicate, so this method
 * reuses the filter rather than restating it.
 *
 * <p>The difference from {@code GET /projects/{id}/members} is the caller gate, and only that: that
 * endpoint resolves the project through the caller's visibility, because the caller is a person
 * picking an assignee. This port's caller has already run its own gate
 * ({@code integration:manage}, for the connected-members list), so passing a caller id here would
 * ask the same question twice and answer it with the wrong subject.
 */
@Component
public class IdentityProjectMemberDirectory implements ProjectMemberDirectory {

    private final ProjectMembershipService memberships;
    private final AdminDirectoryService directory;

    public IdentityProjectMemberDirectory(
            ProjectMembershipService memberships, AdminDirectoryService directory) {

        this.memberships = memberships;
        this.directory = directory;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRef> membersOf(UUID projectId) {
        Map<UUID, UserRef> people = directory.peopleById();
        return memberships.memberIdsOf(projectId).stream()
                .map(people::get)
                .filter(person -> person != null)
                .sorted(Comparator.comparing(UserRef::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
