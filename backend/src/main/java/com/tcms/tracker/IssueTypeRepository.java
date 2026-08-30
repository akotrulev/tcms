package com.tcms.tracker;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's issue types.
 *
 * <p>{@code @Transactional(readOnly = true)} for {@link IssueRepository}'s reason, which is the same
 * on every interface in this module: a derived method without it runs with no company set and RLS
 * answers zero rows, silently.
 */
@Transactional(readOnly = true)
public interface IssueTypeRepository extends JpaRepository<IssueType, UUID> {

    /** Every type this project offers, in the order the settings screen lists them. */
    List<IssueType> findByProjectIdOrderByPositionAsc(UUID projectId);
}
