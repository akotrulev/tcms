package com.tcms.billing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rows an issued invoice's lines are.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface for {@link InvoiceRepository}'s
 * reason, which is the same everywhere in this codebase and has bitten every module in it.
 *
 * <p><strong>There is no update method and there will not be one</strong> (019 D-c). Nothing on this
 * surface changes a line; the only writer is the issue transaction, through the inherited
 * {@code save}. A {@code deleteBy…} is absent for the same reason — an issued invoice cannot be
 * discarded, and the cascade on {@code invoice_id} is what takes these rows if the invoice itself
 * ever goes.
 */
@Transactional(readOnly = true)
interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {

    List<InvoiceLine> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);

    List<InvoiceLine> findByInvoiceIdIn(Collection<UUID> invoiceIds);
}
