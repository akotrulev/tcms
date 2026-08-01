package com.tcms.documentation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for DocPageRepository's reason — a query method without it runs outside any
// transaction, so app.current_company is unset and RLS returns zero rows.
@Transactional(readOnly = true)
public interface DocSpaceRepository extends JpaRepository<DocSpace, UUID> {

    /**
     * The project's spaces, oldest id first.
     *
     * <p>Returns a list although there is always exactly one, because {@code V18} deliberately did
     * not add a {@code UNIQUE (project_id)} index — the ERD models a project as eventually having
     * several spaces, and an index saying "one" would have to be dropped by the migration that adds
     * the second. A method typed {@code Optional} would encode the same claim in Java and break the
     * same way.
     */
    @Query("""
            SELECT s FROM DocSpace s
             WHERE s.projectId = :projectId
             ORDER BY s.id ASC
            """)
    List<DocSpace> findForProject(@Param("projectId") UUID projectId);

    /**
     * The space a new page goes in, or a failure naming the repair.
     *
     * <p>Zero rows is an exception here for {@code DisplayKeys#allocate}'s reason, and it is the
     * same class of missing seed row: {@code V18} creates one per project and backfills every
     * project that predates it, so an absent space means a project created by a path that did not
     * run the seed function. The tempting repairs are creating one inline — which races, since
     * nothing constrains the count — or defaulting to any space in the company, which would put one
     * client project's pages in another project's space.
     *
     * @throws IllegalStateException if the project has no space
     */
    default DocSpace requireForProject(UUID projectId) {
        List<DocSpace> spaces = findForProject(projectId);
        if (spaces.isEmpty()) {
            throw new IllegalStateException(
                    "Project " + projectId + " has no documentation space. Re-run "
                            + "provision-company for its company — the command is idempotent and "
                            + "tcms_seed_project_defaults seeds the space (migration V18).");
        }
        return spaces.getFirst();
    }
}
