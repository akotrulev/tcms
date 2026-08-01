package com.tcms.documentation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// @Transactional for DocPageRepository's reason — without it, RLS returns zero rows to a query
// method that ran outside any transaction.
@Transactional(readOnly = true)
public interface DocPageVersionRepository extends JpaRepository<DocPageVersion, UUID> {

    /**
     * The page's newest version.
     *
     * <p>Read for its {@code contentHash} alone, which is what makes a no-op save write nothing
     * (plan §6j). Ordered by {@code versionNo} rather than by {@code createdAt}: two versions
     * written inside one second are indistinguishable by timestamp, and the numbers are the thing
     * the page's own {@code currentVersionNo} agrees with.
     */
    @Query("""
            SELECT v FROM DocPageVersion v
             WHERE v.pageId = :pageId
             ORDER BY v.versionNo DESC
             LIMIT 1
            """)
    Optional<DocPageVersion> findLatest(@Param("pageId") UUID pageId);
}
