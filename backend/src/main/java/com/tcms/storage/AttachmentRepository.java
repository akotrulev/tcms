package com.tcms.storage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The blob metadata behind a batch of evidence rows.
 *
 * <p>@see EvidenceRepository for why {@code @Transactional(readOnly = true)} is not optional — the
 * derived method below is exactly the kind Spring Data does not give a transaction to, and the
 * failure is zero rows rather than an error.
 */
@Transactional(readOnly = true)
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** One query for any number of evidence rows — see {@code ExecutionViews}' no-N+1 rule. */
    List<Attachment> findByIdIn(Collection<UUID> ids);
}
