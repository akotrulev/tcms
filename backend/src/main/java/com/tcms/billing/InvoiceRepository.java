package com.tcms.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's invoices, drafts and issued together.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} on the INTERFACE is load-bearing.</strong>
 * Spring Data gives a transaction only to the inherited CRUD methods, so a derived or {@code @Query}
 * method without it runs with no company on the connection and every RLS-protected read returns
 * <strong>zero rows</strong> — fail-closed, silent, and here indistinguishable from a company that
 * has issued nothing, which is the state every new company is in.
 */
@Transactional(readOnly = true)
interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** The list, newest period first — {@code billing.html}'s Invoices panel. */
    List<Invoice> findAllByOrderByPeriodStartDescCreatedAtDesc();

    List<Invoice> findByClientIdOrderByPeriodStartDesc(UUID clientId);

    /**
     * Issued in a half-open window — {@code [from, to)}.
     *
     * <p>Spelled out rather than {@code Between}, which Spring Data renders as SQL {@code BETWEEN}
     * and is inclusive at BOTH ends: an invoice issued at exactly midnight on 1 January would land
     * in two years' totals, which is the shape of arithmetic error a year-to-date tile is least
     * likely to be checked for.
     */
    List<Invoice> findByStatusAndIssuedAtGreaterThanEqualAndIssuedAtLessThan(
            InvoiceStatus status, Instant from, Instant to);

    /**
     * The next sequence number for this company (019 D-k).
     *
     * <p>{@code MAX(seq) + 1} under RLS, so the maximum is this company's and no other's, with
     * {@code uq_invoice_seq} as the arbiter when two issues race. Gap-free numbering is not
     * guaranteed and is not required — {@code key_sequence}'s own table comment says the same about
     * display keys for the same reason: a rolled-back transaction burns its number, and the contract
     * is uniqueness and immutability.
     */
    @Query("SELECT COALESCE(MAX(i.seq), 0) + 1 FROM Invoice i")
    long nextSeq();
}
