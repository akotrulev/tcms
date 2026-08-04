package com.tcms.reporting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saved-report reads: this person's definitions in this project, and one of them by id.
 *
 * <p><strong>Every method names the owner</strong>, and that is the whole access rule of the
 * surface rather than a convenience. A definition is one person's (see {@link ReportDefinition}),
 * so a {@code findByIdAndProjectId} here would be a lookup that resolves somebody else's row and
 * leaves the ownership check to whichever caller remembers it — the shape that passes an
 * access-control test because the check ran, one call site over. There is deliberately no method on
 * this interface that can find a definition the caller does not own.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} is not optional.</strong>
 * {@link ReportJobRepository}'s header carries the argument in full: isolation is established in
 * {@code CompanyAwareTransactionManager#doBegin}, and Spring Data gives a transaction only to the
 * CRUD methods <em>inherited</em> from {@code SimpleJpaRepository}, never to the derived methods
 * declared here — so without it these read zero rows, silently, while the company context holds the
 * right company. {@code RepositoryTransactionTest} fails the build on a repository that forgets.
 *
 * <p>The class-level {@code readOnly} does not stop {@code save} and {@code delete}: the service
 * opens its own read-write transaction and the inherited methods join it.
 */
@Transactional(readOnly = true)
public interface ReportDefinitionRepository extends JpaRepository<ReportDefinition, UUID> {

    /**
     * One person's saved reports for one project, newest first.
     *
     * <p>Newest first rather than by name because names are not unique and nothing sorts them for
     * the reader: the useful order on a list nobody has curated is the one that puts what you just
     * saved at the top.
     */
    List<ReportDefinition> findByProjectIdAndOwnerIdOrderByCreatedAtDesc(UUID projectId, UUID ownerId);

    /**
     * One saved report, scoped by project and owner.
     *
     * <p>Three predicates for a primary key lookup, and each removes a different lie: the id alone
     * would resolve a definition through a path naming another project (the gate would have run,
     * against the wrong project), and without the owner it would resolve one belonging to somebody
     * else — which is how a delete route becomes an oracle over other people's saved reports.
     */
    Optional<ReportDefinition> findByIdAndProjectIdAndOwnerId(UUID id, UUID projectId, UUID ownerId);
}
