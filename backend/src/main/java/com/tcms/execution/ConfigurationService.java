package com.tcms.execution;

import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's configurations — <strong>the vocabulary a run form has always needed and could never
 * read</strong>.
 *
 * <p>{@link Configuration} has existed since {@code V1} and {@code test_run.configuration_id} is
 * {@code NOT NULL}, yet the only way to see the list was to open a <em>plan</em>: {@code PlanService}
 * assembles it into every {@code TestPlanView}, because a plan names the environments its cycle
 * covers. So a run created against no plan — which §99 made an ordinary thing to want — was created
 * against a configuration the caller could not name and did not choose, and
 * {@code RunCloneDialog}'s header recorded the consequence as "there is no configuration picker
 * anywhere in this SPA".
 *
 * <h2>The gate is the project read, and the list is not secret</h2>
 *
 * <p>{@code project:read}, exactly like {@code GET /statuses} — the other project vocabulary an
 * execution form needs. Both are answers to "what may this row say", every client with a form needs
 * them, and none of those readers is an administrator. That is the rule §218 phase 2 arrived at from
 * the other side: a read is gated on what the response <em>carries</em>, and this one carries a name
 * and a matrix cell.
 *
 * <p>There is no write. The set is seeded by {@code tcms_seed_project_defaults} and edited by an
 * operator, so this is a read with no writer rather than half of an unfinished CRUD pair — worth
 * saying, because the absence otherwise reads as the thing this slice forgot.
 */
@Service
public class ConfigurationService {

    private final ProjectService projects;
    private final ConfigurationRepository configurations;
    private final PlanCriteria criteria;

    ConfigurationService(
            ProjectService projects,
            ConfigurationRepository configurations,
            PlanCriteria criteria) {

        this.projects = projects;
        this.configurations = configurations;
        this.criteria = criteria;
    }

    /** The project's configurations, or empty when the caller may not see the project (404). */
    @Transactional(readOnly = true)
    public Optional<List<ConfigurationView>> list(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project -> of(project.getId()));
    }

    /**
     * The same list, ungated — for {@link PlanService}, which has already passed the project gate
     * and is assembling a plan around it.
     *
     * <p>This method <em>is</em> {@code PlanService.configurationsOf}, moved rather than copied. Two
     * assemblers over one table would agree today and differ the first time the attribute parse or
     * the ordering changed, and only one of the two would be the one a reviewer read — §58's E1, in
     * the module that has already paid for it twice ({@code ExecutionTargets}, {@code ExecutionPeople}).
     */
    List<ConfigurationView> of(UUID projectId) {
        return configurations.findByProjectIdOrderByIsDefaultDescNameAsc(projectId).stream()
                .map(configuration -> new ConfigurationView(
                        configuration.getId(),
                        configuration.getName(),
                        configuration.isDefault(),
                        criteria.attributes(configuration.getAttributes())))
                .toList();
    }
}
