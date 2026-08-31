package com.tcms.work;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * One person's annotations.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface, without which a derived method runs
 * with no company set and RLS returns zero rows — fail-closed and silent, and on this table the
 * symptom is somebody's notes quietly vanishing from their own board.
 */
@Transactional(readOnly = true)
public interface CardAnnotationRepository extends JpaRepository<CardAnnotation, CardAnnotationId> {

    /**
     * Every annotation this caller holds, for one board assembly.
     *
     * <p>Not narrowed to the cards on the board: the set is one person's own notes, which is bounded
     * by how many they have written rather than by the company's size, and narrowing it would mean
     * binding a list of ids of every kind into one {@code IN} — with the empty-collection hazard
     * {@code BoardFacts}' header describes, on the ordinary path where somebody has annotated
     * nothing.
     */
    List<CardAnnotation> findByUserId(UUID userId);
}
