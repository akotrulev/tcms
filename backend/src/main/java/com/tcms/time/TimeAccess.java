package com.tcms.time;

import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one permission question this module asks that no single project answers.
 *
 * <p>{@code time:log}, {@code time:read} and {@code time:approve} are <strong>PROJECT-scope</strong>
 * grants (V2), and a period is <strong>per person and spans projects</strong> (016 D-i). So half the
 * surface here has no project to gate against: submitting a fortnight of meetings and leave is an act
 * about a person, not about a project. The rule this class implements is the only one that makes
 * sense of that — <em>a caller may act on their own timesheet if they hold the key on at least one
 * project they can see</em> — and Company Admin, holding it company-wide, holds it on all of them.
 *
 * <p>Every answer still comes out of {@link ProjectService}; nothing here evaluates a permission, and
 * {@code identity} is never asked <em>may they</em>. The cost is one evaluation per visible project,
 * which is the ceiling {@code ProjectService} already states for itself.
 */
@Component
class TimeAccess {

    private final ProjectService projects;

    TimeAccess(ProjectService projects) {
        this.projects = projects;
    }

    /** The projects this caller can see <em>and</em> holds {@code permission} on. */
    List<Project> granting(UUID userId, String permission) {
        return projects.readAllVisible(userId, visible -> visible.stream()
                .filter(project -> projects.holds(userId, project.getId(), permission))
                .toList());
    }

    boolean holdsAnywhere(UUID userId, String permission) {
        return !granting(userId, permission).isEmpty();
    }
}
