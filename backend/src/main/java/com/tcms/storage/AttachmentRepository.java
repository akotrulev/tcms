package com.tcms.storage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The blob metadata behind a batch of evidence rows, and — since #49 — behind anything else the
 * product lets somebody attach a file to.
 *
 * <p>@see EvidenceRepository for why {@code @Transactional(readOnly = true)} is not optional — the
 * derived methods below are exactly the kind Spring Data does not give a transaction to, and the
 * failure is zero rows rather than an error.
 *
 * <h2>The typed methods are the API; the derived ones are plumbing</h2>
 *
 * <p>{@code owner_type} is a {@code String} column, so the derived queries have to take a
 * {@code String} — Spring Data derives from the field's type and {@link Attachment#ownerType} is not
 * an enum for the reason {@link AttachmentOwner}'s header gives. The {@code default} wrappers exist
 * so that no <em>caller</em> ever writes one: a mistyped owner type is zero rows, silently, which is
 * the same fail-closed failure the {@code @Transactional} note above is about, and the compiler is
 * the only observer that catches it before a screen renders empty.
 */
@Transactional(readOnly = true)
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** One query for any number of evidence rows — see {@code ExecutionViews}' no-N+1 rule. */
    List<Attachment> findByIdIn(Collection<UUID> ids);

    /**
     * Everything attached to any number of owners, oldest first — the read behind #49's three owners.
     *
     * <p><strong>Plural on purpose, and there is deliberately no singular sibling.</strong>
     * {@code CaseRenderer} renders up to 500 cases in one bulk operation, so a per-owner read here
     * is the fourth N+1 that class exists to prevent — and a singular convenience method beside it
     * is what a caller reaches for inside a loop. §121 phase 3 deleted a seam written for a caller
     * that could not exist; this is the same rule pointed at a method that only the wrong caller
     * would want.
     *
     * <p>Served by {@code attachment_owner_idx (owner_type, owner_id)}, which does <strong>not</strong>
     * lead with {@code company_id} and is right not to: these are lookups on UUIDs already unique
     * across every company, and {@code SchemaConventionTest}'s rule is that {@code company_id}
     * <em>leads every index that includes it</em> — presence is not asserted. That hole is real and
     * is recorded in the slice plan rather than closed here, because closing it would make this index
     * worse for the only queries that use it. RLS applies underneath regardless, which is what makes
     * the omission a performance question and not an isolation one.
     */
    default List<Attachment> findOwnedBy(AttachmentOwner owner, Collection<UUID> ownerIds) {
        return ownerIds.isEmpty()
                ? List.of()
                : findByOwnerTypeAndOwnerIdInOrderByCreatedAtAsc(owner.name(), ownerIds);
    }

    /**
     * One attachment of one owner. Both halves of the predicate are load-bearing: an id alone would
     * let {@code /cases/{a}/attachments/{belongs-to-b}} serve or delete another case's file — the
     * two-segment-path gate {@code CaseQuarantineService} states in the same words.
     */
    default Optional<Attachment> findOwnedById(AttachmentOwner owner, UUID ownerId, UUID id) {
        return findByIdAndOwnerTypeAndOwnerId(id, owner.name(), ownerId);
    }

    List<Attachment> findByOwnerTypeAndOwnerIdInOrderByCreatedAtAsc(
            String ownerType, Collection<UUID> ownerIds);

    Optional<Attachment> findByIdAndOwnerTypeAndOwnerId(UUID id, String ownerType, UUID ownerId);
}
