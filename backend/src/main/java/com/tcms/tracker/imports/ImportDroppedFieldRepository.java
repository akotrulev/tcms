package com.tcms.tracker.imports;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dropped-field rows of one run.
 *
 * <p>{@code @Transactional(readOnly = true)} for {@link ImportRunRepository}'s reason.
 */
@Transactional(readOnly = true)
public interface ImportDroppedFieldRepository extends JpaRepository<ImportDroppedField, UUID> {

    /**
     * In the source's own field order, which is what makes D-f's equality an equality.
     *
     * <p>Ordered by {@code position} and then by {@code fieldName}: {@code position} is unique per
     * run by construction, and the second term is there so a hypothetical tie is still
     * deterministic rather than left to the planner — an unstable order would fail
     * {@code ImportReportMatchesPreviewTest} intermittently, which is the worst way for it to fail.
     */
    List<ImportDroppedField> findByImportRunIdOrderByPositionAscFieldNameAsc(UUID importRunId);
}
