package com.tcms.integration.tracker;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's writer and its one lookup.
 *
 * <p>{@code @Transactional(readOnly = true)} for {@link TrackerBindingRepository}'s reason.
 */
@Transactional(readOnly = true)
public interface TrackerIssueCreationRepository extends JpaRepository<TrackerIssueCreation, UUID> {

    /**
     * The replay lookup, keyed on exactly the four columns of
     * {@code tracker_issue_creation_idempotency_idx}.
     *
     * <p>{@code userId} is in the key because the issue is filed <em>as the caller</em>: two people
     * replaying the same key from the same client are two issues with two reporters, not one. A
     * lookup narrower than the constraint would find nothing and then violate the index on insert.
     */
    Optional<TrackerIssueCreation> findByProjectIdAndUserIdAndIdempotencyKey(
            UUID projectId, UUID userId, String idempotencyKey);
}
