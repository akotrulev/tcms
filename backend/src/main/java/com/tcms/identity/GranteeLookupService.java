package com.tcms.identity;

import com.tcms.api.UserRef;
import com.tcms.tenancy.CompanyContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who, in this company, holds a given permission — the question a <strong>refusal</strong> asks.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Design language rule 7 and §7 state the same requirement twice: a refusal names <em>"the
 * permission's real id <strong>and the name of somebody in this company who holds it</strong>"</em>.
 * The build met four of the five clauses — neutral colour, no retry, the real key in mono, the
 * action disabled rather than hidden — and stopped one short, offering <em>"a company
 * administrator"</em>, which is a role and not a person.
 *
 * <p>It stopped there for a structural reason rather than an oversight. The only endpoint that
 * knows who holds what is the org chart, and the caller reading a refusal is by definition somebody
 * who has just been told they may not do something; asking them to read an administrative surface
 * to find out who to ask is the shape of the problem, not a solution to it.
 *
 * <h2>Gated on membership, and nothing else</h2>
 *
 * <p>This is the decision worth stating. A permission key on the gate would defeat the purpose —
 * the caller lacks a permission, that is why they are here — so the gate is company membership,
 * which {@link CompanyContext} already establishes before any of this runs and which a caller
 * cannot obtain by asking. That is the same line {@link OrgChartService} draws for the same reason:
 * org structure is not sensitive <em>within</em> a company (plan §6k), and hiding it defeats what
 * it is for.
 *
 * <p>What keeps that from being a directory read is the <em>shape of the answer</em> rather than
 * the gate: {@link GranteeView} carries a display name and nothing else, and the list is capped at
 * three. A caller cannot enumerate the company through it, cannot obtain an id or an address, and
 * cannot learn how many people hold anything.
 *
 * <h2>The names come from the one place that applies §6u</h2>
 *
 * <p>{@link AdminDirectoryService#peopleById()} is where the display-name rule lives and the only
 * place it is applied. Assembling a name here from {@code app_user} columns would be a second
 * implementation of it that no architecture test can see — the exact miss that class's own header
 * records.
 */
@Service
public class GranteeLookupService {

    private final RoleAssignmentRepository assignments;
    private final AdminDirectoryService directory;

    public GranteeLookupService(
            RoleAssignmentRepository assignments, AdminDirectoryService directory) {
        this.assignments = assignments;
        this.directory = directory;
    }

    /**
     * Up to three people in the caller's company who hold {@code permission}, most reachable first.
     *
     * <p>The key is not validated against the catalogue and that is deliberate: an unknown key
     * legitimately answers an empty list — a client on a newer deployment asking about a key this
     * one has never heard of should get "nobody here", not a 400 it cannot act on. There is no
     * disclosure either way, because the answer for an unknown key and for a key nobody holds is
     * the same empty list.
     */
    @Transactional(readOnly = true)
    public GranteeListView grantees(String permission) {
        UUID companyId = CompanyContext.require();

        List<UUID> holders = assignments.findGrantees(companyId, permission);
        if (holders.isEmpty()) {
            return new GranteeListView(permission, List.of());
        }

        Map<UUID, UserRef> people = directory.peopleById();
        return new GranteeListView(
                permission,
                holders.stream()
                        .map(people::get)
                        // A holder with no directory row is dropped rather than rendered as a
                        // placeholder: "Ask Unknown to grant it" is worse than naming nobody, and
                        // the caller still gets whichever of the three did resolve.
                        .filter(person -> person != null && person.displayName() != null)
                        .map(person -> new GranteeView(person.displayName()))
                        .toList());
    }
}
