package com.tcms.documentation;

import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The artifact register, read — plan §6n.
 *
 * <p>Goes through {@link ProjectService#readVisible}, which collapses "no such project", "a project
 * in another company" and "a project you hold no grant on" into one empty {@link Optional}. The
 * controller therefore has nothing to un-collapse and cannot accidentally answer 403 where its
 * sibling answers 404 — the pair that turns an endpoint into an oracle for another client's ids
 * (§6t).
 *
 * <h2>The gate is the project read gate and not {@code artifact:read}</h2>
 *
 * <p>The same call {@link DocPageService} makes, on the same evidence: {@code artifact:read} has the
 * identical role distribution to {@code doc:read} in the roles spec — granted to five of the six
 * roles, and the sixth is Automation Service, which holds no {@code project:read} either. So a
 * second check would add a gate that has never once answered differently from the first, and a
 * permission check no test can drive is one nobody can tell is inverted. When a company can define
 * its own roles that stops being true and this is where the check goes.
 *
 * <p>This is deliberately <em>not</em> the reasoning used for the write path, where
 * {@code artifact:edit} genuinely differs between roles — and differs <em>within</em> a role by the
 * row's scope. See {@link ArtifactWriteService}.
 */
@Service
public class ArtifactService {

    private final ProjectService projects;
    private final ArtifactRepository artifacts;
    private final ArtifactPeople people;

    public ArtifactService(
            ProjectService projects, ArtifactRepository artifacts, ArtifactPeople people) {

        this.projects = projects;
        this.artifacts = artifacts;
        this.people = people;
    }

    /**
     * One project's register, or one plan's with the project's inherited into it.
     *
     * <p>{@code []} is a valid answer and the state of every freshly provisioned project. Nothing
     * seeds a first artifact — plan §6n floats seeding "the right empty slots" from a template, and
     * that is a project-settings feature, not something a read endpoint should invent.
     *
     * <h2>Inheritance is computed here, and one row can appear only once</h2>
     *
     * <p>A plan's register is its own rows plus every project-scope row, the latter flagged. The two
     * queries cannot overlap — a row is at exactly one scope, enforced by {@code V1}'s
     * {@code ck_artifact_scope} — so the concatenation needs no de-duplication, and saying so is
     * worth more than a defensive {@code distinct} that would hide the day the constraint changed.
     *
     * <p>The project's rows come <em>after</em> the plan's rather than being merged by category.
     * Sorting them together reads better and is wrong: it interleaves documents the caller can edit
     * with documents they cannot, under one heading, and the register's whole job at plan scope is
     * to keep that difference visible.
     *
     * @param planId required when {@code scope} is {@link ArtifactScope#PLAN} and rejected
     *               otherwise; {@link Optional#empty()} is returned for an unreachable project, and
     *               the invalid-combination case is a separate answer the controller turns into 400
     */
    @Transactional(readOnly = true)
    public Optional<List<ArtifactView>> findRegister(
            UUID userId, UUID projectId, ArtifactScope scope, UUID planId) {

        return projects.readVisible(userId, projectId, project -> {
            List<Artifact> rows = new ArrayList<>();
            List<Boolean> inherited = new ArrayList<>();

            if (scope == ArtifactScope.PLAN) {
                for (Artifact artifact : artifacts.findForPlan(project.getId(), planId)) {
                    rows.add(artifact);
                    inherited.add(false);
                }
            }
            for (Artifact artifact : artifacts.findProjectScoped(project.getId())) {
                rows.add(artifact);
                // Inherited only when read from inside a plan. The same row is the project's own
                // register when the project is what was asked for, and `inherited` describes THIS
                // response rather than the row — see ArtifactView.
                inherited.add(scope == ArtifactScope.PLAN);
            }

            Map<UUID, UserRefView> authors = people.resolve(CompanyContext.require(), updaters(rows));

            List<ArtifactView> views = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                views.add(ArtifactView.of(rows.get(i), inherited.get(i), authors));
            }
            return views;
        });
    }

    /**
     * The distinct non-null {@code updated_by} ids on the page, for one batched resolution.
     *
     * <p>Nulls are filtered rather than passed through: {@code artifact.updated_by} is nullable, and
     * a null bound into {@code IN (...)} matches nothing while still costing the round trip. The
     * caller renders such a row with no author, which is the honest reading of a column with no
     * foreign key behind it.
     */
    private static Set<UUID> updaters(List<Artifact> rows) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Artifact artifact : rows) {
            if (artifact.getUpdatedBy() != null) {
                ids.add(artifact.getUpdatedBy());
            }
        }
        return ids;
    }

    /**
     * Whether the {@code scope}/{@code planId} pair in a request is answerable at all.
     *
     * <p>Both directions are refused rather than tolerated. {@code PLAN} with no plan would have to
     * fall back to the project's register — answering one question under a heading that promises
     * another, which is §47's dashboard tile exactly. {@code PROJECT} with a plan is a client that
     * believes the plan narrowed something; it did not, and silently ignoring the parameter is the
     * failure §50 built two checks to catch.
     */
    static boolean isAnswerable(ArtifactScope scope, UUID planId) {
        return Objects.requireNonNull(scope) == ArtifactScope.PLAN ? planId != null : planId == null;
    }
}
