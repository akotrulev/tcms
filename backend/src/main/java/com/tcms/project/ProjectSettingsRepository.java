package com.tcms.project;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for the reason spelled out on ProjectRepository: without it a query method DECLARED
// on the interface runs outside any transaction, app.current_company is never set, and every
// RLS-protected read returns zero rows — fail-closed and silent. RepositoryTransactionTest derives
// the repository list from the live context, so omitting it fails the build.
@Transactional(readOnly = true)
public interface ProjectSettingsRepository extends JpaRepository<ProjectSettings, UUID> {

    /**
     * A project's settings row, or empty.
     *
     * <p>Spelled as a query on {@code projectId} rather than relying on {@code findById}, even
     * though the project id <em>is</em> the primary key. The two are the same lookup today; they
     * stop being the same the moment anybody adds a surrogate key, and the call sites read as a
     * question about a project either way.
     */
    @Query("SELECT s FROM ProjectSettings s WHERE s.projectId = :projectId")
    Optional<ProjectSettings> findForProject(@Param("projectId") UUID projectId);
}
