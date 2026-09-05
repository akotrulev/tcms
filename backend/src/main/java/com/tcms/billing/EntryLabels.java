package com.tcms.billing;

import com.tcms.api.UserRef;
import com.tcms.project.PeopleDirectory;
import com.tcms.tenancy.CompanyContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a rate-card entry <strong>applies to</strong>, resolved server-side — {@code Mira Sund},
 * {@code Test Lead}, {@code All roles}.
 *
 * <p>One resolution for two readers, and that is the reason it is a class. The rate-card table
 * renders it per entry; an invoice line carries the same string frozen into
 * {@code invoice_line.applies_to} so the row still reads correctly after the person leaves and the
 * role is renamed. Two implementations of "what is this entry called" would be a card and an invoice
 * disagreeing about the same rung, which is the one place a client would notice.
 *
 * <p><strong>A person's name comes from {@code PeopleDirectory}</strong> and therefore from
 * {@code company_membership.display_name}, per V1 line 99 — never from {@code app_user}. That is why
 * neither this class nor {@link ApprovedTimeFacts} joins that table.
 */
@Component
@Transactional(readOnly = true)
class EntryLabels {

    /** What {@code ALL_ROLES} is called on the screen — {@code billing.html}'s own wording. */
    private static final String ALL_ROLES = "All roles";

    /**
     * The label for an entry whose target cannot be resolved.
     *
     * <p><strong>Unreachable for a {@code PERSON} entry</strong>: {@code rate_card_entry.person_id}
     * cascades from {@code app_user}, so the row cannot outlive the account. It is here because the
     * contract makes {@code appliesTo} required and a resolver has to answer something — and it says
     * the name could not be resolved rather than inventing one, which is {@code DashboardService}'s
     * rule about not attributing a row to an "Unknown user" who does not exist.
     */
    private static final String UNRESOLVED = "Unknown";

    private final PeopleDirectory people;
    private final RoleNames roles;

    EntryLabels(PeopleDirectory people, RoleNames roles) {
        this.people = people;
        this.roles = roles;
    }

    /** The labels for a batch of entries, with each lookup done once. */
    Labels forEntries(Collection<RateCardEntry> entries) {
        Set<UUID> personIds = new HashSet<>();
        for (RateCardEntry entry : entries) {
            if (entry.getPersonId() != null) {
                personIds.add(entry.getPersonId());
            }
        }
        Map<UUID, UserRef> resolved = personIds.isEmpty()
                ? Map.of()
                : people.resolve(CompanyContext.require(), personIds);
        return new Labels(resolved, roles.byId());
    }

    /** A resolved batch, and the one rule that turns a rung into a string. */
    record Labels(Map<UUID, UserRef> people, Map<UUID, String> roles) {

        String of(RateCardScope scope, UUID personId, UUID roleId) {
            return switch (scope) {
                case PERSON -> {
                    UserRef person = personId == null ? null : people.get(personId);
                    yield person == null ? UNRESOLVED : person.displayName();
                }
                case ROLE -> {
                    String role = roleId == null ? null : roles.get(roleId);
                    yield role == null ? UNRESOLVED : role;
                }
                case ALL_ROLES -> ALL_ROLES;
            };
        }
    }
}
