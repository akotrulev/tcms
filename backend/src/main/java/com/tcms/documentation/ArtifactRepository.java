package com.tcms.documentation;

import java.util.List;
import java.util.Optional;
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
    /**
     * The artifact a previous request with this key registered, if any (B4, {@code V59}).
     *
     * <p>Project-scoped, naming exactly the columns
     * {@code artifact_created_idempotency_key_idx} covers: a lookup narrower than the constraint
     * lets a key reused across two projects pass here and violate the index one statement later,
     * which is a validated request that 500s.
     *
     * <p><strong>No status filter, and no "not superseded".</strong> A retry whose original has
     * since been archived, superseded or deleted is still a retry — registering a second one would
     * resurrect something somebody deliberately dealt with, and the index would refuse it anyway.
     * {@code SuiteRepository.findByIdempotencyKey} makes the same call for the same reason.
     */
    @Query("""
            SELECT a FROM Artifact a
             WHERE a.projectId = :projectId
               AND a.createdIdempotencyKey = :idempotencyKey
            """)
    Optional<Artifact> findByIdempotencyKey(
            @Param("projectId") UUID projectId,
            @Param("idempotencyKey") String idempotencyKey);

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

    /**
     * One artifact of <em>this</em> project, whatever its scope.
     *
     * <p>{@code projectId} is in the predicate for {@link #findForPlan}'s reason and it is doing
     * more work here, because this feeds a delete: {@code findById} would resolve a sibling
     * project's artifact through a path naming a project the caller <em>can</em> write, and the
     * gate would already have passed. The path segment must select the row, not merely authorise
     * the call. RLS keeps another company out; this keeps another project out.
     */
    @Query("""
            SELECT a FROM Artifact a
             WHERE a.projectId = :projectId
               AND a.id = :artifactId
            """)
    Optional<Artifact> findInProject(
            @Param("projectId") UUID projectId, @Param("artifactId") UUID artifactId);

    /**
     * Whether any artifact names this one as its replacement — <strong>the inbound edge this
     * entity's own javadoc said did not exist</strong>.
     *
     * <p>{@code Artifact}'s header argues it is not soft-deleted because "nothing targets an
     * artifact: {@code superseded_by_id} points the other way … so removing a row breaks no inbound
     * edge." One end of that is true and the other is not: whichever direction the column is read,
     * it is a self-referencing foreign key, and the row it points <em>at</em> has an inbound edge by
     * definition. A delete without this check is a constraint violation the caller reads as a 500.
     * §100's rule for the fourth time — a comment stating a fact about another file is not a check,
     * and here the other file was the schema.
     *
     * <p><strong>Native, because the column is deliberately unmapped.</strong> The entity leaves
     * {@code superseded_by_id} off on the argument that a field this slice cannot set would put an
     * always-null accessor on the wire; that argument is still sound, and mapping the column to
     * satisfy a derived query name would undo it for a check nothing renders. Native SQL is
     * governed by RLS rather than by the Hibernate company filter — which is the stronger of the
     * two, and the precedent is {@code BoardFacts} since §53.
     *
     * <p>Nothing writes the column yet, so this answers false in every deployment today. That is
     * exactly why the refusal is written now rather than when superseding ships: a guard added
     * after the thing it guards was absent for the whole window that mattered.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM artifact WHERE superseded_by_id = :artifactId)",
            nativeQuery = true)
    boolean isSupersedingSomething(@Param("artifactId") UUID artifactId);
}
