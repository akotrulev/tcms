package com.tcms.billing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The snapshots behind an issued invoice's lines.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface for {@link InvoiceRepository}'s
 * reason.
 *
 * <p><strong>There is deliberately no {@code existsByTimeEntryId}.</strong> "Is this hour already
 * billed" is asked of a whole fortnight at once, inside {@link ApprovedTimeFacts}' own statement as
 * a {@code NOT EXISTS}, so the exclusion happens in the database rather than as one query per entry
 * — and so the crossing between the approved hours and the invoices that took them is legible in one
 * place. A convenience method here would be the second answer to that question, on the table where
 * two answers cost double-billing.
 */
@Transactional(readOnly = true)
interface InvoiceLineEntryRepository extends JpaRepository<InvoiceLineEntry, UUID> {

    List<InvoiceLineEntry> findByInvoiceLineIdInOrderBySpentOnAsc(Collection<UUID> lineIds);
}
