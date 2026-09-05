package com.tcms.billing;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * {@code INV-2026-041} — this module's own counter, and {@code key_sequence} is not touched
 * (019 D-k).
 *
 * <p>{@code MAX(seq) + 1} for the company, allocated <strong>inside the issue transaction</strong>
 * with {@code uq_invoice_seq} as the arbiter and <strong>one</strong> retry. The retry lives in
 * {@link InvoiceService#issue} rather than here, and it has to: a unique violation aborts the
 * PostgreSQL transaction, so a second attempt inside the same one would fail on the abort rather
 * than on the number. That is why {@code issue} is the one write on this surface with no
 * {@code @Transactional} of its own — its transaction begins at the gate, so a retry is a second
 * gate call and therefore a second transaction.
 *
 * <h2>Why not {@code key_sequence}</h2>
 *
 * <p>It was the obvious home and is refused on three counts, in ascending order of weight:
 *
 * <ol>
 *   <li>Its shape is {@code prefix + next_value} and it produces {@code TC-1}. A year-qualified,
 *       zero-padded invoice number is not that shape.</li>
 *   <li>Adding an {@code 'INVOICE'} kind costs a CHECK change <em>and</em> a backfill for every
 *       existing company.</li>
 *   <li>It also costs a {@code CREATE OR REPLACE FUNCTION} of
 *       {@code tcms_seed_company_defaults}, or a company provisioned tomorrow could not issue an
 *       invoice at all — and {@code CREATE OR REPLACE} replaces the <em>whole</em> body, which
 *       V11's header names as precisely how this gets broken. Four migrations in a row declined to
 *       touch that function; V12 declines too.</li>
 * </ol>
 *
 * <p><strong>Gap-free numbering is not guaranteed and is not required.</strong>
 * {@code key_sequence}'s own table comment says the same thing about display keys for the same
 * reason — a rolled-back transaction burns its number — and the contract here is uniqueness and
 * immutability, both of which the unique constraint holds whether or not the sequence has holes.
 */
@Component
class InvoiceNumbers {

    /** {@code INV-2026-041}: three digits, and more when a company outgrows them. */
    private static final String FORMAT = "INV-%d-%03d";

    private final InvoiceRepository invoices;

    InvoiceNumbers(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    /** The next sequence number for this company, under RLS. */
    long nextSeq() {
        return invoices.nextSeq();
    }

    /**
     * The rendered number, stored rather than derived on read.
     *
     * <p>The year is the year of <strong>issue</strong>, not of the period: an invoice for December
     * raised in January is a January invoice, which is what the number a client quotes back has to
     * agree with. Deriving it later would mean re-reading {@code issued_at} of a row somebody may be
     * holding a printed copy of, so the string is frozen beside the sequence it came from.
     *
     * <p>UTC, because {@code issued_at} is a {@code TIMESTAMPTZ} and this product has no company
     * time zone. A number allocated at 23:30 on 31 December in Stockholm reads as the new year, and
     * that is stated here rather than discovered: the alternative is a per-company zone this schema
     * does not have.
     */
    static String render(Instant issuedAt, long seq) {
        return FORMAT.formatted(issuedAt.atZone(ZoneOffset.UTC).getYear(), seq);
    }
}
