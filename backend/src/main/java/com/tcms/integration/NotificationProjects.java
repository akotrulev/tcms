package com.tcms.integration;

import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The project a notice is about, by name.
 *
 * <p>A channel is per project, so every message this module sends has a project as its context and
 * a reader who would otherwise have to look the id up. The name comes from {@code project}, which
 * is a foundation module and therefore a direct read rather than something the payload has to
 * carry — and it should not carry it: a display name in an event payload is a value frozen at the
 * moment of the occurrence, so a project renamed between the run finishing and the retry
 * succeeding would be announced under two names.
 *
 * <p>{@code @Transactional} because the read is RLS-protected and this runs on the outbox
 * dispatcher's thread, which holds no transaction of its own.
 */
@Component
class NotificationProjects {

    /**
     * What a message says when the project has been deleted between the occurrence and the
     * delivery. Naming nothing is better than naming an id: the reader is a person in a chat room,
     * and a UUID in a sentence reads as a fault in the product.
     */
    private static final String UNKNOWN = "an unknown project";

    private final ProjectRepository projects;

    NotificationProjects(ProjectRepository projects) {
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public String nameOf(UUID projectId) {
        return projects.findScopedById(projectId).map(Project::getName).orElse(UNKNOWN);
    }
}
