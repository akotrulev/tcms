package com.tcms.integration;

import com.tcms.api.TeamRef;
import com.tcms.api.UserRef;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.TeamDirectory;
import com.tcms.tenancy.CompanyContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The two name resolutions every review notifier needs, in one place instead of four.
 *
 * <p>Four handlers each deciding what a missing person means is four copies of one decision, and
 * the copies would only disagree in a deployment where somebody has configured a channel — a
 * divergence with no observer, which is {@link NotificationTexts}' own reason for existing.
 *
 * <p>What it does <strong>not</strong> hold is any of the copy. A resolution is a lookup; a
 * sentence is a display decision, and that lives one file over.
 */
@Component
class ReviewNotices {

    private final PeopleDirectory people;
    private final TeamDirectory teams;

    ReviewNotices(PeopleDirectory people, TeamDirectory teams) {
        this.people = people;
        this.teams = teams;
    }

    /**
     * @return the person's display name, or null when the id names no account or is itself null.
     *         Both are states real rows are in — {@code review.created_by} is nullable, and
     *         somebody can be off-boarded between the act and the delivery — and
     *         {@link PeopleDirectory} yields no entry rather than inventing one
     */
    String nameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        Map<UUID, UserRef> resolved = people.resolve(CompanyContext.require(), List.of(userId));
        UserRef person = resolved.get(userId);
        return person == null ? null : person.displayName();
    }

    /**
     * @return the team's name, or null when the id names no team or is itself null. A team can be
     *         deleted between the request and the delivery exactly as a person can leave
     */
    String teamNameOf(UUID teamId) {
        if (teamId == null) {
            return null;
        }
        return teams.find(CompanyContext.require(), teamId).map(TeamRef::name).orElse(null);
    }
}
