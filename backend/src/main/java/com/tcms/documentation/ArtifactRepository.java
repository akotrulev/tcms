package com.tcms.documentation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
@Transactional(readOnly = true)
public interface ArtifactRepository extends JpaRepository<Artifact, UUID> {

    /**
     * The project's own register — {@code PROJECT}-scope rows only.
     *
     * <p>Deliberately <em>not</em> "every artifact of this project", which would sweep in every
     * plan's rows and present one cycle's sign-off as though it governed the project. A plan's own
     * rows are reached through {@link #findForPlan}.
     *
     * <p>Every status is returned, {@code SUPERSEDED} and {@code ARCHIVED} included, for
     * {@code DocPageRepository#findLiveForProject}'s reason one level over: a superseded sign-off is
     * the record of what was signed off, and a register that hid it would answer the audit question
     * it exists to answer with a gap. The client marks them; it does not have to ask for them.
     */
    @Query("""
            SELECT a FROM Artifact a
             WHERE a.projectId = :projectId
               AND a.scope = com.tcms.documentation.ArtifactScope.PROJECT
             ORDER BY a.category ASC, a.title ASC
            """)
    List<Artifact> findProjectScoped(@Param("projectId") UUID projectId);

    /**
     * One plan's own register — {@code PLAN}-scope rows for that plan, and nothing else.
     *
     * <p>The caller unions this with {@link #findProjectScoped} and flags the latter
     * {@code inherited}; the union is not done in SQL because the two halves must stay
     * distinguishable all the way to the wire. A single query returning both would have to carry the
     * discriminator anyway, and the one thing plan §6n insists on is that a reader can tell which
     * documents belong to their release — an unmarked union is precisely the state that makes people
     * re-upload the environment guide per cycle.
     *
     * <p>{@code projectId} is in the predicate although {@code planId} alone would identify the
     * rows. It is what makes {@code /projects/{a}/artifacts?planId=<a plan of b>} return the empty
     * register rather than project {@code b}'s cycle documents: a plan id the caller guessed must not
     * select rows through a project they were not gated on.
     */
    @Query("""
            SELECT a FROM Artifact a
             WHERE a.projectId = :projectId
               AND a.planId = :planId
               AND a.scope = com.tcms.documentation.ArtifactScope.PLAN
             ORDER BY a.category ASC, a.title ASC
            """)
    List<Artifact> findForPlan(
            @Param("projectId") UUID projectId, @Param("planId") UUID planId);
}
